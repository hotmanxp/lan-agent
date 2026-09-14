# WorkBuddy accessToken 适用面调研

> 日期：2026-09-14
> 范围：`opencc-web` 新增的 `GET /api/voice/getASRToken` 下发的 WorkBuddy 登录态，到底能在 WorkBuddy 哪些接口上用
> 状态：调研完成（含真机探测），待行动决策
> 作者：Ethan

## 一句话结论

**同一把 JWT 在 `copilot.tencent.com` 下覆盖绝大多数业务接口**（对话历史、ASR、定时任务、连接器、配额、自定义技能、项目等均 HTTP 200）。**例外三组**：(1) 续期 / 切账号走 `X-Refresh-Token` 而非 Bearer；(2) pre-login 流程 (`/v2/plugin/auth/state`、`/v2/plugin/auth/token`) 必须 `X-No-Authorization: true`；(3) `/console/as/*` 管理面接口要后台 scope，被 403 拒。**跨域**（腾讯文档 `/api/v6/open/tdrive/*`、`/openapi/drive/v2/*`、本地 CLI agent sidecar `/api/v1/llm/completions`）不在覆盖范围。

---

## 1. 调研证据来源

| 来源 | 路径 | 用途 |
|---|---|---|
| WorkBuddy 桌面端安装 | `/Applications/WorkBuddy.app/`（macOS） | 提取 app.asar |
| app.asar | `Contents/Resources/app.asar`（298MB） | 169 个 main 进程 JS 文件、HTTP 拦截器、端点表 |
| auth 文件 | `~/Library/Application Support/CodeBuddyExtension/Data/Public/auth/workbuddy-desktop.info` | 取本机真实 accessToken 做真机探测 |
| 产品配置 | `/private/var/folders/.../T/workbuddy-product-spill-*/acc-product-config-v3.json` | 拿 `endpoint` / `prefixPath` / `internalDomain` |
| 客户端代码注释 | `lan-agent/app/src/main/java/.../voice/WorkBuddyAsrAuth.kt:1-30` | token 来源、refreshToken 一次性轮换的语义说明 |

## 2. accessToken 的 JWT claims（实测解码）

```json
{
  "iss": "https://www.workbuddy.cn/auth/realms/copilot",
  "aud": "account",
  "azp": "console",
  "sub": "9e5512e4-34fc-4419-9122-914c64f69c1d",
  "scope": "openid acr profile basic web-origins roles offline_access email",
  "realm_access": { "roles": ["offline_access", "default-roles", "uma_authorization"] },
  "allowed-origins": ["*"],
  "exp": 1789619362,
  "iat": 1789360162,
  "token_source": "enterprise_switch",
  "typ": "Bearer"
}
```

- `aud="account"` 看起来只指 Keycloak Account API，但 **`copilot.tencent.com` 业务网关不看 `aud`**（实测接入有效，下文矩阵）。
- `azp="console"` 是授权方，业务接口不卡。
- `exp` ≈ 3 天后；`refreshExpiresIn` = 7 天。

## 3. 端点适用性矩阵（真机探测）

> base = `https://copilot.tencent.com`；鉴权 = `Authorization: Bearer <accessToken>` + `X-User-Id: <uid>`（来自 `acc-product-config-v3.json` 的 `tokenHeader` / `usernameHeader`）

### ✅ 可用（HTTP 200 / 101）

| 端点 | 用途 | 实测响应 |
|---|---|---|
| `GET /v2/plugin/account` | 当前账号信息 | 200，code:0 |
| `GET /v2/plugin/accounts` | 多账号列表 | 200，code:0 |
| `GET /console/accounts` | console 账号视图 | 200，code:0 |
| `GET /v2/as/conversations/?page=&size=&asType=` | 会话历史列表 | 200，返回真实会话 |
| `GET /v2/as/scheduler/tasks` | 定时任务 | 200，`{tasks:[], total:0}` |
| `GET /v2/user/cloudagent/entitlement` | 用户权益 | 200 |
| `GET /v2/user/cloudagent/agents?page=&size=` | 用户云 agent | 200 |
| `GET /v2/user/cloudagent/quota` | 配额 | 200，`{plan:"default", agentLimit:3}` |
| `GET /v2/user-asset/custom-skills` | 自定义技能 | 200，`{total:0, items:[]}` |
| `GET /v2/user/cloudagent/orchestrator` | orchestrator | 200（`orchestrator not initialized`，但鉴权通过） |
| `GET /v2/user/cloudagent/tasks` | 云 agent 任务列表 | 400（`task_type 参数必填`，鉴权通过） |
| `GET /console/as/projects` | 项目列表 | 200 |
| `GET /console/as/teams/me/quota` | 团队配额 | 200 |
| `GET /v2/as/connector/agentmail/me` | 邮箱连接器 | 200 |
| `GET /v2/as/connector/agentmail/url/{admin,realname}` | OAuth URL | 200 |
| `GET /v2/activity/banner` | 活动 banner | 200（`activity is offline`，鉴权通过） |
| `GET /v2/enterprises/personal/models` | 个人版模型列表 | 200 |
| `POST /v1/traces` | 埋点 | 200，`{partialSuccess:{}}` |
| `WSS /clientcap/v2/asr/stream?source=desktop` | ASR 实时语音 | 101 Switching Protocols（已实测） |

