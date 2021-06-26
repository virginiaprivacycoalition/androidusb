package com.virginiapriacy.androidusb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import com.virginiaprivacy.drivers.sdr.usb.UsbIFace
import java.io.IOException
import java.nio.ByteBuffer

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

    private val transferRequests = mutableMapOf<Int, UsbRequest>()

    override val productName: String
        get() = device.productName ?: ""
    override val serialNumber: String
        get() = device.serialNumber ?: ""
    override val manufacturerName: String
        get() = device.manufacturerName ?: ""

    private val bufferArray: MutableMap<Int, Pair<UsbRequest, ByteBuffer>> = mutableMapOf()

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

    override fun prepareNewBulkTransfer(transferIndex: Int, byteBuffer: ByteBuffer) {
        val request = UsbRequest()
        request.clientData = transferIndex
        request.initialize(connection, endpoint)
        bufferArray[transferIndex] = request to byteBuffer
    }

    override fun submitBulkTransfer(transferIndex: Int) {
        if (bufferArray.containsKey(transferIndex)) {
            bufferArray[transferIndex]?.run {
                first.queue(second)
            }
        } else {
            throw NullPointerException("Invalid transfer index of $transferIndex." +
                    " Use a valid index: ${bufferArray.keys}")
        }
    }

    override fun waitForTransferResult(): ByteBuffer {
        val request = connection.requestWait(300)
        if (connection == null || request == null) {
            throw IOException("Could not get a request that was enqueued by this interface.")
        }
        if (request.clientData is Int && request.clientData != null) {
            bufferArray[request.clientData as Int]?.let {
                return it.second
            }
        }
        throw IOException("Could not get a request that was enqueued by this interface.")

    }

    override fun claimInterface() {
        connection.claimInterface(usbInterface, true)
    }

    override fun releaseUsbDevice() {
        transferRequests.values.forEach {
            it.close()
        }
        connection.releaseInterface(usbInterface)
        connection.close()
    }

    override fun shutdown() {
        transferRequests.values.forEach {
            it.cancel()
        }
        connection.releaseInterface(usbInterface)
        connection.close()
    }
}