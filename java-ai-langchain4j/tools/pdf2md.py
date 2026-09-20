#!/usr/bin/env python3
"""
pdf2md.py —— PDF → Markdown + content_list.json 的旁路转换脚本（MinerU 封装）

## 为什么是旁路脚本，而不是让 Java 解析 PDF

现有 Java 摄入管线用的是 Apache PDFBox，它按字形坐标拼文本，**没有表格概念**。
一张表经它抽取后列会错位（"科室 心内科 门诊时间 周一至周五" 按列交错），
且后续的 DocumentByParagraphSplitter 会把超过 300 字的表格拦腰切断、丢掉表头。
**全程不报错**——解析成功、索引进去了、检索也返回结果，只是内容全是残的。

所以 PDF 一律走 MinerU 转换，产出的 Markdown 给人核对、content_list.json 给管线切分。
Java 主链路零改动，符合现有"离线索引管线"的设计。

## 安装 MinerU

    pip install "mineru[core]"        # 或 pip install -U "magic-pdf[full]"（旧版）
    # 首次运行会自动下载模型，建议 GPU 环境；纯 CPU 也能跑但慢

## 用法

    python tools/pdf2md.py                  # 转换 pdf-source/ 下所有未转换的PDF
    python tools/pdf2md.py --force          # 强制重新转换
    python tools/pdf2md.py --method ocr     # 强制OCR（扫描件）
    python tools/pdf2md.py --file xxx.pdf   # 只转指定文件

## 关于 -m/--method（重要）

    auto（默认）  自动逐页判断，混合语料用这个
    txt           纯文本PDF，快，但**明确不处理图片和表格，且不报错**
    ocr           扫描件，慢且对图片质量要求高

**永远不要为了速度用 -m txt**：表格会静默消失，连残渣都不剩，
比 PDFBox 那种"抽出乱码"更难发现。目录里的扫描件请先做 300dpi + 纠偏预处理。

## 输出

转好的文件直接落到 knowledge/，之后调 POST /api/ingest 建索引即可：

    pdf-source/就诊须知.pdf
        ↓ MinerU
    knowledge/就诊须知.md                  ← 给人核对解析质量
    knowledge/就诊须知_content_list.json   ← 给 TableAwareSplitter 切分
"""

import argparse
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

PROJECT_DIR = Path(__file__).resolve().parent.parent
DEFAULT_SOURCE_DIR = PROJECT_DIR / "pdf-source"
DEFAULT_OUTPUT_DIR = PROJECT_DIR / "knowledge"


def die(message: str) -> None:
    """显式失败。与摄入管线的校验闸门同一原则：
    转换失败必须让调用方立刻知道，而不是产出一份残缺文件让它在检索阶段才暴露。"""
    print(f"[错误] {message}", file=sys.stderr)
    sys.exit(1)


def check_mineru() -> str:
    exe = shutil.which("mineru")
    if exe is None:
        die(
            "未找到 mineru 命令。\n"
            "  安装： pip install \"mineru[core]\"\n"
            "  安装后确认 `mineru --help` 可用再重试。\n"
            "  注意：本脚本不会在缺少 MinerU 时静默跳过——那样你会以为转换成功了。"
        )
    return exe


def find_produced_files(work_dir: Path, stem: str):
    """在 MinerU 的输出目录里发现产物。

    MinerU 各版本的输出目录结构不一致（可能是 <out>/<stem>/auto/<stem>.md，
    也可能是 <out>/<stem>/<stem>.md）。这里靠 rglob 发现而不是硬编码路径，
    避免换个版本就找不到文件。
    """
    md_candidates = [p for p in work_dir.rglob(f"{stem}.md") if p.is_file()]
    json_candidates = [
        p for p in work_dir.rglob("*content_list.json") if p.is_file()
    ]
    md = max(md_candidates, key=lambda p: p.stat().st_size) if md_candidates else None
    json_file = (
        max(json_candidates, key=lambda p: p.stat().st_size) if json_candidates else None
    )
    return md, json_file


