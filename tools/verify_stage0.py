#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
阶段 0 验收：环境与基线。

覆盖计划条目 0.1–0.3：
  · JDK21 构建通过、后端 API 可访问、前端首页可访问、admin 登录成功、数据库已备份
  · git 基线提交与 .gitignore
  · 改进前缺陷存证（3 组）

说明：计划原要求 3 段 30 秒录屏，本机无录屏工具，改为可复现的脚本存证
（tools/evidence.py before 产出的 docs/evidence/改进前对照.md），论文中按「数据对照」引用。

用法：  python tools/verify_stage0.py
输出：  docs/evidence/阶段0-环境基线.md
"""

import os
import re
import sys
import time
import zipfile
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import api as A  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "docs", "evidence", "阶段0-环境基线.md")
checks = []


def check(name, ok, detail=""):
    checks.append((name, bool(ok), detail))
    print(("  [PASS] " if ok else "  [FAIL] ") + name + (" — " + detail if detail else ""))
    return ok


def http_status(url):
    try:
        with urllib.request.urlopen(url, timeout=5) as r:
            return r.status
    except Exception as e:  # noqa: BLE001
        return str(e)


def main():
    t0 = time.monotonic()
    print("阶段 0 验收开始")

    jar = os.path.join(ROOT, "target", "wms-server.jar")
    has_jar = os.path.exists(jar)
    has_aspect = False
    if has_jar:
        with zipfile.ZipFile(jar) as z:
            names = z.namelist()
        has_aspect = any("com/wms/aspect/RequireRoleAspect.class" in n for n in names)
    check("mvn package 产物存在且含权限切面", has_jar and has_aspect,
          "%s，%.1f MB" % (os.path.basename(jar), os.path.getsize(jar) / 1048576.0 if has_jar else 0))

    login_ok = A.do_login("admin", "123456") == 0
    check("后端 8080 可访问且 admin/123456 登录成功", login_ok,
          "token 已写入 .tmp/token" if login_ok else "登录失败，检查 logs/backend.out.log")

    status = http_status("http://localhost:5173")
    check("前端首页可访问（Vite dev 5173）", status == 200, "HTTP %s" % status)

    backup_dir = os.path.join(ROOT, "tools", "db-backup")
    sqls = [f for f in os.listdir(backup_dir) if f.endswith(".sql")] if os.path.isdir(backup_dir) else []
    big = [f for f in sqls if os.path.getsize(os.path.join(backup_dir, f)) > 1024]
    check("数据库现状已备份（结构/数据/全量）", len(sqls) >= 3 and len(big) >= 3,
          "%d 个 SQL，最大 %.1f KB" % (len(sqls), max([os.path.getsize(os.path.join(backup_dir, f))
                                                    for f in sqls] or [0]) / 1024.0))

    gi = os.path.join(ROOT, ".gitignore")
    gtext = open(gi, encoding="utf-8").read() if os.path.exists(gi) else ""
    need = ["target/", "node_modules/", ".idea/", "dist/"]
    missing = [n for n in need if n not in gtext]
    check(".gitignore 覆盖构建产物与 IDE 目录", not missing, "缺失：%s" % (missing or "无"))

    git_dir = os.path.join(ROOT, ".git")
    check("已建立 Git 仓库", os.path.isdir(git_dir), ".git 存在")

    # 提交数用 git 命令取（不依赖网络）
    import subprocess
    log = subprocess.run(["git", "log", "--oneline"], cwd=ROOT, capture_output=True,
                         text=True, encoding="utf-8", errors="replace")
    commits = [l for l in log.stdout.splitlines() if l.strip()]
    check("存在基线提交且持续提交（≥2 个）", log.returncode == 0 and len(commits) >= 2,
          "最近提交：%s" % (commits[0] if commits else "无"))

    before = os.path.join(ROOT, "docs", "evidence", "改进前对照.md")
    btext = open(before, encoding="utf-8").read() if os.path.exists(before) else ""
    sections = len(re.findall(r"##\s*实验[①②③]", btext))
    check("改进前缺陷存证齐全（3 组：地图/任务/低电）", sections >= 3,
          "已存证 %d 组（本机无录屏，改用脚本数据对照，可现场复跑）" % sections)

    passed = sum(1 for _, ok, _ in checks if ok)
    lines = ["# 阶段 0 验收：环境与基线\n",
             "> 采集时间：%s　方式：tools/verify_stage0.py\n" % time.strftime("%Y-%m-%d %H:%M:%S"),
             "**%d/%d 通过，耗时 %.1f 秒**\n" % (passed, len(checks), time.monotonic() - t0),
             "| # | 检查项 | 结果 | 说明 |", "|---|---|---|---|"]
    for i, (name, ok, detail) in enumerate(checks, 1):
        lines.append("| %d | %s | %s | %s |" % (i, name, "✅" if ok else "❌", detail))
    lines += ["", "> 录屏替代说明：计划 0.3 原要求 3 段 30 秒视频；本机无录屏工具，改用", 
              "> `python tools/evidence.py before` 对 v1.0 基线采集的数值对照（库位 4→26、", 
              "> 电量 0 变化、低电不回收），证据可复跑、可核对，答辩时按「改进前数据」引用。"]
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print("\n%d/%d 通过，证据写入 %s" % (passed, len(checks), OUT))
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    sys.exit(main())
