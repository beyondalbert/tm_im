#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
tm_im M0 连接诊断：回答"为什么连不上"，而不仅是"能不能连上"。

与 check_services.py 的分工：
    check_services.py   判定   —— 能不能开工（通过/失败 + 影响）
    diag_conn.py        定位   —— 失败的确切原因（区分四类问题）

四类问题及其判别依据：
    A. 本机网络异常        —— 基线探测（RFC5737 保留段）也"可连"
    B. 端口被防火墙过滤    —— TCP 连接超时（无响应）
    C. 服务未在该端口监听  —— TCP 立即 refused
    D. 凭据/账号问题       —— TCP 通且拿到真实协议响应，但认证被拒
                              MySQL errno 1045 = 凭据错
                              MySQL errno 1130 = 该 IP 不在账号允许范围
                              Redis -ERR/WRONGPASS = 密码错
                              Redis -NOAUTH      = 需要密码却没给

   用法:
    uv run --with pymysql --with cryptography python tools/diag_conn.py
    uv run --with pymysql --with cryptography python tools/diag_conn.py --mysql-only

   本机装有 aTrust / v2ray，出站流量被接管，TCP 探测可能失真。
   此时加 --external 借 check-host.net 全球节点复核（本工具在检出端口级失败时
   会自动触发，除非显式 --no-external）。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import socket
import ssl
import struct
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent


# ------------------------------------------------------------------ 输出

def _setup():
    for s in (sys.stdout, sys.stderr):
        try:
            s.reconfigure(errors="replace")
        except Exception:
            pass
    enc = getattr(sys.stdout, "encoding", None) or "ascii"
    try:
        "\u2713\u2717\u2500".encode(enc)
        return True
    except Exception:
        return False


UNICODE_OK = _setup()
BAR = "\u2500" if UNICODE_OK else "-"
ARROW = "\u2192" if UNICODE_OK else "->"
MARK_OK = "\u2713" if UNICODE_OK else "+"
MARK_BAD = "\u2717" if UNICODE_OK else "x"
MARK_WARN = "!" if UNICODE_OK else "!"

USE_COLOR = sys.stdout.isatty()
def _c(code, s): return f"\033[{code}m{s}\033[0m" if USE_COLOR else s
OK, BAD, WARN, DIM, BOLD = (_c("32", "") or (lambda s: _c("32", s))), _c("31", ""), None, _c("90", ""), _c("1", "")
OK = lambda s: _c("32", s)
BAD = lambda s: _c("31", s)
WARN = lambda s: _c("33", s)
DIM = lambda s: _c("90", s)
BOLD = lambda s: _c("1", s)

FINDINGS: list[tuple[str, str, str]] = []   # (级别, 标题, 结论)
TCP_FAILED_PORTS: list[int] = []            # TCP 层失败的端口，供外部视角复核


def head(t):
    print()
    print(BOLD(f"{BAR}{BAR} {t} " + BAR * max(0, 66 - len(t))))


def ok(m):    print(f"  [ {OK('OK')} ] {m}")
def bad(m):   print(f"  [{BAD('FAIL')}] {m}")
def warn(m):  print(f"  [{WARN('WARN')}] {m}")
def info(m):  print(f"         {DIM(m)}")

def finding(level, title, concl):
    FINDINGS.append((level, title, concl))


# ------------------------------------------------------------------ 配置

def load_env(path: Path) -> dict[str, str]:
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
        if v[:1] not in ("'", '"'):
            v = re.split(r"\s+#", v, maxsplit=1)[0].strip()
        cfg[k.strip()] = v.strip("'\"")
    return cfg


def fingerprint(name: str, val: str) -> None:
    """打印凭据指纹 —— 用于确认文件没被编辑器/剪贴板改动，同时不泄露原文。"""
    if not val:
        info(f"{name}: 空")
        return
    classes = []
    if any(c.islower() for c in val): classes.append("a-z")
    if any(c.isupper() for c in val): classes.append("A-Z")
    if any(c.isdigit() for c in val): classes.append("0-9")
    other = sorted({c for c in val if not c.isalnum()})
    if other: classes.append("符号" + "".join(other))
    if val != val.strip():
        classes.append("!!含首尾空白!!")
    if any(c.isspace() for c in val):
        classes.append("!!含内部空白!!")
    h = hashlib.sha256(val.encode("utf-8")).hexdigest()[:10]
    info(f"{name}: 长度={len(val)} 字符集={','.join(classes)} sha256[:10]={h}")


