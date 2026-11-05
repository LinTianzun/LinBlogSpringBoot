// 本地开发数据种子：走真实 HTTP API 造全模块演示数据（不直接写库，
// 浏览量 / 审计日志等衍生数据由后端自然产生）。
//
// 用法：先起后端（mvn spring-boot:run -Dspring-boot.run.profiles=dev），然后
//   node scripts/seed-dev-data.mjs
// 可重复执行：用户/标签按名幂等，内容按标题去重，其余追加。
// 环境变量：SEED_BASE（默认 http://localhost:8080）、SEED_ADMIN_PW（默认 Admin@linblog2026）。
/* eslint-env node */

const BASE = process.env.SEED_BASE || 'http://localhost:8080'
const ADMIN_PW = process.env.SEED_ADMIN_PW || 'Admin@linblog2026'
const USER_PW = 'Seed@2026pass'

/* ---------------- 极简 cookie jar（XSRF 双提交 + linlog 双 Cookie） ---------------- */
class Client {
  constructor(label, ip = '10.77.0.1') {
    this.label = label
    this.ip = ip // 每个模拟用户独立来源 IP：登录/注册限流按 IP 计，同 IP 连发会 429
    this.cookies = new Map()
  }

  ingest(res) {
    for (const line of res.headers.getSetCookie?.() ?? []) {
      const [pair] = line.split(';')
      const eq = pair.indexOf('=')
      if (eq > 0) this.cookies.set(pair.slice(0, eq).trim(), pair.slice(eq + 1).trim())
    }
  }

  header() {
    const parts = [...this.cookies.entries()].map(([k, v]) => `${k}=${v}`)
    const xsrf = this.cookies.get('XSRF-TOKEN')
    return {
      Cookie: parts.join('; '),
      'X-Forwarded-For': this.ip,
      ...(xsrf ? { 'X-XSRF-TOKEN': decodeURIComponent(xsrf) } : {}),
    }
  }

  async req(method, path, body, extra = {}) {
    const init = {
      method,
      headers: {
        ...this.header(),
        ...(body instanceof FormData ? {} : body === undefined ? {} : { 'Content-Type': 'application/json' }),
        ...extra.headers,
      },
      body: body === undefined ? undefined : body instanceof FormData ? body : JSON.stringify(body),
      redirect: 'manual',
    }
    const res = await fetch(BASE + path, init)
    this.ingest(res)
    const text = await res.text()
    let json
    try {
      json = JSON.parse(text)
    } catch {
      throw new Error(`${method} ${path} -> HTTP ${res.status} 非 JSON: ${text.slice(0, 200)}`)
    }
    if (res.status >= 400 || (json.code !== undefined && json.code !== 0)) {
      throw new Error(`${method} ${path} -> ${res.status} ${JSON.stringify(json).slice(0, 300)}`)
    }
    return json.data
  }

  get(path, query) {
    const q = query ? '?' + new URLSearchParams(Object.entries(query).filter(([, v]) => v !== undefined)).toString() : ''
    return this.req('GET', path + q)
  }
  post(path, body, extra) { return this.req('POST', path, body, extra) }
  patch(path, body) { return this.req('PATCH', path, body) }
  put(path, body) { return this.req('PUT', path, body) }
  del(path) { return this.req('DELETE', path) }

  async login(account, password) {
    await this.get('/api/v1/auth/me').catch(() => {}) // 种 XSRF
    return this.post('/api/v1/auth/login', { account, password })
  }
  async upload(filename, mime, content) {
    const form = new FormData()
    form.append('file', new Blob([content], { type: mime }), filename)
    return this.post('/api/v1/files/upload', form)
  }
}

const results = []
function ok(what, detail) {
  results.push({ what, detail })
  console.log(`  ✓ ${what}${detail ? ' — ' + detail : ''}`)
}

/* ---------------- ProseMirror 工具 ---------------- */
const doc = (...paras) => JSON.stringify({
  type: 'doc',
  content: paras.map((t) => ({ type: 'paragraph', content: [{ type: 'text', text: t }] })),
})

