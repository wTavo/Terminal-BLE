package com.example.terminalble

import OnBackPressedListener
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.icu.text.SimpleDateFormat
import android.icu.util.Calendar
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat.finishAffinity
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getSystemService
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dev.turingcomplete.kotlinonetimepassword.GoogleAuthenticator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.codec.binary.Base32
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.Date
import java.util.Locale

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

    private var isConnected: Boolean = false

    private lateinit var otpTextView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var handler: Handler

    private val plainTextSecret = "HolaMundo123".toByteArray(StandardCharsets.UTF_8)
    private val base32EncodedSecret = Base32().encodeToString(plainTextSecret)

    var bol: Boolean = true
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
            requireActivity().runOnUiThread {
                val lastDeviceAddress = getLastDeviceAddress()
                if (lastDeviceAddress != null) {
                    connect() // Conectar automáticamente si hay un dispositivo guardado
                }
            }
        }
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        service = (binder as SerialService.SerialBinder).getService()
        service?.attach(this)
        if (initialStart && isResumed) {
            initialStart = false

            // Verificar si se seleccionó un dispositivo
            val sharedPref = requireActivity().getPreferences(Context.MODE_PRIVATE)
            val deviceSelected = sharedPref.getBoolean("DEVICE_SELECTED", false)

            if (deviceSelected) {
                requireActivity().runOnUiThread { connect() }

                // Restablecer el valor del booleano a false después de la conexión
                with(sharedPref.edit()) {
                    putBoolean("DEVICE_SELECTED", false)
                    apply()
                }
            }
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
        receiveText.setTextColor(resources.getColor(R.color.yellow)) // set as default color to reduce number of spans
        receiveText.movementMethod = ScrollingMovementMethod.getInstance()

        sendText = view.findViewById(R.id.send_text)
        hexWatcher = TextUtil.HexWatcher(sendText)
        hexWatcher.enable(hexEnabled)
        sendText.addTextChangedListener(hexWatcher)
        sendText.hint = if (hexEnabled) "HEX mode" else ""

        handler = Handler(Looper.getMainLooper())

        val sendBtn = view.findViewById<View>(R.id.send_btn)
        sendBtn.setOnClickListener { send(sendText.text.toString()) }
        return view
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_terminal, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        val connectItem = menu.findItem(R.id.connect)
        val disconnectItem = menu.findItem(R.id.disconnect)
        if (isConnected) {
            connectItem.isVisible = true
            disconnectItem.isVisible = false
        } else {
            connectItem.isVisible = false
            disconnectItem.isVisible = true
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.connect -> {
                disconnect()
                // Agregar la hora y la fecha al mensaje
                val timestamp = getCurrentTimestamp()
                val spn = SpannableStringBuilder("$timestamp Desconectado\n")
                spn.setSpan(
                    ForegroundColorSpan(resources.getColor(R.color.green)),
                    0,
                    timestamp.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                receiveText.append(spn)
                true
            }

            R.id.disconnect -> {
                connect()
                true
            }
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
    @SuppressLint("MissingPermission")
    private fun connect() {
        try {
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            deviceAddress = getLastDeviceAddress()
            if (deviceAddress == null) {
                val statusText = "No hay dispositivo seleccionado"
                val spn = SpannableStringBuilder()
                spn.append(getCurrentTimestamp())
                spn.setSpan(
                    ForegroundColorSpan(resources.getColor(R.color.green)),
                    0,
                    spn.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spn.append(" $statusText\n")
                receiveText.append(spn)
                return
            }
            val device = bluetoothAdapter.getRemoteDevice(deviceAddress)
            val statusText = "Conectando a ${device.name}...\n"
            val spn = SpannableStringBuilder()
            spn.append(getCurrentTimestamp())
            spn.setSpan(
                ForegroundColorSpan(resources.getColor(R.color.green)),
                0,
                spn.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spn.append(" $statusText")
            spn.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(requireContext(), R.color.yellow)),
                spn.length - statusText.length,
                spn.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            receiveText.append(spn)
            connected = Connected.Pending
            val socket = SerialSocket(requireActivity().applicationContext, device)
            service?.connect(socket)
            isConnected = true
            saveLastDeviceAddress(deviceAddress!!) // Guarda el dispositivo actual como el último dispositivo conectado
            requireActivity().invalidateOptionsMenu() // Actualizar el menú

        } catch (e: Exception) {
            onSerialConnectError(e)
        }
    }

    private fun disconnect() {

        connected = Connected.False
        service?.disconnect()
        isConnected = false
        requireActivity().invalidateOptionsMenu()  // Actualizar el menú
    }

    private fun send(str: String) {
        if (connected != Connected.True) {
            Toast.makeText(requireActivity(), "Sin conexión", Toast.LENGTH_SHORT).show()
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
            val spn = SpannableStringBuilder()

            // Agregar la hora y la fecha al mensaje
            val timestamp = getCurrentTimestamp()
            spn.append(timestamp)
            spn.setSpan(
                ForegroundColorSpan(resources.getColor(R.color.green)),
                0,
                timestamp.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spn.append(" ")

            spn.append(msg).append('\n')
            spn.setSpan(
                ForegroundColorSpan(resources.getColor(R.color.white)),
                timestamp.length + 1,
                spn.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            receiveText.append(spn)
            service?.write(data)
            sendText.text = ""
        } catch (e: Exception) {
            onSerialIoError(e)
        }
    }

    private fun receive(datas: ArrayDeque<ByteArray>) {
        val spn = SpannableStringBuilder()
        for (data in datas) {
            // Agregar la hora y la fecha al mensaje recibido
            spn.append(getCurrentTimestamp(), ForegroundColorSpan(resources.getColor(R.color.green)), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                .append(" ")

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

    // Método para obtener la hora y la fecha actual
    private fun getCurrentTimestamp(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val currentTime = Calendar.getInstance().time
        return dateFormat.format(currentTime) + ""
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
     * SerialListener
     */
    override fun onSerialConnect() {
        val statusText = "Conexión exitosa\n"

        // Enviar "connect" directamente al puerto
        val data = "connect\n".toByteArray()
        service?.write(data)

        val spn = SpannableStringBuilder()
        spn.append(getCurrentTimestamp())
        spn.setSpan(
            ForegroundColorSpan(resources.getColor(R.color.green)),
            0,
            spn.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spn.append(" $statusText")
        spn.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(requireContext(), R.color.yellow)),
            spn.length - statusText.length,
            spn.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // Verifica si deviceAddress es nulo antes de guardar
        if (deviceAddress != null) {
            receiveText.append(spn)
            connected = Connected.True
            isConnected = true
            showBottomSheetDialog()
            saveLastDeviceAddress(deviceAddress!!) // Guarda la dirección del dispositivo conectado
        } else {
            status("Error: deviceAddress es nulo")
        }

        requireActivity().invalidateOptionsMenu() // Actualizar el menú
    }

    override fun onSerialConnectError(e: Exception) {
        val errorMessage = "Conexión fallida: ${e.message}"
        val spn = SpannableStringBuilder()
        spn.append(getCurrentTimestamp())
        spn.setSpan(
            ForegroundColorSpan(resources.getColor(R.color.green)),
            0,
            spn.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spn.append(" $errorMessage\n")
        receiveText.append(spn)
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
        val errorMessage = "Conexión perdida: ${e.message}"
        val spn = SpannableStringBuilder()
        spn.append(getCurrentTimestamp())
        spn.setSpan(
            ForegroundColorSpan(resources.getColor(R.color.green)),
            0,
            spn.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spn.append(" $errorMessage\n")
        receiveText.append(spn)
        disconnect()
    }

    override fun onBackPressedInFragment() {
        TODO("Not yet implemented")
    }

    private fun saveLastDeviceAddress(address: String) {
        val sharedPref = requireActivity().getPreferences(Context.MODE_PRIVATE) ?: return
        with(sharedPref.edit()) {
            putString("LAST_DEVICE_ADDRESS", address)
            apply()
        }
    }

    private fun getLastDeviceAddress(): String? {
        val sharedPref = requireActivity().getPreferences(Context.MODE_PRIVATE)
        return sharedPref.getString("LAST_DEVICE_ADDRESS", null)
    }

    private fun showBottomSheetDialog() {
        // Crea un BottomSheetDialog
        val bottomSheetDialog = BottomSheetDialog(requireContext())

        // Infla el layout del BottomSheet
        val bottomSheetView = layoutInflater.inflate(R.layout.bottom_sheet_otp, null)

        // Busca el botón dentro del BottomSheet
        val bottomSheetButton: Button = bottomSheetView.findViewById(R.id.bottomSheetButton)
        otpTextView = bottomSheetView.findViewById(R.id.otpTextView)
        progressBar = bottomSheetView.findViewById(R.id.progressBar)

        // Agrega un clic al botón del BottomSheet
        bottomSheetButton.setOnClickListener {
            val clipboardManager = requireActivity().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

            val textToCopy = otpTextView.text.toString()

            // Crear un objeto ClipData para almacenar el texto a copiar
            val clipData = ClipData.newPlainText("Texto copiado", textToCopy)
            clipboardManager.setPrimaryClip(clipData)
            Toast.makeText(requireContext(), "Texto copiado al portapapeles", Toast.LENGTH_SHORT).show()
            bottomSheetDialog.hide()
        }


        // Configura el contenido del BottomSheet
        bottomSheetDialog.setContentView(bottomSheetView)

        if (bol==true) {
            startOtpGeneration()
            bol=false
        }

        // Muestra el BottomSheet
        bottomSheetDialog.show()
    }

    private fun startOtpGeneration() {
        CoroutineScope(Dispatchers.Main).launch {
            var counter = 0L
            while (true) {
                val job = launch(Dispatchers.IO) {
                    repeat(30) {
                        val timestamp = Date(counter * 30000 + it * 1000)
                        val googleAuthenticator = GoogleAuthenticator(base32EncodedSecret)
                        val otpCode = googleAuthenticator.generate(timestamp)

                        withContext(Dispatchers.Main) {
                            otpTextView.text = otpCode
                        }

                        progressBar.setProgress((it + 1) * 100 / 30, true)
                        delay(1000)
                    }
                }
                job.join()
                counter++
            }
        }
    }
}