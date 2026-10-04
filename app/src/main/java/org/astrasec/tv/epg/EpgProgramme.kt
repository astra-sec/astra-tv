package org.astrasec.tv.epg

import org.astrasec.tv.playlist.Channel

data class EpgProgramme(
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val source: String,
)

/** Schedules use the playlist's stable channel ID, never its position or channel number. */
class EpgSnapshot(
    val schedules: Map<String, List<EpgProgramme>>,
    val fetchedAtMs: Long = 0,
) {
    fun current(channel: Channel, nowMs: Long): EpgProgramme? = schedules[channel.id]
        ?.asSequence()
        ?.filter { it.startMs <= nowMs && nowMs < it.endMs && it.title.isNotBlank() }
        ?.maxByOrNull { it.startMs }
}
