'use strict'

/**
 * 纸盒 · 自动更新服务
 *
 * 一个单端口 Node HTTP 服务，干三件事：
 *   1. 给 App 一个固定接口：GET /api/v1/update
 *   2. 托管/分发 APK，并记录每一次下载
 *   3. 给作者一个后台：发布版本、看检查与下载数据
 *
 * 数据存在 WorkBuddy 云数据库（PostgreSQL，见 clouddb.js）；APK 文件仍在
 * 站点自己的磁盘上 —— 云 Storage 只对登录用户开放、下载链接最长 1 小时，
 * 撑不起「公开下载页 + App 直接下载」这个场景。
 */

const http = require('http')
const fs = require('fs')
const path = require('path')
const crypto = require('crypto')
const { Transform } = require('stream')
const { pipeline } = require('stream/promises')

const cloud = require('./clouddb')

const PORT = Number(process.env.PORT || 3000)
const PUBLIC_DIR = path.join(__dirname, 'public')
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, 'data')
const APK_DIR = path.join(DATA_DIR, 'apk')
const DEFAULT_PACKAGE = 'com.aiphone.assistant'
const MAX_APK = 300 * 1024 * 1024
const SESSION_DAYS = 7

/** 只允许这两个渠道，避免任意字符串进统计表 */
const CHANNELS = new Set(['stable', 'beta'])

// ─────────────────────────────────────────────────────────── 小工具

/**
 * 每个响应都带上的安全头。
 *
 * 之前一个都没加，黑盒扫出来是实打实的：页面能被任意站点 iframe 嵌（点击劫持），
 * 带 sharecode 的 URL 会经 Referer 漏给外链。
 * CORS / `server:` / `eo-*` 那几个头是托管平台网关自己加的，这里改不了。
 */
const SECURITY_HEADERS = {
  'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
  'Referrer-Policy': 'no-referrer',
  'Strict-Transport-Security': 'max-age=31536000; includeSubDomains',
  'Permissions-Policy': 'camera=(), microphone=(), geolocation=(), payment=()',
  // style 用 'unsafe-inline'：页面里大量 style="..." 内联属性，收紧了会整页错位。
  // script 不发这个口子 —— 站点没有内联脚本，全走同源外链。
  'Content-Security-Policy':
    "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
    "img-src 'self' data:; connect-src 'self'; object-src 'none'; " +
    "base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
  'Cross-Origin-Opener-Policy': 'same-origin',
}

function json(res, code, body, extra = {}) {
  const buf = Buffer.from(JSON.stringify(body))
  res.writeHead(code, {
    ...SECURITY_HEADERS,
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': buf.length,
    'Cache-Control': 'no-store',
    ...extra,
  })
  res.end(buf)
}

function text(res, code, body, type = 'text/plain; charset=utf-8') {
  const buf = Buffer.from(body)
  res.writeHead(code, {
    ...SECURITY_HEADERS,
    'Content-Type': type,
    'Content-Length': buf.length,
  })
  res.end(buf)
}

function sha256(s) {
  return crypto.createHash('sha256').update(s).digest('hex')
}

function int(v, fallback = 0) {
  const n = parseInt(v, 10)
  return Number.isFinite(n) ? n : fallback
}

/**
 * 对外的公开地址。
 *
 * 不能直接信 `x-forwarded-host` —— 平台转发过来的是沙箱内网地址
 * （形如 `3000-<id>.e2b.bj7.sandbox.cloudstudio.club`），客户端拿到它根本连不上。
 * 本地开发按 localhost 走，线上统一用这个公开域名。
 */
const PUBLIC_BASE = process.env.PUBLIC_BASE_URL || 'https://paperbox-update.app.workbuddy.host'

function baseUrl(req) {
  const host = String(req.headers['x-forwarded-host'] || req.headers.host || '')
    .split(',')[0]
    .trim()
  if (/^(localhost|127\.0\.0\.1|\[::1\])(:\d+)?$/.test(host)) {
    return `http://${host}`
  }
  if (!host || host.includes('.sandbox.') || /^\d+-/.test(host) || host.endsWith('.local')) {
    return PUBLIC_BASE.replace(/\/+$/, '')
  }
  const proto = (req.headers['x-forwarded-proto'] || 'http').split(',')[0].trim()
  return `${proto}://${host}`
}

