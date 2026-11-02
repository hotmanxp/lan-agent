// sharp/index.mjs — 手机端 sharp 替身。
//
// ## 为什么需要它
//
// core 的图片处理(压缩 / 缩到 1568px / 换格式)全走 sharp。sharp 是**原生
// 模块**:它的预编译包只覆盖 darwin / linux / win / freebsd,**没有
// android-arm64** → 引擎里 `import('sharp')` 直接抛
// ImageProcessorUnavailableError。
//
// 后果不是「完全看不了图」:原图 base64 在 5MB 以内时 core 会原样透传
// (让 API 服务端自己缩),只有超过 5MB 的图(手机相机出的 12MP 大图)才会在
// Read 工具里炸掉 —— 报错还写着「Install `sharp`」,在手机上完全没法照做。
//
// ## 这个替身怎么工作
//
// 手机本身就有原生图像栈(BitmapFactory),没必要在引擎里背一个 wasm 版
// libvips(9MB 下载体积 + 整图 RGBA 的内存)。所以:
//
//   引擎(core)→ import('sharp') → 本替身 → HTTP 能力桥
//            → 主进程 CapabilityService → Android BitmapFactory
//            → 采样解码 + 缩放 + 重编码 → base64 回来
//
// ## 为什么能整体替换掉 sharp
//
// 引擎包里 sharp 是**外置**依赖(esbuild 的 EXTERNALS),运行时才
// `import('sharp')` 解析 —— 所以引擎目录的 node_modules/sharp/ 里放什么
// 就是什么,opencc-web 一行都不用改。
//
// 本文件由 tools/build-engine-pkg.mjs 拷进引擎包的 node_modules/sharp/ ——
// 引擎包一换指纹,手机端重新下载一次就生效。
//
// ## 只实现 core 用到的那几个方法
//
// core 的 SharpFunction 类型只要:metadata / resize / jpeg / png / webp /
// toBuffer。多一个都不实现 —— 用不上的 API 留在那儿只会骗人。

import { writeFileSync, unlinkSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const CAP_BASE = process.env.LANAGENT_CAP_BASE || ''

let seq = 0

/** 调一次能力桥。失败就抛 —— 让 core 的错误路径接住。 */
async function callBridge(payload) {
  if (!CAP_BASE) {
    throw new Error(
      '手机端图片处理不可用:能力桥地址没设(LANAGENT_CAP_BASE 为空)',
    )
  }
  let res
  try {
    res = await fetch(`${CAP_BASE}/cap/image_resize`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(payload),
    })
  } catch (e) {
    throw new Error(`手机端图片处理失败:连不上能力桥(${e?.message || e})`)
  }
  const text = await res.text()
  let body
  try {
    body = JSON.parse(text)
  } catch {
    throw new Error(`手机端图片处理失败:能力桥返回的不是 JSON(HTTP ${res.status})`)
  }
  if (!res.ok || body?.ok !== true) {
    throw new Error(
      `手机端图片处理失败:${body?.error || `HTTP ${res.status}`}`,
    )
  }
  return body
}

/**
 * 把图片字节交给手机解码。
 *
 * 优先走**文件路径**:引擎把原始字节写到 TMPDIR,主进程直接 decodeFile ——
 * 一张 5MB 的图不用在堆里过一遍 base64(那是 6.7MB 的字符串 + 5MB 的
 * 解码结果,手机上很奢侈)。写文件失败才退回 base64。
 */
async function processOnPhone({ buffer, width, height, format, quality }) {
  const payload = {
    width: width || 0,
    height: height || 0,
    format,
    quality,
    withoutEnlargement: true,
  }

  const dir = process.env.TMPDIR || tmpdir()
  const tmp = join(dir, `sharp-shim-${process.pid}-${seq++}.bin`)
  try {
    // 先试文件路径:主进程直接 decodeFile,省掉堆里过一遍 base64。
    // 写不进去(没权限 / 磁盘满)就退回 base64 —— 这两条路都要能走通。
    try {
      writeFileSync(tmp, buffer)
      payload.path = tmp
    } catch {
      payload.base64 = buffer.toString('base64')
    }
    try {
      return await callBridge(payload)
    } catch (e) {
      // 走文件这条路时桥调用失败 → 换 base64 再试一次。
      if (!payload.path) throw e
      delete payload.path
      payload.base64 = buffer.toString('base64')
      return await callBridge(payload)
    }
  } finally {
    try {
      unlinkSync(tmp)
    } catch {
      // 临时文件删不掉就下次覆盖,不用为它失败
    }
  }
}

/** 猜图片格式(magic bytes)。认不出来返回 null。 */
function detectFormat(buf) {
  if (buf.length >= 8 &&
      buf[0] === 0x89 && buf[1] === 0x50 && buf[2] === 0x4e && buf[3] === 0x47) {
    return 'png'
  }
  if (buf.length >= 3 && buf[0] === 0xff && buf[1] === 0xd8 && buf[2] === 0xff) {
    return 'jpeg'
  }
  if (buf.length >= 6 &&
      buf[0] === 0x47 && buf[1] === 0x49 && buf[2] === 0x46) {
    return 'gif'
  }
  if (buf.length >= 12 &&
      buf.toString('ascii', 0, 4) === 'RIFF' &&
      buf.toString('ascii', 8, 12) === 'WEBP') {
    return 'webp'
  }
  return null
}

/**
 * 从编码后的字节里读宽高(不靠解码)。
 *
 * 跟 core 的 readImageDimensions 同源(PNG IHDR / GIF 头 / WebP VP8 系 /
 * JPEG SOF 扫描)—— 抄一份是因为 core 那个函数不导出,而我们要的正是
 * 「不解码就知道多大」:metadata() 是热路径,每次都过一趟 HTTP 不划算。
 */