/* ---------------- 封面 SVG（渐变 + 大字，MediaService 支持 image/svg+xml） ---------------- */
const PALETTES = [
  ['#18a058', '#36c98a'], ['#3b6ce0', '#7aa4ff'], ['#7c5cff', '#b59bff'],
  ['#d98324', '#f4b860'], ['#e0533d', '#ff8f7a'], ['#0ea5c4', '#6fd8ec'],
  ['#c4397a', '#f57bb0'], ['#5a7d3c', '#92b56a'],
]
let svgSeq = 0
function coverSvg(word, sub) {
  const [a, b] = PALETTES[svgSeq++ % PALETTES.length]
  const id = `g${svgSeq}`
  return `<svg xmlns="http://www.w3.org/2000/svg" width="800" height="520" viewBox="0 0 800 520">
<defs><linearGradient id="${id}" x1="0" y1="0" x2="1" y2="1">
<stop offset="0" stop-color="${a}"/><stop offset="1" stop-color="${b}"/></linearGradient></defs>
<rect width="800" height="520" fill="url(#${id})"/>
<circle cx="${140 + (svgSeq * 97) % 520}" cy="${110 + (svgSeq * 61) % 300}" r="150" fill="#ffffff" opacity="0.10"/>
<circle cx="${680 - (svgSeq * 53) % 400}" cy="${420 - (svgSeq * 41) % 260}" r="90" fill="#ffffff" opacity="0.08"/>
<text x="50%" y="47%" font-size="120" text-anchor="middle" fill="#ffffff" opacity="0.92"
 font-family="Georgia, 'Songti SC', serif" font-weight="700">${word}</text>
<text x="50%" y="63%" font-size="34" text-anchor="middle" fill="#ffffff" opacity="0.75"
 font-family="system-ui, sans-serif" letter-spacing="6">${sub}</text>
</svg>`
}

/* ---------------- 主流程 ---------------- */
const admin = new Client('admin', '10.77.0.1')

console.log(`→ 后端 ${BASE}；以 admin 登录`)
await admin.login('admin', ADMIN_PW)
ok('admin 登录')

/* 1. 注册 + 登录三位普通用户（幂等：已存在则直接登录） */
const USERS = [
  { username: 'xiaoman', nickname: '林小满', email: 'xiaoman@linblog.local' },
  { username: 'suwan', nickname: '苏晚', email: 'suwan@linblog.local' },
  { username: 'chenmo', nickname: '陈默', email: 'chenmo@linblog.local' },
]
const userClients = {}
for (const [i, u] of USERS.entries()) {
  const c = new Client(u.username, `10.77.0.${10 + i}`)
  await c.get('/api/v1/auth/me').catch(() => {}) // 种 XSRF
  try {
    await c.post('/api/v1/auth/register', { username: u.username, email: u.email, password: USER_PW, nickname: u.nickname })
    ok(`注册用户 ${u.nickname}`)
  } catch (e) {
    if (!/40900|已被占用|已存在/.test(e.message)) throw e
  }
  const me = await c.login(u.username, USER_PW)
  if (me.mustChangePassword) throw new Error(`${u.username} 意外要求首改`)
  userClients[u.username] = c
}
ok('三位普通用户就绪（林小满 / 苏晚 / 陈默）')

/* 2. 标签（按名幂等） */
const LABELS = ['前端手记', '后端手记', '设计随笔', '摄影日记', '生活碎片', '性能优化']
const labelId = {}
async function loadLabels() {
  const raw = await admin.get('/api/v1/labels') // 公开接口返回裸数组
  for (const l of Array.isArray(raw) ? raw : raw.records ?? []) labelId[l.name] = l.id
}
{
  await loadLabels()
  for (const name of LABELS) {
    if (labelId[name]) continue
    try {
      const created = await admin.post('/api/v1/admin/labels', { name })
      labelId[name] = created.id
      ok(`标签 ${name}`)
    } catch (e) {
      if (!/40900|已存在/.test(e.message)) throw e // 并发/重复：重拉即可
      await loadLabels()
    }
  }
}