/**
 * 托管平台给分享链接加了访问码（`?sharecode=xxx`），**所有**请求都得带上它，
 * 包括 css/js/apk。但平台是在网关层校验完就把这个参数剥掉了，后端拿不到，
 * 所以要用 SHARE_CODE 兜底 —— 值和分享链接里的那个必须一致。
 */
const SHARE_CODE = process.env.SHARE_CODE || '3NBIVEHROW'

function shareQuery(req) {
  let code = ''
  try {
    code = new URL(req.url, 'http://x').searchParams.get('sharecode') || ''
  } catch {}
  if (!code) code = SHARE_CODE
  return code ? `?sharecode=${encodeURIComponent(code)}` : ''
}

function clientIp(req) {
  const xff = req.headers['x-forwarded-for']
  if (xff) return String(xff).split(',')[0].trim()
  return req.socket.remoteAddress || ''
}

function isHttps(req) {
  return String(req.headers['x-forwarded-proto'] || '').split(',')[0].trim() === 'https'
}

/** 设备号只落哈希：能算「多少台不同的机器」，但反推不出原值 */
function deviceHash(raw) {
  if (!raw) return null
  return sha256(`paperbox:${String(raw).slice(0, 128)}`).slice(0, 16)
}

function clip(s, n) {
  if (!s) return ''
  const str = String(s)
  return str.length > n ? `${str.slice(0, n)}…` : str
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/** 极简限流：只为防统计被灌水，不做通用网关 */
const buckets = new Map()
function rateLimit(key, limit, windowMs) {
  const now = Date.now()
  const hit = buckets.get(key)
  if (!hit || now - hit.start > windowMs) {
    buckets.set(key, { start: now, count: 1 })
    return true
  }
  hit.count += 1
  return hit.count <= limit
}
setInterval(() => {
  const now = Date.now()
  for (const [k, v] of buckets) if (now - v.start > 10 * 60e3) buckets.delete(k)
}, 5 * 60e3).unref()

// ─────────────────────────────────────────────────────────── 登录防爆破

/**
 * 按 IP 记失败次数：连续 5 次锁 15 分钟，且失败次数越多响应越慢。
 *
 * 之前只有「同一 IP 每 5 分钟 20 次」的粗限流，5 次错密码毫无感觉 ——
 * 口令要是 6 位数字，在线跑一遍只是时间问题。PBKDF2 的 0.8 秒是唯一阻力，
 * 那不该是唯一防线。
 */
const LOGIN_MAX_FAILS = 5
const LOGIN_LOCK_MS = 15 * 60e3
const loginFailures = new Map()

function loginLockRemaining(ip) {
  const rec = loginFailures.get(ip)
  if (!rec || !rec.lockedUntil) return 0
  const left = rec.lockedUntil - Date.now()
  if (left <= 0) {
    loginFailures.delete(ip)
    return 0
  }
  return left
}

function noteLoginFailure(ip) {
  const now = Date.now()
  let rec = loginFailures.get(ip)
  if (!rec || now - rec.firstAt > LOGIN_LOCK_MS * 4) {
    rec = { count: 0, firstAt: now, lockedUntil: 0 }
  }
  rec.count += 1
  if (rec.count >= LOGIN_MAX_FAILS) rec.lockedUntil = now + LOGIN_LOCK_MS
  loginFailures.set(ip, rec)
  return rec.count
}

function clearLoginFailures(ip) {
  loginFailures.delete(ip)
}

setInterval(() => {
  const now = Date.now()
  for (const [ip, rec] of loginFailures) {
    const until = rec.lockedUntil || rec.firstAt
    if (until < now - LOGIN_LOCK_MS) loginFailures.delete(ip)
  }
}, 10 * 60e3).unref()

// ─────────────────────────────────────────────────────────── 口令与会话（云库）

async function isInitialized() {
  return Boolean(await cloud.getMeta('admin_hash'))
}

function hashPassword(password, salt) {
  return crypto.pbkdf2Sync(password, salt, 120000, 32, 'sha256').toString('hex')
}

async function setPassword(password) {
  const salt = crypto.randomBytes(16).toString('hex')
  await cloud.setMeta('admin_salt', salt)
  await cloud.setMeta('admin_hash', hashPassword(password, salt))
}

async function verifyPassword(password) {
  const salt = await cloud.getMeta('admin_salt')
  const hash = await cloud.getMeta('admin_hash')
  if (!salt || !hash) return false
  const got = Buffer.from(hashPassword(password, salt), 'hex')
  const want = Buffer.from(hash, 'hex')
  if (got.length !== want.length) return false
  return crypto.timingSafeEqual(got, want)
}

async function issueSession() {
  const token = crypto.randomBytes(32).toString('base64url')
  const exp = new Date(Date.now() + SESSION_DAYS * 86400e3)
  await cloud.createSession(sha256(token), exp.toISOString())
  return token
}

/** 会话 Cookie。HTTPS 下补 Secure（本地 http 调试时不加，否则浏览器不存） */
function sessionCookie(token, req) {
  const secure = isHttps(req) ? '; Secure' : ''
  return `pb_admin=${token}; Path=/; Max-Age=${SESSION_DAYS * 86400}; SameSite=Lax; HttpOnly${secure}`
}

function readCookie(req, name) {
  const raw = String(req.headers.cookie || '')
  for (const part of raw.split(';')) {
    const idx = part.indexOf('=')
    if (idx < 0) continue
    if (part.slice(0, idx).trim() === name) return part.slice(idx + 1).trim()
  }
  return ''
}

function tokenFrom(req) {
  // ⚠️ 顺序不能反：托管平台的网关会把自己的凭证写进 Authorization 头，
  // 那个值有几百字符，直接拿来比对永远匹配不上。Cookie 才是我们自己的。
  let token = readCookie(req, 'pb_admin')
  if (!token) token = String(req.headers['x-admin-token'] || '').trim()
  if (!token) {
    const h = req.headers.authorization || ''
    const bearer = h.startsWith('Bearer ') ? h.slice(7).trim() : ''
    // 只认长度像我们自己签发的（43 字符 base64url）
    if (bearer && bearer.length <= 128) token = bearer
  }
  return token
}

async function authed(req) {
  const token = tokenFrom(req)
  if (!token) return false
  const hashed = sha256(token)
  const row = await cloud.getSession(hashed)
  if (!row) return false
  if (new Date(row.expires_at).getTime() < Date.now()) {
    await cloud.deleteSession(hashed)
    return false
  }
  return true
}

// ─────────────────────────────────────────────────────────── 版本查询

function publicVersion(row, req) {
  if (!row) return null
  const share = shareQuery(req)
  return {
    id: row.id,
    versionCode: row.versionCode,
    versionName: row.versionName,
    size: row.size || 0,
    sha256: row.sha256 || '',
    changelog: row.changelog || '',
    force: row.force,
    minSupportedVersionCode: row.minSupported || 0,
    publishedAt: row.publishedAt,
    downloadUrl:
      row.apkPath || row.apkUrl ? `${baseUrl(req)}/api/v1/download/${row.id}${share}` : null,
    directUrl: row.apkPath ? `${baseUrl(req)}${row.apkPath}${share}` : row.apkUrl || null,
  }
}

// ─────────────────────────────────────────────────────────── 静态文件

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.ico': 'image/x-icon',
  '.apk': 'application/vnd.android.package-archive',
}

