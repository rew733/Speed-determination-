package com.rew733.netthrottlemvp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

class ThrottleVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunnelJob: Job? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var currentLimitKbps: Int = 150
    private val limiter = TokenBucketLimiter(initialBytesPerSecond = 150L * 1024L)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_RATE -> {
                val newKbps = intent.getIntExtra(EXTRA_SPEED_KBPS, currentLimitKbps)
                    .coerceIn(50, 2050)
                currentLimitKbps = newKbps
                serviceScope.launch {
                    limiter.setRateBytesPerSecond(newKbps.toLong() * 1024L)
                }
                if (vpnInterface != null) {
                    startForegroundNotification(newKbps)
                }
                return START_STICKY
            }
            ACTION_START, null -> {
                val targetPackages = intent?.getStringArrayListExtra(EXTRA_PACKAGES)
                    ?.filter { it.isNotBlank() && it != packageName }
                    ?.distinct()
                    ?: emptyList()

                val speedKbps = intent?.getIntExtra(EXTRA_SPEED_KBPS, 150)
                    ?.coerceIn(50, 2050)
                    ?: 150

                if (targetPackages.isEmpty()) {
                    stopTunnel()
                    stopSelf()
                    return START_NOT_STICKY
                }

                currentLimitKbps = speedKbps
                serviceScope.launch {
                    limiter.setRateBytesPerSecond(speedKbps.toLong() * 1024L)
                }

                startForegroundNotification(speedKbps)
                startTunnel(targetPackages)
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(targetPackages: List<String>) {
        stopTunnel()

        val builder = Builder()
            .setSession("NetThrottleMVP")
            .setMtu(1400)
            .addAddress("10.66.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")

        var addedAny = false
        for (pkg in targetPackages) {
            try {
                builder.addAllowedApplication(pkg)
                addedAny = true
            } catch (e: Exception) {
                Log.w(TAG, "Failed adding allowed app: $pkg", e)
            }
        }

        if (!addedAny) {
            stopSelf()
            return
        }

        val pfd = try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "Failed establishing VPN interface", e)
            null
        }

        if (pfd == null) {
            stopSelf()
            return
        }

        vpnInterface = pfd

        tunnelJob = serviceScope.launch {
            runPacketRelay(pfd)
        }
    }

    private suspend fun runPacketRelay(pfd: ParcelFileDescriptor) {
        val relaySocket = DatagramSocket().apply {
            soTimeout = 250
        }
        protect(relaySocket)

        val natTable = ConcurrentHashMap<String, InetSocketAddress>()
        val inputStream = FileInputStream(pfd.fileDescriptor)
        val outputStream = FileOutputStream(pfd.fileDescriptor)
        val packetBuffer = ByteArray(1500)

        val downstreamJob = launch {
            val recvBuf = ByteArray(1500)
            val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
            while (isActive && !relaySocket.isClosed) {
                try {
                    relaySocket.receive(recvPacket)
                    val len = recvPacket.length
                    if (len <= 0) continue

                    val senderAddr = recvPacket.address ?: continue
                    val senderPort = recvPacket.port
                    val key = "${senderAddr.hostAddress}:$senderPort"
                    val clientOrigin = natTable[key] ?: continue

                    limiter.acquire(len)
                    val replyIpv4 = buildIpv4UdpPacket(
                        srcIp = senderAddr.address,
                        srcPort = senderPort,
                        dstIp = clientOrigin.address.address,
                        dstPort = clientOrigin.port,
                        payload = recvBuf,
                        payloadLen = len
                    )
                    if (replyIpv4 != null) {
                        synchronized(outputStream) {
                            outputStream.write(replyIpv4)
                        }
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // Normal poll timeout
                } catch (e: Exception) {
                    if (isActive) {
                        Log.d(TAG, "Downstream read stopped: ${e.message}")
                    }
                    break
                }
            }
        }

        try {
            while (isActive) {
                val available = try {
                    inputStream.available()
                } catch (_: Exception) {
                    0
                }
                if (available <= 0) {
                    kotlinx.coroutines.delay(10L)
                    continue
                }

                val readBytes = inputStream.read(packetBuffer)
                if (readBytes <= 28) continue

                val versionAndIhl = packetBuffer[0].toInt() and 0xFF
                val version = versionAndIhl ushr 4
                val ihl = (versionAndIhl and 0x0F) * 4
                if (version != 4 || ihl < 20 || readBytes < ihl + 8) continue

                val protocol = packetBuffer[9].toInt() and 0xFF
                if (protocol != 17) continue

                val srcIp = packetBuffer.copyOfRange(12, 16)
                val dstIp = packetBuffer.copyOfRange(16, 20)

                val srcPort = ((packetBuffer[ihl].toInt() and 0xFF) shl 8) or
                    (packetBuffer[ihl + 1].toInt() and 0xFF)
                val dstPort = ((packetBuffer[ihl + 2].toInt() and 0xFF) shl 8) or
                    (packetBuffer[ihl + 3].toInt() and 0xFF)
                val udpLen = ((packetBuffer[ihl + 4].toInt() and 0xFF) shl 8) or
                    (packetBuffer[ihl + 5].toInt() and 0xFF)

                val payloadOffset = ihl + 8
                val payloadLen = (udpLen - 8).coerceAtMost(readBytes - payloadOffset)
                if (payloadLen <= 0 || payloadOffset + payloadLen > readBytes) continue

                limiter.acquire(payloadLen)

                val destAddress = InetAddress.getByAddress(dstIp)
                val clientOrigin = InetSocketAddress(InetAddress.getByAddress(srcIp), srcPort)
                natTable["${destAddress.hostAddress}:$dstPort"] = clientOrigin

                val outPacket = DatagramPacket(
                    packetBuffer,
                    payloadOffset,
                    payloadLen,
                    destAddress,
                    dstPort
                )
                relaySocket.send(outPacket)
            }
        } catch (e: Exception) {
            if (isActive) {
                Log.d(TAG, "Upstream loop ended: ${e.message}")
            }
        } finally {
            downstreamJob.cancel()
            try {
                relaySocket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun buildIpv4UdpPacket(
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
        payload: ByteArray,
        payloadLen: Int
    ): ByteArray? {
        if (srcIp.size != 4 || dstIp.size != 4 || payloadLen <= 0) return null
        val ipHeaderLen = 20
        val udpHeaderLen = 8
        val totalLen = ipHeaderLen + udpHeaderLen + payloadLen
        if (totalLen > 1500) return null

        val buf = ByteBuffer.allocate(totalLen)
        buf.put(0x45.toByte())
        buf.put(0x00.toByte())
        buf.putShort(totalLen.toShort())
        buf.putShort(0x1234.toShort())
        buf.putShort(0x4000.toShort())
        buf.put(64.toByte())
        buf.put(17.toByte())
        buf.putShort(0)
        buf.put(srcIp)
        buf.put(dstIp)

        val ipChecksum = computeIpv4Checksum(buf.array(), 0, ipHeaderLen)
        buf.putShort(10, ipChecksum.toShort())

        val udpTotalLen = udpHeaderLen + payloadLen
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putShort(udpTotalLen.toShort())
        buf.putShort(0)
        buf.put(payload, 0, payloadLen)

        return buf.array()
    }

    private fun computeIpv4Checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i < end - 1) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += ((data[i].toInt() and 0xFF) shl 8)
        }
        while (sum ushr 16 != 0L) {
            sum = (sum and 0xFFFFL) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFFL).toInt()
    }

    private fun stopTunnel() {
        tunnelJob?.cancel()
        tunnelJob = null
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null
    }

    private fun startForegroundNotification(speedKbps: Int) {
        val channelId = "net_throttle_mvp_channel"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text_format, speedKbps))
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        stopTunnel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ThrottleVpnService"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.rew733.netthrottlemvp.action.START"
        const val ACTION_STOP = "com.rew733.netthrottlemvp.action.STOP"
        const val ACTION_UPDATE_RATE = "com.rew733.netthrottlemvp.action.UPDATE_RATE"
        const val EXTRA_PACKAGES = "com.rew733.netthrottlemvp.extra.PACKAGES"
        const val EXTRA_SPEED_KBPS = "com.rew733.netthrottlemvp.extra.SPEED_KBPS"
    }
}
