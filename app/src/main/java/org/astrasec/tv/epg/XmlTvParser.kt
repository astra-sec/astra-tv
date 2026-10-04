package org.astrasec.tv.epg

import java.io.FilterInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import javax.xml.parsers.SAXParserFactory
import org.astrasec.tv.playlist.Channel
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.LexicalHandler
import org.xml.sax.helpers.DefaultHandler

/** Streaming XMLTV reader. Only matched channels and nearby dates are retained. */
object XmlTvParser {
    private const val MAX_BYTES = 64L * 1_024 * 1_024
    private const val MAX_ELEMENTS = 2_000_000
    private const val MAX_RETAINED = 100_000
    private const val MAX_INFERRED_DURATION = 6L * 60 * 60 * 1_000
    private const val MAX_EXPLICIT_DURATION = 36L * 60 * 60 * 1_000
    private val chinaZone = TimeZone.getTimeZone("Asia/Shanghai")
    private val timestamp = Regex("^(\\d{12}|\\d{14})(?:\\s*([+-]\\d{4}|Z|UTC|GMT))?$")

    fun parse(
        input: InputStream,
        channels: List<Channel>,
        source: String,
        nowMs: Long,
    ): Map<String, List<EpgProgramme>> {
        require(channels.size <= 5_000) { "Too many playlist channels" }
        val day = GregorianCalendar(chinaZone).apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val dayStart = day.timeInMillis
        day.add(Calendar.DAY_OF_MONTH, -1)
        val earliestStart = day.timeInMillis
        day.add(Calendar.DAY_OF_MONTH, 3)
        val latestStart = day.timeInMillis
        val handler = Handler(channels, earliestStart, latestStart)
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
        }
        val reader = factory.newSAXParser().xmlReader
        // Feature availability varies between Android's Expat and desktop JVM parsers.
        listOf(
            "http://xml.org/sax/features/external-general-entities",
            "http://xml.org/sax/features/external-parameter-entities",
            "http://apache.org/xml/features/nonvalidating/load-external-dtd",
        ).forEach { feature -> runCatching { reader.setFeature(feature, false) } }
        runCatching { reader.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        reader.contentHandler = handler
        reader.errorHandler = handler
        reader.entityResolver = handler
        // Mandatory lexical handler rejects internal entities as well as external DTDs.
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
        reader.parse(InputSource(LimitedInputStream(withoutStandardDoctype(input))))
        return handler.schedules(source, dayStart, latestStart)
    }

    /**
     * Many XMLTV producers include the literal standard external DTD declaration. Remove only
     * that declaration from the bounded prologue, so no DTD is fetched or parsed. Every other
     * DOCTYPE (including any internal subset) is still rejected by the SAX reader.
     */
    private fun withoutStandardDoctype(input: InputStream): InputStream {
        val prefix = ByteArray(16 * 1_024)
        var size = 0
        while (size < prefix.size) {
            val amount = input.read(prefix, size, prefix.size - size)
            if (amount < 0) break
            if (amount == 0) continue
            size += amount
        }
        val charset = when {
            size >= 2 && prefix[0] == 0xff.toByte() && prefix[1] == 0xfe.toByte() -> Charsets.UTF_16LE
            size >= 2 && prefix[0] == 0xfe.toByte() && prefix[1] == 0xff.toByte() -> Charsets.UTF_16BE
            size >= 4 && prefix[0] == 0.toByte() && prefix[1] == '<'.code.toByte() -> Charsets.UTF_16BE
            size >= 4 && prefix[0] == '<'.code.toByte() && prefix[1] == 0.toByte() -> Charsets.UTF_16LE
            else -> Charsets.UTF_8
        }
        val decoded = String(prefix, 0, size, charset)
        val root = Regex("<tv(?=[\\s>])").find(decoded)?.range?.first
            ?: throw SAXException("XMLTV prologue exceeds limit or tv root is missing")
        val prologue = decoded.substring(0, root)
        val standard = Regex("<!DOCTYPE\\s+tv\\s+SYSTEM\\s+([\"'])xmltv\\.dtd\\1\\s*>")
        val cleaned = prologue.replace(standard, "")
        if (cleaned == prologue) return SequenceInputStream(ByteArrayInputStream(prefix, 0, size), input)
        val originalLength = prologue.toByteArray(charset).size
        val restored = cleaned.toByteArray(charset) + prefix.copyOfRange(originalLength, size)
        return SequenceInputStream(ByteArrayInputStream(restored), input)
    }

