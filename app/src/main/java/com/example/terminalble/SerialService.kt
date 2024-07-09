package com.example.terminalble

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.Nullable
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.IOException

class SerialService : Service(), SerialListener {

    inner class SerialBinder : Binder() {
        fun getService(): SerialService = this@SerialService
    }

    private enum class QueueType { Connect, ConnectError, Read, IoError }

    private class QueueItem {
        var type: QueueType
        var datas: ArrayDeque<ByteArray>? = null
        var e: Exception? = null

        constructor(type: QueueType) {
            this.type = type
            if (type == QueueType.Read) init()
        }

        constructor(type: QueueType, e: Exception) {
            this.type = type
            this.e = e
        }

        constructor(type: QueueType, datas: ArrayDeque<ByteArray>) {
            this.type = type
            this.datas = datas
        }

        fun init() {
            datas = ArrayDeque()
        }

        fun add(data: ByteArray) {
            datas!!.add(data)
        }
    }

    private var mainLooper: Handler
    private var binder: IBinder
    private val queue1: ArrayDeque<QueueItem>
    private val queue2: ArrayDeque<QueueItem>
    private val lastRead: QueueItem

    init {
        mainLooper = Handler(Looper.getMainLooper())
        binder = SerialBinder()
        queue1 = ArrayDeque()
        queue2 = ArrayDeque()
        lastRead = QueueItem(QueueType.Read)
    }

    private var socket: SerialSocket? = null
    private var listener: SerialListener? = null
    private var connected = false

    /**
     * Lifecycle
     */
    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    /**
     * Api
     */
    @Throws(IOException::class)
    fun connect(socket: SerialSocket) {
        socket.connect(this)
        this.socket = socket
        connected = true
    }

    fun disconnect() {
        connected = false // ignore data,errors while disconnecting
        socket?.disconnect()
        socket = null
    }

    fun write(data: ByteArray) {
        if (!connected) throw IOException("not connected")
        socket!!.write(data)
    }

    fun attach(listener: SerialListener) {
        if (Looper.getMainLooper().thread != Thread.currentThread())
            throw IllegalArgumentException("not in main thread")
        synchronized(this) {
            this.listener = listener
        }
        for (item: QueueItem in queue1) {
            when (item.type) {
                QueueType.Connect -> listener.onSerialConnect()
                QueueType.ConnectError -> listener.onSerialConnectError(item.e!!)
                QueueType.Read -> listener.onSerialRead(item.datas!!)
                QueueType.IoError -> listener.onSerialIoError(item.e!!)
            }
        }
        for (item: QueueItem in queue2) {
            when (item.type) {
                QueueType.Connect -> listener.onSerialConnect()
                QueueType.ConnectError -> listener.onSerialConnectError(item.e!!)
                QueueType.Read -> listener.onSerialRead(item.datas!!)
                QueueType.IoError -> listener.onSerialIoError(item.e!!)
            }
        }
        queue1.clear()
        queue2.clear()
    }

    @SuppressLint("SuspiciousIndentation")
    fun detach() {
        if (connected)
        listener = null
    }

    /**
     * SerialListener
     */
    override fun onSerialConnect() {
        if (connected) {
            synchronized(this) {
                listener?.let {
                    mainLooper.post {
                        if (listener != null) {
                            listener!!.onSerialConnect()
                        } else {
                            queue1.add(QueueItem(QueueType.Connect))
                        }
                    }
                } ?: run {
                    queue2.add(QueueItem(QueueType.Connect))
                }
            }
        }
    }

    override fun onSerialConnectError(e: Exception) {
        if (connected) {
            synchronized(this) {
                listener?.let {
                    mainLooper.post {
                        if (listener != null) {
                            listener!!.onSerialConnectError(e)
                        } else {
                            queue1.add(QueueItem(QueueType.ConnectError, e))
                            disconnect()
                        }
                    }
                } ?: run {
                    queue2.add(QueueItem(QueueType.ConnectError, e))
                    disconnect()
                }
            }
        }
    }

    override fun onSerialRead(datas: ArrayDeque<ByteArray>) {
        throw UnsupportedOperationException()
    }

    override fun onSerialRead(data: ByteArray) {
        if (connected) {
            synchronized(this) {
                listener?.let {
                    val first: Boolean
                    synchronized(lastRead) {
                        first = lastRead.datas!!.isEmpty()
                        lastRead.add(data)
                    }
                    if (first) {
                        mainLooper.post {
                            val datas: ArrayDeque<ByteArray>
                            synchronized(lastRead) {
                                datas = lastRead.datas!!
                                lastRead.init()
                            }
                            if (listener != null) {
                                listener!!.onSerialRead(datas)
                            } else {
                                queue1.add(QueueItem(QueueType.Read, datas))
                            }
                        }
                    }
                } ?: run {
                    if (queue2.isEmpty() || queue2.last().type != QueueType.Read) queue2.add(QueueItem(QueueType.Read))
                    queue2.last().add(data)
                }
            }
        }
    }

    override fun onSerialIoError(e: Exception) {
        if (connected) {
            synchronized(this) {
                listener?.let {
                    mainLooper.post {
                        if (listener != null) {
                            listener!!.onSerialIoError(e)
                        } else {
                            queue1.add(QueueItem(QueueType.IoError, e))
                            disconnect()
                        }
                    }
                } ?: run {
                    queue2.add(QueueItem(QueueType.IoError, e))
                    disconnect()
                }
            }
        }
    }

    override fun onBackPressedInFragment() {
        TODO("Not yet implemented")
    }
}
