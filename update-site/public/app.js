'use strict'

/* 下载页：读取 /api/v1/versions + /api/v1/stats 渲染。纯原生 JS，不依赖 CDN。 */

const $ = (id) => document.getElementById(id)

/* 托管平台给分享链接加了访问码，css/js/接口/apk 的请求都要带上它 */
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

function formatBytes(n) {
  if (!n) return '—'
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}

function formatDate(iso) {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '—'
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function el(tag, props = {}, children = []) {
  const node = document.createElement(tag)
  for (const [k, v] of Object.entries(props)) {
    if (k === 'text') node.textContent = v
    else if (k === 'class') node.className = v
    else if (k === 'href') node.setAttribute('href', v)
    else node.setAttribute(k, v)
  }
  for (const c of children) node.append(c)
  return node
}

async function main() {
  let data
  let stats
  try {
    const [a, b] = await Promise.all([
      fetch(withShare('/api/v1/versions')).then((r) => r.json()),
      fetch(withShare('/api/v1/stats')).then((r) => r.json()),
    ])
    data = a
    stats = b
  } catch {
    $('latestPill').textContent = '读取失败'
    return
  }

  const latest = (data.versions || []).find((v) => v.isLatest) || (data.versions || [])[0]

  if (!latest) {
    $('latestPill').textContent = '还没有发布版本'
    $('latestTitle').textContent = '纸盒'
    $('latestMeta').textContent = '作者还没有在后台发布第一个版本。'
    $('downloadBtn').setAttribute('aria-disabled', 'true')
    $('downloadBtn').style.opacity = 0.45
    $('downloadBtn').style.pointerEvents = 'none'
    return
  }

  $('latestPill').innerHTML = '<span class="dot"></span>最新版本'
  $('latestTitle').innerHTML = `纸盒 <span class="ver">${latest.versionName}</span>`
  $('latestMeta').textContent = `${formatDate(latest.publishedAt)} 发布 · ${formatBytes(latest.size)} · versionCode ${latest.versionCode}`

  const btn = $('downloadBtn')
  if (latest.hasApk === false || !latest.downloadUrl) {
    btn.textContent = '安装包未上传'
    btn.style.opacity = 0.5
    btn.style.pointerEvents = 'none'
    $('downloadHint').textContent = '作者把 APK 传到后台之后，这里就能下载。'
  } else {
    btn.href = latest.downloadUrl
    btn.textContent = '下载 APK'
    btn.addEventListener('click', () => {
      // 让统计立刻反映（跳转 302 后本页不刷新）
      setTimeout(() => {
        $('downloadHint').textContent = '已开始下载。若没有反应，请检查浏览器是否拦截了下载。'
      }, 300)
    })
    $('downloadHint').textContent = stats && stats.downloads
      ? `累计下载 ${stats.downloads} 次${stats.devices ? ` · ${stats.devices} 台设备查询过更新` : ''}`
      : '第一次下载就从这里开始。'
  }

  // 更新日志
  $('changelogSub').textContent = `${latest.versionName} · versionCode ${latest.versionCode}`
  const log = $('changelog')
  log.className = ''
  log.innerHTML = ''
  if (latest.changelog && latest.changelog.trim()) {
    const parts = latest.changelog.split(/\n\s*\n/)
    for (const part of parts) {
      const lines = part.split('\n').filter((l) => l.trim())
      const isBullet = lines.every((l) => /^\s*[-*·]/.test(l))
      if (isBullet && lines.length) {
        const ul = el('ul', { style: 'margin: 0 0 10px; padding-left: 20px;' })
        for (const l of lines) ul.append(el('li', { text: l.replace(/^\s*[-*·]\s*/, '') }))
        log.append(ul)
      } else {
        const p = el('p', { style: 'margin: 0 0 10px; white-space: pre-wrap;' })
        p.textContent = part
        log.append(p)
      }
    }
  } else {
    log.className = 'muted'
    log.textContent = '这个版本没有写更新说明。'
  }

  // 历史版本
  const body = $('historyBody')
  body.innerHTML = ''
  const list = data.versions || []
  if (!list.length) {
    body.append(el('tr', {}, [el('td', { colspan: '5', class: 'muted', text: '暂无记录' })]))
  }
  for (const v of list) {
    const tr = el('tr')
    tr.append(el('td', {}, [
      el('span', { text: v.versionName, style: 'font-weight: 600;' }),
      v.isLatest ? el('span', { class: 'tag live', text: '最新', style: 'margin-left: 7px;' }) : document.createTextNode(''),
    ]))
    tr.append(el('td', { class: 'muted nowrap', text: formatDate(v.publishedAt) }))
    tr.append(el('td', { class: 'num muted', text: formatBytes(v.size) }))
    tr.append(el('td', { class: 'num muted', text: String(v.downloads || 0) }))
    const action = el('td', { class: 'num' })
    if (v.downloadUrl) {
      action.append(el('a', {
        class: 'btn ghost small',
        href: v.downloadUrl,
        text: '下载',
      }))
    } else {
      action.append(el('span', { class: 'muted', text: '未上传' }))
    }
    tr.append(action)
    body.append(tr)
  }

  // 接口示例带上真实域名
  const origin = location.origin
  const code = shareCode()
  const tail = code ? `&amp;sharecode=${encodeURIComponent(code)}` : ''
  $('apiExample').innerHTML =
    `<b>GET</b> ${origin}/api/v1/update?package=com.aiphone.assistant&amp;versionCode=${latest.versionCode}&amp;channel=stable${tail}`
}

main()
