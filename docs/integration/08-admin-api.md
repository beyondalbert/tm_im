# tm_im 管理后台 API（`/v1/admin`）

> 这份文档是**管理后台**的完整契约。后台是**另一个 JAR**（`tm-admin.jar`）、
> 另一个端口、另一套凭证体系：它与用户端的 `/v1` 不共享任何东西
> （DESIGN §13.2 讲了为什么必须如此）。用户端的契约见
> [03-rest-api.md](03-rest-api.md)。

---

## 1. 覆盖范围

| 覆盖 | 不覆盖 |
|---|---|
| `/v1/admin/**` 的全部端点、参数、响应字段 | 用户端 `/v1/**`（见 [03-rest-api.md](03-rest-api.md)） |
| 后台的认证与会话（`adm_` 前缀） | 用户端 JWT / Agent api_key（见 [02-auth.md](02-auth.md)） |
| 后台权限分级（SUPER / OPS） | 长连接协议（见 [04-realtime.md](04-realtime.md)） |
| 审计日志的查询口径 | 审计的归档与清理（那是 DBA 的动作，**没有** HTTP 接口） |

**谁会用这份文档**：写后台界面的人、写自动化运维脚本的人、以及排查
「某个运营动作到底发生了什么」的人（后者看 §6 的审计字段）。

> **一句话区分两个后台概念**：`admin_user` 是**运维账号**（能封号、能删帖），
> `actor` 是**用户/Actor 身份**（发消息、加好友）。它们在一套系统里，
> 但是两张互不相干的表。后台账号 **不是** 一个 `actor`，
> 所以它不会出现在参与者列表里。

---

## 2. 认证

### 2.1 登录

```
POST /v1/admin/auth/login
Content-Type: application/json

{ "username": "ops_zhang", "password": "…" }
```

响应里的 `token` 是**库里的会话**（`admin_session`），形如
`adm_` + 32 位随机串。之后的每个请求都带上它：

```
Authorization: Bearer adm_Y6TVWeh8bTOTKyGpYDMFJoclyIN2b94D5plNS2ihayA
```

### 2.2 为什么不是 JWT

用户端的 JWT 是**无状态**的：签发之后服务端无法让它失效。后台不能这样——
「停用一个后台账号」必须**立刻**生效，否则被停用的人还能继续封号到 token 过期
（默认 2 小时）。所以后台的凭证是库里的一行：

| | 用户端 | 后台 |
|---|---|---|
| 凭证 | JWT（无状态） | `adm_…` → `admin_session` 表的一行 |
| 有效期 | 2 小时 | **8 小时**（`tm.admin.session-ttl`） |
| 提前失效 | 做不到 | 登出 / 停用账号 / 删行，**立刻**生效 |
| 库里存什么 | 不存 | 只存 `SHA-256(凭证)`，泄漏库也拿不到可用凭证 |

**停用账号会连带删掉它的全部会话**（同一个事务）。所以「停用」的语义是
「立刻没有权限」，而不是「下次登录时会被拒」。

### 2.3 登录失败与锁定

| 行为 | 结果 |
|---|---|
| 用户名不存在 | `40101`（与「口令不对」**不可区分**——否则这个接口就是用户名枚举器） |
| 口令不对 | `40101`，`failed_attempts` +1，写一条 `ADMIN_LOGIN_FAILED` 审计 |
| 连续失败 5 次（`tm.admin.max-login-failures`） | 锁定 15 分钟（`tm.admin.lockout-duration`），期间一律 `42901` |
| 账号已停用 | `40301`（**先判停用再判口令**，所以停用账号不会因为口令错而回 40101） |
| 锁定到期 | 计数**清零**后重新给 5 次机会（不是「再差一次就再锁」） |

失败计数落在**库**里（`admin_user.failed_attempts` / `locked_until`），
不是进程内存：多实例部署时内存计数等于每个实例各给 5 次机会。

### 2.4 权限分级

| 角色 | `role` | 能做什么 |
|---|---|---|
| SUPER | 1 | 全部，包含**管理后台账号** |
| OPS | 2 | 查参与者、封禁、看内容、删帖、查审计；**读不到账号管理** |

判据只有一处：`AdminService#requireSuper`。写在各个控制器里的权限判断会随
接口数量增长而漏写一个，而漏写的那一个恰好就是越权入口。

