# 纸盒 · 更新站

给纸盒 App 用的自动更新服务：**版本发布 + 固定更新接口 + 下载统计**。

Node 单端口服务，业务数据存在 **WorkBuddy 云数据库**（托管 PostgreSQL，走官方 SDK）；
APK 文件放在站点自己的磁盘上（`data/apk/`）—— 云 Storage 只对登录用户开放、
签名链接最长 1 小时，撑不起「公开下载页 + App 直接下载」。

## 线上

- 下载页 <https://paperbox-update.app.workbuddy.host/?sharecode=3NBIVEHROW>
- 后台 <https://paperbox-update.app.workbuddy.host/admin?sharecode=3NBIVEHROW>

平台的分享码 `sharecode` **所有请求都要带**（连 css、apk 都是）。网关校验完会把这个参数
剥掉，后端拿不到，所以：

- `server.js` 的 `SHARE_CODE` 必须和分享链接里的一致
- `server.js` 的 `PUBLIC_BASE` 是给客户端用的公开域名（`x-forwarded-host` 是内网地址，不能用）
- 后台会话走 **Cookie**：平台网关会覆盖 `Authorization` 头，Bearer 鉴权永远失败

## 跑起来

```bash
npm install
DATA_DIR=./.dev PORT=3203 node server.js   # 本地
```

云库 key 放在 `.env`（**不进仓库**）：

```
CLOUD_PUBLISHABLE_KEY=wbpk_...
```

⚠️ 本地跑连的是**同一个云库**（没有测试环境），测试写入会进线上统计。

## 云库表

| 表 | 用途 |
|---|---|
| `app_versions` | 版本记录：版本号、更新说明、安装包地址、sha256、是否最新/强制/已删 |
| `app_checks` | 每次检查更新一条 |
| `app_downloads` | 每次下载一条 |
| `app_sessions` | 后台会话（只存 token 的 sha256） |
| `app_meta` | 管理口令的 PBKDF2 哈希与盐 |

聚合统计走 Postgres 函数 `app_stats()`（PostgREST 不支持 GROUP BY）。

RLS 对 `anon` 开放读写 —— 读写都由 Node 进程发起，key 只在服务端。
**不要把 key 挪到前端**，否则任何人都能改版本记录、把下载地址指向别的包。

## 接口

### `GET /api/v1/update`

| 参数 | 说明 |
|---|---|
| `package` | 默认 `com.aiphone.assistant` |
| `channel` | `stable`（默认）/ `beta`，其他值返回 400 |
| `versionCode` | 当前客户端版本号；非整数返回 400 |
| `versionName` / `deviceId` | 只用于统计，`deviceId` 只存哈希 |

返回 `hasUpdate`、`force`、`latest.{versionCode,versionName,size,sha256,changelog,downloadUrl}`。
`downloadUrl` 已带好分享码，客户端直接用。

### 其它

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/download/:id` | 记一次下载后 302 到 APK，支持 Range |
| GET | `/api/v1/versions` | 公开版本列表 |
| GET | `/api/v1/stats` | 公开总数（下载/检查/设备） |
| POST | `/api/v1/admin/login` | 换会话 Cookie |
| GET | `/api/v1/admin/overview` | 统计总览 |
| GET/POST | `/api/v1/admin/versions` | 列表 / 发布 |
| PATCH/DELETE | `/api/v1/admin/versions/:id` | 改说明、设为最新、补包、软删 |
| POST | `/api/v1/admin/upload` | 上传 APK（`X-Filename` 头 + 裸二进制） |
| GET | `/api/v1/admin/backup` | 导出 JSON |

## 安全

- 全部响应带 `X-Content-Type-Options` / `X-Frame-Options: DENY` / HSTS /
  `Referrer-Policy: no-referrer` / CSP / `Permissions-Policy`
- 后台登录：**连续 5 次错口令锁该 IP 15 分钟**，失败次数越多响应越慢（最长 1.5 秒）
- 口令 PBKDF2-SHA256（12 万轮）加盐哈希，明文不落盘
- 会话 Cookie `HttpOnly`，HTTPS 下自动加 `Secure`
- 下载完整性靠 `sha256`；**签名一致性由 Android 安装器保证**（不同签名的同名包装不上）

CORS / `server:` / `eo-*` / `/healthz` 这些是托管平台网关加的，应用层改不了。
