package com.example.rubikscubeapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.Socket

class WifiService {

    private var socket: Socket? = null
    private var connectionJob: Job? = null

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _receivedData = MutableSharedFlow<String>()
    val receivedData: SharedFlow<String> = _receivedData

    fun connect(serverIp: String, serverPort: Int) {
        if (_connectionState.value != ConnectionState.DISCONNECTED) {
            return
        }

        _connectionState.value = ConnectionState.CONNECTING

        connectionJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                socket = Socket(serverIp, serverPort)
                _connectionState.value = ConnectionState.CONNECTED

                listenForData()

            } catch (e: Exception) {
                disconnect()
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
                        break // End of stream
                    }
                } catch (e: IOException) {
                    break // Connection lost
                }
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
    }
}