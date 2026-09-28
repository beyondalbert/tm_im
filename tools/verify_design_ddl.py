#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""DESIGN §9 的 DDL 与「真正建库的那份 SQL」逐列比对。

**为什么需要它**：§9 的 SQL 块不参与编译、不被任何测试执行，于是它会静默地
比库里旧。这一轮就查出三处（都不是小错）：

  * `friendship` 少了 `request_id` / `message` / `expires_at` / `created_at`
    四列 —— 文档里的好友表看起来还是「只有一条关系」，而库里的那张
    同时承载请求的生命周期；
  * `actor_secret` 的主键写成了 `actor_id` 单列（真实是
    `(actor_id, secret_type)`），而「一个 Actor 一行」与「一个 Actor 一行/凭据」
    是两种完全不同的模型；
  * `conversation` 少了 `pair_key` 与它的唯一索引 —— 单聊去重键。

文档不参与编译，所以这类漂移不会被任何测试发现；而它恰好是**新人和未来的
自己**读模型的第一入口。判据只有两条，都很好解释：

  1. §9 里出现的每张表、每一列、每一个索引，都必须与生成器一致
     （列的类型、可空性、默认值；索引的列与顺序）；
  2. 生成器里的每张非分片表，都必须在 §9 里出现过 —— 新增一张表却忘了写文档，
     与「写了文档但列不对」是同一类缺陷。

比对的是 `deploy/sql/01-schema.sql`（生成物，另有 gen_schema.py --check 保证它
与生成器一致），因此这里不需要 import 生成器、也不会因生成器重构而失效。
分片表只比 `message_0`：16 张必须逐字节同构，而那是 gen_schema.py 自己的断言。

用法：
    uv run --no-progress python tools/verify_design_ddl.py [-v]
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SCHEMA = REPO / "deploy" / "sql" / "01-schema.sql"
DESIGN = REPO / "docs" / "DESIGN.md"

# §9 的边界：DESIGN 里只有这一段是「统一领域模型」，别处的 SQL 片段（如 §16 的
# 连接示例）不是模型定义，不能一起比。
SECTION_START = re.compile(r"^## 9\. ", re.M)
SECTION_END = re.compile(r"^## 10\. ", re.M)
SQL_FENCE = re.compile(r"```sql\n(.*?)```", re.S)

CREATE = re.compile(
    r"CREATE TABLE(?: IF NOT EXISTS)?\s+`?(\w+)`?\s*\((.*?)\n\)\s*ENGINE", re.S)
COLUMN = re.compile(r"^`?(\w+)`?\s+([A-Za-z]+(?:\s*\(\s*[\d,\s]+\s*\))?)\s*(.*)$")
INDEX = re.compile(r"^(PRIMARY KEY|UNIQUE KEY|KEY)\s*(?:`?(\w+)`?)?\s*\(([^)]*)\)")
DEFAULT = re.compile(r"\bDEFAULT\s+('(?:[^']*)'|[\w.+-]+)", re.I)


class Table:
    def __init__(self, name: str) -> None:
        self.name = name
        self.columns: dict[str, tuple[str, bool, str | None]] = {}
        self.indexes: dict[str, tuple[str, ...]] = {}

    def __repr__(self) -> str:  # pragma: no cover - 只为失败信息可读
        return f"<Table {self.name}>"


def parse(sql: str) -> dict[str, Table]:
    """把一段建表 SQL 解析成 {表名: Table}。

    只认「整行一条定义」的写法（每列/每索引各占一行），这是本仓库两种写法
    （生成器与文档）的共同形状；遇到单行写法**直接报错**而不是静默解析出半个表——
    半个表会被当成「文档少了好几列」，那比不校验更浪费时间。
    """
    tables: dict[str, Table] = {}
    for m in CREATE.finditer(sql):
        body = m.group(2)
        if "\n" not in body:
            sys.exit(f"表 `{m.group(1)}` 的 DDL 是单行写法，本校验器只支持逐行书写"
                     "（本仓库的生成器与文档都是逐行的）")
        table = Table(m.group(1))
        for raw in body.split("\n"):
            line = raw.strip().rstrip(",")
            if not line or line.startswith("--"):
                continue
            hit = INDEX.match(line)
            if hit:
                kind = hit.group(1)
                name = hit.group(2) or "PRIMARY"
                if kind != "PRIMARY KEY":
                    name = f"{kind}:{name}"
                cols = tuple(c.strip().strip("`") for c in hit.group(3).split(","))
                table.indexes[name] = cols
                continue
            hit = COLUMN.match(line)
            if not hit:
                continue
            name, type_, rest = hit.group(1), hit.group(2), hit.group(3)
            upper = rest.upper()
            nullable = "NULL" in upper and "NOT NULL" not in upper
            default = DEFAULT.search(rest)
            table.columns[name] = (
                re.sub(r"\s+", "", type_).upper(),
                nullable,
                default.group(1).strip("'") if default else None,
            )
            if "PRIMARY KEY" in upper:  # 文档里的行内主键写法
                table.indexes["PRIMARY"] = (name,)
        tables[table.name] = table
    return tables


