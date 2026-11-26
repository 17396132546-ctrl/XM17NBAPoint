package com.mi17.nbabackscreen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

data class TeamScore(
    val abbr: String,
    val cnName: String,
    val score: Int,
    val record: String,
    val logoUrl: String,
    val timeoutsRemaining: Int = -1,
    val inBonus: Boolean = false
)

data class NbaGame(
    val id: String,
    val source: String, // "NBA" or "ESPN"
    val state: String,  // "pre", "in", "post"
    val period: Int,
    val statusText: String,
    val away: TeamScore,
    val home: TeamScore,
    val fallbackLastPlay: String = ""
)

data class LiveGameDetail(
    val gameId: String,
    val awayTimeouts: Int = -1,
    val homeTimeouts: Int = -1,
    val awayInBonus: Boolean = false,
    val homeInBonus: Boolean = false,
    val awayQuarterFouls: Int = -1,
    val homeQuarterFouls: Int = -1,
    val awayTotalFouls: Int = -1,
    val homeTotalFouls: Int = -1,
    val isTimeoutNow: Boolean = false,
    val latestEventType: String = "normal", // "timeout", "foul", "score", "normal"
    val latestEventText: String = ""
)

object NbaRepository {
    private const val NBA_CDN_URL = "https://cdn.nba.com/static/json/liveData/scoreboard/todaysScoreboard_00.json"
    private const val NBA_PBP_FMT = "https://cdn.nba.com/static/json/liveData/playbyplay/playbyplay_%s.json"
    private const val NBA_BOX_FMT = "https://cdn.nba.com/static/json/liveData/boxscore/boxscore_%s.json"
    private const val ESPN_URL = "https://site.api.espn.com/apis/site/v2/sports/basketball/nba/scoreboard"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val logoCache = mutableMapOf<String, Bitmap>()
    private val localTimeFmt = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    private val teamCnMap = mapOf(
        "ATL" to "老鹰", "BOS" to "凯尔特人", "BKN" to "篮网", "CHA" to "黄蜂",
        "CHI" to "公牛", "CLE" to "骑士", "DAL" to "独行侠", "DEN" to "掘金",
        "DET" to "活塞", "GS" to "勇士", "GSW" to "勇士", "HOU" to "火箭",
        "IND" to "步行者", "LAC" to "快船", "LAL" to "湖人", "MEM" to "灰熊",
        "MIA" to "热火", "MIL" to "雄鹿", "MIN" to "森林狼", "NO" to "鹈鹕",
        "NOP" to "鹈鹕", "NY" to "尼克斯", "NYK" to "尼克斯", "OKC" to "雷霆",
        "ORL" to "魔术", "PHI" to "76人", "PHX" to "太阳", "POR" to "开拓者",
        "SAC" to "国王", "SA" to "马刺", "SAS" to "马刺", "TOR" to "猛龙",
        "UTAH" to "爵士", "UTA" to "爵士", "WSH" to "奇才", "WAS" to "奇才"
    )

    fun teamCn(abbr: String): String = teamCnMap[abbr.uppercase()] ?: abbr.uppercase()

    /**
     * 优先使用 NBA 官方 CDN 实时数据源（包含剩余暂停、Bonus 犯满状态且延迟极低），
     * 失败时自动回退至 ESPN 公开接口。
     */
    suspend fun fetchGames(): List<NbaGame> = withContext(Dispatchers.IO) {
        try {
            val nbaGames = fetchFromNbaCdn()
            if (nbaGames.isNotEmpty()) nbaGames else fetchFromEspn()
        } catch (_: Exception) {
            fetchFromEspn()
        }
    }

    private fun getNoCache(url: String): String {
        val req = Request.Builder()
            .url("$url?_t=${System.currentTimeMillis()}")
            .cacheControl(CacheControl.FORCE_NETWORK)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36")
            .header("Accept", "application/json")
            .build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
    }

