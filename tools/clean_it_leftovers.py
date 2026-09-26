"""清掉集成测试遗留的数据（MySQL 里的消息行与测试账号 + Redis 里的集群键）。

为什么需要这个脚本：测试正常结束时会自己精确清理（按 TAG / handle），但如果断言失败、
进程被强杀、或 JVM 崩溃，清理就不会执行。残留行本身无害（都带 it 前缀），
但会污染后续人工排查时的物理表内容。

安全边界：
  * MySQL 消息：只删 `client_msg_id LIKE 'it%'` 的行，不 TRUNCATE、不 DROP；
  * MySQL 账号：只删 handle 形如 `it_...` 的 actor 及其凭据，以及
    **其 actor 行已不存在的孤儿 actor_secret**（它们永远登不上，且正是
    “清理代码只执行了一半”的产物）；
  * MySQL 会话：只删「成员行指向已不存在的 actor」与「一个成员都没有」的会话，
    以及**这些会话的消息**（按 conv_id 在 16 张物理分表上删）——
    判据是「孤儿」而不是「id 长得像测试数据」（雪花号没有可判读的前缀）；
  * Redis：只删 `tm:node:it-*`（测试节点）以及<b>值指向测试节点</b>的
    `tm:route:*`（测试用的 actorId 与真实雪花 id 区间完全不同，
    但真正可靠的判据是路由的值 —— 生产 nodeId 是「主机名:端口」或显式配置，
    不会以 `it-` 开头）；
  * Redis 刷新会话 `tm:rt:*` **不在此列**：键名是凭证的哈希，值里只有 actorId，
    无法与生产会话可靠区分。集成测试把它的 TTL 调到分钟级（AppHttpIT 里 5 分钟），
    所以残留会自己消失 —— 这比一个“看起来像能识别”的判据更安全。
  * Redis 会话序号 `tm:seq:{convId}` 同上：键名里只有 convId，
    但它的值只影响那个会话的取号，且消息插入时会自愈（see ConversationRepository#raiseSeqFloor）。

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


def clean_accounts(cur, apply: bool) -> list[int]:
    """清掉集成测试建的账号（{@code actor} + {@code actor_secret}）。

    两类行，判定依据不同：

    1. **handle 以 `it_` 开头的 actor** —— 与 message 的 `client_msg_id LIKE 'it%'`
       同一思路。AppHttpIT 用这个前缀（handle 只能是字母数字下划线，所以是下划线）。
    2. **孤儿 actor_secret**（其 actor_id 在 actor 表里已不存在）—— 它们**永远无法
       登录**：`IdentityService` 先查 actor，查不到就是 40401，那张凭据行只是死重量。
       真实成因就在本项目里：测试的清理代码曾一半按字段、一半按 handle，
       actor 行被删了而凭据行留下。保守做法是「只清测试账号关联的行」，
       但孤儿行与测试账号无关——它就是垃圾本身。

    不直接 JOIN 删除：`actor_secret` 是非分片表，而取 id → 再按 id 删是两步确定的事，
    在分片驱动下不需要把一条跨表 SQL 交给它解析。
    """
    cur.execute("SELECT id, handle FROM actor WHERE handle LIKE %s", (PREFIX + "\\_%",))
    test_actors = cur.fetchall()

    cur.execute(
        "SELECT s.actor_id FROM actor_secret s "
        "LEFT JOIN actor a ON a.id = s.actor_id WHERE a.id IS NULL")
    orphans = [r[0] for r in cur.fetchall()]

    ids = sorted({a[0] for a in test_actors} | set(orphans))
    if not ids:
        print("账号: 无遗留")
        return []

    print(f"账号: actor {len(test_actors)} 行"
          f"（handle 形如 {PREFIX}_...）、孤儿 actor_secret {len(orphans)} 行")
    for row in test_actors:
        print(f"  actor id={row[0]} handle={row[1]}")
    if not apply:
        return ids

    placeholders = ",".join(["%s"] * len(ids))
    cur.execute(f"DELETE FROM actor_secret WHERE actor_id IN ({placeholders})", tuple(ids))
    removed_secrets = cur.rowcount
    cur.execute(f"DELETE FROM actor WHERE id IN ({placeholders})", tuple(ids))
    print(f"  已删 actor {len(test_actors)} 行、actor_secret {removed_secrets} 行")
    return ids


def clean_conversations(cur, tables: list[str], apply: bool, doomed_actors: list[int]) -> int:
    """清掉集成测试遗留的会话、成员行与它们的消息。

    为什么需要它：M3 的 REST 用例会建会话（`POST /v1/conversations/*`），
    而会话表里没有任何「测试标记」列可依：`conversation` 只有
    id/conv_type/title/owner_actor/seq_counter/pair_key/created_at，
    而 id 是雪花号（没有可判读的前缀）。所以判据只能是**孤儿**：

      1. 成员行的 actor 已经不在 `actor` 表里（测试账号以 it_ 开头，先被删了）
         —— 也包括「本次将要被删的那批」，否则 dry-run 与 --apply 报的不是同一批数据；
      2. 一个成员行都没有的会话（上面那些孤儿成员行被删完之后就会出现，
         或者建会话时进程死在写成员行之前）。

    这两个判据都不会误伤真实数据：一个成员都没有的会话在任何客户端里都打不开
    （打开它就是 40303），而它的消息也永远没人能看到。

    消息必须**逐张物理分表**删：本脚本是直连 MySQL 的，没有 ShardingSphere 的
    逻辑表 `message`，只能按 conv_id 在 16 张表上各删一遍。
    """
    doomed = sorted(set(doomed_actors))
    orphan_members = count_orphan_members(cur, doomed)
    candidates = _candidate_conversations(cur, doomed)

    print(f"会话: 孤儿成员行 {orphan_members} 行、涉及会话 {len(candidates)} 个")
    if not apply:
        return orphan_members + len(candidates)

    if orphan_members:
        cur.execute(delete_orphan_members_sql(doomed), tuple(doomed))
        print(f"  已删孤儿成员行 {cur.rowcount} 行")

    # 删完孤儿成员行后重算：这一步才真正拿到「一个成员都没有的会话」
    conv_ids = _empty_conversations(cur)
    if not conv_ids:
        return orphan_members

    removed_messages = 0
    placeholders = ",".join(["%s"] * len(conv_ids))
    for table in tables:
        cur.execute(f"DELETE FROM `{table}` WHERE conv_id IN ({placeholders})", tuple(conv_ids))
        removed_messages += cur.rowcount
    cur.execute(f"DELETE FROM conversation WHERE id IN ({placeholders})", tuple(conv_ids))
    print(f"  已删无人会话 {cur.rowcount} 个（连同 {removed_messages} 条消息，"
          f"消息表 {len(tables)} 张）")
    return orphan_members + len(conv_ids)


def orphan_members_condition(doomed: list[int]) -> str:
    """孤儿成员行的 WHERE 子句：actor 已不存在，或属于本次要删的测试账号。"""
    if not doomed:
        return "a.id IS NULL"
    placeholders = ",".join(["%s"] * len(doomed))
    return f"a.id IS NULL OR cm.actor_id IN ({placeholders})"


def delete_orphan_members_sql(doomed: list[int]) -> str:
    return ("DELETE cm FROM conversation_member cm"
            " LEFT JOIN actor a ON a.id = cm.actor_id"
            f" WHERE {orphan_members_condition(doomed)}")


def count_orphan_members(cur, doomed: list[int]) -> int:
    cur.execute("SELECT COUNT(*) FROM conversation_member cm"
                " LEFT JOIN actor a ON a.id = cm.actor_id"
                f" WHERE {orphan_members_condition(doomed)}", tuple(doomed))
    return cur.fetchone()[0]


def _candidate_conversations(cur, doomed: list[int]) -> list[int]:
    """本次会被当作孤儿处理的会话 id：

    * 含「actor 已不存在」的成员行的会话；
    * 以及一个成员行都没有的会话（建到一半、或上面那类行已先被删掉）。
    """
    cur.execute("SELECT DISTINCT cm.conv_id FROM conversation_member cm"
                " LEFT JOIN actor a ON a.id = cm.actor_id"
                f" WHERE {orphan_members_condition(doomed)}", tuple(doomed))
    return sorted({row[0] for row in cur.fetchall()} | set(_empty_conversations(cur)))


def _empty_conversations(cur) -> list[int]:
    """没有任何成员行的会话 id。返回列表而不是计数：删消息时需要这些 id。"""
    cur.execute("SELECT c.id FROM conversation c"
                " LEFT JOIN conversation_member cm ON cm.conv_id = c.id"
                " WHERE cm.conv_id IS NULL")
    return [row[0] for row in cur.fetchall()]


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

            doomed_actors = clean_accounts(cur, args.apply)
            total += len(doomed_actors)
            # 会话清理排在账号之后：它的判据（成员行指向已/将不存在的 actor）
            # 正是「账号刚被删掉」的产物。
            total += clean_conversations(cur, tables, args.apply, doomed_actors)
    finally:
        conn.close()

    if not args.mysql:
        clean_redis(args.apply)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
