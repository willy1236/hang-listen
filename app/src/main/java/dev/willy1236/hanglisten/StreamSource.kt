package dev.willy1236.hanglisten

import java.io.IOException

/** 一個可以掛聽的來源，`parse()` 吃使用者輸入或 [key]，都會得回同一個來源。 */
sealed interface StreamSource {

    /** 存檔與判斷重複用的唯一字串 */
    val key: String

    /** 顯示在畫面與通知上的名稱 */
    val label: String

    /** 解析出只有音訊的 HLS 播放清單網址；未開台時丟 [OfflineException]。 */
    suspend fun resolveAudioUrl(): String

    data class Twitch(val login: String) : StreamSource {
        // 不加前綴，舊版存下的紀錄才讀得回來
        override val key get() = login
        override val label get() = login
        override suspend fun resolveAudioUrl() = TwitchResolver.resolveAudioUrl(login)
    }

    /** [path] 是 `@handle`、`channel/UC…`、`c/…`、`user/…` 這類頻道路徑，或 `watch?v=影片ID`。 */
    data class YouTube(val path: String) : StreamSource {
        override val key get() = "youtube.com/$path"
        override val label get() = "YouTube " + path.removePrefix("watch?v=")
        override suspend fun resolveAudioUrl() = YouTubeResolver.resolveAudioUrl(path)
    }

    companion object {
        fun parse(input: String): StreamSource? =
            YouTubeResolver.normalizePath(input)?.let(::YouTube)
                ?: TwitchResolver.normalizeChannel(input)?.let(::Twitch)
    }
}

class OfflineException(message: String) : IOException(message)
