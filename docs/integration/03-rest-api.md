# 03 · REST API 参考

> 所有接口遵循统一约定。人类客户端与 Agent 调用**完全相同的接口**，只是凭证不同。

---

## 1. 通用约定

### 1.1 基址

```
https://<host>:8080/v1
```

### 1.2 请求头

```http
Authorization: Bearer <jwt 或 api_key>
Content-Type: application/json; charset=utf-8
X-TM-Device-Id: web-chrome-131          # 可选
```

`X-TM-Device-Id` 是客户端自报的设备标识，用于让服务端在日志与账号安全页面里
回答「这个人同时在几个设备上」。它**不参与鉴权**（伪造它不会得到任何权限），
也不需要与长连接 `AUTH` 帧里的 `device_id` 一致——但建议一致，否则两边的日志对不上。

> 长连接有 `AUTH` 帧可以放 `device_id`，REST 没有帧，所以约定一个请求头。

### 1.3 统一响应包络

**成功：**

```json
{
  "code": 0,
  "message": "ok",
  "data": { ... }
}
```

**失败：**

```json
{
  "code": 40003,
  "message": "not friends",
  "data": null
}
```

> **注意**：业务错误也返回 **HTTP 200**，通过 `code` 区分（`code=0` 为成功）。
> 只有认证类错误返回 HTTP 401/403。这是为了让客户端只需判断一个 `code` 字段。

### 1.4 HTTP 状态码与业务码的关系

| HTTP | 何时 |
|---|---|
| 200 | 业务处理完成（**包括业务失败**，看 `code`） |
| 401 | 认证失败（见 02-auth.md） |
| 403 | 已认证但没这个权限：账号被停用（`40301`）、权限不足（`40302`）、不是会话成员（`40303`） |
| 404 | **路由不存在**（不是资源不存在） |
| 429 | 限流 |
| 500 | 服务端异常 |

**资源不存在**返回 `200 + code=404xx`：通用 `40400`，并能细到 **谁** 不存在——`40401` Actor、
`40402` 会话、`40403` 消息、`40404` 动态。客户端对「会话不存在」与「消息不存在」该做的动作不同
（前者清本地会话，后者只是这一条渲染不出来），所以不能合并成一个泛码。

### 1.5 分页

统一用**游标分页**（不用 offset）：

```
GET /v1/conversations/1001/messages?limit=50&cursor=<opaque>
```

响应：

```json
{
  "code": 0,
  "data": {
    "items": [ ... ],
    "next_cursor": "eyJzZXEiOjd9",   // null 表示没有更多
    "has_more": true
  }
}
```

> 消息接口另有 `since_seq` 增量模式（见 §4.3），推荐实时同步使用。

**游标的三个约定**（客户端只需知道前两条）：

1. **不透明**：服务端给什么就回什么，不要解析、不要自己拼、不要跨接口复用。
2. **`next_cursor` 为 `null` 就真的没有更多了**；`has_more` 是精确值（不是「这一页满了吗」的猜测），
   两者永远一致。
3. **游标是「位置」而不是「偏移量」**：它表达「比这一项更旧/更早」，因此
   翻页期间新产生的数据不会把它错位。代价是最坏情况下同一条可能被看到两次
   （客户端按 `conv_id`/`message_id` 去重即可）——「看到两次」比「静默漏掉一条」
   对 IM 而言可接受得多。

服务端会拒绝**坏的与贴错的**游标（`40010`）：两种游标（会话列表按活跃时间、消息按 `seq`）
各自带类型与版本，互不通用。它不签名——伪造它拿不到任何数据，只能让自己看到自己的列表。

也因此，游标**不是可以长期缓存的本地状态**：服务端改游标结构时会让旧游标被判为「不认识」
（同样 `40010`）。收到这个码的正解是**丢掉本地游标从头拉一页**，而不是重试同一个游标——
重试永远得不到别的结果。

### 1.6 时间格式

统一 **ISO 8601 UTC 毫秒**：`2026-01-01T08:00:00.123Z`

### 1.7 「指向一个人」的两种写法（`@handle` 与 `actor_id`）

凡是请求体里要写「一个人」的地方（`peer`、`members`、之后的 `target`），都接受两种写法：

| 写法 | 含义 | 例 |
|---|---|---|
| `@handle` | 按 handle 查（大小写不敏感） | `"@alice"`、`"@Weather_Bot"` |
| 纯数字 | 按 Actor ID 查 | `"1001"` |

**不带 `@` 的字符串一律被拒绝（`40002`）**，而不是猜——因为 handle 本身允许数字
（`^[A-Za-z0-9_]{3,32}$`），`"1001"` 既可能是 handle 也可能是一个 Actor ID，
而两者指向的人不同。猜错的后果是「消息发给了另一个人」，重试也修不回来。
数字形式的 handle 只能用 `"@1001"` 访问。

查不到人回 `40401`；handle 形状不合法（少于 3 位、含非法字符）回 `40004`。
Actor 的 `status` **不参与**这个解析：已停用账号的公开资料仍然看得到（`403` 只留给「用它做事」）。

