#!/usr/bin/env python3
"""交付前体检：Kotlin 分隔符平衡 + 常见手改失误扫描。

─────────────────────────────────────────────────────────────
为什么需要它
─────────────────────────────────────────────────────────────
本仓库**没有本地 Android SDK、也没有 gradlew**（沙箱无法生成
gradle-wrapper.jar，见 AI_MAINTENANCE §7），CI 是唯一的编译器。
于是一个手误（少个括号、多个 import）要白等 3~4 分钟才发现。

这个脚本做两件事，都是纯文本检查，秒级出结果：

1. **分隔符平衡**：剥掉注释与字符串字面量后，检查圆括号 / 方括号 / 花括号
   是否配对。Kotlin 在原始字符串（三个双引号那种）里可以随便写括号，
   普通字符串里也常有左括号，所以必须先把字面量剥掉再数，否则全是误报。

2. **手改失误扫描**（都是我自己犯过的，见 AI_MAINTENANCE §34.5 / §35）：
   - `import kotlin.coroutines.resumeWith` → `resumeWith` 是 Continuation
     的接口成员，不是扩展函数，import 会报 Unresolved reference。
   - 未使用的 import → 编译不报错，但属于噪音。
   - `import kotlinx.coroutines.flow.first` 之类存在但整文件未用。

用法：
    python3 scripts/precheck.py <文件或目录> [...]
    python3 scripts/precheck.py app/src/main/java        # 全量
    python3 scripts/precheck.py app/src/main/java/.../Foo.kt

退出码：0 = 通过；1 = 有问题（可直接用于 CI 或 git hook）。

─────────────────────────────────────────────────────────────
⚠️ 本脚本**不能**替代 CI —— 它抓不到的东西（如实记录，别指望它）
─────────────────────────────────────────────────────────────
- **作用域里不存在的标识符**：`uaInput = safe` 这种裸赋值（见 §34.5）
  需要真正的类型解析 / 作用域分析，纯文本扫描做不到。这类错误只能靠
  "改完搜一遍这个名字的所有出现位置"这条人工纪律来防（见 §E 自查薄弱点）。
- **类型不匹配**：`Long` 与 `Long?` 那类（见 §33 的 cacheSizeBytes）。
- **逻辑错误**：本文件只做"语法面"的机械检查，行为正确性靠 §B 的四个必答问题。

设计取向：**宁可漏报，不可误报。** 一份 47 条噪音的报告会被直接无视，
那比没有报告更糟 —— 这正是 §33 批评过的"提示太多等于没提示"。
本文件的误报控制经历了两轮实测（全量 105 文件）：
`getValue`/`setValue`（`by` 委托的 operator，编译器插件按约定使用）
与以引号结尾的原始字符串（正则在其中），都曾被误报并已修正。
"""

import os
import re
import sys

# 已知会「误当扩展函数导入」的名字：接口成员 / 类成员函数。
# 加新条目时请附上"为什么它不是扩展函数"。
#
# ⚠️ 只列**确实**不是扩展的。误列会把正确代码标成错误 ——
#    `kotlin.coroutines.resume` 就属于**扩展**函数（`Continuation<T>.resume(value)`），
#    我一开始把它错列进来，导致 DirectLinkWebViewBridge 被误报。
BAD_IMPORTS = {
    "kotlin.coroutines.resumeWith":
        "Continuation.resumeWith 是接口成员函数，直接调用即可，import 会 Unresolved",
    "kotlin.coroutines.resumeWithException":
        "Continuation.resumeWithException 同上",
}

DELIM_PAIRS = {")": "(", "]": "[", "}": "{"}
OPENERS = "([{"
CLOSERS = ")]}"


def strip_literals(src):
    """剥掉注释、字符串、字符字面量，保留换行以便报行号。

    顺序要紧：先判 `//` 与 `/*`，再判字符串 —— 否则字符串里的 `//`
    会被当成注释，把后面整行吃掉（这正是我第一版脚本的 bug）。
    """
    out = []
    i = 0
    n = len(src)
    while i < n:
        c = src[i]

        # 行注释
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            while i < n and src[i] != "\n":
                i += 1
            continue

        # 块注释（Kotlin 支持嵌套，这里按嵌套处理）
        if c == "/" and i + 1 < n and src[i + 1] == "*":
            depth = 1
            i += 2
            while i < n and depth > 0:
                if src.startswith("/*", i):
                    depth += 1
                    i += 2
                elif src.startswith("*/", i):
                    depth -= 1
                    i += 2
                else:
                    if src[i] == "\n":
                        out.append("\n")
                    i += 1
            continue

        # 原始字符串（三个双引号）
        #
        # ⚠️ 终止判定不能简单找 `"""` —— 正则里出现的
        #    `"""...[^"]+)""""`（内容以引号结尾）会让 `"""` 的匹配
        #    错位到内容里的引号上，把后面整段代码当成字符串吞掉，
        #    于是报出一堆假的"括号未闭合"。真实案例见 HtmlExtractor.kt。
        #
        #    正确规则：结束定界符是**恰好三个**引号，后面不能再跟引号
        #    （跟了说明它属于内容）。Kotlin 里原始字符串最长可以有
        #    四个引号收尾（内容末位是引号时写作 `""""`）。
        if src.startswith('"""', i):
            i += 3
            while i < n:
                if src.startswith('""""', i):
                    # 四个引号：内容末位是引号，前三个属于内容，最后一个收尾
                    i += 4
                    break
                if src.startswith('"""', i):
                    i += 3
                    break
                if src[i] == "\n":
                    out.append("\n")
                i += 1
            continue

        # 普通字符串（\" 转义）
        if c == '"':
            i += 1
            while i < n and src[i] != '"':
                if src[i] == "\\":
                    i += 1
                if i < n and src[i] == "\n":
                    out.append("\n")
                i += 1
            i += 1
            continue

        # 字符字面量
        if c == "'":
            i += 1
            while i < n and src[i] != "'":
                if src[i] == "\\":
                    i += 1
                i += 1
            i += 1
            continue

        out.append(c)
        i += 1
    return "".join(out)