    private fun fetchFromNbaCdn(): List<NbaGame> {
        val body = getNoCache(NBA_CDN_URL)
        val games = JSONObject(body).getJSONObject("scoreboard").getJSONArray("games")
        val result = mutableListOf<NbaGame>()
        for (i in 0 until games.length()) {
            val g = games.getJSONObject(i)
            val statusCode = g.optInt("gameStatus", 1) // 1=pre, 2=in, 3=post
            val state = when (statusCode) {
                2 -> "in"
                3 -> "post"
                else -> "pre"
            }
            val period = g.optInt("period", 0)
            val rawClock = formatIsoClock(g.optString("gameClock", ""))
            val rawStatusText = g.optString("gameStatusText", "").trim()
            val statusText = when (state) {
                "in" -> {
                    when {
                        rawStatusText.contains("Half", ignoreCase = true) -> "中场休息"
                        rawStatusText.contains("End", ignoreCase = true) -> "第${period}节结束"
                        rawClock.isNotBlank() -> {
                            val q = if (period <= 4) "第${period}节" else "OT${period - 4}"
                            "$q $rawClock"
                        }
                        else -> rawStatusText.ifBlank { "进行中" }
                    }
                }
                "post" -> if (period > 4) "已结束(OT${period - 4})" else "已结束"
                else -> formatUtcToLocalStart(g.optString("gameTimeUTC", ""), rawStatusText)
            }

            val away = g.getJSONObject("awayTeam")
            val home = g.getJSONObject("homeTeam")
            result += NbaGame(
                id = g.optString("gameId"),
                source = "NBA",
                state = state,
                period = period,
                statusText = statusText,
                away = parseNbaTeam(away),
                home = parseNbaTeam(home)
            )
        }
        return result.sortedBy { if (it.state == "in") 0 else if (it.state == "pre") 1 else 2 }
    }

    private fun parseNbaTeam(obj: JSONObject): TeamScore {
        val abbr = obj.optString("teamTricode").uppercase()
        val cn = teamCn(abbr)
        val logo = "https://a.espncdn.com/i/teamlogos/nba/500/${abbr.lowercase()}.png"
        val rec = "${obj.optInt("wins")}-${obj.optInt("losses")}"
        val timeouts = if (obj.has("timeoutsRemaining")) obj.optInt("timeoutsRemaining", -1) else -1
        val bonusRaw = obj.optString("inBonus", "0")
        val inBonus = bonusRaw == "1" || bonusRaw.equals("true", ignoreCase = true)
        return TeamScore(abbr, cn, obj.optInt("score", 0), rec, logo, timeouts, inBonus)
    }

    private fun fetchFromEspn(): List<NbaGame> {
        val body = getNoCache(ESPN_URL)
        val root = JSONObject(body)
        val events = root.optJSONArray("events") ?: return emptyList()
        val result = mutableListOf<NbaGame>()
        for (i in 0 until events.length()) {
            val ev = events.getJSONObject(i)
            val comp = ev.getJSONArray("competitions").getJSONObject(0)
            val competitors = comp.getJSONArray("competitors")
            var homeObj: JSONObject? = null
            var awayObj: JSONObject? = null
            for (j in 0 until competitors.length()) {
                val c = competitors.getJSONObject(j)
                if (c.optString("homeAway") == "home") homeObj = c else awayObj = c
            }
            if (homeObj == null || awayObj == null) continue

            val status = ev.getJSONObject("status")
            val type = status.getJSONObject("type")
            val state = type.optString("state", "pre")
            val period = status.optInt("period", 0)
            val clock = status.optString("displayClock", "")
            val statusText = when (state) {
                "in" -> {
                    val shortDetail = type.optString("shortDetail", "")
                    if (shortDetail.contains("Half", ignoreCase = true)) "中场休息"
                    else {
                        val q = if (period <= 4) "第${period}节" else "OT${period - 4}"
                        "$q $clock"
                    }
                }
                "post" -> "已结束"
                else -> formatUtcToLocalStart(ev.optString("date", ""), type.optString("shortDetail", "未开赛"))
            }

            val lastPlay = comp.optJSONObject("situation")
                ?.optJSONObject("lastPlay")
                ?.optString("text")
                .orEmpty()

            result += NbaGame(
                id = ev.optString("id"),
                source = "ESPN",
                state = state,
                period = period,
                statusText = statusText,
                away = parseEspnTeam(awayObj),
                home = parseEspnTeam(homeObj),
                fallbackLastPlay = lastPlay
            )
        }
        return result.sortedBy { if (it.state == "in") 0 else if (it.state == "pre") 1 else 2 }
    }

    private fun parseEspnTeam(obj: JSONObject): TeamScore {
        val team = obj.getJSONObject("team")
        val abbr = team.optString("abbreviation").uppercase()
        val cn = teamCnMap[abbr] ?: team.optString("shortDisplayName", abbr)
        val logo = team.optString("logo")
            .ifBlank { "https://a.espncdn.com/i/teamlogos/nba/500/${abbr.lowercase()}.png" }
        val rec = obj.optJSONArray("records")?.optJSONObject(0)?.optString("summary").orEmpty()
        return TeamScore(abbr, cn, obj.optString("score", "0").toIntOrNull() ?: 0, rec, logo)
    }