/* 3. 上传封面（SVG） */
const COVERS = [
  ['缓存', 'CACHE'], ['组件', 'COMPONENT'], ['晨雾', 'MORNING MIST'],
  ['类型', 'TYPELAB'], ['构建', 'BUILD'], ['长曝光', 'LONG EXPOSURE'],
  ['归档', 'ARCHIVE'], ['夜航', 'NIGHT FLIGHT'],
]
const coverIds = []
for (const [word, sub] of COVERS) {
  const up = await admin.upload(`cover-${svgSeq}-${word}.svg`, 'image/svg+xml', coverSvg(word, sub))
  coverIds.push({ id: up.id, storageKey: up.storageKey })
}
ok(`封面 SVG × ${coverIds.length}`)

/* 3.5 附件文档（非图片 → file_attachments，供文件管理页与总览 files 指标） */
const DOCS = [
  ['发布检查清单.md', 'text/markdown', '# 发布检查清单\n\n- [ ] Flyway 迁移通过\n- [ ] JWT 密钥 ≥32 字节\n- [ ] ALLOWED_ORIGINS 与前端来源一致\n- [ ] 备份脚本 cron 已配置\n'],
  ['访问日志采样-1106.txt', 'text/plain', '2026-11-06 09:00 GET /api/v1/portal/home 200 12ms\n2026-11-06 09:01 GET /api/v1/media/xxx 200 8ms\n2026-11-06 09:02 POST /api/v1/comments 200 34ms\n'],
]
{
  const known = new Set(
    (await admin.get('/api/v1/admin/files', { page: 1, pageSize: 50 })).records
      .map((f) => f.originalName),
  )
  let n = 0
  for (const [name, mime, content] of DOCS) {
    if (known.has(name)) continue
    await admin.upload(name, mime, content)
    n++
  }
  ok(`附件文档 × ${n}（文件管理页 / 总览 files 指标）`)
}

/* 3.6 最小合法 PDF（手写，仅占位展示用） */
const PDF = Buffer.from(
  '%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n' +
  '2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n' +
  '3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]/Resources<</Font<</F1 4 0 R>>>>/Contents 5 0 R>>endobj\n' +
  '4 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj\n' +
  '5 0 obj<</Length 44>>stream\nBT /F1 24 Tf 72 770 Td (LinBlog seed doc) Tj ET\nendstream\nendobj\n' +
  'trailer<</Root 1 0 R>>\n%%EOF',
  'utf8',
)
{
  const known = new Set(
    (await admin.get('/api/v1/admin/files', { page: 1, pageSize: 50 })).records
      .map((f) => f.originalName),
  )
  if (!known.has('LinBlog-种子文档.pdf')) {
    await admin.upload('LinBlog-种子文档.pdf', 'application/pdf', PDF)
    ok('附件 LinBlog-种子文档.pdf')
  }
}

