#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""错误码契约校验：docs/integration/07-errors-limits.md §2 ↔ ErrorCode.java

为什么需要这个检查
--------------------------------------------------------------------------
错误码是对外契约。Agent 开发者照着文档写分支判断，服务端照着枚举返回。
两边一旦漂移，症状是「文档说 40003 是非好友，实际返回了别的」——
排查方向会被引到业务逻辑上，而不是一句文案没同步。

这个脚本做五件事：
  1. §2 表里的每一行都必须在枚举里存在（文档先行，不能有写了不实现的码）；
  2. 枚举里的每一项也必须在 §2 表里存在（代码先行，不能有没写进契约的码）；
  3. 同码的 message 必须逐字相同，retryable 必须一致；
  4. §2 的章节标题必须真的覆盖它表里的码（0、40000-40099、50000+ 等写法）；
  5. HTTP 状态码必须与 §1 的不变量一致——尤其「404 专指路由不存在」。

关于「不自证」
--------------------------------------------------------------------------
本脚本**不在 Python 里重写一遍 httpStatus() 的逻辑**。早期版本正是这么干的：
Python 里手抄一份同样的 if 链，结果 Java 侧把 403 区间上界写成 40305，
校验照样全绿——期望值和被检验对象出自同一支笔，等于没查。
现在改为解析 ErrorCode.java 的方法体，把 if 链和 return 还原成
(条件, 返回码) 序列再求值。Java 写成什么，这里就检验什么。

