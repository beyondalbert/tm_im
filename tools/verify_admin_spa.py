#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""后台三方一致性：服务端 Java ↔ 接入文档 ↔ 后台 SPA 的类型与调用。

**为什么需要它**：后台前端的错误表现几乎全是**静默**的。

  * 端点路径拼错 → 服务端回 `40400`（与「资源不存在」共用一个码），
    界面上看起来像「这条数据没了」；
  * 查询参数名拼错（`actorType` 而不是 `actor_type`）→ 服务端忽略它，
    界面显示「已筛选 Agent」，实际拿到的是全部参与者；
  * 视图字段名拼错（`createdAt` 而不是 `created_at`）→ 那个单元格永远是空的；
  * 文档与实现漂移 → 读文档接入的人写出来的客户端永远 401 或永远查不到。

这四种都不会让 `npm run build` 失败、不会让类型检查失败、
也不会让任何一次「用对了参数」的集成测试失败。它们只在**人对着界面发呆**时
才暴露出来。

判据是三方（Java 控制器与记录 / 08-admin-api.md / web/admin 的
`endpoints.ts` + `types.ts` + `admin.ts` + `errors.ts`）在下列各处一致：

  1. 端点集合（方法 + 路径）—— 三方相等；
  2. 路径参数的**名字**（`{actorId}`）—— Java 与 SPA 相等（拼错时
     `resolvePath` 会在运行期抛错，而那是「点开详情页才炸」）；
  3. 查询参数名 —— SPA 用的每个名字都必须能在该端点的 `@RequestParam` 里找到
     （服务端多出来的会**报告但不判错**：界面有权只暴露一部分筛选条件）；
  4. 视图与请求体的字段 —— Java record 组件（驼峰转蛇形）与 TS 接口逐字段相等，
     且文档里的字段表也相等；
  5. 权限（SUPER / 任意管理员）—— 文档与 `endpoints.superOnly` 相等；
  6. 错误码 —— 后台这条路径上可能抛出的码，文档与 SPA 的文案表都必须覆盖，
     且两边的集合相等。

用法：
    uv run --no-progress python tools/verify_admin_spa.py [-v]
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SPA_API = REPO / "web" / "admin" / "src" / "api"
ADMIN_JAVA = REPO / "server" / "tm-api-admin" / "src" / "main" / "java" / "com" / "tm" / "im" / "api" / "admin"
CORE_ADMIN = REPO / "server" / "tm-core" / "src" / "main" / "java" / "com" / "tm" / "im" / "core" / "admin"
ERROR_CODE_JAVA = (REPO / "server" / "tm-common" / "src" / "main" / "java" / "com" / "tm" / "im"
                   / "common" / "error" / "ErrorCode.java")
DOC = REPO / "docs" / "integration" / "08-admin-api.md"

# ---------------------------------------------------------------------------
# 解析：服务端
# ---------------------------------------------------------------------------

CLASS_MAPPING = re.compile(r"@RequestMapping\(\s*(?:value\s*=\s*)?\"([^\"]*)\"")
METHOD_MAPPING = re.compile(
    r"@(Get|Post|Patch|Put|Delete)Mapping(?:\(\s*(?:value\s*=\s*)?\"([^\"]*)\"\s*\))?")
# 注解 + 它修饰的形参：@RequestParam(name = "x", required = false) Integer y,
#                        @PathVariable long actorId)
PARAM = re.compile(
    r"@(RequestParam|PathVariable)(?:\(\s*([^()]*)\))?\s*([\w<>\[\],.\s]+?)\s*(?=[,)])")
QUOTED_NAME = re.compile(r"name\s*=\s*\"([^\"]*)\"")
LAST_IDENT = re.compile(r"([A-Za-z_]\w*)\s*$")

RECORD = re.compile(r"public record (\w+)\(([^;]*?)\)\s*\{", re.S)
ENUM_ENTRY = re.compile(r"^\s*([A-Z][A-Z0-9_]*)\((\d+)\s*,", re.M)
JAVA_ERROR_REF = re.compile(r"ErrorCode\.([A-Z][A-Z0-9_]*)")