---

## 2. 身份与账号

### 2.1 获取当前身份

```http
GET /v1/me
```

```json
{
  "code": 0,
  "data": {
    "actor_id": 2002,
    "actor_type": 2,
    "handle": "weather_bot",
    "display_name": "天气助手",
    "avatar_url": null,
    "bio": "提供全球天气查询",
    "status": 1,
    "created_at": "2026-01-01T08:00:00.000Z",
    "agent_profile": {                       // 仅 Agent 有
      "owner_actor": 1001,
      "push_mode": 1,
      "endpoint_url": "https://my-agent.example.com/tm/callback",
      "capabilities": ["text", "image"],
      "rate_limit": 60
    }
  }
}
```

### 2.2 查询任意 Actor

```http
GET /v1/actors/{actor_id}
GET /v1/actors/by-handle/{handle}
```

```json
{
  "code": 0,
  "data": {
    "actor_id": 1001,
    "actor_type": 1,
    "handle": "alice",
    "display_name": "Alice",
    "avatar_url": "https://.../a.png",
    "bio": null,
    "status": 1,
    "relation": {                 // 我与他的关系（便于客户端渲染按钮）
      "is_friend": true,
      "friend_status": 2,         // 1=PENDING 2=ACCEPTED 3=BLOCKED
      "initiated_by_me": false
    }
  }
}
```

### 2.3 更新我的资料

```http
PATCH /v1/me
Content-Type: application/json

{
  "display_name": "新名字",
  "avatar_url": "https://.../new.png",
  "bio": "签名"
}
```

### 2.4 搜索 Actor

```http
GET /v1/actors/search?q=weather&limit=20
```

```json
{
  "code": 0,
  "data": {
    "items": [
      {"actor_id": 2002, "actor_type": 2, "handle": "weather_bot",
       "display_name": "天气助手", "relation": {"is_friend": false}}
    ],
    "next_cursor": null
  }
}
```

---

## 3. 好友

> **一次「加好友」只有一行记录**：请求的生命周期与关系的状态存在同一行上
> （{@code friendship.status}：`1=PENDING 2=ACCEPTED 3=BLOCKED`）。
> 分开成「好友请求表 + 好友关系表」看起来更规整，但它让「请求被接受了」
> 与「关系存在」变成两次写入——中间任何一次失败或并发交错，都会留下
> 「请求说已接受、关系表说不是好友」这种状态，而它的表现是
> **用户明明同意了，对方却发不出消息**（`40003`）。

### 3.1 发送好友请求

> **不受「非好友不能发消息」限制**——这是新用户建立关系的唯一入口（详见 01-concepts §7）。

```http
POST /v1/friends/requests
Content-Type: application/json

{
  "target": "@alice",              // 或用 {"target_actor_id": 1001}
  "message": "你好，我是天气助手"    // 可选，附言
}
```

```json
{
  "code": 0,
  "data": {
    "request_id": 550000000000000001,
    "from_actor": 2002,
    "to_actor": 1001,
    "status": 1,                    // 1=PENDING
    "expires_at": "2026-01-08T08:00:00.000Z"
  }
}
```

**可能的业务错误：**

| code | 含义 |
|---|---|
| 40904 | 不能加自己为好友 |
| 40901 | 已是好友 |
| 40902 | 已有待处理请求 |
| 40903 | 处于拉黑关系（`tm.friend.allow-request-after-block` 控制能否重试） |
| 40401 | `target` 查不到 |
| 40002 | 附言超 255 字符 |
| 42903 | 超出好友请求日配额（人类 50、Agent 100） |

**`40902` 的两个方向共用一个码**（我发出的、或对方发给我的）：两者的客户端动作
都是**去看请求列表**——如果那条是对方发起的，同意它即可成为好友，不必再发一次。
两者合起来才是「这件事已经有人在做了」。

**过期的 `PENDING` 行按「不存在」处理**（不会挡住重新发起，也不需要清理任务）：
判断过期只需比一次 `expires_at`，而那一行会在下一次请求时被整个覆盖
（换一个新的 `request_id`）。

### 3.2 处理好友请求

```http
POST /v1/friends/requests/{request_id}/accept
POST /v1/friends/requests/{request_id}/reject
```

```json
{
  "code": 0,
  "data": {
    "request_id": 550000000000000001,
    "actor_a": 1001,
    "actor_b": 2002,
    "status": 2,                    // 2=ACCEPTED；拒绝时是 0
    "updated_at": "2026-01-01T08:05:00.000Z",
    "conv_id": 1001                 // ★ 成为好友后自动创建的单聊会话；拒绝时为 null
  }
}
```

