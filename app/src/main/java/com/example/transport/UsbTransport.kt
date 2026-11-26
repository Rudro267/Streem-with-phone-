package com.example.transport

import android.content.Context
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import com.example.model.ActiveTransportKind
import com.example.model.DeviceRole
import com.example.model.LinkStatus
import com.example.model.TransportPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

interface UsbTransport {
    val status: StateFlow<LinkStatus>
    val activeKind: StateFlow<ActiveTransportKind>
    val peerAddress: StateFlow<String>
    val incomingPackets: SharedFlow<StudioPacket>

    fun start(
        role: DeviceRole,
        preference: TransportPreference,
        manualPeerIp: String,
        onLog: (tag: String, message: String, isError: Boolean) -> Unit
    )

    fun sendPacket(type: PacketType, payload: ByteArray, flags: Byte = 0): Boolean
    fun sendPacketSlice(type: PacketType, payload: ByteArray, length: Int, flags: Byte = 0): Boolean
    fun stop()
}

class UnifiedUsbTransport(private val context: Context) : UsbTransport {

    companion object {
        const val STREAM_PORT = 49150
        const val DISCOVERY_PORT = 49152
        private const val DISCOVERY_MAGIC = "TALLYLINK_HERE"
        private const val DISCOVERY_REPLY_PREFIX = "TALLYLINK_HOST:"

        // Android Open Accessory constants
        private const val AOA_GET_PROTOCOL = 51
        private const val AOA_SEND_IDENT = 52
        private const val AOA_START_ACCESSORY = 53
        private const val AOA_MANUF = "TallyLink"
        private const val AOA_MODEL = "StudioBridge"
        private const val AOA_DESC = "TallyLink USB Stream Bridge"
        private const val AOA_VERSION = "1.0"
        private const val AOA_URI = "https://tallylink.studio"
        private const val AOA_SERIAL = "TL-001"
    }

    private val _status = MutableStateFlow(LinkStatus.DISCONNECTED)
    override val status: StateFlow<LinkStatus> = _status.asStateFlow()

    private val _activeKind = MutableStateFlow(ActiveTransportKind.NONE)
    override val activeKind: StateFlow<ActiveTransportKind> = _activeKind.asStateFlow()

    private val _peerAddress = MutableStateFlow("--")
    override val peerAddress: StateFlow<String> = _peerAddress.asStateFlow()

    private val _incomingPackets = MutableSharedFlow<StudioPacket>(extraBufferCapacity = 32)
    override val incomingPackets: SharedFlow<StudioPacket> = _incomingPackets.asSharedFlow()

    private var scope: CoroutineScope? = null
    private var transportJob: Job? = null

    private val writeLock = Any()
    @Volatile private var activeOutput: DataOutputStream? = null
    @Volatile private var activeSocket: Socket? = null
    @Volatile private var activeServerSocket: ServerSocket? = null
    @Volatile private var activePfd: ParcelFileDescriptor? = null
    @Volatile private var activeUsbConn: UsbDeviceConnection? = null
    @Volatile private var activeUsbOutEndpoint: UsbEndpoint? = null
    @Volatile private var activeUsbInEndpoint: UsbEndpoint? = null
    @Volatile private var activeUsbInterface: UsbInterface? = null

    private val sequenceCounter = AtomicInteger(0)

    override fun start(
        role: DeviceRole,
        preference: TransportPreference,
        manualPeerIp: String,
        onLog: (tag: String, message: String, isError: Boolean) -> Unit
    ) {
        stop()
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope

        transportJob = newScope.launch {
            _status.value = LinkStatus.PROBING
            onLog("USB", "Starting ${role.name} transport (${preference.label})", false)

            while (isActive) {
                val probe = UsbDiscovery.probe(context)
                val trySocket = preference != TransportPreference.USB_ACCESSORY &&
                    (probe.hasTetherInterface || manualPeerIp.isNotBlank() || preference == TransportPreference.USB_TETHER_SOCKET)
                val tryAccessory = preference != TransportPreference.USB_TETHER_SOCKET &&
                    (probe.attachedAccessory != null || probe.attachedUsbDevicesCount > 0 || preference == TransportPreference.USB_ACCESSORY)

                var connectedOnce = false

                if (trySocket) {
                    connectedOnce = runSocketTransportCycle(role, probe.localIp, probe.candidatePeerIps, manualPeerIp, onLog)
                }

                if (!connectedOnce && tryAccessory && isActive) {
                    val acc = probe.attachedAccessory
                    if (acc != null) {
                        connectedOnce = runAccessoryFdCycle(acc, onLog)
                    } else if (probe.attachedUsbDevicesCount > 0) {
                        connectedOnce = runUsbHostBulkCycle(onLog)
                    }
                }

                if (!isActive) break
                _status.value = LinkStatus.RECONNECTING
                _activeKind.value = ActiveTransportKind.NONE
                _peerAddress.value = "--"
                delay(1200)
            }
        }
    }

