#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""从 local-conn.env 生成运行时配置（sharding.yaml / application.yml）。

为什么需要这一层
--------------------------------------------------------------------------
`deploy/conf/*.example` 是可提交的模板，里面写的是 MYSQL_HOST / MYSQL_USER
这样的占位符。但 ShardingSphere-JDBC 读的 sharding.yaml 里必须是**真实**
连接串与账号密码——那份文件绝不能提交。

手工维护两份（模板一份、真的一份）必然会漂移：改了分片数只改一份，
另一份静默地指向 8 张表。所以真配置由模板 + 凭据**生成**，
且生成时校验结果与模板的分片口径完全一致。

输出目录 deploy/conf/runtime/ 已被 .gitignore 忽略。

用法
    uv run python tools/gen_runtime_config.py            # 生成
    uv run python tools/gen_runtime_config.py --check    # 只校验不写
    uv run python tools/gen_runtime_config.py --print-path  # 打出 sharding.yaml 路径（供 -Dtm.it.config）
"""

from __future__ import annotations

import argparse
import importlib.util
import io
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
CONF = REPO / "deploy" / "conf"
RUNTIME = CONF / "runtime"
SHARDING_TMPL = CONF / "sharding.yaml.example"
APP_TMPL = CONF / "application-external.yml.example"


def load_cfg(explicit: str | None) -> dict[str, str]:
    spec = importlib.util.spec_from_file_location("cs", REPO / "tools" / "check_services.py")
    cs = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(cs)
    cfg, _ = cs.resolve_config(explicit)
    return cfg


def render_sharding(cfg: dict[str, str]) -> str:
    text = io.open(SHARDING_TMPL, encoding="utf-8").read()

    host = cfg.get("MYSQL_HOST", "").strip()
    port = cfg.get("MYSQL_PORT", "3306").strip() or "3306"
    user = cfg.get("MYSQL_USER", "").strip()
    pwd = cfg.get("MYSQL_PASSWORD", "")
    db = cfg.get("MYSQL_DATABASE", "tm_im").strip() or "tm_im"
    ssl = str(cfg.get("MYSQL_USE_SSL", "false")).lower() == "true"
    tz = cfg.get("MYSQL_SERVER_TIMEZONE", "Asia/Shanghai").strip() or "Asia/Shanghai"

    # 与 sharding.yaml.example 里的 jdbcUrl 参数保持一致；
    # allowPublicKeyRetrieval 是 caching_sha2_password 在非 TLS 下的必需项，
    # 8.4 的服务端默认就是这个插件。
    url = ("jdbc:mysql://{host}:{port}/{db}"
           "?useSSL={ssl}&serverTimezone={tz}&characterEncoding=utf8"
           "&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true").format(
        host=host, port=port, db=db, ssl=str(ssl).lower(), tz=tz)

    text = re.sub(r"jdbcUrl: .*", "jdbcUrl: " + url, text, count=1)
    text = re.sub(r"^(\s*)username: MYSQL_USER\s*$", r"\1username: " + user, text,
                  count=1, flags=re.M)
    text = re.sub(r"^(\s*)password: MYSQL_PASSWORD\s*$", r"\1password: " + pwd, text,
                  count=1, flags=re.M)

    # 生成物不再需要 TODO 提示
    text = re.sub(r"^\s*#\s*TODO[^\n]*\n", "", text, flags=re.M)
    header = ("# 本文件由 tools/gen_runtime_config.py 生成，含真实凭据，请勿提交\n"
              "# 重新生成： uv run python tools/gen_runtime_config.py\n")
    return header + text


def render_app(cfg: dict[str, str], sharding_path: Path) -> str:
    text = io.open(APP_TMPL, encoding="utf-8").read()

    if "MYSQL_HOST" in text:
        text = text.replace("MYSQL_HOST", cfg.get("MYSQL_HOST", "").strip())
        text = text.replace("MYSQL_PORT", cfg.get("MYSQL_PORT", "3306").strip() or "3306")
        text = text.replace("MYSQL_USER", cfg.get("MYSQL_USER", "").strip())
        text = text.replace("MYSQL_PASSWORD", cfg.get("MYSQL_PASSWORD", ""))

    # 模板用的是 jdbc:shardingsphere:absolutepath:${TM_SHARDING_CONF:默认值}
    # ——不是叫 sharding.url 的键。直接替掉内部的默认值，
    # 这样即使调用方忘了传 -D，也不会静默落到 /etc/tm/... 那个不存在的路径。
    text = re.sub(r"\$\{TM_SHARDING_CONF:[^}]*\}", sharding_path.as_posix(), text)

    header = ("# 本文件由 tools/gen_runtime_config.py 生成，含真实凭据，请勿提交\n")
    return header + text


def main() -> int:
    ap = argparse.ArgumentParser(description="生成运行时配置（含真实凭据）")
    ap.add_argument("--env", help="配置文件路径（默认 deploy/conf/local-conn.env）")
    ap.add_argument("--check", action="store_true", help="只比对不写盘")
    ap.add_argument("--print-path", action="store_true",
                    help="只打印生成的 sharding.yaml 绝对路径")
    args = ap.parse_args()

    sharding_out = RUNTIME / "sharding.yaml"
    app_out = RUNTIME / "application.yml"

    for p in (SHARDING_TMPL, APP_TMPL):
        if not p.exists():
            print("缺少模板: " + str(p))
            return 2

    # 分片口径必须与模板一致——生成不是"重新决定"，而只是填凭据
    tmpl_shard = io.open(SHARDING_TMPL, encoding="utf-8").read()
    shard_count = len(re.findall(r"ds_0\.message_", tmpl_shard))
    if not shard_count:
        print("模板里找不到分片定义，拒绝生成")
        return 2

    if args.print_path:
        print(str(sharding_out))
        return 0

    cfg = load_cfg(args.env)
    host = cfg.get("MYSQL_HOST", "").strip()
    if not host:
        print("local-conn.env 里 MYSQL_HOST 为空——先填好再生成")
        return 2

    sharding_text = render_sharding(cfg)
    app_text = render_app(cfg, sharding_out)

    # 生成物必须真的把占位符换掉了，否则会带着字面 MYSQL_USER 去连库，
    # 表现为"认证失败"，而排查方向会被引到密码上。
    leftovers = [w for w in ("MYSQL_HOST", "MYSQL_USER", "MYSQL_PASSWORD",
                             "MYSQL_PORT", "MYSQL_DATABASE")
                 if w in sharding_text]
    if leftovers:
        print("生成失败：sharding.yaml 里仍残留占位符 " + ", ".join(leftovers))
        return 1

    # 口令不能出现在输出里；包含它意味着没替换成功
    pwd = cfg.get("MYSQL_PASSWORD", "")
    if pwd and pwd not in sharding_text:
        print("生成失败：sharding.yaml 里没有写入口令，说明模板结构与预期不符")
        return 1

    if args.check:
        ok = True
        for out, want in ((sharding_out, sharding_text), (app_out, app_text)):
            if not out.exists():
                print("缺失: " + str(out.relative_to(REPO)))
                ok = False
            elif io.open(out, encoding="utf-8", newline="").read() != want.replace("\r\n", "\n"):
                print("过期: " + str(out.relative_to(REPO)))
                ok = False
        print("OK 运行时配置与当前 local-conn.env 一致" if ok
              else "运行时配置已过期 —— 重跑： python tools/gen_runtime_config.py")
        return 0 if ok else 1

    RUNTIME.mkdir(parents=True, exist_ok=True)
    # newline="" 避免 Windows 上把 \n 变成 \r\n：YAML 与 JDBC URL 都不需要 CRLF，
    # 而且 CRLF 会让 --check 的比对结果依赖平台。
    io.open(sharding_out, "w", encoding="utf-8", newline="").write(sharding_text)
    io.open(app_out, "w", encoding="utf-8", newline="").write(app_text)

    print("已生成（含真实凭据，已被 .gitignore 忽略）:")
    print("  " + str(sharding_out.relative_to(REPO)))
    print("  " + str(app_out.relative_to(REPO)))
    print("  目标库: {}:{} / {}  用户: {}".format(
        host, cfg.get("MYSQL_PORT"), cfg.get("MYSQL_DATABASE"), cfg.get("MYSQL_USER")))
    print()
    print("供集成测试使用：")
    print("  mvn -pl tm-storage -am test -Pit \"-Dtm.it.config={}\"".format(
        sharding_out.as_posix()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
