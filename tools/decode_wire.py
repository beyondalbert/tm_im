"""
protobuf 线格式逐字节解析 + 文档注解生成。

目的：让接入文档里的「字节对照表」是机器生成的、准确的，
      而不是人工推算的（人工推算极易在 varint 长度上出错）。

运行：
  uv run --with protobuf python tools/decode_wire.py
"""
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROTOC = os.path.join(ROOT, ".tools", "protoc.exe")
PROTO_DIR = os.path.join(ROOT, "proto")

WIRE_NAMES = {0: "VARINT", 1: "I64", 2: "LEN", 5: "I32"}


def gen_stubs() -> str:
    out = tempfile.mkdtemp(prefix="tmdecode_")
    p = subprocess.run([PROTOC, "-I", PROTO_DIR, "--python_out", out, "transport.proto"],
                       capture_output=True, text=True)
    if p.returncode != 0:
        print("protoc failed:", p.stderr, file=sys.stderr)
        sys.exit(1)
    return out


def read_varint(buf: bytes, i: int):
    """返回 (值, 消耗字节数, 原始字节)"""
    start = i
    shift = 0
    val = 0
    while True:
        if i >= len(buf):
            raise ValueError("varint 越界")
        b = buf[i]
        val |= (b & 0x7F) << shift
        i += 1
        if not (b & 0x80):
            break
        shift += 7
        if shift > 63:
            raise ValueError("varint 过长")
    return val, i - start, buf[start:i]


def walk(buf: bytes, depth: int = 0, indent: str = ""):
    """通用 protobuf 线格式遍历，不依赖 schema。输出 (field_no, wire_type, 展示值)。"""
    i = 0
    out = []
    while i < len(buf):
        tag, n, raw_tag = read_varint(buf, i)
        i += n
        field_no = tag >> 3
        wire = tag & 0x07

        if wire == 0:
            v, n, raw = read_varint(buf, i)
            i += n
            out.append((indent, field_no, "VARINT", "{}".format(v), raw.hex(" ")))
        elif wire == 2:
            ln, n, raw_len = read_varint(buf, i)
            i += n
            payload = buf[i:i + ln]
            i += ln
            # 猜测是否是可打印 UTF-8
            try:
                txt = payload.decode("utf-8")
                printable = all(c.isprintable() or c in "\n\r\t" for c in txt)
            except UnicodeDecodeError:
                txt, printable = "", False
            shown = '"{}"'.format(txt) if printable else "<{} bytes>".format(ln)
            out.append((indent, field_no, "LEN({})".format(ln), shown,
                        (raw_len + payload).hex(" ")[:96]))
        elif wire == 1:
            v = buf[i:i + 8]
            i += 8
            out.append((indent, field_no, "I64", str(struct.unpack("<Q", v)[0]), v.hex(" ")))
        elif wire == 5:
            v = buf[i:i + 4]
            i += 4
            out.append((indent, field_no, "I32", str(struct.unpack("<I", v)[0]), v.hex(" ")))
        else:
            out.append((indent, field_no, "WIRE{}".format(wire), "?", ""))
            break
    return out


def annotate(frame_bytes: bytes, labels: dict):
    """labels: {字段号: 名称}，为顶层字段注解"""
    rows = []
    for indent, fno, wt, val, raw in walk(frame_bytes):
        name = labels.get(fno, "")
        rows.append((fno, name, wt, val, raw))
    return rows


def main():
    out = gen_stubs()
    sys.path.insert(0, out)
    import transport_pb2 as T  # noqa: E402

    FRAME_LABELS = {1: "cmd", 2: "req_id", 3: "payload"}

    # ============ SEND 帧 ============
    content = json.dumps({"text": "你好，Agent"}, ensure_ascii=False, separators=(",", ":"))
    send = T.Frame(
        cmd=T.Frame.CMD_SEND, req_id=2,
        payload=T.SendRequest(conv_id=1001, client_msg_id="c-7f3a9b21",
                              msg_type=T.MSG_TYPE_TEXT, content_json=content).SerializeToString(),
    ).SerializeToString()

    print("=" * 78)
    print("SEND 帧逐字节解析（共 {} 字节）".format(len(send)))
    print("=" * 78)
    print("{:<8} {:<14} {:<10} {:<26} {}".format("字段号", "名称", "线类型", "值", "原始字节"))
    print("-" * 78)
    for fno, name, wt, val, raw in annotate(send, FRAME_LABELS):
        print("{:<8} {:<14} {:<10} {:<26} {}".format(fno, name, wt, val[:24], raw[:40]))

    # 展开 payload
    print("\n--- payload 展开（SendRequest）---")
    _f = T.Frame()
    _f.ParseFromString(send)
    sr = T.SendRequest()
    sr.ParseFromString(_f.payload)
    SR_LABELS = {1: "conv_id", 2: "client_msg_id", 3: "msg_type", 4: "content_json"}
    for fno, name, wt, val, raw in annotate(sr.SerializeToString(), SR_LABELS):
        print("{:<8} {:<14} {:<10} {:<26} {}".format(fno, name, wt, val[:24], raw[:40]))

    print("\ncontent_json 字节长度 = {}".format(len(content.encode('utf-8'))))
    print("content_json 原文     = {}".format(content))

    # ============ AUTH 帧 ============
    auth = T.Frame(
        cmd=T.Frame.CMD_AUTH, req_id=1,
        payload=T.AuthRequest(token="sk_live_9f2c1d7a4b8e3f60",
                              client_version="1.0.0", device_id="web-chrome-131").SerializeToString(),
    ).SerializeToString()
    print("\n" + "=" * 78)
    print("AUTH 帧逐字节解析（共 {} 字节）".format(len(auth)))
    print("=" * 78)
    for fno, name, wt, val, raw in annotate(auth, FRAME_LABELS):
        print("{:<8} {:<14} {:<10} {:<26} {}".format(fno, name, wt, val[:24], raw[:40]))
    print("\n--- payload 展开（AuthRequest）---")
    _f2 = T.Frame()
    _f2.ParseFromString(auth)
    ar = T.AuthRequest()
    ar.ParseFromString(_f2.payload)
    AR_LABELS = {1: "token", 2: "client_version", 3: "device_id"}
    for fno, name, wt, val, raw in annotate(ar.SerializeToString(), AR_LABELS):
        print("{:<8} {:<14} {:<10} {:<26} {}".format(fno, name, wt, val[:24], raw[:40]))

    # ============ 关键：varint 编码规则演示 ============
    print("\n" + "=" * 78)
    print("varint 编码演示（用户自己实现时最易出错的地方）")
    print("=" * 78)
    for n in [1, 2, 10, 127, 128, 300, 1001, 16384]:
        enc = bytearray()
        v = n
        while True:
            b = v & 0x7F
            v >>= 7
            if v:
                enc.append(b | 0x80)
            else:
                enc.append(b)
                break
        print("  {:<8} -> {:<26} ({} 字节)".format(n, bytes(enc).hex(" "), len(enc)))

    print("\n  tag 编码演示：tag = (字段号 << 3) | 线类型")
    for fno, wt in [(1, 0), (2, 0), (3, 2), (4, 2)]:
        tag = (fno << 3) | wt
        print("  字段{:>2} 线类型{} -> tag={:<4} -> {}".format(fno, wt, tag, bytes([tag]).hex(" ")))

    shutil.rmtree(out, ignore_errors=True)


if __name__ == "__main__":
    main()
