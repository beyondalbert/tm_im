#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""从 deploy/sql/01-schema.sql 反向生成 Java 领域实体与枚举。

为什么是「反向生成」而不是「手工写实体」
--------------------------------------------------------------------------
实体的字段必须与 DDL 严丝合缝：少一列 → 查询结果静默丢数据；
多一列 → 插入时报 Unknown column；类型对不上 → 时间被截断或数字溢出。
25 张表、上百个列，靠人工同步迟早会漂移，而漂移只在运行期才暴露。

本仓库的 DDL 本身就是 gen_schema.py 生成的（机器产物），
所以让实体也从同一份 DDL 生成，等于把「DDL ↔ 实体」的一致性变成构造性质，
而不是需要人去维护的约定。

刻意保留的显式声明
--------------------------------------------------------------------------
ENUM_MAP 是手写的，不是从注释里猜的。
DDL 注释里的 "1=HUMAN 2=AGENT" 只是给人看的文本，格式随时可能被润色；
把它当机器可读的契约，会在某次文案调整后静默产出错误的枚举。
因此这里显式列出码值与常量名，再反向校验 DDL 注释里确实出现了这些码值——
两边对不上直接报错，而不是猜。

用法
--------------------------------------------------------------------------
    python tools/gen_entities.py              # 生成
    python tools/gen_entities.py --check      # 只校验不写盘（verify_all 用）
    python tools/gen_entities.py --out        # 打印到标准输出
