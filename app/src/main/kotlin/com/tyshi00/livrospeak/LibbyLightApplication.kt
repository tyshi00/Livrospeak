package com.tyshi00.livrospeak

import android.app.Application
import com.tyshi00.livrospeak.library.LocalBookRepository
import com.tyshi00.livrospeak.library.AudiobookProgressStore
import com.tyshi00.livrospeak.library.GenreStore
import com.tyshi00.livrospeak.library.LibraryCardPinStore
import com.tyshi00.livrospeak.library.ListeningSessionStore
import com.tyshi00.livrospeak.library.LivrospeakSettingsStore
import com.tyshi00.livrospeak.library.RssFeedRepository
import com.tyshi00.livrospeak.library.RssDownloadManager
import com.tyshi00.livrospeak.player.LocalPlaybackController
import com.tyshi00.livrospeak.ui.LightThemeController

class LibbyLightApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LibbyWebPlayer.init(this)
        AudiobookProgressStore.init(this)
        LivrospeakSettingsStore.init(this)
        ListeningSessionStore.init(this)
        GenreStore.init(this)
        LibraryCardPinStore.init(this)
        RssFeedRepository.init(this)
        RssDownloadManager.init(this)
        LocalBookRepository.init(this)
        LocalPlaybackController.init(this)

        // Apply the persisted theme before any screen composes so LivrospeakSurface never
        // flashes dark-then-light (or vice versa) on startup.
        if (LivrospeakSettingsStore.current.invertColors) {
            LightThemeController.setLightTheme()
        } else {
            LightThemeController.setDarkTheme()
        }
    }
}
