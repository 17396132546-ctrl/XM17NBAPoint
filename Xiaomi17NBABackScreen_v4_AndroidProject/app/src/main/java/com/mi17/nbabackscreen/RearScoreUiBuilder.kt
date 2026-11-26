package com.mi17.nbabackscreen

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import coil.load
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 小米 17 Pro Max 妙享背屏专用 OLED 极简大字记分牌 v4.0：
 * - 球队得分自动触发 1.85 秒整圈环绕流光灯效，颜色实时提取自得分球队 Logo 主色（如尼克斯橙）；
 * - 全面内置 OLED 护屏机制：浅灰白降亮度比分字体（#CCCCCC 替代刺眼纯白）、每 60 秒 ±3px 平滑像素微移、
 *   可调独立背屏护屏亮度；
 * - 去掉球队名字、去掉文字直播、去掉 3s/5s 刷新提示，保留避让摄像头与最大号自适应比分。
 */
class RearScoreViewHolder(context: Context) {
    val root: FrameLayout
    private val contentBox: LinearLayout
    private val glowOverlay: ScoreGlowBorderView
    private val statusTv: TextView
    private val awayLogoIv: ImageView
    private val homeLogoIv: ImageView
    private val awayMetaTv: TextView
    private val homeMetaTv: TextView
    private val scoreTv: TextView

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val shiftHandler = Handler(Looper.getMainLooper())

    // ±3px 平滑轨道偏移表（每 60 秒移动一步，防止静态文字/队标在 OLED 上留残影）
    private val shiftOffsets = arrayOf(
        0f to 0f,
        2f to 1f,
        3f to -1f,
        1f to -3f,
        -2f to -2f,
        -3f to 0f,
        -2f to 3f,
        1f to 2f
    )
    private var shiftStep = 0
    private var shiftRunning = false

    private var lastConfig: RearLayoutConfig = ScoreStateHolder.layoutConfig.value
    private var rootW: Int = 0
    private var rootH: Int = 0

    // 记录上一次比分，用于精确检测哪支球队刚刚得分
    private var trackedGameId: String? = null
    private var trackedAwayScore: Int = -1
    private var trackedHomeScore: Int = -1

    private val shiftRunnable = object : Runnable {
        override fun run() {
            if (!shiftRunning) return
            if (lastConfig.pixelShiftEnabled) {
                shiftStep = (shiftStep + 1) % shiftOffsets.size
                val (dx, dy) = shiftOffsets[shiftStep]
                contentBox.animate()
                    .translationX(dx)
                    .translationY(dy)
                    .setDuration(900L)
                    .start()
            } else {
                contentBox.animate().translationX(0f).translationY(0f).setDuration(300L).start()
            }
            shiftHandler.postDelayed(this, 60_000L)
        }
    }

    init {
        root = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
        }

        contentBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // 1. 顶部精简状态栏（仅显示节数时钟或暂停状态）
        statusTv = TextView(context).apply {
            text = "NBA LIVE"
            setTextColor(Color.parseColor("#8FA3BF"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 1
            includeFontPadding = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 6 }
        }
        contentBox.addView(statusTv)

        // 2. 紧凑队标行（去掉球队名字，只保留队标与暂停/犯规）
        val teamsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 4 }
        }

        val awayCol = makeTeamBadge(context)
        awayLogoIv = awayCol.logo
        awayMetaTv = awayCol.meta

        val homeCol = makeTeamBadge(context)
        homeLogoIv = homeCol.logo
        homeMetaTv = homeCol.meta

        teamsRow.addView(awayCol.container)
        teamsRow.addView(homeCol.container)
        contentBox.addView(teamsRow)

        // 3. 超大号自适应比分（使用护屏浅灰白 #CCCCCC 替代刺眼纯白）
        scoreTv = TextView(context).apply {
            text = "- : -"
            setTextColor(Color.parseColor("#CCCCCC"))
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            scoreTv,
            20,
            260,
            2,
            TypedValue.COMPLEX_UNIT_PX
        )
        contentBox.addView(scoreTv)

        // 4. 整屏环绕得分灯效覆盖层（位于最上层，环绕整个背屏边缘）
        glowOverlay = ScoreGlowBorderView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        root.addView(contentBox)
        root.addView(glowOverlay)

        root.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val w = right - left
            val h = bottom - top
            if (w > 0 && h > 0 && (w != rootW || h != rootH)) {
                rootW = w
                rootH = h
                applyLayout(lastConfig)
            }
        }

        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                startPixelShiftLoop()
            }

            override fun onViewDetachedFromWindow(v: View) {
                stopPixelShiftLoop()
            }
        })
    }

    private fun startPixelShiftLoop() {
        if (shiftRunning) return
        shiftRunning = true
        shiftHandler.removeCallbacks(shiftRunnable)
        shiftHandler.postDelayed(shiftRunnable, 60_000L)
    }

    private fun stopPixelShiftLoop() {
        shiftRunning = false
        shiftHandler.removeCallbacks(shiftRunnable)
    }

    fun destroy() {
        stopPixelShiftLoop()
        uiScope.cancel()
    }

    private data class TeamBadgeViews(
        val container: LinearLayout,
        val logo: ImageView,
        val meta: TextView
    )

    private fun makeTeamBadge(context: Context): TeamBadgeViews {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        val logo = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(88, 88)
            scaleType = ImageView.ScaleType.FIT_CENTER
            // 略微压低队标峰值亮度，保护 OLED 静态区域
            alpha = 0.88f
        }
        val meta = TextView(context).apply {
            text = ""
            setTextColor(Color.parseColor("#828C9E"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 1
            includeFontPadding = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 4 }
        }
        col.addView(logo)
        col.addView(meta)
        return TeamBadgeViews(col, logo, meta)
    }

    fun applyWindowBrightness(window: Window?, config: RearLayoutConfig) {
        if (window == null) return
        val lp = window.attributes
        lp.screenBrightness = (config.brightnessPercent / 100f).coerceIn(0.15f, 0.90f)
        window.attributes = lp
    }

    fun applyLayout(config: RearLayoutConfig) {
        lastConfig = config
        // OLED 像素级亮度联动：在纯黑背景上同步降低静态内容不透明度，确保护屏生效
        val contentAlpha = (0.48f + (config.brightnessPercent / 100f) * 0.46f).coerceIn(0.55f, 0.94f)
        contentBox.alpha = contentAlpha
        if (!config.pixelShiftEnabled) {
            contentBox.translationX = 0f
            contentBox.translationY = 0f
        }

        if (rootW <= 0 || rootH <= 0) return

        val avoidPxW = (rootW * config.avoidPercent / 100f).toInt()
        val avoidPxH = (rootH * config.avoidPercent / 100f).toInt()

        var availLeft = 0
        var availTop = 0
        var availW = rootW
        var availH = rootH

        when (config.avoidMode) {
            CameraAvoidMode.LEFT -> {
                availLeft = avoidPxW
                availW = (rootW - avoidPxW).coerceAtLeast(160)
            }
            CameraAvoidMode.RIGHT -> {
                availW = (rootW - avoidPxW).coerceAtLeast(160)
            }
            CameraAvoidMode.TOP -> {
                availTop = avoidPxH
                availH = (rootH - avoidPxH).coerceAtLeast(120)
            }
            CameraAvoidMode.BOTTOM -> {
                availH = (rootH - avoidPxH).coerceAtLeast(120)
            }
            CameraAvoidMode.CENTER -> {}
        }

        val scale = config.scalePercent / 100f
        val contentW = (availW * scale).toInt().coerceAtLeast(140)
        val contentH = (availH * scale).toInt().coerceAtLeast(110)
        val marginLeft = availLeft + (availW - contentW) / 2
        val marginTop = availTop + (availH - contentH) / 2

        val lp = FrameLayout.LayoutParams(contentW, contentH).apply {
            leftMargin = marginLeft
            topMargin = marginTop
        }
        contentBox.layoutParams = lp

        val logoSize = (minOf(contentH * 0.27f, contentW * 0.24f)).toInt().coerceIn(36, 150)
        awayLogoIv.layoutParams = LinearLayout.LayoutParams(logoSize, logoSize)
        homeLogoIv.layoutParams = LinearLayout.LayoutParams(logoSize, logoSize)

        statusTv.setTextSize(TypedValue.COMPLEX_UNIT_PX, (contentH * 0.088f).coerceIn(18f, 44f))
        val metaPx = (contentH * 0.068f).coerceIn(14f, 32f)
        awayMetaTv.setTextSize(TypedValue.COMPLEX_UNIT_PX, metaPx)
        homeMetaTv.setTextSize(TypedValue.COMPLEX_UNIT_PX, metaPx)

        contentBox.requestLayout()
    }

    fun triggerGlowWithColor(color: Int) {
        if (lastConfig.glowEnabled) {
            glowOverlay.trigger(color)
        }
    }

    private fun triggerGlowForTeam(team: TeamScore) {
        if (!lastConfig.glowEnabled) return
        uiScope.launch {
            val dominantColor = LogoColorExtractor.resolveDominantColor(team.abbr, team.logoUrl)
            glowOverlay.trigger(dominantColor)
        }
    }

    fun bind(game: NbaGame?, detail: LiveGameDetail?, config: RearLayoutConfig) {
        if (config != lastConfig) {
            applyLayout(config)
        }
        if (game == null) {
            statusTv.text = "等待赛程"
            statusTv.setTextColor(Color.parseColor("#828C9E"))
            scoreTv.text = "- : -"
            awayMetaTv.visibility = View.GONE
            homeMetaTv.visibility = View.GONE
            return
        }

        // 预热提取当前两队 Logo 主色，保证得分瞬间零延迟点亮对应色调
        uiScope.launch {
            LogoColorExtractor.resolveDominantColor(game.away.abbr, game.away.logoUrl)
            LogoColorExtractor.resolveDominantColor(game.home.abbr, game.home.logoUrl)
        }

        // 检测同一场比赛是否有队伍得分增加
        if (trackedGameId != game.id) {
            trackedGameId = game.id
            trackedAwayScore = game.away.score
            trackedHomeScore = game.home.score
        } else {
            val awayDelta = game.away.score - trackedAwayScore
            val homeDelta = game.home.score - trackedHomeScore
            if (game.state != "pre") {
                if (awayDelta > 0 && awayDelta >= homeDelta) {
                    triggerGlowForTeam(game.away)
                } else if (homeDelta > 0) {
                    triggerGlowForTeam(game.home)
                }
            }
            trackedAwayScore = game.away.score
            trackedHomeScore = game.home.score
        }

        val d = if (detail?.gameId == game.id) detail else null
        val isTimeout = d?.isTimeoutNow == true

        when {
            isTimeout -> {
                statusTv.text = "⏸ 暂停中 · ${game.statusText}"
                statusTv.setTextColor(Color.parseColor("#D4A326"))
            }
            game.state == "in" -> {
                statusTv.text = "● ${game.statusText}"
                statusTv.setTextColor(Color.parseColor("#D95550"))
            }
            game.state == "pre" -> {
                statusTv.text = game.statusText
                statusTv.setTextColor(Color.parseColor("#D4A326"))
            }
            else -> {
                statusTv.text = game.statusText
                statusTv.setTextColor(Color.parseColor("#828C9E"))
            }
        }

        awayLogoIv.load(game.away.logoUrl)
        homeLogoIv.load(game.home.logoUrl)

        // 护屏浅灰白超大号比分（领先方 #CCCCCC 柔和亮灰，落后方 #6E7686，冒号 #3E4654）
        if (game.state == "pre") {
            scoreTv.text = "VS"
            scoreTv.setTextColor(Color.parseColor("#A8B2C1"))
        } else {
            val aStr = game.away.score.toString()
            val hStr = game.home.score.toString()
            val sep = ":"
            val ssb = SpannableStringBuilder()
            val awayColor = if (game.away.score >= game.home.score) Color.parseColor("#CCCCCC") else Color.parseColor("#6E7686")
            val homeColor = if (game.home.score >= game.away.score) Color.parseColor("#CCCCCC") else Color.parseColor("#6E7686")

            ssb.append(aStr)
            ssb.setSpan(ForegroundColorSpan(awayColor), 0, aStr.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val sepStart = ssb.length
            ssb.append(sep)
            ssb.setSpan(ForegroundColorSpan(Color.parseColor("#3E4654")), sepStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val homeStart = ssb.length
            ssb.append(hStr)
            ssb.setSpan(ForegroundColorSpan(homeColor), homeStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            scoreTv.text = ssb
        }

        val awayTo = d?.awayTimeouts?.takeIf { it >= 0 } ?: game.away.timeoutsRemaining
        val homeTo = d?.homeTimeouts?.takeIf { it >= 0 } ?: game.home.timeoutsRemaining
        val awayBonus = d?.awayInBonus ?: game.away.inBonus
        val homeBonus = d?.homeInBonus ?: game.home.inBonus
        val awayFoul = d?.awayQuarterFouls?.takeIf { it >= 0 } ?: d?.awayTotalFouls ?: -1
        val homeFoul = d?.homeQuarterFouls?.takeIf { it >= 0 } ?: d?.homeTotalFouls ?: -1

        val awayMetaStr = formatCompactMeta(game.state, awayTo, awayFoul, awayBonus)
        val homeMetaStr = formatCompactMeta(game.state, homeTo, homeFoul, homeBonus)

        if (awayMetaStr.isBlank() && homeMetaStr.isBlank()) {
            awayMetaTv.visibility = View.GONE
            homeMetaTv.visibility = View.GONE
        } else {
            awayMetaTv.visibility = View.VISIBLE
            homeMetaTv.visibility = View.VISIBLE
            awayMetaTv.text = awayMetaStr
            homeMetaTv.text = homeMetaStr
            awayMetaTv.setTextColor(if (awayBonus) Color.parseColor("#D67A7A") else Color.parseColor("#828C9E"))
            homeMetaTv.setTextColor(if (homeBonus) Color.parseColor("#D67A7A") else Color.parseColor("#828C9E"))
        }
    }

    private fun formatCompactMeta(
        state: String,
        timeouts: Int,
        fouls: Int,
        inBonus: Boolean
    ): String {
        if (state == "pre") return ""
        val parts = mutableListOf<String>()
        if (timeouts >= 0) parts += "暂$timeouts"
        if (fouls >= 0) parts += "犯$fouls"
        if (inBonus) parts += "满"
        return parts.joinToString("·")
    }
}
