package com.ipodplayer3.app.data.lyrics

import com.ipodplayer3.app.data.model.LyricLine
import com.ipodplayer3.app.data.model.Lyrics
import java.io.File

object LyricParser {

    private val timeRegex = Regex("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?]")

    fun parseLrc(content: String, offsetMs: Long = 0L): Lyrics {
        val lines = mutableListOf<LyricLine>()
        content.lineSequence().forEach { raw ->
            val matches = timeRegex.findAll(raw).toList()
            if (matches.isEmpty()) return@forEach
            val text = timeRegex.replace(raw, "").trim()
            matches.forEach { m ->
                val min = m.groupValues[1].toLongOrNull() ?: 0
                val sec = m.groupValues[2].toLongOrNull() ?: 0
                val fracRaw = m.groupValues[3]
                val fracMs = when {
                    fracRaw.isEmpty() -> 0
                    fracRaw.length == 1 -> fracRaw.toLongOrNull()?.times(100) ?: 0
                    fracRaw.length == 2 -> fracRaw.toLongOrNull()?.times(10) ?: 0
                    else -> fracRaw.toLongOrNull() ?: 0
                }
                lines += LyricLine(
                    timeMs = min * 60_000 + sec * 1000 + fracMs,
                    text = text,
                )
            }
        }
        return Lyrics(lines.sortedBy { it.timeMs }, offsetMs)
    }

    /**
     * Resolve LRC next to the audio file: same name with .lrc,
     * or under a sibling `lyrics/` folder.
     */
    fun loadForAudio(audioPath: String): Lyrics? {
        if (audioPath.isBlank()) return null
        val audio = File(audioPath)
        val candidates = listOf(
            File(audio.parentFile, audio.nameWithoutExtension + ".lrc"),
            File(audio.parentFile, "lyrics/${audio.nameWithoutExtension}.lrc"),
            File(audio.parentFile, audio.nameWithoutExtension + ".LRC"),
        )
        val file = candidates.firstOrNull { it.isFile && it.canRead() } ?: return null
        return runCatching {
            parseLrc(file.readText(Charsets.UTF_8))
        }.getOrNull()
    }
}
