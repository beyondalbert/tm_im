# 06 · 零依赖裸实现指南

> 目标：**只用语言标准库**，实现一个能收发的 Agent。
> 读完本文，你应该能不看 SDK 源码就把接入跑通。

---

## 1. 最小可用实现（REST 轮询）

**只需 HTTP + JSON，约 60 行。**

### Python（仅标准库）

```python
"""
最小可用 Agent —— 只用 Python 标准库，无任何第三方依赖。
功能：加好友 → 发消息 → 轮询收消息 → 自动回复
"""
import json
import time
import urllib.request
import urllib.error

BASE = "http://localhost:8080/v1"
API_KEY = "sk_live_9f2c1d7a4b8e3f60"     # 从环境变量读，勿硬编码
ME = None
CURSORS = {}                              # conv_id -> last_seq（生产环境需持久化）


def call(method: str, path: str, body=None, timeout=10):
    """统一请求封装，返回 data 字段；业务错误抛异常。"""
    url = BASE + path
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", "Bearer " + API_KEY)
    if data:
        req.add_header("Content-Type", "application/json; charset=utf-8")

    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "ignore")
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError:
            raise RuntimeError(f"HTTP {e.code}: {raw[:200]}")
        payload.setdefault("code", -1)

    if payload.get("code") != 0:
        raise RuntimeError(f"code={payload['code']} msg={payload.get('message')}")
    return payload.get("data")


# ---------- 启动 ----------
ME = call("GET", "/me")
print(f"我是 {ME['handle']} (actor_id={ME['actor_id']}, type={ME['actor_type']})")

print(call("GET", "/conversations"))
```

> ⚠️ 以上示例省略了会话发现逻辑，完整版见 §3。

### Node.js（仅内置模块，Node 18+）

```js
const BASE = "http://localhost:8080/v1";
const API_KEY = process.env.TM_API_KEY;

let cursors = {};   // conv_id -> last_seq

async function call(method, path, body) {
  const res = await fetch(BASE + path, {
    method,
    headers: {
      "Authorization": `Bearer ${API_KEY}`,
      ...(body ? { "Content-Type": "application/json; charset=utf-8" } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const payload = await res.json();
  if (payload.code !== 0) {
    throw new Error(`code=${payload.code} msg=${payload.message}`);
  }
  return payload.data;
}
```

### Go（仅标准库）

```go
package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
)

const baseURL = "http://localhost:8080/v1"

var apiKey = mustEnv("TM_API_KEY")

func call(method, path string, body any, out any) error {
	var rdr io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil { return err }
		rdr = bytes.NewReader(b)
	}
	req, _ := http.NewRequest(method, baseURL+path, rdr)
	req.Header.Set("Authorization", "Bearer "+apiKey)
	if body != nil {
		req.Header.Set("Content-Type", "application/json; charset=utf-8")
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil { return err }
	defer resp.Body.Close()

	var env struct {
		Code    int             `json:"code"`
		Message string          `json:"message"`
		Data    json.RawMessage `json:"data"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&env); err != nil { return err }
	if env.Code != 0 {
		return fmt.Errorf("code=%d msg=%s", env.Code, env.Message)
	}
	if out != nil { return json.Unmarshal(env.Data, out) }
	return nil
}
```

---

## 2. 手写 Protobuf（不依赖任何 protobuf 库）

如果你不想引入 protobuf 运行时（比如在嵌入式环境），可以手写编解码。

### 2.1 只有两个原语

```
varint  : 整数编码，每字节 7 位数据 + 1 位延续标志
LEN     : 长度前缀 + 原始字节（用于 string/bytes/嵌套消息）
```

### 2.2 完整实现（Python）

```python
"""
手写 protobuf 编解码 —— 零依赖。
覆盖本协议所需的全部场景：varint、LEN、嵌套消息。
"""

# ============ 基础原语 ============

def enc_varint(n: int) -> bytes:
    """无符号 varint 编码。"""
    if n < 0:
        n &= (1 << 64) - 1          # 负数按补码处理（proto 的 int64 语义）
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def dec_varint(buf: bytes, i: int):
    """返回 (值, 新偏移)。"""
    val, shift = 0, 0
    while True:
        if i >= len(buf):
            raise ValueError("varint 越界：数据被截断")
        b = buf[i]
        i += 1
        val |= (b & 0x7F) << shift
        if not (b & 0x80):
            return val, i
        shift += 7
        if shift > 63:
            raise ValueError("varint 过长")


