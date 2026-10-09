package com.ipodplayer3.app

import android.app.Application
import com.ipodplayer3.app.data.library.MusicRepository
import com.ipodplayer3.app.data.player.PlayerController
import com.ipodplayer3.app.data.playlist.PlaylistStore

class IPodApplication : Application() {

    lateinit var musicRepository: MusicRepository
        private set
    lateinit var playlistStore: PlaylistStore
        private set
    lateinit var playerController: PlayerController
        private set

    override fun onCreate() {
        super.onCreate()
        musicRepository = MusicRepository(this)
        playlistStore = PlaylistStore(this)
        playerController = PlayerController(this)
        com.ipodplayer3.app.ui.device.ClickSound.init(this)
        com.ipodplayer3.app.ui.device.Haptic.init(this)
    }
}
