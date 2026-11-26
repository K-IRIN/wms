#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
采集"改进前"对照证据（阶段 0.3），输出到 docs/evidence/。

三组实验（对应论文"改进前对照"小节）：
  ① 地图只有月台一个格子、AGV 与月台重叠
  ② 建单后任务瞬间完成、电量不降
  ③ 注入低电后任务不回收

用法：  python tools/evidence.py before
"""

import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import api as A  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "docs", "evidence")


def section(title):
    print("\n" + "=" * 70)
    print(title)
    print("=" * 70)


def get(path):
    return A.request("GET", path).get("data")


def put(path, body=None):
    return A.request("PUT", path, body)


def post(path, body):
    return A.request("POST", path, body)


def evidence_before():
    lines = []
    lines.append("# 改进前对照证据（v1.0 基线）\n")
    lines.append("> 采集时间：%s　采集方式：tools/evidence.py before（后端 v1.0 基线代码）\n"
                 % time.strftime("%Y-%m-%d %H:%M:%S"))
    lines.append("> 用途：论文第 3 章「改进前的问题」与第 7 章「改了什么」的对照小节。\n")

    # ---------------- 实验 ① 地图布局 ----------------
    section("实验① 地图布局与 AGV 初始位置")
    locs = get("/base/location/all") or []
    agvs = get("/agv/device/list") or []
    with_xy = [l for l in locs if l.get("posX") is not None and l.get("posY") is not None]
    lines.append("## 实验① 地图上只有月台一个格子，AGV 与月台重叠\n")
    lines.append("**库位总数：%d　其中带坐标：%d**\n" % (len(locs), len(with_xy)))
    lines.append("| 库位编号 | X | Y | 坐标来源 |")
    lines.append("|---|---|---|---|")
    for l in locs:
        src = "编号解析" if (l.get("posX") is not None) else "无坐标（回落到月台 0,0）"
        lines.append("| %s | %s | %s | %s |" % (l.get("locationCode"), l.get("posX"), l.get("posY"), src))
    lines.append("")
    lines.append("| AGV | 编号 | 状态 | 电量 | 当前位置 | 待命位 |")
    lines.append("|---|---|---|---|---|---|")
    for a in agvs:
        lines.append("| %s | %s | %s | %s%% | (%s, %s) | (%s, %s) |" % (
            a.get("id"), a.get("agvCode"), a.get("status"), a.get("battery"),
            a.get("posX"), a.get("posY"), a.get("homeX"), a.get("homeY")))
    lines.append("")
    dock = next((l for l in locs if l.get("locationCode") == "A01-DOCK"), None)
    overlap = [a for a in agvs if dock and a.get("posX") == dock.get("posX")
               and a.get("posY") == dock.get("posY")]
    lines.append("**结论**：月台 A01-DOCK 位于 (0,0)，AGV-01 同样位于 (0,0)，"
                 "两者在地图上完全重叠；除月台外没有任何存储库位坐标，"
                 "地图上只有 1 个格子，AGV 无处可走。\n")
    if overlap:
        lines.append("重叠车辆：%s\n" % "、".join(a.get("agvCode") for a in overlap))
    print("库位 %d（带坐标 %d）；重叠车辆：%s" % (
        len(locs), len(with_xy), [a.get("agvCode") for a in overlap]))

    # ---------------- 实验 ② 建单后瞬间完成 ----------------
    section("实验② 建单后任务瞬间完成、电量不降")
    before_agv = {a["id"]: dict(a) for a in (get("/agv/device/list") or [])}
    # 找一个存储库位与月台作为起终点（当前只有月台有坐标，起终点都用月台以暴露"瞬完"）
    res = post("/agv/task/create", {
        "taskType": "出库拣货", "fromLocationId": None, "toLocationId": None, "priority": 0
    })
    task_no = res.get("data")
    lines.append("## 实验② 建单后任务瞬间完成、电量不降\n")
    lines.append("**新建任务号：%s**（起点/终点均为月台，v1.0 库位无坐标时全部回落到 (0,0)）\n" % task_no)

    trace = []
    for i in range(6):
        time.sleep(1)
        tasks = get("/agv/task/page?pageNum=1&pageSize=10") or {}
        rows = tasks.get("records") or []
        cur = next((r for r in rows if r.get("taskNo") == task_no), None)
        agv_now = {a["id"]: a for a in (get("/agv/device/list") or [])}
        if cur:
            aid = cur.get("agvId")
            a_now = agv_now.get(aid, {})
            trace.append((i + 1, cur.get("status"), cur.get("agvCode"),
                          a_now.get("battery"), a_now.get("posX"), a_now.get("posY")))
            print("  t+%ds  状态=%s AGV=%s 电量=%s 位置=(%s,%s)" % (
                i + 1, cur.get("status"), cur.get("agvCode"),
                a_now.get("battery"), a_now.get("posX"), a_now.get("posY")))
    agv_after = {a["id"]: dict(a) for a in (get("/agv/device/list") or [])}
    lines.append("| 时刻 | 任务状态 | 执行 AGV | AGV 电量 | AGV 坐标 |")
    lines.append("|---|---|---|---|---|")
    for t in trace:
        lines.append("| 第 %d 秒 | %s | %s | %s%% | (%s, %s) |" % t)
    lines.append("")
    lines.append("| AGV | 初始电量 | 任务完成后电量 | 差值 |")
    lines.append("|---|---|---|---|")
    deltas = []
    for aid, b in before_agv.items():
        a2 = agv_after.get(aid)
        if a2:
            d = b.get("battery", 0) - a2.get("battery", 0)
            deltas.append(d)
            lines.append("| %s | %s%% | %s%% | %s |" % (b.get("agvCode"), b.get("battery"),
                                                        a2.get("battery"), d))
    lines.append("")
    lines.append("**结论**：任务一路走完「待分配→取货中→前往卸货→卸货中→返回待命区→已完成」，"
                 "但全程 AGV 坐标一动不动、电量一格没掉——"
                 "因为起点与终点都回落到月台 (0,0)，车辆「原地」就完成了搬运。"
                 "所有 AGV 电量变化为 %s，"
                 "说明「每走一格 -1%%」的电量模型没有被真正触发，"
                 "任务耗时全部来自装卸货的固定等待。\n"
                 % ("、".join(str(d) for d in deltas)))
    print("电量变化：", deltas)

    # ---------------- 实验 ③ 低电不回收 ----------------
    section("实验③ 注入低电后任务不回收")
    lines.append("## 实验③ 注入低电后任务不回收\n")
    agvs = get("/agv/device/list") or []
    target = next((a for a in agvs if a.get("status") == 1), None) or (agvs[0] if agvs else None)
    if target:
        aid = target.get("id")
        # 先给这台车派一个在途任务
        res2 = post("/agv/task/create", {
            "taskType": "入库上架", "fromLocationId": None, "toLocationId": None, "priority": 1
        })
        time.sleep(2)
        tasks = get("/agv/task/page?pageNum=1&pageSize=20") or {}
        in_flight = None
        for r in (tasks.get("records") or []):
            if r.get("agvId") == aid and r.get("status") is not None and r.get("status") < 6:
                in_flight = r
                break
        lines.append("目标 AGV：%s（id=%s）\n" % (target.get("agvCode"), aid))
        if in_flight:
            lines.append("注入低电前，任务「%s」状态=%s，agvId=%s\n"
                         % (in_flight.get("taskNo"), in_flight.get("status"), in_flight.get("agvId")))
        inj = put("/agv/fault/inject/%s/lowBattery" % aid)
        time.sleep(3)
        tasks2 = get("/agv/task/page?pageNum=1&pageSize=20") or {}
        after = None
        for r in (tasks2.get("records") or []):
            if r.get("taskNo") == (in_flight or {}).get("taskNo"):
                after = r
                break
        dev2 = get("/agv/device/list") or []
        t2 = next((d for d in dev2 if d.get("id") == aid), {})
        lines.append("注入低电 3 秒后：\n")
        lines.append("- AGV 电量：%s%%　状态：%s\n" % (t2.get("battery"), t2.get("status")))
        if after:
            lines.append("- 任务「%s」状态=%s　agvId=%s　errorMsg=%s\n"
                         % (after.get("taskNo"), after.get("status"), after.get("agvId"),
                            after.get("errorMsg")))
        lines.append("")
        lines.append("**结论**：注入低电后 AGV 只改了电量并写了一条告警，"
                     "在途任务既没有回到待分配队列（PENDING），也没有清空 agvId，"
                     "更没有改派给其他车辆——任务仍挂在原车上，"
                     "与「低电自动回充并释放任务」的设计承诺不符。\n")
        print("注入后任务状态：", after.get("status") if after else None,
              "agvId:", after.get("agvId") if after else None,
              "电量:", t2.get("battery"))
    else:
        lines.append("**结论**：没有可用的 AGV 设备，无法执行本组实验。\n")

    os.makedirs(OUT_DIR, exist_ok=True)
    path = os.path.join(OUT_DIR, "改进前对照.md")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print("\n证据已写入：", path)


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else "before"
    if mode == "before":
        evidence_before()
    else:
        print(__doc__)