    /** XMLTV timestamps without an explicit offset are UTC, independent of the device zone. */
    internal fun parseTimestamp(value: String): Long? {
        val match = timestamp.matchEntire(value.trim()) ?: return null
        val digits = match.groupValues[1]
        val offset = match.groupValues[2].ifEmpty { "UTC" }
        val zone = if (offset == "Z" || offset == "UTC" || offset == "GMT") {
            TimeZone.getTimeZone("UTC")
        } else {
            val hours = offset.substring(1, 3).toInt()
            val minutes = offset.substring(3, 5).toInt()
            if (hours > 23 || minutes > 59) return null
            TimeZone.getTimeZone("GMT${offset.substring(0, 3)}:${offset.substring(3)}")
        }
        return runCatching {
            GregorianCalendar(zone).apply {
                isLenient = false
                clear()
                set(
                    digits.substring(0, 4).toInt(), digits.substring(4, 6).toInt() - 1,
                    digits.substring(6, 8).toInt(), digits.substring(8, 10).toInt(),
                    digits.substring(10, 12).toInt(),
                    if (digits.length == 14) digits.substring(12, 14).toInt() else 0,
                )
            }.timeInMillis
        }.getOrNull()
    }

    private data class RawProgramme(val startMs: Long, val endMs: Long?, val title: String)

    private class Handler(
        channels: List<Channel>,
        private val earliestStart: Long,
        private val latestStart: Long,
    ) : DefaultHandler(), LexicalHandler {
        private val byAlias = buildMap<String, MutableSet<String>> {
            channels.forEach { channel ->
                EpgChannelMatcher.candidates(channel).forEach { candidate ->
                    getOrPut(candidate) { linkedSetOf() }.add(channel.id)
                }
            }
        }
        private val explicitIds = channels.filter { !it.epgId.isNullOrBlank() }
            .groupBy({ it.epgId!! }, { it.id })
        private val exactMatches = mutableMapOf<String, String>()
        private val feedMatches = mutableMapOf<String, Set<String>>()
        private val programmes = mutableMapOf<String, MutableList<RawProgramme>>()
        private var depth = 0
        private var elements = 0
        private var retained = 0
        private var rootSeen = false
        private var feedChannel: String? = null
        private val names = mutableListOf<String>()
        private var programmeChannel: String? = null
        private var programmeStart: Long? = null
        private var programmeEnd: Long? = null
        private var programmeValid = false
        private var programmeTitle = ""
        private var textElement: String? = null
        private val text = StringBuilder()

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            depth++
            elements++
            if (depth > 32 || elements > MAX_ELEMENTS) throw SAXException("XMLTV structure limit exceeded")
            if (!rootSeen) {
                if (qName != "tv") throw SAXException("Expected an XMLTV tv document")
                rootSeen = true
            }
            when {
                depth == 2 && qName == "channel" -> {
                    feedChannel = attribute(attributes, "id")
                    names.clear()
                }
                depth == 3 && qName == "display-name" && feedChannel != null -> beginText(qName)
                depth == 2 && qName == "programme" -> {
                    programmeChannel = attribute(attributes, "channel")
                    programmeStart = attribute(attributes, "start")?.let(::parseTimestamp)
                    val stop = attribute(attributes, "stop")
                    programmeEnd = stop?.let(::parseTimestamp)
                    programmeTitle = ""
                    val id = programmeChannel
                    if (id != null && id !in feedMatches) register(id, emptyList())
                    programmeValid = id != null && feedMatches[id].orEmpty().isNotEmpty() &&
                        programmeStart != null && programmeStart!! >= earliestStart && programmeStart!! < latestStart &&
                        (stop == null || programmeEnd != null)
                }
                depth == 3 && qName == "title" && programmeValid && programmeTitle.isEmpty() -> beginText(qName)
            }
        }

