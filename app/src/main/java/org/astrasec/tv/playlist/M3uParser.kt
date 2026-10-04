package org.astrasec.tv.playlist

import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** Parses an extended M3U playlist without depending on Android APIs. */
object M3uParser {
    private val attributePattern = Regex(
        """([\w-]+)\s*=\s*(?:"((?:\\.|[^"])*)"|'((?:\\.|[^'])*)'|([^\s,]+))""",
    )

    fun parse(text: String, baseUrl: String): List<Channel> {
        val channels = mutableListOf<Channel>()
        var attributes: Map<String, String> = emptyMap()
        var displayName = ""
        var explicitGroup = ""
        var headers = linkedMapOf<String, String>()

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim().removePrefix("\uFEFF").trim()
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    // An orphaned EXTINF must not leak metadata into the next entry.
                    val metadata = line.substringAfter(':')
                    val separator = metadataSeparator(metadata)
                    val attributeText = if (separator >= 0) metadata.substring(0, separator) else metadata
                    attributes = attributePattern.findAll(attributeText).associate { match ->
                        val value = match.groups[2]?.value
                            ?: match.groups[3]?.value
                            ?: match.groups[4]?.value.orEmpty()
                        match.groupValues[1].lowercase(Locale.ROOT) to unescape(value)
                    }
                    displayName = if (separator >= 0) metadata.substring(separator + 1).trim() else ""
                    explicitGroup = ""
                    headers = linkedMapOf()
                }

                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    explicitGroup = line.substringAfter(':').trim()
                }

                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val option = line.substringAfter(':')
                    val separator = option.indexOf('=')
                    if (separator > 0) {
                        val name = when (option.substring(0, separator).trim().lowercase(Locale.ROOT)) {
                            "http-user-agent" -> "User-Agent"
                            "http-referrer", "http-referer" -> "Referer"
                            else -> null
                        }
                        val value = option.substring(separator + 1).trim()
                        if (name != null && value.isNotEmpty()) headers[name] = value
                    }
                }

                line.startsWith('#') -> Unit

                else -> {
                    val resolvedUrl = resolveHttpUrl(baseUrl, line)
                    if (resolvedUrl != null) {
                        val number = attributes["tvg-chno"].orEmpty().ifBlank { (channels.size + 1).toString() }
                        val name = displayName.ifBlank { attributes["tvg-name"].orEmpty() }
                            .ifBlank { "频道 $number" }
                        channels += Channel(
                            id = stableId(resolvedUrl),
                            name = name,
                            url = resolvedUrl,
                            group = attributes["group-title"].orEmpty().ifBlank { explicitGroup }
                                .ifBlank { "未分组" },
                            number = number,
                            logo = attributes["tvg-logo"]?.takeIf { it.isNotBlank() }
                                ?.let { resolveHttpUrl(baseUrl, it) },
                            headers = headers.toMap(),
                            epgId = attributes["tvg-id"]?.takeIf { it.isNotBlank() },
                            epgName = attributes["tvg-name"]?.takeIf { it.isNotBlank() },
                        )
                    }
                    attributes = emptyMap()
                    displayName = ""
                    explicitGroup = ""
                    headers = linkedMapOf()
                }
            }
        }
        return channels
    }

    private fun metadataSeparator(metadata: String): Int {
        var quote: Char? = null
        var escaped = false
        metadata.forEachIndexed { index, character ->
            when {
                escaped -> escaped = false
                character == '\\' && quote != null -> escaped = true
                quote != null -> if (character == quote) quote = null
                character == '"' || character == '\'' -> quote = character
                character == ',' -> return index
            }
        }
        return -1
    }

    private fun unescape(value: String): String =
        value.replace("\\\"", "\"").replace("\\'", "'").replace("\\\\", "\\")

    private fun resolveHttpUrl(baseUrl: String, value: String): String? = runCatching {
        val uri = URI(baseUrl).resolve(value)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if ((scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()) {
            uri.toASCIIString()
        } else {
            null
        }
    }.getOrNull()

    private fun stableId(url: String): String = MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray(Charsets.UTF_8)).take(12)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
