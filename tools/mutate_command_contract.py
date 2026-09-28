#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""给「命令字契约」校验器（verify_integration_docs.py 第 5 节）做变异测试。

为什么需要它
    命令字契约是**手写三份**的：proto/transport.proto、接入文档 §2.1、服务端 Frames.java。
    三份之间没有任何编译期约束，校验器就成了唯一的守卫——而一个只会说 OK 的守卫
    与没有守卫是一样的（"三份都改了、只有两份对"这种情况不会自己暴露）。

    这里要防的是一类**已经真实发生过**的 bug：CMD_SYNC(14) 曾经一个编号同时充当
    请求与响应，于是接收方拿到 cmd=14 时无法判断帧的语义，而 protobuf 又不报错。
    修法是给响应单独编号（CMD_SYNC_RESP=16，见 04-realtime.md §2.3）。
    本文件逐个把"修回去"的各种写法做成变异，看校验器是否**失败**并指出具体是哪一处。

为什么在临时目录里做
    proto、文档、服务端源码都是仓库里路径固定的文件，校验器按仓库根去找它们。
    直接改真实文件的话，进程被强杀（verify_all 超时、Ctrl+C）就会留下一个被改坏的仓库。
    所以拷一份精简的仓库到临时目录，在那里变异。仓库本体全程只读，脚本最后核对这一点。

用法
    uv run --with protobuf python tools/mutate_command_contract.py
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

GUARD = "tools/verify_integration_docs.py"

FRAMES_JAVA = "server/tm-channel/src/main/java/com/tm/im/channel/codec/Frames.java"
REALTIME_MD = "docs/integration/04-realtime.md"

# 校验器需要的输入（照仓库结构摆好，它就能在临时目录里当仓库根用）。
# deploy/ 与 web/ 是 README 里链接到的目录；runtime/ 子目录含真实凭据，
# node_modules/ 与 dist/ 是构建产物（体积大、与校验无关），都不拷。
#
# ★ 这个清单与 README 里的链接是一对**必须同时维护**的东西：新增一个指向
# 新目录的链接时，忘了更新这里，mutate-cmd 会在临时工作区里报「链接不存在」——
# 那个报错看上去像文档坏了，实际是这个拷贝清单少了东西。
COPY = ["tools", "proto", "docs", "server", ".tools", "deploy", "web", "README.md"]

SYNC_RESP_PROTO_LINE = ("    CMD_SYNC_RESP = 16;  // S→C  payload = SyncResponse"
                        "  续传响应（一次 CMD_SYNC 恰好对应一帧）")


def _replace_once(text: str, old: str, new: str) -> str:
    if old not in text:
        raise AssertionError("变异定义已过期：找不到\n" + old)
    return text.replace(old, new, 1)


def _drop_line(text: str, needle: str) -> str:
    if needle not in text:
        raise AssertionError("变异定义已过期：找不到行\n" + needle)
    return "\n".join(ln for ln in text.split("\n") if needle not in ln)


