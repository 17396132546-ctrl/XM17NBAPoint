package com.mi17.nbabackscreen

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.load

/**
 * 统一的背屏 OLED 实时记分牌视图构建器（供 RearScoreActivity 和悬浮窗 RearScorePresentation 共用）。
 * 展示：队标、中文队名、超大比分、节数时钟、剩余暂停、本节犯规/BONUS 犯满标志、底部暂停/犯规实时播报条。
 */
class RearScoreViewHolder(context: Context) {
    val root: LinearLayout
    private val statusTv: TextView
    private val timeMetaTv: TextView
    private val awayLogoIv: ImageView
    private val homeLogoIv: ImageView
    private val awayNameTv: TextView
    private val homeNameTv: TextView
    private val awayMetaTv: TextView
    private val homeMetaTv: TextView
    private val awayScoreTv: TextView
    private val homeScoreTv: TextView
    private val tickerTv: TextView

    init {
        root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 14, 24, 14)
        }

        // 顶部状态栏：比赛状态 + 刷新时间
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        statusTv = TextView(context).apply {
            text = "NBA LIVE"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        timeMetaTv = TextView(context).apply {
            text = "3s实时"
            setTextColor(Color.parseColor("#64748B"))
            textSize = 11f
        }
        topBar.addView(statusTv)
        topBar.addView(timeMetaTv)
        root.addView(topBar)

        // 中部主比分行
        val midRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        // 客队列
        val awayCol = makeTeamColumn(context)
        awayLogoIv = awayCol.first
        awayNameTv = awayCol.second
        awayMetaTv = awayCol.third

        // 中央大比分
        val scoreBox = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(8, 0, 8, 0)
        }
        awayScoreTv = TextView(context).apply {
            text = "-"
            setTextColor(Color.WHITE)
            textSize = 42f
            typeface = Typeface.DEFAULT_BOLD
        }
        val colonTv = TextView(context).apply {
            text = " : "
            setTextColor(Color.parseColor("#475569"))
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
        }
        homeScoreTv = TextView(context).apply {
            text = "-"
            setTextColor(Color.WHITE)
            textSize = 42f
            typeface = Typeface.DEFAULT_BOLD
        }
        scoreBox.addView(awayScoreTv)
        scoreBox.addView(colonTv)
        scoreBox.addView(homeScoreTv)

        // 主队列
        val homeCol = makeTeamColumn(context)
        homeLogoIv = homeCol.first
        homeNameTv = homeCol.second
        homeMetaTv = homeCol.third

        midRow.addView(awayCol.fourth)
        midRow.addView(scoreBox)
        midRow.addView(homeCol.fourth)
        root.addView(midRow)

        // 底部实时事件条（暂停 / 犯规 / 得分高亮提示）
        tickerTv = TextView(context).apply {
            text = "等待实时比赛动态..."
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(20, 8, 20, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 6 }
            background = makePillBg("#111827", "#1F2937")
        }
        root.addView(tickerTv)
    }

    private data class TeamColViews(
        val first: ImageView,
        val second: TextView,
        val third: TextView,
        val fourth: LinearLayout
    )

    private fun makeTeamColumn(context: Context): TeamColViews {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val logo = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(104, 104)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val name = TextView(context).apply {
            text = "--"
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 1
        }
        val meta = TextView(context).apply {
            text = "暂停- · 犯规-"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
            gravity = Gravity.CENTER
            maxLines = 1
        }
        col.addView(logo)
        col.addView(name)
        col.addView(meta)
        return TeamColViews(logo, name, meta, col)
    }

    private fun makePillBg(fillHex: String, strokeHex: String): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = 24f
            setColor(Color.parseColor(fillHex))
            setStroke(2, Color.parseColor(strokeHex))
        }
    }

    fun bind(game: NbaGame?, detail: LiveGameDetail?, updateTime: String, intervalMs: Long) {
        timeMetaTv.text = "${intervalMs / 1000}s实时 · $updateTime"
        if (game == null) {
            statusTv.text = "等待同步今日 NBA 赛程..."
            tickerTv.visibility = View.GONE
            return
        }

        val d = if (detail?.gameId == game.id) detail else null
        val isTimeout = d?.isTimeoutNow == true
        when {
            isTimeout -> {
                statusTv.text = "⏸️ 暂停中 · ${game.statusText}"
                statusTv.setTextColor(Color.parseColor("#FBBF24"))
            }
            game.state == "in" -> {
                statusTv.text = "🔴 LIVE · ${game.statusText}"
                statusTv.setTextColor(Color.parseColor("#FF6B63"))
            }
            game.state == "pre" -> {
                statusTv.text = "🟡 未开赛 · ${game.statusText}"
                statusTv.setTextColor(Color.parseColor("#FBBF24"))
            }
            else -> {
                statusTv.text = "⚪ 已完赛 · ${game.statusText}"
                statusTv.setTextColor(Color.parseColor("#94A3B8"))
            }
        }

        awayNameTv.text = game.away.cnName
        homeNameTv.text = game.home.cnName
        awayLogoIv.load(game.away.logoUrl)
        homeLogoIv.load(game.home.logoUrl)

        if (game.state == "pre") {
            awayScoreTv.text = "-"
            homeScoreTv.text = "-"
            awayScoreTv.setTextColor(Color.WHITE)
            homeScoreTv.setTextColor(Color.WHITE)
        } else {
            awayScoreTv.text = game.away.score.toString()
            homeScoreTv.text = game.home.score.toString()
            awayScoreTv.setTextColor(if (game.away.score >= game.home.score) Color.WHITE else Color.parseColor("#7C8497"))
            homeScoreTv.setTextColor(if (game.home.score >= game.away.score) Color.WHITE else Color.parseColor("#7C8497"))
        }

        // 暂停与犯规状态条
        val awayTo = d?.awayTimeouts?.takeIf { it >= 0 } ?: game.away.timeoutsRemaining
        val homeTo = d?.homeTimeouts?.takeIf { it >= 0 } ?: game.home.timeoutsRemaining
        val awayBonus = d?.awayInBonus ?: game.away.inBonus
        val homeBonus = d?.homeInBonus ?: game.home.inBonus
        val awayFoul = d?.awayQuarterFouls?.takeIf { it >= 0 } ?: d?.awayTotalFouls ?: -1
        val homeFoul = d?.homeQuarterFouls?.takeIf { it >= 0 } ?: d?.homeTotalFouls ?: -1

        awayMetaTv.text = formatTeamMeta(game.state, awayTo, awayFoul, awayBonus, game.away.record)
        homeMetaTv.text = formatTeamMeta(game.state, homeTo, homeFoul, homeBonus, game.home.record)
        awayMetaTv.setTextColor(if (awayBonus) Color.parseColor("#FCA5A5") else Color.parseColor("#94A3B8"))
        homeMetaTv.setTextColor(if (homeBonus) Color.parseColor("#FCA5A5") else Color.parseColor("#94A3B8"))

        // 底部实时事件播报（暂停黄色、犯规红色、得分蓝色）
        val eventText = d?.latestEventText?.ifBlank { game.fallbackLastPlay } ?: game.fallbackLastPlay
        if (eventText.isBlank()) {
            tickerTv.visibility = View.GONE
        } else {
            tickerTv.visibility = View.VISIBLE
            tickerTv.text = eventText
            when (d?.latestEventType) {
                "timeout" -> {
                    tickerTv.setTextColor(Color.parseColor("#FEF08A"))
                    tickerTv.background = makePillBg("#422006", "#F59E0B")
                }
                "foul" -> {
                    tickerTv.setTextColor(Color.parseColor("#FECACA"))
                    tickerTv.background = makePillBg("#450A0A", "#EF4444")
                }
                "score" -> {
                    tickerTv.setTextColor(Color.parseColor("#BAE6FD"))
                    tickerTv.background = makePillBg("#082F49", "#38BDF8")
                }
                else -> {
                    tickerTv.setTextColor(Color.parseColor("#CBD5E1"))
                    tickerTv.background = makePillBg("#111827", "#1F2937")
                }
            }
        }
    }

    private fun formatTeamMeta(
        state: String,
        timeouts: Int,
        fouls: Int,
        inBonus: Boolean,
        record: String
    ): String {
        if (state == "pre") return record.ifBlank { "未开赛" }
        val parts = mutableListOf<String>()
        if (timeouts >= 0) parts += "暂$timeouts"
        if (fouls >= 0) parts += "犯$fouls"
        if (inBonus) parts += "犯满"
        return if (parts.isNotEmpty()) parts.joinToString("·") else record
    }
}