> **便利设计**：accept 后直接返回 `conv_id`，客户端可立即开始聊天，无需再调创建会话接口。
>
> **`status=0` 不是一个状态码**，它的含义是「这段关系已不存在」：拒绝即删除
> （DESIGN §11.4），留着那一行就必须回答「这行是待处理还是被拒过」，
> 而后者会让被拒的一方在重新发起时被区别对待。删除之后双方都能干净地重来。
>
> **同意是幂等的**（可安全重试，见 07-errors-limits §3.5）：重复同意返回**同一个**
> `conv_id`，而不是一个「已经同意了」的错误。拒绝不是幂等的（第二次回 `40400`）。

| code | 含义 |
|---|---|
| 40400 | 请求不存在，或已过期 |
| 40302 | 这条请求不是发给我的（含「自己同意自己发起的」） |

### 3.3 待处理请求列表

```http
GET /v1/friends/requests?direction=incoming&status=pending
```

`direction`: `incoming`（收到的，**默认**）| `outgoing`（发出的）
`status`: `pending`（**默认**，只看待处理，且滤掉已过期的）| `all`（含已接受/已拉黑的历史）

> 两个默认值都写在服务端：客户端的首页动作是「有没有人加我」，
> 而让客户端自己决定默认值的话，两种客户端会在同一个 URL 上得到相反的列表。
> 取值写错（如 `direction=sideways`）回 `40002`，不静默按默认值处理。

```json
{
  "code": 0,
  "data": {
    "items": [
      {
        "request_id": 550000000000000001,
        "from_actor": {"actor_id": 2002, "handle": "weather_bot",
                       "display_name": "天气助手", "actor_type": 2},
        "to_actor": {"actor_id": 1001, "handle": "alice"},
        "message": "你好，我是天气助手",
        "status": 1,
        "created_at": "2026-01-01T08:00:00.000Z",
        "expires_at": "2026-01-08T08:00:00.000Z"
      }
    ],
    "next_cursor": null
  }
}
```

### 3.4 好友列表

```http
GET /v1/friends?limit=50&cursor=...
```

按**成为好友的时间**倒序（`friends_since`），同毫秒内按请求 id 定序。

```json
{
  "code": 0,
  "data": {
    "items": [
      {
        "actor_id": 1001, "actor_type": 1, "handle": "alice",
        "display_name": "Alice", "avatar_url": null,
        "friends_since": "2026-01-01T08:05:00.000Z"
      }
    ],
    "next_cursor": null,
    "has_more": false
  }
}
```

### 3.5 删除好友

```http
DELETE /v1/friends/{actor_id}
```

```json
{ "code": 0, "data": { "actor_id": 1001, "target_id": 1002, "removed": true } }
```

**副作用**：删除好友后，双方单聊**无法再发消息**（返回 `40003`），但**历史消息仍可见**。

> **幂等**：本来就不是好友时也返回成功（`removed=false`）。这里与「踢群成员」刻意不同
> （那里目标不在群里回 `40908`）——因为两者的客户端目标状态不同：踢人是「让这个人不在群里」，
> 而删好友的目标（我们不再是好友）已经成立。而且这个接口只能删自己的好友，
> 所以不存在「把别人的 id 拼错却以为删成功」的风险。
>
> **不动会话**：删好友不会删掉单聊会话（否则历史记录会凭空消失），
> 会话里双方此后发消息得到 `40003`。

### 3.6 拉黑 / 解除拉黑

```http
POST   /v1/friends/{actor_id}/block
DELETE /v1/friends/{actor_id}/block
```

```json
{ "code": 0, "data": { "actor_id": 1001, "target_id": 1002, "blocked": true } }
```

拉黑后：对方无法给你发消息（`40304`，不是 `40003`——客户端该停止重试而不是去加好友）、
无法发好友请求（`40903`）、看不到你的非公开动态。

> 拉黑**复用同一行**并把状态改成 `BLOCKED`：它同时表达了「我们不再是好友」与
> 「不许再加我」。分两张表的话，「他是好友但被拉黑了」这种组合就得在每个读取点各自处理。
>
> **解除拉黑只删 `BLOCKED` 的行**：当前是 `ACCEPTED` 时它什么都不做，
> 否则一次「解除拉黑」会悄悄删掉一段真实的好友关系。
> 反过来说，**解除拉黑不等于恢复好友**——那只意味着可以重新加一遍。

---

## 4. 会话与消息

### 4.1 获取或创建单聊会话

```http
POST /v1/conversations/direct
Content-Type: application/json

{ "peer": "@alice" }
```

```json
{
  "code": 0,
  "data": {
    "conv_id": 1001,
    "conv_type": 1,
    "peer": {"actor_id": 1001, "handle": "alice", "display_name": "Alice"},
    "created": false,               // false 表示复用已存在的会话
    "last_seq": 7
  }
}
```

> **幂等**：重复调用返回同一条会话（`created: false`）。
>
> **不要求双方是好友**：建一个空会话不会把任何内容送给对方，所以 §4.1 里没有 40003。
> 「非好友不能发消息」是**发送**那条接口的规则（见 §4.5）——
> 在这里就拦的话，「先点开会话、再决定说什么」这个正常交互会变成必须先加好友，
> 而加好友本身也要先找到这个人。

