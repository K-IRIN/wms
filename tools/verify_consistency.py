#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
阶段 7.2 验收：初稿口径一致性检查。

把论文/文档里必须口径一致的五组数字，逐条对着**运行中的系统**和**仓库里的文档**核：
  ① 25 张表
  ② 六项关键技术
  ③ 四类角色（A3 矩阵）+ 系统实际 6 个角色
  ④ 8 态状态机（转换表逐条比对）
  ⑤ 四类异常（故障 / 通道堵塞 / 取货失败 / 电量不足）

另外扫描文档里的过期口径（如"22 张表""八项关键技术"等），列成待改清单。

用法：  python tools/verify_consistency.py
输出：  docs/evidence/阶段7-一致性检查.md
"""

import io
import json
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import api as A  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_MD = os.path.join(ROOT, "docs", "evidence", "阶段7-一致性检查.md")
DOCS = os.path.join(ROOT, "docs")
MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"

checks = []      # (分组, 检查项, 结论, 说明)


def add(group, name, ok, detail):
    checks.append((group, name, ok, detail))
    print("  %s %-22s %-46s %s" % ("PASS" if ok else "FAIL", group, name, detail))


def sql(stmt):
    args = [MYSQL, "-uroot", "-p123456", "--default-character-set=utf8mb4", "wms", "-e", stmt]
    out = subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace")
    return out.stdout


import subprocess  # noqa: E402


def get(path):
    return A.request("GET", path).get("data")


# ==================== ① 25 张表 ====================
def check_tables():
    rows = sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='wms'")
    n_db = int(rows.strip().splitlines()[-1]) if rows.strip() else 0
    add("25 张表", "数据库实际表数", n_db == 25, "wms 库 %d 张表" % n_db)

    doc = io.open(os.path.join(DOCS, "数据库设计.md"), encoding="utf-8").read()
    m = re.search(r"共\s*(\d+)\s*张表", doc)
    n_doc = int(m.group(1)) if m else -1
    add("25 张表", "《数据库设计》声明", n_doc == 25, "文档写「共 %d 张表」" % n_doc)

    sections = len(re.findall(r"^## \w+", doc, re.M))
    add("25 张表", "《数据库设计》章节数", sections == 25, "文档实际 %d 个表小节" % sections)

    readme = io.open(os.path.join(ROOT, "README.md"), encoding="utf-8").read()
    ok = "25 张表" in readme and "22 张表" not in readme
    add("25 张表", "README 口径", ok, "README 写「25 张表」且无「22 张表」残留")

    # 库里没有多出来的临时表
    extra = sql("SELECT table_name FROM information_schema.tables "
                "WHERE table_schema='wms' AND table_name NOT LIKE 'sys_%' AND table_name NOT LIKE 'wms_%'")
    add("25 张表", "无游离表", not extra.strip(), "非 sys_/wms_ 前缀的表：%s" % (extra.strip() or "无"))


# ==================== ② 六项关键技术 ====================
def check_technologies():
    """对照计划文档附 A2 的六项关键技术，逐条验证实现是否在位"""
    # 1 WebSocket 实时推送
    handler = io.open(os.path.join(ROOT, "src/main/java/com/wms/websocket/AgvWebSocketHandler.java"),
                      encoding="utf-8").read()
    has_ws = os.path.exists(os.path.join(ROOT, "src/main/java/com/wms/websocket/AgvWebSocketHandler.java"))
    types = all(t in io.open(os.path.join(ROOT, "wms-web/src/store/agv.js"), encoding="utf-8").read()
                 for t in ("'agv'", "'task'", "'alert'", "'waiting'"))
    add("六项关键技术", "① WebSocket 实时推送", has_ws and types,
        "服务端 %s；前端订阅 agv/task/alert/waiting 四类=%s" % ("在位" if has_ws else "缺失", types))

    # 2 调度与分配（优先级 desc + id asc）
    engine = io.open(os.path.join(ROOT, "src/main/java/com/wms/service/AgvEngineService.java"),
                      encoding="utf-8").read()
    ordered = "orderByDesc(WmsAgvTask::getPriority)" in engine and \
              "orderByAsc(WmsAgvTask::getId)" in engine
    add("六项关键技术", "② 调度与分配", ordered,
        "待分配队列按 priority desc, id asc 排序=%s" % ordered)

    # 3 状态机与生命周期
    tbl = get("/agv/task/transitions") or []
    names = {t["fromName"] for t in tbl}
    add("六项关键技术", "③ 状态机与生命周期", len(tbl) == 8 and len(names) == 8,
        "转换表返回 %d 个状态：%s" % (len(tbl), "、".join(sorted(names))))

    # 4 路径与电量模拟
    cols = sql("SELECT column_name FROM information_schema.columns "
               "WHERE table_schema='wms' AND table_name='wms_agv_task' "
               "AND column_name IN ('empty_distance','loaded_distance')")
    has_dist = "empty_distance" in cols and "loaded_distance" in cols
    has_occ = "cellOwner" in engine and "PUSH_MAX_DEPTH" in engine
    add("六项关键技术", "④ 路径与电量模拟", has_dist and has_occ,
        "里程分段列=%s；占用表与推挤让行=%s" % (has_dist, has_occ))

    # 5 异常注入与重分配
    fault = io.open(os.path.join(ROOT, "src/main/java/com/wms/controller/AgvFaultController.java"),
                    encoding="utf-8").read()
    four = all(t in fault for t in ("fault", "lowBattery", "block", "pickFail"))
    low_recycle = "injectLowBattery" in engine and "退回待分配" in engine
    add("六项关键技术", "⑤ 异常注入与重分配", four and low_recycle,
        "四类注入入口齐全=%s；低电也会回收任务=%s" % (four, low_recycle))

    # 6 日志与统计
    stat = get("/agv/task/stat") or {}
    need = ["utilization", "completionRate", "faultRate", "conflictCount", "chargeCount"]
    missing = [k for k in need if k not in stat]
    add("六项关键技术", "⑥ 日志与统计", not missing,
        "统计接口五项指标齐全，缺 %s" % (missing or "无"))


# ==================== ③ 四类角色 ====================
EXPECT_ROLE_MATRIX = {
    "ADMIN": "全部菜单、用户与权限、基础资料",
    "DISPATCHER": "任务管理、待分配队列、地图、监控",
    "MAINTENANCE": "设备档案、地图、监控、异常注入",
    "GUEST": "数据看板、仓库地图、运行监控（只读）",
}


def check_roles():
    rows = sql("SELECT r.role_code, COUNT(rm.menu_id) c FROM sys_role r "
               "LEFT JOIN sys_role_menu rm ON rm.role_id=r.id GROUP BY r.role_code ORDER BY r.id")
    table = {}
    for line in rows.strip().splitlines()[1:]:
        code, cnt = line.split("\t")
        table[code] = int(cnt)
    add("四类角色", "系统角色数", len(table) == 6, "实际 %d 个角色：%s" % (len(table), "、".join(table)))
    for code, expect in EXPECT_ROLE_MATRIX.items():
        ok = code in table and table[code] > 0
        add("四类角色", "%s 菜单授权" % code, ok,
            "%s → %s（菜单 %d 项）" % (code, expect, table.get(code, 0)))

    # A3 的四类角色在库里都能登录
    for username in ["admin", "dispatcher", "maintainer", "guest"]:
        rc = A.do_login(username, "123456")
        info = get("/auth/userinfo") or {}
        add("四类角色", "%s 可登录" % username, rc == 0 and bool(info.get("menus")),
            "角色=%s，菜单 %d 项" % (info.get("roles"), len(info.get("menus") or [])))


# ==================== ④ 8 态状态机 ====================
EXPECT_TRANSITIONS = {
    0: {1, 7}, 1: {0, 2, 7}, 2: {0, 3, 7}, 3: {0, 4, 7},
    4: {0, 5, 7}, 5: {0, 6, 7}, 6: set(), 7: set(),
}


def check_state_machine():
    tbl = get("/agv/task/transitions") or []
    actual = {t["from"]: {a["status"] for a in t["allowed"]} for t in tbl}
    ok_all = True
    for frm, exp in EXPECT_TRANSITIONS.items():
        got = actual.get(frm, set())
        same = got == exp
        ok_all = ok_all and same
        add("8 态状态机", "状态 %d 的合法流转" % frm, same,
            "允许 %s（期望 %s）" % (sorted(got) or "无", sorted(exp) or "无"))
    # 非法流转必须被拒（该接口只允许管理员，所以先切回 admin）
    A.do_login("admin", "123456")
    task_rows = get("/agv/task/page?pageNum=1&pageSize=50") or {}
    recs = task_rows.get("records") or []
    allowed_by_state = {t["from"]: {a["status"] for a in t["allowed"]} for t in (tbl or [])}
    tid, tstatus, illegal = None, None, None
    for r in recs:
        s = r.get("status")
        allowed = allowed_by_state.get(s, set())
        for target in range(0, 8):
            if target not in allowed:
                tid, tstatus, illegal = r.get("id"), s, target
                break
        if tid:
            break
    if tid is None:
        add("8 态状态机", "非法流转被拒", False, "库里没有可用于测试的任务")
    else:
        res = A.request("PUT", "/agv/task/transition/%s/%s" % (tid, illegal))
        rejected = res.get("code") == 500 and "非法状态流转" in (res.get("msg") or "")
        add("8 态状态机", "非法流转被拒", rejected,
            "任务 %s（状态 %s）→ %s：%s %s" % (tid, tstatus, illegal, res.get("code"), res.get("msg")))


# ==================== ⑤ 四类异常 ====================
def check_faults():
    A.do_login("admin", "123456")
    fault = io.open(os.path.join(ROOT, "src/main/java/com/wms/controller/AgvFaultController.java"),
                    encoding="utf-8").read()
    four = {"fault": "故障停机", "lowBattery": "电量不足",
            "block": "通道堵塞", "pickFail": "取货失败"}
    for t, name in four.items():
        add("四类异常", "%s（%s）" % (t, name), ('"%s"' % t) in fault,
            "注入入口在位" if ('"%s"' % t) in fault else "注入入口缺失")

    # 实打一次故障 + 一次低电，确认这两类事件真的会落库
    # （不能只看代码里有没有这个分支——日志表可能被别的测试清过，检查要自给自足）
    devs = get("/agv/device/list") or []
    idle = next((d for d in devs if d.get("status") == 0), None)
    if idle:
        A.request("PUT", "/agv/fault/inject/%s/fault" % idle.get("id"))
        time.sleep(1.5)
        A.request("PUT", "/agv/fault/repair/%s" % idle.get("id"))
        A.request("PUT", "/agv/fault/inject/%s/lowBattery" % idle.get("id"))
        time.sleep(1.5)

    rows = sql("SELECT log_type, COUNT(*) c FROM wms_agv_log GROUP BY log_type")
    types = {}
    for line in rows.strip().splitlines()[1:]:
        t, c = line.split("\t")
        types[t] = int(c)
    for t in ("故障", "告警", "冲突"):
        add("四类异常", "%s事件有日志" % t, types.get(t, 0) > 0,
            "wms_agv_log 记录 %d 条" % types.get(t, 0))


# ==================== 文档过期口径扫描 ====================
STALE_PATTERNS = [
    (r"22\s*张表", "表数量写成 22（应为 25）"),
    (r"八项关键技术", "关键技术写成八项（应为六项）"),
    (r"8\s*项关键技术", "关键技术写成 8 项（应为六项）"),
    (r"AGV\s*用例\s*0\s*条", "仍声称没有 AGV 用例"),
    (r"共\s*34\s*条", "用例总数写成 34（应为 52）"),
]


def check_stale_docs():
    targets = []
    for base, _dirs, files in os.walk(DOCS):
        # evidence 目录里放的是验收报告，报告本身会引用这些旧口径字样，不算残留
        if os.path.basename(base) == "evidence":
            continue
        for f in files:
            if f.endswith(".md"):
                targets.append(os.path.join(base, f))
    targets.append(os.path.join(ROOT, "README.md"))
    hits = []
    for path in targets:
        text = io.open(path, encoding="utf-8", errors="ignore").read()
        rel = os.path.relpath(path, ROOT).replace("\\", "/")
        # 「附 B 需要修正的表述」核对表里会**故意引用**旧口径（如"八项关键技术""AGV 用例 0 条"），
        # 那是用来对照纠正的，不是残留；整段跳过。
        cut = text.find("附 B")
        if cut >= 0:
            text = text[:cut]
        for pat, desc in STALE_PATTERNS:
            for m in re.finditer(pat, text):
                line = text[:m.start()].count("\n") + 1
                hits.append("%s:%d  %s（命中「%s」）" % (rel, line, desc, m.group(0)))
    add("文档口径", "无过期口径残留", not hits,
        "共 %d 处待改：%s" % (len(hits), "；".join(hits[:5]) if hits else "无"))
    return hits


def main():
    if A.do_login("admin", "123456") != 0:
        print("登录失败")
        return 1
    print("① 25 张表")
    check_tables()
    print("② 六项关键技术")
    check_technologies()
    print("③ 四类角色")
    check_roles()
    print("④ 8 态状态机")
    check_state_machine()
    print("⑤ 四类异常")
    check_faults()
    print("⑥ 文档口径扫描")
    hits = check_stale_docs()

    passed = sum(1 for c in checks if c[2])
    total = len(checks)

    md = ["# 阶段 7.2 初稿一致性检查\n",
          "> 检查时间：%s　执行方式：tools/verify_consistency.py（对运行中的系统与仓库文档同时核）\n" % __import__("time").strftime("%Y-%m-%d %H:%M:%S"),
          "> 检查范围：25 张表 / 六项关键技术 / 四类角色 / 8 态状态机 / 四类异常 / 文档过期口径\n"]
    group = None
    for g, name, ok, detail in checks:
        if g != group:
            md.append("\n## %s\n" % g)
            md.append("| 检查项 | 结论 | 说明 |")
            md.append("|---|---|---|")
            group = g
        md.append("| %s | %s | %s |" % (name, "✅" if ok else "❌", detail))
    md.append("")
    md.append("**合计 %d/%d 项一致**\n" % (passed, total))
    if hits:
        md.append("## 待改清单（文档过期口径）\n")
        for h in hits:
            md.append("- %s" % h)
        md.append("")

    os.makedirs(os.path.dirname(OUT_MD), exist_ok=True)
    with io.open(OUT_MD, "w", encoding="utf-8") as f:
        f.write("\n".join(md))
    print("\n合计 %d/%d 项一致，证据写入：%s" % (passed, total, OUT_MD))
    return 0 if passed == total else 2


if __name__ == "__main__":
    sys.exit(main())
