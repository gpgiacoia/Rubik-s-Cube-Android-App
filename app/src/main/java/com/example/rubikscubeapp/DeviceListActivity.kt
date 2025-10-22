package com.example.rubikscubeapp

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

class DeviceListActivity : AppCompatActivity() {

    private lateinit var listView: ListView

    // Represent either a temporary demo item or a real Bluetooth device
    private sealed class DeviceItem {
        data class Temp(val title: String, val subtitle: String) : DeviceItem()
        data class Real(val device: BluetoothDevice) : DeviceItem()
    }

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

        val realDevices: List<BluetoothDevice> = adapter.bondedDevices?.toList()?.sortedBy { it.name ?: it.address } ?: emptyList()

        // Build the combined list with a temporary demo item at the top
        val items = mutableListOf<DeviceItem>()
        items += DeviceItem.Temp(title = "Rubik's cube solver 1.0", subtitle = "Demo")
        items += realDevices.map { DeviceItem.Real(it) }

        val adapterUi = DeviceItemAdapter(this, items)
        listView.adapter = adapterUi

        listView.setOnItemClickListener { _, _, position, _ ->
            when (val item = adapterUi.getItem(position)) {
                is DeviceItem.Temp -> showConnectedDialogAndNavigate(item.title)
                is DeviceItem.Real -> {
                    // Preserve existing behavior: return the selected device address
                    val device = item.device
                    val data = Intent().apply { putExtra("device_address", device.address) }
                    setResult(Activity.RESULT_OK, data)
                    finish()
                }
                else -> {}
            }
        }
    }

    private fun showConnectedDialogAndNavigate(deviceTitle: String) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_connected_success, null, false)
        view.findViewById<TextView>(R.id.message)?.text = "$deviceTitle is now connected and ready to use."

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(true)
            .create()

        view.findViewById<MaterialButton>(R.id.btnContinue)?.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, ScanCubeActivity::class.java))
        }

        dialog.show()
    }

    private class DeviceItemAdapter(
        context: Context,
        private val items: List<DeviceItem>
    ) : ArrayAdapter<DeviceItem>(context, 0, items) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.list_item_device, parent, false)
            val item = items[position]

            val icon = view.findViewById<ImageView>(R.id.icon)
            val name = view.findViewById<TextView>(R.id.name)
            val subtitle = view.findViewById<TextView>(R.id.subtitle)

            when (item) {
                is DeviceItem.Temp -> {
                    icon.setImageResource(R.drawable.rubiks_cube)
                    name.text = item.title
                    subtitle.text = item.subtitle
                }
                is DeviceItem.Real -> {
                    icon.setImageResource(R.drawable.rubiks_cube)
                    val dev = item.device
                    name.text = dev.name ?: dev.address
                    subtitle.text = "Paired"
                }
            }
            return view
        }

        override fun getItem(position: Int): DeviceItem? = items.getOrNull(position)
        override fun getCount(): Int = items.size
    }
}