function sendFile(req, res, filePath, { immutable = false } = {}) {
  let stat
  try {
    stat = fs.statSync(filePath)
  } catch {
    return text(res, 404, 'Not found')
  }
  if (!stat.isFile()) return text(res, 404, 'Not found')

  const type = MIME[path.extname(filePath).toLowerCase()] || 'application/octet-stream'
  const range = req.headers.range
  const headers = {
    ...SECURITY_HEADERS,
    'Content-Type': type,
    'Accept-Ranges': 'bytes',
    'Cache-Control': immutable ? 'public, max-age=31536000, immutable' : 'no-cache',
  }

  if (range) {
    const m = /^bytes=(\d*)-(\d*)$/.exec(range)
    if (m) {
      let start = m[1] === '' ? stat.size - int(m[2]) : int(m[1])
      let end = m[1] === '' || m[2] === '' ? stat.size - 1 : int(m[2])
      start = Math.max(0, start)
      end = Math.min(end, stat.size - 1)
      if (start > end) {
        res.writeHead(416, { 'Content-Range': `bytes */${stat.size}` })
        return res.end()
      }
      res.writeHead(206, {
        ...headers,
        'Content-Range': `bytes ${start}-${end}/${stat.size}`,
        'Content-Length': end - start + 1,
      })
      return fs.createReadStream(filePath, { start, end }).pipe(res)
    }
  }

  res.writeHead(200, { ...headers, 'Content-Length': stat.size })
  fs.createReadStream(filePath).pipe(res)
}

