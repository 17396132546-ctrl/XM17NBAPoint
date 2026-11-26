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

/**
 * 后台实时比分服务：
 * 1. 每 15 秒拉取 ESPN / NBA 官网比分；
 * 2. 通过 Android 16 原生 Live Updates (setRequestPromotedOngoing) + 小米焦点通知扩展字段推送，
 *    免 Root 自动流转至小米 17 Pro Max 超级岛与妙享背屏卡片。
 */
class ScoreLiveService : Service() {

    companion object {
        const val CHANNEL_ID = "nba_live_score_island"
        const val NOTIFY_ID = 17001
        const val ACTION_STOP = "com.mi17.nbabackscreen.STOP"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pollJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFY_ID, buildNotification(null, null, null))
        startPolling()
        return START_STICKY
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                try {
                    val games = NbaRepository.fetchGames()
                    ScoreStateHolder.updateGames(games)
                    val g = ScoreStateHolder.currentGame()
                    val awayBmp = g?.let { NbaRepository.loadLogoBitmap(it.away.logoUrl) }
                    val homeBmp = g?.let { NbaRepository.loadLogoBitmap(it.home.logoUrl) }
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.notify(NOTIFY_ID, buildNotification(g, awayBmp, homeBmp))
                } catch (_: Exception) {
                }
                delay(15_000L)
            }
        }
    }

    private fun buildNotification(game: NbaGame?, awayBmp: Bitmap?, homeBmp: Bitmap?): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = if (game != null) {
            "${game.away.cnName} ${game.away.score} : ${game.home.score} ${game.home.cnName}"
        } else {
            "NBA 实时比分同步中..."
        }
        val subtitle = game?.statusText ?: "正在连接 ESPN / NBA 数据源"
        val shortChip = if (game != null) {
            "${game.away.cnName}${game.away.score}-${game.home.score}${game.home.cnName}"
        } else {
            "NBA比分"
        }

        // 将两队队标合成为一张通知大图标，方便在超级岛/焦点卡片同时看到双方队徽
        val combinedLogo = if (awayBmp != null || homeBmp != null) {
            combineLogos(awayBmp, homeBmp)
        } else null

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openIntent)
            .setShortCriticalText(shortChip)

        if (combinedLogo != null) {
            builder.setLargeIcon(combinedLogo)
        }

        // Android 16 原生 Live Updates 升级标记（澎湃 OS 自动接管流转至背屏/超级岛）
        if (Build.VERSION.SDK_INT >= 36) {
            builder.setRequestPromotedOngoing(true)
        }

        // 附加小米澎湃 OS 焦点通知/超级岛通用 JSON 扩展参数
        if (game != null) {
            val focusJson = JSONObject().apply {
                put("param_v2", JSONObject().apply {
                    put("business", "sports_nba")
                    put("ticker", shortChip)
                    put("enableFloat", false)
                    put("baseInfo", JSONObject().apply {
                        put("type", 1)
                        put("title", title)
                        put("content", subtitle)
                    })
                })
            }.toString()

            val extras = Bundle().apply {
                putString("miui.focus.param", focusJson)
            }
            builder.addExtras(extras)
        }

        return builder.build()
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
            color = Color.WHITE
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
            description = "用于实时推送 NBA 球队比分至超级岛与妙享背屏"
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
