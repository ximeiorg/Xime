#!/usr/bin/env python3
"""把 GitHub Release 的正文与附件同步到 GitCode Release。

GitCode 的 tag 由其代码镜像自动同步，本脚本只负责 release 页面与附件。
仅使用 Python 3 标准库，可在本地或 GitHub Actions 中直接运行。
"""

from __future__ import annotations

import argparse
import gzip
import http.client
import json
import os
import re
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

GC_API = "https://api.gitcode.com/api/v5"
GH_API = "https://api.github.com"
UA = "xime-release-sync"

BANNER = "> 本页为 GitHub Release 的自动镜像，附件与正文由 CI 同步。"


def _retry_after(headers):
    """GitCode 的 429 不返回配额头，只有 Retry-After 可用。"""
    raw = (headers or {}).get("Retry-After")
    if not raw:
        return 0
    try:
        wait = int(raw)
    except (TypeError, ValueError):
        return 0
    return wait if 0 < wait <= 120 else 0


class SyncError(RuntimeError):
    pass


def _rate_limit_message(headers):
    """GitHub 403 响应头自带 X-RateLimit-Reset，直接换算出恢复时间。"""
    limit = (headers or {}).get("X-RateLimit-Limit") or "60"
    reset = (headers or {}).get("X-RateLimit-Reset")
    if not reset:
        return f"GitHub API 限流（每小时 {limit} 次），请设置 GITHUB_TOKEN"
    try:
        reset_at = int(reset)
    except ValueError:
        return f"GitHub API 限流（每小时 {limit} 次），请设置 GITHUB_TOKEN"
    wait = max(0, reset_at - int(time.time()))
    when = time.strftime("%H:%M", time.localtime(reset_at))
    return (
        f"GitHub API 限流（未认证每小时 {limit} 次）。"
        f"配额将于 {when} 重置，约 {wait // 60} 分 {wait % 60} 秒后恢复。"
        f"设置 GITHUB_TOKEN 可提到 5000 次/小时"
    )


def _http(url, method="GET", token=None, payload=None, timeout=120, accept="application/json"):
    headers = {"User-Agent": UA, "Accept": accept, "Accept-Encoding": "gzip"}
    if token:
        # PATCH 等接口只认 PRIVATE-TOKEN，GET 系列认 Bearer，两个都带最稳
        headers["PRIVATE-TOKEN"] = token
        headers["Authorization"] = f"Bearer {token}"
    data = None
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"

    for attempt in (1, 2):
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                raw = resp.read()
                if (resp.headers.get("Content-Encoding") or "").lower() == "gzip":
                    raw = gzip.decompress(raw)
            break
        except (http.client.IncompleteRead, ConnectionResetError, TimeoutError,
                gzip.BadGzipFile, EOFError) as exc:
            # 大响应被截断时重试一次；只对幂等的 GET 重试，避免重复创建
            if attempt == 1 and method == "GET":
                print(f"  响应传输中断，重试…（{type(exc).__name__}）")
                continue
            raise SyncError(f"{method} {url} -> 传输中断: {exc}") from exc
        except urllib.error.HTTPError as exc:
            detail = exc.read()[:600].decode("utf-8", "replace")
            # GitCode 限流（400/分、4000/小时）：按 Retry-After 自动等一次
            if exc.code == 429 and attempt == 1:
                wait = _retry_after(exc.headers)
                if wait:
                    print(f"  触发限流，{wait} 秒后重试…")
                    time.sleep(wait)
                    continue
            if exc.code == 403 and "rate limit" in detail.lower():
                raise SyncError(_rate_limit_message(exc.headers)) from exc
            raise SyncError(f"{method} {url} -> HTTP {exc.code}: {detail}") from exc
        except urllib.error.URLError as exc:
            raise SyncError(f"{method} {url} -> 网络错误: {exc.reason}") from exc

    if not raw:
        return {}
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return {"raw": raw.decode("utf-8", "replace")}