function serveStatic(req, res, pathname) {
  const rel = pathname === '/' ? 'index.html' : pathname === '/admin' ? 'admin.html' : pathname.slice(1)
  const safe = path.basename(rel)
  const filePath = path.join(PUBLIC_DIR, safe)
  if (!filePath.startsWith(PUBLIC_DIR)) return text(res, 403, 'Forbidden')

  // HTML 里的 {{SHARE}} 在响应时替换成当前的分享码，css/js/图片的引用才能过平台校验
  if (path.extname(filePath).toLowerCase() === '.html') {
    let html
    try {
      html = fs.readFileSync(filePath, 'utf8')
    } catch {
      return text(res, 404, 'Not found')
    }
    const buf = Buffer.from(html.replace(/\{\{SHARE\}\}/g, shareQuery(req)))
    res.writeHead(200, {
      ...SECURITY_HEADERS,
      'Content-Type': 'text/html; charset=utf-8',
      'Content-Length': buf.length,
      'Cache-Control': 'no-cache',
    })
    return res.end(buf)
  }

  return sendFile(req, res, filePath)
}

// ─────────────────────────────────────────────────────────── 请求体

function readJson(req, limit = 1024 * 1024) {
  return new Promise((resolve, reject) => {
    const chunks = []
    let size = 0
    req.on('data', (chunk) => {
      size += chunk.length
      if (size > limit) {
        reject(Object.assign(new Error('请求体过大'), { status: 413 }))
        req.destroy()
        return
      }
      chunks.push(chunk)
    })
    req.on('end', () => {
      if (!chunks.length) return resolve({})
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')))
      } catch {
        reject(Object.assign(new Error('JSON 解析失败'), { status: 400 }))
      }
    })
    req.on('error', reject)
  })
}

// ─────────────────────────────────────────────────────────── 业务处理

/** App 的固定接口 */
async function handleUpdate(req, res, url) {
  const pkg = clip(url.searchParams.get('package') || DEFAULT_PACKAGE, 80)
  const channel = clip(url.searchParams.get('channel') || 'stable', 20)

  // 白名单 + 整数校验：以前随便传什么都会被当成 0 / 原样入库
  if (!CHANNELS.has(channel)) {
    return json(res, 400, { ok: false, error: 'channel 只能是 stable 或 beta' })
  }
  const rawCode = url.searchParams.get('versionCode') ?? url.searchParams.get('version_code')
  let currentCode = 0
  if (rawCode !== null && rawCode !== '') {
    if (!/^\d{1,9}$/.test(rawCode)) {
      return json(res, 400, { ok: false, error: 'versionCode 必须是非负整数' })
    }
    currentCode = parseInt(rawCode, 10)
  }

  const currentName = clip(url.searchParams.get('versionName') || url.searchParams.get('version_name') || '', 40)
  const device = deviceHash(url.searchParams.get('deviceId') || url.searchParams.get('device_id'))
  const ip = clientIp(req)

  if (!rateLimit(`update:${ip}`, 240, 60e3)) {
    return json(res, 429, { ok: false, error: '请求过于频繁，请稍后再试' })
  }

  const row = await cloud.latestVersion(pkg, channel)
  const hasUpdate = Boolean(row && row.versionCode > currentCode)
  const force =
    hasUpdate && (row.force || (row.minSupported > 0 && currentCode < row.minSupported))

  // 统计失败绝不影响客户端拿到更新信息
  try {
    await cloud.recordCheck({
      package: pkg,
      channel,
      versionCode: currentCode || null,
      versionName: currentName || null,
      device,
      hasUpdate,
      versionId: row ? row.id : null,
      ua: clip(req.headers['user-agent'], 160),
      ip,
    })
  } catch (e) {
    console.error('[update] 统计写入失败:', e.message)
  }

  return json(res, 200, {
    ok: true,
    package: pkg,
    channel,
    now: new Date().toISOString(),
    hasUpdate,
    force,
    minSupportedVersionCode: row ? row.minSupported || 0 : 0,
    current: currentCode ? { versionCode: currentCode, versionName: currentName } : null,
    latest: publicVersion(row, req),
  })
}

