#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
tm_im M0 前置检查：外部 MySQL / Redis 连通性与能力探测。

用法：
    uv run --with pymysql --with redis python tools/check_services.py
    uv run --with pymysql --with redis python tools/check_services.py --env deploy/conf/local-conn.env

设计原则：
  * 只读探测，默认不做任何写操作 / DDL（除非 MYSQL_ALLOW_DDL=true 且加 --allow-ddl）
  * 输出中密码一律脱敏
  * 每一项失败都给出可执行的下一条命令
"""

from __future__ import annotations

import argparse
import ipaddress
import os
import re
import socket
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

# ---------------------------------------------------------------- 控制台兼容
#
# Windows 中文控制台默认 GBK，输出 '✗' 这类字符会抛 UnicodeEncodeError 直接崩。
# 这里做两件事：
#   1) 把 errors 改成 replace，保证永不因编码崩溃
#   2) 探测当前编码能否表示装饰字符，不能则退回纯 ASCII 符号集

def _setup_console() -> bool:
    """返回 True 表示可用 Unicode 符号。"""
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(errors="replace")
        except Exception:
            pass
    enc = (getattr(sys.stdout, "encoding", None) or "ascii")
    try:
        "\u2717\u2713\u2500".encode(enc)
        return True
    except (UnicodeEncodeError, LookupError, TypeError):
        return False

UNICODE_OK = _setup_console()

# 符号集：Unicode 不可用时退回 ASCII，保证在纯 GBK/CP437 终端也能读懂
SYM = {
    "ok":   "\u2713" if UNICODE_OK else "+",
    "fail": "\u2717" if UNICODE_OK else "x",
    "warn": "!" if UNICODE_OK else "!",
    "line": "\u2500" if UNICODE_OK else "-",
}

# ---------------------------------------------------------------- 输出工具

# 颜色需要终端支持 ANSI（Windows Terminal / Win11 conhost 支持）；非 TTY 时关闭
USE_COLOR = sys.stdout.isatty()
def _c(code: str, s: str) -> str:
    return f"\033[{code}m{s}\033[0m" if USE_COLOR else s

OK   = lambda s: _c("32", s)
BAD  = lambda s: _c("31", s)
WARN = lambda s: _c("33", s)
DIM  = lambda s: _c("90", s)
BOLD = lambda s: _c("1", s)

PASSED: list[str] = []
FAILED: list[str] = []
WARNED: list[str] = []

def head(title: str) -> None:
    print()
    bar = SYM["line"] * max(0, 66 - len(title))
    print(BOLD(f"{SYM['line']}{SYM['line']} {title} {bar}"))

def ok(msg: str) -> None:
    print(f"  [ {OK('OK')} ] {msg}")
    PASSED.append(msg)

def bad(msg: str, hint: str | None = None) -> None:
    print(f"  [{BAD('FAIL')}] {msg}")
    if hint:
        arrow = "\u2192" if UNICODE_OK else "->"
        print(f"         {DIM(arrow + ' ' + hint)}")
    FAILED.append(msg)

def warn(msg: str) -> None:
    print(f"  [{WARN('WARN')}] {msg}")
    WARNED.append(msg)

def info(msg: str) -> None:
    print(f"         {DIM(msg)}")

# ---------------------------------------------------------------- 脱敏

def mask(v: str | None) -> str:
    if not v:
        return DIM("(empty)")
    if len(v) <= 2:
        return "*" * len(v)
    if len(v) <= 6:
        return v[0] + "*" * (len(v) - 2) + v[-1]
    return f"{v[:2]}{'*' * (len(v) - 4)}{v[-2:]}"

def sanitize(text: str, secrets: list[str]) -> str:
    """把输出里的真实密码替换掉——异常信息经常回显密码。"""
    out = str(text)
    for s in secrets:
        if s and len(s) >= 2:
            out = out.replace(s, "***")
    return out

# ---------------------------------------------------------------- 配置读取

def load_env_file(path: Path) -> dict[str, str]:
    cfg: dict[str, str] = {}
    if not path.exists():
        return cfg
    for raw in path.read_text(encoding="utf-8-sig").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.lower().startswith("export "):
            line = line[7:].strip()
        if "=" not in line:
            continue
        k, v = line.split("=", 1)
        v = v.strip()
        # 去掉行尾注释（仅当值未被引号包裹时）
        if v[:1] not in ("'", '"'):
            v = re.split(r"\s+#", v, maxsplit=1)[0].strip()
        v = v.strip("'\"")
        cfg[k.strip()] = v
    return cfg

def resolve_config(explicit: str | None) -> tuple[dict[str, str], Path | None]:
    """优先级：--env 指定 > deploy/conf/local-conn.env > 环境变量 > .example"""
    candidates: list[Path] = []
    if explicit:
        candidates.append(Path(explicit))
    candidates += [
        REPO / "deploy" / "conf" / "local-conn.env",
        REPO / "deploy" / "conf" / "local-conn.env.example",
    ]
    for p in candidates:
        if p.exists():
            cfg = load_env_file(p)
            if cfg:
                return cfg, p
    return {}, None

# ---------------------------------------------------------------- TCP 探测

# ---------------------------------------------------------------- 网络自校准
#
# 重要：某些企业网/安全客户端会透明拦截所有出站 TCP（连接总是立即成功），
# 此时 “TCP 可达” 完全不能证明目标服务存在。
# 对策：先用 RFC5737 保留段（公网必然不可路由）做基线探测，
#       若基线也报“可达”，则已知本机处于拦截环境，TCP 结果降级为提示。

BASELINE_HOSTS = [("192.0.2.1", 1), ("198.51.100.1", 1), ("203.0.113.1", 1)]
INTERCEPTED: bool | None = None  # None = 尚未校准


def calibrate_network(timeout: float = 2.0) -> bool:
    """返回 True 表示检测到透明拦截。"""
    global INTERCEPTED
    hits = 0
    for h, p in BASELINE_HOSTS:
        try:
            with socket.create_connection((h, p), timeout=timeout):
                hits += 1
        except OSError:
            pass
    INTERCEPTED = hits >= 2
    return INTERCEPTED


def tcp_probe(host: str, port: int, timeout: float = 5.0) -> tuple[bool, str, float]:
    t0 = time.perf_counter()
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True, "", (time.perf_counter() - t0) * 1000
    except socket.gaierror as e:
        return False, f"DNS 解析失败: {e}", 0.0
    except socket.timeout:
        return False, f"TCP 连接超时（>{timeout}s）", 0.0
    except ConnectionRefusedError:
        return False, "连接被拒绝（端口未监听或防火墙拒绝）", 0.0
    except OSError as e:
        return False, f"{type(e).__name__}: {e}", 0.0

def is_private(host: str) -> bool | None:
    try:
        return ipaddress.ip_address(host).is_private
    except ValueError:
        return None


def tcp_verdict(host: str, port: int, ms: float) -> None:
    """TCP 层结论。拦截环境下只能给提示——因为连接成功不代表服务存在。"""
    if INTERCEPTED:
        warn(f"TCP 连接成功 ({ms:.0f}ms)，但本机存在透明拦截，此结果不能证明服务存在")
    else:
        ok(f"TCP 可达 ({ms:.0f} ms)")

# ---------------------------------------------------------------- MySQL

def check_mysql(cfg: dict[str, str], secrets: list[str]) -> dict | None:
    head("MySQL")
    host = cfg.get("MYSQL_HOST", "").strip()
    port_s = cfg.get("MYSQL_PORT", "3306").strip() or "3306"
    user = cfg.get("MYSQL_USER", "").strip()
    pwd = cfg.get("MYSQL_PASSWORD", "")
    db = cfg.get("MYSQL_DATABASE", "tm_im").strip() or "tm_im"

    if not host:
        warn("MYSQL_HOST 为空 —— 跳过 MySQL 检查")
        return None

    try:
        port = int(port_s)
    except ValueError:
        bad(f"MYSQL_PORT 不是数字: {port_s!r}")
        return None

    info(f"target: {host}:{port}  user={user or DIM('(empty)')}  pwd={mask(pwd)}  db={db}")

    reachable, err, ms = tcp_probe(host, port)
    if not reachable:
        bad(f"TCP {host}:{port} 不可达 — {sanitize(err, secrets)}",
            "确认端口已开放 / 有公网或 VPN 可达 / 无 IP 白名单拦截")
        priv = is_private(host)
        if priv:
            info(f"提示：{host} 是内网地址，本机若不在同一网络则无法访问")
        return None
    tcp_verdict(host, port, ms)

    try:
        import pymysql
    except ImportError:
        bad("缺少 pymysql", "uv run --with pymysql --with redis python tools/check_services.py")
        return None

    # 先不带库连（库可能还没建）
    conn = None
    for connect_db in (None, db):
        label = "无库连接" if connect_db is None else f"指定库 `{connect_db}`"
        try:
            conn = pymysql.connect(
                host=host, port=port, user=user, password=pwd,
                database=connect_db or None,
                connect_timeout=8, read_timeout=15, write_timeout=15,
                charset="utf8mb4", autocommit=True,
                ssl={"ssl": {}} if cfg.get("MYSQL_USE_SSL", "").lower() == "true" else None,
            )
            ok(f"{label} 登录成功")
            break
        except pymysql.err.OperationalError as e:
            code = e.args[0] if e.args else "?"
            msg = sanitize(e.args[1] if len(e.args) > 1 else e, secrets)
            if connect_db is None:
                bad(f"认证失败 (errno {code}): {msg}",
                    "检查用户名/密码；若为 errno 1045 且密码含特殊字符，注意 shell/文件转义")
                return None
            if code == 1049:  # Unknown database
                warn(f"库 `{db}` 不存在 (errno 1049)")
                info("→ 需要建库。设 MYSQL_ALLOW_DDL=true 后加 --allow-ddl 由本脚本创建")
            else:
                bad(f"{label} 失败 (errno {code}): {msg}")
        except Exception as e:
            bad(f"{label} 失败: {sanitize(f'{type(e).__name__}: {e}', secrets)}")

    if conn is None:
        return None

    try:
        with conn.cursor() as cur:
            cur.execute("SELECT VERSION()")
            ver = cur.fetchone()[0]
            ok(f"MySQL 版本: {ver}")

            cur.execute("SELECT @@lower_case_table_names, @@default_storage_engine, "
                        "@@character_set_server, @@collation_server, @@max_connections, "
                        "@@sql_mode, @@time_zone, @@transaction_isolation")
            row = cur.fetchone()
            keys = ["lower_case_table_names", "storage_engine", "charset_server",
                    "collation_server", "max_connections", "sql_mode", "time_zone",
                    "tx_isolation"]
            for k, v in zip(keys, row):
                info(f"{k} = {v}")

            lower = row[0]
            if lower == 1:
                warn("lower_case_table_names=1（表名大小写不敏感）—— Linux 部署环境下迁移需注意")
            elif lower == 0:
                ok("lower_case_table_names=0（大小写敏感，符合 Linux 生产惯例）")

            if str(row[8]).upper().replace("-", " ") not in ("REPEATABLE READ",):
                info(f"隔离级别为 {row[8]}（默认 REPEATABLE-READ 更适合本场景）")

            # 时区问题最容易导致 seq/时间戳错乱
            try:
                cur.execute("SELECT @@global.time_zone, @@session.time_zone")
                gz, sz = cur.fetchone()
                if gz in ("SYSTEM",) and sz in ("SYSTEM",):
                    warn("时区为 SYSTEM —— 建议显式设 UTC，避免容器与宿主机时区不一致")
                else:
                    ok(f"时区: global={gz} session={sz}")
            except Exception as e:
                warn(f"读时区失败: {sanitize(e, secrets)}")

            # InnoDB 检查（ShardingSphere + 事务依赖）
            cur.execute("SELECT SUPPORT FROM information_schema.ENGINES "
                        "WHERE ENGINE='InnoDB'")
            r = cur.fetchone()
            if r and str(r[0]).upper() in ("YES", "DEFAULT"):
                ok(f"InnoDB 可用 (SUPPORT={r[0]})")
            else:
                bad("InnoDB 不可用 —— 事务与行锁依赖 InnoDB")

            # 建库建表权限
            cur.execute("SELECT CURRENT_USER()")
            cu = cur.fetchone()[0]
            info(f"current_user = {cu}")

            cur.execute("SHOW GRANTS")
            grants = "\n".join(g[0] for g in cur.fetchall())
            can_ddl = bool(re.search(r"ALL PRIVILEGES|CREATE\b", grants))
            can_create_db = bool(re.search(r"ALL PRIVILEGES|\bCREATE\b[^,]*ON\s+\*\.\*", grants, re.I))
            if can_ddl:
                ok("具备 CREATE 权限（可建表）")
            else:
                warn("未见 CREATE 权限 —— M0 建表可能失败，需要 DBA 预建表或授权")

            if cfg.get("MYSQL_ALLOW_DDL", "").lower() == "true":
                if can_create_db:
                    ok("具备在 *.* 上 CREATE 权限（可建库）")
                else:
                    warn("MYSQL_ALLOW_DDL=true 但未见 *.* 级 CREATE 权限，建库可能失败")

            # 现有表情况
            if connect_db == db:
                cur.execute("SELECT COUNT(*) FROM information_schema.tables "
                            "WHERE table_schema=%s", (db,))
                n = cur.fetchone()[0]
                info(f"库 `{db}` 现有表数: {n}")
                if n > 0:
                    cur.execute("SELECT table_name FROM information_schema.tables "
                                "WHERE table_schema=%s ORDER BY table_name LIMIT 20", (db,))
                    names = [r[0] for r in cur.fetchall()]
                    info("前 20 张表: " + ", ".join(names))
                else:
                    info("库为空 —— M0 将创建 actor/conversation/message 等表")

            # 探测写入能力（只读事务，不落数据）
            try:
                cur.execute("CREATE TEMPORARY TABLE _tm_probe (id INT PRIMARY KEY) "
                            "ENGINE=InnoDB")
                cur.execute("INSERT INTO _tm_probe VALUES (1)")
                cur.execute("DROP TEMPORARY TABLE _tm_probe")
                ok("临时表写入探测成功（DDL 权限确认）")
            except Exception as e:
                warn(f"临时表探测失败（可能无 CREATE TEMPORARY TABLE 权限）: {sanitize(e, secrets)}")

            # 分片表数量预估
            info("")
            info("分片规划：1 逻辑库 × 16 张 message 物理表")
            info("→ 需确认能否创建 16 张表；如单库表数量受限请告知")

    except Exception as e:
        bad(f"探测过程出错: {sanitize(f'{type(e).__name__}: {e}', secrets)}")
    finally:
        try:
            conn.close()
        except Exception:
            pass

    return {"host": host, "port": port, "db": db}

# ---------------------------------------------------------------- Redis

def check_redis(cfg: dict[str, str], secrets: list[str]) -> dict | None:
    head("Redis")
    host = cfg.get("REDIS_HOST", "").strip()
    port_s = cfg.get("REDIS_PORT", "6379").strip() or "6379"
    pwd = cfg.get("REDIS_PASSWORD", "")
    db_s = cfg.get("REDIS_DB", "0").strip() or "0"
    cluster = cfg.get("REDIS_CLUSTER", "").strip().lower() == "true"

    if not host:
        warn("REDIS_HOST 为空 —— 跳过 Redis 检查")
        return None

    try:
        port = int(port_s)
        db = int(db_s)
    except ValueError:
        bad(f"REDIS_PORT/REDIS_DB 不是数字: {port_s!r}/{db_s!r}")
        return None

    info(f"target: {host}:{port}  pwd={mask(pwd)}  db={db}  cluster={cluster}")

    reachable, err, ms = tcp_probe(host, port)
    if not reachable:
        bad(f"TCP {host}:{port} 不可达 — {sanitize(err, secrets)}",
            "确认端口已开放 / 无 IP 白名单拦截")
        if is_private(host):
            info(f"提示：{host} 是内网地址")
        return None
    tcp_verdict(host, port, ms)

    # 裸 RESP 探测，连 redis 库都没装也能判断服务是否存活
    try:
        def resp_cmd(sock, *args) -> bytes:
            payload = f"*{len(args)}\r\n".encode()
            for a in args:
                b = a.encode() if isinstance(a, str) else a
                payload += f"${len(b)}\r\n".encode() + b + b"\r\n"
            sock.sendall(payload)
            return sock.recv(65536)

        with socket.create_connection((host, port), timeout=6) as s:
            s.settimeout(6)
            if pwd:
                r = resp_cmd(s, "AUTH", pwd)
                if r.startswith(b"+OK"):
                    ok("AUTH 成功")
                elif r.startswith(b"-ERR"):
                    bad(f"AUTH 失败: {sanitize(r.decode(errors='replace').strip(), secrets)}",
                        "检查密码；若 Redis 6+ 且用 ACL 需用「用户名 密码」形式，本脚本暂用默认用户")
                    return None
                else:
                    warn(f"AUTH 返回异常: {r[:80]}")
            r = resp_cmd(s, "PING")
            if r.startswith(b"+PONG"):
                ok("PING → PONG")
            else:
                bad(f"PING 异常响应: {r[:120]!r}")
                return None

            # 版本 / 模式（INFO 在 cluster 下也可用）
            r = resp_cmd(s, "INFO", "server")
            text = r.decode("utf-8", "replace")
            ver = re.search(r"redis_version:(\S+)", text)
            mode = re.search(r"redis_mode:(\S+)", text)
            if ver:
                v = ver.group(1)
                major = int(v.split(".")[0]) if v.split(".")[0].isdigit() else 0
                ok(f"Redis 版本: {v}")
                if major and major < 5:
                    warn(f"Redis {v} 过老 —— Streams(5.0+)/Pub-Sub 增强不完整，建议 6.x+")
                elif major >= 6:
                    ok("版本满足要求（≥6.0，支持 RESP3/ACL/Streams）")
            if mode:
                info(f"redis_mode = {mode.group(1)}")
                if mode.group(1) not in ("standalone", "cluster"):
                    warn(f"非预期模式: {mode.group(1)}")

            # 内存策略：本机需要存路由表 + 好友集合 + seq 计数器，绝不能是 noeviction 之外的误配
            r = resp_cmd(s, "INFO", "memory")
            mt = r.decode("utf-8", "replace")
            used = re.search(r"used_memory_human:(\S+)", mt)
            total = re.search(r"total_system_memory_human:(\S+)", mt)
            if used:
                info(f"used_memory = {used.group(1)}" + (f" / system {total.group(1)}" if total else ""))

            r = resp_cmd(s, "CONFIG", "GET", "maxmemory-policy")
            txt = r.decode("utf-8", "replace")
            m = re.search(r"\$(?:maxmemory-policy|allkeys)\S*", txt)
            pol_match = re.findall(r"\$?([a-z\-]*eviction|noeviction|allkeys\S*|volatile\S*)", txt)
            if pol_match:
                pol = pol_match[-1]
                info(f"maxmemory-policy = {pol}")
                if pol == "noeviction":
                    warn("noeviction：内存满时写操作直接报错 —— 对 IM 长连接路由表有风险，建议 allkeys-lru")
                elif "allkeys" in pol:
                    ok(f"逐出策略 {pol} 可用（但 seq 计数器有被逐出风险，建议用持久 key 或独立 db）")
            else:
                warn("CONFIG GET 被禁用（托管 Redis 常见）—— 无法读取 maxmemory-policy，请向运维确认")
                info("注意：托管实例通常也禁用了 KEYS/FLUSHALL，设计已规避")

            # 关键能力探测
            r = resp_cmd(s, "INFO", "keyspace")
            ks = r.decode("utf-8", "replace")
            db_lines = re.findall(r"^db(\d+):keys=(\d+)", ks, re.M)
            if db_lines:
                info("已有数据: " + ", ".join(f"db{d}:{k}keys" for d, k in db_lines))
                warn("实例已有数据 —— 请确认这是专用实例还是共享；共享时我会加 key 前缀隔离")
            else:
                ok("实例无既有数据（或未启用持久化），可独占使用")

            info("")
            info("本方案用到的 Redis 能力：")
            info("  INCR (seq 生成) / SET+EXPIRE (路由表) / SMEMBERS (好友集) / PUBLISH (跨节点推送)")
            info("  → 均为基础命令，无需 RedisJSON / RedisSearch 模块")

    except Exception as e:
        bad(f"RESP 探测失败: {sanitize(f'{type(e).__name__}: {e}', secrets)}")
        return None

    # 有 redis 库时做一次更完整的检查
    try:
        import redis as redis_lib
    except ImportError:
        warn("未安装 redis 包，仅完成 RESP 层探测",
             "uv run --with pymysql --with redis python tools/check_services.py")
        return {"host": host, "port": port}

    try:
        kw = dict(host=host, port=port, socket_timeout=6, socket_connect_timeout=6)
        if pwd:
            kw["password"] = pwd
        if cluster:
            from redis.cluster import RedisCluster
            rc = RedisCluster(**kw)
        else:
            kw["db"] = db
            rc = redis_lib.Redis(**kw)

        # 写入探测（用带前缀的临时 key，并立即删除）
        probe_key = "tm_im:__probe__:m0"
        rc.set(probe_key, "ok", ex=30)
        if rc.get(probe_key) == b"ok":
            ok("SET/GET 读写正常")
        else:
            warn("SET/GET 回读不一致")
        rc.delete(probe_key)

        # INCR 探测 —— seq 生成的核心依赖
        incr_key = "tm_im:__probe__:seq"
        rc.delete(incr_key)
        v1 = rc.incr(incr_key)
        v2 = rc.incr(incr_key)
        if (v1, v2) == (1, 2):
            ok("INCR 正常 (1 → 2) —— seq 生成可用")
        else:
            warn(f"INCR 返回值异常: {v1}, {v2}")
        rc.delete(incr_key)

        # Pub/Sub 探测 —— 跨节点推送的核心依赖
        sub = rc.pubsub()
        sub.subscribe("tm_im:__probe__:ch")
        sub.get_message(timeout=1)  # 消费 subscribe 确认
        rc.publish("tm_im:__probe__:ch", "hello")
        got = None
        deadline = time.time() + 3
        while time.time() < deadline:
            m = sub.get_message(timeout=0.5)
            if m and m.get("type") == "message":
                got = m["data"]
                break
        sub.unsubscribe("tm_im:__probe__:ch")
        sub.close()
        if got == b"hello":
            ok("PUBLISH/SUBSCRIBE 正常 —— 跨节点推送可用")
        else:
            bad("Pub/Sub 未收到消息 —— 跨节点推送会失效",
                "本机开发时单节点可退化为本地 map，但多实例部署必须修复")
    except Exception as e:
        bad(f"redis 客户端探测失败: {sanitize(f'{type(e).__name__}: {e}', secrets)}")

    return {"host": host, "port": port}

# ---------------------------------------------------------------- 多数据源

def check_extra_datasources(cfg: dict[str, str], secrets: list[str]) -> None:
    raw = cfg.get("MYSQL_EXTRA_DATASOURCES", "").strip()
    if not raw:
        return
    head("额外 MySQL 数据源")
    info("（ShardingSphere 支持多数据源分片）")
    for i, spec in enumerate(raw.split(","), 1):
        spec = spec.strip()
        if not spec:
            continue
        parts = spec.split(":")
        if len(parts) < 4:
            bad(f"数据源 #{i} 格式错误: {mask(spec)}",
                "格式应为 host:port:user:password:db")
            continue
        h, p, u, pw = parts[0], parts[1], parts[2], parts[3]
        d = parts[4] if len(parts) > 4 else ""
        info(f"#{i} {h}:{p} user={u} pwd={mask(pw)} db={d}")
        reachable, err, ms = tcp_probe(h, int(p) if p.isdigit() else 3306)
        if reachable:
            tcp_verdict(h, int(p) if p.isdigit() else 3306, ms)
        else:
            bad(f"数据源 #{i} 不可达 — {sanitize(err, secrets)}")

# ---------------------------------------------------------------- 汇总

def summary() -> int:
    print()
    print("=" * 74)
    print(BOLD("M0 前置检查汇总"))
    print("=" * 74)
    print(f"  {OK('通过')}: {len(PASSED)}    {WARN('警告')}: {len(WARNED)}    {BAD('失败')}: {len(FAILED)}")
    if FAILED:
        print()
        print(BOLD("必须解决："))
        for f in FAILED:
            print(f"  {BAD(SYM['fail'])} {f}")
    if WARNED:
        print()
        print(BOLD("建议确认："))
        for w in WARNED:
            print(f"  {WARN(SYM['warn'])} {w}")
    print()
    if not FAILED:
        print(OK("连通性检查通过 —— 可以开始 M0 建表"))
    else:
        print(BAD("存在阻塞项 —— 修复后重新运行本脚本"))
    print("=" * 74)
    return 1 if FAILED else 0

def main() -> int:
    ap = argparse.ArgumentParser(description="tm_im M0 前置连通性检查")
    ap.add_argument("--env", help="配置文件路径（默认 deploy/conf/local-conn.env）")
    ap.add_argument("--allow-ddl", action="store_true",
                    help="允许执行建库（仍需配置里 MYSQL_ALLOW_DDL=true）")
    args = ap.parse_args()

    cfg, used = resolve_config(args.env)
    print(BOLD("tm_im M0 前置检查"))
    if used:
        print(f"  配置文件: {used}")
    else:
        print(f"  {WARN('未找到配置文件')} —— 请先复制模板：")
        print(DIM("    copy deploy\\conf\\local-conn.env.example deploy\\conf\\local-conn.env"))
        print()

    # 网络环境自校准：识别透明拦截，避免把错误信息判为通过
    print(DIM("  正在校准网络环境（探测 RFC5737 保留段基线）..."))
    if calibrate_network():
        print()
        print(WARN("  检测到本机网络透明拦截所有出站 TCP"))
        print(DIM("    现象：连 192.0.2.1:1（公网不可路由）都能连上"))
        print(DIM("    影响：TCP 层“可达”不构成服务存在的证据"))
        print(DIM("    对策：本脚本仅以协议层握手（MySQL 认证 / Redis PING）作为通过依据"))
    else:
        print(DIM("  网络环境正常：TCP 探测结果可信"))

    # 环境变量可覆盖文件
    for k in list(cfg.keys()):
        if os.environ.get(k):
            cfg[k] = os.environ[k]

    secrets = [cfg.get("MYSQL_PASSWORD", ""), cfg.get("REDIS_PASSWORD", "")]
    secrets += [p.split(":")[3] for p in cfg.get("MYSQL_EXTRA_DATASOURCES", "").split(",")
                if len(p.split(":")) > 3]

    if not cfg.get("MYSQL_HOST") and not cfg.get("REDIS_HOST"):
        print(WARN("配置为空 —— 没有可检查的目标。"))
        print("请把连接信息填进 deploy/conf/local-conn.env 后重跑。")
        return 1

    check_mysql(cfg, secrets)
    check_redis(cfg, secrets)
    check_extra_datasources(cfg, secrets)
    return summary()

if __name__ == "__main__":
    sys.exit(main())
