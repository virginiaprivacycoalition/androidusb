package com.virginiapriacy.androidusb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import com.virginiaprivacy.drivers.sdr.usb.UsbIFace
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue

class AndroidUsbInterface(
    private val applicationContext: Context,
    private val device: UsbDevice
) : UsbIFace {

    private val manager = applicationContext.getSystemService(UsbManager::class.java)

    private val connection: UsbDeviceConnection by lazy {
        manager.openDevice(device)
    }

    private val usbInterface: android.hardware.usb.UsbInterface by lazy {

        val usbInterface1 = device.getInterface(0)
        for (i in 0 until device.interfaceCount) {
            println("$i: ${device.getInterface(i).name} ${device.getInterface(i).endpointCount} endpoints ")
        }

        usbInterface1
    }

    private val endpoint by lazy {
        val endpoint1 = usbInterface.getEndpoint(0)

        for (i in 0 until (usbInterface.endpointCount)) {
            println("Direction: ${usbInterface.getEndpoint(i).direction}")
        }
        endpoint1
    }

    private val requestQueue = ArrayBlockingQueue<UsbRequest>(12)

    override val productName: String
        get() = device.productName ?: ""
    override val serialNumber: String
        get() = device.serialNumber ?: ""
    override val manufacturerName: String
        get() = device.manufacturerName ?: ""

    override fun controlTransfer(
        direction: Int,
        requestID: Int,
        address: Int,
        index: Int,
        bytes: ByteArray,
        length: Int,
        timeout: Int
    ): Int {
        return connection.run {
            controlTransfer(direction, requestID, address, index, bytes, length, timeout)
        }
    }

    override suspend fun prepareNewBulkTransfer(transferIndex: Int, byteBuffer: ByteBuffer) {
        val request = UsbRequest()
        request.clientData = transferIndex
        request.initialize(connection, endpoint)
        requestQueue.put(request)
    }

    override suspend fun submitBulkTransfer(buffer: ByteBuffer) {
        val request = requestQueue.take()
        request.queue(buffer)
    }

    override suspend fun waitForTransferResult(): Int {
        val request = connection.requestWait(300)
        val i = request.clientData as Int
        requestQueue.put(request)
        return i

    }

    override fun claimInterface() {
        connection.claimInterface(usbInterface, true)
    }

    override fun releaseUsbDevice() {
        requestQueue.forEach {
            it.cancel()
            it.close()
        }
        connection.releaseInterface(usbInterface)
        connection.close()
    }

    override fun shutdown() {
        requestQueue.forEach {
            it.cancel()
            it.close()
        }
        connection.releaseInterface(usbInterface)
        connection.close()
    }
}