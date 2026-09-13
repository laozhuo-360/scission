# 断流 Scission

> 一部手机，一键模拟 100% 丢包的弱网环境。

测 App 在断网、弱网下的表现，开飞行模式太重，WiFi 和蓝牙一起断；改路由器又太麻烦。断流把设备变成一个网络黑洞，所有进出的流量进得来、出不去，等效于 100% 丢包。开是一下，关也是一下。

**⚠️ 免责声明：本项目仅供网络测试、应用开发调试等合法用途。禁止用于任何非法用途（如切断他人网络、规避计费、干扰通信服务等）。使用本软件所产生的一切后果由使用者自行承担。**

---

## 功能

| 入口 | 操作 |
|---|---|
| 主界面星空开关 | 点一下断网，再点一下恢复 |
| 悬浮窗 | 可拖动圆钮，单击切换状态，长按进主界面 |
| 通知栏按钮 | 常驻通知自带「断网 / 恢复」按钮，任何界面下拉即用 |
| 音量键 | 音量**上**键 = 开启断网；音量**下**键 = 直接恢复（需开启无障碍服务） |

- 开启时有色状态提示，悬浮球蓝（正常）红（断网）双色区分
- 状态机带防抖，快速连按音量键不会卡顿或堆积任务
- 主界面左上角按时段问候，模式选择、快捷开关一目了然
- 首次启动有 5 步配置向导，权限申请与模式选择引导到位

## 两种运行模式

两种模式效果一样，都是 100% 丢包，区别在实现途径和系统表现。

### Root 模式（推荐）

通过 `su` 执行 iptables 规则，走内核级拦截。

- 使用自定义链 `NB_OUT` / `NB_IN`，插入 `OUTPUT` 与 `INPUT` 链首，除 `lo` 回环外全部 `DROP`
- `ip6tables` 同步处理，IPv6 流量一并拦截
- 保留本机内部通信（`-o lo` / `-i lo` 豁免），不影响 App 间本地通信
- **不留 VPN 图标**，可与其他 VPN 共存，断网状态不受进程存活影响
- App 冷启动时会无条件清理残留规则，避免上次异常退出导致永久断网

### 无 Root 模式

基于 `VpnService` 建立黑洞隧道，不需要 Root。

- `Builder` 添加 `0.0.0.0/0` 与 `::/0` 全量路由，接管设备全部流量
- TUN 读出数据后直接丢弃，不做转发，出站包石沉大海
- 首次使用需授权一次系统「VPN 连接」对话框
- 状态栏会出现 VPN 钥匙图标，属正常现象
- 不能与其他 VPN 同时开启

## 技术要点

**状态机统一入口**。所有控制入口（主界面 / 悬浮窗 / 通知 / 音量键）都经过 `NetBlocker`，单点维护状态与广播。

**防抖设计**。`setBlocked()` 只写入期望状态 `AtomicBoolean` 后立即返回，由单线程 `drainApply()` 循环消化。快速连按 N 次时中间态全部跳过，只执行最终值，界面不卡、Toast 也不刷屏。

**音量键拦截**。`AccessibilityService` + `FLAG_REQUEST_FILTER_KEY_EVENTS` 拦截 `VOLUME_UP` / `VOLUME_DOWN`，消费事件后不改变系统音量。

**自绘开关组件**。`PillSwitchView` 完全自绘。关闭态是白天跑道（虚线加白云），开启态是深夜星空（星星加月牙）。`ValueAnimator` 弹性滑动配 `ArgbEvaluator` 颜色混合，没引第三方依赖。

**服务保活自愈**。`FloatingService` 暴露 `sRunning` 静态标志，主界面 `onResume` 检测到服务被系统回收、而用户仍需悬浮窗时，自动重新拉起。

**合规前台服务**。`targetSdk 35`，VPN 与悬浮窗服务都声明了 `foregroundServiceType="specialUse"`，并附上 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 说明。

## 权限说明

| 权限 | 用途 |
|---|---|
| `INTERNET` / `ACCESS_NETWORK_STATE` | 基础网络状态（VPN 模式所需） |
| `SYSTEM_ALERT_WINDOW` | 显示悬浮窗 |
| `FOREGROUND_SERVICE` / `_SPECIAL_USE` | 常驻通知与悬浮窗服务 |
| `POST_NOTIFICATIONS` | 显示断网状态通知（Android 13+） |
| 无障碍服务 | **仅用于监听音量键**，不读取屏幕内容、不采集任何数据 |

无障碍这块可能有人会起疑，说明白：本项目的无障碍服务只实现 `onKeyEvent` 拦截音量键，`onAccessibilityEvent` 是空实现，`accessibility_config.xml` 里还显式设了 `canRetrieveWindowContent="false"`。系统层面就禁止了读界面内容。不信可以翻代码，`VolumeKeyService.java` 和 `res/xml/accessibility_config.xml`。

## 构建

### 环境要求

- JDK 17+
- Android SDK（compileSdk 35、build-tools 34.0.0）
- Gradle 8.9

### 命令行构建

```bash
# 配置 SDK 路径（此文件不会被提交）
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 打包 Debug APK
gradle assembleDebug
```

产物路径 `app/build/outputs/apk/debug/app-debug.apk`

### 使用 Android Studio

直接 `File → Open` 打开项目根目录，等 Gradle 同步完就能跑。

## 项目结构

```
app/src/main/java/com/linye/netblock/
├── App.java                      # Application 入口，初始化状态机
├── core/
│   ├── NetBlocker.java           # 核心状态机（统一控制入口 + 防抖）
│   ├── RootShell.java            # Root 模式：iptables 规则管理
│   └── NetActionReceiver.java    # 通知栏按钮广播接收
├── service/
│   ├── HoleVpnService.java       # 无 Root 模式：VPN 黑洞隧道
│   ├── FloatingService.java      # 前台服务：常驻通知 + 悬浮窗
│   └── VolumeKeyService.java     # 无障碍服务：音量键拦截
└── ui/
    ├── MainActivity.java          # 主界面
    ├── SetupActivity.java         # 首次配置向导
    └── PillSwitchView.java        # 自绘星空胶囊开关
tools/
├── generate_icon.py              # 启动图标生成脚本（Pillow）
├── verify_apk.py                 # APK 资源字节校验
└── verify_fixes.py               # 修复项回归校验
```

## 已知限制

**厂商 ROM 保活**。小米、OPPO、vivo 这些系统的「清理后台」可能连带关掉无障碍服务和后台服务。需要在系统设置里允许自启动，再在最近任务里锁定后台。App 检测到服务失效会提示，但拦不住系统行为。

**应用商店审核**。这个 App 同时用了 `su` 执行 shell、无障碍服务、VPN 全流量接管。这三项凑一起，多数应用商店都难通过。项目面向开发者和本机调试。

**与其他 VPN 冲突**。无 Root 模式会占用系统 VPN 通道，跟别的 VPN 没法同时开。需要共存就用 Root 模式。

## 许可证

[MIT License](LICENSE)

UI 设计灵感来自 [uiverse.io](https://uiverse.io) 开源组件库。开关组件是独立自绘实现，没拷贝第三方代码。
