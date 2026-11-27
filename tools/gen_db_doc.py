#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
生成《数据库设计文档》（论文素材），只读库结构，不动任何 Java 文件。

与 tools/codegen.py 的区别：
  · codegen.py 会连实体类/Mapper/Service 一起重新生成（需要 pymysql），
    手写过的实体注释会被覆盖；
  · 本脚本只重新生成 docs/数据库设计.md，且走本机 mysql 客户端，
    不依赖任何第三方 Python 包。

用法：  python tools/gen_db_doc.py
输出：  docs/数据库设计.md（25 张表，含 v1.1 新增字段）
"""

import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOC_PATH = os.path.join(ROOT, "docs", "数据库设计.md")

DB = os.environ.get("WMS_DB", "wms")
DB_USER = os.environ.get("WMS_DB_USER", "root")
DB_PASS = os.environ.get("WMS_DB_PASS", "123456")

MYSQL_CANDIDATES = [
    r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe",
    "mysql",
]


def find_mysql():
    for c in MYSQL_CANDIDATES:
        if os.path.isfile(c) or shutil_which(c):
            return c
    raise SystemExit("找不到 mysql 客户端，请先执行 . .\\tools\\env.ps1")


def shutil_which(cmd):
    from shutil import which
    return which(cmd)


def query(sql):
    """执行查询并返回 TSV 行列表"""
    exe = find_mysql()
    args = [exe, "-u" + DB_USER, "-p" + DB_PASS, "--default-character-set=utf8mb4",
            "--batch", "--raw", "-e", sql]
    out = subprocess.run(args, capture_output=True, text=True, encoding="utf-8",
                         errors="replace")
    if out.returncode != 0:
        raise SystemExit("查询失败：\n" + out.stderr)
    lines = [l for l in out.stdout.splitlines() if l.strip()]
    if not lines:
        return []
    header = lines[0].split("\t")
    rows = []
    for l in lines[1:]:
        rows.append(dict(zip(header, l.split("\t"))))
    return rows


def main():
    tables = query(
        "SELECT TABLE_NAME AS t, IFNULL(TABLE_COMMENT,'') AS c "
        "FROM information_schema.TABLES WHERE TABLE_SCHEMA='%s' ORDER BY TABLE_NAME" % DB)

    doc = ["# WMS 智能仓储管理系统 数据库设计文档\n",
           "> 本文档由 tools/gen_db_doc.py 依据 wms 数据库实际结构自动生成，可用于论文「系统设计」章节。\n",
           "> 共 %d 张表。\n" % len(tables)]

    for t in tables:
        name = t["t"]
        cols = query(
            "SELECT COLUMN_NAME AS n, COLUMN_TYPE AS ty, IFNULL(COLUMN_COMMENT,'') AS cm, "
            "COLUMN_KEY AS k, IS_NULLABLE AS nul, EXTRA AS ex "
            "FROM information_schema.COLUMNS "
            "WHERE TABLE_SCHEMA='%s' AND TABLE_NAME='%s' ORDER BY ORDINAL_POSITION" % (DB, name))
        comment = (t["c"] or "").split(";")[0].split("；")[0]
        doc.append("\n## %s %s\n" % (name, comment))
        doc.append("| 字段 | 类型 | 含义 | 约束 |")
        doc.append("|------|------|------|------|")
        for c in cols:
            cons = []
            if c.get("k") == "PRI":
                cons.append("主键")
            elif c.get("k") == "UNI":
                cons.append("唯一")
            if c.get("nul") == "NO":
                cons.append("非空")
            if "auto_increment" in (c.get("ex") or ""):
                cons.append("自增")
            doc.append("| %s | %s | %s | %s |" % (
                c.get("n"), c.get("ty"), c.get("cm") or "", "、".join(cons)))

    os.makedirs(os.path.dirname(DOC_PATH), exist_ok=True)
    with open(DOC_PATH, "w", encoding="utf-8") as f:
        f.write("\n".join(doc) + "\n")
    print("已生成 %s（%d 张表）" % (DOC_PATH, len(tables)))


if __name__ == "__main__":
    main()
