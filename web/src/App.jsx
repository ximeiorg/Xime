import React, { useCallback, useEffect, useRef, useState } from 'react'
import { EditorView, basicSetup } from 'codemirror'
import { yaml } from '@codemirror/lang-yaml'
import { keymap } from '@codemirror/view'
import { Prec } from '@codemirror/state'
import logo from './assets/logo.png'

const ACCEPT = '.yaml,.zip,.tar.gz,.tgz,.gram,.jpg,.jpeg,.png,.xipk,.ttf,.otf,.woff,.woff2'
const SUPPORTED = /\.(yaml|zip|tar\.gz|tgz|gram|jpe?g|png|xipk|ttf|otf|woff2?)$/i

/** 可在浏览器里编辑的文本类型；与 Android 端 WirelessTextFilePolicy 保持一致。 */
const EDITABLE = /\.(yaml|yml|txt|md|json|conf|lua)$/i
/** 服务端只允许写 files/rime 下的文件，其它目录即使扩展名匹配也不提供「编辑」。 */
const RIME_PATH = /\/files\/rime\//
const EDIT_MAX_BYTES = 2 * 1024 * 1024
/** 只读预览上限：更大的词典/binary 请下载查看，避免整文件塞进页面。 */
const VIEW_MAX_BYTES = 1024 * 1024

function supported(name) {
  return SUPPORTED.test(name)
}

function editable(node) {
  return (
    !node.isDir &&
    RIME_PATH.test(node.path) &&
    EDITABLE.test(node.name) &&
    (Number(node.size) || 0) <= EDIT_MAX_BYTES
  )
}

function uid() {
  return Math.random().toString(36).slice(2, 10)
}

function statusText(status, progress) {
  switch (status) {
    case 'uploading': return `上传中 ${progress}%`
    case 'ok': return '已完成'
    case 'fail': return '失败'
    default: return '待上传'
  }
}

const badgeClass = {
  pending: 'text-gray-400',
  uploading: 'text-blue-500',
  ok: 'text-green-600',
  fail: 'text-red-500',
}

function formatSize(bytes) {
  const b = Number(bytes)
  if (!Number.isFinite(b) || b < 0) return '0 B'
  if (b < 1024) return `${b} B`
  if (b < 1024 * 1024) return `${(b / 1024).toFixed(1)} KB`
  return `${(b / 1024 / 1024).toFixed(2)} MB`
}

