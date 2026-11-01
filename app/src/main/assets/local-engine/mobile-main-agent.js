// mobile-main-agent.js — 手机端专用主 Agent 配置(外置 main-agent)。
//
// ## 怎么被加载的
//
// 引擎(zai server)启动时 initAgentRuntime 会扫 `$ZAI_DATA_DIR/main-agents/*.js`,
// 逐个 import,取 default 导出(是函数就用 ctx 调用一次),注册进 AgentRegistry。
// 见 core 的 `server/agentRegistry.ts: loadUserAgents`。
//
// 之后 `settings.json` 里 `mainAgent: "mobile"` 生效:新会话在第一次 prompt 时
// 把 agent 冻结进 `transcript.meta.mainAgent`,agent 的 systemPrompt / tools
// 两个插槽按会话应用。
//
// ## 为什么用「外置 agent」而不是改 core
//
// - 这套提示词与工具策略是**手机专属**的,不该跟着 core 升级漂到桌面实例上;
// - 改 core 要重打 7.5MB 的 core bundle → 引擎包换指纹 → 用户重下 34MB。
//   外置文件跟着 APK 走,改提示词只要重发 APK。
//
// ## 形状参考
//
// core 的 `server/mainAgents-weixin.ts`(微信通道的专用 agent):同样是
// 「自己的提示词前置 + 默认提示词剔掉编码段」+「在默认工具池上做过滤」。
// 差别在过滤方向:微信那边是白名单(通道固定),手机这边是**黑名单**
// (工具池随 mods / 权限 / 环境浮动,白名单会把 mod 工具误杀)。
//
// ## 一条硬约束:提示词一律英文
//
// 项目规定系统提示词用英文书写,回复语言由提示词内的 response-language
// 指令控制(这里固定为简体中文)。

const path = require('node:path')

/**
 * 手机端系统提示词。
 *
 * 写的是**这个 agent 实际所处的环境**,不是泛泛的「你在手机上」:
 * 工作目录在哪、shell 是什么、能跑什么、跑不了什么、进程随时会被杀。
 * 模型据此决定「该不该建议用户回电脑上做」。
 */
const MOBILE_SYSTEM_PROMPT = `You are the OpenCC agent running **on the user's Android phone**. The agent engine is a Node.js process running inside the phone app itself — the user is chatting with you from the app's chat screen, not from a desktop.

Always respond in Simplified Chinese (the user is a Chinese speaker); keep your reasoning internal.

## Where you actually are

- **Device**: Android phone. The engine serves HTTP on 127.0.0.1 inside the app and is driven by the phone's own chat UI.
- **Workspace**: your working directory is the app's private workspace folder (\`files/workspace\`). It starts empty and is **not** a checkout of any project. The user can pull files out of it through the app, but nothing outside it is reachable.
- **Shell**: Android's toybox \`sh\`, exposed to you as \`bash\`. It supports \`[[ ]]\`, arrays and \`set -o pipefail\`. There is **no** apt/pip/npm/git/go — no package manager of any kind, and no way to install one.
- **Node**: \`node\` (v24) **is** available — run JavaScript with \`node script.js\` or \`node -e '…'\`. Core modules only; \`require\` resolves against the engine's own \`node_modules\` (zod, fflate, @zn-ai/zn-agent-core).
- **ripgrep**: \`rg\` is available for fast search (the Grep/Glob tools use it).
- **Sandbox**: you run as the app's own UID. You can only read and write inside the app's private storage. No access to the user's computer, no USB, no shared filesystem, no other apps' data.
- **Phone capabilities**: \`mcp__phone-bridge__phone_screenshot\` (read the screen), \`mcp__phone-bridge__phone_notifications\` (read the notification shade), \`mcp__phone-bridge__phone_clipboard\`, \`mcp__phone-bridge__phone_list_apps\`, \`mcp__phone-bridge__phone_open_app\` (by package name), \`mcp__phone-bridge__phone_settings\` (read/write system settings). Use them when the user asks about their phone.
- **Liveness**: the engine process can be killed at any time — the user swipes the app away, the OS reclaims memory, the battery dies. Assume long-running work may be interrupted, deliver results incrementally instead of holding everything to the end. **Scheduled and delayed tasks are not reliable here**: there is no always-on scheduler, so never promise "I'll do it later" — do it now, or say plainly that it has to happen while the app is open.

## What you cannot do — never imply otherwise

- You cannot reach the user's computer: not its files, not its shell, not its browser, not its git repositories, not its running processes. If a task genuinely needs the desktop, say so in one sentence and offer the part that works on the phone.
- You cannot install packages, run a browser, drive a desktop GUI, or hand work to an external CLI agent.
- You cannot use the WebFetch tool, the file-browsing panels, or the desktop task scheduler.
- You cannot open a file on the user's computer — give the absolute path inside the workspace instead.

## How to work here

- Prefer the dedicated tools (Read / Write / Edit / Glob / Grep) over shell commands; use Bash for shell-only work, and \`node\` when the task is really JavaScript.
- Keep replies short — the user reads you on a phone screen. Lead with the conclusion. When there are many details, put them in a file and give the path.
- When you produce a file the user should see, use **PresentFile** so the app renders it as a card, and also state its absolute path.
- **AskUserQuestion** is available: the app renders the options as tappable cards. Use it when a decision is genuinely needed — that is much better than guessing.
- Break multi-step work down with TaskCreate / TaskUpdate so progress is visible on the phone.
- Delegating to a subagent (Agent tool) is fine — subagents run inside this same engine. Delegating to an external CLI agent is not possible here.

## Judgment

- The user may be standing in front of the phone with one hand. Prefer the shortest path that answers the question.
- If a request only makes sense on a desktop (a big refactor in a repo, a long build, a browser session), say that plainly in the first sentence and propose the phone-sized version instead.`

