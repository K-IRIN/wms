#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
阶段 7 验收：安全止血 + 单号并发。

覆盖：
  ① 未登录访问受保护接口 → 401（读接口也必须登录）
  ② 6 角色 × 6 个写接口组矩阵：越权 403、授权 200
  ③ 32 并发建入库单：0 失败、单号互不重复
  ④ git grep 源码/配置中无明文口令与 JWT 密钥

用法：  python tools/verify_stage7.py
前置：  后端已用 tools/run-backend.ps1 启动（日志 logs/backend.out.log）
输出：  docs/evidence/阶段7-安全止血.md
退出码：全绿 0，否则 1（便于 CI 与人工判断）
"""

import concurrent.futures
import json
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import api as A  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "docs", "evidence")
OUT_FILE = os.path.join(OUT_DIR, "阶段7-安全止血.md")

ROLES = [
    ("admin", "ADMIN", "管理员"),
    ("manager", "MANAGER", "仓管员"),
    ("operator", "OPERATOR", "操作员"),
    ("guest", "GUEST", "访客"),
    ("maintainer", "MAINTENANCE", "运维员"),
    ("dispatcher", "DISPATCHER", "调度员"),
]
ALL_CODES = [code for _, code, _ in ROLES]

# 接口组：allow 为附录 C 允许的角色集合
GROUPS = [
    ("主数据-新增仓库", "POST", "/base/warehouse/add", {"ADMIN", "MANAGER"}),
    ("入库-建单", "POST", "/inbound/create", {"ADMIN", "MANAGER", "OPERATOR"}),
    ("入库-审核", "PUT", "/inbound/audit", {"ADMIN", "MANAGER"}),
    ("出库-建单", "POST", "/outbound/create", {"ADMIN", "MANAGER", "OPERATOR"}),
    ("盘点-建单", "POST", "/check/create", {"ADMIN", "MANAGER", "OPERATOR"}),
    ("单据-作废", "PUT", "/doc/cancel", {"ADMIN", "MANAGER"}),
]

TODAY = time.strftime("%Y-%m-%d")
STAMP = time.strftime("%m%d%H%M%S")


def body_for(group, role):
    """按接口组构造“对授权角色一定成功”的请求体。"""
    if group == "主数据-新增仓库":
        return {
            "warehouseCode": "S7-%s-%s" % (STAMP, role),
            "warehouseName": "阶段7验收仓-%s" % role,
            "address": "验收临时数据",
            "warehouseType": "综合仓",
            "status": 1,
        }
    if group == "入库-建单":
        return {
            "supplierId": 1,
            "warehouseId": 1,
            "orderDate": TODAY,
            "remark": "阶段7验收-入库-%s-%s" % (role, STAMP),
            "items": [{"materialId": 1, "quantity": 2, "price": 10}],
        }
    if group == "出库-建单":
        return {
            "customerId": 1,
            "warehouseId": 1,
            "orderDate": TODAY,
            "remark": "阶段7验收-出库-%s-%s" % (role, STAMP),
            "items": [{"materialId": 1, "quantity": 1, "price": 10}],
        }
    if group == "盘点-建单":
        return {
            "warehouseId": 1,
            "scopeType": 1,
            "checkPeriod": "月度",
            "remark": "阶段7验收-盘点-%s-%s" % (role, STAMP),
        }
    return None


def inbound_page_id(order_no, token):
    res = A.request("GET", "/inbound/page?pageNum=1&pageSize=10&orderNo=" + order_no, token=token)
    records = (res.get("data") or {}).get("records") or []
    for row in records:
        if row.get("orderNo") == order_no:
            return row.get("id")
    return None


def create_inbound(token, tag):
    dto = body_for("入库-建单", tag)
    res = A.request("POST", "/inbound/create", dto, token=token)
    return res, res.get("data")


def inbound_items(order_id, token):
    res = A.request("GET", "/inbound/detail/%s" % order_id, token=token)
    data = res.get("data") or {}
    return data.get("items") or data.get("itemList") or []


def prepare_audit(order_id, token):
    """入库审核的前置业务流程：全部明细质检合格 + 上架到空闲库位。

    审核本身要求“已质检、合格明细已上架”，这是业务规则而非鉴权规则；
    矩阵要验证的是“谁可以审核”，因此需先把单据推进到可审核状态。
    """
    items = inbound_items(order_id, token)
    if not items:
        return False, "详情中没有明细"
    free = A.request("GET", "/inbound/free-locations?warehouseId=1", token=token)
    locations = [loc.get("id") for loc in (free.get("data") or [])]
    if len(locations) < len(items):
        return False, "空闲库位不足（%d < %d）" % (len(locations), len(items))
    for idx, item in enumerate(items):
        res = A.request("PUT", "/inbound/quality",
                        {"itemId": item.get("id"), "qualityStatus": 1}, token=token)
        if res.get("code") != 200:
            return False, "质检失败：%s" % res.get("msg")
        res = A.request("PUT", "/inbound/shelf",
                        {"itemId": item.get("id"), "locationId": locations[idx]}, token=token)
        if res.get("code") != 200:
            return False, "上架失败：%s" % res.get("msg")
    return True, "已质检并上架 %d 条明细" % len(items)


def wait_transport_done(order_no, token, timeout=180):
    """阶段 8 起：入库审核要求该单 AGV 搬运任务全部结束（DONE=6 / CANCELED=7）。

    这是 §4.6 方案 A 的业务前置，不是鉴权放宽；上架会生成搬运任务，需等调度跑完。
    """
    if not order_no:
        return False, "缺少单号"
    deadline = time.time() + timeout
    last = []
    while time.time() < deadline:
        res = A.request("GET", "/agv/task/page?pageNum=1&pageSize=50&keyword=" + order_no, token=token)
        rows = [r for r in ((res.get("data") or {}).get("records") or [])
                if r.get("orderNo") == order_no]
        last = [(r.get("taskNo"), r.get("status")) for r in rows]
        if not rows or all(r.get("status") in (6, 7) for r in rows):
            return True, "AGV 任务 %d 条已完成" % len(rows)
        time.sleep(2)
    return False, "等待 AGV 搬运超时，最后状态：%s" % last


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

    lines = []
    failures = []

    def emit(text=""):
        print(text)
        lines.append(text)

    def record(ok, text):
        if not ok:
            failures.append(text)

    emit("# 阶段 7 验收证据：安全止血 + 单号")
    emit()
    emit("> 采集时间：%s　脚本：tools/verify_stage7.py　后端：localhost:8080" % time.strftime("%Y-%m-%d %H:%M:%S"))
    emit("> 判据：未登录 401；越权 body.code=403；授权 body.code=200；32 并发建单 0 失败且单号唯一。")
    emit()

    # ---------- 0. 登录 6 角色 ----------
    emit("## 一、6 角色登录")
    tokens = {}
    for username, code, name in ROLES:
        rc = A.do_login(username, "123456")
        ok = rc == 0
        tokens[code] = A.read_token() if ok else ""
        emit("- %s（%s）：%s" % (name, username, "✅ 登录成功" if ok else "❌ 登录失败"))
        record(ok, "角色 %s 登录失败" % code)
    emit()

    # ---------- 1. 未登录 ----------
    emit("## 二、未登录访问（读/写都应 401）")
    emit()
    emit("| 请求 | 实际 body.code | 期望 | 结果 |")
    emit("|---|---|---|---|")
    for desc, method, path, body in [
        ("读接口 GET /inbound/page", "GET", "/inbound/page?pageNum=1&pageSize=5", None),
        ("写接口 POST /inbound/create", "POST", "/inbound/create", {"supplierId": 1}),
        ("主数据 GET /base/warehouse/page", "GET", "/base/warehouse/page?pageNum=1&pageSize=5", None),
    ]:
        res = A.request(method, path, body, token="")
        actual = res.get("code")
        ok = actual == 401
        emit("| %s | %s | 401 | %s |" % (desc, actual, "✅" if ok else "❌"))
        record(ok, "未登录访问 %s 返回 %s，期望 401" % (path, actual))
    emit()

    # ---------- 2. 预置数据：建单/审核/作废所需单据 ----------
    emit("## 三、6 角色 × 6 接口组矩阵")
    emit()
    created = {}  # code -> orderNo
    for code in ("ADMIN", "MANAGER", "OPERATOR"):
        res, order_no = create_inbound(tokens[code], code)
        if res.get("code") == 200 and order_no:
            created[code] = order_no
    # 额外两张待审核单：供 MANAGER 作废与 ADMIN 作废
    extra_admin, extra_admin_no = create_inbound(tokens["ADMIN"], "cancel-a")
    extra_mgr, extra_mgr_no = create_inbound(tokens["ADMIN"], "cancel-b")
    admin_inv_id = inbound_page_id(created.get("ADMIN"), tokens["ADMIN"]) if created.get("ADMIN") else None
    manager_inv_id = inbound_page_id(created.get("MANAGER"), tokens["ADMIN"]) if created.get("MANAGER") else None
    operator_inv_id = inbound_page_id(created.get("OPERATOR"), tokens["ADMIN"]) if created.get("OPERATOR") else None
    cancel_a_id = inbound_page_id(extra_admin_no, tokens["ADMIN"]) if extra_admin_no else None
    cancel_b_id = inbound_page_id(extra_mgr_no, tokens["ADMIN"]) if extra_mgr_no else None
    emit("预置：ADMIN 单 %s（id=%s）、MANAGER 单 %s（id=%s）、OPERATOR 单 %s（id=%s）、作废单 id=%s/%s"
         % (created.get("ADMIN"), admin_inv_id, created.get("MANAGER"), manager_inv_id,
            created.get("OPERATOR"), operator_inv_id, cancel_a_id, cancel_b_id))
    # 审核用例的前置业务流：质检 + 上架，使两张单据满足“可审核”条件
    for label, order_id in (("ADMIN", admin_inv_id), ("MANAGER", manager_inv_id)):
        ok_pre, msg_pre = prepare_audit(order_id, tokens["ADMIN"]) if order_id else (False, "缺少单据ID")
        emit("- 审核前置（%s 单 id=%s）：%s %s" % (label, order_id, "✅" if ok_pre else "❌", msg_pre))
        record(ok_pre, "审核前置失败（%s 单）：%s" % (label, msg_pre))
    # 阶段 8 起审核要求 AGV 搬运完成（§4.6 方案 A）：等任务跑完再进矩阵
    for label, code in (("ADMIN", "ADMIN"), ("MANAGER", "MANAGER")):
        order_no = created.get(code)
        ok_agv, msg_agv = wait_transport_done(order_no, tokens["ADMIN"]) if order_no else (False, "缺少单号")
        emit("- AGV 搬运（%s 单 %s）：%s %s" % (label, order_no, "✅" if ok_agv else "❌", msg_agv))
        record(ok_agv, "AGV 搬运未完成（%s 单）：%s" % (label, msg_agv))
    emit()

    emit("| 接口组 | 角色 | 期望 | 实际 body.code | 结果 | 后端消息 |")
    emit("|---|---|---|---|---|---|")

    warehouse_codes = []
    for group, method, path, allow in GROUPS:
        for code in ALL_CODES:
            expect = 200 if code in allow else 403
            body = body_for(group, code)
            if group == "入库-审核":
                target = admin_inv_id if code == "ADMIN" else manager_inv_id
                body = {"id": target, "approve": True, "auditRemark": "阶段7验收"}
            elif group == "单据-作废":
                target = cancel_a_id if code == "ADMIN" else cancel_b_id
                body = {"type": "INBOUND", "id": target, "reason": "阶段7验收作废"}
            if body is None:
                res = {"code": None, "msg": "未构造请求体"}
            else:
                res = A.request(method, path, body, token=tokens[code])
            actual = res.get("code")
            ok = actual == expect
            if group == "主数据-新增仓库" and code in allow and ok:
                warehouse_codes.append(body["warehouseCode"])
            emit("| %s | %s | %s | %s | %s | %s |" % (
                group, code, expect, actual, "✅" if ok else "❌", (res.get("msg") or "")[:40]))
            record(ok, "%s × %s 返回 %s，期望 %s" % (group, code, actual, expect))
    emit()

    # ---------- 3. 主数据验收数据清场 ----------
    emit("## 四、清场（删除验收新增的仓库）")
    all_wh = A.request("GET", "/base/warehouse/all", token=tokens["ADMIN"])
    deleted = 0
    for wh in (all_wh.get("data") or []):
        if wh.get("warehouseCode") in warehouse_codes:
            res = A.request("DELETE", "/base/warehouse/%s" % wh.get("id"), token=tokens["ADMIN"])
            if res.get("code") == 200:
                deleted += 1
    emit("- 已删除 %d/%d 个验收仓库" % (deleted, len(warehouse_codes)))
    if deleted != len(warehouse_codes):
        record(False, "验收仓库清理不完整：%d/%d" % (deleted, len(warehouse_codes)))
    emit()

    # ---------- 4. 32 并发建单 ----------
    emit("## 五、32 并发建入库单（单号唯一性）")
    emit()
    worker_token = tokens["OPERATOR"]
    results = []

    def one(i):
        dto = body_for("入库-建单", "conc%d" % i)
        dto["remark"] = "阶段7并发-%d-%s" % (i, STAMP)
        return A.request("POST", "/inbound/create", dto, token=worker_token)

    with concurrent.futures.ThreadPoolExecutor(max_workers=32) as pool:
        results = list(pool.map(one, range(32)))

    ok_calls = [r for r in results if r.get("code") == 200]
    order_nos = [r.get("data") for r in ok_calls if r.get("data")]
    errs = [(r.get("code"), r.get("msg")) for r in results if r.get("code") != 200]
    unique = len(set(order_nos))
    emit("- 请求数：32；成功：%d；失败：%d；不同单号数：%d" % (len(ok_calls), len(errs), unique))
    if errs:
        emit("- 失败样例：%s" % json.dumps(errs[:5], ensure_ascii=False))
    emit("- 单号样例：%s" % ", ".join(order_nos[:5]))
    ok_concurrent = len(ok_calls) == 32 and unique == 32
    emit("- 结论：%s" % ("✅ 0 失败且单号互不重复" if ok_concurrent else "❌ 存在失败或重号"))
    record(ok_concurrent, "32 并发建单未达标：成功 %d、唯一单号 %d" % (len(ok_calls), unique))
    emit()

    # ---------- 5. 明文口令/密钥扫描 ----------
    emit("## 六、配置明文扫描（git grep）")
    emit()
    emit("判据：`src/main` 下不得再出现部署密钥——数据库口令赋值 `password: 123456` 与 JWT 密钥串；")
    emit("`application.yml` 必须使用 `${DB_PASSWORD:}` / `${JWT_SECRET:}` 占位。")
    pattern = "password: *123456|wms-graduation-project-secret-key"
    proc = subprocess.run(["git", "grep", "-nE", pattern, "--", "src/main"],
                          cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace")
    hits = proc.stdout.strip()
    emit("- 命令：`git grep -nE \"%s\" -- src/main`" % pattern)
    emit("- 退出码：%d（1 = 无匹配）" % proc.returncode)
    if hits:
        emit("- ❌ 命中：")
        emit("```")
        emit(hits)
        emit("```")
        record(False, "src/main 下仍存在配置明文口令/密钥：%s" % hits.splitlines()[:3])
    else:
        emit("- ✅ 无配置明文口令/密钥")
    yml = open(os.path.join(ROOT, "src", "main", "resources", "application.yml"), encoding="utf-8").read()
    placeholders_ok = "${DB_PASSWORD:}" in yml and "${JWT_SECRET:}" in yml
    emit("- application.yml 占位：%s" % ("✅ ${DB_PASSWORD:} / ${JWT_SECRET:}" if placeholders_ok else "❌ 缺少占位"))
    record(placeholders_ok, "application.yml 未使用环境变量占位")
    emit("- ⚠️ 残留说明：`SysUserController` 新增/重置用户时默认初始密码仍为字面量 `123456`（业务默认值，")
    emit("  非部署密钥；本阶段不改变用户管理交互，故未纳入本次配置明文判据，已列入交付报告残留项）。")
    emit()

    # ---------- 结论 ----------
    passed = len(failures) == 0
    emit("## 七、结论")
    emit()
    if passed:
        emit("✅ 阶段 7 验收全部通过：未登录 401、越权 403、授权 200、32 并发 0 失败且单号唯一、配置无明文。")
    else:
        emit("❌ 阶段 7 验收未通过，共 %d 项：" % len(failures))
        for f in failures:
            emit("- %s" % f)

    os.makedirs(OUT_DIR, exist_ok=True)
    with open(OUT_FILE, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")
    print("\n证据已写入：", OUT_FILE)

    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
