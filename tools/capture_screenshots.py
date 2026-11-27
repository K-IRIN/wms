#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
阶段 6.2：九大模块截图取证（Playwright + Chromium，无头）。

用法（注意要用带 Playwright 的解释器）：
    C:\\Users\\16134\\AppData\\Local\\Python\\pythoncore-3.14-64\\python.exe tools/capture_screenshots.py

登录要过图形验证码：脚本从 /api/auth/captcha 的响应里取 key，
再从后端日志 logs/backend.out.log 里读出对应验证码答案（开发期后端会打印）。

输出：docs/evidence/阶段6-九模块截图/*.png
"""

import io
import os
import re
import sys
import time

from playwright.sync_api import sync_playwright

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "docs", "evidence", "阶段6-九模块截图")
LOG_FILE = os.path.join(ROOT, "logs", "backend.out.log")
BASE = os.environ.get("WMS_WEB", "http://127.0.0.1:5173")
CAPTCHA_RE = re.compile(r"图形验证码\s+([0-9a-zA-Z\-]+)\s*=\s*([0-9a-zA-Z]+)")

# (文件名, 路径, 等待出现的选择器或文本, 说明, 视口高度)
# 视口高度：运行监控页内容较长（卡片 + 图表 + 告警列表），默认 950 会把告警列表切在画面外，
# 布局的主内容区是内部滚动容器，full_page 也截不全，所以直接给这一页用更高的视口。
PAGES = [
    ("01-数据看板-总览", "/dashboard", "text=库存总览", "模块9 统计总览", 950),
    ("02-数据看板-AGV统计", "/dashboard", "text=设备利用率", "模块9 AGV 统计与导出", 950),
    ("03-仓库地图", "/agv/map", "text=AGV 仓库实时地图", "模块2/6 地图与库位（26 个库位 + 8 台 AGV）", 950),
    ("04-AGV设备档案", "/agv/device", "text=AGV 编号", "模块3 设备档案", 950),
    ("05-搬运任务", "/agv/task", "text=新建搬运任务", "模块4 搬运任务管理", 950),
    ("06-待分配队列", "/agv/queue", "text=待分配队列", "模块5 调度分配", 950),
    ("07-运行监控", "/agv/monitor", "text=AGV 设备运行监控", "模块7/9 监控大屏（8 台设备卡 + 状态分布环形图 + 完成量折线图）", 1500),
    ("08-运行监控-告警回看", "/agv/monitor", "text=告警事件（可回看）", "模块6/8 告警可回看（告警/故障/冲突三类，可筛选分页）", 1500),
    ("09-基础资料-库位", "/base/location", "text=库位", "模块2 库区库位", 950),
    ("10-基础资料-物料", "/base/material", "text=物料", "模块2 物料档案", 950),
    ("11-采购入库", "/inbound", "text=入库单", "模块 采购入库", 950),
    ("12-销售出库", "/outbound", "text=出库单", "模块 销售出库", 950),
    ("13-库存管理", "/inventory", "text=库存", "模块 库存管理", 950),
    ("14-条码管理", "/qrcode", "text=条码", "模块 条码管理", 950),
    ("15-库存盘点", "/check", "text=盘点", "模块 库存盘点", 950),
    ("16-单据中心", "/doc", "text=单据", "模块 单据中心", 950),
    ("17-操作日志", "/log", "text=操作日志", "模块 操作日志", 950),
    ("18-用户管理", "/system/user", "text=用户", "模块1 权限管理", 950),
]


def captcha_code(key, timeout=8.0):
    deadline = time.time() + timeout
    while True:
        if os.path.exists(LOG_FILE):
            with io.open(LOG_FILE, encoding="utf-8", errors="ignore") as f:
                for line in f:
                    m = CAPTCHA_RE.search(line)
                    if m and m.group(1) == key:
                        return m.group(2)
        if time.time() >= deadline:
            return None
        time.sleep(0.3)


def login(page, username="admin", password="123456"):
    state = {"key": None}

    def on_resp(resp):
        if "/api/auth/captcha" in resp.url:
            try:
                state["key"] = resp.json()["data"]["key"]
            except Exception:
                pass

    page.on("response", on_resp)
    page.goto(BASE + "/login", wait_until="networkidle")
    page.wait_for_selector("img.captcha-img", timeout=15000)
    page.wait_for_timeout(800)

    code = captcha_code(state["key"])
    if not code:
        raise RuntimeError("没读到验证码答案，请确认后端以 tools/run-backend.ps1 启动")

    page.fill("input[placeholder='请输入账号']", username)
    page.fill("input[placeholder='请输入密码']", password)
    page.fill("input[placeholder='验证码']", code)
    page.click("button.login-btn")
    page.wait_for_url(lambda u: "/login" not in u, timeout=20000)
    page.wait_for_timeout(2500)


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    shots = []
    with sync_playwright() as p:
        browser = p.chromium.launch(args=["--force-device-scale-factor=1"])
        page = browser.new_page(viewport={"width": 1600, "height": 950})
        page.on("dialog", lambda d: d.accept())

        print("登录中…")
        login(page)
        print("登录成功")

        for name, path, wait_sel, desc, vh in PAGES:
            try:
                page.set_viewport_size({"width": 1600, "height": vh})
                page.goto(BASE + path, wait_until="networkidle", timeout=30000)
                try:
                    page.wait_for_selector(wait_sel, timeout=8000)
                except Exception:
                    pass  # 选择器没命中也照样截图，便于人工确认
                page.wait_for_timeout(2200)   # 等图表动画与接口渲染
                file = os.path.join(OUT_DIR, name + ".png")
                page.screenshot(path=file, full_page=False)
                size = os.path.getsize(file)
                shots.append((name, path, desc, size))
                print("  ✅ %-24s %-18s %6.1f KB" % (name, path, size / 1024))
            except Exception as e:
                print("  ❌ %-24s %s" % (name, e))
        browser.close()

    # 索引
    idx = os.path.join(OUT_DIR, "README.md")
    with io.open(idx, "w", encoding="utf-8") as f:
        f.write("# 阶段 6.2 九大模块截图\n\n")
        f.write("> 采集时间：%s　采集方式：tools/capture_screenshots.py（Playwright + Chromium 无头）\n"
                % time.strftime("%Y-%m-%d %H:%M:%S"))
        f.write("> 数据基线：26 个库位（24 货架 + 2 月台）、8 台 AGV、16 条物料、6 张入库单、5 张出库单。\n\n")
        f.write("| 截图 | 页面 | 对应模块/看点 |\n|---|---|---|\n")
        for name, path, desc, size in shots:
            f.write("| ![%s](%s.png) | `%s` | %s |\n" % (name, name, path, desc))
    print("\n截图 %d 张，已写入：%s" % (len(shots), OUT_DIR))


if __name__ == "__main__":
    main()