# ---------- GitHub 侧 ----------

def gh_release(repo, tag, token):
    if tag:
        url = f"{GH_API}/repos/{repo}/releases/tags/{urllib.parse.quote(tag)}"
    else:
        url = f"{GH_API}/repos/{repo}/releases/latest"
    return _http(url, token=token)


def gh_releases(repo, token, per_page=30, max_pages=10):
    releases = []
    for page in range(1, max_pages + 1):
        batch = _http(f"{GH_API}/repos/{repo}/releases?per_page={per_page}&page={page}", token=token)
        if not isinstance(batch, list) or not batch:
            break
        releases.extend(batch)
        if len(batch) < per_page:
            break
    return releases


def _progress(prefix, done, total):
    """只在终端里画进度，CI 日志保持干净。"""
    if not sys.stdout.isatty() or total <= 0:
        return
    pct = done * 100.0 / total
    sys.stdout.write(f"\r  {prefix} {pct:5.1f}%  {done / 1048576:.1f}/{total / 1048576:.1f} MB")
    sys.stdout.flush()


def _download(url, dest, token=None, timeout=120, label=""):
    headers = {"User-Agent": UA}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=timeout) as resp, open(dest, "wb") as fh:
        total = int(resp.headers.get("Content-Length") or 0)
        done = 0
        while True:
            chunk = resp.read(1 << 20)
            if not chunk:
                break
            fh.write(chunk)
            done += len(chunk)
            _progress("下载", done, total)
    if label and sys.stdout.isatty():
        sys.stdout.write("\n")
        sys.stdout.flush()


def _redirect_target(url, token):
    """GitHub asset API 会 302 到签名 CDN，签名地址不能带 Authorization。"""
    req = urllib.request.Request(
        url,
        headers={"User-Agent": UA, "Accept": "application/octet-stream", "Authorization": f"Bearer {token}"},
    )
    opener = urllib.request.build_opener(_NoRedirect)
    try:
        with opener.open(req, timeout=60) as resp:
            return resp.geturl()
    except urllib.error.HTTPError as exc:
        if exc.code in (301, 302, 303, 307, 308):
            return exc.headers["Location"]
        detail = exc.read()[:400].decode("utf-8", "replace")
        raise SyncError(f"下载附件失败 HTTP {exc.code}: {detail}") from exc


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def download_asset(asset, dest, gh_repo, gh_token):
    expected = int(asset.get("size") or 0)
    try:
        _download(asset["browser_download_url"], dest, label=asset["name"])
    except urllib.error.HTTPError:
        if not gh_token:
            raise
        signed = _redirect_target(f"{GH_API}/repos/{gh_repo}/releases/assets/{asset['id']}", gh_token)
        _download(signed, dest, label=asset["name"])
    actual = os.path.getsize(dest)
    if expected and actual != expected:
        raise SyncError(f"{asset['name']} 下载不完整: {actual}/{expected} 字节")


# ---------- GitCode 侧 ----------

def gc_release(owner, repo, tag, token):
    url = f"{GC_API}/repos/{owner}/{repo}/releases/tags/{urllib.parse.quote(tag)}"
    try:
        return _http(url, token=token)
    except SyncError as exc:
        if "HTTP 404" in str(exc):
            return None
        raise


