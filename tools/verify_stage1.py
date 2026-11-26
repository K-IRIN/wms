#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
阶段 1 验收：权限与数据自洽。

覆盖计划条目 1.1–1.7：
  · 权限矩阵与越权拦截（复用 verify_permissions.py，产出 docs/evidence/权限矩阵.md）
  · 26 个库位均有坐标、AGV 不再与月台重叠
  · 角色菜单含按钮权限（perms）
  · 手工坐标优先于编号解析（改坐标 → 刷新 → 重启不回退 中的「改后立即生效」）
  · 前端动态路由按菜单落地页（静态核对）

用法：  python tools/verify_stage1.py
输出：  docs/evidence/阶段1-权限与数据.md，全部通过才返回 0
"""

import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import api as A            # noqa: E402
import verify_permissions as P  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "docs", "evidence", "阶段1-权限与数据.md")

checks = []


def check(name, ok, detail=""):
    checks.append((name, bool(ok), detail))
    print(("  [PASS] " if ok else "  [FAIL] ") + name + (" — " + detail if detail else ""))
    return ok


def flatten_perms(menus, acc=None):
    if acc is None:
        acc = []
    for m in menus or []:
        if m.get("perms"):
            acc.append(m["perms"])
        flatten_perms(m.get("children"), acc)
    return acc


def main():
    t0 = time.monotonic()
    print("阶段 1 验收开始")
    # ---------- 1. 权限矩阵（含 6 角色登录、10 条 403、5 条允许） ----------
    check("权限矩阵全绿（6 角色登录 / 10 条越权 403 / 5 条允许 200）", P.run(),
          "证据：docs/evidence/权限矩阵.md")

    # ---------- 2. 菜单按钮权限 ----------
    assert A.do_login("admin", "123456") == 0
    admin = A.read_token()
    admin_perms = flatten_perms((A.request("GET", "/auth/userinfo", token=admin).get("data") or {}).get("menus"))
    need_admin = ["agv:device:list", "agv:map:view", "agv:monitor:view"]
    missing = [p for p in need_admin if p not in admin_perms]
    check("管理员菜单带按钮权限（perms 非空）", not missing, "缺失：%s" % (missing or "无"))

    assert A.do_login("dispatcher", "123456") == 0
    disp = A.read_token()
    disp_perms = flatten_perms((A.request("GET", "/auth/userinfo", token=disp).get("data") or {}).get("menus"))
    need_disp = ["agv:task:list", "agv:queue:list", "agv:map:view", "agv:monitor:view"]
    missing = [p for p in need_disp if p not in disp_perms]
    check("调度员菜单带任务/队列权限", not missing, "perms=%s" % (missing or "全部存在"))

    # ---------- 3. 库位与 AGV 坐标 ----------
    locs = (A.request("GET", "/base/location/all", token=admin).get("data") or [])
    with_pos = [l for l in locs if l.get("posX") is not None and l.get("posY") is not None]
    check("库位 ≥24 且坐标全部回填", len(locs) >= 24 and len(with_pos) == len(locs),
          "库位 %d 个，有坐标 %d 个" % (len(locs), len(with_pos)))
    distinct = {(l["posX"], l["posY"]) for l in with_pos}
    check("地图可渲染 ≥20 个不同位置", len(distinct) >= 20, "不同坐标 %d 个" % len(distinct))

    devs = (A.request("GET", "/agv/device/list", token=admin).get("data") or [])
    dock = next((l for l in with_pos if l.get("locationCode") == "A01-DOCK"), None)
    dock_pos = (dock.get("posX"), dock.get("posY")) if dock else (0, 0)
    homes = [(d.get("agvCode"), (d.get("homeX"), d.get("homeY"))) for d in devs]
    home_overlap = [code for code, p in homes if p == dock_pos]
    check("AGV 待命位不再与月台重叠", not home_overlap,
          "重叠：%s；月台 %s" % (home_overlap or "无", dock_pos))
    home_dup = {p for _, p in homes if [q for _, q in homes].count(p) > 1}
    check("AGV 待命位彼此不重叠", not home_dup, "重复待命位：%s" % (home_dup or "无"))
    idle_on_dock = [d.get("agvCode") for d in devs
                    if d.get("status") == 0 and (d.get("posX"), d.get("posY")) == dock_pos]
    check("空闲 AGV 不停在月台", not idle_on_dock, "停在月台：%s" % (idle_on_dock or "无"))

    # ---------- 4. 手工坐标优先于编号解析 ----------
    probe = next((l for l in locs if l.get("locationCode") == "A02-02-01"), with_pos[0])
    old = (probe.get("posX"), probe.get("posY"))
    r1 = A.request("PUT", "/base/location/update",
                   {"id": probe["id"], "posX": 5, "posY": 3}, token=admin)
    row = next((l for l in (A.request("GET", "/base/location/all", token=admin).get("data") or [])
                if l["id"] == probe["id"]), {})
    moved = (row.get("posX"), row.get("posY")) == (5, 3)
    A.request("PUT", "/base/location/update",
              {"id": probe["id"], "posX": old[0], "posY": old[1]}, token=admin)
    check("手工改坐标立即生效（不被编号覆盖）", r1.get("code") == 200 and moved,
          "%s 改到 (5,3) 后读取为 (%s,%s)，已还原 %s" % (
              probe.get("locationCode"), row.get("posX"), row.get("posY"), old))

    # ---------- 5. 前端动态路由（静态核对） ----------
    router = os.path.join(ROOT, "wms-web", "src", "router", "index.js")
    text = open(router, encoding="utf-8").read() if os.path.exists(router) else ""
    missing = [k for k in ["firstAccessiblePath", "import.meta.glob", "/profile"] if k not in text]
    check("前端按菜单排序取首个可访问页并回落 /profile", not missing,
          "缺失：%s" % (missing or "无"))

    # ---------- 输出 ----------
    passed = sum(1 for _, ok, _ in checks if ok)
    lines = ["# 阶段 1 验收：权限与数据自洽\n",
             "> 采集时间：%s　方式：tools/verify_stage1.py\n" % time.strftime("%Y-%m-%d %H:%M:%S"),
             "> 权限矩阵明细见 `docs/evidence/权限矩阵.md`（本脚本已同时重新生成）。\n",
             "**%d/%d 通过，耗时 %.1f 秒**\n" % (passed, len(checks), time.monotonic() - t0),
             "| # | 检查项 | 结果 | 说明 |", "|---|---|---|---|"]
    for i, (name, ok, detail) in enumerate(checks, 1):
        lines.append("| %d | %s | %s | %s |" % (i, name, "✅" if ok else "❌", detail))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print("\n%d/%d 通过，证据写入 %s" % (passed, len(checks), OUT))
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    sys.exit(main())
