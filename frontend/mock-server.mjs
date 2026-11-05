// Visual-verification mock server: serves built dist + mocks /api/v1/portal/*
import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const DIST = path.join(__dirname, 'dist')

// --- mock media: generate simple SVG thumbnails by storageKey ---
const PALETTES = [
  ['#18a058', '#36c98a'], ['#3b6ce0', '#7aa4ff'], ['#7c5cff', '#b59bff'],
  ['#d98324', '#f4b860'], ['#e0533d', '#ff8f7a'], ['#0ea5c4', '#6fd8ec'],
  ['#c4397a', '#f57bb0'], ['#5a7d3c', '#92b56a'],
]
function svgTile(key, w, h) {
  let hsh = 0
  for (const c of key) hsh = (hsh * 31 + c.charCodeAt(0)) & 0xffff
  const [a, b] = PALETTES[hsh % PALETTES.length]
  const id = hsh
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}">
  <defs><linearGradient id="g${id}" x1="0" y1="0" x2="1" y2="1">
    <stop offset="0" stop-color="${a}"/><stop offset="1" stop-color="${b}"/></linearGradient></defs>
  <rect width="${w}" height="${h}" fill="url(#g${id})"/>
  <g fill="#ffffff" opacity="0.85" font-family="system-ui,sans-serif">
    <text x="50%" y="47%" font-size="${Math.round(w*0.10)}" text-anchor="middle" font-weight="700">${key.slice(0,2).toUpperCase()}</text>
    <text x="50%" y="62%" font-size="${Math.round(w*0.05)}" text-anchor="middle" opacity="0.7">photo</text>
  </g></svg>`
}

// --- fixtures ---
const conversations = [
  { id: 1, peer: { id: 2, username: 'alice', nickname: '爱丽丝' }, lastMessageAt: new Date(Date.now() - 600000).toISOString(), lastMessagePreview: '好久不见，最近怎么样？', unread: 3 },
  { id: 2, peer: { id: 3, username: 'bob', nickname: '鲍勃' }, lastMessageAt: new Date(Date.now() - 86400000).toISOString(), lastMessagePreview: '谢谢你的建议，非常有用！', unread: 0 },
  { id: 3, peer: { id: 4, username: 'carol', nickname: '卡罗尔' }, lastMessageAt: new Date(Date.now() - 3 * 86400000).toISOString(), lastMessagePreview: '那个开源项目我 fork 了', unread: 1 },
]
const labels = [
  { id: 1, name: '前端' }, { id: 2, name: '后端' }, { id: 3, name: '设计' },
  { id: 4, name: '生活随笔' }, { id: 5, name: '性能优化' }, { id: 6, name: '架构' },
  { id: 7, name: 'Vue' }, { id: 8, name: '摄影' },
]
const INTROS = [
  '从一次线上事故出发，回顾我们对缓存层做的三处关键改造，以及最终把 P99 延迟从 380ms 压到 90ms 的完整过程。',
  '这篇文章整理了我在实际项目里沉淀的一套组件设计规范，覆盖命名、状态、可访问性以及与设计稿的协作流程。',
  '周末去了城郊的一个水库，拍了一组清晨的薄雾。设备是一台陪伴多年的老相机，记录一下当时的光线。',
  '很多人以为类型体操只是炫技，但当你的业务复杂到一定程度，一套好的类型约束能省下大量运行时检查和线上排查。',
  '用一篇文章把 Vite 的冷启动、依赖预构建、HMR 原理讲清楚，并给出几个真实项目的优化前后对比数据。',
  '关于如何在没有 Lombok 的约束下，用 record 写出既简洁又不可变的数据模型，附可运行的示例代码。',
  '把博客从零重写了一遍，踩了不少坑也收获很多，这里是整体架构与取舍的复盘。',
  '分享一本最近读完的书，以及它带给我的关于「长期主义」的思考，配几张书的照片。',
]
let seq = 10
function makeContent(i, type, extra = {}) {
  const id = ++seq
  return {
    id,
    contentType: type,
    title: type === 'article'
      ? ['把 P99 延迟压到 90ms：一次缓存层重构','组件设计规范：我沉淀的 12 条原则','水库晨雾','类型体操到底有没有用','Vite 冷启动与 HMR 原理拆解','不用 Lombok，record 也能优雅','博客重写复盘','读《长期主义》有感'][i % 8]
      : ['城市夜景 · 长曝光','山间的清晨','海边落日','雨后的街角','老街的猫','雪松与石阶','灯塔','秋日树林'][i % 8],
    introduce: INTROS[i % INTROS.length],
    coverMediaId: 100 + i,
    coverStorageKey: 'cover-' + id,
    state: 'published',
    publishedAt: new Date(Date.now() - i * 86400000 * 2).toISOString(),
    views: 120 + i * 37,
    praises: 8 + i * 5,
    commentsCount: i % 7,
    isPinned: false,
    authorId: 1,
    authorName: '林深',
    labelIds: [labels[i % labels.length].id],
    labels: [labels[i % labels.length].name, labels[(i + 2) % labels.length].name],
    ...extra,
  }
}
const bodyDoc = { type: 'doc', content: [
  { type: 'paragraph', content: [{ type: 'text', text: '正文第一段：这是一段足够长的正文，用来验证详情页排版的行高、段间距与可读性。颜色应随主题变化，阅读体验应当舒展而克制。' }] },
  { type: 'heading', attrs: { level: 2 }, content: [{ type: 'text', text: '一、背景与问题' }] },
  { type: 'paragraph', content: [{ type: 'text', text: '当流量上升后，旧的同步逻辑逐渐暴露出瓶颈。这里详细记录当时观测到的指标与复现方式。' }] },
] }

const articles = Array.from({ length: 12 }, (_, i) => makeContent(i, 'article'))
const images = Array.from({ length: 16 }, (_, i) => makeContent(i, 'image'))
const diaries = Array.from({ length: 10 }, (_, i) => ({
  id: 500 + i,
  authorId: 1,
  authorName: '林深',
  diaryDate: new Date(Date.now() - i * 86400000 * 3).toISOString().slice(0, 10),
  weather: ['晴天', '多云', '大雨', '夜晚', '阵风'][i % 5],
  mood: ['平静', '充实', '疲惫', '雀跃', '沉思'][i % 5],
  body: JSON.stringify({ type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text: '今天的事情不多，写点随手记。窗外的光很好，适合读一会儿书再开始工作。' }] }] }),
  state: 'published',
  views: 40 + i * 9,
  praises: 2 + i,
  coverStorageKey: 'diary-' + i,
}))

const subsets = [
  { id: 1, name: '精选长文', description: '值得反复阅读的深度长文合集，适合安静时慢慢看。', coverMediaId: 101, ownerId: 1, createdAt: '2026-09-02T10:20:00.000Z' },
  { id: 2, name: '光影拾遗', description: '随手拍下的城市光影与街头瞬间。', coverMediaId: 106, ownerId: 1, createdAt: '2026-09-18T14:05:00.000Z' },
  { id: 3, name: '周末影集', description: '每个周末整理一次的照片小集。', coverMediaId: 110, ownerId: 1, createdAt: '2026-10-06T09:40:00.000Z' },
  { id: 4, name: '待整理', description: undefined, coverMediaId: undefined, ownerId: 1, createdAt: '2026-10-28T20:12:00.000Z' },
]
const subsetItems = [
  { subsetId: 1, contentId: 1, sort: 0 },
  { subsetId: 1, contentId: 4, sort: 1 },
  { subsetId: 1, contentId: 7, sort: 2 },
  { subsetId: 2, contentId: 105, sort: 0 },
  { subsetId: 2, contentId: 108, sort: 1 },
  { subsetId: 2, contentId: 112, sort: 2 },
  { subsetId: 2, contentId: 115, sort: 3 },
  { subsetId: 3, contentId: 103, sort: 0 },
  { subsetId: 3, contentId: 110, sort: 1 },
]
let subsetSeq = 4

function wrap(data) {
  return JSON.stringify({ code: 0, message: 'ok', data, requestId: 'mock' })
}

const server = http.createServer(async (req, res) => {
  const u = new URL(req.url, 'http://localhost')
  const p = u.pathname

  // media endpoint
  let m = p.match(/^\/api\/v1\/media\/(.+)$/)
  if (m) {
    const w = parseInt(u.searchParams.get('width') || '640', 10)
    const h = Math.round(w * 0.66)
    res.writeHead(200, { 'Content-Type': 'image/svg+xml', 'Cache-Control': 'no-store' })
    res.end(svgTile(m[1], w, h))
    return
  }

  // API
  if (p === '/api/v1/portal/home') {
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(wrap({ latest: articles.slice(0, 6), pinned: [articles[3]], labels }))
    return
  }
  if (p === '/api/v1/portal/contents') {
    const ct = u.searchParams.get('contentType')
    const page = parseInt(u.searchParams.get('page') || '1', 10)
    const pageSize = parseInt(u.searchParams.get('pageSize') || '10', 10)
    const src = ct === 'image' ? images : articles
    const start = (page - 1) * pageSize
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(wrap({ records: src.slice(start, start + pageSize), total: src.length }))
    return
  }
  let cm = p.match(/^\/api\/v1\/portal\/contents\/(\d+)$/)
  if (cm) {
    const id = parseInt(cm[1], 10)
    const found = [...articles, ...images].find((x) => x.id === id) || articles[0]
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(wrap({ ...found, body: JSON.stringify(bodyDoc), plainText: '正文第一段这是一段足够长的正文用来验证详情页排版' }))
    return
  }
  if (p === '/api/v1/portal/diaries') {
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(wrap({ records: diaries, total: diaries.length }))
    return
  }
  if (p === '/api/v1/auth/me') {
    const perms = [
      'overview.view','user.view','user.update','user.disable','user.resetPassword','user.assignAdmin',
      'permission.view','permission.edit','article.viewAll','article.viewOwn','article.create','article.manageOwn',
      'article.manageAll','article.publish','gallery.viewAll','gallery.viewOwn','gallery.create','gallery.manageOwn',
      'gallery.manageAll','diary.viewAll','diary.viewOwn','diary.create','diary.manageOwn','diary.manageAll',
      'comment.viewAll','comment.moderate','message.private','message.wallModerate','file.view','file.upload','file.delete',
      'systemLog.view','system.setting','label.manage',
    ]
    const user = {
      id: 1, username: 'admin', email: 'admin@linblog.local', nickname: '林深',
      avatarMediaId: null, roleCode: 'super_admin', isSuperAdmin: true, status: 1,
      mustChangePassword: 0, createdAt: '2025-01-01T00:00:00',
    }
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(wrap({ user, permissions: perms, isSuperAdmin: true }))
    return
  }
  if (p === '/api/v1/admin/overview/summary') {
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(wrap({ contents: 128, comments: 342, unreadMessages: 7, files: 96, viewsToday: 1830, viewsWeek: 12480 }))
    return
  }
  if (p === '/api/v1/admin/overview/visit-stats') {
    const range = u.searchParams.get('range') || 'week'
    const n = range === 'moon' ? 30 : 7
    const points = Array.from({ length: n }, (_, i) => ({ date: new Date(Date.now() - (n - 1 - i) * 86400000).toISOString().slice(0, 10), count: 800 + Math.round(Math.sin(i) * 300) + i * 40 }))
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(wrap({ range, points }))
    return
  }
  if (p === '/api/v1/admin/contents') {
    const page = parseInt(u.searchParams.get('page') || '1', 10)
    const pageSize = parseInt(u.searchParams.get('pageSize') || '10', 10)
    const sort = u.searchParams.get('sort') || 'latest'
    const sorted = [...articles].sort((a, b) =>
      sort === 'hot' ? (b.views ?? 0) - (a.views ?? 0) : String(b.createdAt).localeCompare(String(a.createdAt)),
    )
    const start = (page - 1) * pageSize
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(wrap({ records: sorted.slice(start, start + pageSize), total: sorted.length }))
    return
  }
  if (p === '/api/v1/admin/files/storage-status') {
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(wrap({ primaryBytes: 320000000, primaryCount: 96, backupBytes: 42000000, backupCount: 12, quotaBytes: 2147483648, backupPercent: 12.4, alert: false }))
    return
  }
  if (p === '/api/v1/admin/files') {
    const files = Array.from({ length: 8 }, (_, i) => ({ id: 700 + i, uploaderId: 1, uploaderName: '林深', storageKey: 'file-' + i, originalName: ['封面.png','照片.jpg','文档.pdf','icon.svg'][i % 4], mimeType: 'image/png', sizeBytes: 120000 + i * 40000, state: 'active', createdAt: new Date(Date.now() - i * 86400000).toISOString() }))
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(wrap({ records: files, total: files.length }))
    return
  }
  if (p === '/api/v1/admin/comment-reports') {
    const reps = Array.from({ length: 5 }, (_, i) => ({ id: 900 + i, commentId: 300 + i, commentBody: '这是一条被举报的评论内容示例。', commentAuthorName: '路人' + i, reporterName: '用户' + i, reason: ['spam','abuse','other'][i%3], detail: '详细说明', status: 'pending', createdAt: new Date(Date.now() - i * 3600000).toISOString() }))
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(wrap({ records: reps, total: reps.length }))
    return
  }
  if (p === '/api/v1/admin/users') {
    const us = Array.from({ length: 6 }, (_, i) => ({ id: 20 + i, username: 'user' + i, email: 'u' + i + '@x.com', nickname: ['林深','阿雅','小北','晨曦','木子','南风'][i], role: ['super_admin','admin','editor','editor','viewer','admin'][i], status: i === 5 ? 0 : 1, createdAt: '2025-03-0' + (i + 1) + 'T10:00:00' }))
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(wrap({ records: us, total: us.length }))
    return
  }
  if (p === '/api/v1/permissions/catalog') {
    const groups = [
      { name: '概览', items: [
        { key: 'overview.view', label: '查看运营概览', isProtected: false },
      ] },
      { name: '用户与权限', items: [
        { key: 'user.view', label: '查看用户', isProtected: false },
        { key: 'user.update', label: '编辑用户', isProtected: false },
        { key: 'user.disable', label: '禁用用户', isProtected: false },
        { key: 'user.resetPassword', label: '重置密码', isProtected: false },
        { key: 'user.assignAdmin', label: '授予管理员', isProtected: true },
        { key: 'permission.view', label: '查看权限', isProtected: false },
        { key: 'permission.edit', label: '编辑权限矩阵', isProtected: true },
      ] },
      { name: '内容', items: [
        { key: 'article.viewAll', label: '查看文章（全部）', isProtected: false },
        { key: 'article.viewOwn', label: '查看文章（本人）', isProtected: false },
        { key: 'article.create', label: '创建文章', isProtected: false },
        { key: 'article.manageOwn', label: '管理文章（本人）', isProtected: false },
        { key: 'article.manageAll', label: '管理文章（全部）', isProtected: false },
        { key: 'article.publish', label: '发布文章', isProtected: false },
        { key: 'gallery.viewAll', label: '查看画廊（全部）', isProtected: false },
        { key: 'gallery.viewOwn', label: '查看画廊（本人）', isProtected: false },
        { key: 'gallery.create', label: '创建画廊', isProtected: false },
        { key: 'gallery.manageOwn', label: '管理画廊（本人）', isProtected: false },
        { key: 'gallery.manageAll', label: '管理画廊（全部）', isProtected: false },
        { key: 'diary.viewAll', label: '查看日记（全部）', isProtected: false },
        { key: 'diary.create', label: '创建日记', isProtected: false },
        { key: 'diary.manageOwn', label: '管理日记（本人）', isProtected: false },
        { key: 'diary.manageAll', label: '管理日记（全部）', isProtected: false },
        { key: 'label.manage', label: '管理标签', isProtected: false },
      ] },
      { name: '互动', items: [
        { key: 'comment.viewAll', label: '查看评论（全部）', isProtected: false },
        { key: 'comment.moderate', label: '审核/删除评论', isProtected: false },
        { key: 'message.private', label: '私信管理', isProtected: false },
        { key: 'message.wallModerate', label: '留言墙审核', isProtected: false },
      ] },
      { name: '系统', items: [
        { key: 'file.view', label: '查看文件', isProtected: false },
        { key: 'file.upload', label: '上传文件', isProtected: false },
        { key: 'file.delete', label: '删除文件', isProtected: false },
        { key: 'systemLog.view', label: '查看系统日志', isProtected: false },
        { key: 'system.setting', label: '系统设置', isProtected: false },
      ] },
    ]
    const protectedKeys = groups.flatMap((g) => g.items.filter((it) => it.isProtected).map((it) => it.key))
    const totalKeys = groups.reduce((sum, g) => sum + g.items.length, 0)
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(wrap({ groups, protectedKeys, totalKeys }))
    return
  }
  if (p === '/api/v1/permissions/matrix' && req.method === 'PUT') {
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(wrap({ admin: 26, editor: 10, viewer: 2 }))
    return
  }
  if (p === '/api/v1/admin/logs' || p === '/api/v1/admin/system-logs') {
    const mods = ['content', 'auth', 'file', 'comment', 'system']
    const acts = ['发布文章', '登录', '上传文件', '发表评论', '更新设置']
    const tt = ['content', 'user', 'file', 'comment', 'setting']
    const logs = Array.from({ length: 14 }, (_, i) => ({
      id: 1000 + i, userId: 1 + (i % 3), userName: ['林深', '爱丽丝', '鲍勃'][i % 3],
      module: mods[i % mods.length], action: acts[i % acts.length],
      targetType: tt[i % tt.length], targetId: 10 + i,
      detail: '操作详情记录，针对目标对象的变更已生效', ip: '127.0.0.1',
      createdAt: new Date(Date.now() - i * 5400000).toISOString(),
    }))
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(wrap({ records: logs, total: logs.length }))
    return
  }
  if (p === '/api/v1/admin/settings') {
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(wrap({ siteName: 'LinBlog', siteDescription: '记录技术与生活', icp: '粤ICP备00000000号', allowRegister: 'true', commentAudit: 'false' }))
    return
  }
  if (p === '/api/v1/labels') {
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(wrap(labels))
    return
  }
  // private messages: conversation list
  if (p === '/api/v1/messages/conversations') {
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(wrap({ records: conversations, total: conversations.length }))
    return
  }
  if (p === '/api/v1/messages/unread-count') {
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(wrap(conversations.reduce((sum, c) => sum + c.unread, 0)))
    return
  }
  // mark a conversation as read
  if (/^\/api\/v1\/messages\/conversations\/\d+\/read$/.test(p)) {
    const cid = Number(p.split('/')[5])
    const target = conversations.find((c) => c.id === cid)
    if (target) target.unread = 0
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(wrap(null))
    return
  }
  if (p === '/api/v1/admin/diaries') {
    const weathers = ['sunny', 'cloudy', 'rain', 'night', 'wind']
    const moods = ['平静', '充实', '疲惫', '愉快']
    const diaries = Array.from({ length: 9 }, (_, i) => {
      const d = new Date(Date.now() - i * 86400000)
      return {
        id: 700 + i, authorId: 1, authorName: '林深',
        diaryDate: d.toISOString().slice(0, 10),
        weather: weathers[i % weathers.length], mood: moods[i % moods.length],
        body: '今天的事情不多，写点随手记。窗外的光很好，适合读一会儿书再开始工作。' + (i % 2 ? '傍晚去散了会步，风很轻。' : ''),
        state: i % 4 === 3 ? 'DRAFT' : 'PUBLISHED',
        views: 20 + i * 7, praises: 3 + i,
        createdAt: d.toISOString(), updatedAt: d.toISOString(),
      }
    })
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(wrap({ records: diaries, total: diaries.length }))
    return
  }
  // private messages: message thread for a conversation
  if (/^\/api\/v1\/messages\/conversations\/\d+$/.test(p)) {
    const cid = Number(p.split('/').pop())
    const records = Array.from({ length: 4 }, (_, i) => ({
      id: cid * 100 + i,
      conversationId: cid,
      senderId: i % 2 === 0 ? 2 : 1,
      body: i % 2 === 0 ? ['好久不见，最近怎么样？', '在忙什么项目呢？', '有空一起吃个饭？', '那就这么说定了~'][i] : ['挺好的，最近在研究前端性能优化', '在做一个新的博客项目', '好呀，周末有空', '嗯嗯，期待!'][i],
      readAt: i < 3 ? new Date(Date.now() - (4 - i) * 600000).toISOString() : null,
      createdAt: new Date(Date.now() - (5 - i) * 3600000).toISOString(),
    }))
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(wrap({ records, hasMore: false }))
    return
  }
  // guestbook (admin wall moderation): entries with two-level replies
  if (p === '/api/v1/guestbook') {
    const entries = [
      { id: 1, userId: 2, userName: '爱丽丝', body: '博客做得真不错，学到了很多！', createdAt: new Date(Date.now() - 3600000).toISOString(),
        replies: [{ id: 11, userId: 1, userName: '林深', body: '谢谢支持~', parentId: 1, createdAt: new Date(Date.now() - 1800000).toISOString(), replies: [] }] },
      { id: 2, userId: 3, userName: '鲍勃', body: '请问用的什么主题系统？可以切换颜色太酷了', createdAt: new Date(Date.now() - 2 * 86400000).toISOString(), replies: [] },
      { id: 3, userId: 4, userName: '卡罗尔', body: '期待更多技术文章！', createdAt: new Date(Date.now() - 5 * 86400000).toISOString(), replies: [] },
    ]
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(wrap(entries))
    return
  }
  // portal comments: /api/v1/portal/{type}/{id}/comments
  if (/^\/api\/v1\/portal\/(article|image|diary|guestbook)\/\d+\/comments$/.test(p)) {
    const segs = p.split('/')
    const targetType = segs[4]
    const targetId = Number(segs[5])
    const mk = (id, parentId, authorId, authorName, body, praise, mins, replyTo) => ({
      id, targetType, targetId, authorId, authorName, parentId,
      replyToCommentId: replyTo ? replyTo.id : null,
      replyToUserId: replyTo ? replyTo.authorId : null,
      replyToUserName: replyTo ? replyTo.authorName : null,
      body, praiseCount: praise, liked: false,
      createdAt: new Date(Date.now() - mins * 60000).toISOString(),
    })
    const r1 = mk(1, null, 2, '爱丽丝', '写得很详细，收藏了！', 8, 120, null)
    const r2 = mk(2, null, 3, '鲍勃', '这一段关于缓存的解释很有启发。', 5, 60, null)
    const r3 = mk(3, null, 5, '木子', '请问后续还有实战部分吗？', 2, 30, null)
    const replies = [
      mk(11, 1, 1, '林深', '谢谢支持，会继续更新~', 3, 90, r1),
      mk(12, 2, 1, '林深', '很高兴对你有帮助！', 1, 40, r2),
    ]
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(wrap({ rootComments: [r1, r2, r3], replyComments: replies }))
    return
  }
  // comments (two-segment shape)
  if (/^\/api\/v1\/comments/.test(p)) {
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(wrap({ rootComments: [], replyComments: [] }))
    return
  }
  // ---- subsets (合集管理) ----
  if (/^\/api\/v1\/admin\/subsets/.test(p)) {
    const subsetsM = p.match(/^\/api\/v1\/admin\/subsets\/(\d+)\/items$/)
    const oneM = p.match(/^\/api\/v1\/admin\/subsets\/(\d+)$/)
    const readBody = () =>
      new Promise((resolve) => {
        let raw = ''
        req.on('data', (chunk) => {
          raw += chunk
        })
        req.on('end', () => {
          try {
            resolve(raw ? JSON.parse(raw) : {})
          } catch {
            resolve({})
          }
        })
      })
    const findSubset = (id) => subsets.find((s) => s.id === id)
    const itemsOf = (id) =>
      subsetItems
        .filter((it) => it.subsetId === id)
        .sort((a, b) => a.sort - b.sort)
        .map((it) => ({ subsetId: it.subsetId, contentId: it.contentId, sort: it.sort }))
    const countOf = (id) => subsetItems.filter((it) => it.subsetId === id).length
    const withCount = (s) => ({ ...s, itemsCount: countOf(s.id) })
    // list
    if (req.method === 'GET' && /^\/api\/v1\/admin\/subsets\/?$/.test(p)) {
      const pg = Math.max(parseInt(u.searchParams.get('page') || '1', 10), 1)
      const size = Math.min(Math.max(parseInt(u.searchParams.get('pageSize') || '10', 10), 1), 50)
      const slice = subsets.slice((pg - 1) * size, pg * size).map(withCount)
      res.writeHead(200, { 'Content-Type': 'application/json' })
      res.end(wrap({ records: slice, total: subsets.length, page: pg, pageSize: size }))
      return
    }
    // create
    if (req.method === 'POST' && /^\/api\/v1\/admin\/subsets\/?$/.test(p)) {
      const body = await readBody()
      const name = String(body.name || '').trim()
      if (!name || name.length > 50) {
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ code: 40001, message: '名称不能为空或超过 50 个字符', data: null, requestId: 'mock' }))
        return
      }
      const created = {
        id: ++subsetSeq,
        name,
        description: body.description ? String(body.description) : undefined,
        coverMediaId: Number.isFinite(Number(body.coverMediaId)) && body.coverMediaId !== null ? Number(body.coverMediaId) : undefined,
        ownerId: 1,
        createdAt: new Date().toISOString(),
      }
      subsets.unshift(created)
      if (Array.isArray(body.contentIds)) {
        body.contentIds.forEach((contentId, idx) => subsetItems.push({ subsetId: created.id, contentId, sort: idx }))
      }
      res.writeHead(200, { 'Content-Type': 'application/json' })
      res.end(wrap(withCount(created)))
      return
    }
    // items of one subset
    if (req.method === 'GET' && subsetsM) {
      res.writeHead(200, { 'Content-Type': 'application/json' })
      res.end(wrap(itemsOf(Number(subsetsM[1]))))
      return
    }
    // replace items
    if (req.method === 'PUT' && subsetsM) {
      const body = await readBody()
      const id = Number(subsetsM[1])
      const ids = Array.isArray(body.contentIds) ? body.contentIds.map(Number) : []
      for (let i = subsetItems.length - 1; i >= 0; i--) if (subsetItems[i].subsetId === id) subsetItems.splice(i, 1)
      ids.forEach((contentId, idx) => subsetItems.push({ subsetId: id, contentId, sort: idx }))
      res.writeHead(200, { 'Content-Type': 'application/json' })
      res.end(wrap({ updated: ids.length }))
      return
    }
    // update / delete one
    if (oneM && (req.method === 'PATCH' || req.method === 'DELETE')) {
      const id = Number(oneM[1])
      const found = findSubset(id)
      if (!found) {
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ code: 40404, message: '合集不存在', data: null, requestId: 'mock' }))
        return
      }
      if (req.method === 'DELETE') {
        subsets.splice(subsets.indexOf(found), 1)
        for (let i = subsetItems.length - 1; i >= 0; i--) if (subsetItems[i].subsetId === id) subsetItems.splice(i, 1)
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(wrap({ deleted: id }))
        return
      }
      const body = await readBody()
      if (typeof body.name === 'string' && body.name.trim()) found.name = body.name.trim()
      if (typeof body.description === 'string') found.description = body.description || undefined
      if (body.coverMediaId === null || Number.isFinite(Number(body.coverMediaId))) {
        found.coverMediaId = body.coverMediaId === null ? undefined : Number(body.coverMediaId)
      }
      res.writeHead(200, { 'Content-Type': 'application/json' })
      res.end(wrap(withCount(found)))
      return
    }
  }
  // catch-all for portal/api
  if (p.startsWith('/api/v1/')) {
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(wrap(null))
    return
  }

  // static from dist
  let fp = p === '/' ? '/index.html' : p
  let full = path.join(DIST, fp)
  if (!fs.existsSync(full) || fs.statSync(full).isDirectory()) {
    full = path.join(DIST, 'index.html') // SPA fallback
  }
  const ext = path.extname(full)
  const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.json': 'application/json', '.woff2': 'font/woff2' }
  res.writeHead(200, { 'Content-Type': types[ext] || 'application/octet-stream' })
  fs.createReadStream(full).pipe(res)
})

const PORT = process.env.PORT || 4319
server.listen(PORT, () => console.log('mock server on http://localhost:' + PORT))
