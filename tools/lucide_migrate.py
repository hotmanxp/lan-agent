#!/usr/bin/env python3
"""把 Material Symbols 图标引用批量换成 Lucide(0.21.0)。

关键点(踩过的坑):
  1. Lucide 图标是**扩展属性**(`getTerminal(Lucide)`),不是 Lucide 对象的成员。
     所以每个用到的图标都要单独 `import com.composables.icons.lucide.<Name>`,
     光 import Lucide 不够 —— 这是本脚本存在的全部理由。
  2. 旧名 `AlertTriangle` / `CheckCircle` 在 1.1.0 里已不存在,映射表里用的是新名。
  3. fill-only 图标(Star/Play/Stop/CheckCircle)映射到手绘 Glyph(见 MAP 的 GLYPH 值),
     这些不进 import 列表,改成 `<GlyphName>Glyph(...)` 调用。

用法: python3 tools/lucide_migrate.py [--dry-run]
"""
import re
import sys
from pathlib import Path

# Material 引用 -> (kind, target)
#   kind="lucide"  → Lucide.<Target>
#   kind="glyph"   → 手绘 Glyph:<Target>
MAP = {
    # 高频
    "Refresh": ("lucide", "RefreshCw"), "Add": ("lucide", "Plus"),
    "ArrowBack": ("lucide", "ArrowLeft"), "Terminal": ("lucide", "Terminal"),
    "Stop": ("glyph", "SolidSquare"), "PlayArrow": ("lucide", "Play"),
    "Delete": ("lucide", "Trash2"), "Edit": ("lucide", "Pencil"),
    "Close": ("lucide", "X"), "Check": ("lucide", "Check"),
    "OpenInNew": ("lucide", "ArrowUpRight"), "Chat": ("lucide", "MessageCircle"),
    "Menu": ("lucide", "Menu"), "Language": ("lucide", "Globe"),
    "GraphicEq": ("lucide", "AudioLines"), "FolderOpen": ("lucide", "FolderOpen"),
    "Folder": ("lucide", "Folder"), "ExpandMore": ("lucide", "ChevronDown"),
    "Dns": ("lucide", "Server"), "Bolt": ("lucide", "Zap"),
    "AutoAwesome": ("lucide", "Sparkles"), "ArrowUpward": ("lucide", "ArrowUp"),
    "KeyboardArrowRight": ("lucide", "ChevronRight"),
    # 单次
    "Warning": ("lucide", "TriangleAlert"), "TableChart": ("lucide", "Table"),
    "Storage": ("lucide", "Database"), "SmartToy": ("lucide", "Bot"),
    "Slideshow": ("glyph", "Slide"), "Settings": ("lucide", "Settings"),
    "Send": ("lucide", "ArrowUp"), "Schedule": ("lucide", "Clock"),
    "RocketLaunch": ("lucide", "Rocket"),
    "RadioButtonUnchecked": ("glyph", "SolidCircle"),
    "QrCodeScanner": ("lucide", "QrCode"), "Psychology": ("glyph", "Brain"),
    "PictureAsPdf": ("lucide", "FileText"), "Photo": ("lucide", "Image"),
    "Memory": ("lucide", "Cpu"), "KeyboardArrowUp": ("lucide", "ChevronUp"),
    "KeyboardArrowDown": ("lucide", "ChevronDown"), "Keyboard": ("lucide", "Keyboard"),
    "Hub": ("glyph", "Hub"), "Html": ("lucide", "Code"),
    "Home": ("lucide", "House"), "Favorite": ("glyph", "SolidStar"),
    "ExpandLess": ("lucide", "ChevronUp"), "Description": ("lucide", "FileText"),
    "DeleteSweep": ("lucide", "Trash"), "ContentPaste": ("lucide", "ClipboardPaste"),
    "ContentCopy": ("lucide", "Copy"), "Construction": ("glyph", "Hammer"),
    "Computer": ("lucide", "Monitor"),
    "CheckCircle": ("glyph", "SolidCheckCircle"), "Build": ("glyph", "Hammer"),
    "AttachFile": ("lucide", "Paperclip"), "Article": ("lucide", "FileText"),
    "ArrowDownward": ("lucide", "ArrowDown"), "AddPhotoAlternate": ("lucide", "ImagePlus"),
    "InsertDriveFile": ("lucide", "File"), "ArrowRight": ("lucide", "ArrowRight"),
}