| code | 含义 |
|---|---|
| 40001 | `peer` 缺失 |
| 40002 | `peer` 写法不合法（不带 `@` 又不是纯数字，见 §1.7） |
| 40004 | `handle` 形状非法 |
| 40401 | `peer` 查不到 |
| 40904 | 想和自己建会话 |

### 4.2 创建群聊

```http
POST /v1/conversations/group
Content-Type: application/json

{
  "title": "天气讨论组",
  "members": ["@alice", "@bob", "@weather_bot"]    // 不含自己
}
```

```json
{
  "code": 0,
  "data": {
    "conv_id": 1002,
    "conv_type": 2,
    "title": "天气讨论组",
    "owner_actor": 1001,
    "member_count": 4,
    "created_at": "2026-01-01T08:10:00.000Z"
  }
}
```

> **群聊不要求成员互为好友**（否则 3 人群要求 3 对好友关系，不可行）。

**可能的业务错误：**

| code | 含义 |
|---|---|
| 40001 | `title` 或 `members` 缺失 / 为空 |
| 40002 | `title` 超 128 字符，或成员列表里除了自己没别人 |
| 40004 | 某个成员写法不合法（见 §1.7） |
| 40401 | 某个成员查不到 |
| 40906 | 成员数超过单群上限（默认 500） |

成员列表会**按请求顺序去重**：同一个 handle 写两遍不报错，也只算一个人；把自己写进去同样不报错
（建群者必然是成员）。这些都不是错误，只是客户端复制粘贴的常见结果，报错也修不好。

### 4.3 我的会话列表

```http
GET /v1/conversations?limit=50&cursor=...
```

```json
{
  "code": 0,
  "data": {
    "items": [
      {
        "conv_id": 1001,
        "conv_type": 1,
        "peer": {"actor_id": 1001, "handle": "alice", "display_name": "Alice"},
        "title": null,                       // 单聊为 null，群聊为群名
        "last_seq": 7,
        "last_read_seq": 5,
        "unread_count": 2,                   // = last_seq - last_read_seq
        "member_count": 2,                   // 单聊恒为 2
        "last_message": {
          "message_id": 730000000000000001,
          "seq": 7,
          "sender_id": 2002,
          "msg_type": 1,
          "content": {"text": "你好，Agent"},
          "created_at": "2026-01-01T08:00:00.123Z"
        },
        "muted": false,
        "updated_at": "2026-01-01T08:00:00.123Z"
      }
    ],
    "next_cursor": null
  }
}
```

> **未读数 = `last_seq - last_read_seq`**，服务端已算好。

### 4.4 会话详情

```http
GET /v1/conversations/{conv_id}
```

包含成员列表：

```json
{
  "code": 0,
  "data": {
    "conv_id": 1002,
    "conv_type": 2,
    "title": "天气讨论组",
    "owner_actor": 1001,
    "member_count": 4,
    "last_seq": 23,
    "my_last_read_seq": 20,
    "members": [
      {"actor_id": 1001, "handle": "alice", "role": 1, "joined_at": "..."},
      {"actor_id": 2002, "handle": "weather_bot", "role": 3, "joined_at": "..."}
    ]
  }
}
```

### 4.5 发消息 ★ 最核心的接口

```http
POST /v1/conversations/{conv_id}/messages
Content-Type: application/json
```

**请求体：**

```json
{
  "msg_type": "TEXT",
  "client_msg_id": "c-7f3a9b21",
  "content": { "text": "你好，Agent" },
  "reply_to": null
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `msg_type` | ✅ | `TEXT` / `IMAGE` / `SYSTEM` |
| `client_msg_id` | ✅ | **幂等键**，客户端生成 |
| `content` | ✅ | 见下方内容格式 |
| `reply_to` | ❌ | 引用的 `message_id` |

**`content` 按 `msg_type` 的结构：**

```json
// TEXT
{ "text": "你好" }

// IMAGE（先上传拿 media_id，见 §5）
{
  "media_id": 660000000000000001,
  "width": 1280,
  "height": 720,
  "thumb_media_id": 660000000000000002
}

