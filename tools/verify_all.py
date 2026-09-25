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
    # 模板是「运维视角的权威文档」，而代码才是默认值的真正来源。
    # 两者之间没有任何编译期约束，缺项的表现是「运维照模板配完，服务用的是代码默认值」——
    # 看起来完全合理，所以必须机器校验。
    ("config-tmpl", "verify_config_template.py", "配置模板与 @ConfigurationProperties 一致", "pyyaml", []),
    ("mutate",   "mutate_schema.py",            "变异测试：证明校验器不是摆设",   "sqlglot", []),
    ("mutate-deps", "mutate_sharding_deps.py",   "变异测试：缺 ShardingSphere 依赖必被捕获", "", []),
    ("mutate-cfg", "mutate_config_template.py",  "变异测试：模板校验器的 5 类盲区", "pyyaml", []),
    # 命令字契约是手写三份的（proto / 接入文档 / 服务端 Frames），三份之间没有任何
    # 编译期约束。CMD_SYNC 曾经一个编号同时充当请求与响应，就是这么溜进去的。
    ("mutate-cmd", "mutate_command_contract.py", "变异测试：命令字契约三方一致校验器不是摆设", "protobuf", []),
    # 续传读取路径的规则都属于「改坏了照样能跑、而且看起来更正常」那一类：
    # 少取一行不报错、has_more=true 时多回一帧 END 不报错、失败时静默回空结果更不报错。
    # 它们只能靠测试钉住，而测试本身也需要被验证（约 2.5 分钟：每个变异跑一次 mvn）。
    ("mutate-sync", "mutate_sync_read_path.py", "变异测试：续传读取路径的 10 条规则都有测试钉住", "", []),
]

# 需要外部服务（真实 MySQL/Redis）的检查。
# 单独一组而不是混进 CHECKS：它们在没有凭据的机器上必然失败，
# 而「因为环境缺失而变红」与「因为代码有问题而变红」必须能区分开。
SERVICE_CHECKS = [
    ("runtime-cfg", "gen_runtime_config.py",
     "运行时配置与当前 local-conn.env 一致（--check）", "", ["--check"]),
    ("collation",   "probe_collation.py",
     "实测服务端排序规则等价性（决定 handle 唯一性口径）", "pymysql", []),
    # 集群路由（tm:route / tm:node）的规则几乎只在「多实例 + 出故障」时才显形：
    # 顶号误删新连接的路由、节点键不带 TTL、解绑没有 CAS、续期失败不重新注册……
    # 13 条变异里有 5 条要跑集成测试（真实 Redis），所以放在这一组。
    # 只跑不需要外部服务的那 8 条： python tools/mutate_cluster_routing.py --unit-only
    ("mutate-cluster", "mutate_cluster_routing.py",
     "变异测试：集群路由/节点探活的 13 条规则都有测试钉住", "", []),
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
    ap.add_argument("--services", action="store_true",
                    help="额外跑需要外部 MySQL/Redis 的检查（需 deploy/conf/local-conn.env）")
    ap.add_argument("-v", "--verbose", action="store_true", help="失败时打印完整输出")
    args = ap.parse_args()

    checks = CHECKS
    if args.quick:
        checks = [c for c in checks
                  if c[0] not in ("docs", "mutate", "mutate-deps", "mutate-cfg", "mutate-cmd")]
    checks = list(checks) + (list(SERVICE_CHECKS) if args.services else [])
    print(BOLD("tm_im 全量自检"))
    print(DIM(f"  仓库: {REPO}"))
    if not args.services:
        # 明确说出「哪些没验证」，而不是让读者以为绿色代表全都验过了
        print(DIM(f"  跳过 {len(SERVICE_CHECKS)} 项需要外部服务的检查"
                  f"（--services 启用：{'、'.join(c[0] for c in SERVICE_CHECKS)}）"))
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