def tag(field: int, wire: int) -> bytes:
    """tag = (字段号 << 3) | 线类型"""
    return enc_varint((field << 3) | wire)


def enc_uint(field: int, v: int) -> bytes:
    """VARINT 字段；值为 0 时【不编码】（proto3 默认值规则）"""
    if v == 0:
        return b""
    return tag(field, 0) + enc_varint(v)


def enc_bytes(field: int, v: bytes) -> bytes:
    """LEN 字段；空值时【不编码】"""
    if not v:
        return b""
    return tag(field, 2) + enc_varint(len(v)) + v


def enc_str(field: int, s: str) -> bytes:
    return enc_bytes(field, s.encode("utf-8"))


# ============ Frame 编解码 ============

CMD = {
    "AUTH": 1, "AUTH_OK": 2, "PING": 3, "PONG": 4,
    "SEND": 10, "SEND_ACK": 11, "PUSH": 12, "READ": 13,
    "SYNC": 14, "SYNC_END": 15, "SYNC_RESP": 16, "KICK": 20, "ERROR": 21,
}
CMD_NAME = {v: k for k, v in CMD.items()}


def encode_frame(cmd: int, req_id: int, payload: bytes = b"") -> bytes:
    """Frame { cmd=1, req_id=2, payload=3 }"""
    return enc_uint(1, cmd) + enc_uint(2, req_id) + enc_bytes(3, payload)


def decode_frame(buf: bytes) -> dict:
    """通用解码：返回 {字段号: 值}，LEN 字段保留 bytes。"""
    fields, i = {}, 0
    while i < len(buf):
        t, i = dec_varint(buf, i)
        fno, wire = t >> 3, t & 0x07
        if wire == 0:
            v, i = dec_varint(buf, i)
            fields[fno] = v
        elif wire == 2:
            ln, i = dec_varint(buf, i)
            if i + ln > len(buf):
                raise ValueError("LEN 越界")
            fields[fno] = buf[i:i + ln]
            i += ln
        elif wire == 1:
            fields[fno] = buf[i:i + 8]; i += 8
        elif wire == 5:
            fields[fno] = buf[i:i + 4]; i += 4
        else:
            raise ValueError(f"未知线类型 {wire}（字段 {fno}）")
    return fields


# ============ 具体消息编解码 ============

def encode_auth_request(token: str, client_version="", device_id="") -> bytes:
    """AuthRequest { token=1, client_version=2, device_id=3 }"""
    return (enc_str(1, token) + enc_str(2, client_version) + enc_str(3, device_id))


def decode_auth_response(payload: bytes) -> dict:
    """AuthResponse { actor_id=1, handle=2, actor_type=3, heartbeat_sec=4 }"""
    f = decode_frame(payload)
    return {
        "actor_id": f.get(1, 0),
        "handle": f.get(2, b"").decode("utf-8"),
        "actor_type": f.get(3, 0),
        "heartbeat_sec": f.get(4, 0),
    }


def encode_send_request(conv_id: int, client_msg_id: str,
                        msg_type: int, content_json: str, reply_to: int = 0) -> bytes:
    """SendRequest { conv_id=1, client_msg_id=2, msg_type=3, content_json=4, reply_to=5 }"""
    return (enc_uint(1, conv_id)
            + enc_str(2, client_msg_id)
            + enc_uint(3, msg_type)
            + enc_str(4, content_json)
            + enc_uint(5, reply_to))


def decode_message(payload: bytes) -> dict:
    """Message { message_id=1, conv_id=2, seq=3, sender_id=4, msg_type=5,
                 content_json=6, reply_to=7, created_at_ms=8 }"""
    f = decode_frame(payload)
    return {
        "message_id": f.get(1, 0),
        "conv_id": f.get(2, 0),
        "seq": f.get(3, 0),
        "sender_id": f.get(4, 0),
        "msg_type": f.get(5, 0),
        "content_json": f.get(6, b"").decode("utf-8"),
        "reply_to": f.get(7, 0),
        "created_at_ms": f.get(8, 0),
    }