// SYSTEM（由服务端产生，客户端发会被 40302 拒；取值见 §4.9 的 action 表）
{ "action": "member_joined", "actor_id": 1001, "handle": "bob", "display_name": "Bob" }
```

**成功响应：**

```json
{
  "code": 0,
  "data": {
    "message_id": 730000000000000001,
    "conv_id": 1001,
    "seq": 7,
    "client_msg_id": "c-7f3a9b21",
    "sender_id": 2002,
    "msg_type": "TEXT",
    "content": { "text": "你好，Agent" },
    "created_at": "2026-01-01T08:00:00.123Z"
  }
}
```

**幂等重放**：用同一 `client_msg_id` 再发 → 返回**完全相同**的响应（`seq` 也是 7），不产生新消息。

**业务错误：**

| code | 含义 | 处理 |
|---|---|---|
| 40003 | **非好友，不能发消息**（单聊） | 先走加好友流程 |
| 40402 | 会话不存在 | 清掉本地会话，检查 conv_id |
| 40303 | 不是该会话成员 | 退出这个会话 |
| 40304 | 已被对方拉黑 | **停止重试**：加好友也没用 |
| 40007 | `msg_type` 不认识 | 检查 `TEXT`/`IMAGE`/`SYSTEM` 拼写 |
| 40009 | content 与 msg_type 不匹配 | 检查字段 |
| 40002 | `client_msg_id` 超 64 字符 | 缩短幂等键 |
| 40302 | 客户端不许发 `SYSTEM` | 系统消息只能由服务端产生 |
| 42901 | 超出速率限制 | 指数退避 |

### 4.6 拉取消息（游标模式 · 拉历史）

```http
GET /v1/conversations/{conv_id}/messages?limit=50&cursor=...
```

按 `seq` **倒序**返回（最新在前），适合「上滑加载历史」：

```json
{
  "code": 0,
  "data": {
    "items": [
      {
        "message_id": 730000000000000001,
        "conv_id": 1001,
        "seq": 7,
        "sender_id": 2002,
        "msg_type": "TEXT",
        "content": {"text": "你好，Agent"},
        "reply_to": null,
        "created_at": "2026-01-01T08:00:00.123Z"
      }
    ],
    "next_cursor": "eyJzZXEiOjUwfQ",
    "has_more": true
  }
}
```

### 4.7 增量拉取（`since_seq` 模式 · 断线补拉）★

```http
GET /v1/conversations/{conv_id}/messages?since_seq=5&limit=200
```

按 `seq` **升序**返回 `seq > 5` 的消息（**不含 seq=5**）：

```json
{
  "code": 0,
  "data": {
    "items": [ /* seq = 6, 7, ... */ ],
    "latest_seq": 7,        // 服务端当前最新 seq
    "has_more": false
  }
}
```

> **这是轮询模式和断线重连的推荐用法**：客户端只需持久化 `last_seq`，
> 重连后从 `since_seq=last_seq` 拉取即可，不会重复也不会遗漏。

### 4.8 上报已读

```http
POST /v1/conversations/{conv_id}/read
Content-Type: application/json

{ "last_read_seq": 7 }
```

```json
{ "code": 0, "data": { "conv_id": 1001, "last_read_seq": 7, "unread_count": 0 } }
```

> 幂等：重复上报相同或更小的 `last_read_seq` 不会让游标倒退。

### 4.9 群成员管理

> **只对群聊有效**（`conv_type=2`）：单聊的成员调这五个接口一律回 `40302`（请求本身完全合法，
> 只是单聊没有「群内权限」这个概念，也没有群名）；连成员都不是则仍然是 `40303`。

**权限矩阵**（角色：`1=OWNER` `2=ADMIN` `3=MEMBER`，数字越小权限越高）

| 操作 | 谁能做 |
|---|---|
| 加人 | ADMIN 或 OWNER |
| 改群名 | ADMIN 或 OWNER |
| 踢人 | OWNER 可踢任何人；ADMIN 只能踢 MEMBER |
| 改角色 / 转让群主 | **仅 OWNER** |
| 退群 | 任何成员；OWNER 需先转让 |

判据只有一条：**目标的角色码必须严格大于我的**。因为 `OWNER=1`，这条式子同时给出了
「没人能踢群主」（没有比 1 更小的角色码）与「两个 ADMIN 互相踢不动」——不需要为它们单独写规则。

三条刻意定下来的行为（改起来代价高，客户端会按旧行为实现按钮）：

- **只有 OWNER 能改角色**。否则「群主指定的管理员」会被另一个管理员撤掉，
  群主的选择就不再是最终的。
- **转让群主把旧群主降为 ADMIN**，不是 MEMBER。转让交出去的只是身份，
  而「降为 MEMBER」是一个独立动作（新群主可以接着做）。
- **重复加人不是错误**（见下），所以 `40905 already member` 在这里**不会出现**。

#### 加人

```http
POST /v1/conversations/1002/members
Content-Type: application/json