    private suspend fun runSocketTransportCycle(
        role: DeviceRole,
        localIp: String?,
        candidates: List<String>,
        manualPeerIp: String,
        onLog: (String, String, Boolean) -> Unit
    ): Boolean {
        return if (role == DeviceRole.HOST) {
            runHostSocketServer(localIp, onLog)
        } else {
            runDirectorSocketClient(localIp, candidates, manualPeerIp, onLog)
        }
    }

    private suspend fun runHostSocketServer(
        localIp: String?,
        onLog: (String, String, Boolean) -> Unit
    ): Boolean {
        var beaconJob: Job? = null
        return try {
            val server = ServerSocket(STREAM_PORT)
            server.reuseAddress = true
            server.soTimeout = 1500
            activeServerSocket = server

            // Launch UDP discovery responder so Director auto-finds Host IP over USB tether
            beaconJob = scope?.launch(Dispatchers.IO) {
                runUdpDiscoveryResponder(localIp)
            }

            _status.value = LinkStatus.HANDSHAKING
            _peerAddress.value = localIp?.let { "$it:$STREAM_PORT" } ?: "0.0.0.0:$STREAM_PORT"
            onLog("USB-TCP", "Host listening on ${_peerAddress.value}", false)

            var client: Socket? = null
            while (scope?.isActive == true && client == null) {
                try {
                    client = server.accept()
                } catch (_: java.net.SocketTimeoutException) {
                    // Check if job is still active
                }
            }
            if (client == null) return false

            client.tcpNoDelay = true
            client.trafficClass = 0x10
            activeSocket = client
            val peerIp = client.inetAddress?.hostAddress ?: "USB Peer"
            _peerAddress.value = peerIp
            _activeKind.value = ActiveTransportKind.USB_TETHER_SOCKET
            _status.value = LinkStatus.CONNECTED
            onLog("USB-TCP", "Director connected from $peerIp", false)

            val input = DataInputStream(BufferedInputStream(client.getInputStream(), 16384))
            val output = DataOutputStream(BufferedOutputStream(client.getOutputStream(), 16384))
            activeOutput = output

            readLoop(input, onLog)
            true
        } catch (e: Exception) {
            onLog("USB-TCP", "Host socket closed: ${e.message}", false)
            false
        } finally {
            beaconJob?.cancel()
            cleanupIo()
        }
    }

