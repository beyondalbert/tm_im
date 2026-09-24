#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""变异测试：证明「模板一致性校验器」不是摆设。

一个从没红过的校验器与没有校验器是等价的 —— 它只是让 `verify_all` 多打一行 OK。
所以这里对模板注入 5 类**真实发生过**的缺陷，逐个确认校验器会失败，
并且失败信息里能指出是哪个键。

注入的缺陷都只写进临时文件，绝不碰仓库里的模板。

用法
    uv run --with pyyaml python tools/mutate_config_template.py
"""

from __future__ import annotations

import re
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
TEMPLATE = REPO / "deploy" / "conf" / "application-external.yml.example"
CHECKER = REPO / "tools" / "verify_config_template.py"


def mutate_drop_key(text: str) -> str:
    """把某一行配置整个删掉（模板缺项）。"""
    return re.sub(r"^(\s*)auth-timeout-ms:.*\n", "", text, count=1, flags=re.M)


def mutate_change_value(text: str) -> str:
    """改掉一个数值（模板默认值与代码默认值不一致）。"""
    return re.sub(r"^(\s*)heartbeat-idle-seconds: 30", r"\g<1>heartbeat-idle-seconds: 15",
                  text, count=1, flags=re.M)


def mutate_typo_key(text: str) -> str:
    """键名拼错（这个键会被 Spring 静默忽略）。"""
    return re.sub(r"^(\s*)max-frames-per-second:", r"\g<1>max-frams-per-second:",
                  text, count=1, flags=re.M)


def mutate_secret_default(text: str) -> str:
    """给密钥一个默认值 —— 等于把一把能用的钥匙放进仓库。"""
    return re.sub(r"jwt-secret: \$\{TM_JWT_SECRET\}",
                  "jwt-secret: dev-secret-please-change-me-0123456789", text, count=1)


def mutate_drop_section(text: str) -> str:
    """把整个 tm.identity 段落删掉（运维照模板配不出来）。"""
    return re.sub(r"^  identity:\n(?:    .*\n|\n)*", "", text, count=1, flags=re.M)


MUTATIONS = [
    ("模板缺项", mutate_drop_key, "tm.netty.auth-timeout-ms"),
    ("默认值与代码不一致", mutate_change_value, "tm.netty.heartbeat-idle-seconds"),
    ("键名拼写错误", mutate_typo_key, "tm.netty.max-frams-per-second"),
    ("密钥被写了默认值", mutate_secret_default, "tm.identity.jwt-secret"),
    ("整段配置缺失", mutate_drop_section, "tm.identity"),
]


def run_checker(path: Path) -> tuple[int, str]:
    r = subprocess.run([sys.executable, str(CHECKER), "--template", str(path)],
                       capture_output=True, cwd=REPO)
    out = (r.stdout or b"").decode("utf-8", "replace") + (r.stderr or b"").decode("utf-8", "replace")
    return r.returncode, out


def main() -> int:
    original = TEMPLATE.read_text(encoding="utf-8")

    # 先确认「干净模板」是绿的，否则下面的失败可能来自别的 bug
    with tempfile.TemporaryDirectory() as tmp:
        clean = Path(tmp) / "clean.yml"
        clean.write_text(original, encoding="utf-8")
        code, out = run_checker(clean)
        if code != 0:
            print("基线不通过：原样模板应当被判定为一致，实际返回 " + str(code))
            print(out)
            return 1
        print(f"  基线 OK  原样模板 → 退出码 0")

        failed = 0
        for name, mutate, expect_key in MUTATIONS:
            mutated = Path(tmp) / "mutated.yml"
            mutated.write_text(mutate(original), encoding="utf-8")
            if mutated.read_text(encoding="utf-8") == original:
                # 变异没生效意味着这个「测试」什么也没测（例如正则不再匹配）
                print(f"  失效!!  {name}：注入后文件与原文一致，变异规则已过期")
                failed += 1
                continue

            code, out = run_checker(mutated)
            caught = code != 0 and expect_key in out
            status = "捕获 OK " if caught else "漏过!!  "
            print(f"  {status} {name} → 退出码 {code}，期望提到 {expect_key}")
            if not caught:
                failed += 1
                print("    校验器输出：")
                for line in out.splitlines():
                    print("      " + line)

    print()
    if failed:
        print(f"变异测试失败 {failed}/{len(MUTATIONS) + 1} —— 校验器存在盲区")
        return 1
    print(f"变异测试通过 {len(MUTATIONS)}/{len(MUTATIONS)} —— 5 类缺陷全部被捕获，校验器不是摆设")
    return 0


if __name__ == "__main__":
    sys.exit(main())
