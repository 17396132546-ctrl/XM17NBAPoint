# 小米 17 Pro Max 妙享背屏 · NBA 实时比分客户端

专为小米 17 Pro Max（Android 16 / 澎湃 OS 3 & 4，妙享背屏 976×596 OLED）设计的纯本地 NBA 实时比分应用，**无需自建服务器**。

## 数据源（免费免 Key，自动双源容灾）
1. **主数据源（ESPN 公开接口）**：`https://site.api.espn.com/apis/site/v2/sports/basketball/nba/scoreboard`
2. **备用数据源（NBA 官方 CDN）**：`https://cdn.nba.com/static/json/liveData/scoreboard/todaysScoreboard_00.json`
3. **高清球队图标**：ESPN 官方 500px 透明底队标 PNG（`https://a.espncdn.com/i/teamlogos/nba/500/{abbr}.png`）
4. **中文队名**：内置全 30 支 NBA 球队中文名映射，彻底告别英文缩写。

## 如何生成可直接安装的 APK（两种方式）

### 方式 A：零环境云端打包（推荐，不需要装 Android Studio）
1. 在 GitHub 新建一个私有或公开仓库，把本压缩包内所有文件上传上去。
2. 打开仓库顶部的 **Actions** 页面，点击 `Build Xiaomi 17 Pro Max NBA BackScreen APK` -> **Run workflow**。
3. 等待约 2 分钟，在页面下方 **Artifacts** 直接下载 `Xiaomi17-NBA-BackScreen-APK` 解压得到 `app-debug.apk`，传到小米 17 Pro Max 安装即可。

### 方式 B：本地 Android Studio 打包
1. 用 Android Studio（2024.3+ / JDK 17）打开本文件夹。
2. 点击菜单 `Build -> Build Bundle(s) / APK(s) -> Build APK(s)` 即可生成安装包。

## 小米 17 Pro Max 使用说明
1. **免 Root 推荐模式**：打开 App -> 点击想看的比赛锁定 -> 点 **① 开启超级岛 / 背屏实时通知**。请在小米设置中给本应用开启 **通知权限、自启动、省电策略无限制**。
2. **副屏全屏记分牌模式（Display 1）**：点击 **② 投送全屏大字记分牌到背屏**。如原厂系统拦截了第三方副屏窗口，可配合 Shizuku 或已 Root 环境的 `REAREye` 模块将本应用（`com.mi17.nbabackscreen`）加入背屏白名单。
