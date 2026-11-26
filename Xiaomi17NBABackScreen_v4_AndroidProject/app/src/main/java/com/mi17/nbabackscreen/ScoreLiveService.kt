package com.mi17.nbabackscreen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

class ScoreLiveService : Service() {

    companion object {
        const val CHANNEL_ID = "nba_live_score_island"
        const val NOTIFY_ID = 17001
        const val ACTION_STOP = "com.mi17.nbabackscreen.STOP"
        const val ACTION_FORCE_REFRESH = "com.mi17.nbabackscreen.FORCE_REFRESH"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pollJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ScoreStateHolder.init(this)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(
            NOTIFY_ID,
            buildNotification(ScoreStateHolder.currentGame(), ScoreStateHolder.liveDetail.value, null, null)
        )
        startPolling()
        return START_STICKY
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                var isCurrentLive = false
                try {
                    val games = NbaRepository.fetchGames()
                    ScoreStateHolder.updateGames(games)
                    val g = ScoreStateHolder.currentGame()
                    isCurrentLive = (g?.state == "in")

                    val detail = if (g != null) NbaRepository.fetchLiveDetail(g) else null
                    ScoreStateHolder.updateDetail(detail)

                    val awayBmp = g?.let { NbaRepository.loadLogoBitmap(it.away.logoUrl) }
                    val homeBmp = g?.let { NbaRepository.loadLogoBitmap(it.home.logoUrl) }
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.notify(NOTIFY_ID, buildNotification(g, detail, awayBmp, homeBmp))
                } catch (_: Exception) {
                }

                val interval = if (isCurrentLive) {
                    ScoreStateHolder.refreshIntervalMs.value
                } else {
                    maxOf(15_000L, ScoreStateHolder.refreshIntervalMs.value)
                }
                delay(interval)
            }
        }
    }

    private fun buildNotification(
        game: NbaGame?,
        detail: LiveGameDetail?,
        awayBmp: Bitmap?,
        homeBmp: Bitmap?
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val d = if (game != null && detail?.gameId == game.id) detail else null
        val title = if (game != null) {
            val tag = if (d?.isTimeoutNow == true) "⏸ " else ""
            if (game.state == "pre") "$tag${game.away.cnName} vs ${game.home.cnName}"
            else "$tag${game.away.score} : ${game.home.score}"
        } else {
            "NBA 比分同步中"
        }

        val metaLine = buildMetaLine(game, d)
        val shortChip = if (game != null) {
            val prefix = if (d?.isTimeoutNow == true) "暂|" else ""
            if (game.state == "pre") "${game.away.abbr}-${game.home.abbr}"
            else "$prefix${game.away.score}:${game.home.score}"
        } else {
            "NBA"
        }

        val combinedLogo = if (awayBmp != null || homeBmp != null) {
            combineLogos(awayBmp, homeBmp)
        } else null

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(title)
            .setContentText(metaLine)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openIntent)
            .setShortCriticalText(shortChip)

        if (combinedLogo != null) {
            builder.setLargeIcon(combinedLogo)
        }

        if (Build.VERSION.SDK_INT >= 36) {
            builder.setRequestPromotedOngoing(true)
        }

        if (game != null) {
            val focusJson = JSONObject().apply {
                put("param_v2", JSONObject().apply {
                    put("business", "sports_nba")
                    put("ticker", shortChip)
                    put("enableFloat", false)
                    put("baseInfo", JSONObject().apply {
                        put("type", 1)
                        put("title", title)
                        put("content", metaLine)
                    })
                })
            }.toString()

            builder.addExtras(Bundle().apply {
                putString("miui.focus.param", focusJson)
            })
        }

        return builder.build()
    }

    private fun buildMetaLine(game: NbaGame?, d: LiveGameDetail?): String {
        if (game == null) return "正在同步赛程"
        val sb = StringBuilder(game.statusText)
        if (game.state == "in") {
            val aTo = d?.awayTimeouts?.takeIf { it >= 0 } ?: game.away.timeoutsRemaining
            val hTo = d?.homeTimeouts?.takeIf { it >= 0 } ?: game.home.timeoutsRemaining
            val aF = d?.awayQuarterFouls?.takeIf { it >= 0 } ?: -1
            val hF = d?.homeQuarterFouls?.takeIf { it >= 0 } ?: -1
            val aB = if (d?.awayInBonus ?: game.away.inBonus) "(满)" else ""
            val hB = if (d?.homeInBonus ?: game.home.inBonus) "(满)" else ""
            if (aTo >= 0 || hTo >= 0) sb.append(" · 暂$aTo:$hTo")
            if (aF >= 0 || hF >= 0) sb.append(" · 犯$aF$aB:$hF$hB")
        }
        return sb.toString()
    }

    private fun combineLogos(left: Bitmap?, right: Bitmap?): Bitmap {
        val size = 192
        val half = size / 2
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        left?.let {
            val scaled = Bitmap.createScaledBitmap(it, half - 8, half - 8, true)
            canvas.drawBitmap(scaled, 4f, (size - scaled.height) / 2f, null)
        }
        right?.let {
            val scaled = Bitmap.createScaledBitmap(it, half - 8, half - 8, true)
            canvas.drawBitmap(scaled, half + 4f, (size - scaled.height) / 2f, null)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CCCCCC")
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("VS", half.toFloat(), size - 16f, paint)
        return out
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "NBA 实时比分（超级岛与背屏）",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "推送精简 NBA 实时比分、暂停与犯规至超级岛与妙享背屏"
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