### ⚠️ Auth 通过但路由/参数问题（非鉴权失败）

- `GET /v2/api-keys` → 400 `10001 invalid query parameters`（需 `?page=&size=`）
- `GET /v2/as/netdrive/cloudagent-config/download` → 404（可能要 `?accountId=`）
- `GET /console/as/support/presigned_url` → 404（可能要 `?bucket=`）
- `GET /v3/config` → 400 `check ua, get coding copilot version error`（要特定 User-Agent）
- `GET /v2/feature-flag/api` → 404（实际是 `/console/feature-flag/api`，且 403，见下）
- `POST /v2/plugin/auth/state?platform=workbuddy` → 用 Bearer 试时是 `X-No-Authorization: true`，加这个 header 就 200 返回 `{state, authUrl}`；不加会被忽略。**结论：本就走 `X-No-Authorization: true`**。

### ❌ 403（scope/role 不足）

- `GET /console/as/conversations`
- `GET /console/as/tasks`
- `GET /console/client-login`
- `GET /console/feature-flag/api`

### ❌ 必须用 `X-Refresh-Token` 而非 Bearer

```
POST /v2/plugin/auth/token/refresh
  headers:
    X-Refresh-Token: <refreshToken>
    X-Auth-Refresh-Source: plugin
    X-Domain: copilot.tencent.com
  body: {}

POST /v2/plugin/account/switch
  headers: 同上
```

实测用 Bearer 试 → `10001: refreshToken is empty` / `X-Refresh-Token header is required`，确认走 refreshToken 头。

### ❌ 不能用这套 token（pre-login / 跨域 / sidecar）

- `POST /v2/plugin/auth/state` → 必须 `X-No-Authorization: true`
- `GET /v2/plugin/auth/token` → 同上，轮询登录态
- `POST /v2/plugin/login/enterprise` / `login/account` → 同上
- `/api/v1/llm/completions` → 本地 CLI agent sidecar（`this.endpoint` 指向 `127.0.0.1`，非远程）
- `/chat/completions` → 本地自定义模型连通性测试用
- `/api/v6/open/tdrive/*`、`/openapi/drive/v2/*` → 腾讯文档独立域，自带 COS/OAuth 鉴权

## 4. 关键发现

### 4.1 客户端拦截器：自动 Bearer 全覆盖

`main/common.js:73605` 反编译产物（ProductEndpointHttpInterceptor + AuthBearerInterceptor）：

```js
if (!config.headers[AUTHORIZATION] && auth.accessToken && !config.headers["X-No-Authorization"]) {
  config.headers[AUTHORIZATION] = `Bearer ${auth.accessToken}`;
}
```

WorkBuddy desktop **所有 `restOperations.*` 调用都自动挂 Bearer**，除非显式 `X-No-Authorization: true`。也就是说 WorkBuddy 自己把 accessToken 当万能通行证用。

### 4.2 产品配置（`acc-product-config-v3.json`）

```json
"authentication": {
  "id": "auth",
  "label": "...",
  "type": "...",
  "attributes": {
    "platform": "workbuddy",
    "prefixPath": "/plugin",
    "tokenHeader": "Authorization",
    "tokenType": "bearerToken",
    "usernameHeader": "X-User-Id",
    "usernameEncode": "URLEncode",
    "internalDomain": ["copilot.tencent.com", "staging-copilot.tencent.com",
                       "www.codebuddy.cn", "staging.codebuddy.cn",
                       "www.workbuddy.cn", "staging.workbuddy.cn"],
    "externalDomain": ["www.codebuddy.ai", "staging-codebuddy.tencent.com"],
    "cloudHostedDomain": ["*.sso.copilot.tencent.com", "*.sso.codebuddy.cn", ...],
    "iOADomain": ["tencent.sso.copilot.tencent.com", ...]
  }
}
```

`endpoint` 在根级：`https://copilot.tencent.com`。**只有 `/v2/plugin/auth/...` 走 prefixPath，其余业务路径(`/v2/as/...`、`/console/as/...`、`/clientcap/...`)全部挂在同一域名根下**。

### 4.3 refreshToken 一次性轮换（关键约束）

来源：`WorkBuddyAsrAuth.kt:23-31` 注释 + 实测 `/v2/plugin/auth/token/refresh` 必须 `X-Refresh-Token` 头。

