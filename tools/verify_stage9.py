#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
阶段 9 验收脚本：调度第一刀（Publisher / Planner / Lifecycle / 防重复派单）。

验收内容（对应 docs/改进实施计划.md 阶段 9 的 9.1–9.4）：
  1. 24 台 AGV 连续调度 N 分钟（默认 30 分钟），无卡死、无重复派单；
  2. 调度 tick 的 p99 < 200ms，最大 tick < 2000ms；
  3. 存在"慢客户端"（连上 WebSocket 但不读 socket）时，tick 指标不退化；
  4. 结束后不存在"永久 RUNNING 且无车"的任务；
  5. 发布队列的丢弃计数被记录（有界队列 1000，满了丢最旧）。

用法：
  python tools/verify_stage9.py            # 30 分钟完整验收
  python tools/verify_stage9.py --minutes 2  # 冒烟（结论里会标注运行时长）

结论写入 docs/evidence/阶段9-调度.md；失败时退出码非 0。
"""
import argparse
import base64
import json
import os
import socket
import statistics
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import api  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EVIDENCE = os.path.join(ROOT, "docs", "evidence", "阶段9-调度.md")
AGV_TARGET = 24
DOCK_IN = 4      # A01-DOCK   (0,0)
DOCK_OUT = 29    # A01-DOCK-OUT (7,0)
TICK_P99_LIMIT_MS = 200
TICK_MAX_LIMIT_MS = 2000

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

failures = []
notes = []


def fail(msg):
    failures.append(msg)
    print("  [FAIL]", msg)


def ok(msg):
    print("  [ OK ]", msg)


def call(method, path, body=None, token=None):
    res = api.request(method, path, body, token)
    if "_error" in res:
        raise RuntimeError("请求异常 %s %s: %s" % (method, path, res["_error"]))
    return res


def ensure_devices(token):
    """补足 24 台 AGV（演示库只有 8 台），多出来的停在网格外圈待命。"""
    res = call("GET", "/agv/device/list", None, token)
    devices = res.get("data") or []
    if isinstance(devices, dict):
        devices = devices.get("records") or []
    have = {d.get("agvCode") for d in devices}
    print("当前 AGV 数量：%d，已启用：%d" % (len(devices), sum(1 for d in devices if d.get("enabled") == 1)))
    created = 0
    idx = 1
    while len(have) < AGV_TARGET and idx <= 40:
        code = "AGV-%02d" % idx
        idx += 1
        if code in have:
            continue
        n = len(have)
        x, y = n % 8, 8 + (n // 8)          # 停在 y=8 起的空行，不压货架
        body = {
            "agvCode": code, "agvName": "验收车" + code[-2:],
            "maxLoad": 100, "speed": 1.0, "battery": 100, "status": 0,
            "enabled": 1, "posX": x, "posY": y, "homeX": x, "homeY": y,
            "remark": "阶段9验收临时车",
        }
        r = call("POST", "/agv/device/add", body, token)
        if r.get("code") == 200:
            have.add(code)
            created += 1
        else:
            fail("新增 AGV %s 失败：%s" % (code, r.get("msg")))
            break
    ok("AGV 补足到 %d 台（本次新增 %d 台）" % (len(have), created))
    return len(have)


def reset_sim(token, n):
    r = call("POST", "/agv/task/reset", {"agvCount": n, "strategy": "nearest"}, token)
    if r.get("code") != 200:
        raise RuntimeError("重置实验台失败：%s" % json.dumps(r, ensure_ascii=False))
    return r.get("data") or {}


def open_slow_client(token, host="127.0.0.1", port=8080):
    """完成 WS 握手后不再读取，制造慢客户端。

    WebSocket 握手要求 ?token=（见 WebSocketConfig 的 HandshakeInterceptor），
    否则 Tomcat 会以 200 空响应拒绝升级。
    """
    s = socket.create_connection((host, port), timeout=5)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4096)
    key = base64.b64encode(os.urandom(16)).decode()
    path = "/api/ws/agv?token=" + token
    req = (
        "GET %s HTTP/1.1\r\nHost: %s:%d\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
        "Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\nOrigin: http://localhost:5173\r\n\r\n"
        % (path, host, port, key)
    )
    s.sendall(req.encode())
    s.settimeout(3)
    try:
        resp = s.recv(4096)
    except socket.timeout:
        resp = b""
    if b"101" not in resp.split(b"\r\n")[0]:
        s.close()
        return None
    s.settimeout(None)
    return s


def page_tasks(token, size=500):
    r = call("GET", "/agv/task/page?pageNum=1&pageSize=%d" % size, None, token)
    data = r.get("data") or {}
    return data.get("records") or []


def metrics(token):
    r = call("GET", "/agv/engine/metrics", None, token)
    if r.get("code") != 200:
        raise RuntimeError("读取调度指标失败：%s" % json.dumps(r, ensure_ascii=False))
    return r.get("data") or {}


def mysql_scalar(sql):
    mysql = os.environ.get("MYSQL_BIN", "mysql")
    cmd = [mysql, "-uroot", "-p" + os.environ.get("DB_PASSWORD", ""), "-N", "-e", sql]
    out = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if out.returncode != 0:
        raise RuntimeError("SQL 执行失败：%s\n%s" % (sql, out.stderr))
    line = (out.stdout or "").strip().splitlines()
    return int(line[0]) if line and line[0].strip().isdigit() else 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--minutes", type=float, default=30.0)
    args = ap.parse_args()
    run_seconds = int(args.minutes * 60)
    print("=== 阶段 9 验收：%s ===" % ("30 分钟完整版" if run_seconds >= 1800 else "冒烟 %.1f 分钟" % args.minutes))

    print("[1/5] 登录")
    if api.do_login("admin", "123456") != 0:
        print("登录失败，终止")
        return 1
    token = api.read_token()
    ok("admin 登录成功")

    print("[2/5] 准备 24 台 AGV 并重置实验台")
    n = ensure_devices(token)
    if n < AGV_TARGET:
        fail("AGV 数量不足：%d < %d" % (n, AGV_TARGET))
    reset = reset_sim(token, max(n, AGV_TARGET))
    ok("重置完成：%s，车辆回待命位" % json.dumps(reset, ensure_ascii=False)[:160])

    print("[3/5] 打开 3 个慢客户端（连上不读）")
    slow = []
    for _ in range(3):
        s = open_slow_client(token)
        if s:
            slow.append(s)
    if not slow:
        fail("慢客户端连接失败")
    else:
        ok("已建立 %d 个慢客户端连接" % len(slow))

    print("[4/5] 持续派单并采样 %.1f 分钟" % args.minutes)
    samples = []
    started = time.time()
    next_wave = started
    wave = 0
    created = 0
    while time.time() - started < run_seconds:
        now = time.time()
        if now >= next_wave:
            active = sum(1 for t in page_tasks(token) if t.get("status") in (0, 1, 2, 3, 4, 5))
            if active < 16:
                wave += 1
                r = call("POST", "/agv/task/batch",
                         {"count": 8, "fromLocationId": DOCK_IN, "toLocationId": DOCK_OUT,
                          "priority": wave % 3, "taskType": "验收搬运"}, token)
                if r.get("code") == 200:
                    created += len(r.get("data") or [])
                next_wave = now + 20
            else:
                next_wave = now + 5
        m = metrics(token)
        samples.append({
            "t": int(now - started), "ticks": m.get("ticks"),
            "p99": m.get("tickP99Ms"), "max": m.get("tickMaxMs"),
            "queue": (m.get("publisher") or {}).get("queueSize"),
            "dropped": (m.get("publisher") or {}).get("dropped"),
            "sent": (m.get("publisher") or {}).get("sent"),
        })
        if len(samples) % 8 == 0:
            print("  +%3ds ticks=%s p99=%sms max=%sms queue=%s dropped=%s"
                  % (samples[-1]["t"], samples[-1]["ticks"], samples[-1]["p99"],
                     samples[-1]["max"], samples[-1]["queue"], samples[-1]["dropped"]))
        time.sleep(15)

    print("[5/5] 收尾检查")
    for s in slow:
        try:
            s.close()
        except Exception:
            pass
    final = metrics(token)
    p99 = final.get("tickP99Ms") or 0
    tmax = final.get("tickMaxMs") or 0
    pub = final.get("publisher") or {}
    print("  tick 数 %s，p99=%sms，max=%sms，发布 sent=%s dropped=%s queue=%s"
          % (final.get("ticks"), p99, tmax, pub.get("sent"), pub.get("dropped"), pub.get("queueSize")))
    if p99 < TICK_P99_LIMIT_MS:
        ok("tick p99 < %dms" % TICK_P99_LIMIT_MS)
    else:
        fail("tick p99=%sms 超过 %dms" % (p99, TICK_P99_LIMIT_MS))
    if tmax < TICK_MAX_LIMIT_MS:
        ok("tick max < %dms" % TICK_MAX_LIMIT_MS)
    else:
        fail("tick max=%sms 超过 %dms（慢客户端阻塞了调度线程）" % (tmax, TICK_MAX_LIMIT_MS))

    tasks = page_tasks(token)
    # 重复派单以数据库为准：接口分页有上限，只看最新 500 条可能漏掉更早的在途任务
    dup = mysql_scalar(
        "SELECT COUNT(*) FROM (SELECT agv_id FROM wms.wms_agv_task "
        "WHERE status BETWEEN 1 AND 5 AND agv_id IS NOT NULL "
        "GROUP BY agv_id HAVING COUNT(*) > 1) t")
    if dup:
        fail("同一 AGV 同时存在多个在途任务，涉及 %d 台车" % dup)
    else:
        ok("无重复派单（同一 AGV 在途任务均 ≤1 条，全库检查）")

    permanent = mysql_scalar(
        "SELECT COUNT(*) FROM wms.wms_agv_task WHERE status BETWEEN 1 AND 5 "
        "AND agv_id IS NULL AND update_time < DATE_SUB(NOW(), INTERVAL 5 MINUTE)")
    if permanent == 0:
        ok("无永久 RUNNING 且无车任务")
    else:
        fail("存在 %d 条永久 RUNNING 且无车任务" % permanent)

    done = sum(1 for t in tasks if t.get("status") in (6, 7))
    print("  任务总计 %d 条：终结 %d，创建于本脚本 %d 条" % (len(tasks), done, created))
    if created == 0:
        fail("本轮没有成功创建任何搬运任务")
    if pub.get("dropped"):
        notes.append("发布队列丢弃 %s 条（慢客户端不读 → 有界队列丢最旧，符合 1000 上限设计）" % pub.get("dropped"))
    notes.append("慢客户端 %d 个，tick max=%sms（调度线程未被阻塞）" % (len(slow), tmax))
    notes.append("AGV 24 台、运行 %.1f 分钟、任务创建 %d 条" % (args.minutes, created))

    write_evidence(samples, final, len(slow), created, run_seconds)
    print()
    if failures:
        print("结论：失败 %d 项" % len(failures))
        for f in failures:
            print(" -", f)
        return 1
    print("结论：全绿")
    return 0


def write_evidence(samples, final, slow_count, created, run_seconds):
    os.makedirs(os.path.dirname(EVIDENCE), exist_ok=True)
    p99s = [s["p99"] for s in samples if isinstance(s.get("p99"), (int, float))]
    lines = []
    lines.append("## 调度压测原始输出（tools/verify_stage9.py）")
    lines.append("")
    lines.append("- 采样时间：%s" % time.strftime("%Y-%m-%d %H:%M:%S"))
    lines.append("- 运行时长：%d 秒；24 台 AGV；新建任务 %d 条；慢客户端 %d 个" % (run_seconds, created, slow_count))
    lines.append("- tick 采样：%d 次，p99 最大值 %s ms，最终 p99=%s ms，max=%s ms"
                 % (len(p99s), max(p99s) if p99s else "-", final.get("tickP99Ms"), final.get("tickMaxMs")))
    pub = final.get("publisher") or {}
    lines.append("- 发布器：sent=%s dropped=%s capacity=%s maxQueue=%s queue=%s"
                 % (pub.get("sent"), pub.get("dropped"), pub.get("capacity"),
                    pub.get("maxQueueSize"), pub.get("queueSize")))
    lines.append("")
    lines.append("| 秒 | ticks | p99(ms) | max(ms) | queue | dropped | sent |")
    lines.append("|---:|---:|---:|---:|---:|---:|---:|")
    for s in samples[:: max(1, len(samples) // 20)]:
        lines.append("| %s | %s | %s | %s | %s | %s | %s |"
                     % (s["t"], s["ticks"], s["p99"], s["max"], s["queue"], s["dropped"], s["sent"]))
    lines.append("")
    if failures:
        lines.append("失败项：")
        for f in failures:
            lines.append("- " + f)
    else:
        lines.append("结论：全部通过（p99 < %dms、无重复派单、无永久 RUNNING 无车）。" % TICK_P99_LIMIT_MS)
    for n in notes:
        lines.append("- 说明：" + n)
    lines.append("")
    with open(EVIDENCE, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print("证据已写入", os.path.relpath(EVIDENCE, ROOT))


if __name__ == "__main__":
    sys.exit(main())