function formatTime(ms) {
  if (!ms) return ''
  const d = new Date(ms)
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function typeIcon(name, isDir) {
  if (isDir) return '📁'
  const lower = name.toLowerCase()
  if (lower.endsWith('.schema.yaml')) return '⚙️'
  if (lower.endsWith('.dict.yaml') || lower.endsWith('.yaml')) return '📘'
  if (lower.endsWith('.zip')) return '📦'
  if (lower.endsWith('.tar.gz') || lower.endsWith('.tgz')) return '🗜️'
  if (lower.endsWith('.txt') || lower.endsWith('.md')) return '📝'
  if (lower.endsWith('.db')) return '🗄️'
  if (lower.endsWith('.png') || lower.endsWith('.jpg') || lower.endsWith('.webp')) return '🖼️'
  if (lower.endsWith('.ttf') || lower.endsWith('.otf') || lower.endsWith('.woff') || lower.endsWith('.woff2')) return '🔤'
  return '📄'
}

function typeBadge(name, isDir) {
  if (isDir) return '目录'
  const lower = name.toLowerCase()
  if (lower.endsWith('.schema.yaml')) return '方案'
  if (lower.endsWith('.dict.yaml')) return '词典'
  if (lower.endsWith('.zip')) return 'ZIP'
  if (lower.endsWith('.xipk')) return '插件'
  if (lower.endsWith('.tar.gz') || lower.endsWith('.tgz')) return '归档'
  if (lower.endsWith('.yaml')) return '配置'
  if (lower.endsWith('.txt')) return '文本'
  if (lower.endsWith('.png') || lower.endsWith('.jpg') || lower.endsWith('.jpeg') || lower.endsWith('.webp')) return '图片'
  if (lower.endsWith('.ttf') || lower.endsWith('.otf') || lower.endsWith('.woff') || lower.endsWith('.woff2')) return '字体'
  if (lower.endsWith('.xipk')) return '插件'
  return '文件'
}

function countStats(node) {
  let dirs = 0
  let files = 0
  let totalBytes = 0
  const walk = (n) => {
    if (n.isDir) {
      dirs += 1
      n.children?.forEach(walk)
    } else {
      files += 1
      totalBytes += (Number(n.size) || 0)
    }
  }
  walk(node)
  return { dirs, files, totalBytes }
}

export default function App() {
  const inputRef = useRef(null)
  const previewRef = useRef(null)
  const [files, setFiles] = useState([])
  const [dragOver, setDragOver] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [summary, setSummary] = useState(null)

  const [tree, setTree] = useState(null)
  const [treeErr, setTreeErr] = useState(null)
  const [expanded, setExpanded] = useState({})
  const [preview, setPreview] = useState(null)
  const [editing, setEditing] = useState(null)
  const [loading, setLoading] = useState(false)
  const [stats, setStats] = useState({ dirs: 0, files: 0, totalBytes: 0 })

  const addFiles = useCallback((list) => {
    const invalid = []
    const newFiles = []
    for (const f of list) {
      if (!supported(f.name)) {
        invalid.push(f.name)
        continue
      }
      if (!files.some((x) => x.name === f.name)) {
        newFiles.push({ id: uid(), name: f.name, file: f, status: 'pending', progress: 0 })
      }
    }
    if (invalid.length) alert('不支持的类型: ' + invalid.join(', '))
    if (newFiles.length) setFiles((prev) => [...prev, ...newFiles])
  }, [files])

  const onDrop = useCallback((e) => {
    e.preventDefault()
    setDragOver(false)
    if (e.dataTransfer?.files) addFiles(Array.from(e.dataTransfer.files))
  }, [addFiles])

  const update = useCallback((id, patch) => {
    setFiles((prev) => prev.map((f) => (f.id === id ? { ...f, ...patch } : f)))
  }, [])

  const uploadOne = useCallback((file, id) => {
    const fd = new FormData()
    fd.append('file', file)
    const xhr = new XMLHttpRequest()
    xhr.open('POST', '/upload', true)
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) update(id, { progress: Math.round((e.loaded / e.total) * 100) })
    }
    xhr.onload = () => {
      let ok = false
      try {
        ok = JSON.parse(xhr.responseText)?.success === true
      } catch (_) { /* ignore */ }
      update(id, { progress: 100, status: ok ? 'ok' : 'fail' })
    }
    xhr.onerror = () => update(id, { progress: 100, status: 'fail' })
    update(id, { status: 'uploading', progress: 0 })
    xhr.send(fd)
  }, [update])

  const refreshTree = useCallback(async () => {
    setLoading(true)
    setTreeErr(null)
    try {
      const res = await fetch('/tree')
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const data = await res.json()
      setTree(data)
      setStats(countStats(data))
    } catch (e) {
      setTreeErr(String(e.message || e))
    } finally {
      setLoading(false)
    }
  }, [])

  const upload = useCallback(() => {
    if (!files.length || uploading) return
    setUploading(true)
    setSummary(null)
    const pending = files.filter((f) => f.status !== 'ok')
    pending.forEach((f) => uploadOne(f.file, f.id))

    const timer = setInterval(() => {
      setFiles((prev) => {
        const done = prev.filter((f) => f.status === 'ok' || f.status === 'fail').length
        const total = prev.length
        if (done === total) {
          clearInterval(timer)
          const ok = prev.filter((f) => f.status === 'ok').length
          const fail = total - ok
          setSummary({ ok, fail })
          setUploading(false)
          refreshTree()
          if (fail === 0) return []
        }
        return prev
      })
    }, 200)
  }, [files, uploading, uploadOne, refreshTree])

  useEffect(() => {
    refreshTree()
    const t = setInterval(refreshTree, 5000)
    return () => clearInterval(t)
  }, [refreshTree])

  // 预览面板在长目录树之后：打开文件后滚到可见位置，否则手机上要手动翻很久。
  useEffect(() => {
    if (preview) previewRef.current?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  }, [preview])

  const toggleDir = useCallback((node) => {
    if (!node.isDir) return
    setExpanded((prev) => ({ ...prev, [node.path]: !prev[node.path] }))
  }, [])

  const readText = useCallback(async (node) => {
    const res = await fetch(`/read?path=${encodeURIComponent(node.path)}`)
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    return res.text()
  }, [])

  const openFile = useCallback(async (node) => {
    if ((Number(node.size) || 0) > VIEW_MAX_BYTES) {
      alert('文件较大，请在右侧下载后查看')
      return
    }
    try {
      setPreview({ node, text: await readText(node) })
    } catch (e) {
      alert('打开文件失败: ' + (e.message || e))
    }
  }, [readText])

  const openEditor = useCallback(async (node) => {
    if (!editable(node)) {
      alert('该文件不支持编辑（仅限 2MB 内的 yaml / txt / md / json / conf / lua）')
      return
    }
    try {
      const text = await readText(node)
      setEditing({ node, text })
      setPreview(null)
    } catch (e) {
      alert('打开文件失败: ' + (e.message || e))
    }
  }, [readText])

  const saveText = useCallback(async (node, content) => {
    const res = await fetch('/write', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ path: node.path, content }),
    })
    let data = {}
    try {
      data = await res.json()
    } catch (_) { /* ignore */ }
    if (!res.ok || data.success !== true) {
      throw new Error(data.error || `HTTP ${res.status}`)
    }
    refreshTree()
  }, [refreshTree])

  const downloadFile = useCallback((node) => {
    const a = document.createElement('a')
    a.href = `/download?path=${encodeURIComponent(node.path)}`
    a.download = node.name
    document.body.appendChild(a)
    a.click()
    a.remove()
  }, [])

  const deleteNode = useCallback(async (node) => {
    const label = node.isDir ? '目录' : '文件'
    if (!window.confirm(`确定删除${label} ${node.name} 吗？`)) return
    try {
      const res = await fetch('/delete', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ path: node.path }),
      })
      const data = await res.json()
      if (data.success) {
        refreshTree()
      } else {
        alert('删除失败: ' + (data.error || 'unknown'))
      }
    } catch (e) {
      alert('删除失败: ' + (e.message || e))
    }
  }, [refreshTree])

  return (
    <div className="flex min-h-full flex-col md:h-full md:flex-row">
      {/* 左栏：Logo + 上传，贴左、占满高度 */}
      <div className="flex w-full shrink-0 flex-col border-b border-gray-200 bg-white p-5 md:h-full md:w-[480px] md:border-b-0 md:border-r md:p-8">
        <div className="mb-5 flex items-center gap-3 md:mb-8 md:flex-col md:gap-3 md:text-center">
          <img
            src={logo}
            alt="Xime 输入法"
            className="h-14 w-14 shrink-0 rounded-2xl object-contain md:h-28 md:w-28 md:rounded-3xl"
          />
          <div className="text-left md:text-center">
            <div className="text-lg font-semibold text-gray-900 md:text-xl">Xime 输入法</div>
            <div className="text-xs text-gray-400 md:text-sm">无线导入方案</div>
          </div>
        </div>

        <h1 className="mb-2 text-center text-lg font-semibold text-gray-900 md:text-xl">导入输入方案</h1>
        <p className="mb-4 text-center text-xs text-gray-500 md:mb-6 md:text-sm">
          将方案压缩包或者 xime.custom.yaml 拖拽到下方，或点击选择
        </p>

        <div
          onClick={() => inputRef.current?.click()}
          onDragOver={(e) => { e.preventDefault(); setDragOver(true) }}
          onDragLeave={() => setDragOver(false)}
          onDrop={onDrop}
          className={
            'flex min-h-[8rem] cursor-pointer flex-col items-center justify-center rounded-xl border-2 border-dashed p-5 text-center transition-all md:min-h-[20rem] md:p-10 ' +
            (dragOver
              ? 'border-blue-500 bg-blue-50'
              : files.length
                ? 'border-green-500 bg-green-50'
                : 'border-gray-300')
          }
        >
          <div className="mb-2 text-3xl text-gray-400 md:mb-3 md:text-4xl">📄</div>
          <div className="text-sm text-gray-400">拖拽文件到此处</div>
          <div className="mt-2 text-xs text-gray-300">          支持 .yaml / .zip / .tar.gz / 插件(.xipk) / 图片(.jpg/.png) / 字体(.ttf/.otf)</div>
        </div>

        <input
          ref={inputRef}
          type="file"
          accept={ACCEPT}
          multiple
          className="hidden"
          onChange={(e) => { if (e.target.files) addFiles(Array.from(e.target.files)); e.target.value = '' }}
        />

        {files.length > 0 && (
          <div className="mt-4 space-y-2">
            {files.map((f) => (
              <div key={f.id} className="relative flex items-center justify-between overflow-hidden rounded-lg bg-gray-100 px-3 py-2 text-xs">
                <div className="absolute inset-y-0 left-0 bg-blue-200 transition-all" style={{ width: `${f.progress}%` }} />
                <span className="relative z-10 text-gray-900">{f.name}</span>
                <span className={'relative z-10 ml-2 whitespace-nowrap ' + badgeClass[f.status]}>
                  {statusText(f.status, f.progress)}
                </span>
              </div>
            ))}
          </div>
        )}

        <button
          disabled={!files.length || uploading}
          onClick={upload}
          className="mt-4 w-full rounded-xl bg-blue-500 py-3 text-base font-medium text-white transition-colors hover:bg-blue-600 disabled:cursor-default disabled:bg-gray-300"
        >
          {uploading ? '上传中...' : '上传'}
        </button>

        <div className="mt-4 hidden rounded-lg bg-gray-100 p-2.5 text-left text-xs leading-relaxed text-gray-400 md:block">
          <div>💡 上传文件会自动分类放置：</div>
          <div>· 方案压缩包(.zip/.tar.gz) → market 目录</div>
          <div>· 配置文件(.yaml) → rime 目录</div>
          <div>· 图片(.jpg/.png) → themes 目录</div>
          <div>· 字体(.ttf/.otf) → fonts 目录</div>
        </div>

        {summary && (
          <div
            className={
              'mt-4 rounded-lg py-3 text-center text-sm ' +
              (summary.fail ? 'bg-red-50 text-red-500' : 'bg-green-50 text-green-600')
            }
          >
            {summary.ok}/{summary.ok + summary.fail} 个成功
            {summary.fail ? `，${summary.fail} 个失败` : '，请在手机上部署'}
          </div>
        )}
      </div>

      {/* 右栏：数据目录，填满剩余空间 */}
      <div className="flex min-w-0 flex-1 flex-col p-4 md:h-full md:p-6">
        <div className="mb-3 flex items-center justify-between md:mb-4">
          <h2 className="text-base font-semibold text-gray-900">数据目录</h2>
          <div className="flex items-center gap-2">
            {loading && <span className="text-xs text-gray-400">刷新中...</span>}
            <button
              onClick={refreshTree}
              className="rounded-lg bg-gray-100 px-3 py-1 text-xs text-gray-600 hover:bg-gray-200"
            >
              刷新
            </button>
          </div>
        </div>

        {tree && (
          <div className="mb-3 flex flex-wrap gap-x-5 gap-y-1 text-xs text-gray-500 md:mb-4 md:text-sm">
            <span className="inline-flex items-center gap-1.5"><span className="text-gray-400">📁</span>{stats.dirs} 目录</span>
            <span className="inline-flex items-center gap-1.5"><span className="text-gray-400">📄</span>{stats.files} 文件</span>
            <span className="inline-flex items-center gap-1.5"><span className="text-gray-400">📦</span>{formatSize(stats.totalBytes)}</span>
          </div>
        )}

        <div className="min-h-[14rem] flex-1 overflow-auto rounded-2xl bg-white p-3 shadow-lg md:min-h-0 md:p-4">
          {treeErr ? (
            <div className="rounded-lg bg-red-50 py-3 text-center text-sm text-red-500">加载失败: {treeErr}</div>
          ) : tree ? (
            <div>
              <FileTreeNode
                node={tree}
                depth={0}
                expanded={expanded}
                onToggle={toggleDir}
                onOpen={openFile}
                onEdit={openEditor}
                onDelete={deleteNode}
                onDownload={downloadFile}
              />
            </div>
          ) : (
            <div className="py-8 text-center text-sm text-gray-400">加载中...</div>
          )}

          {preview && (
            <div ref={previewRef} className="mt-4 rounded-xl border border-gray-200">
              <div className="flex items-center justify-between border-b border-gray-200 bg-gray-50 px-3 py-2 text-xs">
                <span className="min-w-0 truncate font-medium text-gray-700">{preview.node.path}</span>
                <span className="flex shrink-0 items-center gap-1">
                  <button
                    onClick={() => downloadFile(preview.node)}
                    className="rounded px-1.5 text-emerald-600 hover:bg-emerald-50"
                  >
                    下载
                  </button>
                  {editable(preview.node) && (
                    <button
                      onClick={() => openEditor(preview.node)}
                      className="rounded px-1.5 text-blue-600 hover:bg-blue-50"
                    >
                      编辑
                    </button>
                  )}
                  <button onClick={() => setPreview(null)} className="px-1 text-gray-400 hover:text-gray-600">✕</button>
                </span>
              </div>
              <pre className="max-h-80 overflow-auto whitespace-pre-wrap break-all p-3 text-xs text-gray-700">
                {preview.text}
              </pre>
            </div>
          )}
        </div>
      </div>

      {editing && (
        <EditorDialog
          key={editing.node.path}
          file={editing.node}
          initialText={editing.text}
          onClose={() => setEditing(null)}
          onSave={(content) => saveText(editing.node, content)}
          onReload={() => readText(editing.node)}
        />
      )}
    </div>
  )
}

