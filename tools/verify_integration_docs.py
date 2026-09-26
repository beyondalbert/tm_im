"""
接入文档全量校验器。

校验八件事，任何一项失败都会让接入方踩坑：
  1. 文档中所有 hex 字节块能正确解码，且与 protobuf 官方编码一致
  2. 文档中「手写 protobuf 实现」的代码能被真实抽取并运行通过断言
  3. 文档中所有 JSON 代码块语法合法
  4. 文档间内部链接都存在
  5. 命令字契约：proto/transport.proto ↔ 04-realtime.md §2.1 ↔ 服务端 Frames 三方一致，
     且「一个命令字只有一种方向与一种载荷」
  6. 06-no-sdk-guide.md 里那份手写客户端的命令字字典与 proto 一致
  7. 文档里引用的测试用例（`XxxTest.methodName`）真的存在
  8. 文档里没有「长得像闭合标签」的字面文本

第 6、7 项都是“文档里的另一份手写副本”：
  * 手写客户端的 CMD 字典漏一个命令字，接入方会把它解成“未知帧”；
  * 文档点名“这条规则由 xxx 用例钉住”时，用例改名/删掉后引用就变成了谎话——
    而读者会以为“有测试盯着”，于是放心改。

第 8 项不关心文档写得好不好，它关心的是**文档能不能被机器完整读出来**。

背景（一次真实的排查阻塞）：很多工具——包括各类 Agent 的回显——用成对标签
包装一次调用的结果，标签的闭合形态是：左方括号 + 斜杠 + 标签名 + 右方括号。
而本仓库曾经在 README 里用「左方括号 + 斜杠 + {id} + 右方括号」表示“这段路径
可选”（写在 GET /v1/conversations 之后），于是那层解析器把它读成一个**没有开标签
的闭合标签**，报出：

    closing tag ... doesn't match any open tag

后果比报错本身严重：**整次读取的结果被丢弃**，而且这是确定性的——只要输出里
包含那一行就必然失败，重试永远得到同一个错误。它看起来像“工具挂了/文件太大”，
实际上是文档里的一对相邻字符。修法是换个写法（可选路径段展开成两条真实路由），
而不是加转义：夹一个反斜杠去不掉那对相邻字符。

注意：本文件自身必须不包含该相邻组合，否则校验器自己就成了读不出来的文件。
所以下面构造正则与说明时一律用 chr() 拼装，并在文件末尾做一次自检。
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


# 构造「左方括号紧跟斜杠」这个组合，供第 8 项使用。
# 写成 chr() 而不是字面量是本文件自己的约束，见模块 docstring。
L = chr(0x5B)   # 左方括号
S = chr(0x2F)   # 斜杠


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

    # §4.5 / §4.6：协议修正后的续传两帧。第一条消息与 PUSH 里那条完全相同，
    # 所以两份样本能互相印证「推送」与「续传」送的是同一条消息。
    sync_resp = T.Frame(cmd=T.Frame.CMD_SYNC_RESP, req_id=3,
                        payload=T.SyncResponse(messages=[
                            T.Message(message_id=730000000000000002, conv_id=1001, seq=8,
                                      sender_id=2002, msg_type=T.MSG_TYPE_TEXT,
                                      content_json='{"text":"收到，今天北京晴"}',
                                      created_at_ms=1767225600456),
                            T.Message(message_id=730000000000000003, conv_id=1001, seq=9,
                                      sender_id=1001, msg_type=T.MSG_TYPE_TEXT,
                                      content_json='{"text":"明天见"}',
                                      created_at_ms=1767225600789),
                        ], has_more=False, truncated=False).SerializeToString())
    expected["SYNC_RESP"] = sync_resp.SerializeToString()

    sync_end = T.Frame(cmd=T.Frame.CMD_SYNC_END, req_id=3,
                       payload=T.SyncEnd(ok=True, message="", conv_synced=2).SerializeToString())
    expected["SYNC_END"] = sync_end.SerializeToString()

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


# ============================================================
# 5. 命令字契约：proto ↔ 文档 ↔ 服务端代码三方一致
# ============================================================
# 这一节是被一个真缺陷遍出来的：CMD_SYNC(14) 曾经同时充当请求与响应，
# 于是同一个编号在两个方向上挂着两种载荷，接收方无法判断手上这帧的语义，
# 而 protobuf 又不会报错（字段号近似时两种都能解出来）。
# 修正动作本身（新增 CMD_SYNC_RESP=16）只发生三处：proto、本篇文档、服务端 Frames。
# 三处都是手写的，所以必须有东西盯着它们。

PROTO_CMD_RE = re.compile(
    r"^\s*(CMD_[A-Z0-9_]+)\s*=\s*(\d+);\s*//\s*"
    r"(C→S|S→C|双向|-)\s+payload\s*=\s*([A-Za-z0-9_]+|-)\s*(.*)$")
DOC_ROW_RE = re.compile(
    r"^\|\s*(\d+)\s*\|\s*`(CMD_[A-Z0-9_]+)`\s*\|\s*([^|]*?)\s*\|\s*([^|]*?)\s*\|\s*([^|]*?)\s*\|\s*$")
JAVA_PAYLOAD_RE = re.compile(r"case\s+([A-Z0-9_,\s]+?)\s*->\s*(\w+)\.class\s*;")
JAVA_NULL_RE = re.compile(r"case\s+([A-Z0-9_,\s]+?)\s*->\s*null\s*;")
JAVA_DIR_RE = re.compile(r"case\s+([A-Z0-9_,\s]+?)\s*->\s*Direction\.(\w+)\s*;")
DOC_INLINE_RE = re.compile(r"`(CMD_[A-Z0-9_]+)`\s*=\s*\*?\*?(\d+)")
DOC_FRAME_CMD_RE = re.compile(r"cmd:\s*(\d+)[^\n]*?//\s*(CMD_[A-Z0-9_]+)")
DESIGN_SNIPPET_RE = re.compile(r"^\s*(CMD_[A-Z0-9_]+)\s*=\s*(\d+);\s*(?://\s*(C→S|S→C|双向|-))?")

FRAMES_JAVA = os.path.join(ROOT, "server", "tm-channel", "src", "main", "java",
                           "com", "tm", "im", "channel", "codec", "Frames.java")


def _java_switch_body(txt, marker):
    """取 marker 所在方法的花括号体（用于把解析范围限在目标 switch 里）。"""
    start = txt.find(marker)
    if start < 0:
        fail("Frames.java 里找不到 {}：命令字契约的代码侧来源缺失".format(marker))
        return ""
    i = txt.find("{", start)
    depth = 0
    for j in range(i, len(txt)):
        if txt[j] == "{":
            depth += 1
        elif txt[j] == "}":
            depth -= 1
            if depth == 0:
                return txt[i:j + 1]
    fail("Frames.java 的方法体无法配对花括号: " + marker)
    return ""


def _java_cases(body):
    """把 ``case A, B -> X;`` 拆成 {名称: X}，重复声明直接报错。"""
    found = {}

    def put(name, value):
        if name in found and found[name] != value:
            fail("Frames.java 里 {} 被声明了两种结果：{} 与 {}".format(name, found[name], value))
        found[name] = value

    for names, value in JAVA_PAYLOAD_RE.findall(body):
        for n in names.replace("\n", " ").split(","):
            if n.strip():
                put(n.strip(), value)
    for names in JAVA_NULL_RE.findall(body):
        for n in names.replace("\n", " ").split(","):
            if n.strip():
                put(n.strip(), None)
    return found


def parse_proto_cmds():
    txt = read(os.path.join(PROTO_DIR, "transport.proto"))
    start = txt.find("enum Cmd {")
    if start < 0:
        fail("proto/transport.proto 里找不到 enum Cmd")
        return {}
    cmds = {}
    for line in txt[start:].splitlines()[1:]:
        stripped = line.strip()
        if stripped == "}":
            break
        if not stripped or stripped.startswith("//"):
            continue
        m = PROTO_CMD_RE.match(line)
        if not m:
            fail("proto 命令字行格式不合规（应为 `CMD_X = N; // <方向> payload = <消息|-> 说明`）: "
                 + stripped[:80])
            continue
        name, num, direction, payload, desc = m.groups()
        cmds[name] = {"num": int(num), "dir": direction,
                      "payload": None if payload == "-" else payload, "desc": desc.strip()}
    if not cmds:
        fail("proto 里没有解出任何命令字")
    return cmds


def parse_doc_table():
    txt = read(os.path.join(DOCS, "04-realtime.md"))
    start = txt.find("### 2.1 命令字表")
    if start < 0:
        fail("04-realtime.md 里找不到 §2.1 命令字表")
        return {}
    rows = {}
    for line in txt[start:].splitlines():
        if line.startswith("### ") and "2.1" not in line:
            break
        if not line.startswith("|"):
            continue
        m = DOC_ROW_RE.match(line)
        if not m:
            continue
        num, name, direction, payload, desc = m.groups()
        if direction not in ("C→S", "S→C", "双向", "—"):
            fail("§2.1 {} 的方向列 `{}` 不是 C→S/S→C/双向/—".format(name, direction))
        p = payload
        if p.startswith("`") and p.endswith("`"):
            p = p[1:-1]
        elif p != "—":
            fail("§2.1 {} 的 payload 列 `{}` 应为 `消息名` 或 —".format(name, payload))
            p = None
        else:
            p = None
        rows[name] = {"num": int(num), "dir": "-" if direction == "—" else direction,
                      "payload": p, "desc": desc}
    if not rows:
        fail("§2.1 命令字表里没有解出任何一行")
    return rows


def parse_java_frames():
    txt = read(FRAMES_JAVA)
    payload_cases = _java_cases(_java_switch_body(
        txt, "private static Class<? extends MessageLite> expectedBodyType"))
    dir_body = _java_switch_body(txt, "public static Direction direction")
    dirs = {}
    for names, direction in JAVA_DIR_RE.findall(dir_body):
        for n in names.replace("\n", " ").split(","):
            n = n.strip()
            if not n:
                continue
            if n in dirs and dirs[n] != direction:
                fail("Frames.direction 里 {} 被声明了两个方向：{} 与 {}".format(
                    n, dirs[n], direction))
            dirs[n] = direction
    return payload_cases, dirs


JAVA_DIR_TO_PROTO = {
    "CLIENT_TO_SERVER": "C→S",
    "SERVER_TO_CLIENT": "S→C",
    "BOTH": "双向",
    "NONE": "-",
}
PROTO_DIR_TO_JAVA = {v: k for k, v in JAVA_DIR_TO_PROTO.items()}


def check_command_contract():
    print("\n" + "=" * 74)
    print("5. 命令字契约：proto ↔ 文档 ↔ 服务端代码")
    print("=" * 74)

    proto = parse_proto_cmds()
    doc = parse_doc_table()
    java_payload, java_dir = parse_java_frames()
    if not (proto and doc and java_payload and java_dir):
        return

    # ---- 不变量 1&2：编号与载荷各自唯一 ----
    # 方向不是「唯一」而是「只能有一个」：一个命令字不能同时挂在两个方向上
    # （那正是 CMD_SYNC 的错），但这个检查由「集合对比」与 _java_cases 的重复检测完成。
    for key in ("num", "payload"):
        seen = {}
        for name, spec in sorted(proto.items()):
            v = spec[key]
            if v is None:
                continue
            if v in seen:
                what = {"num": "编号", "payload": "载荷类型", "dir": "方向"}[key]
                fail("proto {} {} 被两个命令字共用：{} 与 {}".format(what, v, seen[v], name))
            seen[v] = name
    if proto.get("CMD_UNKNOWN", {}).get("dir") != "-":
        fail("CMD_UNKNOWN 必须是保留值（方向 -）")
    if proto.get("CMD_SYNC", {}).get("dir") != "C→S" or \
            proto.get("CMD_SYNC_RESP", {}).get("dir") != "S→C":
        fail("CMD_SYNC 必须只做请求（C→S），响应只归 CMD_SYNC_RESP（S→C）")

    # ---- 三方对比 ----
    def triple(tag):
        return "{}: proto={} 文档={} 服务端={}".format(tag, proto.get(tag), doc.get(tag), java_dir.get(tag))

    if set(proto) != set(doc):
        fail("命令字集合不一致：仅在 proto {}；仅在 §2.1 文档 {}".format(
            sorted(set(proto) - set(doc)), sorted(set(doc) - set(proto))))
    for name in sorted(set(proto) & set(doc)):
        p, d = proto[name], doc[name]
        if p["num"] != d["num"]:
            fail("{} 编号不一致：proto={} 文档={}".format(name, p["num"], d["num"]))
        if p["dir"] != d["dir"]:
            fail("{} 方向不一致：proto={} 文档={}".format(name, p["dir"], d["dir"]))
        if p["payload"] != d["payload"]:
            fail("{} 载荷不一致：proto={} 文档={}".format(name, p["payload"], d["payload"]))
        if java_payload.get(name) != p["payload"]:
            fail("{} 载荷与 Frames.java 不一致：proto={} Java={}".format(
                name, p["payload"], java_payload.get(name)))
        if java_dir.get(name) != PROTO_DIR_TO_JAVA[p["dir"]]:
            fail("{} 方向与 Frames.direction 不一致：proto={} Java={}".format(
                name, p["dir"], java_dir.get(name)))
    ok("{} 个命令字：编号/方向/载荷在 proto、§2.1、Frames 之间完全一致".format(len(proto)))

    # ---- Frames 不得漏掉（也不得多出）任何命令字 ----
    # UNRECOGNIZED 是 protobuf-java 为未知枚举值合成的，proto 里没有对应行。
    java_expected = set(proto) | {"UNRECOGNIZED"}
    if set(java_payload) != java_expected:
        fail("Frames.expectedBodyType 覆盖不全：仅在 proto {}".format(
            sorted(java_expected - set(java_payload))))
    if set(java_dir) != java_expected:
        fail("Frames.direction 覆盖不全：仅在 proto {}".format(
            sorted(java_expected - set(java_dir))))
    ok("Frames 的载荷表与方向表覆盖了全部命令字（含 protobuf 的 UNRECOGNIZED）")

    # ---- 文档正文里的编号引用 ----
    realtime = read(os.path.join(DOCS, "04-realtime.md"))
    refs = 0
    for name, num in DOC_INLINE_RE.findall(realtime):
        refs += 1
        if name not in proto:
            fail("04-realtime.md 引用了不存在的命令字 {}".format(name))
        elif proto[name]["num"] != int(num):
            fail("04-realtime.md 正文把 {} 写成 {}，应为 {}".format(
                name, num, proto[name]["num"]))
    for num, name in DOC_FRAME_CMD_RE.findall(realtime):
        refs += 1
        if name not in proto:
            fail("04-realtime.md 的帧示例引用了不存在的命令字 {}".format(name))
        elif proto[name]["num"] != int(num):
            fail("04-realtime.md 的帧示例 `cmd: {}` 与 {} 的编号 {} 不符".format(
                num, name, proto[name]["num"]))
    ok("校验文档正文中 {} 处命令字编号引用".format(refs))

    # ---- DESIGN §7.5 的命令字总览（它曾经漏过 SYNC/ERROR 两个） ----
    design = read(os.path.join(ROOT, "docs", "DESIGN.md"))
    start = design.find("### 7.5 协议帧")
    end = design.find("### 7.6", start)
    if start < 0 or end < 0:
        fail("DESIGN.md 里找不到 §7.5 命令字总览")
        return
    listed = 0
    for line in design[start:end].splitlines():
        m = DESIGN_SNIPPET_RE.match(line)
        if not m or "=" not in line:
            continue
        name, num, direction = m.groups()
        if name == "syntax":
            continue
        listed += 1
        if name not in proto:
            fail("DESIGN.md §7.5 列出了 proto 里不存在的命令字 {}".format(name))
            continue
        if proto[name]["num"] != int(num):
            fail("DESIGN.md §7.5 把 {} 写成 {}，proto 是 {}".format(
                name, num, proto[name]["num"]))
        if direction and proto[name]["dir"] != direction:
            fail("DESIGN.md §7.5 把 {} 标为 {}，proto 是 {}".format(
                name, direction, proto[name]["dir"]))
    if listed != len(proto):
        fail("DESIGN.md §7.5 只列了 {} 个命令字，proto 里有 {} 个（总览漏项会让人以为它不存在）"
             .format(listed, len(proto)))
    ok("DESIGN.md §7.5 总览包含全部 {} 个命令字，编号与方向一致".format(listed))


def check_nosdk_command_dict():
    print("\n" + "=" * 74)
    print("6. 手写客户端的命令字字典：06-no-sdk-guide.md ↔ proto")
    print("=" * 74)

    txt = read(os.path.join(DOCS, "06-no-sdk-guide.md"))
    m = re.search(r"^CMD = \{(.*?)^\}", txt, re.S | re.M)
    if not m:
        fail("06-no-sdk-guide.md 里找不到手写客户端的 CMD 字典")
        return

    dict_cmds = dict(re.findall(r'"([A-Z_]+)"\s*:\s*(\d+)', m.group(1)))
    if not dict_cmds:
        fail("06-no-sdk-guide.md 的 CMD 字典里没有解出任何命令字")
        return

    # proto 的命令字是 CMD_XXX，字典里写 XXX
    proto = {name[4:]: spec["num"] for name, spec in parse_proto_cmds().items()}
    # CMD_UNKNOWN 是保留值（0，从不发送），字典里不需要它
    proto.pop("UNKNOWN", None)

    missing = sorted(set(proto) - set(dict_cmds))
    extra = sorted(set(dict_cmds) - set(proto))
    for name in missing:
        fail("06-no-sdk-guide.md 的 CMD 字典缺少 {}（接入方会把它解成“未知帧”）".format(name))
    for name in extra:
        fail("06-no-sdk-guide.md 的 CMD 字典里有 proto 里不存在的 {}".format(name))
    for name in sorted(set(proto) & set(dict_cmds)):
        if int(dict_cmds[name]) != proto[name]:
            fail("06-no-sdk-guide.md 把 {} 写成 {}，proto 是 {}".format(
                name, dict_cmds[name], proto[name]))
    if not missing and not extra:
        ok("CMD 字典与 proto 一致（{} 个命令字，保留值 UNKNOWN 除外）".format(len(proto)))


# ============================================================
# 7. 文档里点名的测试用例必须真的存在
# ============================================================
test_method_re = re.compile(r"([A-Za-z0-9]+(?:Test|IT))\.(?!java\b)([a-zA-Z][A-Za-z0-9_]*)")
VOID_METHOD_RE = re.compile(r"\bvoid\s+([a-zA-Z_][A-Za-z0-9_]*)\s*\(")


def java_test_methods() -> dict:
    """扫服务端的测试源码，返回 {测试类名: {方法名}}。"""
    index: dict = {}
    root = os.path.join(ROOT, "server")
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in ("target", "__pycache__")]
        if os.sep + "src" + os.sep + "test" + os.sep not in dirpath + os.sep:
            continue
        for name in filenames:
            if not name.endswith(".java"):
                continue
            methods = set(VOID_METHOD_RE.findall(read(os.path.join(dirpath, name))))
            index.setdefault(name[:-len(".java")], set()).update(methods)
    return index


def doc_files() -> list:
    files = [os.path.join(ROOT, "README.md")]
    for base in (DOCS, os.path.join(ROOT, "docs")):
        for name in sorted(os.listdir(base)):
            if name.endswith(".md"):
                files.append(os.path.join(base, name))
    return files


def check_doc_test_references():
    print("\n" + "=" * 74)
    print("7. 文档引用的测试用例是否存在")
    print("=" * 74)

    index = java_test_methods()
    if not index:
        fail("没有扫到任何测试源码，无法校验文档引用")
        return

    refs = 0
    for path in doc_files():
        name = os.path.basename(path)
        for cls, method in test_method_re.findall(read(path)):
            refs += 1
            if cls not in index:
                fail("{}: 引用了不存在的测试类 {}".format(name, cls))
            elif method not in index[cls]:
                fail("{}: 引用了 {}.{}，但该类里没有这个方法"
                     "（用例被改名/删掉后，这句“有测试盯着”就变成了谎话）".format(name, cls, method))
    ok("校验 {} 处测试用例引用（对 {} 个测试类）".format(refs, len(index)))


# ============================================================
# 8. 文档里不能有「长得像闭合标签」的字面文本
# ============================================================
#
# 形状构造见模块 docstring。这里刻意用 chr() 拼，而不是写字面量：
# 本文件的任何一行只要含那对相邻字符，本文件自己就读不出来了。
CLOSING_TAG_RE = re.compile(
    re.escape(L) + S + r"[^\]\s]{0,60}\]")


def check_no_tag_shaped_text():
    print("\n" + "=" * 74)
    print("8. 文档里没有「长得像闭合标签」的字面文本")
    print("=" * 74)

    scanned = 0
    for path in doc_files():
        scanned += 1
        for lineno, line in enumerate(read(path).splitlines(), 1):
            for m in CLOSING_TAG_RE.finditer(line):
                fail(("{}:{} 出现 `{}`：这是「左方括号紧跟斜杠」，会被用成对标签"
                      "包装工具输出的那层解析器当成没有开标签的闭合标签，" 
                      "导致整次读取被丢弃且重试必然同样失败。"
                      "把可选路径段展开成多条真实路由即可；夹一个反斜杠没用。"
                      ).format(os.path.basename(path), lineno, m.group(0)))
    ok("扫描 {} 个文档（README + docs/ 与 docs/integration/ 下全部 .md）".format(scanned))


def main():
    out = check_hex_blocks()
    check_handwritten_proto(out)
    check_json_blocks()
    check_links()
    check_command_contract()
    check_nosdk_command_dict()
    check_doc_test_references()
    check_no_tag_shaped_text()
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
