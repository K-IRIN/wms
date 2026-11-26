#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
阶段 5：对比实验执行器（档 B：6 组）。

实验设计（论文第 5 章）：
  策略变量：nearest（就近分配） / nearest-load（就近 + 负载惩罚）
  车辆数  ：3 / 5 / 8
  共 6 组，每组从完全相同的初态出发（先 reset），组间只有策略与车辆数不同。

每组流程：
  ① reset（清空任务与日志、车辆回待命位满电、设定车辆数与策略）
  ② 批量建 20 单（起终点随机落在不同库位，制造真实的取放分布）
  ③ 等到全部任务终结（最长 300 秒）
  ④ 抓取 /agv/task/experiment 的指标

用法：  python tools/run_experiments.py
输出：  docs/evidence/阶段5-对比实验.csv  与  docs/evidence/阶段5-对比实验.md
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

TASKS_PER_GROUP = 20
MAX_WAIT_SEC = 300

STRATEGIES = [
    ("nearest", "就近分配"),
    ("nearest-load", "就近+负载惩罚"),
]
VEHICLE_COUNTS = [3, 5, 8]


def get(path):
    return A.request("GET", path).get("data")


def post(path, body):
    return A.request("POST", path, body)


def put(path, body=None):
    return A.request("PUT", path, body)


def locations():
    return [l for l in (get("/base/location/all") or []) if l.get("posX") is not None]


def all_tasks():
    rows = []
    page = 1
    while True:
        data = get("/agv/task/page?pageNum=%d&pageSize=100" % page) or {}
        recs = data.get("records") or []
        rows.extend(recs)
        if len(rows) >= (data.get("total") or 0) or not recs:
            break
        page += 1
    return rows


def run_group(strategy, label, agv_count):
    print("\n=== 组：策略=%s（%s），车辆=%d 台 ===" % (strategy, label, agv_count))
    # ① reset：设定车辆数与策略
    r = post("/agv/task/reset", {"agvCount": agv_count, "strategy": strategy})
    if r.get("code") != 200:
        print("  reset 失败：", r)
        return None
    print("  reset:", r.get("data", {}).get("message"))
    time.sleep(2)

    # ② 批量建单：起终点分散在不同货架，制造真实的取放分布
    locs = locations()
    shelf = [l for l in locs if l.get("locationCode", "").startswith("A02-")]
    if len(shelf) < 4:
        print("  货架库位不足，无法建单")
        return None
    made = 0
    for i in range(TASKS_PER_GROUP):
        a = shelf[i % len(shelf)]
        b = shelf[(i * 7 + 3) % len(shelf)]
        if a.get("id") == b.get("id"):
            b = shelf[(i * 7 + 4) % len(shelf)]
        res = post("/agv/task/create", {
            "taskType": "移库搬运",
            "fromLocationId": a.get("id"),
            "toLocationId": b.get("id"),
            "priority": 1 if i % 5 == 0 else 0,
        })
        if res.get("code") == 200:
            made += 1
    print("  建单 %d 单" % made)

    # ③ 等全部任务终结
    t0 = time.time()
    last = {}
    while time.time() - t0 < MAX_WAIT_SEC:
        time.sleep(2)
        tasks = all_tasks()
        for t in tasks:
            last[t.get("taskNo")] = t.get("status")
        if tasks and all(s in (6, 7) for s in last.values()):
            break
    elapsed = time.time() - t0
    unfinished = sum(1 for s in last.values() if s not in (6, 7))
    print("  用时 %.0f 秒，未终结 %d 单" % (elapsed, unfinished))

    # ④ 抓指标
    m = get("/agv/task/experiment") or {}
    m["groupLabel"] = "%s / %d 车" % (label, agv_count)
    m["elapsedSec"] = round(elapsed, 1)
    m["unfinished"] = unfinished
    print("  完成率=%s%% 平均完成=%ss 空驶率=%s%% 冲突=%s 负载标准差=%s"
          % (m.get("completionRate"), m.get("avgDurationSec"), m.get("emptyRate"),
             m.get("conflictCount"), m.get("loadStdDev")))
    return m


def main():
    if A.do_login("admin", "123456") != 0:
        print("登录失败")
        return 1

    rows = []
    for strategy, label in STRATEGIES:
        for n in VEHICLE_COUNTS:
            m = run_group(strategy, label, n)
            if m:
                rows.append(m)

    os.makedirs(OUT_DIR, exist_ok=True)
    csv_path = os.path.join(OUT_DIR, "阶段5-对比实验.csv")
    fields = ["groupLabel", "strategy", "strategyLabel", "agvCount", "totalTask", "doneTask",
              "completionRate", "avgDurationSec", "emptyRate", "totalDistance",
              "conflictCount", "loadStdDev", "utilization", "elapsedSec", "unfinished"]
    with open(csv_path, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields, extrasaction="ignore")
        w.writeheader()
        for r in rows:
            w.writerow(r)
    print("\nCSV 已写入：", csv_path)

    md = ["# 阶段 5 对比实验结果（档 B：6 组）\n",
          "> 采集时间：%s　采集方式：tools/run_experiments.py\n" % time.strftime("%Y-%m-%d %H:%M:%S"),
          "> 每组 %d 单；从完全相同的初态出发，组间只有「分配策略」与「车辆数」两个变量不同。\n" % TASKS_PER_GROUP,
          "## 一、结果总表\n",
          "| 组 | 策略 | 车辆数 | 完成率(%) | 平均完成时间(s) | 空驶率(%) | 冲突次数 | 负载标准差 | 利用率(%) |",
          "|---|---|---|---|---|---|---|---|---|"]
    for r in rows:
        md.append("| %s | %s | %s | %s | %s | %s | %s | %s | %s |" % (
            r.get("groupLabel"), r.get("strategyLabel"), r.get("agvCount"),
            r.get("completionRate"), r.get("avgDurationSec"), r.get("emptyRate"),
            r.get("conflictCount"), r.get("loadStdDev"), r.get("utilization")))
    md.append("")
    md.append("## 二、各车负载分布\n")
    for r in rows:
        md.append("- **%s**：%s" % (r.get("groupLabel"),
                                    json.dumps(r.get("agvLoad"), ensure_ascii=False)))
    md.append("")
    md.append("## 三、原始数据\n")
    md.append("```json")
    for r in rows:
        md.append(json.dumps(r, ensure_ascii=False))
    md.append("```")
    md_path = os.path.join(OUT_DIR, "阶段5-对比实验.md")
    with open(md_path, "w", encoding="utf-8") as f:
        f.write("\n".join(md))
    print("Markdown 已写入：", md_path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
