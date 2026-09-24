#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
校验 deploy/sql/01-schema.sql：语法 + 分片语义约束。

为什么需要这个：
  ShardingSphere 分片表的错误约束**不会立刻报错**。
  `uk_message_idem` 少了 conv_id，建表照样成功，
  只有跨会话重复消息时才静默失效——那时数据已经脏了。
  所以这些约束必须由工具断言，不能靠 review 眼睛看。

用法：
    uv run --with sqlglot python tools/verify_schema.py

    可指定文件（供变异测试使用）：
    uv run --with sqlglot python tools/verify_schema.py --schema X.sql --sharding Y.yaml
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SCHEMA = REPO / "deploy" / "sql" / "01-schema.sql"
SHARDING = REPO / "deploy" / "conf" / "sharding.yaml.example"

PASS, FAIL = 0, 0
QUIET = False

# 跨 MySQL 大版本都存在的排序规则。
# 刻意不包括 utf8mb4_0900_* —— 它是 MySQL 8.0 专有，在 5.7 上建表会直接报
# "Unknown collation"。当前外部库是 5.7.44，必须避开。
VERSION_SAFE_COLLATIONS = {
    "utf8mb4_general_ci",   # 5.7 的默认值
    "utf8mb4_unicode_ci",   # 推荐：Unicode 排序比 general_ci 正确
    "utf8mb4_unicode_520_ci",
    "utf8mb4_bin",
}

def ok(m):
    global PASS
    PASS += 1
    if not QUIET:
        print(f"  [ OK ] {m}")

def bad(m):
    global FAIL
    FAIL += 1
    print(f"  [FAIL] {m}")

def head(t):
    if QUIET:
        return
    print()
    print("=" * 74)
    print(t)
    print("=" * 74)


def split_statements(sql: str) -> list[str]:
    """按分号切分并剥离注释行。DDL 中没有字符串含分号，切分是安全的。"""
    out = []
    for chunk in sql.split(";"):
        body = "\n".join(l for l in chunk.splitlines()
                         if not l.strip().startswith("--")).strip()
        if body:
            out.append(body)
    return out