async function handleDownload(req, res, id) {
  const row = await cloud.getVersion(id)
  if (!row || row.isDeleted) return json(res, 404, { ok: false, error: '版本不存在' })

  const target = row.apkPath ? `${baseUrl(req)}${row.apkPath}${shareQuery(req)}` : row.apkUrl
  if (!target) {
    return json(res, 404, { ok: false, error: '这个版本还没有上传安装包' })
  }

  const ip = clientIp(req)
  if (rateLimit(`dl:${ip}`, 60, 60e3)) {
    try {
      await cloud.recordDownload({
        versionId: row.id,
        versionName: row.versionName,
        device: deviceHash(new URL(req.url, 'http://x').searchParams.get('deviceId')),
        ua: clip(req.headers['user-agent'], 160),
        ip,
      })
    } catch (e) {
      console.error('[download] 统计写入失败:', e.message)
    }
  }

  res.writeHead(302, { Location: target, 'Cache-Control': 'no-store' })
  res.end()
}

async function handlePublicVersions(req, res, url) {
  const pkg = url.searchParams.get('package') || DEFAULT_PACKAGE
  const channel = url.searchParams.get('channel') || 'stable'
  if (!CHANNELS.has(channel)) {
    return json(res, 400, { ok: false, error: 'channel 只能是 stable 或 beta' })
  }
  const rows = await cloud.listVersions(pkg, channel)
  let dlMap = new Map()
  try {
    const stats = await cloud.getStats()
    dlMap = new Map((stats.dlByVersion || []).map((r) => [Number(r.version_id), Number(r.downloads)]))
  } catch {}

  json(res, 200, {
    ok: true,
    package: pkg,
    channel,
    versions: rows.map((r) => ({
      ...publicVersion(r, req),
      downloads: dlMap.get(r.id) || 0,
      isLatest: r.isLatest,
    })),
  })
}

async function handlePublicStats(req, res) {
  const stats = await cloud.getStats()
  json(res, 200, {
    ok: true,
    downloads: Number(stats.totals.downloads || 0),
    checks: Number(stats.totals.checks || 0),
    devices: Number(stats.totals.devices || 0),
  })
}

async function handleOverview(req, res) {
  const stats = await cloud.getStats()
  const recent = await cloud.recentChecks(20)
  const today = cloud.dayKey()

  json(res, 200, {
    ok: true,
    today,
    totals: {
      checks: Number(stats.totals.checks || 0),
      downloads: Number(stats.totals.downloads || 0),
      devices: Number(stats.totals.devices || 0),
      downloadDevices: Number(stats.totals.downloadDevices || 0),
    },
    todayStats: {
      checks: Number(stats.today.checks || 0),
      downloads: Number(stats.today.downloads || 0),
    },
    series: stats.series || [],
    byVersion: (stats.byVersion || []).map((v) => ({
      version_code: v.version_code,
      version_name: v.version_name,
      checks: Number(v.checks),
      devices: Number(v.devices),
    })),
    dlByVersion: (stats.dlByVersion || []).map((v) => ({
      version_id: Number(v.version_id),
      version_name: v.version_name,
      downloads: Number(v.downloads),
    })),
    recent: recent.map((r) => ({
      at: r.at,
      version_code: r.version_code,
      version_name: r.version_name,
      has_update: r.has_update,
      device: r.device,
      ip: r.ip,
    })),
  })
}

// ─────────────────────────────────────────────────────────── 管理接口

