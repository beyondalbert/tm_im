#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
对 tools/verify_schema.py 做变异测试 —— 证明它不是"永远说通过"的摆设。

为什么需要：
    一个只会输出 OK 的校验器，和一个什么都抓不到的校验器，
    在正常代码上表现完全一样。区别只在**注入错误时**才显现。

    本项目里这些错误特别危险，因为它们**不会报错**：
      · uk_message_idem 少写 conv_id   → 建表成功，跨会话才静默失效
      · 表级 COLLATE 写 8.0 专有值     → 本机 5.7 建表直接失败
      · 16 张分片表里漂移 1 张         → 平时看不出来，路由到那张表才出诡异行为

    所以校验器必须自己也被验证。

做法：
    1. 先确认未变异的文件**通过**（否则说明基线就是红的）
    2. 注入 N 类真实错误，每次单独注入
    3. 断言每一类都被**捕获**（校验器退出码非 0）
    4. 若某类未被捕获 → 说明该校验项是假检查，本脚本失败

用法：
    uv run --with sqlglot python tools/mutate_schema.py
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
TOOLS = REPO / "tools"
SCHEMA = REPO / "deploy" / "sql" / "01-schema.sql"
SHARDING = REPO / "deploy" / "conf" / "sharding.yaml.example"


# ------------------------------------------------------------------ 变异定义
#
# 每个变异：(名字, 说明, 变异函数)
# 变异函数接收 (sql, yaml) 返回 (sql', yaml')

def _drop_conv_from_idem(sql: str, yaml: str):
    """幂等唯一键去掉分片列 —— 最危险的一类，建表不报错。"""
    return sql.replace(
        "UNIQUE KEY `uk_message_idem` (`conv_id`, `sender_id`, `client_msg_id`)",
        "UNIQUE KEY `uk_message_idem` (`sender_id`, `client_msg_id`)"), yaml


def _pk_drop_conv(sql: str, yaml: str):
    """主键去掉分片列 —— 同会话消息不再物理相邻，拉历史退化为随机 IO。"""
    return sql.replace("PRIMARY KEY (`conv_id`, `seq`)", "PRIMARY KEY (`id`)"), yaml


def _drift_one_shard(sql: str, yaml: str):
    """16 张分片表中漂移 1 张 —— 只在路由到该表时才暴露。"""
    return sql.replace(
        "CREATE TABLE IF NOT EXISTS `message_7` (\n",
        "CREATE TABLE IF NOT EXISTS `message_7` (\n  `oops` INT NULL,\n"), yaml


def _second_precision(sql: str, yaml: str):
    """时间精度降为秒 —— IM 场景同秒多条消息排序不稳。"""
    return sql.replace("DATETIME(3)", "DATETIME"), yaml


def _myisam(sql: str, yaml: str):
    """引擎换成 MyISAM —— 无事务无行锁。"""
    return sql.replace("ENGINE=InnoDB", "ENGINE=MyISAM"), yaml


def _utf8_not_utf8mb4(sql: str, yaml: str):
    """字符集降为 utf8 —— 存不了 emoji，插入即报错。"""
    return sql.replace("CHARSET=utf8mb4", "CHARSET=utf8"), yaml


def _drop_business_table(sql: str, yaml: str):
    """删掉一张业务表。"""
    return re.sub(r"CREATE TABLE IF NOT EXISTS `media` \(.*?\) ENGINE=[^;]*;",
                  "", sql, flags=re.S), yaml


def _drop_one_shard_table(sql: str, yaml: str):
    """删掉一张分片表 —— 剩 15 张，不是 2 的幂，取模路由出现空槽。"""
    return re.sub(r"CREATE TABLE IF NOT EXISTS `message_15` \(.*?\) ENGINE=[^;]*;",
                  "", sql, flags=re.S), yaml


def _directed_friendship(sql: str, yaml: str):
    """好友表主键变单向 —— A→B 与 B→A 可以同时存在，关系判定失效。"""
    return sql.replace("PRIMARY KEY (`actor_a`, `actor_b`)",
                       "PRIMARY KEY (`actor_a`)"), yaml


def _collation_8_only(sql: str, yaml: str):
    """表级排序规则换成 8.0 专有值 —— 在 5.7 上建表报 Unknown collation。"""
    return sql.replace("COLLATE=utf8mb4_unicode_ci", "COLLATE=utf8mb4_0900_ai_ci"), yaml


def _collation_missing(sql: str, yaml: str):
    """去掉表级 COLLATE —— 排序规则随 MySQL 版本漂移。"""
    return sql.replace(" COLLATE=utf8mb4_unicode_ci", ""), yaml


def _yaml_wrong_key(sql: str, yaml: str):
    """YAML 用错键名 dataNodes —— ShardingSphere 5.5.3 只认 actualDataNodes。"""
    return sql, yaml.replace("actualDataNodes:", "dataNodes:")


def _yaml_shard_mismatch(sql: str, yaml: str):
    """YAML 分片数与 DDL 表数不一致。"""
    return sql, (yaml.replace("${0..15}", "${0..7}")
                     .replace("conv_id % 16", "conv_id % 8"))


def _comment_only_8_collation(sql: str, yaml: str):
    """只在注释里留下 8.0 排序规则 —— 用户复制注释里的语句就会踩。

    注意注释里是 `COLLATE utf8mb4_unicode_ci;`（空格），
    表定义里是 `COLLATE=utf8mb4_unicode_ci`（等号），两者不是同一串。
    """
    return sql.replace("COLLATE utf8mb4_unicode_ci;",
                       "COLLATE utf8mb4_0900_ai_ci;"), yaml