# ------------------------------------------------------------------ 网络基线

BASELINE = [("192.0.2.1", 1), ("198.51.100.1", 1), ("10.255.255.1", 1)]


def net_baseline():
    head("网络基线（判断 TCP 探测本身是否可信）")
    info("RFC5737 保留段 —— 公网必然不可路由，正常情况下必须连不上")
    intercept = 0
    for h, p in BASELINE:
        t0 = time.perf_counter()
        try:
            with socket.create_connection((h, p), timeout=2):
                ms = (time.perf_counter() - t0) * 1000
                print(f"         {h}:{p}  {BAD('竟然连上了')} ({ms:.0f}ms)")
                intercept += 1
        except OSError as e:
            print(f"         {h}:{p}  正常拒绝 ({type(e).__name__})")

    info("")
    info("对照组：一个真实可达的主机")
    for h, p in [("github.com", 443), ("1.1.1.1", 443)]:
        t0 = time.perf_counter()
        try:
            with socket.create_connection((h, p), timeout=5):
                print(f"         {h}:{p}  可连 ({(time.perf_counter()-t0)*1000:.0f}ms)")
        except OSError as e:
            print(f"         {h}:{p}  {type(e).__name__}: {e}")

    info("")
    info("对照组：一个真实存在但必然没有服务的地址")
    for h, p in [("1.1.1.1", 33999)]:
        t0 = time.perf_counter()
        try:
            with socket.create_connection((h, p), timeout=4):
                print(f"         {h}:{p}  {BAD('竟然连上了')} —— 强烈提示存在透明拦截")
                intercept += 1
        except OSError as e:
            print(f"         {h}:{p}  {type(e).__name__} (正常)")

    if intercept > 0:
        warn("检测到透明拦截：TCP 层结果不可作为服务存在的证据")
        finding("warn", "透明拦截", "TCP 探测不可信，判定须以协议层握手为准")
    else:
        ok("基线正常：TCP 探测结果可信")
        finding("ok", "网络", "无透明拦截，TCP 结果可信")
    return intercept > 0


# ------------------------------------------------------------------ MySQL

def mysql_greeting(host: str, port: int, timeout: float = 8.0) -> dict | None:
    """裸读服务端握手包 —— 确认对端是真 MySQL，并取版本与认证插件。"""
    try:
        with socket.create_connection((host, port), timeout=timeout) as s:
            s.settimeout(timeout)
            hdr = b""
            while len(hdr) < 4:
                chunk = s.recv(4 - len(hdr))
                if not chunk:
                    return None
                hdr += chunk
            length = struct.unpack("<I", hdr[:3] + b"\x00")[0]
            body = b""
            while len(body) < length:
                chunk = s.recv(length - len(body))
                if not chunk:
                    break
                body += chunk
            if not body:
                return None
            proto_ver = body[0]
            rest = body[1:]
            nul = rest.find(b"\x00")
            server_ver = rest[:nul].decode("latin-1")
            tail = rest[nul + 1:]
            # cap_flags 之后是 auth plugin 名
            plugin = ""
            m = re.search(rb"(mysql_native_password|caching_sha2_password|sha256_password|"
                          rb"mysql_clear_password|auth_socket)", tail)
            if m:
                plugin = m.group(1).decode()
            cap = struct.unpack("<H", tail[:2])[0] if len(tail) >= 2 else 0
            return {"proto": proto_ver, "version": server_ver, "plugin": plugin,
                    "cap_low": cap, "thread_id": struct.unpack("<I", (body[6:10] + b"\0\0\0\0")[:4])[0]}
    except Exception as e:
        info(f"握手包读取失败: {type(e).__name__}: {e}")
        return None


