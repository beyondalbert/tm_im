# web/admin —— 管理后台 SPA

后台界面（DESIGN §4 的第 2 个端）。它消费的是 `tm-admin.jar` 的 `/v1/admin/**`，
契约见 [docs/integration/08-admin-api.md](../../docs/integration/08-admin-api.md)。

```
npm install          # 首次
npm run dev          # 开发服务器（:5174，/v1 代理到 127.0.0.1:8081）
npm run typecheck    # vue-tsc --noEmit（含 .vue 的模板与脚本）
npm test             # 单元测试（vitest，39 用例，不需要服务端）
npm run build        # 生产构建 → dist/（M10 把它打进 tm-admin.jar）
```

## 四个工作面

| 页面 | 路由 | 对应端点 |
|---|---|---|
| 参与者（人 + Agent） | `/admin/actors` | `GET`/`PATCH /v1/admin/actors**` |
| 内容审核 | `/admin/posts` | `GET`/`DELETE /v1/admin/posts**` |
| 审计日志 | `/admin/audit-logs` | `GET /v1/admin/audit-logs` |
| 后台账号（SUPER） | `/admin/accounts` | `/v1/admin/accounts**` |

**没有「Agent 单独一页」**：Agent 与人在库里是同一张表的同一批行，
后台也只有一个参与者列表（服务端没有 `/v1/admin/agents`）。理由见
08-admin-api.md §4 的「没有的端点」表。

## 为什么开发期不需要配 CORS

生产环境前端与 API 同源（前端在 `/admin/**`、API 在 `/v1/admin/**`，同一个 JAR）。
开发期用 Vite 的 `server.proxy` 把 `/v1` 转发到 `127.0.0.1:8081`，**复现**这个同源关系——
所以 `AdminWebConfiguration` 里没有 CORS 配置，而它也不该有：一旦加上，
生产环境就会多出一条无人审计的跨域信任。

## 真实服务端集成测试（M9 验收的可执行形式）

`tests/live/` 里跑的是**线上那段客户端代码**（`src/api/`），只换掉
`localStorage`（→ 内存）与 `baseUrl`（→ 真实地址）。它验证的是「前端的
字段名 / 参数名 / 信封假设与服务端真实行为一致」——这类漂移在构建、类型检查、
单元测试里都不会失败。

```bash
# 1) 起一个真实实例（需要外部 MySQL；首个账号由 bootstrap 配置创建一次）
#    注意用 JDK 17，且刻意**不设** TM_JWT_SECRET —— 后台不扫 core.identity
TM_SHARDING_CONF=<repo>/deploy/conf/runtime/sharding.yaml \
TM_ADMIN_HTTP_PORT=8081 \
TM_ADMIN_BOOTSTRAP_USERNAME=it_spa_boot_1 \
TM_ADMIN_BOOTSTRAP_PASSWORD=it-spa-boot-1 \
java -jar server/tm-admin/target/tm-admin.jar

# 2) 跑集成测试
cd web/admin
TM_ADMIN_URL=http://127.0.0.1:8081 \
TM_ADMIN_USERNAME=it_spa_boot_1 \
TM_ADMIN_PASSWORD=it-spa-boot-1 \
npm run test:live

# 3) 清掉测试账号（用户名以 it_ 开头，clean_it_leftovers 认这个前缀）
uv run --with pymysql python tools/clean_it_leftovers.py --apply
```

三个环境变量缺任何一个，这一层就整体跳过（`npm test` 不受影响）。

### 它第一次跑就抓到一个真实缺陷

**64 位 id 在 JS 里被悄悄改写了。**

```
服务端返回的原文   362810375490994176     （18 位，Snowflake）
JSON.parse 之后    362810375490994180     （最后两位变了）
```

后果不是「显示得难看」，而是**拿这个 id 去请求会查不到东西**：
`GET /v1/admin/actors/{actorId}` 回 40401、`PATCH .../accounts/{id}/status` 回 40400。
界面上的表现是「这个人不存在」——看起来像数据问题。

类型检查看不见（两边都是 `number`）、用小数字的单元测试也看不见。
修法见 `src/api/json.ts`：**解析前把整数形式的超长数字字面量加引号**，
于是它们在 TS 里是字符串（`BigId`），逐字节保真。同一条不变量被
`tests/unit/json.test.ts` 用真机上那个数字钉住。

它对界面的影响不止一处：审计筛选的两个 id 输入框因此从
`el-input-number` 换成了普通文本输入（数字控件的值就是 JS number，
一进控件就已经错了）。

## 三方一致性校验

```bash
uv run --no-progress python tools/verify_admin_spa.py -v
```

它把 Java 控制器与记录、`08-admin-api.md`、以及本目录的
`endpoints.ts` / `types.ts` / `admin.ts` / `errors.ts` 放在一起比：
端点集合、路径参数名、查询参数名、字段、权限、错误码。
并带 14 类注入漂移的自检（证明这个校验器不是摆设）。

它挂在 `tools/verify_all.py` 的 `admin-spa` 一项上；本目录的
类型检查 / 单元测试 / 生产构建挂在 `--web` 上：

```bash
uv run --with protobuf --with pyyaml --with sqlglot \
  python tools/verify_all.py --services --web
```
