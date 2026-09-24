#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""跑指定的集成测试类（-Pit），并按行过滤输出。

为什么要有这个脚本：mvn 的输出经过 PowerShell 管道会触发「死管」卡住，
而且控制台是 GBK，中文报错会花屏。这里用 subprocess 直接收字节、
按 UTF-8 解码（mvn 的 argLine 已强制子进程 UTF-8），再按正则留行。

用法：
    uv run --no-progress python tools/run_it.py SsProbeIT
    uv run --no-progress python tools/run_it.py ConversationSeqAllocationIT --module tm-storage
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SERVER = REPO / "server"
FILTER = re.compile(r"PROBE|\[IT\]|Tests run|BUILD|\[ERROR\]|\[WARNING\] "
                    r"|Caused by|Expecting|expected|but was|AssertionError")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("test")
    ap.add_argument("--module", default="tm-storage")
    ap.add_argument("--all", action="store_true", help="不过滤输出")
    ap.add_argument("--conf", help="覆盖 tm.it.config 指向的 sharding 配置（诊断同名问题用）")
    ap.add_argument("--props", help="覆盖 tm.it.properties（诊断用）")
    args = ap.parse_args()

    sharding = Path(args.conf) if args.conf else REPO / "deploy" / "conf" / "runtime" / "sharding.yaml"
    props = Path(args.props) if args.props else REPO / "deploy" / "conf" / "runtime" / "it.properties"
    cmd = ["cmd", "/c", "mvn", "-B", "-o", "-pl", args.module, "-am", "test", "-Pit",
           "-Dtest=" + args.test, "-Dsurefire.failIfNoSpecifiedTests=false",
           "-Dtm.it.config=" + sharding.as_posix(),
           "-Dtm.it.properties=" + props.as_posix()]
    env = dict(os.environ, PYTHONIOENCODING="utf-8", MAVEN_OPTS="-Dfile.encoding=UTF-8")
    proc = subprocess.run(cmd, cwd=SERVER, capture_output=True, env=env, timeout=1800)
    text = proc.stdout.decode("utf-8", errors="replace")
    log = SERVER / args.module / "target" / ("it-" + args.test + ".log")
    log.parent.mkdir(parents=True, exist_ok=True)
    log.write_text(text, encoding="utf-8")

    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    for line in text.splitlines():
        if args.all or FILTER.search(line):
            print(line.rstrip()[:400])
    print("exit={} 完整输出: {}".format(proc.returncode, log ))
    return 0


if __name__ == "__main__":
    sys.exit(main())
