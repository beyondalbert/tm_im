#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""分片配置三方一致性校验：DDL ↔ sharding.yaml ↔ ShardRouter.java

为什么需要这个检查
--------------------------------------------------------------------------
分片这件事的事实被写在三个互不相识的地方：

  1. deploy/sql/01-schema.sql            —— 16 张 message_N 物理表（MySQL 认这个）
  2. deploy/conf/sharding.yaml.example   —— INLINE 表达式（ShardingSphere 认这个）
  3. ShardRouter.java                    —— 应用层算表名的逻辑（我们的代码认这个）

三者任一处单独改动，都很可能仍然「能跑」：数据照样能写进去，也能读出来，
只是散落在错误的表里。等发现时，要么是某张表膨胀到写不动，要么是范围查询
慢得莫名其妙。所以必须在这里把它们钉在一起——一旦漂移就让构建失败。

关键设计：**不重算，而是去读**
--------------------------------------------------------------------------
早期版本在这里用 `logical + sharding_column + SHARD_COUNT` 自己拼出期望表达式，
再和 YAML 比。这是自证：期望值和 YAML 都是本脚本算的，Java 侧写成什么都不影响结论。
现在改成真正解析 ShardRouter.java 的常量声明，把 `"a" + X + "b"` 这种拼接求值出来，
再和 YAML 逐字符比。这样 Java 侧才是被检验的对象。