/* 4. 文章（标题去重；含置顶 / 草稿 / 回收站各态） */
const ARTICLES = [
  { title: '把 P99 延迟压到 90ms：一次缓存层重构', labels: ['后端手记', '性能优化'], cover: 0, pin: true,
    paras: ['从一次线上事故出发，回顾我们对缓存层做的三处关键改造：热点 key 本地化、击穿保护改为逻辑过期、以及把大 Value 拆出主链路。', '最终 P99 从 380ms 压到 90ms。这篇文章把压测数据、取舍过程和两处返工都如实记录下来。'] },
  { title: '组件设计规范：我沉淀的 12 条原则', labels: ['前端手记', '设计随笔'], cover: 1,
    paras: ['命名、状态、可访问性、与设计稿的协作流程——这套规范在实际项目里迭代了三轮。', '原则不在多，在于每一条都对应一次真实的返工。本文给出全部 12 条与正反例。'] },
  { title: '类型体操到底有没有用', labels: ['前端手记'], cover: 3,
    paras: ['很多人以为类型体操只是炫技。但当业务复杂到一定程度，一套好的类型约束能省下大量运行时检查和线上排查。', '本文用三个真实需求演示：模板字面量类型推导表单字段、判别联合收紧状态机、以及用 satisfies 替代一半的 as。'] },
  { title: '水库晨雾：一组清晨的照片', labels: ['摄影日记', '生活碎片'], cover: 2,
    paras: ['周末去了城郊的水库，拍了一组清晨的薄雾。设备是一台陪伴多年的老相机。', '雾把所有轮廓都泡软了，快门声都显得突兀。挑了九张，按时间排。'] },
  { title: '博客重写复盘：从 Express 到 Spring Boot', labels: ['后端手记'], cover: 6,
    paras: ['把博客从零重写了一遍。领域模型不变，换的是存储位置、认证载体、富文本方案、类型断层与工程化底线这五样基础设施。', '本文是整体架构与取舍的复盘：为什么 Cookie 而不是 localStorage、为什么 Flyway、为什么 22 张表。'] },
  { title: 'Vite 冷启动与 HMR 原理拆解', labels: ['前端手记', '性能优化'], cover: 4,
    paras: ['用一篇文章把 Vite 的冷启动、依赖预构建、HMR 原理讲清楚。', '附三个真实项目的优化前后对比数据：冷启动 8.2s → 1.1s。'] },
]
const articleIds = []
{
  const byIdTitle = new Map(
    (await admin.get('/api/v1/admin/contents', { page: 1, pageSize: 50 })).records
      .map((r) => [r.title, r.id]),
  )
  let n = 0
  for (const a of ARTICLES) {
    if (byIdTitle.has(a.title)) { articleIds.push(byIdTitle.get(a.title)); continue } // 重跑回填现有 id
    const created = await admin.post('/api/v1/admin/contents', {
      contentType: 'article', title: a.title,
      introduce: a.paras[0].slice(0, 60),
      body: doc(...a.paras),
      coverMediaId: coverIds[a.cover]?.id,
      labelIds: a.labels.map((l) => labelId[l]).filter(Boolean),
      state: 'published',
    })
    articleIds.push(created.id)
    if (a.pin) await admin.patch(`/api/v1/admin/contents/${created.id}`, { isPinned: true })
    n++
  }
  ok(`文章（新发布 ${n}，共 ${articleIds.length}）`)
  // 一篇草稿 + 一篇回收站，让后台管理页有状态可看
  if (!byIdTitle.has('草稿箱里的开头：关于「慢写作」')) {
    const draft = await admin.post('/api/v1/admin/contents', {
      contentType: 'article', title: '草稿箱里的开头：关于「慢写作」',
      introduce: '还没写完的一篇。', body: doc('只写了开头两段，先存草稿。'),
      labelIds: [labelId['生活碎片']].filter(Boolean), state: 'draft',
    })
    ok(`草稿文章 id=${draft.id}`)
  }
  const trashTitle = '发错的一篇：测试占位'
  if (!byIdTitle.has(trashTitle)) {
    const t = await admin.post('/api/v1/admin/contents', {
      contentType: 'article', title: trashTitle, introduce: '占位', body: doc('占位内容'), state: 'published',
    })
    await admin.del(`/api/v1/admin/contents/${t.id}`)
    ok(`回收站文章 id=${t.id}（已删入回收站）`)
  }
}

/* 5. 画廊（contentType=image，封面即作品） */
const GALLERY = [
  { title: '城市夜景 · 长曝光', cover: 5, intro: '天桥上 thirty 秒长曝光，车流拉成光的河。' },
  { title: '山间的清晨', cover: 2, intro: '雾还没散，先听见鸟叫。' },
  { title: '海边落日', cover: 4, intro: '太阳沉下去的那七分钟。' },
  { title: '老街的猫', cover: 6, intro: '它盯了我很久，最后打了个哈欠。' },
  { title: '雪松与石阶', cover: 7, intro: '下过雪的石阶，只有我一行脚印。' },
  { title: '灯塔与归船', cover: 3, intro: '灯塔亮起时，正好有一艘归船。' },
]
const galleryIds = []
{
  const byIdTitle = new Map(
    (await admin.get('/api/v1/admin/contents', { page: 1, pageSize: 50, contentType: 'image' })).records
      .map((r) => [r.title, r.id]),
  )
  for (const g of GALLERY) {
    if (byIdTitle.has(g.title)) { galleryIds.push(byIdTitle.get(g.title)); continue } // 重跑回填现有 id
    const created = await admin.post('/api/v1/admin/contents', {
      contentType: 'image', title: g.title, introduce: g.intro,
      body: doc(`${g.intro}`),
      coverMediaId: coverIds[g.cover]?.id,
      labelIds: [labelId['摄影日记']].filter(Boolean),
      state: 'published',
    })
    galleryIds.push(created.id)
  }
  ok(`画廊作品 ${galleryIds.length} 件`)
}

