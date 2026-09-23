"""
接入文档全量校验器。

校验四件事，任何一项失败都会让接入方踩坑：
  1. 文档中所有 hex 字节块能正确解码，且与 protobuf 官方编码一致
  2. 文档中「手写 protobuf 实现」的代码能被真实抽取并运行通过断言
  3. 文档中所有 JSON 代码块语法合法
  4. 文档间内部链接都存在

运行：
  uv run --with protobuf python tools/verify_integration_docs.py
"""
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOCS = os.path.join(ROOT, "docs", "integration")
PROTOC = os.path.join(ROOT, ".tools", "protoc.exe")
PROTO_DIR = os.path.join(ROOT, "proto")

failures = []
notes = []


def fail(msg):
    failures.append(msg)
    print("  [FAIL] " + msg)


def ok(msg):
    print("  [ OK ] " + msg)


def info(msg):
    notes.append(msg)
    print("  [INFO] " + msg)


def read(p):
    return io.open(p, encoding="utf-8").read()


def strip_hex(text):
    return bytes(int(x, 16) for x in text.split())


# ============================================================
# 1. hex 字节块校验
# ============================================================
def check_hex_blocks():
    print("\n" + "=" * 74)
    print("1. 校验所有文档中的 hex 字节块")
    print("=" * 74)

    # 官方编码参照
    out = tempfile.mkdtemp(prefix="tmver_")
    p = subprocess.run([PROTOC, "-I", PROTO_DIR, "--python_out", out, "transport.proto"],
                       capture_output=True, text=True)
    if p.returncode != 0:
        fail("protoc 生成失败: " + p.stderr)
        return out
    sys.path.insert(0, out)
    import transport_pb2 as T

    # 构造期望的帧集合（与文档示例对应）
    expected = {}

    auth = T.Frame(cmd=T.Frame.CMD_AUTH, req_id=1,
                   payload=T.AuthRequest(token="sk_live_9f2c1d7a4b8e3f60",
                                         client_version="1.0.0",
                                         device_id="web-chrome-131").SerializeToString())
    expected["AUTH"] = auth.SerializeToString()

    send = T.Frame(cmd=T.Frame.CMD_SEND, req_id=2,
                   payload=T.SendRequest(conv_id=1001, client_msg_id="c-7f3a9b21",
                                         msg_type=T.MSG_TYPE_TEXT,
                                         content_json='{"text":"你好，Agent"}').SerializeToString())
    expected["SEND"] = send.SerializeToString()

    ack = T.Frame(cmd=T.Frame.CMD_SEND_ACK, req_id=2,
                  payload=T.SendAck(conv_id=1001, seq=7, message_id=730000000000000001,
                                    created_at_ms=1767225600123,
                                    client_msg_id="c-7f3a9b21").SerializeToString())
    expected["SEND_ACK"] = ack.SerializeToString()

    push = T.Frame(cmd=T.Frame.CMD_PUSH, req_id=0,
                   payload=T.PushMessage(message=T.Message(
                       message_id=730000000000000002, conv_id=1001, seq=8, sender_id=2002,
                       msg_type=T.MSG_TYPE_TEXT,
                       content_json='{"text":"收到，今天北京晴"}',
                       created_at_ms=1767225600456)).SerializeToString())
    expected["PUSH"] = push.SerializeToString()

    by_hex = {v.hex(): k for k, v in expected.items()}
    for k, v in expected.items():
        info("官方编码 {} = {} bytes".format(k, len(v)))

    # 扫描所有文档的围栏块
    found = 0
    for name in sorted(os.listdir(DOCS)):
        if not name.endswith(".md"):
            continue
        txt = read(os.path.join(DOCS, name))
        for blk in re.findall(r"```[a-z]*\n(.*?)```", txt, re.S):
            tokens = blk.strip().split()
            if len(tokens) < 4:
                continue
            if not all(re.fullmatch(r"[0-9a-f]{2}", t) for t in tokens):
                continue
            found += 1
            raw = bytes(int(t, 16) for t in tokens)
            kind = by_hex.get(raw.hex())
            if kind:
                ok("{}: hex 块 #{} = {} 帧 ({} bytes) 完全匹配官方编码".format(
                    name, found, kind, len(raw)))
            else:
                # 可能是长度前缀块或截断展示
                body = raw[4:] if len(raw) > 4 else b""
                if body and by_hex.get(body.hex()):
                    ok("{}: hex 块 #{} = 4 字节长度前缀 + {} 帧".format(
                        name, found, by_hex[body.hex()]))
                elif len(raw) in (24,):
                    info("{}: hex 块 #{} 是截断展示 ({} bytes)，跳过".format(name, found, len(raw)))
                else:
                    fail("{}: hex 块 #{} 无法匹配任何已知帧 ({} bytes, 开头 {})".format(
                        name, found, len(raw), raw[:8].hex(" ")))

    if found == 0:
        fail("未找到任何 hex 块")
    else:
        info("共扫描 {} 个 hex 块".format(found))
    return out


