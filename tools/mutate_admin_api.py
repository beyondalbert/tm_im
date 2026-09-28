#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""变异测试：证明「管理后台那些规则」真的被测试钉住了。

为什么后台这一组要单独跑
--------------------------------------------------------------------------
后台的规则与其它模块的规则有一个共同的坏性质：**改坏了照样能跑**。

  * 去掉 `requireSuper` —— 接口全部正常，只是 OPS 也能建号停号；
  * `limit` 不再夹到 `max_page_size` —— 只是某天被人一次拉走十万行；
  * 非法码值不再抛 40002 而是返回 null —— 表现为「筛了 Agent，拿到全部人」；
  * 停用账号时不删会话 —— 表现为「停用要等下一次登录才生效」；
  * 登录失败不计数 —— 表现为「怎么撞都不会锁」，而错误码一个不少。

这五条都不是编译错误，也不会让任何功能用例变红；它们只会让后台<b>看起来正常</b>。
所以这里逐条注入，确认 `tm-core` 与 `tm-api-admin` 的测试会失败，并且失败的是
**该失败的那一个用例**（失败在别处等于测试之间没有分工）。

与 `mutate_member_rules.py` 同一取舍：注入只写进源文件、跑完立刻还原，
并用「原文里锚点必须唯一」防止规则悄悄过期（正则/字符串对不上时，
变异会「什么都没改」而测试照样绿——那是变异测试自己最危险的假通过）。

代价：每条变异跑一次 `mvn test`（约 20-40 秒）。这也是它单独一个脚本、
而不是混进 `verify_all --quick` 的原因。

用法
    uv run --no-progress python tools/mutate_admin_api.py
    uv run --no-progress python tools/mutate_admin_api.py --list
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SERVER = REPO / "server"


@dataclass(frozen=True)
class Mutation:
    """一条变异：改哪个文件、怎么改、期望哪个用例变红。"""

    label: str
    path: str
    old: str
    new: str
    module: str
    test_class: str
    expect_method: str
    why: str


MUTATIONS: list[Mutation] = [
    Mutation(
        label="权限判断被删掉（OPS 也能管后台账号）",
        path="tm-core/src/main/java/com/tm/im/core/admin/AdminService.java",
        old="""    private void requireSuper(AdminContext ctx, String why) {
        if (!ctx.isSuper()) {
            throw new TmException(ErrorCode.PERMISSION_DENIED, why + "（当前 role=" + ctx.role() + "）");
        }
    }""",
        new="""    private void requireSuper(AdminContext ctx, String why) {
        // MUTANT: 判断被删掉
    }""",
        module="tm-api-admin",
        test_class="AdminApiContractTest",
        expect_method="accountManagementIsSuperOnly",
        why="后台账号管理整组 SUPER only（40302）；漏写一处就是一个越权入口",
    ),
    Mutation(
        label="分页大小不再被夹到上限",
        path="tm-core/src/main/java/com/tm/im/core/admin/AdminService.java",
        old="        return Math.min(limit, properties.getMaxPageSize());",
        new="        return limit;  // MUTANT",
        module="tm-api-admin",
        test_class="AdminApiContractTest",
        expect_method="paginationClampsAndCarriesCursor",
        why="客户端要 1000 条实际拿到 100 条并继续翻页，比回一个 40002 更有用",
    ),
    Mutation(
        label="非法码值静默当成「不过滤」",
        path="tm-api-admin/src/main/java/com/tm/im/api/admin/web/AdminParams.java",
        old='''            throw invalid("actor_type", code, "1=HUMAN 2=AGENT");''',
        new="            return null;  // MUTANT",
        module="tm-api-admin",
        test_class="AdminApiContractTest",
        expect_method="actorFiltersValidateCodes",
        why="「筛了 Agent 却拿到全部用户」是运营永远发现不了的那类错",
    ),
    Mutation(
        label="缺少 status 时静默返回 null",
        path="tm-api-admin/src/main/java/com/tm/im/api/admin/web/AdminParams.java",
        old='''        if (code == null) {
            throw new TmException(ErrorCode.MISSING_PARAMETER, "缺少 status（1=ACTIVE 2=SUSPENDED）");
        }
        return actorStatus(code);''',
        new="        return actorStatus(code);  // MUTANT",
        module="tm-api-admin",
        test_class="AdminApiContractTest",
        expect_method="actorStatusRequiresExplicitValue",
        why="一次「没带参数」的请求不能静默解封一个号",
    ),
    Mutation(
        label="停用后台账号时不删它的会话",
        path="tm-core/src/main/java/com/tm/im/core/admin/AdminService.java",
        old="""        if (status == AdminStatus.DISABLED) {
            killed = sessions.deleteByAdminId(adminId);
        }""",
        new="""        if (false) {  // MUTANT
            killed = sessions.deleteByAdminId(adminId);
        }""",
        module="tm-core",
        test_class="AdminServiceTest",
        expect_method="disablingAdminKillsItsSessions",
        why="「停用」的语义是立刻没有权限，而不是「下次登录时会被拒」",
    ),
    Mutation(
        label="登录失败不再累计计数（锁定永不触发）",
        path="tm-core/src/main/java/com/tm/im/core/admin/AdminService.java",
        old="        admins.updateLoginState(admin.getId(), failed, lockedUntil, admin.getLastLoginAt());",
        new="        // MUTANT: 不写登录状态\n        if (false) { admins.updateLoginState(admin.getId(), failed, lockedUntil, admin.getLastLoginAt()); }",
        module="tm-core",
        test_class="AdminServiceTest",
        expect_method="lockoutAfterTooManyFailures",
        why="失败计数落库是后台防爆破的全部内容（多实例部署时内存计数等于每个实例各给几次机会）",
    ),
]