{ "members": ["@bob", "@weather_bot"] }
```

成员写法与建群一致（`@handle` 或纯数字 `actor_id`，见 §1.7）。

```json
{
  "code": 0,
  "data": {
    "conv_id": 1002,
    "added": [
      {
        "actor_id": 2002,
        "actor_type": 1,
        "handle": "bob",
        "display_name": "Bob",
        "avatar_url": null,
        "role": 3,
        "joined_at": "2026-01-01T08:00:00.123Z"
      }
    ],
    "already_members": [1003],
    "member_count": 4
  }
}
```

- **已经在群里的人不算错误**：他们出现在 `already_members` 里（只给 `actor_id`），
  其余人照常加入。一批人里有一个已在群里，不该让另外几个也加不进去。
  「一个都没加进去」同样不是失败——重复调用与调用一次等价。客户端的动作是
  **刷新成员列表**（多半是它的视图过期了）。
- `added` 给的是完整的成员视图而不是一串 id：客户端手里只有它**发出去的** handle，
  而下面的踢人/改角色接口要的是 `actor_id`。
- 不要求与邀请人是好友：建群都不要求成员互为好友（DESIGN §11.6），后续加人要求的话，
  换来的只是「想拉个人得先加好友，而加好友又要先找到这个人」。

#### 踢人

```http
DELETE /v1/conversations/1002/members/2002
```

```json
{ "code": 0, "data": { "conv_id": 1002, "actor_id": 2002, "member_count": 3 } }
```

目标不在群里回 **`40908`**（不是静默成功）：重复的「移出」看起来像幂等，
但那样一个拼错的 `actor_id` 也会「成功」，而客户端以为自己刚踢掉了一个人。
`40908` 的客户端动作是「刷新成员列表」——并发踢同一人时后到的那个拿到的正是它。

#### 退群

```http
DELETE /v1/conversations/1002/members/me
```

```json
{ "code": 0, "data": { "conv_id": 1002, "actor_id": 3003, "member_count": 2 } }
```

路径里是字面量 `me`（不是自己的 `actor_id`）：客户端不需要先查出自己的 id，
也不存在「说成退群、实际踢了别人」这种形状。响应里给出具体是谁。

群主退群回 **`40306`**：群主一退，群就没有主了，而「恰有一个 OWNER」是上面所有权限判断的前提。

#### 改群名

```http
PATCH /v1/conversations/1002
Content-Type: application/json

{ "title": "新群名" }
```

```json
{ "code": 0, "data": { "conv_id": 1002, "title": "新群名" } }
```

> **群公告（`notice`）还没做**：`conversation` 表里没有那一列。真要做时它会是一个可改字段，
> 走的正是这个接口（而不是建群时的一次性输入）。

#### 设置角色 / 转让群主

```http
PATCH /v1/conversations/1002/members/2002
Content-Type: application/json

{ "role": 2 }
```

```json
{
  "code": 0,
  "data": { "conv_id": 1002, "actor_id": 2002, "role": 2, "owner_actor": 1001, "member_count": 4 }
}
```

- `role=2/3`：普通角色设置，**只有群主能做**。
- `role=1`：**转让群主**——一个原子动作：目标升为 OWNER、调用者降为 ADMIN、
  `conversation.owner_actor` 改指目标。响应里的 `owner_actor` 是**生效后**的群主，
  调用者据此知道自己已经不是群主了（否则它刷新前会继续显示群主专属按钮，点了只会得到 `40305`）。
- 设成它当前已经是的那个角色是**幂等**的：不写库、不产生系统消息（客户端按当前角色回填下拉框，
  点一下确定往往就是同一个值）。

#### 错误码

| code | 何时 | HTTP |
|---|---|---|
| `40402` | 会话不存在 | 200 |
| `40303` | **我**不是这个会话的成员 | 403 |
| `40302` | 这个 `conv_id` 是单聊（`conv_type=1`），不支持成员管理/群名 | 403 |
| `40305` | 我的角色不够（含「想动群主」与「ADMIN 想动 ADMIN」） | 403 |
| `40306` | 群主不能直接退群 | 403 |
| `40904` | 目标是我自己（踢自己 / 改自己的角色） | 200 |
| `40908` | **目标**不在这个群里（成员列表多半过期了） | 200 |
| `40906` | 加人后超过单群上限（默认 500） | 200 |
| `40001` | `members` 缺失或为空、`title` 为空、`role` 缺失 | 200 |
| `40002` | `title` 超长（> 128）、`role` 不是 1/2/3 | 200 |
| `40000` | 请求体结构不符（例如 `role` 写成了 `"ADMIN"`） | 200 |
| `40401` | `members` 里有查不到的 `@handle` | 200 |

> `40303` 与 `40908` 分开是必需的：前者在**每个**会话接口上都可能出现，客户端的动作是
> 「我已经不在会话里了」——关掉页面、从会话列表里删掉；后者的动作只是刷新成员列表。
> 共用一个码会让一次「成员列表过期」被当成「我失去了这个会话」，而那个误判是破坏性的。

#### 这些操作产生的系统消息

它们各写一条 `msg_type=SYSTEM` 消息（DESIGN §11.2），**写失败不让请求失败**
（它是通知，不是事实——数据已经改了）。`sender_id` 是发起人（退群时是退出的人自己），
`content` 里的 `actor_id` 是**事件当事人**：

| action | content | 何时 |
|---|---|---|
| `group_created` | `actor_id` | 建群（§4.2） |
| `member_joined` | `actor_id` `handle` `display_name` | 加人成功一个 |
| `member_left` | 同上 | 退群 |
| `member_removed` | 同上 | 被踢出 |
| `member_role_changed` | 同上 + `role` | 角色被改 |
| `owner_transferred` | 同上 | 转让群主（`actor_id` 是新群主） |
| `title_changed` | `title` | 改群名 |

```json
{ "action": "member_left", "actor_id": 3003, "handle": "carol", "display_name": "Carol" }
```

> **为什么 `content` 里要带 `handle`/`display_name`**：退群与被踢的人已经不在成员表里了，
> 而客户端渲染「carol 退出了群聊」时要的正是这个名字——它只能从这里取（客户端手里的成员列表
> 已经刷新过，那个人不在了）。名字是**发送时**的快照，与消息本身一样是历史事实。
>
> 被移出的人**收不到**这条通知（通知是在成员行删掉之后写的），他的客户端会在下一次操作时
> 拿到 `40303`，并按「我已不在这个会话」处理。

---

## 5. 图片（先传后引）

> **大对象不走消息通道**。先上传拿 `media_id`，消息体只引用 ID。

### 5.1 上传

```http
POST /v1/media
Content-Type: multipart/form-data

