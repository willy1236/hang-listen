package dev.willy1236.hanglisten

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder

/**
 * 把 YouTube 直播轉成「只有音訊」的 HLS 播放清單網址。
 *
 * - 頻道（`@handle`、`channel/UC…`）先讀它的 `/live` 頁面，找出現在在播的影片；沒在播就是未開台。
 * - 影片的播放資訊交給 NewPipeExtractor 解析：YouTube 的播放器介面常改，自己維護撐不久。
 * - 直播的 HLS 主清單裡音訊與影像是分開的，挑出音訊那份直接播，就不會下載影像片段。
 *
 * 注意：NewPipeExtractor 用的也是非官方介面，YouTube 改版時要升級它的版本。
 */
object YouTubeResolver {

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

    private val HANDLE_RE = Regex("^@[^/?#\\s]{3,100}$")
    private val VIDEO_ID_RE = Regex("^[A-Za-z0-9_-]{11}$")
    private val NAME_RE = Regex("^[^/?#\\s]+$")
    private val CANONICAL_WATCH_RE =
        Regex("<link rel=\"canonical\" href=\"https://www\\.youtube\\.com/watch\\?v=([A-Za-z0-9_-]{11})\"")

    private val VIDEO_PATHS = setOf("live", "shorts", "embed", "v")
    private val CHANNEL_PATHS = setOf("channel", "c", "user")

    init {
        NewPipe.init(HttpDownloader)
    }

    /**
     * 接受 `@handle`、`youtube.com/@handle(/live)`、`youtube.com/channel/UC…`、
     * `youtube.com/watch?v=ID`、`youtube.com/live/ID`、`youtu.be/ID` 等寫法，
     * 回傳 [StreamSource.YouTube.path]；看不出是 YouTube 就回傳 null。
     */
    fun normalizePath(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.startsWith("@")) return handle(trimmed.substringBefore('/'))

