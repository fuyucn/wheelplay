package com.shilapi.xcertplay.web

import org.junit.Assert.*
import org.junit.Test

class TeslaOfflineChecksTest {
    // Standard query with RD set and an EDNS OPT additional record, as dig sends.
    private fun query(name: String, type: Int): ByteArray {
        val labels = name.split('.').flatMap { listOf(it.length.toByte()) + it.toByteArray().toList() }
        val opt = listOf<Byte>(0, 0, 41, 0x10, 0, 0, 0, 0, 0, 0, 0)
        return (listOf<Byte>(0x12, 0x34, 0x01, 0x20, 0, 1, 0, 0, 0, 0, 0, 1) + labels +
            listOf<Byte>(0, 0, type.toByte(), 0, 1) + opt).toByteArray()
    }

    @Test fun checkNamesResolveToTheAliasAndDropAdditionalRecords() {
        val query = query("Connman.VN.Tesla.Services", 1)
        val question = DnsMessage.question(query)!!
        assertEquals("connman.vn.tesla.services", TeslaOfflineChecks.checkName(question.name))
        val reply = DnsMessage.answer(query, question, byteArrayOf(100, 96, 0, 1))
        assertEquals(0x12, reply[0].toInt())
        assertEquals(0x85, reply[2].toInt() and 0xFF) // QR, AA, RD copied
        assertEquals(0x80, reply[3].toInt() and 0xFF) // RA, NOERROR
        assertEquals(1, reply[7].toInt())
        assertEquals(0, reply[11].toInt())
        assertArrayEquals(byteArrayOf(100, 96, 0, 1), reply.copyOfRange(reply.size - 4, reply.size))
    }

    @Test fun nonAQueriesGetAnEmptyAnswerAndFailuresAreServfail() {
        val query = query("www.baidu.com", 28)
        val question = DnsMessage.question(query)!!
        assertEquals(28, question.type)
        assertEquals(0, DnsMessage.answer(query, question, null)[7].toInt())
        assertEquals(0x82, DnsMessage.failure(query, question)[3].toInt() and 0xFF)
    }

    @Test fun malformedOrNonQueryPacketsAreIgnored() {
        assertNull(DnsMessage.question(ByteArray(5)))
        val response = query("www.qq.com", 1).also { it[2] = 0x81.toByte() }
        assertNull(DnsMessage.question(response))
        assertNull(DnsMessage.question(query("www.qq.com", 1).copyOf(20)))
    }

    @Test fun httpRepliesMatchConnmanAndGeneratedChecks() {
        val connman = TeslaOfflineChecks.reply("connman.vn.cloud.tesla.cn", "/online/status.html")
        assertEquals(200, connman.status)
        assertEquals("online", connman.headers["X-ConnMan-Status"])
        assertEquals(204, TeslaOfflineChecks.reply("connectivitycheck.gstatic.com", "/generate_204?x=1").status)
        assertEquals(200, TeslaOfflineChecks.reply("www.qq.com", "/").status)
        assertNull(TeslaOfflineChecks.checkName("example.com"))
        assertEquals("www.tesla.cn", TeslaOfflineChecks.checkName("WWW.TESLA.CN.:80"))
    }

    @Test fun inactiveModeNeverInterceptsHttp() {
        assertNull(TeslaOfflineChecks.httpReply("connman.vn.tesla.services", "/", "192.168.43.2"))
    }

    @Test fun dnsRulesAreScopedToTheHotspotAndAlias() {
        val plan = RootHttpPlan("100.96.0.1", 8080, listOf(HttpDownstream("ap0", 7, "192.168.43.1/24")),
            dnsPort = TeslaOfflineChecks.DNS_PORT)
        val commands = plan.commands().map { it.apply }
        assertTrue(commands.any { it.contains("-t nat -I PREROUTING -i ap0 -p udp --dport 53") &&
            it.endsWith("--to-destination 100.96.0.1:18053") })
        assertTrue(commands.any { it.contains("INPUT -i ap0 -d 100.96.0.1/32 -p udp --dport 18053") })
        assertTrue(commands.any { it.contains("OUTPUT -o ap0 -s 100.96.0.1/32 -d 192.168.43.1/24 -p udp --sport 18053") })
        assertFalse(RootHttpPlan("100.96.0.1", 8080, plan.downstreams).commands().any { it.apply.contains("udp") })
    }

    @Test fun hotspotPortalPortIsRedirectedWithoutACustomAddress() {
        val plan = RootHttpPlan("100.96.0.1", 8080, listOf(HttpDownstream("wlan0", 7, "10.190.175.88/24")),
            dnsPort = TeslaOfflineChecks.DNS_PORT, portalAddress = "10.190.175.88")
        val commands = plan.commands().map { it.apply }
        assertTrue(commands.any { it.contains("-t nat -I PREROUTING -i wlan0 -d 10.190.175.88/32 -p tcp --dport 80") &&
            it.endsWith("--to-destination :8080") })
        assertTrue(commands.any { it.contains("INPUT -i wlan0 -d 10.190.175.88/32 -p tcp --dport 8080") })
        assertTrue(commands.any { it.contains("OUTPUT -o wlan0 -s 10.190.175.88/32 -d 10.190.175.88/24 -p tcp -m conntrack --ctstate ESTABLISHED") })
        assertFalse(plan.copy(portalAddress = "100.96.0.1").commands().any { it.apply.contains("wheelplay-portal") })
    }

    @Test fun genericProbesAreOnlyAnsweredForClientsThatAskedForConnman() {
        TeslaOfflineChecks.forgetClients()
        assertFalse(TeslaOfflineChecks.answers("captive.apple.com", "10.0.0.5", now = 0))
        assertTrue(TeslaOfflineChecks.answers("connman.vn.tesla.services", "10.0.0.9", now = 0))
        assertTrue(TeslaOfflineChecks.answers("captive.apple.com", "10.0.0.9", now = 1000))
        assertFalse(TeslaOfflineChecks.answers("captive.apple.com", "10.0.0.9", now = 6 * 60_000L))
        assertFalse(TeslaOfflineChecks.answers("captive.apple.com", "10.0.0.5", now = 1000))
        assertTrue(TeslaOfflineChecks.answers("www.tesla.com", "10.0.0.5", now = 1000))
    }
}
