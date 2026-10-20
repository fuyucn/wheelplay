package com.shilapi.xcertplay.web

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Answers Tesla's connectivity checks locally when the hotspot has no upstream (no SIM).
 *
 * Root routing DNATs hotspot DNS (UDP 53) to [DNS_PORT] on the shared alias. Check names resolve
 * to the hotspot's own address, which is on-subnet for every client; a hotspot-scoped DNAT sends
 * its port 80 to the web listener, which then replies "online".
 * Other names are forwarded to the phone's own resolvers when it has any, else SERVFAIL.
 * Check names and replies follow DashPlay scripts/onecloud/setup_wizard.py (c00a6b6).
 */
internal object TeslaOfflineChecks {
    const val DNS_PORT = 18053
    private const val TAG = "WheelPlayOffline"
    private const val LOG_FILE = "tesla-offline-checks.log"
    private const val LOG_LIMIT = 256 * 1024L

    private val CONNMAN_NAMES = setOf("connman.vn.cloud.tesla.cn", "connman.vn.tesla.services")
    internal val CHECK_NAMES =
        CONNMAN_NAMES +
            setOf(
                "www.baidu.cn",
                "www.qq.com",
                "www.tesla.cn",
                "www.baidu.com",
                "connectivitycheck.gstatic.com",
                // Generic captive-portal probes; Tesla queried these on the 2023 Moto hotspot.
                "www.tesla.com",
                "www.microsoft.com",
                "www.google.com",
                "www.apple.com",
                "captive.apple.com",
                "www.msftconnecttest.com",
                "msftconnecttest.com",
                "clients3.google.com",
                "www.gstatic.com",
            )
    // Probes that other clients (iPhone, Android) also make: answering them would make those
    // clients believe the hotspot has internet, so only a client that asked for ConnMan gets them.
    private val GENERIC_NAMES =
        setOf(
            "www.microsoft.com",
            "www.google.com",
            "www.apple.com",
            "captive.apple.com",
            "www.msftconnecttest.com",
            "msftconnecttest.com",
            "clients3.google.com",
            "www.gstatic.com",
            "connectivitycheck.gstatic.com",
            "www.baidu.com",
        )
    private const val TESLA_CLIENT_TTL_MS = 5 * 60_000L
    private val teslaClients = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private const val CONNMAN_BODY = "<html>\n<head>\n</head>\n<body>\n</body>\n</html>\n\n"

    data class HttpReply(
        val status: Int,
        val body: String,
        val headers: Map<String, String> = emptyMap(),
    )

    @Volatile private var server: DnsServer? = null
    @Volatile private var logFile: File? = null

    val active
        get() = server != null

    @Synchronized
    fun ensure(context: Context, address: String, portal: String) {
        logFile = File(context.filesDir, LOG_FILE)
        server?.let { if (it.address == address && it.portal == portal && it.alive) return }
        stop()
        server =
            runCatching { DnsServer(context.applicationContext, address, portal) }
                .onFailure { log("dns start failed on $address:$DNS_PORT: $it") }
                .getOrNull()
                ?.also { log("dns started on $address:$DNS_PORT") }
    }

    @Synchronized
    fun stop() {
        server?.let {
            it.close()
            log("dns stopped")
        }
        server = null
    }

    internal fun checkName(host: String?): String? {
        val name = host?.substringBefore(':')?.trimEnd('.')?.lowercase(Locale.ROOT) ?: return null
        return name.takeIf { it in CHECK_NAMES }
    }

    /** True when [name] is a check that should be answered for [client]. */
    internal fun answers(name: String, client: String?, now: Long = System.currentTimeMillis()): Boolean {
        if (client == null) return false
        if (name in CONNMAN_NAMES) teslaClients[client] = now
        if (name !in GENERIC_NAMES) return true
        val seen = teslaClients[client] ?: return false
        return now - seen <= TESLA_CLIENT_TTL_MS
    }

    internal fun forgetClients() = teslaClients.clear()

    /** Null when the request is not a connectivity check or the mode is off. */
    fun httpReply(host: String?, uri: String, client: String?): HttpReply? {
        if (!active) return null
        val name = checkName(host)?.takeIf { answers(it, client) } ?: return null
        val reply = reply(name, uri)
        log("http $client $name$uri -> ${reply.status}")
        return reply
    }

    internal fun reply(name: String, uri: String): HttpReply {
        val path = uri.substringBefore('?')
        return when {
            name in CONNMAN_NAMES ->
                HttpReply(
                    200,
                    CONNMAN_BODY,
                    mapOf("X-ConnMan-Status" to "online", "X-Cache" to "Hit from cloudfront"),
                )
            path == "/generate_204" || path == "/gen_204" -> HttpReply(204, "")
            path == "/connecttest.txt" -> HttpReply(200, "Microsoft Connect Test")
            path == "/ncsi.txt" -> HttpReply(200, "Microsoft NCSI")
            path == "/hotspot-detect.html" || path == "/library/test/success.html" ->
                HttpReply(200, "<HTML><HEAD><TITLE>Success</TITLE></HEAD><BODY>Success</BODY></HTML>")
            else -> HttpReply(200, "<html><body>online</body></html>\n")
        }
    }

