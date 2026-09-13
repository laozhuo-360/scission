# -*- coding: utf-8 -*-
"""修复项回归校验：确认已修复的问题不会在后续改动中复发。

校验内容：
- 状态栏磁贴（已废弃功能）未回流进 Manifest
- VPN 授权引导文案已打入 APK
- 通知栏控制、无障碍失效提醒等文案已打入 APK
- VPN / 悬浮窗前台服务类型已声明

用法：
    python tools/verify_fixes.py [apk路径]
不指定路径时默认校验 app/build/outputs/apk/debug/app-debug.apk。
"""
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_APK = os.path.join(ROOT, "app", "build", "outputs", "apk", "debug", "app-debug.apk")

apk = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_APK
if not os.path.exists(apk):
    print("APK not found: %s" % apk)
    print("请先执行 gradle assembleDebug")
    sys.exit(1)

z = zipfile.ZipFile(apk)
arsc = z.read("resources.arsc")
mf = z.read("AndroidManifest.xml")

lines = []
# 磁贴组件必须保持移除状态
lines.append("BlockTileService gone: %s"
             % ("BlockTileService".encode("utf-16-le") not in mf))
lines.append("QS_TILE action gone: %s"
             % ("android.service.quicksettings.action.QS_TILE".encode("utf-16-le") not in mf))
# VPN 授权引导文案必须进包
for s in ["VPN 授权", "需要授权 VPN 连接", "未授权 VPN 连接，已取消断网"]:
    lines.append("%-14s in arsc: %s" % (s, s.encode("utf-8") in arsc))
# 通知栏控制文案
lines.append("通知栏控制 in arsc: %s" % ("通知栏控制".encode("utf-8") in arsc))
# 无障碍失效提醒文案
lines.append("toast_acc_killed in arsc: %s" % ("音量键服务被系统关闭".encode("utf-8") in arsc))
# VPN 前台服务类型
lines.append("specialUse FGS type in manifest: %s"
             % ("foregroundServiceType".encode("utf-16-le") in mf))

print("\n".join(lines))