# 方向性图标:RTL 下需翻转(原来是 Icons.AutoMirrored.*)
AUTO_MIRRORED = {"ArrowBack", "OpenInNew", "ArrowRight", "KeyboardArrowRight",
                "ArrowUpward", "ArrowDownward", "Chat", "InsertDriveFile"}

IMPORT_RE = re.compile(r"^import androidx\.compose\.material\.icons\.(?:automirrored\.)?rounded\.(\w+)$")
REF_RE = re.compile(r"\bIcons\.(?:AutoMirrored\.)?Rounded\.(\w+)\b")

ROOT = Path(__file__).resolve().parent.parent / "app/src/main/java/io/github/hotmanxp/lanagent"
# 脚本自身产物 / 已在上一轮手工改完的文件 —— 跳过。
# Glyphs.kt 的注释里引用了 `Icons.Rounded.Xxx` 作为"原来叫什么"的文档,
# 那是历史说明不是待替换的代码,改了反而丢失溯源信息。
SKIP = {"Glyphs.kt", "WbIcon.kt", "NoRippleClickable.kt", "BottomTabs.kt"}
# 有 ImageVector 的地方才 import Lucide;Glyph 走函数调用不需要。
# 但 import Lucide 本身只在用到 Lucide.Xxx 时才有必要,统一加上无害(AA 也这么写)。
needs_lucide_obj = "import com.composables.icons.lucide.Lucide"


def process(path: Path, dry: bool) -> tuple[int, list[str]]:
    text = path.read_text(encoding="utf-8")
    lines = text.split("\n")

    # 1) 收集 import 与其对应 Material 名
    dropped: list[str] = []
    used: set[str] = set()
    for line in lines:
        m = IMPORT_RE.match(line.strip())
        if m:
            dropped.append(line)
            used.add(m.group(1))

    unknown = {u for u in used if u not in MAP}
    if unknown:
        raise SystemExit(f"{path.name}: 映射表缺失 {sorted(unknown)}")

    # 2) 替换引用
    def repl(m: re.Match) -> str:
        name = m.group(1)
        kind, target = MAP[name]
        return f"Lucide.{target}" if kind == "lucide" else f"{target}Glyph()"

    body = "\n".join(l for l in lines if l not in dropped)
    new_body, n = REF_RE.subn(repl, body)
    if n == 0:
        return 0, []

    # 3) 重写 import 块
    lucide_imports = sorted(
        f"import com.composables.icons.lucide.{MAP[u][1]}"
        for u in used if MAP[u][0] == "lucide"
    )
    new_imports = [needs_lucide_obj] + lucide_imports

    # 插到最后一个既有 import 之后,保持排序观感
    out, inserted = [], False
    for line in new_body.split("\n"):
        out.append(line)
        if not inserted and line.startswith("import "):
            # 找到 import 区末尾:下一行不是 import 时插
            idx = new_body.split("\n").index(line)
            nxt = new_body.split("\n")[idx + 1] if idx + 1 < len(new_body.split("\n")) else ""
            if not nxt.startswith("import "):
                out.extend(new_imports)
                inserted = True
    result = "\n".join(out)
    if not dry:
        path.write_text(result, encoding="utf-8")
    return n, [f"{u} → {MAP[u]}" for u in sorted(used)]


def main() -> None:
    dry = "--dry-run" in sys.argv
    total, total_files = 0, 0
    for path in sorted(ROOT.rglob("*.kt")):
        if path.name in SKIP:
            continue
        n, detail = process(path, dry)
        if n:
            total += n
            total_files += 1
            print(f"{path.name}: {n} 处  [{', '.join(detail)}]")
    print(f"\n合计 {total} 处 / {total_files} 个文件{'(dry-run)' if dry else ''}")


if __name__ == "__main__":
    main()
