#!/usr/bin/env python3
"""生成 fdroiddata 里 Xime 新预发布版本的 Builds 条目。

    python3 scripts/fdroid-alpha-recipe.py --tag v3.5.0-alpha.5
    python3 scripts/fdroid-alpha-recipe.py --tag v3.5.0-alpha.5 \\
        --write ~/vscode/fdroiddata/metadata/com.kingzcheung.xime.yml

commit 取 tag 指向的提交，versionName/versionCode 直接读该提交里的
app/build.gradle.kts，避免手抄。--write 会插到 `AllowedAPKSigningKeys:` 之前；
`CurrentVersion` 保持稳定版不动——预发布不进 F-Droid 的自动更新链。
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

TEMPLATE = """\
  - versionName: {version_name}
    versionCode: {version_code}
    commit: {commit}
    subdir: app
    submodules: true
    sudo:
      - apt-get update
      - apt-get install -y python-is-python3
    gradle:
      - yes
    srclibs:
      - rustup@1.28.2
    prebuild:
      - mkdir -p build
      - sed -i -e '/abiFilters += listOf/d' -e 's/include(.*)/include("arm64-v8a")/'
        -e '/isUniversalApk/s/true/false/' build.gradle.kts
      - sed -i 's|mirrors.cloud.tencent.com/gradle/|services.gradle.org/distributions/|'
        ../gradle/wrapper/gradle-wrapper.properties
      - sed -i -e '/tencent/d' -e '/aliyun/d' ../settings.gradle.kts
      - $$rustup$$/rustup-init.sh -y --profile minimal --default-toolchain 1.88.0
        --target aarch64-linux-android
      - cd .. && ABI=arm64-v8a PATH="$HOME/.cargo/bin:$PATH" bash scripts/build-p2p-android.sh
        && cd app
    scanignore:
      - app/src/main/assets/ChaiPUA-0.2.7-snow.ttf
    scandelete:
      - app/src/main/jni/librime-predict/tools/make_predict_data/Cargo.toml
    ndk: 29.0.14206865
