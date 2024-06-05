package com.example.terminalble

import OnBackPressedListener
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.method.ScrollingMovementMethod
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat.finishAffinity
import androidx.fragment.app.Fragment
import java.util.Arrays

class TerminalFragment : Fragment(), ServiceConnection, SerialListener, OnBackPressedListener {

    private enum class Connected { False, Pending, True }

    private var deviceAddress: String? = null
    private var service: SerialService? = null

    private lateinit var receiveText: TextView
    private lateinit var sendText: TextView
    private lateinit var hexWatcher: TextUtil.HexWatcher

    private var connected: Connected = Connected.False
    private var initialStart: Boolean = true
    private var hexEnabled: Boolean = false
    private var pendingNewline: Boolean = false
    private var newline: String = TextUtil.newline_crlf

    /*
     * Lifecycle
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        setRetainInstance(true)
        deviceAddress = arguments?.getString("device")
    }

    override fun onDestroy() {
        if (connected != Connected.False) {
            disconnect()
        }
        requireActivity().stopService(Intent(requireActivity(), SerialService::class.java))
        super.onDestroy()
    }

    override fun onBackPressed() {
        // Cerrar la aplicación
        activity?.finish()
    }

    override fun onStart() {
        super.onStart()
        if (service != null)
            service?.attach(this)
        else
            activity?.let {
                val serviceIntent = Intent(it, SerialService::class.java)
                it.startService(serviceIntent)
            } // prevents service destroy on unbind from recreated activity caused by orientation change
    }

    @SuppressLint("UseRequireInsteadOfGet")
    override fun onStop() {
        if (service != null && !activity!!.isChangingConfigurations)
            service?.detach()
        super.onStop()
    }

    override fun onAttach(activity: Activity) {
        super.onAttach(activity)
        activity.bindService(Intent(activity, SerialService::class.java), this, Context.BIND_AUTO_CREATE)
    }

    override fun onDetach() {
        try {
            activity?.unbindService(this)
        } catch (ignored: Exception) {
        }
        super.onDetach()
    }

    override fun onResume() {
        super.onResume()
        if (initialStart && service != null) {
            initialStart = false
            requireActivity().runOnUiThread { connect() }
        }
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        service = (binder as SerialService.SerialBinder).getService()
        service?.attach(this)
        if (initialStart && isResumed) {
            initialStart = false
            requireActivity().runOnUiThread { connect() }
        }
    }

    override fun onServiceDisconnected(name: ComponentName) {
        service = null
    }

    /*
     * UI
     */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_terminal, container, false)
        receiveText = view.findViewById(R.id.receive_text)
        receiveText.setTextColor(resources.getColor(R.color.black)) // set as default color to reduce number of spans
        receiveText.movementMethod = ScrollingMovementMethod.getInstance()

        sendText = view.findViewById(R.id.send_text)
        hexWatcher = TextUtil.HexWatcher(sendText)
        hexWatcher.enable(hexEnabled)
        sendText.addTextChangedListener(hexWatcher)
        sendText.hint = if (hexEnabled) "HEX mode" else ""

        val sendBtn = view.findViewById<View>(R.id.send_btn)
        sendBtn.setOnClickListener { send(sendText.text.toString()) }
        return view
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_terminal, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        menu.findItem(R.id.hex).isChecked = hexEnabled
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.clear -> {
                receiveText.text = ""
                true
            }
            R.id.hex -> {
                hexEnabled = !hexEnabled
                sendText.text = ""
                hexWatcher.enable(hexEnabled)
                sendText.hint = if (hexEnabled) "HEX mode" else ""
                item.isChecked = hexEnabled
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /*
     * Serial + UI
     */
    private fun connect() {
        try {
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            val device = bluetoothAdapter.getRemoteDevice(deviceAddress)
            status("connecting...")
            connected = Connected.Pending
            val socket = SerialSocket(requireActivity().applicationContext, device)
            service?.connect(socket)
        } catch (e: Exception) {
            onSerialConnectError(e)
        }
    }

    private fun disconnect() {
        connected = Connected.False
        service?.disconnect()
    }

    private fun send(str: String) {
        if (connected != Connected.True) {
            Toast.makeText(requireActivity(), "not connected", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val msg: String
            val data: ByteArray
            if (hexEnabled) {
                val sb = StringBuilder()
                TextUtil.toHexString(sb, TextUtil.fromHexString(str))
                TextUtil.toHexString(sb, newline.toByteArray())
                msg = sb.toString()
                data = TextUtil.fromHexString(msg)
            } else {
                msg = str
                data = (str + newline).toByteArray()
            }
            val spn = SpannableStringBuilder("$msg\n")
            spn.setSpan(
                ForegroundColorSpan(resources.getColor(R.color.black)),
                0,
                spn.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            receiveText.append(spn)
            service?.write(data)
        } catch (e: Exception) {
            onSerialIoError(e)
        }
    }

    private fun receive(datas: ArrayDeque<ByteArray>) {
        val spn = SpannableStringBuilder()
        for (data in datas) {
            if (hexEnabled) {
                spn.append(TextUtil.toHexString(data)).append('\n')
            } else {
                var msg = String(data)
                if (newline == TextUtil.newline_crlf && msg.isNotEmpty()) {
                    // don't show CR as ^M if directly before LF
                    msg = msg.replace(TextUtil.newline_crlf, TextUtil.newline_lf)
                    // special handling if CR and LF come in separate fragments
                    if (pendingNewline && msg.startsWith("\n")) {
                        if (spn.length >= 2) {
                            spn.delete(spn.length - 2, spn.length)
                        } else {
                            val edt = receiveText.editableText
                            if (edt != null && edt.length >= 2)
                                edt.delete(edt.length - 2, edt.length)
                        }
                    }
                    pendingNewline = msg.endsWith("\r")
                }
                spn.append(TextUtil.toCaretString(msg, newline.isNotEmpty()))
            }
        }
        receiveText.append(spn)
    }

    private fun status(str: String) {
        val spn = SpannableStringBuilder("$str\n")
        spn.setSpan(
            ForegroundColorSpan(resources.getColor(R.color.black)),
            0,
            spn.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        receiveText.append(spn)
    }

    /*
     * starting with Android 14, notifications are not shown in notification bar by default when App is in background
     */

    private fun showNotificationSettings() {
        val intent = Intent()
        intent.action = "android.settings.APP_NOTIFICATION_SETTINGS"
        intent.putExtra("android.provider.extra.APP_PACKAGE", requireActivity().packageName)
        startActivity(intent)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (Arrays.equals(permissions, arrayOf(Manifest.permission.POST_NOTIFICATIONS)) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && service?.areNotificationsEnabled() == false
        )
            showNotificationSettings()
    }

    /*
     * SerialListener
     */
    override fun onSerialConnect() {
        status("connected")
        connected = Connected.True
    }

    override fun onSerialConnectError(e: Exception) {
        status("connection failed: ${e.message}")
        disconnect()
    }

    override fun onSerialRead(data: ByteArray) {
        val datas = ArrayDeque<ByteArray>()
        datas.add(data)
        receive(datas)
    }

    override fun onSerialRead(datas: ArrayDeque<ByteArray>) {
        receive(datas)
    }

    override fun onSerialIoError(e: Exception) {
        status("connection lost: ${e.message}")
        disconnect()
    }

    override fun onBackPressedInFragment() {
        TODO("Not yet implemented")
    }
}