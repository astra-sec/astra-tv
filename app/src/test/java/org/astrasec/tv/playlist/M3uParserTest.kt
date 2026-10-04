package org.astrasec.tv.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {
    private val baseUrl = "http://example.test/iptv/channels.m3u"

    @Test
    fun parsesQuotedMetadataBomCrLfAndRelativeAddresses() {
        val text = "\uFEFF#EXTM3U\r\n" +
            "#EXTINF:-1 tvg-name=\"备用名称\" tvg-chno=\"42\" " +
            "group-title=\"新闻, 综合\" tvg-logo=\"../logos/news.png\",新闻频道\r\n" +
            "streams/news.ts?token=one\r\n"

        val channel = M3uParser.parse(text, baseUrl).single()

        assertEquals("新闻频道", channel.name)
        assertEquals("42", channel.number)
        assertEquals("新闻, 综合", channel.group)
        assertEquals("http://example.test/iptv/streams/news.ts?token=one", channel.url)
        assertEquals("http://example.test/logos/news.png", channel.logo)
        assertEquals("备用名称", channel.epgName)
    }

    @Test
    fun preservesEpgIdentityWithoutChangingTheDisplayNameOrStableChannelId() {
        val original = M3uParser.parse("#EXTINF:-1,客厅频道\n/live.ts", baseUrl).single()
        val channel = M3uParser.parse(
            "#EXTINF:-1 tvg-id=\"6\" tvg-name=\"CCTV5+\",客厅频道\n/live.ts",
            baseUrl,
        ).single()
        assertEquals("客厅频道", channel.name)
        assertEquals("6", channel.epgId)
        assertEquals("CCTV5+", channel.epgName)
        assertEquals(original.id, channel.id)
    }

    @Test
    fun headersAndGroupsApplyOnlyToTheirChannel() {
        val channels = M3uParser.parse(
            """
                #EXTM3U
                #EXTINF:-1,First
                #EXTGRP:Local TV
                #EXTVLCOPT:http-user-agent=Living Room TV
                #EXTVLCOPT:http-referrer=http://portal.test/watch
                #EXTVLCOPT:network-caching=1000
                http://example.test/first.ts
                #EXTINF:-1 tvg-name='Second channel'
                http://example.test/second.ts
            """.trimIndent(),
            baseUrl,
        )

        assertEquals("Local TV", channels[0].group)
        assertEquals(
            mapOf("User-Agent" to "Living Room TV", "Referer" to "http://portal.test/watch"),
            channels[0].headers,
        )
        assertEquals("Second channel", channels[1].name)
        assertEquals("未分组", channels[1].group)
        assertTrue(channels[1].headers.isEmpty())
        assertEquals(listOf("1", "2"), channels.map { it.number })
    }

    @Test
    fun ignoresUnsupportedAndBrokenUrlsWithoutLeakingMetadata() {
        val channels = M3uParser.parse(
            """
                #EXTM3U
                #EXTINF:-1 tvg-chno="98" group-title="Unsupported",UDP
                #EXTVLCOPT:http-user-agent=should not leak
                udp://239.0.0.1:1234
                #EXTINF:-1,Malformed
                http://
                # A plain URL is still a valid M3U entry.
                https://example.test/plain.ts
                #EXTINF:-1,Orphaned entry
                #EXTINF:-1 group-title="Valid",Last
                //example.test/last.ts
            """.trimIndent(),
            baseUrl,
        )

        assertEquals(2, channels.size)
        assertEquals("频道 1", channels[0].name)
        assertEquals("1", channels[0].number)
        assertEquals("未分组", channels[0].group)
        assertTrue(channels[0].headers.isEmpty())
        assertEquals("Last", channels[1].name)
        assertEquals("Valid", channels[1].group)
        assertEquals("http://example.test/last.ts", channels[1].url)
    }

    @Test
    fun quotedCommasAndEscapedQuotesDoNotSplitTheMetadata() {
        val channels = M3uParser.parse(
            """
                #EXTINF:-1 group-title="A, \"B\"" tvg-chno=7,Channel, with comma
                /stream.ts
            """.trimIndent(),
            baseUrl,
        )

        assertEquals("A, \"B\"", channels.single().group)
        assertEquals("Channel, with comma", channels.single().name)
        assertEquals("7", channels.single().number)
    }

    @Test
    fun stableIdsDependOnUrlAndDuplicateAliasesArePreserved() {
        val first = M3uParser.parse("#EXTINF:-1,Original\nhttp://example.test/live.ts", baseUrl).single()
        val renamed = M3uParser.parse("#EXTINF:-1 tvg-chno=500,Renamed\n/live.ts", baseUrl).single()
        val changedUrl = M3uParser.parse("#EXTINF:-1,Original\n/other.ts", baseUrl).single()
        val aliases = M3uParser.parse(
            "#EXTINF:-1,One\n/live.ts\n#EXTINF:-1,Two\n/live.ts",
            baseUrl,
        )

        assertEquals(first.id, renamed.id)
        assertNotEquals(first.id, changedUrl.id)
        assertEquals(24, first.id.length)
        assertEquals(2, aliases.size)
        assertEquals(aliases[0].id, aliases[1].id)
    }

    @Test
    fun parsesRepresentativePlaylistWithAliasesMetadataAndRelativeUrls() {
        val fixture = requireNotNull(javaClass.getResourceAsStream("/playlist.m3u")) {
            "The test playlist fixture must be present"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val channels = M3uParser.parse(fixture, baseUrl)

        assertEquals(14, channels.size)
        assertEquals("CCTV-1 综合", channels.first().name)
        assertEquals("1", channels.first().number)
        assertEquals("CGTN英语", channels.last().name)
        assertEquals("99", channels.last().number)
        assertTrue(channels.all { it.url.startsWith("http://example.test/") || it.url.startsWith("https://example.test/") })
        val localNews = channels.single { it.name == "城镇新闻" }
        assertEquals("201", localNews.number)
        assertEquals("本地频道", localNews.group)
        assertEquals("city-news", localNews.epgId)
        assertEquals("自定义新闻别名", localNews.epgName)
        assertEquals("http://example.test/iptv/streams/local-news.ts", localNews.url)
        assertEquals("http://example.test/logos/local.png", localNews.logo)
        val duplicate = channels.single { it.name == "城镇新闻别名" }
        assertEquals(localNews.id, duplicate.id)
        assertEquals(2, channels.count { it.url == localNews.url })
    }
}
