#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
tm_im 全量自检入口。

一条命令跑完所有验证，用于提交前或 CI：

    uv run --with protobuf --with pyyaml --with sqlglot python tools/verify_all.py

可加 --quick 跳过长耗时项。
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
TOOLS = REPO / "tools"

# 每项：(名称, 脚本, 说明, 是否需要额外包[, 附加参数])
# --check 类脚本必须挂进来：生成器一旦能静默跑，
# 实体与 DDL 之间就会慢慢长出一段谁也没注意到的偏差。
CHECKS = [
    ("schema",   "verify_schema.py",            "建表 SQL 语法 + 分片语义约束", "sqlglot", []),
    ("server-sql","verify_server_sql.py",       "服务端核验脚本语法与列引用",   "sqlglot", []),
    ("docs",     "verify_integration_docs.py",  "接入文档字节级样本与签名向量", "protobuf", []),
    ("shard",    "validate_yaml.py",            "分片口径三方一致 + 配置可解析", "pyyaml", []),
    ("errors",   "verify_error_codes.py",       "错误码契约：文档 §2 ↔ 枚举",   "", []),
    ("entities", "gen_entities.py",             "实体与建表 SQL 一致（--check）", "", ["--check"]),
    ("samples",  "verify_doc_samples.py",       "Webhook 签名测试向量",         "", []),
    ("mutate",   "mutate_schema.py",            "变异测试：证明校验器不是摆设",   "sqlglot", []),
    ("mutate-deps", "mutate_sharding_deps.py",   "变异测试：缺 ShardingSphere 依赖必被捕获", "", []),
]

USE_COLOR = sys.stdout.isatty()
def _c(code, s): return f"\033[{code}m{s}\033[0m" if USE_COLOR else s
OK, BAD, DIM, BOLD, WARN = (lambda s: _c("32", s)), (lambda s: _c("31", s)), \
                            (lambda s: _c("90", s)), (lambda s: _c("1", s)), \
                            (lambda s: _c("33", s))


def run_one(name: str, script: str, desc: str, pkg: str,
            extra: list[str] | None = None) -> tuple[bool, float, str]:
    path = TOOLS / script
    if not path.exists():
        return False, 0.0, f"脚本不存在: {script}"

    cmd = ["uv", "run"]
    if pkg:
        cmd += ["--with", pkg]
    cmd += ["--no-progress", "python", str(path)]
    cmd += extra or []

    t0 = time.perf_counter()
    try:
        r = subprocess.run(cmd, cwd=REPO, capture_output=True, timeout=600,
                           env={**os.environ, "PYTHONIOENCODING": "utf-8"})
    except subprocess.TimeoutExpired:
        return False, time.perf_counter() - t0, "超时（>600s）"
    dt = time.perf_counter() - t0

    out = (r.stdout or b"").decode("utf-8", "replace") + \
          (r.stderr or b"").decode("utf-8", "replace")
    return r.returncode == 0, dt, out


def main() -> int:
    ap = argparse.ArgumentParser(description="tm_im 全量自检")
    ap.add_argument("--quick", action="store_true", help="跳过长耗时项")
    ap.add_argument("-v", "--verbose", action="store_true", help="失败时打印完整输出")
    args = ap.parse_args()

    checks = CHECKS
    if args.quick:
        checks = [c for c in checks if c[0] not in ("docs", "mutate", "mutate-deps")]
    print(BOLD("tm_im 全量自检"))
    print(DIM(f"  仓库: {REPO}"))
    print()

    results = []
    for name, script, desc, pkg, extra in checks:
        if sys.stdout.isatty():
            print(f"  {DIM('...')} {name:<10} {desc}", end="\r")
        okk, dt, out = run_one(name, script, desc, pkg, extra)
        results.append((name, script, desc, okk, dt, out))
        status = OK(" OK ") if okk else BAD("FAIL")
        print(f"  [{status}] {name:<10} {desc:<32} {DIM(f'{dt:5.1f}s')}")

    print()
    print("=" * 74)
    passed = sum(1 for r in results if r[3])
    total = len(results)

    for name, script, desc, okk, dt, out in results:
        if not okk:
            print(BAD(f"── {name} 失败 ──"))
            lines = [l for l in out.splitlines() if l.strip()]
            show = lines if args.verbose else lines[-25:]
            for l in show:
                print("   " + l)
            print()

    if passed == total:
        print(OK(f"全部通过 ({passed}/{total})"))
    else:
        print(BAD(f"失败 {total - passed}/{total}"))
    print("=" * 74)
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
