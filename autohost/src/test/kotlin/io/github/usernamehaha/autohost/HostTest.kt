package io.github.usernamehaha.autohost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class HostTest {
    @Test
    fun `解析主机名并规范成小写`() {
        val host = Host.parse(" API.Example.com ")
        assertEquals("api.example.com", host.name)
        assertNull(host.port)
        assertEquals("api.example.com", host.toString())
    }

    @Test
    fun `显式端口会被保留，即使它是默认端口`() {
        assertEquals(8443, Host.parse("api.example.com:8443").port)
        assertEquals(80, Host.parse("api.example.com:80").port)
    }

    @Test
    fun `IPv6 地址往返一致`() {
        val host = Host.parse("[::1]:8080")
        assertEquals("::1", host.name)
        assertEquals(8080, host.port)
        assertEquals(host, Host.parse(host.toString()))
        assertNull(Host.parse("[::1]").port)
    }

    @Test
    fun `带 scheme、path 或不合法的主机名会被拒绝`() {
        for (value in listOf("", "https://api.example.com", "api.example.com/v1", "api.example.com?x=1", "a b.com", "user@host")) {
            assertThrows(value, IllegalArgumentException::class.java) { Host.parse(value) }
        }
    }

    @Test
    fun `相等性由主机名和端口决定`() {
        assertEquals(Host.parse("a.com"), Host.parse("A.com"))
        assertEquals(Host.parse("a.com").hashCode(), Host.parse("A.com").hashCode())
        assert(Host.parse("a.com") != Host.parse("a.com:443"))
    }
}