> ⚠️ 服务端会**轮换** refreshToken —— 拿到新值必须存下来，否则第二次续期会失败。
> refreshToken 默认 7 天（refreshExpiresIn = 604799）。

**约束已落实在 opencc-web `routes/voice.ts:97-122`**：只读 auth 文件不刷新，每次请求现读，由桌面端自己的续期保证 token 新鲜度。**这一约束对任何后续接入 WorkBuddy 接口的端点都同样适用**。

## 5. 后续行动建议

### 选项 A：保持现状（仅 ASR）

- `routes/voice.ts` 不动，端点名保持 `/api/voice/getASRToken`
- lan-agent 只用它连 ASR 网关
- 优点：边界清晰、不引入新鉴权风险
- 缺点：其他 WorkBuddy 业务能力用不到

### 选项 B：通用化 token 端点

- 把 `routes/voice.ts` 改名 / 重塑为 `routes/workbuddyCredential.ts`
- 端点：`GET /api/workbuddy/credential`（不再叫 "ASR token"）
- payload 保留：`endpoint + accessToken + refreshToken + uid + nickname + expiresAt`
- 新增：`internalDomain`（`["copilot.tencent.com", ...]`）让 App 端筛 host
- lan-agent 拿到后，自行拼 `Bearer` / `X-User-Id` 调上面 ✅ 列表里的任何接口
- **严守"只读不刷新"**：刷新仍由桌面端完成，refreshToken 不在服务端调用 `/v2/plugin/auth/token/refresh`

### 选项 C：在 lan-agent 内做 WorkBuddy SDK

- 把上表 ✅ 端点抽成 `WorkBuddyClient` 类，统一 `Bearer + X-User-Id + baseURL` 拼装
- 业务层只调语义化方法（`listConversations()`, `getQuota()`, `listCloudAgents()` 等）
- 优点：业务能力扩张有边界，未来 WorkBuddy 改协议时只改 SDK 一处

### 推荐：选项 B + 选项 C 组合

短期先把 voice.ts 改名（选项 B），中期为 lan-agent 抽 SDK（选项 C）。**前提是每个接口上线前都做一次真机探测**（如本调研），不要靠拍脑袋信任"业务接口都用同一个 Keycloak 域就一定能用"。

## 6. 反模式 / 不要做的事

1. **不要在服务端调用 `/v2/plugin/auth/token/refresh`** —— refreshToken 一次性轮换，会把桌面端踢下线。
2. **不要把 accessToken 当 ASR 专用** —— 它是通用凭证，命名误导会让后来者以为只能调 ASR。
3. **不要给 accessToken 加额外 scope** —— scope 是 Keycloak 在登录时定的，发出去什么就是什么，App 端改不了。
4. **不要缓存 accessToken 到 zai 内存 / 磁盘** —— 直接每次读 auth 文件，最新鲜，桌面端刚续完期就能拿到。
5. **不要把腾讯文档相关端点（`/api/v6/open/tdrive/*`、`/openapi/drive/v2/*`）混进来** —— 它们走独立域 + 独立鉴权（COS / OAuth），Keycloak token 不通用。

---

## 附录 A：探测脚本片段

```python
import urllib.request, json

with open('/Users/ethan/Library/Application Support/CodeBuddyExtension/Data/Public/auth/workbuddy-desktop.info') as f:
    auth = json.load(f)
token = auth['auth']['accessToken']
uid = auth['account']['uid']

req = urllib.request.Request(
    'https://copilot.tencent.com/v2/plugin/account',
    headers={'Authorization': f'Bearer {token}', 'X-User-Id': uid}
)
print(urllib.request.urlopen(req, timeout=8).read()[:200])
```

WS 握手确认：

```python
# wss://copilot.tencent.com/clientcap/v2/asr/stream?source=desktop
# headers: Authorization: Bearer <accessToken> + X-User-Id: <uid>
# → HTTP/1.1 101 Switching Protocols  ← 实测确认
```

## 附录 B：相关引用

- `opencc-web/packages/zai/src/server/routes/voice.ts:97-122` — `GET /api/voice/getASRToken` 实现（"只读不刷新"原话出处）
- `opencc-web/packages/zai/src/server/routes/voice.ts:36-93` — JWT 解析 + 兼容标准 / 旧版扁平形态
- `lan-agent/app/src/main/java/io/github/hotmanxp/lanagent/voice/WorkBuddyAsrAuth.kt:1-50` — token 来源、续期规则、refreshToken 一次性轮换说明
- `lan-agent/app/src/main/java/io/github/hotmanxp/lanagent/voice/TencentAsrSignature.kt:131-198` — `AsrUrlProvider.WorkBuddyApi` 调用 `/api/voice/getASRToken` 的客户端
- `lan-agent/AGENTS.md:399-405` — Tencent 实时 ASR vs WorkBuddy 鉴权对照表