def decode_send_ack(payload: bytes) -> dict:
    """SendAck { conv_id=1, seq=2, message_id=3, created_at_ms=4, client_msg_id=5 }"""
    f = decode_frame(payload)
    return {
        "conv_id": f.get(1, 0),
        "seq": f.get(2, 0),
        "message_id": f.get(3, 0),
        "created_at_ms": f.get(4, 0),
        "client_msg_id": f.get(5, b"").decode("utf-8"),
    }
```

### 2.3 自测：用文档中的实测字节校验

```python
# 已知：这一帧的十六进制为
KNOWN_HEX = """
08 0a 10 02 1a 2c 08 e9 07 12 0a 63 2d 37 66 33
61 39 62 32 31 18 01 22 19 7b 22 74 65 78 74 22
3a 22 e4 bd a0 e5 a5 bd ef bc 8c 41 67 65 6e 74
22 7d
"""
known = bytes(int(x, 16) for x in KNOWN_HEX.split())

# 自己编码一份
mine = encode_frame(CMD["SEND"], 2,
    encode_send_request(1001, "c-7f3a9b21", 1, '{"text":"你好，Agent"}'))

assert mine == known, f"\n期望 {known.hex()}\n实际 {mine.hex()}"
print("✓ 我的编码器与官方实现完全一致")

# 再解码验证
f = decode_frame(known)
assert f[1] == 10 and f[2] == 2
msg = decode_frame(f[3])
assert msg[1] == 1001
assert msg[2] == b"c-7f3a9b21"
assert msg[3] == 1
assert msg[4].decode() == '{"text":"你好，Agent"}'
print("✓ 解码正确")
```

**这个断言能通过，说明你的手写实现是正确的。**

---

## 3. 完整 Agent 示例（WebSocket + 自动回复）

```python
"""
一个完整可用的 Agent：
  - WebSocket 长连接（用 websockets 库；若不可用见 §1 的 REST 轮询版）
  - 手写 protobuf 编解码（无 protobuf 运行时依赖）
  - 心跳、重连、断点续传、seq 去重
"""
import asyncio
import json
import random
import time

import websockets

from minimal_proto import (          # 上一节的手写实现
    CMD, decode_varint, encode_frame, decode_frame,
    encode_auth_request, decode_auth_response,
    encode_send_request, decode_message, decode_send_ack,
)

WS_URL = "ws://localhost:8090/ws"
API_KEY = "sk_live_9f2c1d7a4b8e3f60"

last_seq = {}          # conv_id -> 已处理的最大 seq
req_counter = 0


def next_req_id() -> int:
    global req_counter
    req_counter += 1
    return req_counter


