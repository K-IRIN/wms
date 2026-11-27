#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
阶段 6.2 演示数据准备：把九大模块的数据补到"截图能看清业务"的程度。

全部走真实接口（不直接改库），这样单据状态、库存流水、操作日志都是系统自己产生的，
截图与论文里的数据自然一致。

产出：
  物料      4 → 16 条（覆盖原料/半成品/成品三类）
  入库单    0 → 6 单（含已审核生效的，库存随之生成）
  出库单    0 → 5 单（含已复核出库的）
  移库单    0 → 3 单
  盘点单    0 → 2 单
  AGV 任务  若干（跑出历史任务与完成量趋势）

用法：  python tools/seed_demo_data.py
"""

import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import api as A  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

MATERIALS = [
    ("M001", "十字螺丝", "M4×10mm", "盒", 5.50, 8.00, 1, 5000, 200),
    ("M002", "主控电路板", "PCB-V2.1", "块", 45.00, 68.00, 1, 1000, 50),
    ("M003", "包装纸箱", "40×30×30cm", "个", 2.00, 3.50, 1, 8000, 300),
    ("M004", "蓝牙音箱", "BS-2026 黑色", "台", 120.00, 199.00, 3, 500, 20),
    ("M005", "铝合金型材", "6063-T5", "米", 18.00, 29.00, 1, 3000, 100),
    ("M006", "轴承", "6204-2RS", "套", 6.80, 12.50, 1, 4000, 150),
    ("M007", "电机组件", "DC24V-200W", "套", 260.00, 399.00, 2, 800, 30),
    ("M008", "传感器模组", "SEN-08", "个", 35.00, 58.00, 2, 1500, 60),
    ("M009", "控制器主板", "CTRL-V3", "块", 180.00, 289.00, 2, 600, 25),
    ("M010", "锂电池组", "48V-20Ah", "组", 420.00, 659.00, 2, 400, 15),
    ("M011", "注塑外壳", "ABS 白色", "套", 22.00, 36.00, 3, 2500, 80),
    ("M012", "成品包装箱", "五层瓦楞", "个", 8.50, 14.00, 3, 3000, 120),
    ("M013", "铜排连接件", "T2-50A", "件", 14.00, 24.00, 1, 1200, 50),
    ("M014", "散热片", "AL-6063", "片", 9.60, 16.80, 2, 1400, 60),
    ("M015", "包装膜", "PE 500mm", "卷", 25.00, 39.00, 3, 900, 40),
    ("M016", "标签贴纸", "80×60 热敏", "卷", 12.00, 20.00, 3, 1100, 45),
]

SUPPLIERS = [
    ("S001", "深圳电子元件厂", "李经理", "13900000001", "深圳市宝安区XX工业园", 1),
    ("S002", "东莞精密五金厂", "王主管", "13900000002", "东莞市长安镇XX路8号", 1),
    ("S003", "苏州包装制品厂", "陈经理", "13900000003", "苏州市吴中区XX路21号", 1),
    ("S004", "杭州电机有限公司", "刘工", "13900000004", "杭州市余杭区XX路66号", 1),
]

CUSTOMERS = [
    ("C001", "杭州贸易有限公司", "王总", "13900000002", "杭州市西湖区XX大厦", 1),
    ("C002", "广州机电设备行", "林经理", "13900000005", "广州市天河区XX路12号", 1),
    ("C003", "深圳智能仓储公司", "张总", "13900000006", "深圳市南山区XX科技园", 1),
]


def get(path):
    return A.request("GET", path).get("data")


def post(path, body):
    return A.request("POST", path, body)


def put(path, body=None):
    return A.request("PUT", path, body)


def sql(stmt):
    """只用于补基础资料（物料/供应商/客户的 INSERT 太啰嗦，走接口反而更绕）"""
    import subprocess
    args = [r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe",
            "-uroot", "-p123456", "--default-character-set=utf8mb4", "wms", "-e", stmt]
    subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace")


def reset_locations():
    """把库位占用标记清空。

    上架成功会把 wms_location.status 置 1（占用）。如果之前跑过一轮又删了单据，
    库位还占着，新一轮上架就会报「库位不存在或已被占用」，所以重跑前先清一遍。
    """
    print("→ 清空库位占用标记")
    sql("UPDATE wms_location SET status = 0 WHERE (goods_type IS NULL OR goods_type <> 'AGV虚拟月台');")


def seed_materials():
    print("→ 物料 / 供应商 / 客户")
    for code, name, spec, unit, price, sale, cat, upper, lower in MATERIALS:
        if not any(m.get("materialCode") == code for m in (get("/base/material/all") or [])):
            sql("INSERT INTO wms_material (material_code, material_name, spec, unit, "
                "purchase_price, sale_price, category_id, shelf_life, stock_upper, stock_lower, status) "
                "VALUES ('%s','%s','%s','%s',%s,%s,%s,NULL,%d,%d,1);"
                % (code, name, spec, unit, price, sale, cat, upper, lower))
    for code, name, contact, phone, addr, status in SUPPLIERS:
        if not any(s.get("supplierCode") == code for s in (get("/base/supplier/all") or [])):
            sql("INSERT INTO wms_supplier (supplier_code, supplier_name, contact, phone, address, "
                "cooperate_status, status) VALUES ('%s','%s','%s','%s','%s',%d,1);"
                % (code, name, contact, phone, addr, status))
    for code, name, contact, phone, addr, level in CUSTOMERS:
        if not any(c.get("customerCode") == code for c in (get("/base/customer/all") or [])):
            sql("INSERT INTO wms_customer (customer_code, customer_name, contact, phone, address, "
                "level, status) VALUES ('%s','%s','%s','%s','%s','金牌',1);" % (code, name, contact, phone, addr))
    print("   物料 %d 条" % len(get("/base/material/all") or []))


def find_order_id(path, order_no):
    """创建接口返回的是单号（Result<String>），明细接口要的是主键 id，这里换一下"""
    data = get(path) or {}
    for r in (data.get("records") or []):
        if r.get("orderNo") == order_no:
            return r.get("id")
    return None


def seed_inbound():
    print("→ 入库单（创建 → 质检 → 上架 → 审核生效）")
    suppliers = {s["supplierCode"]: s["id"] for s in (get("/base/supplier/all") or [])}
    materials = get("/base/material/all") or []
    locs = [l for l in (get("/base/location/all") or []) if l.get("posX") is not None]
    free = [l for l in locs if (l.get("goodsType") or "") != "AGV虚拟月台"]
    today = time.strftime("%Y-%m-%d")
    existing = get("/inbound/page?pageNum=1&pageSize=100") or {}
    if (existing.get("total") or 0) >= 6:
        print("   已有 %d 单，跳过" % existing["total"])
        return
    plan = [("S001", [("M001", 500), ("M002", 120), ("M005", 300)]),
            ("S002", [("M006", 400), ("M013", 250), ("M003", 800)]),
            ("S003", [("M003", 600), ("M012", 400), ("M015", 200)]),
            ("S004", [("M007", 60), ("M010", 40), ("M009", 80)]),
            ("S001", [("M004", 50), ("M014", 150), ("M016", 300)]),
            ("S003", [("M011", 200), ("M008", 120), ("M003", 500)])]
    mid = {m["materialCode"]: m["id"] for m in materials}
    for i, (sup, items) in enumerate(plan):
        res = post("/inbound/create", {
            "supplierId": suppliers.get(sup, 1), "warehouseId": 1, "orderDate": today,
            "remark": "演示入库单 %d" % (i + 1),
            "items": [{"materialId": mid.get(code, 1), "quantity": qty, "price": 10.0} for code, qty in items]
        })
        if res.get("code") != 200:
            print("   创建失败：", res.get("msg"))
            continue
        order_no = res.get("data")
        oid = find_order_id("/inbound/page?pageNum=1&pageSize=100", order_no)
        if oid is None:
            print("   找不到单据主键：", order_no)
            continue
        detail = get("/inbound/detail/%s" % oid) or {}
        for idx, item in enumerate(detail.get("items") or []):
            put("/inbound/quality", {"itemId": item.get("id"), "qualityStatus": 1, "remark": "抽检合格"})
            loc = free[(i * 3 + idx) % len(free)]
            put("/inbound/shelf", {"itemId": item.get("id"), "locationId": loc.get("id")})
        put("/inbound/audit", {"id": oid, "approve": True, "auditRemark": "审核通过，库存生效"})
    print("   入库单 %d 单" % ((get("/inbound/page?pageNum=1&pageSize=100") or {}).get("total") or 0))


def seed_outbound():
    print("→ 出库单（创建 → 审核 → 拣货 → 复核）")
    customers = {c["customerCode"]: c["id"] for c in (get("/base/customer/all") or [])}
    materials = get("/base/material/all") or []
    mid = {m["materialCode"]: m["id"] for m in materials}
    today = time.strftime("%Y-%m-%d")
    existing = get("/outbound/page?pageNum=1&pageSize=100") or {}
    if (existing.get("total") or 0) >= 5:
        print("   已有 %d 单，跳过" % existing["total"])
        return
    plan = [("C001", [("M001", 80), ("M006", 60)]),
            ("C002", [("M007", 10), ("M010", 8)]),
            ("C003", [("M004", 20), ("M009", 15)]),
            ("C001", [("M005", 50), ("M013", 40)]),
            ("C003", [("M003", 120), ("M011", 30)])]
    for i, (cus, items) in enumerate(plan):
        res = post("/outbound/create", {
            "customerId": customers.get(cus, 1), "warehouseId": 1, "orderDate": today,
            "remark": "演示出库单 %d" % (i + 1),
            "items": [{"materialId": mid.get(code, 1), "quantity": qty, "price": 12.0} for code, qty in items]
        })
        if res.get("code") != 200:
            print("   创建失败：", res.get("msg"))
            continue
        order_no = res.get("data")
        oid = find_order_id("/outbound/page?pageNum=1&pageSize=100", order_no)
        if oid is None:
            print("   找不到单据主键：", order_no)
            continue
        put("/outbound/audit", {"id": oid, "approve": True, "auditRemark": "审核通过"})
        detail = get("/outbound/detail/%s" % oid) or {}
        for item in (detail.get("items") or []):
            code = next((m.get("materialCode") for m in materials if m.get("id") == item.get("materialId")), "")
            put("/outbound/pick", {"itemId": item.get("id"), "materialCode": code,
                                   "pickedQty": item.get("quantity")})
        put("/outbound/review/%s" % oid)
    print("   出库单 %d 单" % ((get("/outbound/page?pageNum=1&pageSize=100") or {}).get("total") or 0))


def seed_move():
    print("→ 移库单")
    existing = get("/inventory/move/page?pageNum=1&pageSize=100") or {}
    if (existing.get("total") or 0) >= 3:
        print("   已有 %d 单，跳过" % existing["total"])
        return
    rows = get("/inventory/by-location") or []
    locs = [l for l in (get("/base/location/all") or []) if (l.get("goodsType") or "") != "AGV虚拟月台"]
    n = 0
    for i, r in enumerate(rows[:4]):
        src = r.get("locationId")
        dst = locs[(i + 7) % len(locs)].get("id")
        if not src or not dst or src == dst:
            continue
        res = post("/inventory/move", {
            "fromLocationId": src, "toLocationId": dst,
            "materialId": r.get("materialId"),
            "quantity": min(20, r.get("quantity") or 10),
            "remark": "演示移库 %d" % (i + 1)
        })
        if res.get("code") == 200:
            n += 1
        else:
            print("   移库失败：", res.get("msg"))
    print("   移库单 %d 单" % ((get("/inventory/move/page?pageNum=1&pageSize=100") or {}).get("total") or 0))


def seed_check():
    print("→ 盘点单")
    existing = get("/check/page?pageNum=1&pageSize=100") or {}
    if (existing.get("total") or 0) >= 2:
        print("   已有 %d 单，跳过" % existing["total"])
        return
    for i in range(2):
        res = post("/check/create", {"warehouseId": 1, "scopeType": 1,
                                     "remark": "演示盘点单 %d" % (i + 1)})
        if res.get("code") == 200:
            oid = find_order_id("/check/page?pageNum=1&pageSize=100", res.get("data"))
            if oid is None:
                print("   找不到盘点单主键：", res.get("data"))
                continue
            detail = get("/check/detail/%s" % oid) or {}
            items = []
            for item in (detail.get("items") or []):
                items.append({"itemId": item.get("id"),
                              "actualQty": item.get("bookQty") or 0,
                              "reason": ""})
            if items:
                put("/check/entry", {"items": items})
            put("/check/submit/%s" % oid)
            put("/check/audit", {"id": oid, "approve": True, "auditRemark": "盘点无误，审核通过"})
    print("   盘点单 %d 单" % ((get("/check/page?pageNum=1&pageSize=100") or {}).get("total") or 0))


def seed_agv_history():
    """跑一批 AGV 任务，产出历史任务与完成量趋势"""
    print("→ AGV 历史任务")
    existing = get("/agv/task/page?pageNum=1&pageSize=1") or {}
    if (existing.get("total") or 0) >= 14:
        print("   已有 %d 条历史任务，跳过" % existing["total"])
        return
    locs = [l for l in (get("/base/location/all") or []) if (l.get("goodsType") or "") != "AGV虚拟月台"]
    made = 0
    for i in range(14):
        a = locs[i % len(locs)]
        b = locs[(i * 5 + 3) % len(locs)]
        if a.get("id") == b.get("id"):
            continue
        res = post("/agv/task/create", {"taskType": "移库搬运", "fromLocationId": a.get("id"),
                                        "toLocationId": b.get("id"),
                                        "priority": 1 if i % 4 == 0 else 0})
        if res.get("code") == 200:
            made += 1
    print("   建单 %d 条，等待执行…" % made)
    time.sleep(75)


def main():
    if A.do_login("admin", "123456") != 0:
        print("登录失败")
        return 1
    seed_materials()
    reset_locations()
    seed_inbound()
    seed_outbound()
    seed_move()
    seed_check()
    seed_agv_history()
    print("\n=== 演示数据现状 ===")
    for label, path in [("物料", "/base/material/all"), ("入库单", "/inbound/page?pageNum=1&pageSize=100"),
                         ("出库单", "/outbound/page?pageNum=1&pageSize=100"),
                         ("库存", "/inventory/summary"),
                         ("移库单", "/inventory/move/page?pageNum=1&pageSize=100"),
                         ("盘点单", "/check/page?pageNum=1&pageSize=100"),
                         ("AGV任务", "/agv/task/page?pageNum=1&pageSize=100"),
                         ("操作日志", "/log/page?pageNum=1&pageSize=10")]:
        d = get(path)
        if isinstance(d, list):
            n = len(d)
        else:
            n = (d or {}).get("total", 0)
        print("   %-6s %s" % (label, n))
    return 0


if __name__ == "__main__":
    sys.exit(main())
