#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""给「续传读取路径」做变异测试：把每条规则逐一改坏，看测试是否必然失败。

为什么需要它
    续传（CMD_SYNC）的规则里，有几条是**只靠读代码看不出来**的：

      * has_more 必须由「每会话多取一行」得出——少取一行的话，has_more 永远是 false，
        客户端会在第一轮就以为补齐了，而缺的那段再也没人补；
      * has_more=true 时**绝不能**发 SYNC_END——发了客户端就会开始收实时推送，
        中间的空洞永久留在本地；
      * 非成员/已不存在的会话游标要「跳过并点名」，而不是让整轮回 40303；
      * 失败必须回 ERROR，不能回一帧看着像成功的空结果。

    这四条都属于「改坏了照样能跑、而且看起来更正常」的那类：
    少取一行不会报错、多回一帧不会报错、静默返回空结果更不会报错。
    因此它们只能靠测试钉住，而测试本身也需要被验证——一个永远绿的测试与没有测试等价。

为什么在临时目录里做
    直接改仓库里的文件，进程一旦被强杀（超时、Ctrl+C）就会留下一个被改坏的仓库。
    所以把 server/ 与 proto/ 拷到临时目录，在那里变异并跑 mvn；仓库本体只读，
    脚本结束时核对每个被变异过的文件哈希未变。

怎么判断「被抓到了」
    不看 mvn 的控制台输出（它受控制台编码影响，中文断言信息在 Windows 上会变成乱码），
    而是读 surefire 的纯文本报告（UTF-8）并检查**具体的失败用例名**：
    每个变异都必须让「它该触发的那个用例」失败。用例名变了说明钉住这条规则的测试没了。

用法
    uv run python tools/mutate_sync_read_path.py          # 全部变异
    uv run python tools/mutate_sync_read_path.py has_more # 只跑名字里含该串的变异
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

MESSAGE_SERVICE = ("server/tm-core/src/main/java/com/tm/im/core/message/MessageService.java")
BUSINESS_HANDLER = ("server/tm-channel/src/main/java/com/tm/im/channel/handler/BusinessHandler.java")

# 临时工作区只需要这两个目录：proto 是 tm-channel 的 protoSourceRoot（../../proto），
# protoc 本体由 Maven 从本地仓库取（server/pom.xml 的 protocArtifact），不需要 .tools。
COPY = ["server", "proto"]

MUTATION_TIMEOUT_SEC = 900


def _replace_once(text: str, old: str, new: str) -> str:
    if old not in text:
        raise AssertionError("变异定义已过期：找不到\n" + old)
    return text.replace(old, new, 1)


# ---------------------------------------------------------------------------
# 变异清单：(名字, 说明, 文件, 变异函数, 模块, 测试类, 必须失败的用例)
# ---------------------------------------------------------------------------