"""
from __future__ import annotations

import argparse
import hashlib
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SCHEMA = REPO / "deploy" / "sql" / "01-schema.sql"
DOMAIN_JAVA = REPO / "server" / "tm-domain" / "src" / "main" / "java" / "com" / "tm" / "im" / "domain"
ENTITY_DIR = DOMAIN_JAVA / "entity"
ENUM_DIR = DOMAIN_JAVA / "enums"
MAPPER_DIR = (REPO / "server" / "tm-storage" / "src" / "main" / "java"
              / "com" / "tm" / "im" / "storage" / "mapper")

SHARDED_LOGICAL = "message"
SHARDED_PHYSICAL_RE = re.compile(r"^message_\d+$")

# (表名, 列名) -> (枚举类名, [(码值, 常量名), ...])；分片表用逻辑表名
ENUM_MAP: dict[tuple[str, str], tuple[str, list[tuple[int, str]]]] = {
    ("actor", "actor_type"): ("ActorType", [(1, "HUMAN"), (2, "AGENT")]),
    ("actor", "status"): ("ActorStatus", [(1, "ACTIVE"), (2, "SUSPENDED")]),
    ("actor_secret", "secret_type"): ("SecretType", [(1, "PASSWORD_HASH"), (2, "API_KEY_HASH"),
                                             (3, "WEBHOOK_SECRET")]),
    ("agent_profile", "push_mode"): ("PushMode", [(1, "WEBHOOK"), (2, "WS"), (3, "PULL")]),
    ("conversation", "conv_type"): ("ConvType", [(1, "DIRECT"), (2, "GROUP")]),
    ("conversation_member", "role"): ("MemberRole", [(1, "OWNER"), (2, "ADMIN"), (3, "MEMBER")]),
    ("message", "msg_type"): ("MessageType", [(1, "TEXT"), (2, "IMAGE"), (3, "SYSTEM")]),
    ("friendship", "status"): ("FriendshipStatus", [(1, "PENDING"), (2, "ACCEPTED"), (3, "BLOCKED")]),
    ("post", "visibility"): ("Visibility", [(1, "PUBLIC"), (2, "FRIENDS_ONLY")]),
}

TYPE_MAP = {
    "BIGINT": "Long",
    "INT": "Integer",
    "INTEGER": "Integer",
    "SMALLINT": "Integer",
    "TINYINT": "Integer",
    "VARCHAR": "String",
    "CHAR": "String",
    "TEXT": "String",
    "LONGTEXT": "String",
    "JSON": "String",
    "DATETIME": "LocalDateTime",
    "TIMESTAMP": "LocalDateTime",
    "DATE": "LocalDate",
    "DECIMAL": "BigDecimal",
    "DOUBLE": "Double",
    "FLOAT": "Float",
}

JAVA_KEYWORDS = {
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
    "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
    "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
    "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
    "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
    "volatile", "while", "true", "false", "null", "var", "record", "yield", "sealed",
}

TABLE_RE = re.compile(
    r"CREATE TABLE IF NOT EXISTS `(?P<name>\w+)`\s*\((?P<body>.*?)\n\)\s*ENGINE=(?P<engine>\w+)"
    r"(?P<tail>.*?);",
    re.DOTALL,
)
COLUMN_RE = re.compile(
    r"^\s*`(?P<col>\w+)`\s+(?P<type>[A-Za-z]+(?:\s*\(\s*\d+(?:\s*,\s*\d+)?\s*\))?)"
    r"\s*(?P<notnull>NOT\s+NULL)?(?P<rest>.*?),?\s*$",
    re.IGNORECASE,
)
COMMENT_RE = re.compile(r"COMMENT\s+'((?:[^']|'')*)'", re.IGNORECASE)
PK_RE = re.compile(r"^\s*PRIMARY\s+KEY\s*\((?P<cols>[^)]*)\)", re.IGNORECASE)
TABLE_COMMENT_RE = re.compile(r"COMMENT\s*=\s*'((?:[^']|'')*)'", re.IGNORECASE)

SENSITIVE_FIELD_HINTS = ("secret", "hash", "token", "password")


class GenError(Exception):
    pass


def snake_to_pascal(name: str) -> str:
    return "".join(part[:1].upper() + part[1:] for part in name.split("_") if part)


def snake_to_camel(name: str) -> str:
    parts = [p for p in name.split("_") if p]
    if not parts:
        raise GenError(f"无法从 {name!r} 推导驼峰名")
    return parts[0] + "".join(p[:1].upper() + p[1:] for p in parts[1:])


def java_type(sql_type: str) -> str:
    base = re.match(r"[A-Za-z]+", sql_type).group(0).upper()
    if base == "TINYINT" and re.search(r"\(\s*1\s*\)", sql_type):
        return "Boolean"
    if base not in TYPE_MAP:
        raise GenError(f"未映射的 SQL 类型 {sql_type!r}；请在 TYPE_MAP 中补充后再生成")
    return TYPE_MAP[base]


class Column:
    def __init__(self, name: str, sql_type: str, nullable: bool, comment: str, enum_name):
        self.name = name
        self.sql_type = sql_type
        self.nullable = nullable
        self.comment = comment.strip()
        self.enum_name = enum_name
        self.field = snake_to_camel(name)
        if self.field in JAVA_KEYWORDS:
            raise GenError(f"列 {name!r} 映射出的字段名 {self.field!r} 是 Java 关键字")

    @property
    def type(self) -> str:
        return self.enum_name if self.enum_name else java_type(self.sql_type)

    @property
    def is_sensitive(self) -> bool:
        return any(k in self.field.lower() for k in SENSITIVE_FIELD_HINTS)


class Table:
    def __init__(self, name, logical, comment, columns, pk):
        self.name = name
        self.logical = logical
        self.comment = comment
        self.columns = columns
        self.pk = pk

    @property
    def class_name(self) -> str:
        return snake_to_pascal(self.logical)

    @property
    def composite_pk(self) -> bool:
        return len(self.pk) > 1

    @property
    def single_pk(self):
        return self.pk[0] if len(self.pk) == 1 else None


def parse_schema():
    if not SCHEMA.exists():
        raise GenError(f"找不到 {SCHEMA}；先运行 python tools/gen_schema.py")
    text = SCHEMA.read_text(encoding="utf-8")

    physical: dict[str, Table] = {}
    enums_needed: dict[str, list[tuple[int, str]]] = {}

    for m in TABLE_RE.finditer(text):
        pname = m.group("name")
        body = m.group("body")
        tail = m.group("tail") or ""
        logical = SHARDED_LOGICAL if SHARDED_PHYSICAL_RE.match(pname) else pname

        tcm = TABLE_COMMENT_RE.search(tail)
        table_comment = tcm.group(1) if tcm else ""

        columns: list[Column] = []
        pk: list[str] = []
        for raw_line in body.split("\n"):
            stripped = raw_line.strip()
            if not stripped or stripped.startswith("--"):
                continue
            pkm = PK_RE.match(stripped)
            if pkm:
                pk = [c.strip().strip("`") for c in pkm.group("cols").split(",")]
                continue
            if not stripped.startswith("`"):
                continue  # UNIQUE KEY / KEY / INDEX
            cm = COLUMN_RE.match(stripped)
            if not cm:
                raise GenError(f"{pname}: 无法解析列定义行：{stripped!r}")
            col_name = cm.group("col")
            sql_type = re.sub(r"\s+", "", cm.group("type"))
            nullable = cm.group("notnull") is None
            com = COMMENT_RE.search(stripped)
            comment = com.group(1) if com else ""

            enum_name = None
            key = (logical, col_name)
            if key in ENUM_MAP:
                enum_name, values = ENUM_MAP[key]
                for code, _ in values:
                    if f"{code}=" not in comment:
                        raise GenError(
                            f"{pname}.{col_name}: 枚举 {enum_name} 声明了码值 {code}，"
                            f"但列注释 {comment!r} 中找不到 '{code}='；注释与枚举映射已不同步"
                        )
                existing = enums_needed.get(enum_name)
                if existing is not None and existing != list(values):
                    raise GenError(
                        f"{pname}.{col_name}: 枚举 {enum_name} 的值与先前同名列不一致："
                        f"{existing} vs {list(values)}"
                    )
                enums_needed[enum_name] = list(values)

            columns.append(Column(col_name, sql_type, nullable, comment, enum_name))

        if not columns:
            raise GenError(f"{pname}: 未解析到任何列")
        physical[pname] = Table(pname, logical, table_comment, columns, pk)

    if not physical:
        raise GenError("未从 DDL 中解析到任何表")

    shard_tables = sorted((t for t in physical.values() if SHARDED_PHYSICAL_RE.match(t.name)),
                          key=lambda t: int(t.name.split("_")[1]))
    if not shard_tables:
        raise GenError("未找到任何 message_N 分片表")

    def shape(t: Table):
        return [(c.name, c.sql_type, c.nullable) for c in t.columns], tuple(t.pk)

    ref = shape(shard_tables[0])
    for t in shard_tables[1:]:
        if shape(t) != ref:
            raise GenError(
                f"分片表 {t.name} 的结构与 {shard_tables[0].name} 不一致；"
                "它们必须完全相同，否则同一逻辑表在不同分片上行为不同"
            )

    merged = Table(
        SHARDED_LOGICAL, SHARDED_LOGICAL,
        f"消息分片表（逻辑表 message → 物理表 message_0..{len(shard_tables) - 1}）",
        shard_tables[0].columns, shard_tables[0].pk,
    )

    result: list[Table] = []
    emitted = False
    for t in physical.values():
        if SHARDED_PHYSICAL_RE.match(t.name):
            if not emitted:
                result.append(merged)
                emitted = True
            continue
        result.append(t)

    return result, sorted(enums_needed.items())


_ENUM_TEMPLATE = """package com.tm.im.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * @@ORIGIN@@
 *
 * <p>由 <code>tools/gen_entities.py</code> 生成，请勿手工编辑。
 * 改取值请改生成器中的 ENUM_MAP 后重跑。
 */