def check_delimiters(path, stripped):
    stack = []
    line = 1
    problems = []
    for ch in stripped:
        if ch == "\n":
            line += 1
        elif ch in OPENERS:
            stack.append((ch, line))
        elif ch in CLOSERS:
            if not stack:
                problems.append(f"{path}:{line}: 多余的 '{ch}'")
            elif stack[-1][0] != DELIM_PAIRS[ch]:
                problems.append(
                    f"{path}:{line}: '{ch}' 与第 {stack[-1][1]} 行的 '{stack[-1][0]}' 不匹配"
                )
                stack.pop()
            else:
                stack.pop()
    for ch, ln in stack:
        problems.append(f"{path}: 第 {ln} 行的 '{ch}' 未闭合")
    return problems


def check_imports(path, src):
    """检查可疑 import 与未使用的 import。返回问题列表。"""
    problems = []
    lines = src.splitlines()

    imports = []
    for idx, raw in enumerate(lines, start=1):
        m = re.match(r"^\s*import\s+([\w.]+)(?:\s+as\s+(\w+))?\s*$", raw)
        if not m:
            continue
        fq = m.group(1)
        alias = m.group(2)
        imports.append((idx, fq, alias))

    # 1) 已知的错误 import
    for idx, fq, _ in imports:
        if fq in BAD_IMPORTS:
            problems.append(f"{path}:{idx}: 可疑 import '{fq}' —— {BAD_IMPORTS[fq]}")

    # 2) 未使用的 import（保守判断：简单尾名在整个文件里没再出现）
    #
    #    ⚠️ 必须用「允许列表」排除一批**编译器插件按约定使用**的 import ——
    #    它们不会以名字形式出现在代码里，静态扫描必然误报：
    #    - getValue / setValue：`by remember {}` 委托的 operator 函数
    #    - provideDelegate：`by` 委托的另一种形式
    #    - SerializedName 等注解：只出现在注解位置（其实会出现，属漏报方向）
    #    宁可漏报也不要误报：一份满是噪音的报告会被直接无视，
    #    那比没有报告更糟（这正是 §33 批评过的"提示太多等于没提示"）。
    ALLOWLIST = {
        "getValue", "setValue", "provideDelegate",
        "get", "set",  # 委托属性 / 索引操作符
    }
    body_lines = []
    for idx, raw in enumerate(lines, start=1):
        if re.match(r"^\s*import\s+", raw):
            continue
        body_lines.append(raw)
    body = "\n".join(body_lines)

    for idx, fq, alias in imports:
        name = alias or fq.rsplit(".", 1)[-1]
        # 通配符 import 无法静态判断，跳过
        if name == "*":
            continue
        if name in ALLOWLIST:
            continue
        if not re.search(r"\b" + re.escape(name) + r"\b", body):
            problems.append(f"{path}:{idx}: 未使用的 import '{fq}'")

    return problems


def iter_kotlin(target):
    if os.path.isfile(target):
        yield target
        return
    for root, _dirs, files in os.walk(target):
        for f in files:
            if f.endswith(".kt"):
                yield os.path.join(root, f)


def check_file(path):
    with open(path, encoding="utf-8") as fh:
        src = fh.read()
    stripped = strip_literals(src)
    problems = check_delimiters(path, stripped)
    if not problems:  # 括号都断了就别再报 import，噪音
        problems += check_imports(path, src)
    return problems


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2

    targets = argv[1:]
    all_problems = []
    files = []
    for t in targets:
        if not os.path.exists(t):
            print(f"跳过（不存在）：{t}")
            continue
        files.extend(iter_kotlin(t))

    for path in sorted(files):
        try:
            all_problems += check_file(path)
        except Exception as e:  # 单个文件读失败不该中断整体
            all_problems.append(f"{path}: 读取失败 {type(e).__name__}: {e}")

    for p in all_problems:
        print(p)

    if all_problems:
        print(f"\n共 {len(all_problems)} 处问题（检查了 {len(files)} 个文件）")
        return 1
    print(f"OK  检查了 {len(files)} 个文件，无问题")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