def check_mysql(cfg: dict[str, str]) -> None:
    head("MySQL")
    host = cfg.get("MYSQL_HOST", "").strip()
    if not host:
        warn("MYSQL_HOST 为空，跳过")
        return
    port = int(cfg.get("MYSQL_PORT", "3306") or 3306)
    user = cfg.get("MYSQL_USER", "").strip()
    pwd = cfg.get("MYSQL_PASSWORD", "")
    db = cfg.get("MYSQL_DATABASE", "tm_im").strip() or "tm_im"

    info(f"target = {host}:{port}  user={user}  db={db}")
    fingerprint("password", pwd)

    # 1) 服务端握手 —— 证明对端是真 MySQL 而不是代理伪造
    g = mysql_greeting(host, port)
    info("")
    if g:
        ok("收到真实 MySQL 握手包 —— 对端是真 MySQL 服务，网络路径通")
        info(f"  协议版本 = {g['proto']}")
        info(f"  服务端版本 = {g['version']}")
        info(f"  认证插件 = {g['plugin'] or '(未解析)'}")
        info(f"  连接线程 id = {g['thread_id']}")
        if g["plugin"] in ("caching_sha2_password", "sha256_password"):
            info(f"  注意：{g['plugin']} 在非 TLS 连接下需要 RSA 公钥交换，")
            info("        客户端须装 cryptography，否则会报混淆性错误")
        finding("ok", "MySQL 可达", f"真实 MySQL {g['version']}，插件 {g['plugin']}")
    else:
        bad("未收到 MySQL 握手包 —— 对端可能不是 MySQL，或连接被中断")
        finding("fail", "MySQL 握手", "未收到真实 MySQL 握手包")
        return

    # 2) 认证
    info("")
    try:
        import pymysql
    except ImportError:
        bad("缺少 pymysql")
        return

    have_crypto = True
    try:
        import cryptography  # noqa: F401
    except ImportError:
        have_crypto = False
        warn("未安装 cryptography —— caching_sha2_password 可能无法完成认证")
        info("  " + ARROW + " 用 --with cryptography 重跑以排除此变量")

    for label, use_db in (("不指定库", None), (f"指定库 {db}", db)):
        try:
            conn = pymysql.connect(
                host=host, port=port, user=user, password=pwd,
                database=use_db,
                connect_timeout=10, read_timeout=20, write_timeout=20,
                charset="utf8mb4", autocommit=True,
                ssl=None,
            )
            with conn.cursor() as cur:
                cur.execute("SELECT VERSION(), CURRENT_USER(), USER()")
                v, cu, u = cur.fetchone()
            conn.close()
            ok(f"{label} 认证成功")
            info(f"  VERSION()={v}")
            info(f"  CURRENT_USER()={cu}   USER()={u}")
            finding("ok", "MySQL 认证", f"{label} 成功，账号 {cu} (cryptography={have_crypto})")
            return
        except pymysql.err.OperationalError as e:
            code = e.args[0] if e.args else "?"
            msg = e.args[1] if len(e.args) > 1 else str(e)
            if code == 1045:
                bad(f"{label} 认证被拒 (errno 1045): {msg}")
            elif code == 1130:
                bad(f"{label} 主机不允许 (errno 1130): {msg}")
            elif code == 1049:
                warn(f"{label} 库不存在 (errno 1049)")
            else:
                bad(f"{label} 失败 (errno {code}): {msg}")
        except Exception as e:
            bad(f"{label} 失败: {type(e).__name__}: {e}")

    # 3) 结论推导
    info("")
    info("errno 语义（MySQL 官方行为）：")
    info("  1130 = 该来源 IP 不在任何账号的允许范围  " + ARROW + " 需要 DBA 授权 IP")
    info("  1045 = 账号存在但凭据不匹配，或账号不存在 " + ARROW + " 密码/用户名需核对")
    finding("fail", "MySQL 认证", f"凭据被拒；服务端真实存在，网络无问题")


# ------------------------------------------------------------------ Redis

def redis_resp(sock, *args) -> bytes:
    payload = f"*{len(args)}\r\n".encode()
    for a in args:
        b = a.encode() if isinstance(a, str) else a
        payload += f"${len(b)}\r\n".encode() + b + b"\r\n"
    sock.sendall(payload)
    return sock.recv(65536)


