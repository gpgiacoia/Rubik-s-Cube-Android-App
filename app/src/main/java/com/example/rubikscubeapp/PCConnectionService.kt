package com.example.rubikscubeapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket

class PCConnectionService {

    private var socket: Socket? = null
    private var connectionJob: Job? = null
    private var writer: PrintWriter? = null

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _receivedData = MutableSharedFlow<String>()
    val receivedData: SharedFlow<String> = _receivedData

    fun connect(serverIp: String, serverPort: Int) {
        if (_connectionState.value != ConnectionState.DISCONNECTED) return

        _connectionState.value = ConnectionState.CONNECTING

        connectionJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                socket = Socket(serverIp, serverPort)
                writer = PrintWriter(socket!!.getOutputStream(), true)
                _connectionState.value = ConnectionState.CONNECTED

                listenForData()

            } catch (e: Exception) {
                disconnect()
            }
        }
    }

    fun sendCommand(command: String) {
        CoroutineScope(Dispatchers.IO).launch {
            if (_connectionState.value == ConnectionState.CONNECTED) {
                writer?.println(command)
            }
        }
    }

    private suspend fun listenForData() {
        socket?.getInputStream()?.let { stream ->
            val reader = BufferedReader(InputStreamReader(stream))
            while (currentCoroutineContext().isActive) {
                try {
                    val line = reader.readLine()
                    if (line != null) {
                        _receivedData.emit(line)
                    } else {
                        break // Server disconnected
                    }
                } catch (e: IOException) {
                    break // Connection lost
                }
            }
        }
    }

    fun disconnect() {
        if (_connectionState.value == ConnectionState.DISCONNECTED) return
        
        _connectionState.value = ConnectionState.DISCONNECTED
        connectionJob?.cancel()
        connectionJob = null
        try {
            writer?.close()
            socket?.close()
        } catch (e: IOException) {
            // Ignore
        }
        writer = null
        socket = null
    }
}
