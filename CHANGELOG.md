# 更新日志

断流 Scission 的版本变更都记在这儿。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

## [1.1] - 2026-09-26

### 修复

- 无 Root 模式断网后恢复不了。根源是三处缺陷叠在一起：
  - 黑洞读取循环用阻塞式 `read()`，隧道一关，线程就卡死在那儿，
    服务销毁不掉，下次启动被这个半死的实例拦住。
    改用 `Os.poll()` 每 100ms 轮询一次，`running` 一置位，循环很快就退出来。
  - 服务声明为 `START_STICKY`，被系统回收后拿到的 intent 是 null，
    原代码把它当成启动指令，刚恢复的网络又被自动断开。
    改成 `START_NOT_STICKY`，遇到 null intent 直接自杀。
  - 关闭动作走 `stopService()` 被动等系统回收，回收时序一乱，
    清理逻辑就轮不上执行。改成主动发停止指令，让服务自己走完关闭流程。
- 连着快速按开关时，后面那次请求会被防抖逻辑吞掉，界面像没反应。
  状态比对那儿加了同步保护。

### 变更

- 补上 Gradle Wrapper，克隆下来直接 `./gradlew` 就能构建，不用自己装 Gradle。

## [1.0] - 2026-09-13

### 新增

- 首次发布。
- Root 模式：通过 `su` 执行 iptables 规则，在内核层丢弃全部出入站流量。
- 无 Root 模式：基于 `VpnService` 建立黑洞隧道，读取后直接丢弃。
- 四个控制入口：主界面胶囊开关、可拖动悬浮窗、常驻通知栏按钮、音量键。
- 自绘胶囊开关组件，关闭态为白天跑道，开启态为深夜星空。
- 首次启动的 5 步配置向导。

[1.1]: https://github.com/laozhuo-360/scission/releases/tag/v1.1
[1.0]: https://github.com/laozhuo-360/scission/releases/tag/v1.0
