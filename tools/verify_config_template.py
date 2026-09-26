#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""模板与代码的一致性校验：deploy/conf/application-external.yml.example ↔ @ConfigurationProperties。

为什么必须机器校验
--------------------------------------------------------------------------
那份模板在代码注释里被称作「运维视角的权威文档」。但「文档」与「代码」之间
没有任何编译期约束，于是必然漂移，且漂移方向总是最坏的一种：**模板缺项**。
运维照着模板配完，服务用的是代码默认值 —— 而「模板里没写」看起来完全等于
「这一项不需要配」。真实事故形态：

  * 模板没写 `auth-timeout-ms` → 运维不知道它存在，也不知道它可调
  * 模板没写 `tm.identity.jwt-secret` → 生产没设 `TM_JWT_SECRET`，
    启动时才炸（更糟的版本是代码里给个默认密钥，于是仓库里躺着一把能用的钥匙）
  * 模板写 `worker-id: ${TM_WORKER_ID:1}` → 每个实例都拿到 1，
    多实例部署时 Snowflake 节点号相撞 → 主键撞车（而单实例测试永远发现不了）

检查三件事
--------------------------------------------------------------------------
1. 代码里有的 `tm.*` 配置项，模板必须有（方向一：代码 → 模板）
2. 在已有配置类的前缀下，模板不得出现代码里没有的键（方向二：模板 → 代码）
   —— 这条抓的是拼写错误，如 `max-frams-per-second`
3. 两边的默认值必须一致；代码里「没有默认值」的项（如 jwt-secret）
   在模板里必须写成 `${ENV}` 且不给默认值

`tm.storage` / `tm.message` / `tm.agent` / `tm.friend` / `tm.feed` 这些
还没有对应配置类的段落属于「模板先行」，只做提示不算失败；但一旦某个前缀
有了配置类，它下面就按严格模式比对。

用法
    uv run --with pyyaml python tools/verify_config_template.py
    uv run --with pyyaml python tools/verify_config_template.py -v
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

import yaml

REPO = Path(__file__).resolve().parent.parent
TEMPLATE = REPO / "deploy" / "conf" / "application-external.yml.example"
SERVER = REPO / "server"

# 只检查这些前缀：它们都在 tm.* 之下，与外部服务（spring.*）无关
PREFIX_ROOT = "tm"

# 模板里以 ${VAR} 或 ${VAR:默认值} 形式出现的环境变量占位符
PLACEHOLDER = re.compile(r"^\$\{([A-Za-z_][A-Za-z0-9_]*)(?::(.*))?\}$", re.S)

# 只允许数字与四则运算：模板默认值在代码里可能写成 1024 * 1024
ARITHMETIC = re.compile(r"^[0-9_+\-*/\s()]+$")


class Problem(Exception):
    """校验过程中发现的不一致。"""


# --------------------------------------------------------------------------
# Java 侧：解析 @ConfigurationProperties 类
# --------------------------------------------------------------------------

def strip_comments(text: str) -> str:
    """去掉 // 与 /* */ 注释。

    不去注释会误判：类注释里写 `tm.netty.port` 之类的字样、
    或者被注释掉的旧字段，都会被当成真实存在。

    **同时要认出字符串字面量**（否则一样误判，方向相反）：
    第一版用正则 `//[^\n]*` 一刀切，而 `String mediaPublicBase =
    "http://localhost:8080/v1/media";` 里的 `//` 就在字符串里。
    后果是这一行被从中间截断 → 字段解析不出来 → 报「模板里的
    `tm.storage.media-public-base` 在配置类里不存在（拼写错误？）」，
    指向一个完全没写错的配置项，而真正的原因是这个函数。
    所以这里改成一个小状态机：进字符串就忽略注释符号，处理转义。
    """
    out = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c == '"':
            # 字符串字面量：整体保留（含其中的 // 与 /*）。
            # Java 的文本块（"""）在本仓库里没被用于初值，不单独处理。
            out.append(c)
            i += 1
            while i < n:
                ch = text[i]
                out.append(ch)
                i += 1
                if ch == "\\":
                    if i < n:
                        out.append(text[i])
                        i += 1
                    continue
                if ch == '"':
                    break
            continue
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                i += 1
            continue
        if c == "/" and i + 1 < n and text[i + 1] == "*":
            end = text.find("*/", i + 2)
            i = n if end < 0 else end + 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def kebab(name: str) -> str:
    """workerId → worker-id；jwtSecret → jwt-secret。"""
    return re.sub(r"(?<!^)(?=[A-Z])", "-", name).lower()


