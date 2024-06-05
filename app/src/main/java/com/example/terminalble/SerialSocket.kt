package com.example.terminalble

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.IOException
import java.security.InvalidParameterException
import java.util.Arrays
import java.util.UUID
import kotlin.math.min

@SuppressLint("MissingPermission") // various BluetoothGatt, BluetoothDevice methods
class SerialSocket(private var context: Context, private var device: BluetoothDevice?) : BluetoothGattCallback() {

    private open class DeviceDelegate {
        open fun connectCharacteristics(service: BluetoothGattService): Boolean = true
        open fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {}
        open fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {}
        open fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {}
        open fun canWrite(): Boolean {
            return true
        }
        open fun disconnect() {}
    }

    companion object {
        private val BLUETOOTH_LE_CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val BLUETOOTH_LE_NRF_SERVICE = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private val BLUETOOTH_LE_NRF_CHAR_RW2 = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        private val BLUETOOTH_LE_NRF_CHAR_RW3 = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        private const val MAX_MTU = 512
        private const val DEFAULT_MTU = 23
        private const val TAG = "SerialSocket"
    }

    private var writeBuffer = ArrayList<ByteArray>()
    private val pairingIntentFilter: IntentFilter
    private val pairingBroadcastReceiver: BroadcastReceiver
    private val disconnectBroadcastReceiver: BroadcastReceiver

    private var listener: SerialListener? = null
    private var delegate: DeviceDelegate? = null
    private var gatt: BluetoothGatt? = null
    private var readCharacteristic: BluetoothGattCharacteristic? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    private var writePending = false
    private var canceled = false
    private var connected = false
    private var payloadSize = DEFAULT_MTU - 3