async function handleAdmin(req, res, url, method) {
  const p = url.pathname

  if (p === '/api/v1/admin/state' && method === 'GET') {
    return json(res, 200, { ok: true, initialized: await isInitialized() })
  }

  if (p === '/api/v1/admin/setup' && method === 'POST') {
    if (await isInitialized()) return json(res, 409, { ok: false, error: '已经设置过管理口令了' })
    const body = await readJson(req)
    const password = String(body.password || '')
    if (password.length < 6) return json(res, 400, { ok: false, error: '口令至少 6 位' })
    await setPassword(password)
    const token = await issueSession()
    return json(res, 200, { ok: true, token }, { 'Set-Cookie': sessionCookie(token, req) })
  }

  if (p === '/api/v1/admin/login' && method === 'POST') {
    const ip = clientIp(req)
    const locked = loginLockRemaining(ip)
    if (locked > 0) {
      return json(
        res,
        429,
        { ok: false, error: `试错次数太多，请 ${Math.ceil(locked / 60000)} 分钟后再试` },
        { 'Retry-After': String(Math.ceil(locked / 1000)) },
      )
    }
    if (!(await isInitialized())) return json(res, 409, { ok: false, error: '还没有设置管理口令' })

    const body = await readJson(req)
    if (!(await verifyPassword(String(body.password || '')))) {
      const fails = noteLoginFailure(ip)
      // 失败越多拖得越久，把在线爆破压到没有意义的速度
      await sleep(Math.min(1500, 200 * fails))
      return json(res, 401, {
        ok: false,
        error:
          fails >= LOGIN_MAX_FAILS
            ? '口令错误次数过多，已锁定 15 分钟'
            : `口令不对（再错 ${LOGIN_MAX_FAILS - fails} 次将锁定 15 分钟）`,
      })
    }

    clearLoginFailures(ip)
    const token = await issueSession()
    return json(res, 200, { ok: true, token }, { 'Set-Cookie': sessionCookie(token, req) })
  }

  // 以下全部要登录
  if (!(await authed(req))) return json(res, 401, { ok: false, error: '未登录或登录已过期' })

  if (p === '/api/v1/admin/logout' && method === 'POST') {
    const token = tokenFrom(req)
    if (token) await cloud.deleteSession(sha256(token))
    return json(
      res,
      200,
      { ok: true },
      { 'Set-Cookie': 'pb_admin=; Path=/; Max-Age=0; SameSite=Lax' },
    )
  }

  if (p === '/api/v1/admin/password' && method === 'POST') {
    const body = await readJson(req)
    const password = String(body.password || '')
    if (password.length < 6) return json(res, 400, { ok: false, error: '口令至少 6 位' })
    await setPassword(password)
    await cloud.clearSessions()
    const token = await issueSession()
    return json(res, 200, { ok: true, token }, { 'Set-Cookie': sessionCookie(token, req) })
  }

  if (p === '/api/v1/admin/overview' && method === 'GET') {
    return handleOverview(req, res)
  }

  if (p === '/api/v1/admin/versions' && method === 'GET') {
    const rows = await cloud.listAllVersions()
    let dlMap = new Map()
    try {
      const stats = await cloud.getStats()
      dlMap = new Map((stats.dlByVersion || []).map((r) => [Number(r.version_id), Number(r.downloads)]))
    } catch {}
    return json(res, 200, {
      ok: true,
      versions: rows.map((r) => ({
        ...publicVersion(r, req),
        apkPath: r.apkPath,
        apkUrl: r.apkUrl,
        channel: r.channel,
        package: r.package,
        isLatest: r.isLatest,
        downloads: dlMap.get(r.id) || 0,
        hasApk: Boolean(r.apkPath || r.apkUrl),
      })),
    })
  }

  if (p === '/api/v1/admin/versions' && method === 'POST') {
    const body = await readJson(req)
    const versionName = clip(String(body.versionName || '').trim(), 40)
    const versionCode = int(body.versionCode, 0)
    const channel = clip(String(body.channel || 'stable').trim(), 20) || 'stable'
    const pkg = clip(String(body.package || DEFAULT_PACKAGE).trim(), 80) || DEFAULT_PACKAGE
    if (!versionName) return json(res, 400, { ok: false, error: '版本名不能为空' })
    if (versionCode <= 0) return json(res, 400, { ok: false, error: 'versionCode 必须是正整数' })
    if (!CHANNELS.has(channel)) {
      return json(res, 400, { ok: false, error: 'channel 只能是 stable 或 beta' })
    }

    try {
      const created = await cloud.createVersion({
        package: pkg,
        channel,
        versionName,
        versionCode,
        changelog: String(body.changelog || ''),
        apkPath: body.apkPath ? String(body.apkPath) : null,
        apkUrl: body.apkUrl ? String(body.apkUrl) : null,
        size: int(body.size, 0),
        sha256: clip(String(body.sha256 || ''), 64),
        force: Boolean(body.force),
        minSupported: int(body.minSupportedVersionCode, 0),
      })
      return json(res, 200, { ok: true, id: created.id })
    } catch (e) {
      if (e.code === '23505' || /duplicate key/i.test(e.message)) {
        return json(res, 409, { ok: false, error: `versionCode ${versionCode} 已经发布过了` })
      }
      throw e
    }
  }

  const patchMatch = /^\/api\/v1\/admin\/versions\/(\d+)$/.exec(p)
  if (patchMatch && (method === 'PATCH' || method === 'DELETE')) {
    const id = int(patchMatch[1])
    const row = await cloud.getVersion(id)
    if (!row || row.isDeleted) return json(res, 404, { ok: false, error: '版本不存在' })

    if (method === 'DELETE') {
      await cloud.softDeleteVersion(id)
      if (row.isLatest) {
        const rest = await cloud.listVersions(row.package, row.channel, 1)
        const next = rest.find((v) => v.id !== id)
        if (next) await cloud.setLatest(next.id, row.package, row.channel)
      }
      return json(res, 200, { ok: true })
    }

    const body = await readJson(req)
    const patch = {}
    if (body.changelog !== undefined) patch.changelog = String(body.changelog)
    if (body.force !== undefined) patch.force = Boolean(body.force)
    if (body.minSupportedVersionCode !== undefined) {
      patch.minSupported = int(body.minSupportedVersionCode)
    }
    if (body.apkPath !== undefined) patch.apkPath = body.apkPath === null ? null : String(body.apkPath)
    if (body.apkUrl !== undefined) patch.apkUrl = body.apkUrl === null ? null : String(body.apkUrl)
    if (body.size !== undefined) patch.size = int(body.size)
    if (body.sha256 !== undefined) patch.sha256 = String(body.sha256)
    await cloud.patchVersion(id, patch)

    if (body.setLatest) {
      await cloud.setLatest(id, row.package, row.channel)
    }
    return json(res, 200, { ok: true })
  }

  if (p === '/api/v1/admin/backup' && method === 'GET') {
    const payload = {
      exportedAt: new Date().toISOString(),
      versions: await cloud.listAllVersions(),
      checks: await cloud.recentChecks(5000),
      downloads: await cloud.recentDownloads(5000),
    }
    const buf = Buffer.from(JSON.stringify(payload, null, 2))
    res.writeHead(200, {
      ...SECURITY_HEADERS,
      'Content-Type': 'application/json; charset=utf-8',
      'Content-Disposition': `attachment; filename="paperbox-backup-${cloud.dayKey()}.json"`,
      'Content-Length': buf.length,
    })
    return res.end(buf)
  }

  return json(res, 404, { ok: false, error: '接口不存在' })
}

