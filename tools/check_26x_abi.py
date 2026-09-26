#!/usr/bin/env python3
"""拿我们的编译产物去另一版 MC 的运行时 jar 里逐个核对引用，提前找出跨版本会崩的点。

为什么需要它：26.1.2 → 26.3 有三种「源码看不出来」的破坏，今天我们一个下午撞了三个：
  1. 方法改名      PoseStack.mulPose(Quaternionfc) -> rotate(Quaternionfc)
  2. 字段换包      RenderPipelines.GUI_TEXTURED 的类型从 com.mojang.blaze3d.pipeline
                   变成了 com.mojang.renderpearl.api.pipeline（名字一样但描述符变了 -> NoSuchFieldError）
  3. 内联常量      26.3 重编了整个键码空间（KEY_LSHIFT 340 -> 225）。带 ConstantValue 的
                   常量在编译期就被内联进字节码，字段名一样、编译不报错、加载也不报错，
                   只是悄悄绑到错的键码上 —— 这一条本脚本查不出来，得靠源码里别用常量（见下）。

本脚本查的是 1 和 2：把 build/classes 里每个类 javap 出来，抽出所有对非本项目的
方法/字段引用（连描述符），再去目标 jar 里看这个名字+描述符还在不在。

查不出来的：内联常量（字节码里根本没有引用，只剩一个光秃秃的数字）。
对付那类只能靠约定：**不要直接引用原版的数字/字符串常量，要用运行期查表**
（例如按键用 InputConstants.getKey("key.keyboard.left.shift")，别用 KEY_LSHIFT）。

用法：
  python tools/check_26x_abi.py "D:/Minecraft/.minecraft/versions/26.3-NeoForge_26.3.0.22-beta/26.3-NeoForge_26.3.0.22-beta.jar"
  python tools/check_26x_abi.py <jar> --verbose    # 连通过的一起列
"""

import argparse
import collections
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
CLASSES = ROOT / "build/classes/java/main"
JAVAP = pathlib.Path(r"C:\Program Files\Java\jdk-21.0.10\bin\javap.exe")

# javap -c 的注释形如：
#   // Method net/minecraft/client/KeyMapping$Category.register:(Lnet/minecraft/resources/Identifier;)L...
#   // Field  net/minecraft/client/KeyMapping.OPEN_MENU:Lnet/minecraft/client/KeyMapping;
REF = re.compile(r"// (?:Method|InterfaceMethod|Field) ([^\s:]+?)\.([^.:\s]+):([^\s]+)")


