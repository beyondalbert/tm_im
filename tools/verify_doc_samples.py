"""
校验接入文档中「自测向量」的正确性。

为什么需要：文档给出的 body 字符串 + 期望签名，如果对不上，
用户会以为自己的实现错了，实际是文档错了 —— 会浪费大量时间。

本脚本从文档中【直接抽取】body 与期望签名，独立重算并比对。

运行：
  python tools/verify_doc_samples.py
"""
import hashlib
import hmac
import io
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOC = os.path.join(ROOT, "docs", "integration", "02-auth.md")

SECRET = "whsec_3a7f9c2e5b8d1046"
TS = "1767225600"
EXPECTED = "eefcc660e1b273f9a9c36eedc162ccda38380f638d69c434fff0ac633b035712"

failures = []


def check(label, cond, detail=""):
    status = "OK" if cond else "FAIL"
    print("  [{}] {}{}".format(status, label, ("  " + detail) if detail else ""))
    if not cond:
        failures.append(label)


print("=" * 74)
print("校验 02-auth.md 的 Webhook 自测向量")
print("=" * 74)

txt = io.open(DOC, encoding="utf-8").read()

# 抽取时间戳
m = re.search(r"^timestamp\s*=\s*(\S+)\s*$", txt, re.M)
check("文档含 timestamp 行", m is not None)
doc_ts = m.group(1) if m else None
check("timestamp 值一致", doc_ts == TS, "doc={} expect={}".format(doc_ts, TS))

# 抽取期望签名
m = re.search(r"^期望签名\s*=\s*([0-9a-f]{64})\s*$", txt, re.M)
doc_sig = m.group(1) if m else None
check("文档含 64 位期望签名", doc_sig is not None)
check("期望签名与基准一致", doc_sig == EXPECTED,
      "doc={} expect={}".format((doc_sig or "")[:16], EXPECTED[:16]))

# 抽取 body
m = re.search(r"^body \(原始字节, (\d+) bytes\):\s*\n(\{.*\})\s*$", txt, re.M)
check("文档含 body 行", m is not None)
if m:
    doc_len = int(m.group(1))
    doc_body = m.group(2)
    body_bytes = doc_body.encode("utf-8")
    check("body 字节数与文档一致", len(body_bytes) == doc_len,
          "doc={} actual={}".format(doc_len, len(body_bytes)))

    # 独立重算签名
    signing = TS.encode("utf-8") + b"." + body_bytes
    recomputed = hmac.new(SECRET.encode("utf-8"), signing, hashlib.sha256).hexdigest()
    check("重算签名 == 文档期望签名", recomputed == doc_sig,
          "recomputed={}".format(recomputed[:16]))

    # 也按「先解析再序列化」的反面示例算一遍，证明它确实会不一致
    try:
        naive = json.dumps(json.loads(doc_body), ensure_ascii=False).encode("utf-8")
        naive_sig = hmac.new(SECRET.encode("utf-8"),
                             TS.encode() + b"." + naive, hashlib.sha256).hexdigest()
        differs = naive_sig != doc_sig
        print("  [INFO] 「先解析再序列化」会得到不同签名: {} ({} vs {})".format(
            differs, naive_sig[:16], doc_sig[:16]))
        if not differs:
            print("         (本例恰好一致，但键顺序/空格变化时就会不一致)")
    except Exception as e:
        print("  [INFO] naive 对比跳过:", e)

# 校验文档中的十六进制示例格式合法
print("\n校验 02-auth.md 中的 hex 块格式")

# 提取所有围栏块，再筛选出「内容全是 hex 字节」的
fenced = re.findall(r"```[a-z]*\n(.*?)```", txt, re.S)
hex_blocks = []
for blk in fenced:
    tokens = blk.strip().split()
    if not tokens:
        continue
    if all(re.fullmatch(r"[0-9a-f]{2}", t) for t in tokens):
        hex_blocks.append(tokens)

check("找到 hex 示例块", len(hex_blocks) > 0, "count={}".format(len(hex_blocks)))
for i, tokens in enumerate(hex_blocks):
    try:
        data = bytes(int(x, 16) for x in tokens)
        ok_len = (len(data) == 55)
        print("  [{}] hex block #{}: {} bytes".format(
            "OK" if ok_len else "FAIL", i + 1, len(data)))
        if not ok_len:
            failures.append("hex block #{} length (got {})".format(i + 1, len(data)))
    except ValueError as e:
        check("hex block #{} 合法".format(i + 1), False, str(e))

print("\n" + "=" * 74)
if failures:
    print("FAILED: {}".format(failures))
    sys.exit(1)
print("ALL DOC SAMPLES VERIFIED")
print("=" * 74)
