package com.example.rubikscubeapp

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class DeviceListActivity : AppCompatActivity() {

    private lateinit var listView: ListView

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms.values.all { it }
        if (granted) {
            loadBondedDevices()
        } else {
            Toast.makeText(this, "Bluetooth permission is required to list devices", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_list)

        listView = findViewById(R.id.device_list)
        listView.emptyView = findViewById(R.id.empty_view)

        if (hasBtPermissions()) {
            loadBondedDevices()
        } else {
            requestBtPermissions()
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

        val items = adapter.bondedDevices.map { d ->
            val name = d.name ?: "Unknown"
            "$name\n${d.address}"
        }.sorted()

        val adapterUi = ArrayAdapter(this, android.R.layout.simple_list_item_1, items)
        listView.adapter = adapterUi

        listView.setOnItemClickListener { _, _, position, _ ->
            val item = items[position]
            Toast.makeText(this, "Selected: $item", Toast.LENGTH_SHORT).show()
            // TODO: Navigate to a connection flow or return selection
        }
    }
}