/**
 * 默认系统提示词里「编码专属」段落的稳定前缀。
 *
 * 与 core 的 `server/mainAgents-promptSections.ts: stripCodingSections` 同源
 * (那份是 core 内部模块,外置 agent 拿不到,这里复刻一份)。
 */
const CODING_SECTION_PREFIXES = [
  'You are an interactive agent', // intro:软件工程定位
  '# Doing tasks', // 代码风格 / 反向兼容 hack 规程
  '# CodeGraph', // 代码库探索指引
  'Session ticket id:', // git commit 前缀规程
]

/**
 * 手机上跑不了、或者与「依赖电脑」相关的工具。
 *
 * 判据是「这个工具的正常工作前提在手机上不成立」,不是「手机用不上」:
 *   - CliAgent:外置 CLI agent(opencc / dsh 二进制),手机上没有这些二进制
 *   - Monitor / RemoteTrigger / WorkflowTool / SendUserFile / Brief:
 *     面向宿主机进程与桌面端流程
 *   - EnterWorktree / ExitWorktree:git worktree,手机工作区不是仓库
 *   - LSP:要语言服务器二进制
 *   - PowerShell / snip / WebBrowser:桌面平台的东西
 *   - CronCreate / CronDelete / CronList:引擎进程随时被杀,定时任务不可靠
 *   - EnterPlanMode / ExitPlanMode:手机 UI 没有计划审批交互
 *   - Config:模型/凭据由 App 设置页管理,引擎侧改了会被下次物化覆盖
 *
 * ⚠️ **不要把 mod 工具砍掉** —— 那是手机能力层(截屏 / 通知 / 剪贴板 /
 * 应用列表 / 设置),是这台设备上 agent 相对于桌面唯一独占的能力。
 * mod 贡献的工具名带 mod 名前缀:`mcp__phone-bridge__phone_screenshot`。
 */
const MOBILE_BANNED_TOOLS = new Set([
  'CliAgent',
  'RemoteTrigger',
  'Monitor',
  'Brief',
  'SendUserMessage',
  'SendUserFile',
  'WorkflowTool',
  'EnterWorktree',
  'ExitWorktree',
  'EnterPlanMode',
  'ExitPlanMode',
  'LSP',
  'PowerShell',
  'snip',
  'CronCreate',
  'CronDelete',
  'CronList',
  'Config',
])

/** 工具池指纹 —— 只在变化时打日志,免得每次 query 都刷一行。 */
let lastPoolSignature = ''

