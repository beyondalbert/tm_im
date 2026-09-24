"""清掉集成测试遗留的数据（client_msg_id 以 it 开头的行）。

为什么需要这个脚本：测试正常结束时会用 TAG 精确清理，但如果断言失败、
进程被强杀、或 JVM 崩溃，清理就不会执行。残留行本身无害（都带 it 前缀），
但会污染后续人工排查时的物理表内容。

安全边界：只删 `client_msg_id LIKE 'it%'` 的行，不 TRUNCATE、不 DROP。
真实数据的 client_msg_id 由客户端生成，不会以 it 开头（且 msg 表的数据
只可能来自本仓库的测试与联调）。

用法：
    uv run --with pymysql python tools/clean_it_leftovers.py [--apply]

不带 --apply 时只统计不删除。
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

import pymysql

REPO_ROOT = Path(__file__).resolve().parent.parent
RUNTIME_YAML = REPO_ROOT / "deploy" / "conf" / "runtime" / "sharding.yaml"

PREFIX = "it"


def load_dsn(path: Path) -> tuple[str, int, str, str, str]:
    if not path.is_file():
        sys.exit(f"找不到 {path}\n  生成： uv run python tools/gen_runtime_config.py")
    text = path.read_text(encoding="utf-8")
    url = re.search(r"^\s*jdbcUrl:\s*(\S+)\s*$", text, re.M)
    user = re.search(r"^\s*username:\s*(\S+)\s*$", text, re.M)
    pwd = re.search(r"^\s*password:\s*(\S+)\s*$", text, re.M)
    for name, hit in (("jdbcUrl", url), ("username", user), ("password", pwd)):
        if hit is None:
            sys.exit(f"{path} 里读不到 {name}")
    m = re.search(r"//([^:/]+):(\d+)/([^?]+)", url.group(1))
    if not m:
        sys.exit(f"jdbcUrl 解析失败: {url.group(1)}")
    return m.group(1), int(m.group(2)), m.group(3), user.group(1), pwd.group(1)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true", help="真正执行删除；默认只统计")
    ap.add_argument("--config", default=str(RUNTIME_YAML))
    args = ap.parse_args()

    host, port, db, user, pwd = load_dsn(Path(args.config))
    print(f"目标: {host}:{port}/{db}  用户={user}  模式={'删除' if args.apply else '只统计'}")

    conn = pymysql.connect(host=host, port=port, user=user, password=pwd,
                           database=db, autocommit=True)
    try:
        with conn.cursor() as cur:
            cur.execute(r"SHOW TABLES LIKE 'message\_%'")
            tables = [r[0] for r in cur.fetchall()]
            if not tables:
                sys.exit("没有 message_* 物理表，先跑 tools/bootstrap_db.py")
            print(f"物理分表: {len(tables)} 张")

            total = 0
            for t in sorted(tables, key=lambda s: int(s.split("_")[1])):
                cur.execute(f"SELECT COUNT(*) FROM `{t}` WHERE client_msg_id LIKE %s",
                            (PREFIX + "%",))
                n = cur.fetchone()[0]
                if not n:
                    continue
                if args.apply:
                    cur.execute(f"DELETE FROM `{t}` WHERE client_msg_id LIKE %s",
                                (PREFIX + "%",))
                    print(f"  {t}: 清除 {n} 行")
                else:
                    print(f"  {t}: 发现 {n} 行（未删除）")
                total += n
            print(f"合计 {total} 行")
    finally:
        conn.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
