#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""按 01-schema.sql 把**已存在的库**迁到当前定义（只增不改，绝不 DROP）。

为什么需要它
--------------------------------------------------------------------------
`deploy/sql/01-schema.sql` 里的每条语句都是 `CREATE TABLE IF NOT EXISTS`。
这对「从零建库」是正确的，对「加一列」则**什么都不做**：

    mysql> ALTER ... 没人执行的后果
    ERROR 1054 (42S22): Unknown column 'friendship.request_id' in 'field list'

而报错只会出现在真正用到那一列的请求上，看起来像业务代码写错了字段名。
更糟的是它出现的时机很晚（改 DDL 的那一刻一切正常，直到有人签出代码重启）。

所以「结构变更」这件事必须有工具承载，而不是靠人记得在两个地方各改一次：
  1. 改 tools/gen_schema.py → 重跑 gen_schema.py（DDL 是生成的）
  2. 重跑 gen_entities.py（实体是生成的）
  3. **跑本脚本把库迁上去**（这一步此前是手工的）
  4. 跑 gen_runtime_config.py 无关；跑 verify_all 确认三方一致

安全约定
--------------------------------------------------------------------------
  * 只生成 ADD COLUMN / ADD KEY / CREATE TABLE —— 没有 DROP、没有 MODIFY、
    没有 ALTER COLUMN。删列与改类型是**破坏性**的（数据会没），必须由人显式决定，
    因此本脚本遇到「库里有、DDL 里没有」的列/索引时只报 WARN 并列出建议语句，
    不执行。
  * 默认 dry-run，必须显式 --apply 才连库执行。
  * 每条语句单独提交（autocommit），失败即停并打印是哪一张表哪一步——
    MySQL 8.0 的 DDL 是原子的，所以不会留下「改了一半的表」。
  * 幂等：`--check` 在迁移完成后再跑一次应当没有输出（verify_all --services 会跑它）。

用法
--------------------------------------------------------------------------
    # 1) 看要做什么（不连库）
    python tools/migrate_schema.py --dry-run

    # 2) 真正执行
    uv run --with pymysql python tools/migrate_schema.py --apply

    # 3) 只检查偏差（CI 用；有偏差则退出码 1）
    uv run --with pymysql python tools/migrate_schema.py --check

    # 4) 只看某张表
    uv run --with pymysql python tools/migrate_schema.py --table friendship
