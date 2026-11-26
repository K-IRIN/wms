#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
WMS 接口调试助手：自动完成"图形验证码 → 登录 → 带 Token 请求"。

用法：
    python tools/api.py <命令> [参数...]

命令：
    login <username> <password>        登录并把 token 写入 .tmp/token
    get  <path>                        GET  /api<path>
    post <path> <json>                 POST /api<path>，body 为 JSON 字符串
    put  <path> [json]                 PUT  /api<path>
    raw  <method> <path> [json]        任意方法
    whoami                             打印当前 token 的角色与菜单

后端需以本脚本约定的日志文件启动（tools/run-backend.ps1），
验证码答案会打印在日志里，脚本据此自动完成登录。
"""

import json
import os
import re
import sys
import time
import urllib.request
import urllib.error

BASE = os.environ.get("WMS_API", "http://localhost:8080/api")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOKEN_FILE = os.path.join(ROOT, ".tmp", "token")
LOG_FILE = os.path.join(ROOT, "logs", "backend.out.log")

CAPTCHA_RE = re.compile(r"图形验证码\s+([0-9a-zA-Z\-]+)\s*=\s*([0-9a-zA-Z]+)")


def read_token():
    if os.path.exists(TOKEN_FILE):
        with open(TOKEN_FILE, "r", encoding="utf-8") as f:
            return f.read().strip()
    return ""


def write_token(tok):
    os.makedirs(os.path.dirname(TOKEN_FILE), exist_ok=True)
    with open(TOKEN_FILE, "w", encoding="utf-8") as f:
        f.write(tok)


def captcha_code_for(key, timeout=8.0):
    """从后端日志里找该 key 对应的验证码答案（开发期后端会打印）。

    后端日志经 Tee-Object 落盘有延迟，这里轮询几秒再放弃，
    否则会偶发「刚拿到验证码、日志里还没写进去」的竞态。
    """
    deadline = time.time() + timeout
    while True:
        if os.path.exists(LOG_FILE):
            with open(LOG_FILE, "r", encoding="utf-8", errors="ignore") as f:
                for line in f:
                    m = CAPTCHA_RE.search(line)
                    if m and m.group(1) == key:
                        return m.group(2)
        if time.time() >= deadline:
            return None
        time.sleep(0.3)


def request(method, path, body=None, token=None, raw=False):
    url = path if path.startswith("http") else BASE + path
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json;charset=UTF-8")
    tok = token if token is not None else read_token()
    if tok:
        req.add_header("Authorization", "Bearer " + tok)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            text = resp.read().decode("utf-8", errors="replace")
            status = resp.status
    except urllib.error.HTTPError as e:
        text = e.read().decode("utf-8", errors="replace")
        status = e.code
    except Exception as e:
        return {"_error": str(e)}
    if raw:
        return {"_status": status, "_body": text}
    try:
        obj = json.loads(text)
        obj["_status"] = status
        return obj
    except Exception:
        return {"_status": status, "_body": text}


def do_login(username, password):
    cap = request("GET", "/auth/captcha")
    key = cap.get("data", {}).get("key")
    if not key:
        print("获取验证码失败:", json.dumps(cap, ensure_ascii=False))
        return 1
    code = captcha_code_for(key)
    if not code:
        print("未在后端日志中找到验证码答案，请确认后端以 tools/run-backend.ps1 启动")
        return 1
    res = request("POST", "/auth/login",
                  {"username": username, "password": password, "code": code, "key": key})
    if res.get("code") == 200 and res.get("data"):
        write_token(res["data"]["token"])
        print("登录成功:", username, "| 角色:", res["data"].get("roles"),
              "| 菜单数:", len(res["data"].get("menus") or []))
        return 0
    print("登录失败:", json.dumps(res, ensure_ascii=False))
    return 1


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    cmd = sys.argv[1]
    if cmd == "login":
        return do_login(sys.argv[2], sys.argv[3])
    if cmd == "whoami":
        res = request("GET", "/auth/userinfo")
        print(json.dumps(res, ensure_ascii=False, indent=2))
        return 0
    if cmd in ("get", "post", "put", "delete"):
        body = None
        if cmd in ("post", "put") and len(sys.argv) > 3:
            body = json.loads(sys.argv[3])
        res = request(cmd.upper(), sys.argv[2], body)
        print(json.dumps(res, ensure_ascii=False, indent=2))
        return 0
    if cmd == "raw":
        method = sys.argv[2]
        path = sys.argv[3]
        body = json.loads(sys.argv[4]) if len(sys.argv) > 4 else None
        res = request(method, path, body, raw=True)
        print("HTTP", res.get("_status"))
        print(res.get("_body", res))
        return 0
    print(__doc__)
    return 1


if __name__ == "__main__":
    sys.exit(main())
