#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
阶段 8 验收：库存一致性（原子增减 + 状态 CAS + 真实预占）。

覆盖：
  ① 入库存量审核重复点击：只加一次库存，第二次被状态 CAS 拦截
  ② 出库审核重复点击：只预占一次（frozen_qty 真实增加）
  ③ 出库复核重复点击：quantity 与 frozen_qty 同扣一次，frozen 归零
  ④ 盘点审核重复点击：按 diff 增量修正一次，不被重复覆盖
  ⑤ 附录 B 对账 SQL 四类全部 0 行

用法：  python tools/verify_stage8.py
前置：  后端已按最终 jar 启动（日志 logs/backend.out.log），MySQL root 口令在 DB_PASSWORD
输出：  docs/evidence/阶段8-一致性.md
退出码：全绿 0，否则 1
"""

import os
import subprocess
from concurrent.futures import ThreadPoolExecutor
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import api as A  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "docs", "evidence")
OUT_FILE = os.path.join(OUT_DIR, "阶段8-一致性.md")
TODAY = time.strftime("%Y-%m-%d")
STAMP = time.strftime("%m%d%H%M%S")
WH = 1


def wait_transport_done(order_no, token, timeout=180):
    """入库审核要求该单 AGV 搬运全部终结（DONE=6 / CANCELED=7），见 §4.6 方案 A。"""
    deadline = time.time() + timeout
    last = []
    while time.time() < deadline:
        res = A.request("GET", "/agv/task/page?pageNum=1&pageSize=50&keyword=" + order_no, token=token)
        rows = [r for r in ((res.get("data") or {}).get("records") or [])
                if r.get("orderNo") == order_no]
        last = [(r.get("taskNo"), r.get("status")) for r in rows]
        if not rows or all(r.get("status") in (6, 7) for r in rows):
            return True, "AGV 任务 %d 条已终结" % len(rows)
        time.sleep(2)
    return False, "等待 AGV 搬运超时：%s" % last


def by_location(token):
    res = A.request("GET", "/inventory/by-location?warehouseId=%d" % WH, token=token)
    return (res.get("data") or [])


def find_row(rows, location_id, material_id):
    for r in rows:
        if r.get("locationId") == location_id and r.get("materialId") == material_id:
            return r
    return None


def mysql_scalar(sql):
    pwd = os.environ.get("DB_PASSWORD", "")
    proc = subprocess.run(
        ["mysql", "-uroot", "-p" + pwd, "-N", "-B", "wms", "-e", sql],
        cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace")
    out = (proc.stdout or "").strip()
    if proc.returncode != 0:
        return None, (proc.stderr or "").strip() or ("exit %d" % proc.returncode)
    try:
        return int(out.splitlines()[-1]), None
    except Exception:
        return None, "无法解析输出：%s" % out[:200]


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

    lines, failures = [], []

    def emit(t=""):
        print(t)
        lines.append(t)

    def record(ok, text):
        if not ok:
            failures.append(text)

    emit("# 阶段 8 验收证据：库存一致性")
    emit()
    emit("> 采集时间：%s　脚本：tools/verify_stage8.py　后端：localhost:8080" % time.strftime("%Y-%m-%d %H:%M:%S"))
    emit("> 判据：重复点击审核/复核/盘点只生效一次；frozen_qty 为真实预占；对账 SQL 四类 0 行。")
    emit()

    emit("## 一、登录与环境准备")
    rc = A.do_login("admin", "123456")
    token = A.read_token() if rc == 0 else ""
    emit("- admin：%s" % ("✅ 登录成功" if token else "❌ 登录失败"))
    record(bool(token), "admin 登录失败")
    if not token:
        return finish(lines, failures, emit)

    # 演示库里残留了大量旧 AGV 任务（id 85x）把 8 台车长期占满，且电量停在 14–20%：
    # 低于派单门槛 20 又不触发自动充电（阈值 10），车队整体卡死。
    # 用“一键重置实验台”把任务/日志清空、车辆回待命位满电，属于验收环境准备，不改业务代码。
    rst = A.request("POST", "/agv/task/reset", {"agvCount": 8, "strategy": "nearest"}, token=token)
    body = rst.get("data") or {}
    emit("- 实验台重置：code=%s 清理任务 %s 条，参与车辆 %s 台"
         % (rst.get("code"), body.get("clearedTasks"), body.get("agvCount")))
    record(rst.get("code") == 200, "实验台重置失败：%s" % rst.get("msg"))
    devs = A.request("GET", "/agv/device/list", token=token)
    ready = [d for d in (devs.get("data") or []) if (d.get("battery") or 0) >= 20 and d.get("enabled") == 1]
    emit("- 当前可派车 %d 台" % len(ready))
    record(len(ready) >= 4, "可派车不足 4 台（当前 %d）" % len(ready))
    emit()

    # ---------- ① 入库审核幂等 ----------
    emit("## 二、入库审核重复点击（只加一次库存）")
    emit()
    inv = A.request("POST", "/inbound/create", {
        "supplierId": 1, "warehouseId": WH, "orderDate": TODAY,
        "remark": "阶段8验收-入库-" + STAMP,
        "items": [{"materialId": 1, "quantity": 4, "price": 10}],
    }, token=token)
    order_no = inv.get("data")
    emit("- 建单：code=%s orderNo=%s" % (inv.get("code"), order_no))
    record(inv.get("code") == 200, "入库建单失败：%s" % inv.get("msg"))
    page = A.request("GET", "/inbound/page?pageNum=1&pageSize=10&orderNo=" + str(order_no), token=token)
    order_id = None
    for row in ((page.get("data") or {}).get("records") or []):
        if row.get("orderNo") == order_no:
            order_id = row.get("id")
    detail = A.request("GET", "/inbound/detail/%s" % order_id, token=token)
    items = (detail.get("data") or {}).get("items") or []
    free = A.request("GET", "/inbound/free-locations?warehouseId=%d" % WH, token=token)
    locs = [l.get("id") for l in (free.get("data") or [])]
    emit("- 明细 %d 条，空闲库位 %d 个" % (len(items), len(locs)))
    ok_pre = bool(items) and len(locs) >= len(items)
    record(ok_pre, "入库前置数据不足")
    if ok_pre:
        loc = locs[0]
        for it in items:
            q = A.request("PUT", "/inbound/quality", {"itemId": it.get("id"), "qualityStatus": 1}, token=token)
            record(q.get("code") == 200, "质检失败：%s" % q.get("msg"))
            s = A.request("PUT", "/inbound/shelf", {"itemId": it.get("id"), "locationId": loc}, token=token)
            record(s.get("code") == 200, "上架失败：%s" % s.get("msg"))
        ok_agv, msg_agv = wait_transport_done(str(order_no), token)
        emit("- AGV 搬运：%s %s" % ("✅" if ok_agv else "❌", msg_agv))
        record(ok_agv, "AGV 搬运未完成：%s" % msg_agv)

        before = find_row(by_location(token), loc, 1)
        before_qty = before.get("quantity") if before else 0
        a1 = A.request("PUT", "/inbound/audit", {"id": order_id, "approve": True, "auditRemark": "阶段8验收"}, token=token)
        emit("- 第 1 次审核：code=%s msg=%s" % (a1.get("code"), a1.get("msg")))
        record(a1.get("code") == 200, "第 1 次审核失败：%s" % a1.get("msg"))
        a2 = A.request("PUT", "/inbound/audit", {"id": order_id, "approve": True, "auditRemark": "阶段8验收-重复"}, token=token)
        emit("- 第 2 次审核：code=%s msg=%s" % (a2.get("code"), a2.get("msg")))
        record(a2.get("code") != 200, "重复审核未被拦截（code=%s）" % a2.get("code"))
        after = find_row(by_location(token), loc, 1)
        after_qty = after.get("quantity") if after else 0
        emit("- 库位 %s 库存：%s → %s（期望 +4）" % (loc, before_qty, after_qty))
        record(after_qty == before_qty + 4, "入库库存增量错误：%s → %s" % (before_qty, after_qty))
        d2 = A.request("GET", "/inbound/detail/%s" % order_id, token=token)
        st2 = (d2.get("data") or {}).get("status")
        emit("- 单据状态：%s（期望 3 已完成）" % st2)
        record(st2 == 3, "入库单状态不是已完成：%s" % st2)
    emit()

    # ---------- ②③ 出库预占 + 复核 ----------
    emit("## 三、出库审核预占 + 复核同扣（重复点击只生效一次）")
    emit()
    rows = by_location(token)
    pick = None
    for r in rows:
        if (r.get("quantity") or 0) - (r.get("frozenQty") or 0) >= 10:
            pick = r
            break
    record(pick is not None, "没有可用库存做预占用例")
    if pick:
        loc, mat = pick.get("locationId"), pick.get("materialId")
        before_qty, before_frozen = pick.get("quantity"), pick.get("frozenQty")
        emit("- 目标库存：库位 %s 物料 %s，quantity=%s frozen=%s" % (loc, mat, before_qty, before_frozen))

        out = A.request("POST", "/outbound/create", {
            "customerId": 1, "warehouseId": WH, "orderDate": TODAY,
            "remark": "阶段8验收-出库-" + STAMP,
            "items": [{"materialId": mat, "quantity": 4, "price": 10}],
        }, token=token)
        out_no = out.get("data")
        emit("- 出库建单：code=%s orderNo=%s" % (out.get("code"), out_no))
        record(out.get("code") == 200, "出库建单失败：%s" % out.get("msg"))

        opage = A.request("GET", "/outbound/page?pageNum=1&pageSize=10&orderNo=" + str(out_no), token=token)
        out_id = None
        for row in ((opage.get("data") or {}).get("records") or []):
            if row.get("orderNo") == out_no:
                out_id = row.get("id")

        snap = {(r.get("locationId"), r.get("materialId")): (r.get("quantity"), r.get("frozenQty"))
                for r in by_location(token)}
        oa1 = A.request("PUT", "/outbound/audit", {"id": out_id, "approve": True, "auditRemark": "阶段8验收"}, token=token)
        emit("- 第 1 次审核：code=%s msg=%s" % (oa1.get("code"), oa1.get("msg")))
        record(oa1.get("code") == 200, "出库审核失败：%s" % oa1.get("msg"))
        oa2 = A.request("PUT", "/outbound/audit", {"id": out_id, "approve": True, "auditRemark": "重复"}, token=token)
        emit("- 第 2 次审核：code=%s msg=%s" % (oa2.get("code"), oa2.get("msg")))
        record(oa2.get("code") != 200, "出库重复审核未被拦截")

        mid = find_row(by_location(token), loc, mat)
        # 审核会自动挑“库存最少的可用库位”，预占可能落在别的库位：以明细实际库位为准
        odetail = A.request("GET", "/outbound/detail/%s" % out_id, token=token)
        oitems = (odetail.get("data") or {}).get("items") or []
        if oitems and oitems[0].get("locationId") is not None:
            loc = oitems[0].get("locationId")
            mid = find_row(by_location(token), loc, mat)
        bq, bf = snap.get((loc, mat), (0, 0))
        emit("- 审核落位：库位 %s；审核后 quantity=%s→%s frozen=%s→%s（期望 quantity 不变、frozen +4）"
             % (loc, bq, mid.get("quantity") if mid else None,
                bf, mid.get("frozenQty") if mid else None))
        record(mid and mid.get("quantity") == bq and mid.get("frozenQty") == bf + 4,
               "出库审核预占不正确")

        for it in oitems:
            pk = A.request("PUT", "/outbound/pick", {"itemId": it.get("id"), "pickedQty": 4}, token=token)
            emit("- 拣货：code=%s msg=%s" % (pk.get("code"), pk.get("msg")))
            record(pk.get("code") == 200, "拣货登记失败：%s" % pk.get("msg"))

        rv1 = A.request("PUT", "/outbound/review/%s" % out_id, None, token=token)
        emit("- 第 1 次复核：code=%s msg=%s" % (rv1.get("code"), rv1.get("msg")))
        record(rv1.get("code") == 200, "复核失败：%s" % rv1.get("msg"))
        rv2 = A.request("PUT", "/outbound/review/%s" % out_id, None, token=token)
        emit("- 第 2 次复核：code=%s msg=%s" % (rv2.get("code"), rv2.get("msg")))
        record(rv2.get("code") != 200, "重复复核未被拦截")

        end = find_row(by_location(token), loc, mat)
        emit("- 复核后：quantity=%s frozen=%s（期望 %s / %s）"
             % (end.get("quantity") if end else None, end.get("frozenQty") if end else None, bq - 4, bf))
        record(end and end.get("quantity") == bq - 4 and end.get("frozenQty") == bf,
               "复核扣减/预占释放不正确")
    emit()

    # ---------- ④ 移库并发（原子增减，总量守恒） ----------
    emit("## 四、移库并发（目标库存原子累加，总量守恒）")
    emit()
    rows = by_location(token)
    src = None
    for r in rows:
        if (r.get("quantity") or 0) >= 20 and (r.get("frozenQty") or 0) == 0:
            src = r
            break
    record(src is not None, "没有足够库存做移库并发用例")
    if src:
        sloc, smat = src.get("locationId"), src.get("materialId")
        used = set((r.get("locationId"), r.get("materialId")) for r in rows)
        locs = A.request("GET", "/base/location/all?warehouseId=%s" % WH, token=token)
        tloc = None
        for loc in (locs.get("data") or []):
            if loc.get("status") == 0 and loc.get("id") != sloc and (loc.get("id"), smat) not in used:
                tloc = loc.get("id")
                break
        record(tloc is not None, "没有空闲库位（status=0）做移库并发用例")
        if tloc is not None:
            total_before = sum((r.get("quantity") or 0) for r in rows if r.get("materialId") == smat)
            emit("- 源：库位 %s 物料 %s quantity=%s；目标库位 %s；移库前物料总量 %s"
                 % (sloc, smat, src.get("quantity"), tloc, total_before))

            def do_move(_):
                return A.request("POST", "/inventory/move", {
                    "materialId": smat, "fromLocationId": sloc, "toLocationId": tloc,
                    "quantity": 1, "remark": "阶段8验收-并发移库",
                }, token=token)

            with ThreadPoolExecutor(max_workers=8) as pool:
                mres = list(pool.map(do_move, range(8)))
            ok = sum(1 for r in mres if r.get("code") == 200)
            emit("- 8 并发移库各 1 件：成功 %d，失败 %d（失败样例：%s）"
                 % (ok, 8 - ok, next((r.get("msg") for r in mres if r.get("code") != 200), "-")))
            record(ok == 8, "并发移库有 %d 次失败" % (8 - ok))
            after = by_location(token)
            srow = find_row(after, sloc, smat)
            trow = find_row(after, tloc, smat)
            total_after = sum((r.get("quantity") or 0) for r in after if r.get("materialId") == smat)
            emit("- 移库后：源 %s（期望 %s），目标 %s（期望 8），物料总量 %s（期望 %s）"
                 % (srow.get("quantity") if srow else None, src.get("quantity") - ok,
                    trow.get("quantity") if trow else None, total_after, total_before))
            record(srow and srow.get("quantity") == src.get("quantity") - ok, "移库后源库存不正确")
            record(trow and trow.get("quantity") == ok, "移库后目标库存累加不正确")
            record(total_after == total_before, "移库后物料总量不守恒")
    emit()

    # ---------- ⑤ 盘点重复审核 ----------
    emit("## 五、盘点审核重复点击（按 diff 只修一次）")
    emit()
    rows = by_location(token)
    target = None
    for r in rows:
        if (r.get("quantity") or 0) >= 10:
            target = r
            break
    record(target is not None, "没有可用库存做盘点用例")
    if target:
        tloc, tmat, tqty = target.get("locationId"), target.get("materialId"), target.get("quantity")
        emit("- 目标库存：库位 %s 物料 %s，quantity=%s，实盘 %s（diff -3）" % (tloc, tmat, tqty, tqty - 3))
        co = A.request("POST", "/check/create", {
            "warehouseId": WH, "scopeType": 1, "checkPeriod": "月度",
            "remark": "阶段8验收-盘点-" + STAMP,
        }, token=token)
        check_no = co.get("data")
        cpage = A.request("GET", "/check/page?pageNum=1&pageSize=10&orderNo=" + str(check_no), token=token)
        check_id = None
        for row in ((cpage.get("data") or {}).get("records") or []):
            if row.get("orderNo") == check_no:
                check_id = row.get("id")
        emit("- 盘点建单：code=%s orderNo=%s id=%s" % (co.get("code"), check_no, check_id))
        record(co.get("code") == 200, "盘点建单失败：%s" % co.get("msg"))
        cdetail = A.request("GET", "/check/detail/%s" % check_id, token=token)
        citems = (cdetail.get("data") or {}).get("items") or []
        entry = []
        for it in citems:
            row = {"itemId": it.get("id"), "actualQty": it.get("bookQty")}
            if it.get("locationId") == tloc and it.get("materialId") == tmat:
                row["actualQty"] = (it.get("bookQty") or 0) - 3
                row["reason"] = "阶段8验收盘亏"
            entry.append(row)
        en = A.request("PUT", "/check/entry", {"items": entry}, token=token)
        emit("- 录入 %d 条：code=%s msg=%s" % (len(entry), en.get("code"), en.get("msg")))
        record(en.get("code") == 200, "盘点录入失败：%s" % en.get("msg"))
        sub = A.request("PUT", "/check/submit/%s" % check_id, None, token=token)
        emit("- 提交：code=%s msg=%s" % (sub.get("code"), sub.get("msg")))
        record(sub.get("code") == 200, "盘点提交失败：%s" % sub.get("msg"))

        ca1 = A.request("PUT", "/check/audit", {"id": check_id, "approve": True, "auditRemark": "阶段8验收"}, token=token)
        emit("- 第 1 次审核：code=%s msg=%s" % (ca1.get("code"), ca1.get("msg")))
        record(ca1.get("code") == 200, "盘点审核失败：%s" % ca1.get("msg"))
        ca2 = A.request("PUT", "/check/audit", {"id": check_id, "approve": True, "auditRemark": "重复"}, token=token)
        emit("- 第 2 次审核：code=%s msg=%s" % (ca2.get("code"), ca2.get("msg")))
        record(ca2.get("code") != 200, "盘点重复审核未被拦截")

        cend = find_row(by_location(token), tloc, tmat)
        emit("- 盘点后：quantity=%s（期望 %s）" % (cend.get("quantity") if cend else None, tqty - 3))
        record(cend and cend.get("quantity") == tqty - 3, "盘点差额应用不正确")
    emit()

    # ---------- ⑤ 对账 SQL ----------
    emit("## 六、对账 SQL（附录 B，四类必须 0 行）")
    emit()
    emit("| # | 检查 | SQL 摘要 | 行数 | 结果 |")
    emit("|---|---|---|---|---|")
    checks = [
        ("1", "负库存", "SELECT COUNT(*) FROM wms_inventory WHERE quantity < 0",
         lambda n: n == 0),
        ("2", "冻结超过库存", "SELECT COUNT(*) FROM wms_inventory WHERE frozen_qty > quantity",
         lambda n: n == 0),
        ("3", "已审核出库单未预占（报告原判据：无 picked_qty）",
         "SELECT COUNT(*) FROM wms_outbound_order o WHERE o.status=1 AND o.invalid_flag=0 "
         "AND NOT EXISTS (SELECT 1 FROM wms_outbound_item i WHERE i.order_id=o.id AND i.picked_qty>0)",
         lambda n: n == 0),
        ("3'", "已审核出库单明细未预占（frozen_qty 判据，见说明）",
         "SELECT COUNT(*) FROM wms_outbound_order o JOIN wms_outbound_item i ON i.order_id=o.id "
         "LEFT JOIN wms_inventory v ON v.warehouse_id=o.warehouse_id AND v.location_id=i.location_id "
         "AND v.material_id=i.material_id WHERE o.status=1 AND o.invalid_flag=0 AND i.location_id IS NOT NULL "
         "AND (v.id IS NULL OR v.frozen_qty < i.quantity)",
         lambda n: n == 0),
        ("4", "永久运行中但无车", "SELECT COUNT(*) FROM wms_agv_task WHERE status BETWEEN 1 AND 5 "
         "AND agv_id IS NULL AND update_time < DATE_SUB(NOW(), INTERVAL 5 MINUTE)",
         lambda n: n == 0),
    ]
    for no, desc, sql, okf in checks:
        n, err = mysql_scalar(sql)
        if err:
            emit("| %s | %s | `%s` | 执行失败 | ❌ %s |" % (no, desc, sql[:40] + "…", err[:80]))
            record(False, "对账 SQL #%s 执行失败：%s" % (no, err))
        else:
            emit("| %s | %s | `%s` | %d | %s |" % (no, desc, sql[:40] + "…", n, "✅" if okf(n) else "❌"))
            record(okf(n), "对账 SQL #%s（%s）返回 %d 行" % (no, desc, n))
    emit()
    emit("> 说明：#3 原判据用 `picked_qty>0` 判断“已预占”，但阶段 8 的模型是“审核时预占、拣货只推进 pick_status”，")
    emit("> 因此审核后未拣货的正常单据也会命中 #3。本次在全部用例复核完成后执行故为 0 行；")
    emit("> 为真正验证预占，#3' 直接校验明细对应库存行的 frozen_qty ≥ 明细数量，结果必须为 0 行。")
    emit()

    # ---------- 结论 ----------
    passed = len(failures) == 0
    emit("## 七、结论")
    emit()
    if passed:
        emit("✅ 阶段 8 验收全部通过：入库/出库/盘点重复点击均只生效一次，frozen_qty 真实预占并释放，移库并发总量守恒，对账 SQL 全 0 行。")
    else:
        emit("❌ 阶段 8 验收未通过，共 %d 项：" % len(failures))
        for f in failures:
            emit("- %s" % f)
    return finish(lines, failures, emit)


def finish(lines, failures, emit):
    emit()
    os.makedirs(OUT_DIR, exist_ok=True)
    with open(OUT_FILE, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")
    print("\n证据已写入：", OUT_FILE)
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(main())
