package com.ipodplayer3.app.data.lyrics

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.ipodplayer3.app.data.model.LyricLine
import com.ipodplayer3.app.data.model.Lyrics
import java.io.File

object LyricParser {

    private val timeRegex = Regex("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,4}))?]")

    /** 标准 [offset:±ms] 标签。 */
    private val offsetRegex = Regex("\\[offset:\\s*([+-]?\\d+)\\s*]", RegexOption.IGNORE_CASE)

    fun parseLrc(content: String, offsetMs: Long = 0L): Lyrics {
        val lines = mutableListOf<LyricLine>()
        content.lineSequence().forEach { raw ->
            if (offsetRegex.containsMatchIn(raw)) return@forEach
            val matches = timeRegex.findAll(raw).toList()
            if (matches.isEmpty()) return@forEach
            val text = timeRegex.replace(raw, "").trim().removePrefix("\uFEFF")
            // 只有时间标签、没有文字的行（间奏占位）不要生成空歌词行，
            // 否则歌词页会在那一行显示空白，而不是继续高亮上一句。
            if (text.isEmpty()) return@forEach
            matches.forEach { m ->
                val min = m.groupValues[1].toLongOrNull() ?: 0
                val sec = m.groupValues[2].toLongOrNull() ?: 0
                val fracRaw = m.groupValues[3]
                val fracMs = when {
                    fracRaw.isEmpty() -> 0
                    fracRaw.length == 1 -> fracRaw.toLongOrNull()?.times(100) ?: 0
                    fracRaw.length == 2 -> fracRaw.toLongOrNull()?.times(10) ?: 0
                    // 4 位小数是 1/10000 秒
                    fracRaw.length == 4 -> (fracRaw.toLongOrNull() ?: 0) / 10
                    else -> fracRaw.toLongOrNull() ?: 0
                }
                lines += LyricLine(
                    timeMs = min * 60_000 + sec * 1000 + fracMs,
                    text = text,
                )
            }
        }
        // [offset:+500] = 整体时间戳 +500ms（歌词晚 500ms 出现）。
        // Lyrics.offsetMs 的语义是 `t = position - offsetMs`，所以这里是加。
        val tagOffset = offsetRegex.find(content)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return Lyrics(lines.sortedBy { it.timeMs }, offsetMs + tagOffset)
    }

    /**
     * LRC 解码：先剥 BOM，再按 UTF-8 解；出现替换字符说明不是 UTF-8，
     * 回退 GB18030（中文歌词最常见的编码）。
     *
     * 以前硬编码 UTF-8：GBK 歌词不是「读不到」，而是满屏乱码，用户完全无从判断。
     */
    fun decodeLrc(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        val utf8 = String(bytes, Charsets.UTF_8)
        if (utf8.none { it == '\uFFFD' }) return utf8
        return runCatching { String(bytes, charset("GB18030")) }.getOrDefault(utf8)
    }

    /**
     * Resolve LRC next to the audio file: same name with .lrc,
     * or under a sibling `lyrics/` folder.
     *
     * Direct [File] access only works below Android 10 or with legacy storage;
     * on scoped storage this returns null for non-media `.lrc`. Callers should
     * fall back to [loadViaSaf].
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
            // 不用 readText(UTF_8)：GBK 歌词会变成乱码而不是报错，见 decodeLrc。
            parseLrc(decodeLrc(file.readBytes()))
        }.getOrNull()
    }

    /**
     * Load `\<name>.lrc` from a user-granted SAF directory tree. This is the
     * path that works under scoped storage (Android 10+), where [File] cannot
     * read `.lrc` because it is not a media type.
     *
     * @param treeUri persisted `ACTION_OPEN_DOCUMENT_TREE` result
     * @param audioName file name of the song, e.g. `MySong.mp3`
     */
    fun loadViaSaf(context: Context, treeUri: Uri, audioName: String): Lyrics? {
        // A revoked/expired persistable grant makes DocumentsContract throw.
        return runCatching { loadViaSafInternal(context, treeUri, audioName) }.getOrNull()
    }

    private fun loadViaSafInternal(context: Context, treeUri: Uri, audioName: String): Lyrics? {
        if (audioName.isBlank()) return null
        val base = audioName.substringBeforeLast('.')
        if (base.isBlank()) return null

        // Try the picked folder first, then a nested lyrics/ subfolder.
        val roots = buildList {
            add(treeUri)
            findChildDir(context, treeUri, "lyrics")?.let { add(it) }
            findChildDir(context, treeUri, "Lyrics")?.let { add(it) }
        }

        for (root in roots) {
            val lrcUri = findChildFile(context, root, base, listOf("lrc", "LRC")) ?: continue
            val text = runCatching {
                context.contentResolver.openInputStream(lrcUri)?.use { it.readBytes() }
                    ?.let { decodeLrc(it) }
            }.getOrNull() ?: continue
            // 找到了同名 .lrc 但里面没有任何时间标签（例如只是说明文件）时不要
            // 直接返回空歌词把后面的候选目录挡掉，继续找下一个。
            val parsed = runCatching { parseLrc(text) }.getOrNull()
            if (parsed != null && parsed.lines.isNotEmpty()) return parsed
        }
        return null
    }

    private fun findChildDir(context: Context, treeUri: Uri, dirName: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
        val proj = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        return runCatching {
            context.contentResolver.query(children, proj, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(2) != DocumentsContract.Document.MIME_TYPE_DIR) continue
                    if (!dirName.equals(c.getString(1), ignoreCase = true)) continue
                    return@runCatching DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0))
                }
                null
            }
        }.getOrNull()
    }

    private fun findChildFile(
        context: Context,
        treeUri: Uri,
        base: String,
        extensions: List<String>,
    ): Uri? {
        // A nested dir comes back as tree/<treeId>/document/<docId>; its children
        // hang off <docId>, not off the tree root.
        val docId = if (DocumentsContract.isDocumentUri(context, treeUri)) {
            DocumentsContract.getDocumentId(treeUri)
        } else {
            DocumentsContract.getTreeDocumentId(treeUri)
        }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val proj = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        return runCatching {
            context.contentResolver.query(children, proj, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val name = c.getString(1) ?: continue
                    val ok = extensions.any { ext ->
                        name.equals("$base.$ext", ignoreCase = true)
                    }
                    if (ok) {
                        return@runCatching DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0))
                    }
                }
                null
            }
        }.getOrNull()
    }
}
