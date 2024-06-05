package com.example.terminalble

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.fragment.app.Fragment
import android.Manifest

object BluetoothUtil {

    interface PermissionGrantedCallback {
        fun call()
    }

    /**
     * More efficient caching of name than BluetoothDevice which always does RPC
     */
    data class Device(val device: BluetoothDevice) : Comparable<Device> {
        @SuppressLint("MissingPermission")
        val name: String? = device.name

        override fun equals(other: Any?): Boolean {
            if (other is Device) {
                return device == other.device
            }
            return false
        }

        /**
         * Sort by name, then address. Sort named devices first
         */
        override fun compareTo(other: Device): Int {
            val thisValid = !this.name.isNullOrEmpty()
            val otherValid = !other.name.isNullOrEmpty()
            if (thisValid && otherValid) {
                var ret = this.name!!.compareTo(other.name!!)
                if (ret != 0) return ret
                return this.device.address.compareTo(other.device.address)
            }
            if (thisValid) return -1
            return if (otherValid) 1 else this.device.address.compareTo(other.device.address)
        }
    }

    /**
     * Android 12 permission handling
     */
    private fun showRationaleDialog(fragment: Fragment, listener: DialogInterface.OnClickListener) {
        AlertDialog.Builder(fragment.requireActivity())
            .setTitle(fragment.getString(R.string.bluetooth_permission_title))
            .setMessage(fragment.getString(R.string.bluetooth_permission_grant))
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue", listener)
            .show()
    }

    private fun showSettingsDialog(fragment: Fragment) {
        val s = fragment.resources.getString(fragment.resources.getIdentifier("@android:string/permgrouplab_nearby_devices", null, null))
        AlertDialog.Builder(fragment.requireActivity())
            .setTitle(fragment.getString(R.string.bluetooth_permission_title))
            .setMessage(String.format(fragment.getString(R.string.bluetooth_permission_denied), s))
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Settings") { dialog, which ->
                fragment.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + BuildConfig.APPLICATION_ID))
                )
            }
            .show()
    }

    /**
     * CONNECT + SCAN are granted together in the same permission group, so actually no need to check/request both, but one never knows
     */
    fun hasPermissions(fragment: Fragment, requestPermissionLauncher: ActivityResultLauncher<Array<String>>): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val missingPermissions = fragment.requireActivity().checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                fragment.requireActivity().checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
        val showRationale = fragment.shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT) ||
                fragment.shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_SCAN)
        val permissions = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        return if (missingPermissions) {
            if (showRationale) {
                showRationaleDialog(fragment) { dialog, which ->
                    requestPermissionLauncher.launch(permissions)
                }
            } else {
                requestPermissionLauncher.launch(permissions)
            }
            false
        } else {
            true
        }
    }

    fun onPermissionsResult(fragment: Fragment, grants: Map<String, Boolean>, cb: PermissionGrantedCallback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val showRationale = fragment.shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT) ||
                fragment.shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_SCAN)
        val granted = grants.values.reduce { acc, b -> acc && b }
        if (granted) {
            cb.call()
        } else if (showRationale) {
            showRationaleDialog(fragment) { dialog, which -> cb.call() }
        } else {
            showSettingsDialog(fragment)
        }
    }
}