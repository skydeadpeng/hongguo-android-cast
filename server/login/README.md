# 红果轻量账号与观看记录同步服务 · Go + Gin · 0.2.0

供自有服务器部署的独立登录与观看记录同步 API。Go + Gin + SQLite，单进程运行，不依赖 MySQL、Redis、Node.js 或 Docker。

## 这次包含什么

- 管理员在服务器创建账号、重置密码、停用账号。
- 手机和 TV 可以使用同一账号，分别获得独立的登录会话。
- 登录、查询当前用户、退出、列出会话、撤销指定会话、全部退出。
- 密码使用 bcrypt 加盐哈希；随机登录令牌仅保存 SHA-256 摘要。
- 默认会话有效期 30 天；最多保留 20 个会话；重置密码或停用账号会撤销已有会话。
- 登录频率限制、并发限制、请求大小限制及指定反向代理校验。
- 同一账号在手机与 TV 间同步观看记录、当前集和毫秒级播放进度；离线重连采用修订号冲突检测，旧设备不会直接覆盖新进度。
- 同步回执支持网络断开后的幂等重试；每设备持久化序号水位，回执清理后仍阻止旧请求重放。

**配合支持账号同步的手机／TV APK 使用。此服务没有网页管理后台；只同步观看记录和播放进度，不上传视频、缓存、播放源、收藏或搜索历史。视频播放、投屏、连播、缓存 5 集与自动清理由各设备原有播放器负责。**

## 1. 选择服务器文件

Linux 上执行 `uname -m`：

| 结果 | 使用的压缩包 |
| --- | --- |
| x86_64 | hongguo-login-0.2.0-linux-amd64.tar.gz |
| aarch64 / arm64 | hongguo-login-0.2.0-linux-arm64.tar.gz |

上传并解压对应包。下文从解压后的目录执行。预编译文件不需要服务器安装 Go。

## 2. 安装到 Linux（systemd）

以下命令适用于已有 `sudo`、`useradd` 和 systemd 的 Linux。已有同名服务账号时跳过建号命令。

```sh
sudo useradd --system --user-group --home-dir /var/lib/hongguo-login --shell /usr/sbin/nologin hongguo-login
sudo install -d -m 755 /opt/hongguo-login
sudo install -d -m 700 -o hongguo-login -g hongguo-login /var/lib/hongguo-login
sudo install -m 755 hongguo-login /opt/hongguo-login/hongguo-login
sudo install -m 600 config.env.example /etc/hongguo-login.env
sudo install -m 644 deploy/hongguo-login.service /etc/systemd/system/hongguo-login.service
sudo -u hongguo-login env DB_PATH=/var/lib/hongguo-login/login.sqlite /opt/hongguo-login/hongguo-login create-user yourname
sudo systemctl daemon-reload
sudo systemctl enable --now hongguo-login
curl --fail http://127.0.0.1:8787/healthz
```

把 `yourname` 换为自己的账号，按提示输入密码。用户名为 3–32 位英文字母、数字、点、下划线或短横线；自动转小写。密码至少 12 个字符，最多 72 个 UTF-8 字节，中文会占多个字节。密码不要写入启动参数、配置文件或聊天记录。

服务只监听本机 `127.0.0.1:8787`，对外使用下一步的 HTTPS 域名。健康检查成功只是进程正常，还需验证公网登录链路。

## 3. 配置 HTTPS（二选一）

**已有 Nginx / 面板反向代理：** 把有有效证书的 `https://你的域名` 转发到 `http://127.0.0.1:8787`。参考 `deploy/nginx.conf.example`，替换域名、证书路径，检查配置后再重载。由代理覆盖 `X-Forwarded-Proto` 和 `X-Forwarded-For`，不要原样信任客户端传入的值。

**使用 Caddy：** 将 `deploy/Caddyfile` 中 `login.example.com` 替换为你的域名，合并到已有 Caddy 配置。域名 DNS 指向服务器，80/443 端口可达且未被其他服务占用时，Caddy 可自动申请和续期证书。已有网站时不要覆盖整份配置。

示例默认同机代理，环境配置只信任 `127.0.0.1/32,::1/128`。远程或容器代理需要明确配置实际代理地址，并限制后端端口的来源，别直接信任全部地址。

```sh
curl --fail https://你的域名/healthz
```

查看运行日志：`sudo journalctl -u hongguo-login -n 50 --no-pager`。

## 4. 不用 systemd 的启动方式

由你现有的进程管理器守护下面的命令；运行目录和数据目录应由专用非 root 账号拥有。仍需同机 HTTPS 反向代理。