    /**
     * 拉取锁定场次的实时细粒度信息：
     * 1) 本节两队犯规次数（满 5 次进入 BONUS）
     * 2) 剩余暂停数 & 罚满状态
     * 3) 最新实时事件（暂停、犯规、三分/得分、失误等中文播报）
     */
    suspend fun fetchLiveDetail(game: NbaGame): LiveGameDetail = withContext(Dispatchers.IO) {
        var awayTo = game.away.timeoutsRemaining
        var homeTo = game.home.timeoutsRemaining
        var awayBonus = game.away.inBonus
        var homeBonus = game.home.inBonus
        var awayQFouls = -1
        var homeQFouls = -1
        var awayTotalFouls = -1
        var homeTotalFouls = -1
        var isTimeoutNow = false
        var eventType = "normal"
        var eventText = game.fallbackLastPlay

        if (game.source != "NBA" || game.state == "pre") {
            return@withContext LiveGameDetail(
                gameId = game.id,
                awayTimeouts = awayTo,
                homeTimeouts = homeTo,
                awayInBonus = awayBonus,
                homeInBonus = homeBonus,
                latestEventText = if (game.state == "pre") "等待开赛 · ${game.statusText}" else eventText
            )
        }

        // 1. 拉取 Boxscore 获取全队总犯规 & 最新的暂停/Bonus 状态
        try {
            val boxJson = JSONObject(getNoCache(NBA_BOX_FMT.format(game.id))).getJSONObject("game")
            val awayObj = boxJson.optJSONObject("awayTeam")
            val homeObj = boxJson.optJSONObject("homeTeam")
            awayObj?.let {
                if (it.has("timeoutsRemaining")) awayTo = it.optInt("timeoutsRemaining", awayTo)
                val b = it.optString("inBonus", "")
                if (b.isNotBlank()) awayBonus = (b == "1" || b.equals("true", true))
                val st = it.optJSONObject("statistics")
                if (st != null && st.has("foulsPersonal")) awayTotalFouls = st.optInt("foulsPersonal", -1)
            }
            homeObj?.let {
                if (it.has("timeoutsRemaining")) homeTo = it.optInt("timeoutsRemaining", homeTo)
                val b = it.optString("inBonus", "")
                if (b.isNotBlank()) homeBonus = (b == "1" || b.equals("true", true))
                val st = it.optJSONObject("statistics")
                if (st != null && st.has("foulsPersonal")) homeTotalFouls = st.optInt("foulsPersonal", -1)
            }
        } catch (_: Exception) {
        }

        // 2. 拉取 Play-by-Play 统计本节犯规数并提取最新暂停/犯规/得分事件
        try {
            val pbpJson = JSONObject(getNoCache(NBA_PBP_FMT.format(game.id))).getJSONObject("game")
            val actions = pbpJson.optJSONArray("actions")
            if (actions != null && actions.length() > 0) {
                var aFoul = 0
                var hFoul = 0
                val targetPeriod = if (game.period > 0) game.period else actions.getJSONObject(actions.length() - 1).optInt("period", 1)

                for (i in 0 until actions.length()) {
                    val act = actions.getJSONObject(i)
                    if (act.optInt("period", 0) == targetPeriod && act.optString("actionType") == "foul") {
                        val sub = act.optString("subType").lowercase()
                        // 排除技术犯规与防守三秒（不计入球队 Bonus 犯规）
                        if (!sub.contains("technical") && !sub.contains("3second")) {
                            val tri = act.optString("teamTricode").uppercase()
                            if (tri == game.away.abbr) aFoul++
                            else if (tri == game.home.abbr) hFoul++
                        }
                    }
                }
                awayQFouls = aFoul
                homeQFouls = hFoul

                // 从后往前找最近一条有意义的事件（跳过纯换人 substitution）
                var latestAct: JSONObject? = null
                for (i in actions.length() - 1 downTo maxOf(0, actions.length() - 10)) {
                    val act = actions.getJSONObject(i)
                    val t = act.optString("actionType").lowercase()
                    if (t != "substitution" && t != "jumpball") {
                        latestAct = act
                        break
                    }
                }
                if (latestAct == null) latestAct = actions.getJSONObject(actions.length() - 1)

                val parsed = formatLiveAction(latestAct)
                eventType = parsed.first
                eventText = parsed.second
                isTimeoutNow = (eventType == "timeout")
            }
        } catch (_: Exception) {
        }

        LiveGameDetail(
            gameId = game.id,
            awayTimeouts = awayTo,
            homeTimeouts = homeTo,
            awayInBonus = awayBonus,
            homeInBonus = homeBonus,
            awayQuarterFouls = awayQFouls,
            homeQuarterFouls = homeQFouls,
            awayTotalFouls = awayTotalFouls,
            homeTotalFouls = homeTotalFouls,
            isTimeoutNow = isTimeoutNow,
            latestEventType = eventType,
            latestEventText = eventText
        )
    }