    init {
        if (context is Activity) throw InvalidParameterException("expected non UI context")
        this.context = context
        this.device = device
        writeBuffer = java.util.ArrayList()
        pairingIntentFilter = IntentFilter()
        pairingIntentFilter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        pairingIntentFilter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
        pairingBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                onPairingBroadcastReceive(context, intent)
            }
        }
        disconnectBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (listener != null) listener!!.onSerialIoError(IOException("background disconnect"))
                disconnect() // disconnect now, else would be queued until UI re-attached
            }
        }
    }

    fun getName(): String = device?.name ?: device?.address ?: "Unknown"

     fun disconnect() {
        Log.d(TAG, "disconnect")
        listener = null
        device = null
        canceled = true
        synchronized(writeBuffer) {
            writePending = false
            writeBuffer.clear()
        }
        readCharacteristic = null
        writeCharacteristic = null
        delegate?.disconnect()
        gatt?.let {
            Log.d(TAG, "gatt.disconnect")
            it.disconnect()
            Log.d(TAG, "gatt.close")
            try {
                it.close()
            } catch (ignored: Exception) {
            }
            gatt = null
            connected = false
        }
        try {
            context.unregisterReceiver(pairingBroadcastReceiver)
        } catch (ignored: Exception) {
        }
        try {
            context.unregisterReceiver(disconnectBroadcastReceiver)
        } catch (ignored: Exception) {
        }
    }

    @Throws(IOException::class)
    fun connect(listener: SerialListener) {
        if (connected || gatt != null) throw IOException("already connected")
        canceled = false
        this.listener = listener
        ContextCompat.registerReceiver(
            context,
            disconnectBroadcastReceiver,
            IntentFilter(Constants.INTENT_ACTION_DISCONNECT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        Log.d(TAG, "connect $device")
        context.registerReceiver(pairingBroadcastReceiver, pairingIntentFilter)
        if (Build.VERSION.SDK_INT < 23) {
            Log.d(TAG, "connectGatt")
            gatt = device!!.connectGatt(context, false, this)
        } else {
            Log.d(TAG, "connectGatt,LE")
            gatt = device!!.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE)
        }
        if (gatt == null) throw IOException("connectGatt failed")
    }

    private fun onPairingBroadcastReceive(context: Context, intent: Intent) {
        // for ARM Mbed, Microbit, ... use pairing from Android bluetooth settings
        // for HM10-clone, ... pairing is initiated here
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        if (device == null || device != this.device) return
        when (intent.action) {
            BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                val pairingVariant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
                Log.d(TAG, "pairing request $pairingVariant")
                onSerialConnectError(IOException(context.getString(R.string.pairing_request)))
            }

            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                val previousBondState =
                    intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
                Log.d(TAG, "bond state $previousBondState->$bondState")
            }

            else -> Log.d(TAG, "unknown broadcast " + intent.action)
        }
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        // status directly taken from gat_api.h, e.g. 133=0x85=GATT_ERROR ~= timeout
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            Log.d(TAG, "connect status $status, discoverServices")
            if (!gatt.discoverServices()) onSerialConnectError(IOException("discoverServices failed"))
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            if (connected) onSerialIoError(IOException("gatt status $status"))
            else onSerialConnectError(IOException("gatt status $status"))
        } else {
            Log.d(TAG, "unknown connect state $newState $status")
        }

    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        Log.d(TAG, "servicesDiscovered, status $status")
        if (canceled) return
        connectCharacteristics1(gatt)
    }

    private fun connectCharacteristics1(gatt: BluetoothGatt) {
        var sync = true
        writePending = false
        for (gattService in gatt.services) {
            if (gattService.uuid == BLUETOOTH_LE_NRF_SERVICE) delegate = NrfDelegate()

            if (delegate != null) {
                sync = delegate!!.connectCharacteristics(gattService)
                break
            }
        }
        if (canceled) return
        if (delegate == null || readCharacteristic == null || writeCharacteristic == null) {
            for (gattService in gatt.services) {
                Log.d(TAG, "service " + gattService.uuid)
                for (characteristic in gattService.characteristics) Log.d(
                    TAG,
                    "characteristic " + characteristic.uuid
                )
            }
            onSerialConnectError(IOException("no serial profile found"))
            return
        }
        if (sync) connectCharacteristics2(gatt)
    }

    private fun connectCharacteristics2(gatt: BluetoothGatt) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Log.d(TAG, "request MTU")
            if (!gatt.requestMtu(MAX_MTU)) {
                Log.d(TAG, "request MTU failed")
                connectCharacteristics3(gatt, DEFAULT_MTU)
            }
        } else {
            Log.d(TAG, "no MTU support")
            connectCharacteristics3(gatt, DEFAULT_MTU)
        }
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        Log.d(TAG, "mtu size $mtu, status=$status")
        if (status == BluetoothGatt.GATT_SUCCESS) {
            payloadSize = mtu - 3
            Log.d(TAG, "payload size $payloadSize")
        }
        connectCharacteristics3(gatt, DEFAULT_MTU)
    }

    private fun connectCharacteristics3(gatt: BluetoothGatt, payloadSize: Int) {
        val writeProperties = writeCharacteristic!!.properties
        if ((writeProperties and (BluetoothGattCharacteristic.PROPERTY_WRITE +  // Microbit,HM10-clone have WRITE
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) == 0
        ) { // HM10,TI uart,Telit have only WRITE_NO_RESPONSE
            onSerialConnectError(IOException("write characteristic not writable"))
            return
        }
        if (!gatt.setCharacteristicNotification(readCharacteristic, true)) {
            onSerialConnectError(IOException("no notification for read characteristic"))
            return
        }
        val readDescriptor = readCharacteristic!!.getDescriptor(BLUETOOTH_LE_CCCD)
        if (readDescriptor == null) {
            onSerialConnectError(IOException("no CCCD descriptor for read characteristic"))
            return
        }
        val readProperties = readCharacteristic!!.properties
        if ((readProperties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
            Log.d(TAG, "enable read indication")
            readDescriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
        } else if ((readProperties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
            Log.d(TAG, "enable read notification")
            readDescriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            onSerialConnectError(IOException("no indication/notification for read characteristic ($readProperties)"))
            return
        }
        Log.d(TAG, "writing read characteristic descriptor")
        if (!gatt.writeDescriptor(readDescriptor)) {
            onSerialConnectError(IOException("read characteristic CCCD descriptor not writable"))
        }

    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        delegate!!.onDescriptorWrite(gatt, descriptor, status)
        if (canceled) return
        if (descriptor.characteristic === readCharacteristic) {
            Log.d(
                TAG,
                "writing read characteristic descriptor finished, status=$status"
            )
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onSerialConnectError(IOException("write descriptor failed"))
            } else {
                // onCharacteristicChanged with incoming data can happen after writeDescriptor(ENABLE_INDICATION/NOTIFICATION)
                // before confirmed by this method, so receive data can be shown before device is shown as 'Connected'.
                onSerialConnect()
                connected = true
                Log.d(TAG, "connected")
            }
        }
    }

    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (canceled) return
        delegate!!.onCharacteristicChanged(gatt, characteristic)
        if (canceled) return
        if (characteristic === readCharacteristic) { // NOPMD - test object identity
            val data = readCharacteristic!!.value
            onSerialRead(data)
            Log.d(TAG, "read, len=" + data.size)
        }
    }

    @Throws(IOException::class)
    fun write(data: ByteArray) {
        if (canceled || !connected || writeCharacteristic == null) throw IOException("not connected")
        var data0: ByteArray?
        synchronized(writeBuffer) {
            data0 = if (data.size <= payloadSize) {
                data
            } else {
                Arrays.copyOfRange(data, 0, payloadSize)
            }
            if (!writePending && writeBuffer.isEmpty() && delegate!!.canWrite()) {
                writePending = true
            } else {
                writeBuffer.add(data0!!)
                Log.d(TAG, "write queued, len=" + data0!!.size)
                data0 = null
            }
            if (data.size > payloadSize) {
                for (i in 1 until (data.size + payloadSize - 1) / payloadSize) {
                    val from = i * payloadSize
                    val to =
                        min((from + payloadSize).toDouble(), data.size.toDouble())
                            .toInt()
                    writeBuffer.add(Arrays.copyOfRange(data, from, to))
                    Log.d(
                        TAG,
                        "write queued, len=" + (to - from)
                    )
                }
            }
        }
        if (data0 != null) {
            writeCharacteristic!!.setValue(data0)
            if (!gatt!!.writeCharacteristic(writeCharacteristic)) {
                onSerialIoError(IOException("write failed"))
            } else {
                Log.d(TAG, "write started, len=" + data0!!.size)
            }
        }
    }

    override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        if (canceled || !connected || writeCharacteristic == null) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            onSerialIoError(IOException("write failed"))
            return
        }
        delegate!!.onCharacteristicWrite(gatt, characteristic, status)
        if (canceled) return
        if (characteristic === writeCharacteristic) { // NOPMD - test object identity
            Log.d(TAG, "write finished, status=$status")
            writeNext()
        }
    }

    private fun writeNext() {
        val data: ByteArray?
        synchronized(writeBuffer) {
            if (!writeBuffer.isEmpty() && delegate!!.canWrite()) {
                writePending = true
                data = writeBuffer.removeAt(0)
            } else {
                writePending = false
                data = null
            }
        }
        if (data != null) {
            writeCharacteristic!!.setValue(data)
            if (!gatt!!.writeCharacteristic(writeCharacteristic)) {
                onSerialIoError(IOException("write failed"))
            } else {
                Log.d(TAG, "write started, len=" + data.size)
            }
        }
    }

    private fun onSerialConnect() {
        if (listener != null) listener!!.onSerialConnect()
    }

    private fun onSerialConnectError(e: Exception) {
        canceled = true
        if (listener != null) listener!!.onSerialConnectError(e)
    }

    private fun onSerialRead(data: ByteArray) {
        if (listener != null) listener!!.onSerialRead(data)
    }

    private fun onSerialIoError(e: Exception) {
        writePending = false
        canceled = true
        if (listener != null) listener!!.onSerialIoError(e)
    }

    /*
     * Nrf
     */

    private inner class NrfDelegate : DeviceDelegate() {
        override fun connectCharacteristics(gattService: BluetoothGattService): Boolean {
            Log.d(TAG, "service nrf uart")
            val rw2 = gattService.getCharacteristic(BLUETOOTH_LE_NRF_CHAR_RW2)
            val rw3 = gattService.getCharacteristic(BLUETOOTH_LE_NRF_CHAR_RW3)
            if (rw2 != null && rw3 != null) {
                val rw2prop = rw2.properties
                val rw3prop = rw3.properties
                val rw2write = (rw2prop and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                val rw3write = (rw3prop and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                Log.d(TAG, "characteristic properties $rw2prop/$rw3prop")
                if (rw2write && rw3write) {
                    onSerialConnectError(IOException("multiple write characteristics ($rw2prop/$rw3prop)"))
                } else if (rw2write) {
                    writeCharacteristic = rw2
                    readCharacteristic = rw3
                } else if (rw3write) {
                    writeCharacteristic = rw3
                    readCharacteristic = rw2
                } else {
                    onSerialConnectError(IOException("no write characteristic ($rw2prop/$rw3prop)"))
                }
            }
            return true
        }
    }
}
