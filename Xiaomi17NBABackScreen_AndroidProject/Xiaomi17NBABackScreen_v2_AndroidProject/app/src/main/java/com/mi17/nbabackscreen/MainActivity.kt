package com.mi17.nbabackscreen

import android.Manifest
import android.app.ActivityOptions
import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotifyPermissionIfNeeded()
        setContentView(buildMainUi())
        observeLiveState()
        // 启动即拉取一次并默认开启实时后台轮询（3秒一刷）
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
            setPadding(40, 120, 40, 80)
        }

        // 自动适配小米状态栏与底部导航栏高度
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(40, bars.top + 36, 40, bars.bottom + 48)
            insets
        }

        val title = TextView(this).apply {
            text = "小米17 Pro Max · NBA 妙享背屏 v2.0"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(title)

        infoTv = TextView(this).apply {
            text = "正在检测背屏与实时赛程..."
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12.5f
            setPadding(0, 12, 0, 14)
        }
        root.addView(infoTv)

        // 当前锁定场次的实时态势卡片（比分 + 暂停 + 犯规 + 最新事件）
        liveSummaryTv = TextView(this).apply {
            text = "正在同步暂停、犯规与实时动态..."
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 13.5f
            setPadding(28, 22, 28, 22)
            background = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(Color.parseColor("#111622"))
                setStroke(2, Color.parseColor("#1E293B"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
        }
        root.addView(liveSummaryTv)

        // 实时刷新频率切换栏
        val speedRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 14 }
        }
        listOf(3_000L to "⚡ 3秒实时", 5_000L to "🚀 5秒标准", 15_000L to "🔋 15秒省电").forEach { (ms, label) ->
            speedRow.addView(Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    ScoreStateHolder.setRefreshInterval(ms)
                    startLiveService()
                    Toast.makeText(this@MainActivity, "已切换为每 ${ms / 1000} 秒刷新", Toast.LENGTH_SHORT).show()
                }
            })
        }
        root.addView(speedRow)

        fun makeActionBtn(label: String, onClick: () -> Unit): Button {
            return Button(this).apply {
                text = label
                isAllCaps = false
                setPadding(24, 22, 24, 22)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 12 }
                setOnClickListener { onClick() }
            }
        }

        root.addView(makeActionBtn("① 开启/刷新 超级岛 & 背屏实时通知") {
            startLiveService()
            Toast.makeText(this@MainActivity, "已启动实时推送（含暂停/犯规状态）", Toast.LENGTH_SHORT).show()
        })

        root.addView(makeActionBtn("② 悬浮窗直投全屏记分牌到背屏（Display 1）") {
            launchRearPresentation()
        })

        root.addView(makeActionBtn("③ 打开全屏大字记分牌（可配合 MRSS 切背屏）") {
            startActivity(Intent(this@MainActivity, RearScoreActivity::class.java))
        })

        val rowBtns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 12 }
        }
        rowBtns.addView(Button(this).apply {
            text = "立即刷新赛程"
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

    private fun observeLiveState() {
        scope.launch {
            combine(
                ScoreStateHolder.games,
                ScoreStateHolder.selectedGameId,
                ScoreStateHolder.liveDetail,
                ScoreStateHolder.lastUpdateTime,
                ScoreStateHolder.refreshIntervalMs
            ) { games, _, detail, time, interval ->
                StateSnapshot(games, ScoreStateHolder.currentGame(), detail, time, interval)
            }.collect { snap ->
                renderList(snap.games, snap.current?.id)
                renderLiveSummary(snap.current, snap.detail, snap.time, snap.interval)
            }
        }
    }

    private data class StateSnapshot(
        val games: List<NbaGame>,
        val current: NbaGame?,
        val detail: LiveGameDetail?,
        val time: String,
        val interval: Long
    )

    private fun renderLiveSummary(
        game: NbaGame?,
        detail: LiveGameDetail?,
        time: String,
        interval: Long
    ) {
        if (game == null) {
            liveSummaryTv.text = "当前暂无锁定比赛（更新于 $time）"
            return
        }
        val d = if (detail?.gameId == game.id) detail else null
        val aTo = d?.awayTimeouts?.takeIf { it >= 0 } ?: game.away.timeoutsRemaining
        val hTo = d?.homeTimeouts?.takeIf { it >= 0 } ?: game.home.timeoutsRemaining
        val aF = d?.awayQuarterFouls?.takeIf { it >= 0 } ?: -1
        val hF = d?.homeQuarterFouls?.takeIf { it >= 0 } ?: -1
        val aB = if (d?.awayInBonus ?: game.away.inBonus) " [犯满]" else ""
        val hB = if (d?.homeInBonus ?: game.home.inBonus) " [犯满]" else ""

        val lines = buildString {
            append("当前锁定：${game.away.cnName} ${game.away.score} : ${game.home.score} ${game.home.cnName}")
            append("  (${game.statusText})\n")
            if (game.state != "pre") {
                val toText = if (aTo >= 0 && hTo >= 0) "剩余暂停：$aTo vs $hTo" else "剩余暂停：--"
                val fText = if (aF >= 0 && hF >= 0) "本节犯规：$aF$aB vs $hF$hB" else "本节犯规：--"
                append("$toText  |  $fText\n")
            }
            val latest = d?.latestEventText.orEmpty()
            if (latest.isNotBlank()) {
                append("最新动态：$latest\n")
            }
            append("刷新频率：每 ${interval / 1000} 秒  ·  上次同步：$time  ·  源：${game.source}")
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
                setPadding(24, 22, 24, 22)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 12 }
                setOnClickListener {
                    ScoreStateHolder.selectGame(g.id)
                    startLiveService()
                    Toast.makeText(
                        this@MainActivity,
                        "已切换并实时同步：${g.away.cnName} vs ${g.home.cnName}",
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
            infoTv.text = "✅ 已通过悬浮窗直投背屏 Display ${rear.displayId}（每${ScoreStateHolder.refreshIntervalMs.value / 1000}秒实时刷新），翻转手机查看！"
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
        uiHolder = RearScoreViewHolder(context)
        setContentView(uiHolder.root)

        scope.launch {
            combine(
                ScoreStateHolder.games,
                ScoreStateHolder.selectedGameId,
                ScoreStateHolder.liveDetail,
                ScoreStateHolder.lastUpdateTime,
                ScoreStateHolder.refreshIntervalMs
            ) { _, _, detail, time, interval ->
                Quad(ScoreStateHolder.currentGame(), detail, time, interval)
            }.collect { (game, detail, time, interval) ->
                uiHolder.bind(game, detail, time, interval)
            }
        }
    }

    private data class Quad(
        val game: NbaGame?,
        val detail: LiveGameDetail?,
        val time: String,
        val interval: Long
    )

    override fun onStop() {
        scope.cancel()
        super.onStop()
    }
}