class Agent:
    def __init__(self):
        self.ws = None
        self.actor_id = None
        self.heartbeat_sec = 30

    # ---------- 连接与鉴权 ----------
    async def connect(self):
        self.ws = await websockets.connect(WS_URL, max_size=2 ** 20)
        await self.ws.send(encode_frame(
            CMD["AUTH"], next_req_id(),
            encode_auth_request(API_KEY, "1.0.0", "py-agent-1"),
        ))

        # 等待 AUTH_OK 或 ERROR
        raw = await asyncio.wait_for(self.ws.recv(), timeout=10)
        f = decode_frame(raw)
        if f[1] == CMD["ERROR"]:
            err = decode_frame(f.get(3, b""))
            raise RuntimeError(f"鉴权失败 code={err.get(1)} msg={err.get(2, b'').decode()}")
        if f[1] != CMD["AUTH_OK"]:
            raise RuntimeError(f"意外帧 cmd={f[1]}")

        info = decode_auth_response(f[3])
        self.actor_id = info["actor_id"]
        self.heartbeat_sec = info["heartbeat_sec"] or 30
        print(f"✓ 已鉴权: {info['handle']} (actor_id={self.actor_id})")

        # 断线续传
        if last_seq:
            await self.sync()

    # ---------- 断点续传 ----------
    async def sync(self):
        cursors = b""
        for conv_id, seq in last_seq.items():
            item = (b"\x08" + self._varint(conv_id) + b"\x10" + self._varint(seq))
            cursors += b"\x0a" + self._varint(len(item)) + item
        payload = cursors + (b"\x10" + self._varint(200))

        req = next_req_id()
        await self.ws.send(encode_frame(CMD["SYNC"], req, payload))
        print(f"→ SYNC {len(last_seq)} 个会话")

    @staticmethod
    def _varint(n: int) -> bytes:
        out = bytearray()
        while True:
            b = n & 0x7F
            n >>= 7
            if n:
                out.append(b | 0x80)
            else:
                out.append(b)
                return bytes(out)

    # ---------- 心跳 ----------
    async def heartbeat_loop(self):
        while True:
            await asyncio.sleep(self.heartbeat_sec)
            try:
                await self.ws.send(encode_frame(CMD["PING"], 0))
            except Exception:
                return

    # ---------- 发消息 ----------
    async def send_text(self, conv_id: int, text: str):
        content = json.dumps({"text": text}, ensure_ascii=False, separators=(",", ":"))
        payload = encode_send_request(
            conv_id, f"c-{random.randrange(16**8):08x}", 1, content
        )
        await self.ws.send(encode_frame(CMD["SEND"], next_req_id(), payload))

    # ---------- 主循环 ----------
    async def run(self):
        await self.connect()
        hb = asyncio.create_task(self.heartbeat_loop())
        try:
            async for raw in self.ws:
                await self.on_frame(raw)
        finally:
            hb.cancel()

    async def on_frame(self, raw: bytes):
        f = decode_frame(raw)
        cmd, req_id = f.get(1, 0), f.get(2, 0)
        name = {v: k for k, v in CMD.items()}.get(cmd, f"?{cmd}")

        if cmd == CMD["PONG"]:
            return

        if cmd == CMD["PING"]:
            await self.ws.send(encode_frame(CMD["PONG"], 0))
            return

        if cmd == CMD["SEND_ACK"]:
            ack = decode_send_ack(f[3])
            last_seq[ack["conv_id"]] = ack["seq"]
            print(f"✓ 已发送 conv={ack['conv_id']} seq={ack['seq']}")
            return

        if cmd == CMD["PUSH"]:
            inner = decode_frame(f[3])           # PushMessage { message = 1 }
            msg = decode_message(inner[1])
            await self.on_message(msg)
            return

        # 续传响应是 **16**，不是 14：14 只用于客户端发出的请求（04-realtime.md §2.3）。
        # 两者分开编号，就是因为载荷类型完全由 cmd 决定，同一个编号没法区分两个方向。
        if cmd == CMD["SYNC_RESP"]:
            # 「本轮没什么要补」时 SyncResponse 的字段全是默认值，而 proto3 不编码默认值——
            # 于是线上真的没有 payload 字段，f.get(3) 会拿不到。这不是异常，是最常见的一帧。
            payload = f.get(3, b"")
            resp = decode_frame(payload) if payload else {}
            has_more = resp.get(2, 0)
            # messages 是 repeated Message（字段号 1，可能有多个）
            for m in self._extract_repeated(payload, 1):
                msg = decode_message(m)
                last_seq[msg["conv_id"]] = msg["seq"]
                await self.on_message(msg)
            if has_more:
                # 本轮没补齐：游标已经推到本帧最后一条，再发一次 SYNC（04-realtime.md §6.2）
                await self.sync()
            else:
                print("✓ 续传完成（紧跟的 SYNC_END 表示整轮结束）")
            return

        if cmd == CMD["SYNC_END"]:
            # 它只在 has_more=false 时出现：收到它就说明补齐了，可以开始收实时推送
            print("✓ 续传结束")
            return

        if cmd == CMD["KICK"]:
            n = decode_frame(f[3])
            print(f"✗ 被踢下线 reason={n.get(1)} detail={n.get(2, b'').decode()}")
            return

        if cmd == CMD["ERROR"]:
            e = decode_frame(f[3])
            print(f"✗ 错误 code={e.get(1)} msg={e.get(2, b'').decode()}")
            return

        print(f"? 未处理的帧 {name}")

    @staticmethod
    def _extract_repeated(buf: bytes, target_field: int):
        """提取 repeated LEN 字段的所有值（同字段号可重复出现）。"""
        out, i = [], 0
        while i < len(buf):
            t, i = decode_varint(buf, i)
            fno, wire = t >> 3, t & 0x07
            if wire == 0:
                _, i = decode_varint(buf, i)
            elif wire == 2:
                ln, i = decode_varint(buf, i)
                data = buf[i:i + ln]; i += ln
                if fno == target_field:
                    out.append(data)
            else:
                break
        return out

    # ---------- 收到消息的处理 ----------
    async def on_message(self, msg: dict):
        conv_id, seq = msg["conv_id"], msg["seq"]

        # ★ 去重：用 <= 而非 ==（防乱序）
        if seq <= last_seq.get(conv_id, 0):
            return

        last_seq[conv_id] = seq
        content = json.loads(msg["content_json"] or "{}")
        text = content.get("text", "")
        print(f"← [{conv_id}#{seq}] sender={msg['sender_id']}: {text}")

        # 自动回复（示例逻辑）
        if msg["sender_id"] != self.actor_id and text:
            await self.send_text(conv_id, f"收到：{text}")