def javap(args):
    r = subprocess.run([str(JAVAP), *args], capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    return r.stdout or ""


def jar_members(classpath, owner, cache, depth=0):
    """某类在目标 classpath 里可用的 成员名:描述符 集合（含继承来的）。

    javap -s 的输出是「声明行 + descriptor 行」成对出现：
        public static com.mojang...InputConstants$Key getKey(java.lang.String);
          descriptor: (Ljava/lang/String;)Lcom/mojang/...InputConstants$Key;
    声明行的成员名 = 最后一个空格后的那个 token 再去掉参数列表。

    <p>必须连父类/接口一起收：`BlockPos.getX()` 声明在 `Vec3i` 里，只查 BlockPos 会报假阳性。
    """
    if owner in cache:
        return cache[owner]
    out = javap(["-p", "-s", "-cp", classpath, owner.replace("/", ".")])
    members = set()
    pending = None
    for line in out.splitlines():
        stripped = line.strip()
        d = re.search(r"descriptor:\s*(\S+)", stripped)
        if d:
            if pending:
                members.add(f"{pending}:{d.group(1)}")
                pending = None
            continue
        if not stripped.endswith(";") or stripped.startswith("//"):
            continue
        decl = stripped[:-1]
        name = decl.split("(")[0].strip().split()[-1] if "(" in decl else decl.split()[-1]
        name = name.split(".")[-1]  # 构造函数那行给的是全限定名，取最后一段
        # 构造函数在 javap -s 里写成类名，但引用侧写的是 "<init>"，不归一就永远匹配不上
        pending = "<init>" if name == owner.rsplit("/", 1)[-1].split("$")[-1] else name
    # 把父类/接口的成员并进来。引用是按编译期类型写的，但成员常常声明在上一层
    # （BlockPos.getX 在 Vec3i 里），只查本类会满屏假阳性。
    if depth < 6:
        header = next((l for l in out.splitlines() if re.search(r"\b(?:class|interface)\b", l)), "")
        # 注意：extends 的捕获必须在 implements 处收住，否则会把 "implements A, B" 一起吞掉
        found = re.search(r"\bextends\s+([\w.$<>, ]+?)(?=\s+implements\b|\s*\{|$)", header)
        impl = re.search(r"\bimplements\s+([\w.$<>, ]+?)(?=\s*\{|$)", header)
        for group in ([found.group(1)] if found else []) + ([impl.group(1)] if impl else []):
            for sup in group.split(","):
                sup = sup.split("<")[0].strip()  # 去泛型参数
                if not sup or sup == owner.replace("/", "."):
                    continue
                sub_members, sub_out = jar_members(classpath, sup.replace(".", "/"), cache, depth + 1)
                if sub_out.strip():
                    members |= sub_members
    cache[owner] = (members, out)
    return cache[owner]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("jar", help="目标版本的客户端 jar（未混淆、官方名）")
    ap.add_argument("--extra", action="append", default=[],
                    help="额外的 jar（NeoForge 的类在 universal jar 里，不在客户端 jar 里）")
    ap.add_argument("--verbose", action="store_true", help="连通过的也列出来")
    args = ap.parse_args()
    jars = [pathlib.Path(args.jar)] + [pathlib.Path(p) for p in args.extra]
    for j in jars:
        if not j.exists():
            sys.exit(f"找不到 jar: {j}")
    classpath = ";".join(str(j) for j in jars)  # Windows 上用 ; 分隔

    # 1) javap 我们的全部类，收集对外引用（owner -> {成员:描述符})
    refs = collections.defaultdict(set)
    for p in sorted(CLASSES.rglob("*.class")):
        cls = str(p.relative_to(CLASSES)).replace("\\", "/")[:-6].replace("/", ".")
        if "$" in cls:
            continue  # 内部类跟着外部类一起扫，且 javap 要转义，跳过更省事
        for m in REF.finditer(javap(["-c", "-p", "-cp", str(CLASSES), cls])):
            owner, member, desc = m.group(1), m.group(2).strip('"'), m.group(3)
            if owner.startswith("com/chessboard"):
                continue
            if not (owner.startswith("net/minecraft") or owner.startswith("com/mojang")
                    or owner.startswith("net/neoforged")):
                continue
            refs[owner].add(f"{member}:{desc}")

    # 2) 逐个去目标 jar 里核对
    cache = {}
    missing = collections.defaultdict(list)
    checked = 0
    for owner, members in sorted(refs.items()):
        have, out = jar_members(classpath, owner, cache)
        if not out.strip():
            missing[owner].append("  (整个类在目标版本里不存在!)")
            continue
        for mem in sorted(members):
            checked += 1
            if mem not in have:
                missing[owner].append(mem)
            elif args.verbose:
                print(f"  OK {owner}.{mem}")

    print(f"核对 {len(refs)} 个类、{checked} 个引用\n")
    if not missing:
        print("✓ 全部存在（名字+描述符都对得上）")
        return
    print(f"[{len(missing)} 个类有问题]  个类上有对不上的引用：\n")
    for owner, items in sorted(missing.items()):
        print(f"{owner}")
        for it in items[:12]:
            print(f"    {it}")
        if len(items) > 12:
            print(f"    ... 还有 {len(items)-12} 个")
        print()
    print("注意：目标类「继承来的」成员会报成对不上 —— 看的时候留意一下是不是继承的。")


if __name__ == "__main__":
    main()
