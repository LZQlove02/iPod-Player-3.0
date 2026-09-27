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
        instance = this
        musicRepository = MusicRepository(this)
        playlistStore = PlaylistStore(this)
        playerController = PlayerController(this)
    }

    companion object {
        lateinit var instance: IPodApplication
            private set
    }
}
