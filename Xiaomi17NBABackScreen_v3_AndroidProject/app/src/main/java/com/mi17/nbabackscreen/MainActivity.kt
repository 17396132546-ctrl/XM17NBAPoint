package com.mi17.nbabackscreen

import android.Manifest
import android.app.ActivityOptions
import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Display
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private var activePresentation: RearScorePresentation? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var infoTv: TextView
    private lateinit var liveSummaryTv: TextView
    private lateinit var listContainer: LinearLayout

    private val scaleButtons = mutableMapOf<Int, Button>()
    private val avoidModeButtons = mutableMapOf<CameraAvoidMode, Button>()
    private val avoidPctButtons = mutableMapOf<Int, Button>()
    private val speedButtons = mutableMapOf<Long, Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScoreStateHolder.init(this)
        requestNotifyPermissionIfNeeded()
        setContentView(buildMainUi())
        observeLiveState()
        refreshNow()
        startLiveService()
    }

    private fun requestNotifyPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                1001
            )
        }
    }

    private fun startLiveService() {
        val intent = Intent(this, ScoreLiveService::class.java).apply {
            action = ScoreLiveService.ACTION_FORCE_REFRESH
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun buildMainUi(): ScrollView {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0B0D12")) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 120, 36, 80)
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(36, bars.top + 32, 36, bars.bottom + 48)
            insets
        }

        val title = TextView(this).apply {
            text = "小米17 Pro Max · NBA 妙享背屏 v3.0"
            setTextColor(Color.WHITE)
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(title)

        infoTv = TextView(this).apply {
            text = "正在检测背屏与实时赛程..."
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setPadding(0, 10, 0, 12)
        }
        root.addView(infoTv)

        liveSummaryTv = TextView(this).apply {
            text = "正在同步比分、暂停与犯规..."
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 13f
            setPadding(24, 18, 24, 18)
            background = GradientDrawable().apply {
                cornerRadius = 20f
                setColor(Color.parseColor("#111622"))
                setStroke(2, Color.parseColor("#1E293B"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 14 }
        }
        root.addView(liveSummaryTv)

        // ===== 背屏防遮挡与缩放调节区（即时同步到背屏） =====
        root.addView(sectionLabel("背屏缩放比例（即时生效）"))
        val scaleRow = makeRow()
        listOf(60, 70, 80, 85, 95).forEach { pct ->
            val b = makeCompactChip("$pct%") {
                ScoreStateHolder.updateScale(pct)
            }
            scaleButtons[pct] = b
            scaleRow.addView(b)
        }
        root.addView(scaleRow)

        root.addView(sectionLabel("摄像头避让方向（防止双摄遮挡）"))
        val avoidRow = makeRow()
        CameraAvoidMode.entries.forEach { mode ->
            val b = makeCompactChip(mode.label) {
                ScoreStateHolder.updateAvoidMode(mode)
            }
            avoidModeButtons[mode] = b
            avoidRow.addView(b)
        }
        root.addView(avoidRow)

        root.addView(sectionLabel("摄像头避让留白宽度（可微调）"))
        val pctRow = makeRow()
        listOf(25, 35, 42, 48).forEach { pct ->
            val b = makeCompactChip("留白$pct%") {
                ScoreStateHolder.updateAvoidPercent(pct)
            }
            avoidPctButtons[pct] = b
            pctRow.addView(b)
        }
        root.addView(pctRow)

        root.addView(sectionLabel("后台同步频率（不在背屏显示）"))
        val speedRow = makeRow()
        listOf(3_000L to "3秒", 5_000L to "5秒", 15_000L to "15秒").forEach { (ms, label) ->
            val b = makeCompactChip(label) {
                ScoreStateHolder.setRefreshInterval(ms)
                startLiveService()
                Toast.makeText(this@MainActivity, "后台已切为每 ${ms / 1000} 秒刷新", Toast.LENGTH_SHORT).show()
            }
            speedButtons[ms] = b
            speedRow.addView(b)
        }
        root.addView(speedRow)

        fun makeActionBtn(label: String, onClick: () -> Unit): Button {
            return Button(this).apply {
                text = label
                isAllCaps = false
                setPadding(22, 20, 22, 20)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 10 }
                setOnClickListener { onClick() }
            }
        }

        root.addView(makeActionBtn("① 开启/刷新 超级岛 & 背屏实时通知") {
            startLiveService()
            Toast.makeText(this@MainActivity, "已开启后台精简比分同步", Toast.LENGTH_SHORT).show()
        })

        root.addView(makeActionBtn("② 悬浮窗直投大字记分牌到背屏（Display 1）") {
            launchRearPresentation()
        })

        root.addView(makeActionBtn("③ 打开全屏大字记分牌（可配合 MRSS 切背屏）") {
            startActivity(Intent(this@MainActivity, RearScoreActivity::class.java))
        })

        val rowBtns = makeRow()
        rowBtns.addView(Button(this).apply {
            text = "刷新赛程"
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                refreshNow()
                startLiveService()
            }
        })
        rowBtns.addView(Button(this).apply {
            text = "关闭背屏窗口"
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                activePresentation?.dismiss()
                activePresentation = null
                Toast.makeText(this@MainActivity, "已关闭背屏记分牌窗口", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(rowBtns)

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 0)
        }
        root.addView(listContainer)
        scroll.addView(root)
        return scroll
    }

    private fun sectionLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11.5f
            setPadding(4, 6, 4, 4)
        }
    }

    private fun makeRow(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8 }
        }
    }

    private fun makeCompactChip(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 11.5f
            minWidth = 0
            minimumWidth = 0
            setPadding(8, 12, 8, 12)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 6
            }
            setOnClickListener { onClick() }
        }
    }

    private fun updateChipHighlights(cfg: RearLayoutConfig, intervalMs: Long) {
        val activeTint = ColorStateList.valueOf(Color.parseColor("#0284C7"))
        val normalTint = ColorStateList.valueOf(Color.parseColor("#1E2430"))

        scaleButtons.forEach { (pct, btn) ->
            btn.backgroundTintList = if (pct == cfg.scalePercent) activeTint else normalTint
            btn.setTextColor(if (pct == cfg.scalePercent) Color.WHITE else Color.parseColor("#CBD5E1"))
        }
        avoidModeButtons.forEach { (mode, btn) ->
            btn.backgroundTintList = if (mode == cfg.avoidMode) activeTint else normalTint
            btn.setTextColor(if (mode == cfg.avoidMode) Color.WHITE else Color.parseColor("#CBD5E1"))
        }
        avoidPctButtons.forEach { (pct, btn) ->
            btn.backgroundTintList = if (pct == cfg.avoidPercent) activeTint else normalTint
            btn.setTextColor(if (pct == cfg.avoidPercent) Color.WHITE else Color.parseColor("#CBD5E1"))
        }
        speedButtons.forEach { (ms, btn) ->
            btn.backgroundTintList = if (ms == intervalMs) activeTint else normalTint
            btn.setTextColor(if (ms == intervalMs) Color.WHITE else Color.parseColor("#CBD5E1"))
        }
    }

    private fun observeLiveState() {
        scope.launch {
            combine(
                ScoreStateHolder.games,
                ScoreStateHolder.selectedGameId,
                ScoreStateHolder.liveDetail,
                ScoreStateHolder.layoutConfig,
                ScoreStateHolder.refreshIntervalMs
            ) { games, _, detail, cfg, interval ->
                UiSnapshot(games, ScoreStateHolder.currentGame(), detail, cfg, interval)
            }.collect { snap ->
                updateChipHighlights(snap.cfg, snap.interval)
                renderList(snap.games, snap.current?.id)
                renderLiveSummary(snap.current, snap.detail, snap.cfg)
            }
        }
    }

    private data class UiSnapshot(
        val games: List<NbaGame>,
        val current: NbaGame?,
        val detail: LiveGameDetail?,
        val cfg: RearLayoutConfig,
        val interval: Long
    )

    private fun renderLiveSummary(
        game: NbaGame?,
        detail: LiveGameDetail?,
        cfg: RearLayoutConfig
    ) {
        if (game == null) {
            liveSummaryTv.text = "当前暂无锁定比赛"
            return
        }
        val d = if (detail?.gameId == game.id) detail else null
        val aTo = d?.awayTimeouts?.takeIf { it >= 0 } ?: game.away.timeoutsRemaining
        val hTo = d?.homeTimeouts?.takeIf { it >= 0 } ?: game.home.timeoutsRemaining
        val aF = d?.awayQuarterFouls?.takeIf { it >= 0 } ?: -1
        val hF = d?.homeQuarterFouls?.takeIf { it >= 0 } ?: -1
        val aB = if (d?.awayInBonus ?: game.away.inBonus) "(满)" else ""
        val hB = if (d?.homeInBonus ?: game.home.inBonus) "(满)" else ""
        val pauseTag = if (d?.isTimeoutNow == true) " ⏸暂停中" else ""

        val lines = buildString {
            append("已锁定：${game.away.cnName} ${game.away.score} : ${game.home.score} ${game.home.cnName}$pauseTag\n")
            append("状态：${game.statusText}")
            if (game.state != "pre") {
                if (aTo >= 0 && hTo >= 0) append("  |  暂 $aTo:$hTo")
                if (aF >= 0 && hF >= 0) append("  |  犯 $aF$aB:$hF$hB")
            }
            append("\n背屏布局：${cfg.avoidMode.label} · 留白${cfg.avoidPercent}% · 缩放${cfg.scalePercent}%")
        }
        liveSummaryTv.text = lines
    }

    private fun refreshNow() {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val rearDisplay = dm.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        val displayDesc = if (rearDisplay != null) {
            "已检测到背屏 Display ID=${rearDisplay.displayId} (${rearDisplay.mode.physicalWidth}x${rearDisplay.mode.physicalHeight})"
        } else {
            "当前仅检测到主屏"
        }
        infoTv.text = displayDesc

        scope.launch {
            try {
                val games = NbaRepository.fetchGames()
                ScoreStateHolder.updateGames(games)
                ScoreStateHolder.currentGame()?.let { g ->
                    ScoreStateHolder.updateDetail(NbaRepository.fetchLiveDetail(g))
                }
            } catch (e: Exception) {
                infoTv.text = "$displayDesc\n拉取失败: ${e.message}"
            }
        }
    }

    private fun renderList(games: List<NbaGame>, selectedId: String?) {
        listContainer.removeAllViews()
        if (games.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "今日暂无 NBA 比赛"
                setTextColor(Color.LTGRAY)
            })
            return
        }
        games.forEach { g ->
            val isSel = g.id == selectedId
            val btn = Button(this).apply {
                val tag = if (g.state == "in") "🔴" else if (g.state == "pre") "🟡" else "⚪"
                val selPrefix = if (isSel) "✔ " else ""
                val extra = if (g.state == "in" && g.away.timeoutsRemaining >= 0) {
                    " · 暂${g.away.timeoutsRemaining}:${g.home.timeoutsRemaining}"
                } else ""
                text = "$selPrefix$tag ${g.away.cnName} ${g.away.score} : ${g.home.score} ${g.home.cnName}\n${g.statusText}$extra"
                isAllCaps = false
                setPadding(22, 18, 22, 18)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 10 }
                setOnClickListener {
                    ScoreStateHolder.selectGame(g.id)
                    startLiveService()
                    Toast.makeText(
                        this@MainActivity,
                        "已切换：${g.away.cnName} vs ${g.home.cnName}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            listContainer.addView(btn)
        }
    }

    private fun launchRearPresentation() {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val rear = dm.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        if (rear == null) {
            Toast.makeText(this, "未检测到背屏，改用主屏全屏预览", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, RearScoreActivity::class.java))
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先打开「显示在其他应用上层」悬浮窗权限，返回后再点②", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        try {
            activePresentation?.dismiss()
            val pres = RearScorePresentation(applicationContext, rear)
            pres.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            pres.window?.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            )
            pres.show()
            activePresentation = pres
            startLiveService()
            infoTv.text = "✅ 已投送至背屏 Display ${rear.displayId}，点上方缩放/避让按钮可实时微调！"
            Toast.makeText(this, "已投送到背屏，翻转手机查看", Toast.LENGTH_SHORT).show()
            return
        } catch (presErr: Exception) {
            try {
                val intent = Intent(this, RearScoreActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                }
                val opts = ActivityOptions.makeBasic().setLaunchDisplayId(rear.displayId)
                startActivity(intent, opts.toBundle())
                infoTv.text = "✅ 已通过 Activity 直投背屏 Display ${rear.displayId}"
            } catch (actErr: Exception) {
                infoTv.text = "⚠️ 两种直投均被澎湃OS拦截：\n" +
                    "1) 悬浮窗方式: ${presErr.javaClass.simpleName}: ${presErr.message}\n" +
                    "2) Activity方式: ${actErr.javaClass.simpleName}: ${actErr.message}\n" +
                    "建议：点③打开全屏记分牌后，用 Shizuku+MRSS 一键切到背屏。"
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

class RearScorePresentation(outerContext: Context, display: Display) :
    Presentation(outerContext, display) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var uiHolder: RearScoreViewHolder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScoreStateHolder.init(context)
        uiHolder = RearScoreViewHolder(context)
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
                uiHolder.bind(game, detail, cfg)
            }
        }
    }

    override fun onStop() {
        scope.cancel()
        super.onStop()
    }
}
