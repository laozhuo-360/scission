# 更新日志

本文件记录断流 Scission 的所有重要变更。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

## [1.1] - 2026-09-26

### 修复

- 无 Root 模式断网后无法恢复。原实现有三处叠加缺陷：
  - 黑洞读取循环用阻塞式 `read()`，隧道关闭时线程会永久卡住，
    服务无法完全销毁，后续启动被半死的实例拦住。
    改用 `Os.poll()` 每 100ms 轮询，`running` 置位后循环必然退出。
  - 服务声明为 `START_STICKY`，被系统回收后会拿到一个 null intent，
    原代码把它当作启动指令，导致刚恢复的网络又被自动断开。
    改为 `START_NOT_STICKY`，并且 null intent 直接自杀。
  - 关闭动作走 `stopService()` 被动等系统回收，回收时序不确定时
    清理逻辑可能不被执行。改为主动发送停止指令，由服务自己走完关闭流程。
- 连续快速触发开关时，第二次请求可能被防抖逻辑吞掉，界面无响应。
  状态比对环节加了同步保护。

### 变更

- 补充 Gradle Wrapper，现在克隆后可以直接用 `./gradlew` 构建，不再依赖本机预装 Gradle。

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
