#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""给 ShardingSphere SPI 守卫（ShardingSphereSpiAvailabilityTest）做变异测试。

为什么需要它
    一个只会说 OK 的校验，无法证明它抓得到东西。本文件就是那个"反证"：
    逐个把真实需要的依赖删掉，看守卫是否**失败**并给出可读的诊断。

    这里要防的是一类特别隐蔽的 bug：shardingsphere-jdbc 只是门面，
    特性/模式/连接池/解析器/URL 加载器都要显式声明。漏掉任何一个，
    报错都不指向缺依赖——表现为"YAML 标签无效""框架抛 NPE""SPI 找不到实现"。
    本轮真实发生过 4 次，排查成本很高。所以守卫必须能被证明有效。

为什么在临时目录里做
    Maven 必须在工程目录里跑，没法像 mutate_schema.py 那样只把"输入"拷出去。
    但如果直接改真实 pom.xml，进程被强杀（verify_all 超时、Ctrl+C）
    就会留下一个残缺的 pom——比不做变异测试更糟。
    所以先把 server/ 与 deploy/ 拷到临时目录，在那里变异、在那里跑 Maven。
    仓库本体全程只读，脚本最后还会核对这一点。

    拷贝时排除 deploy/conf/runtime/：那里面有真实凭据，
    没有必要把它复制到临时目录（临时目录未必会被清理）。

用法
    uv run python tools/mutate_sharding_deps.py