# ---------------------------------------------------------------------------
# 解析：文档
# ---------------------------------------------------------------------------

DOC_ENDPOINT = re.compile(r"^\|\s*(GET|POST|PATCH|DELETE)\s*\|\s*`([^`]+)`\s*\|\s*([^|]+?)\s*\|", re.M)
DOC_VIEW_HEAD = re.compile(r"^####\s+`(\w+)`\s*$", re.M)
DOC_TABLE_ROW = re.compile(r"^\|\s*([^|]+?)\s*\|", re.M)
DOC_VIEW_END = re.compile(r"^(?:####|###|##)\s", re.M)
DOC_ERROR_CODE = re.compile(r"^\|\s*(\d{5})\s*\|", re.M)

# ---------------------------------------------------------------------------
# 解析：SPA
# ---------------------------------------------------------------------------

TS_ENDPOINT = re.compile(
    r"^\s*(\w+):\s*\{\s*method:\s*'(\w+)',\s*path:\s*'([^']+)',\s*superOnly:\s*(true|false)\s*\},", re.M)
TS_INTERFACE = re.compile(r"export interface (\w+) \{(.*?)\n\}", re.S)
TS_FIELD = re.compile(r"^\s*([a-z_][a-z0-9_]*)\??:\s*(.+?)\s*$", re.M)
TS_REQUEST_HEAD = re.compile(r"client\.request<[^>]*>\(\s*ENDPOINTS\.(\w+)\s*,\s*\{")
TS_QUERY_BLOCK = re.compile(r"query:\s*\{(.*?)\}", re.S)
TS_PATH_PARAMS_BLOCK = re.compile(r"pathParams:\s*\{(.*?)\}", re.S)
TS_PROPERTY = re.compile(r"^[A-Za-z_]\w*$")


def object_keys(block: str) -> set[str]:
    """对象字面量的**键**（而不是它引用的变量）。

    `actor_type: query.actorType,` 的键是 `actor_type`，`query.actorType` 是值；
    只靠「标识符后面跟冒号或逗号」会把值里的标识符也算成键，
    于是 `handlePrefix` 会被当成一个服务端不认识的查询参数。
    """
    keys: set[str] = set()
    for part in block.split(","):
        piece = part.strip()
        if not piece:
            continue
        key = piece.split(":", 1)[0].strip() if ":" in piece else piece
        if TS_PROPERTY.match(key):
            keys.add(key)
    return keys
TS_ERROR_BLOCK = re.compile(r"ADMIN_ERROR_MESSAGES[^=]*=\s*\{(.*?)\n\}", re.S)
TS_ERROR_ENTRY = re.compile(r"^\s*(\d+)\s*:", re.M)
TS_PLACEHOLDER = re.compile(r"\{(\w+)\}")


def snake(name: str) -> str:
    """Java 的驼峰组件名 → 线上字段名（Jackson 的 SNAKE_CASE）。"""
    return re.sub(r"(?<=[a-z0-9])([A-Z])", r"_\1", name).lower()


def _split_top_level(text: str) -> list[str]:
    """按顶层逗号切分（尖括号里的逗号不算，`Map<String, Integer>`）。"""
    parts, depth, current = [], 0, ""
    for ch in text:
        if ch in "<(":
            depth += 1
        elif ch in ">)":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append(current)
            current = ""
        else:
            current += ch
    if current.strip():
        parts.append(current)
    return parts


