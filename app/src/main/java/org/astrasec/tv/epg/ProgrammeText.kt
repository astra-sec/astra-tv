package org.astrasec.tv.epg

/** Decode one residual entity layer without interpreting markup or external entities. */
internal object ProgrammeText {
    private val entity = Regex("&(apos|quot|amp|lt|gt|nbsp|#\\d{1,7}|#[xX][0-9a-fA-F]{1,6});")

    fun unescape(value: String): String = entity.replace(value) { match ->
        val name = match.groupValues[1]
        when (name) {
            "apos" -> "'"
            "quot" -> "\""
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "nbsp" -> " "
            else -> {
                val codePoint = if (name.startsWith("#x", ignoreCase = true)) {
                    name.substring(2).toIntOrNull(16)
                } else name.substring(1).toIntOrNull()
                if (codePoint != null && codePoint in 0x20..0x10FFFF &&
                    codePoint !in 0x7F..0x9F && codePoint !in 0xD800..0xDFFF
                ) {
                    String(Character.toChars(codePoint))
                } else match.value
            }
        }
    }
}
