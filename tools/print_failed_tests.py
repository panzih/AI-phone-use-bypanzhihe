#!/usr/bin/env python3
"""把 JUnit XML 里挂掉的用例打到 stdout，并输出 GitHub Actions 的 ::error:: 注解。

CI 里单测红的时候，日志只有一行 "Process completed with exit code 1"，
得下载 artifact 才知道挂在哪。这个脚本让失败用例名直接出现在日志和
Annotations 区，省一步。

本地也能用：
    python3 tools/print_failed_tests.py
    python3 tools/print_failed_tests.py app/build/test-results/testDebugUnitTest
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

DEFAULT_DIR = "app/build/test-results/testDebugUnitTest"


def collect(results_dir):
    fails = []
    for path in sorted(glob.glob(os.path.join(results_dir, "*.xml"))):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        for case in root.iter("testcase"):
            for bad in list(case.findall("failure")) + list(case.findall("error")):
                text = (bad.get("message") or bad.text or "").strip()
                first = text.splitlines()[0] if text else ""
                fails.append((case.get("classname", ""), case.get("name", ""), first))
    return fails


def main():
    results_dir = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_DIR
    if not os.path.isdir(results_dir):
        print(f"::error::找不到测试结果目录 {results_dir}（可能是编译阶段就失败了）")
        return 0

    fails = collect(results_dir)
    if not fails:
        print("没有解析到失败用例；如果任务确实是红的，看上面的 Gradle 输出（多半是编译或配置失败）")
        return 0

    print(f"失败用例 {len(fails)} 项：")
    for cls, name, msg in fails:
        short = f"{cls.rsplit('.', 1)[-1]}.{name}"
        print(f"  - {short}: {msg}")
        # 让 GitHub 在 Annotations 里也列出来
        print(f"::error title={short}::{msg[:500]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
