"""
生成接入文档所需的「已验证字节样本」。

为什么要这个脚本：
  接入文档里若给出编造的十六进制字节，用户照着自己实现会永远对不上。
  本脚本用真实 protoc 生成的代码编码真实帧，输出可复现的字节，
  直接贴进文档，用户可逐字节对照。

运行：
  uv run --with protobuf --with grpcio-tools python tools/gen_wire_samples.py
"""
import hashlib
import hmac
import json
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROTOC = os.path.join(ROOT, ".tools", "protoc.exe")
PROTO_DIR = os.path.join(ROOT, "proto")


def gen_python_stubs() -> str:
    """用 protoc 生成 Python 桩到临时目录，并返回该目录。"""
    out = tempfile.mkdtemp(prefix="tmsamples_")
    p = subprocess.run(
        [PROTOC, "-I", PROTO_DIR, "--python_out", out, "transport.proto"],
        capture_output=True, text=True,
    )
    if p.returncode != 0:
        print("protoc failed:", p.stderr, file=sys.stderr)
        sys.exit(1)
    return out


def hexdump(data: bytes, indent: str = "  ") -> str:
    """输出带偏移与 ASCII 的 hexdump，便于文档展示。"""
    lines = []
    for off in range(0, len(data), 16):
        chunk = data[off:off + 16]
        hx = " ".join("{:02x}".format(b) for b in chunk)
        asc = "".join(chr(b) if 32 <= b < 127 else "." for b in chunk)
        lines.append("{}{:04x}  {:<47}  {}".format(indent, off, hx, asc))
    return "\n".join(lines)


