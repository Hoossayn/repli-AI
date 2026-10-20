package dev.patrickgold.florisboard.repli.openai.auth

import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The loopback redirect target for Sign in with ChatGPT: a one-request HTTP listener bound to
 * 127.0.0.1 on an ephemeral port. Started before the browser opens, closed after the callback
 * (or the timeout). Any other path gets a 404; the callback gets a small page with a
 * "Back to Repli" link ([returnLink]) and the query is handed to the caller.
 */
class LoopbackRedirectServer(
    private val returnLink: String?,
    private val appName: String = "Repli",
) : Closeable {
    private val socket = ServerSocket(0, 1, InetAddress.getByName(ChatGptOAuthConfig.REDIRECT_HOST))
    private val result = AtomicReference<AuthorizationCallback?>(null)
    private val done = CountDownLatch(1)

    val port: Int get() = socket.localPort
    val redirectUri: String get() = ChatGptOAuthConfig.redirectUri(port)

    /** Serves requests until the callback arrives or [timeoutMillis] passes; null on timeout. */
    suspend fun await(timeoutMillis: Long): AuthorizationCallback? = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (result.get() == null) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0 || socket.isClosed) break
            socket.soTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val client = try { socket.accept() } catch (_: SocketTimeoutException) { break } catch (_: Exception) { break }
            client.use(::handle)
        }
        result.get()
    }

    /** Blocking variant for tests and callers without coroutines. */
    fun awaitBlocking(timeoutMillis: Long): AuthorizationCallback? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (result.get() == null) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0 || socket.isClosed) break
            socket.soTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val client = try { socket.accept() } catch (_: Exception) { break }
            client.use(::handle)
        }
        return result.get()
    }

    private fun handle(client: Socket) {
        client.soTimeout = 5_000
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
        val requestLine = reader.readLine() ?: return
        // Drain headers; nothing in them is needed.
        while (true) { val line = reader.readLine(); if (line.isNullOrEmpty()) break }
        val parts = requestLine.split(' ')
        val target = parts.getOrNull(1).orEmpty()
        val path = target.substringBefore('?')
        val output = client.getOutputStream()
        if (parts.getOrNull(0) != "GET" || path != ChatGptOAuthConfig.REDIRECT_PATH) {
            output.write(response(404, "Not found", "<p>Not found.</p>"))
            output.flush()
            return
        }
        val callback = AuthorizationCallback.fromQuery(target.substringAfter('?', ""))
        val body = if (callback.error != null) {
            "<h1>Sign-in not completed</h1><p>You can close this tab and try again in $appName.</p>"
        } else {
            "<h1>You're signed in</h1><p>Return to $appName to finish.</p>" +
                (returnLink?.let { "<p><a class=\"btn\" href=\"${escape(it)}\">Back to $appName</a></p>" } ?: "")
        }
        output.write(response(200, "OK", body))
        output.flush()
        result.compareAndSet(null, callback)
        done.countDown()
    }

    private fun response(status: Int, reason: String, body: String): ByteArray {
        val html = "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">" +
            "<title>$appName</title><style>body{font-family:sans-serif;margin:40px auto;max-width:420px;padding:0 20px;color:#27243A}" +
            ".btn{display:inline-block;padding:12px 20px;border-radius:12px;background:#6654D1;color:#fff;text-decoration:none}</style></head>" +
            "<body>$body</body></html>"
        val bytes = html.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status $reason\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        return head.toByteArray(Charsets.ISO_8859_1) + bytes
    }

    private fun escape(value: String) = value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

    fun waitForCallback(timeout: Long, unit: TimeUnit): Boolean = done.await(timeout, unit)

    override fun close() {
        runCatching { socket.close() }
    }
}