/**
 * 拿 core 的 default agent —— 它的 tools 槽会挂 PresentFile。
 *
 * default 是唯一挂 PresentFile 的 agent(它在 core 内部,外置 agent 拿不到那个
 * 工具对象),所以手机 agent 要沿用它的槽,再在上面做过滤。
 *
 * 加载方式:**按文件 URL 动态 import**(主路径)。
 * 引擎目录从 OPENCC_MODS_DIR 反推 —— core bundle 就在
 * `<引擎目录>/node_modules/@zn-ai/zn-agent-core/opencc-core.mjs`。
 *
 * 兜底是 `require('@zn-ai/zn-agent-core')`(靠 NODE_PATH 解析)。实测在
 * 「从 $ZAI_DATA_DIR 里由 import() 加载的 CJS 文件」这个上下文里它会解析失败
 * (同一个 NODE_PATH 在普通 node 子进程里是好的),所以只当兜底。
 */
async function loadDefaultAgent() {
  try {
    const modsDir = process.env.OPENCC_MODS_DIR
    if (modsDir) {
      const engineDir = path.dirname(modsDir)
      const corePath = path.join(
        engineDir,
        'node_modules',
        '@zn-ai',
        'zn-agent-core',
        'opencc-core.mjs',
      )
      const { pathToFileURL } = require('node:url')
      const core = await import(pathToFileURL(corePath).href)
      const list = core.getBuiltinMainAgents ? core.getBuiltinMainAgents() : []
      const found = list.find((a) => a && a.name === 'default') || null
      if (found) return found
    }
  } catch (e) {
    // eslint-disable-next-line no-console
    console.log('[mobile-agent] 按文件 import core 失败,试 require:', e && e.message)
  }
  try {
    const core = require('@zn-ai/zn-agent-core')
    const list = core.getBuiltinMainAgents ? core.getBuiltinMainAgents() : []
    return list.find((a) => a && a.name === 'default') || null
  } catch (e) {
    // eslint-disable-next-line no-console
    console.log('[mobile-agent] 拿不到 core 的 default agent(PresentFile 将缺失):', e && e.message)
    return null
  }
}

/**
 * 外置 main-agent 的导出形状:core 的 loader 会 `await raw(ctx)`。
 */
module.exports = async function mobileMainAgent(ctx) {
  const defaultAgent = await loadDefaultAgent()

  return {
    name: 'mobile',
    description:
      '手机端 —— 跑在用户 Android 手机本地的 agent(去掉依赖电脑的工具,交代手机工作环境)',

    /**
     * 提示词槽:自己的手机提示词前置 + 默认提示词剔掉编码专属段。
     *
     * 保留默认段落的理由:工具使用纪律、上下文压缩、hook 说明这些通用规程
     * 在手机上同样成立;砍掉的是「你在写软件」这层定位。
     */
    systemPrompt: (origin) => {
      const kept = (origin || []).filter((section) => {
        const s = String(section).trim()
        return !CODING_SECTION_PREFIXES.some((p) => s.startsWith(p))
      })
      return [MOBILE_SYSTEM_PROMPT, ...kept]
    },

    /**
     * 工具槽:沿用 default 的槽(挂上 PresentFile),再砍掉依赖电脑的工具。
     *
     * 用黑名单而不是白名单:工具池随 mod / 权限 / 环境浮动(mod 贡献的
     * phone_* 工具就在其中),白名单会把它们误杀。
     */
    tools: (origin) => {
      const pool = defaultAgent && defaultAgent.tools ? defaultAgent.tools(origin) : origin
      const kept = (pool || []).filter((t) => !MOBILE_BANNED_TOOLS.has(String(t.name)))
      const signature = kept.map((t) => String(t.name)).sort().join(',')
      if (signature !== lastPoolSignature) {
        lastPoolSignature = signature
        // eslint-disable-next-line no-console
        console.log('[mobile-agent] tool pool =', signature)
        // eslint-disable-next-line no-console
        console.log(
          '[mobile-agent] dropped =',
          (pool || [])
            .map((t) => String(t.name))
            .filter((n) => MOBILE_BANNED_TOOLS.has(n))
            .join(','),
        )
      }
      return kept
    },

    /**
     * mods 槽不设 —— 默认「全部 mod 可见」。
     * 手机能力层(phone-bridge mod)正是靠这个进来的。
     */
  }
}
