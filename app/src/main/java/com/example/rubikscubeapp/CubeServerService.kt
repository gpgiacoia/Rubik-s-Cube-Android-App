package com.example.rubikscubeapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.ServerSocket

class CubeServerService {

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    enum class ConnectionState {
        DISCONNECTED, LISTENING, CONNECTED
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _receivedData = MutableSharedFlow<String>()
    val receivedData: SharedFlow<String> = _receivedData

    fun start(port: Int) {
        if (serverJob != null) return // Already running

        serverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                serverSocket = ServerSocket(port)
                _connectionState.value = ConnectionState.LISTENING

                while (isActive) {
                    val clientSocket = serverSocket!!.accept() // Blocking call
                    _connectionState.value = ConnectionState.CONNECTED

                    clientSocket.use { // Automatically closes socket
                        val reader = BufferedReader(InputStreamReader(it.inputStream))
                        try {
                            while (isActive) {
                                val line = reader.readLine()
                                if (line != null) {
                                    _receivedData.emit(line)
                                } else {
                                    break // Client disconnected
                                }
                            }
                        } catch (e: IOException) {
                            // Connection lost
                        }
                    }
                    _connectionState.value = ConnectionState.LISTENING
                }
            } catch (e: Exception) {
                // Server stopped or failed
            } finally {
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
    }

    fun stop() {
        serverJob?.cancel() // Stop the coroutine
        serverJob = null
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            // Ignore
        }
    }
}
