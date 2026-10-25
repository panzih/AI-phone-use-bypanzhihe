'use strict'

/* 更新后台：登录 → 概览 → 发布 → 版本管理。原生 JS，无框架。 */

const $ = (id) => document.getElementById(id)

/* 托管平台给分享链接加了访问码，页面上所有请求都得带上 */
function shareCode() {
  try {
    const fromUrl = new URLSearchParams(location.search).get('sharecode')
    if (fromUrl) {
      localStorage.setItem('paperbox_share', fromUrl)
      return fromUrl
    }
    return localStorage.getItem('paperbox_share') || ''
  } catch {
    return ''
  }
}

function withShare(url) {
  const code = shareCode()
  if (!code) return url
  return `${url}${url.includes('?') ? '&' : '?'}sharecode=${encodeURIComponent(code)}`
}
const TOKEN_KEY = 'paperbox_admin_token'

let token = localStorage.getItem(TOKEN_KEY) || ''
let initialized = false
let versions = []
let latestCode = 73
let pickTarget = null

// ───────────────────────────────────────────── 基础

function toast(msg) {
  const node = $('toast')
  node.textContent = msg
  node.classList.add('show')
  clearTimeout(toast._t)
  toast._t = setTimeout(() => node.classList.remove('show'), 2200)
}

function alertBox(node, msg, kind = '') {
  node.className = `alert ${kind}`.trim()
  node.textContent = msg
  node.classList.remove('hidden')
}

function formatBytes(n) {
  if (!n) return '—'
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}