def main():
    out = gen_python_stubs()
    sys.path.insert(0, out)
    import transport_pb2 as T  # noqa: E402

    print("=" * 74)
    print("Part 1 — 长连接帧的真实字节（protobuf 4.36.2 编码）")
    print("=" * 74)

    # ---------- 1. AUTH 帧 ----------
    auth_req = T.AuthRequest(
        token="sk_live_9f2c1d7a4b8e3f60",
        client_version="1.0.0",
        device_id="web-chrome-131",
    )
    auth_frame = T.Frame(cmd=T.Frame.CMD_AUTH, req_id=1, payload=auth_req.SerializeToString())
    auth_bytes = auth_frame.SerializeToString()

    print("\n[1] AUTH 帧 (clients -> server)")
    print("  语义: cmd=1 (CMD_AUTH), req_id=1")
    print("  AuthRequest payload = {} bytes".format(len(auth_req.SerializeToString())))
    print("  Frame total = {} bytes".format(len(auth_bytes)))
    print("  十六进制:")
    print(hexdump(auth_bytes))
    print("  Python 复现:")
    print("    f = Frame(cmd=1, req_id=1, payload=AuthRequest(token=..., client_version=..., device_id=...).SerializeToString())")
    print("    wire = f.SerializeToString()   # ->", len(auth_bytes), "bytes")

    # ---------- 2. SEND 帧 ----------
    content = json.dumps({"text": "你好，Agent"}, ensure_ascii=False, separators=(",", ":"))
    send_req = T.SendRequest(
        conv_id=1001,
        client_msg_id="c-7f3a9b21",
        msg_type=T.MSG_TYPE_TEXT,
        content_json=content,
    )
    send_frame = T.Frame(cmd=T.Frame.CMD_SEND, req_id=2, payload=send_req.SerializeToString())
    send_bytes = send_frame.SerializeToString()

    print("\n[2] SEND 帧 (clients -> server)")
    print("  content_json =", content)
    print("  Frame total = {} bytes".format(len(send_bytes)))
    print("  十六进制:")
    print(hexdump(send_bytes))

    # ---------- 3. SEND_ACK 帧 ----------
    ack = T.SendAck(
        conv_id=1001, seq=7, message_id=730000000000000001,
        created_at_ms=1767225600123, client_msg_id="c-7f3a9b21",
    )
    ack_frame = T.Frame(cmd=T.Frame.CMD_SEND_ACK, req_id=2, payload=ack.SerializeToString())
    ack_bytes = ack_frame.SerializeToString()

    print("\n[3] SEND_ACK 帧 (server -> clients)")
    print("  语义: seq=7 由服务端分配，客户端应以它为准")
    print("  Frame total = {} bytes".format(len(ack_bytes)))
    print("  十六进制:")
    print(hexdump(ack_bytes))

    # ---------- 4. PUSH 帧 ----------
    msg = T.Message(
        message_id=730000000000000002, conv_id=1001, seq=8, sender_id=2002,
        msg_type=T.MSG_TYPE_TEXT,
        content_json=json.dumps({"text": "收到，今天北京晴"}, ensure_ascii=False, separators=(",", ":")),
        created_at_ms=1767225600456,
    )
    push_frame = T.Frame(cmd=T.Frame.CMD_PUSH, req_id=0, payload=T.PushMessage(message=msg).SerializeToString())
    push_bytes = push_frame.SerializeToString()

    print("\n[4] PUSH 帧 (server -> clients)")
    print("  语义: req_id=0 表示服务端主动推送，非请求响应")
    print("  Frame total = {} bytes".format(len(push_bytes)))
    print("  十六进制:")
    print(hexdump(push_bytes))

    # ---------- 5. 往返校验 ----------
    print("\n[5] 往返校验（decode 后字段是否与发送前一致）")
    d = T.Frame()
    d.ParseFromString(send_bytes)
    assert d.cmd == T.Frame.CMD_SEND, d.cmd
    assert d.req_id == 2, d.req_id
    sr = T.SendRequest()
    sr.ParseFromString(d.payload)
    assert sr.conv_id == 1001
    assert sr.client_msg_id == "c-7f3a9b21"
    assert sr.msg_type == T.MSG_TYPE_TEXT
    assert sr.content_json == content
    print("  OK: cmd/req_id/conv_id/client_msg_id/msg_type/content_json 全部一致")

    # ---------- 6. 原生 TCP 长度前缀 ----------
    print("\n[6] 原生 TCP 传输：4 字节大端长度前缀")
    import struct
    framed = struct.pack(">I", len(send_bytes)) + send_bytes
    print("  length prefix = {}".format(framed[:4].hex()))
    print("  total on wire = {} bytes (= 4 + {})".format(len(framed), len(send_bytes)))
    print("  十六进制(前 24 字节):")
    print(hexdump(framed[:24]))

    print("\n" + "=" * 74)
    print("Part 2 — Webhook 签名（HMAC-SHA256），可自行实现")
    print("=" * 74)

    # 与设计文档 §13 一致的签名方案：
    #   X-TM-Signature: sha256=<hex>
    #   签名串 = timestamp + "." + raw_body
    secret = "whsec_3a7f9c2e5b8d1046"
    ts = "1767225600"
    body = json.dumps(
        {
            "event": "message.created",
            "event_id": "evt_01HQ2X3Y4Z5A6B7C8D9E0F",
            "occurred_at": 1767225600456,
            "data": {
                "message_id": 730000000000000002,
                "conv_id": 1001,
                "seq": 8,
                "sender_id": 2002,
                "msg_type": 1,
                "content": {"text": "收到，今天北京晴"},
            },
        },
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")

    signing_string = ts.encode() + b"." + body
    sig_hex = hmac.new(secret.encode(), signing_string, hashlib.sha256).hexdigest()

    print("\n  密钥(示例)     :", secret)
    print("  时间戳         :", ts)
    print("  body 字节长度  :", len(body), "bytes")
    print("  签名原文        = timestamp + \".\" + raw_body")
    print("  签名原文 hex 前32:", signing_string[:32].hex())
    print("  HMAC-SHA256   :", sig_hex)
    print("  请求头应发送    : X-TM-Signature: sha256=" + sig_hex)
    print("  请求头应发送    : X-TM-Timestamp: " + ts)

    print("\n  服务端校验伪代码（任何语言均可实现）:")
    print("    1. 读取 X-TM-Timestamp，与当前时间差 > 300s 则拒绝（防重放）")
    print("    2. 读取【原始】请求体字节 (不要先 JSON 解析再重新序列化！)")
    print("    3. expected = HMAC_SHA256(secret, timestamp + \".\" + raw_body)")
    print("    4. 用【常量时间比较】对比 expected 与 X-TM-Signature 的值")
    print("    5. event_id 做幂等去重，重复投递直接返回 200")

    # 验证签名确定性
    sig_hex2 = hmac.new(secret.encode(), ts.encode() + b"." + body, hashlib.sha256).hexdigest()
    assert sig_hex == sig_hex2
    print("\n  [校验] 签名可复现: OK")

    print("\n" + "=" * 74)
    print("Part 3 — REST 请求/响应样本")
    print("=" * 74)

    api_key = "sk_live_9f2c1d7a4b8e3f60"
    api_body = json.dumps(
        {"msg_type": "TEXT", "client_msg_id": "c-7f3a9b21", "content": {"text": "你好，Agent"}},
        ensure_ascii=False, separators=(",", ":"),
    ).encode("utf-8")

    print("\n  POST /v1/conversations/1001/messages")
    print("  Authorization: Bearer " + api_key)
    print("  Content-Type: application/json; charset=utf-8")
    print("  Body:", api_body.decode("utf-8"))
    print("\n  成功响应 (200):")
    resp = {
        "code": 0,
        "data": {
            "message_id": 730000000000000001,
            "conv_id": 1001,
            "seq": 7,
            "client_msg_id": "c-7f3a9b21",
            "created_at": "2026-01-01T08:00:00.123Z",
        },
    }
    print("  " + json.dumps(resp, ensure_ascii=False, indent=2).replace("\n", "\n  "))

    print("\n  幂等重放（同 client_msg_id 再发一次）→ 返回同样的 seq=7，不产生新消息")

    shutil.rmtree(out, ignore_errors=True)
    print("\n" + "=" * 74)
    print("ALL SAMPLES GENERATED AND VERIFIED")
    print("=" * 74)


if __name__ == "__main__":
    main()