def run_tests(module: str, test_class: str) -> tuple[int, str]:
    """跑一个模块里的一个测试类。返回 (退出码, 输出)。"""
    cmd = ["cmd", "/c", "mvn", "-B", "-o", "-q", "-pl", module, "-am", "test",
           "-Dtest=" + test_class, "-Dsurefire.failIfNoSpecifiedTests=false"]
    env = dict(os.environ, PYTHONIOENCODING="utf-8", MAVEN_OPTS="-Dfile.encoding=UTF-8")
    proc = subprocess.run(cmd, cwd=SERVER, capture_output=True, env=env, timeout=1800)
    out = (proc.stdout or b"").decode("utf-8", "replace") \
        + (proc.stderr or b"").decode("utf-8", "replace")
    return proc.returncode, out


def main() -> int:
    ap = argparse.ArgumentParser(description="管理后台规则的变异测试")
    ap.add_argument("--list", action="store_true", help="只列出变异，不跑（改规则时看锚点用）")
    args = ap.parse_args()

    if args.list:
        for m in MUTATIONS:
            print(f"  {m.label}\n    {m.path} → 期望 {m.test_class}.{m.expect_method}")
        return 0

    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    # 先确认「原样代码」是绿的：否则下面的失败可能来自别的 bug，而不是变异
    modules = sorted({(m.module, m.test_class) for m in MUTATIONS})
    for module, test_class in modules:
        code, out = run_tests(module, test_class)
        if code != 0:
            print(f"基线不通过：{module}/{test_class} 在原样代码上是红的（退出码 {code}）")
            print("\n".join(out.splitlines()[-25:]))
            return 1
        print(f"  基线 OK  {module}/{test_class} → 退出码 0")

    failed = 0
    for m in MUTATIONS:
        path = SERVER / m.path
        original = path.read_text(encoding="utf-8")
        n = original.count(m.old)
        if n != 1:
            # 锚点不唯一 = 这条规则已经过期（源码改过），必须报出来而不是跳过：
            # 跳过会让「变异测试通过」变成一句空话。
            print(f"  失效!!  {m.label}：锚点在 {m.path} 里出现 {n} 次（应恰好 1 次）")
            failed += 1
            continue

        path.write_text(original.replace(m.old, m.new), encoding="utf-8", newline="\n")
        try:
            code, out = run_tests(m.module, m.test_class)
            caught = code != 0 and m.expect_method in out
            mark = "捕获 OK " if caught else "漏过!!  "
            print(f"  {mark} {m.label}")
            print(f"          {m.why}")
            print(f"          期望 {m.test_class}.{m.expect_method}，退出码 {code}")
            if not caught:
                failed += 1
                print("    校验输出：")
                for line in out.splitlines()[-25:]:
                    print("      " + line)
        finally:
            path.write_text(original, encoding="utf-8", newline="\n")

    print()
    if failed:
        print(f"变异测试失败 {failed}/{len(MUTATIONS)} —— 后台有规则没有被测试钉住")
        return 1
    print(f"变异测试通过 {len(MUTATIONS)}/{len(MUTATIONS)} —— 每条规则都有测试钉住，"
          f"且失败的是该失败的那个用例")
    return 0


if __name__ == "__main__":
    sys.exit(main())
