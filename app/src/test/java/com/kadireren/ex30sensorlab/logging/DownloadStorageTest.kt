package com.kadireren.ex30sensorlab.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStorageTest {
    @Test
    fun displayPathUsesDownloadSubfolder() {
        assertEquals("Download/EX30SensorLab", DownloadStorage.displayPath())
    }

    @Test
    fun folderNameIsStable() {
        assertTrue(DownloadStorage.FOLDER_NAME.isNotBlank())
    }
}
