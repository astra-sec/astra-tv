package org.astrasec.tv.epg

import java.time.Instant
import java.util.TimeZone
import org.astrasec.tv.playlist.Channel
import org.astrasec.tv.playlist.M3uParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.xml.sax.SAXException

class EpgCoreTest {
    private val now = Instant.parse("2026-10-02T12:10:00Z").toEpochMilli()

    private fun channel(name: String, id: String = name, epgId: String? = null, epgName: String? = null) =
        Channel(id, name, "http://example.test/$id", "", "1", epgId = epgId, epgName = epgName)

    private fun parse(xml: String, channels: List<Channel>, at: Long = now) =
        XmlTvParser.parse(xml.byteInputStream(), channels, "test-source", at)

    @Test
    fun normalizesQualityAndPunctuationButPreservesSeparateServices() {
        assertEquals("cctv16", EpgChannelMatcher.normalize("ＣＣＴＶ－１６ 奥林匹克 HD"))
        assertEquals("cctv5+", EpgChannelMatcher.normalize("CCTV-5+ 体育赛事"))
        assertEquals("cctv5+", EpgChannelMatcher.normalize("CCTV5PLUS"))
        assertNotEquals(EpgChannelMatcher.normalize("CCTV5"), EpgChannelMatcher.normalize("CCTV5+"))
        assertNotEquals(EpgChannelMatcher.normalize("CCTV4"), EpgChannelMatcher.normalize("CCTV4K"))
        assertNotEquals(EpgChannelMatcher.normalize("CCTV16"), EpgChannelMatcher.normalize("CCTV16 4K"))
        assertNotEquals(EpgChannelMatcher.normalize("CCTV16"), EpgChannelMatcher.normalize("CCTV16 超高清"))
        assertNotEquals(EpgChannelMatcher.normalize("湖南卫视"), EpgChannelMatcher.normalize("湖南卫视4K"))
        assertNotEquals(EpgChannelMatcher.normalize("CHC影迷电影"), EpgChannelMatcher.normalize("CHC高清电影"))
        assertNotEquals(EpgChannelMatcher.normalize("CGTN英语"), EpgChannelMatcher.normalize("CGTN英文纪录"))
        assertNotEquals(EpgChannelMatcher.normalize("CGTN西语"), EpgChannelMatcher.normalize("CGTN法语"))
        assertNotEquals(EpgChannelMatcher.normalize("CCTV4"), EpgChannelMatcher.normalize("CCTV4EUO"))
        assertNotEquals(EpgChannelMatcher.normalize("CCTV4EUO"), EpgChannelMatcher.normalize("CCTV4AME"))
        assertEquals("cctv18", EpgChannelMatcher.normalize("CCTV18"))
    }

    @Test
    fun ambiguousPlaylistMetadataCannotTurn4kOrRegionalServiceIntoStandardService() {
        assertEquals(setOf("cctv164k"), EpgChannelMatcher.candidates(channel("CCTV-16 奥林匹克 4K", epgName = "CCTV16")))
        assertEquals(setOf("湖南卫视4k"), EpgChannelMatcher.candidates(channel("湖南卫视4K", epgName = "湖南卫视")))
        assertEquals(setOf("cctv4europe"), EpgChannelMatcher.candidates(channel("CCTV-4 中文国际欧洲", epgName = "CCTV4")))
        assertEquals(setOf("cgtnfrench"), EpgChannelMatcher.candidates(channel("CGTN法语", epgName = "CGTN英语")))
    }

    @Test
    fun matches5plusAnd4kOnlyToTheirOwnXmlTvChannels() {
        val channels = listOf(channel("CCTV5"), channel("CCTV5+"), channel("CCTV4"), channel("CCTV4K"), channel("CCTV16 4K"))
        val schedules = parse(
            "<tv>" + listOf("CCTV5", "CCTV5+", "CCTV4", "CCTV4K", "CCTV16").mapIndexed { index, name ->
                "<channel id='$index'><display-name>$name</display-name></channel>"
            }.joinToString("") + (0..4).joinToString("") { index ->
                "<programme channel='$index' start='20261002200000 +0800' stop='20261002210000 +0800'><title>节目$index</title></programme>"
            } + "</tv>",
            channels,
        )
        assertEquals("节目1", EpgSnapshot(schedules).current(channels[1], now)?.title)
        assertEquals("节目3", EpgSnapshot(schedules).current(channels[3], now)?.title)
        assertNull(EpgSnapshot(schedules).current(channels[4], now))
    }

