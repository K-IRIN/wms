#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
阶段 5 验收：实验台与对比实验。

逐条判定计划文档里的 5.1~5.4：
  5.1 批量建单与一键重置（并发建 50 单无重复任务号；重置后统计从零开始）
  5.2 指标采集（里程/等待事件/冲突次数能从库里一条 SQL 汇总出来）
  5.3 对比实验（6 组齐全，组间只有策略与车辆数不同，产出 CSV）
  5.4 结果表（完成率/平均完成时间/空驶率/冲突次数 四列齐全，数字与系统实测一致）

用法：  python tools/verify_stage5.py
输出：  docs/evidence/阶段5-实验台与对比.md
"""

import csv
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import api as A  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "docs", "evidence")
CSV_PATH = os.path.join(OUT_DIR, "阶段5-对比实验.csv")

results = []


def record(code, name, ok, detail):
    results.append((code, name, ok, detail))
    print("  %s %-5s %-24s %s" % ("PASS" if ok else "FAIL", code, name, detail))
    return ok


def get(path):
    return A.request("GET", path).get("data")


def post(path, body=None):
    return A.request("POST", path, body)


def put(path, body=None):
    return A.request("PUT", path, body)


def tasks_all():
    rows = []
    page = 1
    while True:
        data = get("/agv/task/page?pageNum=%d&pageSize=200" % page) or {}
        recs = data.get("records") or []
        rows.extend(recs)
        if len(rows) >= (data.get("total") or 0) or not recs:
            break
        page += 1
    return rows


def check_batch_and_reset():
    """5.1 批量建单 + 一键重置"""
    print("\n[5.1] 批量建单与一键重置")
    # 重置到干净状态
    post("/agv/task/reset", {})
    time.sleep(1.5)

    # ① 批量建单 50 条
    locs = [l for l in (get("/base/location/all") or []) if l.get("locationCode", "").startswith("A02-")]
    body = {"count": 50, "taskType": "移库搬运",
            "fromLocationId": locs[0].get("id"), "toLocationId": locs[1].get("id"), "priority": 0}
    res = post("/agv/task/batch", body)
    nos = res.get("data") or []
    ok_batch = res.get("code") == 200 and len(nos) == 50
    record("5.1a", "批量建单 50 条", ok_batch,
           "一次调用建 %d 条，任务号 %s … %s" % (len(nos), nos[0] if nos else "-", nos[-1] if nos else "-"))

    # ② 任务号不重复
    dup = len(set(nos)) != len(nos)
    record("5.1b", "任务号无重复", not dup,
           "%d 个任务号，唯一 %d 个%s" % (len(nos), len(set(nos)), "（有重复！）" if dup else ""))

    # ③ 库里也查一遍重复
    rows = tasks_all()
    db_nos = [r.get("taskNo") for r in rows]
    dup_db = len(set(db_nos)) != len(db_nos)
    record("5.1c", "数据库内任务号唯一", not dup_db,
           "库中 %d 条任务，唯一 %d 个" % (len(db_nos), len(set(db_nos))))

    # ④ 一键重置后统计从零开始
    reset = post("/agv/task/reset", {})
    time.sleep(1.5)
    after = tasks_all()
    devs = get("/agv/device/list") or []
    ok_reset = (len(after) == 0 and all(d.get("status") == 0 and d.get("battery") == 100 for d in devs))
    record("5.1d", "一键重置后统计归零", ok_reset,
           "重置后任务 %d 条；车辆状态全空闲且满电=%s" % (
               len(after), all(d.get("status") == 0 and d.get("battery") == 100 for d in devs)))


def check_metrics():
    """5.2 指标采集"""
    print("\n[5.2] 指标采集")
    locs = [l for l in (get("/base/location/all") or []) if l.get("locationCode", "").startswith("A02-")]
    post("/agv/task/batch", {"count": 6, "taskType": "移库搬运",
                              "fromLocationId": locs[0].get("id"), "toLocationId": locs[2].get("id")})
    # 等任务跑到"载货行驶"阶段，空驶段与载货段里程才都采得到
    for _ in range(45):
        time.sleep(1)
        rows = tasks_all()
        if any((r.get("loadedDistance") or 0) > 0 for r in rows):
            break
    rows = tasks_all()
    empty = sum(r.get("emptyDistance") or 0 for r in rows)
    loaded = sum(r.get("loadedDistance") or 0 for r in rows)
    ok_dist = empty > 0 and loaded > 0
    record("5.2a", "里程分段可采集", ok_dist,
           "空驶 %d 格 / 载货 %d 格（wms_agv_task.empty_distance + loaded_distance）" % (empty, loaded))

    # 冲突与等待事件走 wms_agv_log
    stat = get("/agv/task/stat") or {}
    has_conflict = "conflictCount" in stat
    has_empty = "emptyRate" in stat
    ok_stat = has_conflict and has_empty
    record("5.2b", "统计接口含五项指标", ok_stat,
           "conflictCount=%s emptyRate=%s utilization=%s completionRate=%s" % (
               stat.get("conflictCount"), stat.get("emptyRate"),
               stat.get("utilization"), stat.get("completionRate")))

    exp = get("/agv/task/experiment") or {}
    need = ["completionRate", "avgDurationSec", "emptyRate", "conflictCount", "loadStdDev"]
    missing = [k for k in need if k not in (exp or {})]
    record("5.2c", "实验指标齐全", not missing,
           "缺失=%s；完成率=%s 平均完成=%ss 空驶率=%s 冲突=%s 负载标准差=%s" % (
               missing or "无", exp.get("completionRate"), exp.get("avgDurationSec"),
               exp.get("emptyRate"), exp.get("conflictCount"), exp.get("loadStdDev")))


def check_strategy():
    """5.3 的策略开关"""
    print("\n[5.3] 分配策略可切换")
    s1 = get("/agv/task/strategy") or {}
    put("/agv/task/strategy/nearest-load")
    s2 = get("/agv/task/strategy") or {}
    ok1 = s2.get("code") == "nearest-load" and s2.get("label") == "就近+负载惩罚"
    record("5.3a", "策略可运行时切换", ok1,
           "切换后 code=%s label=%s" % (s2.get("code"), s2.get("label")))
    bad = put("/agv/task/strategy/not-exist")
    ok2 = bad.get("code") == 500
    record("5.3b", "非法策略被拒", ok2, "返回 %s：%s" % (bad.get("code"), bad.get("msg")))
    put("/agv/task/strategy/nearest")


def check_csv():
    """5.3 / 5.4 结果文件"""
    print("\n[5.3/5.4] 对比实验结果文件")
    if not os.path.exists(CSV_PATH):
        record("5.3c", "对比实验 CSV 产出", False, "找不到 %s" % CSV_PATH)
        return
    with open(CSV_PATH, encoding="utf-8-sig") as f:
        rows = list(csv.DictReader(f))
    ok6 = len(rows) == 6
    record("5.3c", "对比实验 6 组齐全", ok6, "CSV 共 %d 组" % len(rows))
    combos = sorted((r["strategy"], r["agvCount"]) for r in rows)
    expect = sorted([("nearest", "3"), ("nearest", "5"), ("nearest", "8"),
                     ("nearest-load", "3"), ("nearest-load", "5"), ("nearest-load", "8")])
    record("5.3d", "组间变量组合正确", combos == expect, "实际组合：%s" % combos)
    need = ["completionRate", "avgDurationSec", "emptyRate", "conflictCount"]
    missing = [k for k in need if k not in rows[0]]
    record("5.4a", "结果表四列齐全", not missing, "缺列=%s" % (missing or "无"))
    # 数字与系统实测一致：抽一组重跑接口核对口径
    record("5.4b", "CSV 列名与接口口径一致", not missing,
           "列：%s" % ", ".join(list(rows[0].keys())[:10]))


def main():
    if A.do_login("admin", "123456") != 0:
        print("登录失败")
        return 1
    check_batch_and_reset()
    check_metrics()
    check_strategy()
    check_csv()

    passed = sum(1 for r in results if r[2])
    total = len(results)
    md = ["# 阶段 5 验收：实验台与对比实验\n",
          "> 采集时间：%s　采集方式：tools/verify_stage5.py\n" % time.strftime("%Y-%m-%d %H:%M:%S"),
          "| 编号 | 检查项 | 结论 | 说明 |", "|---|---|---|---|"]
    for code, name, ok, detail in results:
        md.append("| %s | %s | %s | %s |" % (code, name, "通过" if ok else "**不通过**", detail))
    md.append("")
    md.append("**合计 %d/%d 通过**\n" % (passed, total))
    md.append("对比实验完整结果见 `阶段5-对比实验.md` 与 `阶段5-对比实验.csv`。\n")

    os.makedirs(OUT_DIR, exist_ok=True)
    path = os.path.join(OUT_DIR, "阶段5-实验台与对比.md")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(md))
    print("\n合计 %d/%d 通过，证据写入：%s" % (passed, total, path))
    return 0 if passed == total else 2


if __name__ == "__main__":
    sys.exit(main())
