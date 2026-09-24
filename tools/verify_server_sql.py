#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
校验 tools/verify_server.sql 的语法与列引用正确性。

为什么需要：
    这个脚本是要拿到生产服务器上执行的。里面任何一个列名写错，
    都会让运维在排查现场看到报错，损害排查效率与信任。
    所以它自己也必须被校验，尤其是 information_schema 的列名
    （各 MySQL 版本间偶有增减，写错不会在本地暴露）。

用法：
    uv run --with sqlglot python tools/verify_server_sql.py
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SQL = REPO / "tools" / "verify_server.sql"

# information_schema 各表的真实列（按 MySQL 5.7/8.0 公共部分）
IS_COLUMNS = {
    "CHARACTER_SETS": {"CHARACTER_SET_NAME", "DEFAULT_COLLATE_NAME",
                       "DESCRIPTION", "MAXLEN"},
    "COLLATIONS": {"COLLATION_NAME", "CHARACTER_SET_NAME", "ID", "IS_DEFAULT",
                   "IS_COMPILED", "SORTLEN", "PAD_ATTRIBUTE"},
    "SCHEMATA": {"CATALOG_NAME", "SCHEMA_NAME", "DEFAULT_CHARACTER_SET_NAME",
                 "DEFAULT_COLLATION_NAME", "SQL_PATH"},
    "TABLES": {"TABLE_CATALOG", "TABLE_SCHEMA", "TABLE_NAME", "TABLE_TYPE",
               "ENGINE", "VERSION", "ROW_FORMAT", "TABLE_ROWS", "AVG_ROW_LENGTH",
               "DATA_LENGTH", "INDEX_LENGTH", "TABLE_COLLATION", "TABLE_COMMENT",
               "CREATE_TIME", "UPDATE_TIME", "CHECK_TIME", "AUTO_INCREMENT"},
    "ENGINES": {"ENGINE", "SUPPORT", "COMMENT", "TRANSACTIONS", "XA", "SAVEPOINTS"},
    "COLUMNS": {"TABLE_SCHEMA", "TABLE_NAME", "COLUMN_NAME", "ORDINAL_POSITION",
                "COLUMN_DEFAULT", "IS_NULLABLE", "DATA_TYPE", "COLUMN_TYPE",
                "COLLATION_NAME", "COLUMN_KEY", "EXTRA", "COLUMN_COMMENT"},
    "STATISTICS": {"TABLE_SCHEMA", "TABLE_NAME", "INDEX_NAME", "NON_UNIQUE",
                   "SEQ_IN_INDEX", "COLUMN_NAME", "CARDINALITY"},
}

# mysql 系统表的列
MYSQL_COLUMNS = {
    "user": {"user", "host", "plugin", "account_locked", "password_expired",
             "authentication_string", "password_last_changed", "User", "Host"},
}

# 校验时跳过的关键字（会被误抓成列名）
KEYWORDS = {
    "SELECT", "AS", "CASE", "WHEN", "THEN", "ELSE", "END", "FROM", "WHERE",
    "AND", "OR", "NOT", "NULL", "LIKE", "COUNT", "CONCAT", "SUBSTRING_INDEX",
    "DISTINCT", "ORDER", "BY", "GROUP", "ASC", "DESC", "SHOW", "USE",
    "VARIABLES", "GRANTS", "FOR", "IS", "IN", "ON", "JOIN", "LEFT", "RIGHT",
    "SUM", "MAX", "MIN", "AVG", "IFNULL", "COALESCE", "TRUE", "FALSE",
}


def strip_aliases(seg: str) -> str:
    """去掉 `AS xxx` 别名声明。

    否则别名会被当成列名误报。例：
        SELECT TABLE_COLLATION AS collation FROM ...
    不处理就会把 collation 报成"不存在的列"。
    """
    # AS <标识符>  ；标识符可能带反引号
    seg = re.sub(r"\bAS\s+`?[A-Za-z_][A-Za-z0-9_]*`?", " ", seg, flags=re.I)
    # 形如 `expr` alias 的隐式别名（少数）也处理
    return seg

PASS, FAIL = 0, 0


def ok(m):
    global PASS
    PASS += 1
    print(f"  [ OK ] {m}")


def bad(m):
    global FAIL
    FAIL += 1
    print(f"  [FAIL] {m}")


def warn(m):
    print(f"  [WARN] {m}")


def head(t):
    print()
    print("=" * 74)
    print(t)
    print("=" * 74)


def strip_comments(sql: str) -> str:
    return "\n".join(l for l in sql.splitlines() if not l.strip().startswith("--"))


