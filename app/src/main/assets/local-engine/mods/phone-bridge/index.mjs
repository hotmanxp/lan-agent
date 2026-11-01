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
      capability: 'clipboard_set',
      description:
        '把一段文字写进手机剪贴板。用来「把这段内容复制给用户」——' +
        '写完之后用户到别的 App 里直接粘贴就行。',
      schema: {
        properties: {
          text: { type: 'string', description: '要写进剪贴板的文字' },
        },
        required: ['text'],
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
      capability: 'app_info',
      description:
        '查一个 App 的详细信息:版本号、安装时间、占用体积、能被外部拉起的入口(activity)。' +
        '要知道 App 装没装、什么版本时用。',
      schema: {
        properties: {
          package: { type: 'string', description: 'Android 包名' },
        },
        required: ['package'],
      },
    }),
  )

  ctx.registerTool(
    capTool({
      capability: 'app_open_link',
      description:
        '用链接打开手机上的页面。http/https 链接开浏览器,' +
        'tel: 打电话,mailto: 发邮件,geo: 打开地图,' +
        '也可以用别的 App 的自定义 scheme(比如打开某个 App 的详情页)。',
      schema: {
        properties: {
          url: { type: 'string', description: '要打开的链接或 scheme' },
          package: {
            type: 'string',
            description: '指定用哪个 App 打开(不给就由系统选默认的)',
          },
        },
        required: ['url'],
      },
    }),
  )

  ctx.registerTool(
    capTool({
      capability: 'app_send_intent',
      description:
        '直接给某个 App 的某个界面发指令(component + extras)。' +
        '比 app_open_link 更底层:能带参数、能精确指定打开哪个界面。' +
        '目标 activity 的全名可以用 app_info 查。',
      schema: {
        properties: {
          package: { type: 'string', description: '目标 App 的包名' },
          class: { type: 'string', description: '目标 activity 的全名(如 com.x.MainActivity)' },
          action: { type: 'string', description: 'Intent action(不给就空 action)' },
          extras: {
            type: 'object',
            description: '额外参数,值可以是字符串 / 数字 / 布尔',
          },
        },
        required: ['package', 'class'],
      },
    }),
  )

  ctx.registerTool(
    capTool({
      capability: 'share_to_app',
      description:
        '把一段文字或一个文件分享给手机上的别的 App(会弹系统的分享菜单,让用户选)。' +
        '文件路径用工作区里的绝对路径。',
      schema: {
        properties: {
          text: { type: 'string', description: '要分享的文字' },
          path: { type: 'string', description: '要分享的文件绝对路径' },
          title: { type: 'string', description: '分享标题' },
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

  ctx.registerTool(
    capTool({
      capability: 'device_status',
      description:
        '读手机当前状态:电量百分比、是否在充电、网络连上没有(是不是 Wi-Fi / 流量)、' +
        'Wi-Fi 名、存储用了多少、勿扰有没有开、各音量档位。' +
        '回答「我手机怎么了」「还剩多少电」「有没有网」这类问题用它。',
      schema: {
        properties: {},
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
      capability: 'screen_set_wheel',
      description:
        '转滚轮 / 选择器(日期选择器、时间选择器、音量条这类)。' +
        '**滚轮不是可滚容器**,screen_scroll 对它无效,得用这个。' +
        '给 id 就转那个滚轮;不给 id 就自动找屏幕上那个滚轮控件。',
      schema: {
        properties: {
          id: { type: 'string', description: '滚轮控件的元素 id' },
          direction: { type: 'string', description: 'forward(默认,往后一格)/ backward(往前一格)' },
          value: { type: 'number', description: '直接设成这个值(只有进度条类控件支持)' },
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

  // ── 交给用户 ──────────────────────────────────────────────────────────────
  //
  // 登录 / 验证码 / 支付确认这类步骤**只能人做**。硬试的后果很实在:
  // 反复输验证码会被风控、误点支付不可逆。所以这里给一条正经的退路 ——
  // 把用户叫过来,然后停下。

  ctx.registerTool(
    capTool({
      capability: 'hand_to_user',
      description:
        '把这一步交回用户去做(需要输验证码、要登录、要支付确认这类只能人做的操作)。' +
        '会发一条通知把用户叫过来。**调完这个就要停下**,别再自己操作屏幕 —— ' +
        '等用户做完用 screen_wait 确认,或者直接在回复里问用户好了没。',
      schema: {
        properties: {
          reason: { type: 'string', description: '为什么要交给用户(比如「要收验证码」)' },
          instruction: { type: 'string', description: '要用户具体做什么' },
        },
      },
    }),
  )
}
