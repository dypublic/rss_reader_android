package com.codex.rssreader

import com.codex.rssreader.update.FallbackDns
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class UpdateDnsTest {
    private val primaryAddress = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1))
    private val fallbackAddress = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 2))

    @Test fun keepsSystemDnsResultWhenAvailable() {
        var fallbackCalls = 0
        val dns = FallbackDns(
            primary = fixedDns(primaryAddress),
            fallback = dns { fallbackCalls += 1; listOf(fallbackAddress) },
        )

        assertEquals(listOf(primaryAddress), dns.lookup("example.com"))
        assertEquals(0, fallbackCalls)
    }

    @Test fun usesEncryptedFallbackWhenSystemDnsCannotResolve() {
        val dns = FallbackDns(
            primary = dns { throw UnknownHostException("system DNS failed") },
            fallback = fixedDns(fallbackAddress),
        )

        assertEquals(listOf(fallbackAddress), dns.lookup("release-assets.githubusercontent.com"))
    }

    @Test fun reportsFailureWhenBothResolversCannotResolve() {
        val dns = FallbackDns(
            primary = dns { emptyList() },
            fallback = dns { throw UnknownHostException("fallback DNS failed") },
        )

        assertThrows(UnknownHostException::class.java) { dns.lookup("missing.example") }
    }

    private fun fixedDns(address: InetAddress) = dns { listOf(address) }

    private fun dns(resolve: (String) -> List<InetAddress>) = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = resolve(hostname)
    }
}