def parse_java_endpoints(source: str) -> dict[tuple[str, str], dict]:
    """{（方法, 路径）: {params, vars}}。"""
    prefix_match = CLASS_MAPPING.search(source)
    prefix = prefix_match.group(1) if prefix_match else ""
    found: dict[tuple[str, str], dict] = {}
    for m in METHOD_MAPPING.finditer(source):
        method = m.group(1).upper()
        path = prefix + (m.group(2) or "")
        tail = source[m.end():]
        brace = tail.find("{")
        signature = tail[:brace] if brace >= 0 else tail
        params, vars_ = set(), set()
        for ann, args, decl in PARAM.findall(signature):
            args = args or ""
            decl = " ".join(decl.split())
            if ann == "PathVariable":
                quoted = re.search(r"\"([^\"]*)\"", args)
                name = quoted.group(1) if quoted else (LAST_IDENT.search(decl).group(1) if LAST_IDENT.search(decl) else "")
                if not name:
                    continue
                vars_.add(name)
                continue
            quoted = QUOTED_NAME.search(args)
            if quoted:
                params.add(quoted.group(1))
                continue
            last = LAST_IDENT.search(decl)
            if last:
                params.add(last.group(1))
        found[(method, normalize_path(path))] = {"params": params, "vars": vars_}
    return found


def normalize_path(path: str) -> str:
    """占位符统一成 `{}`：`{actorId}` 与 `{actor_id}` 是同一段路由。"""
    return re.sub(r"\{\w+\}", "{}", path)


def parse_java_records(source: str) -> dict[str, list[str]]:
    records: dict[str, list[str]] = {}
    for m in RECORD.finditer(source):
        fields = []
        for part in _split_top_level(m.group(2)):
            name = LAST_IDENT.search(" ".join(part.split()))
            if name:
                fields.append(snake(name.group(1)))
        records[m.group(1)] = fields
    return records


def parse_enum(source: str) -> dict[str, int]:
    return {name: int(code) for name, code in ENUM_ENTRY.findall(source)}


def parse_java_error_refs(*sources: str) -> set[str]:
    refs: set[str] = set()
    for source in sources:
        refs.update(JAVA_ERROR_REF.findall(source))
    return refs


# ---------------------------------------------------------------------------
# 解析：文档与 SPA
# ---------------------------------------------------------------------------

def parse_doc_endpoints(source: str) -> dict[tuple[str, str], str]:
    return {(m.group(1), normalize_path(m.group(2))): m.group(3).strip()
            for m in DOC_ENDPOINT.finditer(source)}


def parse_doc_views(source: str) -> dict[str, list[str]]:
    heads = list(DOC_VIEW_HEAD.finditer(source))
    views: dict[str, list[str]] = {}
    for index, head in enumerate(heads):
        end = len(source)
        nxt = DOC_VIEW_END.search(source, head.end())
        if nxt:
            end = nxt.start()
        body = source[head.end():end]
        fields: list[str] = []
        for row in DOC_TABLE_ROW.finditer(body):
            cell = row.group(1).strip().strip("`").strip("*").strip()
            if not cell or cell in {"字段", "码", "动作", "方法"} or set(cell) <= set("-: "):
                continue
            fields.append(cell)
        views[head.group(1)] = fields
    return views


def parse_doc_error_codes(source: str) -> set[int]:
    return {int(code) for code in DOC_ERROR_CODE.findall(source)}


def parse_ts_endpoints(source: str) -> dict[str, dict]:
    return {name: {"method": method, "path": path, "superOnly": super_only == "true"}
            for name, method, path, super_only in TS_ENDPOINT.findall(source)}


def parse_ts_interfaces(source: str) -> dict[str, list[str]]:
    return {m.group(1): [f.group(1) for f in TS_FIELD.finditer(m.group(2))]
            for m in TS_INTERFACE.finditer(source)}


def parse_ts_calls(source: str) -> dict[str, dict]:
    """每个 `client.request(ENDPOINTS.x, { ... })` 用了哪些查询/路径参数。

    花括号按嵌套计数配平，而不是用非贪婪正则：后者的结束条件（`\n  })`）
    会跨过同文件里下一个调用的结尾，于是把「下一个端点传的路径参数」算到这个
    端点头上——而那种错误正好会在本校验器里指向一个**完全无关**的端点。
    """
    calls: dict[str, dict] = {}
    for m in TS_REQUEST_HEAD.finditer(source):
        depth, index = 1, m.end()
        while index < len(source) and depth:
            if source[index] == "{":
                depth += 1
            elif source[index] == "}":
                depth -= 1
            index += 1
        body = source[m.end():index - 1]
        query = TS_QUERY_BLOCK.search(body)
        path_params = TS_PATH_PARAMS_BLOCK.search(body)
        calls[m.group(1)] = {
            "query": object_keys(query.group(1)) if query else set(),
            "path_params": object_keys(path_params.group(1)) if path_params else set(),
        }
    return calls