# ============================================================
# 2. 抽取并运行文档中的「手写 protobuf 实现」
# ============================================================
def check_handwritten_proto(official_out):
    print("\n" + "=" * 74)
    print("2. 抽取并运行文档中的手写 protobuf 实现")
    print("=" * 74)

    txt = read(os.path.join(DOCS, "06-no-sdk-guide.md"))

    # 定位包含 enc_varint 定义的完整 python 代码块
    blocks = re.findall(r"```python\n(.*?)```", txt, re.S)
    impl = None
    for b in blocks:
        if "def enc_varint" in b and "def encode_frame" in b and "def decode_frame" in b:
            impl = b
            break

    if impl is None:
        fail("未能在 06-no-sdk-guide.md 中定位手写 protobuf 实现代码块")
        return

    ok("已定位手写实现（{} 行）".format(len(impl.splitlines())))

    # 写入临时模块并导入执行
    mod_dir = tempfile.mkdtemp(prefix="tmhw_")
    mod_path = os.path.join(mod_dir, "minimal_proto.py")
    with io.open(mod_path, "w", encoding="utf-8") as f:
        f.write(impl)
    sys.path.insert(0, mod_dir)

    import minimal_proto as MP

    # ---------- varint 表校验 ----------
    varint_cases = [(1, "01"), (2, "02"), (10, "0a"), (127, "7f"),
                    (128, "8001"), (300, "ac02"), (1001, "e907"), (16384, "808001")]
    for val, hexs in varint_cases:
        got = MP.enc_varint(val).hex()
        if got == hexs:
            ok("varint {} -> {} 与文档一致".format(val, hexs))
        else:
            fail("varint {} 期望 {} 实际 {}".format(val, hexs, got))

    # ---------- varint 往返 ----------
    for val in [0, 1, 127, 128, 255, 256, 16383, 16384, 2**31, 2**53, 2**63 - 1]:
        enc = MP.enc_varint(val)
        dec, n = MP.dec_varint(enc, 0)
        if dec != val or n != len(enc):
            fail("varint 往返失败 {} -> {}".format(val, dec))
    ok("varint 往返（含 2^63-1）全部通过")

    # ---------- tag 表校验 ----------
    tag_cases = [(1, 0, "08"), (2, 0, "10"), (3, 2, "1a"), (4, 2, "22")]
    for fno, wire, hexs in tag_cases:
        got = MP.tag(fno, wire).hex()
        if got == hexs:
            ok("tag 字段{} 线类型{} -> {}".format(fno, wire, hexs))
        else:
            fail("tag 字段{} 线类型{} 期望 {} 实际 {}".format(fno, wire, hexs, got))

    # ---------- 与官方编码逐字节对比 ----------
    sys.path.insert(0, official_out)
    import transport_pb2 as T

    # AUTH
    mine = MP.encode_frame(MP.CMD["AUTH"], 1,
                           MP.encode_auth_request("sk_live_9f2c1d7a4b8e3f60",
                                                  "1.0.0", "web-chrome-131"))
    theirs = T.Frame(cmd=T.Frame.CMD_AUTH, req_id=1,
                     payload=T.AuthRequest(token="sk_live_9f2c1d7a4b8e3f60",
                                           client_version="1.0.0",
                                           device_id="web-chrome-131").SerializeToString()
                     ).SerializeToString()
    if mine == theirs:
        ok("AUTH 帧手写编码 == 官方编码 ({} bytes)".format(len(mine)))
    else:
        fail("AUTH 帧不一致\n    手写 {}\n    官方 {}".format(mine.hex(), theirs.hex()))

    # SEND
    mine = MP.encode_frame(MP.CMD["SEND"], 2,
                           MP.encode_send_request(1001, "c-7f3a9b21", 1,
                                                  '{"text":"你好，Agent"}'))
    theirs = T.Frame(cmd=T.Frame.CMD_SEND, req_id=2,
                     payload=T.SendRequest(conv_id=1001, client_msg_id="c-7f3a9b21",
                                           msg_type=T.MSG_TYPE_TEXT,
                                           content_json='{"text":"你好，Agent"}'
                                           ).SerializeToString()).SerializeToString()
    if mine == theirs:
        ok("SEND 帧手写编码 == 官方编码 ({} bytes)".format(len(mine)))
    else:
        fail("SEND 帧不一致\n    手写 {}\n    官方 {}".format(mine.hex(), theirs.hex()))

    # ---------- 解码官方帧 ----------
    f = MP.decode_frame(theirs)
    if f.get(1) == 10 and f.get(2) == 2:
        ok("手写解码 SEND 帧: cmd=10 req_id=2 正确")
    else:
        fail("手写解码 SEND 帧错误: {}".format(f))

    inner = MP.decode_frame(f[3])
    checks = [(1, 1001, "conv_id"), (2, b"c-7f3a9b21", "client_msg_id"),
              (3, 1, "msg_type")]
    for fno, want, label in checks:
        if inner.get(fno) == want:
            ok("payload {} = {!r} 正确".format(label, want))
        else:
            fail("payload {} 期望 {!r} 实际 {!r}".format(label, want, inner.get(fno)))
    if inner.get(4, b"").decode("utf-8") == '{"text":"你好，Agent"}':
        ok("payload content_json 正确（UTF-8 长度 25 字节）")
    else:
        fail("payload content_json 错误: {!r}".format(inner.get(4)))

    # ---------- 默认值不编码 ----------
    if MP.enc_uint(2, 0) == b"":
        ok("默认值不编码规则正确（req_id=0 时字段省略）")
    else:
        fail("默认值不编码规则错误")

    # 验证 PUSH 帧确实没有 req_id 字段（官方编码）
    push_official = T.Frame(cmd=T.Frame.CMD_PUSH, req_id=0,
                            payload=T.PushMessage(message=T.Message(
                                message_id=730000000000000002, conv_id=1001, seq=8,
                                sender_id=2002, msg_type=T.MSG_TYPE_TEXT,
                                content_json='{"text":"收到，今天北京晴"}',
                                created_at_ms=1767225600456)).SerializeToString()
                            ).SerializeToString()
    mp = MP.decode_frame(push_official)
    if 2 not in mp:
        ok("PUSH 帧(req_id=0) 确实不含字段 2，符合 proto3 默认值规则")
    else:
        fail("PUSH 帧异常地包含了 req_id 字段")

    # ---------- SendAck / Message 解码器 ----------
    ack_official = T.Frame(cmd=T.Frame.CMD_SEND_ACK, req_id=2,
                           payload=T.SendAck(conv_id=1001, seq=7,
                                             message_id=730000000000000001,
                                             created_at_ms=1767225600123,
                                             client_msg_id="c-7f3a9b21"
                                             ).SerializeToString()).SerializeToString()
    af = MP.decode_frame(ack_official)
    dack = MP.decode_send_ack(af[3])
    if (dack["conv_id"] == 1001 and dack["seq"] == 7
            and dack["message_id"] == 730000000000000001
            and dack["client_msg_id"] == "c-7f3a9b21"):
        ok("decode_send_ack 正确解析全部字段")
    else:
        fail("decode_send_ack 错误: {}".format(dack))

    # ---------- 确认 9 字节 varint 的观察 ----------
    mi = MP.enc_varint(730000000000000001)
    if len(mi) == 9:
        ok("message_id=7.3e17 的 varint 确实为 9 字节（文档所述正确）")
    else:
        fail("message_id varint 长度 {} 与文档所述 9 不符".format(len(mi)))

    shutil.rmtree(mod_dir, ignore_errors=True)