MUTATIONS = [
    (
        "has_more 少取一行",
        "每个会话只取 limit 条（不再多取一行）：has_more 从此永远是 false，"
        "客户端第一轮就以为补齐了，缺的那段再也没人补——而服务端不会报任何错",
        MESSAGE_SERVICE,
        lambda t: _replace_once(
            t,
            "messages.listAfterSeq(cursor.convId(), cursor.sinceSeq(), pageSize + 1)",
            "messages.listAfterSeq(cursor.convId(), cursor.sinceSeq(), pageSize)"),
        "tm-core", "MessageServiceTest", "syncReportsHasMoreByFetchingOneExtraRow",
    ),
    (
        "非成员游标不再跳过",
        "非成员的游标改成回 40303：一个已退群的游标会让该用户永远补不了其他会话的消息"
        "（而修它的办法恰好要靠 SYNC 才能发现）",
        MESSAGE_SERVICE,
        lambda t: _replace_once(
            t,
            """                skipped.add(cursor.convId());
                log.warn("SYNC 跳过不可访问的会话 actorId={} convId={}（非成员或会话不存在）",
                        cmd.actorId(), cursor.convId());
                continue;""",
            """                throw new TmException(ErrorCode.NOT_A_MEMBER, "convId=" + cursor.convId());"""),
        "tm-core", "MessageServiceTest", "syncSkipsCursorsTheActorCannotAccess",
    ),
    (
        "重复 conv_id 不再拒绝",
        "同一个会话出现两次时不再报 40002：「本轮该按哪个游标算」没有定义，"
        "静默挑一个会漏消息或重复下发",
        MESSAGE_SERVICE,
        lambda t: _replace_once(t, "            if (!seen.add(cursor.convId())) {",
                                "            if (false) {"),
        "tm-core", "MessageServiceTest", "syncRejectsDuplicateCursor",
    ),
    (
        "游标数上限失效",
        "单次 CMD_SYNC 的游标数不再有上限：一个有 5000 个会话的客户端"
        "可以用一帧换来 5000 次查询与几十万条消息的响应体（限流器只管帧数）",
        MESSAGE_SERVICE,
        lambda t: _replace_once(t, """        if (cursors.size() > max) {""",
                                """        if (false) {"""),
        "tm-core", "MessageServiceTest", "syncRejectsTooManyCursors",
    ),
    (
        "limit 不收敛",
        "limit 不再收敛到 maxPullSize：客户端传 100000 就真的拉 100000 行，"
        "一个恶意请求能拖垮一个分片",
        MESSAGE_SERVICE,
        lambda t: _replace_once(t, "        int pageSize = properties.clampPullSize(cmd.limit());",
                                "        int pageSize = cmd.limit() <= 0 ? 200 : cmd.limit();"),
        "tm-core", "MessageServiceTest", "syncClampsPageSize",
    ),
    (
        "has_more=true 也发 SYNC_END",
        "本轮没补齐也发「这轮完了」：客户端开始收实时推送，中间缺的那段永久留在本地",
        BUSINESS_HANDLER,
        lambda t: _replace_once(t, """                if (outcome.hasMore()) {""",
                                """                if (false) {"""),
        "tm-channel", "TwoClientEndToEndTest", "syncDoesNotSendEndWhileMoreRoundsArePending",
    ),
    (
        "跳过的游标不点名",
        "SYNC_END 不再带 skipped 说明：客户端会永远带着一个已退群的游标重连，"
        "而日志之外没有任何人知道这件事",
        BUSINESS_HANDLER,
        lambda t: _replace_once(t, """                if (!outcome.skippedConvs().isEmpty()) {
                    // 跳过是「不因此失败」，不是「不重要」：不说出来的话，
                    // 客户端会永远带着那个已退群的游标重连，而没有任何人知道这件事。
                    end.setMessage("skipped " + outcome.skippedConvs().size()
                            + " cursor(s) you cannot access: " + outcome.skippedConvs());
                }""",
                                """                end.setMessage("");"""),
        "tm-channel", "TwoClientEndToEndTest", "syncReportsSkippedCursorsInTheEndFrame",
    ),
    (
        "失败时静默回空结果",
        "续传失败时不再回 ERROR，而是回一帧空 RESP + END：客户端以为补齐了，"
        "而缺失的消息永远不会被发现（假成功比报错危险得多）",
        BUSINESS_HANDLER,
        lambda t: _replace_once(t, """            } catch (TmException e) {
                respondAsync(ctx, reqId, e.errorCode(), e.detail());
            } catch (RuntimeException e) {
                log.error("续传失败 actorId={} cursors={}", session.actorId(),""",
                                """            } catch (TmException e) {
                log.warn("续传失败（变异：静默返回空结果）actorId={}", session.actorId());
                ctx.writeAndFlush(Frames.of(Frame.Cmd.CMD_SYNC_RESP, reqId,
                        SyncResponse.getDefaultInstance()));
                ctx.writeAndFlush(Frames.of(Frame.Cmd.CMD_SYNC_END, reqId,
                        SyncEnd.newBuilder().setOk(true).build()));
            } catch (RuntimeException e) {
                log.error("续传失败 actorId={} cursors={}", session.actorId(),"""),
        "tm-channel", "TwoClientEndToEndTest", "syncFailureIsReportedInsteadOfSilentSuccess",
    ),
    (
        "两帧顺序反了",
        "先发 SYNC_END 再发 SYNC_RESP：客户端拿 END 去配对 req_id 时会当成「本轮结束」，"
        "而后面那帧消息来得太晚",
        BUSINESS_HANDLER,
        lambda t: _replace_once(t, """                ctx.write(respFrame);
                ctx.writeAndFlush(Frames.of(Frame.Cmd.CMD_SYNC_END, reqId, end.build()));""",
                                """                ctx.write(Frames.of(Frame.Cmd.CMD_SYNC_END, reqId, end.build()));
                ctx.writeAndFlush(respFrame);"""),
        "tm-channel", "TwoClientEndToEndTest", "syncDeliversTheGapAndEndsTheRound",
    ),
    (
        "END 的 req_id 不复用",
        "SYNC_END 的 req_id 写成 0：客户端按 req_id 配对响应，配不上就不知道该等谁，"
        "表现是续传卡住直到超时",
        BUSINESS_HANDLER,
        lambda t: _replace_once(
            t, "ctx.writeAndFlush(Frames.of(Frame.Cmd.CMD_SYNC_END, reqId, end.build()));",
            "ctx.writeAndFlush(Frames.of(Frame.Cmd.CMD_SYNC_END, 0, end.build()));"),
        "tm-channel", "TwoClientEndToEndTest", "syncDeliversTheGapAndEndsTheRound",
    ),
]

