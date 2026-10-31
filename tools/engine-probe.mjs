// engine-probe.mjs — 在设备上跑,验证「本地引擎」这条链路的每一环。
//
// 由 tools/verify-engine-on-device.sh 推到设备上执行(不要在手机上直接跑)。
// 所有可调项走环境变量:P=端口 PROMPT=1 时才发真实消息。
const base = 'http://127.0.0.1:' + process.env.P
const health = await fetch(base + '/api/health').then((r) => r.text())
console.log('HEALTH ' + health)

const ctrl = new AbortController()
const res = await fetch(base + '/api/event', { signal: ctrl.signal })
const rd = res.body.getReader()
const dec = new TextDecoder()
let buf = ''
const events = []
;(async () => {
  try {
    while (true) {
      const { value, done } = await rd.read()
      if (done) break
      buf += dec.decode(value, { stream: true })
      let i
      while ((i = buf.indexOf('\n\n')) >= 0) {
        const frame = buf.slice(0, i)
        buf = buf.slice(i + 2)
        const m = frame.match(/^data: (.*)$/m)
        if (m) {
          try { events.push(JSON.parse(m[1])) } catch {}
        }
      }
    }
  } catch {}
})()
await new Promise((r) => setTimeout(r, 800))
console.log('SSE ' + (events.some((e) => e.type === 'server.connected') ? 'server.connected OK' : 'MISSING'))

if (process.env.PROMPT) {
  const created = await fetch(base + '/api/agent/sessions', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ model: process.env.MODEL, providerId: process.env.PROVIDER_ID }),
  }).then((r) => r.json())
  const sid = created.sessionId
  console.log('SESSION ' + sid)

  const c2 = new AbortController()
  const r2 = await fetch(base + '/api/event?sid=' + encodeURIComponent(sid), { signal: c2.signal })
  const rd2 = r2.body.getReader()
  const d2 = new TextDecoder()
  let b2 = ''
  const ev2 = []
  ;(async () => {
    try {
      while (true) {
        const { value, done } = await rd2.read()
        if (done) break
        b2 += d2.decode(value, { stream: true })
        let i
        while ((i = b2.indexOf('\n\n')) >= 0) {
          const frame = b2.slice(0, i)
          b2 = b2.slice(i + 2)
          const m = frame.match(/^data: (.*)$/m)
          if (m) {
            try { ev2.push(JSON.parse(m[1])) } catch {}
          }
        }
      }
    } catch {}
  })()
  await new Promise((r) => setTimeout(r, 800))

  await fetch(base + '/api/agent/prompt', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      sessionId: sid,
      prompt: '用 Bash 工具执行 echo HELLO_FROM_DEVICE,把输出原样告诉我',
    }),
  })
  await new Promise((r) => setTimeout(r, 45000))
  c2.abort()

  const delta = ev2.filter((e) => e.type === 'runtime.delta').map((e) => e.delta).join('')
  console.log('DELTA ' + JSON.stringify(delta))
  const err = ev2.find((e) => e.type === 'runtime.error')
  if (err) console.log('ERROR ' + JSON.stringify(err).slice(0, 300))
}
ctrl.abort()
