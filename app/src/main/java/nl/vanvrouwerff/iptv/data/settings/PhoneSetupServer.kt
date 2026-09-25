package nl.vanvrouwerff.iptv.data.settings

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * Tiny LAN web server so the source can be typed on a phone instead of with the remote.
 * The TV shows a QR code to `http://<tv-ip>:<port>/<token>`; the phone gets a form, and
 * a submit lands in [submission] for the settings screen to pick up. The random token
 * keeps other devices on the network from stumbling onto the form. Runs only while a
 * screen that shows the QR is open (reference counted via [start]/[stop]).
 */
object PhoneSetupServer {

    data class Submission(
        val type: String,
        val m3uUrl: String,
        val host: String,
        val username: String,
        val password: String,
    )

    private val _submission = MutableStateFlow<Submission?>(null)
    /** Latest form sent from a phone; consume with [consume] once applied. */
    val submission: StateFlow<Submission?> = _submission.asStateFlow()

    private var socket: ServerSocket? = null
    private var thread: Thread? = null
    private var users = 0
    private var token: String = ""

    /** Starts (or joins) the server; returns the URL to encode in the QR, or null offline. */
    @Synchronized
    fun start(): String? {
        users++
        val address = lanAddress() ?: return null
        if (socket == null) {
            token = newToken()
            val server = runCatching { ServerSocket(0) }.getOrElse {
                Log.w(TAG, "could not open server socket", it)
                return null
            }
            socket = server
            thread = Thread({ acceptLoop(server) }, "phone-setup").apply {
                isDaemon = true
                start()
            }
        }
        return "http://$address:${socket!!.localPort}/$token"
    }

    @Synchronized
    fun stop() {
        users = (users - 1).coerceAtLeast(0)
        if (users > 0) return
        runCatching { socket?.close() }
        socket = null
        thread = null
    }

    fun consume() {
        _submission.value = null
    }

    val isRunning: Boolean get() = socket != null

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = runCatching { server.accept() }.getOrNull() ?: break
            runCatching { client.use { handle(it) } }
                .onFailure { Log.w(TAG, "request failed", it) }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 10_000
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        val requestLine = reader.readLine() ?: return
        val parts = requestLine.split(" ")
        val method = parts.getOrNull(0).orEmpty()
        val path = parts.getOrNull(1).orEmpty().substringBefore('?').trim('/')
        var contentLength = 0
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        val out = client.getOutputStream()
        fun respond(status: String, html: String) {
            val bytes = html.toByteArray(Charsets.UTF_8)
            out.write(
                ("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(),
            )
            out.write(bytes)
            out.flush()
        }
        if (path != token) {
            respond("404 Not Found", page("<p>Niet gevonden.</p>"))
            return
        }
        when (method) {
            "GET" -> respond("200 OK", page(FORM))
            "POST" -> {
                val body = CharArray(contentLength.coerceIn(0, 16_384))
                var read = 0
                while (read < body.size) {
                    val n = reader.read(body, read, body.size - read)
                    if (n < 0) break
                    read += n
                }
                val fields = parseForm(String(body, 0, read))
                _submission.value = Submission(
                    type = fields["type"].orEmpty().ifBlank { "xtream" },
                    m3uUrl = fields["m3u"].orEmpty().trim(),
                    host = fields["host"].orEmpty().trim(),
                    username = fields["username"].orEmpty().trim(),
                    password = fields["password"].orEmpty(),
                )
                respond("200 OK", page("<h2>Ontvangen</h2><p>Kijk op je tv: de gegevens staan nu in Instellingen. Test de verbinding en sla op.</p>"))
            }
            else -> respond("405 Method Not Allowed", page("<p>Niet ondersteund.</p>"))
        }
    }

    internal fun parseForm(body: String): Map<String, String> = body.split('&')
        .mapNotNull { pair ->
            val key = pair.substringBefore('=', "")
            if (key.isEmpty()) null
            else URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
        }
        .toMap()

    private fun lanAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    private fun newToken(): String {
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
        val random = SecureRandom()
        return (1..6).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    private fun page(content: String) = """
        <!doctype html><html lang="nl"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>IPTV Player instellen</title>
        <style>
          body{font-family:system-ui,sans-serif;background:#0A0A0C;color:#F4F4F6;margin:0;padding:24px;max-width:480px}
          h1,h2{font-size:1.3rem} label{display:block;margin:14px 0 4px;color:#B4B7C0}
          input[type=text],input[type=password],input[type=url]{width:100%;box-sizing:border-box;padding:12px;border-radius:10px;border:1px solid #333;background:#1D1F26;color:#fff;font-size:1rem}
          .row{display:flex;gap:16px;margin-top:8px} button{margin-top:20px;width:100%;padding:14px;border:0;border-radius:10px;background:#FF3B5C;color:#fff;font-size:1rem;font-weight:600}
        </style></head><body>$content</body></html>
    """.trimIndent()

    private val FORM = """
        <h1>Bron instellen</h1>
        <form method="post">
          <div class="row">
            <label><input type="radio" name="type" value="xtream" checked> Xtream Codes</label>
            <label><input type="radio" name="type" value="m3u"> M3U-URL</label>
          </div>
          <label for="host">Host (Xtream)</label><input type="text" id="host" name="host" placeholder="http://provider.example:8080" autocapitalize="off">
          <label for="username">Gebruikersnaam</label><input type="text" id="username" name="username" autocapitalize="off">
          <label for="password">Wachtwoord</label><input type="password" id="password" name="password">
          <label for="m3u">M3U-URL</label><input type="url" id="m3u" name="m3u" placeholder="http://…/playlist.m3u">
          <button type="submit">Naar de tv sturen</button>
        </form>
    """.trimIndent()

    private const val TAG = "PhoneSetupServer"
}
