package com.simpleshield.adblocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AdBlockVpnService.kt
 *
 * HOW THIS APP BLOCKS ADS, IN PLAIN TERMS:
 *
 * Android doesn't let a normal app watch other apps' network traffic. The
 * one exception is VpnService — an app can ask the user's permission to
 * become a lightweight local VPN, and Android then routes traffic through
 * it. We use that ability for ONE narrow purpose: intercepting DNS lookups
 * (the "what's the IP address for doubleclick.net?" step every app and
 * browser does before it can actually connect to a server).
 *
 * We do NOT intercept your regular web/app traffic — only DNS. This is done
 * with a deliberate trick in `buildVpnInterface()`: we tell Android our own
 * virtual address is the DNS server to use, but we only add a network ROUTE
 * for that one address. Everything else (the actual content of web pages,
 * videos, etc.) never touches this VPN tunnel at all and goes out normally.
 *
 * For every DNS query that arrives:
 *   1. We read the domain being looked up (DnsMessageLogic.extractQueriedDomain).
 *   2. If it's a known ad/tracker domain (BlockedDomainList.isBlocked), we
 *      immediately reply "this domain doesn't exist" (NXDOMAIN) — the app
 *      that asked never gets an IP address to connect to, so the ad never
 *      loads.
 *   3. Otherwise, we forward the query to the current network's DNS server
 *      (with public fallbacks) and relay its real answer back, so normal
 *      browsing is unaffected.
 *
 * All the tricky byte-level packet math (checksums, header parsing) lives
 * in DnsMessageLogic.kt and IpPacketLogic.kt, which were tested separately
 * before being used here — this file just wires them together.
 */
class AdBlockVpnService : VpnService() {