另外校验 YAML 本身可解析（模板带 !SHARDING 这类自定义标签，需注册宽松构造器）。
"""
from __future__ import annotations

import io
import re
import sys
from pathlib import Path

try:
    import yaml
except ImportError:
    print("需要 PyYAML：uv run --with pyyaml python tools/validate_yaml.py")
    sys.exit(2)

REPO = Path(__file__).resolve().parent.parent
SHARDING_YAML = REPO / "deploy" / "conf" / "sharding.yaml.example"
APP_YAML = REPO / "deploy" / "conf" / "application-external.yml.example"
SCHEMA_SQL = REPO / "deploy" / "sql" / "01-schema.sql"
SHARD_ROUTER = (REPO / "server" / "tm-common" / "src" / "main" / "java" / "com" / "tm"
                / "im" / "common" / "shard" / "ShardRouter.java")


class LenientLoader(yaml.SafeLoader):
    """允许 !SHARDING / !SINGLE 这类 ShardingSphere 自定义标签。"""


def _unknown_tag(loader, suffix, node):
    if isinstance(node, yaml.MappingNode):
        return loader.construct_mapping(node, deep=True)
    if isinstance(node, yaml.SequenceNode):
        return loader.construct_sequence(node, deep=True)
    return loader.construct_scalar(node)


LenientLoader.add_multi_constructor("!", _unknown_tag)

FAILURES: list[str] = []
CHECKS = 0


def check(condition: bool, message: str) -> bool:
    global CHECKS
    CHECKS += 1
    if not condition:
        FAILURES.append(message)
    return condition


def read(path: Path) -> str:
    return io.open(path, encoding="utf-8").read()


# ---------------------------------------------------------------------------
# Java 常量求值器
# ---------------------------------------------------------------------------
# 目的只有一个：把 ShardRouter.java 里 `"a" + NAME + "b"` 形式的常量还原成字符串。
# 故意做得极简——它不是 Java 解析器，遇到不认识的语法就抛 Unresolved，
# 让校验失败并提示人工确认，而不是悄悄算出一个错的期望值。

class Unresolved(Exception):
    """Java 常量表达式里出现了本求值器不认识的成分。"""


_JAVA_DECL = re.compile(
    r"public static final (String|int|long) (\w+)\s*=\s*(.+?);", re.S)


def java_const_decls(src: str) -> dict[str, tuple[str, str]]:
    return {m.group(2): (m.group(1), m.group(3).strip())
            for m in _JAVA_DECL.finditer(src)}


def _split_plus(expr: str) -> list[str]:
    """按顶层 '+' 切分；字符串字面量和括号内的 '+' 不切。"""
    parts, buf, depth, in_str, esc = [], [], 0, False, False
    for ch in expr:
        if in_str:
            buf.append(ch)
            if esc:
                esc = False
            elif ch == "\\":
                esc = True
            elif ch == '"':
                in_str = False
            continue
        if ch == '"':
            in_str = True
            buf.append(ch)
        elif ch == "(":
            depth += 1
            buf.append(ch)
        elif ch == ")":
            depth -= 1
            buf.append(ch)
        elif ch == "+" and depth == 0:
            parts.append("".join(buf))
            buf = []
        else:
            buf.append(ch)
    parts.append("".join(buf))
    return parts


# 整数算术表达式：只接受数字、已知整型常量、+ - * % 与括号。
# 故意不接受 '/'（Java 的整除与 Python 的 '/' 语义不同，宁可报错让人来看）；
# 也不接受任何其它字符，这样 eval 之前输入已被限制在一个极小的字母表内。
_INT_TOKEN = re.compile(r"[ \t]*(\d+|[A-Za-z_]\w*|[-+*%()])")
_INT_SAFE = re.compile(r"[0-9\s\-+*%()]+")


def eval_java_int(rhs: str, decls: dict[str, tuple[str, str]],
                  stack: tuple[str, ...] = ()) -> int:
    toks: list[str] = []
    pos = 0
    while pos < len(rhs):
        m = _INT_TOKEN.match(rhs, pos)
        if not m:
            raise Unresolved("整数表达式含非法字符：" + repr(rhs[pos:pos + 12]))
        toks.append(m.group(1))
        pos = m.end()
    if not toks:
        raise Unresolved("空整数表达式")

    resolved: list[str] = []
    for t in toks:
        if t.isdigit():
            resolved.append(t)
        elif re.fullmatch(r"[A-Za-z_]\w*", t):
            if t in stack:
                raise Unresolved("常量循环引用：" + " -> ".join(stack + (t,)))
            if t not in decls:
                raise Unresolved("引用了未定义的常量 " + t)
            typ, sub = decls[t]
            if typ not in ("int", "long"):
                raise Unresolved(t + " 不是整数常量，不能参与算术")
            resolved.append(str(eval_java_int(sub, decls, stack + (t,))))
        else:
            resolved.append(t)

    joined = " ".join(resolved)
    if not _INT_SAFE.fullmatch(joined):
        raise Unresolved("整数表达式清洗后仍含可疑字符：" + joined)
    return int(eval(joined, {"__builtins__": {}}))  # noqa: S307 —— 已限定字母表


def eval_java_const(rhs: str, decls: dict[str, tuple[str, str]],
                    stack: tuple[str, ...] = ()) -> str:
    out: list[str] = []
    for raw in _split_plus(rhs):
        term = raw.strip()
        if not term:
            continue
        if len(term) >= 2 and term[0] == '"' and term[-1] == '"':
            out.append(term[1:-1])
        elif re.fullmatch(r"-?\d+", term):
            out.append(term)
        elif term.startswith("(") and term.endswith(")"):
            inner = term[1:-1]
            try:
                out.append(str(eval_java_int(inner, decls, stack)))
            except Unresolved:
                out.append(eval_java_const(inner, decls, stack))
        elif term in decls:
            if term in stack:
                raise Unresolved("常量循环引用：" + " -> ".join(stack + (term,)))
            out.append(eval_java_const(decls[term][1], decls, stack + (term,)))
        else:
            raise Unresolved("无法求值的项 " + repr(term))
    return "".join(out)


def java_const_int(decls: dict[str, tuple[str, str]], name: str) -> int:
    if name not in decls:
        raise Unresolved("缺少常量 " + name)
    return eval_java_int(decls[name][1], decls)


def java_const_str(decls: dict[str, tuple[str, str]], name: str) -> str:
    if name not in decls:
        raise Unresolved("缺少常量 " + name)
    return eval_java_const(decls[name][1], decls)


def main() -> int:
    print("=" * 74)
    print("分片配置三方一致性校验")
    print("=" * 74)

    for p in (SHARDING_YAML, APP_YAML, SCHEMA_SQL, SHARD_ROUTER):
        if not p.exists():
            print("缺少文件: " + str(p.relative_to(REPO)))
            return 2

    # ---------- 1. YAML 可解析性 ----------
    parsed: dict[Path, dict] = {}
    for p in (SHARDING_YAML, APP_YAML):
        try:
            parsed[p] = yaml.load(read(p), Loader=LenientLoader)
            print("  [ OK ] 可解析  " + str(p.relative_to(REPO)))
        except Exception as e:  # noqa: BLE001
            print("  [FAIL] 解析失败 " + str(p.relative_to(REPO)) + ": " + str(e))
            return 2

    sharding = parsed[SHARDING_YAML]
    app = parsed[APP_YAML]

    # ---------- 2. 从 YAML 提取分片事实 ----------
    rules = sharding.get("rules") or []
    sharding_rule = next((r for r in rules if "tables" in r), None)
    if not check(sharding_rule is not None, "sharding.yaml 中缺少带 tables 的分片规则"):
        return report()

    tables = sharding_rule["tables"]
    check(len(tables) == 1,
          "分片规则应只声明 1 张逻辑表（message），实际 {} 张：{}".format(len(tables), list(tables)))
    logical = next(iter(tables))
    table_cfg = tables[logical]

    yaml_actual_nodes = table_cfg.get("actualDataNodes")
    strategy = table_cfg.get("tableStrategy", {}).get("standard", {})
    yaml_sharding_column = strategy.get("shardingColumn")
    algo_name = strategy.get("shardingAlgorithmName")
    algos = sharding_rule.get("shardingAlgorithms", {})
    algo = algos.get(algo_name, {})
    yaml_expression = algo.get("props", {}).get("algorithm-expression")
    algo_type = algo.get("type")

    check(algo_type == "INLINE",
          "分片算法类型应为 INLINE（内置、无额外依赖），实际 {!r}".format(algo_type))
    check(bool(yaml_expression), "分片算法缺少 algorithm-expression")

    # ---------- 3. 从 DDL 提取物理表 ----------
    ddl = read(SCHEMA_SQL)
    all_tables = re.findall(r"CREATE TABLE IF NOT EXISTS `(\w+)`", ddl)
    shard_tables = [t for t in all_tables if re.fullmatch(logical + r"_\d+", t)]
    shard_ids = sorted(int(t.rsplit("_", 1)[1]) for t in shard_tables)
    check(bool(shard_ids), "DDL 中找不到 " + logical + "_N 物理分片表")
    check(shard_ids == list(range(len(shard_ids))),
          "分片表编号不连续：{}；缺号会让路由算出的表名不存在".format(shard_ids))
    shard_count_ddl = len(shard_ids)

    # ---------- 4. 解析 ShardRouter.java（被检验的对象） ----------
    java = read(SHARD_ROUTER)
    try:
        decls = java_const_decls(java)
        java_shard_count = java_const_int(decls, "SHARD_COUNT")
        java_logic = java_const_str(decls, "LOGIC_TABLE")
        java_column = java_const_str(decls, "SHARDING_COLUMN")
        java_algo = java_const_str(decls, "INLINE_ALGORITHM")
        java_expression = java_const_str(decls, "INLINE_EXPRESSION")
        java_actual_nodes = java_const_str(decls, "ACTUAL_DATA_NODES")
    except Unresolved as e:
        check(False, "无法从 ShardRouter.java 求值出分片常量：{}".format(e))
        return report()
    print("  从 ShardRouter.java 求值:")
    print("      SHARD_COUNT          = {}".format(java_shard_count))
    print("      INLINE_EXPRESSION    = {}".format(java_expression))
    print("      ACTUAL_DATA_NODES    = {}".format(java_actual_nodes))

    # ---------- 5. Java ↔ YAML ↔ DDL 交叉断言 ----------
    check(logical == java_logic,
          "逻辑表名不一致：yaml={!r} java={!r}".format(logical, java_logic))
    check(yaml_sharding_column == java_column,
          "分片列不一致：yaml={!r} java={!r}".format(yaml_sharding_column, java_column))
    check(algo_name == java_algo,
          "分片算法名不一致：yaml={!r} java={!r}".format(algo_name, java_algo))

    check(java_expression == yaml_expression,
          "INLINE 表达式不一致：\n        yaml = {!r}\n        java = {!r}".format(
              yaml_expression, java_expression))
    check(java_actual_nodes == yaml_actual_nodes,
          "actualDataNodes 不一致：\n        yaml = {!r}\n        java = {!r}".format(
              yaml_actual_nodes, java_actual_nodes))

    check(shard_count_ddl == java_shard_count,
          "分片数与 DDL 不符：DDL 有 {} 张表，ShardRouter.SHARD_COUNT={}".format(
              shard_count_ddl, java_shard_count))

    check(yaml_expression == java_logic + "_${" + java_column + " % " + str(java_shard_count) + "}",
          "INLINE 表达式与 Java 常量拼不回来，三者口径已分裂")
    check(yaml_actual_nodes == "ds_0." + java_logic + "_${0.." + str(java_shard_count - 1) + "}",
          "actualDataNodes 与 SHARD_COUNT 不符")

    # ---------- 6. 硬编码口径 lint ----------
    # 分片数只能来自 SHARD_COUNT。注释或异常消息里写死数字，改分片数时不会报错，
    # 只会静静地给出错误提示（“分片算法 message_${conv_id % 16}” 而实际是 %8）。
    #
    # 规则刻意做得窄，因为这里审的是语义而不是词法：
    #   A. 出现 `${标识符 % 数字}` —— 这是把算法式写死了，必须是常量拼接
    #   B. javadoc 里的示例 “conv_id=N 落在 message_M” 必须与 SHARD_COUNT 自洽
    # 不去拦所有 `% 数字`：`-1 % 16 == -1` 是在讲 Java 取模语义，与分片数无关，
    # 报它会逼人把注释改得更难懂。
    stale_algo = [ln for ln, line in enumerate(java.splitlines(), 1)
                  if re.search(r"\$\{\s*\w+\s*%\s*\d+\s*\}", line)]
    check(not stale_algo,
          "ShardRouter.java 把分片算法式写死了（应引用 SHARD_COUNT 常量），行号：{}".format(stale_algo))

    examples: list[str] = []
    for ln, line in enumerate(java.splitlines(), 1):
        m_conv = re.search(r"conv_id\s*=\s*(\d+)", line)
        m_tbl = re.search(r"message_(\d+)", line)
        if m_conv and m_tbl:
            conv_id, tbl = int(m_conv.group(1)), int(m_tbl.group(1))
            if conv_id % java_shard_count != tbl:
                examples.append("第 {} 行：conv_id={} 实际落在 message_{}，文中写的是 message_{}"
                                .format(ln, conv_id, conv_id % java_shard_count, tbl))
    check(not examples, "javadoc 里的分片示例与 SHARD_COUNT 不符：\n        " + "\n        ".join(examples))

    # ---------- 7. 键名与数据源 ----------
    check("actualDataNodes" in table_cfg,
          "5.5.3 的键名是 actualDataNodes，不是 dataNodes（写错会解析不到物理节点）")
    check("dataNodes" not in table_cfg,
          "出现了旧版键名 dataNodes，ShardingSphere 5.5.3 会忽略它")

    check(isinstance(sharding.get("dataSources"), dict) and "ds_0" in sharding["dataSources"],
          "缺少 ds_0 数据源定义（actualDataNodes 里的 ds_0 指向它）")

    ds0 = sharding["dataSources"]["ds_0"]
    check(ds0.get("driverClassName") == "com.mysql.cj.jdbc.Driver",
          "ds_0.driverClassName 应为 com.mysql.cj.jdbc.Driver，实际 {!r}".format(
              ds0.get("driverClassName")))
    check(ds0.get("dataSourceClassName") == "com.zaxxer.hikari.HikariDataSource",
          "ds_0.dataSourceClassName 应为 HikariDataSource")

    # ---------- 8. 路由算法复算（与 ShardRouter.shardOf 同式） ----------
    for conv_id, want in ((1, 1), (15, 15), (16, 0), (100, 4)):
        got = "{}_{}".format(java_logic, conv_id % java_shard_count)
        check(got == "message_" + str(want),
              "路由复算不符：conv_id={} → {}，期望 message_{}".format(conv_id, got, want))
    check(str(100 % java_shard_count) == "4", "M1 验收标准：conv_id=100 必须落在 message_4")

    # ---------- 9. 应用配置模板的关键项 ----------
    tm = app.get("tm", {})
    check(bool(tm), "application-external.yml 缺少 tm 段")
    ds = (app.get("spring", {}) or {}).get("datasource", {}) or {}
    check(ds.get("driver-class-name") == "org.apache.shardingsphere.driver.ShardingSphereDriver",
          "spring.datasource.driver-class-name 必须是 ShardingSphereDriver")

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
    # 校验器本身崩溃也必须是显式失败：早期版本在这里抛了 AttributeError，
    # 结果失败清单没来得及打印，看上去像“卡在半路”而不是“校验不通过”。
    try:
        sys.exit(main())
    except SystemExit:
        raise
    except Exception:  # noqa: BLE001
        import traceback
        print("\n校验器自身异常（这不是分片配置的问题，是校验脚本的 bug）:",
              file=sys.stderr)
        traceback.print_exc()
        sys.exit(2)
