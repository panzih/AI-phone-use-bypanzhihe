'use strict'

/**
 * 数据层：WorkBuddy 云数据库（托管 PostgreSQL，走官方 SDK 的 PostgREST 客户端）。
 *
 * 表结构见 README「云库表结构」，聚合统计走 `app_stats()` 这个 Postgres 函数，
 * 一次调用返回后台需要的全部数字（PostgREST 本身不支持 GROUP BY）。
 *
 * ⚠️ publishableKey 从 `.env` 或环境变量读，**不写死在代码里**：
 * 仓库是公开的，key 泄露等于把「改版本记录 / 把下载地址指向别的包」的权限公开。
 */

const fs = require('fs')
const path = require('path')
const { createWorkBuddyCloud } = require('@tencent-ai/workbuddy-cloud-sdk')

/** 极简 .env 解析：只有几行，不值得为它引 dotenv */
function loadEnvFile() {
  try {
    const text = fs.readFileSync(path.join(__dirname, '.env'), 'utf8')
    for (const line of text.split('\n')) {
      const m = /^\s*([A-Z0-9_]+)\s*=\s*(.*)$/.exec(line.trim())
      if (m && !process.env[m[1]]) process.env[m[1]] = m[2].trim()
    }
  } catch {
    // 没有 .env 就走环境变量
  }
}
loadEnvFile()

const CLOUD_ENDPOINT = process.env.CLOUD_ENDPOINT || 'https://paperbox-update.app.workbuddy.host'
const CLOUD_KEY = process.env.CLOUD_PUBLISHABLE_KEY || ''

if (!CLOUD_KEY) {
  console.error('[clouddb] 缺少 CLOUD_PUBLISHABLE_KEY —— 检查 update-site/.env 是否存在')
}

const cloud = createWorkBuddyCloud({ endpoint: CLOUD_ENDPOINT, publishableKey: CLOUD_KEY })
const db = cloud.database

const TZ = process.env.SITE_TZ || 'Asia/Shanghai'
const dayFmt = new Intl.DateTimeFormat('sv-SE', {
  timeZone: TZ,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
})

function dayKey(date = new Date()) {
  return dayFmt.format(date)
}

/** 把 `{ data, error }` 拆成「数据或抛错」，错误里带上 Postgres 代码方便定位 */
function unwrap(res, what = '云数据库操作') {
  if (res && res.error) {
    const detail = res.error.message || res.error.details || JSON.stringify(res.error)
    const err = new Error(`${what}失败：${detail}`)
    err.code = res.error.code
    throw err
  }
  return res ? res.data : null
}

/** 行 → 业务对象（数据库是 snake_case，代码里统一 camelCase） */
function mapVersion(r) {
  if (!r) return null
  return {
    id: Number(r.id),
    package: r.package,
    channel: r.channel,
    versionName: r.version_name,
    versionCode: r.version_code,
    changelog: r.changelog || '',
    apkPath: r.apk_path,
    apkUrl: r.apk_url,
    size: Number(r.size || 0),
    sha256: r.sha256 || '',
    force: Boolean(r.force_update),
    minSupported: r.min_supported || 0,
    isLatest: Boolean(r.is_latest),
    isDeleted: Boolean(r.is_deleted),
    publishedAt: r.published_at,
  }
}

// ───────────────────────────────────────────── 版本

async function latestVersion(pkg, channel) {
  const base = () =>
    db
      .from('app_versions')
      .select('*')
      .eq('package', pkg)
      .eq('channel', channel)
      .eq('is_deleted', false)

  let rows = unwrap(
    await base().eq('is_latest', true).order('version_code', { ascending: false }).limit(1)
  )
  if (!rows || !rows.length) {
    // 兜底：is_latest 标记乱了也要能返回最大版本
    rows = unwrap(await base().order('version_code', { ascending: false }).limit(1))
  }
  return mapVersion(rows && rows[0])
}

async function listVersions(pkg, channel, limit = 30) {
  const rows = unwrap(
    await db
      .from('app_versions')
      .select('*')
      .eq('package', pkg)
      .eq('channel', channel)
      .eq('is_deleted', false)
      .order('version_code', { ascending: false })
      .limit(limit)
  )
  return (rows || []).map(mapVersion)
}

async function listAllVersions() {
  const rows = unwrap(
    await db.from('app_versions').select('*').eq('is_deleted', false).order('version_code', {
      ascending: false,
    })
  )
  return (rows || []).map(mapVersion)
}

async function getVersion(id) {
  const rows = unwrap(await db.from('app_versions').select('*').eq('id', id).limit(1))
  return mapVersion(rows && rows[0])
}

async function createVersion(v) {
  // 先把同类目的「最新」摘掉，避免出现两个 latest
  unwrap(
    await db
      .from('app_versions')
      .update({ is_latest: false })
      .eq('package', v.package)
      .eq('channel', v.channel)
      .eq('is_latest', true)
  )

  const rows = unwrap(
    await db
      .from('app_versions')
      .insert({
        package: v.package,
        channel: v.channel,
        version_name: v.versionName,
        version_code: v.versionCode,
        changelog: v.changelog || '',
        apk_path: v.apkPath || null,
        apk_url: v.apkUrl || null,
        size: v.size || 0,
        sha256: v.sha256 || '',
        force_update: Boolean(v.force),
        min_supported: v.minSupported || 0,
        is_latest: true,
      })
      .select(),
    '发布版本'
  )
  return mapVersion(rows && rows[0])
}

