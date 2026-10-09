package com.ipodplayer3.app.ui.settings

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import com.ipodplayer3.app.R
import java.util.Locale

/**
 * 界面文案。
 *
 * 应用内的语言开关**不跟随系统**，所以这里按指定 Locale 造一个 Context 再取资源 ——
 * 直接读 `context.resources` 会永远拿到系统语言。
 * 文案本体在 `res/values/strings.xml`（中文）与 `res/values-en/strings.xml`（英文）。
 */
object Strings {

    fun of(context: Context, language: AppLanguage): Bundle =
        Bundle(resourcesFor(context, language))

    private fun resourcesFor(context: Context, language: AppLanguage): Resources {
        val locale = when (language) {
            AppLanguage.ZH -> Locale.SIMPLIFIED_CHINESE
            AppLanguage.EN -> Locale.ENGLISH
        }
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config).resources
    }

    class Bundle(private val res: Resources) {

        val appName: String get() = res.getString(R.string.app_name)

        // 主菜单 / 音乐子菜单
        val music: String get() = res.getString(R.string.music)
        val songs: String get() = res.getString(R.string.songs)
        val albums: String get() = res.getString(R.string.albums)
        val artists: String get() = res.getString(R.string.artists)
        val coverFlow: String get() = res.getString(R.string.cover_flow)
        val playlists: String get() = res.getString(R.string.playlists)
        val search: String get() = res.getString(R.string.search)
        val nowPlaying: String get() = res.getString(R.string.now_playing)
        val settings: String get() = res.getString(R.string.settings)
        val about: String get() = res.getString(R.string.about)

        // 占位文案：数据层只给空串，显示层负责替换
        val unknownSong: String get() = res.getString(R.string.unknown_song)
        val unknownAlbum: String get() = res.getString(R.string.unknown_album)
        val unknownArtist: String get() = res.getString(R.string.unknown_artist)

        fun albumLabel(raw: String): String = raw.ifBlank { unknownAlbum }
        fun artistLabel(raw: String): String = raw.ifBlank { unknownArtist }
        fun songLabel(raw: String): String = raw.ifBlank { unknownSong }

        /** 复数：中文恒为「N 首」，英文 one/other 分开。 */
        fun tracks(count: Int): String =
            res.getQuantityString(R.plurals.track_count, count, count)

        // 空状态 / 权限
        val emptyLibrary: String get() = res.getString(R.string.empty_library)
        val noPermission: String get() = res.getString(R.string.no_permission)
        val grantPermission: String get() = res.getString(R.string.grant_permission)

        // 播放列表
        val createPlaylist: String get() = res.getString(R.string.create_playlist)
        val defaultPlaylistName: String get() = res.getString(R.string.default_playlist_name)
        val emptyPlaylist: String get() = res.getString(R.string.empty_playlist)
        val addToPlaylist: String get() = res.getString(R.string.add_to_playlist)
        val pickSong: String get() = res.getString(R.string.pick_song)
        val selectPlaylist: String get() = res.getString(R.string.select_playlist)
        val newPlaylistAndAdd: String get() = res.getString(R.string.new_playlist_and_add)
        val addSongToPlaylist: String get() = res.getString(R.string.add_song_to_playlist)
        val removeSongFromPlaylist: String get() = res.getString(R.string.remove_song_from_playlist)
        val deletePlaylist: String get() = res.getString(R.string.delete_playlist)
        val renamePlaylist: String get() = res.getString(R.string.rename_playlist)
        val renameHint: String get() = res.getString(R.string.rename_hint)
        val save: String get() = res.getString(R.string.save)
        val cancel: String get() = res.getString(R.string.cancel)
        val confirm: String get() = res.getString(R.string.confirm)
        val playlistRemoved: String get() = res.getString(R.string.playlist_removed)
        val playlistDeleted: String get() = res.getString(R.string.playlist_deleted)
        val playlistRenamed: String get() = res.getString(R.string.playlist_renamed)
        val playlistSaveFailed: String get() = res.getString(R.string.playlist_save_failed)
        val noPlaylistYet: String get() = res.getString(R.string.no_playlist_yet)

        fun addedTo(name: String): String = res.getString(R.string.playlist_added, name)
        fun confirmDeletePlaylist(name: String): String =
            res.getString(R.string.confirm_delete_playlist, name)

        // 设置
        val shuffle: String get() = res.getString(R.string.shuffle)
        val repeat: String get() = res.getString(R.string.repeat)
        val all: String get() = res.getString(R.string.repeat_all)
        val one: String get() = res.getString(R.string.repeat_one)
        val none: String get() = res.getString(R.string.repeat_none)
        val language: String get() = res.getString(R.string.language)
        val zh: String get() = res.getString(R.string.language_zh)
        val en: String get() = res.getString(R.string.language_en)
        val theme: String get() = res.getString(R.string.theme)
        val silver: String get() = res.getString(R.string.theme_silver)
        val black: String get() = res.getString(R.string.theme_black)
        val u2: String get() = res.getString(R.string.theme_u2)
        val clickSound: String get() = res.getString(R.string.click_sound)
        val vibrate: String get() = res.getString(R.string.vibrate)
        val on: String get() = res.getString(R.string.on)
        val off: String get() = res.getString(R.string.off)
        val rescan: String get() = res.getString(R.string.rescan)
        val transitionLabel: String get() = res.getString(R.string.transition_label)
        val transitionFade: String get() = res.getString(R.string.transition_fade)
        val transitionSlide: String get() = res.getString(R.string.transition_slide)
        val transitionMorph: String get() = res.getString(R.string.transition_morph)

        // 均衡器
        val equalizer: String get() = res.getString(R.string.equalizer)
        val preset: String get() = res.getString(R.string.preset)
        val flat: String get() = res.getString(R.string.preset_flat)
        val pop: String get() = res.getString(R.string.preset_pop)
        val rock: String get() = res.getString(R.string.preset_rock)
        val classical: String get() = res.getString(R.string.preset_classical)
        val jazz: String get() = res.getString(R.string.preset_jazz)
        val bassBoost: String get() = res.getString(R.string.preset_bass)

        // 歌词
        val noLyrics: String get() = res.getString(R.string.no_lyrics)
        val lyricsFolder: String get() = res.getString(R.string.lyrics_folder)
        val lyricsFolderSet: String get() = res.getString(R.string.lyrics_folder_set)
        val lyricsFolderNone: String get() = res.getString(R.string.lyrics_folder_none)

        // 搜索
        val searchHint: String get() = res.getString(R.string.search_hint)

        // 无障碍
        val clickWheel: String get() = res.getString(R.string.click_wheel)
        val a11yMenu: String get() = res.getString(R.string.a11y_menu)
        val a11yPlayPause: String get() = res.getString(R.string.a11y_play_pause)
        val a11yPrevious: String get() = res.getString(R.string.a11y_previous)
        val a11yNext: String get() = res.getString(R.string.a11y_next)
        val a11ySelect: String get() = res.getString(R.string.a11y_select)
    }
}
