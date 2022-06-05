package com.virginiapriacy.androidusb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.Parcel
import android.os.Parcelable
import com.virginiaprivacy.sdr.adapters.ByteToFloatSampleAdapter
import com.virginiaprivacy.sdr.exceptions.DeviceException
import com.virginiaprivacy.sdr.exceptions.UsbException
import com.virginiaprivacy.sdr.sample.SampleRate
import com.virginiaprivacy.sdr.tuner.*
import com.virginiaprivacy.sdr.usb.UsbController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.isActive
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.LinkedTransferQueue
import kotlin.reflect.KClass

class AndroidUsbController(
    private val applicationContext: Context,
    private val bufferCount: Int = 15, private val bufferSize: Int = 131072
) : UsbController() {
    val productID: Int = 2838
    val vendorID: Short = 0x0bda

    public constructor(context: Context, device: UsbDevice) : this(context) {
        this.device = device
    }

    override lateinit var controller: RTL2832TunerController

    private lateinit var tunerClass: KClass<Any>

    var tunerType: TunerType? = null

    val manufacturer by lazy { controller.descriptor.vendorLabel }

    val product by lazy { controller.descriptor.productLabel }

    private lateinit var device: UsbDevice

    private val ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION"

    private val manager = applicationContext.getSystemService(UsbManager::class.java)

    private lateinit var connection: UsbDeviceConnection

    private val usbInterface: android.hardware.usb.UsbInterface by lazy {
        device.getInterface(0)
    }

    private val endpoint by lazy {
        usbInterface.getEndpoint(0)
    }

    private val buffers = mutableListOf<ByteBuffer>()

    private val requestQueue by lazy {
        LinkedTransferQueue<UsbRequest>(List(buffers.size) { index ->
            UsbRequest().apply {
                initialize(connection, endpoint)
                clientData = index
            }
        })
    }

    private val filledBuffers = LinkedTransferQueue<ByteArray>()

    private val scope = CoroutineScope(Executors.newFixedThreadPool(1).asCoroutineDispatcher())

    private val sampleAdapter = ByteToFloatSampleAdapter()

    init {
        synchronized(this) {
            if (this::device.isInitialized)  {
                controller = RTL2832TunerController.getTunerController(this)
                tunerType = controller.tunerType
            } else {
                findDevice()
                controller = RTL2832TunerController.getTunerController(this)
                tunerType = controller.tunerType
            }
        }

    }

    override fun start(): ReceiveChannel<FloatArray> {
        val bo = usbInterface.getEndpoint(UsbConstants.USB_ENDPOINT_XFER_BULK and UsbConstants.USB_DIR_IN)
        val requests = mutableListOf<UsbRequest>()
        try {
            controller.setSampleRate(SampleRate.RATE_2_400MHZ)
            controller.resetUSBBuffer()
        } catch (e: Throwable) {
            throw e
        }
        buffers.clear()
        for (i in 0..bufferCount) {
            buffers.add(ByteBuffer.allocateDirect(bufferSize))
        }
        buffers.forEachIndexed { index, byteBuffer ->
            requests.add(UsbRequest()
                .apply {
                    initialize(connection, bo)
                    clientData = index
                    queue(byteBuffer)
                })
        }
        return scope.produce {
            invokeOnClose {
                requests.forEach {
                    it.cancel()
                    it.close()
                }
            }
            while (isActive) {
                val request = connection.requestWait()
                val buf = buffers[request.clientData as Int]
                val out = ByteArray(buf.position())
                buf.rewind()
                buf[out]
                buf.rewind()
                request.queue(buf)
                send(sampleAdapter.convert(out))
            }

        }
    }

    private fun findDevice() {
        device = manager.deviceList.toList().first { it.second.productName?.contains("RTL", true) ?: false }.second ?: throw UsbException("No USB device found. make sure device is connected. device list: ${manager.deviceList}")
    }

    private fun identifyTunerType() {
        val type = RTL2832TunerController.identifyTunerType(this)
        claimInterface(0)
        when (type) {
            is R820TTunerType -> {
                controller = R820TTunerController(this)
                tunerType = TunerType.RAFAELMICRO_R820T
            }
            is TunerType.ELONICS_E4000 -> {
                controller = E4KTunerController(this)
                tunerType = TunerType.ELONICS_E4000
            }
            else -> {
                throw UsbException("No valid USB tuner devices found.")
            }
        }
    }


    override fun stop() {
        scope.cancel()
    }

    override var deviceOpened: Boolean = false


    override fun claimInterface(interfaceNumber: Int): Int {
       return if (connection.claimInterface(device.getInterface(interfaceNumber), true)) 0 else 1
    }

    override fun close() {
        connection.close()
    }

    override fun detachKernelDriver(interfaceNumber: Int) {
        /**
         * On Android the [UsbDeviceConnection.claimInterface] function does this, so we just do nothing
         */
    }

    override fun getErrorMessage(errorCode: Int): String {
        return "android usb error"
    }

    override fun handleEventsTimeout(): Int {
        return 0
    }

    override fun kernelDriverActive(interfaceNumber: Int): Boolean {
        return false
    }

    override fun open() {
        connection = manager.openDevice(device) ?: throw UsbException("Could not open device.")
        deviceOpened = true
    }

    override fun read(address: Short, index: Short, buffer: ByteBuffer): Int {
        val buf = ByteArray(buffer.capacity())
        val r = connection.controlTransfer(-64, 0, address.toInt(), index.toInt(), buf, buf.size, 250)
        buffer.put(buf)
        buffer.rewind()
        return r
    }

    override fun release(interfaceNumber: Int): Int {
        connection.releaseInterface(usbInterface)
        return 0
    }

    override fun releaseInterface(interfaceNumber: Int): Int {
        connection.releaseInterface(usbInterface)
        return 0
    }

    override fun resetDevice() {
    }

    override fun write(value: Short, index: Short, buffer: ByteBuffer): Int {
        connection.let {
            buffer.rewind()
            val buf = ByteArray(buffer.capacity())
            buffer[buf]
            buffer.rewind()
            return it.controlTransfer(64, 0, value.toInt(), index.toInt(), buf, buf.size, 250)
        }
    }


}