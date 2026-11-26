#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""分析阶段 4 验收采样：统计等待原因分布与任务最终状态。"""
import io
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
path = os.path.join(ROOT, "docs", "evidence", "阶段4-多车冲突.md")
txt = io.open(path, encoding="utf-8").read()
m = re.search(r"```json\n(.*?)\n```", txt, re.S)
if not m:
    print("没找到采样数据")
    sys.exit(1)

lines = m.group(1).splitlines()
tag = sys.argv[1] if len(sys.argv) > 1 else "拥堵"
reasons = {}
tasks = {}
dev = {}
for l in lines:
    s = json.loads(l)
    if s.get("tag") != tag:
        continue
    for w in s.get("waiting") or []:
        r = (w.get("reason") or "").split("(")[0]
        reasons[r] = reasons.get(r, 0) + 1
    for t in s.get("tasks") or []:
        tasks[t["no"]] = t
    for d in s.get("devices") or []:
        dev[d["code"]] = d

print("--- 等待原因分布 ---")
for k, v in sorted(reasons.items(), key=lambda x: -x[1]):
    print("%4d  %s" % (v, k))
print("--- 任务最终状态 ---")
for k, v in tasks.items():
    print("%s  status=%s agv=%s err=%s" % (k, v.get("st"), v.get("agv"), v.get("err")))
print("--- 车辆最终位置 ---")
for k, v in dev.items():
    print("%s (%s,%s) st=%s bat=%s" % (k, v.get("x"), v.get("y"), v.get("st"), v.get("bat")))
