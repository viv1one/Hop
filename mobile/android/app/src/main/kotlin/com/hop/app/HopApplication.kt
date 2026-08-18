package com.hop.app

import android.app.Application
import java.io.File

/**
 * App entry point. Constructs the single [AppContainer] instance for the
 * process's lifetime -- every screen reaches its dependencies via
 * `(application as HopApplication).container`, never by constructing its own.
 */
class HopApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(applicationContext)
        sweepStaleDecryptedPlaybackFiles()
    }

    /**
     * Defense-in-depth, run once per process start: deletes any leftover
     * contents of `cacheDir/decrypted-playback/` from a previous process
     * instance (e.g. a crash while a video post was on-screen). The Feed
     * screen's own `DisposableEffect` deletes a post's temp playback file the
     * moment its page leaves composition in the normal case -- this sweep
     * only exists to catch what that didn't. See `com.hop.app.feed`'s video
     * playback path for why a short-lived plaintext temp file exists at all:
     * a deliberate, bounded, documented exception to [com.hop.data.PostEntity]'s
     * "ciphertext only on disk" contract, needed because `ContentEncryption`
     * is whole-blob AES-GCM, not a streaming cipher, and Media3 needs an
     * actual seekable file to play from.
     */
    private fun sweepStaleDecryptedPlaybackFiles() {
        File(cacheDir, "decrypted-playback").deleteRecursively()
    }
}
