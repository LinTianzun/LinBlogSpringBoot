// Download Google Fonts (Newsreader + DM Sans) locally and rewrite CSS to local paths.
// Run: node scripts/fetch-fonts.mjs
import fs from 'node:fs'
import path from 'node:path'

const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36'
const family =
  'Newsreader:ital,opsz,wght@0,6..72,300;0,6..72,400;0,6..72,500;1,6..72,300;1,6..72,400&family=DM+Sans:wght@400;500;600;700'
const url = `https://fonts.googleapis.com/css2?family=${family}&display=swap`

const outDir = path.resolve('public/fonts')
fs.mkdirSync(outDir, { recursive: true })

const css = await (await fetch(url, { headers: { 'User-Agent': UA } })).text()

// Collect every @font-face block (we keep both latin and latin-ext for our fonts).
const faceRe = /@font-face\s*\{[^}]*\}/g
const blocks = css.match(faceRe) || []

let out = ''
let idx = 0
const jobs = []
for (const block of blocks) {
  const fam = (block.match(/font-family:\s*'([^']+)'/) || [])[1] || 'font'
  const sub = (block.match(/\/\*\s*([^*]+?)\s*\*\//) || [])[1] || ''
  const urlMatch = block.match(/url\((https:\/\/[^)]+)\)/)
  if (!urlMatch) { out += block + '\n\n'; continue }
  const remote = urlMatch[1]
  const safeFam = fam.toLowerCase().replace(/\s+/g, '-')
  const fname = `${safeFam}-${(sub || 'subset').replace(/[^a-z]/gi, '')}-${idx++}.woff2`
  const localBlock = block.replace(remote, `/fonts/${fname}`)
  out += localBlock + '\n\n'
  jobs.push({ remote, fname })
}

await Promise.all(
  jobs.map(async ({ remote, fname }) => {
    const buf = Buffer.from(await (await fetch(remote, { headers: { 'User-Agent': UA } })).arrayBuffer())
    fs.writeFileSync(path.join(outDir, fname), buf)
    console.log('saved', fname, buf.length, 'bytes')
  }),
)

fs.writeFileSync(path.join(outDir, 'fonts.css'), out)
console.log('wrote fonts.css', out.length, 'chars,', jobs.length, 'files')