```sh
mkdir -p data
chmod 700 data
DB_PATH="$PWD/data/login.sqlite" ./hongguo-login create-user yourname
DB_PATH="$PWD/data/login.sqlite" HOST=127.0.0.1 PORT=8787 TRUST_PROXY=true TRUSTED_PROXIES=127.0.0.1/32,::1/128 ./hongguo-login serve
```

## 5. 账号管理

安装为 systemd 服务后：

```sh
# 列出账号（不显示密码或登录令牌）
sudo -u hongguo-login env DB_PATH=/var/lib/hongguo-login/login.sqlite /opt/hongguo-login/hongguo-login list-users
# 重置密码，所有设备原来的登录随即失效
sudo -u hongguo-login env DB_PATH=/var/lib/hongguo-login/login.sqlite /opt/hongguo-login/hongguo-login reset-password yourname
# 停用账号并撤销全部会话
sudo -u hongguo-login env DB_PATH=/var/lib/hongguo-login/login.sqlite /opt/hongguo-login/hongguo-login disable-user yourname
```

没有公开注册、短信、邮箱找回接口；遗忘密码由服务器管理员处理。登录限流存在进程内，重启会清空；这是个人/家庭小规模单实例服务，不是多副本账号平台。

## 6. API 接入

所有接口返回 JSON，退出和撤销成功返回 HTTP 204（无正文）。登录后发送 `Authorization: Bearer <token>`，不要把令牌放 URL。客户端不需要接触管理员密码、数据库或服务器 SSH 密钥。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/healthz` | 健康检查 |
| POST | `/v1/auth/login` | 账号密码登录 |
| GET | `/v1/auth/me` | 当前用户 |
| GET | `/v1/auth/sessions` | 当前账号的会话列表 |
| DELETE | `/v1/auth/sessions/{id}` | 撤销自己的指定会话 |
| POST | `/v1/auth/logout` | 退出当前会话 |
| POST | `/v1/auth/logout-all` | 撤销全部会话 |
| GET | `/v1/history?cursor=0&limit=100` | 当前账号观看记录的增量分页 |
| POST | `/v1/history/sync` | 批量比较修订号后写入当前账号观看记录 |

登录请求，`Content-Type: application/json`：

```json
{"username":"yourname","password":"<用户输入的密码>","deviceName":"客厅电视"}
```

登录响应包含 `token`、`expiresAt`、`user` 和 `session`。会话列表中的 `current` 表示是否为本次请求使用的会话。密码错误和账号不存在使用相同的 401 结果；429 表示限流，客户端按 `Retry-After` 等待，不要马上循环重试；401 应回到登录。

### 手机与 TV 的连接方式

两端填写同一个 **HTTPS 服务根地址**（例如 `https://login.example.com`），使用同一个管理员创建的账号。用户名和密码属于普通账号，客户端不使用管理员 SSH 账号。服务器地址必须使用有效证书，不跳过证书校验。域名换地址、切换账号或退出登录时，客户端分别隔离账号记录和会话；原有未登录本地记录保持独立。

客户端令牌仅发给用户配置的固定服务地址；不发送给视频 CDN、上游取源接口、电视或投屏接收端。服务仍按会话身份隔离数据，不接受请求里的 `userId`。密码和登录令牌不进入接口日志。

### 同步协议 v1

完整 JSON 与客户端要求见 [HISTORY_API.md](HISTORY_API.md)。核心规则：

- 每部剧的真实 `seriesId` 为记录键，记录保留对应季的真实 `vid`、从 1 开始的 `index`、非负 `positionMs`。不使用跨季虚拟序号。
- 服务分配递增 `revision` 和毫秒 `updatedAt`。新记录 `baseRevision=0`，更新必须等于该剧当前修订号。
- 冲突返回服务端记录，不根据“更大的集数／秒数”或客户端时间强行覆盖。用户主动重播早期集数或回退进度，在掌握最新修订号后仍能正常保存。
- 每设备序号 `sequence` 必须持久化且递增；一次在途批次重试保留原内容、原序号和原 `mutationId`。每设备保留最近 256 个回执，旧回执过期后序号水位仍保留。
- 每账号最多 1000 部记录、64 个安装设备；单批最多 20 项、请求最多 1 MiB。GET 单页最多 100 项，并受 1 MiB 正文预算约束，始终按 `hasMore` 判断是否继续。正常播放的持续更新不会耗尽回执容量。
- 只同步记录，不包含视频上传／转码。服务停机或断网时，本地播放不依赖服务；待网络恢复后重试同步。服务端没有公开删除历史记录接口，本版本不执行云端历史清理。

