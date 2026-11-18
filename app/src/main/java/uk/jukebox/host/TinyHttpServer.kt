package uk.jukebox.host

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * A deliberately tiny HTTP/1.1 server for the guest page on the local Wi-Fi.
 * One request per connection, small bodies only. No external libraries.
 */
class TinyHttpServer(
    private val port: Int,
    private val handler: (Request) -> Response,
) {
    data class Request(
        val method: String,
        val path: String,
        val params: Map<String, String>,
        val remote: String,
    )

    class Response(val status: Int, val contentType: String, val body: ByteArray) {
        companion object {
            fun text(status: Int, s: String, type: String = "text/plain; charset=utf-8") =
                Response(status, type, s.toByteArray(Charsets.UTF_8))
        }
    }

    @Volatile private var socket: ServerSocket? = null
    private var pool: ExecutorService? = null

    val isRunning get() = socket?.isClosed == false

    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        socket = s
        val p = Executors.newFixedThreadPool(8)
        pool = p
        thread(isDaemon = true, name = "jukebox-http") {
            while (!s.isClosed) {
                try {
                    val client = s.accept()
                    p.execute { serve(client) }
                } catch (e: IOException) {
                    if (s.isClosed) break
                }
            }
        }
    }

    fun stop() {
        socket?.close()
        pool?.shutdownNow()
    }

    private fun serve(client: Socket) {
        client.use { c ->
            try {
                c.soTimeout = 10_000
                val input = BufferedInputStream(c.getInputStream())
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                var contentLength = 0
                while (true) {
                    val h = readLine(input) ?: return
                    if (h.isEmpty()) break
                    val colon = h.indexOf(':')
                    if (colon > 0 && h.substring(0, colon).trim().equals("content-length", true)) {
                        contentLength = h.substring(colon + 1).trim().toIntOrNull() ?: 0
                    }
                }
                if (contentLength > MAX_BODY) {
                    write(c, Response.text(413, "Too large")); return
                }
                val body = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(body, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                val target = parts[1]
                val path = target.substringBefore('?')
                val params = parseForm(target.substringAfter('?', "")) +
                    parseForm(String(body, 0, read, Charsets.UTF_8))
                val response = try {
                    handler(Request(parts[0].uppercase(), path, params, c.inetAddress.hostAddress ?: ""))
                } catch (e: Exception) {
                    Response.text(500, "Server error")
                }
                write(c, response)
            } catch (_: IOException) {
                // client went away; nothing to do
            }
        }
    }

    private fun write(c: Socket, r: Response) {
        val head = "HTTP/1.1 ${r.status} ${reason(r.status)}\r\n" +
            "Content-Type: ${r.contentType}\r\n" +
            "Content-Length: ${r.body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        val out = c.getOutputStream()
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(r.body)
        out.flush()
    }

    companion object {
        const val MAX_BODY = 10_000

        fun parseForm(s: String): Map<String, String> =
            s.split('&').filter { it.isNotEmpty() }.associate { pair ->
                val k = pair.substringBefore('=')
                val v = pair.substringAfter('=', "")
                decode(k) to decode(v)
            }

        private fun decode(s: String) =
            try { URLDecoder.decode(s, "UTF-8") } catch (e: IllegalArgumentException) { s }

        private fun readLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b == -1) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
                if (sb.length > 8192) return null
            }
        }

        private fun reason(code: Int) = when (code) {
            200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"
            413 -> "Payload Too Large"; 429 -> "Too Many Requests"; else -> "Error"
        }
    }
}
