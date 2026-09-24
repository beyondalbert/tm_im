#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""探测 MySQL 实例能力与排序规则语义，为建库选 COLLATE 提供依据。

为什么需要它
--------------------------------------------------------------------------
`DEFAULT CHARSET=utf8mb4` 而不写 COLLATE 时，排序规则取"该字符集的默认值"，
而这个默认值随大版本变化：5.7 是 utf8mb4_general_ci，8.0+ 是 utf8mb4_0900_ai_ci。
先前的 DDL 选 utf8mb4_unicode_ci，理由是"5.7 与 8.0 上都有"——那是为兼容
一个已经不再使用的 5.7 实例而做的妥协。

更关键的是：两种 _ci 排序规则的**等价语义并不相同**，而 handle 上有唯一索引，
等价语义直接决定"哪些 handle 会被判为重复"。例如 strasse 与 straße
在 utf8mb4_unicode_ci 下相等，在 utf8mb4_0900_ai_ci 下不等。
这不能靠记忆判断，必须在目标实例上实测。

用法
    uv run --with pymysql python tools/probe_collation.py
    uv run --with pymysql python tools/probe_collation.py --json
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

CANDIDATE_COLLATIONS = [
    "utf8mb4_general_ci",       # 5.7 默认
    "utf8mb4_unicode_ci",       # 5.7/8.0 都有，本仓库原选
    "utf8mb4_0900_ai_ci",       # 8.0+ 默认
    "utf8mb4_0900_as_cs",       # 8.0+，重音与大小写都敏感
    "utf8mb4_bin",              # 逐字节
]

# (表达式, 说明, 期望语义)
EQUIVALENCE_CASES = [
    ("'@alice' = '@ALICE'", "handle 仅大小写不同", "应相等（handle 大小写不敏感）"),
    ("'@alice' = '@alic3'", "handle 实际不同", "应不等"),
    ("'cafe' = 'café'", "重音差异", "不宜相等（cafe 与 café 是不同名字）"),
    ("'strasse' = 'straße'", "ß 与 ss", "不宜相等"),
    ("'ae' = 'æ'", "æ 与 ae", "不宜相等"),
    ("'a' = 'a '", "尾随空格", "取决于 PAD 属性"),
]


def load_cfg(explicit: str | None):
    """复用 check_services 的配置解析（含 BOM 处理与脱敏）。

    注意 resolve_config 返回的是 (cfg, path) 二元组，不是单个 dict。
    """
    spec = importlib.util.spec_from_file_location("cs", REPO / "tools" / "check_services.py")
    cs = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(cs)
    cfg, _path = cs.resolve_config(explicit)
    return cfg


