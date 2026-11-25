# 胶囊短信同步服务器

这是胶囊短信的自托管设备管理与加密消息中继服务器。它提供：

- 管理员后台：创建、启停用户以及重置密码。
- 用户门户：查看/撤销设备，生成一次性二维码或手动注册码。
- 设备 API：账号密码连接、注册码连接、设备发现、同步关系和消息中继。
- 单一关系模型：`Pixel → OPPO` 会同时显示为 Pixel 的“推送到 OPPO”和 OPPO 的“接收来自 Pixel”。
- 密文最小化：服务器把客户端生成的 `envelope` 当作不透明 JSON；设备确认后立即清空密文，未确认消息按 TTL 删除。

> Android 客户端已经实现隐藏高级设置、客户端加密、离线待发与实时长轮询。服务器只中继客户端产生的不透明密文，不会替客户端加密或接触短信明文。

## 本机运行

需要 Node.js 22.13 或更高版本。

```bash
npm install
ADMIN_USERNAME=admin \
ADMIN_PASSWORD='至少十二位的随机密码' \
ALLOW_INSECURE_HTTP=1 \
PUBLIC_BASE_URL=http://127.0.0.1:8787 \
npm start
```

访问 `http://127.0.0.1:8787`。默认情况下，远程环境中的用户名、密码、注册码和设备令牌必须通过 HTTPS。只有在应用仍绑定 loopback，且 HTTP 入口明确只绑定 Tailscale IP 等加密私网时，才可审慎设置 `ALLOW_INSECURE_HTTP=1`；不得与公网 HTTP 监听器组合使用。

## Docker + 自动 HTTPS

1. 将域名的 A/AAAA 记录指向服务器，并开放 80/443。
2. 在 `server/` 创建 `.env`：

```dotenv
SYNC_DOMAIN=sms.example.com
ADMIN_USERNAME=admin
ADMIN_PASSWORD=一段足够长的随机管理员密码
MESSAGE_TTL_HOURS=168
```

3. 启动：

```bash
docker compose up -d --build
```

Caddy 会为公开域名申请和续期 HTTPS 证书，Node 服务只在 Docker 内部监听 HTTP。

## Tailscale-only 部署

`deploy/capsule-sms-sync.service` 用于把应用绑定在 `127.0.0.1`，再由 Tailscale Serve 提供仅 Tailnet 可访问的 HTTPS。生产环境不要同时启动上面的公网 Caddy Compose。

示例环境文件 `/etc/capsule-sms-sync.env`：

```dotenv
HOST=127.0.0.1
PORT=8787
DATABASE_PATH=/var/lib/capsule-sms-sync/capsule-sms.sqlite
PUBLIC_BASE_URL=https://server-name.example-tailnet.ts.net:8444
ADMIN_USERNAME=admin
ADMIN_PASSWORD=一段足够长的随机管理员密码
SESSION_TTL_HOURS=12
MESSAGE_TTL_HOURS=168
ALLOW_INSECURE_HTTP=0
TRUST_PROXY=1
```

应用启动并通过本机健康检查后，再添加 Tailscale Serve 入口：

```bash
tailscale serve --bg --https=8444 --yes http://127.0.0.1:8787
```

验收时同时检查 `tailscale serve status` 和 `ss -lntp`：应用端口必须只出现在 `127.0.0.1:8787`，不得出现在 `0.0.0.0:8787` 或服务器公网 IP。

## 手机接入流程

### 用户名和密码

`POST /api/v1/connect`

```json
{
  "username": "yuri",
  "password": "user password",
  "deviceName": "Pixel 10",
  "platform": "android-17",
  "publicKey": "base64 encoded client public key"
}
```

### 二维码或手动注册码

用户登录网页后点击“添加设备”。二维码内容格式为：

```text
capsulesms://enroll?server=https%3A%2F%2Fsms.example.com&code=ABCD-EFGH
```

手机也可以把 `enrollmentCode`、`deviceName`、`platform` 和 `publicKey` 提交到同一个 `/api/v1/connect`。注册码十分钟过期且只能使用一次。

成功后服务器只返回一次 `deviceToken`。客户端应存入 Android Keystore 保护的本地存储，后续请求使用：

```http
Authorization: Bearer <deviceToken>
```

## API 摘要

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET` | `/healthz` | 健康检查 |
| `POST` | `/api/v1/connect` | 密码或注册码连接新设备 |
| `GET` | `/api/v1/devices` | 设备列表及本机推送/接收关系 |
| `PUT` | `/api/v1/sync-routes` | 原子更新本机 `pushTo` 和 `receiveFrom` |
| `POST` | `/api/v1/devices/self/revoke` | 本机断开时撤销自己的令牌和同步关系 |
| `POST` | `/api/v1/messages` | 将一个加密信封扇出到已选择目标 |
| `GET` | `/api/v1/messages?after=0` | 按游标拉取本设备的待收消息 |
| `GET` | `/api/v1/messages/wait?timeout=25` | 等待待收消息，新增投递时立即唤醒 |
| `POST` | `/api/v1/messages/:id/ack` | 确认并清空服务器密文 |

消息上传示例：

```json
{
  "eventId": "device-id:random-event-id",
  "envelope": {
    "version": 1,
    "algorithm": "由客户端协议定义",
    "ciphertext": "base64 ciphertext"
  }
}
```

服务器限制单个加密信封为 64 KiB。同一 `eventId + targetDeviceId` 只投递一次。

## 安全边界

- 数据库中的密码使用带随机盐的 scrypt 派生值保存。
- 网页会话和设备令牌只保存 SHA-256 摘要，原始令牌只在签发时返回。
- 管理表单带 CSRF token；会话 Cookie 为 HttpOnly、SameSite=Strict，HTTPS 环境使用 Secure。
- 路由只能连接同一用户下的设备。
- 服务器仍可观察账号、设备、连接时间、来源/目标关系和密文大小。即使使用端到端加密，也应在隐私说明中披露这些元数据。
- 秒级唤醒由认证长轮询完成，不依赖 FCM。Android 客户端使用低优先级 `remoteMessaging` 前台服务保持连接，并以系统定时任务兜底。

## 测试

```bash
npm test
```

测试覆盖设备注册、双端关系自动呈现、密文投递/确认、长轮询即时唤醒、一次性注册码及跨账号隔离。
