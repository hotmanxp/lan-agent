// tools/apk-serve.js — 局域网 APK 下载服务(0.26.3)
//
// 为什么不用 `npx serve`:它对 .apk 发 `Content-Disposition: inline`,浏览器会
// 尝试「在页面里渲染」APK,结果是白屏,用户看到的现象是「打不开」。
//
// 为什么不用 `python3 -m http.server`:它是 **HTTP/1.0 + 8KB 同步 sendall**,
// 58MB 的 APK 传到一半手机浏览器一握手/重试就断,服务端刷 BrokenPipeError。
// 实测就是这么失败的(手机 192.168.101.162,每次请求都 200 然后中途断)。
//
// 所以自己起一个:HTTP/1.1 keep-alive + `Accept-Ranges: bytes`(断点续传)+
// attachment 头 + 1MB 流式 chunk。纯 Node 内置模块,零依赖。
//
// 用法: node tools/apk-serve.js [port]
// 注意:必须用**已在 macOS 防火墙白名单里的** node 跑(见 AGENTS.md「分享 APK」),
// nvm 切版本后的新二进制不在名单里,外部设备连不上。
'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = Number(process.argv[2]) || 8765;
const ROOT = path.resolve(__dirname, '..', 'app', 'build', 'outputs', 'apk', 'debug');
const CHUNK = 1024 * 1024;

http
  .createServer((req, res) => {
    const name = decodeURIComponent((req.url || '/').split('?')[0]);
    if (name === '/' || name === '/index.html') {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(
        '<meta charset="utf-8"><body style="font:16px -apple-system;padding:24px">' +
          '<p>点击下载:</p><p><a href="/app-debug.apk" style="font-size:18px">app-debug.apk</a></p></body>',
      );
      return;
    }

    // 只放行 apk/debug 目录内的文件,别把整个工程目录挂出去
    const file = path.join(ROOT, path.normalize(name).replace(/^(\.\.[/\\])+/, ''));
    if (!file.startsWith(ROOT) || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('not found');
      return;
    }

    const size = fs.statSync(file).size;
    const base = {
      'Content-Type': 'application/vnd.android.package-archive',
      'Content-Disposition': 'attachment',
      // 不缓存:每次点开都是最新构建的包(手机浏览器 / 下载器很爱按 URL 缓存)
      'Cache-Control': 'no-store',
      'Accept-Ranges': 'bytes',
    };

    // Range:手机下载器断点续传靠它,不支持的话重试会从头再传一遍
    const m = /bytes=(\d*)-(\d*)/.exec(req.headers.range || '');
    if (m) {
      const start = m[1] ? Number(m[1]) : 0;
      const end = m[2] ? Number(m[2]) : size - 1;
      if (start >= size || end >= size || start > end) {
        res.writeHead(416, { 'Content-Range': `bytes */${size}` });
        res.end();
        return;
      }
      res.writeHead(206, {
        ...base,
        'Content-Range': `bytes ${start}-${end}/${size}`,
        'Content-Length': end - start + 1,
      });
      if (req.method === 'HEAD') return res.end();
      fs.createReadStream(file, { start, end }).pipe(res);
      return;
    }

    res.writeHead(200, { ...base, 'Content-Length': size });
    if (req.method === 'HEAD') return res.end();
    fs.createReadStream(file, { highWaterMark: CHUNK }).pipe(res);
  })
  .listen(PORT, '0.0.0.0', () => {
    console.log(`APK 下载: http://0.0.0.0:${PORT}/app-debug.apk  (root=${ROOT})`);
  });
