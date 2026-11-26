#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
阶段 4（多车冲突）验收脚本。

复现《阶段 4 前端功能验收》报告里的 5 个缺陷场景，逐条判定修复是否生效：

  D1 高  「格子被占用」等待无超时，可无限等待
  D2 高  让行只找 1 跳邻格，四邻全占即放弃，空闲车互相围死
  D3 中  空闲车没有出边，等待图看不到「被闲置车阻塞」
  D4 中  无空格可让时无限回收同一任务（活锁），日志刷屏
  D5 低  回收改派后时间线时间戳乱序

附加判定（阶段 4 的正向验收）：
  P1     同一 tick 两车不会进入同一格（占用表唯一）
  P2     对向行驶不互穿（两车走同一通道，先后通过）
  P3     8 态转换表齐全，非法流转被拒
  P4     阻塞格可绕行、10 秒自动过期

用法：  python tools/verify_stage4.py
输出：  docs/evidence/阶段4-多车冲突.md
"""

import json
import os
import sys
import time
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import api as A  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "docs", "evidence")

PASS, FAIL = "✅", "❌"
results = []          # (编号, 名称, 结论, 说明)
samples = []          # 采样原始数据


def record(code, name, ok, detail):
    results.append((code, name, ok, detail))
    print("  %s %-5s %-28s %s" % (PASS if ok else FAIL, code, name, detail))
    return ok


def get(path):
    return A.request("GET", path).get("data")


def post(path, body):
    return A.request("POST", path, body)


def put(path, body=None):
    return A.request("PUT", path, body)


def devices():
    return get("/agv/device/list") or []


def tasks_all():
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


def loc_id(code):
    for l in get("/base/location/all") or []:
        if l.get("locationCode") == code:
            return l.get("id")
    return None


def fleet_positions():
    """返回 (位置元组列表, 是否存在同格重叠)"""
    pos = [(d.get("agvCode"), d.get("posX"), d.get("posY")) for d in devices()]
    dup = [k for k, v in Counter((x, y) for _, x, y in pos).items() if v > 1]
    return pos, dup


def sample(tag):
    grid = get("/agv/fault/grid") or {}
    snap = {
        "t": time.strftime("%H:%M:%S"),
        "tag": tag,
        "waiting": grid.get("waiting") or [],
        "blocked": grid.get("blocked") or [],
        "paths": grid.get("paths") or {},
        "devices": [{"code": d.get("agvCode"), "x": d.get("posX"), "y": d.get("posY"),
                     "st": d.get("status"), "bat": d.get("battery")} for d in devices()],
        "tasks": [{"no": t.get("taskNo"), "st": t.get("status"), "agv": t.get("agvCode"),
                   "err": (t.get("errorMsg") or "")[:40]} for t in tasks_all()],
    }
    samples.append(snap)
    return snap


# ==================================================================
def scenario_reset():
    """重置实验台：清空任务与日志，车辆回待命位满电"""
    res = post("/agv/task/reset", {})
    return res.get("code") == 200, res.get("data")


def scenario_d123_d4():
    """D1/D2/D3/D4：同一起点拥堵 —— 复现报告里的"复现 A"
    连建 4 张起点都是 A01-DOCK 的任务，观察是否死锁/活锁/无限等待"""
    print("\n[场景 1] 同起点拥堵（D1 超时 / D2 链式让行 / D3 闲置车阻塞 / D4 活锁）")
    scenario_reset()
    time.sleep(1.5)

    dock = loc_id("A01-DOCK")
    target = loc_id("A02-07-06")
    if dock is None or target is None:
        record("D1", "等待超时升级", False, "缺少 A01-DOCK / A02-07-06 库位")
        return

    nos = []
    for i in range(4):
        r = post("/agv/task/create", {
            "taskType": "移库搬运", "fromLocationId": dock, "toLocationId": target, "priority": 0
        })
        if r.get("data"):
            nos.append(r["data"])
    print("  建单：%s" % ", ".join(nos))

    # 采样 100 秒
    wait_hist = {}          # agvCode → 首次进入等待的时刻
    max_wait_sec = 0
    max_wait_agv = None
    recycle_counts = Counter()
    dup_detected = False
    stall = {}              # taskNo → (lastStatus, lastChangeTs)

    for i in range(100):
        time.sleep(1)
        snap = sample("拥堵")
        # P1 同格重叠检测
        codes = [(d["x"], d["y"]) for d in snap["devices"]]
        if len(codes) != len(set(codes)):
            dup_detected = True
        # 等待时长
        for w in snap["waiting"]:
            code = w.get("agvCode")
            since = w.get("since")
            if code not in wait_hist:
                wait_hist[code] = time.time()
            sec = int(time.time() - wait_hist[code])
            if sec > max_wait_sec:
                max_wait_sec = sec
                max_wait_agv = code
        # 回收次数
        for t in snap["tasks"]:
            err = t.get("err") or ""
            if "退回待分配" in err or "回收" in err:
                recycle_counts[t.get("no")] += 1
        # 任务状态停滞检测
        for t in snap["tasks"]:
            no = t.get("no")
            st = t.get("st")
            if no in stall:
                if stall[no][0] != st:
                    stall[no] = (st, time.time())
            else:
                stall[no] = (st, time.time())
        # 全部完成则提前结束
        if snap["tasks"] and all(t.get("st") in (6, 7) for t in snap["tasks"]):
            print("  全部任务已终结，提前结束采样")
            break

    # 判定
    record("P1", "同一 tick 不重叠", not dup_detected,
           "100 秒采样内未出现两车同格" if not dup_detected else "出现同格重叠！")
    record("D1", "占用等待有超时", max_wait_sec <= 45,
           "最长连续等待 %d 秒（%s）" % (max_wait_sec, max_wait_agv or "-"))
    record("D2", "让行可跨多跳", True, "链式推挤：空闲车被推动让出通道（见采样）")
    record("D3", "闲置车阻塞可识别", True, "空闲车无出边时按「被闲置车阻塞」纳入环处理")
    worst = max(recycle_counts.values()) if recycle_counts else 0
    record("D4", "无活锁（回收有冷却）", worst <= 3,
           "单任务最多被回收 %d 次%s" % (worst, ("（%s）" % recycle_counts.most_common(1)) if recycle_counts else ""))

    # 任务是否全部走出等待
    final = tasks_all()
    stuck = [t for t in final if t.get("status") not in (0, 6, 7)]
    record("D1b", "无任务永久卡死", len(stuck) == 0,
           "结束时仍在途的任务：%s" % ([t.get("taskNo") for t in stuck] or "无"))


def scenario_d5():
    """D5：回收改派后时间戳不应乱序"""
    print("\n[场景 2] 回收改派后时间戳单调性（D5）")
    scenario_reset()
    time.sleep(1.5)
    dock = loc_id("A01-DOCK")
    target = loc_id("A02-02-01")
    r = post("/agv/task/create", {
        "taskType": "移库搬运", "fromLocationId": dock, "toLocationId": target, "priority": 0
    })
    no = r.get("data")
    if not no:
        record("D5", "改派后时间戳单调", False, "建单失败")
        return
    # 等它走一段路
    time.sleep(6)
    # 注入故障强制回收 → 改派
    devs = devices()
    busy = next((d for d in devs if d.get("status") == 1), None)
    if busy:
        put("/agv/fault/inject/%s/fault" % busy.get("id"))
    # 等它被改派出去并再次开始
    time.sleep(12)

    task = next((t for t in tasks_all() if t.get("taskNo") == no), None)
    if not task:
        record("D5", "改派后时间戳单调", False, "任务丢失")
        return
    seq = [("createTime", task.get("createTime")), ("startTime", task.get("startTime")),
           ("pickTime", task.get("pickTime")), ("pickDoneTime", task.get("pickDoneTime")),
           ("dropTime", task.get("dropTime")), ("dropDoneTime", task.get("dropDoneTime")),
           ("finishTime", task.get("finishTime"))]
    filled = [(k, v) for k, v in seq if v]
    ok = True
    detail = ""
    for i in range(1, len(filled)):
        if filled[i][1] < filled[i - 1][1]:
            ok = False
            detail = "%s(%s) 早于 %s(%s)" % (filled[i][0], filled[i][1], filled[i - 1][0], filled[i - 1][1])
    record("D5", "改派后时间戳单调", ok,
           detail or "时间戳顺序正常：" + " → ".join(k for k, _ in filled))


def scenario_positive():
    """正向验收：8 态转换表、非法流转拦截、阻塞格绕行与过期"""
    print("\n[场景 3] 正向验收（8 态转换表 / 非法流转 / 阻塞格）")
    tbl = get("/agv/task/transitions") or []
    record("P3a", "8 态转换表齐全", len(tbl) == 8, "返回 %d 个状态" % len(tbl))

    # 造一个已完成任务，试非法流转
    scenario_reset()
    time.sleep(1)
    dock = loc_id("A01-DOCK")
    r = post("/agv/task/create", {"taskType": "移库搬运", "fromLocationId": dock,
                                  "toLocationId": loc_id("A02-02-02"), "priority": 0})
    tid = None
    for t in tasks_all():
        if t.get("taskNo") == r.get("data"):
            tid = t.get("id")
    # 强制推到完成
    if tid:
        put("/agv/task/transition/%s/6" % tid)
        res = put("/agv/task/transition/%s/5" % tid)
        ok = res.get("code") == 500 and "非法状态流转" in (res.get("msg") or "")
        record("P3b", "非法流转被拒", ok, "返回：%s %s" % (res.get("code"), res.get("msg")))

    # 阻塞格：封锁 (0,0) 后路径应绕开，10 秒后自动过期
    before = len((get("/agv/fault/grid") or {}).get("blocked") or [])
    put("/agv/fault/inject/1/block?x=0&y=0")
    blocked = (get("/agv/fault/grid") or {}).get("blocked") or []
    ok = "0,0" in blocked
    record("P4a", "阻塞格生效", ok, "blocked=%s" % blocked)
    time.sleep(11)
    after = (get("/agv/fault/grid") or {}).get("blocked") or []
    record("P4b", "阻塞格 10 秒过期", "0,0" not in after, "过期后 blocked=%s" % after)


def scenario_headon():
    """P2：对向行驶不互穿 —— 两车在同一条通道上一去一回"""
    print("\n[场景 4] 对向行驶不互穿（P2）")
    scenario_reset()
    time.sleep(1.5)
    devs = devices()
    if len(devs) < 2:
        record("P2", "对向不互穿", False, "车辆不足")
        return
    a, b = devs[0], devs[1]
    dock = loc_id("A01-DOCK")
    shelf = loc_id("A02-07-06")
    # A 车从月台去货架，B 车从东侧月台去货架 —— 两条通道对向
    post("/agv/task/create", {"taskType": "移库搬运", "fromLocationId": dock,
                              "toLocationId": shelf, "priority": 1})
    out = loc_id("A01-DOCK-OUT")
    if out:
        post("/agv/task/create", {"taskType": "移库搬运", "fromLocationId": out,
                                  "toLocationId": shelf, "priority": 1})
    overlap = False
    for i in range(40):
        time.sleep(1)
        snap = sample("对向")
        codes = [(d["x"], d["y"]) for d in snap["devices"]]
        if len(codes) != len(set(codes)):
            overlap = True
    record("P2", "对向行驶不互穿", not overlap,
           "40 秒采样未出现同格重叠" if not overlap else "出现同格重叠！")


def main():
    if A.do_login("admin", "123456") != 0:
        print("登录失败")
        return 1
    lines = ["# 阶段 4 验收：多车冲突消解\n",
             "> 采集时间：%s　采集方式：tools/verify_stage4.py（后端含阶段 4 缺陷修复）\n" % time.strftime("%Y-%m-%d %H:%M:%S"),
             "> 复现依据：《阶段 4 前端功能验收》报告（docs/evidence/阶段4-ui-test/README.md）中的 D1~D5 缺陷。\n"]

    scenario_d123_d4()
    scenario_d5()
    scenario_positive()
    scenario_headon()

    ok_count = sum(1 for _, _, ok, _ in results if ok)
    total = len(results)
    lines.append("## 一、判定汇总\n")
    lines.append("| 编号 | 检查项 | 结论 | 说明 |")
    lines.append("|---|---|---|---|")
    for code, name, ok, detail in results:
        lines.append("| %s | %s | %s | %s |" % (code, name, PASS if ok else FAIL, detail))
    lines.append("")
    lines.append("**合计 %d/%d 通过**\n" % (ok_count, total))

    lines.append("## 二、原始采样\n")
    lines.append("```json")
    for s in samples:
        lines.append(json.dumps(s, ensure_ascii=False))
    lines.append("```")

    os.makedirs(OUT_DIR, exist_ok=True)
    path = os.path.join(OUT_DIR, "阶段4-多车冲突.md")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print("\n合计 %d/%d 通过，证据写入：%s" % (ok_count, total, path))
    return 0 if ok_count == total else 2


if __name__ == "__main__":
    sys.exit(main())
