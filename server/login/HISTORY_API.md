# 观看记录同步协议 v1（服务 0.2.0）

所有接口都要求 HTTPS 和 `Authorization: Bearer <token>`，使用与 `/v1/auth/me` 相同的账号身份。JSON 错误结构为 `{"error":{"code":"...","message":"..."}}`，响应禁止缓存。路径相对用户配置的 HTTPS 服务根地址。请求里的用户 ID、令牌字段、额外未知字段均不接受；服务器只按已验证会话对应的用户访问 SQLite。

## 获取增量记录

`GET /v1/history?cursor=0&limit=100`

```json
{
  "records": [{
    "seriesId": "123456", "title": "持械入宋", "coverUrl": "https://example.com/cover.jpg",
    "description": "简介", "episodeCount": 68, "episodeLabel": "共68集", "tags": ["短剧"],
    "vid": "987654321", "index": 8, "positionMs": 50000,
    "revision": 101, "updatedAt": 1792713600000
  }],
  "cursor": 101,
  "hasMore": false
}
```

- `cursor` 默认 0；十进制非负整数，最多 `9007199254740991`。`limit` 默认 100、范围 1–100。不接受负数、小数、重复或未知查询参数。
- 返回当前用户的 `revision > cursor` 记录，按修订号升序；每部剧只保留当前记录，不返回每一次播放事件。
- 单页同时受 1 MiB 编码预算约束，可能少于 `limit`。存在后续记录时 `hasMore=true`。空页保留请求游标。
- 更新中的记录会移动到新修订号，在之后的页面或下一轮拉取再次出现；客户端按修订号幂等合并，不按页码推断总数。
- **拉取游标只能推进到成功持久化的 GET 响应 cursor。上传响应的修订号不能作为拉取游标，否则会漏掉其他设备的中间更新。**
- 本协议没有删除接口或过期清理，最多保存每账号 1000 部记录。达到上限后已有剧的进度仍可更新。

## 上传进度

`POST /v1/history/sync`，`Content-Type: application/json`：

```json
{
  "deviceId": "install-phone-1234",
  "mutations": [{
    "mutationId": "change-uuid-1234", "sequence": 12, "baseRevision": 101,
    "record": {
      "seriesId": "123456", "title": "持械入宋", "coverUrl": "https://example.com/cover.jpg",
      "description": "简介", "episodeCount": 68, "episodeLabel": "共68集", "tags": ["短剧"],
      "vid": "987654321", "index": 8, "positionMs": 55000
    }
  }]
}
```

响应为 `{"results":[{"mutationId":"change-uuid-1234","status":"applied","record":{...}}]}`。`record` 结构同 GET，并带服务端分配的 `revision` 和 `updatedAt`。

### 比较与冲突

- 新剧使用 `baseRevision=0`；已有剧使用最后一次确认过的该剧 `revision`。相同修订号时应用变更，分配新全局修订号，`status=applied`。
- 服务端已有更近修订号时，`status=conflict`，`record` 返回当时的服务端权威记录。客户端保留本地待确认状态时也不得把这个旧变更自动改成新 baseRevision 继续上传；那会把冲突检测变成“最后到达者覆盖”。
- 当前写入会话发生冲突后应暂停其自动续写，并让用户从同步后的记录重新选择播放，或以明确新播放操作开启新写入会话。用户主动重播／回退时用最新修订号更新，允许集数和秒数变小。
- `positionMs=0` 为合法进度，按客户端当前播放结束／重播语义保留，不自行解释成删除或忽略。
- 同一请求批次为事务：格式错误、容量错误或幂等标识误用导致整批不写入。正常 `conflict` 是逐项结果，不属于事务错误。
- 服务器时间仅用于显示排序，修订号用于并发控制；服务器分配的显示时间也保持递增，客户端系统时间不参与覆盖决策。

### 序号与网络重试

- `deviceId` 对本安装稳定；`mutationId` 每次新变更唯一。两者长度 8–80，仅允许 `[A-Za-z0-9_.:-]`。
- 每个账号/设备的正整数 `sequence` 持久化递增，最大 `9007199254740991`，允许跳号；批内严格升序。同一安装仅保持一批在途请求，先完成重试再发送新批。
- 同一 `(用户, deviceId, mutationId/sequence)` 的相同输入返回存储的原结果，不重复写入、不分配新修订号。若重复标识内容不同，整批返回 409 `MUTATION_REUSED`。
- 服务保留最近 **256 个回执/设备**，同时永久保留该设备已处理序号水位。早于水位且回执已移除的请求返回 `status=obsolete` 和该剧当前记录，绝不重新应用。若其所指剧从未存在，则返回 400；客户端不导入空记录。
- `obsolete` 的客户端处理同冲突。保留回执的重试可能返回较旧记录，客户端不能用较小 `revision` 覆盖已经见过的较新记录。
- 不删除设备水位来“清理回执”。安装 ID 变更应视为新设备；账号最多 64 个设备，并非 64 个同时在线登录会话（登录仍最多 20 个会话）。

### 字段与容量

- `seriesId`：6–30 位数字字符串；`vid`：1–30 位数字字符串。
- `index`：1–10000 整数；`positionMs`：0–604800000 整数（7 天）；`episodeCount`：0–10000。
- 字符串长度按 Android UTF-16 单元计：标题 1–200、封面 4096、简介 8000、集数标签 100；标签最多 8 个，每个 80。
- 请求记录不包含 `revision`、`updatedAt`、账号、服务端状态、缓存路径或媒体地址（封面展示地址除外）。`seriesId/title/vid/index/positionMs` 以及变更的 `mutationId/sequence/baseRevision/record` 必须存在，不能使用 JSON null 代替数值。
- 每批 1–20 项，请求正文最多 1 MiB；客户端建议每批 10 项并先限长。20 项最坏合法元数据的返回结果小于 2 MiB。每账号最多 1000 部剧和 64 个安装设备。回执清理不造成正常持续同步达到固定次数上限。

### 常见结果

| HTTP | code / status | 处理 |
|---|---|---|
| 200 | applied | 保存确认修订号；按本地持久化规则移除相应待传项 |
| 200 | conflict / obsolete | 合并较新记录，停止旧写入者自动覆盖；不自动重新基准上传 |
| 400 | INVALID_INPUT | 检查字段、序号及查询参数；不是网络重试 |
| 401 | UNAUTHORIZED | 登录过期／停用／撤销；保留该账号本地队列并重新登录 |
| 403 | HTTPS_REQUIRED | 使用有效 HTTPS 服务地址 |
| 409 | MUTATION_REUSED | 客户端持久化或序号错误；不改原请求强行重试 |
| 409 | HISTORY_CAPACITY / DEVICE_CAPACITY | 容量已满；已有记录与数据不删除 |
| 413 | BODY_TOO_LARGE | 减小批次／显示元数据 |
| 415 | UNSUPPORTED_MEDIA_TYPE | 使用 application/json |
| 500 / 503 | INTERNAL_ERROR / UNAVAILABLE | 限速退避重试；保留原幂等请求 |

## 运维

0.1.0 → 0.2.0 自动新增表，不重建账号数据库。备份整个 SQLite 数据目录（先停服或使用可靠 SQLite 在线备份）。旧 0.1.0 程序忽略新增表，因此程序回退不删除记录，但也不再提供同步接口。不要对公网公开 SQLite 文件，不打印请求 Authorization、密码或完整用户观看记录。

此服务用于单实例个人／家庭账号同步，没有自动注册、找回密码、跨副本一致性、视频云存储或自动转码功能。
