package org.astrasec.tv.epg

import java.text.Normalizer
import java.util.Locale
import org.astrasec.tv.playlist.Channel

/** Conservative aliases checked against test playlists and upstream EPG names. */
object EpgChannelMatcher {
    private val separator = Regex("[\\s\\p{Z}\\-_.,·:：()（）\\[\\]【】]")
    private val qualitySuffix = Regex("(?:(?<!超)高清|标清|hd|sd)+$")
    private val cctvNumber = Regex("^cctv0?(1[0-7]|[1-9])(\\+|plus)?(.*)$")

    private val aliases = mapOf(
        "中国教育1台" to "中国教育1", "中国教育一套" to "中国教育1", "cetv1" to "中国教育1",
        "中国教育2台" to "中国教育2", "中国教育二套" to "中国教育2", "cetv2" to "中国教育2",
        "中国教育4台" to "中国教育4", "中国教育四套" to "中国教育4", "cetv4" to "中国教育4",
        "国学频道" to "国学", "书画频道" to "书画",
        "chc家庭影院" to "chc家庭电影",
        "cctv第一剧场" to "第一剧场", "cctv怀旧剧场" to "怀旧剧场",
        "cctv风云剧场" to "风云剧场", "cctv风云音乐" to "风云音乐",
        "央视文化精品" to "央视精品", "cctv文化精品" to "央视精品",
        "凤凰中文台" to "凤凰中文", "凤凰资讯台" to "凤凰资讯",
        "cgtn英文纪录" to "cgtndocumentary", "cgtn纪录" to "cgtndocumentary",
        "cgtndocumentary" to "cgtndocumentary",
        "cgtn西班牙语" to "cgtnspanish", "cgtn西语" to "cgtnspanish",
        "cgtnespañol" to "cgtnspanish", "cgtnspanish" to "cgtnspanish",
        "cgtn法语" to "cgtnfrench", "cgtnfrançais" to "cgtnfrench",
        "cgtnfrench" to "cgtnfrench",
        "cgtn阿拉伯语" to "cgtnarabic", "cgtn阿语" to "cgtnarabic",
        "cgtnarabic" to "cgtnarabic",
        "cgtn俄语" to "cgtnrussian", "cgtnrussian" to "cgtnrussian",
        "cgtn英语" to "cgtnenglish", "cgtn英文" to "cgtnenglish",
        "cgtnenglish" to "cgtnenglish",
    )

    fun candidates(channel: Channel): Set<String> {
        val identity = normalize(channel.name)
        return sequenceOf(channel.epgName, channel.name)
            .filterNotNull().map(::normalize).filter(String::isNotEmpty)
            .filter { candidate ->
                // Keep variants separate even if an M3U's optional tvg-name is less specific.
                when {
                    identity.startsWith("cctv") || identity.startsWith("cgtn") -> candidate == identity
                    identity.contains("4k") -> candidate.contains("4k")
                    else -> true
                }
            }.toSet()
    }

    fun normalize(name: String): String {
        val compact = Normalizer.normalize(name.take(1_024), Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).replace(separator, "").replace(qualitySuffix, "")
        if (compact.startsWith("cctv4k")) return "cctv4k"
        cctvNumber.matchEntire(compact)?.let { match ->
            val number = match.groupValues[1]
            val plus = match.groupValues[2].isNotEmpty()
            val suffix = match.groupValues[3]
            if (suffix.firstOrNull()?.isDigit() == true) return compact
            if (suffix.contains("4k") || suffix.contains("超高清")) return "cctv${number}${if (plus) "+" else ""}4k"
            if (number == "4" && !plus) {
                if (suffix.contains("欧洲") || suffix in setOf("europe", "euo", "eu")) return "cctv4europe"
                if (suffix.contains("美洲") || suffix in setOf("america", "ame", "us")) return "cctv4america"
            }
            return "cctv$number${if (plus) "+" else ""}"
        }
        return aliases[compact] ?: compact
    }

}
