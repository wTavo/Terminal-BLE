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
import android.graphics.Bitmap
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
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat.finishAffinity
import androidx.fragment.app.Fragment
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dev.turingcomplete.kotlinonetimepassword.GoogleAuthenticator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.apache.commons.codec.binary.Base32
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.Date

class OPTFragment : Fragment() {

    private lateinit var otpTextView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var qrCodeImageView: ImageView
    private lateinit var handler: Handler

    private val plainTextSecret = "HolaMundo123".toByteArray(StandardCharsets.UTF_8)
    private val base32EncodedSecret = Base32().encodeToString(plainTextSecret)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_opt, container, false)

        otpTextView = view.findViewById(R.id.otpTextView)
        progressBar = view.findViewById(R.id.progressBar)
        qrCodeImageView = view.findViewById(R.id.qrCodeImageView)
        handler = Handler(Looper.getMainLooper())

        generateQrCode()
        startOtpGeneration()

        return view
    }

    private fun startOtpGeneration() {
        CoroutineScope(Dispatchers.Main).launch {
            var counter = 0L
            while (true) {
                val timestamp = Date(counter * 30000)

                val googleAuthenticator = GoogleAuthenticator(base32EncodedSecret)
                val otpCode = googleAuthenticator.generate(timestamp)

                otpTextView.text = otpCode

                val job = launch(Dispatchers.IO) {
                    repeat(30) {
                        delay(1000)
                        progressBar.setProgress((it + 1) * 100 / 30, true)
                    }
                }

                job.join()
                counter++
            }
        }
    }

    private fun generateQrCode() {
        val qrCodeSize = 200
        val qrCodeWriter = QRCodeWriter()
        val qrText = "otpauth://totp/Tavo:GusMontejo.25@gmail.com?secret=$base32EncodedSecret&issuer=Tavo&algorithm=SHA1&digits=12&period=30"
        Log.d("TAG", "El valor de base32EncodedSecret es: $base32EncodedSecret")
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L)
        val bitMatrix = qrCodeWriter.encode(qrText, BarcodeFormat.QR_CODE, qrCodeSize, qrCodeSize, hints)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        qrCodeImageView.setImageBitmap(bitmap)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
    }
}