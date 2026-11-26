#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
角色权限矩阵验证（阶段 1.7）。

逐个角色登录，核对：
  ① 拿到的菜单集合是否与设计矩阵一致
  ② 登录后的落地页（首个可访问菜单）是否为该角色的合理首页
  ③ 越权访问是否被后端拦截（403）而非放行或 500

用法：  python tools/verify_permissions.py
输出：  docs/evidence/权限矩阵.md
"""

import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import api as A  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "docs", "evidence")

ROLES = [
    ("admin", "ADMIN", "管理员"),
    ("manager", "MANAGER", "仓管员"),
    ("operator", "OPERATOR", "普通操作员"),
    ("guest", "GUEST", "访客"),
    ("maintainer", "MAINTENANCE", "设备运维员"),
    ("dispatcher", "DISPATCHER", "调度员"),
]

# 期望的落地页（= 该角色菜单排序后首个可访问页）
EXPECT_LANDING = {
    "ADMIN": "/dashboard",
    "MANAGER": "/dashboard",
    "OPERATOR": "/inbound",
    "GUEST": "/dashboard",
    "MAINTENANCE": "/dashboard",
    "DISPATCHER": "/agv/map",
}

# 期望：菜单里必须包含的路径
MUST_HAVE = {
    "ADMIN": ["/dashboard", "/base/warehouse", "/system/user", "/agv/device", "/agv/map", "/agv/task", "/agv/queue"],
    "MANAGER": ["/dashboard", "/base/location", "/inventory", "/check", "/doc", "/agv/map"],
    "OPERATOR": ["/inbound", "/outbound", "/inventory"],
    "GUEST": ["/dashboard", "/agv/map", "/agv/monitor"],
    "MAINTENANCE": ["/dashboard", "/agv/device", "/agv/map", "/agv/monitor"],
    "DISPATCHER": ["/agv/map", "/agv/monitor", "/agv/task", "/agv/queue"],
}

# 期望：菜单里不能出现的路径
MUST_NOT_HAVE = {
    "ADMIN": [],
    "MANAGER": ["/system/user", "/system/role", "/agv/task", "/agv/queue", "/agv/device"],
    "OPERATOR": ["/dashboard", "/base/location", "/system/user", "/agv/task", "/agv/queue", "/agv/device"],
    "GUEST": ["/inbound", "/outbound", "/inventory", "/base/location", "/system/user",
              "/agv/device", "/agv/task", "/agv/queue"],
    "MAINTENANCE": ["/system/user", "/agv/task", "/agv/queue"],
    "DISPATCHER": ["/system/user", "/agv/device", "/base/location"],
}


def flatten_paths(menus, acc=None):
    if acc is None:
        acc = []
    for m in menus or []:
        if m.get("menuType") == "C" and m.get("path"):
            acc.append(m["path"])
        if m.get("children"):
            flatten_paths(m["children"], acc)
    return acc


def first_accessible(menus):
    for m in menus or []:
        if m.get("menuType") == "C" and m.get("path"):
            return m["path"]
        if m.get("children"):
            r = first_accessible(m["children"])
            if r:
                return r
    return "/profile"


def run():
    results = []
    tokens = {}
    lines = ["# 六角色登录矩阵（阶段 1.7）\n",
             "> 采集时间：%s　采集方式：tools/verify_permissions.py（后端 v1.1 代码）\n" % time.strftime("%Y-%m-%d %H:%M:%S"),
             "> 判据：菜单集合与设计矩阵一致；落地页为该角色首个可访问页；越权接口返回 403。\n"]

    # ---------- 1. 六角色登录与菜单 ----------
    lines.append("## 一、六角色登录与菜单矩阵\n")
    lines.append("| 角色 | 账号 | 登录 | 菜单数 | 落地页 | 期望落地页 | 菜单校验 |")
    lines.append("|---|---|---|---|---|---|---|")

    for username, code, name in ROLES:
        rc = A.do_login(username, "123456")
        if rc != 0:
            lines.append("| %s | %s | ❌ 失败 | - | - | - | - |" % (name, username))
            results.append((code, False, "登录失败"))
            continue
        tok = A.read_token()
        tokens[code] = tok
        info = A.request("GET", "/auth/userinfo", token=tok).get("data") or {}
        menus = info.get("menus") or []
        paths = flatten_paths(menus)
        landing = first_accessible(menus)
        expect = EXPECT_LANDING.get(code, "")
        must = [p for p in MUST_HAVE.get(code, []) if p not in paths]
        mustnot = [p for p in MUST_NOT_HAVE.get(code, []) if p in paths]
        ok = (not must) and (not mustnot) and landing == expect
        detail = "✅ 一致" if ok else "❌ 缺%s 多%s 落地%s" % (
            must or "无", mustnot or "无", landing)
        lines.append("| %s | %s | ✅ | %d | %s | %s | %s |" % (
            name, username, len(paths), landing, expect, detail))
        results.append((code, ok, "菜单=%s 落地=%s" % (paths, landing)))
        print("  %-12s 菜单%2d  落地=%-12s %s" % (code, len(paths), landing,
                                              "OK" if ok else "FAIL " + detail))

    # ---------- 2. 越权接口拦截 ----------
    lines.append("\n## 二、越权访问后端接口（应返回 403）\n")

    # (说明, 方法, 路径, body, 期望码, 期望消息关键词)
    checks = [
        ("访客 新增 AGV 设备", "PUT", "/agv/device/status/1/0", None, "GUEST", 403),
        ("访客 删除 AGV 设备", "DELETE", "/agv/device/1", None, "GUEST", 403),
        ("访客 创建搬运任务", "POST", "/agv/task/create", {"taskType": "移库搬运"}, "GUEST", 403),
        ("访客 注入 AGV 异常", "PUT", "/agv/fault/inject/1/fault", None, "GUEST", 403),
        ("操作员 新增 AGV 设备", "PUT", "/agv/device/status/1/0", None, "OPERATOR", 403),
        ("操作员 创建搬运任务", "POST", "/agv/task/create", {"taskType": "移库搬运"}, "OPERATOR", 403),
        ("调度员 新增 AGV 设备", "PUT", "/agv/device/status/1/0", None, "DISPATCHER", 403),
        ("调度员 注入 AGV 异常", "PUT", "/agv/fault/inject/1/fault", None, "DISPATCHER", 403),
        ("运维员 创建搬运任务", "POST", "/agv/task/create", {"taskType": "移库搬运"}, "MAINTENANCE", 403),
        ("仓管员 删除 AGV 设备", "DELETE", "/agv/device/1", None, "MANAGER", 403),
    ]
    allowed = [
        ("调度员 创建搬运任务", "POST", "/agv/task/create", {"taskType": "移库搬运"}, "DISPATCHER", 200),
        ("运维员 启停 AGV 设备", "PUT", "/agv/device/status/1/0", None, "MAINTENANCE", 200),
        ("管理员 启停 AGV 设备", "PUT", "/agv/device/status/1/0", None, "ADMIN", 200),
        ("访客 查看仓库地图数据", "GET", "/base/location/all", None, "GUEST", 200),
        ("访客 查看 AGV 列表", "GET", "/agv/device/list", None, "GUEST", 200),
    ]

    lines.append("### 应当被拒绝的越权操作\n")
    lines.append("| 操作 | 角色 | 实际返回码 | 期望 | 结果 | 后端消息 |")
    lines.append("|---|---|---|---|---|---|")
    for desc, method, path, body, role, expect in checks:
        tok = tokens.get(role, "")
        res = A.request(method, path, body, token=tok)
        code = res.get("code")
        msg = res.get("msg") or ""
        ok = code == expect
        lines.append("| %s | %s | %s | %s | %s | %s |" % (
            desc, role, code, expect, "✅" if ok else "❌", msg))
        print("  拒绝 %-24s %-12s -> %s %s" % (desc, role, code, "OK" if ok else "FAIL"))

    lines.append("\n### 应当被允许的正常操作\n")
    lines.append("| 操作 | 角色 | 实际返回码 | 期望 | 结果 |")
    lines.append("|---|---|---|---|---|")
    for desc, method, path, body, role, expect in allowed:
        tok = tokens.get(role, "")
        res = A.request(method, path, body, token=tok)
        code = res.get("code")
        ok = code == expect
        lines.append("| %s | %s | %s | %s | %s |" % (desc, role, code, expect, "✅" if ok else "❌"))
        print("  允许 %-24s %-12s -> %s %s" % (desc, role, code, "OK" if ok else "FAIL"))

    os.makedirs(OUT_DIR, exist_ok=True)
    path = os.path.join(OUT_DIR, "权限矩阵.md")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print("\n权限矩阵已写入：", path)


if __name__ == "__main__":
    run()
