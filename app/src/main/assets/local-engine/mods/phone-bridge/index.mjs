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

  // ── 通知动作 ────────────────────────────────────────────────────────────

  ctx.registerTool(
    capTool({
      capability: 'notification_open',
      description:
        '打开一条通知(像点它那样),或者点它上面的某个动作按钮(比如「标记已读」)。' +
        'key 用 phone_notifications 给的那个。',
      schema: {
        properties: {
          key: { type: 'string', description: '通知的 key' },
          action: { type: 'string', description: '要点哪个动作按钮的标题(不给就点通知本身)' },
        },
        required: ['key'],
      },
    }),
  )

  ctx.registerTool(
    capTool({
      capability: 'notification_dismiss',
      description: '把通知从通知栏清掉(可多条)。key 用 phone_notifications 给的那些。',
      schema: {
        properties: {
          keys: { type: 'array', items: { type: 'string' }, description: '要清掉的通知 key 列表' },
        },
      },
    }),
  )

  ctx.registerTool(
    capTool({
      capability: 'notification_reply',
      description:
        '直接回一条通知(走通知自带的回复输入框 —— 微信 / QQ / 短信这类)。' +
        '只有带回复框的通知才能回,别的会明确说不能。',
      schema: {
        properties: {
          key: { type: 'string', description: '通知的 key' },
          text: { type: 'string', description: '要回复的内容' },
        },
        required: ['key', 'text'],
      },
    }),
  )

  // ── 屏幕操作(无障碍)────────────────────────────────────────────────────
  //
  // 这组是这台手机上 agent 相对于桌面唯一独占的能力:它能「看着屏幕点」。
  // 靠的是无障碍服务给的**控件树**(不是像素),所以点得准、输入不依赖输入法。
  //
  // 前置条件:用户得在系统设置里打开「无障碍 → lan-agent」。没开的话能力层
  // 返回的是一句可执行的提示(去哪开),不是干巴巴的失败。

  ctx.registerTool(
    capTool({
      capability: 'screen_read',
      description:
        '读当前屏幕:返回屏幕上所有可交互元素的列表(类型 / 文字 / resource-id / ' +
        '能不能点 / 中心坐标),每个元素带一个 id(如 e12)。**元素 id 跨读稳定** —— ' +
        '同一个元素下次读还是同一个 id,所以可以「读一次 → 拿 id 去点」。' +
        '页面是自绘的(读不到控件)时用 screen_capture 看截图。',
      schema: {
        properties: {
          max: { type: 'number', description: '最多返回多少个元素,默认 200' },
        },
      },
    }),
  )

  ctx.registerTool(
    capTool({
      capability: 'screen_tap',
      description:
        '点一下。三种寻址:id(screen_read 给的 e12)、text(屏幕上的文字)、' +
        'x/y(坐标)。优先点控件本身(准),控件点不动才退回坐标。',
      schema: {
        properties: {
          id: { type: 'string', description: '元素 id,例如 e12' },
          text: { type: 'string', description: '屏幕上要找的文字' },
          x: { type: 'number', description: '屏幕坐标 x' },
          y: { type: 'number', description: '屏幕坐标 y' },
        },
      },
    }),
  )

  ctx.registerTool(
    capTool({
      capability: 'screen_type',
      description:
        '往输入框里写文字。给 id 就写进那个框;不给 id 就写进当前有焦点的输入框。' +
        '直接设值(不是模拟键盘),所以不依赖输入法、也不会触发中文候选。',
      schema: {
        properties: {
          id: { type: 'string', description: '输入框的元素 id' },
          text: { type: 'string', description: '要输入的文字' },
        },
        required: ['text'],
      },
    }),
  )

  ctx.registerTool(
    capTool({
      capability: 'screen_swipe',
      description: '在屏幕上滑动(坐标 → 坐标)。用来翻页、拖动、下拉刷新。',
      schema: {
        properties: {
          x1: { type: 'number', description: '起点 x' },
          y1: { type: 'number', description: '起点 y' },
          x2: { type: 'number', description: '终点 x' },
          y2: { type: 'number', description: '终点 y' },
          duration: { type: 'number', description: '滑动时长(毫秒),默认 300' },
        },
        required: ['x1', 'y1', 'x2', 'y2'],
      },
    }),
  )

  ctx.registerTool(
    capTool({
      capability: 'screen_scroll',
      description:
        '滚动当前页面。direction=forward(默认)往下,backward 往上。' +
        '优先让可滚控件自己滚,滚不动才用滑动手势兜底。',
      schema: {
        properties: {
          direction: { type: 'string', description: 'forward / backward' },
          id: { type: 'string', description: '要滚的容器元素 id(不给就滚当前焦点)' },
        },
      },
    }),
  )

  ctx.registerTool(
    capTool({
      capability: 'screen_key',
      description:
        '按键。back(返回)/home(主页)/recents(最近任务)/notifications(下拉通知)/' +
        'quick_settings(快捷设置)/lock_screen(息屏)直接可用;' +
        'delete 删输入框最后一个字;enter 会去找屏幕上的「搜索 / 确定 / 发送」按钮点它。',
      schema: {
        properties: {
          key: {
            type: 'string',
            description:
              'back / home / recents / notifications / quick_settings / lock_screen / enter / delete',
          },
        },
        required: ['key'],
      },
    }),
  )

  ctx.registerTool(
    capTool({
      capability: 'screen_wait',
      description:
        '等某个条件成立再返回:某段文字出现(默认)/消失(gone=true),或某个 App ' +
        '进到前台。比 sleep 后再读屏靠谱 —— 条件一满足就立刻返回。',
      schema: {
        properties: {
          text: { type: 'string', description: '要等的文字' },
          package: { type: 'string', description: '要等进前台的 App 包名' },
          gone: { type: 'boolean', description: 'true = 等它消失' },
          timeout: { type: 'number', description: '最多等多少毫秒,默认 10000' },
        },
      },
    }),
  )

  ctx.registerTool(
    capTool({
      capability: 'screen_capture',
      description:
        '截当前屏,存成 PNG 并返回路径(用 Read 工具读那个路径就能看见屏幕)。' +
        '走无障碍截图,不需要用户授权。',
      schema: {
        properties: {},
      },
    }),
  )
}
