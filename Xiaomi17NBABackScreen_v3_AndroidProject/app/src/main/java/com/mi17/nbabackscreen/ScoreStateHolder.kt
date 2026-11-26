package com.mi17.nbabackscreen

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalTime
import java.time.format.DateTimeFormatter

enum class CameraAvoidMode(val label: String) {
    LEFT("避左摄"),
    RIGHT("避右摄"),
    TOP("避上摄"),
    BOTTOM("避下摄"),
    CENTER("居中")
}

data class RearLayoutConfig(
    val scalePercent: Int = 85,
    val avoidMode: CameraAvoidMode = CameraAvoidMode.LEFT,
    val avoidPercent: Int = 35
)

object ScoreStateHolder {
    private const val PREF_NAME = "mi17_rear_score_prefs"
    private var appContext: Context? = null

    private val _games = MutableStateFlow<List<NbaGame>>(emptyList())
    val games: StateFlow<List<NbaGame>> = _games

    private val _selectedGameId = MutableStateFlow<String?>(null)
    val selectedGameId: StateFlow<String?> = _selectedGameId

    private val _liveDetail = MutableStateFlow<LiveGameDetail?>(null)
    val liveDetail: StateFlow<LiveGameDetail?> = _liveDetail

    // 后台刷新频率默认 3 秒（不在背屏显示）
    private val _refreshIntervalMs = MutableStateFlow(3_000L)
    val refreshIntervalMs: StateFlow<Long> = _refreshIntervalMs

    private val _lastUpdateTime = MutableStateFlow("--:--:--")
    val lastUpdateTime: StateFlow<String> = _lastUpdateTime

    private val _layoutConfig = MutableStateFlow(RearLayoutConfig())
    val layoutConfig: StateFlow<RearLayoutConfig> = _layoutConfig

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun init(context: Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        val prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val scale = prefs.getInt("scalePercent", 85).coerceIn(50, 100)
        val avoidPct = prefs.getInt("avoidPercent", 35).coerceIn(0, 55)
        val modeName = prefs.getString("avoidMode", CameraAvoidMode.LEFT.name)
        val mode = CameraAvoidMode.entries.firstOrNull { it.name == modeName } ?: CameraAvoidMode.LEFT
        val interval = prefs.getLong("refreshIntervalMs", 3_000L).coerceIn(2_000L, 30_000L)
        _layoutConfig.value = RearLayoutConfig(scale, mode, avoidPct)
        _refreshIntervalMs.value = interval
    }

    private fun savePrefs() {
        val prefs = appContext?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE) ?: return
        val cfg = _layoutConfig.value
        prefs.edit()
            .putInt("scalePercent", cfg.scalePercent)
            .putInt("avoidPercent", cfg.avoidPercent)
            .putString("avoidMode", cfg.avoidMode.name)
            .putLong("refreshIntervalMs", _refreshIntervalMs.value)
            .apply()
    }

    fun updateScale(percent: Int) {
        _layoutConfig.value = _layoutConfig.value.copy(scalePercent = percent.coerceIn(50, 100))
        savePrefs()
    }

    fun updateAvoidMode(mode: CameraAvoidMode) {
        _layoutConfig.value = _layoutConfig.value.copy(avoidMode = mode)
        savePrefs()
    }

    fun updateAvoidPercent(percent: Int) {
        _layoutConfig.value = _layoutConfig.value.copy(avoidPercent = percent.coerceIn(0, 55))
        savePrefs()
    }

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
        savePrefs()
    }

    fun currentGame(): NbaGame? {
        val list = _games.value
        val id = _selectedGameId.value
        return list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }
}
