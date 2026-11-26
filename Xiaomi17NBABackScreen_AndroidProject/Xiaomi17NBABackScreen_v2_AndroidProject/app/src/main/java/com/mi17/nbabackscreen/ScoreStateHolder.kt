package com.mi17.nbabackscreen

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object ScoreStateHolder {
    private val _games = MutableStateFlow<List<NbaGame>>(emptyList())
    val games: StateFlow<List<NbaGame>> = _games

    private val _selectedGameId = MutableStateFlow<String?>(null)
    val selectedGameId: StateFlow<String?> = _selectedGameId

    private val _liveDetail = MutableStateFlow<LiveGameDetail?>(null)
    val liveDetail: StateFlow<LiveGameDetail?> = _liveDetail

    // 默认 3 秒实时刷新（直播中生效）
    private val _refreshIntervalMs = MutableStateFlow(3_000L)
    val refreshIntervalMs: StateFlow<Long> = _refreshIntervalMs

    private val _lastUpdateTime = MutableStateFlow("--:--:--")
    val lastUpdateTime: StateFlow<String> = _lastUpdateTime

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun updateGames(list: List<NbaGame>) {
        _games.value = list
        if (_selectedGameId.value == null && list.isNotEmpty()) {
            _selectedGameId.value = list.first().id
        }
        _lastUpdateTime.value = LocalTime.now().format(timeFmt)
    }

    fun updateDetail(detail: LiveGameDetail?) {
        _liveDetail.value = detail
        _lastUpdateTime.value = LocalTime.now().format(timeFmt)
    }

    fun selectGame(gameId: String) {
        if (_selectedGameId.value != gameId) {
            _selectedGameId.value = gameId
            _liveDetail.value = null
        }
    }

    fun setRefreshInterval(ms: Long) {
        _refreshIntervalMs.value = ms.coerceIn(2_000L, 30_000L)
    }

    fun currentGame(): NbaGame? {
        val list = _games.value
        val id = _selectedGameId.value
        return list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }
}