def design_section() -> str:
    text = DESIGN.read_text(encoding="utf-8")
    start = SECTION_START.search(text)
    end = SECTION_END.search(text)
    if not start or not end or end.start() < start.start():
        sys.exit("在 docs/DESIGN.md 里找不到 §9 的范围（## 9. … ## 10.）")
    return "\n\n".join(SQL_FENCE.findall(text[start.start():end.start()]))


# --------------------------------------------------------------------------
# 自检：证明这个校验器不是摆设
# --------------------------------------------------------------------------
#
# 一个从没红过的校验器与没有校验器是等价的（它只让 verify_all 多打一行 OK）。
# 所以这里在**内存里**给两份 SQL 注入真实发生过的漂移，逐个确认比对会报出来，
# 并且报错信息里能看出是哪张表、哪一列。不写任何临时文件。

SELFTEST_REAL = """\
CREATE TABLE IF NOT EXISTS `demo` (
  `id`      BIGINT       NOT NULL,
  `name`    VARCHAR(64)  NOT NULL,
  `note`    VARCHAR(255) NULL,
  `tier`    TINYINT      NOT NULL DEFAULT 2,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_name` (`name`),
  KEY `idx_tier_name` (`tier`, `name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='自检用';

CREATE TABLE IF NOT EXISTS `extra` (
  `id` BIGINT NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='只存在于生成器里';
"""

# 文档侧的「正常版本」：三张表各带一个可改的位（列类型 / 可空性 / 默认值 / 索引 /
# 是否多出一张表），下面逐个只改一处。用模板而不是抄 8 份：
# 每个用例只该因它要验的那一处而红，抄写时一眼看不出改了什么的用例是验不出东西的。
DEMO_TPL = """CREATE TABLE demo (
  id   BIGINT       NOT NULL,
  name {name}  NOT NULL,
  note VARCHAR(255) {note},
  tier TINYINT      NOT NULL DEFAULT {tier},
  PRIMARY KEY (id),
  UNIQUE KEY uk_name (name),
  {tier_idx}
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
"""

EXTRA_DOC = """CREATE TABLE extra (
  id BIGINT NOT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
"""

GHOST_DOC = """CREATE TABLE ghost (
  id BIGINT NOT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
"""

_OK = {"name": "VARCHAR(64)", "note": "NULL", "tier": 2, "tier_idx": "KEY idx_tier_name (tier, name)"}


def _demo(**over) -> str:
    return DEMO_TPL.format(**{**_OK, **over})


SELFTEST_CASES = [
    ("缺列", _demo(note="NULL").replace("  note VARCHAR(255) NULL,\n", "") + EXTRA_DOC,
     "`demo` 缺列 `note`"),
    ("列类型不同", _demo(name="VARCHAR(128)") + EXTRA_DOC, "`demo.name` 列定义不一致"),
    ("可空性不同", _demo(note="NOT NULL") + EXTRA_DOC, "`demo.note` 列定义不一致"),
    ("默认值不同", _demo(tier=3) + EXTRA_DOC, "`demo.tier` 列定义不一致"),
    ("缺索引", _demo(tier_idx="") + EXTRA_DOC, "`demo` 缺索引 `KEY:idx_tier_name`"),
    ("索引列顺序不同", _demo(tier_idx="KEY idx_tier_name (name, tier)") + EXTRA_DOC,
     "`demo` 的 `KEY:idx_tier_name` 列不一致"),
    ("文档里多写了一张表", _demo() + EXTRA_DOC + GHOST_DOC, "§9 写了表 `ghost`"),
    ("生成器里的表没写进文档", _demo(), "生成器里的表 `extra` 在 §9 里完全没有出现"),
]