function FileTreeNode({ node, depth, expanded, onToggle, onOpen, onEdit, onDelete, onDownload }) {
  const isExpanded = expanded[node.path]
  const padding = { paddingLeft: `${depth * 20 + 4}px` }

  return (
    <div>
      <div
        className="group flex items-center gap-1.5 rounded-md py-2 pr-1 hover:bg-gray-100 md:gap-2 md:pr-2"
        style={padding}
      >
        {node.isDir ? (
          <button
            onClick={() => onToggle(node)}
            className="w-5 text-center text-sm text-gray-400 hover:text-gray-600"
          >
            {isExpanded ? '▾' : '▸'}
          </button>
        ) : (
          <span className="w-5 text-center text-sm text-gray-300">·</span>
        )}
        <span className="text-base leading-none">{typeIcon(node.name, node.isDir)}</span>
        <span
          className={
            'min-w-0 flex-1 truncate text-[15px] ' +
            (node.isDir ? 'cursor-pointer font-semibold text-gray-800' : 'cursor-pointer text-gray-600')
          }
          onClick={() => (node.isDir ? onToggle(node) : onOpen(node))}
          title={node.path}
        >
          {node.name}
        </span>

        {node.isDir ? (
          <span className="shrink-0 text-xs text-gray-400">
            {node.children?.length ?? 0} 项
          </span>
        ) : (
          <>
            <span className="shrink-0 text-xs text-gray-400">{formatSize(node.size)}</span>
            <span className="hidden shrink-0 rounded bg-gray-100 px-1.5 py-0.5 text-[11px] text-gray-500 md:inline">
              {typeBadge(node.name, false)}
            </span>
          </>
        )}

        <span className="hidden shrink-0 text-xs text-gray-300 lg:inline">
          {formatTime(node.mtime)}
        </span>

        {!node.isDir && editable(node) && (
          <button
            onClick={() => onEdit(node)}
            className="hidden whitespace-nowrap rounded px-1.5 text-xs text-blue-600 hover:bg-blue-50 group-hover:inline-flex pointer-coarse:inline-flex md:text-sm"
          >
            编辑
          </button>
        )}
        {!node.isDir && (
          <button
            onClick={() => onOpen(node)}
            className="hidden whitespace-nowrap rounded px-1.5 text-xs text-gray-500 hover:bg-gray-100 group-hover:inline-flex md:text-sm"
          >
            查看
          </button>
        )}
        {!node.isDir && (
          <button
            onClick={() => onDownload(node)}
            className="hidden whitespace-nowrap rounded px-1.5 text-xs text-emerald-600 hover:bg-emerald-50 group-hover:inline-flex md:text-sm"
          >
            下载
          </button>
        )}
        <button
          onClick={() => onDelete(node)}
          className="hidden whitespace-nowrap rounded px-1.5 text-xs text-red-500 hover:bg-red-50 group-hover:inline-flex pointer-coarse:inline-flex md:text-sm"
        >
          删除
        </button>
      </div>
      {node.isDir && isExpanded && node.children && (
        <div>
          {node.children.length === 0 ? (
            <div className="py-1.5 text-sm text-gray-300" style={{ paddingLeft: `${(depth + 1) * 20 + 20}px` }}>
              空目录
            </div>
          ) : (
            node.children.map((c) => (
              <FileTreeNode
                key={c.path}
                node={c}
                depth={depth + 1}
                expanded={expanded}
                onToggle={onToggle}
                onOpen={onOpen}
                onEdit={onEdit}
                onDelete={onDelete}
                onDownload={onDownload}
              />
            ))
          )}
        </div>
      )}
    </div>
  )
}

