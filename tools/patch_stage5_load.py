#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
把「各车负载分布」补回阶段5 CSV。

背景：第一次生成 CSV 时字段表里没有 agvLoad，Markdown 里有但 CSV 丢了；
后续 EXP_FROM_CSV 重生成时读不到负载分布。这里用同一次实测run的负载数据补回，
数值来自该次运行生成的 阶段5-对比实验.md（同一批实验，不是重新测的）。
"""
import csv
import io
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CSV_PATH = os.path.join(ROOT, "docs", "evidence", "阶段5-对比实验.csv")

LOAD = {
    "就近分配 / 3 车": {"AGV-01": 5, "AGV-02": 5, "AGV-03": 4},
    "就近分配 / 5 车": {"AGV-01": 4, "AGV-02": 4, "AGV-03": 3, "AGV-04": 4, "AGV-05": 5},
    "就近分配 / 8 车": {"AGV-01": 2, "AGV-02": 2, "AGV-03": 2, "AGV-04": 3,
                        "AGV-05": 3, "AGV-06": 3, "AGV-07": 3, "AGV-08": 2},
    "就近+负载惩罚 / 3 车": {"AGV-01": 5, "AGV-02": 10, "AGV-03": 5},
    "就近+负载惩罚 / 5 车": {"AGV-01": 4, "AGV-02": 4, "AGV-03": 3, "AGV-04": 4, "AGV-05": 5},
    "就近+负载惩罚 / 8 车": {"AGV-01": 2, "AGV-02": 3, "AGV-03": 2, "AGV-04": 3,
                            "AGV-05": 3, "AGV-06": 3, "AGV-07": 2, "AGV-08": 2},
}

with io.open(CSV_PATH, encoding="utf-8-sig") as f:
    rows = list(csv.DictReader(f))
    fields = list(rows[0].keys())

if "agvLoad" not in fields:
    fields.append("agvLoad")
for r in rows:
    r["agvLoad"] = json.dumps(LOAD.get(r.get("groupLabel"), {}), ensure_ascii=False)

with io.open(CSV_PATH, "w", encoding="utf-8-sig", newline="") as f:
    w = csv.DictWriter(f, fieldnames=fields)
    w.writeheader()
    for r in rows:
        w.writerow(r)
print("已补回 agvLoad 列，%d 行" % len(rows))