async function handleUpload(req, res) {
  if (!(await authed(req))) return json(res, 401, { ok: false, error: '未登录或登录已过期' })
  const raw = decodeURIComponent(String(req.headers['x-filename'] || 'app.apk'))
  const name = raw.replace(/[^\w.\-]+/g, '_').replace(/^_+|_+$/g, '').slice(-60) || 'app.apk'
  if (!/\.apk$/i.test(name)) return json(res, 400, { ok: false, error: '只接受 .apk 文件' })

  const len = int(req.headers['content-length'], 0)
  if (len > MAX_APK) return json(res, 413, { ok: false, error: '文件超过 300MB' })

  fs.mkdirSync(APK_DIR, { recursive: true })
  const stored = `${crypto.randomBytes(6).toString('hex')}-${name}`
  const target = path.join(APK_DIR, stored)

  let size = 0
  const hash = crypto.createHash('sha256')
  const tap = new Transform({
    transform(chunk, _enc, cb) {
      size += chunk.length
      if (size > MAX_APK) return cb(new Error('文件超过 300MB'))
      hash.update(chunk)
      cb(null, chunk)
    },
  })

  try {
    await pipeline(req, tap, fs.createWriteStream(target))
  } catch (e) {
    try {
      fs.unlinkSync(target)
    } catch {}
    return json(res, 400, { ok: false, error: e.message || '上传失败' })
  }

  return json(res, 200, {
    ok: true,
    path: `/files/${stored}`,
    filename: name,
    size,
    sha256: hash.digest('hex'),
  })
}