/* 6. 日记（一天一篇，跨两个月；天气走 8 值白名单） */
const DIARIES = [
  ['2026-11-05', '晴天', '平静', ['今天的事情不多，写点随手记。窗外的光很好，适合读一会儿书再开始工作。']],
  ['2026-11-02', '多云', '充实', ['把缓存层的收尾工作做完了。下午煮了壶红茶，配完最后一章的图。']],
  ['2026-10-30', '大雨', '疲惫', ['加班到很晚。雨太大，出租车等了二十分钟。回家路上听完了那期播客。']],
  ['2026-10-26', '阵风', '平静', ['风很大，去江边走了一圈。看别人放风筝，线放得很长。']],
  ['2026-10-21', '雷雨', '兴奋', ['雷雨夜写代码意外地专注。把类型体操那篇的初稿写完了。']],
  ['2026-10-15', '雾霾', '低落', ['天灰蒙蒙的。给老街的猫拍了张照片，它还是那副爱答不理的样子。']],
  ['2026-10-08', '大雪', '惊喜', ['十月就下雪， uncommon。院子里的雪松压了一层白，拍了两卷。']],
  ['2026-10-01', '夜晚', '怀念', ['假期第一天。翻出旧硬盘，看到十年前写的第一个博客后台。']],
]
{
  const known = new Set(
    (await admin.get('/api/v1/admin/diaries', { page: 1, pageSize: 50 })).records
      .map((r) => r.diaryDate),
  )
  let n = 0
  for (const [date, weather, mood, paras] of DIARIES) {
    if (known.has(date)) continue
    await admin.post('/api/v1/admin/diaries', {
      diaryDate: date, weather, mood, body: doc(...paras), state: 'published',
    })
    n++
  }
  ok(`日记 ${n} 篇（覆盖 10–11 月）`)
}

/* 7. 留言墙（不同用户留言 + 一条回复；幂等：按 body 去重） */
{
  const existing = (await admin.get('/api/v1/guestbook', { page: 1, pageSize: 50 })).records ?? []
  const has = (body) => existing.some((g) => g.body === body)
  let first = existing.find((g) => g.body.startsWith('路过留个爪印'))
  if (!has('路过留个爪印：缓存那篇写得真好，等一个续集。')) {
    const made = await userClients.xiaoman.post('/api/v1/guestbook', { body: '路过留个爪印：缓存那篇写得真好，等一个续集。' })
    first = made.entry ?? made
    ok('留言（林小满）')
  }
  if (!has('博客的新版面好好看，衬线大标题很舒服。')) {
    await userClients.suwan.post('/api/v1/guestbook', { body: '博客的新版面好好看，衬线大标题很舒服。' })
  }
  if (!has('求分享摄影的后期参数，尤其是晨雾那组。')) {
    await userClients.chenmo.post('/api/v1/guestbook', { body: '求分享摄影的后期参数，尤其是晨雾那组。' })
  }
  if (!has('谢谢大家，续集已经在写了。后期参数整理好会发在摄影日记里。') && first?.id) {
    await admin.post('/api/v1/guestbook', { body: '谢谢大家，续集已经在写了。后期参数整理好会发在摄影日记里。', replyToId: first.id })
  }
  ok('留言就绪（3 条 + 1 回复）')
}

