#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
阶段 2 验收：任务闭环（建单 → 队列 → 分配 → 执行 → 完成 → 取消）。

全程只走 HTTP 接口，不读写数据库，对应计划判据：
  「调度员建单→队列→执行→完成，全程不碰数据库」

用法：  python tools/verify_stage2.py
前提：  后端已用 tools/run-backend.ps1 启动（日志里能取到验证码）
输出：  docs/evidence/阶段2-任务闭环.md，任何一项失败时退出码 1
"""

import os
import sys
import time
import json
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import api as A  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "docs", "evidence", "阶段2-任务闭环.md")

TASK_PENDING, TASK_DONE, TASK_CANCELED = 0, 6, 7
AGV_IDLE = 0

checks = []       # (名称, 是否通过, 说明)
details = []      # 额外 markdown 段落
transitions = []  # (时刻, 状态, 说明)


def check(name, ok, detail=""):
    checks.append((name, bool(ok), detail))
    print(("  [PASS] " if ok else "  [FAIL] ") + name + (" — " + detail if detail else ""))
    return ok


def now():
    return datetime.now()


def call(method, path, body=None, token=None):
    return A.request(method, path, body, token=token)


def data_of(res):
    return res.get("data") if isinstance(res, dict) else None


def get_task(token, task_no):
    res = call("GET", "/agv/task/page?pageNum=1&pageSize=5&keyword=" + task_no, token=token)
    for row in data_of(res).get("records", []) if data_of(res) else []:
        if row.get("taskNo") == task_no:
            return row
    return None


def active(token):
    return data_of(call("GET", "/agv/task/active", token=token)) or []


def devices(token):
    return data_of(call("GET", "/agv/device/list", token=token)) or []


def wait_until(fn, timeout, interval=0.5):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        v = fn()
        if v:
            return v
        time.sleep(interval)
    return None


def create(token, frm, to, priority):
    res = call("POST", "/agv/task/create",
               {"taskType": "移库搬运", "fromLocationId": frm, "toLocationId": to,
                "priority": priority}, token=token)
    if res.get("code") != 200:
        raise RuntimeError("建单失败：%s" % json.dumps(res, ensure_ascii=False))
    return data_of(res)


def cancel(token, task_id):
    return call("PUT", "/agv/task/cancel/%s" % task_id, token=token)


def main():
    t0 = time.monotonic()
    lines, data_lines = [], []
    print("阶段 2 验收开始")

    # ---------- 0. 登录与前置数据 ----------
    if A.do_login("dispatcher", "123456") != 0:
        print("调度员登录失败，无法继续")
        return 1
    tok = A.read_token()
    info = data_of(call("GET", "/auth/userinfo", token=tok)) or {}
    roles = info.get("roles") or []
    check("调度员登录成功", "DISPATCHER" in roles, "roles=%s" % roles)

    locs = data_of(call("GET", "/base/location/all", token=tok)) or []
    with_pos = [l for l in locs if l.get("posX") is not None and l.get("posY") is not None]
    check("库位坐标可用（≥24 个库位且坐标非空）", len(locs) >= 24 and len(with_pos) >= 24,
          "库位 %d 个，坐标非空 %d 个" % (len(locs), len(with_pos)))

    dock = next((l for l in locs if l.get("locationCode") == "A01-DOCK"), None)
    if not dock:
        dock = next((l for l in locs if l.get("posX") == 0 and l.get("posY") == 0), None)
    target = next((l for l in locs if l.get("locationCode") == "A02-07-06"), None)
    if not target:
        target = max(locs, key=lambda l: (l.get("posX") or 0) + (l.get("posY") or 0))
    check("存在月台与远端库位", dock is not None and target is not None,
          "from=%s to=%s" % (dock and dock.get("locationCode"), target and target.get("locationCode")))

    devs = devices(tok)
    available = [d for d in devs if d.get("enabled") == 1]
    check("可用 AGV ≥ 3 台", len(available) >= 3, "enabled=1 共 %d 台" % len(available))

    # ---------- 1. 建单 + 队列优先级 ----------
    normal_no = create(tok, dock["id"], target["id"], 0)
    urgent_no = create(tok, dock["id"], target["id"], 1)
    check("建单接口返回任务号（普通+紧急）", bool(normal_no) and bool(urgent_no),
          "normal=%s urgent=%s" % (normal_no, urgent_no))

    act = active(tok)
    nos = [t.get("taskNo") for t in act]
    urgent_pos = nos.index(urgent_no) if urgent_no in nos else -1
    normal_pos = nos.index(normal_no) if normal_no in nos else -1
    check("紧急任务排普通任务之前（队列置顶）",
          0 <= urgent_pos < normal_pos or (urgent_pos == 0 and normal_pos == -1),
          "active 顺序：%s" % nos[:6])

    urgent_task = wait_until(lambda: get_task(tok, urgent_no), 5)
    check("任务分页可按任务号检索", urgent_task is not None, urgent_no)
    urgent_id = urgent_task["id"] if urgent_task else None

    # ---------- 2. 分配 ----------
    assigned = wait_until(lambda: next(
        (t for t in active(tok) if t.get("taskNo") == urgent_no and t.get("agvId")), None), 25)
    check("任务被引擎自动分配（≤25 秒）", assigned is not None,
          assigned and "AGV=%s 状态=%s" % (assigned.get("agvCode"), assigned.get("status")))
    agv_code = assigned.get("agvCode") if assigned else None
    agv_before = next((d for d in devs if d.get("agvCode") == agv_code), None)

    # ---------- 3. 执行 → 完成 ----------
    started = now()
    last = None
    seen = []
    done_row = None
    mid_pos = None
    home_pos = (agv_before.get("posX"), agv_before.get("posY")) if agv_before else None
    while (now() - started).total_seconds() < 180:
        row = get_task(tok, urgent_no)
        if row:
            if row.get("status") != last:
                last = row.get("status")
                seen.append((now().strftime("%H:%M:%S"), last))
                transitions.append((now(), last, "任务状态 → %s" % last))
            if mid_pos is None and home_pos and 0 < row.get("status") < TASK_DONE:
                d = next((x for x in devices(tok) if x.get("agvCode") == agv_code), None)
                if d and (d.get("posX"), d.get("posY")) != home_pos:
                    mid_pos = (d.get("posX"), d.get("posY"))
            if row.get("status") == TASK_DONE:
                done_row = row
                break
        time.sleep(0.5)
    check("任务状态推进到已完成（≤180 秒）", done_row is not None,
          "观察到状态序列：%s" % [s for _, s in seen])

    if done_row:
        fields = ["startTime", "pickTime", "pickDoneTime", "dropTime", "dropDoneTime", "finishTime"]
        missing = [f for f in fields if not done_row.get(f)]
        check("状态时间戳齐全（时间线可用）", not missing, "缺失：%s" % (missing or "无"))

        agv_after = next((d for d in devices(tok) if d.get("agvCode") == agv_code), None)
        if agv_before and agv_after:
            drop = agv_before.get("battery", 0) - agv_after.get("battery", 0)
            check("执行过程中电量下降（每格 -1%）", drop > 0,
                  "%s 电量 %s → %s（- %s）" % (agv_code, agv_before.get("battery"),
                                              agv_after.get("battery"), drop))
            check("执行途中 AGV 位置逐格变化",
                  mid_pos is not None,
                  "途中 (%s,%s)，完成后回到待命区 (%s,%s)" % (
                      (mid_pos or ("-", "-"))[0], (mid_pos or ("-", "-"))[1],
                      agv_after.get("posX"), agv_after.get("posY")))
        else:
            check("执行过程中电量下降（每格 -1%）", False, "未取到 AGV 前后快照")
            check("执行途中 AGV 位置逐格变化", False, "未取到 AGV 前后快照")

    # ---------- 4. 取消任务并释放 AGV ----------
    cancel_no = create(tok, target["id"], dock["id"], 0)
    cancel_row = wait_until(lambda: next(
        (t for t in active(tok) if t.get("taskNo") == cancel_no and t.get("agvId")), None), 25)
    check("取消用任务已分配", cancel_row is not None, cancel_no)
    if cancel_row:
        cid, cagv = cancel_row["id"], cancel_row.get("agvId")
        res = cancel(tok, cid)
        check("取消接口返回成功", res.get("code") == 200, res.get("msg", ""))
        canceled = wait_until(lambda: (get_task(tok, cancel_no) or {}).get("status") == TASK_CANCELED, 8)
        check("任务状态变为已取消（7）", bool(canceled),
              "任务 %s" % cancel_no)
        idle = wait_until(lambda: next(
            (d for d in devices(tok) if d.get("id") == cagv and d.get("status") == AGV_IDLE), None), 8)
        check("取消后 AGV 回到空闲（0）", idle is not None,
              "AGV id=%s" % cagv)

    # ---------- 5. 待分配队列与紧急插队 ----------
    burst = []
    for _ in range(len(available) + 1):
        burst.append(create(tok, dock["id"], target["id"], 0))
    time.sleep(3)
    act = active(tok)
    pending = next((t for t in act if t.get("taskNo") in burst and t.get("status") == TASK_PENDING), None)
    check("车辆全部占用时有任务停留在待分配队列", pending is not None,
          "本轮建单 %d 个，可用车 %d 台" % (len(burst), len(available)))
    if pending:
        before_idx = [t.get("taskNo") for t in act].index(pending.get("taskNo"))
        call("PUT", "/agv/task/priority/%s" % pending["id"], {"priority": 1}, token=tok)
        after = active(tok)
        after_idx = [t.get("taskNo") for t in after].index(pending.get("taskNo"))
        check("紧急插队后任务排到队首", after_idx == 0,
              "插队前第 %d 位 → 插队后第 %d 位" % (before_idx + 1, after_idx + 1))

    # ---------- 6. 清理 ----------
    leftover = [t for t in active(tok) if t.get("taskNo") in burst]
    for t in leftover:
        cancel(tok, t["id"])
    idle_ok = wait_until(lambda: all(d.get("status") == AGV_IDLE for d in devices(tok)
                                     if d.get("enabled") == 1 and d.get("status") != 2), 20)
    check("清理后可用 AGV 全部空闲", bool(idle_ok), "残留任务 %d 个已取消" % len(leftover))

    # 清场：把空闲车辆挪回待命位，避免验收残留状态污染地图与后续阶段
    A.do_login("admin", "123456")
    admin = A.read_token()
    home_ok = True
    for d in devices(admin):
        if d.get("status") == AGV_IDLE and (d.get("posX"), d.get("posY")) != (d.get("homeX"), d.get("homeY")):
            A.request("PUT", "/agv/device/update",
                      {"id": d["id"], "agvCode": d.get("agvCode"),
                       "posX": d.get("homeX"), "posY": d.get("homeY")}, token=admin)
    for d in devices(admin):
        if d.get("status") == AGV_IDLE and (d.get("posX"), d.get("posY")) != (d.get("homeX"), d.get("homeY")):
            home_ok = False
    check("清场后空闲 AGV 回到待命位", home_ok, "空闲车辆待命位与当前位置一致")
    data_lines.append("| 任务号 | 类型 | 结果 |")
    data_lines.append("|---|---|---|")

    # ---------- 7. 前端接线静态核对 ----------
    static = [
        ("任务管理页调用分页/建单/取消接口",
         "wms-web/src/views/agv/task/index.vue",
         ["pageAgvTask", "createAgvTask", "cancelAgvTask"]),
        ("任务管理页时间线读取各阶段时间戳",
         "wms-web/src/views/agv/task/index.vue",
         ["stagesOf", "pickTime", "pickDoneTime", "dropTime", "dropDoneTime"]),
        ("队列页调用进行中任务接口并计算等待秒数/可用车辆",
         "wms-web/src/views/agv/queue/index.vue",
         ["listActiveAgvTask", "waitSec", "availableCount", "updateAgvTaskPriority"]),
        ("设备档案页接入 AGV WebSocket 状态推送",
         "wms-web/src/views/agv/device/index.vue",
         ["useAgvStore"]),
    ]
    for name, rel, needles in static:
        p = os.path.join(ROOT, rel)
        text = open(p, encoding="utf-8").read() if os.path.exists(p) else ""
        missing = [n for n in needles if n not in text]
        check(name, not missing, "缺失：%s" % (missing or "无"))

    # ---------- 输出证据 ----------
    passed = sum(1 for _, ok, _ in checks if ok)
    total = len(checks)
    lines = ["# 阶段 2 验收：任务闭环\n",
             "> 采集时间：%s　方式：tools/verify_stage2.py（全程 HTTP 接口，未写数据库）\n" % now().strftime("%Y-%m-%d %H:%M:%S"),
             "> 环境：dispatcher 登录；from=%s（%s,%s）→ to=%s（%s,%s）\n" % (
                 dock.get("locationCode"), dock.get("posX"), dock.get("posY"),
                 target.get("locationCode"), target.get("posX"), target.get("posY")),
             "## 结果汇总\n",
             "**%d/%d 通过，耗时 %.1f 秒**\n" % (passed, total, time.monotonic() - t0),
             "| # | 检查项 | 结果 | 说明 |", "|---|---|---|---|"]
    for i, (name, ok, detail) in enumerate(checks, 1):
        lines.append("| %d | %s | %s | %s |" % (i, name, "✅" if ok else "❌", detail))
    if transitions:
        lines += ["\n## 任务状态观察序列\n", "| 时刻 | 状态 | 说明 |", "|---|---|---|"]
        for ts, st, note in transitions:
            lines.append("| %s | %s | %s |" % (ts.strftime("%H:%M:%S"), st, note))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print("\n%d/%d 通过，证据写入 %s" % (passed, total, OUT))
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
