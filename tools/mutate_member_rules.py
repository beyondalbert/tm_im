#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""给「群成员管理」（§4.9）做变异测试：把每条规则逐一改坏，看测试是否必然失败。

为什么需要它
    这一批规则几乎全都属于「改坏了照样能跑、而且看起来更正常」那一类：

      * 权限判据少一个等号（`<=` 写成 `<`），两个 ADMIN 就能互相踢——而双方都会觉得
        「我确实比他权限高」；
      * 踢人时目标不在群里改成静默成功，一个拼错的 actor_id 从此看起来像踢成功了；
      * 退群时把「写通知」与「删成员行」调个顺序，请求照样 200，只是那条通知永远丢失
        （真实实现只会记一条 ERROR）；
      * 转让群主只改成员行不降旧群主，群里就出现了**两个** OWNER——而权限判据读成员行，
        两个人从此都能转让、都能踢对方；
      * 去掉 `@Transactional`，转让失败时事务不回滚，于是「旧群主降级了、新群主没升上去」，
        群里一个 OWNER 都没有——而这一条**只有真实 MySQL 才验得了**（内存替身没有事务）。

    它们都只能靠测试钉住，而测试本身也需要被验证——一个永远绿的测试与没有测试等价。

为什么在临时目录里做
    直接改仓库里的文件，进程一旦被强杀（超时、Ctrl+C）就会留下一个被改坏的仓库。
    所以把 server/ 拷到临时目录，在那里变异并跑 mvn；仓库本体只读，脚本结束时核对
    每个被变异过的文件哈希未变。

怎么判断「被抓到了」
    不看 mvn 的控制台输出（它受控制台编码影响，中文断言信息在 Windows 上会变成乱码），
    而是读 surefire 的纯文本报告（UTF-8）并检查**具体的失败用例名**：
    每个变异都必须让「它该触发的那个用例」失败。用例名变了说明钉住这条规则的测试没了。

用法
    uv run python tools/mutate_member_rules.py                 # 全部变异（含需要真实 MySQL 的那条）
    uv run python tools/mutate_member_rules.py --unit-only     # 只跑不需要外部服务的（无凭据的机器）
    uv run python tools/mutate_member_rules.py 退群           # 只跑名字里含该串的变异