def main() -> int:
    ap = argparse.ArgumentParser(description="MySQL 能力与排序规则语义探测")
    ap.add_argument("--env", help="配置文件路径（默认 deploy/conf/local-conn.env）")
    ap.add_argument("--json", action="store_true", help="以 JSON 输出")
    args = ap.parse_args()

    try:
        import pymysql
    except ImportError:
        print("需要 pymysql：uv run --with pymysql python tools/probe_collation.py")
        return 2

    cfg = load_cfg(args.env)
    conn = pymysql.connect(
        host=cfg["MYSQL_HOST"], port=int(cfg.get("MYSQL_PORT", "3306") or 3306),
        user=cfg["MYSQL_USER"], password=cfg["MYSQL_PASSWORD"], charset="utf8mb4",
        autocommit=True, connect_timeout=10,
        ssl_disabled=str(cfg.get("MYSQL_USE_SSL", "false")).lower() != "true",
    )

    report: dict = {"target": "{}:{}".format(cfg["MYSQL_HOST"], cfg.get("MYSQL_PORT"))}
    with conn.cursor() as cur:
        cur.execute("SELECT VERSION(), @@character_set_server, @@collation_server, "
                    "@@lower_case_table_names, @@sql_mode, @@transaction_isolation")
        v = cur.fetchone()
        report["version"] = v[0]
        report["server_charset"] = v[1]
        report["server_collation"] = v[2]
        report["lower_case_table_names"] = v[3]
        report["sql_mode"] = v[4]
        report["isolation"] = v[5]

        cur.execute("SELECT @@max_connections, @@innodb_buffer_pool_size, "
                    "@@innodb_default_row_format, @@default_storage_engine")
        v = cur.fetchone()
        report["max_connections"] = v[0]
        report["innodb_buffer_pool_size"] = v[1]
        report["innodb_default_row_format"] = v[2]
        report["default_storage_engine"] = v[3]

        major = tuple(int(x) for x in report["version"].split("-")[0].split(".")[:2])

        # 支持的排序规则
        cur.execute("SELECT COLLATION_NAME FROM information_schema.COLLATIONS "
                    "WHERE COLLATION_NAME IN ({})".format(
                        ",".join(["%s"] * len(CANDIDATE_COLLATIONS))),
                    CANDIDATE_COLLATIONS)
        supported = {r[0] for r in cur.fetchall()}
        report["supported_collations"] = sorted(supported)

        # 等价语义实测
        semantics: dict[str, dict[str, bool]] = {}
        for coll in CANDIDATE_COLLATIONS:
            if coll not in supported:
                continue
            row: dict[str, bool] = {}
            for expr, label, _ in EQUIVALENCE_CASES:
                cur.execute("SELECT {}".format(expr.replace("=", "COLLATE " + coll + " =", 1)))
                row[label] = bool(cur.fetchone()[0])
            semantics[coll] = row
        report["equivalence"] = semantics

        # handle 唯一性含义：在每种子下，哪些"看起来不同"的 handle 会被判重复
        risky: dict[str, list[str]] = {}
        for coll, row in semantics.items():
            pairs = {
                "handle 仅大小写不同": "@alice vs @ALICE",
                "重音不同": "cafe vs café",
                "ß 与 ss": "strasse vs straße",
                "æ 与 ae": "ae vs æ",
            }
            same = []
            for label, pair in pairs.items():
                if row.get(label):
                    same.append(pair)
            # 大小写相同是期望行为，不算风险
            risky[coll] = [p for p in same if "大小写" not in p]
        report["handle_collision_risk"] = risky

        # 现有 schema
        cur.execute("SELECT SCHEMA_NAME, DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME "
                    "FROM information_schema.SCHEMATA WHERE SCHEMA_NAME NOT IN "
                    "('mysql','information_schema','performance_schema','sys') ORDER BY 1")
        report["schemas"] = [{"name": r[0], "charset": r[1], "collation": r[2]}
                             for r in cur.fetchall()]

        db = cfg.get("MYSQL_DATABASE", "tm_im")
        # 注意：pymysql 用 %s 做参数占位，SQL 里字面的 % 必须写成 %%
        cur.execute("SELECT COUNT(*), COALESCE(SUM(TABLE_NAME LIKE 'message\\_%%'), 0) "
                    "FROM information_schema.TABLES WHERE TABLE_SCHEMA=%s", (db,))
        r = cur.fetchone()
        # 库是否存在要看 SCHEMATA，不能拿表数量当依据：
        # 一个已创建但还没建表的库会被误报成“不存在”，进而误导建库决策。
        cur.execute("SELECT DEFAULT_COLLATION_NAME FROM information_schema.SCHEMATA "
                    "WHERE SCHEMA_NAME=%s", (db,))
        srow = cur.fetchone()
        report["target_db"] = {
            "name": db,
            "exists": srow is not None,
            "collation": srow[0] if srow else None,
            "tables": r[0],
            "message_shards": int(r[1]),
        }
    conn.close()

    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0

    print("=" * 78)
    print("MySQL 能力与排序规则语义探测")
    print("=" * 78)
    print("  目标                {}".format(report["target"]))
    print("  服务端版本          {}".format(report["version"]))
    print("  服务端默认字符集    {} / {}".format(report["server_charset"],
                                               report["server_collation"]))
    print("  lower_case_tables   {}".format(report["lower_case_table_names"]))
    print("  默认引擎 / 行格式   {} / {}".format(report["default_storage_engine"],
                                               report["innodb_default_row_format"]))
    print("  隔离级别            {}".format(report["isolation"]))
    print("  max_connections     {}  (buffer_pool {} MB)".format(
        report["max_connections"], report["innodb_buffer_pool_size"] // 1024 // 1024))

    print()
    print("SQL_MODE")
    modes = set(report["sql_mode"].split(","))
    for flag in ("STRICT_TRANS_TABLES", "ONLY_FULL_GROUP_BY", "NO_ZERO_DATE",
                 "NO_ENGINE_SUBSTITUTION", "ANSI_QUOTES"):
        print("  {:<24} {}".format(flag, "开" if flag in modes else "关"))

    print()
    print("=" * 78)
    print("排序规则等价语义（实测）")
    print("=" * 78)
    labels = [c[1] for c in EQUIVALENCE_CASES]
    print("  {:<22} {}".format("排序规则", "  ".join("{:<10}".format(l[:10]) for l in labels)))
    for coll in CANDIDATE_COLLATIONS:
        row = semantics.get(coll)
        if row is None:
            print("  {:<22} （实例不支持）".format(coll))
            continue
        cells = ["相等" if row[l] else "不等" for l in labels]
        print("  {:<22} {}".format(coll, "  ".join("{:<10}".format(c) for c in cells)))

    print()
    print("handle 唯一索引的实际后果（除大小写外会被判重复的组合）")
    for coll, pairs in risky.items():
        if pairs:
            print("  {:<22} {}".format(coll, "; ".join(pairs)))
        else:
            print("  {:<22} 无（只有纯大小写会判重复，符合预期）".format(coll))

    print()
    print("=" * 78)
    print("现有库")
    print("=" * 78)
    for s in report["schemas"]:
        print("  {:<24} charset={:<10} collation={}".format(s["name"], s["charset"], s["collation"]))
    if not report["schemas"]:
        print("  （无用户库）")
    t = report["target_db"]
    print()
    print("  目标库 {!r}: {}".format(
        t["name"],
        "已存在，collation={}".format(t["collation"]) if t["exists"] else "不存在"))
    print("  表数量 {}，其中 message_* {} 张".format(t["tables"], t["message_shards"]))
    if t["exists"] and t["tables"] == 0:
        print("  提示：库已存在但没有表，说明此前用服务端默认排序规则建过库。")
        print("        CREATE DATABASE IF NOT EXISTS 不会修改已存在库的排序规则，")
        print("        需要 ALTER DATABASE 才能纠正（0 张表时该操作是瞬间的）。")

    return 0


if __name__ == "__main__":
    sys.exit(main())
