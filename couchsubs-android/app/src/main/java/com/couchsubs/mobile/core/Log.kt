// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thread-safe in-app ring buffer backed by two rotating files (`current.log` and `previous.log`).
 * Normal [append] calls perform no I/O; disk writes happen on a background timer or synchronously on crash.
 */
class AppLogBuffer(
    initialDir: File? = null,
    private val maxLines: Int = DEFAULT_MAX_LINES,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
) {
    private val lock = Any()
    private val fileLock = Any()
    private val ring = ArrayDeque<String>()
    private val pending = ArrayDeque<String>()
    @Volatile private var dir: File? = initialDir

    fun attach(directory: File) {
        dir = directory
    }

    fun append(line: String) {
        synchronized(lock) {
            if (maxLines <= 0) return
            while (ring.size >= maxLines) ring.removeFirst()
            ring.addLast(line)
            while (pending.size >= maxLines) pending.removeFirst()
            pending.addLast(line)
        }
    }

    fun memoryLines(): List<String> = synchronized(lock) { ring.toList() }

    fun clear() {
        synchronized(lock) {
            ring.clear()
            pending.clear()
        }
    }

    fun flushSync() {
        val targetDir = dir ?: return
        synchronized(fileLock) {
            val batch = synchronized(lock) {
                if (pending.isEmpty()) return
                val copy = ArrayList<String>(pending.size)
                copy.addAll(pending)
                pending.clear()
                copy
            }
            try {
                writeBatch(targetDir, batch)
            } catch (_: IOException) {
                synchronized(lock) {
                    val merged = ArrayDeque<String>(minOf(maxLines, batch.size + pending.size))
                    for (line in batch) {
                        if (merged.size >= maxLines) merged.removeFirst()
                        merged.addLast(line)
                    }
                    for (line in pending) {
                        if (merged.size >= maxLines) merged.removeFirst()
                        merged.addLast(line)
                    }
                    pending.clear()
                    pending.addAll(merged)
                }
            }
        }
    }

    /**
     * Returns `previous.log` + `current.log` + any unflushed in-memory lines (newest last),
     * capped at [maxChars] characters.
     */
    fun readAll(maxChars: Int = DEFAULT_READ_CAP): String {
        if (maxChars <= 0) return ""
        return synchronized(fileLock) {
            val out = StringBuilder()
            val targetDir = dir
            if (targetDir != null) {
                appendFileTail(out, File(targetDir, PREVIOUS_FILE), maxChars)
                appendFileTail(out, File(targetDir, CURRENT_FILE), maxChars)
                val unflushed = synchronized(lock) { pending.toList() }
                for (line in unflushed) {
                    appendChunk(out, line, maxChars)
                    appendChunk(out, "\n", maxChars)
                }
            } else {
                val snapshot = synchronized(lock) { ring.toList() }
                for (line in snapshot) {
                    appendChunk(out, line, maxChars)
                    appendChunk(out, "\n", maxChars)
                }
            }
            out.toString()
        }
    }

    private fun writeBatch(targetDir: File, batch: List<String>) {
        if (!targetDir.exists()) targetDir.mkdirs()
        val current = File(targetDir, CURRENT_FILE)
        val previous = File(targetDir, PREVIOUS_FILE)
        var currentSize = if (current.isFile) current.length() else 0L
        var stream: FileOutputStream? = null
        try {
            for (line in batch) {
                val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
                if (currentSize > 0L && currentSize + bytes.size > maxFileBytes) {
                    stream?.flush()
                    stream?.close()
                    stream = null
                    rotate(current, previous)
                    currentSize = 0L
                }
                if (stream == null) stream = FileOutputStream(current, true)
                stream.write(bytes)
                currentSize += bytes.size
            }
            stream?.flush()
        } finally {
            runCatching { stream?.close() }
        }
    }

    private fun rotate(current: File, previous: File) {
        if (!current.exists()) return
        if (previous.exists()) previous.delete()
        if (!current.renameTo(previous)) {
            current.copyTo(previous, overwrite = true)
            current.delete()
        }
    }

    private fun appendFileTail(out: StringBuilder, file: File, maxChars: Int) {
        if (!file.isFile) return
        try {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                val buf = CharArray(4096)
                while (true) {
                    val count = reader.read(buf)
                    if (count < 0) break
                    out.append(buf, 0, count)
                    if (out.length > maxChars) out.delete(0, out.length - maxChars)
                }
            }
        } catch (_: IOException) {
            // Best-effort read; never fail report generation on log read errors.
        }
    }

    private fun appendChunk(out: StringBuilder, text: String, maxChars: Int) {
        if (text.length >= maxChars) {
            out.setLength(0)
            out.append(text, text.length - maxChars, text.length)
            return
        }
        out.append(text)
        if (out.length > maxChars) out.delete(0, out.length - maxChars)
    }

    companion object {
        const val DEFAULT_MAX_LINES = 3000
        const val DEFAULT_MAX_FILE_BYTES = 1024L * 1024L
        const val DEFAULT_READ_CAP = 200_000
        const val CURRENT_FILE = "current.log"
        const val PREVIOUS_FILE = "previous.log"
    }
}

