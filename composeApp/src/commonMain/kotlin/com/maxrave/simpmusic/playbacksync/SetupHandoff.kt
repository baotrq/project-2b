package com.maxrave.simpmusic.playbacksync

import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val TAG = "SetupHandoff"

/**
 * Fork: the other direction of [PlaybackSyncSetup], for a device that cannot scan (no camera, or a
 * broken one). The device that wants to JOIN shows a receive code — its own addresses, a port it has
 * just opened and a one-time key — and the device that already syncs scans it and sends its setup
 * over the local network, sealed with that key. The same shape as signing in on the laptop.
 *
 * The key only ever travels on the screen, so nothing on the network can read or forge the setup;
 * the receiver still asks before joining. Plain TCP with a length prefix, no HTTP: both ends are this
 * app, and composeApp's commonMain has the JDK on both of its targets.
 */
object SetupHandoff {
    private const val PREFIX = "nhacnheo-sync-recv:1:"
    private val AAD = "nhacnheo-sync-handoff/v1/setup".encodeToByteArray()
    private const val MAX_BODY = 8 * 1024
    private const val CONNECT_TIMEOUT_MS = 2_500
    private const val READ_TIMEOUT_MS = 10_000
    private const val OK: Int = 1

    class Invite(
        val hosts: List<String>,
        val port: Int,
        val key: ByteArray,
    )

    fun isInvite(text: String) = text.trim().startsWith(PREFIX)

    /** `PREFIX` + `host,host;port;base64url(key)`. */
    fun decodeInvite(text: String): Invite? {
        val parts = text.trim().takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.split(';') ?: return null
        if (parts.size != 3) return null
        val hosts = parts[0].split(',').filter { it.isNotBlank() }
        val port = parts[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val key = runCatching { Base64.getUrlDecoder().decode(parts[2]) }.getOrNull()?.takeIf { it.size == 32 } ?: return null
        return if (hosts.isEmpty()) null else Invite(hosts, port, key)
    }

    /** A port held open while the receive code is on screen. Close it when the code goes away. */
    class Receiver private constructor(
        private val server: ServerSocket,
        private val key: ByteArray,
        val hosts: List<String>,
    ) : AutoCloseable {
        val invite: String =
            PREFIX + hosts.joinToString(",") + ";" + server.localPort + ";" +
                Base64.getUrlEncoder().withoutPadding().encodeToString(key)

        /**
         * Waits for a valid setup and returns it. Connections that do not carry one (anything else on
         * the network poking the port) are dropped and the wait goes on. Cancellable: the accept loop
         * wakes every half second to check.
         */
        suspend fun receive(): PlaybackSyncSetup.Setup =
            withContext(Dispatchers.IO) {
                server.soTimeout = 500
                var received: PlaybackSyncSetup.Setup? = null
                while (received == null) {
                    ensureActive()
                    val socket =
                        try {
                            server.accept()
                        } catch (_: SocketTimeoutException) {
                            continue
                        }
                    received =
                        socket.use { s ->
                            runCatching { handle(s) }
                                .onFailure { Logger.w(TAG, "Dropped a connection: ${it.message}") }
                                .getOrNull()
                        }
                }
                Logger.i(TAG, "Setup received")
                checkNotNull(received)
            }

        private fun handle(socket: Socket): PlaybackSyncSetup.Setup? {
            socket.soTimeout = READ_TIMEOUT_MS
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            val length = input.readInt()
            if (length !in 13..MAX_BODY) return null
            val body = ByteArray(length).also { input.readFully(it) }
            val setup = open(key, body)?.let { PlaybackSyncSetup.decode(it.decodeToString()) }
            output.writeByte(if (setup != null) OK else 0)
            output.flush()
            return setup
        }

        override fun close() {
            runCatching { server.close() }
        }

        companion object {
            /** Opens a port; null when this device has no address another device could reach. */
            suspend fun open(): Receiver? =
                withContext(Dispatchers.IO) {
                    val hosts = localAddresses()
                    if (hosts.isEmpty()) return@withContext null
                    val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
                    val server = ServerSocket().apply { bind(InetSocketAddress(0)) }
                    Logger.i(TAG, "Waiting for a setup on port ${server.localPort}")
                    Receiver(server, key, hosts)
                }
        }
    }

    /** Sends [setup] to the device that showed [invite]. True once it has taken the setup. */
    suspend fun send(
        invite: Invite,
        setup: PlaybackSyncSetup.Setup,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val body = seal(invite.key, PlaybackSyncSetup.encode(setup).encodeToByteArray())
            for (host in invite.hosts) {
                ensureActive()
                val delivered =
                    runCatching {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress(host, invite.port), CONNECT_TIMEOUT_MS)
                            socket.soTimeout = READ_TIMEOUT_MS
                            val output = DataOutputStream(socket.getOutputStream())
                            output.writeInt(body.size)
                            output.write(body)
                            output.flush()
                            DataInputStream(socket.getInputStream()).readByte().toInt() == OK
                        }
                    }.onFailure { Logger.w(TAG, "Could not send to $host: ${it.message}") }
                        .getOrDefault(false)
                if (delivered) {
                    Logger.i(TAG, "Setup sent to $host")
                    return@withContext true
                }
            }
            false
        }

    /**
     * Every up, non-loopback IPv4 address, minus container and hypervisor bridges, which exist only
     * inside this machine. Same rule as Login Sync's `reachableAddresses()`: private ranges are not
     * required, so a corporate LAN or a Tailscale address still works.
     */
    private fun localAddresses(): List<String> =
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }
                .filterNot { nic -> BRIDGE_PREFIXES.any { nic.name.startsWith(it) } }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
                .map { it.hostAddress }
                .distinct()
        }.getOrDefault(emptyList())

    private val BRIDGE_PREFIXES = listOf("docker", "br-", "veth", "virbr", "vboxnet", "vmnet", "podman", "cni")

    private fun seal(
        key: ByteArray,
        plain: ByteArray,
    ): ByteArray {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(AAD)
        return nonce + cipher.doFinal(plain)
    }

    private fun open(
        key: ByteArray,
        sealed: ByteArray,
    ): ByteArray? =
        runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed, 0, 12))
            cipher.updateAAD(AAD)
            cipher.doFinal(sealed, 12, sealed.size - 12)
        }.getOrNull()
}
