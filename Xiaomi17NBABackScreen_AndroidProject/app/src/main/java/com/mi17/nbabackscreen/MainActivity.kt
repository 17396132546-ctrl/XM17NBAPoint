package com.mi17.nbabackscreen

import android.Manifest
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 主屏控制台：
 * 1. 展示今日 NBA 赛程并点选关注场次；
 * 2. 一键开启「超级岛/背屏焦点通知流转」（免 Root）；
 * 3. 一键检测小米 17 Pro Max 副屏 Display 1 并投送全屏记分牌。
 */
class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var infoTv: TextView
    private lateinit var listContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotifyPermissionIfNeeded()
        setContentView(buildMainUi())
        refreshNow()
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

    private fun buildMainUi(): ScrollView {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0B0D12")) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 80, 40, 60)
        }

        val title = TextView(this).apply {
            text = "小米17 Pro Max · NBA 妙享背屏"
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(title)

        infoTv = TextView(this).apply {
            text = "正在检测屏幕与赛程..."
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13f
            setPadding(0, 14, 0, 24)
        }
        root.addView(infoTv)

        val btnStartLive = Button(this).apply {
            text = "① 开启超级岛 / 背屏实时通知（免Root推荐）"
            setOnClickListener {
                val intent = Intent(this@MainActivity, ScoreLiveService::class.java)
                ContextCompat.startForegroundService(this@MainActivity, intent)
                Toast.makeText(this@MainActivity, "已开启每15秒后台比分推送", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(btnStartLive)

        val btnCastRear = Button(this).apply {
            text = "② 投送全屏大字记分牌到背屏 (Display 1)"
            setOnClickListener { launchOnRearDisplay() }
        }
        root.addView(btnCastRear)

        val btnRefresh = Button(this).apply {
            text = "刷新今日 NBA 赛程"
            setOnClickListener { refreshNow() }
        }
        root.addView(btnRefresh)

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 24, 0, 0)
        }
        root.addView(listContainer)
        scroll.addView(root)
        return scroll
    }

    private fun refreshNow() {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = dm.displays
        val rearDisplay = displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        val displayDesc = if (rearDisplay != null) {
            "已检测到背屏 Display ID=${rearDisplay.displayId} (${rearDisplay.mode.physicalWidth}x${rearDisplay.mode.physicalHeight})"
        } else {
            "当前仅检测到主屏（点击①仍可通过系统焦点通知流转至背屏）"
        }
        infoTv.text = displayDesc

        scope.launch {
            try {
                val games = NbaRepository.fetchGames()
                ScoreStateHolder.updateGames(games)
                renderList(games)
            } catch (e: Exception) {
                infoTv.text = "$displayDesc\n拉取失败: ${e.message}"
            }
        }
    }

    private fun renderList(games: List<NbaGame>) {
        listContainer.removeAllViews()
        if (games.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "今日暂无 NBA 比赛"
                setTextColor(Color.LTGRAY)
            })
            return
        }
        games.forEach { g ->
            val btn = Button(this).apply {
                val tag = if (g.state == "in") "🔴" else if (g.state == "pre") "🟡" else "⚪"
                text = "$tag ${g.away.cnName} ${g.away.score} : ${g.home.score} ${g.home.cnName} (${g.statusText})"
                setOnClickListener {
                    ScoreStateHolder.selectGame(g.id)
                    Toast.makeText(this@MainActivity, "已锁定：${g.away.cnName} vs ${g.home.cnName}", Toast.LENGTH_SHORT).show()
                }
            }
            listContainer.addView(btn)
        }
    }

    private fun launchOnRearDisplay() {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val rear = dm.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        val intent = Intent(this, RearScoreActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        if (rear != null) {
            val opts = ActivityOptions.makeBasic().setLaunchDisplayId(rear.displayId)
            try {
                startActivity(intent, opts.toBundle())
                Toast.makeText(this, "已向背屏 Display ${rear.displayId} 发送界面", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "系统拦截了副屏直投，请配合 Shizuku/REAREye 或改用方案①", Toast.LENGTH_LONG).show()
            }
        } else {
            // 未检测到副屏时在主屏预览背屏大字 UI
            startActivity(intent)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