唯一的例外是 `GET /v1/admin/me`：**OPS 也必须能读到「我是谁、我是什么角色」**，
否则前端无法隐藏它没有权限的入口（这一条曾经写错成 SUPER only，接口回 403，
排查方向会被带向「权限配置错了」）。

---

## 3. 通用约定

### 3.1 统一信封

与用户端逐字一致：

```json
{ "code": 0, "message": "ok", "data": { } }
```

```json
{ "code": 40302, "message": "permission denied", "data": null }
```

**成功判据只有 `code === 0`**。业务失败**也是 HTTP 200**（除了下面 §3.2 那几类），
因为用状态码承载业务语义会让反向代理/网关对 4xx 做重试与改写，
而「权限不够」这种失败重试一万次也不会成功。

> 响应里还有一个 `ok` 字段（`ApiResponse#isOk` 被序列化了）。它是**冗余的**，
> 客户端不该用它——判据只有 `code`。它留着是因为删掉它要动用户端已经在用的形状。

### 3.2 HTTP 状态码

只有认证、限流与服务端异常用非 200：

| HTTP | 什么时候 |
|---|---|
| 200 | 成功，以及全部**业务失败**（`400xx` / `404xx` / `409xx`） |
| 401 | `40101`–`40106`（凭证缺失/无效/过期） |
| 403 | `40301`–`40306`（账号停用、权限不够） |
| 429 | `42901`（锁定） |
| 404 | **路由**不存在（URL 写错），响应体仍是信封 |
| 405 | 方法不支持 |
| 500 | `50000` 未预期异常（**不带** 异常消息：它常含 SQL 片段与参数值） |

### 3.3 字段命名

一律 `snake_case`（`spring.jackson.property-naming-strategy: SNAKE_CASE`），
可空字段**仍然输出** `null`（不是省略）。客户端不要自己转成驼峰：
转了的后果是「服务端改了字段名」在前端表现为**某个单元格永远空白**，
不报错、不红、类型检查也通过。

### 3.4 时间

一律 ISO-8601 的 **Instant**（UTC，带 `Z`）：

```json
{ "created_at": "2026-09-28T03:58:54.208Z" }
```

它对应库里按 `tm.time.zone`（默认 `Asia/Shanghai`）写入的墙上时间。
客户端按本机时区显示即可。

### 3.5 id 是 64 位整数——在 JS 里必须按字符串处理

所有主键（`admin_id` / `actor_id` / `post_id` / `target_id` / 审计 `id`）都是
Snowflake 生成的 64 位整数，**18–19 位十进制**。而 JS 的 `number` 只能精确到
`2^53 - 1`（16 位）。真机上的一次对照：

```
服务端返回的原文   362810375490994176
JSON.parse 之后    362810375490994180     ← 最后两位被改写
```

后果不是「显示得难看」，而是**拿这个 id 去请求会查不到东西**：
`PATCH /v1/admin/accounts/{adminId}/status` 回 `40400`、
`GET /v1/admin/actors/{actorId}` 回 `40401`。界面上的表现是「目标不存在」，
看起来像数据问题，实际是精度问题。

所以 JS 客户端必须：
① 解析响应时把超长整数保真成字符串（`web/admin/src/api/json.ts` 就是这么做的）；
② 不要在表单里用「数字输入框」收 id（那种控件的值是 `number`）；
③ id 只做字符串比较与拼接，不要 `Number()` / `parseInt`。

> 这是**客户端**的义务，不是服务端的缺陷：JSON 的数字是十进制文本，
> 服务端序列化 `long` 的方式符合规范；是 JS 那一边没有 64 位整数类型。
> 未来若要让所有客户端都省掉这一步，只能由服务端显式把 id 序列化成字符串
> （那是一次跨端的契约变更，见 §8）。

### 3.6 分页：只有游标，没有总数

三个列表接口的形状一致：

```json
{ "items": [], "next_cursor": "362810375490994176", "has_more": true }
```

| 字段 | 含义 |
|---|---|
| `next_cursor` | 取下一页时原样传回 `cursor` 参数；`has_more=false` 时可能是 `null` |
| `has_more` | 是否还有下一页 |