"""

MARKER = "AllowedAPKSigningKeys:"


def git(repo: Path, *args: str) -> str:
    return subprocess.run(
        ["git", "-C", str(repo), *args],
        check=True, capture_output=True, text=True,
    ).stdout


def resolve_commit(repo: Path, tag: str | None, commit: str | None) -> str:
    if commit:
        return git(repo, "rev-parse", f"{commit}^{{commit}}").strip()
    assert tag
    try:
        return git(repo, "rev-parse", f"{tag}^{{commit}}").strip()
    except subprocess.CalledProcessError:
        sys.exit(f"找不到 tag {tag}，先 git fetch --tags，或用 --commit 指定提交")


def read_version(repo: Path, ref: str) -> tuple[str, int]:
    text = git(repo, "show", f"{ref}:app/build.gradle.kts")
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    code = re.search(r"versionCode\s*=\s*(\d+)", text)
    if not name or not code:
        sys.exit(f"{ref} 的 app/build.gradle.kts 里找不到 versionName/versionCode")
    return name.group(1), int(code.group(1))


def build_entry(repo: Path, commit: str) -> str:
    version_name, version_code = read_version(repo, commit)
    return TEMPLATE.format(
        version_name=version_name, version_code=version_code, commit=commit,
    )


def warn_if_unreachable(repo: Path, commit: str, upstream_ref: str) -> None:
    """F-Droid 是从 metadata 里的 Repo（ximeiorg/Xime）克隆后 checkout 的。

    本地 fork 上的提交若还没合进上游主仓，那边根本没有这个对象，构建会以
    `Git checkout of '<sha>' failed` 收场，所以这里提前对比上游分支。
    """
    try:
        git(repo, "rev-parse", "--verify", f"{upstream_ref}^{{commit}}")
    except subprocess.CalledProcessError:
        print(
            f"注意：本地没有 {upstream_ref}，无法确认提交是否已合进上游。\n"
            f"      可先 git fetch <上游> main:refs/remotes/{upstream_ref}",
            file=sys.stderr,
        )
        return
    merged = subprocess.run(
        ["git", "-C", str(repo), "merge-base", "--is-ancestor", commit, upstream_ref],
        capture_output=True,
    )
    if merged.returncode != 0:
        print(
            f"警告：{commit[:10]} 不在 {upstream_ref} 上。F-Droid 从 Repo 克隆后 checkout\n"
            "      这一步会失败，先把它合并进上游（ximeiorg/Xime）并把 tag 推上去。",
            file=sys.stderr,
        )


PRERELEASE_TAG_MODE = r"Tags ^v\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?$"


def update_current(lines: list[str], version_name: str, version_code: int) -> list[str]:
    r"""把 CurrentVersion/CurrentVersionCode 对齐新配方，并让 tag 正则能匹配预发布。

    checkupdates 会因为「最新配方比能匹配到的最新 tag 还新」直接报错
    （latest build recipe is newer），预发布 tag 带字母，`^v[\d.]+$` 匹配不到。
    """
    touched: list[str] = []
    prerelease = "-" in version_name
    for index, line in enumerate(lines):
        if line.startswith("CurrentVersion: "):
            lines[index] = f"CurrentVersion: {version_name}\n"
            touched.append("CurrentVersion")
        elif line.startswith("CurrentVersionCode: "):
            lines[index] = f"CurrentVersionCode: {version_code}\n"
            touched.append("CurrentVersionCode")
        elif prerelease and line.startswith("UpdateCheckMode: Tags"):
            if line.strip() != PRERELEASE_TAG_MODE:
                lines[index] = PRERELEASE_TAG_MODE + "\n"
                touched.append("UpdateCheckMode")
    return touched


def write_into(path: Path, entry: str, version_name: str, update_current_versions: bool) -> None:
    lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
    if any(l.startswith(f"  - versionName: {version_name}") for l in lines):
        sys.exit(f"{path} 里已有 {version_name} 的条目，无需重复插入")
    for index, line in enumerate(lines):
        if line.startswith(MARKER):
            break
    else:
        sys.exit(f"{path} 里找不到 {MARKER}，不确定该插到哪，已放弃写入")
    lines[index:index] = [entry, "\n"]
    if update_current_versions:
        version_code = int(re.search(r"versionCode: (\d+)", entry).group(1))
        touched = update_current(lines, version_name, version_code)
        if touched:
            print(f"已更新：{', '.join(touched)}", file=sys.stderr)
    path.write_text("".join(lines), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default=".", help="Xime 仓库根目录（默认当前目录）")
    parser.add_argument("--tag", help="发布 tag，如 v3.5.0-alpha.5")
    parser.add_argument("--commit", help="直接指定提交（tag 还没推上来时用）")
    parser.add_argument("--write", help="插入到该 fdroiddata metadata 文件")
    parser.add_argument(
        "--update-current", action="store_true",
        help="同时对齐 CurrentVersion/CurrentVersionCode，并放宽 UpdateCheckMode 以匹配预发布 tag",
    )
    parser.add_argument(
        "--upstream-ref", default="upstream/main",
        help="上游主仓分支的本地引用（默认 upstream/main），用于校验提交是否已合并",
    )
    args = parser.parse_args()

    if not args.tag and not args.commit:
        parser.error("--tag 与 --commit 至少要给一个")

    repo = Path(args.repo).resolve()
    commit = resolve_commit(repo, args.tag, args.commit)
    entry = build_entry(repo, commit)
    warn_if_unreachable(repo, commit, args.upstream_ref)
    sys.stdout.write(entry)

    if args.write:
        name = re.search(r"versionName: (.+)", entry).group(1).strip()
        target = Path(args.write).expanduser().resolve()
        write_into(target, entry, name, args.update_current)
        print(f"\n已写入 {target}", file=sys.stderr)


if __name__ == "__main__":
    main()
