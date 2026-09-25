"""清掉集成测试遗留的数据（MySQL 里 client_msg_id 以 it 开头的行 + Redis 里的集群键）。

为什么需要这个脚本：测试正常结束时会用 TAG 精确清理，但如果断言失败、
进程被强杀、或 JVM 崩溃，清理就不会执行。残留行本身无害（都带 it 前缀），
但会污染后续人工排查时的物理表内容。

安全边界：
  * MySQL：只删 `client_msg_id LIKE 'it%'` 的行，不 TRUNCATE、不 DROP；
  * Redis：只删 `tm:node:it-*`（测试节点）以及<b>值指向测试节点</b>的
    `tm:route:*`（测试用的 actorId 与真实雪花 id 区间完全不同，
    但真正可靠的判据是路由的值 —— 生产 nodeId 是「主机名:端口」或显式配置，
    不会以 `it-` 开头）。

用法：
    uv run --with pymysql --with redis python tools/clean_it_leftovers.py [--apply] [--mysql|--redis]

不带 --apply 时只统计不删除。
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# 两个客户端都按需导入：只想清 MySQL 的人不该被迫装 redis 包（反之亦然），
# 而 ImportError 在启动时抛出会让「只是少装一个包」看起来像脚本坏了。
REPO_ROOT = Path(__file__).resolve().parent.parent
RUNTIME_YAML = REPO_ROOT / "deploy" / "conf" / "runtime" / "sharding.yaml"
RUNTIME_IT_PROPS = REPO_ROOT / "deploy" / "conf" / "runtime" / "it.properties"

PREFIX = "it"

# 集成测试的节点键前缀（见 tm-channel 的 ClusterRedisIT：nodeId = "it-<run>-<name>"）
TEST_NODE_PREFIX = "it-"


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


def load_redis_dsn(path: Path) -> tuple[str, int, str, int]:
    """it.properties 是生成器输出的扁平键值对（无占位符、无嵌套）。"""
    if not path.is_file():
        sys.exit(f"找不到 {path}\n  生成： uv run python tools/gen_runtime_config.py")
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        key, _, value = line.partition("=")
        values[key.strip()] = value
    missing = [k for k in ("redis.host", "redis.port") if k not in values]
    if missing:
        sys.exit(f"{path} 里缺少 {', '.join(missing)}")
    return (values["redis.host"], int(values["redis.port"].strip()),
            values.get("redis.password", ""), int(values.get("redis.db", "0").strip() or 0))


def scan(client, pattern: str) -> list[str]:
    """用 SCAN 而不是 KEYS：本脚本可能在开发 Redis 上跑，而 KEYS 是 O(N) 且会阻塞它。"""
    out: list[str] = []
    cursor = 0
    while True:
        cursor, keys = client.scan(cursor, match=pattern, count=500)
        out.extend(k.decode() if isinstance(k, bytes) else k for k in keys)
        if cursor == 0:
            return out


def clean_redis(apply: bool) -> int:
    import redis as redis_lib

    host, port, pwd, db = load_redis_dsn(RUNTIME_IT_PROPS)
    print(f"Redis 目标: {host}:{port}/{db}  模式={'删除' if apply else '只统计'}")
    client = redis_lib.Redis(host=host, port=port, password=pwd or None, db=db,
                             socket_timeout=5, decode_responses=False)
    try:
        node_keys = scan(client, "tm:node:" + TEST_NODE_PREFIX + "*")
        if apply:
            for key in node_keys:
                client.delete(key)
            print(f"  tm:node: 清除 {len(node_keys)} 个测试节点键")
        else:
            print(f"  tm:node: 发现 {len(node_keys)} 个测试节点键（未删除）")

        # 路由键按「值指向测试节点」判定：测试 actorId 恰好落在 9xxxxxxxxx
        # 这个与真实雪花 id 不重叠的区间，但真正可靠的判据是值里的 nodeId。
        stale_routes: list[str] = []
        for key in scan(client, "tm:route:*"):
            value = client.get(key)
            if value is None:
                continue
            if value.decode(errors="replace").startswith(TEST_NODE_PREFIX):
                stale_routes.append(key)
        if apply:
            for key in stale_routes:
                client.delete(key)
            print(f"  tm:route: 清除 {len(stale_routes)} 条指向测试节点的路由")
        else:
            print(f"  tm:route: 发现 {len(stale_routes)} 条指向测试节点的路由（未删除）")
        return len(node_keys) + len(stale_routes)
    finally:
        client.close()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true", help="真正执行删除；默认只统计")
    ap.add_argument("--config", default=str(RUNTIME_YAML), help="MySQL 的 sharding.yaml")
    ap.add_argument("--mysql", action="store_true", help="只清 MySQL")
    ap.add_argument("--redis", action="store_true", help="只清 Redis")
    args = ap.parse_args()

    if args.redis and not args.mysql:
        clean_redis(args.apply)
        return 0

    import pymysql

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

    if not args.mysql:
        clean_redis(args.apply)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