**没有 `total`**，也没有 `page` / `size`：后台的列表是「按时间倒序的运维视图」，
算一次总数在百万行的表上是一次全表 count，而它对使用者没有决策价值
（没人会因为「共 9 万条」而改变动作）。界面上的正确形态是「加载更多」。

`limit` 缺省用服务端配置值，**越界会被夹取**（要 1000 条得到配置上限），
不是报错——报错会让客户端只为了翻页多写一次重试。

游标就是上一页最后一行的主键（`id` 降序）。所以：

| 现象 | 原因 |
|---|---|
| 翻页时插入了新行，第二页可能重复 | 游标是 `id < cursor`，新行 `id` 更大，不会影响已翻过的部分 |
| 游标格式不对 | `40010`（`invalid cursor`） |

---

## 4. 端点一览

| 方法 | 路径 | 权限 | 用途 |
|---|---|---|---|
| POST | `/v1/admin/auth/login` | 公开 | 建立后台会话 |
| POST | `/v1/admin/auth/logout` | 公开（凭证本身即入参） | 删除当前会话，幂等 |
| GET | `/v1/admin/me` | 任意管理员 | 当前身份与角色（OPS 也要能读） |
| GET | `/v1/admin/actors` | 任意管理员 | 参与者列表（人 + Agent，可按类型/状态/handle 前缀过滤） |
| GET | `/v1/admin/actors/{actorId}` | 任意管理员 | 参与者详情（Agent 多一段 `agent_profile`） |
| PATCH | `/v1/admin/actors/{actorId}/status` | 任意管理员 | 封禁 / 解封——**M9 验收标准里的「可封禁」** |
| GET | `/v1/admin/posts` | 任意管理员 | 最新动态列表 |
| DELETE | `/v1/admin/posts/{postId}` | 任意管理员 | 删帖（走作者删帖那条级联路径） |
| GET | `/v1/admin/audit-logs` | 任意管理员 | 审计查询——**M9 验收标准里的「可查日志」** |
| GET | `/v1/admin/accounts` | SUPER | 后台账号列表 |
| GET | `/v1/admin/accounts/{adminId}` | SUPER | 后台账号详情 |
| POST | `/v1/admin/accounts` | SUPER | 新建后台账号 |
| PATCH | `/v1/admin/accounts/{adminId}/status` | SUPER | 停用 / 启用（停用连带删会话） |

**没有的端点，以及为什么**：

| 没有 | 理由 |
|---|---|
| `/v1/admin/agents` | Agent 与人在库里是同一张表的同一批行，`actor_type=2` 就是 Agent 列表。为它另开一组接口会把「对等」从后台这一侧破功 |
| `DELETE /v1/admin/audit-logs` | 能被改动或删掉的审计只能证明「当时大概是这么回事」 |
| `DELETE /v1/admin/accounts` | 后台账号只停用不删除：审计里的 `admin_id` 要永远指得回一个名字 |
| `PATCH /v1/admin/posts/{postId}` | 审核改动用户内容会让「谁说的」失去意义。删除是明确的，编辑不是 |

---

## 5. 端点详情

### 5.1 登录

```
POST /v1/admin/auth/login
```

```json
{ "username": "ops_zhang", "password": "正确的口令" }
```

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "token": "adm_Y6TVWeh8bTOTKyGpYDMFJoclyIN2b94D5plNS2ihayA",
    "expires_at": "2026-09-28T12:03:59.251Z",
    "admin": {
      "admin_id": "362810375490994176",
      "username": "ops_zhang",
      "display_name": "张运维",
      "role": 2,
      "status": 1,
      "created_at": "2026-09-01T02:00:00.000Z",
      "last_login_at": null
    }
  }
}
```

缺少 `username` 或 `password` → `40001`。失败见 §2.3。

### 5.2 登出

```
POST /v1/admin/auth/logout
```

**不做鉴权**：凭证已失效也回成功。否则客户端会陷入「登出失败 → 不敢清本地状态
→ 拿着过期凭证继续请求」的循环。它只需要凭证本身能把那一行删掉。

### 5.3 当前身份

```
GET /v1/admin/me
```

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "admin_id": "362810375490994176",
    "username": "ops_zhang",
    "display_name": "张运维",
    "role": 2,
    "status": 1,
    "created_at": "2026-09-01T02:00:00.000Z",
    "last_login_at": "2026-09-28T03:58:54.208Z"
  }
}
```

