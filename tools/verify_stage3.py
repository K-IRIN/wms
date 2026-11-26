#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
阶段 3 验收：电量与统计（低电回收、在途兜底、利用率）。

全程只走 HTTP 接口（不读写数据库），对应计划判据：
  3.1 注入低电后任务回队列、优先级不变、另一台车下一周期接单；
  3.2 电量跌破阈值即回收、归零不再移动，充电中车辆不接单；
  3.3 统计数据能与任务表逐条对账（utilization/completionRate/faultRate）。

用法：  python tools/verify_stage3.py
前提：  后端已用 tools/run-backend.ps1 启动（日志里能取到验证码）
输出：  docs/evidence/阶段3-电量与统计.md，任何一项失败时退出码 1
"""

import os
import re
import sys
import time
import json
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import api as A  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "docs", "evidence", "阶段3-电量与统计.md")

TASK_PENDING, TASK_DONE, TASK_CANCELED = 0, 6, 7
AGV_IDLE, AGV_CHARGING = 0, 2

checks = []
details = []


def check(name, ok, detail=""):
    checks.append((name, bool(ok), detail))
    print(("  [PASS] " if ok else "  [FAIL] ") + name + (" — " + detail if detail else ""))
    return ok


def call(method, path, body=None, token=None):
    return A.request(method, path, body, token=token)


def download(token, path, dest):
    """下载二进制内容（Excel 导出），返回本地路径；失败返回 None"""
    import urllib.request
    req = urllib.request.Request(A.BASE + path, headers={"Authorization": "Bearer " + token})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            data = r.read()
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with open(dest, "wb") as f:
            f.write(data)
        return dest
    except Exception as e:  # noqa: BLE001
        print("下载失败:", e)
        return None


def data_of(res):
    return res.get("data") if isinstance(res, dict) else None


def devices(tok):
    return data_of(call("GET", "/agv/device/list", token=tok)) or []


def stat(tok):
    return data_of(call("GET", "/agv/task/stat", token=tok)) or {}


def task_by_no(tok, no):
    res = call("GET", "/agv/task/page?pageNum=1&pageSize=10&keyword=" + no, token=tok)
    for row in (data_of(res) or {}).get("records", []):
        if row.get("taskNo") == no:
            return row
    return None


def active(tok):
    return data_of(call("GET", "/agv/task/active", token=tok)) or []


def wait_for(fn, timeout, interval=0.4):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        v = fn()
        if v:
            return v
        time.sleep(interval)
    return None


def create(tok, frm, to, priority=0):
    res = call("POST", "/agv/task/create",
               {"taskType": "移库搬运", "fromLocationId": frm, "toLocationId": to,
                "priority": priority}, token=tok)
    if res.get("code") != 200:
        raise RuntimeError("建单失败：%s" % json.dumps(res, ensure_ascii=False))
    return data_of(res)


def cancel(tok, task_id):
    return call("PUT", "/agv/task/cancel/%s" % task_id, token=tok)


def update_dev(tok, dev, **kw):
    body = {"id": dev["id"], "agvCode": dev.get("agvCode")}
    body.update(kw)
    return call("PUT", "/agv/device/update", body, token=tok)


def main():
    t0 = time.monotonic()
    print("阶段 3 验收开始")

    # ---------- 0. 登录与前置 ----------
    if A.do_login("admin", "123456") != 0:
        print("管理员登录失败")
        return 1
    tok = A.read_token()
    if A.do_login("dispatcher", "123456") != 0:
        print("调度员登录失败")
        return 1
    tok_d = A.read_token()
    check("管理员/调度员登录成功", bool(tok) and bool(tok_d), "双 token 就绪")

    locs = data_of(call("GET", "/base/location/all", token=tok_d)) or []
    dock = next((l for l in locs if l.get("posX") == 0 and l.get("posY") == 0), None)
    far = max(locs, key=lambda l: (l.get("posX") or 0) + (l.get("posY") or 0))
    check("库位数据可用（月台 + 远端库位）", dock is not None and far is not None,
          "from=%s to=%s" % (dock and dock.get("locationCode"), far and far.get("locationCode")))

    # ---------- 1. 统计接口字段（3.3） ----------
    st = stat(tok_d)
    need = ["utilization", "completionRate", "faultRate", "conflictCount", "chargeCount", "enabledAgv"]
    missing = [k for k in need if k not in st]
    check("统计数据包含利用率/故障率/完成率/冲突/充电字段", not missing,
          "缺失：%s" % (missing or "无"))
    util = st.get("utilization")
    check("设备利用率落在 0–100", isinstance(util, (int, float)) and 0 <= util <= 100,
          "utilization=%s%%，启用 AGV=%s 台" % (util, st.get("enabledAgv")))

    # 与任务表逐条对账
    page = data_of(call("GET", "/agv/task/page?pageNum=1&pageSize=500", token=tok_d)) or {}
    rows = page.get("records") or []
    total, done = page.get("total", len(rows)), sum(1 for r in rows if r.get("status") == TASK_DONE)
    exp_completion = round(done * 100.0 / total, 1) if total else 0
    check("完成率与任务表对账一致", abs((st.get("completionRate") or 0) - exp_completion) < 0.15,
          "接口 %s%% vs 任务表 %s/%s=%s%%" % (st.get("completionRate"), done, total, exp_completion))
    check("任务总数与分页 total 一致", st.get("totalTask") == total,
          "stat=%s，page.total=%s" % (st.get("totalTask"), total))

    # ---------- 2. 3.1 低电回收（在途注入） ----------
    before = devices(tok)
    avail = [d for d in before if d.get("enabled") == 1 and d.get("status") == AGV_IDLE
             and (d.get("battery") or 0) > 25]
    check("存在可派单 AGV（电量 >25%）", len(avail) >= 1, "可用 %d 台" % len(avail))

    low_no = create(tok_d, dock["id"], far["id"], 1)
    got = wait_for(lambda: next((t for t in active(tok_d)
                                 if t.get("taskNo") == low_no and t.get("agvId")), None), 30)
    check("紧急任务已被分配给 AGV", got is not None, low_no)
    low_agv = got.get("agvId") if got else None
    low_code = got.get("agvCode") if got else None
    battery0 = next((d.get("battery") for d in before if d.get("id") == low_agv), None)

    if low_agv:
        res = call("PUT", "/agv/fault/inject/%s/lowBattery" % low_agv, token=tok)
        check("低电注入接口返回成功", res.get("code") == 200, res.get("msg", ""))

        # 回收后在途任务 errorMsg 会保留，即使已被别的车接走也能核对
        recycled = wait_for(lambda: (lambda r: r if r and "电量不足" in (r.get("errorMsg") or "")
                                     else None)(task_by_no(tok_d, low_no)), 20)
        check("低电后任务被回收（errorMsg 记录原因）", recycled is not None,
              recycled and recycled.get("errorMsg"))
        check("回收后任务优先级保持不变", recycled is not None and recycled.get("priority") == 1,
              "priority=%s" % (recycled or {}).get("priority"))

        # 该车转入充电，且不再持有任务
        charging = wait_for(lambda: next((d for d in devices(tok) if d.get("id") == low_agv
                                          and d.get("status") == AGV_CHARGING), None), 15)
        check("低电 AGV 自动转入充电（状态 2）", charging is not None,
              charging and "电量=%s%%" % charging.get("battery"))
        check("低电 AGV 电量已降至 8%", charging is not None and charging.get("battery") == 8,
              "注入前 %s%% → 注入后 %s%%" % (battery0, (charging or {}).get("battery")))

        taken = wait_for(lambda: next((t for t in active(tok_d) if t.get("taskNo") == low_no
                                       and t.get("agvId") and t.get("agvId") != low_agv), None), 40)
        check("任务由另一台 AGV 接手（不回到低电车）", taken is not None,
              taken and "接手 AGV=%s" % taken.get("agvCode"))
        never_back = low_no not in [t.get("taskNo") for t in active(tok_d)
                                    if t.get("agvId") == low_agv]
        check("充电中车辆不再接单", never_back, "%s 未被重新分配任务" % low_code)

    # ---------- 3. 3.2 在途电量兜底（跌破阈值即回收） ----------
    if low_agv:
        # 清理上一单，避免干扰
        row = task_by_no(tok_d, low_no)
        if row and row.get("status") not in (TASK_DONE, TASK_CANCELED):
            cancel(tok_d, row["id"])

    guard_no = create(tok_d, far["id"], dock["id"], 0)
    g = wait_for(lambda: next((t for t in active(tok_d)
                               if t.get("taskNo") == guard_no and t.get("agvId")
                               and t.get("status") in (1, 2, 3, 4, 5)), None), 30)
    check("兜底用例任务已分配", g is not None, guard_no)
    if g:
        gid, gcode = g.get("agvId"), g.get("agvCode")
        gdev = next((d for d in devices(tok) if d.get("id") == gid), None)
        pos0 = (gdev.get("posX"), gdev.get("posY")) if gdev else None
        # 模拟行驶耗电：把车电量压到 5%，引擎下一周期应回收任务（3.2）
        call("PUT", "/agv/fault/drain/%s/5" % gid, token=tok)
        rec = wait_for(lambda: (lambda r: r if r and "在途电量" in (r.get("errorMsg") or "")
                                else None)(task_by_no(tok_d, guard_no)), 20)
        check("在途电量跌破阈值 → 任务自动回收", rec is not None,
              rec and rec.get("errorMsg"))
        time.sleep(1.5)
        d1 = next((d for d in devices(tok) if d.get("id") == gid), None)
        pos1 = (d1.get("posX"), d1.get("posY")) if d1 else None
        check("回收后不再移动（不会 0% 继续跑）", d1 is not None and pos1 == pos0,
              "位置 %s → %s，电量 %s%%" % (pos0, pos1, d1 and d1.get("battery")))
        check("低电回收的 AGV 下一周期转入充电",
              wait_for(lambda: next((d for d in devices(tok) if d.get("id") == gid
                                     and d.get("status") == AGV_CHARGING), None), 15) is not None,
              "AGV=%s" % gcode)

        row = task_by_no(tok_d, guard_no)
        if row and row.get("status") not in (TASK_DONE, TASK_CANCELED):
            cancel(tok_d, row["id"])

    # ---------- 4. 统计口径复核（含本轮产生的充电记录） ----------
    st2 = stat(tok_d)
    check("充电次数已累计（chargeCount ≥ 1）", (st2.get("chargeCount") or 0) >= 1,
          "chargeCount=%s" % st2.get("chargeCount"))
    check("故障率与故障日志对账",
          (st2.get("faultRate") or 0) == 0 or abs((st2.get("faultRate") or 0) -
                                                 (st2.get("faultCount") or 0) * 100.0 /
                                                 max(1, st2.get("totalTask"))) < 1.0,
          "faultRate=%s%%，faultCount=%s，totalTask=%s" % (
              st2.get("faultRate"), st2.get("faultCount"), st2.get("totalTask")))
    check("利用率仍为合法百分比", 0 <= (st2.get("utilization") or 0) <= 100,
          "utilization=%s%%" % st2.get("utilization"))

    # ---------- 4.5 3.4 看板与导出（AGV 页签） ----------
    import zipfile
    xlsx = download(tok, "/dashboard/export", os.path.join(ROOT, ".tmp", "报表-stage3.xlsx"))
    sheets, xlsx_ok = [], False
    if xlsx:
        try:
            with zipfile.ZipFile(xlsx) as z:
                wb = z.read("xl/workbook.xml").decode("utf-8", errors="replace")
            sheets = re.findall(r'<sheet name="([^"]+)"', wb)
            xlsx_ok = len(sheets) > 0
        except Exception:  # noqa: BLE001
            xlsx_ok = False
    check("报表导出为可解析的 xlsx（Excel 可打开）", xlsx_ok, "工作表：%s" % sheets)
    check("导出包含 AGV 调度页签", "AGV调度" in sheets, "工作表：%s" % sheets)

    dash_src = os.path.join(ROOT, "wms-web", "src", "views", "dashboard", "index.vue")
    dash = open(dash_src, encoding="utf-8").read() if os.path.exists(dash_src) else ""
    need = ["agvUtilChartRef", "type: 'gauge'", "设备利用率", "getAgvTaskStat"]
    miss = [n for n in need if n not in dash]
    check("看板含 AGV 完成量趋势与利用率环形图", not miss, "缺失：%s" % (miss or "无"))

    # ---------- 5. 清理 ----------
    for t in active(tok_d):
        if t.get("taskNo") in (low_no, guard_no):
            cancel(tok_d, t["id"])
    leftover = [t for t in active(tok_d) if t.get("status") == TASK_PENDING]
    for t in leftover[:20]:
        cancel(tok_d, t["id"])

    # 电量与位置复位，避免污染后续阶段
    for d in devices(tok):
        if d.get("status") == 3:
            call("PUT", "/agv/fault/repair/%s" % d["id"], token=tok)
    time.sleep(1)
    for d in devices(tok):
        # 电量只能走故障注入接口复位（device update 会把 battery 置空）
        call("PUT", "/agv/fault/drain/%s/%s" % (d["id"], max(60, d.get("battery") or 0)), token=tok)
        body = {"id": d["id"], "agvCode": d.get("agvCode"),
                "posX": d.get("homeX"), "posY": d.get("homeY")}
        call("PUT", "/agv/device/update", body, token=tok)
    time.sleep(2)
    devs = devices(tok)
    ok = all(d.get("battery", 0) >= 60 for d in devs if d.get("enabled") == 1)
    check("清场：AGV 电量复位（≥60%）", ok,
          ", ".join("%s=%s%%" % (d.get("agvCode"), d.get("battery")) for d in devs if d.get("enabled") == 1))

    # ---------- 6. 输出证据 ----------
    passed = sum(1 for _, ok_, _ in checks if ok_)
    total_c = len(checks)
    lines = ["# 阶段 3 验收：电量与统计\n",
             "> 采集时间：%s　方式：tools/verify_stage3.py（全程 HTTP 接口，未写数据库）\n" % now_s(),
             "> 判据：低电回收任务且优先级不变、另一台车接手；在途跌破阈值即回收；利用率/故障率可对账。\n",
             "\n## 结果汇总\n",
             "**%d/%d 通过，耗时 %.1f 秒**\n" % (passed, total_c, time.monotonic() - t0),
             "| # | 检查项 | 结果 | 说明 |", "|---|---|---|---|"]
    for i, (name, ok_, detail) in enumerate(checks, 1):
        lines.append("| %d | %s | %s | %s |" % (i, name, "✅" if ok_ else "❌", detail))
    lines += ["\n## 统计快照\n", "| 指标 | 值 |", "|---|---|"]
    for k in ("utilization", "completionRate", "faultRate", "conflictCount", "chargeCount",
              "totalDone", "totalTask", "enabledAgv"):
        lines.append("| %s | %s |" % (k, st2.get(k)))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print("\n%d/%d 通过，证据写入 %s" % (passed, total_c, OUT))
    return 0 if passed == total_c else 1


def now_s():
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


if __name__ == "__main__":
    sys.exit(main())
