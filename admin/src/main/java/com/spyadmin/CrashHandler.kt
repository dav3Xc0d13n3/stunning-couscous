package com.spyadmin

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CrashHandler implements Thread.UncaughtExceptionHandler {

    private val context: Context
    private val defaultHandler: Thread.UncaughtExceptionHandler

    constructor(context: Context) {
        this.context = context
        this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, ex: Throwable) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val crashDir = getCrashDirectory()
        val crashFile = File(crashDir, "crash_$timestamp.txt")

        try {
            crashDir.mkdirs()
            val writer = PrintWriter(FileWriter(crashFile))
            writer.println("=== CRASH REPORT ===")
            writer.println("Time: $timestamp")
            writer.println("Package: ${context.packageName}")
            writer.println("Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            writer.println("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            writer.println("Thread: ${thread.name}")
            writer.println()
            ex.printStackTrace(writer)
            writer.flush()
            writer.close()
            Log.e("CrashHandler", "Crash saved to: ${crashFile.absolutePath}")
        } catch (e: Exception) {
            Log.e("CrashHandler", "Failed to write crash log", e)
        }

        Log.e("CRASH", "Uncaught exception", ex)
        defaultHandler.uncaughtException(thread, ex)
    }

    private fun getCrashDirectory(): File {
        return if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "SpyAdmin/crashes")
        } else {
            File(context.filesDir, "crashes")
        }
    }

    companion object {
        @JvmStatic
        fun init(context: Context) {
            CrashHandler(context.applicationContext)
        }
    }
}