def parse_ts_error_codes(source: str) -> set[int]:
    block = TS_ERROR_BLOCK.search(source)
    if not block:
        return set()
    return {int(code) for code in TS_ERROR_ENTRY.findall(block.group(1))}


# ---------------------------------------------------------------------------
# 比对
# ---------------------------------------------------------------------------

def compare(sources: dict[str, str]) -> tuple[list[str], list[str]]:
    """返回（问题, 提示）。"""
    problems: list[str] = []
    notes: list[str] = []

    java_eps: dict[tuple[str, str], dict] = {}
    for name, text in sources.items():
        if name.startswith("controller:"):
            java_eps.update(parse_java_endpoints(text))

    java_records: dict[str, list[str]] = {}
    for name, text in sources.items():
        if name.startswith("view:"):
            java_records.update(parse_java_records(text))
    if not sources.get("view:AdminViews.java"):
        pass  # AdminViews 是转换点、不是视图；没有 record 是正常的

    doc_eps = parse_doc_endpoints(sources["doc"])
    ts_eps = parse_ts_endpoints(sources["endpoints.ts"])
    ts_eps_keys = {(spec["method"], normalize_path(spec["path"])) for spec in ts_eps.values()}

    # 1. 端点集合：三方相等 --------------------------------------------------
    for key in sorted(set(java_eps) - ts_eps_keys):
        problems.append(f"前端少了端点 `{key[0]} {key[1]}`（服务端有，`endpoints.ts` 里没有）"
                        "——后台界面会少一个能力，而且不会报错")
    for key in sorted(ts_eps_keys - set(java_eps)):
        problems.append(f"前端多了端点 `{key[0]} {key[1]}`（服务端没有这个路由）"
                        "——调用它的结果是 40400，看起来像「数据不存在」")
    for key in sorted(set(java_eps) - set(doc_eps)):
        problems.append(f"文档少了端点 `{key[0]} {key[1]}`（§4 的端点一览里没有）")
    for key in sorted(set(doc_eps) - set(java_eps)):
        problems.append(f"文档多了端点 `{key[0]} {key[1]}`（服务端没有这个路由）")

    # 2. 路径参数名：Java 与 SPA 相等 ----------------------------------------
    java_vars = {key: spec["vars"] for key, spec in java_eps.items()}
    for name, spec in sorted(ts_eps.items()):
        key = (spec["method"], normalize_path(spec["path"]))
        placeholders = set(TS_PLACEHOLDER.findall(spec["path"]))
        if key in java_vars and placeholders != java_vars[key]:
            problems.append(
                f"端点 `{key[0]} {key[1]}` 的路径参数名不一致："
                f"前端 {sorted(placeholders)}，服务端 {sorted(java_vars[key])}"
                "——`resolvePath` 找不到同名参数会在运行期抛错，而那要等点到那一页才发生")

    # 3. 查询参数与路径参数：SPA 用的名字必须在服务端存在 --------------------
    ts_calls = parse_ts_calls(sources["admin.ts"])
    for call, usage in sorted(ts_calls.items()):
        spec = ts_eps.get(call)
        if spec is None:
            problems.append(f"`admin.ts` 调用了 `ENDPOINTS.{call}`，但 `endpoints.ts` 里没有这个名字")
            continue
        key = (spec["method"], normalize_path(spec["path"]))
        java_spec = java_eps.get(key)
        if java_spec is None:
            continue  # 端点缺失已在上面的集合比对里报过
        unknown = usage["query"] - java_spec["params"]
        for param in sorted(unknown):
            problems.append(
                f"端点 `{key[0]} {key[1]}` 用了查询参数 `{param}`，服务端没有这个 `@RequestParam`"
                "——它会**被静默忽略**：界面显示「已筛选」，拿到的其实是全部数据")
        wrong_vars = usage["path_params"] - java_spec["vars"]
        for param in sorted(wrong_vars):
            problems.append(f"端点 `{key[0]} {key[1]}` 传了路径参数 `{param}`，服务端没有这个 `@PathVariable`")
        unused = java_spec["params"] - usage["query"]
        if unused:
            notes.append(f"`{key[0]} {key[1]}` 有 {len(unused)} 个服务端筛选参数未在前端暴露："
                         f"{', '.join(sorted(unused))}")

    # 4. 字段：Java record ↔ TS 接口 ↔ 文档字段表 ----------------------------
    ts_ifaces = parse_ts_interfaces(sources["types.ts"])
    doc_views = parse_doc_views(sources["doc"])
    for name in sorted(set(java_records) - set(ts_ifaces)):
        problems.append(f"服务端的 `{name}` 在前端 `types.ts` 里没有对应接口"
                        "——那个端点的响应在前端只能当 `any`，字段名写错也不会有人告诉你")
    for name in sorted(set(ts_ifaces) - set(java_records)):
        problems.append(f"前端的 `{name}` 在服务端没有对应记录（名字写错了，或者是删掉后没清理）")
    for name in sorted(set(java_records) & set(ts_ifaces)):
        want, got = set(java_records[name]), set(ts_ifaces[name])
        for field in sorted(want - got):
            problems.append(f"前端类型 `{name}` 缺字段 `{field}`（服务端有）"
                            "——界面上那个单元格永远是空的，不报错")
        for field in sorted(got - want):
            problems.append(f"前端类型 `{name}` 多字段 `{field}`（服务端没有；"
                            "名字写错就是这个样子）")
    for name in sorted(set(java_records) - set(doc_views)):
        problems.append(f"文档 §6 里没有 `{name}` 的字段表")
    for name in sorted(set(doc_views) - set(java_records)):
        problems.append(f"文档 §6 里写了 `{name}`，但服务端没有这个记录")
    for name in sorted(set(java_records) & set(doc_views)):
        want, got = set(java_records[name]), set(doc_views[name])
        for field in sorted(want - got):
            problems.append(f"文档的 `{name}` 字段表缺字段 `{field}`")
        for field in sorted(got - want):
            problems.append(f"文档的 `{name}` 字段表多字段 `{field}`")

    # 5. 权限：文档的「权限」列 ↔ endpoints.superOnly ------------------------
    for name, spec in sorted(ts_eps.items()):
        key = (spec["method"], normalize_path(spec["path"]))
        if key not in doc_eps:
            continue
        doc_says_super = doc_eps[key].upper().startswith("SUPER")
        if doc_says_super != spec["superOnly"]:
            problems.append(
                f"端点 `{key[0]} {key[1]}` 的权限不一致：文档写「{doc_eps[key]}」，"
                f"前端 superOnly={spec['superOnly']}"
                "——两者不一致时，要么界面把有用的入口藏了，要么露出一条注定 40302 的路径")

    # 6. 错误码：后台这条路径上的码，文档与 SPA 都要覆盖 ----------------------
    enum_codes = parse_enum(sources["ErrorCode.java"])
    refs = parse_java_error_refs(sources["AdminService.java"],
                                 *[t for n, t in sources.items() if n.startswith("controller:")],
                                 *[t for n, t in sources.items() if n.startswith("web:")])
    required: set[int] = set()
    for ref in sorted(refs):
        if ref in enum_codes:
            required.add(enum_codes[ref])
        else:
            problems.append(f"代码里引用了 `ErrorCode.{ref}`，但枚举里没有这个常量")
    ts_codes = parse_ts_error_codes(sources["errors.ts"])
    doc_codes = parse_doc_error_codes(sources["doc"])
    for code in sorted(required - ts_codes):
        problems.append(f"服务端可能抛 `{code}`，但前端 `errors.ts` 的文案表里没有它"
                        "——界面上会显示「未知错误」，而它可能是「不能停用自己」这种必须说清的事")
    for code in sorted(required - doc_codes):
        problems.append(f"服务端可能抛 `{code}`，但文档 §7.1 的错误码表里没有它")
    for code in sorted(ts_codes - doc_codes):
        problems.append(f"前端文案表里有 `{code}`，文档 §7.1 里没有")
    for code in sorted(doc_codes - ts_codes):
        problems.append(f"文档 §7.1 里有 `{code}`，前端文案表里没有")

    notes.insert(0, f"端点 {len(java_eps)} 个（三方一致）")
    notes.insert(1, f"视图/请求体 {len(java_records)} 个，字段 "
                    f"{sum(len(v) for v in java_records.values())} 个（三方一致）")
    notes.insert(2, f"错误码 {len(required)} 个被服务端引用，文档与前端各覆盖 {len(doc_codes)} 个")
    return problems, notes