"""

from __future__ import annotations

import hashlib
import os
import shutil
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
WORKSPACE = REPO / ".tools" / "mutate-member-ws"

CONVERSATION_SERVICE = "server/tm-core/src/main/java/com/tm/im/core/conversation/ConversationService.java"
REPOSITORY_IMPL = ("server/tm-storage/src/main/java/com/tm/im/storage/repository/"
                   "ConversationRepositoryImpl.java")

COPY = ["server"]

MUTATION_TIMEOUT_SEC = 900

# 需要真实 MySQL/Redis 的测试：跑它们时要 -Pit 并指到运行时配置（与 tools/run_it.py 同一套约定）。
IT_SHARDING = (REPO / "deploy" / "conf" / "runtime" / "sharding.yaml").as_posix()
IT_PROPERTIES = (REPO / "deploy" / "conf" / "runtime" / "it.properties").as_posix()


def _replace_once(text: str, old: str, new: str) -> str:
    if old not in text:
        raise AssertionError("变异定义已过期：找不到\n" + old)
    return text.replace(old, new, 1)


# ---------------------------------------------------------------------------
# 变异清单：(名字, 说明, 文件, 变异函数, 模块, 测试类/用例, 必须失败的用例, 需要外部服务)
# ---------------------------------------------------------------------------

MUTATIONS = [
    (
        "权限判据失效",
        "「目标的角色码必须严格大于我的」这条唯一的判据被去掉：两个 ADMIN 可以互相踢，"
        "而任何一方都会认为自己确实比对方权限高——群里从此没有稳定状态",
        CONVERSATION_SERVICE,
        lambda t: _replace_once(
            t,
            "        if (mine == null || theirs == null || theirs.code() <= mine.code()) {",
            "        if (false) {"),
        "tm-core", "ConversationMemberServiceTest", "adminsCannotKickEachOther", False,
    ),
    (
        "ADMIN 也能改角色",
        "设置角色改成 ADMIN 也能做：群主指定的管理员会被另一个管理员撤掉，"
        "「群主的选择是最终的」这条前提消失，而且两个 ADMIN 可以互相降级",
        CONVERSATION_SERVICE,
        lambda t: _replace_once(t, '        requireOwner(ctx, "设置角色");',
                                '        requireAdminOrOwner(ctx, "设置角色");'),
        "tm-core", "ConversationMemberServiceTest", "onlyTheOwnerCanChangeRoles", False,
    ),
    (
        "同值改角色也写一条消息",
        "去掉「目标已经是这个角色就什么都不做」：客户端按当前角色回填下拉框、"
        "点确定往往就是同一个值，于是每次刷新群设置都会在群里留下一条「X 成为了管理员」",
        CONVERSATION_SERVICE,
        lambda t: _replace_once(t, "        if (target.getRole() == requested) {",
                                "        if (false) {"),
        "tm-core", "ConversationMemberServiceTest", "onlyTheOwnerCanChangeRoles", False,
    ),
    (
        "加人时「已在群里」当错误",
        "已有成员从 already_members 改成抛 40905：一批人里有一个已在群里，"
        "另外几个也加不进去——客户端只能整批重试，而重试仍然是同一个结果",
        CONVERSATION_SERVICE,
        lambda t: _replace_once(
            t,
            """            if (members.contains(id)) {
                already.add(id);""",
            """            if (members.contains(id)) {
                throw new TmException(ErrorCode.ALREADY_MEMBER, "actorId=" + id);"""),
        "tm-core", "ConversationMemberServiceTest", "addingExistingMemberIsNotAnError", False,
    ),
    (
        "加人不再检查上限",
        "去掉人数上限检查：一个群可以被加到超过扇出阈值，而那个阈值决定推送怎么降级——"
        "结果是「建得出、推不动」的群（客户端看到的最后一条消息永远停在某个位置）",
        CONVERSATION_SERVICE,
        lambda t: _replace_once(t, "        if (current.size() + toAdd.size() > maxMembers) {",
                                "        if (false) {"),
        "tm-core", "ConversationMemberServiceTest",
        "addingStopsAtTheLimitWithoutCountingExistingMembers", False,
    ),
    (
        "踢人时目标不在群里也当成功",
        "目标不在群里时静默返回成功：一个拼错的 actor_id 看起来像踢成功了，"
        "而客户端以为自己刚移除的这个人还在群里（只是它已经刷新过成员列表了）",
        CONVERSATION_SERVICE,
        lambda t: _replace_once(
            t,
            """        ConversationMember target = conversations.findMember(convId, targetId)
                .orElseThrow(() -> new TmException(ErrorCode.TARGET_NOT_MEMBER,
                        "actorId=" + targetId + " 不是 convId=" + convId + " 的成员"));
        requireCanActOn(ctx, target, "踢人");""",
            """        ConversationMember target = conversations.findMember(convId, targetId).orElse(null);
        if (target == null) {
            return new RemoveOutcome(convId, targetId, conversations.countMembers(convId));
        }
        requireCanActOn(ctx, target, "踢人");"""),
        "tm-core", "ConversationMemberServiceTest", "kickingANonMemberIs40908", False,
    ),
    (
        "群主可以退群",
        "去掉 40306：群主一退，群就没有主了，而「恰有一个 OWNER」是后面所有权限判断的前提"
        "（转让、改角色全都要「我是群主」这个事实）",
        CONVERSATION_SERVICE,
        lambda t: _replace_once(t, "        if (ctx.me().getRole() == MemberRole.OWNER) {",
                                "        if (false) {"),
        "tm-core", "ConversationMemberServiceTest", "leavingIsBlockedForTheOwner", False,
    ),
    (
        "退群先删成员行再写通知",
        "把「写通知」与「删成员行」调个顺序：请求照样成功，只是那条「X 退出了群聊」"
        "再也没有人会写（MessageService 要求 SYSTEM 消息的作者当时是成员）——"
        "而真实实现只会记一条 ERROR 日志，客户端与用户都看不出来",
        CONVERSATION_SERVICE,
        lambda t: _replace_once(
            t,
            """        announce(convId, actorId, "member_left", loadActors(List.of(actorId)).get(actorId), Map.of());
        conversations.removeMember(convId, actorId);
        return new RemoveOutcome(convId, actorId, conversations.countMembers(convId));""",
            """        conversations.removeMember(convId, actorId);
        announce(convId, actorId, "member_left", loadActors(List.of(actorId)).get(actorId), Map.of());
        return new RemoveOutcome(convId, actorId, conversations.countMembers(convId));"""),
        "tm-core", "ConversationMemberServiceTest",
        "leavingAnnouncesBeforeRemovingTheRow", False,
    ),
    (
        "转让只改成员行，不动群主指针",
        "转让群主时只把目标升为 OWNER，不降自己、也不改 conversation.owner_actor："
        "群里出现两个 OWNER，而「谁能退群」（读 owner_actor）与「谁能改角色」（读成员行）"
        "从此各认一个人",
        CONVERSATION_SERVICE,
        lambda t: _replace_once(
            t,
            "        if (!conversations.transferOwnership(convId, actorId, target.getActorId())) {",
            "        if (!conversations.updateMemberRole(convId, target.getActorId(), MemberRole.OWNER)) {"),
        "tm-core", "ConversationMemberServiceTest", "transferringOwnershipKeepsExactlyOneOwner", False,
    ),
    (
        "转让失败也当成功",
        "条件式更新没命中（并发下别人已经把群主转走了）时不再报 40305：响应里说"
        "「现在群主是 TA」，而库里一个字都没改——客户端据此把界面切成了群主视图，"
        "而它接下来的每个操作都会失败",
        CONVERSATION_SERVICE,
        lambda t: _replace_once(
            t,
            "        if (!conversations.transferOwnership(convId, actorId, target.getActorId())) {",
            "        if (false) {"),
        "tm-core", "ConversationMemberServiceTest", "losingTheRaceToTransferIsAPrivilegeError", False,
    ),
    (
        "改群名超长就截断",
        "群名超过 128 字符时截断而不是报错：客户端回显的群名与库里的从此不一致，"
        "而「回显不一致」是最难被当成 bug 报告的一类问题（用户以为自己的群名叫全了）",
        CONVERSATION_SERVICE,
        lambda t: _replace_once(
            t,
            """        if (clean.length() > MAX_TITLE_LENGTH) {
            throw new TmException(ErrorCode.INVALID_PARAMETER,
                    "title 长度 " + clean.length() + " 超过上限 " + MAX_TITLE_LENGTH);
        }""",
            """        if (clean.length() > MAX_TITLE_LENGTH) {
            return clean.substring(0, MAX_TITLE_LENGTH);
        }"""),
        "tm-core", "ConversationMemberServiceTest", "renamingChecksPrivilegeAndLength", False,
    ),
    (
        "同名改群名也写一条消息",
        "去掉「群名没变就什么都不做」：每次点开群设置再保存都会在群里留下一条"
        "「群名被改为 X」（而 X 与原来一样）",
        CONVERSATION_SERVICE,
        lambda t: _replace_once(
            t, "        if (Objects.equals(clean, ctx.conversation().getTitle())) {",
            "        if (false) {"),
        "tm-core", "ConversationMemberServiceTest", "renamingToTheSameTitleIsANoop", False,
    ),
    (
        "单聊也允许成员管理",
        "去掉「必须是群聊」这一条：单聊会得到「加人 / 踢人 / 改群名」这些没有定义的操作，"
        "而它的成员数恒为 2（§4.3），踢掉一个就等于把对方的聊天窗口删了",
        CONVERSATION_SERVICE,
        lambda t: _replace_once(t, "        if (conversation.getConvType() != ConvType.GROUP) {",
                                "        if (false) {"),
        "tm-core", "ConversationMemberServiceTest",
        "everyMemberOperationRejectsDirectConversations", False,
    ),
    (
        "系统消息不带 handle",
        "成员管理的 SYSTEM 消息只带 actor_id：退群/被踢的人已经不在成员表里，"
        "客户端手里也没有他的资料了——于是历史里那句「X 退出了群聊」永远显示不出名字",
        CONVERSATION_SERVICE,
        lambda t: _replace_once(t, '            content.put("handle", subject.getHandle());\n', ""),
        "tm-core", "ConversationMemberServiceTest", "addingAnnouncesEachMember", False,
    ),
    (
        "转让群主去掉事务",
        "去掉 transferOwnership 上的 @Transactional：三行写入不再是一个整体，"
        "中途失败（例如目标成员行刚好被并发删掉）时就留下「旧群主已降级、新群主没升上去」，"
        "群里一个 OWNER 都没有——而这一条只有真实 MySQL 能验（内存替身没有事务）",
        REPOSITORY_IMPL,
        lambda t: _replace_once(
            t, "    @Override\n    @Transactional\n    public boolean transferOwnership(",
            "    @Override\n    public boolean transferOwnership("),
        "tm-storage", "ConversationReadPathIT", "transferOwnershipIsAtomic", True,
    ),
]

# 仓库里这些文件必须自始至终不变（脚本只读它们）
READONLY_IN_REPO = sorted({m[2] for m in MUTATIONS})


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def make_workspace() -> Path:
    if WORKSPACE.exists():
        shutil.rmtree(WORKSPACE, ignore_errors=True)
    WORKSPACE.parent.mkdir(parents=True, exist_ok=True)
    ignore = shutil.ignore_patterns("target", "__pycache__", "mutate-member-ws")
    for rel in COPY:
        src = REPO / rel
        if src.is_dir():
            shutil.copytree(src, WORKSPACE / rel, ignore=ignore)
        else:
            shutil.copy2(src, WORKSPACE / rel)
    return WORKSPACE


def run_tests(workspace: Path, module: str, test_class: str,
              needs_services: bool) -> tuple[int, str, str]:
    """在临时工作区里跑指定的测试。

    返回 (退出码, 所有 surefire 报告拼起来的文本, mvn 输出的末尾片段)。
    第三项用于「跑都没跑起来」的情况（编译失败、数据库连不上）：那时报告目录是空的，
    只报「看不到该用例」会让人以为是测试改名了，而真正的原因在 mvn 输出里。
    """
    argv = ["mvn", "-B", "-o", "test", "-pl", module, "-am",
            "-Dtest=" + test_class, "-Dsurefire.failIfNoSpecifiedTests=false"]
    if needs_services:
        argv += ["-Pit", "-Dtm.it.config=" + IT_SHARDING, "-Dtm.it.properties=" + IT_PROPERTIES]
    if os.name == "nt":
        argv = ["cmd", "/c"] + argv

    reports_dir = workspace / "server" / module / "target" / "surefire-reports"
    shutil.rmtree(reports_dir, ignore_errors=True)
    proc = subprocess.run(argv, cwd=workspace / "server", capture_output=True,
                          timeout=MUTATION_TIMEOUT_SEC,
                          env={**os.environ, "PYTHONIOENCODING": "utf-8",
                               "MAVEN_OPTS": "-Dfile.encoding=UTF-8"})

    text = []
    for report in sorted(reports_dir.glob("*.txt")):
        text.append(report.read_text(encoding="utf-8", errors="replace"))
    raw = ((proc.stdout or b"").decode("utf-8", "replace")
           + (proc.stderr or b"").decode("utf-8", "replace"))
    return proc.returncode, "\n".join(text), raw[-2000:]


def main() -> int:
    # Windows 的默认控制台编码是 GBK，而这里的输出全是中文与符号：
    # 不显式改成 UTF-8 的话，重定向到文件时会直接 UnicodeEncodeError。
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    args = sys.argv[1:]
    unit_only = "--unit-only" in args
    names = [a for a in args if not a.startswith("-")]
    selected = [m for m in MUTATIONS if not names or any(n in m[0] for n in names)]
    if unit_only:
        selected = [m for m in selected if not m[7]]
    if not selected:
        print("没有匹配的变异名：" + ", ".join(m[0] for m in MUTATIONS))
        return 2

    before = {rel: sha(REPO / rel) for rel in READONLY_IN_REPO}
    workspace = make_workspace()
    print("临时工作区: {}".format(workspace))
    print("本轮变异 {} 条（{}）".format(
        len(selected), "只跑单元级" if unit_only else "含需要真实 MySQL 的一条"))
    failures: list[str] = []
    caught = 0
    try:
        # 每个变异都从原始文件重新开始：否则多个变异落在同一个文件时，
        # 后一个的目标文本可能已被前一个删掉，那时「变异未生效」会被误判成「测试没抓到」。
        pristine = {rel: (workspace / rel).read_text(encoding="utf-8")
                    for rel in {m[2] for m in selected}}

        print("\n步骤 0：先确认未变异时这些测试是绿的（否则后面全无意义）")
        for module, test_class, needs_services in sorted({(m[4], m[5], m[7]) for m in selected}):
            label = "集成测试（需真实 MySQL）" if needs_services else "单元测试"
            code, report, raw = run_tests(workspace, module, test_class, needs_services)
            if code != 0 or "Failures: 0, Errors: 0" not in report:
                print("  ❌ 未变异就无法通过：{} {}/{}".format(label, module, test_class))
                print("     " + report[:2000].replace("\n", "\n     "))
                print("     mvn 输出末尾：" + raw.replace("\n", "\n     "))
                return 2
            print("  ✅ {} 通过（{}）".format(test_class, label))

        print("\n逐条改坏，看它该触发的用例是否必然失败")
        for name, why, rel, mutate, module, test_class, must_fail, needs_services in selected:
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
            code, report, raw = run_tests(workspace, module, test_class, needs_services)
            target.write_text(pristine[rel], encoding="utf-8", newline="")

            if code == 0 and "Failures: 0, Errors: 0" in report:
                failures.append("{}：测试竟然全绿 —— 这条规则没有人钉住".format(name))
                print("  ❌ {}：测试未察觉（该用例是 {}）".format(name, must_fail))
                continue
            if must_fail not in report:
                failures.append("{}：测试失败了，但不是 {}（是不是用例改名了？）".format(name, must_fail))
                print("  ⚠️  {}：失败了，但看不到用例 {}".format(name, must_fail))
                print("     报告开头：" + report[:1200].replace("\n", "\n     "))
                print("     mvn 输出末尾：" + raw.replace("\n", "\n     "))
                continue
            caught += 1
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
    print("{} 个变异全部被捕获：群成员管理的规则都有测试钉住 ✓".format(caught))
    return 0


if __name__ == "__main__":
    sys.exit(main())