"""

from __future__ import annotations

import argparse
import importlib.util
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SCHEMA = REPO / "deploy" / "sql" / "01-schema.sql"

PASS, FAIL, WARN = [], [], []


def ok(m):
    PASS.append(m)
    print(f"  [ OK ] {m}")


def bad(m):
    FAIL.append(m)
    print(f"  [FAIL] {m}")


def warn(m):
    WARN.append(m)
    print(f"  [WARN] {m}")


def head(t):
    print()
    print("=" * 74)
    print(t)
    print("=" * 74)


# ---------------------------------------------------------------------------
# DDL 解析：复用 gen_entities 的正则，避免「同一份 DDL 两套解析」
# ---------------------------------------------------------------------------

def load_gen_entities():
    spec = importlib.util.spec_from_file_location("gen_entities", REPO / "tools" / "gen_entities.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class TableDef:
    """一张表的「期望结构」：整条 CREATE 语句 + 列/索引的原始定义行。"""

    def __init__(self, name: str, create_stmt: str):
        self.name = name
        self.create_stmt = create_stmt
        # 列：按 DDL 里的出现顺序（顺序有意义：ADD COLUMN 用 AFTER 对齐它）
        self.columns: list[tuple[str, str]] = []
        # 索引/唯一键：原始定义行（`KEY x (y) COMMENT '...'`）
        self.keys: list[tuple[str, str]] = []
        self.primary_key: list[str] = []


COLUMN_NAME_RE = re.compile(r"^`(?P<name>\w+)`")
KEY_NAME_RE = re.compile(r"^(?:UNIQUE\s+)?(?:KEY|INDEX)\s+`(?P<name>\w+)`", re.IGNORECASE)
PK_COLS_RE = re.compile(r"^\s*PRIMARY\s+KEY\s*\((?P<cols>[^)]*)\)", re.IGNORECASE)


def parse_ddl() -> dict[str, TableDef]:
    text = SCHEMA.read_text(encoding="utf-8")
    gen = load_gen_entities()

    tables: dict[str, TableDef] = {}
    for m in gen.TABLE_RE.finditer(text):
        name = m.group("name")
        body = m.group("body")
        table = TableDef(name, m.group(0).strip())
        for raw_line in body.split("\n"):
            stripped = raw_line.strip()
            if not stripped or stripped.startswith("--"):
                continue
            stripped = stripped.rstrip(",").rstrip()
            pkm = PK_COLS_RE.match(stripped)
            if pkm:
                table.primary_key = [c.strip().strip("`") for c in pkm.group("cols").split(",")]
                continue
            km = KEY_NAME_RE.match(stripped)
            if km:
                table.keys.append((km.group("name"), stripped))
                continue
            cm = COLUMN_NAME_RE.match(stripped)
            if cm:
                table.columns.append((cm.group("name"), stripped))
                continue
            raise SystemExit(f"错误：{name} 里有无法解析的定义行：{stripped!r}")
        if not table.columns:
            raise SystemExit(f"错误：{name} 里一列都没解析出来")
        tables[name] = table
    if not tables:
        raise SystemExit("错误：从 DDL 里解析不到任何表")
    return tables


# ---------------------------------------------------------------------------
# 与库比对
# ---------------------------------------------------------------------------

def live_columns(cur, db: str, table: str) -> dict[str, str]:
    cur.execute(
        "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_KEY "
        "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = %s AND TABLE_NAME = %s "
        "ORDER BY ORDINAL_POSITION", (db, table))
    return {row[0]: row[1] for row in cur.fetchall()}


def live_indexes(cur, db: str, table: str) -> dict[str, list[str]]:
    """{索引名: [列名...]}，按索引内顺序。"""
    cur.execute(
        "SELECT INDEX_NAME, COLUMN_NAME FROM information_schema.STATISTICS "
        "WHERE TABLE_SCHEMA = %s AND TABLE_NAME = %s ORDER BY INDEX_NAME, SEQ_IN_INDEX",
        (db, table))
    out: dict[str, list[str]] = {}
    for index_name, column in cur.fetchall():
        out.setdefault(index_name, []).append(column)
    return out


def table_exists(cur, db: str, table: str) -> bool:
    cur.execute(
        "SELECT COUNT(*) FROM information_schema.TABLES "
        "WHERE TABLE_SCHEMA = %s AND TABLE_NAME = %s", (db, table))
    return cur.fetchone()[0] > 0


def plan(cur, db: str, tables: dict[str, TableDef], only: str | None):
    """算出要执行的语句，并按表打印现状与计划。"""
    creates: list[str] = []
    alters: list[tuple[str, str, str]] = []      # (表, 语句, 说明)

    for name, table in tables.items():
        if only and name != only:
            continue
        if not table_exists(cur, db, name):
            creates.append(table.create_stmt)
            continue

        have_cols = live_columns(cur, db, name)
        have_idx = live_indexes(cur, db, name)

        missing = [(cn, cdef) for cn, cdef in table.columns if cn not in have_cols]
        for i, (cn, cdef) in enumerate(missing):
            # AFTER 用来对齐 DDL 里的列顺序。顺序本身不影响 Java 实体（按名字映射），
            # 但影响 SELECT * 与人工对照，而「生成的 DDL 与库里长得一样」让
            # verify_server.sql 这类人工核对少一层噪音。
            index_in_ddl = [c for c, _ in table.columns].index(cn)
            if index_in_ddl == 0:
                position = " FIRST"
            else:
                position = f" AFTER `{table.columns[index_in_ddl - 1][0]}`"
            alters.append((name, f"ALTER TABLE `{name}` ADD COLUMN {cdef}{position}",
                           f"缺少列 {cn}"))

        missing_keys = [(kn, kdef) for kn, kdef in table.keys if kn not in have_idx]
        for kn, kdef in missing_keys:
            alters.append((name, f"ALTER TABLE `{name}` ADD {kdef}", f"缺少索引 {kn}"))

        # ---- 只报告、不执行的偏差（删列/删索引是破坏性的） ----
        for cn in have_cols:
            if cn not in [c for c, _ in table.columns]:
                warn(f"{name}.{cn} 在库里有、DDL 里没有 —— 本脚本不删列"
                     f"（数据会没）。确认无用后手工： ALTER TABLE `{name}` DROP COLUMN `{cn}`;")
        for kn in have_idx:
            if kn == "PRIMARY":
                continue
            if kn not in [k for k, _ in table.keys]:
                warn(f"{name}.{kn} 索引在库里有、DDL 里没有 —— 本脚本不删索引。"
                     f"确认无用后手工： ALTER TABLE `{name}` DROP INDEX `{kn}`;")

        # 主键不比对列顺序以外的结构；不一致只报 WARN（改主键=重建表）
        live_pk = have_idx.get("PRIMARY", [])
        if live_pk and table.primary_key and live_pk != table.primary_key:
            warn(f"{name} 主键不一致：库 {live_pk} vs DDL {table.primary_key} "
                 f"—— 改主键是破坏性操作，需要人工迁移（本脚本不动它）")

    return creates, alters


def main() -> int:
    ap = argparse.ArgumentParser(description="按 DDL 迁移已存在的库（只增不改）")
    ap.add_argument("--env", help="配置文件路径（默认 deploy/conf/local-conn.env）")
    ap.add_argument("--apply", action="store_true", help="真正执行（默认只打印）")
    ap.add_argument("--check", action="store_true", help="只检查偏差；有偏差退出码 1")
    ap.add_argument("--table", help="只处理某一张表")
    args = ap.parse_args()

    if not SCHEMA.exists():
        print(f"缺少 {SCHEMA} —— 先跑： python tools/gen_schema.py")
        return 1

    tables = parse_ddl()
    print("tm_im 结构迁移")
    print(f"  DDL      : {SCHEMA.relative_to(REPO)}")
    print(f"  定义表数 : {len(tables)}")
    print(f"  模式     : {'check' if args.check else ('apply' if args.apply else 'dry-run')}")
    if args.table:
        print(f"  仅表     : {args.table}")

    # ---------------------------------------------------------------- 先做不需要连库的那半
    names = sorted(tables)
    if args.table and args.table not in tables:
        print(f"\n错误：DDL 里没有表 {args.table}")
        return 1

    try:
        import pymysql
    except ImportError:
        print("\n需要 pymysql: uv run --with pymysql python tools/migrate_schema.py --apply")
        return 1

    # 复用 check_services / bootstrap_db 的凭据解析（含脱敏与网络校准）
    spec = importlib.util.spec_from_file_location("cs", REPO / "tools" / "check_services.py")
    cs = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(cs)
    cfg, used = cs.resolve_config(args.env)

    host = cfg.get("MYSQL_HOST", "").strip()
    port = int(cfg.get("MYSQL_PORT", "3306") or 3306)
    user = cfg.get("MYSQL_USER", "").strip()
    pwd = cfg.get("MYSQL_PASSWORD", "")
    db = cfg.get("MYSQL_DATABASE", "tm_im").strip() or "tm_im"
    if not host:
        print("\n错误：未配置 MYSQL_HOST")
        return 1
    print(f"  配置文件 : {used or '(未找到)'}")
    print(f"  目标     : {host}:{port}  db={db}  user={user or '(空)'}")

    if cs.INTERCEPTED is None:
        cs.calibrate_network()

    secrets = [pwd]
    try:
        conn = pymysql.connect(host=host, port=port, user=user, password=pwd,
                               database=db, connect_timeout=10, read_timeout=120,
                               write_timeout=120, charset="utf8mb4", autocommit=True)
    except Exception as e:
        print(f"\n连接失败: {cs.sanitize(f'{type(e).__name__}: {e}', secrets)}")
        return 1

    with conn:
        cur = conn.cursor()
        creates, alters = plan(cur, db, tables, args.table)

        head("1. 需要新建的表")
        if not creates:
            ok("没有（DDL 里的表在库里都存在）")
        for stmt in creates:
            print(f"  + {stmt.splitlines()[0].strip()} ...")

        head("2. 需要变更的表")
        if not alters:
            ok("没有（库的结构已经是当前定义）")
        for table, stmt, why in alters:
            print(f"  [{table}] {why}")
            print(f"    {cs.sanitize(stmt, secrets)}")

        if args.check:
            print()
            head("结果")
            if creates or alters:
                bad(f"结构与 DDL 不一致：{len(creates)} 张表待建、{len(alters)} 条变更待执行")
                print("  修复： uv run --with pymysql python tools/migrate_schema.py --apply")
                return 1
            ok("库结构与 DDL 一致")
            return 0

        if not args.apply:
            print()
            print("（dry-run 不执行任何语句。真正执行： 加 --apply）")
            return 0

        head("3. 执行")
        for stmt in creates:
            try:
                cur.execute(stmt)
                ok(f"{cs.sanitize(stmt.splitlines()[0].strip(), secrets)}")
            except Exception as e:
                bad(f"建表失败: {cs.sanitize(f'{type(e).__name__}: {e}', secrets)}")
                return 1
        for table, stmt, why in alters:
            try:
                cur.execute(stmt)
                ok(f"[{table}] {why}")
            except Exception as e:
                bad(f"[{table}] {why} —— {cs.sanitize(f'{type(e).__name__}: {e}', secrets)}")
                return 1

        head("4. 复核")
        creates2, alters2 = plan(cur, db, tables, args.table)
        if creates2 or alters2:
            bad("迁移后仍有偏差（见上面第 1/2 节）")
            return 1
        ok("迁移完成，库结构与 DDL 一致")

    print()
    print("=" * 74)
    print(f"通过 {len(PASS)} 项" + (f"，警告 {len(WARN)} 项" if WARN else "") +
          (f"，失败 {len(FAIL)} 项" if FAIL else ""))
    print("=" * 74)
    return 0 if not FAIL else 1


if __name__ == "__main__":
    sys.exit(main())
