#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
生成 tm_im 的 M0 建表 SQL。

为什么用生成器而不是手写 SQL：
  16 张分片表 `message_0..message_15` 结构必须**逐字节完全一致**。
  手写 16 遍必然出现漂移（少一个索引、注释不同、字段顺序错位），
  而这类漂移在 ShardingSphere 下不会立刻报错，只会在某个分片上
  静默丢失约束——等发现时数据已经脏了。

用法：
    python tools/gen_schema.py                 # 生成 deploy/sql/01-schema.sql
    python tools/gen_schema.py --check         # 只校验已生成文件与当前定义是否一致
    python tools/gen_schema.py --shards 32     # 改分片数（需同步改 sharding.yaml）
"""

from __future__ import annotations

import argparse
import hashlib
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
OUT = REPO / "deploy" / "sql" / "01-schema.sql"

SHARDS_DEFAULT = 16
PREFIX = "tm_im"

# ============================================================================
# 字符集与排序规则
#
# 为什么必须显式写 COLLATE：
#   只写 DEFAULT CHARSET=utf8mb4 时，排序规则取“该字符集的默认值”，而这个
#   默认值随服务端大版本变化：
#       MySQL 5.7  →  utf8mb4_general_ci
#       MySQL 8.0  →  utf8mb4_0900_ai_ci
#   后果：
#     · 同一个脚本在 5.7 与 8.0 上建出的表，排序规则不同
#     · 跨表 JOIN / 比较时可能报 Illegal mix of collations
#     · 迁移或主从混版本时出现难以定位的排序差异
#
# 选 utf8mb4_unicode_ci 的理由：
#   · 5.7 与 8.0 上**都存在**（utf8mb4_0900_* 是 8.0 独有，5.7 会报错）
#   · Unicode 排序正确性优于 utf8mb4_general_ci
# ============================================================================
CHARSET = "utf8mb4"
COLLATION = "utf8mb4_unicode_ci"

# ============================================================================
# 表定义
#
# 约定：所有 DATETIME(3) 用毫秒精度（IM 场景秒级不够，见 DESIGN §9.4）
#      所有表 ENGINE=InnoDB CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
#      （表级 COLLATE 由 _normalize_charset 统一补齐，见 build()）
# ============================================================================

HEADER_TMPL = """\
-- ============================================================================
-- tm_im M0 建表脚本
-- ============================================================================
-- 本文件由 tools/gen_schema.py 自动生成，请勿手工编辑。
-- 如要修改结构，改生成器后重跑：  python tools/gen_schema.py
--
-- 分片布局：1 库 × {shards} 表（仅分表，单数据源）
-- 分片键：  conv_id
-- 路由算法：message_${{conv_id % {shards}}}
--
-- 生成指纹：{fingerprint}
-- ============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 1;

-- ---------------------------------------------------------------------------
-- 库（如需手工建库可取消注释；权限不足请让 DBA 执行）
-- ---------------------------------------------------------------------------
-- CREATE DATABASE IF NOT EXISTS `{prefix}`
--   DEFAULT CHARACTER SET {charset} COLLATE {collation};

USE `{prefix}`;
"""


def actor_tables() -> str:
    return """\
-- ============================================================================
-- 1. 参与者：人与 Agent 同构
-- ============================================================================
-- 「对等」红线的物理体现：只有一张 actor 表，actor_type 区分人/Agent。
-- 领域层禁止依据 actor_type 分支；它只允许出现在「展示元数据」与「投递适配」。

