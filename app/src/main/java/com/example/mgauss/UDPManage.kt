package com.example.mgauss

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.UUID

class UDPManager(context: Context, private val onAlertReceived: (String) -> Unit) {

    private val PORT = 8888
    private var socket: MulticastSocket? = null
    private var isListening = false
    val myUUID: String = UUID.randomUUID().toString()
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val multicastLock = wifiManager.createMulticastLock("MgaussLock")

    fun startListening() {
        if (isListening) return
        isListening = true

        try {
            multicastLock.setReferenceCounted(true)
            multicastLock.acquire()
        } catch (e: Exception) {
        }

        CoroutineScope(Dispatchers.IO).launch {
            var buffer: ByteArray
            var packet: DatagramPacket
            var data: String

            try {
                socket = MulticastSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), PORT))
                }

                buffer = ByteArray(1024)
                while (isListening) {
                    packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)

                    data = String(packet.data, 0, packet.length)

                    handleIncomingPacket(data)
                }
            } catch (e: Exception) {
                isListening = false
            }
        }
    }

    fun stop() {
        isListening = false
        try {
            if (multicastLock.isHeld) multicastLock.release()
        } catch (e: Exception) {}
        socket?.close()
        socket = null
    }

    fun sendAlert() {
        CoroutineScope(Dispatchers.IO).launch {
            var json: JSONObject
            var data: ByteArray
            var targetAddress: InetAddress
            var sendSocket: java.net.DatagramSocket
            var packet: DatagramPacket

            try {
                json = JSONObject()
                json.put("uuid", myUUID)
                json.put("event", "ALERT")
                json.put("timestamp", System.currentTimeMillis())

                data = json.toString().toByteArray()
                targetAddress = getBroadcastAddress()

                sendSocket = java.net.DatagramSocket()
                sendSocket.broadcast = true

                packet = DatagramPacket(data, data.size, targetAddress, PORT)
                repeat(3) { sendSocket.send(packet) }
                sendSocket.close()


            } catch (e: Exception) {
            }
        }
    }

    private fun handleIncomingPacket(jsonString: String) {
        var json: JSONObject
        var senderUUID: String

        try {
            json = JSONObject(jsonString)
            senderUUID = json.optString("uuid")

            if (senderUUID == myUUID) return

            Handler(Looper.getMainLooper()).post {
                onAlertReceived(senderUUID)
            }
        } catch (e: Exception) {
        }
    }

    private fun getBroadcastAddress(): InetAddress {
        var interfaces: java.util.Enumeration<NetworkInterface>
        var networkInterface: NetworkInterface
        var broadcast: InetAddress?

        try {
            interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                for (interfaceAddress in networkInterface.interfaceAddresses) {
                    broadcast = interfaceAddress.broadcast
                    if (broadcast != null) return broadcast
                }
            }
        } catch (e: Exception) {
        }
        return InetAddress.getByName("255.255.255.255")
    }
}