# (名字, 说明, 目标文件, 变异函数, 期望在失败输出里出现的关键词)
MUTATIONS = [
    (
        "服务端漏登记新命令字",
        "Frames.expectedBodyType 里漏掉 CMD_SYNC_RESP：新增命令字时最常见的疏漏。"
        "Java 侧由「switch 不完备就编译失败」拦住，Python 侧由覆盖率检查拦住，两条都要在",
        FRAMES_JAVA,
        lambda t: _drop_line(t, "            case CMD_SYNC_RESP -> SyncResponse.class;"),
        ["覆盖不全"],
    ),
    (
        "proto 漏写方向",
        "命令字行不再声明方向：格式不合规必须当场失败，"
        "否则新命令可以悄悄不写方向而没人发现",
        "proto/transport.proto",
        lambda t: _replace_once(t, SYNC_RESP_PROTO_LINE,
                                "    CMD_SYNC_RESP = 16;  // 续传响应"),
        ["格式不合规"],
    ),
    (
        "文档表格漏掉一行",
        "§2.1 少写 CMD_SYNC_RESP：接入方根本不知道响应该用哪个命令字，"
        "只能又去猜 14",
        REALTIME_MD,
        lambda t: _drop_line(t, "| 16 | `CMD_SYNC_RESP` |"),
        ["集合不一致"],
    ),
    (
        "文档把 CMD_PUSH 写成 C→S",
        "方向列被写反：客户端会以为推送要自己发",
        REALTIME_MD,
        lambda t: _replace_once(t, "| 12 | `CMD_PUSH` | S→C |",
                                "| 12 | `CMD_PUSH` | C→S |"),
        ["方向不一致"],
    ),
    (
        "文档把响应写回 14",
        "编号改回 14：与 proto/服务端都不一致，且正好是修正前的歧义状态",
        REALTIME_MD,
        lambda t: _replace_once(t, "| 16 | `CMD_SYNC_RESP` |", "| 14 | `CMD_SYNC_RESP` |"),
        ["编号不一致"],
    ),
    (
        "服务端载荷类型写错",
        "Frames 里 CMD_SYNC_RESP 解成 SyncRequest：字段号近似时 protobuf 不报错，"
        "会静默解出空对象",
        FRAMES_JAVA,
        lambda t: _replace_once(t, "case CMD_SYNC_RESP -> SyncResponse.class;",
                                "case CMD_SYNC_RESP -> SyncRequest.class;"),
        ["载荷与 Frames.java 不一致"],
    ),
    (
        "服务端方向表写错",
        "Frames.direction 把 CMD_SYNC_RESP 标成双向：客户端发来的响应帧会被当成合法请求",
        FRAMES_JAVA,
        lambda t: _replace_once(
            _replace_once(t, "CMD_SYNC_RESP, CMD_SYNC_END,", "CMD_SYNC_END,"),
            "case CMD_PING, CMD_PONG -> Direction.BOTH;",
            "case CMD_PING, CMD_PONG, CMD_SYNC_RESP -> Direction.BOTH;"),
        ["方向与 Frames.direction 不一致"],
    ),
    (
        "文档帧示例编号错",
        "§6 的帧示例写成 `cmd: 14 // CMD_SYNC_RESP`：说明文字与字节不符，"
        "照抄的接入方会对不上",
        REALTIME_MD,
        lambda t: _replace_once(t, "cmd: 16,                          // CMD_SYNC_RESP",
                                "cmd: 14,                          // CMD_SYNC_RESP"),
        ["与 CMD_SYNC_RESP 的编号"],
    ),
    (
        "DESIGN 总览漏项",
        "DESIGN §7.5 的总览少了 CMD_ERROR：读者会以为这个命令不存在"
        "（这个漏项真实发生过，原来少了 SYNC 与 ERROR）",
        "docs/DESIGN.md",
        lambda t: _drop_line(t, "CMD_ERROR     = 21;  // S→C 错误（关联 req_id）"),
        ["只列了"],
    ),
    (
        "DESIGN 总览方向写反",
        "DESIGN §7.5 把 CMD_SYNC 标成 S→C：总览与详规打架，"
        "读者会信更早读到的那份",
        "docs/DESIGN.md",
        lambda t: _replace_once(t, "CMD_SYNC      = 14;  // C→S 断点续传拉取",
                                "CMD_SYNC      = 14;  // S→C 断点续传拉取"),
        ["标为"],
    ),
    (
        "手写客户端字典缺一个命令字",
        "06-no-sdk-guide.md 的 CMD 字典里删掉 SYNC_RESP（16）：接照文档实现的客户端"
        "会把续传响应当成“未知帧”，而文档里其它地方都在引用这个命令字",
        "docs/integration/06-no-sdk-guide.md",
        lambda t: _replace_once(t, '"SYNC": 14, "SYNC_END": 15, "SYNC_RESP": 16,',
                                '"SYNC": 14, "SYNC_END": 15,'),
        ["CMD 字典缺少 SYNC_RESP"],
    ),
    (
        "文档引用一个不存在的用例",
        "04-realtime.md 里把“钉住这条规则的用例”改成不存在的名字："
        "引用漂移的后果比没引用更坏——读者以为“有测试盯着”，于是放心改",
        REALTIME_MD,
        lambda t: _replace_once(t, "`MessageServiceTest.syncClampsPageSize`",
                                "`MessageServiceTest.syncClampsPageSizeV2`"),
        ["引用了 MessageServiceTest.syncClampsPageSizeV2"],
    ),
]