function EditorDialog({ file, initialText, onClose, onSave, onReload }) {
  const [editorText, setEditorText] = useState(initialText)
  const [draft, setDraft] = useState(initialText)
  const [saved, setSaved] = useState(initialText)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState(null)
  const [notice, setNotice] = useState(null)
  const [editorKey, setEditorKey] = useState(0)
  const dirty = draft !== saved

  const close = () => {
    if (dirty && !window.confirm('有未保存的修改，确定关闭吗？')) return
    onClose()
  }

  const save = async () => {
    if (saving || !dirty) return
    setSaving(true)
    setError(null)
    setNotice(null)
    try {
      await onSave(draft)
      setSaved(draft)
      setNotice('已保存，请回手机点「部署」')
    } catch (e) {
      setError(String(e.message || e))
    } finally {
      setSaving(false)
    }
  }

  const reload = async () => {
    if (saving) return
    if (dirty && !window.confirm('放弃未保存的修改并重新加载？')) return
    setError(null)
    setNotice(null)
    try {
      const text = await onReload()
      setEditorText(text)
      setDraft(text)
      setSaved(text)
      setEditorKey((k) => k + 1)
    } catch (e) {
      setError(String(e.message || e))
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 sm:p-6">
      <div className="flex h-full w-full flex-col overflow-hidden bg-white shadow-2xl sm:h-[85vh] sm:max-w-5xl sm:rounded-2xl">
        <div className="flex items-center gap-3 border-b border-gray-200 px-4 py-3">
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-2">
              <span className="truncate text-sm font-semibold text-gray-900">{file.name}</span>
              {dirty && (
                <span className="shrink-0 rounded-full bg-amber-100 px-1.5 py-0.5 text-[11px] text-amber-700">
                  未保存
                </span>
              )}
            </div>
            <div className="truncate text-xs text-gray-400">{file.path}</div>
          </div>
          <button
            onClick={close}
            aria-label="关闭"
            className="shrink-0 rounded-lg px-2 py-1 text-gray-400 hover:bg-gray-100 hover:text-gray-600"
          >
            ✕
          </button>
        </div>

        <div className="min-h-0 flex-1 overflow-hidden">
          <TextEditor
            key={editorKey}
            initialValue={editorText}
            fileName={file.name}
            onChange={setDraft}
            onSave={save}
          />
        </div>

        <div className="flex flex-wrap items-center gap-2 border-t border-gray-200 px-4 py-3">
          <span className="min-w-0 flex-1 text-xs">
            {error ? (
              <span className="text-red-500">保存失败：{error}</span>
            ) : notice ? (
              <span className="text-green-600">{notice}</span>
            ) : (
              <span className="text-gray-400">{draft.length} 字符 · 上限 2MB</span>
            )}
          </span>
          <button
            onClick={reload}
            disabled={saving}
            className="rounded-lg px-3 py-1.5 text-sm text-gray-600 hover:bg-gray-100 disabled:text-gray-300"
          >
            重新加载
          </button>
          <button
            onClick={close}
            disabled={saving}
            className="rounded-lg px-3 py-1.5 text-sm text-gray-600 hover:bg-gray-100 disabled:text-gray-300"
          >
            关闭
          </button>
          <button
            onClick={save}
            disabled={saving || !dirty}
            className="rounded-lg bg-blue-500 px-4 py-1.5 text-sm font-medium text-white hover:bg-blue-600 disabled:cursor-default disabled:bg-gray-300"
          >
            {saving ? '保存中...' : '保存'}
          </button>
        </div>
      </div>
    </div>
  )
}

function TextEditor({ initialValue, fileName, onChange, onSave }) {
  const hostRef = useRef(null)
  const onChangeRef = useRef(onChange)
  const onSaveRef = useRef(onSave)

  useEffect(() => {
    onChangeRef.current = onChange
    onSaveRef.current = onSave
  })

  useEffect(() => {
    if (!hostRef.current) return

    const extensions = [
      basicSetup,
      EditorView.lineWrapping,
      Prec.highest(
        keymap.of([
          {
            key: 'Mod-s',
            preventDefault: true,
            run: () => {
              onSaveRef.current?.()
              return true
            },
          },
        ]),
      ),
      EditorView.updateListener.of((update) => {
        if (update.docChanged) onChangeRef.current?.(update.state.doc.toString())
      }),
      EditorView.theme({
        '&': { height: '100%', fontSize: '13px', backgroundColor: '#ffffff' },
        '.cm-scroller': {
          fontFamily:
            'ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace',
          lineHeight: '1.6',
        },
        '.cm-content': { padding: '12px 0' },
        '.cm-gutters': { backgroundColor: '#fafafa', borderRight: '1px solid #eeeeee' },
        '&.cm-focused': { outline: 'none' },
      }),
    ]
    if (/\.ya?ml$/i.test(fileName)) extensions.push(yaml())

    const view = new EditorView({
      doc: initialValue,
      extensions,
      parent: hostRef.current,
    })
    view.focus()
    return () => view.destroy()
    // initialValue/fileName 只在对话框打开（或重新加载）时变化，由外层 key 控制重挂载
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return <div ref={hostRef} className="h-full w-full overflow-hidden" />
}
