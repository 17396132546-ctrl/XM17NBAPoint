package com.mi17.nbabackscreen

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.load
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 专为小米 17 Pro Max 妙享背屏（976x596 OLED）设计的独立全屏记分牌 Activity。
 * 纯黑背景极致省电防烧屏，大字号显示客队/主队队标、中文名、实时比分与比赛节数。
 */
class RearScoreActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var statusTv: TextView
    private lateinit var awayLogoIv: ImageView
    private lateinit var homeLogoIv: ImageView
    private lateinit var awayNameTv: TextView
    private lateinit var homeNameTv: TextView
    private lateinit var scoreTv: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        setContentView(buildRearUi())

        scope.launch {
            ScoreStateHolder.games.collectLatest {
                renderGame(ScoreStateHolder.currentGame())
            }
        }
        scope.launch {
            ScoreStateHolder.selectedGameId.collectLatest {
                renderGame(ScoreStateHolder.currentGame())
            }
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun buildRearUi(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(24, 16, 24, 16)
        }

        statusTv = TextView(this).apply {
            text = "NBA LIVE"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        root.addView(statusTv)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 }
        }

        // 客队列
        val awayCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        awayLogoIv = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(130, 130)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        awayNameTv = TextView(this).apply {
            text = "客队"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        awayCol.addView(awayLogoIv)
        awayCol.addView(awayNameTv)

        // 中央大比分
        scoreTv = TextView(this).apply {
            text = "0 : 0"
            setTextColor(Color.WHITE)
            textSize = 44f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(12, 0, 12, 0)
        }

        // 主队列
        val homeCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        homeLogoIv = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(130, 130)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        homeNameTv = TextView(this).apply {
            text = "主队"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        homeCol.addView(homeLogoIv)
        homeCol.addView(homeNameTv)

        row.addView(awayCol)
        row.addView(scoreTv)
        row.addView(homeCol)
        root.addView(row)
        return root
    }

    private fun renderGame(game: NbaGame?) {
        if (game == null) {
            statusTv.text = "等待同步今日赛程..."
            return
        }
        val prefix = if (game.state == "in") "🔴 LIVE · " else ""
        statusTv.text = "$prefix${game.statusText}"
        awayNameTv.text = game.away.cnName
        homeNameTv.text = game.home.cnName
        scoreTv.text = if (game.state == "pre") "VS" else "${game.away.score} : ${game.home.score}"
        awayLogoIv.load(game.away.logoUrl)
        homeLogoIv.load(game.home.logoUrl)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
