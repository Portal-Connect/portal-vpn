package io.github.dovecoteescapee.byedpi.core

import android.util.Log

/**
 * JNI wrapper for the native libbyedpi.so engine compiled with Android NDK.
 */
class ByeDpiProxy {
    companion object {
        private const val TAG = "ByeDpiProxy"

        init {
            try {
                System.loadLibrary("byedpi")
                Log.i(TAG, "libbyedpi.so successfully loaded via System.loadLibrary")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to load libbyedpi.so", e)
            }
        }
    }

    external fun jniCreateSocketWithCommandLine(args: Array<String>): Int
    external fun jniStartProxy(fd: Int): Int
    external fun jniStopProxy(fd: Int): Int
}
