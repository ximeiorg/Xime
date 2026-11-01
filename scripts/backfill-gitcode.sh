#!/usr/bin/env bash
# 本地把 GitHub Release 回填到 GitCode 镜像仓库。
#
#   bash scripts/backfill-gitcode.sh              # 预览：v2.7.1 之后缺失的版本
#   bash scripts/backfill-gitcode.sh --yes        # 实际执行
#   bash scripts/backfill-gitcode.sh --yes --last 3   # 只补最近 3 个正式版
#   bash scripts/backfill-gitcode.sh --yes --all      # 全量对齐（老版本附件会重传）
#
# 凭据写在仓库根目录 .env.local（已在 .gitignore 中）：
#   GITCODE_TOKEN=xxxx   # https://gitcode.com/setting/token-classic  勾选 api、write_repository
#   GITHUB_TOKEN=xxxx    # https://github.com/setting/tokens  只读仓库即可
# GITHUB_TOKEN 本地批量强烈建议配置：未认证时每小时只有 60 次 API 配额。
#
# 已同步过的版本按「同名同大小」跳过，中断后重跑是安全的。
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -f .env.local ]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env.local
  set +a
fi

# GitCode 上最后一个手动同步的版本，默认从它之后开始补
BASE_TAG="${BASE_TAG:-v2.7.1}"

run=0
args=()
for arg in "$@"; do
  case "$arg" in
    --yes|-y) run=1 ;;
    *) args+=("$arg") ;;
  esac
done

if [ ${#args[@]} -eq 0 ]; then
  args=(--since "$BASE_TAG")
fi
if [ "$run" -eq 0 ]; then
  echo "== 预览模式，不写入 GitCode；确认无误后加 --yes 执行 =="
  args+=(--dry-run)
fi

if [ "$run" -eq 1 ] && [ -z "${GITCODE_TOKEN:-}" ]; then
  cat >&2 <<'EOF'
缺少 GITCODE_TOKEN。
  1. 打开 https://gitcode.com/setting/token-classic 生成令牌（勾选 api、write_repository）
  2. 在仓库根目录写 .env.local：GITCODE_TOKEN=xxxx
EOF
  exit 1
fi

if [ -z "${GITHUB_TOKEN:-}" ] && [ -z "${GH_TOKEN:-}" ]; then
  echo "提示：未设置 GITHUB_TOKEN，GitHub API 每小时仅 60 次，批量同步可能中途中断" >&2
fi

exec python3 scripts/sync-release-to-gitcode.py ${args[@]+"${args[@]}"}
