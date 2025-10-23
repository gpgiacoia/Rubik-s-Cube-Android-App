package com.example.rubikscubeapp

import java.io.File

// Simple in-memory session store for ephemeral data during a single app run
object SessionStore {
    // Raw CSV text of the last imported cube state for this session
    var cubeCsv: String? = null

    // Temporary CSV file backed in cacheDir (deleted/replaced on next import)
    var tempCsvFile: File? = null

    // UI/session state: current running flag and chosen speed label
    var isRunning: Boolean = false
    var speedLabel: String? = null

    // Timer state (milliseconds)
    var elapsedMs: Long = 0L
    var runningSinceMs: Long? = null
}