function readDimensions(buf) {
  if (buf.length >= 24 &&
      buf[0] === 0x89 && buf[1] === 0x50 && buf[2] === 0x4e && buf[3] === 0x47) {
    return { width: buf.readUInt32BE(16), height: buf.readUInt32BE(20) }
  }
  if (buf.length >= 10 &&
      buf[0] === 0x47 && buf[1] === 0x49 && buf[2] === 0x46) {
    return { width: buf.readUInt16LE(6), height: buf.readUInt16LE(8) }
  }
  const webp = readWebpDimensions(buf)
  if (webp) return webp
  return readJpegDimensions(buf)
}

function readWebpDimensions(buf) {
  try {
    if (buf.length < 16) return null
    const fourCC = buf.toString('ascii', 12, 16)
    if (fourCC === 'VP8L') {
      if (buf.length < 25 || buf[20] !== 0x2f) return null
      const bits = buf.readUInt32LE(21)
      return {
        width: ((bits & 0x3fff) + 1) >>> 0,
        height: (((bits >>> 14) & 0x3fff) + 1) >>> 0,
      }
    }
    if (fourCC === 'VP8 ') {
      if (buf.length < 30) return null
      if (buf[23] !== 0x9d || buf[24] !== 0x01 || buf[25] !== 0x2a) return null
      return {
        width: buf.readUInt16LE(26) & 0x3fff,
        height: buf.readUInt16LE(28) & 0x3fff,
      }
    }
    if (fourCC === 'VP8X') {
      if (buf.length < 30) return null
      return {
        width: buf.readUIntLE(24, 3) + 1,
        height: buf.readUIntLE(27, 3) + 1,
      }
    }
  } catch {
    return null
  }
  return null
}

function readJpegDimensions(buf) {
  try {
    if (buf.length < 4 || buf[0] !== 0xff || buf[1] !== 0xd8) return null
    let i = 2
    while (i + 1 < buf.length) {
      if (buf[i] !== 0xff) {
        i++
        continue
      }
      while (i + 1 < buf.length && buf[i + 1] === 0xff) i++
      if (i + 1 >= buf.length) return null
      const marker = buf[i + 1]
      const isSof =
        (marker >= 0xc0 && marker <= 0xcf &&
         marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc)
      if (isSof) {
        if (i + 9 >= buf.length) return null
        const height = buf.readUInt16BE(i + 5)
        const width = buf.readUInt16BE(i + 7)
        if (width > 0 && height > 0) return { width, height }
        return null
      }
      if (marker === 0x01 || marker === 0xd8 || marker === 0xd9 ||
          (marker >= 0xd0 && marker <= 0xd7)) {
        i += 2
        continue
      }
      if (i + 3 >= buf.length) return null
      const segLen = buf.readUInt16BE(i + 2)
      if (segLen < 2) return null
      i += 2 + segLen
    }
  } catch {
    return null
  }
  return null
}

/**
 * sharp 的替身实例。
 *
 * 只记「要干什么」(缩放框 / 输出格式 / 质量),真正干活的是 toBuffer() ——
 * 那时候才知道最终要什么,一趟请求过去就够。
 */
class SharpShim {
  constructor(input) {
    if (input && typeof input === 'object' && !Buffer.isBuffer(input) &&
        !(input instanceof Uint8Array) && input.create) {
      throw new Error(
        '手机端不支持 sharp 的 create(从零生成图片)能力',
      )
    }
    this.buffer = Buffer.isBuffer(input) ? input : Buffer.from(input)
    this.resizeBox = null
    this.outFormat = null
    this.quality = 85
  }

  /** 尺寸 / 格式 —— 本地读 magic bytes,不过桥。 */
  async metadata() {
    const format = detectFormat(this.buffer)
    const dims = readDimensions(this.buffer)
    return {
      width: dims?.width ?? 0,
      height: dims?.height ?? 0,
      format: format ?? 'png',
    }
  }

  resize(width, height, options = {}) {
    this.resizeBox = {
      width: width ?? undefined,
      height: height ?? undefined,
      withoutEnlargement: options.withoutEnlargement !== false,
    }
    return this
  }

  jpeg(options = {}) {
    this.outFormat = 'jpeg'
    if (options.quality != null) this.quality = options.quality
    return this
  }

  png() {
    this.outFormat = 'png'
    return this
  }

  webp(options = {}) {
    this.outFormat = 'webp'
    if (options.quality != null) this.quality = options.quality
    return this
  }

  async toBuffer() {
    const box = this.resizeBox
    // 输出格式没指定就沿用原格式。
    let format = this.outFormat
    if (!format) {
      const src = detectFormat(this.buffer)
      if (src === 'gif') {
        // Android 没有 GIF 编码器,只能编成 JPEG —— 但**不能**就这么降级:
        // core 的 media_type 取自 metadata().format(还是 'gif'),字节却是
        // JPEG,两者对不上 → API 收下之后按 image/gif 解析 JPEG 字节,直接 400。
        //
        // 抛错让 core 走它自己的 catch:那条路会**原图透传**(media_type 与
        // 字节都是 GIF,一致),尺寸超限由 API 服务端自己缩到 1568。
        throw new Error(
          '手机端不支持 GIF 编码:请把图片转成 JPEG / PNG / WebP',
        )
      }
      format = src === 'png' ? 'png' : src === 'webp' ? 'webp' : 'jpeg'
    }
    const out = await processOnPhone({
      buffer: this.buffer,
      width: box?.width ?? 0,
      height: box?.height ?? 0,
      format,
      quality: this.quality,
    })
    return Buffer.from(out.base64, 'base64')
  }
}

export default function sharp(input) {
  return new SharpShim(input)
}

export { sharp }
