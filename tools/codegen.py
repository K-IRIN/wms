# -*- coding: utf-8 -*-
"""
WMS 代码生成器（毕业设计用）
读取 wms 数据库表结构，自动生成：
  1. com.wms.entity      实体类（@Data + @TableName）
  2. com.wms.mapper      Mapper 接口（继承 BaseMapper）
  3. com.wms.service     Service 接口（继承 IService）
  4. com.wms.service.impl ServiceImpl（继承 ServiceImpl）
  5. docs/数据库设计.md   数据库表设计文档（论文素材）

用法：python tools/codegen.py   （在项目根目录执行，需先 pip install pymysql）
"""
import os
import re
import pymysql

DB = dict(host="localhost", port=3306, user="root", password="123456",
          database="wms", charset="utf8mb4")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA_BASE = os.path.join(ROOT, "src", "main", "java", "com", "wms")

TYPE_MAP = {
    "bigint": "Long", "int": "Integer", "tinyint": "Integer",
    "smallint": "Integer", "mediumint": "Integer",
    "varchar": "String", "char": "String", "text": "String",
    "longtext": "String", "decimal": "BigDecimal", "double": "Double",
    "float": "Float", "datetime": "LocalDateTime", "timestamp": "LocalDateTime",
    "date": "LocalDate",
}
IMPORT_MAP = {"BigDecimal": "java.math.BigDecimal",
              "LocalDateTime": "java.time.LocalDateTime",
              "LocalDate": "java.time.LocalDate"}


def to_camel(name, upper_first=False):
    parts = name.split("_")
    s = "".join(p.capitalize() for p in parts)
    return s if upper_first else s[0].lower() + s[1:]


def main():
    conn = pymysql.connect(**DB)
    cur = conn.cursor()
    cur.execute("""
        SELECT TABLE_NAME, TABLE_COMMENT FROM information_schema.TABLES
        WHERE TABLE_SCHEMA = %s ORDER BY TABLE_NAME
    """, (DB["database"],))
    tables = cur.fetchall()

    doc = ["# WMS 智能仓储管理系统 数据库设计文档\n",
           "> 本文档由 tools/codegen.py 依据 wms 数据库实际结构自动生成，可用于论文「系统设计」章节。\n",
           f"> 共 {len(tables)} 张表。\n"]

    for table_name, table_comment in tables:
        cur.execute("""
            SELECT COLUMN_NAME, COLUMN_TYPE, COLUMN_COMMENT, COLUMN_KEY,
                   IS_NULLABLE, COLUMN_DEFAULT, EXTRA
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = %s AND TABLE_NAME = %s
            ORDER BY ORDINAL_POSITION
        """, (DB["database"], table_name))
        cols = cur.fetchall()

        class_name = to_camel(table_name, upper_first=True)
        comment = (table_comment or "").split(";")[0].split("；")[0]

        # ---------- entity ----------
        imports = set()
        fields = []
        for col_name, col_type, col_comment, key, nullable, default, extra in cols:
            base_type = re.sub(r"\(.*\)", "", col_type).lower()
            java_type = TYPE_MAP.get(base_type, "String")
            if java_type in IMPORT_MAP:
                imports.add(IMPORT_MAP[java_type])
            field = to_camel(col_name)
            anno = ""
            if col_name == "id" and "auto_increment" in extra:
                anno = "    @TableId(type = IdType.AUTO)\n"
            fields.append(f"    /** {col_comment or col_name} */\n{anno}    private {java_type} {field};\n")

        entity = f"""package com.wms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
"""
        if imports:
            entity += "\n".join(f"import {i};" for i in sorted(imports)) + "\n"
        entity += f"""
/**
 * {comment}（{table_name}）
 */
@Data
@TableName("{table_name}")
public class {class_name} implements Serializable {{

""" + "\n".join(fields) + "}\n"

        # ---------- mapper ----------
        mapper = f"""package com.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wms.entity.{class_name};

/**
 * {comment} Mapper
 */
public interface {class_name}Mapper extends BaseMapper<{class_name}> {{
}}
"""

        # ---------- service ----------
        service = f"""package com.wms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.wms.entity.{class_name};

/**
 * {comment} Service
 */
public interface {class_name}Service extends IService<{class_name}> {{
}}
"""

        # ---------- service impl ----------
        impl = f"""package com.wms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wms.entity.{class_name};
import com.wms.mapper.{class_name}Mapper;
import com.wms.service.{class_name}Service;
import org.springframework.stereotype.Service;

/**
 * {comment} ServiceImpl
 */
@Service
public class {class_name}ServiceImpl extends ServiceImpl<{class_name}Mapper, {class_name}> implements {class_name}Service {{
}}
"""

        for rel_path, content in [
            (os.path.join("entity", f"{class_name}.java"), entity),
            (os.path.join("mapper", f"{class_name}Mapper.java"), mapper),
            (os.path.join("service", f"{class_name}Service.java"), service),
            (os.path.join("service", "impl", f"{class_name}ServiceImpl.java"), impl),
        ]:
            path = os.path.join(JAVA_BASE, rel_path)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8") as f:
                f.write(content)

        # ---------- 设计文档 ----------
        doc.append(f"\n## {table_name} {comment}\n")
        doc.append("| 字段 | 类型 | 含义 | 约束 |")
        doc.append("|------|------|------|------|")
        for col_name, col_type, col_comment, key, nullable, default, extra in cols:
            constraints = []
            if key == "PRI":
                constraints.append("主键")
            elif key == "UNI":
                constraints.append("唯一")
            if nullable == "NO":
                constraints.append("非空")
            if "auto_increment" in extra:
                constraints.append("自增")
            doc.append(f"| {col_name} | {col_type} | {col_comment or ''} | {'、'.join(constraints)} |")

    conn.close()

    doc_path = os.path.join(ROOT, "docs", "数据库设计.md")
    os.makedirs(os.path.dirname(doc_path), exist_ok=True)
    with open(doc_path, "w", encoding="utf-8") as f:
        f.write("\n".join(doc) + "\n")

    print(f"生成完成：{len(tables)} 张表 × 4 个 Java 文件 + docs/数据库设计.md")


if __name__ == "__main__":
    main()