def gc_write_release(owner, repo, tag, name, body, status, token, target_commitish=None):
    """GitCode 更新 Release 只支持 PATCH（PUT 返回 404 Route Not Found）。"""
    base = f"{GC_API}/repos/{owner}/{repo}/releases"
    existing = gc_release(owner, repo, tag, token)
    payload = {"tag_name": tag, "name": name, "body": body}
    if status:
        payload["release_status"] = status
    if existing:
        try:
            _http(
                f"{base}/{urllib.parse.quote(tag)}",
                method="PATCH",
                token=token,
                payload=payload,
            )
            return "updated"
        except SyncError as exc:
            # 正文更新失败不应连累附件
            print(f"  ! 更新 Release 信息失败，继续同步附件: {exc}")
            return "update-failed"

    try:
        _http(base, method="POST", token=token, payload=payload)
    except SyncError:
        # tag 尚未同步到 GitCode 时，用它建 tag
        if not target_commitish:
            raise
        payload["target_commitish"] = target_commitish
        _http(base, method="POST", token=token, payload=payload)
        print(f"  tag {tag} 此前不存在，已基于 {target_commitish} 创建")
    return "created"


def gc_delete_asset(owner, repo, tag, asset_id, token):
    url = f"{GC_API}/repos/{owner}/{repo}/releases/{urllib.parse.quote(tag)}/attach_files/{asset_id}"
    _http(url, method="DELETE", token=token)


def gc_upload(owner, repo, tag, path, name, token):
    query = urllib.parse.urlencode({"file_name": name})
    info = _http(f"{GC_API}/repos/{owner}/{repo}/releases/{urllib.parse.quote(tag)}/upload_url?{query}", token=token)
    put_url = info.get("url")
    if not put_url:
        raise SyncError(f"未取得 {name} 的上传地址: {info}")

    headers = {k: v for k, v in (info.get("headers") or {}).items() if v}
    size = os.path.getsize(path)
    parts = urllib.parse.urlsplit(put_url)
    target = parts.path + (f"?{parts.query}" if parts.query else "")

    conn = http.client.HTTPSConnection(parts.netloc, timeout=900)
    try:
        conn.putrequest("PUT", target, skip_accept_encoding=True)
        for key, value in headers.items():
            if key.lower() == "host":
                continue
            conn.putheader(key, value)
        conn.putheader("Content-Length", str(size))
        conn.endheaders()
        sent = 0
        with open(path, "rb") as fh:
            while True:
                chunk = fh.read(1 << 20)
                if not chunk:
                    break
                conn.send(chunk)
                sent += len(chunk)
                _progress("上传", sent, size)
        if sys.stdout.isatty():
            sys.stdout.write("\n")
            sys.stdout.flush()
        resp = conn.getresponse()
        detail = resp.read(2000).decode("utf-8", "replace")
        if resp.status not in (200, 201, 204):
            raise SyncError(f"上传 {name} 失败 HTTP {resp.status}: {detail}")
    finally:
        conn.close()


def gc_wait_asset(owner, repo, tag, name, size, token, timeout=180):
    deadline = time.time() + timeout
    while time.time() < deadline:
        rel = gc_release(owner, repo, tag, token) or {}
        for asset in rel.get("assets") or []:
            if asset.get("type") == "attach" and asset.get("name") == name and int(asset.get("size") or 0) == size:
                return asset
        time.sleep(5)
    return None


# ---------- 同步流程 ----------

def build_body(release):
    body = (release.get("body") or "").strip()
    return f"{BANNER}\n\n{body}" if body else BANNER


def pick_assets(release, excludes):
    picked = []
    for asset in release.get("assets") or []:
        name = asset.get("name") or ""
        if asset.get("state") and asset["state"] != "uploaded":
            continue
        if any(rx.search(name) for rx in excludes):
            print(f"  跳过（命中排除规则）: {name}")
            continue
        picked.append(asset)
    return picked


def latest_tag(args):
    """批量同步时只查一次 latest，避免触发 GitHub 限流。"""
    if getattr(args, "cached_latest", None) is None:
        try:
            args.cached_latest = gh_release(args.github_repo, None, args.github_token).get("tag_name") or ""
        except SyncError:
            args.cached_latest = ""
    return args.cached_latest