# ---------------------------------------------------------------------------
# 自检：注入漂移，看它们是否被抓住
# ---------------------------------------------------------------------------

def load_sources() -> dict[str, str]:
    sources: dict[str, str] = {}
    for path, key in ((SPA_API / "endpoints.ts", "endpoints.ts"),
                      (SPA_API / "admin.ts", "admin.ts"),
                      (SPA_API / "types.ts", "types.ts"),
                      (SPA_API / "errors.ts", "errors.ts"),
                      (ERROR_CODE_JAVA, "ErrorCode.java"),
                      (CORE_ADMIN / "AdminService.java", "AdminService.java"),
                      (DOC, "doc")):
        if not path.is_file():
            sys.exit(f"缺少 {path.relative_to(REPO)}")
        sources[key] = path.read_text(encoding="utf-8")
    for path in sorted((ADMIN_JAVA / "controller").glob("*.java")):
        sources[f"controller:{path.name}"] = path.read_text(encoding="utf-8")
    for path in sorted((ADMIN_JAVA / "view").glob("*.java")):
        sources[f"view:{path.name}"] = path.read_text(encoding="utf-8")
    for path in sorted((ADMIN_JAVA / "web").glob("*.java")):
        sources[f"web:{path.name}"] = path.read_text(encoding="utf-8")
    return sources


