package dev.willy1236.hanglisten

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 加過的頻道，最近加的排最前面 */
class ChannelHistory(context: Context) {

    private val prefs = context.getSharedPreferences("channel_history", Context.MODE_PRIVATE)

    private val _channels = MutableStateFlow(
        prefs.getString(KEY, null)?.split('\n')?.filter { it.isNotEmpty() }.orEmpty(),
    )
    val channels: StateFlow<List<String>> = _channels.asStateFlow()

    fun record(channel: String) {
        save((listOf(channel) + _channels.value.filter { it != channel }).take(MAX))
    }

    fun forget(channel: String) {
        save(_channels.value.filter { it != channel })
    }

    private fun save(list: List<String>) {
        _channels.value = list
        prefs.edit().putString(KEY, list.joinToString("\n")).apply()
    }

    private companion object {
        const val KEY = "channels"
        const val MAX = 30
    }
}