    internal fun log(line: String) {
        Log.i(TAG, line)
        val file = logFile ?: return
        runCatching {
            synchronized(this) {
                if (file.length() > LOG_LIMIT) file.delete()
                val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(Date())
                file.appendText("$time $line\n")
            }
        }
    }

    private class DnsServer(private val context: Context, val address: String, val portal: String) {
        private val socket = DatagramSocket(InetSocketAddress(address, DNS_PORT))
        private val forwarders = Executors.newFixedThreadPool(4)
        private val alias = InetAddress.getByName(address).address
        private val portalAddress = InetAddress.getByName(portal).address
        val alive
            get() = !socket.isClosed

        init {
            Thread(::serve, "tesla-offline-dns").apply {
                isDaemon = true
                start()
            }
        }

        fun close() {
            socket.close()
            forwarders.shutdownNow()
        }

        private fun serve() {
            val buffer = ByteArray(1500)
            while (!socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: Exception) {
                    break
                }
                val query = packet.data.copyOf(packet.length)
                val client = packet.socketAddress
                val from = packet.address.hostAddress
                val question = DnsMessage.question(query) ?: continue
                val checked = checkName(question.name)
                if (checked != null && answers(checked, from)) {
                    log("dns $from ${question.name} type=${question.type} -> local")
                    // DashPlay answers checks with a carrier-grade /10 alias, not the private gateway;
                    // some Tesla firmware rejects private answers as offline.
                    val answer = alias.takeIf { question.type == 1 }
                    send(DnsMessage.answer(query, question, answer), client)
                    continue
                }
                runCatching {
                    forwarders.execute {
                        val answer = forward(query)
                        val result = if (answer == null) "servfail" else "forwarded"
                        log("dns $from ${question.name} type=${question.type} -> $result")
                        send(answer ?: DnsMessage.failure(query, question), client)
                    }
                }
            }
        }

        private fun send(bytes: ByteArray, client: SocketAddress) {
            runCatching { socket.send(DatagramPacket(bytes, bytes.size, client)) }
        }

        // Use the phone's own upstream, if any; without one there is nothing to resolve against.
        private fun forward(query: ByteArray): ByteArray? {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val network = connectivity?.activeNetwork ?: return null
            val servers =
                connectivity.getLinkProperties(network)?.dnsServers?.filterIsInstance<Inet4Address>()
                    ?: return null
            for (server in servers) {
                runCatching {
                        DatagramSocket().use { upstream ->
                            network.bindSocket(upstream)
                            upstream.soTimeout = 2000
                            upstream.send(DatagramPacket(query, query.size, server, 53))
                            val buffer = ByteArray(4096)
                            val reply = DatagramPacket(buffer, buffer.size)
                            upstream.receive(reply)
                            buffer.copyOf(reply.length)
                        }
                    }
                    .getOrNull()
                    ?.let {
                        return it
                    }
            }
            return null
        }
    }
}

/** Minimal DNS wire handling for single-question queries; questions carry no compression. */
internal object DnsMessage {
    data class Question(val name: String, val type: Int, val end: Int)

    fun question(query: ByteArray): Question? {
        if (query.size < 17) return null
        // Only standard queries (QR=0, opcode 0) with exactly one question.
        if (query[2].toInt() and 0xF8 != 0) return null
        if (u16(query, 4) != 1) return null
        val labels = mutableListOf<String>()
        var offset = 12
        while (true) {
            if (offset >= query.size) return null
            val length = query[offset].toInt() and 0xFF
            if (length == 0) break
            if (length > 63 || offset + 1 + length > query.size) return null
            labels += String(query, offset + 1, length, Charsets.US_ASCII)
            offset += 1 + length
        }
        offset += 1
        if (offset + 4 > query.size) return null
        return Question(labels.joinToString("."), u16(query, offset), offset + 4)
    }

    /** NOERROR reply; [address] adds one A record, otherwise the answer section is empty. */
    fun answer(query: ByteArray, question: Question, address: ByteArray?): ByteArray {
        val header = header(query, question, rcode = 0, answers = if (address == null) 0 else 1)
        if (address == null) return header
        // Name pointer to the question, type A, class IN, TTL 60, RDLENGTH 4.
        val record = byteArrayOf(0xC0.toByte(), 0x0C, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4) + address
        return header + record
    }

    fun failure(query: ByteArray, question: Question) =
        header(query, question, rcode = 2, answers = 0)

    // Copy ID, RD and the question; set QR, AA and RA; drop any additional records (EDNS).
    private fun header(query: ByteArray, question: Question, rcode: Int, answers: Int): ByteArray {
        val out = query.copyOf(question.end)
        out[2] = (0x84 or (query[2].toInt() and 0x01)).toByte()
        out[3] = (0x80 or rcode).toByte()
        out[6] = 0
        out[7] = answers.toByte()
        for (i in 8 until 12) out[i] = 0
        return out
    }

    private fun u16(bytes: ByteArray, offset: Int) =
        (bytes[offset].toInt() and 0xFF shl 8) or (bytes[offset + 1].toInt() and 0xFF)
}