/**
 * Drop-in facade for `android.util.Log` that forwards to the platform logger and mirrors
 * every entry into an in-app rotating ring buffer (for devices such as HONOR/Huawei that
 * suppress `logcat --pid`).
 */
object Log {
    const val VERBOSE = 2
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6
    const val ASSERT = 7

    private const val FLUSH_INTERVAL_SECONDS = 3L
    private const val MAX_STACK_FRAMES = 5
    private val timeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS", Locale.ROOT)

    private val buffer = AppLogBuffer()
    private val initialized = AtomicBoolean(false)
    @Volatile private var scheduler: ScheduledExecutorService? = null

    internal var platformLog: (level: Char, tag: String?, msg: String, tr: Throwable?) -> Int = ::forwardToAndroid
    internal var platformIsLoggable: (tag: String?, level: Int) -> Boolean = { tag, level ->
        android.util.Log.isLoggable(tag, level)
    }

    fun init(filesDir: File) {
        if (!initialized.compareAndSet(false, true)) return
        buffer.attach(File(filesDir, "applog"))
        val exec = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "CouchSubs applog").apply { isDaemon = true }
        }
        scheduler = exec
        exec.scheduleWithFixedDelay(
            { runCatching { buffer.flushSync() } },
            FLUSH_INTERVAL_SECONDS,
            FLUSH_INTERVAL_SECONDS,
            TimeUnit.SECONDS,
        )
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                e("UncaughtException", "Uncaught exception on ${thread.name}", error)
                buffer.flushSync()
            } catch (_: Throwable) {
                // Flushing the log must never prevent the previous crash handler from running.
            } finally {
                previous?.uncaughtException(thread, error)
            }
        }
    }

    fun flushSync() = buffer.flushSync()

    fun readAll(maxChars: Int = AppLogBuffer.DEFAULT_READ_CAP): String = buffer.readAll(maxChars)

    internal fun memoryLines(): List<String> = buffer.memoryLines()

    internal fun clearMemory() = buffer.clear()

    fun v(tag: String?, msg: String): Int = record('V', tag, msg, null)
    fun v(tag: String?, msg: String?, tr: Throwable?): Int = record('V', tag, msg.orEmpty(), tr)

    fun d(tag: String?, msg: String): Int = record('D', tag, msg, null)
    fun d(tag: String?, msg: String?, tr: Throwable?): Int = record('D', tag, msg.orEmpty(), tr)

    fun i(tag: String?, msg: String): Int = record('I', tag, msg, null)
    fun i(tag: String?, msg: String?, tr: Throwable?): Int = record('I', tag, msg.orEmpty(), tr)

    fun w(tag: String?, msg: String): Int = record('W', tag, msg, null)
    fun w(tag: String?, msg: String?, tr: Throwable?): Int = record('W', tag, msg.orEmpty(), tr)
    fun w(tag: String?, tr: Throwable?): Int = record('W', tag, "", tr)

    fun e(tag: String?, msg: String): Int = record('E', tag, msg, null)
    fun e(tag: String?, msg: String?, tr: Throwable?): Int = record('E', tag, msg.orEmpty(), tr)

    fun isLoggable(tag: String?, level: Int): Boolean = try {
        platformIsLoggable(tag, level)
    } catch (_: Throwable) {
        false
    }

    private fun record(level: Char, tag: String?, msg: String, tr: Throwable?): Int {
        buffer.append(formatLine(level, tag, msg, tr))
        return try {
            platformLog(level, tag, msg, tr)
        } catch (_: Throwable) {
            0
        }
    }

    internal fun formatLine(
        level: Char,
        tag: String?,
        msg: String,
        tr: Throwable?,
        nowMillis: Long = System.currentTimeMillis(),
    ): String {
        val timestamp = timeFormatter.format(Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()))
        val safeTag = tag.orEmpty()
        val safeMsg = sanitizeOneLine(msg)
        val sb = StringBuilder(timestamp.length + safeTag.length + safeMsg.length + 16)
        sb.append(timestamp).append(' ').append(level).append('/').append(safeTag).append(": ").append(safeMsg)
        if (tr != null) {
            sb.append(" | ").append(sanitizeOneLine(tr.toString()))
            val frames = tr.stackTrace
            val count = minOf(frames.size, MAX_STACK_FRAMES)
            for (index in 0 until count) {
                sb.append(" | at ").append(frames[index])
            }
        }
        return sb.toString()
    }

    private fun sanitizeOneLine(text: String): String =
        text.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')

    private fun forwardToAndroid(level: Char, tag: String?, msg: String, tr: Throwable?): Int = when (level) {
        'V' -> if (tr != null) android.util.Log.v(tag, msg, tr) else android.util.Log.v(tag, msg)
        'D' -> if (tr != null) android.util.Log.d(tag, msg, tr) else android.util.Log.d(tag, msg)
        'I' -> if (tr != null) android.util.Log.i(tag, msg, tr) else android.util.Log.i(tag, msg)
        'W' -> if (tr != null) android.util.Log.w(tag, msg, tr) else android.util.Log.w(tag, msg)
        'E' -> if (tr != null) android.util.Log.e(tag, msg, tr) else android.util.Log.e(tag, msg)
        else -> 0
    }
}
