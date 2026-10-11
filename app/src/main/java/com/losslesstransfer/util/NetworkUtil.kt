package com.losslesstransfer.util

import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

object NetworkUtil {

    /**
     * 获取本机局域网 IPv4。
     * 通过 UDP 连接 8.8.8.8 让系统选定出口网卡，失败则回退遍历网卡。
     */
    fun getLanIp(): String {
        return try {
            DatagramSocket().use { socket ->
                socket.connect(InetAddress.getByName("8.8.8.8"), 80)
                val addr = socket.localAddress
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    addr.hostAddress
                } else {
                    fallbackFromInterfaces()
                }
            }
        } catch (e: Exception) {
            fallbackFromInterfaces()
        }
    }

    private fun fallbackFromInterfaces(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (nif.isLoopback || !nif.isUp) continue
                val addresses = nif.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
            "127.0.0.1"
        } catch (e: Exception) {
            "127.0.0.1"
        }
    }
}