function formatDate(iso, withTime = false) {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '—'
  const p = (n) => String(n).padStart(2, '0')
  const base = `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
  return withTime ? `${base} ${p(d.getHours())}:${p(d.getMinutes())}` : base
}

async function api(method, url, body, { auth = true } = {}) {
  const headers = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const res = await fetch(withShare(url), {
    method,
    headers,
    credentials: 'same-origin',
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  let data = {}
  try {
    data = await res.json()
  } catch {}
  if (!res.ok) {
    const err = new Error(data.error || `请求失败（${res.status}）`)
    err.status = res.status
    throw err
  }
  return data
}

// ───────────────────────────────────────────── 登录

function showLogin() {
  $('loginView').classList.remove('hidden')
  $('appView').classList.add('hidden')
  $('loginSub').textContent = initialized ? '输入管理口令' : '第一次使用，先设一个管理口令'
  $('pwdLabel').textContent = initialized ? '管理口令' : '设置管理口令'
  $('pwd2Field').classList.toggle('hidden', initialized)
  $('loginBtn').textContent = initialized ? '进入后台' : '设置口令并进入'
  setTimeout(() => $('pwd').focus(), 50)
}

async function submitLogin() {
  const pwd = $('pwd').value
  const alertNode = $('loginAlert')
  alertNode.classList.add('hidden')
  if (pwd.length < 6) return alertBox(alertNode, '口令至少 6 位')

  $('loginBtn').disabled = true
  try {
    if (!initialized) {
      if (pwd !== $('pwd2').value) return alertBox(alertNode, '两次输入不一致')
      const data = await api('POST', '/api/v1/admin/setup', { password: pwd }, { auth: false })
      token = data.token
      initialized = true
    } else {
      const data = await api('POST', '/api/v1/admin/login', { password: pwd }, { auth: false })
      token = data.token
    }
    localStorage.setItem(TOKEN_KEY, token)
    $('pwd').value = ''
    $('pwd2').value = ''
    await enterApp()
  } catch (e) {
    alertBox(alertNode, e.message)
  } finally {
    $('loginBtn').disabled = false
  }
}

async function enterApp() {
  $('loginView').classList.add('hidden')
  $('appView').classList.remove('hidden')
  await loadAll()
}

// ───────────────────────────────────────────── 数据

async function loadAll() {
  try {
    const [overview, list] = await Promise.all([
      api('GET', '/api/v1/admin/overview'),
      api('GET', '/api/v1/admin/versions'),
    ])
    versions = list.versions || []
    renderOverview(overview)
    renderVersions()
    renderApi()
  } catch (e) {
    if (e.status === 401) {
      token = ''
      localStorage.removeItem(TOKEN_KEY)
      showLogin()
      alertBox($('loginAlert'), '登录已过期，重新输入口令')
      return
    }
    toast(e.message)
  }
}

function renderOverview(o) {
  $('stDownloads').textContent = o.totals.downloads.toLocaleString()
  $('stDownloadsToday').textContent = `今日 ${o.todayStats.downloads}`
  $('stChecks').textContent = o.totals.checks.toLocaleString()
  $('stChecksToday').textContent = `今日 ${o.todayStats.checks}`
  $('stDevices').textContent = o.totals.devices.toLocaleString()
  $('stDevicesHint').textContent = o.totals.downloadDevices
    ? `${o.totals.downloadDevices} 台下载过`
    : '按设备号去重'

  const latest = versions.find((v) => v.isLatest)
  $('stLatest').textContent = latest ? latest.versionName : '—'
  $('stLatestHint').textContent = latest
    ? `versionCode ${latest.versionCode} · ${formatDate(latest.publishedAt)}`
    : '还没有版本'

  renderChart(o.series || [])
  renderVersionBars(o.byVersion || [])
  renderRecent(o.recent || [])
}

function niceCeil(n) {
  if (n <= 4) return 4
  const pow = Math.pow(10, Math.floor(Math.log10(n)))
  const base = n / pow
  const step = base <= 1 ? 1 : base <= 2 ? 2 : base <= 5 ? 5 : 10
  return step * pow
}

function renderChart(series) {
  const host = $('chart')
  if (!series.length) {
    host.innerHTML = '<p class="muted" style="font-size:12.5px;">暂无数据</p>'
    return
  }

  const W = 900
  const H = 250
  const padL = 36
  const padR = 8
  const padT = 12
  const padB = 26
  const innerW = W - padL - padR
  const innerH = H - padT - padB
  const n = series.length

  const maxV = Math.max(1, ...series.map((s) => Math.max(s.checks, s.downloads)))
  const top = niceCeil(maxV)
  const y = (v) => padT + innerH - (v / top) * innerH
  const stepX = innerW / n
  const cx = (i) => padL + stepX * i + stepX / 2
  const barW = Math.max(4, Math.min(18, stepX * 0.5))

  let svg = `<svg viewBox="0 0 ${W} ${H}" role="img" aria-label="最近 30 天查询与下载趋势">`

  // 横向网格 + y 轴刻度
  for (let k = 0; k <= 4; k += 1) {
    const v = (top / 4) * k
    const yy = y(v)
    svg += `<line x1="${padL}" y1="${yy}" x2="${W - padR}" y2="${yy}" stroke="var(--line)" stroke-width="1" />`
    svg += `<text x="${padL - 8}" y="${yy + 4}" text-anchor="end" font-size="10.5" fill="var(--ink-3)">${Math.round(v)}</text>`
  }

  // 柱子：查询次数
  series.forEach((s, i) => {
    if (!s.checks) return
    const yy = y(s.checks)
    svg += `<rect x="${(cx(i) - barW / 2).toFixed(1)}" y="${yy.toFixed(1)}" width="${barW.toFixed(1)}" height="${Math.max(1, innerH - (yy - padT)).toFixed(1)}" rx="3" fill="#e8a87c" opacity="0.9"><title>${s.day} 查询 ${s.checks}</title></rect>`
  })

  // 折线：下载
  const pts = series.map((s, i) => `${cx(i).toFixed(1)},${y(s.downloads).toFixed(1)}`).join(' ')
  svg += `<polyline points="${pts}" fill="none" stroke="#c2410c" stroke-width="2" stroke-linejoin="round" stroke-linecap="round" />`
  series.forEach((s, i) => {
    if (s.downloads > 0) {
      svg += `<circle cx="${cx(i).toFixed(1)}" cy="${y(s.downloads).toFixed(1)}" r="2.6" fill="#c2410c"><title>${s.day} 下载 ${s.downloads}</title></circle>`
    }
  })

  // x 轴：每 5 天一个标签
  series.forEach((s, i) => {
    if (i % 5 !== 0 && i !== n - 1) return
    svg += `<text x="${cx(i).toFixed(1)}" y="${H - 6}" text-anchor="middle" font-size="10.5" fill="var(--ink-3)">${s.day.slice(5)}</text>`
  })

  svg += '</svg>'
  host.innerHTML = svg
}

function renderVersionBars(rows) {
  const host = $('versionBars')
  if (!rows.length) {
    host.innerHTML = '<span class="muted" style="font-size:12.5px;">还没有客户端来查过更新</span>'
    return
  }
  const max = Math.max(...rows.map((r) => r.checks), 1)
  host.innerHTML = rows
    .slice(0, 6)
    .map(
      (r) => `
      <div class="bar-row">
        <span class="mono nowrap" style="overflow:hidden;text-overflow:ellipsis;">${r.version_name || r.version_code}</span>
        <span class="bar-track"><span class="bar-fill" style="width:${Math.max(3, (r.checks / max) * 100).toFixed(1)}%"></span></span>
        <span class="bar-num">${r.checks}</span>
      </div>`
    )
    .join('')
}

function renderRecent(rows) {
  const body = $('recentBody')
  if (!rows.length) {
    body.innerHTML = '<tr><td colspan="5" class="muted">还没有数据</td></tr>'
    return
  }
  body.innerHTML = rows
    .map((r) => {
      const ver = r.version_name ? `${r.version_name} (${r.version_code})` : r.version_code || '—'
      return `<tr>
        <td class="muted nowrap">${formatDate(r.at, true)}</td>
        <td class="mono">${ver}</td>
        <td>${r.has_update ? '<span class="tag warn">有新版</span>' : '<span class="tag">最新</span>'}</td>
        <td class="mono muted">${r.device || '—'}</td>
        <td class="mono muted">${r.ip || '—'}</td>
      </tr>`
    })
    .join('')
}

function renderVersions() {
  const body = $('versionBody')
  if (!versions.length) {
    body.innerHTML = '<tr><td colspan="7" class="muted">还没有版本，先发布一个</td></tr>'
    return
  }

  body.innerHTML = versions
    .map(
      (v) => `<tr>
      <td>
        <span style="font-weight:600">${v.versionName}</span>
        ${v.force ? '<span class="tag warn" style="margin-left:6px">强制</span>' : ''}
      </td>
      <td class="num mono muted">${v.versionCode}</td>
      <td>${v.isLatest ? '<span class="tag live">线上</span>' : '<span class="tag">历史</span>'}</td>
      <td class="num muted">${formatBytes(v.size)}</td>
      <td class="num">${v.downloads || 0}</td>
      <td class="muted nowrap">${formatDate(v.publishedAt)}</td>
      <td class="nowrap" style="text-align:right">
        ${v.hasApk ? '' : `<button class="btn ghost small" data-act="upload" data-id="${v.id}">补包</button>`}
        ${v.isLatest ? '' : `<button class="btn ghost small" data-act="latest" data-id="${v.id}">设为最新</button>`}
        <button class="btn danger small" data-act="delete" data-id="${v.id}">删除</button>
      </td>
    </tr>`
    )
    .join('')

  body.querySelectorAll('button[data-act]').forEach((btn) => {
    btn.addEventListener('click', () => onVersionAction(btn.dataset.act, Number(btn.dataset.id)))
  })

  const latest = versions.find((v) => v.isLatest) || versions[0]
  if (latest) {
    latestCode = latest.versionCode
    const nextCode = Math.max(...versions.map((v) => v.versionCode)) + 1
    if (!$('fCode').value) $('fCode').value = nextCode
    if (!$('fName').value) $('fName').value = suggestName(latest.versionName)
  }
}

function suggestName(current) {
  const m = /^(\d+)\.(\d+)\.(\d+)$/.exec(current || '')
  if (!m) return ''
  return `${m[1]}.${m[2]}.${Number(m[3]) + 1}`
}

async function onVersionAction(act, id) {
  const v = versions.find((x) => x.id === id)
  if (!v) return

  if (act === 'latest') {
    if (!confirm(`把 ${v.versionName} 设为线上最新版？所有客户端会收到这个版本。`)) return
    try {
      await api('PATCH', `/api/v1/admin/versions/${id}`, { setLatest: true })
      toast(`${v.versionName} 已设为最新`)
      await loadAll()
    } catch (e) {
      toast(e.message)
    }
    return
  }

  if (act === 'delete') {
    if (!confirm(`删除 ${v.versionName} 的发布记录？\n下载统计会一起消失，安装包文件仍留在服务器上。`)) return
    try {
      await api('DELETE', `/api/v1/admin/versions/${id}`)
      toast('已删除')
      await loadAll()
    } catch (e) {
      toast(e.message)
    }
    return
  }

  if (act === 'upload') {
    pickTarget = id
    $('filePicker').click()
  }
}

// ───────────────────────────────────────────── 上传与发布

function uploadFile(file) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    const bar = $('uploadProgress')
    const fill = bar.querySelector('i')
    bar.classList.add('show')
    fill.style.width = '0%'

    xhr.open('POST', withShare('/api/v1/admin/upload'))
    xhr.withCredentials = true
    xhr.setRequestHeader('X-Filename', encodeURIComponent(file.name))

    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) fill.style.width = `${((e.loaded / e.total) * 100).toFixed(1)}%`
    }

    xhr.onload = () => {
      bar.classList.remove('show')
      let data = {}
      try {
        data = JSON.parse(xhr.responseText)
      } catch {}
      if (xhr.status === 200) resolve(data)
      else reject(new Error(data.error || `上传失败（${xhr.status}）`))
    }
    xhr.onerror = () => {
      bar.classList.remove('show')
      reject(new Error('上传中断，检查一下网络或文件大小'))
    }
    xhr.send(file)
  })
}

async function publish() {
  const btn = $('publishBtn')
  const name = $('fName').value.trim()
  const code = Number($('fCode').value)
  const changelog = $('fChangelog').value
  const channel = $('fChannel').value
  const force = $('fForce').checked
  const minSupported = Number($('fMin').value) || 0
  const file = $('fFile').files[0]
  const externalUrl = $('fApkUrl').value.trim()

  if (!name) return toast('先填版本名')
  if (!Number.isInteger(code) || code <= 0) return toast('versionCode 要是正整数')

  btn.disabled = true
  try {
    let apkPath = null
    let apkUrl = null
    let size = 0
    let sha256 = ''

    if (file) {
      $('publishHint').textContent = '正在上传安装包…'
      const up = await uploadFile(file)
      apkPath = up.path
      size = up.size
      sha256 = up.sha256
    } else if (externalUrl) {
      apkUrl = externalUrl
    }

    $('publishHint').textContent = '正在发布…'
    await api('POST', '/api/v1/admin/versions', {
      versionName: name,
      versionCode: code,
      changelog,
      channel,
      apkPath,
      apkUrl,
      size,
      sha256,
      force,
      minSupportedVersionCode: minSupported,
    })

    toast(`${name} 已发布`)
    $('fName').value = ''
    $('fCode').value = ''
    $('fChangelog').value = ''
    $('fFile').value = ''
    $('fApkUrl').value = ''
    $('fForce').checked = false
    $('publishHint').textContent = ''
    await loadAll()
  } catch (e) {
    $('publishHint').textContent = ''
    toast(e.message)
  } finally {
    btn.disabled = false
  }
}

async function uploadToExisting(file) {
  const id = pickTarget
  pickTarget = null
  if (!id) return
  try {
    const up = await uploadFile(file)
    await api('PATCH', `/api/v1/admin/versions/${id}`, {
      apkPath: up.path,
      size: up.size,
      sha256: up.sha256,
    })
    toast('安装包已补上')
    await loadAll()
  } catch (e) {
    toast(e.message)
  }
}

// ───────────────────────────────────────────── 接口地址

function renderApi() {
  const code = shareCode()
  const tail = code ? `&sharecode=${encodeURIComponent(code)}` : ''
  const url = `${location.origin}/api/v1/update?package=com.aiphone.assistant&versionCode=${latestCode}&channel=stable${tail}`
  $('apiUrl').innerHTML = `<b>GET</b> ${url}`
  $('tryApi').href = url
}

function copyApi() {
  const url = $('apiUrl').textContent.replace(/^GET\s+/, '')
  const done = () => toast('接口地址已复制')
  if (navigator.clipboard && window.isSecureContext) {
    navigator.clipboard.writeText(url).then(done).catch(() => fallbackCopy(url, done))
  } else {
    fallbackCopy(url, done)
  }
}

function fallbackCopy(text, done) {
  const ta = document.createElement('textarea')
  ta.value = text
  ta.style.position = 'fixed'
  ta.style.opacity = '0'
  document.body.append(ta)
  ta.select()
  try {
    document.execCommand('copy')
    done()
  } catch {
    toast('复制失败，手动选中吧')
  }
  ta.remove()
}

async function backup() {
  try {
    const res = await fetch(withShare('/api/v1/admin/backup'), {
      credentials: 'same-origin',
    })
    if (!res.ok) throw new Error('备份失败')
    const blob = await res.blob()
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = `paperbox-backup-${new Date().toISOString().slice(0, 10)}.json`
    a.click()
    setTimeout(() => URL.revokeObjectURL(a.href), 4000)
    toast('备份已下载')
  } catch (e) {
    toast(e.message)
  }
}

// ───────────────────────────────────────────── 启动

async function boot() {
  try {
    const state = await api('GET', '/api/v1/admin/state', undefined, { auth: false })
    initialized = state.initialized
  } catch {
    initialized = true
  }

  // 会话走 cookie：有没有登录态，试一次就知道
  try {
    await api('GET', '/api/v1/admin/versions')
    $('loginView').classList.add('hidden')
    await enterApp()
    return
  } catch {
    localStorage.removeItem(TOKEN_KEY)
  }
  showLogin()
}

$('loginBtn').addEventListener('click', submitLogin)
$('pwd').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') submitLogin()
})
$('pwd2').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') submitLogin()
})

$('publishBtn').addEventListener('click', publish)
$('copyApi').addEventListener('click', copyApi)
$('refreshLink').addEventListener('click', (e) => {
  e.preventDefault()
  loadAll().then(() => toast('已刷新'))
})
$('backupLink').addEventListener('click', (e) => {
  e.preventDefault()
  backup()
})
$('logoutLink').addEventListener('click', async (e) => {
  e.preventDefault()
  try {
    await api('POST', '/api/v1/admin/logout')
  } catch {}
  token = ''
  localStorage.removeItem(TOKEN_KEY)
  showLogin()
})

$('pwdLink').addEventListener('click', async (e) => {
  e.preventDefault()
  const next = prompt('新的管理口令（至少 6 位）：')
  if (!next) return
  if (next.length < 6) return toast('口令至少 6 位')
  const again = prompt('再输一遍：')
  if (again !== next) return toast('两次输入不一致')
  try {
    const data = await api('POST', '/api/v1/admin/password', { password: next })
    token = data.token
    localStorage.setItem(TOKEN_KEY, token)
    toast('口令已更新')
  } catch (err) {
    toast(err.message)
  }
})

// 隐藏的补包选择器
const picker = document.createElement('input')
picker.type = 'file'
picker.id = 'filePicker'
picker.accept = '.apk,application/vnd.android.package-archive'
picker.style.display = 'none'
document.body.append(picker)
picker.addEventListener('change', () => {
  const file = picker.files && picker.files[0]
  picker.value = ''
  if (file) uploadToExisting(file)
})

boot()