    companion object {
        private const val LOG_TAG = "SimpleShieldVpn"

        // Our own fake network, used only inside this VPN tunnel.
        private const val VPN_INTERFACE_ADDRESS = "10.111.222.2"
        private const val VPN_DNS_ADDRESS = "10.111.222.1"
        private const val VPN_INTERFACE_PREFIX_LENGTH = 32

        // Public fallbacks are only used when the DNS servers supplied by the
        // current Wi-Fi/mobile network do not answer.
        private val FALLBACK_DNS_SERVERS = listOf("1.1.1.1", "8.8.8.8")
        private const val DNS_PORT = 53

        private const val MAX_PACKET_SIZE_BYTES = 4096
        private const val UPSTREAM_RESPONSE_TIMEOUT_MS = 1500
        private const val DNS_WORKER_COUNT = 4
        private const val DNS_QUEUE_CAPACITY = 128
        private const val DNS_ENDPOINT_REFRESH_INTERVAL_MS = 10_000L

        // A long-running VpnService should run in the foreground with a
        // visible, persistent notification — this is standard practice for
        // any app continuously intercepting network traffic (transparent to
        // the user about what's running), and also keeps Android's battery
        // optimizations from killing the service in the background.
        private const val NOTIFICATION_CHANNEL_ID = "ad_blocking_active"
        private const val FOREGROUND_NOTIFICATION_ID = 1

        // Broadcast so MainActivity can update its "blocked today" counter.
        const val ACTION_DOMAIN_BLOCKED = "com.simpleshield.adblocker.DOMAIN_BLOCKED"
        const val ACTION_SERVICE_STATE_CHANGED = "com.simpleshield.adblocker.SERVICE_STATE_CHANGED"
        const val EXTRA_SERVICE_ACTIVE = "service_active"
        const val ACTION_STOP_SERVICE = "com.simpleshield.adblocker.STOP_SERVICE"
        const val ACTION_PAUSE_SERVICE = "com.simpleshield.adblocker.PAUSE_SERVICE"

        // MainActivity and this service live in the same app process. This
        // is the authoritative live-state check when the Activity is rebuilt.
        private val serviceInstanceActive = AtomicBoolean(false)
        fun isRunningInProcess(): Boolean = serviceInstanceActive.get()
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private data class DnsEndpoint(val network: Network?, val address: InetAddress)

    @Volatile private var physicalDnsEndpoints: List<DnsEndpoint> = emptyList()
    @Volatile private var lastDnsEndpointRefreshElapsedMs = 0L
    private val isServiceRunning = AtomicBoolean(false)
    private val hasCompletedShutdown = AtomicBoolean(false)

    // DNS traffic arrives in bursts. A bounded pool avoids creating one
    // thread per lookup (which was costly for apps such as YouTube), while
    // CallerRunsPolicy applies back-pressure instead of silently losing DNS.
    private val dnsExecutor = ThreadPoolExecutor(
        DNS_WORKER_COUNT,
        DNS_WORKER_COUNT,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(DNS_QUEUE_CAPACITY),
        { task -> Thread(task, "SimpleShield-DnsWorker").apply { isDaemon = true } },
        ThreadPoolExecutor.CallerRunsPolicy(),
    )

    // Writes to the VPN's output stream can happen from multiple background
    // threads (one per in-flight forwarded query) — this lock keeps those
    // writes from interleaving and corrupting each other.
    private val vpnWriteLock = Any()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE_SERVICE) {
            stopBlocking()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP_SERVICE) {
            getSharedPreferences("app_settings", MODE_PRIVATE)
                .edit()
                .putBoolean("start_on_boot", false)
                .apply()
            stopBlocking()
            ProtectionPauseScheduler.cancel(this)
            stopSelf()
            return START_NOT_STICKY
        }
        if (isServiceRunning.get()) {
            Log.i(LOG_TAG, "Service already running; ignoring duplicate start request.")
            return START_STICKY
        }
        startBlocking()
        return START_STICKY
    }

    override fun onDestroy() {
        stopBlocking()
        super.onDestroy()
    }

    override fun onRevoke() {
        // The user revoked VPN permission (e.g. from Android's system VPN
        // settings, or by enabling a different VPN app) — stop cleanly.
        stopBlocking()
        super.onRevoke()
    }

    private fun startBlocking() {
        hasCompletedShutdown.set(false)
        startForeground(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification())

        // Capture the real Wi-Fi/mobile DNS before establish() makes this VPN
        // Android's active network. Looking it up afterwards can return our
        // own synthetic 10.111.222.1 address and unnecessarily force every
        // allowed lookup through a public fallback.
        physicalDnsEndpoints = readPhysicalDnsEndpoints()
        lastDnsEndpointRefreshElapsedMs = SystemClock.elapsedRealtime()
        val establishedInterface = buildVpnInterface()
        if (establishedInterface == null) {
            Log.e(LOG_TAG, "Could not establish the VPN interface; stopping service.")
            stopSelf()
            return
        }
        vpnInterface = establishedInterface
        isServiceRunning.set(true)
        serviceInstanceActive.set(true)
        publishServiceState(active = true)

        Thread({ runPacketReadLoop(establishedInterface) }, "SimpleShield-PacketLoop").start()
        Log.i(LOG_TAG, "Ad blocking started.")
    }

    /** Builds the persistent "ad blocking is active" notification required
     * to run as a foreground service. Tapping it reopens the app. */
    private fun buildForegroundNotification(): Notification {
        val notificationManager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW, // low = no sound/heads-up, just a quiet status icon
            )
            notificationManager.createNotificationChannel(channel)
        }

        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopServiceIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AdBlockVpnService::class.java).setAction(ACTION_STOP_SERVICE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_shield_notification)
            .setContentIntent(openAppIntent)
            .addAction(0, getString(R.string.stop_protection), stopServiceIntent)
            .setOngoing(true)
            .build()
    }

    private fun stopBlocking() {
        if (!hasCompletedShutdown.compareAndSet(false, true)) return
        isServiceRunning.set(false)
        serviceInstanceActive.set(false)
        dnsExecutor.shutdownNow()
        try {
            vpnInterface?.close()
        } catch (error: Exception) {
            Log.w(LOG_TAG, "Error while closing the VPN interface", error)
        }
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(FOREGROUND_NOTIFICATION_ID)
        publishServiceState(active = false)
        Log.i(LOG_TAG, "Ad blocking stopped.")
    }

    private fun publishServiceState(active: Boolean) {
        ProtectionStateStore.setActive(this, active)
        sendBroadcast(
            Intent(ACTION_SERVICE_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_SERVICE_ACTIVE, active),
        )
    }

    /**
     * Sets up the virtual network interface. The key line is the single,
     * narrow addRoute() call — see the class-level comment for why that's
     * what keeps this a "DNS-only" VPN instead of routing all your traffic.
     */
    private fun buildVpnInterface(): ParcelFileDescriptor? {
        return try {
            val builder = Builder()
                .setSession(getString(R.string.app_name))
                .addAddress(VPN_INTERFACE_ADDRESS, VPN_INTERFACE_PREFIX_LENGTH)
                .addDnsServer(VPN_DNS_ADDRESS)
                .addRoute(VPN_DNS_ADDRESS, VPN_INTERFACE_PREFIX_LENGTH)
                .setMtu(1500)
                .setBlocking(true)
            AppExclusionStore.packages(this).forEach { packageName ->
                runCatching { builder.addDisallowedApplication(packageName) }
                    .onFailure { Log.w(LOG_TAG, "Could not exclude $packageName", it) }
            }
            builder.establish()
        } catch (error: Exception) {
            Log.e(LOG_TAG, "Failed to establish VPN interface", error)
            null
        }
    }

    /**
     * The main loop: reads one raw packet at a time from the VPN interface
     * (which, thanks to the narrow routing above, will only ever be DNS
     * queries) and hands each one off for handling.
     */
    private fun runPacketReadLoop(tunInterface: ParcelFileDescriptor) {
        val inputStream = FileInputStream(tunInterface.fileDescriptor)
        val outputStream = FileOutputStream(tunInterface.fileDescriptor)
        val readBuffer = ByteArray(MAX_PACKET_SIZE_BYTES)

        while (isServiceRunning.get()) {
            val bytesRead = try {
                inputStream.read(readBuffer)
            } catch (error: Exception) {
                if (isServiceRunning.get()) Log.w(LOG_TAG, "Error reading from VPN interface", error)
                break
            }
            if (bytesRead <= 0) continue

            val packetBytes = readBuffer.copyOfRange(0, bytesRead)
            try {
                handleIncomingPacket(packetBytes, outputStream)
            } catch (error: Exception) {
                // A single malformed/unexpected packet should never crash
                // the whole service — log it and keep processing others.
                Log.w(LOG_TAG, "Skipping a packet we couldn't handle", error)
            }
        }
    }

    private fun handleIncomingPacket(packetBytes: ByteArray, outputStream: FileOutputStream) {
        val parsedPacket = IpPacketLogic.parseIpv4UdpPacket(packetBytes)
        if (parsedPacket.destPort != DNS_PORT) return // not a DNS query; ignore

        val queriedDomain = DnsMessageLogic.extractQueriedDomain(parsedPacket.udpPayload)

        val adBlockingEnabled = getSharedPreferences("protection_options", MODE_PRIVATE)
            .getBoolean("ads", true)
        val isAllowedByUser = DomainRulesStore.matches(queriedDomain, DomainRulesStore.allowDomains(this))
        val isCustomBlocked = DomainRulesStore.matches(queriedDomain, DomainRulesStore.blockDomains(this))
        val category = when {
            !adBlockingEnabled || isAllowedByUser -> null
            isCustomBlocked -> BlockedDomainList.Category.ADS
            else -> BlockedDomainList.categorize(queriedDomain)
        }
        if (category != null) {
            Log.d(LOG_TAG, "Blocked ($category): $queriedDomain")
            if (getSharedPreferences("app_settings", MODE_PRIVATE).getBoolean("local_stats", true)) {
                BlockStatsStore.recordBlock(this, category)
                RecentBlocksStore.record(this, queriedDomain, category.name)
            }
            sendBroadcast(
                Intent(ACTION_DOMAIN_BLOCKED)
                    .putExtra("domain", queriedDomain)
                    .putExtra("category", category.name),
            )

            val blockedResponsePayload = DnsMessageLogic.buildBlockedResponse(parsedPacket.udpPayload)
            writeDnsResponseToVpn(parsedPacket, blockedResponsePayload, outputStream)
        } else {
            // Keep slow network I/O away from the TUN reader, but use a
            // small bounded pool rather than creating a thread per lookup.
            dnsExecutor.execute { forwardToRealDnsServer(parsedPacket, outputStream) }
        }
    }

    private fun forwardToRealDnsServer(
        parsedPacket: IpPacketLogic.ParsedPacket,
        outputStream: FileOutputStream,
    ) {
        var lastError: Exception? = null
        for (endpoint in upstreamDnsEndpoints()) {
            if (!isServiceRunning.get()) return
            try {
                DatagramSocket().use { forwardingSocket ->
                    // Exempt the forwarding socket from our VPN, otherwise
                    // its query would loop back into the TUN interface.
                    check(protect(forwardingSocket)) { "Could not protect upstream DNS socket" }
                    forwardingSocket.soTimeout = UPSTREAM_RESPONSE_TIMEOUT_MS
                    // A router/carrier DNS address is generally reachable
                    // only through the network that supplied it. Binding
                    // avoids sending Wi-Fi DNS over mobile data or vice versa.
                    endpoint.network?.bindSocket(forwardingSocket)
                    forwardingSocket.connect(InetSocketAddress(endpoint.address, DNS_PORT))
                    forwardingSocket.send(DatagramPacket(parsedPacket.udpPayload, parsedPacket.udpPayload.size))

                    val responseBuffer = ByteArray(MAX_PACKET_SIZE_BYTES)
                    val responseDatagram = DatagramPacket(responseBuffer, responseBuffer.size)
                    forwardingSocket.receive(responseDatagram)

                    val realResponsePayload = responseDatagram.data.copyOfRange(0, responseDatagram.length)
                    writeDnsResponseToVpn(parsedPacket, realResponsePayload, outputStream)
                    return
                }
            } catch (error: Exception) {
                lastError = error
                Log.d(LOG_TAG, "DNS server ${endpoint.address.hostAddress} did not answer; trying next", error)
            }
        }
        Log.w(LOG_TAG, "All upstream DNS servers failed", lastError)
        // Force the next lookup to rediscover the physical network. This is
        // important when Wi-Fi/mobile changed during the current request.
        // Protection stays on regardless — only the user's own Stop tap
        // should ever turn it off, never a transient DNS failure.
        lastDnsEndpointRefreshElapsedMs = 0L
    }

    /** Prefer DNS captured from the physical network before the VPN became
     * active. Public DNS is only a fallback. */
    private fun upstreamDnsEndpoints() = buildList {
        // Refresh periodically rather than making several system-service
        // calls for every DNS packet. A failure above invalidates this cache.
        val now = SystemClock.elapsedRealtime()
        if (now - lastDnsEndpointRefreshElapsedMs >= DNS_ENDPOINT_REFRESH_INTERVAL_MS) {
            synchronized(this@AdBlockVpnService) {
                if (now - lastDnsEndpointRefreshElapsedMs >= DNS_ENDPOINT_REFRESH_INTERVAL_MS) {
                    val refreshedEndpoints = readPhysicalDnsEndpoints()
                    if (refreshedEndpoints.isNotEmpty()) {
                        physicalDnsEndpoints = refreshedEndpoints
                    }
                    lastDnsEndpointRefreshElapsedMs = now
                }
            }
        }
        addAll(physicalDnsEndpoints)

        val preferredNetwork = firstOrNull()?.network
        FALLBACK_DNS_SERVERS
            .mapNotNull { address -> runCatching { InetAddress.getByName(address) }.getOrNull() }
            .map { address -> DnsEndpoint(preferredNetwork, address) }
            .forEach { if (it !in this) add(it) }
    }

    private fun readPhysicalDnsEndpoints(): List<DnsEndpoint> =
        runCatching {
            val connectivityManager = getSystemService(ConnectivityManager::class.java)
            connectivityManager.allNetworks
                .filter { network ->
                    val capabilities = connectivityManager.getNetworkCapabilities(network)
                    capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                        !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                }
                .sortedByDescending { network ->
                    connectivityManager.getNetworkCapabilities(network)
                        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                }
                .flatMap { network ->
                    connectivityManager.getLinkProperties(network)?.dnsServers.orEmpty()
                        .filterNot { it.hostAddress == VPN_DNS_ADDRESS }
                        .map { address -> DnsEndpoint(network, address) }
                }
                .distinct()
        }.getOrElse { error ->
            Log.w(LOG_TAG, "Could not read physical network DNS servers; using fallbacks", error)
            emptyList()
        }

    /** Wraps a DNS response payload back into an IP/UDP packet addressed to
     * whoever originally asked, and writes it to the VPN interface. */
    private fun writeDnsResponseToVpn(
        originalQuery: IpPacketLogic.ParsedPacket,
        dnsResponsePayload: ByteArray,
        outputStream: FileOutputStream,
    ) {
        // Source and destination are SWAPPED relative to the query: the
        // reply comes FROM where the query was addressed TO, and goes TO
        // where the query came FROM.
        val responsePacket = IpPacketLogic.buildIpv4UdpPacket(
            sourceIp = originalQuery.destIp,
            destIp = originalQuery.sourceIp,
            sourcePort = originalQuery.destPort,
            destPort = originalQuery.sourcePort,
            payload = dnsResponsePayload,
        )
        synchronized(vpnWriteLock) {
            outputStream.write(responsePacket)
        }
    }
}