async function patchVersion(id, patch) {
  const row = {}
  if (patch.changelog !== undefined) row.changelog = String(patch.changelog)
  if (patch.force !== undefined) row.force_update = Boolean(patch.force)
  if (patch.minSupported !== undefined) row.min_supported = Number(patch.minSupported) || 0
  if (patch.apkPath !== undefined) row.apk_path = patch.apkPath
  if (patch.apkUrl !== undefined) row.apk_url = patch.apkUrl
  if (patch.size !== undefined) row.size = Number(patch.size) || 0
  if (patch.sha256 !== undefined) row.sha256 = String(patch.sha256)

  if (Object.keys(row).length) {
    unwrap(await db.from('app_versions').update(row).eq('id', id), '更新版本')
  }
  return getVersion(id)
}

async function softDeleteVersion(id) {
  const rows = unwrap(
    await db
      .from('app_versions')
      .update({ is_deleted: true, is_latest: false })
      .eq('id', id)
      .select(),
    '删除版本'
  )
  return mapVersion(rows && rows[0])
}

async function setLatest(id, pkg, channel) {
  unwrap(
    await db
      .from('app_versions')
      .update({ is_latest: false })
      .eq('package', pkg)
      .eq('channel', channel)
      .eq('is_latest', true),
    '切换最新版'
  )
  unwrap(await db.from('app_versions').update({ is_latest: true }).eq('id', id), '切换最新版')
}

// ───────────────────────────────────────────── 统计写入

async function recordCheck(c) {
  return unwrap(
    await db.from('app_checks').insert({
      package: c.package,
      channel: c.channel,
      version_code: c.versionCode || null,
      version_name: c.versionName || null,
      device: c.device || null,
      has_update: Boolean(c.hasUpdate),
      version_id: c.versionId || null,
      ua: c.ua || null,
      ip: c.ip || null,
    }),
    '记录检查'
  )
}

async function recordDownload(d) {
  return unwrap(
    await db.from('app_downloads').insert({
      version_id: d.versionId,
      version_name: d.versionName || null,
      device: d.device || null,
      ua: d.ua || null,
      ip: d.ip || null,
    }),
    '记录下载'
  )
}

// ───────────────────────────────────────────── 统计读取

async function getStats() {
  const data = unwrap(await db.rpc('app_stats'), '读取统计')
  // 函数返回 jsonb，SDK 直接给对象；个别版本会包一层数组
  const obj = Array.isArray(data) ? data[0] : data
  return obj || { totals: {}, today: {}, series: [], byVersion: [], dlByVersion: [] }
}

async function recentChecks(limit = 20) {
  const rows = unwrap(
    await db
      .from('app_checks')
      .select('at,version_code,version_name,has_update,device,ip')
      .order('id', { ascending: false })
      .limit(limit)
  )
  return rows || []
}

async function recentDownloads(limit = 5000) {
  const rows = unwrap(
    await db
      .from('app_downloads')
      .select('at,version_id,version_name,device')
      .order('id', { ascending: false })
      .limit(limit)
  )
  return rows || []
}

async function countVersions() {
  const res = await db.from('app_versions').select('id', { count: 'exact', head: true })
  if (res.error) return 0
  return res.count || 0
}

// ───────────────────────────────────────────── 管理口令 / 会话

async function getMeta(key) {
  const rows = unwrap(await db.from('app_meta').select('value').eq('key', key).limit(1))
  return rows && rows.length ? rows[0].value : null
}

async function setMeta(key, value) {
  unwrap(
    await db.from('app_meta').upsert({ key, value, updated_at: new Date().toISOString() }, { onConflict: 'key' }),
    '保存设置'
  )
}

async function createSession(token, expiresAt) {
  unwrap(await db.from('app_sessions').insert({ token, expires_at: expiresAt }), '建立会话')
}

async function getSession(token) {
  const rows = unwrap(
    await db.from('app_sessions').select('token,expires_at').eq('token', token).limit(1)
  )
  return rows && rows.length ? rows[0] : null
}

async function deleteSession(token) {
  unwrap(await db.from('app_sessions').delete().eq('token', token), '退出登录')
}

async function clearSessions() {
  // PostgREST 不接受无过滤条件的 delete，这里用「所有非空 token」兜住
  unwrap(await db.from('app_sessions').delete().neq('token', ''), '清理会话')
}

async function purgeExpiredSessions() {
  unwrap(await db.from('app_sessions').delete().lt('expires_at', new Date().toISOString()))
}

module.exports = {
  dayKey,
  latestVersion,
  listVersions,
  listAllVersions,
  getVersion,
  createVersion,
  patchVersion,
  softDeleteVersion,
  setLatest,
  recordCheck,
  recordDownload,
  getStats,
  recentChecks,
  recentDownloads,
  countVersions,
  getMeta,
  setMeta,
  createSession,
  getSession,
  deleteSession,
  clearSessions,
  purgeExpiredSessions,
  CLOUD_ENDPOINT,
  hasKey: Boolean(CLOUD_KEY),
}