## 7. 备份与更新

SQLite 默认启用 WAL。不要在写入期间只拷贝一个 `.sqlite` 文件。最简单方式是停服务后备份整个数据目录，再启动；备份只保存到受限目录。

```sh
sudo systemctl stop hongguo-login
sudo sh -c 'umask 077; tar -C /var/lib -czf "/root/hongguo-login-data-$(date +%Y%m%d-%H%M%S).tar.gz" hongguo-login'
sudo systemctl start hongguo-login
```

### 从 0.1.0 升级

更新前保留旧可执行文件、配置和数据备份。停止服务，替换可执行文件，再启动，继续使用 **原来的 DB_PATH**。0.2.0 首次打开数据库时仅新增观看记录、设备序号和回执表，原账号、密码摘要、会话及配置保留；不必重新创建账号。

```sh
sudo systemctl stop hongguo-login
# 按上方方式先备份整个数据目录，再保存旧程序
sudo cp /opt/hongguo-login/hongguo-login /opt/hongguo-login/hongguo-login.previous
sudo install -m 755 ./hongguo-login /opt/hongguo-login/hongguo-login
sudo systemctl start hongguo-login
/opt/hongguo-login/hongguo-login version
curl --fail http://127.0.0.1:8787/healthz
```

如果沿用 0.1.0 的 Nginx 示例，请把 `client_max_body_size 8k` 调整为 `client_max_body_size 1m`，检查配置后重载，否则较长的观看记录批次会在代理处被拒绝。登录接口自身仍限制为 8 KiB。Caddy 示例无需这项调整。

预期版本输出 `hongguo-login 0.2.0`。服务器需先升级，否则旧 0.1.0 的登录接口虽然正常，观看记录接口会返回 404。不要覆盖数据目录或使用空白数据库替换旧库。

如需回退程序，停服后恢复 `.previous` 可执行文件，继续使用原数据目录；0.1.0 忽略新增同步表，账号登录仍可用，但观看记录同步接口暂时不可用。回退不删除已同步记录。只有明确要恢复整库快照时才恢复数据备份，恢复旧快照会丢弃备份后的记录。

停止同步服务不会修改播放器或删除各设备本地观看记录。服务端本身不管理 App 的视频缓存文件。

## 8. 源码构建与验收

使用 `go.mod` 指定或更新的受支持 Go 工具链，从本目录运行：

```sh
go test -tags nomsgpack -race -count=1 -coverprofile=coverage.out ./...
go vet -tags nomsgpack ./...
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -tags nomsgpack -trimpath -ldflags='-s -w' -o bin/hongguo-login-linux-amd64 ./cmd/login-server
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -tags nomsgpack -trimpath -ldflags='-s -w' -o bin/hongguo-login-linux-arm64 ./cmd/login-server
```

服务使用 JSON；`nomsgpack` 仅排除未使用的 MsgPack 编解码依赖。本地开发测试才使用 `ALLOW_INSECURE_HTTP=true`；部署示例默认关闭。服务端账号、会话数据与 APK 不共用数据目录。

参考：[Gin 可信代理](https://gin-gonic.com/en/docs/server-config/trusted-proxies/)、[Caddy 自动 HTTPS](https://caddyserver.com/docs/automatic-https)、[Go bcrypt](https://pkg.go.dev/golang.org/x/crypto/bcrypt)、[SQLite 纯 Go 驱动](https://pkg.go.dev/modernc.org/sqlite)。


### 可选：真实 Android 传输与 Go HTTPS 集成验证

正常 Go 测试不依赖 Android 工具。已有完整项目、JDK、Android SDK 35 和 org.json JVM 包时，可额外执行下面的跨语言验证；它只使用本地临时账号和 Go TLS 测试服务，不连接生产服务器、不启动模拟器。

```sh
HONGGUO_ANDROID_SOURCE_ROOT=/完整项目目录 \
JAVA_HOME=/JDK目录 ANDROID_HOME=/Android-SDK目录 JSON_JAR=/android-json.jar \
go test -tags nomsgpack -run '^TestAndroidHTTPSIntegration$' -count=1 -v ./internal/auth
```

该验证编译当前 APK 的 5 个真实 Java 源文件，用实际 `AccountSync.request` 与 `HistorySyncState` 访问 Go TLS 服务；覆盖两设备、断线丢应答后的原请求重试、冲突保护、主动重播、分页、账号隔离和退出。测试单独信任临时本地证书，不修改生产 TLS 校验，也不验证 Android 界面、设备 Keystore 或真实电视投屏。
