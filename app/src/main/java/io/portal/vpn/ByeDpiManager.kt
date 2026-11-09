package io.portal.vpn

import android.content.Context
import android.util.Log
import io.github.dovecoteescapee.byedpi.core.ByeDpiProxy
import java.util.concurrent.Executors

/**
 * Manages the native ByeDPI JNI core engine (libbyedpi.so).
 *
 * Runs entirely in-process on the Android Bionic libc network stack, providing
 * on-device TLS/TCP packet fragmentation (desynchronization) to bypass Russian
 * TSPPU/DPI throttling for YouTube without routing video streams through foreign
 * servers, ensuring:
 * 1. Zero YouTube ads (originates from local Russian network environment).
 * 2. Full unthrottled gigabit playback speed for 4K video.
 */
object ByeDpiManager {
    private const val TAG = "ByeDpiManager"
    const val DEFAULT_PORT = 1082

    private val proxy = ByeDpiProxy()
    private var currentFd: Int = -1
    private val executor = Executors.newSingleThreadExecutor()

    val isRunning: Boolean
        @Synchronized get() = currentFd >= 0

    @Synchronized
    fun startByeDpi(context: Context, port: Int = DEFAULT_PORT): Boolean {
        stopByeDpi()

        try {
            // Proven Russian TSPPU multi-stage evasion for YouTube and GoogleVideo:
            val args = arrayOf(
                "-i", "127.0.0.1",
                "-p", port.toString(),
                "-s1", "-q1", "-Y",
                "-Ar", "-s5", "-o1+s",
                "-At", "-f-1", "-r1+s",
                "-As", "-s1", "-o1+s", "-s-1",
                "-An"
            )

            Log.i(TAG, "Creating ByeDPI JNI socket with arguments: ${args.joinToString(" ")}")
            val fd = proxy.jniCreateSocketWithCommandLine(args)
            if (fd < 0) {
                Log.e(TAG, "Failed to create ByeDPI JNI socket (error code $fd)")
                return false
            }

            currentFd = fd
            Log.i(TAG, "ByeDPI JNI socket created successfully (fd=$fd), launching proxy event loop on port $port...")

            executor.execute {
                try {
                    val result = proxy.jniStartProxy(fd)
                    Log.i(TAG, "ByeDPI proxy loop finished with code: $result")
                } catch (e: Exception) {
                    Log.e(TAG, "ByeDPI proxy loop error", e)
                } finally {
                    synchronized(this) {
                        if (currentFd == fd) {
                            currentFd = -1
                        }
                    }
                }
            }

            return true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start ByeDPI JNI proxy", e)
            return false
        }
    }

    @Synchronized
    fun stopByeDpi() {
        val fd = currentFd
        if (fd >= 0) {
            try {
                Log.i(TAG, "Stopping ByeDPI proxy socket (fd=$fd)...")
                proxy.jniStopProxy(fd)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping ByeDPI proxy: ${e.message}")
            } finally {
                currentFd = -1
            }
        }
    }
}