def class_body(text: str, prefix: str) -> str:
    """取出 @ConfigurationProperties(prefix = "x") 那个类的花括号内部。"""
    m = re.search(r'@ConfigurationProperties\(\s*prefix\s*=\s*"' + re.escape(prefix) + r'"\s*\)', text)
    if not m:
        raise Problem(f"找不到 prefix={prefix} 的配置类")
    brace = text.find("{", m.end())
    if brace < 0:
        raise Problem(f"prefix={prefix}：类声明后找不到 {{")
    depth = 0
    for i in range(brace, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[brace + 1:i]
    raise Problem(f"prefix={prefix}：花括号不配对")


FIELD = re.compile(
    r"^\s*private\s+(?!static)(?P<type>[A-Za-z0-9_.<>\[\], ]+?)\s+"
    r"(?P<name>[a-z][A-Za-z0-9_]*)\s*(?:=\s*(?P<init>.+?))?;\s*$",
    re.M)


def parse_duration(expr: str):
    """把 Java 里的 Duration 表达式折算为纳秒；不认识的写法返回 None。"""
    m = re.fullmatch(r"Duration\.of(Nanos|Millis|Seconds|Minutes|Hours|Days)\(\s*(\d+)\s*\)", expr)
    if m:
        unit, amount = m.group(1), int(m.group(2))
        factor = {"Nanos": 1, "Millis": 10 ** 6, "Seconds": 10 ** 9,
                  "Minutes": 60 * 10 ** 9, "Hours": 3600 * 10 ** 9,
                  "Days": 86400 * 10 ** 9}[unit]
        return amount * factor
    m = re.fullmatch(r'Duration\.parse\(\s*"([^"]+)"\s*\)', expr)
    if m:
        return iso_duration_to_nanos(m.group(1))
    return None


def iso_duration_to_nanos(text: str):
    """ISO-8601 时长（PT2H、PT30S…）→ 纳秒。"""
    m = re.fullmatch(r"P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?", text)
    if not m:
        return None
    d, h, mi, s = (float(g) if g else 0.0 for g in m.groups())
    return int((d * 86400 + h * 3600 + mi * 60 + s) * 10 ** 9)


def human_duration_to_nanos(text: str):
    """Spring 风格的时长字面量：`2h`、`3000ms`、`1d`、`90s`。"""
    m = re.fullmatch(r"\s*(\d+)\s*(ms|s|m|h|d)?\s*", text or "")
    if not m:
        return None
    amount = int(m.group(1))
    factor = {"None": 1, None: 1, "ms": 10 ** 6, "s": 10 ** 9,
              "m": 60 * 10 ** 9, "h": 3600 * 10 ** 9, "d": 86400 * 10 ** 9}[m.group(2)]
    return amount * factor


def java_default(type_name: str, init: str | None):
    """算出 Java 字段的默认值。返回 (是否有默认值, 归一化后的值)。"""
    type_name = type_name.strip()
    if init is None:
        return False, None
    init = init.strip()

    if type_name == "String":
        m = re.fullmatch(r'"((?:[^"\\]|\\.)*)"', init)
        if not m:
            return False, None          # 非字面量初值（如注入来的），当作「无默认」
        return True, m.group(1)
    if type_name in ("int", "long", "short", "byte"):
        if not ARITHMETIC.match(init):
            raise Problem(f"看不懂的整型初值: {init}")
        return True, int(eval(init, {"__builtins__": {}}, {}))  # noqa: S307 —— 已被 ARITHMETIC 限制为数字与四则运算
    if type_name in ("Integer", "Long"):
        return True, int(init) if init.isdigit() else None
    if type_name == "boolean":
        return True, init == "true"
    if type_name == "Duration":
        nanos = parse_duration(init)
        if nanos is None:
            raise Problem(f"看不懂的 Duration 初值: {init}")
        return True, nanos
    if type_name == "ZoneId":
        m = re.fullmatch(r'ZoneId\.of\(\s*"([^"]+)"\s*\)', init)
        if m:
            return True, m.group(1)
        raise Problem(f"看不懂的 ZoneId 初值: {init}")
    # 其它类型（List/Map/自定义类）不做默认值比对
    return False, None


def collect_java_properties() -> dict[str, dict[str, object]]:
    """返回 {前缀: {键: Field}}。"""
    result: dict[str, dict[str, object]] = {}
    for path in sorted(SERVER.rglob("*.java")):
        if "src/main/java" not in path.as_posix() or "target" in path.parts:
            continue
        text = strip_comments(path.read_text(encoding="utf-8", errors="replace"))
        for prefix in re.findall(r'@ConfigurationProperties\(\s*prefix\s*=\s*"([^"]+)"\s*\)', text):
            body = class_body(text, prefix)
            fields: dict[str, object] = {}
            for m in FIELD.finditer(body):
                name = m.group("name")
                fields[kebab(name)] = {
                    "java": name,
                    "type": m.group("type").strip(),
                    "has_default": None,
                    "default": None,
                    "file": path.relative_to(REPO).as_posix(),
                }
                has_default, default = java_default(m.group("type"), m.group("init"))
                fields[kebab(name)]["has_default"] = has_default
                fields[kebab(name)]["default"] = default
            if prefix in result:
                raise Problem(f"前缀 {prefix} 被两个类声明：{path}")
            result[prefix] = fields
    return result


# --------------------------------------------------------------------------
# 模板侧
# --------------------------------------------------------------------------

def flatten(node, prefix: str = "") -> dict[str, object]:
    out: dict[str, object] = {}
    if isinstance(node, dict):
        for k, v in node.items():
            out.update(flatten(v, f"{prefix}{k}."))
    else:
        out[prefix[:-1]] = node
    return out


def normalize_template_value(key: str, raw):
    """把模板里的值归一化，并报告它是否用了环境变量占位符。

    返回 (是否占位符, 是否真的提供了默认值, 归一化值, 外层占位符名)

    ``${ENV:}`` 这种「空默认值」不算提供了默认值：对非 String 类型，
    空串会被 Spring 绑定成 null（已有单测钉住这个行为），
    正好对应代码里的「本项未配置」分支。
    但对 String 类型，``${ENV:}`` 就是空字符串 —— 它依然是一次「赋值」，
    把「未配置」与「配成空串」混为一谈，所以那种情况仍算提供了默认值。
    """
    if isinstance(raw, str):
        m = PLACEHOLDER.match(raw.strip())
        if m:
            inner = m.group(2)
            if inner is None:
                return True, False, None, m.group(1)
            inner = inner.strip()
            if inner == "":
                return True, False, None, m.group(1)
            return True, True, normalize_scalar(key, inner), m.group(1)
    return False, True, normalize_scalar(key, raw), None


def normalize_scalar(key: str, value):
    if isinstance(value, bool):
        return value
    if isinstance(value, int):
        return value
    if isinstance(value, str):
        text = value.strip()
        if text in ("true", "false"):
            return text == "true"
        if re.fullmatch(r"-?\d+", text):
            return int(text)
        # ttl / 超时这类键按时长比较；其余按字符串
        if any(word in key for word in ("ttl", "timeout", "millis", "ttl-seconds")):
            nanos = human_duration_to_nanos(text)
            if nanos is not None:
                return nanos
        return text
    return value


def main() -> int:
    ap = argparse.ArgumentParser(description="模板与代码配置项一致性校验")
    ap.add_argument("-v", "--verbose", action="store_true", help="打印全部比对明细")
    ap.add_argument("--template", help="改用指定的模板文件（供变异测试注入缺陷用）")
    args = ap.parse_args()

    template_path = Path(args.template) if args.template else TEMPLATE
    if not template_path.exists():
        print(f"缺少模板: {template_path}")
        return 2

    try:
        java_props = collect_java_properties()
    except Problem as e:
        print("解析 Java 配置类失败: " + str(e))
        return 2

    document = yaml.safe_load(template_path.read_text(encoding="utf-8"))
    if not isinstance(document, dict):
        print("模板不是一个 YAML 映射")
        return 2
    template = flatten(document)

    problems: list[str] = []
    checked = 0
    forward_looking: list[str] = []

    # ---- 方向一：代码 → 模板（含默认值一致性） ----
    for prefix, fields in sorted(java_props.items()):
        if not prefix.startswith(PREFIX_ROOT):
            continue
        if not any(k.startswith(prefix + ".") for k in template):
            problems.append(
                f"模板里完全没有 `{prefix}.*` 段落，而代码里有配置类 "
                f"（{next(iter(fields.values()))['file']}）—— 运维照模板配不出来")
            continue
        for key, field in sorted(fields.items()):
            full = f"{prefix}.{key}"
            checked += 1
            if full not in template:
                problems.append(
                    f"模板缺项 `{full}`（代码默认 "
                    f"{'无' if not field['has_default'] else repr(field['default'])}，"
                    f"来自 {field['file']}）—— 运维不会知道这一项存在")
                continue

            is_placeholder, has_default, value, env = normalize_template_value(full, template[full])
            if not field["has_default"]:
                # 代码里没有默认值 = 这一项必须由部署方提供。
                # 模板若给了默认值，等于在仓库里放了一个「能用」的密钥。
                # ${ENV:} 例外地允许：非 String 类型下空串绑定为 null，
                # 正是代码里那条「未配置」分支（如 snowflake 按主机名哈希取节点号）。
                allowed_empty = is_placeholder and not has_default and not field["type"].endswith("String")
                if not is_placeholder or (has_default and not allowed_empty):
                    problems.append(
                        f"`{full}` 在代码里没有默认值，模板必须写成 ${{ENV}}（不给默认值），"
                        f"现在是 {template[full]!r}")
                continue

            if not has_default:
                problems.append(f"`{full}` 的占位符 ${{{env}}} 没有默认值，"
                                f"而代码默认值是 {field['default']!r} —— "
                                f"缺省部署会直接启动失败")
                continue

            if value != field["default"]:
                problems.append(
                    f"`{full}` 默认值不一致：代码 {field['default']!r}，模板 {value!r}"
                    f"（模板值用于人工阅读与缺省部署，两者不同意味着"
                    f"「开发环境行为」与「生产行为」不同）")

    # ---- 方向二：模板 → 代码（抓拼写错误） ----
    for key, raw in sorted(template.items()):
        if not key.startswith(PREFIX_ROOT + "."):
            continue
        # 找出这个键归属的配置类：取「最长的、确实是配置类的前缀」
        parts = key.split(".")
        owner = None
        for i in range(len(parts) - 1, 0, -1):
            candidate = ".".join(parts[:i])
            if candidate in java_props:
                owner = candidate
                break
        if owner is None:
            forward_looking.append(key)
            continue
        # 注意：java_props 里的键是「去掉前缀」的相对名（worker-id），
        # 而模板展开出来的是全路径（tm.snowflake.worker-id）—— 必须换算后再比。
        relative = key[len(owner) + 1:]
        if relative not in java_props[owner]:
            problems.append(
                f"模板里的 `{key}` 在 {owner} 的配置类里不存在"
                f"（拼写错误？字段已改名？）—— 这个键会被静默忽略")

    print("配置模板一致性校验")
    print(f"  模板: {template_path}")
    print(f"  配置类: " + ", ".join(f"{p}({len(f)})" for p, f in sorted(java_props.items())))
    print(f"  已比对 {checked} 项")
    if forward_looking:
        print(f"  模板先行（暂无配置类，只提示）: {len(forward_looking)} 项")
        if args.verbose:
            for key in forward_looking:
                print(f"    - {key}")
    print()

    if problems:
        print(f"发现 {len(problems)} 处不一致：")
        for p in problems:
            print("  ✗ " + p)
        return 1
    print("OK 模板与代码一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