def check_redis(cfg: dict[str, str]) -> None:
    head("Redis")
    host = cfg.get("REDIS_HOST", "").strip()
    if not host:
        warn("REDIS_HOST 为空，跳过")
        return
    port = int(cfg.get("REDIS_PORT", "6379") or 6379)
    pwd = cfg.get("REDIS_PASSWORD", "")
    db = cfg.get("REDIS_DB", "0") or "0"

    info(f"target = {host}:{port}  db={db}")
    fingerprint("password", pwd)

    # 1) TCP 可达性 —— 递增超时，区分 timeout（过滤）与 refused（未监听）
    info("")
    info("TCP 探测（递增超时，用于区分「被过滤」与「未监听」）：")
    conn_ok = False
    last_err = ""
    for tmo in (3.0, 8.0, 15.0):
        t0 = time.perf_counter()
        try:
            s = socket.create_connection((host, port), timeout=tmo)
            ms = (time.perf_counter() - t0) * 1000
            ok(f"连接成功 ({ms:.0f}ms, timeout={tmo}s)")
            conn_ok = True
            break
        except socket.timeout:
            last_err = "timeout"
            print(f"         {tmo}s 超时（无任何响应）")
        except ConnectionRefusedError:
            last_err = "refused"
            print(f"         立即被拒绝 (refused)")
            break
        except OSError as e:
            last_err = type(e).__name__
            print(f"         {type(e).__name__}: {e}")

    if not conn_ok:
        info("")
        TCP_FAILED_PORTS.append(port)
        if last_err == "timeout":
            bad("TCP 连接超时 —— 端口被防火墙过滤，或服务未对公网监听")
            info(f"  {ARROW} 同一主机的 3306 是通的，说明网络路径本身没问题")
            info(f"  {ARROW} 请在服务器上确认：")
            info("      1. redis-server 是否在跑（ps -ef | grep redis-server）")
            info("      2. 监听地址是否为 0.0.0.0（bind 127.0.0.1 只允许本机）")
            info("      3. 云安全组 / firewalld / iptables 是否放行 6379")
            info(f"      ss -lntp | grep 6379")
            finding("fail", "Redis 端口", "6379 超时：被防火墙过滤或仅监听 127.0.0.1")
        else:
            bad(f"TCP 连接失败：{last_err}")
            finding("fail", "Redis 端口", f"连接失败：{last_err}")
        return

    # 2) 协议握手
    try:
        with socket.create_connection((host, port), timeout=10) as s:
            s.settimeout(10)
            r = redis_resp(s, "PING")
            if r.startswith(b"-NOAUTH") or r.startswith(b"-ERR Client sent AUTH"):
                info("服务要求认证（-NOAUTH）—— 说明密码未生效或未发送")
                if pwd:
                    ra = redis_resp(s, "AUTH", pwd)
                    if ra.startswith(b"+OK"):
                        ok("AUTH 成功")
                        r = redis_resp(s, "PING")
                    elif b"WRONGPASS" in ra or b"invalid password" in ra:
                        bad("AUTH 失败：密码错误")
                        finding("fail", "Redis 认证", "密码错误（WRONGPASS）")
                        return
                    else:
                        bad(f"AUTH 异常: {ra[:120]!r}")
                        finding("fail", "Redis 认证", f"AUTH 异常 {ra[:80]!r}")
                        return
            elif pwd:
                ra = redis_resp(s, "AUTH", pwd)
                if ra.startswith(b"+OK"):
                    ok("AUTH 成功")
                elif b"WRONGPASS" in ra or b"invalid password" in ra:
                    bad(f"AUTH 失败: {ra[:120]!r}")
                    finding("fail", "Redis 认证", "密码错误（WRONGPASS）")
                    return
                elif ra.startswith(b"-ERR"):
                    warn(f"AUTH 返回: {ra[:120]!r}")
                r = redis_resp(s, "PING")

            if r.startswith(b"+PONG"):
                ok("PING " + ARROW + " PONG")
            else:
                warn(f"PING 响应异常: {r[:120]!r}")

            if db != "0":
                rs = redis_resp(s, "SELECT", db)
                if rs.startswith(b"+OK"):
                    ok(f"SELECT {db} 成功")
                else:
                    warn(f"SELECT {db}: {rs[:80]!r}")

            ri = redis_resp(s, "INFO", "server").decode("utf-8", "replace")
            v = re.search(r"redis_version:(\S+)", ri)
            md = re.search(r"redis_mode:(\S+)", ri)
            if v:
                info(f"redis_version = {v.group(1)}")
            if md:
                info(f"redis_mode    = {md.group(1)}")

            rb = redis_resp(s, "INFO", "memory").decode("utf-8", "replace")
            um = re.search(r"used_memory_human:(\S+)", rb)
            ts = re.search(r"total_system_memory_human:(\S+)", rb)
            if um:
                info(f"used_memory = {um.group(1)}" + (f" / {ts.group(1)}" if ts else ""))

            rp = redis_resp(s, "CONFIG", "GET", "maxmemory-policy").decode("utf-8", "replace")
            pm = re.findall(r"(noeviction|allkeys-[a-z]+|volatile-[a-z]+)", rp)
            if pm:
                info(f"maxmemory-policy = {pm[-1]}")
            else:
                info("maxmemory-policy = (CONFIG 被禁用，托管实例常见)")

            rk = redis_resp(s, "INFO", "keyspace").decode("utf-8", "replace")
            ks = re.findall(r"^db(\d+):keys=(\d+)", rk, re.M)
            if ks:
                info("keyspace: " + ", ".join(f"db{d}={k}keys" for d, k in ks))
            else:
                info("keyspace: 空")

            # 写探针（带前缀，立即删除）
            pk = "tm_im:__diag__:probe"
            redis_resp(s, "SET", pk, "ok", "EX", "30")
            gv = redis_resp(s, "GET", pk)
            redis_resp(s, "DEL", pk)
            if b"ok" in gv:
                ok("SET/GET 正常")
            ik = "tm_im:__diag__:seq"
            redis_resp(s, "DEL", ik)
            i1 = redis_resp(s, "INCR", ik)
            i2 = redis_resp(s, "INCR", ik)
            redis_resp(s, "DEL", ik)
            if i1 == b":1\r\n" and i2 == b":2\r\n":
                ok("INCR 正常 (1 " + ARROW + " 2)  " + ARROW + " seq 生成可用")
            else:
                warn(f"INCR 异常: {i1!r} {i2!r}")

            # TLS 支持
            rl = redis_resp(s, "CONFIG", "GET", "tls-port").decode("utf-8", "replace")
            if "tls-port" in rl:
                vals = re.findall(r"\$?\r?\n?(\d{2,5})", rl.split("tls-port")[-1])
                info(f"tls-port = {vals[0] if vals else '未设置'}")

        finding("ok", "Redis", "连通且认证通过，基础命令可用")
    except Exception as e:
        bad(f"协议探测失败: {type(e).__name__}: {e}")
        finding("fail", "Redis", f"协议探测失败 {type(e).__name__}")