# 仓库里这些文件必须自始至终不变（脚本只读它们）
READONLY_IN_REPO = [MESSAGE_SERVICE, BUSINESS_HANDLER]


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def make_workspace() -> Path:
    tmp = Path(tempfile.mkdtemp(prefix="tm-mutate-sync-"))
    ignore = shutil.ignore_patterns("target", "__pycache__", "runtime")
    for rel in COPY:
        src = REPO / rel
        if src.is_dir():
            shutil.copytree(src, tmp / rel, ignore=ignore)
        else:
            shutil.copy2(src, tmp / rel)
    return tmp


def run_tests(workspace: Path, module: str, test_class: str) -> tuple[int, str]:
    """在临时仓库里跑单个测试类。返回 (退出码, surefire 报告文本)。"""
    argv = ["mvn", "-B", "-o", "test", "-pl", module, "-am",
            "-Dtest=" + test_class, "-Dsurefire.failIfNoSpecifiedTests=false"]
    if os.name == "nt":
        argv = ["cmd", "/c"] + argv
    proc = subprocess.run(argv, cwd=workspace / "server", capture_output=True,
                          timeout=MUTATION_TIMEOUT_SEC,
                          env={**os.environ, "PYTHONIOENCODING": "utf-8"})

    reports = list((workspace / "server" / module / "target" / "surefire-reports")
                   .glob("*" + test_class + ".txt"))
    if not reports:
        return proc.returncode, ""
    return proc.returncode, reports[0].read_text(encoding="utf-8", errors="replace")


def main() -> int:
    # Windows 的默认控制台编码是 GBK，而这里的输出全是中文与符号：
    # 不显式改成 UTF-8 的话，重定向到文件时会直接 UnicodeEncodeError（本脚本刚写完就踩了一次）。
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    argv = sys.argv[1:]
    selected = [m for m in MUTATIONS if not argv or any(a in m[0] for a in argv)]
    if not selected:
        print("没有匹配的变异名：" + ", ".join(m[0] for m in MUTATIONS))
        return 2

    before = {rel: sha(REPO / rel) for rel in READONLY_IN_REPO}
    workspace = make_workspace()
    print("临时工作区: {}".format(workspace))
    failures: list[str] = []
    try:
        # 每个变异都从原始文件重新开始：否则多个变异落在同一个文件时，
        # 后一个的目标文本可能已被前一个删掉，那时「变异未生效」会被误判成「测试没抓到」。
        pristine = {rel: (workspace / rel).read_text(encoding="utf-8")
                    for rel in {m[2] for m in selected}}

        print("\n步骤 0：先确认未变异时这些测试是绿的（否则后面全无意义）")
        for module, test_class in sorted({(m[4], m[5]) for m in selected}):
            code, report = run_tests(workspace, module, test_class)
            if code != 0 or "Failures: 0, Errors: 0" not in report:
                print("  ❌ 未变异就无法通过：{}/{}".format(module, test_class))
                print("     " + report[:2000].replace("\n", "\n     "))
                return 2
            print("  ✅ {}/{} 通过".format(module, test_class))

        print("\n逐条改坏，看它该触发的用例是否必然失败")
        for name, why, rel, mutate, module, test_class, must_fail in selected:
            target = workspace / rel
            try:
                mutated = mutate(pristine[rel])
            except AssertionError as e:
                failures.append("{}：{}".format(name, e))
                print("  ⚠️  {}：{}".format(name, e))
                continue
            if mutated == pristine[rel]:
                failures.append("{}：变异没有改变文件内容".format(name))
                print("  ⚠️  {}：变异没有改变文件内容".format(name))
                continue

            target.write_text(mutated, encoding="utf-8", newline="")
            code, report = run_tests(workspace, module, test_class)
            target.write_text(pristine[rel], encoding="utf-8", newline="")

            if code == 0:
                failures.append("{}：测试竟然全绿 —— 这条规则没有人钉住".format(name))
                print("  ❌ {}：测试未察觉（该用例是 {}）".format(name, must_fail))
                continue
            if must_fail not in report:
                failures.append("{}：测试失败了，但不是 {}（是不是用例改名了？）".format(name, must_fail))
                print("  ⚠️  {}：失败了，但看不到用例 {}".format(name, must_fail))
                print("     报告开头：" + report[:1200].replace("\n", "\n     "))
                continue
            print("  ✅ {}：{} 失败（正是它该抓的）".format(name, must_fail))
            print("     {}".format(why))
    finally:
        shutil.rmtree(workspace, ignore_errors=True)

    print("\n仓库本体未被改动核对")
    for rel in READONLY_IN_REPO:
        same = sha(REPO / rel) == before[rel]
        print("  {} {}".format("✅" if same else "❌", rel))
        if not same:
            failures.append("仓库本体被改动了：" + rel)

    print("\n" + "=" * 74)
    if failures:
        print("FAILED ({} 项)：".format(len(failures)))
        for f in failures:
            print("  - " + f)
        return 1
    print("{} 个变异全部被捕获：续传读取路径的规则都有测试钉住 ✓".format(len(selected)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
