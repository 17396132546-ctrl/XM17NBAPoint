package com.mi17.nbabackscreen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class TeamScore(
    val abbr: String,
    val cnName: String,
    val score: Int,
    val record: String,
    val logoUrl: String
)

data class NbaGame(
    val id: String,
    val state: String, // "pre", "in", "post"
    val statusText: String,
    val away: TeamScore,
    val home: TeamScore
)

object NbaRepository {
    private const val ESPN_URL = "https://site.api.espn.com/apis/site/v2/sports/basketball/nba/scoreboard"
    private const val NBA_CDN_URL = "https://cdn.nba.com/static/json/liveData/scoreboard/todaysScoreboard_00.json"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val logoCache = mutableMapOf<String, Bitmap>()

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

    suspend fun fetchGames(): List<NbaGame> = withContext(Dispatchers.IO) {
        try {
            fetchFromEspn()
        } catch (_: Exception) {
            // ESPN 失败时自动回退到 NBA 官网 CDN 接口
            fetchFromNbaCdn()
        }
    }

    private fun fetchFromEspn(): List<NbaGame> {
        val req = Request.Builder().url(ESPN_URL).header("User-Agent", "Mozilla/5.0").build()
        val body = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("ESPN HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
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
                    val q = if (period <= 4) "第${period}节" else "OT${period - 4}"
                    "$q $clock"
                }
                "post" -> "已结束"
                else -> type.optString("shortDetail", "未开赛")
            }

            result += NbaGame(
                id = ev.optString("id"),
                state = state,
                statusText = statusText,
                away = parseEspnTeam(awayObj),
                home = parseEspnTeam(homeObj)
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

    private fun fetchFromNbaCdn(): List<NbaGame> {
        val req = Request.Builder().url(NBA_CDN_URL).header("User-Agent", "Mozilla/5.0").build()
        val body = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("NBA CDN HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
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
            val away = g.getJSONObject("awayTeam")
            val home = g.getJSONObject("homeTeam")
            result += NbaGame(
                id = g.optString("gameId"),
                state = state,
                statusText = g.optString("gameStatusText", ""),
                away = parseNbaTeam(away),
                home = parseNbaTeam(home)
            )
        }
        return result
    }

    private fun parseNbaTeam(obj: JSONObject): TeamScore {
        val abbr = obj.optString("teamTricode").uppercase()
        val cn = teamCnMap[abbr] ?: obj.optString("teamName", abbr)
        val logo = "https://a.espncdn.com/i/teamlogos/nba/500/${abbr.lowercase()}.png"
        val rec = "${obj.optInt("wins")}-${obj.optInt("losses")}"
        return TeamScore(abbr, cn, obj.optInt("score", 0), rec, logo)
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
