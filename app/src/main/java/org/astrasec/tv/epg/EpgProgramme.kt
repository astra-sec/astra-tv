package org.astrasec.tv.epg

import org.astrasec.tv.playlist.Channel

data class EpgProgramme(
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val source: String,
) {
    /** Keep the stored source text unchanged so cache reads never decode it repeatedly. */
    val displayTitle: String get() = ProgrammeText.unescape(title)
}

/** Schedules use the playlist's stable channel ID, never its position or channel number. */
class EpgSnapshot(
    val schedules: Map<String, List<EpgProgramme>>,
    val fetchedAtMs: Long = 0,
) {
    fun current(channel: Channel, nowMs: Long): EpgProgramme? = schedules[channel.id]
        ?.asSequence()
        ?.filter { it.startMs <= nowMs && nowMs < it.endMs && it.title.isNotBlank() }
        ?.maxByOrNull { it.startMs }

    /** Follow start times, including gaps and overlaps in the upstream schedule. */
    fun next(channel: Channel, nowMs: Long): EpgProgramme? = schedules[channel.id]
        ?.asSequence()
        ?.filter { it.startMs > nowMs && it.endMs > it.startMs && it.title.isNotBlank() }
        ?.minByOrNull { it.startMs }
}
