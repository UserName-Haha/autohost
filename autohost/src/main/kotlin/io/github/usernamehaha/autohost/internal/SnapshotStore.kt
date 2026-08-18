package io.github.usernamehaha.autohost.internal

import io.github.usernamehaha.autohost.Host
import java.io.File
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/** 落盘的内容：上一轮探测的时刻、当时选中的线路、探测成功的线路的延迟。 */
internal class Snapshot(
    val savedAtMillis: Long,
    val current: Host,
    val latencies: Map<Host, Duration>,
)

internal interface SnapshotStore {
    /** 没有缓存返回 null；内容不合法抛 [IOException]。 */
    @Throws(IOException::class)
    fun read(): Snapshot?

    @Throws(IOException::class)
    fun write(snapshot: Snapshot)

    fun clear()
}

/**
 * 按行存储的文本文件：
 * ```
 * autohost 1
 * <保存时刻，epoch 毫秒>
 * <当前线路>
 * <线路> <延迟微秒>
 * ...
 * ```
 * 内容只有几个主机名和数字，不值得为它引入 JSON 库。
 */
internal class FileSnapshotStore(private val file: File) : SnapshotStore {

    override fun read(): Snapshot? {
        if (!file.exists()) return null
        return try {
            decode(file.readLines())
        } catch (e: IllegalArgumentException) {
            throw IOException("缓存内容不合法：${e.message}", e)
        }
    }

    override fun write(snapshot: Snapshot) {
        file.parentFile?.mkdirs()
        // 先写临时文件再改名，进程在写入中途被杀也不会留下半个文件
        val temp = File(file.path + ".tmp")
        temp.writeText(encode(snapshot))
        if (!temp.renameTo(file)) {
            temp.delete()
            throw IOException("无法写入 ${file.path}")
        }
    }

    override fun clear() {
        file.delete()
    }

    internal companion object {
        private const val HEADER = "autohost 1"

        fun encode(snapshot: Snapshot): String = buildString {
            appendLine(HEADER)
            appendLine(snapshot.savedAtMillis)
            appendLine(snapshot.current)
            for ((host, latency) in snapshot.latencies) {
                appendLine("$host ${latency.inWholeMicroseconds}")
            }
        }

        fun decode(lines: List<String>): Snapshot {
            require(lines.size >= 3 && lines[0] == HEADER) { "未知的文件头" }
            val savedAt = requireNotNull(lines[1].toLongOrNull()) { "保存时刻不是数字" }
            val current = Host.parse(lines[2])
            val latencies = LinkedHashMap<Host, Duration>()
            for (line in lines.drop(3)) {
                if (line.isBlank()) continue
                val parts = line.split(' ')
                require(parts.size == 2) { "无法解析的行 \"$line\"" }
                val micros = requireNotNull(parts[1].toLongOrNull()?.takeIf { it >= 0 }) { "延迟不合法 \"$line\"" }
                latencies[Host.parse(parts[0])] = micros.microseconds
            }
            return Snapshot(savedAt, current, latencies)
        }
    }
}