# ------------------------------------------------------------------ 外部视角验证
#
# 为什么必须有这个：
#   本机装着 Sangfor aTrust（零信任 SASE）与 v2ray，出站流量被大量接管。
#   实测：连 RFC5737 保留段 192.0.2.1:1（公网必不可能路由）都能 "连上"，
#   而且仅靠 TCP 探测无法区分"远端拒绝"与"本机拦截"。
#
#   对策：借 check-host.net 的全球节点，从**完全独立的网络视角**验证端口。
#   这是绕开本机一切代理/安全客户端的唯一可靠办法。
#
#   实战价值：曾用它确认 6379 在全球 14 个节点全部被 RST，
#   从而判定"远端确实没监听"，而非本机拦截。

EXTERNAL_API = "https://check-host.net"


def _ext_api(path: str, timeout: int = 60):
    import urllib.request
    import ssl as _ssl
    req = urllib.request.Request(EXTERNAL_API + path, headers={
        "Accept": "application/json", "User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=timeout,
                                context=_ssl.create_default_context()) as r:
        return json.loads(r.read().decode("utf-8", "replace"))


def external_port_check(host: str, ports: list[int], max_nodes: int = 14) -> dict:
    """从全球节点验证端口。返回 {port: (成功数, 总数, 明细)}。

    优先用有公网 IPv4 的节点，避开会因本机网络而失真的判断。
    """
    out: dict[int, tuple[int, int, list]] = {}

    for port in ports:
        try:
            d = _ext_api(f"/check-tcp?host={host}:{port}&max_nodes={max_nodes}")
        except Exception as e:
            info(f"  端口 {port}: 外部 API 不可用 ({type(e).__name__})")
            out[port] = (-1, 0, [])
            continue

        rid = d.get("request_id")
        nodes = d.get("nodes", {})
        if not rid:
            info(f"  端口 {port}: 提交失败 {str(d)[:60]}")
            out[port] = (-1, 0, [])
            continue

        res = None
        for _ in range(22):
            time.sleep(3)
            try:
                cur = _ext_api(f"/check-result/{rid}")
            except Exception:
                continue
            if cur and all(v is not None for v in cur.values()):
                res = cur
                break
            res = cur

        if not res:
            info(f"  端口 {port}: 未取得结果（API 限流或超时）")
            out[port] = (-1, 0, [])
            continue

        rows, ok = [], 0
        for node, data in res.items():
            entry = data[0] if isinstance(data, list) and data else None
            if not isinstance(entry, dict):
                continue
            nv = nodes.get(node)
            loc = f"{nv[1]}/{nv[2]}" if isinstance(nv, list) and len(nv) >= 3 \
                else node.split(".")[0]
            err = entry.get("error")
            if err:
                rows.append((loc, f"失败: {str(err)[:40]}", False))
            else:
                ok += 1
                rows.append((loc, f"成功 {entry.get('time')}s", True))
        out[port] = (ok, len(rows), rows)

    return out


def show_external(host: str, ports: list[int]) -> None:
    head("外部视角验证（check-host.net，绕开本机 aTrust/v2ray）")
    info("本机网络已被安全客户端接管，故从全球独立节点复核端口状态")
    info(f"目标 {host}，端口 {ports}；耗时约 30-60 秒")
    info("")

    result = external_port_check(host, ports)
    verds = {}
    for port in ports:
        okn, tot, rows = result.get(port, (-1, 0, []))
        if tot == 0:
            continue
        print(f"  ── 端口 {port} ──")
        for loc, verdict, good in sorted(rows):
            mark = OK("OK ") if good else DIM("-- ")
            print(f"   {mark}{loc:<26} {verdict}")
        verds[port] = (okn, tot)
        level = "ok" if okn > 0 else "fail"
        print(f"   → 成功 {okn} / {tot} 个节点")
        info("")

    # 至少需要一个"成功"的对照端口才能下结论
    succ = [p for p, (o, t) in verds.items() if o > 0]
    allfail = [p for p, (o, t) in verds.items() if o == 0]

    if allfail and succ:
        for p in allfail:
            bad(f"端口 {p}: 全球节点无一可连，而同主机 {sorted(succ)} 正常")
            info(f"  {ARROW} 该端口在远端确实没有监听（不是本机网络问题）")
            finding("fail", f"端口 {p}", "全球节点验证：远端无监听")
    elif allfail and not succ:
        warn("所有被测端口在全球均不可连 —— 无法区分是远端问题还是目标本身不可达")
        finding("warn", "外部验证", "无成功对照端口，结论不可靠")
    else:
        for p, (o, t) in sorted(verds.items()):
            if o > 0:
                ok(f"端口 {p}: {o}/{t} 节点可连")


# ------------------------------------------------------------------ 汇总

def summary() -> int:
    print()
    print("=" * 74)
    print(BOLD("诊断结论"))
    print("=" * 74)
    for level, title, concl in FINDINGS:
        mark = {"ok": OK(MARK_OK), "warn": WARN(MARK_WARN), "fail": BAD(MARK_BAD)}.get(level, "?")
        print(f"  {mark} {title:<14} {concl}")
    print("=" * 74)
    return 0 if all(l != "fail" for l, _, _ in FINDINGS) else 1


def main() -> int:
    ap = argparse.ArgumentParser(description="tm_im M0 连接诊断")
    ap.add_argument("--env", default=str(REPO / "deploy" / "conf" / "local-conn.env"))
    ap.add_argument("--mysql-only", action="store_true")
    ap.add_argument("--redis-only", action="store_true")
    ap.add_argument("--external", action="store_true",
                    help="强制用 check-host.net 全球节点复核端口")
    ap.add_argument("--no-external", action="store_true",
                    help="不做外部验证（默认在有端口失败时自动做）")
    args = ap.parse_args()

    cfg = load_env(Path(args.env))
    for k in list(cfg.keys()):
        if os.environ.get(k):
            cfg[k] = os.environ[k]

    print(BOLD("tm_im M0 连接诊断"))
    print(DIM(f"  配置: {args.env}"))

    run_mysql = not args.redis_only
    run_redis = not args.mysql_only
    host = cfg.get("REDIS_HOST", "").strip() or cfg.get("MYSQL_HOST", "").strip()

    if run_mysql and run_redis:
        net_baseline()
    if run_mysql:
        check_mysql(cfg)
    if run_redis:
        check_redis(cfg)

    # 外部视角复核
    #
    # 只要有端口在 TCP 层失败，就必须引入外部视角——因为本机装有 aTrust/v2ray，
    # 仅凭本机结果无法区分"远端拒绝"与"本机拦截"。
    # 同时必须带上一个已知可用的对照端口（MySQL），否则没有基准：
    # 若所有端口都失败，可能是目标主机整体不可达，而非某个端口没监听。
    if not args.no_external and host and (args.external or TCP_FAILED_PORTS):
        ports = list(TCP_FAILED_PORTS)
        control = int(cfg.get("MYSQL_PORT", "3306") or 3306)
        if control not in ports and cfg.get("MYSQL_HOST", "").strip() == host:
            ports.append(control)
        if ports:
            show_external(host, sorted(set(ports)))

    return summary()


if __name__ == "__main__":
    sys.exit(main())
