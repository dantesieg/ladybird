/**
 * Copyright (c) 2023, Andrew Kaster <akaster@serenityos.org>
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

package org.serenityos.ladybird

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.content.Intent
import android.content.res.Configuration
import androidx.activity.OnBackPressedCallback
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.io.path.Path
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.outputStream

class LadybirdActivity : AppCompatActivity() {

    private lateinit var resourceDir: String
    private lateinit var browser: Browser
    private var timerService = TimerExecutorService()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        resourceDir = TransferAssets.transferAssets(this)
        val testFile = File("$resourceDir/res/icons/48x48/app-browser.png")
        if (!testFile.exists())
        {
            ZipFile("$resourceDir/ladybird-assets.zip").use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    val fileName = entry.name
                    val file = File("$resourceDir/$fileName")
                    if (!entry.isDirectory)
                    {
                        val parentFolder = File(file.parent!!)
                        if (!parentFolder.exists())
                            parentFolder.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            file.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
            }

            // curl has some issues with the Android's way of storing certificates.
            // We need to do this in order to make curl happy.
            val certMain = File("$resourceDir/cacert.pem")
            certMain.outputStream().use { output ->
                Files.walk(Path("/system/etc/security/cacerts")).forEach { certPath ->
                    if (!certPath.isDirectory()) {
                        certPath.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
        val userDir = applicationContext.getExternalFilesDir(null)!!.absolutePath
        for (name in listOf("config", "userdata", "cache", "run", "tmp"))
            File(userDir, name).mkdirs()
        // The helper processes ship as lib<Name>.so in the native library directory, which is where they are run from.
        initNativeCode(resourceDir, "Ladybird", timerService, userDir, applicationInfo.nativeLibraryDir)

        // Android leaves an app at 60 Hz unless it asks for more, so ask for the fastest mode at the current resolution.
        display?.let { display ->
            val current = display.mode
            display.supportedModes
                .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
                .maxByOrNull { it.refreshRate }
                ?.let { mode -> window.attributes = window.attributes.also { it.preferredDisplayModeId = mode.modeId } }
        }

        browser = Browser(this)
        browser.openTab(intent.dataString, isPrivate = false)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!browser.handleBack())
                    moveTaskToBack(true)
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let { browser.openTab(it, isPrivate = false) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        browser.onConfigurationChanged()
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onDestroy() {
        browser.dispose()
        disposeNativeCode()
        super.onDestroy()
    }

    private fun scheduleEventLoop() {
        mainExecutor.execute {
            execMainEventLoop()
        }
    }

    private external fun initNativeCode(
        resourceDir: String, tag: String, timerService: TimerExecutorService, userDir: String, nativeLibraryDir: String
    )

    private external fun disposeNativeCode()
    private external fun execMainEventLoop()

    companion object {

        // Used to load the 'ladybird' library on application startup.
        init {
            System.loadLibrary("Ladybird")
        }
    }
}