    @Test
    fun explicitTvgIdWinsOverNameAndChannelNumberIsNeverAnEpgId() {
        val explicit = channel("CCTV5", epgId = "real-id")
        val numeric = channel("Unrelated local channel", id = "numeric")
        val xml = """
            <tv>
              <channel id="alias"><display-name>CCTV5</display-name></channel>
              <channel id="real-id"><display-name>Operator sports service</display-name></channel>
              <channel id="1"><display-name>Different station</display-name></channel>
              <programme channel="alias" start="20261002200000 +0800" stop="20261002210000 +0800"><title>Wrong alias</title></programme>
              <programme channel="real-id" start="20261002200000 +0800" stop="20261002210000 +0800"><title>Explicit ID</title></programme>
              <programme channel="1" start="20261002200000 +0800" stop="20261002210000 +0800"><title>Channel number trap</title></programme>
            </tv>
        """.trimIndent()
        val schedules = parse(xml, listOf(explicit, numeric))
        assertEquals("Explicit ID", EpgSnapshot(schedules).current(explicit, now)?.title)
        assertFalse(schedules.containsKey(numeric.id))
    }

    @Test
    fun timesUseXmlTvOffsetsAndDefaultUtcEvenWhenDeviceZoneDiffers() {
        val oldZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            assertEquals(now, XmlTvParser.parseTimestamp("20261002201000 +0800"))
            assertEquals(now, XmlTvParser.parseTimestamp("20261002121000 +0000"))
            assertEquals(now, XmlTvParser.parseTimestamp("20261002121000"))
            assertEquals(now, XmlTvParser.parseTimestamp("202610020510 -0700"))
            assertNull(XmlTvParser.parseTimestamp("20260230200000 +0800"))
            assertNull(XmlTvParser.parseTimestamp("20261002200000 +0860"))
        } finally {
            TimeZone.setDefault(oldZone)
        }
    }

    @Test
    fun infersMissingStopAcrossMidnightFromNextStartButNeverAcrossLongGap() {
        val at = Instant.parse("2026-10-02T16:10:00Z").toEpochMilli()
        val target = channel("News")
        val schedules = parse(
            """
                <tv><channel id="news"><display-name>News</display-name></channel>
                  <programme channel="news" start="20261002235000 +0800"><title>Late news</title></programme>
                  <programme channel="news" start="20261003002000 +0800" stop="20261003010000 +0800"><title>Next show</title></programme>
                  <programme channel="news" start="20261003020000 +0800"><title>Unknown ending</title></programme>
                  <programme channel="news" start="20261004020000 +0800"><title>Next day only</title></programme>
                </tv>
            """.trimIndent(), listOf(target), at,
        )
        assertEquals("Late news", EpgSnapshot(schedules).current(target, at)?.title)
        assertEquals(Instant.parse("2026-10-02T16:20:00Z").toEpochMilli(), schedules[target.id]!!.first().endMs)
        assertEquals(2, schedules[target.id]!!.size)
    }

    @Test
    fun discardsMalformedStaleUnmatchedAndTooDistantProgrammes() {
        val target = channel("News")
        val schedules = parse(
            """
                <tv><channel id="news"><display-name>News</display-name></channel>
                  <programme channel="news" start="20260901000000 +0800" stop="20260901010000 +0800"><title>Old</title></programme>
                  <programme channel="news" start="20261002200000 +0800" stop="invalid"><title>Invalid ending</title></programme>
                  <programme channel="news" start="20261002200000 +0800" stop="20261002190000 +0800"><title>Reversed</title></programme>
                  <programme channel="news" start="20261101000000 +0800" stop="20261101010000 +0800"><title>Too distant</title></programme>
                  <programme channel="unknown" start="20261002200000 +0800" stop="20261002210000 +0800"><title>Different channel</title></programme>
                </tv>
            """.trimIndent(), listOf(target),
        )
        assertTrue(schedules.isEmpty())
    }

    @Test
    fun currentRespectsEndBoundaryAndGapsAndDoesNotHideAnOverlappingValidEntry() {
        val target = channel("News")
        val programme = EpgProgramme(1_000, 2_000, "News", "test")
        val snapshot = EpgSnapshot(mapOf(target.id to listOf(programme, EpgProgramme(1_500, 1_600, "Ended overlap", "test"))))
        assertNull(snapshot.current(target, 999))
        assertEquals(programme, snapshot.current(target, 1_000))
        assertEquals(programme, snapshot.current(target, 1_999))
        assertNull(snapshot.current(target, 2_000))
        assertNull(snapshot.current(channel("Missing"), 1_200))
        assertNull(EpgSnapshot(emptyMap()).current(target, 1_200))
    }

    @Test
    fun acceptsStandardXmlTvDtdDeclarationWithoutLoadingIt() {
        val target = channel("News")
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE tv SYSTEM "xmltv.dtd">
            <tv><channel id="news"><display-name>News</display-name></channel>
              <programme channel="news" start="20261002200000 +0800" stop="20261002210000 +0800"><title>News &amp; weather</title></programme>
            </tv>
        """.trimIndent()
        assertEquals("News & weather", EpgSnapshot(parse(xml, listOf(target))).current(target, now)?.title)
        val utf16 = xml.replace("UTF-8", "UTF-16").toByteArray(Charsets.UTF_16)
        val schedules = XmlTvParser.parse(utf16.inputStream(), listOf(target), "test", now)
        assertEquals("News & weather", EpgSnapshot(schedules).current(target, now)?.title)
    }

    @Test
    fun rejectsExternalDtdAndInternalEntitiesInsteadOfFetchingOrExpandingThem() {
        val badPrologues = listOf(
            "<!DOCTYPE tv SYSTEM 'http://127.0.0.1:9/never-fetch.dtd'>",
            "<!DOCTYPE tv SYSTEM 'xmltv.dtd' [<!ENTITY hidden 'Expanded secret'>]>",
            "<!DOCTYPE tv [<!ENTITY hidden SYSTEM 'file:///etc/passwd'>]>",
        )
        badPrologues.forEach { prologue ->
            try {
                parse("$prologue<tv><channel id='news'><display-name>News</display-name></channel></tv>", listOf(channel("News")))
                fail("Unsafe DTD was accepted")
            } catch (_: SAXException) {
                // Rejection is intentional, regardless of whether a particular JVM uses feature or handler.
            }
        }
    }

    @Test
    fun rejectsOversizedFieldsAndDeeplyNestedUntrustedXml() {
        val target = channel("News")
        val payloads = listOf(
            "<tv><channel id='news'><display-name>${"x".repeat(4_097)}</display-name></channel></tv>",
            "<tv>${"<unknown>".repeat(33)}${"</unknown>".repeat(33)}</tv>",
        )
        payloads.forEach { xml ->
            try {
                parse(xml, listOf(target))
                fail("Unbounded XML was accepted")
            } catch (_: SAXException) {
                // The parser should stop without retaining arbitrary input.
            }
        }
    }

    @Test
    fun representativePlaylistMatchesAliasesAndExplicitIdsWithoutInventingCoverage() {
        val fixture = requireNotNull(javaClass.getResourceAsStream("/playlist.m3u")) {
            "The test playlist fixture must be present"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val channels = M3uParser.parse(fixture, "http://example.test/playlist.m3u")
        assertEquals(14, channels.size)
        val xmlChannels = listOf(
            "general" to "CCTV1", "sports" to "CCTV5", "events" to "CCTV5+",
            "europe" to "CCTV4EUO", "america" to "CCTV4AME", "olympics" to "CCTV16",
            "ultra" to "CCTV4K", "education" to "中国教育1台",
            "family" to "CHC家庭电影", "cinema" to "CHC影迷电影",
            "city-news" to "城镇公共服务", "documentary" to "CGTN英文纪录",
        )
        val xml = "<tv>" + xmlChannels.joinToString("") { (id, name) ->
            "<channel id='$id'><display-name>$name</display-name></channel>"
        } + xmlChannels.joinToString("") { (id, _) ->
            "<programme channel='$id' start='20261002200000 +0800' stop='20261002210000 +0800'><title>Schedule $id</title></programme>"
        } + "</tv>"
        val snapshot = EpgSnapshot(parse(xml, channels))
        assertEquals(11, channels.count { snapshot.current(it, now) != null })
        val unmapped = channels.filter { snapshot.current(it, now) == null }.map { it.name }.toSet()
        assertEquals(setOf("CCTV-16 奥林匹克 4K", "城镇文体", "CGTN英语"), unmapped)
        val expectedSchedules = mapOf(
            "CCTV-5 体育" to "sports", "CCTV-5+ 体育赛事" to "events",
            "CCTV-4 中文国际欧洲" to "europe", "CCTV-4 中文国际美洲" to "america",
            "CCTV-4K 超高清" to "ultra", "CETV-1" to "education",
            "CHC家庭影院" to "family", "CHC影迷电影" to "cinema",
            "城镇新闻" to "city-news", "城镇新闻别名" to "city-news",
        )
        expectedSchedules.forEach { (channelName, scheduleId) ->
            val target = channels.single { it.name == channelName }
            assertEquals("Schedule $scheduleId", snapshot.current(target, now)?.title)
        }
    }
}