/* 8. 评论（文章 / 画廊 / 日记）+ 评论点赞；幂等：按 body 匹配已有评论 */
async function findComment(targetType, targetId, body) {
  const t = await admin.get(`/api/v1/portal/${targetType}/${targetId}/comments`)
  const all = [...(t.rootComments ?? []), ...(t.replyComments ?? [])]
  return all.find((c) => c.body === body)
}
{
  const aId = articleIds[0]
  let c1 = await findComment('article', aId, '「逻辑过期」那段能否展开讲讲？我这边遇到过热点 key 的坑。')
  if (!c1) {
    c1 = await userClients.xiaoman.post('/api/v1/comments', { targetType: 'article', targetId: aId, body: '「逻辑过期」那段能否展开讲讲？我这边遇到过热点 key 的坑。' })
    ok('评论（林小满 → 文章）')
  }
  if (!(await findComment('article', aId, '压测数据很扎实，收藏了。'))) {
    await userClients.suwan.post('/api/v1/comments', { targetType: 'article', targetId: aId, body: '压测数据很扎实，收藏了。' })
  }
  const replyBody = '可以，下一篇就写这个。'
  if (!(await findComment('article', aId, replyBody))) {
    await admin.post('/api/v1/comments', { targetType: 'article', targetId: aId, parentId: c1.id, replyToCommentId: c1.id, body: replyBody })
  }
  if (!(await findComment('image', galleryIds[0], '这张的光轨太漂亮了，问下快门参数？'))) {
    await userClients.chenmo.post('/api/v1/comments', { targetType: 'image', targetId: galleryIds[0], body: '这张的光轨太漂亮了，问下快门参数？' })
  }
  if (!(await findComment('diary', 1, '「日子很轻，记下来就沉成了故事」，喜欢这句。'))) {
    await userClients.suwan.post('/api/v1/comments', { targetType: 'diary', targetId: 1, body: '「日子很轻，记下来就沉成了故事」，喜欢这句。' })
  }
  ok('评论就绪（4 + 1 回复，跨文章/画廊/日记）')
  // 评论点赞：liked 才说明已赞（toggle 幂等，直接重发会取消）
  if (c1 && !c1.liked) await admin.post(`/api/v1/comments/${c1.id}/praise`)
  ok('评论点赞就绪')
}

/* 9. 内容点赞（interactions toggle） */
{
  await userClients.suwan.post('/api/v1/interactions', { targetType: 'content', targetId: articleIds[0] })
  await userClients.chenmo.post('/api/v1/interactions', { targetType: 'content', targetId: articleIds[0] })
  await userClients.xiaoman.post('/api/v1/interactions', { targetType: 'content', targetId: galleryIds[0] })
  await admin.post('/api/v1/interactions', { targetType: 'content', targetId: galleryIds[1] })
  ok('内容点赞 × 4')
}

/* 10. 举报一条评论（留给举报工作台待处理） */
{
  const thread = await admin.get('/api/v1/portal/article/' + articleIds[0] + '/comments')
  // CommentThread 形状：{ rootComments: [...], replyComments: [...] }
  const root = (thread.rootComments ?? []).find((c) => c.body?.includes('快门参数')) ?? (thread.rootComments ?? [])[0]
  const already = (await admin.get('/api/v1/admin/comment-reports', { page: 1, pageSize: 50 })).records ?? []
  if (root && !already.some((r) => r.commentId === root.id)) {
    await userClients.chenmo.post(`/api/v1/comments/${root.id}/report`, { reason: 'spam', detail: '疑似复制粘贴的水评，麻烦审核看一下。' })
    ok(`举报评论 id=${root.id}（待处理，供举报工作台演示）`)
  } else if (root) {
    ok('举报已存在，跳过')
  }
}

/* 11. 私信（两个会话，双向消息；幂等：从 admin 侧查同对端会话是否已存在。
        注意 GET /messages/conversations 需要 message.private（后台权限），普通用户不可列） */
{
  const convPage = await admin.get('/api/v1/messages/conversations', { page: 1, pageSize: 50 })
  const peers = new Set((convPage.records ?? []).map((c) => c.peer?.id))
  if (!peers.has(2)) {
    const c1 = await admin.post('/api/v1/messages/conversations', { recipientId: 2, body: '小满，下期「组件规范」想请你画配图，档期如何？' })
    await userClients.xiaoman.post(`/api/v1/messages/conversations/${c1.conversationId}`, { body: '可以呀，这周末给你两版草图。' })
    ok(`私信会话①（id ${c1.conversationId}，双向）`)
  }
  if (!peers.has(3)) {
    const c2 = await userClients.suwan.post('/api/v1/messages/conversations', { recipientId: 1, body: '站长好，画廊详情页的图片能点开放大吗？' })
    await admin.post(`/api/v1/messages/conversations/${c2.conversationId}`, { body: '可以的，点图片会出现灯箱，滚动可翻页。' })
    ok(`私信会话②（id ${c2.conversationId}，双向）`)
  }
  ok('私信就绪（两个会话各一对消息）')
}