    private fun formatLiveAction(act: JSONObject): Pair<String, String> {
        val type = act.optString("actionType").lowercase()
        val sub = act.optString("subType").lowercase()
        val tri = act.optString("teamTricode").uppercase()
        val cnTeam = if (tri.isNotBlank()) teamCn(tri) else ""
        val player = act.optString("playerNameI").ifBlank { act.optString("playerName") }
        val clock = formatIsoClock(act.optString("clock", ""))
        val clockSuffix = if (clock.isNotBlank()) " ($clock)" else ""

        return when (type) {
            "timeout" -> {
                val label = when {
                    sub.contains("official") || cnTeam.isBlank() -> "⏸️ 官方暂停$clockSuffix"
                    sub.contains("short") -> "⏸️ ${cnTeam} 请求短暂停$clockSuffix"
                    else -> "⏸️ ${cnTeam} 请求暂停$clockSuffix"
                }
                "timeout" to label
            }
            "foul" -> {
                val foulKind = when {
                    sub.contains("shooting") -> "投篮犯规"
                    sub.contains("offensive") -> "进攻犯规"
                    sub.contains("technical") -> "技术犯规"
                    sub.contains("flagrant") -> "恶意犯规"
                    sub.contains("loose") -> "无球犯规"
                    sub.contains("personal") -> "个人犯规"
                    else -> "犯规"
                }
                val who = listOf(cnTeam, player).filter { it.isNotBlank() }.joinToString(" ")
                "foul" to "🚨 $who $foulKind$clockSuffix"
            }
            "3pt" -> {
                val made = act.optString("shotResult").equals("Made", true)
                val who = listOf(cnTeam, player).filter { it.isNotBlank() }.joinToString(" ")
                if (made) "score" to "🔥 $who 三分命中$clockSuffix"
                else "normal" to "$who 三分不中$clockSuffix"
            }
            "2pt" -> {
                val made = act.optString("shotResult").equals("Made", true)
                val who = listOf(cnTeam, player).filter { it.isNotBlank() }.joinToString(" ")
                if (made) "score" to "🏀 $who 两分命中$clockSuffix"
                else "normal" to "$who 投篮不中$clockSuffix"
            }
            "freethrow" -> {
                val made = act.optString("shotResult").equals("Made", true)
                val who = listOf(cnTeam, player).filter { it.isNotBlank() }.joinToString(" ")
                if (made) "score" to "🎯 $who 罚球命中$clockSuffix"
                else "normal" to "$who 罚球不中$clockSuffix"
            }
            "turnover" -> {
                val who = listOf(cnTeam, player).filter { it.isNotBlank() }.joinToString(" ")
                "normal" to "⚠️ $who 失误$clockSuffix"
            }
            "rebound" -> {
                val kind = if (sub.contains("offensive")) "前场篮板" else "后场篮板"
                val who = listOf(cnTeam, player).filter { it.isNotBlank() }.joinToString(" ")
                "normal" to "💪 $who $kind$clockSuffix"
            }
            "period" -> {
                val label = if (sub.contains("end")) "🔔 本节结束" else "🔔 比赛开始"
                "normal" to label
            }
            else -> {
                val desc = act.optString("description").ifBlank { "$cnTeam $type" }
                "normal" to "$desc$clockSuffix"
            }
        }
    }

    private fun formatIsoClock(iso: String): String {
        if (iso.isBlank()) return ""
        // 例：PT05M12.00S -> 05:12
        val regex = Regex("""PT(\d+)M(\d+)(?:\.\d+)?S""")
        val m = regex.find(iso) ?: return iso
        val min = m.groupValues[1].padStart(2, '0')
        val sec = m.groupValues[2].padStart(2, '0')
        return "$min:$sec"
    }

    private fun formatUtcToLocalStart(utcStr: String, fallback: String): String {
        if (utcStr.isBlank()) return fallback
        return try {
            val instant = Instant.parse(utcStr)
            "${localTimeFmt.format(instant)} 开赛"
        } catch (_: Exception) {
            fallback
        }
    }

    suspend fun loadLogoBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
        logoCache[url]?.let { return@withContext it }
        try {
            val req = Request.Builder().url(url).build()
            val bytes = client.newCall(req).execute().use { it.body?.bytes() } ?: return@withContext null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { logoCache[url] = it }
        } catch (_: Exception) {
            null
        }
    }
}