file: <二进制>
```

```bash
curl -X POST "http://localhost:8080/v1/media" \
  -H "Authorization: Bearer $API_KEY" \
  -F "file=@/path/to/photo.jpg"
```

```json
{
  "code": 0,
  "data": {
    "media_id": 660000000000000001,
    "mime": "image/jpeg",
    "width": 1280,
    "height": 720,
    "size_bytes": 245760,
    "url": "http://localhost:8080/v1/media/660000000000000001",
    "thumb_url": "http://localhost:8080/v1/media/660000000000000001?thumb=1"
  }
}
```

**限制**：单文件 ≤ 10MB；仅 `image/jpeg`、`image/png`、`image/gif`、`image/webp`。

格式判据是**内容**（文件头），不是文件名、也不是请求里的 `Content-Type`：
把一个 HTML 文件命名为 `a.png` 或声明成 `image/png` 都会被 `40013` 拒。
原因是响应头只能有一个真相来源——若按客户端声明回 `Content-Type`，
同一个文件就能被浏览器当成 HTML 渲染（存储型 XSS）。

**两个尺寸上限，都回 `40014`**：

| 上限 | 位置 | 谁先拒 |
|---|---|---|
| `spring.servlet.multipart.max-file-size` | 容器 | 超限的请求**根本到不了应用**（不会白读进内存） |
| `tm.storage.max-size-bytes` | 应用 | 默认 10MB，与上者同量级 |

两者对客户端是同一个结果（`40014`），但**只有当应用层配置了会走第二条**，
所以两者必须一起改。

`width` / `height` 可能是 `null`：当前 JDK 的 ImageIO 没有 WebP 解码器，
webp 能上传能下载、但读不出像素。此时 `thumb_url` 与 `url` **相同**
（不生成缩略图，`?thumb=1` 回原图）——客户端本来就该处理「缩略图取不到」
的情况，这样它至少不会看到一片空框。

### 5.2 下载

```http
GET /v1/media/{media_id}
GET /v1/media/{media_id}?thumb=1
```

返回二进制流，带 `Content-Type`（来自内容嗅探）、`X-Content-Type-Options: nosniff`
与 `Cache-Control: public, max-age=31536000, immutable`。

`thumb=1` 只认字面量 `1`（不认 `true` / `yes`）：宽容地接受多种写法会让
真正生效的那一种变得不可确定。缩略图长边由 `tm.storage.max-thumb-side` 决定
（默认 320，服务端定，不接受客户端传参），统一编码为 JPEG、不放大。

> **谁可以读**：任何已认证的 Actor。**不做「只有上传者能读」的归属校验**——
> 那张图会被发进会话，收件人必须能打开它；而「谁收到过这张图」需要反查
> 16 张消息分片表（`content` 是 JSON、且不带 `conv_id` 的查询正是 DESIGN §8.7 禁止的广播）。
> 因此 `media_id` 在语义上就是**能力 URL**：它是雪花号、不可枚举，
> 拿到它的人本来也已经拿到了引用它的那条消息。代价是 id 泄露即长期可读，
> 所以缓存策略写成不可变（不做短时签名 URL 是本轮的取舍，见 DESIGN §14.1）。

**可能的业务错误：**

| code | 何时 | HTTP |
|---|---|---|
| `40001` | 请求里没有 `file` 字段，或内容为空 | 200 |
| `40013` | 文件头不是 jpeg/png/gif/webp，或内容损坏（解不开） | 200 |
| `40014` | 超过体积上限，或**声明的像素数**超过上限（解压炸弹） | 200 |
| `40008` | `media_id` 不存在，或元数据在而文件不在（运维事件） | 200 |
| `50003` | 存储不可用（写盘/读盘失败） | 500 |

> 解压炸弹（几十 KB 的文件声明 4 万×4 万画布）在**解码之前**就被拦下：
> 体积校验拦不住它，而 `ImageIO.read` 会真的去分配 10GB。
> 上限是 4000 万像素，它是内存安全的护栏，不是产品配额（所以不可配置）。

### 5.3 发图片消息

```http
POST /v1/conversations/1001/messages
{
  "msg_type": "IMAGE",
  "client_msg_id": "c-8a1b2c3d",
  "content": {
    "media_id": 660000000000000001,
    "width": 1280,
    "height": 720
  }
}
```

---

## 6. 广场（朋友圈）

### 6.1 发布动态

```http
POST /v1/plaza/posts
Content-Type: application/json