/* 12. 合集（subset + items） */
{
  const subsets = await admin.get('/api/v1/admin/subsets', { page: 1, pageSize: 50 })
  if (!(subsets.records ?? []).some((s) => s.name === '2026 精选')) {
    const ids = articleIds.filter(Boolean).slice(0, 4)
    const s = await admin.post('/api/v1/admin/subsets', {
      name: '2026 精选', description: '今年最想被读到的四篇：缓存重构、组件规范、类型体操与一次重写复盘。',
      coverMediaId: coverIds[1]?.id, contentIds: ids,
    })
    await admin.put(`/api/v1/admin/subsets/${s.id}/items`, { contentIds: [...ids, ...galleryIds.filter(Boolean).slice(0, 2)] })
    ok(`合集「2026 精选」id=${s.id}（4 文 + 2 图）`)
  }
}

/* 13. 浏览量：走真实上报端点 POST /views（前端详情页加载后即报它；30 分钟去重，
        visitorKey=sha256(ip|ua)——带 XSRF Cookie 过过滤器，伪造不同 XFF+UA 制造多访客） */
{
  const viewer = new Client('viewer', '10.66.0.1')
  await viewer.get('/api/v1/portal/home').catch(() => {}) // 种 XSRF
  const targets = [
    ...articleIds.filter(Boolean).map((id) => ['content', id]),
    ...galleryIds.filter(Boolean).map((id) => ['content', id]),
    ...Array.from({ length: 8 }, (_, i) => ['diary', i + 1]), // 日记 id 从 1 连续
  ]
  let n = 0
  for (let round = 0; round < 5; round++) {
    for (const [targetType, targetId] of targets) {
      try {
        await viewer.req('POST', '/api/v1/views', { targetType, targetId }, {
          headers: {
            'X-Forwarded-For': `10.66.${round}.${n % 240 + 1}`,
            'User-Agent': `seed-viewer/${round}/${n}`,
          },
        })
        n++
      } catch { /* 去重窗口内重复计数返回 false 而非报错；这里容错跳过 */ }
    }
  }
  ok(`浏览上报 × ${n}（浏览量 + 访问趋势）`)
}

/* 13.5 清理历史重跑造成的重复评论 / 重复留言（每组同 body 保留最小 id） */
{
  const targets = [
    ['article', articleIds[0]],
    ['image', galleryIds[0]],
    ['diary', 1],
  ]
  let removed = 0
  for (const [type, id] of targets) {
    const t = await admin.get(`/api/v1/portal/${type}/${id}/comments`)
    const groups = new Map()
    for (const c of [...(t.rootComments ?? []), ...(t.replyComments ?? [])]) {
      const key = c.body
      if (!groups.has(key)) groups.set(key, [])
      groups.get(key).push(c)
    }
    for (const list of groups.values()) {
      for (const dup of list.slice(1).sort((a, b) => b.id - a.id)) {
        try { await admin.del(`/api/v1/comments/${dup.id}`); removed++ } catch { /* 无权/已删忽略 */ }
      }
    }
  }
  const gbs = (await admin.get('/api/v1/guestbook', { page: 1, pageSize: 50 })).records ?? []
  const gbGroups = new Map()
  for (const g of gbs) {
    if (!gbGroups.has(g.body)) gbGroups.set(g.body, [])
    gbGroups.get(g.body).push(g)
  }
  for (const list of gbGroups.values()) {
    for (const dup of list.slice(1).sort((a, b) => b.id - a.id)) {
      try { await admin.del(`/api/v1/guestbook/${dup.id}`); removed++ } catch { /* ignore */ }
    }
  }
  ok(`历史重复清理 × ${removed}`)
}

/* ---------------- 汇总 ---------------- */
const summary = await admin.get('/api/v1/admin/overview/summary')
console.log('\n===== 完成。后台总览口径 =====')
console.log(JSON.stringify(summary, null, 2).slice(0, 900))
console.log(`\n共 ${results.length} 步全部成功。`)