        private fun attribute(attributes: Attributes, name: String): String? = attributes.getValue(name)
            ?.also { if (it.length > 1_024) throw SAXException("XMLTV attribute limit exceeded") }
            ?.trim()?.takeIf(String::isNotEmpty)

        private fun beginText(element: String) {
            textElement = element
            text.setLength(0)
        }

        override fun characters(chars: CharArray, start: Int, length: Int) {
            if (textElement != null) {
                if (text.length + length > 4_096) throw SAXException("XMLTV text limit exceeded")
                text.append(chars, start, length)
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            if (depth == 3 && textElement == qName) {
                val value = text.toString().replace(Regex("\\s+"), " ").trim()
                if (qName == "display-name" && value.isNotEmpty() && names.size < 16) names.add(value)
                if (qName == "title") programmeTitle = value.take(512)
                textElement = null
                text.setLength(0)
            }
            if (depth == 2 && qName == "channel") {
                feedChannel?.let { register(it, names) }
                feedChannel = null
            }
            if (depth == 2 && qName == "programme") {
                if (programmeValid && programmeTitle.isNotEmpty()) {
                    retained++
                    if (retained > MAX_RETAINED) throw SAXException("XMLTV programme limit exceeded")
                    programmes.getOrPut(programmeChannel!!) { mutableListOf() }
                        .add(RawProgramme(programmeStart!!, programmeEnd, programmeTitle))
                }
                programmeValid = false
                programmeChannel = null
            }
            depth--
        }

        private fun register(id: String, names: List<String>) {
            if (feedMatches.size >= 20_000 && id !in feedMatches) throw SAXException("XMLTV channel limit exceeded")
            val targets = linkedSetOf<String>()
            explicitIds[id].orEmpty().forEach { target ->
                targets.add(target)
                exactMatches[target] = id
            }
            (names.asSequence() + sequenceOf(id)).forEach { name ->
                targets.addAll(byAlias[EpgChannelMatcher.normalize(name)].orEmpty())
            }
            feedMatches[id] = targets
        }

        fun schedules(source: String, dayStart: Long, latestStart: Long): Map<String, List<EpgProgramme>> {
            if (!rootSeen) throw SAXException("Empty XMLTV document")
            val result = mutableMapOf<String, MutableList<EpgProgramme>>()
            programmes.forEach { (feedId, entries) ->
                val sorted = entries.distinct().sortedBy { it.startMs }
                val targets = feedMatches[feedId].orEmpty().filter { target ->
                    exactMatches[target] == null || exactMatches[target] == feedId
                }
                sorted.forEachIndexed { index, entry ->
                    val end = entry.endMs ?: sorted.asSequence().drop(index + 1)
                        .firstOrNull { it.startMs > entry.startMs }?.startMs ?: return@forEachIndexed
                    val maximum = if (entry.endMs == null) MAX_INFERRED_DURATION else MAX_EXPLICIT_DURATION
                    if (end <= entry.startMs || end - entry.startMs > maximum || end <= dayStart || entry.startMs >= latestStart) {
                        return@forEachIndexed
                    }
                    val programme = EpgProgramme(entry.startMs, end, entry.title, source)
                    targets.forEach { target -> result.getOrPut(target) { mutableListOf() }.add(programme) }
                }
            }
            return result.mapValues { (_, entries) -> entries.distinct().sortedBy { it.startMs } }
        }

        override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
            throw SAXException("External entities are forbidden")

        override fun startDTD(name: String?, publicId: String?, systemId: String?) {
            throw SAXException("DOCTYPE is forbidden")
        }
        override fun endDTD() = Unit
        override fun startEntity(name: String?) = Unit
        override fun endEntity(name: String?) = Unit
        override fun startCDATA() = Unit
        override fun endCDATA() = Unit
        override fun comment(chars: CharArray?, start: Int, length: Int) = Unit
    }

    private class LimitedInputStream(input: InputStream) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int = super.read().also { value -> if (value >= 0) consumed(1) }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
            `in`.read(bytes, offset, length).also { amount -> if (amount > 0) consumed(amount) }

        private fun consumed(amount: Int) {
            count += amount
            if (count > MAX_BYTES) throw java.io.IOException("XMLTV input size limit exceeded")
        }
    }
}
