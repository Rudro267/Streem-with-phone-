package com.example.transport

import android.content.Context
import android.hardware.usb.UsbManager
import com.example.model.UsbProbeResult
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object UsbDiscovery {
    private val usbInterfacePrefixes = listOf("rndis", "usb", "ncm", "eth")

    fun probe(context: Context): UsbProbeResult {
        var tetherName: String? = null
        var localIp: String? = null
        val candidates = linkedSetOf<String>()

        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                val matchesUsb = usbInterfacePrefixes.any { name.startsWith(it) }
                if (!matchesUsb) continue

                val addrs = Collections.list(iface.inetAddresses)
                for (addr in addrs) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        tetherName = iface.name
                        localIp = ip
                        val parts = ip.split(".")
                        if (parts.size == 4) {
                            val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"
                            candidates.add("$prefix.1")
                            candidates.add("$prefix.129")
                            candidates.add("$prefix.2")
                            // Android default RNDIS tether subnets
                            candidates.add("192.168.42.129")
                            candidates.add("192.168.42.1")
                            candidates.remove(ip)
                        }
                        break
                    }
                }
                if (tetherName != null) break
            }
        } catch (_: Exception) {
            // Network interface enumeration may fail transiently during USB switching
        }

        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        val accessories = try {
            usbManager?.accessoryList
        } catch (_: Exception) {
            null
        }
        val devicesCount = try {
            usbManager?.deviceList?.size ?: 0
        } catch (_: Exception) {
            0
        }

        return UsbProbeResult(
            hasTetherInterface = tetherName != null && localIp != null,
            interfaceName = tetherName,
            localIp = localIp,
            candidatePeerIps = candidates.toList(),
            attachedAccessory = accessories?.firstOrNull(),
            attachedUsbDevicesCount = devicesCount
        )
    }

    fun computeBroadcastIp(localIp: String?): String {
        if (localIp == null) return "255.255.255.255"
        val parts = localIp.split(".")
        return if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}.255" else "255.255.255.255"
    }
}