    private suspend fun runDirectorSocketClient(
        localIp: String?,
        candidates: List<String>,
        manualPeerIp: String,
        onLog: (String, String, Boolean) -> Unit
    ): Boolean {
        val discoveredIp = discoverHostViaUdp(localIp)
        val targets = buildList {
            if (manualPeerIp.isNotBlank()) add(manualPeerIp.trim())
            if (!discoveredIp.isNullOrBlank()) add(discoveredIp)
            addAll(candidates)
        }.distinct()

        if (targets.isEmpty()) return false

        _status.value = LinkStatus.HANDSHAKING
        for (targetIp in targets) {
            if (scope?.isActive != true) break
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(targetIp, STREAM_PORT), 900)
                socket.tcpNoDelay = true
                socket.trafficClass = 0x10
                activeSocket = socket
                _peerAddress.value = targetIp
                _activeKind.value = ActiveTransportKind.USB_TETHER_SOCKET
                _status.value = LinkStatus.CONNECTED
                onLog("USB-TCP", "Connected to Host $targetIp", false)

                val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 16384))
                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 16384))
                activeOutput = output

                readLoop(input, onLog)
                return true
            } catch (_: Exception) {
                cleanupIo()
            }
        }
        return false
    }

    private fun runUdpDiscoveryResponder(localIp: String?) {
        var udpSocket: DatagramSocket? = null
        try {
            udpSocket = DatagramSocket(DISCOVERY_PORT)
            udpSocket.soTimeout = 1000
            val buf = ByteArray(256)
            while (scope?.isActive == true) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    udpSocket.receive(pkt)
                    val msg = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                    if (msg == DISCOVERY_MAGIC) {
                        val replyIp = localIp ?: pkt.address.hostAddress ?: ""
                        val replyBytes = "$DISCOVERY_REPLY_PREFIX$replyIp".toByteArray(Charsets.UTF_8)
                        val replyPkt = DatagramPacket(replyBytes, replyBytes.size, pkt.address, pkt.port)
                        udpSocket.send(replyPkt)
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // loop
                }
            }
        } catch (_: Exception) {
            // port may be bound or interface changing
        } finally {
            try { udpSocket?.close() } catch (_: Exception) {}
        }
    }

    private fun discoverHostViaUdp(localIp: String?): String? {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            socket.broadcast = true
            socket.soTimeout = 550
            val probeBytes = DISCOVERY_MAGIC.toByteArray(Charsets.UTF_8)
            val broadcastAddr = InetAddress.getByName(UsbDiscovery.computeBroadcastIp(localIp))
            socket.send(DatagramPacket(probeBytes, probeBytes.size, broadcastAddr, DISCOVERY_PORT))

            val recvBuf = ByteArray(256)
            val recvPkt = DatagramPacket(recvBuf, recvBuf.size)
            socket.receive(recvPkt)
            val msg = String(recvPkt.data, 0, recvPkt.length, Charsets.UTF_8)
            if (msg.startsWith(DISCOVERY_REPLY_PREFIX)) {
                recvPkt.address.hostAddress
            } else null
        } catch (_: Exception) {
            null
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private suspend fun runAccessoryFdCycle(
        accessory: UsbAccessory,
        onLog: (String, String, Boolean) -> Unit
    ): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        if (!usbManager.hasPermission(accessory)) {
            onLog("USB-AOA", "Waiting for USB Accessory permission", false)
            return false
        }
        return try {
            val pfd = usbManager.openAccessory(accessory) ?: return false
            activePfd = pfd
            val input = DataInputStream(BufferedInputStream(FileInputStream(pfd.fileDescriptor), 16384))
            val output = DataOutputStream(BufferedOutputStream(FileOutputStream(pfd.fileDescriptor), 16384))
            activeOutput = output
            _activeKind.value = ActiveTransportKind.USB_ACCESSORY
            _peerAddress.value = "${accessory.manufacturer} ${accessory.model}"
            _status.value = LinkStatus.CONNECTED
            onLog("USB-AOA", "Connected via USB Accessory mode", false)
            readLoop(input, onLog)
            true
        } catch (e: Exception) {
            onLog("USB-AOA", "Accessory stream ended: ${e.message}", false)
            false
        } finally {
            cleanupIo()
        }
    }

    private suspend fun runUsbHostBulkCycle(onLog: (String, String, Boolean) -> Unit): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        val device = usbManager.deviceList.values.firstOrNull() ?: return false
        if (!usbManager.hasPermission(device)) {
            onLog("USB-HOST", "USB device attached; awaiting host permission", false)
            return false
        }
        val conn = usbManager.openDevice(device) ?: return false
        return try {
            // Attempt AOA protocol handshake if device isn't already in AOA PID (0x2D00/0x2D01)
            if (device.vendorId != 0x18D1 || (device.productId != 0x2D00 && device.productId != 0x2D01)) {
                initiateAoaHandshake(conn)
                conn.close()
                delay(600)
                return false
            }
            val iface = (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull()
                ?: return false
            if (!conn.claimInterface(iface, true)) return false

            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
                }
            }
            if (inEp == null || outEp == null) return false

            activeUsbConn = conn
            activeUsbInterface = iface
            activeUsbInEndpoint = inEp
            activeUsbOutEndpoint = outEp
            _activeKind.value = ActiveTransportKind.USB_ACCESSORY
            _peerAddress.value = device.productName ?: "USB AOA Device"
            _status.value = LinkStatus.CONNECTED
            onLog("USB-AOA", "Connected as USB Host to ${_peerAddress.value}", false)

            // Bulk read loop using piped stream into PacketCodec
            val pipeIn = java.io.PipedInputStream(32768)
            val pipeOut = java.io.PipedOutputStream(pipeIn)
            val readerJob = scope?.launch(Dispatchers.IO) {
                val buf = ByteArray(8192)
                try {
                    while (isActive && activeUsbConn != null) {
                        val read = conn.bulkTransfer(inEp, buf, buf.size, 250)
                        if (read > 0) {
                            pipeOut.write(buf, 0, read)
                            pipeOut.flush()
                        } else if (read < 0 && !isActive) {
                            break
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    try { pipeOut.close() } catch (_: Exception) {}
                }
            }
            readLoop(DataInputStream(pipeIn), onLog)
            readerJob?.cancel()
            true
        } catch (e: Exception) {
            onLog("USB-AOA", "USB Host bulk error: ${e.message}", true)
            false
        } finally {
            cleanupIo()
        }
    }

    private fun initiateAoaHandshake(conn: UsbDeviceConnection) {
        val protocolBuf = ByteArray(2)
        val protoLen = conn.controlTransfer(
            UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_VENDOR,
            AOA_GET_PROTOCOL,
            0, 0, protocolBuf, 2, 1000
        )
        if (protoLen < 2) return
        sendAoaString(conn, 0, AOA_MANUF)
        sendAoaString(conn, 1, AOA_MODEL)
        sendAoaString(conn, 2, AOA_DESC)
        sendAoaString(conn, 3, AOA_VERSION)
        sendAoaString(conn, 4, AOA_URI)
        sendAoaString(conn, 5, AOA_SERIAL)
        conn.controlTransfer(
            UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_VENDOR,
            AOA_START_ACCESSORY,
            0, 0, null, 0, 1000
        )
    }

    private fun sendAoaString(conn: UsbDeviceConnection, index: Int, value: String) {
        val bytes = (value + "\u0000").toByteArray(Charsets.UTF_8)
        conn.controlTransfer(
            UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_VENDOR,
            AOA_SEND_IDENT,
            0, index, bytes, bytes.size, 1000
        )
    }

    private suspend fun readLoop(input: DataInputStream, onLog: (String, String, Boolean) -> Unit) {
        try {
            while (scope?.isActive == true) {
                val packet = PacketCodec.readPacket(input)
                _incomingPackets.emit(packet)
            }
        } catch (e: IOException) {
            onLog("USB", "Stream disconnected (${e.message ?: "EOF"})", false)
        }
    }

    override fun sendPacket(type: PacketType, payload: ByteArray, flags: Byte): Boolean =
        sendPacketSlice(type, payload, payload.size, flags)

    override fun sendPacketSlice(type: PacketType, payload: ByteArray, length: Int, flags: Byte): Boolean {
        val seq = sequenceCounter.incrementAndGet()
        val tsUs = System.nanoTime() / 1000L
        synchronized(writeLock) {
            val out = activeOutput
            if (out != null) {
                return try {
                    PacketCodec.writePacketDirect(out, type, flags, seq, tsUs, payload, length)
                    true
                } catch (_: IOException) {
                    false
                }
            }
            val usbConn = activeUsbConn
            val outEp = activeUsbOutEndpoint
            if (usbConn != null && outEp != null) {
                return try {
                    val baos = java.io.ByteArrayOutputStream(20 + length)
                    PacketCodec.writePacketDirect(DataOutputStream(baos), type, flags, seq, tsUs, payload, length)
                    val frame = baos.toByteArray()
                    val written = usbConn.bulkTransfer(outEp, frame, frame.size, 500)
                    written >= 0
                } catch (_: Exception) {
                    false
                }
            }
        }
        return false
    }

    private fun cleanupIo() {
        synchronized(writeLock) {
            try { activeOutput?.close() } catch (_: Exception) {}
            activeOutput = null
            try { activeSocket?.close() } catch (_: Exception) {}
            activeSocket = null
            try { activeServerSocket?.close() } catch (_: Exception) {}
            activeServerSocket = null
            try { activePfd?.close() } catch (_: Exception) {}
            activePfd = null
            try {
                activeUsbInterface?.let { activeUsbConn?.releaseInterface(it) }
                activeUsbConn?.close()
            } catch (_: Exception) {}
            activeUsbConn = null
            activeUsbInterface = null
            activeUsbInEndpoint = null
            activeUsbOutEndpoint = null
        }
    }

    override fun stop() {
        transportJob?.cancel()
        transportJob = null
        cleanupIo()
        scope?.cancel()
        scope = null
        _status.value = LinkStatus.DISCONNECTED
        _activeKind.value = ActiveTransportKind.NONE
        _peerAddress.value = "--"
    }
}