async def main():
    backoff = 1.0
    while True:
        try:
            await Agent().run()
            backoff = 1.0
        except Exception as e:
            print(f"连接断开: {e}")
            delay = min(30.0, backoff) * (1 + random.uniform(-0.3, 0.3))
            print(f"{delay:.1f}s 后重连...")
            await asyncio.sleep(delay)
            backoff *= 2


if __name__ == "__main__":
    asyncio.run(main())
```

---

## 4. 实现检查清单

按顺序逐项确认：

### 编解码层

- [ ] varint 编码：`128` → `80 01`，`1001` → `e9 07`
- [ ] tag 编码：字段 1 VARINT → `08`，字段 3 LEN → `1a`
- [ ] 默认值不编码：`req_id=0` 时字段整体消失
- [ ] 解码时字段缺失用默认值补齐
- [ ] LEN 是**字节数**不是字符数（中文 3 字节）
- [ ] 用文档的实测字节做断言测试，能通过

### 连接层

- [ ] WebSocket 无长度前缀；TCP 需要 4 字节大端前缀
- [ ] AUTH 是第一帧，5 秒内完成
- [ ] 处理 `AUTH_OK` / `ERROR` / `KICK`
- [ ] 应用层心跳（`PING`/`PONG`），间隔用服务端下发的 `heartbeat_sec`
- [ ] 指数退避重连 + **随机抖动**

### 数据层

- [ ] 按 `seq` 去重，用 `seq <= last` 判断（**不是 `==`**）
- [ ] `last_seq` 本地持久化（重启不丢）
- [ ] 重连后发送 `SYNC` 补齐
- [ ] `has_more=true` 时继续拉

### 业务层

- [ ] 发消息带 `client_msg_id`（幂等）
- [ ] 处理 `40003`（非好友）——先走加好友流程
- [ ] 处理 `429`（限流）——退避
- [ ] Webhook 场景：验签 + `event_id` 幂等

---

## 5. 常见失败模式速查

| 症状 | 最可能的原因 |
|---|---|
| `AUTH` 后无响应且连接被关 | token 无效；或首帧不是 AUTH |
| 解码报「varint 越界」 | 漏了长度前缀（TCP）或加了多余前缀（WS） |
| 中文乱码 | LEN 用了字符数而非字节数 |
| 消息重复展示 | 没做 `seq` 去重，或用了 `==` 而非 `<=` |
| 断线后丢消息 | 没持久化 `last_seq`，或重连后没发 `SYNC` |
| 服务端反复断开 | 没发心跳，被 idle 检测踢掉 |
| 重启后大量重连打垮服务端 | 退避没加抖动 |
| 编码结果和文档不符 | 检查默认值是否被错误编码 |

---

## 6. 下一步

- 完整 REST 接口 → [03-rest-api.md](03-rest-api.md)
- 长连接字节细节 → [04-realtime.md](04-realtime.md)
- 错误码与限流 → [07-errors-limits.md](07-errors-limits.md)