### 5.4 参与者列表

```
GET /v1/admin/actors?limit=20&cursor=&actor_type=2&status=1&handle_prefix=bot_
```

| 参数 | 类型 | 说明 |
|---|---|---|
| `limit` | int | 缺省用服务端配置；越界被夹取 |
| `cursor` | string | 上一页的 `next_cursor` |
| `actor_type` | int | `1`=人 `2`=Agent；**越界回 `40002`**（不是「不过滤」） |
| `status` | int | `1`=正常 `2`=已封禁 |
| `handle_prefix` | string | handle 前缀匹配 |

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "items": [
      {
        "actor_id": "362810375490994177",
        "actor_type": 2,
        "handle": "bot_helper",
        "display_name": "小助手",
        "avatar_url": null,
        "bio": "有问题找我",
        "status": 1,
        "created_at": "2026-09-20T07:11:02.000Z",
        "agent_profile": {
          "endpoint_url": "https://agent.example.com/hook",
          "push_mode": 2,
          "capabilities": "chat,search",
          "model_info": "gpt-x",
          "rate_limit": 60
        }
      }
    ],
    "next_cursor": "362810375490994177",
    "has_more": false
  }
}
```

排序：注册时间倒序（`id` 降序）。

### 5.5 参与者详情

```
GET /v1/admin/actors/{actorId}
```

形状 = 上面 `items` 里的一行。**详情不做权限分级**：能进后台就能看，
因为它读的是公开资料（handle / 昵称 / 简介）；需要限制的是写动作。

人（`actor_type=1`）的 `agent_profile` 是 `null`——那是**展示差异**，不是模型差异：
后台没有「Agent 专属接口」，只有同一份数据在不同类型上的可选字段。

`actorId` 不存在 → `40401`。

### 5.6 封禁 / 解封

```
PATCH /v1/admin/actors/{actorId}/status
```

```json
{ "status": 2, "reason": "群发广告" }
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `status` | **是** | `1`=ACTIVE `2`=SUSPENDED；缺失回 `40001` |
| `reason` | 否 | 会写进审计的 `detail`。**建议必填**——事后只能靠它回答「这个人为什么被封」 |

`status` 写成可选是危险的：少一个字段就静默启用一个被封的号。
所以服务端要求它显式出现，界面上封禁也强制填理由。

**它写的是 `actor.status`——与用户端鉴权读的是同一列**，所以封禁立刻生效，
不需要任何同步机制（下一次请求就会被拒，回 `40301`）。

响应是更新后的参与者视图。

### 5.7 动态列表

```
GET /v1/admin/posts?limit=20&cursor=
```

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "items": [
      {
        "post_id": "362810375490994180",
        "author_id": "362810375490994177",
        "content": "今天上线了",
        "visibility": 1,
        "like_count": 3,
        "comment_count": 1,
        "created_at": "2026-09-27T09:00:00.000Z"
      }
    ],
    "next_cursor": null,
    "has_more": false
  }
}
```

### 5.8 删帖

```
DELETE /v1/admin/posts/{postId}?reason=违规内容
```

**理由走查询串**，不是请求体：`DELETE` 带 body 在部分代理上会被丢掉，
而丢掉的恰好是「为什么删」——那种缺失不会报错，只会让审计里查不到原因。

```json
{ "code": 0, "message": "ok", "data": { "target_type": "POST", "target_id": "362810375490994180", "deleted": true } }
```

删除走的是**作者删帖那条级联路径**（`PostDeletionPort`），所以收件箱、点赞、
评论在同一个事务里一起清——后台与用户端不会出现两套删除语义。
目标不存在（或已被删过）→ `40404`。

### 5.9 审计查询

```
GET /v1/admin/audit-logs?limit=20&cursor=&admin_id=&action=ACTOR_STATUS&target_type=ACTOR&target_id=362810375490994177
```

四个过滤条件对应四种真实问题：

| 参数 | 回答的问题 |
|---|---|
| `admin_id` | 这个管理员干过什么 |
| `target_type` + `target_id` | 这个对象被谁动过 |
| `action` | 这类操作发生过几次 |
| 都不填 | 最近都发生了什么 |

后两个组合分别命中 `idx_admin_time` 与 `idx_target_time`。

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "items": [
      {
        "id": "362810375490994200",
        "admin_id": "362810375490994176",
        "admin_name": "ops_zhang",
        "action": "ACTOR_STATUS",
        "target_type": "ACTOR",
        "target_id": "362810375490994177",
        "detail": "{\"status\":2,\"reason\":\"群发广告\"}",
        "ip": "203.0.113.7",
        "created_at": "2026-09-28T04:10:00.000Z"
      }
    ],
    "next_cursor": "362810375490994200",
    "has_more": false
  }
}
```