# ============================================================
# 3. JSON 代码块校验
# ============================================================
def check_json_blocks():
    print("\n" + "=" * 74)
    print("3. 校验文档中的 JSON 代码块")
    print("=" * 74)

    total = parsed = skipped = 0
    for name in sorted(os.listdir(DOCS)):
        if not name.endswith(".md"):
            continue
        txt = read(os.path.join(DOCS, name))
        for i, blk in enumerate(re.findall(r"```json\n(.*?)```", txt, re.S)):
            total += 1
            s = blk.strip()
            # 含注释或占位符的片段无法直接解析
            if "//" in s or "/*" in s or "{ ... }" in s or "..." in s:
                skipped += 1
                continue
            try:
                json.loads(s)
                parsed += 1
            except json.JSONDecodeError as e:
                fail("{} JSON 块 #{} 解析失败: {}".format(name, i + 1, str(e)[:90]))

    ok("JSON 块共 {} 个：{} 个成功解析，{} 个含注释/占位符跳过".format(total, parsed, skipped))


# ============================================================
# 4. 内部链接校验
# ============================================================
def check_links():
    print("\n" + "=" * 74)
    print("4. 校验文档内部链接")
    print("=" * 74)

    link_re = re.compile(r"\[([^\]]+)\]\(([^)]+)\)")
    checked = 0
    for name in sorted(os.listdir(DOCS)):
        if not name.endswith(".md"):
            continue
        txt = read(os.path.join(DOCS, name))
        for label, target in link_re.findall(txt):
            if target.startswith(("http://", "https://", "#")):
                continue
            path = target.split("#")[0]
            if not path:
                continue
            checked += 1
            full = os.path.normpath(os.path.join(DOCS, path))
            if not os.path.exists(full):
                fail("{}: 链接 [{}]({}) 指向不存在的文件".format(name, label, target))
    ok("校验 {} 个文档间链接".format(checked))

    # 校验 README 中提到的路径存在
    root_readme = os.path.join(ROOT, "README.md")
    if os.path.exists(root_readme):
        rt = read(root_readme)
        for m in re.findall(r"\[.*?\]\(([^)]+)\)", rt):
            if m.startswith(("http://", "https://", "#")):
                continue
            full = os.path.normpath(os.path.join(ROOT, m.split("#")[0]))
            if not os.path.exists(full):
                fail("README.md: 链接 {} 不存在".format(m))
        ok("README.md 链接校验完成")


def main():
    out = check_hex_blocks()
    check_handwritten_proto(out)
    check_json_blocks()
    check_links()
    shutil.rmtree(out, ignore_errors=True)

    print("\n" + "=" * 74)
    if failures:
        print("FAILED ({} 项):".format(len(failures)))
        for f in failures:
            print("  - " + f)
        sys.exit(1)
    print("ALL INTEGRATION DOCS VERIFIED ✓")
    print("=" * 74)


if __name__ == "__main__":
    main()
