// phone-bridge/index.mjs — 把 Android 手机能力注册成引擎的工具。
//
// ## 为什么是这个形状
//
// 引擎(Node,跑在 :engine 进程)不能直接调 Android 的 API —— 那边是
// Kotlin。所以能力实现在 App 主进程里跑一个 HTTP 服务,Node 通过
// loopback 调它:
//
//   模型 → ctx.registerTool 的 execute() → HTTP POST → Kotlin → Android API
//
// 目录靠 env 指过去(LANAGENT_CAP_BASE),不是硬编码:同一份 mod 也能在
// 桌面版 lan-agent 上跑(那时候 base 指本机的另一个服务)。
//
// ## 为什么不走 DSH 那套 socketpair 能力通道
//
// dsh 用 socketpair 传 newline-delimited JSON-RPC,是因为它的引擎是
// C++ 的 dsh,不是 Node —— 没有现成的 HTTP 客户端能复用。我们这边引擎
// 就是 Node,HTTP 是现成的,多一个协议没有收益。

const CAP_BASE = process.env.LANAGENT_CAP_BASE
const CAP_TOKEN = process.env.LANAGENT_CAP_TOKEN || ''

/**
 * 调一次能力。
 *
 * 失败**不抛异常** —— 返回一句人话。工具抛异常时模型看到的是栈,
 * 那对「手机没插卡」这种用户能理解的情况毫无帮助。
 */
async function callCap(path, args = {}) {
  if (!CAP_BASE) return { ok: false, error: '能力桥未配置(LANAGENT_CAP_BASE 为空)' }
  try {
    const res = await fetch(`${CAP_BASE}${path}`, {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        ...(CAP_TOKEN ? { authorization: `Bearer ${CAP_TOKEN}` } : {}),
      },
      body: JSON.stringify(args),
    })
    const text = await res.text()
    let body
    try {
      body = JSON.parse(text)
    } catch {
      body = { raw: text }
    }
    if (!res.ok) {
      return { ok: false, error: body?.error || `能力调用失败:HTTP ${res.status}` }
    }
    return body
  } catch (e) {
    return { ok: false, error: `能力调用失败:${e?.message || e}` }
  }
}

/** 统一的工具定义:形状一样,只有 capability 名和参数不同。 */
function capTool({ capability, description, schema }) {
  return {
    name: capability,
    description,
    inputSchema: schema,
    async execute(args) {
      const out = await callCap(`/cap/${capability}`, args ?? {})
      if (!out || out.ok !== true) {
        return { ok: false, error: out?.error || '能力调用失败' }
      }
      return out
    },
  }
}

export function register(ctx) {
  ctx.registerTool(
    capTool({
      capability: 'phone_screenshot',
      description:
        '截取当前手机屏幕,返回图片的 base64。用来「看看屏幕上是什么」——' +
        '用户说「我屏幕上有个报错」的时候用它。',
      schema: {
        properties: {},
      },
    }),
  )

  ctx.registerTool(
    capTool({
      capability: 'phone_notifications',
      description:
        '读取手机当前的通知栏(标题 + 正文 + 应用名)。用来回答「我刚收到什么消息」。',
      schema: {
        properties: {
          limit: {
            type: 'number',
            description: '最多返回几条,默认 20',
          },
        },
      },
    }),
  )

  ctx.registerTool(
    capTool({
      capability: 'phone_clipboard',
      description: '读取手机剪贴板当前内容。',
      schema: {
        properties: {},
      },
    }),
  )

  ctx.registerTool(
    capTool({
      capability: 'phone_open_app',
      description: '按包名启动手机上的 App。包名可以从手机的应用列表工具拿到。',
      schema: {
        properties: {
          package: { type: 'string', description: 'Android 包名,例如 com.android.settings' },
        },
        required: ['package'],
      },
    }),
  )

  ctx.registerTool(
    capTool({
      capability: 'phone_list_apps',
      description: '列出手机上安装的 App(包名 + 应用名)。',
      schema: {
        properties: {
          query: { type: 'string', description: '按名字过滤,不传则全列' },
        },
      },
    }),
  )

  ctx.registerTool(
    capTool({
      capability: 'phone_settings',
      description:
        '读取/修改系统设置(Wi-Fi 开关、亮度、音量等)。读写同一套工具,' +
        'write=true 表示写。',
      schema: {
        properties: {
          key: { type: 'string', description: '设置项名,例如 wifi / brightness / volume' },
          value: { type: 'string', description: '要写的值;读的时候不用传' },
          write: { type: 'boolean', description: 'true=写,false=读' },
        },
        required: ['key'],
      },
    }),
  )
}