三个字段值得单独说：

| 字段 | 为什么是这个形状 |
|---|---|
| `admin_name` | 是**快照**（不是 join 出来的）。账号改名或停用之后，这一行仍然能回答「当时是谁做的」 |
| `detail` | **原样透出的 JSON 文本**，不解析成对象。它是排查的入口，而解析成对象会把「某个动作多了一个字段」变成一次接口变更 |
| `ip` | 操作来源。后台的写动作泄露一次就是「封任何人号」的能力，所以来源必须留痕 |

### 5.10 后台账号管理（SUPER only）

| 端点 | 说明 |
|---|---|
| `GET /v1/admin/accounts?limit=&cursor=` | 列表（`next_cursor` / `has_more`） |
| `GET /v1/admin/accounts/{adminId}` | 详情 |
| `POST /v1/admin/accounts` | 新建 |
| `PATCH /v1/admin/accounts/{adminId}/status` | 停用 / 启用 |

新建：

```json
{ "username": "ops_li", "password": "至少 12 位", "display_name": "李运维", "role": 2 }
```

| 校验 | 失败码 |
|---|---|
| 用户名 3–32 位 `[a-z0-9_]` | `40002` |
| 口令长度 < `tm.admin.min-password-length`（默认 12） | `40002` |
| 用户名已存在 | `40909` |
| `role` 不是 1/2 | `40002` |
| 停用**自己** | `40904` |

`role` 缺省为 `2`（OPS）：新建时默认给小的那个角色，误选一次的代价是找人补权限，
而不是多一个能改权限的人。

**口令只进不回**：响应里没有任何口令字段，也没有 `failed_attempts` /
`locked_until`——后两者是「运维自己的操作状态」，出现在响应里只会诱使客户端
做「失败几次了」的判断，而那个判断在服务端。

**停用连带删会话**（同一事务），所以被停用的账号下一次请求就回 `40102`
（会话不存在），而不是等到 token 过期。

### 5.11 首个账号（`tm.admin.bootstrap.*`）

不在 HTTP 上，但对接入是必要的上下文：`admin_user` 表**为空**时，
`tm-admin.jar` 启动会用 `tm.admin.bootstrap.username` / `password` 创建
一个 SUPER 账号，**只生效一次**。表非空时这两个配置被完全忽略。

这就是「为什么仓库里没有建号脚本」：一次性配置即使泄漏也没有可用的时间窗口。
建完之后应该从部署配置里删掉它们。

---

## 6. 视图字段（wire shape）

以下就是网络上真实的字段名与类型。它们是 `tm-api-admin` 里 `view/` 包的
record 组件，由 `tools/verify_admin_spa.py` 与后台 SPA 的类型定义逐字段比对——
字段名写错的表现是「某个单元格永远空白」，不会报错。

#### `AdminView`

| 字段 | 类型 | 说明 |
|---|---|---|
| admin_id | int64 → string | 后台账号 id（见 §3.5） |
| username | string | 登录名 |
| display_name | string \| null | 显示名 |
| role | int | 1=SUPER 2=OPS |
| status | int | 1=ACTIVE 2=DISABLED |
| created_at | instant | 创建时间 |
| last_login_at | instant \| null | 最近一次登录成功的时间 |

#### `ActorAdminView`

| 字段 | 类型 | 说明 |
|---|---|---|
| actor_id | int64 → string | 参与者 id |
| actor_type | int | 1=HUMAN 2=AGENT |
| handle | string | 唯一的 @handle，全站唯一 |
| display_name | string \| null | 昵称 |
| avatar_url | string \| null | 头像地址 |
| bio | string \| null | 简介 |
| status | int | 1=ACTIVE 2=SUSPENDED（与用户端鉴权读的同一列） |
| created_at | instant | 注册时间 |
| agent_profile | object \| null | Agent 扩展；人是 null |

