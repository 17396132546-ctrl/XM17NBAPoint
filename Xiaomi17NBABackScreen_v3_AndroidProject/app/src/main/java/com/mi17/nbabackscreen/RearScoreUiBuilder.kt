package com.mi17.nbabackscreen

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import coil.load

/**
 * 小米 17 Pro Max 妙享背屏专用 OLED 极简大字记分牌：
 * - 自动避让后置摄像头挖孔 + 支持任意比例缩放（主界面调节即时生效）
 * - 去掉球队名字、去掉文字直播、去掉 3s/5s 刷新提示
 * - 只保留：顶部精简状态(节数/时间/暂停) + 双方队标与暂停/犯规 + 占满宽度的超大号自适应比分
 */
class RearScoreViewHolder(context: Context) {
    val root: FrameLayout
    private val contentBox: LinearLayout
    private val statusTv: TextView
    private val awayLogoIv: ImageView
    private val homeLogoIv: ImageView
    private val awayMetaTv: TextView
    private val homeMetaTv: TextView
    private val scoreTv: TextView

    private var lastConfig: RearLayoutConfig = ScoreStateHolder.layoutConfig.value
    private var rootW: Int = 0
    private var rootH: Int = 0

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

        // 1. 顶部状态栏（仅显示节数时钟或暂停状态，不显示 3s/5s 或刷新时间）
        statusTv = TextView(context).apply {
            text = "NBA LIVE"
            setTextColor(Color.parseColor("#38BDF8"))
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

        // 2. 紧凑队标行（去掉球队名字，只保留队徽 + 暂停/犯规，将最大面积留给下方比分）
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

        // 3. 超大号自适应比分（占满整个安全区宽度和剩余全部高度，绝不换行）
        scoreTv = TextView(context).apply {
            text = "- : -"
            setTextColor(Color.WHITE)
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

        root.addView(contentBox)

        // 监听背屏真实像素尺寸变化，精准计算避让摄像头与缩放后的安全区
        root.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val w = right - left
            val h = bottom - top
            if (w > 0 && h > 0 && (w != rootW || h != rootH)) {
                rootW = w
                rootH = h
                applyLayout(lastConfig)
            }
        }
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
        }
        val meta = TextView(context).apply {
            text = ""
            setTextColor(Color.parseColor("#94A3B8"))
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

    fun applyLayout(config: RearLayoutConfig) {
        lastConfig = config
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
            CameraAvoidMode.CENTER -> {
                // 居中不偏置，仅按 scalePercent 整体缩小
            }
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

        // 按可用安全区高度等比例调整队标和顶部文字，给比分留足最大空间
        val logoSize = (minOf(contentH * 0.27f, contentW * 0.24f)).toInt().coerceIn(36, 150)
        awayLogoIv.layoutParams = LinearLayout.LayoutParams(logoSize, logoSize)
        homeLogoIv.layoutParams = LinearLayout.LayoutParams(logoSize, logoSize)

        statusTv.setTextSize(TypedValue.COMPLEX_UNIT_PX, (contentH * 0.088f).coerceIn(18f, 44f))
        val metaPx = (contentH * 0.068f).coerceIn(14f, 32f)
        awayMetaTv.setTextSize(TypedValue.COMPLEX_UNIT_PX, metaPx)
        homeMetaTv.setTextSize(TypedValue.COMPLEX_UNIT_PX, metaPx)

        contentBox.requestLayout()
    }

    fun bind(game: NbaGame?, detail: LiveGameDetail?, config: RearLayoutConfig) {
        if (config != lastConfig) {
            applyLayout(config)
        }
        if (game == null) {
            statusTv.text = "等待赛程"
            statusTv.setTextColor(Color.parseColor("#94A3B8"))
            scoreTv.text = "- : -"
            awayMetaTv.visibility = View.GONE
            homeMetaTv.visibility = View.GONE
            return
        }

        val d = if (detail?.gameId == game.id) detail else null
        val isTimeout = d?.isTimeoutNow == true

        when {
            isTimeout -> {
                statusTv.text = "⏸ 暂停中 · ${game.statusText}"
                statusTv.setTextColor(Color.parseColor("#FBBF24"))
            }
            game.state == "in" -> {
                statusTv.text = "● ${game.statusText}"
                statusTv.setTextColor(Color.parseColor("#FF5E57"))
            }
            game.state == "pre" -> {
                statusTv.text = game.statusText
                statusTv.setTextColor(Color.parseColor("#FBBF24"))
            }
            else -> {
                statusTv.text = game.statusText
                statusTv.setTextColor(Color.parseColor("#94A3B8"))
            }
        }

        awayLogoIv.load(game.away.logoUrl)
        homeLogoIv.load(game.home.logoUrl)

        // 渲染超大号富文本比分（领先方纯白高亮，落后方柔和浅灰）
        if (game.state == "pre") {
            scoreTv.text = "VS"
            scoreTv.setTextColor(Color.parseColor("#CBD5E1"))
        } else {
            val aStr = game.away.score.toString()
            val hStr = game.home.score.toString()
            val sep = ":"
            val ssb = SpannableStringBuilder()
            val awayColor = if (game.away.score >= game.home.score) Color.WHITE else Color.parseColor("#8B95A8")
            val homeColor = if (game.home.score >= game.away.score) Color.WHITE else Color.parseColor("#8B95A8")

            ssb.append(aStr)
            ssb.setSpan(ForegroundColorSpan(awayColor), 0, aStr.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val sepStart = ssb.length
            ssb.append(sep)
            ssb.setSpan(ForegroundColorSpan(Color.parseColor("#475569")), sepStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val homeStart = ssb.length
            ssb.append(hStr)
            ssb.setSpan(ForegroundColorSpan(homeColor), homeStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            scoreTv.text = ssb
        }

        // 仅在比赛进行中/完赛时显示紧凑的暂停与犯规，未开赛隐藏以更清爽
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
            awayMetaTv.setTextColor(if (awayBonus) Color.parseColor("#FCA5A5") else Color.parseColor("#94A3B8"))
            homeMetaTv.setTextColor(if (homeBonus) Color.parseColor("#FCA5A5") else Color.parseColor("#94A3B8"))
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
