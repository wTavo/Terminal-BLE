package com.example.terminalble

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.PermissionChecker.checkSelfPermission
import androidx.fragment.app.Fragment
import androidx.fragment.app.ListFragment
import androidx.navigation.Navigation.findNavController
import androidx.navigation.fragment.findNavController
import com.example.terminalble.BluetoothUtil.hasPermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DevicesFragment : ListFragment() {

    private enum class ScanState { NONE, LE_SCAN, DISCOVERY, DISCOVERY_FINISHED }

    private var scanState: ScanState = ScanState.NONE
    private val LE_SCAN_PERIOD: Long = 10000 // similar to bluetoothAdapter.startDiscovery
    private val leScanStopHandler = Handler()
    private val leScanCallback: BluetoothAdapter.LeScanCallback
    private val leScanStopCallback: Runnable
    private val discoveryBroadcastReceiver: BroadcastReceiver
    private val discoveryIntentFilter: IntentFilter

    private var menu: Menu? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private val listItems: ArrayList<BluetoothUtil.Device> = ArrayList()
    private lateinit var listAdapter: ArrayAdapter<BluetoothUtil.Device>

    private var requestBluetoothPermissionLauncherForStartScan: ActivityResultLauncher<Array<String>>
    private var requestLocationPermissionLauncherForStartScan: ActivityResultLauncher<String>

    private val unnamedDevices: MutableList<BluetoothDevice> = mutableListOf()
    private var showUnnamedDevices: Boolean = false

    init {
        leScanCallback = BluetoothAdapter.LeScanCallback { device, rssi, scanRecord ->
            if (device != null && activity != null) {
                activity?.runOnUiThread { updateScan(device) }
            }
        }

        discoveryBroadcastReceiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(context: Context?, intent: Intent?) {
                if (BluetoothDevice.ACTION_FOUND == intent?.action) {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (device?.type != BluetoothDevice.DEVICE_TYPE_CLASSIC && activity != null) {
                        activity?.runOnUiThread { updateScan(device!!) }
                    }
                }
                if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED == intent?.action) {
                    scanState = ScanState.DISCOVERY_FINISHED // don't cancel again
                    stopScan()
                }
            }
        }

        discoveryIntentFilter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }

        leScanStopCallback = Runnable { stopScan() }

        requestBluetoothPermissionLauncherForStartScan =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
                BluetoothUtil.onPermissionsResult(this@DevicesFragment, granted, object : BluetoothUtil.PermissionGrantedCallback {
                    override fun call() {
                        startScan()
                    }
                })
            }

        requestLocationPermissionLauncherForStartScan =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) {
                    Handler(Looper.getMainLooper()).postDelayed({ startScan() }, 1)
                } else {
                    AlertDialog.Builder(requireActivity())
                        .setTitle(getString(R.string.location_permission_title))
                        .setMessage(getString(R.string.location_permission_denied))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        if (activity?.packageManager?.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH) == true) {
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        }
        listAdapter = object : ArrayAdapter<BluetoothUtil.Device>(requireActivity(), 0, listItems) {
            @SuppressLint("MissingPermission")
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                var view = convertView
                val device = listItems[position]
                if (view == null) {
                    view = requireActivity().layoutInflater.inflate(R.layout.device_list_item, parent, false)
                }
                val text1 = view?.findViewById<TextView>(R.id.text1)
                val text2 = view?.findViewById<TextView>(R.id.text2)
                var deviceName = device.name
                if (deviceName.isNullOrEmpty()) {
                    deviceName = "<unnamed>"
                }
                text1?.text = deviceName
                text2?.text = device.device.address

                // Cambiar el color del texto si el dispositivo está vinculado
                if (device.device.bondState == BluetoothDevice.BOND_BONDED) {
                    text1?.setTextColor(Color.RED)
                    text2?.setTextColor(Color.RED)
                } else {
                    // Si el dispositivo no está vinculado, usar el color predeterminado
                    text1?.setTextColor(Color.BLACK)
                    text2?.setTextColor(Color.BLACK)
                }

                return view!!
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        setListAdapter(null)
        val header = requireActivity().layoutInflater.inflate(R.layout.device_list_header, null, false)
        listView.addHeaderView(header, null, false)
        setEmptyText("initializing...")
        (listView.emptyView as TextView).textSize = 18f
        setListAdapter(listAdapter)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.menu_devices, menu)
        this.menu = menu
        if (bluetoothAdapter == null) {
            menu.findItem(R.id.bt_settings)?.isEnabled = false
            menu.findItem(R.id.ble_scan)?.isEnabled = false
        } else if (!bluetoothAdapter!!.isEnabled) {
            menu.findItem(R.id.ble_scan)?.isEnabled = false
        }
    }

    @SuppressLint("MissingPermission")
    override fun onResume() {
        super.onResume()
        requireActivity().registerReceiver(discoveryBroadcastReceiver, discoveryIntentFilter)
        if (bluetoothAdapter == null) {
            setEmptyText("<bluetooth LE not supported>")
        } else if (!bluetoothAdapter!!.isEnabled) {
            setEmptyText("<bluetooth is disabled>")
            menu?.let {
                listItems.clear()
                listAdapter.notifyDataSetChanged()
                it.findItem(R.id.ble_scan)?.isEnabled = false
            }
        } else {
            // Verificar si hay permisos de ubicación
            if (!hasPermissions(this, requestBluetoothPermissionLauncherForStartScan)) {
                // No hay permisos, mostrar el mensaje correspondiente
                setEmptyText("Use SCAN to request permits")
                menu?.findItem(R.id.ble_scan)?.isEnabled = true
            } else {
                // Verificar si hay dispositivos vinculados
                val bondedDevices = bluetoothAdapter?.bondedDevices
                if (bondedDevices != null && bondedDevices.isNotEmpty()) {
                    // Mostrar dispositivos vinculados
                    val bondedDevicesList = bondedDevices.map { BluetoothUtil.Device(it) }
                    listItems.clear()
                    listItems.addAll(bondedDevicesList)
                    listAdapter.notifyDataSetChanged()
                    setEmptyText("<no bluetooth devices found>")
                    menu?.findItem(R.id.ble_scan)?.isEnabled = true
                } else {
                    setEmptyText("<use SCAN to refresh devices>")
                    menu?.findItem(R.id.ble_scan)?.isEnabled = true
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        stopScan()
        requireActivity().unregisterReceiver(discoveryBroadcastReceiver)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        menu = null
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.ble_scan -> {
                startScan()
                true
            }

            R.id.ble_scan_stop -> {
                stopScan()
                true
            }

            R.id.bt_settings -> {
                val intent = Intent()
                intent.action = android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
                startActivity(intent)
                true
            }

            R.id.show_unnamed -> {
                toggleUnnamedDevices()
                item.isChecked = showUnnamedDevices
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun toggleUnnamedDevices() {
        if (showUnnamedDevices) {
            // Si se están mostrando los dispositivos no nombrados, se ocultan
            listItems.removeAll { it.name.isNullOrEmpty() || it.name == "<unnamed>" }
        } else {
            // Si no se están mostrando, se agregan a la lista principal
            listItems.addAll(unnamedDevices.map { BluetoothUtil.Device(it) }.distinctBy { it.device.address })
        }
        listAdapter.notifyDataSetChanged() // Actualizar el adaptador de la lista
        showUnnamedDevices = !showUnnamedDevices // Alternar el estado
    }

    @SuppressLint("WrongConstant")
    private fun startScan() {
        if (scanState != ScanState.NONE)
            return
        val nextScanState = ScanState.LE_SCAN
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!BluetoothUtil.hasPermissions(this, requestBluetoothPermissionLauncherForStartScan))
                return
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(
                    requireContext(),
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                scanState = ScanState.NONE
                val builder = AlertDialog.Builder(requireActivity())
                builder.setTitle(R.string.location_permission_title)
                builder.setMessage(R.string.location_permission_grant)
                builder.setPositiveButton(android.R.string.ok) { dialog, which ->
                    requestLocationPermissionLauncherForStartScan.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
                builder.show()
                return
            }
            val locationManager =
                requireActivity().getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var locationEnabled = false
            try {
                locationEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            } catch (ignored: Exception) {
            }
            try {
                locationEnabled =
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            } catch (ignored: Exception) {
            }
            if (!locationEnabled)
                scanState = ScanState.DISCOVERY
        }
        scanState = nextScanState
        setEmptyText("<scanning...>")
        menu?.findItem(R.id.ble_scan)?.isVisible = false
        menu?.findItem(R.id.ble_scan_stop)?.isVisible = true
        if (scanState == ScanState.LE_SCAN) {
            leScanStopHandler.postDelayed(leScanStopCallback, LE_SCAN_PERIOD)
            // Escanear dispositivos vinculados
            bluetoothAdapter?.bondedDevices?.forEach { device ->
                updateScan(device)
            }
            // Escanear dispositivos nuevos
            CoroutineScope(Dispatchers.IO).launch {
                bluetoothAdapter?.startLeScan(null) { device, rssi, scanRecord ->
                    if (device != null && activity != null) {
                        activity?.runOnUiThread {
                            updateScan(device)
                        }
                    }
                }
            }
        } else {
            bluetoothAdapter?.startDiscovery()
        }
    }


    @SuppressLint("MissingPermission")
    private fun updateScan(device: BluetoothDevice) {
        if (scanState == ScanState.NONE) return

        val device2 = BluetoothUtil.Device(device)
        if (!showUnnamedDevices && (device2.name.isNullOrEmpty() || device2.name == "<unnamed>")) {
            // Si no se están mostrando los dispositivos no nombrados, no los agregues a la lista
            unnamedDevices.add(device)
            return
        }

        val pos = listItems.indexOfFirst { it.device.address == device2.device.address }
        if (pos == -1) {
            listItems.add(device2)
            listAdapter.notifyDataSetChanged()
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (scanState == ScanState.NONE)
            return
        setEmptyText("<no bluetooth devices found>")
        menu?.let {
            it.findItem(R.id.ble_scan)?.isVisible = true
            it.findItem(R.id.ble_scan_stop)?.isVisible = false
        }
        when (scanState) {
            ScanState.LE_SCAN -> {
                leScanStopHandler.removeCallbacks(leScanStopCallback)
                bluetoothAdapter?.stopLeScan(leScanCallback)
            }

            ScanState.DISCOVERY -> {
                bluetoothAdapter?.cancelDiscovery()
            }

            else -> {
                // already canceled
            }
        }
        scanState = ScanState.NONE
    }

    @SuppressLint("UseRequireInsteadOfGet")
    override fun onListItemClick(l: ListView, v: View, position: Int, id: Long) {
        stopScan()
        val device = listItems[position - 1]
        val args = Bundle()
        args.putString("device", device.device.address)

        // Usar NavController para navegar al TerminalFragment
        val navController = findNavController()
        navController.navigate(R.id.nav_terminal, args)
    }

}