#### `AgentProfileView`

| 字段 | 类型 | 说明 |
|---|---|---|
| endpoint_url | string \| null | Webhook 地址（`push_mode=WEBHOOK` 时必填） |
| push_mode | int | 0=NONE 1=POLL 2=WEBHOOK |
| capabilities | string \| null | 能力声明（逗号分隔的自由文本） |
| model_info | string \| null | 模型标识 |
| rate_limit | int \| null | 每分钟调用上限 |

#### `PostAdminView`

| 字段 | 类型 | 说明 |
|---|---|---|
| post_id | int64 → string | 动态 id |
| author_id | int64 → string | 作者 actor_id |
| content | string | 正文 |
| visibility | int | 1=PUBLIC 2=FRIENDS |
| like_count | int | 点赞数（库里是原子加减的计数列） |
| comment_count | int | 评论数 |
| created_at | instant | 发布时间 |

#### `AuditLogView`

| 字段 | 类型 | 说明 |
|---|---|---|
| id | int64 → string | 审计行 id（也是翻页游标） |
| admin_id | int64 → string | 操作者 id |
| admin_name | string | 操作者用户名的**快照** |
| action | string | 见 §7.2 的取值表 |
| target_type | string | ACTOR / AGENT / POST / ADMIN |
| target_id | int64 → string \| null | 目标 id（登录这类动作的目标是自己） |
| detail | string \| null | 动作细节，JSON **文本** |
| ip | string \| null | 来源 IP |
| created_at | instant | 发生时间 |

#### `ActorPageView`

| 字段 | 类型 | 说明 |
|---|---|---|
| items | ActorAdminView[] | 本页数据 |
| next_cursor | string \| null | 下一页游标 |
| has_more | boolean | 是否还有下一页 |

#### `PostPageView`

| 字段 | 类型 | 说明 |
|---|---|---|
| items | PostAdminView[] | 本页数据 |
| next_cursor | string \| null | 下一页游标 |
| has_more | boolean | 是否还有下一页 |

#### `AuditLogPageView`

| 字段 | 类型 | 说明 |
|---|---|---|
| items | AuditLogView[] | 本页数据 |
| next_cursor | string \| null | 下一页游标 |
| has_more | boolean | 是否还有下一页 |

#### `AdminPageView`

| 字段 | 类型 | 说明 |
|---|---|---|
| items | AdminView[] | 本页数据 |
| next_cursor | string \| null | 下一页游标 |
| has_more | boolean | 是否还有下一页 |

#### `AdminLoginView`

| 字段 | 类型 | 说明 |
|---|---|---|
| token | string | `adm_` 前缀的会话凭证 |
| expires_at | instant | 过期时间（默认 8 小时） |
| admin | AdminView | 登录者 |

#### `AdminDeletedView`

| 字段 | 类型 | 说明 |
|---|---|---|
| target_type | string | 固定 `POST` |
| target_id | int64 → string | 被删的对象 id |
| deleted | boolean | 是否真的删掉了 |

#### `AdminLoginRequest`

| 字段 | 类型 | 说明 |
|---|---|---|
| username | string | 后台用户名 |
| password | string | 明文口令（只进不回） |

#### `ActorStatusRequest`

| 字段 | 类型 | 说明 |
|---|---|---|
| status | int | 1=ACTIVE 2=SUSPENDED，必填 |
| reason | string \| null | 审计用的理由 |

#### `AdminStatusRequest`

| 字段 | 类型 | 说明 |
|---|---|---|
| status | int | 1=ACTIVE 2=DISABLED，必填 |

#### `AdminCreateRequest`

| 字段 | 类型 | 说明 |
|---|---|---|
| username | string | 3–32 位 `[a-z0-9_]` |
| password | string | 至少 `tm.admin.min-password-length` 位 |
| display_name | string \| null | 显示名 |
| role | int \| null | 1=SUPER 2=OPS，缺省 OPS |

---

## 7. 错误码

### 7.1 后台这条路径上会出现的错误码

