package com.maxrave.simpmusic.playbacksync

import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.logger.Logger
import com.maxrave.simpmusic.lyricsfix.LyricsFixes
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

private const val TAG = "PlaybackSyncSetup"

/**
 * Fork: the setup code one device shows and another scans (or pastes) to join the same sync server.
 * It carries the server, the sync key and, on the owner's devices, the lyrics admin key. It does NOT
 * carry the device id or name: those belong to each device, and a copied id would make both devices
 * ignore each other's snapshots.
 *
 * Whoever holds the code holds the sync key, and the sync key opens the sealed sign-ins on the
 * server. That is why the code is only read from the app's own scanner or paste box, after a
 * confirmation, and never routed as a `simpmusic://` link: any web page could open such a link and
 * point this device's uploads at a server of its choosing.
 */
object PlaybackSyncSetup {
    /** Not a URL scheme anything registers: a system camera app shows it as plain text. */
    private const val PREFIX = "nhacnheo-sync:1:"

    @Serializable
    data class Setup(
        @SerialName("u") val url: String,
        @SerialName("k") val key: String,
        @SerialName("a") val adminKey: String? = null,
        /** [CARRIED_SETTINGS] this device had set. Older codes carry none. */
        @SerialName("s") val settings: Map<String, String> = emptyMap(),
    ) {
        /** The server's host, for the confirmation; the key is never shown. */
        val host: String get() = url.removePrefix("https://").substringBefore('/')
    }

    /**
     * Lyrics translation and AI, carried in the code itself rather than left to the server: the
     * server's copy of the AI settings can be older than this device's, or missing, and the new
     * device should translate the moment it joins. All are string preferences.
     */
    private val CARRIED_SETTINGS =
        listOf(
            "ai_provider",
            "ai_gemini_api_key",
            "custom_model_id",
            "custom_openai_base_url",
            "custom_openai_headers",
            "use_ai_translation",
            "translation_language",
            "use_translation_language",
        )

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(setup: Setup): String =
        PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(json.encodeToString(Setup.serializer(), setup).encodeToByteArray())

    /** The setup in [text], or null when it is not a valid setup code. */
    fun decode(text: String): Setup? {
        val body = text.trim().takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX) ?: return null
        val setup =
            runCatching {
                json.decodeFromString(Setup.serializer(), Base64.getUrlDecoder().decode(body).decodeToString())
            }.getOrNull() ?: return null
        val url = setup.url.trim().trimEnd('/')
        val key = setup.key.trim()
        // Same rule as PlaybackSync.server(): the key travels in a header, so HTTPS only.
        if (!url.startsWith("https://") || url.length <= "https://".length || key.isEmpty()) return null
        return Setup(
            url = url,
            key = key,
            adminKey = setup.adminKey?.trim()?.takeIf { it.isNotEmpty() },
            // Only names this build knows how to carry, so a code cannot write anything else.
            settings = setup.settings.filter { (name, value) -> name in CARRIED_SETTINGS && value.isNotBlank() },
        )
    }

    /** This device's setup, or null until a server and key are both saved. */
    suspend fun current(dataStore: DataStoreManager): Setup? {
        val url = dataStore.getString(PlaybackSync.KEY_URL).first()?.trim()?.trimEnd('/')
        val key = dataStore.getString(PlaybackSync.KEY_SECRET).first()?.trim()
        if (url.isNullOrEmpty() || key.isNullOrEmpty() || !url.startsWith("https://")) return null
        val admin = dataStore.getString(LyricsFixes.KEY_ADMIN).first()?.trim()?.takeIf { it.isNotEmpty() }
        val settings =
            CARRIED_SETTINGS
                .mapNotNull { name -> dataStore.getString(name).first()?.takeIf { it.isNotBlank() }?.let { name to it } }
                .toMap()
        return Setup(url, key, admin, settings)
    }

    /**
     * Saves [setup] and switches sync on. A missing admin key leaves this device's own in place.
     * Sign-ins and the AI key then arrive with the next settings pull, which is started here rather
     * than left to the next time the app comes to the front.
     */
    suspend fun apply(
        dataStore: DataStoreManager,
        setup: Setup,
    ) {
        dataStore.putString(PlaybackSync.KEY_URL, setup.url)
        dataStore.putString(PlaybackSync.KEY_SECRET, setup.key)
        dataStore.putString(PlaybackSync.KEY_ENABLED, DataStoreManager.TRUE)
        setup.adminKey?.let { dataStore.putString(LyricsFixes.KEY_ADMIN, it) }
        Logger.i(TAG, "Sync set up from a setup code (server ${setup.host}, ${setup.settings.size} settings)")
        SettingsSync.pull()
        // Written AFTER the pull: the server's copy may be older than the device that showed the
        // code, and a pull run later would otherwise put that older key or model back. Changing them
        // here also makes SettingsSync upload them, which brings the server up to date.
        setup.settings.forEach { (name, value) -> dataStore.putString(name, value) }
    }
}
