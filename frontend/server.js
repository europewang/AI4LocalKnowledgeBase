import { createServer } from 'node:http'
import { request as httpRequest } from 'node:http'
import { request as httpsRequest } from 'node:https'
import { createReadStream, existsSync, statSync } from 'node:fs'
import { extname, join, normalize } from 'node:path'

const env = globalThis.process?.env || {}
const cwd = globalThis.process?.cwd ? globalThis.process.cwd() : '.'
const PORT = Number(env.PORT || 80)
const BACKEND_URL = new URL(env.BACKEND_URL || 'http://backend:8083')
const DIST_DIR = join(cwd, 'dist')

const CONTENT_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.ico': 'image/x-icon',
  '.txt': 'text/plain; charset=utf-8',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2'
}

function proxyApi(req, res) {
  const isHttps = BACKEND_URL.protocol === 'https:'
  const client = isHttps ? httpsRequest : httpRequest
  const targetPath = req.url || '/'
  const headers = { ...req.headers }
  headers.host = BACKEND_URL.host
  headers.connection = headers.connection || 'keep-alive'
  const requestOptions = {
    protocol: BACKEND_URL.protocol,
    hostname: BACKEND_URL.hostname,
    port: BACKEND_URL.port || (isHttps ? 443 : 80),
    method: req.method,
    path: targetPath,
    headers
  }
  const proxyReq = client(requestOptions, (proxyRes) => {
    res.writeHead(proxyRes.statusCode || 502, proxyRes.headers)
    proxyRes.pipe(res)
  })
  proxyReq.on('error', () => {
    if (!res.headersSent) {
      res.writeHead(502, { 'Content-Type': 'application/json; charset=utf-8' })
    }
    res.end(JSON.stringify({ code: 502, message: '前端代理后端失败' }))
  })
  req.pipe(proxyReq)
}

function sendFile(filePath, res, noCache = false) {
  const extension = extname(filePath)
  const contentType = CONTENT_TYPES[extension] || 'application/octet-stream'
  const stats = statSync(filePath)
  const headers = {
    'Content-Type': contentType,
    'Content-Length': stats.size
  }
  if (noCache) {
    headers['Cache-Control'] = 'no-store, no-cache, must-revalidate, max-age=0'
    headers.Pragma = 'no-cache'
    headers.Expires = '0'
  }
  res.writeHead(200, headers)
  createReadStream(filePath).pipe(res)
}

function resolveStaticPath(urlPath) {
  const pathname = urlPath.split('?')[0].split('#')[0]
  const cleaned = pathname === '/' ? '/index.html' : pathname
  const safePath = normalize(cleaned).replace(/^(\.\.(\/|\\|$))+/, '')
  return join(DIST_DIR, safePath)
}

const server = createServer((req, res) => {
  const urlPath = req.url || '/'
  if (urlPath.startsWith('/api/')) {
    proxyApi(req, res)
    return
  }
  const filePath = resolveStaticPath(urlPath)
  const indexPath = join(DIST_DIR, 'index.html')
  if (existsSync(filePath) && statSync(filePath).isFile()) {
    const noCache = urlPath === '/' || urlPath.startsWith('/index.html')
    sendFile(filePath, res, noCache)
    return
  }
  if (existsSync(indexPath)) {
    sendFile(indexPath, res, true)
    return
  }
  res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' })
  res.end('Not Found')
})

server.listen(PORT, '0.0.0.0')
