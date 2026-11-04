package dev.willy1236.hanglisten

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlin.random.Random

/**
 * 把 Twitch 頻道轉成「只有音訊」的 HLS 播放清單網址。
 *
 * 流程：GQL PlaybackAccessToken 取 token/sig → usher 主播放清單 → 挑出 audio_only 變體。
 * 直接播 audio_only 的媒體播放清單，而不是丟主清單給 ExoPlayer，
 * 因為 ExoPlayer 的 HLS 在同時有影像變體時會把純音訊變體濾掉。
 *
 * 注意：這是 Twitch 網頁播放器用的非官方介面，Twitch 改版就可能失效。
 */
object TwitchResolver {

    private const val GQL_URL = "https://gql.twitch.tv/gql"

    // Twitch 網頁版公開使用的 Client-ID（streamlink 等工具也用這個）
    private const val CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

    private const val QUERY =
        "query PlaybackAccessToken_Template(\$login: String!, \$isLive: Boolean!, \$vodID: ID!, " +
            "\$isVod: Boolean!, \$playerType: String!) { " +
            "streamPlaybackAccessToken(channelName: \$login, params: {platform: \"web\", " +
            "playerBackend: \"mediaplayer\", playerType: \$playerType}) @include(if: \$isLive) " +
            "{ value signature __typename } " +
            "videoPlaybackAccessToken(id: \$vodID, params: {platform: \"web\", " +
            "playerBackend: \"mediaplayer\", playerType: \$playerType}) @include(if: \$isVod) " +
            "{ value signature __typename } }"

    private val LOGIN_RE = Regex("^[a-z0-9_]{3,25}$")
    private val BANDWIDTH_RE = Regex("BANDWIDTH=(\\d+)")

    /** 接受 `shroud`、`twitch.tv/shroud`、`https://www.twitch.tv/shroud?foo` 這幾種寫法。 */
    fun normalizeChannel(input: String): String? {
        val trimmed = input.trim().trimEnd('/')
        val name = trimmed
            .substringAfter("twitch.tv/", trimmed)
            .substringBefore('?')
            .substringBefore('/')
            .lowercase()
        return name.takeIf { LOGIN_RE.matches(it) }
    }

    suspend fun resolveAudioUrl(channel: String): String = withContext(Dispatchers.IO) {
        val (token, sig) = fetchAccessToken(channel)

        val usherUrl = Uri.parse("https://usher.ttvnw.net/api/channel/hls/$channel.m3u8")
            .buildUpon()
            .appendQueryParameter("token", token)
            .appendQueryParameter("sig", sig)
            .appendQueryParameter("allow_source", "true")
            .appendQueryParameter("allow_audio_only", "true")
            .appendQueryParameter("fast_bread", "true")
            .appendQueryParameter("player_backend", "mediaplayer")
            .appendQueryParameter("playlist_include_framerate", "true")
            .appendQueryParameter("supported_codecs", "avc1")
            .appendQueryParameter("p", Random.nextInt(1_000_000, 9_999_999).toString())
            .build()
            .toString()

        val (code, body) = request(usherUrl)
        if (code == 404) throw OfflineException("$channel 目前沒有開台")
        if (code !in 200..299) throw IOException("usher HTTP $code")
        pickAudioOnly(body, usherUrl)
    }

    private fun fetchAccessToken(channel: String): Pair<String, String> {
        val payload = JSONObject()
            .put("operationName", "PlaybackAccessToken_Template")
            .put("query", QUERY)
            .put(
                "variables",
                JSONObject()
                    .put("isLive", true)
                    .put("login", channel)
                    .put("isVod", false)
                    .put("vodID", "")
                    .put("playerType", "site"),
            )

        val (code, body) = request(GQL_URL, payload.toString())
        if (code !in 200..299) throw IOException("GQL HTTP $code")

        val json = JSONObject(body)
        json.optJSONArray("errors")?.let { errors ->
            if (errors.length() > 0) {
                throw IOException("GQL：" + errors.getJSONObject(0).optString("message"))
            }
        }
        val token = json.optJSONObject("data")?.optJSONObject("streamPlaybackAccessToken")
            ?: throw IOException("找不到頻道 $channel")
        return token.getString("value") to token.getString("signature")
    }

    /** 優先挑 audio_only；沒有的話退而求其次挑頻寬最低的變體。 */
    private fun pickAudioOnly(master: String, baseUrl: String): String {
        val lines = master.lines()
        var fallback: Pair<Long, String>? = null

        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue
            val uri = lines.asSequence()
                .drop(i + 1)
                .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                ?: continue

            if (line.contains("audio_only")) return URI(baseUrl).resolve(uri.trim()).toString()

            val bandwidth = BANDWIDTH_RE.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: Long.MAX_VALUE
            if (fallback == null || bandwidth < fallback.first) fallback = bandwidth to uri.trim()
        }

        val chosen = fallback?.second ?: throw IOException("播放清單裡沒有可用的串流")
        return URI(baseUrl).resolve(chosen).toString()
    }

    /** body 為 null 時發 GET，否則發 JSON POST。回傳 (狀態碼, 內容)。 */
    private fun request(url: String, body: String? = null): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Client-ID", CLIENT_ID)
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to text
        } finally {
            conn.disconnect()
        }
    }
}