def _with(sources: dict[str, str], key: str, old: str, new: str) -> dict[str, str]:
    mutated = dict(sources)
    text = mutated[key]
    if old not in text:
        sys.exit(f"自检失败：在 {key} 里找不到要替换的片段 {old!r}")
    mutated[key] = text.replace(old, new, 1)
    return mutated


def selftest(sources: dict[str, str]) -> int:
    clean, _ = compare(sources)
    if clean:
        print("自检失败：没有任何漂移的三方被判成了不一致")
        for p in clean:
            print(f"    {p}")
        return 1

    cases = [
        ("前端路径写错（少了一个字母）",
         _with(sources, "endpoints.ts", "path: '/v1/admin/actors/{actorId}', superOnly: false },",
               "path: '/v1/admin/actor/{actorId}', superOnly: false },"),
         "前端少了端点 `GET /v1/admin/actors/{}`"),
        ("前端把端点名字改了，而调用处没跟上",
         _with(sources, "endpoints.ts", "  getActor: { method: 'GET'", "  ghostActor: { method: 'GET'"),
         "`admin.ts` 调用了 `ENDPOINTS.getActor`，但 `endpoints.ts` 里没有这个名字"),
        ("前端端点方法写错",
         _with(sources, "endpoints.ts",
               "listPosts: { method: 'GET', path: '/v1/admin/posts'",
               "listPosts: { method: 'POST', path: '/v1/admin/posts'"),
         "前端多了端点 `POST /v1/admin/posts`"),
        ("前端路径拼错",
         _with(sources, "endpoints.ts", "path: '/v1/admin/audit-logs'", "path: '/v1/admin/auditlog'"),
         "前端多了端点 `GET /v1/admin/auditlog`"),
        ("前端多了一个服务端没有的端点",
         _with(sources, "endpoints.ts", "  me: { method: 'GET'",
               "  ghost: { method: 'GET', path: '/v1/admin/ghost', superOnly: false },\n"
               "  me: { method: 'GET'"),
         "前端多了端点 `GET /v1/admin/ghost`"),
        ("服务端记录多了一列、前端没跟上",
         _with(sources, "view:ActorAdminView.java", "String bio,", "String bio, String ghostField,"),
         "前端类型 `ActorAdminView` 缺字段 `ghost_field`"),
        ("前端字段名写成了驼峰",
         _with(sources, "types.ts", "  created_at: Instant\n", "  createdAt: Instant\n"),
         "前端类型 `AdminView` 缺字段 `created_at`"),
        ("文档的字段表漏了一个字段",
         _with(sources, "doc", "| deleted | boolean |", "| 已删除 | boolean |"),
         "文档的 `AdminDeletedView` 字段表多字段 `已删除`"),
        ("文档漏了一个端点",
         _with(sources, "doc", "| POST | `/v1/admin/auth/login` | 公开 | 建立后台会话 |\n", ""),
         "文档少了端点 `POST /v1/admin/auth/login`"),
        ("文档把权限标错了",
         _with(sources, "doc", "| GET | `/v1/admin/accounts` | SUPER |",
               "| GET | `/v1/admin/accounts` | 任意管理员 |"),
         "的权限不一致"),
        ("前端查询参数名写错",
         _with(sources, "admin.ts", "actor_type: query.actorType,", "actorType: query.actorType,"),
         "用了查询参数 `actorType`，服务端没有这个 `@RequestParam`"),
        ("前端路径参数名与服务端不一致",
         _with(sources, "endpoints.ts", "path: '/v1/admin/actors/{actorId}', superOnly: false },",
               "path: '/v1/admin/actors/{actor_id}', superOnly: false },"),
         "的路径参数名不一致"),
        ("服务端抛了一个前端文案表里没有的码",
         _with(sources, "AdminService.java", "if (admins.count() > 0) {",
               "if (admins.count() > 0) {\n"
               "            if (System.nanoTime() < 0) { throw new TmException(ErrorCode.ALREADY_LIKED, \"x\"); }"),
         "但前端 `errors.ts` 的文案表里没有它"),
        ("前端文案表里多了一个文档没有的码",
         _with(sources, "errors.ts", "  50000: ", "  40999: '不存在的码',\n  50000: "),
         "前端文案表里有 `40999`，文档 §7.1 里没有"),
    ]

    for label, mutated, expected in cases:
        problems, _ = compare(mutated)
        if not problems:
            print(f"自检失败：注入的「{label}」没有被发现")
            return 1
        if not any(expected in p for p in problems):
            print(f"自检失败：「{label}」虽然报错了，但没有报出预期信息 {expected!r}")
            for p in problems:
                print(f"    {p}")
            return 1

    print(f"自检 OK：注入的 {len(cases)} 类漂移全部被捕获，且指出了具体端点/字段/错误码")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="后台三方（Java ↔ 文档 ↔ 后台 SPA）一致性校验")
    ap.add_argument("-v", "--verbose", action="store_true", help="打印计数与未暴露的筛选参数")
    ap.add_argument("--no-selftest", action="store_true", help="跳过自检（诊断用）")
    args = ap.parse_args()

    sources = load_sources()
    if not args.no_selftest and selftest(sources) != 0:
        return 1
    print()

    problems, notes = compare(sources)
    print("服务端 Java ↔ docs/integration/08-admin-api.md ↔ web/admin")
    for note in notes:
        print(f"  · {note}")
    if args.verbose:
        for note in notes[3:]:
            print(f"    - {note}")

    print()
    if problems:
        print(f"发现 {len(problems)} 处不一致：")
        for p in problems:
            print(f"  ✗ {p}")
        return 1
    print("OK 端点、查询参数、路径参数、字段、权限、错误码三方一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
