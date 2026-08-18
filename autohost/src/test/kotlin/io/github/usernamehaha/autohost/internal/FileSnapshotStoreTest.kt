package io.github.usernamehaha.autohost.internal

import io.github.usernamehaha.autohost.Host
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileSnapshotStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val a = Host.parse("a.com")
    private val b = Host.parse("[::1]:8443")

    @Test
    fun `没有文件时读到 null`() {
        assertNull(FileSnapshotStore(File(folder.root, "missing")).read())
    }

    @Test
    fun `写入后能原样读回，父目录不存在时自动创建`() {
        val file = File(folder.root, "nested/dir/default")
        val store = FileSnapshotStore(file)
        store.write(Snapshot(1234L, b, mapOf(a to 120.milliseconds, b to 1500.microseconds)))

        val snapshot = store.read()!!
        assertEquals(1234L, snapshot.savedAtMillis)
        assertEquals(b, snapshot.current)
        assertEquals(mapOf(a to 120.milliseconds, b to 1500.microseconds), snapshot.latencies)
        assertFalse(File(file.path + ".tmp").exists())
    }

    @Test
    fun `覆盖写入`() {
        val store = FileSnapshotStore(File(folder.root, "default"))
        store.write(Snapshot(1L, a, mapOf(a to 1.milliseconds)))
        store.write(Snapshot(2L, a, emptyMap()))
        assertEquals(2L, store.read()!!.savedAtMillis)
        assertEquals(emptyMap<Host, Any>(), store.read()!!.latencies)
    }

    @Test
    fun `内容不合法时抛 IOException`() {
        val file = File(folder.root, "default")
        val store = FileSnapshotStore(file)
        val broken = listOf(
            "",
            "garbage",
            "autohost 2\n1\na.com\n",
            "autohost 1\nnot-a-number\na.com\n",
            "autohost 1\n1\nhttps://a.com\n",
            "autohost 1\n1\na.com\na.com\n",
            "autohost 1\n1\na.com\na.com -5\n",
            "autohost 1\n1\na.com\na.com 12 34\n",
        )
        for (content in broken) {
            file.writeText(content)
            assertThrows(content, IOException::class.java) { store.read() }
        }
    }

    @Test
    fun `clear 删除文件`() {
        val file = File(folder.root, "default")
        val store = FileSnapshotStore(file)
        store.write(Snapshot(1L, a, emptyMap()))
        store.clear()
        assertFalse(file.exists())
        store.clear()
    }
}