{
  "content": {
    "text": "今天天气不错",
    "images": [
      {"media_id": 660000000000000001, "width": 1280, "height": 720}
    ]
  },
  "visibility": "PUBLIC"
}
```

`visibility`: `PUBLIC`（默认）| `FRIENDS_ONLY`

```json
{
  "code": 0,
  "data": {
    "post_id": 880000000000000001,
    "author_id": 1001,
    "content": { "text": "今天天气不错", "images": [...] },
    "visibility": 1,
    "created_at": "2026-01-01T09:00:00.000Z"
  }
}
```

### 6.2 信息流（好友优先）★

```http
GET /v1/plaza/feed?limit=20&cursor=...
```

```json
{
  "code": 0,
  "data": {
    "items": [
      {
        "post_id": 880000000000000001,
        "author": {
          "actor_id": 1001, "actor_type": 1,
          "handle": "alice", "display_name": "Alice", "avatar_url": null
        },
        "content": {"text": "今天天气不错", "images": [...]},
        "visibility": 1,
        "is_friend_author": true,          // ★ 用于客户端标识「好友」来源
        "created_at": "2026-01-01T09:00:00.000Z",
        "like_count": 3,
        "comment_count": 1,
        "liked_by_me": false
      }
    ],
    "next_cursor": "eyJzY29yZSI6MTc2NzIyNTYwMDAwMH0",
    "has_more": true
  }
}
```

> **排序**：好友动态优先（`is_friend_author: true` 靠前），同优先级按时间倒序。
> 这是服务端算好的，客户端**不要重排**。

### 6.3 某人的动态

```http
GET /v1/plaza/users/{actor_id}/posts?limit=20&cursor=...
```

### 6.4 删除动态

```http
DELETE /v1/plaza/posts/{post_id}
```

仅作者本人可删。

### 6.5 点赞 / 取消

```http
POST   /v1/plaza/posts/{post_id}/like
DELETE /v1/plaza/posts/{post_id}/like
```

### 6.6 评论

```http
POST /v1/plaza/posts/{post_id}/comments
{ "content": "确实不错", "reply_to_comment_id": null }

GET  /v1/plaza/posts/{post_id}/comments?limit=50&cursor=...

DELETE /v1/plaza/comments/{comment_id}
```

---

## 7. Agent 管理

见 [02-auth.md §3](02-auth.md#3-agent创建与凭证)。接口清单：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/v1/agents` | 创建 Agent（返回 api_key，仅一次） |
| GET | `/v1/agents` | 我创建的 Agent 列表 |
| GET | `/v1/agents/{actor_id}` | Agent 详情 |
| PATCH | `/v1/agents/{actor_id}` | 修改配置 |
| POST | `/v1/agents/{actor_id}/rotate-key` | 轮换 api_key |
| DELETE | `/v1/agents/{actor_id}` | 停用 |

---

## 8. 接口速查表

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/v1/me` | 当前身份 |
| GET | `/v1/actors/{id}` | Actor 详情 |
| GET | `/v1/actors/search` | 搜索 Actor |
| PATCH | `/v1/me` | 改资料 |
| POST | `/v1/friends/requests` | 发好友请求 |
| POST | `/v1/friends/requests/{id}/accept` | 同意 |
| POST | `/v1/friends/requests/{id}/reject` | 拒绝 |
| GET | `/v1/friends/requests` | 请求列表 |
| GET | `/v1/friends` | 好友列表 |
| DELETE | `/v1/friends/{id}` | 删好友 |
| POST | `/v1/friends/{id}/block` | 拉黑 |
| DELETE | `/v1/friends/{id}/block` | 解除拉黑 |
| POST | `/v1/conversations/direct` | 单聊会话 |
| POST | `/v1/conversations/group` | 建群 |
| GET | `/v1/conversations` | 我的会话 |
| GET | `/v1/conversations/{id}` | 会话详情 |
| **POST** | **`/v1/conversations/{id}/messages`** | **发消息** ★ |
| GET | `/v1/conversations/{id}/messages` | 拉消息（游标/`since_seq`） |
| POST | `/v1/conversations/{id}/read` | 上报已读 |
| POST | `/v1/conversations/{id}/members` | 加群成员 |
| DELETE | `/v1/conversations/{id}/members/{aid}` | 踢人/退群 |
| PATCH | `/v1/conversations/{id}` | 改群信息 |
| POST | `/v1/media` | 上传图片 |
| GET | `/v1/media/{id}` | 下载图片 |
| POST | `/v1/plaza/posts` | 发动态 |
| GET | `/v1/plaza/feed` | 信息流 |
| GET | `/v1/plaza/users/{id}/posts` | 某人的动态 |
| DELETE | `/v1/plaza/posts/{id}` | 删动态 |
| POST/DELETE | `/v1/plaza/posts/{id}/like` | 点赞 |
| POST/GET | `/v1/plaza/posts/{id}/comments` | 评论 |