MUTATIONS = [
    ("idem_key_drops_shard_col", "幂等唯一键去掉分片列 conv_id",   _drop_conv_from_idem),
    ("pk_drops_shard_col",       "主键去掉分片列 conv_id",         _pk_drop_conv),
    ("one_shard_table_drift",    "16 张分片表中漂移 1 张",          _drift_one_shard),
    ("second_precision_time",    "时间精度降为秒级",                _second_precision),
    ("myisam_engine",            "引擎换成 MyISAM",                 _myisam),
    ("utf8_instead_of_utf8mb4",  "字符集降为 utf8",                 _utf8_not_utf8mb4),
    ("missing_business_table",   "删掉一张业务表",                  _drop_business_table),
    ("shard_count_not_pow2",     "分片表数不是 2 的幂",              _drop_one_shard_table),
    ("directed_friendship_pk",   "好友表主键变单向",                _directed_friendship),
    ("collation_8_0_only",       "排序规则用 8.0 专有值",            _collation_8_only),
    ("collation_missing",        "缺少表级 COLLATE",                 _collation_missing),
    ("collation_in_comment",     "注释里残留 8.0 排序规则",          _comment_only_8_collation),
    ("yaml_wrong_key_name",      "YAML 错用 dataNodes 键名",         _yaml_wrong_key),
    ("yaml_shard_count_mismatch","YAML 与 DDL 分片数不一致",         _yaml_shard_mismatch),
]


# ------------------------------------------------------------------ 执行

def run_verify(schema: Path, sharding: Path) -> tuple[int, str]:
    cmd = [sys.executable, str(TOOLS / "verify_schema.py"),
           "--schema", str(schema), "--sharding", str(sharding), "--quiet"]
    r = subprocess.run(cmd, cwd=REPO, capture_output=True, timeout=300,
                       env={**os.environ, "PYTHONIOENCODING": "utf-8"})
    out = (r.stdout or b"").decode("utf-8", "replace") + \
          (r.stderr or b"").decode("utf-8", "replace")
    return r.returncode, out


def first_failure_line(out: str) -> str:
    for line in out.splitlines():
        if "[FAIL]" in line:
            return line.split("[FAIL]", 1)[1].strip()
    return "(未输出失败详情)"


def main() -> int:
    if not SCHEMA.exists():
        print(f"缺少 {SCHEMA} —— 先跑 python tools/gen_schema.py")
        return 1

    base_sql = SCHEMA.read_text(encoding="utf-8")
    base_yaml = SHARDING.read_text(encoding="utf-8")

    print("=" * 78)
    print("微变异测试：验证 tools/verify_schema.py 能抓到它声称能抓的错误")
    print("=" * 78)
    print(f"  变异数: {len(MUTATIONS)}")

    tmp = Path(tempfile.mkdtemp(prefix="tm_mut_"))
    try:
        # ---- 1. 基线必须通过
        print()
        print("── 基线（未变异，必须通过）" + " " + "-" * 44)
        bs = tmp / "base.sql"
        by = tmp / "base.yaml"
        bs.write_text(base_sql, encoding="utf-8")
        by.write_text(base_yaml, encoding="utf-8")
        code, out = run_verify(bs, by)
        if code == 0:
            print("  [ OK ] 基线通过 —— 校验器在大体正确的文件上不误报")
        else:
            print("  [FAIL] 基线竟然未通过 —— 校验器本身有问题：")
            for line in out.splitlines():
                print("         " + line)
            return 1

        # ---- 2. 逐项变异，必须全部被捕获
        print()
        print("── 逐项注入错误（每一项都必须被捕获）" + " " * 12)
        print(f"  {'变异':<28} {'结果':<8} 捕获依据")
        print("  " + "-" * 74)

        missed: list[str] = []
        for i, (name, desc, fn) in enumerate(MUTATIONS, 1):
            m_sql, m_yaml = fn(base_sql, base_yaml)
            if m_sql == base_sql and m_yaml == base_yaml:
                # 变异没生效（目标文本不存在）——必须暴露，否则是假测试
                print(f"  {name:<28} {'无效':<8} 变异未生效：目标文本未找到")
                missed.append(name)
                continue

            sp = tmp / f"m{i}.sql"
            yp = tmp / f"m{i}.yaml"
            sp.write_text(m_sql, encoding="utf-8")
            yp.write_text(m_yaml, encoding="utf-8")

            code, out = run_verify(sp, yp)
            caught = code != 0
            mark = "已捕获" if caught else "漏过"
            reason = first_failure_line(out)[:38] if caught else "校验器仍然报通过"
            print(f"  {name:<28} {mark:<8} {reason}")
            if not caught:
                missed.append(name)

        # ---- 3. 汇总
        print()
        print("=" * 78)
        caught_n = len(MUTATIONS) - len(missed)
        if missed:
            print(f"失败：{len(missed)}/{len(MUTATIONS)} 类错误未被捕获")
            for name in missed:
                print(f"  - {name}     <- 说明对应的校验项是假检查，或者根本不存在")
            print("=" * 78)
            return 1

        print(f"全部捕获：{caught_n}/{len(MUTATIONS)} 类注入错误均被校验器拦下")
        print("=" * 78)
        return 0
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
