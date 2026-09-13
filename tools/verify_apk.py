# -*- coding: utf-8 -*-
"""APK 资源字节校验：确认应用名、包名、图标等关键资源正确打入 APK。

用法：
    python tools/verify_apk.py [apk路径]
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
for name in ["断流", "断流 Scission", "Scission"]:
    lines.append("%-16s in arsc: %s" % (name, name.encode("utf-8") in arsc))
lines.append("com.linye.netblock in manifest: %s"
             % ("com.linye.netblock".encode("utf-16-le") in mf))
names = z.namelist()
lines.append("ic_launcher_fg packed: %s"
             % any("ic_launcher_fg" in n for n in names))
lines.append("ic_launcher_bg packed: %s"
             % any("ic_launcher_bg" in n for n in names))

print("\n".join(lines))