// ─────────────────────────────────────────────────────────── 预置数据

/** 云库空的时候补一条当前版本，免得下载页空着 */
async function seed() {
  try {
    const n = await cloud.countVersions()
    if (n > 0) return

    let apkPath = null
    let size = 0
    try {
      const files = fs
        .readdirSync(APK_DIR)
        .filter((f) => f.toLowerCase().endsWith('.apk'))
        .map((f) => ({ f, t: fs.statSync(path.join(APK_DIR, f)).mtimeMs }))
        .sort((a, b) => b.t - a.t)
      if (files.length) {
        apkPath = `/files/${files[0].f}`
        size = fs.statSync(path.join(APK_DIR, files[0].f)).size
      }
    } catch {}

    await cloud.createVersion({
      package: DEFAULT_PACKAGE,
      channel: 'stable',
      versionName: '0.10.7',
      versionCode: 74,
      changelog: '新增自动更新：打开应用时查一次自己的更新站，有新版本直接提示、下载、安装。',
      apkPath,
      size,
    })
    console.log('[seed] 云库为空，已补一条 0.10.7 (74)')
  } catch (e) {
    console.error('[seed] 跳过:', e.message)
  }
}

// ─────────────────────────────────────────────────────────── 服务器

const server = http.createServer(async (req, res) => {
  const method = (req.method || 'GET').toUpperCase()
  let url
  try {
    url = new URL(req.url, `http://${req.headers.host || 'localhost'}`)
  } catch {
    return json(res, 400, { ok: false, error: '请求地址不合法' })
  }
  const p = url.pathname

  try {
    if (method === 'OPTIONS') {
      res.writeHead(204, {
        ...SECURITY_HEADERS,
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Methods': 'GET,POST,PATCH,DELETE,OPTIONS',
        'Access-Control-Allow-Headers': 'Authorization,Content-Type,X-Filename',
      })
      return res.end()
    }

    if (p === '/api/v1/update' && method === 'GET') return await handleUpdate(req, res, url)
    if (p === '/api/v1/versions' && method === 'GET') return await handlePublicVersions(req, res, url)
    if (p === '/api/v1/stats' && method === 'GET') return await handlePublicStats(req, res)
    if (p === '/api/v1/health') return json(res, 200, { ok: true })

    const dl = /^\/api\/v1\/download\/(\d+)$/.exec(p)
    if (dl && method === 'GET') return await handleDownload(req, res, int(dl[1]))

    if (p === '/api/v1/admin/upload' && method === 'POST') return await handleUpload(req, res)
    if (p.startsWith('/api/v1/admin/')) return await handleAdmin(req, res, url, method)

    if (p.startsWith('/files/')) {
      const safe = path.basename(p.slice('/files/'.length))
      const filePath = path.join(APK_DIR, safe)
      if (!filePath.startsWith(APK_DIR)) return text(res, 403, 'Forbidden')
      return sendFile(req, res, filePath, { immutable: true })
    }

    if (p.startsWith('/api/')) return json(res, 404, { ok: false, error: '接口不存在' })

    return serveStatic(req, res, p)
  } catch (e) {
    const status = e.status || 500
    console.error(`[${method} ${p}]`, e)
    if (!res.headersSent) json(res, status, { ok: false, error: e.message || '服务器内部错误' })
    else res.end()
  }
})

server.listen(PORT, '0.0.0.0', async () => {
  console.log(`纸盒更新站已启动: http://0.0.0.0:${PORT}`)
  console.log(`数据: WorkBuddy 云数据库 ${cloud.CLOUD_ENDPOINT}`)
  console.log(`APK 目录: ${APK_DIR}`)
  if (!cloud.hasKey) console.error('⚠️ 没有云库 key，所有数据接口都会失败')
  await seed()
  cloud.purgeExpiredSessions().catch(() => {})
})