        val uri = runCatching { URI(if ("://" in trimmed) trimmed else "https://$trimmed") }.getOrNull()
            ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
        val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }.map(::decode)
        val first = segments.firstOrNull() ?: return null
        val second = segments.getOrNull(1)

        return when (host) {
            "youtu.be" -> video(first)
            "youtube.com" -> when {
                first == "watch" -> video(queryParam(uri, "v"))
                first in VIDEO_PATHS -> video(second)
                first.startsWith("@") -> handle(first)
                first in CHANNEL_PATHS && second != null && NAME_RE.matches(second) -> "$first/$second"
                else -> null
            }
            else -> null
        }
    }

    suspend fun resolveAudioUrl(path: String): String = withContext(Dispatchers.IO) {
        val videoId = path.removePrefix("watch?v=").takeIf { it != path } ?: findLiveVideo(path)
        val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
        try {
            extractor.fetchPage()
        } catch (e: SignInConfirmNotBotException) {
            throw IOException("YouTube 暫時擋下這個網路的匿名存取", e)
        } catch (e: ContentNotAvailableException) {
            // 排定時間、還沒開始的直播
            if (e.message.orEmpty().contains("LIVE_STREAM_OFFLINE")) throw OfflineException("直播還沒開始")
            throw IOException(e.message, e)
        }
        if (extractor.streamType != StreamType.LIVE_STREAM) throw OfflineException("目前沒有在直播")
        val master = extractor.hlsUrl.ifEmpty { throw IOException("YouTube 沒有提供 HLS 串流") }
        pickAudio(master)
    }

    private fun handle(raw: String) = raw.lowercase().takeIf { HANDLE_RE.matches(it) }

    private fun video(id: String?) = id?.takeIf { VIDEO_ID_RE.matches(it) }?.let { "watch?v=$it" }

    private fun decode(segment: String) = URLDecoder.decode(segment, "UTF-8")

    private fun queryParam(uri: URI, name: String): String? = uri.rawQuery.orEmpty()
        .split('&')
        .firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')

    /** 頻道的 `/live` 頁面在開台時會把 canonical 指向正在播的影片，沒開台則指回頻道本身。 */
    private fun findLiveVideo(channelPath: String): String {
        val url = "https://www.youtube.com/" + Uri.encode(channelPath, "@/") + "/live"
        val response = HttpDownloader.get(url)
        if (response.responseCode() == 404) throw IOException("找不到 YouTube 頻道 $channelPath")
        if (response.responseCode() !in 200..299) throw IOException("YouTube HTTP ${response.responseCode()}")
        return CANONICAL_WATCH_RE.find(response.responseBody())?.groupValues?.get(1)
            ?: throw OfflineException("目前沒有在直播")
    }

    /**
     * 優先挑分開的音訊清單（取最高畫質變體搭配的那組，音質通常也最好）；
     * 沒有分開的音訊就退而求其次，挑頻寬最低的影音混合變體。
     */
    private fun pickAudio(masterUrl: String): String {
        val response = HttpDownloader.get(masterUrl)
        if (response.responseCode() !in 200..299) throw IOException("HLS HTTP ${response.responseCode()}")
        val lines = response.responseBody().lines()

        val audioByGroup = LinkedHashMap<String, String>()
        val variants = mutableListOf<Variant>()
        lines.forEachIndexed { i, line ->
            when {
                line.startsWith("#EXT-X-MEDIA:") -> {
                    val attrs = line.substringAfter(':')
                    if (attr(attrs, "TYPE") != "AUDIO") return@forEachIndexed
                    val group = attr(attrs, "GROUP-ID") ?: return@forEachIndexed
                    val uri = attr(attrs, "URI") ?: return@forEachIndexed
                    if (group !in audioByGroup || attr(attrs, "DEFAULT") == "YES") audioByGroup[group] = uri
                }
                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    val attrs = line.substringAfter(':')
                    val uri = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                        ?: return@forEachIndexed
                    val bandwidth = attr(attrs, "BANDWIDTH")?.toLongOrNull() ?: Long.MAX_VALUE
                    variants += Variant(bandwidth, attr(attrs, "AUDIO"), uri.trim())
                }
            }
        }

        val audio = variants.sortedByDescending { it.bandwidth }
            .firstNotNullOfOrNull { variant -> variant.audioGroup?.let(audioByGroup::get) }
            ?: audioByGroup.values.firstOrNull()
        val chosen = audio
            ?: variants.minByOrNull { it.bandwidth }?.uri
            ?: throw IOException("播放清單裡沒有可用的串流")
        return URI(masterUrl).resolve(chosen).toString()
    }

    private class Variant(val bandwidth: Long, val audioGroup: String?, val uri: String)

    /** 從 `KEY=value,KEY="value"` 格式的屬性列表取值。 */
    private fun attr(attrs: String, key: String): String? =
        Regex("(?:^|,)$key=(\"[^\"]*\"|[^,]*)").find(attrs)?.groupValues?.get(1)?.trim('"')

    /** 給 NewPipeExtractor 用的 HTTP 實作 */
    private object HttpDownloader : Downloader() {
        override fun execute(request: Request): Response {
            val conn = URL(request.url()).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.requestMethod = request.httpMethod()
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                // 略過歐盟地區的 cookie 同意頁
                conn.setRequestProperty("Cookie", "SOCS=CAI")
                request.headers().forEach { (name, values) ->
                    values.forEachIndexed { i, value ->
                        if (i == 0) conn.setRequestProperty(name, value) else conn.addRequestProperty(name, value)
                    }
                }
                request.dataToSend()?.let { data ->
                    conn.doOutput = true
                    conn.outputStream.use { it.write(data) }
                }

                val code = conn.responseCode
                if (code == 429) throw ReCaptchaException("YouTube 要求驗證，暫時無法存取", request.url())
                val stream = if (code >= 400) conn.errorStream else conn.inputStream
                val body = stream?.bufferedReader()?.use { it.readText() }
                val headers = conn.headerFields.filterKeys { it != null }
                return Response(code, conn.responseMessage, headers, body, conn.url.toString())
            } finally {
                conn.disconnect()
            }
        }
    }
}