"""

from __future__ import annotations

import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

GUARD = "ShardingSphereSpiAvailabilityTest"

POM_ARTIFACT_BY_MUTATION = {
    "去掉 sharding-core": "shardingsphere-sharding-core",
    "去掉 pool-hikari": "shardingsphere-infra-data-source-pool-hikari",
    "去掉 standalone-mode-repository-memory":
        "shardingsphere-standalone-mode-repository-memory",
    "去掉 infra-url-absolutepath": "shardingsphere-infra-url-absolutepath",
    "去掉 parser-sql-engine-mysql": "shardingsphere-parser-sql-engine-mysql",
}

# (名字, 说明, 目标文件相对路径, 期望在失败输出里出现的关键词)
MUTATIONS = [
    (
        "去掉 sharding-core",
        "分片功能整块消失：!SHARDING 无法识别、没有路由引擎、INLINE 算法不存在",
        "server/tm-storage/pom.xml",
        ["YamlShardingRuleConfigurationSwapper", "ShardingSQLRouter", "InlineShardingAlgorithm"],
    ),
    (
        "去掉 pool-hikari",
        "Hikari 的 jdbcUrl -> url 同义词映射没了，StorageUnit 里会抛 NPE",
        "server/tm-storage/pom.xml",
        ["HikariDataSourcePoolMetaData"],
    ),
    (
        "去掉 standalone-mode-repository-memory",
        "单机模式没有 ContextManagerBuilder、也没有元数据仓库",
        "server/tm-storage/pom.xml",
        ["StandaloneContextManagerBuilder", "MemoryRepository"],
    ),
    (
        "去掉 infra-url-absolutepath",
        "生产用的 absolutepath: 加载器没了（classpath: 那个还在，所以只报一条）",
        "server/tm-storage/pom.xml",
        ["AbsolutePathLocalFileURLLoader"],
    ),
    (
        "去掉 parser-sql-engine-mysql",
        "MySQL 方言的 SQL 解析器没了",
        "server/tm-storage/pom.xml",
        ["MySQLParserFacade"],
    ),
    (
        "模板里取模数与表数不一致",
        "actualDataNodes 是 ${0..15} 但表达式写成 % 8：数据只会落在前 8 张表，且不报错",
        "deploy/conf/sharding.yaml.example",
        ["取模数"],
    ),
]

# 仓库里这几个文件必须自始至终不变（脚本只读它们）
READONLY_IN_REPO = [
    "server/tm-storage/pom.xml",
    "deploy/conf/sharding.yaml.example",
]


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def make_workspace() -> Path:
    """把 server/ 与 deploy/ 拷进临时目录，返回临时目录根。

    排除 target/（构建产物）与 deploy/conf/runtime/（含真实凭据）。
    保留 deploy/ 是必须的：守卫测试要从工作目录向上找到同时含有
    deploy/ 与 server/ 的"仓库根"，少了 deploy/ 它会直接抛异常，
    那样每次变异都会"失败"——变异测试就会变成一次自我欺骗。
    """
    tmp = Path(tempfile.mkdtemp(prefix="tm-mutate-deps-"))
    ignore = shutil.ignore_patterns("target", "runtime")
    shutil.copytree(REPO / "server", tmp / "server", ignore=ignore)
    shutil.copytree(REPO / "deploy", tmp / "deploy", ignore=ignore)
    return tmp


def drop_dependency(pom: str, artifact: str) -> str:
    """删掉一个 <dependency> 块（按 artifactId 定位）。"""
    marker = f"<artifactId>{artifact}</artifactId>"
    idx = pom.find(marker)
    if idx < 0:
        raise AssertionError(f"pom 里找不到 {artifact}，变异定义已过期")
    start = pom.rfind("<dependency>", 0, idx)
    end = pom.find("</dependency>", idx) + len("</dependency>")
    while end < len(pom) and pom[end] in "\r\n":   # 顺带吃掉换行，不留空行噪音
        end += 1
    return pom[:start] + pom[end:]


def run_guard(workspace: Path) -> tuple[int, bytes]:
    """只跑守卫测试。用 -am 是因为兄弟模块未 install 到本地仓库。

    返回原始字节而不是 str：JVM 往管道写的中文用的是平台编码（本机为 GBK），
    按 utf-8 解码会变成乱码，中文关键词永远匹配不上——
    那会把"已捕获"误报成"未捕获"。
    """
    # Windows 上 mvn 是 .cmd 壳，CreateProcess 不能直接执行，
    # 必须经 cmd /c；否则报 WinError 2"系统找不到指定的文件"。
    mvn = shutil.which("mvn") or "mvn"
    argv = [mvn, "-B", "-o", "-pl", "tm-storage", "-am", "test",
            f"-Dtest={GUARD}", "-Dsurefire.failIfNoSpecifiedTests=false"]
    if os.name == "nt":
        argv = ["cmd", "/c"] + argv
    proc = subprocess.run(argv, cwd=workspace / "server", capture_output=True)
    return proc.returncode, (proc.stdout or b"") + (proc.stderr or b"")


def decode_output(raw: bytes) -> tuple[str, str]:
    """解成两套文本：utf-8 与平台编码（GBK）。

    两种都留着：Maven 自身的英文输出在两种解码下一致，
    而 JVM 写的中文只在 GBK 下可读（换到 UTF-8 的机器上则相反）。
    关键词在两套里任一命中即算命中，脚本因此不绑定开发机的编码。
    """
    return (raw.decode("utf-8", errors="replace"),
            raw.decode("gbk", errors="replace"))


def main() -> int:
    before = {p: sha(REPO / p) for p in READONLY_IN_REPO}

    workspace = make_workspace()
    print(f"临时工作区: {workspace}")
    try:
        print("步骤 0：先确认未变异时守卫是绿的（否则后面全无意义）")
        code, out = run_guard(workspace)
        if code != 0:
            print("  未变异就无法通过，先修好再跑变异测试。")
            print(decode_output(out)[1][-4000:])
            return 2
        print("  ✅ 通过\n")

        failures: list[str] = []
        for name, why, rel, keywords in MUTATIONS:
            target = workspace / rel
            original = target.read_text(encoding="utf-8")

            if rel.endswith("pom.xml"):
                mutated = drop_dependency(original, POM_ARTIFACT_BY_MUTATION[name])
            else:
                if "% 16" not in original:
                    raise AssertionError("模板里找不到 '% 16'，变异定义已过期")
                mutated = original.replace("% 16", "% 8", 1)

            # newline="" 表示不做换行转换，保证写回与读入的字节风格一致
            target.write_text(mutated, encoding="utf-8", newline="")
            code, out = run_guard(workspace)

            if code == 0:
                failures.append(f"{name}：守卫竟然通过了 —— 这是假检查")
                print(f"  ❌ {name}：守卫未察觉")
                continue

            haystack = "\n".join(decode_output(out))
            missing = [k for k in keywords if k not in haystack]
            if missing:
                failures.append(f"{name}：守卫失败了，但诊断里没提到 {missing}")
                print(f"  ⚠️  {name}：失败了，但缺少关键词 {missing}")
                print("      实际输出尾部（用于判断真正报的是什么）：")
                print("      " + decode_output(out)[1][-1200:].replace("\n", "\n      "))
            else:
                print(f"  ✅ {name}：被捕获")
                print(f"      {why}")
    finally:
        shutil.rmtree(workspace, ignore_errors=True)

    print("\n仓库本体未被改动核对")
    for rel, h in before.items():
        ok = sha(REPO / rel) == h
        print(f"  {'✅' if ok else '❌'} {rel}")
        if not ok:
            failures.append(f"{rel} 被改动了 —— 脚本不应接触仓库本体")

    if failures:
        print(f"\n{len(failures)} 项未达成：")
        for f in failures:
            print("  - " + f)
        return 1

    print(f"\n全部 {len(MUTATIONS)} 个变异均被捕获。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
