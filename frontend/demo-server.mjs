// Minimal static file server for frontend/public (demos + fonts). No deps.
import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const ROOT = path.join(__dirname, 'public')
const PORT = 4320
const MIME = { '.html':'text/html; charset=utf-8', '.css':'text/css; charset=utf-8', '.js':'text/javascript', '.woff2':'font/woff2', '.woff':'font/woff', '.svg':'image/svg+xml', '.png':'image/png', '.jpg':'image/jpeg', '.json':'application/json' }

http.createServer((req, res) => {
  let p = decodeURIComponent(req.url.split('?')[0])
  if (p === '/') p = '/demos/index.html'
  let fp = path.join(ROOT, p)
  if (!fp.startsWith(ROOT)) { res.writeHead(403); return res.end('no') }
  fs.stat(fp, (e, st) => {
    if (e || !st.isFile()) { res.writeHead(404); return res.end('not found: ' + p) }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(fp).toLowerCase()] || 'application/octet-stream' })
    fs.createReadStream(fp).pipe(res)
  })
}).listen(PORT, () => console.log('demo server http://localhost:' + PORT))