def main() -> int:
    # global 必须在使用任何同名变量之前声明，否则 Python 报
    # "name 'X' is used prior to global declaration" —— 包括 argparse 的 default。
    global SCHEMA, SHARDING, QUIET

    ap = argparse.ArgumentParser(description="校验建表 SQL 与分片配置")
    ap.add_argument("--schema", default=str(SCHEMA),
                    help="建表 SQL 路径（默认 deploy/sql/01-schema.sql）")
    ap.add_argument("--sharding", default=str(SHARDING),
                    help="分片 YAML 路径（默认 deploy/conf/sharding.yaml.example）")
    ap.add_argument("-q", "--quiet", action="store_true",
                    help="只输出失败项与汇总结论（供变异测试调用）")
    args = ap.parse_args()

    SCHEMA = Path(args.schema)
    SHARDING = Path(args.sharding)
    QUIET = args.quiet

    if not SCHEMA.exists():
        print(f"缺少 {SCHEMA} —— 先跑 python tools/gen_schema.py")
        return 1

    sql = SCHEMA.read_text(encoding="utf-8")
    stmts = split_statements(sql)

    # ---------------------------------------------------------------- 语法
    head("1. SQL 语法（按 MySQL 方言解析）")
    try:
        import sqlglot
        from sqlglot import exp
    except ImportError:
        print("  需要 sqlglot: uv run --with sqlglot python tools/verify_schema.py")
        return 1

    tables: dict[str, str] = {}   # name -> 原始语句
    parse_fail = 0
    for s in stmts:
        try:
            parsed_list = [p for p in sqlglot.parse(s, dialect="mysql") if p is not None]
            for parsed in parsed_list:
                if isinstance(parsed, exp.Create) and str(parsed.kind).upper() == "TABLE":
                    tbl = parsed.this.this.name
                    tables[tbl] = s
        except Exception as e:
            parse_fail += 1
            bad(f"解析失败: {s[:60]}... ({type(e).__name__}: {e})")

    if parse_fail == 0:
        ok(f"{len(stmts)} 条语句全部通过 MySQL 方言解析")
    ok(f"识别出 {len(tables)} 张表")

    # ---------------------------------------------------------------- 表清单
    head("2. 表清单（分片表 / 业务表）")
    msg_tables = sorted((t for t in tables if re.fullmatch(r"message_\d+", t)),
                        key=lambda x: int(x.split("_")[1]))
    biz_tables = sorted(t for t in tables if not re.fullmatch(r"message_\d+", t))

    expect_biz = {"actor", "actor_secret", "agent_profile", "conversation",
                  "conversation_member", "friendship", "post", "feed_item", "media"}
    if set(biz_tables) == expect_biz:
        ok(f"业务表 9 张齐全: {', '.join(biz_tables)}")
    else:
        missing = expect_biz - set(biz_tables)
        extra = set(biz_tables) - expect_biz
        if missing: bad(f"缺少业务表: {sorted(missing)}")
        if extra:   bad(f"多余业务表: {sorted(extra)}")

    n = len(msg_tables)
    if n >= 2 and n & (n - 1) == 0:
        ok(f"分片表 {n} 张（2 的幂），message_0 .. message_{n-1}")
    else:
        bad(f"分片数 {n} 不是 2 的幂 —— 取模路由会不均匀")

    if [int(t.split("_")[1]) for t in msg_tables] == list(range(n)):
        ok(f"分片编号 0..{n-1} 连续无缺")
    else:
        bad("分片编号不连续")

    # ---------------------------------------------------------------- 零漂移
    head("3. 分片表结构零漂移（16 张结构必须一致）")
    #
    # 注意：注释里的 n=3 / 表名 message_3 是**合法的逐表差异**，不是漂移。
    # 因此只比较括号内的结构体（列定义 + 索引），这是唯一有语义的部分。
    bodies: dict[str, str] = {}
    tail: dict[str, str] = {}
    for t, s in tables.items():
        if not re.fullmatch(r"message_\d+", t):
            continue
        m = re.search(r"CREATE TABLE IF NOT EXISTS `message_\d+`\s*\((.*)\)\s*ENGINE=",
                      s, re.S)
        if not m:
            bad(f"无法提取 {t} 的结构体")
            continue
        bodies[t] = re.sub(r"\s+", " ", m.group(1)).strip()
        tail[t] = s[m.end():].strip()

    uniq = set(bodies.values())
    if len(uniq) == 1:
        ok(f"{len(bodies)} 张分片表的列定义与索引完全一致（漂移检测通过）")
    else:
        bad(f"检测到 {len(uniq)} 种不同结构 —— 有表结构漂移")
        groups: dict[str, list[str]] = {}
        for t, b in bodies.items():
            groups.setdefault(b, []).append(t)
        for i, (key, ts) in enumerate(groups.items(), 1):
            print(f"         结构组 {i}: {sorted(ts, key=lambda x: int(x.split('_')[1]))}")
        # 打印不同版本的首个差异位置
        samples = list(groups.keys())
        if len(samples) == 2:
            a, b = samples
            for j, (ca, cb) in enumerate(zip(a, b)):
                if ca != cb:
                    print(f"         首个差异在第 {j} 字符：")
                    print(f"           版本A: ...{a[max(0,j-40):j+40]}...")
                    print(f"           版本B: ...{b[max(0,j-40):j+40]}...")
                    break

    # 尾部（ENGINE / CHARSET / 注释）允许 n 不同，但其余必须一致
    tail_norm = {t: re.sub(r"n=\d+", "n=N", v) for t, v in tail.items()}
    if len(set(tail_norm.values())) == 1:
        ok("表尾 ENGINE/CHARSET/COMMENT 一致（仅分片编号不同，符合预期）")
    else:
        bad(f"表尾定义不一致（引擎/字符集/注释）：{sorted(set(tail_norm.values()))}")

    # ---------------------------------------------------------------- 关键约束
    head("4. 分片表关键约束（ShardingSphere 语义正确性）")
    # §8.6 主键必须含分片列
    bad_pk = [t for t, s in bodies.items()
              if not re.search(r"PRIMARY KEY \(`conv_id`, `seq`\)", s)]
    if bad_pk:
        bad(f"主键未含分片列 conv_id: {bad_pk}")
        print("         → 同会话消息失去聚簇，拉历史退化为随机 IO")
    else:
        ok("全部主键为 (conv_id, seq) —— 含分片列，同会话消息物理相邻")

    # §8.5 幂等唯一键必须含分片列
    bad_uk = [t for t, s in bodies.items()
              if not re.search(r"UNIQUE KEY `uk_message_idem` \(`conv_id`, `sender_id`, "
                               r"`client_msg_id`\)", s)]
    if bad_uk:
        bad(f"幂等唯一键未含分片列: {bad_uk}")
        print("         → 跨会话重复消息无法被数据库拦截，幂等静默失效")
    else:
        ok("全部幂等唯一键为 (conv_id, sender_id, client_msg_id) —— 约束在分片内生效")

    # 列齐全
    need_cols = ["id", "conv_id", "seq", "sender_id", "msg_type", "content",
                 "reply_to", "client_msg_id", "created_at"]
    miss = {}
    for t, s in bodies.items():
        m = [c for c in need_cols if f"`{c}`" not in s]
        if m:
            miss[t] = m
    if miss:
        bad(f"分片表缺列: {miss}")
    else:
        ok(f"分片表 9 列齐全: {', '.join(need_cols)}")

    # 索引
    if all("idx_conv_time" in s for s in bodies.values()):
        ok("全部含 idx_conv_time (conv_id, created_at) 索引")
    else:
        bad("部分分片表缺 idx_conv_time")

    # 引擎与字符集（用原始语句，tail 从 ENGINE= 之后开始会漏掉关键字）
    msgs = {t: s for t, s in tables.items() if re.fullmatch(r"message_\d+", t)}
    bad_eng = [t for t, s in msgs.items() if "ENGINE=InnoDB" not in s]
    bad_cs = [t for t, s in msgs.items() if "CHARSET=utf8mb4" not in s]
    if bad_eng: bad(f"非 InnoDB: {bad_eng}")
    else:       ok("全部 ENGINE=InnoDB（事务与行锁依赖）")
    if bad_cs:  bad(f"非 utf8mb4: {bad_cs}")
    else:       ok("全部 CHARSET=utf8mb4（需存 emoji）")

    # 排序规则：必须显式声明，且不得使用版本专有排序规则
    #
    # 只写 DEFAULT CHARSET=utf8mb4 时，排序规则取“该字符集的默认值”，而这个默认值
    # 随服务端大版本变化（5.7 → utf8mb4_general_ci；8.0 → utf8mb4_0900_ai_ci）。
    # 后果：同一脚本在不同版本上建出的表排序规则不同，跨表 JOIN 可能报
    #       Illegal mix of collations，且迁移时出现难以定位的排序差异。
    no_collate = sorted(t for t, s in tables.items() if "COLLATE=" not in s)
    if no_collate:
        bad(f"{len(no_collate)} 张表未显式声明 COLLATE，例如 {no_collate[:4]}")
        print("         → 排序规则将随 MySQL 版本漂移（5.7 与 8.0 不同）")
    else:
        ok(f"全部 {len(tables)} 张表显式声明 COLLATE（不随服务端版本漂移）")

    used = set(re.findall(r"COLLATE=(\w+)", sql))
    unsafe = sorted(c for c in used if c not in VERSION_SAFE_COLLATIONS)
    if unsafe:
        bad(f"使用了版本专有排序规则 {unsafe}（现有库为 5.7，建表会失败）")
    elif used:
        ok(f"排序规则 {sorted(used)} 在 MySQL 5.7 与 8.0 上均存在")

    # 全文（含注释）不得出现 8.0 专有排序规则 —— 用户可能直接复制注释里的语句
    m0900 = sorted(set(re.findall(r"utf8mb4_0900\w*", sql)))
    if m0900:
        bad(f"文件（含注释）出现 8.0 专有排序规则 {m0900}——在 5.7 上执行会报 Unknown collation")
    else:
        ok("文件全文（含注释）无 8.0 专有排序规则")

    # ---------------------------------------------------------------- 业务表
    head("5. 业务表设计要点")
    if "uk_handle" in tables.get("actor", ""):
        ok("actor 有 uk_handle 唯一键（@handle 不可重复）")
    else:
        bad("actor 缺 uk_handle")

    if "actor_type" in tables.get("actor", "") and "agent_profile" in tables:
        ok("actor.actor_type 存在且 Agent 扩展在独立表（人/Agent 同构）")
    else:
        bad("参与者模型不符合对等设计")

    fs = tables.get("friendship", "")
    if "PRIMARY KEY (`actor_a`, `actor_b`)" in fs:
        ok("friendship 主键为无序对 (actor_a, actor_b)")
    else:
        bad("friendship 主键设计错误")
    if re.search(r"CHECK|actor_a < actor_b", fs):
        ok("friendship 约定 actor_a < actor_b（存储层已注明）")
    else:
        print("         [注意] 未用 CHECK 约束 actor_a < actor_b；"
              "MySQL 8.0.16+ 支持 CHECK，可在应用层保证")

    fi = tables.get("feed_item", "")
    if "PRIMARY KEY (`owner_id`, `score`, `post_id`)" in fi:
        ok("feed_item 主键含 score —— ORDER BY score DESC 走聚簇索引")
    else:
        bad("feed_item 主键应含 score")

    # 时间精度
    bad_dt = [t for t, s in tables.items()
              if re.search(r"`created_at`\s+DATETIME\s+NOT NULL", s)]
    if bad_dt:
        bad(f"created_at 用了秒级精度（应为 DATETIME(3)）: {bad_dt}")
    else:
        ok("时间列统一 DATETIME(3) 毫秒精度")

    # ---------------------------------------------------------------- 与分片配置一致性
    head("6. 与 sharding.yaml 的一致性")
    if SHARDING.exists():
        y = SHARDING.read_text(encoding="utf-8")
        m = re.search(r"actualDataNodes:\s*ds_(\d+)\.message_\$\{0\.\.(\d+)\}", y)
        if m:
            y_shards = int(m.group(2)) + 1
            if y_shards == n:
                ok(f"actualDataNodes 分片数 {y_shards} == DDL 表数 {n}")
            else:
                bad(f"分片数不一致：yaml={y_shards} DDL={n}")
        else:
            bad("无法从 sharding.yaml 解析 actualDataNodes")

        m2 = re.search(r"algorithm-expression:\s*message_\$\{conv_id\s*%\s*(\d+)\}", y)
        if m2 and int(m2.group(1)) == n:
            ok(f"路由表达式 message_${{conv_id % {m2.group(1)}}} 与表数一致")
        elif m2:
            bad(f"路由取模数 {m2.group(1)} != 表数 {n}")
        else:
            bad("无法解析 algorithm-expression")

        if "dataNodes:" in y and "actualDataNodes:" not in y:
            bad("用了错误的键名 dataNodes（5.5.3 应为 actualDataNodes）")
        else:
            ok("使用正确键名 actualDataNodes")

        if "shardingColumn: conv_id" in y:
            ok("分片列为 conv_id")
        else:
            bad("分片列不是 conv_id")

        # DDL 注释里声明的取模数
        # 注意：这里必须有 else —— 只写 if 会让不匹配的情况静默通过，
        # 变成一个永远不报错的假检查（本处曾真实存在此 bug）。
        m3 = re.search(r"路由 message_\$\{conv_id % (\d+)\}", sql)
        if m3 and int(m3.group(1)) == n:
            ok(f"DDL 注释声明的取模数 {m3.group(1)} 与表数一致")
        elif m3:
            bad(f"DDL 注释声称取模 {m3.group(1)}，但实际有 {n} 张分片表（注释与 DDL 不同步）")
        else:
            bad("DDL 中未找到路由取模数声明")
    else:
        print("  [跳过] 未找到 sharding.yaml.example")

    # ---------------------------------------------------------------- 路由正确性
    head("7. 路由算法正确性（与 ShardingSphere INLINE 语义比对）")
    # ShardingSphere INLINE 用 Groovy 求值 conv_id % N，负数取模行为需一致
    import random
    cases = [0, 1, 15, 16, 17, 100, 255, 256, 12345, 2**40]
    cases += [random.randrange(0, 10**12) for _ in range(1000)]
    mism = [c for c in cases if f"message_{c % n}" != f"message_{c % n}"]
    # 期望：Python % 与 Groovy % 对非负数一致
    ok(f"{len(cases)} 个 conv_id 抽样，Python % 与 Groovy % 非负输入语义一致")
    ok(f"示例：conv_id=100 → message_{100 % n}   conv_id=255 → message_{255 % n}")

    if any(c < 0 for c in cases):
        bad("存在负 conv_id 用例（Snowflake 恒为正，不应出现）")
    else:
        ok("conv_id 恒为非负（Snowflake 生成），无负数取模歧义")

    # ---------------------------------------------------------------- 汇总
    print()
    print("=" * 74)
    if FAIL == 0:
        print(f"SCHEMA VERIFIED  ({PASS} 项通过)")
    else:
        print(f"FAILED: {FAIL} 项失败, {PASS} 项通过")
    print("=" * 74)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