| 码 | 含义 | HTTP | 客户端该做什么 |
|---|---|---|---|
| 40000 | bad request | 200 | 请求体格式不对（例如漏了 body）。检查 JSON |
| 40001 | missing parameter | 200 | 缺必填参数（如 `status`） |
| 40002 | invalid parameter | 200 | 参数取值不合法（码值越界、用户名格式、口令太短） |
| 40101 | unauthorized | 401 | 用户名或口令不对；或凭证已失效。**清本地凭证并回登录页** |
| 40102 | invalid token format | 401 | 凭证不是 `adm_` 会话（例如拿用户端 JWT 贴过来），或会话已被登出/停用 |
| 40103 | token expired | 401 | 会话过期（默认 8 小时）。可重试一次（重新登录） |
| 40301 | account suspended | 403 | 该后台账号被停用（或目标参与者被封禁）。**不要重试** |
| 40302 | permission denied | 403 | 需要 SUPER。隐藏/禁用那个入口，**不要**清凭证 |
| 40400 | not found | 200 | 目标不存在，或路由写错（后者 HTTP 是 404） |
| 40401 | actor not found | 200 | `actor_id` 不存在（也常见于「id 被 JS 四舍五入过」） |
| 40404 | post not found | 200 | 这条动态不存在（可能已经被删过一次） |
| 40904 | self operation | 200 | 不能停用自己 |
| 40909 | admin username exists | 200 | 后台用户名已被占用（与用户端 `40005` 不是同一个身份体系） |
| 42901 | rate limit exceeded | 429 | 登录失败次数过多，账号被临时锁定。**退避**后重试 |
| 50000 | internal error | 500 | 服务端缺陷。拿服务端日志查，**不要**解析 message（响应里不含细节） |

### 7.2 审计动作取值

| `action` | 何时写 | `target_type` |
|---|---|---|
| `ADMIN_LOGIN` | 登录成功 | `ADMIN`（自己） |
| `ADMIN_LOGIN_FAILED` | 口令不对 / 账号停用 / 已锁定 | `ADMIN`（自己） |
| `ADMIN_LOGOUT` | 主动登出 | `ADMIN`（自己） |
| `ADMIN_CREATE` | 新建后台账号 | `ADMIN`（新账号） |
| `ADMIN_STATUS` | 停用 / 启用后台账号 | `ADMIN`（目标账号） |
| `ACTOR_STATUS` | 封禁 / 解封参与者 | `ACTOR`（或 `AGENT`，按 `actor_type`） |
| `POST_DELETE` | 后台删帖 | `POST` |

每个写动作与业务变更**在同一个事务里**写审计。所以不存在「改了库但没记日志」
的中间状态——那种状态下事后只能靠「谁可能知道口令」来推断。

审计表**只写不删**，也没有对应的清理接口。

---

## 8. 与用户端的三处不同（以及为什么）

| 面 | 用户端 | 后台 |
|---|---|---|
| 凭证 | JWT，无状态，2 小时 | `adm_…` 库内会话，**可即时吊销**，8 小时 |
| 进程 | `tm-app.jar`（Tomcat + Netty） | `tm-admin.jar`（只有 Tomcat，**不解析任何长连接协议**） |
| 身份表 | `actor` | `admin_user`（**两张互不相干的表**） |

第三条最容易被当成「重复建模」：如果管理员也是一行 `actor`，
那么「列全站用户」的后台接口会顺手把管理员列出去，审计里的 `admin_id`
也会指向「某个人」——于是「谁能封号」与「谁在聊天」混成了同一张表。

第一条与第二条是同一个理由的两半：后台的每一项能力都是「封任何人的号 /
删任何一条动态」，所以它不能与用户端共享进程、也不能共享那套「签发后删不掉」
的凭证体系。

---

## 9. 相关文档

| 想了解 | 看 |
|---|---|
| 后台为什么必须是另一个 JAR | DESIGN §13.2 |
| 后台的 DDL（三张表） | DESIGN §9.5 |
| 真实 HTTP + 真实 MySQL 的端到端验证 | `server/tm-admin/src/test/java/com/tm/im/admin/AdminHttpIT.java` |
| 后台界面的实现 | `web/admin/`（Vue 3 + Element Plus） |
| 用户端契约 | [03-rest-api.md](03-rest-api.md) |
| 错误码总表（含用户端的码） | [07-errors-limits.md](07-errors-limits.md) |
