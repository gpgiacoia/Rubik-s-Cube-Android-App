package com.example.rubikscubeapp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID

@SuppressLint("MissingPermission")
class BluetoothService(context: Context) {

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private var connectionJob: Job? = null

    // Standard SPP UUID for serial communication
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _receivedData = MutableSharedFlow<String>()
    val receivedData: SharedFlow<String> = _receivedData

    fun connect(deviceAddress: String) {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }

        if (_connectionState.value != ConnectionState.DISCONNECTED) {
            return
        }

        _connectionState.value = ConnectionState.CONNECTING

        connectionJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val device = bluetoothAdapter.getRemoteDevice(deviceAddress)
                socket = device.createRfcommSocketToServiceRecord(sppUuid)
                socket?.connect() // This is a blocking call

                inputStream = socket?.inputStream
                outputStream = socket?.outputStream

                _connectionState.value = ConnectionState.CONNECTED

                // Listen for data
                listenForData()

            } catch (e: Exception) {
                disconnect()
            }
        }
    }

    private suspend fun listenForData() {
        val reader = BufferedReader(InputStreamReader(inputStream))
        while (currentCoroutineContext().isActive) {
            try {
                val line = reader.readLine()
                if (line != null) {
                    _receivedData.emit(line)
                } else {
                    break // End of stream
                }
            } catch (e: IOException) {
                // Connection lost
                break
            }
        }
    }

    fun disconnect() {
        if (_connectionState.value == ConnectionState.DISCONNECTED) {
            return
        }
        _connectionState.value = ConnectionState.DISCONNECTED
        connectionJob?.cancel()
        connectionJob = null
        try {
            socket?.close()
        } catch (e: IOException) {
            // Ignore
        }
        socket = null
        inputStream = null
        outputStream = null
    }
}