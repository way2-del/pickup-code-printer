/** 通过反射调用 Shizuku.newProcess（API 13+ 对该方法做了限制）。 */
package com.pickup.print.shizuku

import android.util.Log
import rikka.shizuku.Shizuku
import java.io.InputStream
import java.util.concurrent.TimeUnit

object ShizukuShell {
    private const val TAG = "ShizukuShell"

    fun exec(args: Array<String>, timeoutSec: Long = 8): String? {
        return try {
            val process = newProcess(args) ?: return null
            val text = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            process.waitFor(timeoutSec, TimeUnit.SECONDS)
            text
        } catch (e: Exception) {
            Log.w(TAG, "exec failed: ${args.joinToString(" ")} ${e.message}")
            null
        }
    }

    fun execBytes(args: Array<String>, timeoutSec: Long = 8): ByteArray? {
        return try {
            val process = newProcess(args) ?: return null
            val bytes = process.inputStream.readBytes()
            process.waitFor(timeoutSec, TimeUnit.SECONDS)
            bytes
        } catch (e: Exception) {
            Log.w(TAG, "execBytes failed: ${args.joinToString(" ")} ${e.message}")
            null
        }
    }

    private fun newProcess(args: Array<String>): Process? {
        // 1) 公开 API（旧版）
        try {
            val m = Shizuku::class.java.getMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            @Suppress("UNCHECKED_CAST")
            return m.invoke(null, args, null, null) as? Process
        } catch (_: Exception) {
        }
        // 2) 私有方法反射
        try {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            m.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            return m.invoke(null, args, null, null) as? Process
        } catch (e: Exception) {
            Log.w(TAG, "newProcess reflect failed: ${e.message}")
        }
        return null
    }

    fun openStream(args: Array<String>): InputStream? {
        return try {
            newProcess(args)?.inputStream
        } catch (_: Exception) {
            null
        }
    }
}