public enum @@NAME@@ implements CodedEnum {

@@CONSTS@@;

    /** 落库值。{@code @EnumValue} 告诉 MyBatis-Plus 用这个字段而不是 name()/ordinal()。 */
    @EnumValue
    private final int code;

    @@NAME@@(int code) {
        this.code = code;
    }

    @Override
    public int code() {
        return code;
    }

    /** 反查；未知码值返回 null，用于解析外部输入。 */
    public static @@NAME@@ of(int code) {
        for (@@NAME@@ v : values()) {
            if (v.code == code) {
                return v;
            }
        }
        return null;
    }

    /**
     * 反查；未知码值直接抛异常。用于<b>读数据库</b>。
     *
     * <p>库里出现 @@LEGAL@@ 之外的值，说明数据已损坏或有人绕过应用直接改库。
     * 此时静默返回 null 会把错误推迟到更远的地方才爆发，还不如就地炸掉。
     */
    public static @@NAME@@ require(int code) {
        @@NAME@@ v = of(code);
        if (v == null) {
            throw new IllegalArgumentException(
                    "非法的 @@NAME@@ 码值: " + code + "（合法值: @@LEGAL@@）");
        }
        return v;
    }
}
"""


def render_enum(name: str, values, origin: str) -> str:
    """渲染枚举源码。

    刻章不用 f-string：模板里全是 Javadoc 的 {{@code ...}} 写法，
    花括号必须逐个转义，漏一个就整个模板报废。用占位符 + replace 更稳。
    """
    consts = ",\n".join(f"    {c}({v})" for v, c in values)
    legal = ", ".join(str(v) for v, _ in values)
    return (_ENUM_TEMPLATE
            .replace("@@NAME@@", name)
            .replace("@@ORIGIN@@", origin)
            .replace("@@CONSTS@@", consts)
            .replace("@@LEGAL@@", legal))


CODED_ENUM_SOURCE = """package com.tm.im.domain.enums;