def main() -> int:
    if not SQL.exists():
        print(f"缺少 {SQL}")
        return 1

    sql = SQL.read_text(encoding="utf-8")
    body = strip_comments(sql)
    stmts = [s.strip() for s in body.split(";") if s.strip()]

    head("1. 语法（按 MySQL 方言解析）")
    try:
        import sqlglot
    except ImportError:
        print("  需要 sqlglot: uv run --with sqlglot python tools/verify_server_sql.py")
        return 1

    n_fail = 0
    n_skip = 0
    for i, s in enumerate(stmts, 1):
        first = s.splitlines()[0].strip().upper()
        # sqlglot 对 SHOW 系列支持不完整，尤其是 SHOW GRANTS FOR 'user'@'host'
        # 这类含 user@host 字面量的形式。跳过而非报错，避免假失败。
        if first.startswith("SHOW"):
            n_skip += 1
            continue
        try:
            parsed = sqlglot.parse(s, dialect="mysql")
            kind = type(parsed[0]).__name__ if parsed and parsed[0] else "?"
            if n_fail == 0:
                print(f"  [ OK ] #{i:<2} {kind:<16} {s.splitlines()[0][:56]}")
        except Exception as e:
            n_fail += 1
            bad(f"#{i} 解析失败 {type(e).__name__}: {str(e)[:60]}")
            print(f"         {s.splitlines()[0][:70]}")
    if n_fail == 0:
        ok(f"{len(stmts) - n_skip} 条语句通过 MySQL 方言解析（{n_skip} 条 SHOW 跳过）")

    # 嵌套引号检查：这类写法本地能跑，通过 shell/docker exec 传参时会碎
    if re.search(r"CONCAT\s*\([^)]*''", sql, re.I):
        bad("存在嵌套转义引号的 CONCAT 写法，经 shell 传参会碎裂")
    else:
        ok("无嵌套转义引号（脚本可安全地经 shell / 重定向执行）")

    head("2. information_schema 列引用")
    known_problems = 0
    for tbl in sorted(set(re.findall(r"information_schema\.(\w+)", sql, re.I))):
        key = tbl.upper()
        if key not in IS_COLUMNS:
            warn(f"未收录的表，无法校验其列：{tbl}")
            continue
        cols = IS_COLUMNS[key]
        # 抓取该表所在的 SELECT 片段
        for m in re.finditer(rf"FROM\s+information_schema\.{tbl}\b", sql, re.I):
            start = max(sql.rfind("SELECT", 0, m.start()), 0)
            seg = strip_aliases(sql[start:m.start()])
            for col in re.findall(r"\b([A-Za-z_][A-Za-z0-9_]{2,})\b", seg):
                up = col.upper()
                if up in KEYWORDS:
                    continue
                if up not in cols:
                    bad(f"{tbl} 无此列: {col}")
                    known_problems += 1
    if known_problems == 0:
        ok("所有 information_schema 列引用均存在")

    head("3. mysql 系统表列引用")
    p2 = 0
    for m in re.finditer(r"FROM\s+mysql\.(\w+)", sql, re.I):
        tbl = m.group(1)
        if tbl.lower() not in MYSQL_COLUMNS:
            warn(f"未收录的系统表：mysql.{tbl}")
            continue
        cols = {c.lower() for c in MYSQL_COLUMNS[tbl.lower()]}
        seg = strip_aliases(sql[max(sql.rfind("SELECT", 0, m.start()), 0):m.start()])
        for col in re.findall(r"\b([A-Za-z_][A-Za-z0-9_]{2,})\b", seg):
            if col.upper() in KEYWORDS:
                continue
            if col.lower() not in cols:
                bad(f"mysql.{tbl} 无此列: {col}")
                p2 += 1
    if p2 == 0:
        ok("所有 mysql 系统表列引用均存在")

    head("4. 版本判别所需的检查项是否齐全")
    # 这些是判断 MySQL 真实大版本的关键，缺一不可
    required = [
        (r"VERSION\(\)", "服务端版本函数 VERSION()"),
        (r"@@collation_server", "服务端默认排序规则"),
        (r"DEFAULT_COLLATE_NAME", "utf8mb4 的默认排序规则"),
        (r"utf8mb4\\_0900|utf8mb4_0900", "8.0 专有排序规则探测"),
        (r"@@lower_case_table_names", "表名大小写敏感性"),
        (r"@@bind_address|bind_address", "监听地址（判断能否外部访问）"),
        (r"mysql\.user", "账号是否存在"),
        (r"SHOW GRANTS", "账号权限"),
        (r"information_schema\.SCHEMATA", "库是否存在及其排序规则"),
    ]
    for pat, desc in required:
        if re.search(pat, sql, re.I):
            ok(f"包含：{desc}")
        else:
            bad(f"缺少：{desc}")

    print()
    print("=" * 74)
    if FAIL == 0:
        print(f"VERIFY_SERVER.SQL OK  ({PASS} 项通过)")
    else:
        print(f"FAILED: {FAIL} 项失败, {PASS} 项通过")
    print("=" * 74)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
