#!/usr/bin/env bash
#
# 校验三语 README 的版本号与 app/build.gradle.kts 的 versionName 一致。
#
# README 里的版本号是硬编码的（刻意为之：构建过程不该去改受版本管理的源文件，
# 否则每次构建都会弄脏工作区、在 CI 里产生幽灵 diff）。代价是发版时需要手动
# 同步三处，本脚本就是防漏的保险。
#
# 用法：bash scripts/check-version.sh     （在仓库根目录执行）

set -uo pipefail

GRADLE_FILE="app/build.gradle.kts"
READMES=("README.md" "README.en.md" "README.zh-TW.md")

if [[ ! -f "$GRADLE_FILE" ]]; then
  echo "✗ 找不到 $GRADLE_FILE，请在仓库根目录执行本脚本"
  exit 1
fi

# 从 build.gradle.kts 里取 versionName（兼容 BSD / GNU grep）
VERSION=$(sed -n 's/.*versionName[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$GRADLE_FILE" | head -1)

if [[ -z "$VERSION" ]]; then
  echo "✗ 无法从 $GRADLE_FILE 解析出 versionName"
  exit 1
fi

echo "build.gradle.kts versionName: $VERSION"

failed=0
for f in "${READMES[@]}"; do
  if [[ ! -f "$f" ]]; then
    echo "✗ 缺少文件 $f"
    failed=1
    continue
  fi

  header=$(sed -n '5p' "$f")
  doc_version=$(printf '%s' "$header" | grep -oE 'v[0-9]+\.[0-9]+\.[0-9]+' | head -1)

  if [[ -z "$doc_version" ]]; then
    echo "✗ $f 第 5 行读不到版本号：$header"
    failed=1
  elif [[ "$doc_version" != "$VERSION" ]]; then
    echo "✗ $f 版本号不一致：README=$doc_version, build.gradle.kts=$VERSION"
    failed=1
  else
    echo "✓ $f → $doc_version"
  fi

  # 版本历史表格里也应当有该版本条目
  if ! grep -qE "^\| $VERSION \|" "$f"; then
    echo "  ⚠ $f 的版本历史表格中缺少 $VERSION 条目"
  fi
done

if [[ "$failed" -ne 0 ]]; then
  echo ""
  echo "版本号校验失败：发布时请同步更新 ${READMES[*]} 的第 5 行。"
  exit 1
fi

echo ""
echo "版本号校验通过（${#READMES[@]} 个 README 均为 ${VERSION}）"