# 仓库里这些文件必须自始至终不变（脚本只读它们）
READONLY_IN_REPO = ["proto/transport.proto", REALTIME_MD, "docs/DESIGN.md", FRAMES_JAVA,
                   "docs/integration/06-no-sdk-guide.md"]


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def make_workspace() -> Path:
    """拷一份精简仓库到临时目录并返回其根。排除 target/（构建产物，体积大）。"""
    tmp = Path(tempfile.mkdtemp(prefix="tm-mutate-cmd-"))
    ignore = shutil.ignore_patterns("target", "__pycache__", "runtime", "node_modules", "dist")
    for rel in COPY:
        src = REPO / rel
        if src.is_dir():
            shutil.copytree(src, tmp / rel, ignore=ignore)
        else:
            shutil.copy2(src, tmp / rel)
    return tmp


def run_guard(workspace: Path) -> tuple[int, str]:
    """在临时仓库里跑校验器。返回 (退出码, 合并输出)。"""
    argv = ["uv", "run", "--with", "protobuf", "--no-progress",
            "python", str(workspace / GUARD)]
    if os.name == "nt":
        argv = ["cmd", "/c"] + argv
    proc = subprocess.run(argv, cwd=workspace, capture_output=True,
                          env={**os.environ, "PYTHONIOENCODING": "utf-8"})
    raw = (proc.stdout or b"") + (proc.stderr or b"")
    return proc.returncode, raw.decode("utf-8", errors="replace")


def main() -> int:
    before = {rel: sha(REPO / rel) for rel in READONLY_IN_REPO}

    workspace = make_workspace()
    print(f"临时工作区: {workspace}")
    failures: list[str] = []
    try:
        print("步骤 0：先确认未变异时校验器是绿的（否则后面全无意义）")
        code, out = run_guard(workspace)
        if code != 0:
            print("  未变异就无法通过，先修好再跑变异测试。")
            print(out[-4000:])
            return 2
        print("  ✅ 通过\n")

        # 每个变异都从「原始文件」重新开始，而不是在上一个变异的基础上继续：
        # 否则多个变异落在同一个文件时，后一个的目标文本可能已被前一个删掉，
        # 那时「变异未生效」会被当成「守卫没抓到」，结论完全反了。
        pristine = {rel: (workspace / rel).read_text(encoding="utf-8")
                    for _, _, rel, _, _ in MUTATIONS}

        for name, why, rel, mutate, keywords in MUTATIONS:
            target = workspace / rel
            try:
                mutated = mutate(pristine[rel])
            except AssertionError as e:
                failures.append(f"{name}：{e}")
                print(f"  ⚠️  {name}：{e}")
                continue
            if mutated == pristine[rel]:
                failures.append(f"{name}：变异没有改变文件内容")
                print(f"  ⚠️  {name}：变异没有改变文件内容")
                continue

            # newline="" 表示不做换行转换，保证写回与读入的字节风格一致
            target.write_text(mutated, encoding="utf-8", newline="")
            code, out = run_guard(workspace)
            target.write_text(pristine[rel], encoding="utf-8", newline="")

            if code == 0:
                failures.append(f"{name}：校验器竟然通过了 —— 这是假检查")
                print(f"  ❌ {name}：校验器未察觉")
                continue

            missing = [k for k in keywords if k not in out]
            if missing:
                failures.append(f"{name}：校验器失败了，但诊断里没提到 {missing}")
                print(f"  ⚠️  {name}：失败了，但缺少关键词 {missing}")
                print("      实际输出尾部（用于判断真正报的是什么）：")
                print("      " + out[-1200:].replace("\n", "\n      "))
            else:
                print(f"  ✅ {name}：被捕获")
                print(f"      {why}")
    finally:
        shutil.rmtree(workspace, ignore_errors=True)

    print("\n仓库本体未被改动核对")
    for rel, h in before.items():
        same = sha(REPO / rel) == h
        print(f"  {'✅' if same else '❌'} {rel}")
        if not same:
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
