#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""直连 MySQL 列出库内表（诊断用，凭据来自 deploy/conf/local-conn.env，不回显口令）。

用法：
    uv run --with pymysql --no-progress python tools/probe_tables.py
"""
from __future__ import annotations

import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent


def load_env() -> dict[str, str]:
    out: dict[str, str] = {}
    for raw in (REPO / "deploy" / "conf" / "local-conn.env").read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def main() -> int:
    import pymysql

    env = load_env()
    conn = pymysql.connect(host=env["MYSQL_HOST"], port=int(env["MYSQL_PORT"]),
                           user=env["MYSQL_USER"], password=env["MYSQL_PASSWORD"],
                           database=env["MYSQL_DATABASE"], charset="utf8mb4")
    try:
        with conn.cursor() as cur:
            cur.execute("SHOW TABLES")
            tables = [r[0] for r in cur.fetchall()]
            print("库 {} 共 {} 张表".format(env["MYSQL_DATABASE"], len(tables)))
            print("非 message* 表:", sorted(t for t in tables if not t.startswith("message")))
            cur.execute("SELECT @@lower_case_table_names, @@version")
            print("lower_case_table_names, version =", cur.fetchone())
            cur.execute("SELECT COUNT(*) FROM conversation")
            print("conversation 行数 =", cur.fetchone()[0])
    finally:
        conn.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