def selftest() -> int:
    real = parse(SELFTEST_REAL)
    if compare(parse(SELFTEST_REAL), real):
        print("自检失败：无漂移的两份 SQL 被判成了不一致")
        return 1
    for label, doc_sql, expected in SELFTEST_CASES:
        problems = compare(parse(doc_sql), real)
        if not problems:
            print(f"自检失败：注入的「{label}」没有被发现")
            return 1
        if not any(expected in p for p in problems):
            print(f"自检失败：「{label}」虽然报错了，但信息里没有 {expected!r}")
            for p in problems:
                print(f"    {p}")
            return 1
    print(f"自检 OK：{len(SELFTEST_CASES)} 类注入的漂移都被捕获，且指出了表名/列名")
    return 0


def compare(doc: dict[str, Table], real: dict[str, Table]) -> list[str]:
    problems: list[str] = []

    def describe(info) -> str:
        type_, nullable, default = info
        text = f"{type_} {'NULL' if nullable else 'NOT NULL'}"
        return text if default is None else f"{text} DEFAULT {default}"

    for name in sorted(doc):
        if name not in real:
            problems.append(f"§9 写了表 `{name}`，但生成器里没有这张表")
            continue
        got, want = doc[name], real[name]
        for col in sorted(set(want.columns) - set(got.columns)):
            problems.append(f"`{name}` 缺列 `{col}`（生成器：{describe(want.columns[col])}）"
                            "——文档里的模型比库旧，读它的人会漏掉这个字段")
        for col in sorted(set(got.columns) - set(want.columns)):
            problems.append(f"`{name}` 多列 `{col}`（生成器里没有这一列，"
                            "要么文档没跟上删列，要么名字写错了）")
        for col in sorted(set(got.columns) & set(want.columns)):
            if got.columns[col] != want.columns[col]:
                problems.append(f"`{name}.{col}` 列定义不一致：文档 {describe(got.columns[col])}"
                                f"，生成器 {describe(want.columns[col])}")
        for idx in sorted(set(want.indexes) - set(got.indexes)):
            problems.append(f"`{name}` 缺索引 `{idx}`({', '.join(want.indexes[idx])})"
                            "——索引是模型的一部分（唯一约束靠它、分页靠它），文档漏掉它"
                            "等于漏掉一条规则")
        for idx in sorted(set(got.indexes) - set(want.indexes)):
            problems.append(f"`{name}` 多索引 `{idx}`（生成器里没有）")
        for idx in sorted(set(got.indexes) & set(want.indexes)):
            if got.indexes[idx] != want.indexes[idx]:
                problems.append(f"`{name}` 的 `{idx}` 列不一致：文档 {got.indexes[idx]}，"
                                f"生成器 {want.indexes[idx]}")

    for name in sorted(real):
        if name not in doc:
            problems.append(f"生成器里的表 `{name}` 在 §9 里完全没有出现"
                            "——新增表必须写进设计文档，否则它只存在于 DDL 里")
    return problems


def main() -> int:
    ap = argparse.ArgumentParser(description="DESIGN §9 与建表 SQL 的一致性校验")
    ap.add_argument("-v", "--verbose", action="store_true", help="打印比对的表与列数")
    ap.add_argument("--no-selftest", action="store_true", help="跳过自检（诊断用）")
    args = ap.parse_args()

    if not args.no_selftest and selftest() != 0:
        return 1
    print()

    if not SCHEMA.is_file():
        sys.exit(f"缺少 {SCHEMA.relative_to(REPO)}（先跑 uv run python tools/gen_schema.py）")

    real = parse(SCHEMA.read_text(encoding="utf-8"))
    # 16 张分片表必须逐字节同构（gen_schema.py 自己断言），这里只比 message_0：
    # 比 16 遍只会把同一条缺陷打印 16 次。
    sharded = sorted(t for t in real if t.startswith("message_"))
    for t in sharded[1:]:
        del real[t]
    doc = parse(design_section())

    print("DESIGN §9 ↔ deploy/sql/01-schema.sql")
    print(f"  §9 : {len(doc)} 张表 / {sum(len(t.columns) for t in doc.values())} 列")
    print(f"  DDL: {len(real)} 张表（含 1 张分片表代表 {len(sharded)} 张物理表）"
          f" / {sum(len(t.columns) for t in real.values())} 列")
    if args.verbose:
        for name in sorted(doc):
            print(f"    - {name}: {len(doc[name].columns)} 列, "
                  f"{len(doc[name].indexes)} 索引")

    problems = compare(doc, real)
    print()
    if problems:
        print(f"发现 {len(problems)} 处不一致：")
        for p in problems:
            print(f"  ✗ {p}")
        return 1
    print("OK §9 的 DDL 与生成器逐列一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
