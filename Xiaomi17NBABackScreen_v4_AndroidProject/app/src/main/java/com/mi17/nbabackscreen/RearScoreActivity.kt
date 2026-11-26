package com.mi17.nbabackscreen

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 专为小米 17 Pro Max 妙享背屏设计的独立全屏记分牌 Activity。
 */
class RearScoreActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var uiHolder: RearScoreViewHolder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScoreStateHolder.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        uiHolder = RearScoreViewHolder(this)
        setContentView(uiHolder.root)

        scope.launch {
            combine(
                ScoreStateHolder.games,
                ScoreStateHolder.selectedGameId,
                ScoreStateHolder.liveDetail,
                ScoreStateHolder.layoutConfig
            ) { _, _, detail, cfg ->
                Triple(ScoreStateHolder.currentGame(), detail, cfg)
            }.collect { (game, detail, cfg) ->
                uiHolder.applyWindowBrightness(window, cfg)
                uiHolder.bind(game, detail, cfg)
            }
        }

        scope.launch {
            ScoreStateHolder.manualGlowEvent.collect { ev ->
                uiHolder.triggerGlowWithColor(ev.color)
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

    override fun onDestroy() {
        uiHolder.destroy()
        scope.cancel()
        super.onDestroy()
    }
}