def convert_one(exe: str, pdf: Path, output_dir: Path, method: str, lang: str) -> bool:
    stem = pdf.stem
    print(f"\n=== 转换 {pdf.name} ===")

    with tempfile.TemporaryDirectory(prefix="pdf2md_") as tmp:
        work_dir = Path(tmp)
        cmd = [exe, "-p", str(pdf), "-o", str(work_dir), "-m", method, "-l", lang]
        print(f"  执行： {' '.join(cmd)}")

        result = subprocess.run(cmd, capture_output=True, text=True)
        if result.returncode != 0:
            print(f"  [失败] MinerU 退出码 {result.returncode}", file=sys.stderr)
            if result.stderr:
                print(f"  stderr: {result.stderr.strip()[:2000]}", file=sys.stderr)
            return False

        md, json_file = find_produced_files(work_dir, stem)

        if md is None:
            print(f"  [失败] 未找到 {stem}.md，MinerU 可能解析失败", file=sys.stderr)
            return False
        if json_file is None:
            print(
                f"  [失败] 未找到 content_list.json。切分器依赖它识别表格块，"
                f"没有它表格会被按段落顺序切坏。请确认 MinerU 版本支持该输出",
                file=sys.stderr,
            )
            return False

        if md.stat().st_size == 0:
            print(f"  [失败] {stem}.md 为空，疑似扫描件未走OCR", file=sys.stderr)
            return False

        output_dir.mkdir(parents=True, exist_ok=True)
        target_md = output_dir / f"{stem}.md"
        target_json = output_dir / f"{stem}_content_list.json"

        shutil.copy2(md, target_md)
        shutil.copy2(json_file, target_json)

        # 粗略的表格计数，让"表格到底转出来没有"在转换阶段就可见
        table_markers = md.read_text(encoding="utf-8", errors="ignore").count("|---")
        print(f"  [完成] {target_md.name}（{target_md.stat().st_size} 字节）")
        print(f"         {target_json.name}")
        print(f"         Markdown 中检测到约 {table_markers} 处表格分隔行")
        if table_markers == 0:
            print(
                "         [提示] 没有检测到表格。若原PDF确实含表格，"
                "请确认未使用 -m txt（该模式不处理表格且不报错）"
            )
        return True


def main() -> int:
    parser = argparse.ArgumentParser(
        description="用 MinerU 把 pdf-source/ 下的 PDF 转成 knowledge/ 下的 md + content_list.json"
    )
    parser.add_argument("--source", default=str(DEFAULT_SOURCE_DIR),
                        help=f"PDF目录，默认 {DEFAULT_SOURCE_DIR}")
    parser.add_argument("--output", default=str(DEFAULT_OUTPUT_DIR),
                        help=f"产物目录，默认 {DEFAULT_OUTPUT_DIR}")
    parser.add_argument("--method", default="auto", choices=["auto", "txt", "ocr"],
                        help="MinerU解析方法，默认 auto（强烈建议保持 auto）")
    parser.add_argument("--lang", default="ch", help="文档语言，默认 ch（提升OCR准确率）")
    parser.add_argument("--force", action="store_true", help="已转换过的也重新转换")
    parser.add_argument("--file", help="只转换指定文件名（相对 source 目录）")
    args = parser.parse_args()

    source_dir = Path(args.source).resolve()
    output_dir = Path(args.output).resolve()

    if not source_dir.exists():
        die(f"PDF目录不存在：{source_dir}\n  请先创建该目录并放入待转换的 PDF。")

    if args.method == "txt":
        print(
            "[警告] 使用了 -m txt：该模式**不处理图片和表格**，且不会报错。"
            "表格会静默消失，检索阶段表现为'文档里明明有就是答不出'。",
            file=sys.stderr,
        )

    if args.file:
        pdfs = [source_dir / args.file]
        if not pdfs[0].exists():
            die(f"指定文件不存在：{pdfs[0]}")
    else:
        pdfs = sorted(p for p in source_dir.rglob("*.pdf") if p.is_file())
        pdfs += sorted(p for p in source_dir.rglob("*.PDF") if p.is_file())

    if not pdfs:
        print(f"没有在 {source_dir} 找到 PDF。请把待转换的 PDF 放进去。")
        return 0

    exe = check_mineru()
    output_dir.mkdir(parents=True, exist_ok=True)

    converted, skipped, failed = 0, 0, []

    for pdf in pdfs:
        target_json = output_dir / f"{pdf.stem}_content_list.json"
        if target_json.exists() and not args.force:
            print(f"跳过（已转换）：{pdf.name}    加 --force 可强制重转")
            skipped += 1
            continue

        if convert_one(exe, pdf, output_dir, args.method, args.lang):
            converted += 1
        else:
            failed.append(pdf.name)

    print("\n" + "=" * 56)
    print(f"转换完成：成功 {converted}，跳过 {skipped}，失败 {len(failed)}")
    if failed:
        print(f"失败文件：{', '.join(failed)}")
        print("这些文档**没有**进入知识库。修复后请重新运行本脚本。")
        return 1

    print("\n下一步： POST /api/ingest 重建索引，并检查响应中的 warnings 是否为空")
    return 0


if __name__ == "__main__":
    sys.exit(main())
