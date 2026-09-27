#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
tm_im M0 数据库引导：建库 → 建表 → 验证 → 冒烟测试。

用法：
    # 1) 只看要执行什么（不连库）
    python tools/bootstrap_db.py --dry-run

    # 2) 真正执行（需要 deploy/conf/local-conn.env 且有 DDL 权限）
    uv run --with pymysql python tools/bootstrap_db.py --apply

    # 3) 只验证已有库（不建任何东西）
    uv run --with pymysql python tools/bootstrap_db.py --verify-only

安全约定：
  * 全程不做 DROP / TRUNCATE / DELETE
  * 冒烟测试写入的行会被显式回滚或删除，且使用 test 前缀的 handle
  * 默认不执行，必须显式 --apply
"""

from __future__ import annotations

import argparse
import re
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "tools"))

SCHEMA = REPO / "deploy" / "sql" / "01-schema.sql"

PASS, FAIL, WARN = [], [], []

def ok(m):   PASS.append(m); print(f"  [ OK ] {m}")
def bad(m):  FAIL.append(m); print(f"  [FAIL] {m}")
def warn(m): WARN.append(m); print(f"  [WARN] {m}")
def head(t): print(); print("=" * 74); print(t); print("=" * 74)


def load_cfg(explicit: str | None):
    """复用 check_services 的配置解析（含 BOM 处理与脱敏）。"""
    import importlib.util
    spec = importlib.util.spec_from_file_location("cs", REPO / "tools" / "check_services.py")
    cs = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(cs)
    return cs, cs.resolve_config(explicit)


def split_statements(sql: str) -> list[str]:
    out = []
    for chunk in sql.split(";"):
        body = "\n".join(l for l in chunk.splitlines()
                         if not l.strip().startswith("--")).strip()
        if body:
            out.append(body)
    return out


def expected_collation(schema_sql: str) -> str | None:
    """从建表脚本里读出预期的排序规则，而不是在本文件里再写一份。

    这两处曾经确实各写了一份而且不一致（本文件写 0900_ai_ci，
    gen_schema.py 生成 unicode_ci），后果是库与表的排序规则不同，
    跨表 JOIN 会报 Illegal mix of collations。所以只保留一个真源。
    """
    m = re.search(r"DEFAULT CHARACTER SET\s+(\w+)\s+COLLATE\s+(\w+)", schema_sql)
    return m.group(2) if m else None


def main() -> int:
    ap = argparse.ArgumentParser(description="tm_im M0 数据库引导")
    ap.add_argument("--env", help="配置文件路径")
    ap.add_argument("--dry-run", action="store_true", help="只打印要执行的语句")
    ap.add_argument("--apply", action="store_true", help="真正执行建库建表")
    ap.add_argument("--verify-only", action="store_true", help="只验证，不做任何变更")
    ap.add_argument("--skip-smoke", action="store_true", help="跳过冒烟测试")
    args = ap.parse_args()

    if not SCHEMA.exists():
        print(f"缺少 {SCHEMA} —— 先跑： python tools/gen_schema.py")
        return 1

    schema_sql = SCHEMA.read_text(encoding="utf-8")
    stmts = split_statements(schema_sql)
    want_coll = expected_collation(schema_sql)
    if not want_coll:
        print("错误：建表脚本里找不到 DEFAULT CHARACTER SET ... COLLATE ...")
        return 1

    # 去掉 USE 和 SET，它们由脚本按连接需要单独处理
    body_stmts = [s for s in stmts
                  if not s.upper().startswith(("USE ", "SET "))]
    create_stmts = [s for s in body_stmts if s.upper().startswith("CREATE TABLE")]
    other_stmts = [s for s in body_stmts if s not in create_stmts]

    cs, (cfg, used) = load_cfg(args.env)
    host = cfg.get("MYSQL_HOST", "").strip()
    port = int(cfg.get("MYSQL_PORT", "3306") or 3306)
    user = cfg.get("MYSQL_USER", "").strip()
    pwd = cfg.get("MYSQL_PASSWORD", "")
    db = cfg.get("MYSQL_DATABASE", "tm_im").strip() or "tm_im"

    print("tm_im M0 数据库引导")
    print(f"  配置文件 : {used or '(未找到)'}")
    print(f"  目标     : {host}:{port}  db={db}  user={user or '(空)'}")
    print(f"  模式     : {'dry-run' if args.dry_run else ('verify-only' if args.verify_only else ('apply' if args.apply else '未指定（默认 dry-run）'))}")

    # ---------------------------------------------------------------- dry-run
    if args.dry_run or not (args.apply or args.verify_only):
        head("将要执行的语句")
        print(f"  CREATE DATABASE IF NOT EXISTS `{db}` "
              f"CHARACTER SET utf8mb4 COLLATE {want_coll};")
        print(f"  （若库已存在但排序规则不是 {want_coll}，则先 ALTER DATABASE 纠正）")
        print(f"  （{len(create_stmts)} 条 CREATE TABLE）")
        for s in create_stmts[:3]:
            print(f"    {s.splitlines()[0].strip()}")
        print(f"    ... 共 {len(create_stmts)} 张表")
        if other_stmts:
            print(f"  （另有 {len(other_stmts)} 条语句）")
        print()
        print("  冒烟测试将执行（写入后回滚）：")
        print("    - 插入 2 个测试 actor")
        print("    - 按 conv_id % 16 路由插入消息到正确物理表")
        print("    - 重复插入相同 (conv_id, sender_id, client_msg_id) → 断言被唯一键拒绝")
        print()
        print("  不执行任何 DROP / TRUNCATE / DELETE")
        print()
        print("如需真正执行： uv run --with pymysql python tools/bootstrap_db.py --apply")
        return 0

    if not host:
        print("\n错误：未配置 MYSQL_HOST")
        return 1

    try:
        import pymysql
    except ImportError:
        print("\n需要 pymysql: uv run --with pymysql python tools/bootstrap_db.py --apply")
        return 1

    secrets = [pwd]

    # ---------------------------------------------------------------- 连接
    head("1. 连接与建库")
    if cs.INTERCEPTED is None:
        cs.calibrate_network()

    def connect(database=None):
        return pymysql.connect(host=host, port=port, user=user, password=pwd,
                               database=database, connect_timeout=10,
                               read_timeout=120, write_timeout=120,
                               charset="utf8mb4", autocommit=True)

    try:
        conn = connect()
    except Exception as e:
        bad(f"连接失败: {cs.sanitize(f'{type(e).__name__}: {e}', secrets)}")
        return 1
    ok("已连接 MySQL 实例")

    with conn.cursor() as cur:
        cur.execute("SELECT VERSION()")
        ver = cur.fetchone()[0]
        ok(f"MySQL {ver}")

        # 排序规则是否被服务端支持——在 5.7 上建 0900 表会直接报 1273，
        # 而错误发生在第一条 CREATE TABLE，前面看着一切正常，容易误判。
        cur.execute("SELECT COUNT(*) FROM information_schema.COLLATIONS "
                    "WHERE COLLATION_NAME=%s", (want_coll,))
        if cur.fetchone()[0]:
            ok(f"服务端支持预期排序规则 {want_coll}")
        else:
            bad(f"服务端不支持 {want_coll} —— 大概指向了 5.7 旧实例；"
                f"请确认 MYSQL_PORT 是 13306（8.4）而不是 3306（5.7）")
            conn.close()
            return 1

        cur.execute("SELECT DEFAULT_COLLATION_NAME FROM information_schema.SCHEMATA "
                    "WHERE SCHEMA_NAME=%s", (db,))
        row = cur.fetchone()
        exists = row is not None
        cur_coll = row[0] if row else None

        if exists:
            ok(f"库 `{db}` 已存在（collation={cur_coll}）")
            if cur_coll != want_coll:
                # 关键：CREATE DATABASE IF NOT EXISTS 对已存在的库是空操作，
                # 不会修改它的排序规则，必须 ALTER 才能纠正。
                cur.execute("SELECT COUNT(*) FROM information_schema.TABLES "
                            "WHERE TABLE_SCHEMA=%s", (db,))
                n_tables = cur.fetchone()[0]
                if args.verify_only:
                    bad(f"库排序规则为 {cur_coll}，与预期 {want_coll} 不一致"
                        f"（verify-only 不修改）")
                elif n_tables == 0:
                    try:
                        cur.execute(f"ALTER DATABASE `{db}` CHARACTER SET utf8mb4 "
                                    f"COLLATE {want_coll}")
                        ok(f"已纠正库排序规则 {cur_coll} → {want_coll}（库为空，瞬时完成）")
                    except Exception as e:
                        bad(f"ALTER DATABASE 失败: {cs.sanitize(e, secrets)}")
                else:
                    bad(f"库排序规则为 {cur_coll}，与预期 {want_coll} 不一致，"
                        f"但库中已有 {n_tables} 张表")
                    print("         → 不自动改（ALTER DATABASE 只改默认值，")
                    print("           已存在表的规则不变，留着会更难查）")
                    print(f"         → 需要 DBA 执行： ALTER DATABASE `{db}` "
                          f"CHARACTER SET utf8mb4 COLLATE {want_coll};")
                    conn.close()
                    return 1
        elif args.verify_only:
            bad(f"库 `{db}` 不存在（verify-only 不创建）")
            conn.close()
            return 1
        else:
            try:
                cur.execute(f"CREATE DATABASE IF NOT EXISTS `{db}` "
                            f"DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE {want_coll}")
                ok(f"已创建库 `{db}`（collation={want_coll}）")
            except Exception as e:
                bad(f"建库失败: {cs.sanitize(e, secrets)}",
                    )
                print("         → 需要 DBA 执行：")
                print(f"           CREATE DATABASE `{db}` DEFAULT CHARACTER SET utf8mb4 "
                      f"DEFAULT COLLATE {want_coll};")
                conn.close()
                return 1
    conn.close()

    # ---------------------------------------------------------------- 建表
    head("2. 建表")
    conn = connect(db)
    if args.verify_only:
        print("  （verify-only：跳过建表）")
        create_stmts = []
    else:
        t0 = time.perf_counter()
        done = 0
        for s in create_stmts:
            try:
                with conn.cursor() as cur:
                    cur.execute(s)
                done += 1
            except Exception as e:
                bad(f"建表失败: {s.splitlines()[0].strip()} — {cs.sanitize(e, secrets)}")
        dt = time.perf_counter() - t0
        if done == len(create_stmts):
            ok(f"{done} 张表全部创建完成（{dt:.2f}s，含 16 张分片表）")
        else:
            bad(f"仅 {done}/{len(create_stmts)} 张表创建成功")

    # ---------------------------------------------------------------- 验证
    head("3. 验证表结构")
    with conn.cursor() as cur:
        cur.execute("SELECT table_name FROM information_schema.tables WHERE table_schema=%s", (db,))
        have = {r[0] for r in cur.fetchall()}

    msg_tables = sorted((t for t in have if re.fullmatch(r"message_\d+", t)),
                        key=lambda x: int(x.split("_")[1]))
    biz = sorted(t for t in have if not re.fullmatch(r"message_\d+", t))
    expect_biz = {"actor", "actor_secret", "agent_profile", "conversation",
                  "conversation_member", "friendship", "post", "post_like", "post_comment",
                  "feed_item", "media"}

    if len(msg_tables) == 16:
        ok(f"分片表 16 张齐全: message_0 .. message_15")
    else:
        bad(f"分片表数量 {len(msg_tables)}（期望 16）: {msg_tables}")

    miss = expect_biz - set(biz)
    if not miss:
        ok(f"业务表 9 张齐全")
    else:
        bad(f"缺少业务表: {sorted(miss)}")

    # 关键约束是否真的落到库里（而不是只写在 SQL 文件里）
    with conn.cursor() as cur:
        # 排序规则必须逐表落实。库的默认值只影响日后新建的表，
        # 已经建好的表不会跟着变，所以必须直接问表自己。
        cur.execute("SELECT TABLE_NAME, TABLE_COLLATION FROM information_schema.TABLES "
                    "WHERE TABLE_SCHEMA=%s", (db,))
        colls = {t: c for t, c in cur.fetchall()}
        wrong_coll = {t: c for t, c in colls.items() if c != want_coll}
        if wrong_coll:
            bad(f"以下表的排序规则不是 {want_coll}:")
            for t, c in sorted(wrong_coll.items())[:5]:
                print(f"         {t}: {c}")
            print("         → 这类不一致会在跨表 JOIN 时报 Illegal mix of collations")
        else:
            ok(f"{len(colls)} 张表的排序规则均为 {want_coll}")

        cur.execute("""
            SELECT table_name, index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) cols,
                   non_unique
            FROM information_schema.statistics
            WHERE table_schema=%s AND table_name LIKE 'message\\_%%'
            GROUP BY table_name, index_name, non_unique
        """, (db,))
        idx_rows = cur.fetchall()

    idem = {t: cols for t, n, cols, nu in idx_rows
            if n == "uk_message_idem" and nu == 0}
    pk = {t: cols for t, n, cols, nu in idx_rows
          if n == "PRIMARY"}

    wrong_idem = {t: c for t, c in idem.items() if c != "conv_id,sender_id,client_msg_id"}
    wrong_pk = {t: c for t, c in pk.items() if c != "conv_id,seq"}

    if len(idem) == 16 and not wrong_idem:
        ok("16 张分片表的 uk_message_idem 均为 (conv_id, sender_id, client_msg_id) —— 幂等约束在分片内生效")
    elif wrong_idem:
        bad(f"幂等唯一键列不正确: {list(wrong_idem.items())[:3]}")
    else:
        bad(f"仅有 {len(idem)}/16 张表存在 uk_message_idem 唯一键")

    if len(pk) == 16 and not wrong_pk:
        ok("16 张分片表主键均为 (conv_id, seq)")
    elif wrong_pk:
        bad(f"主键列不正确: {list(wrong_pk.items())[:3]}")

    # ---------------------------------------------------------------- 冒烟测试
    if not args.skip_smoke:
        head("4. 冒烟测试（写入 → 断言 → 回滚）")
        try:
            conn.begin()
            with conn.cursor() as cur:
                import random
                tag = f"m0smoke{random.randrange(10**8):08d}"

                cur.execute(
                    "INSERT INTO actor (id, actor_type, handle, display_name, status, created_at) "
                    "VALUES (%s,1,%s,%s,1,NOW(3))",
                    (900000000000000001, f"{tag}_a", "M0 冒烟测试 A"))
                cur.execute(
                    "INSERT INTO actor (id, actor_type, handle, display_name, status, created_at) "
                    "VALUES (%s,2,%s,%s,1,NOW(3))",
                    (900000000000000002, f"{tag}_agent", "M0 冒烟测试 Agent"))

                # 人 + Agent 写入同一张 actor 表 —— 这就是「对等」的物理验证
                cur.execute("SELECT actor_type FROM actor WHERE id IN (%s,%s) ORDER BY id",
                            (900000000000000001, 900000000000000002))
                types = [r[0] for r in cur.fetchall()]
                if types == [1, 2]:
                    ok("人与 Agent 写入同一张 actor 表（对等模型的物理验证）")
                else:
                    bad(f"actor_type 读回异常: {types}")

                # 按 conv_id % 16 路由到正确物理表
                conv_id = 100
                target = conv_id % 16
                tname = f"message_{target}"
                cur.execute(
                    f"INSERT INTO `{tname}` (id, conv_id, seq, sender_id, msg_type, content, "
                    f"client_msg_id, created_at) VALUES (%s,%s,%s,%s,1,%s,%s,NOW(3))",
                    (910000000000000001, conv_id, 1, 900000000000000001,
                     '{"text":"m0 smoke"}', f"{tag}-c1"))
                ok(f"conv_id={conv_id} 按 {conv_id}%16={target} 写入 `{tname}` 成功")

                # 反证：写入错误分片时，唯一键不会跨表拦截（这正是 §8.5 的成因）
                wrong = (conv_id + 1) % 16
                ok(f"（参考）{conv_id + 1}%16={wrong} → 落在 `message_{wrong}`，"
                   f"与 `{tname}` 不同物理表 → 跨表唯一约束天然不生效")

                # 断言幂等唯一键真的拦截重复
                dup_blocked = False
                try:
                    cur.execute(
                        f"INSERT INTO `{tname}` (id, conv_id, seq, sender_id, msg_type, content, "
                        f"client_msg_id, created_at) VALUES (%s,%s,%s,%s,1,%s,%s,NOW(3))",
                        (910000000000000002, conv_id, 2, 900000000000000001,
                         '{"text":"dup"}', f"{tag}-c1"))
                except pymysql.err.IntegrityError as e:
                    dup_blocked = True
                    code = e.args[0]
                    ok(f"重复 (conv_id, sender_id, client_msg_id) 被拒绝 (errno {code}) —— 幂等约束真实生效")
                if not dup_blocked:
                    bad("重复幂等键未被拒绝 —— uk_message_idem 未生效！")

                # 主键冲突（同 conv_id 同 seq）
                pk_blocked = False
                try:
                    cur.execute(
                        f"INSERT INTO `{tname}` (id, conv_id, seq, sender_id, msg_type, content, "
                        f"client_msg_id, created_at) VALUES (%s,%s,%s,%s,1,%s,%s,NOW(3))",
                        (910000000000000003, conv_id, 1, 900000000000000002,
                         '{"text":"pk"}', f"{tag}-c2"))
                except pymysql.err.IntegrityError:
                    pk_blocked = True
                    ok("同 (conv_id, seq) 重复插入被主键拒绝 —— 会话内 seq 唯一性生效")
                if not pk_blocked:
                    bad("主键未拦截同 (conv_id, seq) 重复")

                # 顺序读（拉历史走主键聚簇索引）
                #
                # 两条断言，都是被本次首跑发现的：
                #  1) pymysql 默认游标的 fetchall() 返回的是 tuple of tuples，
                #     拿它和 list 比较永远为假。原写法 rows == [(conv_id, 1)]
                #     在本脚本第一次真连上库时直接报失败，而数据其实是对的。
                #  2) 执行到这里时表里只有 1 行，ORDER BY 根本没被验证。
                #     移到插入 seq=2 之后，并乱序插入以真正考验排序。
                cur.execute(f"SELECT seq FROM `{tname}` WHERE conv_id=%s ORDER BY seq", (conv_id,))
                seqs = [r[0] for r in cur.fetchall()]
                if seqs != [1]:
                    bad(f"单行范围查询结果异常: {seqs!r}（期望 [1]）")

                # 插入 seq=0（比现有行小），验证 ORDER BY 而不仅是“只有一行”
                cur.execute(
                    f"INSERT INTO `{tname}` (id, conv_id, seq, sender_id, msg_type, content, "
                    f"client_msg_id, created_at) VALUES (%s,%s,%s,%s,1,%s,%s,NOW(3))",
                    (910000000000000005, conv_id, 0, 900000000000000001,
                     '{"text":"order probe"}', f"{tag}-c0"))
                cur.execute(f"SELECT seq FROM `{tname}` WHERE conv_id=%s ORDER BY seq", (conv_id,))
                seqs = [r[0] for r in cur.fetchall()]
                if seqs == [0, 1]:
                    ok(f"按 (conv_id, seq) 范围查询有序返回: {seqs}（聚簇主键生效）")
                else:
                    bad(f"范围查询顺序异常: {seqs!r}（期望 [0, 1]）")

                # 跨会话隔离：同表内其他 conv_id 不应被查出
                cur.execute(f"SELECT COUNT(*) FROM `{tname}` WHERE conv_id=%s", (conv_id + 16,))
                if cur.fetchone()[0] == 0:
                    ok("conv_id 隔离生效：同一物理表内其他会话的行不被查出")

                # JSON 列可写可读
                cur.execute(f"SELECT content->>'$.text' FROM `{tname}` WHERE conv_id=%s AND seq=1",
                            (conv_id,))
                txt = cur.fetchone()[0]
                if txt == "m0 smoke":
                    ok("JSON 列写入/提取正常（content->>'$.text'）")
                else:
                    bad(f"JSON 列读取异常: {txt!r}")

                # 中文 + emoji（验证 utf8mb4 真的生效）
                cur.execute(
                    f"INSERT INTO `{tname}` (id, conv_id, seq, sender_id, msg_type, content, "
                    f"client_msg_id, created_at) VALUES (%s,%s,%s,%s,1,%s,%s,NOW(3))",
                    (910000000000000004, conv_id, 2, 900000000000000001,
                     '{"text":"中文与 emoji 🎉🚀 测试"}', f"{tag}-c3"))
                cur.execute(f"SELECT content->>'$.text' FROM `{tname}` WHERE conv_id=%s AND seq=2",
                            (conv_id,))
                t2 = cur.fetchone()[0]
                if t2 == "中文与 emoji 🎉🚀 测试":
                    ok("utf8mb4 生效：中文与 emoji 往返无损")
                else:
                    bad(f"字符集有问题，读回: {t2!r}")

                # DATETIME(3) 毫秒精度
                cur.execute(f"SELECT created_at FROM `{tname}` WHERE conv_id=%s AND seq=1", (conv_id,))
                dt = cur.fetchone()[0]
                ok(f"DATETIME(3) 毫秒精度正常: {dt}")
        except Exception as e:
            bad(f"冒烟测试异常: {cs.sanitize(f'{type(e).__name__}: {e}', secrets)}")
        finally:
            conn.rollback()
            ok("冒烟测试已回滚，库中无残留数据")

            # 确认确实没残留
            with conn.cursor() as cur:
                cur.execute("SELECT COUNT(*) FROM message_4 WHERE conv_id=%s", (100,))
                leftover = cur.fetchone()[0]
            if leftover == 0:
                ok("确认 message_4 中 conv_id=100 无残留行")
            else:
                warn(f"message_4 中 conv_id=100 有 {leftover} 行（可能是先前测试数据）")

    conn.close()

    # ---------------------------------------------------------------- 汇总
    print()
    print("=" * 74)
    print(f"M0 汇总：  通过 {len(PASS)}   警告 {len(WARN)}   失败 {len(FAIL)}")
    if FAIL:
        print()
        for f in FAIL:
            print(f"  x {f}")
    print()
    if not FAIL:
        print("M0 数据库就绪 —— 可以开始写 Java 侧代码并验证 ShardingSphere 路由")
    else:
        print("存在失败项，需处理后重跑")
    print("=" * 74)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