CREATE TABLE IF NOT EXISTS `actor` (
  `id`            BIGINT       NOT NULL                COMMENT 'Snowflake',
  `actor_type`    TINYINT      NOT NULL                COMMENT '1=HUMAN 2=AGENT',
  `handle`        VARCHAR(64)  NOT NULL                COMMENT '@alice',
  `display_name`  VARCHAR(128) NOT NULL,
  `avatar_url`    VARCHAR(512) NULL,
  `bio`           VARCHAR(512) NULL,
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=ACTIVE 2=SUSPENDED',
  `created_at`    DATETIME(3)  NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_handle` (`handle`),
  KEY `idx_type_status` (`actor_type`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='唯一参与者表：人/Agent 同构';

-- 凭据隔离到独立表：actor 是高频读取的公开信息，凭据不应与之同页
CREATE TABLE IF NOT EXISTS `actor_secret` (
  `actor_id`      BIGINT       NOT NULL,
  `secret_type`   TINYINT      NOT NULL                COMMENT '1=密码哈希 2=API_KEY哈希',
  `secret_hash`   VARCHAR(255) NOT NULL,
  `last_used_at`  DATETIME(3)  NULL,
  PRIMARY KEY (`actor_id`, `secret_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='凭据，与人/Agent 无关';

-- Agent 扩展：Human 无此行。owner_actor 防止 Agent 成为孤儿
CREATE TABLE IF NOT EXISTS `agent_profile` (
  `actor_id`      BIGINT       NOT NULL,
  `owner_actor`   BIGINT       NOT NULL                COMMENT '归属人类',
  `endpoint_url`  VARCHAR(512) NULL                    COMMENT 'webhook 回调',
  `push_mode`     TINYINT      NOT NULL                COMMENT '1=WEBHOOK 2=WS 3=PULL',
  `capabilities`  JSON         NULL                    COMMENT '["text","image"]',
  `model_info`    VARCHAR(128) NULL,
  `rate_limit`    INT          NOT NULL DEFAULT 60     COMMENT '每秒配额',
  PRIMARY KEY (`actor_id`),
  KEY `idx_owner` (`owner_actor`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 专属扩展';
"""


def conversation_tables() -> str:
    return """\
-- ============================================================================
-- 2. 会话与成员
-- ============================================================================

CREATE TABLE IF NOT EXISTS `conversation` (
  `id`           BIGINT       NOT NULL,
  `conv_type`    TINYINT      NOT NULL                COMMENT '1=DIRECT 2=GROUP',
  `title`        VARCHAR(128) NULL,
  `owner_actor`  BIGINT       NULL                    COMMENT '群主；单聊为 NULL',
  `seq_counter`  BIGINT       NOT NULL DEFAULT 0      COMMENT 'Redis 不可用时的兜底序号',
  `pair_key`     VARCHAR(80)  NULL                    COMMENT '单聊去重键：min_max',
  `created_at`   DATETIME(3)  NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pair_key` (`pair_key`),
  KEY `idx_owner` (`owner_actor`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话；单聊/群聊同构';

-- 注意：不做「单聊双方唯一」的额外约束——单聊会话由 pair_key 保证唯一

CREATE TABLE IF NOT EXISTS `conversation_member` (
  `conv_id`        BIGINT      NOT NULL,
  `actor_id`       BIGINT      NOT NULL,
  `role`           TINYINT     NOT NULL DEFAULT 3      COMMENT '1=OWNER 2=ADMIN 3=MEMBER',
  `last_read_seq`  BIGINT      NOT NULL DEFAULT 0      COMMENT '统一未读游标',
  `muted`          TINYINT(1)  NOT NULL DEFAULT 0,
  `joined_at`      DATETIME(3) NOT NULL,
  PRIMARY KEY (`conv_id`, `actor_id`),
  KEY `idx_actor` (`actor_id`)                         COMMENT '拉「我的会话列表」'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话成员';
"""


# 分片表模板 --------------------------------------------------------------
# {n} 由生成器替换。占位符只出现在表名处，保证 16 张表结构零漂移。
MESSAGE_TMPL = """\
CREATE TABLE IF NOT EXISTS `message_{n}` (
  `id`            BIGINT      NOT NULL                 COMMENT 'Snowflake',
  `conv_id`       BIGINT      NOT NULL                 COMMENT '★分片键',
  `seq`           BIGINT      NOT NULL                 COMMENT '会话内严格递增',
  `sender_id`     BIGINT      NOT NULL,
  `msg_type`      TINYINT     NOT NULL                 COMMENT '1=TEXT 2=IMAGE 3=SYSTEM',
  `content`       JSON        NOT NULL,
  `reply_to`      BIGINT      NULL,
  `client_msg_id` VARCHAR(64) NULL                     COMMENT '幂等键',
  `created_at`    DATETIME(3) NOT NULL,
  PRIMARY KEY (`conv_id`, `seq`),
  UNIQUE KEY `uk_message_idem` (`conv_id`, `sender_id`, `client_msg_id`),
  KEY `idx_conv_time` (`conv_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息分片表 n={n}';
"""


def message_tables(shards: int) -> str:
    body = [
        "-- ============================================================================",
        f"-- 3. 消息分片表（逻辑表 message → 物理表 message_0 .. message_{shards - 1}）",
        "-- ============================================================================",
        "-- 分片键 conv_id，路由 message_${conv_id % " + str(shards) + "}",
        "--",
        "-- 两条硬约束（见 DESIGN §8.5 / §8.6），改结构时务必保留：",
        "--   1. PRIMARY KEY (conv_id, seq)",
        "--      含分片列 → 同会话消息物理聚簇，范围扫描（拉历史）走顺序 IO",
        "--   2. UNIQUE KEY uk_message_idem (conv_id, sender_id, client_msg_id)",
        "--      含分片列 → 分片表的唯一约束只在单分片内生效，",
        "--      若不含 conv_id，跨会话的重复消息将无法被数据库拦截，幂等静默失效",
        "--",
        "-- 另注：不建外键。ShardingSphere 下跨分片外键无法保证，且分片表写入",
        "--       会显著放大锁范围。参照完整性由应用层保证。",
        "",
    ]
    for n in range(shards):
        body.append(MESSAGE_TMPL.format(n=n))
    return "\n".join(body)


def social_tables() -> str:
    return """\
-- ============================================================================
-- 4. 社交与广场
-- ============================================================================

-- 好友关系：约定 actor_a < actor_b，消除方向，避免存两份
-- 这样「是否是好友」只需一次点查，且天然去重
CREATE TABLE IF NOT EXISTS `friendship` (
  `actor_a`    BIGINT      NOT NULL                  COMMENT '约定 actor_a < actor_b',
  `actor_b`    BIGINT      NOT NULL,
  `status`     TINYINT     NOT NULL                  COMMENT '1=PENDING 2=ACCEPTED 3=BLOCKED',
  `initiator`  BIGINT      NOT NULL                  COMMENT '发起方，用于展示「谁加的你」',
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`actor_a`, `actor_b`),
  KEY `idx_b` (`actor_b`, `status`)                  COMMENT '反向查询',
  KEY `idx_initiator_status` (`initiator`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='好友关系，无序对存储';

CREATE TABLE IF NOT EXISTS `post` (
  `id`         BIGINT      NOT NULL,
  `author_id`  BIGINT      NOT NULL,
  `content`    JSON        NOT NULL,
  `visibility` TINYINT     NOT NULL DEFAULT 1        COMMENT '1=PUBLIC 2=FRIENDS_ONLY',
  `created_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_author_time` (`author_id`, `created_at`),
  KEY `idx_visibility_time` (`visibility`, `created_at`)  COMMENT '广场全局流'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='动态';

-- 收件箱式时间线（写扩散产物）。
-- 主键含 score → ORDER BY score DESC 走聚簇索引，无需额外排序
CREATE TABLE IF NOT EXISTS `feed_item` (
  `owner_id`  BIGINT NOT NULL                        COMMENT '收件人',
  `score`     BIGINT NOT NULL                        COMMENT '排序分（好友加权后）',
  `post_id`   BIGINT NOT NULL,
  `author_id` BIGINT NOT NULL,
  PRIMARY KEY (`owner_id`, `score`, `post_id`),
  KEY `idx_post` (`post_id`)                         COMMENT '删除动态时清理'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='广场收件箱';

CREATE TABLE IF NOT EXISTS `media` (
  `id`         BIGINT       NOT NULL,
  `owner_id`   BIGINT       NOT NULL,
  `object_key` VARCHAR(512) NOT NULL,
  `mime`       VARCHAR(64)  NOT NULL,
  `width`      INT          NULL,
  `height`     INT          NULL,
  `size_bytes` BIGINT       NULL,
  `created_at` DATETIME(3)  NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_owner_time` (`owner_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据（对象存储只存 key）';
"""


def _normalize_charset(text: str, expected: int) -> str:
    """给所有表统一补上表级 COLLATE。

    各表定义里只写 DEFAULT CHARSET=utf8mb4。与其在 25 处字面量里手写 COLLATE，
    不如集中补齐，理由：
      · 保证 25 张表排序规则绝对一致（手写 25 遍必然漂移）
      · 与 MySQL 大版本解耦（见文件顶部 CHARSET/COLLATION 注释）
    并断言替换数量 —— 漏掉任何一张表都会立即报错，而不是静默生成不一致的 DDL。
    """
    old = f"DEFAULT CHARSET={CHARSET} COMMENT="
    new = f"DEFAULT CHARSET={CHARSET} COLLATE={COLLATION} COMMENT="
    n = text.count(old)
    if n != expected:
        raise SystemExit(
            f"错误：期望 {expected} 处表定义，实际匹配 {n} 处 —— "
            f"有表缺少 `DEFAULT CHARSET={CHARSET}` 声明"
        )
    return text.replace(old, new)


def build(shards: int, prefix: str, collation: str = COLLATION) -> str:
    global COLLATION
    orig = COLLATION
    COLLATION = collation
    try:
        parts = [
            HEADER_TMPL.format(
                shards=shards,
                prefix=prefix,
                charset=CHARSET,
                collation=collation,
                fingerprint="__FINGERPRINT__",  # 先占位，算完再回填
            ),
            actor_tables(),
            conversation_tables(),
            message_tables(shards),
            social_tables(),
            "-- ============================================================================\n"
            f"-- 共 {shards} 张 message 分片表 + 9 张非分片表\n"
            "-- ============================================================================\n",
        ]
        text = "\n".join(parts)
        text = _normalize_charset(text, text.count("CREATE TABLE IF NOT EXISTS"))
    finally:
        COLLATION = orig

    # 指纹只覆盖表定义部分，避免自我引用
    payload = text.replace("__FINGERPRINT__", "FP")
    fp = hashlib.sha256(payload.encode("utf-8")).hexdigest()[:16]
    return text.replace("__FINGERPRINT__", fp)


def main() -> int:
    ap = argparse.ArgumentParser(description="生成 tm_im 建表 SQL")
    ap.add_argument("--shards", type=int, default=SHARDS_DEFAULT)
    ap.add_argument("--prefix", default=PREFIX, help="库名")
    ap.add_argument("--collation", default=COLLATION,
                    help="表排序规则。默认 utf8mb4_unicode_ci（5.7/8.0 均支持）。\n"
                         "若库已存在且为 8.0 默认（utf8mb4_0900_ai_ci），"
                         "可传该值以对齐，但会失去 5.7 兼容性")
    ap.add_argument("--check", action="store_true",
                    help="校验已生成文件是否与当前定义一致（CI 用；不写盘）")
    args = ap.parse_args()

    if args.shards < 1 or (args.shards & (args.shards - 1)) != 0:
        print(f"错误：--shards 必须是 2 的幂（取模路由要求），当前 {args.shards}")
        return 2

    # 排序规则合法性：8.0 专有排序规则会让 5.7 建表直接报 Unknown collation
    if args.collation.endswith("0900_ai_ci") or "_0900_" in args.collation:
        print(f"警告：{args.collation} 是 MySQL 8.0 专有排序规则，")
        print("      在 5.7 服务端上执行本脚本会报 ERROR 1273 Unknown collation。")
        print("      仅当你确认服务端为 8.0 且库已用该排序规则时才使用。")

    text = build(args.shards, args.prefix, args.collation)

    if args.check:
        if not OUT.exists():
            print(f"缺少生成文件：{OUT}")
            return 1
        cur = OUT.read_text(encoding="utf-8")
        if cur != text:
            print("生成文件已过期 —— 请重跑： python tools/gen_schema.py")
            return 1
        print(f"OK  {OUT.relative_to(REPO)} 与生成器一致（{len(text)} 字节）")
        return 0

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8", newline="\n")

    n_tables = text.count("CREATE TABLE IF NOT EXISTS")
    print(f"已生成 {OUT.relative_to(REPO)}")
    print(f"  分片数      : {args.shards} (message_0 .. message_{args.shards - 1})")
    print(f"  建表语句数  : {n_tables}")
    print(f"  大小        : {len(text)} 字节")

    # 一致性自检：所有 message 表除表名外必须完全相同
    import re
    bodies = re.findall(r"CREATE TABLE IF NOT EXISTS `message_(\d+)` \((.*?)\) ENGINE",
                        text, re.S)
    if len(bodies) != args.shards:
        print(f"错误：期望 {args.shards} 张分片表，实际 {len(bodies)}")
        return 1
    uniq = {b for _, b in bodies}
    if len(uniq) != 1:
        print(f"错误：{len(uniq)} 种不同的表结构 —— 手写漂移！")
        return 1
    print(f"  分片表自检  : {args.shards} 张结构完全一致 OK")

    names = sorted(int(n) for n, _ in bodies)
    if names != list(range(args.shards)):
        print(f"错误：分片编号不连续: {names}")
        return 1
    print(f"  编号连续性  : 0..{args.shards - 1} OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