只读 §2 的表格，不看 §3/§5 里的示例代码：那些是客户端示范，
例如 §6 把 42902 写成 retryable=False（当日不可重试），
而 §2.4 标的是 ✅（次日）。§2 才是契约，枚举注释里也写明了这个取舍。
"""
from __future__ import annotations

import io
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DOC = REPO / "docs" / "integration" / "07-errors-limits.md"
ENUM_JAVA = (REPO / "server" / "tm-common" / "src" / "main" / "java" / "com" / "tm"
             / "im" / "common" / "error" / "ErrorCode.java")

FAILURES: list[str] = []
CHECKS = 0


class Unresolved(Exception):
    """源码里出现了本校验器不认识的结构。宁可报错，也不要偷偷算出一个错的期望值。"""


def check(cond: bool, msg: str) -> bool:
    global CHECKS
    CHECKS += 1
    if not cond:
        FAILURES.append(msg)
    return cond


def read(p: Path) -> str:
    return io.open(p, encoding="utf-8").read()


def strip_md(cell: str) -> str:
    """去掉 Markdown 强调与行内代码标记，取出纯文本。"""
    return cell.replace("**", "").replace("`", "").strip()


def strip_comments(src: str) -> str:
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)


# ---------------------------------------------------------------------------
# 文档侧
# ---------------------------------------------------------------------------

def parse_section_heading(title: str) -> tuple[set[int], int | None]:
    """从章节标题里取出它声称覆盖的错误码范围。

    支持三种写法：「通用（0, 40000-40099）」里的独立码 0 与区间，
    以及「服务端（50000+）」里的开区间。

    返回 (离散码集, 开区间下界或 None)。
    """
    t = strip_md(title)
    allowed: set[int] = set()
    consumed: set[int] = set()
    open_from: int | None = None

    for m in re.finditer(r"(\d+)\s*-\s*(\d+)", t):
        lo, hi = int(m.group(1)), int(m.group(2))
        allowed |= set(range(lo, hi + 1))
        consumed |= set(range(m.start(), m.end()))

    for m in re.finditer(r"(\d+)\s*(\+)?", t):
        if any(i in consumed for i in range(m.start(), m.end())):
            continue
        if m.group(2):                      # 「50000+」——大于等于
            v = int(m.group(1))
            open_from = v if open_from is None else min(open_from, v)
        else:
            allowed.add(int(m.group(1)))

    return allowed, open_from


def in_section(code: int, allowed: set[int], open_from: int | None) -> bool:
    return code in allowed or (open_from is not None and code >= open_from)


def _render_ranges(allowed: set[int], open_from: int | None = None) -> str:
    """把码范围压回「0, 40000-40099」这种可读形式，用于失败提示。"""
    out, start, prev = [], None, None
    for c in sorted(allowed):
        if start is None:
            start = prev = c
        elif c == prev + 1:
            prev = c
        else:
            out.append(str(start) if start == prev else "{}-{}".format(start, prev))
            start = prev = c
    if start is not None:
        out.append(str(start) if start == prev else "{}-{}".format(start, prev))
    if open_from is not None:
        out.append("{}+".format(open_from))
    return ", ".join(out) or "(未声明)"


def parse_doc(text: str) -> tuple[dict[int, dict], dict[str, tuple[set[int], int | None]]]:
    """返回 (code -> {message, retryable, section, line}, 章节名 -> 允许的码范围)。"""
    lines = text.splitlines()

    start = next((i for i, l in enumerate(lines) if re.match(r"^##\s*2\.", l)), None)
    if start is None:
        raise Unresolved("文档里找不到 §2 标题")
    end = next((i for i in range(start + 1, len(lines)) if re.match(r"^##\s+", lines[i])),
               len(lines))
    body = lines[start:end]

    codes: dict[int, dict] = {}
    ranges: dict[str, tuple[set[int], int | None]] = {}
    section = "?"

    for line in body:
        m = re.match(r"^###\s*2\.\d+\s*(.+?)\s*$", line)
        if m:
            section = strip_md(m.group(1))
            allowed, open_from = parse_section_heading(section)
            if allowed or open_from is not None:
                ranges[section] = (allowed, open_from)
            continue

        if not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) < 4:
            continue
        if not re.fullmatch(r"\d+", strip_md(cells[0])):
            continue                       # 表头或分隔行

        code = int(strip_md(cells[0]))
        message = strip_md(cells[1])
        retryable = "\u2705" in strip_md(cells[3])      # ✅

        if code in codes:
            FAILURES.append("文档中错误码 {} 重复出现（§2 表格内应唯一）".format(code))
        codes[code] = {"message": message, "retryable": retryable,
                       "section": section, "line": line.strip()}

    return codes, ranges


# ---------------------------------------------------------------------------
# 枚举侧
# ---------------------------------------------------------------------------

def parse_enum(text: str) -> dict[int, dict]:
    body = strip_comments(text)
    if "public enum ErrorCode {" not in body:
        raise Unresolved("找不到 ErrorCode 枚举声明")
    inner = body.split("public enum ErrorCode {", 1)[1]
    if "private final int code;" not in inner:
        raise Unresolved("枚举体与字段声明的分界线（private final int code;）不见了")
    inner = inner.split("private final int code;", 1)[0]

    out: dict[int, dict] = {}
    for m in re.finditer(
            r"^\s*([A-Z][A-Z0-9_]*)\s*\(\s*(\d+)\s*,\s*\"([^\"]*)\"\s*,\s*(true|false)\s*\)",
            inner, re.M):
        name, code, message, retry = m.group(1), int(m.group(2)), m.group(3), m.group(4)
        if code in out:
            FAILURES.append("枚举中错误码 {} 重复出现（{} 与 {}）".format(
                code, out[code]["name"], name))
        out[code] = {"name": name, "message": message, "retryable": retry == "true"}
    if not out:
        raise Unresolved("没解析到任何枚举常量，格式可能已变")
    return out


# httpStatus() 的两种分支形态：带 if 的、以及兜底的裸 return
_HTTP_BRANCH = re.compile(
    r"if\s*\(([^()]*)\)\s*\{\s*return\s+(\d+);\s*\}|\breturn\s+(\d+);")


def parse_http_status(text: str) -> list[tuple[str | None, int]]:
    """解析 ErrorCode.httpStatus() 的 (条件, 返回码) 序列，顺序与源码一致。

    条件为 None 表示无条件 return（兜底分支）。
    """
    m = re.search(r"public\s+int\s+httpStatus\s*\(\s*\)\s*\{", text)
    if not m:
        raise Unresolved("找不到 httpStatus() 方法")

    i, depth = m.end(), 1
    while i < len(text) and depth:
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
        i += 1
    if depth:
        raise Unresolved("httpStatus() 的花括号不配对")
    body = text[m.end():i - 1]

    branches: list[tuple[str | None, int]] = []
    for bm in _HTTP_BRANCH.finditer(body):
        value = int(bm.group(2) if bm.group(2) is not None else bm.group(3))
        branches.append((bm.group(1), value))

    leftover = _HTTP_BRANCH.sub("", body)
    leftover = re.sub(r"[{}]", "", strip_comments(leftover))
    if leftover.strip():
        raise Unresolved(
            "httpStatus() 里出现了本校验器不认识的结构，无法确定它是否影响结果："
            + repr(leftover.strip()[:160]))
    if not branches:
        raise Unresolved("httpStatus() 里没解析到任何 return")
    return branches


def eval_http_status(branches: list[tuple[str | None, int]], code: int) -> int:
    """按源码顺序求值，等价于逐条 if 判断。"""
    for cond, value in branches:
        if cond is None:
            return value
        py = cond.replace("&&", " and ").replace("||", " or ")
        if not re.fullmatch(r"[A-Za-z0-9_\s()<>=!+\-*/%.]*", py):
            raise Unresolved("无法安全求值的条件：" + repr(cond))
        if eval(py, {"__builtins__": {}}, {"code": code}):   # noqa: S307 —— 字母表已限定
            return value
    raise Unresolved("httpStatus() 没有兜底 return，code={} 会落到方法末尾".format(code))


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

def main() -> int:
    print("=" * 74)
    print("错误码契约校验：文档 §2 ↔ ErrorCode.java")
    print("=" * 74)

    for p in (DOC, ENUM_JAVA):
        if not p.exists():
            print("缺少文件: " + str(p))
            return 2

    java_src = read(ENUM_JAVA)
    doc, ranges = parse_doc(read(DOC))
    enum = parse_enum(java_src)
    branches = parse_http_status(java_src)

    print("  文档 §2 解析出 {} 个错误码；枚举解析出 {} 个；"
          "httpStatus() 解析出 {} 个分支".format(len(doc), len(enum), len(branches)))
    if not doc:
        print("文档解析结果为空，说明表格格式已变——先修本脚本再谈一致性")
        return 2

    # ---------- 1/2. 双向存在性 ----------
    only_doc = sorted(set(doc) - set(enum))
    only_enum = sorted(set(enum) - set(doc))
    check(not only_doc,
          "文档 §2 里有、枚举里没有（写了不实现）：{}".format(
              ["{} {}".format(c, doc[c]["message"]) for c in only_doc]))
    check(not only_enum,
          "枚举里有、文档 §2 里没有（没写进对外契约）：{}".format(
              ["{} {} ({})".format(c, enum[c]["message"], enum[c]["name"]) for c in only_enum]))

    # ---------- 3. 同码字段逐字一致 ----------
    msg_mismatch, retry_mismatch = [], []
    for code in sorted(set(doc) & set(enum)):
        d, e = doc[code], enum[code]
        if d["message"] != e["message"]:
            msg_mismatch.append(
                "{}: 文档 {!r} / 枚举 {!r}（{}）".format(code, d["message"], e["message"], e["name"]))
        if d["retryable"] != e["retryable"]:
            retry_mismatch.append(
                "{} {}: 文档 retryable={} / 枚举 retryable={}".format(
                    code, e["name"], d["retryable"], e["retryable"]))
    check(not msg_mismatch,
          "message 文案漂移（Agent 会按文档字符串做匹配，必须逐字一致）：\n        "
          + "\n        ".join(msg_mismatch))
    check(not retry_mismatch,
          "retryable 标记不一致：\n        " + "\n        ".join(retry_mismatch))

    # ---------- 4. 文档章节区间自洽 ----------
    out_of_range = []
    for code, d in sorted(doc.items()):
        rng = ranges.get(d["section"])
        if rng and not in_section(code, *rng):
            out_of_range.append("{} 出现在「{}」，但该段声称的范围是 {}".format(
                code, d["section"], _render_ranges(*rng)))
    check(not out_of_range,
          "错误码放错了分区，读者会照着错误的分类去判断可重试性：\n        "
          + "\n        ".join(out_of_range))

    # ---------- 5. HTTP 状态码（对照 §1 的不变量） ----------
    # §1 明确：200 覆盖「业务处理完成（含业务失败）」；401 认证；429 限流；
    # 500 服务端异常；503 服务过载。并且 404 专指「路由不存在」。
    #
    # 期望值由「文档自己的分段」推出，而不是按「§1 说 403 是账号被停用」逐码断言：
    # 后者会把 40302 不成员、40303 无权限判为错误，而它们用 403 是通行做法，
    # §1 的描述确实比实现窄。文档把 401xx 归认证、403xx 归权限、
    # 429xx 归限流、5xxxx 归服务端——按这个分段推期望值才站得住脚。
    def expected_status(code: int) -> int | None:
        if code == 0:
            return 200
        if 40100 <= code <= 40199:
            return 401
        if 40300 <= code <= 40399:
            return 403
        if 42900 <= code <= 42999:
            return 429
        if code >= 50000:
            return 503 if code == 50004 else 500
        if 40000 <= code < 50000:
            return 200
        return None

    bad_status, undocumented, actual = [], [], {}
    for code in sorted(enum):
        want = expected_status(code)
        if want is None:
            undocumented.append("{} {}".format(code, enum[code]["name"]))
            continue
        got = eval_http_status(branches, code)
        actual[code] = got
        if got != want:
            bad_status.append("{} {}: §1/§2 分段推出 {}，httpStatus() 实际返回 {}".format(
                code, enum[code]["name"], want, got))
    check(not bad_status,
          "HTTP 状态码与 §1 约定不符：\n        " + "\n        ".join(bad_status))
    check(not undocumented,
          "这些错误码不属于任何已约定分段（40000-49999 / 50000+），"
          "无法从 §1 推出 HTTP 状态码：{}".format(undocumented))

    forbidden = sorted(c for c, st in actual.items() if st in (404, 400))
    check(not forbidden,
          "这些错误码映射到了 404/400，但 §1 说明 404 专指「路由不存在」、"
          "400 不在约定状态码集合内：{}".format(
              ["{} {} -> {}".format(c, enum[c]["name"], actual[c]) for c in forbidden]))

    check(all(st in (200, 401, 403, 429, 500, 503) for st in actual.values()),
          "出现了 §1 未约定的 HTTP 状态码：{}".format(
              sorted({st for st in actual.values()} - {200, 401, 403, 429, 500, 503})))

    # 权限段的每个码都必须真的返回 403。这条专门盯 httpStatus() 里的区间常量：
    # 写成 `code <= 40305` 时 40306 会落回 200，契约说「权限错误」实则返回成功。
    priv_wrong = [c for c in sorted(enum) if 40300 <= c <= 40399 and actual.get(c) != 403]
    check(not priv_wrong,
          "文档 §2.2 列为权限错误的码，httpStatus() 的区间常量没覆盖到，"
          "实际会返回 200：{}".format(priv_wrong))
    auth_wrong = [c for c in sorted(enum) if 40100 <= c <= 40199 and actual.get(c) != 401]
    check(not auth_wrong,
          "文档 §2.2 列为认证错误的码，httpStatus() 实际没返回 401：{}".format(auth_wrong))

    # ---------- 6. retryable 与分段自洽 ----------
    retryable_server = [c for c in enum if c >= 50000 and not enum[c]["retryable"]]
    check(not retryable_server,
          "5xxxx 服务端错误按 §1 约定应可退避重试，但这些标为不可重试：{}".format(
              ["{} {}".format(c, enum[c]["name"]) for c in sorted(retryable_server)]))

    allowed_retry = {40103} | set(range(42901, 43000))
    unexpected = sorted(c for c in enum
                        if c != 0 and 40000 <= c < 50000 and enum[c]["retryable"]
                        and c not in allowed_retry)
    check(not unexpected,
          "这些 4xxxx 客户端错误被标为可重试，但既不是 40103（刷新 token 后重试）"
          "也不是限流类，会诱导客户端盲目重试：{}".format(
              ["{} {} -> {}".format(c, enum[c]["name"], actual.get(c)) for c in unexpected]))

    # ---------- 7. 业务硬规则引用的码必须在册 ----------
    # MessageSendPolicy 等业务规则抛出的码如果不在契约里，Agent 拿到后无法查表。
    for name in ("NOT_FRIENDS", "NOT_A_MEMBER", "BLOCKED_BY_PEER", "REQUEST_PENDING",
                 "CONTENT_TOO_LONG", "RATE_LIMIT_EXCEEDED"):
        hit = [c for c, e in enum.items() if e["name"] == name]
        check(len(hit) == 1, "枚举中应有且仅有一个 {}".format(name))

    return report()


def report() -> int:
    print()
    if FAILURES:
        print("=" * 74)
        print("校验失败（共 {} 项，失败 {} 项）".format(CHECKS, len(FAILURES)))
        print("=" * 74)
        for f in FAILURES:
            print("  \u2717 " + f)
        return 1
    print("=" * 74)
    print("全部通过（{} 项）".format(CHECKS))
    print("=" * 74)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except SystemExit:
        raise
    except Exception:  # noqa: BLE001
        import traceback
        print("\n校验器自身异常（不是错误码的问题，是本脚本的 bug）:", file=sys.stderr)
        traceback.print_exc()
        sys.exit(2)
