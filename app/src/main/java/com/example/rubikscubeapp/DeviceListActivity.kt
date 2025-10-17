package com.example.rubikscubeapp

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

class DeviceListActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private var devices: List<BluetoothDevice> = emptyList()

    private val requestEnableBt = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            loadBondedDevices()
        } else {
            Toast.makeText(this, "Bluetooth must be enabled to list devices", Toast.LENGTH_LONG).show()
        }
    }

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms.values.all { it }
        if (granted) {
            ensureBluetoothEnabledThenLoad()
        } else {
            Toast.makeText(this, "Bluetooth permission is required to list devices", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_list)

        // Wire Back button to standard back navigation (returns to previous screen)
        findViewById<MaterialButton>(R.id.Back)?.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        listView = findViewById(R.id.device_list)

        if (hasBtPermissions()) {
            ensureBluetoothEnabledThenLoad()
        } else {
            requestBtPermissions()
        }
    }

    private fun ensureBluetoothEnabledThenLoad() {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = btManager.adapter
        if (adapter == null) {
            Toast.makeText(this, "Bluetooth not supported on this device", Toast.LENGTH_LONG).show()
            return
        }
        if (!adapter.isEnabled) {
            val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            requestEnableBt.launch(enableIntent)
        } else {
            loadBondedDevices()
        }
    }

    private fun hasBtPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            // Below S, BLUETOOTH permissions are normal and granted at install time
            true
        }
    }

    private fun requestBtPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermission.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
        }
    }

    private fun loadBondedDevices() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = btManager.adapter
        if (adapter == null) {
            Toast.makeText(this, "Bluetooth not supported on this device", Toast.LENGTH_LONG).show()
            return
        }

        devices = adapter.bondedDevices?.toList()?.sortedBy { it.name ?: it.address } ?: emptyList()
        val items = devices.map { d ->
            val name = d.name ?: "Unknown"
            "$name\n${d.address}"
        }

        val adapterUi = ArrayAdapter(this, android.R.layout.simple_list_item_1, items)
        listView.adapter = adapterUi

        listView.setOnItemClickListener { _, _, position, _ ->
            val device = devices[position]
            // Return the selected device address to the caller
            val data = Intent().apply { putExtra("device_address", device.address) }
            setResult(Activity.RESULT_OK, data)
            finish()
        }
    }
}