def sync_release(args, tag, release=None):
    gh_token = args.github_token
    if release is None:
        release = gh_release(args.github_repo, tag, gh_token)
    tag = release["tag_name"]
    print(f"\n=== {tag} ===")

    if release.get("draft"):
        print("  草稿 Release，跳过")
        return "skipped", 0, 0
    if release.get("prerelease") and not args.include_prerelease:
        print("  预发布 Release，跳过（--include-prerelease 可开启）")
        return "skipped", 0, 0

    status = "pre" if release.get("prerelease") else ("latest" if latest_tag(args) == tag else None)

    name = release.get("name") or tag
    body = build_body(release) if not args.no_banner else (release.get("body") or "")
    assets = [] if args.skip_assets else pick_assets(release, args.exclude_regex)

    existing = gc_release(args.gitcode_owner, args.gitcode_repo, tag, args.token) or {}
    remote = {a.get("name"): a for a in (existing.get("assets") or []) if a.get("type") == "attach"}

    todo = []
    for asset in assets:
        current = remote.get(asset["name"])
        if current and int(current.get("size") or 0) == int(asset.get("size") or 0):
            print(f"  已存在，跳过: {asset['name']}")
            continue
        todo.append((asset, current))

    print(f"  Release: {'更新' if existing else '创建'} | 附件 {len(todo)}/{len(assets)} 待上传")
    planned_bytes = sum(int(asset.get("size") or 0) for asset, _ in todo)
    if args.dry_run:
        for asset, current in todo:
            flag = "覆盖" if current else "新增"
            print(f"    [{flag}] {asset['name']} ({asset['size'] / 1048576:.1f} MB)")
        return "dry-run", planned_bytes, len(todo)

    action = gc_write_release(
        args.gitcode_owner,
        args.gitcode_repo,
        tag,
        name,
        body,
        status,
        args.token,
        target_commitish=release.get("target_commitish"),
    )
    print(f"  Release {action}")

    failed = 0
    with tempfile.TemporaryDirectory(prefix="xime-sync-") as tmp:
        for asset, current in todo:
            label = asset["name"]
            if current:
                try:
                    gc_delete_asset(args.gitcode_owner, args.gitcode_repo, tag, current["id"], args.token)
                    print(f"  已删除同名的旧附件: {label}")
                except SyncError as exc:
                    print(f"  ! 删除旧附件失败（继续尝试上传）: {exc}")
            dest = os.path.join(tmp, label)
            try:
                print(f"  下载 {label} ({asset['size'] / 1048576:.1f} MB)")
                download_asset(asset, dest, args.github_repo, gh_token)
                print(f"  上传 {label}")
                gc_upload(args.gitcode_owner, args.gitcode_repo, tag, dest, label, args.token)
                if gc_wait_asset(args.gitcode_owner, args.gitcode_repo, tag, label, os.path.getsize(dest), args.token):
                    print(f"  ✓ {label}")
                else:
                    print(f"  ! {label} 上传后未在 Release 中出现，请人工确认")
                    failed += 1
            except SyncError as exc:
                failed += 1
                print(f"  ✗ {label}: {exc}")
            finally:
                if os.path.exists(dest):
                    os.remove(dest)

    if failed:
        raise SyncError(f"{tag}: {failed} 个附件同步失败")
    return "synced", planned_bytes, len(todo)


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description="同步 GitHub Release 到 GitCode")
    parser.add_argument("--tag", help="要同步的 tag；留空取 GitHub 最新正式版")
    parser.add_argument("--all", action="store_true", help="同步全部正式版 Release")
    parser.add_argument("--last", type=int, metavar="N", help="同步最近 N 个正式版 Release")
    parser.add_argument("--since", metavar="TAG", help="同步该 tag 之后（不含）的全部正式版")
    parser.add_argument("--tag-filter", default=r"^v\d+\.\d+\.\d+$",
                        help="批量同步时只处理匹配此正则的 tag，默认仅正式版；传空字符串关闭过滤")
    parser.add_argument("--github-repo", default="ximeiorg/Xime")
    parser.add_argument("--gitcode-repo", default="ximeiorg/Xime")
    parser.add_argument("--github-token", default=os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN", ""),
                        help="GitHub 令牌，读取 GITHUB_TOKEN / GH_TOKEN；本地批量同步必需，否则会触发 60 次/小时的限流")
    parser.add_argument("--token", default=os.environ.get("GITCODE_TOKEN", ""),
                        help="GitCode 令牌，读取 GITCODE_TOKEN（需 api、write_repository 权限）")
    parser.add_argument("--include-prerelease", action="store_true", help="同时同步预发布版本")
    parser.add_argument("--skip-assets", action="store_true", help="只同步正文，不传附件")
    parser.add_argument("--exclude", action="append", default=[], help="排除文件名正则，可重复")
    parser.add_argument("--no-banner", action="store_true", help="正文不插入镜像说明")
    parser.add_argument("--dry-run", action="store_true", help="只打印计划，不写入")
    args = parser.parse_args(argv)

    if "/" not in args.gitcode_repo:
        parser.error("--gitcode-repo 需要 owner/repo 格式")
    if sum([bool(args.tag), args.all, bool(args.last), bool(args.since)]) > 1:
        parser.error("--tag / --all / --last / --since 只能选一个")
    args.gitcode_owner, args.gitcode_repo = args.gitcode_repo.split("/", 1)
    args.exclude_regex = [re.compile(p) for p in args.exclude]
    args.tag_regex = re.compile(args.tag_filter) if args.tag_filter else None
    if not args.token and not args.dry_run:
        parser.error("缺少 GitCode 令牌：设置环境变量 GITCODE_TOKEN 或传 --token")
    return args