/**
 * 带显式落库码值的枚举。
 *
 * <p>为什么不用 Java 的 {@code name()} 或 {@code ordinal()} 直接落库：
 * <ul>
 *   <li>{@code name()}：常量一重命名（HUMAN → PERSON），历史数据立刻变成非法值；</li>
 *   <li>{@code ordinal()}：在枚举中间插入一个新常量，会让其后所有常量的含义整体错位。
 *       这是最难排查的一类数据损坏——库里存的是数字，肉眼完全看不出错。</li>
 * </ul>
 * 显式码值把「落库表示」与「Java 命名」解耦，两者可以各自演进。
 *
 * <p>全部实现类由 {@code EnumCodeTest} 自动扫描并校验码值往返。
 */
public interface CodedEnum {

    int code();
}
"""


def render_entity(t: Table) -> str:
    mp_imports = [
        "com.baomidou.mybatisplus.annotation.IdType",
        "com.baomidou.mybatisplus.annotation.TableId",
        "com.baomidou.mybatisplus.annotation.TableName",
    ]
    used = {c.type for c in t.columns}
    java_imports = []
    if "LocalDateTime" in used:
        java_imports.append("java.time.LocalDateTime")
    if "LocalDate" in used:
        java_imports.append("java.time.LocalDate")
    if "BigDecimal" in used:
        java_imports.append("java.math.BigDecimal")
    enum_imports = sorted({f"com.tm.im.domain.enums.{c.enum_name}" for c in t.columns if c.enum_name})

    L: list[str] = []
    L.append("package com.tm.im.domain.entity;")
    L.append("")
    L.extend(f"import {i};" for i in mp_imports)
    L.extend(f"import {i};" for i in enum_imports)
    L.append("")
    L.extend(f"import {i};" for i in java_imports)
    L.append("")
    L.append("/**")
    L.append(f" * {t.comment or t.logical}")
    L.append(" *")
    L.append(" * <p>由 <code>tools/gen_entities.py</code> 从 <code>deploy/sql/01-schema.sql</code> 生成，")
    L.append(" * 请勿手工编辑——改结构请改 DDL 生成器后重跑。")
    if t.composite_pk:
        L.append(" *")
        L.append(f" * <p><b>本表是联合主键（{', '.join(t.pk)}），故刻意不标注 {{@code @TableId}}。</b>")
        L.append(" * MyBatis-Plus 不支持联合主键；若把其中一列强标为 {@code @TableId}，")
        L.append(" * 它生成的 {@code selectById} 会退化成 {@code WHERE "
                + t.pk[0] + " = ?}，")
        L.append(" * 在分片表上返回多行中的任意一行且不报错。这类静默错误比一条启动告警危险得多，")
        L.append(" * 因此选择让它告警。本表必须用显式条件查询。")
    L.append(" */")
    L.append(f'@TableName("{t.logical}")')
    L.append(f"public class {t.class_name} {{")
    L.append("")

    for c in t.columns:
        if c.comment:
            # 注释里的 \ 会被当成转义符；前导 @ 会被 javadoc 当成 block tag
            text = c.comment.replace("\\", "\\\\").replace("*/", "* /")
            if text.startswith("@"):
                text = "&#64;" + text[1:]
            L.append(f"    /** {text} */")
        if t.composite_pk:
            # 只标注真正的主键列，非主键列不该背上这句注解
            if c.name in t.pk:
                L.append("    // 联合主键之一，见类注释：故意不标 @TableId")
        elif c.name == t.single_pk:
            L.append(f'    @TableId(value = "{c.name}", type = IdType.INPUT)')
        L.append(f"    private {c.type} {c.field};")
        L.append("")

    for c in t.columns:
        cap = c.field[:1].upper() + c.field[1:]
        L.append(f"    public {c.type} get{cap}() {{")
        L.append(f"        return {c.field};")
        L.append("    }")
        L.append("")
        L.append(f"    public void set{cap}({c.type} {c.field}) {{")
        L.append(f"        this.{c.field} = {c.field};")
        L.append("    }")
        L.append("")

    L.append("    /**")
    L.append("     * 凭据类字段（secret / hash / token / password）一律脱敏。")
    L.append("     * 实体被随手打进日志是极常见的事，脱敏只有放在这里才拦得住。")
    L.append("     */")
    L.append("    @Override")
    L.append("    public String toString() {")
    L.append(f'        return "{t.class_name}{{" +')
    for i, c in enumerate(t.columns):
        text = f'"{c.field}=***"' if c.is_sensitive else f'"{c.field}=" + {c.field}'
        tail = ' + ", " +' if i < len(t.columns) - 1 else ""
        L.append(f"                {text}{tail}")
    L.append("                + '}';")
    L.append("    }")
    L.append("}")
    return "\n".join(L) + "\n"


def render_mapper(t: Table) -> str:
    """MyBatis-Plus Mapper 接口。

    内容全是样板，因此也交给生成器——手写 10 个只差一个类名的接口，
    唯一的作用就是提供 10 次写错类名的机会。
    """
    extra = []
    if t.composite_pk:
        extra = [
            " *",
            " * <p><b>{@code selectById} / {@code deleteById} 在本表上不可用</b>（联合主键），",
            " * 原因见其实体类注释。请改用 {@code selectList(Wrapper)} 配合显式条件。",
        ]
    return "\n".join([
        "package com.tm.im.storage.mapper;",
        "",
        "import com.baomidou.mybatisplus.core.mapper.BaseMapper;",
        "import com.tm.im.domain.entity." + t.class_name + ";",
        "import org.apache.ibatis.annotations.Mapper;",
        "",
        "/**",
        " * " + (t.comment or t.logical) + " 的基础 CRUD。",
        " *",
        " * <p>由 <code>tools/gen_entities.py</code> 生成，请勿手工编辑。",
        " * 复杂查询写在仓储实现里（用 Wrapper），不要往这里加自定义 SQL——",
        " * 分片表上任何不带 conv_id 的语句都会退化成 16 张表的广播查询。",
        *extra,
        " */",
        "@Mapper",
        "public interface " + t.class_name + "Mapper extends BaseMapper<" + t.class_name + "> {",
        "}",
        "",
    ])


def collect_outputs() -> dict[Path, str]:
    tables, enums = parse_schema()
    fingerprint = hashlib.sha256(SCHEMA.read_bytes()).hexdigest()[:16]
    banner = f"// 源: deploy/sql/01-schema.sql  sha256[:16]={fingerprint}\n"

    outputs: dict[Path, str] = {ENUM_DIR / "CodedEnum.java": CODED_ENUM_SOURCE}
    for name, values in enums:
        origin = next(f"{t.logical}.{c.name}" for t in tables for c in t.columns if c.enum_name == name)
        outputs[ENUM_DIR / f"{name}.java"] = banner + render_enum(name, values, f"落库码值来源：{origin}")
    for t in tables:
        outputs[ENTITY_DIR / f"{t.class_name}.java"] = banner + render_entity(t)
        outputs[MAPPER_DIR / f"{t.class_name}Mapper.java"] = banner + render_mapper(t)
    return outputs


def main() -> int:
    ap = argparse.ArgumentParser(description="从 DDL 生成 Java 实体与枚举")
    ap.add_argument("--check", action="store_true", help="只比对不写盘；有差异则退出码 1")
    ap.add_argument("--out", action="store_true", help="打印到标准输出")
    args = ap.parse_args()

    try:
        outputs = collect_outputs()
    except GenError as e:
        print(f"生成失败: {e}", file=sys.stderr)
        return 2

    if args.out:
        for path, content in sorted(outputs.items()):
            print(f"===== {path.relative_to(REPO)} =====")
            print(content)
        return 0

    known = set(outputs)
    orphan_files = [p for d in (ENTITY_DIR, ENUM_DIR, MAPPER_DIR) if d.exists()
                    for p in d.glob("*.java") if p not in known]

    if args.check:
        missing = [p for p in outputs if not p.exists()]
        stale = [p for p in outputs if p.exists() and p.read_text(encoding="utf-8") != outputs[p]]
        if missing or stale or orphan_files:
            print("实体与 DDL 不一致：")
            for p in missing:
                print(f"  缺失: {p.relative_to(REPO)}")
            for p in stale:
                print(f"  过时: {p.relative_to(REPO)}")
            for p in orphan_files:
                print(f"  残留: {p.relative_to(REPO)}（生成器已不产出，请删除）")
            print("\n修复：python tools/gen_entities.py")
            return 1
        print(f"OK 实体与 DDL 一致（{len(outputs)} 个文件）")
        return 0

    written = 0
    for path, content in outputs.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        if not path.exists() or path.read_text(encoding="utf-8") != content:
            path.write_text(content, encoding="utf-8", newline="\n")
            written += 1
    for p in orphan_files:
        p.unlink()

    print(f"生成 {len(outputs)} 个文件（写入 {written}，删除残留 {len(orphan_files)}）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
