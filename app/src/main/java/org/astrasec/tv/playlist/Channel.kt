package org.astrasec.tv.playlist

data class Channel(
    val id: String,
    val name: String,
    val url: String,
    val group: String,
    val number: String,
    val logo: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val epgId: String? = null,
    val epgName: String? = null,
)