def collect_targets(args):
    """返回 [(tag, release)]；tag 为 None 表示 GitHub 最新正式版。"""
    if args.tag:
        return [(args.tag, None)]
    if not (args.all or args.last or args.since):
        return [(None, None)]

    releases = [r for r in gh_releases(args.github_repo, args.github_token) if not r.get("draft")]
    if not args.include_prerelease:
        releases = [r for r in releases if not r.get("prerelease")]
    if args.tag_regex:
        releases = [r for r in releases if args.tag_regex.search(r.get("tag_name") or "")]
    names = [r.get("tag_name") for r in releases]

    if args.since:
        if args.since not in names:
            raise SyncError(f"--since 指定的 {args.since} 不在 Release 列表中")
        releases = releases[: names.index(args.since)]
    if args.last:
        releases = releases[: args.last]

    releases.reverse()  # 旧 → 新，最新版最后落 latest 标记
    return [(r.get("tag_name"), r) for r in releases]


def main(argv=None):
    args = parse_args(argv)
    try:
        targets = collect_targets(args)
    except SyncError as exc:
        print(f"无法列出待同步版本: {exc}", file=sys.stderr)
        return 1

    if not targets:
        print("没有匹配的 Release")
        return 0
    if len(targets) > 1:
        print(f"待处理 {len(targets)} 个 Release: {', '.join(t or 'latest' for t, _ in targets)}")

    done = skipped = 0
    total_bytes = total_files = 0
    failures = []
    for tag, release in targets:
        try:
            result, nbytes, nfiles = sync_release(args, tag, release)
            total_bytes += nbytes
            total_files += nfiles
            if result == "skipped":
                skipped += 1
            else:
                done += 1
        except SyncError as exc:
            failures.append(f"{tag or 'latest'}: {exc}")
            print(f"  ✗ {exc}", file=sys.stderr)

    if len(targets) > 1:
        verb = "预计上传" if args.dry_run else "已上传"
        print(f"\n完成：成功 {done}，跳过 {skipped}，失败 {len(failures)}")
        print(f"{verb} {total_files} 个附件，共 {total_bytes / 1073741824:.2f} GB")
    for item in failures:
        print(f"  失败 {item}", file=sys.stderr)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
