import { useState, useEffect, useRef, useCallback } from 'react'
import { MessageSquare, Database, Send, User, Bot, Layers, CheckSquare, Loader2, LogOut, Shield, Users, Lock, BookOpen, FileText, X, ChevronLeft, ZoomIn, ZoomOut, Image as ImageIcon, Upload, Trash2, Clock, Search, RefreshCw, Brain, Edit, Settings, Download, Plus } from 'lucide-react'
import Markdown from 'react-markdown'
import clsx from 'clsx'
import { twMerge } from 'tailwind-merge'
import { Document, Page, pdfjs } from 'react-pdf'
import 'react-pdf/dist/Page/AnnotationLayer.css'
import 'react-pdf/dist/Page/TextLayer.css'

// Set PDF worker
pdfjs.GlobalWorkerOptions.workerSrc = `//unpkg.com/pdfjs-dist@${pdfjs.version}/build/pdf.worker.min.mjs`

function cn(...inputs) {
  return twMerge(clsx(inputs))
}

// --- API Helpers ---
const API_BASE = '/api'
const AUTH_SESSION_KEY = 'ai4kb_auth_session'

function loadAuthSession() {
  if (typeof window === 'undefined') {
    return null
  }
  try {
    const raw = window.localStorage.getItem(AUTH_SESSION_KEY)
    if (!raw) {
      return null
    }
    const parsed = JSON.parse(raw)
    if (!parsed?.token || !parsed?.user?.role || !parsed?.user?.username) {
      return null
    }
    return parsed
  } catch {
    return null
  }
}

function saveAuthSession(session) {
  if (typeof window === 'undefined') {
    return
  }
  window.localStorage.setItem(AUTH_SESSION_KEY, JSON.stringify(session))
}

function clearAuthSession() {
  if (typeof window === 'undefined') {
    return
  }
  window.localStorage.removeItem(AUTH_SESSION_KEY)
}

function isAdminLikeRole(role) {
  const normalizedRole = String(role || '').toLowerCase()
  return normalizedRole === 'admin' || normalizedRole === 'super_admin'
}

function isSuperAdminRole(role) {
  return String(role || '').toLowerCase() === 'super_admin'
}

function getAuthToken() {
  return loadAuthSession()?.token || ''
}

async function apiFetch(path, options = {}) {
  const headers = new Headers(options.headers || {})
  const token = getAuthToken()
  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }
  const res = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers
  })
  if (res.status === 401) {
    if (typeof window !== 'undefined') {
      window.dispatchEvent(new CustomEvent('ai4kb-auth-expired'))
    }
    throw new Error('登录已过期，请重新登录')
  }
  if (res.status === 403) {
    throw new Error('当前账号无权限执行该操作')
  }
  return res
}

async function loginByPassword(username, password) {
  const res = await fetch(`${API_BASE}/user/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password })
  })
  if (!res.ok) {
    let message = '登录失败'
    try {
      const payload = await res.json()
      if (payload?.message) {
        message = payload.message
      }
    } catch (error) {
      if (error) {
        message = '登录失败'
      }
    }
    throw new Error(message)
  }
  return res.json()
}

async function fetchDatasets() {
  const res = await apiFetch('/admin/datasets')
  if (!res.ok) throw new Error('Failed to fetch datasets')
  const json = await res.json()
  return json.data || []
}

async function createDataset(name) {
  const res = await apiFetch('/admin/datasets', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name })
  })
  if (!res.ok) throw new Error('Failed to create dataset')
  return res.json()
}

async function deleteDataset(id) {
  const res = await apiFetch(`/admin/datasets/${id}`, {
    method: 'DELETE'
  })
  if (!res.ok) throw new Error('Failed to delete dataset')
  return res.json()
}

async function deleteDatasets(ids) {
  const res = await apiFetch('/admin/datasets', {
    method: 'DELETE',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ids })
  })
  if (!res.ok) throw new Error('Failed to delete datasets')
  return res.json()
}

async function updateDataset(id, name, description, language, permission, parser_config) {
  const body = { name, description }
  if (language) body.language = language
  if (permission) body.permission = permission
  if (parser_config) body.parser_config = parser_config

  const res = await apiFetch(`/admin/datasets/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  })
  if (!res.ok) throw new Error('Failed to update dataset')
  return res.json()
}

async function updateDocument(datasetId, docId, name) {
  const res = await apiFetch(`/admin/datasets/${datasetId}/documents/${docId}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name })
  })
  if (!res.ok) throw new Error('Failed to update document')
  return res.json()
}

async function fetchUsers() {
  const res = await apiFetch('/admin/users')
  if (!res.ok) throw new Error('Failed to fetch users')
  return res.json()
}

async function fetchUserPermissions(username) {
  const res = await apiFetch(`/admin/permission/${username}`)
  if (!res.ok) throw new Error('Failed to fetch permissions')
  return res.json()
}

async function fetchRouteSamples({ limit = 100, userId, source } = {}) {
  const params = new URLSearchParams()
  params.set('limit', String(limit))
  if (userId !== undefined && userId !== null && String(userId).trim()) {
    params.set('userId', String(userId).trim())
  }
  if (source && String(source).trim()) {
    params.set('source', String(source).trim())
  }
  const res = await apiFetch(`/admin/route-samples?${params.toString()}`)
  if (!res.ok) throw new Error('Failed to fetch route samples')
  const data = await res.json()
  return Array.isArray(data) ? data : []
}

async function fetchRouteSampleSources() {
  const res = await apiFetch('/admin/route-samples/sources')
  if (!res.ok) throw new Error('Failed to fetch route sample sources')
  const data = await res.json()
  return Array.isArray(data) ? data : []
}

async function fetchSuperAdminOverview() {
  const res = await apiFetch('/admin/super/ownership-overview')
  if (!res.ok) throw new Error('Failed to fetch super admin overview')
  const data = await res.json()
  return data || {}
}

async function fetchAdminSkills(onlineOnly = false) {
  const res = await apiFetch(`/admin/skills?onlineOnly=${onlineOnly ? 'true' : 'false'}`)
  if (!res.ok) throw new Error('加载技能列表失败')
  const data = await res.json()
  return Array.isArray(data) ? data : []
}

async function registerAdminSkill(payload) {
  const res = await apiFetch('/admin/skills/register', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload || {})
  })
  if (!res.ok) throw new Error('新增技能失败')
  return res.json()
}

async function deleteAdminSkill(toolCode) {
  const res = await apiFetch(`/admin/skills/${encodeURIComponent(toolCode)}`, {
    method: 'DELETE'
  })
  if (!res.ok) throw new Error('删除技能失败')
  return res.json()
}

async function onlineSkill(toolCode) {
  const res = await apiFetch(`/admin/skills/${encodeURIComponent(toolCode)}/online`, {
    method: 'POST'
  })
  if (!res.ok) throw new Error('上线技能失败')
  return res.json()
}

async function fetchSkillAudit(limit = 100) {
  const n = Number.parseInt(String(limit), 10)
  const safeLimit = Number.isNaN(n) ? 100 : Math.max(1, Math.min(n, 500))
  const res = await apiFetch(`/admin/skills/audit?limit=${safeLimit}`)
  if (!res.ok) throw new Error('加载技能审计失败')
  const data = await res.json()
  return Array.isArray(data) ? data : []
}

async function offlineSkill(toolCode) {
  const res = await apiFetch(`/admin/skills/${encodeURIComponent(toolCode)}/offline`, {
    method: 'POST'
  })
  if (!res.ok) throw new Error('下线技能失败')
  return res.json()
}

async function fetchToolCatalog() {
  const res = await apiFetch('/v1/agent/tool/catalog')
  if (!res.ok) throw new Error('加载技能目录失败')
  const data = await res.json()
  return Array.isArray(data) ? data : []
}

async function createToolDraft(conversationId, toolCode, query = '') {
  const res = await apiFetch('/v1/agent/tool/draft', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ conversationId, toolCode, query })
  })
  if (!res.ok) throw new Error('创建技能草稿失败')
  return res.json()
}

async function createConversation(title = '') {
  const res = await apiFetch('/user/conversations', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title })
  })
  if (!res.ok) throw new Error('创建会话失败')
  return res.json()
}

async function fetchConversations() {
  const res = await apiFetch('/user/conversations')
  if (!res.ok) throw new Error('加载会话列表失败')
  const data = await res.json()
  return Array.isArray(data) ? data : []
}

async function renameConversation(conversationId, title) {
  const res = await apiFetch(`/user/conversations/${encodeURIComponent(conversationId)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title })
  })
  if (!res.ok) throw new Error('重命名会话失败')
  return res.json()
}

async function deleteConversation(conversationId) {
  const res = await apiFetch(`/user/conversations/${encodeURIComponent(conversationId)}`, {
    method: 'DELETE'
  })
  if (!res.ok) throw new Error('删除会话失败')
  return res.json()
}

async function fetchConversationMessages(conversationId) {
  const res = await apiFetch(`/user/conversations/${encodeURIComponent(conversationId)}/messages`)
  if (!res.ok) throw new Error('加载会话消息失败')
  const data = await res.json()
  return Array.isArray(data) ? data : []
}

async function saveConversationMessage(conversationId, role, content, conversationTitle = '', messagePayload = '') {
  const res = await apiFetch(`/user/conversations/${encodeURIComponent(conversationId)}/messages`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      role,
      content,
      conversationTitle,
      messagePayload
    })
  })
  if (!res.ok) throw new Error('保存会话消息失败')
  return res.json()
}

async function syncPermissions(username, datasetIds) {
  const res = await apiFetch('/admin/permission/sync', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      username,
      dataset_ids: datasetIds
    })
  })
  if (!res.ok) throw new Error('Failed to sync permissions')
  return res.json()
}

async function fetchDocuments(datasetId, page = 1, pageSize = 100) {
  const res = await apiFetch(`/admin/datasets/${datasetId}/documents?page=${page}&page_size=${pageSize}&t=${Date.now()}`)
  if (!res.ok) throw new Error('Failed to fetch documents')
  const json = await res.json()
  // Handle both array and object response (RAGFlow returns { data: { docs: [...] } })
  if (json.data && Array.isArray(json.data.docs)) {
    return json.data.docs
  }
  return Array.isArray(json.data) ? json.data : []
}

async function uploadDocument(datasetId, file) {
  const formData = new FormData()
  formData.append('file', file)
  
  const res = await apiFetch(`/admin/datasets/${datasetId}/documents`, {
    method: 'POST',
    body: formData
  })
  if (!res.ok) throw new Error('Failed to upload document')
  return res.json()
}

async function deleteDocuments(datasetId, ids) {
  const res = await apiFetch(`/admin/datasets/${datasetId}/documents`, {
    method: 'DELETE',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ids })
  })
  if (!res.ok) throw new Error('Failed to delete documents')
  return res.json()
}

async function runDocuments(datasetId, docIds) {
  const res = await apiFetch(`/admin/datasets/${datasetId}/documents/run`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ doc_ids: docIds })
  })
  if (!res.ok) throw new Error('Failed to run documents')
  return res.json()
}

async function getDocumentFile(datasetId, docId) {
  const res = await apiFetch(`/admin/datasets/${datasetId}/documents/${docId}/file`)
  if (!res.ok) throw new Error('Failed to fetch document file')
  return res.blob()
}

async function fetchChunks(datasetId, docId, page = 1, pageSize = 10000) {
  const res = await apiFetch(`/admin/datasets/${datasetId}/documents/${docId}/chunks?page=${page}&page_size=${pageSize}`)
  if (!res.ok) throw new Error('Failed to fetch chunks')
  const json = await res.json()
  return json.data || []
}

async function startAgentStream(conversationId, query, options = {}) {
  const payload = {
    conversationId,
    query,
    adjustmentInstruction: options.adjustmentInstruction || '',
    editedSteps: Array.isArray(options.editedSteps) ? options.editedSteps : [],
    rerunMode: options.rerunMode || 'AUTO',
    restartFromStep: Number.isFinite(Number(options.restartFromStep)) ? Number(options.restartFromStep) : 1,
    replanOnly: !!options.replanOnly
  }
  const res = await apiFetch('/v1/agent/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res
}

async function uploadToolInputFile(toolCallId, file) {
  const formData = new FormData()
  formData.append('file', file)
  const res = await apiFetch(`/v1/agent/tool/upload?toolCallId=${encodeURIComponent(toolCallId)}`, {
    method: 'POST',
    body: formData
  })
  if (!res.ok) throw new Error(`上传失败: HTTP ${res.status}`)
  return res.json()
}

async function approveToolCall(conversationId, toolCallId, reviewedArgs) {
  const res = await apiFetch('/v1/agent/tool/approve', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ conversationId, toolCallId, reviewedArgs })
  })
  if (!res.ok) throw new Error(`审批失败: HTTP ${res.status}`)
  return res
}

// --- Components ---

function ChunkHighlights({ chunk, scale, pageNumber }) {
  if (!chunk || !chunk.positions || chunk.positions.length === 0) return null

  // Debug log
  console.log('Rendering highlights for chunk:', chunk.id, 'Page:', pageNumber, 'Scale:', scale)
  console.log('Positions:', chunk.positions)

  // RAGFlow positions format: [page_num, x_min, x_max, y_min, y_max]
  // We need to filter for current page
  const rects = chunk.positions
    .filter(pos => pos[0] === pageNumber)
    .map((pos, i) => {
      const [, x1, x2, y1, y2] = pos
      // Calculate width and height
      const width = (x2 - x1) * scale
      const height = (y2 - y1) * scale
      
      console.log(`Rect ${i}:`, { left: x1 * scale, top: y1 * scale, width, height })

      return (
        <div
          key={i}
          className="absolute bg-yellow-400/50 border-2 border-yellow-600 transition-all duration-300 z-[100]"
          style={{
            left: x1 * scale,
            top: y1 * scale,
            width: width,
            height: height,
          }}
        />
      )
    })

  if (rects.length === 0) {
      console.log('No rects for this page')
      return null
  }

  return <div className="absolute inset-0 pointer-events-none z-[100]">{rects}</div>
}

function DocumentViewer({ doc, datasetId, onClose }) {
  const [chunks, setChunks] = useState([])
  const [loadingChunks, setLoadingChunks] = useState(false)
  const [numPages, setNumPages] = useState(null)
  const [searchTerm, setSearchTerm] = useState('')
  const [scale, setScale] = useState(1.0)
  const [pdfError, setPdfError] = useState(null)
  const [activeChunk, setActiveChunk] = useState(null)

  useEffect(() => {
    // Check status using 'run' or 'run_status'
    // run: 'DONE', '0' (not run?), 'RUNNING'? 
    // run_status: '1' (parsed), '0' (not parsed)
    // We should treat '0' as not parsed.
    const isParsed = doc.run === 'DONE' || doc.run_status === '1'
    if (isParsed) {
      setLoadingChunks(true)
      fetchChunks(datasetId, doc.id)
        .then(data => {
            console.log('Fetched chunks data:', data);
            if (Array.isArray(data)) setChunks(data)
            else if (data && Array.isArray(data.chunks)) setChunks(data.chunks)
            else setChunks([])
        })
        .catch(error => {
            console.error('Fetch chunks error:', error);
            setChunks([]);
        })
        .finally(() => setLoadingChunks(false))
    }
  }, [datasetId, doc.id, doc.run, doc.run_status])

  const onDocumentLoadSuccess = ({ numPages }) => {
    setNumPages(numPages)
    setPdfError(null)
  }

  const onDocumentLoadError = (error) => {
    console.error('PDF Load Error:', error)
    setPdfError(error.message)
  }

  const filteredChunks = chunks.filter(c => {
    const content = c.content_with_weight || c.content || '';
    return content.toLowerCase().includes(searchTerm.toLowerCase());
  })

  const handleChunkClick = (chunk) => {
    setActiveChunk(chunk)
    // Scroll to page logic handled in useEffect
  }

  // Scroll to chunk page when activeChunk changes
  useEffect(() => {
    if (activeChunk) {
        let targetPage = 1;
        if (activeChunk.positions && activeChunk.positions.length > 0) {
            targetPage = activeChunk.positions[0][0];
        } else if (activeChunk.page_num && activeChunk.page_num.length > 0) {
            targetPage = activeChunk.page_num[0];
        }
        
        // Find page element and scroll
        setTimeout(() => {
            const pageEl = document.getElementById(`pdf-page-${targetPage}`);
            if (pageEl) {
                pageEl.scrollIntoView({ behavior: 'smooth', block: 'center' });
            }
        }, 100);
    }
  }, [activeChunk]);

  return (
    <div className="fixed inset-0 z-50 bg-black/90 flex items-center justify-center p-4">
      <div className="bg-white rounded-xl w-full h-full max-w-[95vw] flex flex-col overflow-hidden relative shadow-2xl">
         {/* Header */}
         <div className="p-4 border-b flex items-center justify-between bg-slate-50">
           <div className="flex items-center gap-3">
             <div className="p-2 bg-blue-100 rounded-lg">
                <FileText className="text-blue-600" size={20} />
             </div>
             <div>
                <h3 className="font-bold text-slate-800">{doc.name}</h3>
                <p className="text-xs text-slate-500">
                    {numPages ? `${numPages} 页` : '加载中...'} · {chunks.length} 个切片
                </p>
             </div>
           </div>
           <div className="flex items-center gap-2">
             <button onClick={() => setScale(s => Math.max(0.5, s - 0.1))} className="p-2 hover:bg-slate-200 rounded-lg"><ZoomOut size={18} /></button>
             <span className="text-sm font-mono w-12 text-center">{Math.round(scale * 100)}%</span>
             <button onClick={() => setScale(s => Math.min(2.5, s + 0.1))} className="p-2 hover:bg-slate-200 rounded-lg"><ZoomIn size={18} /></button>
             <div className="w-px h-6 bg-slate-300 mx-2" />
             <button 
               onClick={onClose}
               className="p-2 bg-slate-200 hover:bg-slate-300 rounded-full transition-colors"
             >
               <X size={20} />
             </button>
           </div>
         </div>

         {/* Body */}
         <div className="flex-1 flex overflow-hidden">
           {/* Left: PDF Viewer */}
           <div className="flex-1 bg-slate-100 overflow-auto flex justify-center p-8 relative scroll-smooth">
             {doc.type === 'pdf' ? (
                 <div className="relative w-full flex flex-col items-center">
                    <Document 
                        file={doc.url} 
                        className="flex flex-col items-center"
                        onLoadSuccess={onDocumentLoadSuccess}
                        onLoadError={onDocumentLoadError}
                        loading={<div className="flex items-center gap-2 p-4"><Loader2 className="animate-spin"/> 加载PDF中...</div>}
                    >
                        {Array.from(new Array(numPages), (el, index) => {
                            const currentPage = index + 1;
                            return (
                                <div key={`page_${currentPage}`} id={`pdf-page-${currentPage}`} className="relative inline-block border border-slate-200 shadow-lg mb-6">
                                    <Page 
                                        pageNumber={currentPage} 
                                        scale={scale} 
                                        renderTextLayer={true} 
                                        renderAnnotationLayer={true}
                                        className="bg-white"
                                    />
                                    <ChunkHighlights 
                                        chunk={activeChunk} 
                                        scale={scale} 
                                        pageNumber={currentPage} 
                                    />
                                </div>
                            );
                        })}
                    </Document>
                    {pdfError && <div className="text-red-500 p-4 bg-white rounded shadow">无法加载PDF: {pdfError}</div>}
                    
                    {/* Debug Info */}
                    {activeChunk && (
                        <div className="absolute bottom-0 right-0 p-2 bg-black/70 text-white text-xs z-50 pointer-events-none">
                            Chunk: {activeChunk.id} <br/>
                            Positions: {activeChunk.positions ? activeChunk.positions.length : '0'}
                        </div>
                    )}
                 </div>
             ) : (
                 <iframe src={doc.url} className="w-full h-full bg-white rounded-lg border p-4 font-mono whitespace-pre-wrap" />
             )}
           </div>

           {/* Right: Chunks & Search */}
           <div className="w-96 bg-white border-l flex flex-col shrink-0">
             <div className="p-4 border-b">
                <div className="relative">
                    <Search className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" size={16} />
                    <input 
                        type="text" 
                        placeholder="搜索切片内容..." 
                        className="w-full pl-9 pr-4 py-2 bg-slate-50 border rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 text-sm"
                        value={searchTerm}
                        onChange={e => setSearchTerm(e.target.value)}
                    />
                </div>
             </div>
             
             <div className="flex-1 overflow-y-auto p-4 space-y-3 bg-slate-50">
                {loadingChunks ? (
                    <div className="text-center py-8 text-slate-500 flex flex-col items-center gap-2">
                        <Loader2 className="animate-spin" />
                        <span className="text-xs">加载切片中...</span>
                    </div>
                ) : filteredChunks.length === 0 ? (
                    <div className="text-center py-8 text-slate-500 text-sm">
                        {searchTerm ? '未找到匹配的切片' : '暂无切片数据'}
                    </div>
                ) : (
                    filteredChunks.map((chunk, idx) => (
                        <div 
                            key={chunk.id || idx}
                            onClick={() => handleChunkClick(chunk)}
                            className={`bg-white p-3 rounded-lg border hover:border-blue-400 hover:shadow-md cursor-pointer transition-all group ${activeChunk && activeChunk.id === chunk.id ? 'border-blue-500 ring-2 ring-blue-200' : ''}`}
                        >
                            <div className="flex justify-between items-center mb-2">
                                <span className="text-xs font-mono text-slate-400 bg-slate-100 px-2 py-0.5 rounded">
                                    Page {chunk.page_num && chunk.page_num[0]}
                                </span>
                                <span className="text-xs text-slate-300 group-hover:text-blue-400">
                                    #{idx + 1}
                                </span>
                            </div>
                            <p className="text-sm text-slate-700 line-clamp-4 leading-relaxed">
                                {chunk.content_with_weight ? (
                                   <span dangerouslySetInnerHTML={{ __html: chunk.content_with_weight }} />
                                ) : (
                                   chunk.content
                                )}
                            </p>
                        </div>
                    ))
                )}
             </div>
           </div>
         </div>
      </div>
    </div>
  )
}


function LoginScreen({ onLogin }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')

  const handleSubmit = async (event) => {
    event.preventDefault()
    if (!username.trim() || !password.trim() || submitting) {
      return
    }
    setSubmitting(true)
    setError('')
    try {
      await onLogin({
        username: username.trim(),
        password
      })
    } catch (loginError) {
      setError(loginError?.message || '登录失败，请检查账号密码')
      setSubmitting(false)
    }
  }

  const fillDemoAccount = (name) => {
    setUsername(name)
    setPassword('ChangeMe123!')
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-slate-100">
      <div className="w-full max-w-md p-8 bg-white rounded-xl shadow-lg">
        <div className="flex justify-center mb-6">
          <div className="p-3 bg-blue-100 rounded-full">
            <Layers className="w-8 h-8 text-blue-600" />
          </div>
        </div>
        <h2 className="text-2xl font-bold text-center text-slate-800 mb-2">AI4KB 知识库系统</h2>
        <p className="text-center text-slate-500 mb-6">请输入账号密码登录</p>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="block text-sm font-medium text-slate-700 mb-2">用户名</label>
            <input
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              className="w-full px-4 py-2 border rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 bg-slate-50 focus:bg-white transition-colors"
              placeholder="请输入用户名"
              autoComplete="username"
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-slate-700 mb-2">密码</label>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="w-full px-4 py-2 border rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 bg-slate-50 focus:bg-white transition-colors"
              placeholder="请输入密码"
              autoComplete="current-password"
            />
          </div>
          {error && (
            <div className="text-sm text-red-600 bg-red-50 border border-red-100 rounded-lg px-3 py-2">
              {error}
            </div>
          )}
          <button
            type="submit"
            disabled={submitting || !username.trim() || !password.trim()}
            className="w-full flex items-center justify-center gap-2 px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          >
            {submitting ? <Loader2 className="animate-spin" size={16} /> : <Shield size={16} />}
            登录
          </button>
        </form>
        <div className="mt-5 border-t pt-4">
          <p className="text-xs text-slate-500 mb-2">快捷填充测试账号（默认密码 ChangeMe123!）</p>
          <div className="grid grid-cols-3 gap-2">
            <button onClick={() => fillDemoAccount('superadmin')} className="text-xs px-2 py-1.5 rounded border border-slate-200 hover:bg-slate-100 transition-colors">superadmin</button>
            <button onClick={() => fillDemoAccount('admin')} className="text-xs px-2 py-1.5 rounded border border-slate-200 hover:bg-slate-100 transition-colors">admin</button>
            <button onClick={() => fillDemoAccount('zhangsan')} className="text-xs px-2 py-1.5 rounded border border-slate-200 hover:bg-slate-100 transition-colors">zhangsan</button>
          </div>
        </div>
      </div>
    </div>
  )
}

function Sidebar({ role, username, activeTab, setActiveTab, onLogout }) {
  const menuItems = isSuperAdminRole(role) ? [
    { id: 'super_overview', label: '管理员总览', icon: Users },
    { id: 'datasets', label: '知识库管理', icon: Database },
    { id: 'permissions', label: '权限分配', icon: Lock },
    { id: 'skills', label: '技能管理', icon: Settings },
    { id: 'route_samples', label: '审计查询', icon: Brain },
    { id: 'chat', label: '调试对话', icon: MessageSquare },
  ] : (isAdminLikeRole(role) ? [
    { id: 'datasets', label: '知识库管理', icon: Database },
    { id: 'permissions', label: '权限分配', icon: Lock },
    { id: 'skills', label: '技能管理', icon: Settings },
    { id: 'chat', label: '调试对话', icon: MessageSquare },
  ] : [
    { id: 'chat', label: '智能问答', icon: MessageSquare },
  ])

  return (
    <div className="w-64 bg-slate-900 text-white flex flex-col h-screen shrink-0">
      <div className="p-6 border-b border-slate-800">
        <h1 className="text-xl font-bold flex items-center gap-2">
          <Layers className="text-blue-400" />
          AI4KB
        </h1>
        <p className="text-xs text-slate-500 mt-1">Local Knowledge Base</p>
      </div>
      
      <nav className="flex-1 p-4 space-y-2">
        {menuItems.map((item) => (
          <button
            key={item.id}
            onClick={() => setActiveTab(item.id)}
            className={cn(
              "w-full flex items-center gap-3 px-4 py-3 rounded-lg transition-colors",
              activeTab === item.id 
                ? "bg-blue-600 text-white" 
                : "text-slate-400 hover:bg-slate-800 hover:text-white"
            )}
          >
            <item.icon size={20} />
            <span>{item.label}</span>
          </button>
        ))}
      </nav>

      <div className="p-4 border-t border-slate-800">
        <div className="flex items-center gap-3 px-4 py-2 mb-4">
          <div className={cn(
            "w-8 h-8 rounded-full flex items-center justify-center text-xs font-bold",
            isAdminLikeRole(role) ? "bg-purple-500" : "bg-emerald-500"
          )}>
            {isAdminLikeRole(role) ? 'AD' : 'US'}
          </div>
          <div className="overflow-hidden">
            <p className="text-sm font-medium truncate">{username}</p>
            <p className="text-xs text-slate-500 uppercase">{role}</p>
          </div>
        </div>
        <button 
          onClick={onLogout}
          className="w-full flex items-center gap-2 text-slate-400 hover:text-white px-4 py-2 text-sm transition-colors"
        >
          <LogOut size={16} />
          退出登录
        </button>
      </div>
    </div>
  )
}

function RenameModal({ isOpen, onClose, onConfirm, initialValue, initialDescription, title, isSubmitting }) {
  const [value, setValue] = useState(initialValue)
  const [description, setDescription] = useState(initialDescription || '')

  useEffect(() => {
    setValue(initialValue)
    setDescription(initialDescription || '')
  }, [initialValue, initialDescription])

  if (!isOpen) return null

  return (
    <div className="fixed inset-0 z-[100] bg-black/50 flex items-center justify-center p-4 backdrop-blur-sm animate-in fade-in duration-200">
      <div className="bg-white rounded-xl shadow-2xl w-full max-w-md overflow-hidden animate-in zoom-in-95 duration-200">
        <div className="p-4 border-b flex items-center justify-between bg-slate-50">
          <h3 className="font-bold text-slate-800 flex items-center gap-2">
            <Edit size={18} className="text-blue-500" />
            {title}
          </h3>
          <button onClick={onClose} className="p-1 hover:bg-slate-200 rounded-full transition-colors">
            <X size={20} className="text-slate-500" />
          </button>
        </div>
        <div className="p-6 space-y-4">
          <div>
            <label className="block text-sm font-medium text-slate-700 mb-2">名称</label>
            <input
              type="text"
              value={value}
              onChange={(e) => setValue(e.target.value)}
              className="w-full px-4 py-2 border rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 bg-slate-50 focus:bg-white transition-colors"
              autoFocus
              onKeyDown={(e) => {
                if (e.key === 'Enter' && value.trim() && initialDescription === undefined) {
                  onConfirm(value)
                }
              }}
            />
          </div>
          
          {initialDescription !== undefined && (
            <div>
              <label className="block text-sm font-medium text-slate-700 mb-2">描述 (可选)</label>
              <textarea
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                className="w-full px-4 py-2 border rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 bg-slate-50 focus:bg-white transition-colors min-h-[100px] resize-none"
                placeholder="请输入知识库描述..."
              />
            </div>
          )}
        </div>
        <div className="p-4 border-t bg-slate-50 flex justify-end gap-2">
          <button
            onClick={onClose}
            className="px-4 py-2 text-slate-600 hover:bg-slate-200 rounded-lg transition-colors font-medium text-sm"
          >
            取消
          </button>
          <button
            onClick={() => onConfirm(value, description)}
            disabled={!value.trim() || isSubmitting}
            className="px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2 font-medium text-sm transition-colors"
          >
            {isSubmitting && <Loader2 className="animate-spin" size={16} />}
            确定
          </button>
        </div>
      </div>
    </div>
  )
}

function SettingsModal({ isOpen, dataset, isSubmitting, onClose, onConfirm }) {
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [language, setLanguage] = useState('English')
  const [permission, setPermission] = useState('me')
  const [layoutRecognize, setLayoutRecognize] = useState('DeepDOC')
  const [chunkTokenNum, setChunkTokenNum] = useState(128)
  const [useRaptor, setUseRaptor] = useState(false)
  const [raptorPrompt, setRaptorPrompt] = useState('')
  const [autoKeywords, setAutoKeywords] = useState(0)
  const [autoQuestions, setAutoQuestions] = useState(0)
  const [pagerank, setPagerank] = useState(0)

  // Default Chinese prompt for RAPTOR
  const DEFAULT_RAPTOR_PROMPT = "请总结以下段落。注意数字，不要编造。段落如下：\n      {cluster_content}\n以上是你需要总结的内容。"

  useEffect(() => {
    if (isOpen && dataset) {
      setName(dataset.name || '')
      setDescription(dataset.description || '')
      setLanguage(dataset.language || 'Chinese')
      setPermission(dataset.permission || 'me')
      
      const config = dataset.parser_config || {}
      setLayoutRecognize(config.layout_recognize || 'DeepDOC')
      setChunkTokenNum(config.chunk_token_num || 128)
      setAutoKeywords(config.auto_keywords || 0)
      setAutoQuestions(config.auto_questions || 0)
      setPagerank(dataset.pagerank || 0)

      const raptor = config.raptor || {}
      setUseRaptor(raptor.use_raptor || false)
      setRaptorPrompt(raptor.prompt || DEFAULT_RAPTOR_PROMPT)
    }
  }, [isOpen, dataset])

  if (!isOpen) return null

  const handleConfirm = () => {
    const parser_config = {
      ...dataset.parser_config,
      chunk_token_num: parseInt(chunkTokenNum),
      layout_recognize: layoutRecognize,
      auto_keywords: parseInt(autoKeywords),
      auto_questions: parseInt(autoQuestions),
      raptor: {
        ...dataset.parser_config?.raptor,
        use_raptor: useRaptor,
        prompt: raptorPrompt
      }
    }
    onConfirm({ name, description, language, permission, pagerank, parser_config })
  }

  return (
    <div className="fixed inset-0 z-50 bg-black/50 flex items-center justify-center p-4">
      <div className="bg-white rounded-xl w-full max-w-2xl max-h-[90vh] flex flex-col shadow-2xl overflow-hidden">
        <div className="p-4 border-b flex items-center justify-between">
          <h3 className="font-bold text-lg text-slate-800">知识库设置</h3>
          <button onClick={onClose} className="p-1 hover:bg-slate-200 rounded-full transition-colors">
            <X size={20} className="text-slate-500" />
          </button>
        </div>
        
        <div className="p-6 overflow-y-auto space-y-6">
          {/* Basic Info */}
          <div className="space-y-4">
            <h4 className="font-semibold text-slate-900 border-b pb-2">基本信息</h4>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1">名称</label>
                <input
                  type="text"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  className="w-full px-3 py-2 border rounded-lg focus:ring-2 focus:ring-blue-500 bg-slate-50"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1">语言</label>
                <select 
                  value={language}
                  onChange={(e) => setLanguage(e.target.value)}
                  className="w-full px-3 py-2 border rounded-lg focus:ring-2 focus:ring-blue-500 bg-slate-50"
                >
                  <option value="Chinese">Chinese</option>
                  <option value="English">English</option>
                </select>
              </div>
            </div>
            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1">描述</label>
              <textarea
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                className="w-full px-3 py-2 border rounded-lg focus:ring-2 focus:ring-blue-500 bg-slate-50 h-20 resize-none"
              />
            </div>
            <div>
               <label className="block text-sm font-medium text-slate-700 mb-1">权限</label>
               <select 
                  value={permission}
                  onChange={(e) => setPermission(e.target.value)}
                  className="w-full px-3 py-2 border rounded-lg focus:ring-2 focus:ring-blue-500 bg-slate-50"
                >
                  <option value="me">仅自己 (Me)</option>
                  <option value="team">团队 (Team)</option>
                </select>
            </div>
          </div>

          {/* Parser Config */}
          <div className="space-y-4">
            <h4 className="font-semibold text-slate-900 border-b pb-2">解析配置</h4>
            <div className="grid grid-cols-2 gap-4">
               <div>
                <label className="block text-sm font-medium text-slate-700 mb-1">Layout Recognize</label>
                <select 
                  value={layoutRecognize}
                  onChange={(e) => setLayoutRecognize(e.target.value)}
                  className="w-full px-3 py-2 border rounded-lg focus:ring-2 focus:ring-blue-500 bg-slate-50"
                >
                  <option value="DeepDOC">DeepDOC</option>
                  <option value="Naive">Naive</option>
                </select>
              </div>
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1">Chunk Token Number</label>
                <input
                  type="number"
                  value={chunkTokenNum}
                  onChange={(e) => setChunkTokenNum(e.target.value)}
                  className="w-full px-3 py-2 border rounded-lg focus:ring-2 focus:ring-blue-500 bg-slate-50"
                />
              </div>
            </div>
            
            <div className="flex items-center gap-2">
                <input 
                  type="checkbox" 
                  id="useRaptor"
                  checked={useRaptor} 
                  onChange={(e) => setUseRaptor(e.target.checked)}
                  className="w-4 h-4 text-blue-600 rounded focus:ring-blue-500"
                />
                <label htmlFor="useRaptor" className="text-sm font-medium text-slate-700">启用 RAPTOR (递归摘要)</label>
            </div>
            
            {useRaptor && (
                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-1">RAPTOR Prompt</label>
                  <textarea
                    value={raptorPrompt}
                    onChange={(e) => setRaptorPrompt(e.target.value)}
                    className="w-full px-3 py-2 border rounded-lg focus:ring-2 focus:ring-blue-500 bg-slate-50 h-32 resize-none text-xs font-mono"
                  />
                  <p className="text-xs text-slate-500 mt-1">请保持 `{'{cluster_content}'}` 占位符。</p>
                </div>
            )}
          </div>
        </div>

        <div className="p-4 border-t bg-slate-50 flex justify-end gap-2">
          <button
            onClick={onClose}
            className="px-4 py-2 text-slate-600 hover:bg-slate-200 rounded-lg transition-colors font-medium text-sm"
          >
            取消
          </button>
          <button
            onClick={handleConfirm}
            disabled={isSubmitting}
            className="px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50 flex items-center gap-2 font-medium text-sm transition-colors"
          >
            {isSubmitting && <Loader2 className="animate-spin" size={16} />}
            保存配置
          </button>
        </div>
      </div>
    </div>
  )
}

function DatasetDetail({ dataset, onBack, onUpdate }) {
  const [docs, setDocs] = useState([])
  const [loading, setLoading] = useState(true)
  const [uploading, setUploading] = useState(false)
  const [deletingId, setDeletingId] = useState(null)
  const [parsingId, setParsingId] = useState(null)
  const [viewingDoc, setViewingDoc] = useState(null)
  const [selectedDocs, setSelectedDocs] = useState([])
  const [batchDeleting, setBatchDeleting] = useState(false)
  const [renameModal, setRenameModal] = useState({
    isOpen: false,
    doc: null,
    initialValue: '',
    isSubmitting: false
  })
  const [settingsModal, setSettingsModal] = useState({
    isOpen: false,
    isSubmitting: false
  })

  const loadDocs = useCallback(() => {
    // Only set loading on initial load to avoid flickering during polling
    if (docs.length === 0) setLoading(true)
    setSelectedDocs([]) // Reset selection on reload
    fetchDocuments(dataset.id)
      .then(data => setDocs(Array.isArray(data) ? data : []))
      .catch(console.error)
      .finally(() => setLoading(false))
  }, [dataset.id, docs.length])

  useEffect(() => {
    loadDocs()
    // Poll for status updates if there are parsing documents
      const interval = setInterval(() => {
          // Simple check: if any doc is not parsed (run != 'DONE'), poll. 
          // Or if we just triggered parsing.
          // For now, poll every 5s to keep UI fresh
          fetchDocuments(dataset.id).then(data => {
              if (Array.isArray(data)) setDocs(data)
          }).catch(console.error)
      }, 5000)
    return () => clearInterval(interval)
  }, [dataset.id, loadDocs])

  const handleUpdateSettings = async (newSettings) => {
    setSettingsModal(prev => ({ ...prev, isSubmitting: true }))
    try {
        await updateDataset(
            dataset.id, 
            newSettings.name, 
            newSettings.description, 
            newSettings.language, 
            newSettings.permission, 
            newSettings.parser_config
        )
        if (onUpdate) onUpdate() // Refresh parent list
        alert('配置已更新')
        setSettingsModal({ isOpen: false, isSubmitting: false })
    } catch (e) {
        alert('更新失败: ' + e.message)
        setSettingsModal(prev => ({ ...prev, isSubmitting: false }))
    }
  }

  const handleFileUpload = async (e) => {
    const file = e.target.files[0]
    if (!file) return
    setUploading(true)
    try {
      await uploadDocument(dataset.id, file)
      loadDocs()
    } catch (e) {
      alert('上传失败: ' + e.message)
    } finally {
      setUploading(false)
      e.target.value = null
    }
  }
  
  const handleDeleteDoc = async (docId) => {
    if (!window.confirm('确定删除此文件吗？')) return
    setDeletingId(docId)
    try {
      await deleteDocuments(dataset.id, [docId])
      loadDocs()
    } catch (e) {
      alert('删除失败: ' + e.message)
    } finally {
      setDeletingId(null)
    }
  }

  const handleParseDoc = async (docId) => {
    setParsingId(docId)
    try {
      await runDocuments(dataset.id, [docId])
      // Trigger immediate reload
      loadDocs()
    } catch (e) {
      alert('解析失败: ' + e.message)
    } finally {
      // Keep parsingId set for a moment or until status changes?
      // Actually we should rely on doc.run_status or doc.progress from now on.
      setParsingId(null)
    }
  }

  const handleBatchDeleteDocs = async () => {
    if (selectedDocs.length === 0) return
    if (!window.confirm(`确定删除选中的 ${selectedDocs.length} 个文件吗？`)) return
    
    setBatchDeleting(true)
    try {
      await deleteDocuments(dataset.id, selectedDocs)
      loadDocs()
    } catch (e) {
      alert('批量删除失败: ' + e.message)
    } finally {
      setBatchDeleting(false)
    }
  }

  const handleSelectAllDocs = (e) => {
    if (e.target.checked) {
      setSelectedDocs(docs.map(d => d.id))
    } else {
      setSelectedDocs([])
    }
  }

  const handleSelectDoc = (docId) => {
    setSelectedDocs(prev => 
      prev.includes(docId) 
        ? prev.filter(id => id !== docId)
        : [...prev, docId]
    )
  }

  const handleRenameDoc = (doc) => {
    setRenameModal({
      isOpen: true,
      doc,
      initialValue: doc.name,
      isSubmitting: false
    })
  }

  const handleConfirmRename = async (newName) => {
    if (!newName || newName === renameModal.initialValue) {
      setRenameModal(prev => ({ ...prev, isOpen: false }))
      return
    }

    setRenameModal(prev => ({ ...prev, isSubmitting: true }))
    try {
      await updateDocument(dataset.id, renameModal.doc.id, newName)
      loadDocs()
      setRenameModal(prev => ({ ...prev, isOpen: false }))
    } catch (e) {
      alert('重命名失败: ' + e.message)
      setRenameModal(prev => ({ ...prev, isSubmitting: false }))
    }
  }

  const handleViewDoc = async (doc) => {
    try {
      const blob = await getDocumentFile(dataset.id, doc.id)
      const url = URL.createObjectURL(blob)
      setViewingDoc({ 
          ...doc, 
          url, 
          type: doc.name.toLowerCase().endsWith('.pdf') ? 'pdf' : 'text' 
      })
    } catch (e) {
      alert('无法预览文件: ' + e.message)
    }
  }

  return (
    <div className="p-8 max-w-6xl mx-auto h-full overflow-y-auto relative">
      {viewingDoc && (
        <DocumentViewer 
            doc={viewingDoc} 
            datasetId={dataset.id} 
            onClose={() => {
                URL.revokeObjectURL(viewingDoc.url)
                setViewingDoc(null)
            }} 
        />
      )}

      <button onClick={onBack} className="mb-4 text-slate-500 hover:text-slate-800 flex items-center gap-1 transition-colors">
        <ChevronLeft size={16} /> 返回知识库列表
      </button>
      
      <div className="flex justify-between items-center mb-8">
        <div>
          <h2 className="text-2xl font-bold text-slate-800 flex items-center gap-2">
            <Database className="text-blue-500" size={24} />
            {dataset.name}
            <button 
                onClick={() => setSettingsModal(prev => ({ ...prev, isOpen: true }))}
                className="ml-2 p-1.5 text-slate-400 hover:text-blue-600 hover:bg-blue-50 rounded-lg transition-colors"
                title="设置"
            >
                <Settings size={20} />
            </button>
          </h2>
          <p className="text-slate-500 text-sm mt-1 font-mono select-all">ID: {dataset.id}</p>
        </div>
        <div className="relative group flex gap-2">
          {selectedDocs.length > 0 && (
            <button 
              onClick={handleBatchDeleteDocs}
              disabled={batchDeleting}
              className="px-4 py-2 bg-red-50 text-red-600 border border-red-200 rounded-lg hover:bg-red-100 flex items-center gap-2 disabled:opacity-50 transition-colors"
            >
              {batchDeleting ? <Loader2 size={16} className="animate-spin" /> : <Trash2 size={16} />}
              批量删除 ({selectedDocs.length})
            </button>
          )}
          <div className="relative">
            <input 
              type="file" 
              onChange={handleFileUpload}
              className="absolute inset-0 w-full h-full opacity-0 cursor-pointer z-10"
              disabled={uploading}
            />
            <button className="px-6 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 flex items-center gap-2 disabled:opacity-50 transition-colors shadow-sm h-full">
              {uploading ? <Loader2 size={16} className="animate-spin" /> : <Upload size={16} />}
              上传文件
            </button>
          </div>
        </div>
      </div>
      
      <div className="bg-white rounded-xl border shadow-sm overflow-hidden">
        <table className="w-full text-sm text-left">
          <thead className="bg-slate-50 text-slate-500 font-medium border-b">
            <tr>
              <th className="px-6 py-4 w-12">
                <input 
                  type="checkbox" 
                  className="rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                  checked={docs.length > 0 && selectedDocs.length === docs.length}
                  onChange={handleSelectAllDocs}
                  disabled={docs.length === 0}
                />
              </th>
              <th className="px-6 py-4">文件名</th>
              <th className="px-6 py-4">上传时间</th>
              <th className="px-6 py-4">分块数</th>
              <th className="px-6 py-4">状态</th>
              <th className="px-6 py-4 text-right">操作</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {loading ? (
              <tr>
                <td colSpan={6} className="px-6 py-8 text-center text-slate-500">
                  <Loader2 className="w-6 h-6 animate-spin mx-auto mb-2" />
                  加载中...
                </td>
              </tr>
            ) : docs.length === 0 ? (
              <tr>
                <td colSpan={6} className="px-6 py-8 text-center text-slate-500">
                  暂无文档，请上传文件
                </td>
              </tr>
            ) : (
              docs.map((doc) => (
                <tr key={doc.id} className={`hover:bg-slate-50 transition-colors ${selectedDocs.includes(doc.id) ? 'bg-blue-50/50' : ''}`}>
                  <td className="px-6 py-4">
                    <input 
                      type="checkbox" 
                      className="rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                      checked={selectedDocs.includes(doc.id)}
                      onChange={() => handleSelectDoc(doc.id)}
                    />
                  </td>
                  <td className="px-6 py-4 font-medium text-slate-700 flex items-center gap-2">
                    <FileText size={16} className="text-slate-400" />
                    <button onClick={() => handleViewDoc(doc)} className="hover:text-blue-600 hover:underline text-left">
                      {doc.name}
                    </button>
                  </td>
                  <td className="px-6 py-4 text-slate-500">
                    {new Date(doc.create_time).toLocaleString()}
                  </td>
                  <td className="px-6 py-4 text-slate-500 font-mono">
                    {doc.chunk_count !== undefined ? doc.chunk_count : '-'}
                  </td>
                  <td className="px-6 py-4">
                     <div className="flex flex-col gap-1">
                         <span className={cn(
                           "px-2 py-1 rounded-full text-xs font-medium w-fit",
                           (doc.run === 'DONE' || doc.run_status === '1') ? "bg-emerald-100 text-emerald-700" : 
                           (doc.progress > 0 && doc.progress < 1) ? "bg-amber-100 text-amber-700" :
                           "bg-slate-100 text-slate-500"
                         )}>
                           {(doc.run === 'DONE' || doc.run_status === '1') ? '已解析' : 
                            (doc.progress > 0 && doc.progress < 1) ? `解析中 ${Math.round(doc.progress * 100)}%` : '未解析'}
                         </span>
                         {(doc.progress > 0 && doc.progress < 1) && (
                             <div className="w-20 h-1.5 bg-slate-100 rounded-full overflow-hidden">
                                <div 
                                    className="h-full bg-blue-500 animate-pulse" 
                                    style={{ width: `${(doc.progress || 0) * 100}%` }}
                                />
                             </div>
                         )}
                     </div>
                  </td>
                  <td className="px-6 py-4 text-right">
                    <div className="flex items-center justify-end gap-2">
                        <button 
                          onClick={() => handleParseDoc(doc.id)}
                          disabled={parsingId === doc.id || (doc.progress > 0 && doc.progress < 1)}
                          className="p-2 text-blue-600 hover:bg-blue-50 rounded-lg transition-colors disabled:opacity-50"
                          title="解析文档"
                        >
                          {parsingId === doc.id ? <Loader2 size={16} className="animate-spin" /> : <RefreshCw size={16} />}
                        </button>
                      <button 
                        onClick={() => handleRenameDoc(doc)}
                        className="p-2 text-slate-600 hover:bg-slate-100 rounded-lg transition-colors"
                        title="重命名"
                      >
                        <Edit size={16} />
                      </button>
                      <button 
                        onClick={() => handleDeleteDoc(doc.id)}
                        disabled={deletingId === doc.id}
                        className="p-2 text-red-600 hover:bg-red-50 rounded-lg transition-colors"
                        title="删除文档"
                      >
                        {deletingId === doc.id ? <Loader2 size={16} className="animate-spin" /> : <Trash2 size={16} />}
                      </button>
                    </div>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
      <RenameModal 
        isOpen={renameModal.isOpen}
        title="重命名文件"
        initialValue={renameModal.initialValue}
        isSubmitting={renameModal.isSubmitting}
        onClose={() => setRenameModal(prev => ({ ...prev, isOpen: false }))}
        onConfirm={handleConfirmRename}
      />
      <SettingsModal 
        isOpen={settingsModal.isOpen}
        dataset={dataset}
        isSubmitting={settingsModal.isSubmitting}
        onClose={() => setSettingsModal(prev => ({ ...prev, isOpen: false }))}
        onConfirm={handleUpdateSettings}
      />
    </div>
  )
}

function DatasetCard({ dataset, onClick, onDelete, onRename, selected, onSelect, selectionMode }) {
  return (
    <div 
      onClick={selectionMode ? (e) => onSelect(dataset.id, e) : onClick}
      className={`bg-white rounded-xl border p-6 hover:shadow-lg transition-all cursor-pointer group relative ${selected ? 'border-blue-500 ring-1 ring-blue-500 bg-blue-50/10' : 'border-slate-100 hover:border-blue-200'}`}
    >
      <div className="absolute top-4 right-4 z-10 flex gap-2">
        {!selectionMode && (
          <>
            <button 
              onClick={(e) => onRename(dataset, e)}
              className="w-5 h-5 rounded flex items-center justify-center text-slate-400 hover:text-blue-500 hover:bg-blue-50 transition-all"
              title="重命名"
            >
              <FileText size={12} />
            </button>
            <button 
              onClick={(e) => onDelete(dataset.id, e)}
              className="w-5 h-5 rounded flex items-center justify-center text-slate-400 hover:text-red-500 hover:bg-red-50 transition-all"
              title="删除知识库"
            >
              <Trash2 size={12} />
            </button>
          </>
        )}
        <div 
          onClick={(e) => onSelect(dataset.id, e)}
          className={`w-5 h-5 rounded border flex items-center justify-center transition-all ${selected ? 'bg-blue-500 border-blue-500 text-white' : 'bg-white border-slate-300 hover:border-blue-400'}`}
        >
          {selected && <CheckSquare size={14} />}
        </div>
      </div>

      <div className="flex items-start justify-between mb-4">
        <div className="p-3 bg-blue-50 rounded-lg group-hover:bg-blue-100 transition-colors">
          <Database className="w-6 h-6 text-blue-500" />
        </div>
      </div>
      
      <h3 className="font-bold text-slate-800 mb-1 group-hover:text-blue-600 transition-colors line-clamp-1 pr-14">{dataset.name}</h3>
      <p className="text-sm text-slate-400 mb-4 line-clamp-2">{dataset.description || '暂无描述'}</p>
      <div className="text-xs text-slate-500 mb-3">
        创建人：{dataset.creatorUsername || dataset.creator_username || dataset.creatorUserName || dataset.owner_username || dataset.ownerUsername || dataset.created_by || '未知'}
      </div>
      
      <div className="flex items-center justify-between text-xs text-slate-500 border-t pt-4">
        <span className="flex items-center gap-1">
          <FileText size={14} />
          全部文件: {dataset.document_count || 0}
        </span>
        <span className="flex items-center gap-1">
          <Clock size={14} />
          {new Date(dataset.create_time).toLocaleDateString()}
        </span>
      </div>
    </div>
  )
}

function DatasetManager() {
  const [datasets, setDatasets] = useState([])
  const [loading, setLoading] = useState(true)
  const [creating, setCreating] = useState(false)
  const [newDatasetName, setNewDatasetName] = useState('')
  const [viewingDataset, setViewingDataset] = useState(null)
  const [selectedDatasets, setSelectedDatasets] = useState([])
  const [batchDeleting, setBatchDeleting] = useState(false)
  const [renameModal, setRenameModal] = useState({
    isOpen: false,
    dataset: null,
    initialValue: '',
    initialDescription: '',
    isSubmitting: false
  })

  const loadData = () => {
    setLoading(true)
    setSelectedDatasets([]) // Reset selection
    fetchDatasets()
      .then(setDatasets)
      .catch(console.error)
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    loadData()
  }, [])

  const handleCreate = async () => {
    if (!newDatasetName.trim()) return
    setCreating(true)
    try {
      await createDataset(newDatasetName)
      setNewDatasetName('')
      loadData()
    } catch (e) {
      alert('创建失败: ' + e.message)
    } finally {
      setCreating(false)
    }
  }

  const handleDelete = async (id, e) => {
    e.stopPropagation()
    if (!window.confirm('确定要删除这个知识库吗？此操作不可恢复。')) return
    try {
      // Optimistic update to immediately remove from UI
      setDatasets(prev => prev.filter(d => d.id !== id))
      await deleteDataset(id)
      // Wait a bit before reloading to allow backend consistency
      setTimeout(() => loadData(), 500)
    } catch (e) {
      alert('删除失败: ' + e.message)
      loadData() // Revert if failed
    }
  }

  const handleRenameDataset = (dataset, e) => {
    e.stopPropagation()
    setRenameModal({
      isOpen: true,
      dataset,
      initialValue: dataset.name,
      initialDescription: dataset.description || '',
      isSubmitting: false
    })
  }

  const handleConfirmRename = async (newName, newDescription) => {
    if (!newName || (newName === renameModal.initialValue && newDescription === renameModal.initialDescription)) {
      setRenameModal(prev => ({ ...prev, isOpen: false }))
      return
    }

    setRenameModal(prev => ({ ...prev, isSubmitting: true }))
    try {
      await updateDataset(renameModal.dataset.id, newName, newDescription)
      loadData()
      setRenameModal(prev => ({ ...prev, isOpen: false }))
    } catch (e) {
      alert('修改失败: ' + e.message)
      setRenameModal(prev => ({ ...prev, isSubmitting: false }))
    }
  }

  const handleBatchDelete = async () => {
    if (selectedDatasets.length === 0) return
    if (!window.confirm(`确定要删除选中的 ${selectedDatasets.length} 个知识库吗？此操作不可恢复。`)) return
    
    setBatchDeleting(true)
    try {
      await deleteDatasets(selectedDatasets)
      // Optimistic update
      setDatasets(prev => prev.filter(d => !selectedDatasets.includes(d.id)))
      setSelectedDatasets([])
      // Wait a bit before reloading
      setTimeout(() => loadData(), 500)
    } catch (e) {
      alert('批量删除失败: ' + e.message)
      loadData()
    } finally {
      setBatchDeleting(false)
    }
  }

  const handleSelectDataset = (id, e) => {
    if (e) e.stopPropagation()
    setSelectedDatasets(prev => 
      prev.includes(id) 
        ? prev.filter(did => did !== id)
        : [...prev, id]
    )
  }

  const handleSelectAll = () => {
    if (selectedDatasets.length === datasets.length) {
      setSelectedDatasets([])
    } else {
      setSelectedDatasets(datasets.map(d => d.id))
    }
  }

  if (viewingDataset) {
    return <DatasetDetail dataset={viewingDataset} onBack={() => {
      setViewingDataset(null)
      loadData() // Reload list when coming back
    }} />
  }

  return (
    <div className="p-8 max-w-7xl mx-auto h-full overflow-y-auto">
      <div className="flex justify-between items-center mb-8">
        <div>
          <h2 className="text-2xl font-bold text-slate-800 flex items-center gap-2">
            <Database className="text-blue-500" size={24} />
            知识库管理
          </h2>
          <div className="flex items-center gap-3 mt-1">
            <p className="text-slate-500 text-sm">创建和管理您的本地知识库</p>
            {datasets.length > 0 && (
              <button 
                onClick={handleSelectAll}
                className="text-xs text-blue-600 hover:underline ml-2"
              >
                {selectedDatasets.length === datasets.length ? '取消全选' : '全选'}
              </button>
            )}
          </div>
        </div>
        <div className="flex gap-2">
          {selectedDatasets.length > 0 && (
            <button 
              onClick={handleBatchDelete}
              disabled={batchDeleting}
              className="px-4 py-2 bg-red-50 text-red-600 border border-red-200 rounded-lg hover:bg-red-100 flex items-center gap-2 disabled:opacity-50 transition-colors mr-2"
            >
              {batchDeleting ? <Loader2 size={16} className="animate-spin" /> : <Trash2 size={16} />}
              批量删除 ({selectedDatasets.length})
            </button>
          )}
          <input
            type="text"
            placeholder="新知识库名称"
            className="px-4 py-2 border rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500"
            value={newDatasetName}
            onChange={(e) => setNewDatasetName(e.target.value)}
          />
          <button
            onClick={handleCreate}
            disabled={creating || !newDatasetName.trim()}
            className="px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors flex items-center gap-2"
          >
            {creating ? <Loader2 size={16} className="animate-spin" /> : <Database size={16} />}
            新建
          </button>
        </div>
      </div>

      {loading ? (
        <div className="flex justify-center items-center h-64">
          <Loader2 className="w-8 h-8 animate-spin text-blue-500" />
        </div>
      ) : (
        <div className="grid gap-6 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
          {datasets.map((ds) => (
            <DatasetCard 
              key={ds.id} 
              dataset={ds} 
              onClick={() => setViewingDataset(ds)} 
              onDelete={handleDelete}
              onRename={handleRenameDataset}
              selected={selectedDatasets.includes(ds.id)}
              onSelect={handleSelectDataset}
              selectionMode={selectedDatasets.length > 0}
            />
          ))}
          {datasets.length === 0 && (
            <div className="col-span-full flex flex-col items-center justify-center py-16 text-slate-400 bg-slate-50 rounded-xl border border-dashed border-slate-200">
              <Database size={48} className="mb-4 text-slate-300" />
              <p>暂无知识库，请点击右上角新建</p>
            </div>
          )}
        </div>
      )}
      <RenameModal 
        isOpen={renameModal.isOpen}
        title="编辑知识库"
        initialValue={renameModal.initialValue}
        initialDescription={renameModal.initialDescription}
        isSubmitting={renameModal.isSubmitting}
        onClose={() => setRenameModal(prev => ({ ...prev, isOpen: false }))}
        onConfirm={handleConfirmRename}
      />
    </div>
  )
}

function PermissionManager() {
  const [datasets, setDatasets] = useState([])
  const [users, setUsers] = useState([])
  const [selectedUser, setSelectedUser] = useState(null)
  const [selectedDatasetIds, setSelectedDatasetIds] = useState([])
  const [loading, setLoading] = useState(false)
  const [processing, setProcessing] = useState(false)

  // Load datasets and users on mount
  useEffect(() => {
    fetchDatasets().then(data => setDatasets(Array.isArray(data) ? data : []))
    fetchUsers().then(data => {
      setUsers(Array.isArray(data) ? data : [])
      if (data.length > 0) setSelectedUser(data[0].username)
    })
  }, [])

  // Load permissions when selectedUser changes
  useEffect(() => {
    if (!selectedUser) return
    setLoading(true)
    fetchUserPermissions(selectedUser)
      .then(perms => {
        const ids = perms.map(p => p.resourceId)
        setSelectedDatasetIds(ids)
      })
      .catch(console.error)
      .finally(() => setLoading(false))
  }, [selectedUser])

  const handleCheckboxChange = (dsId) => {
    setSelectedDatasetIds(prev => 
      prev.includes(dsId) 
        ? prev.filter(id => id !== dsId)
        : [...prev, dsId]
    )
  }

  const handleSelectAll = () => {
    if (selectedDatasetIds.length === datasets.length) {
      setSelectedDatasetIds([])
    } else {
      setSelectedDatasetIds(datasets.map(ds => ds.id))
    }
  }

  const handleSave = async () => {
    if (!selectedUser) return
    setProcessing(true)
    try {
      await syncPermissions(selectedUser, selectedDatasetIds)
      alert('权限已保存')
    } catch (e) {
      alert('保存失败: ' + e.message)
    } finally {
      setProcessing(false)
    }
  }

  return (
    <div className="p-8 max-w-5xl mx-auto h-full overflow-y-auto">
      <h2 className="text-2xl font-bold text-slate-800 mb-6">权限分配</h2>
      
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-8">
        {/* User Selection */}
        <div className="bg-white p-6 rounded-xl border shadow-sm h-fit">
          <h3 className="text-lg font-semibold text-slate-800 mb-4 flex items-center gap-2">
            <Users className="text-blue-500" size={20} />
            选择用户
          </h3>
          <div className="space-y-2">
            {users.map(u => (
              <button
                key={u.username}
                onClick={() => setSelectedUser(u.username)}
                className={cn(
                  "w-full px-4 py-3 rounded-lg border text-left transition-all flex items-center justify-between",
                  selectedUser === u.username 
                    ? "bg-blue-50 border-blue-500 text-blue-700 shadow-sm" 
                    : "hover:bg-slate-50 border-slate-200 text-slate-600"
                )}
              >
                <span className="font-medium">{u.username}</span>
                {selectedUser === u.username && <CheckSquare size={18} />}
              </button>
            ))}
          </div>
        </div>

        {/* Dataset Selection */}
        <div className="lg:col-span-2 bg-white p-6 rounded-xl border shadow-sm flex flex-col h-[600px]">
          <div className="flex justify-between items-center mb-4 pb-4 border-b">
            <h3 className="text-lg font-semibold text-slate-800 flex items-center gap-2">
              <Database className="text-blue-500" size={20} />
              选择知识库
            </h3>
            <div className="flex gap-3">
              <button 
                onClick={handleSelectAll}
                className="text-sm text-blue-600 hover:underline"
              >
                {selectedDatasetIds.length === datasets.length ? '取消全选' : '全选'}
              </button>
              <span className="text-sm text-slate-400">
                已选: {selectedDatasetIds.length}
              </span>
            </div>
          </div>

          <div className="flex-1 overflow-y-auto space-y-2 pr-2">
            {loading ? (
              <div className="flex justify-center py-8"><Loader2 className="animate-spin text-slate-400" /></div>
            ) : datasets.map(ds => (
              <label 
                key={ds.id} 
                className={cn(
                  "flex items-center gap-3 p-3 rounded-lg border cursor-pointer transition-all",
                  selectedDatasetIds.includes(ds.id)
                    ? "bg-blue-50 border-blue-200"
                    : "hover:bg-slate-50 border-slate-100"
                )}
              >
                <div className={cn(
                  "w-5 h-5 rounded border flex items-center justify-center transition-colors",
                  selectedDatasetIds.includes(ds.id)
                    ? "bg-blue-500 border-blue-500 text-white"
                    : "bg-white border-slate-300"
                )}>
                  {selectedDatasetIds.includes(ds.id) && <CheckSquare size={14} />}
                </div>
                <input 
                  type="checkbox" 
                  className="hidden"
                  checked={selectedDatasetIds.includes(ds.id)}
                  onChange={() => handleCheckboxChange(ds.id)}
                />
                <div className="flex-1 min-w-0">
                  <div className="font-medium text-slate-700 truncate">{ds.name}</div>
                  <div className="text-xs text-slate-400 font-mono">{ds.id.slice(0, 8)}</div>
                </div>
              </label>
            ))}
            {datasets.length === 0 && (
              <div className="text-center py-12 text-slate-400">
                暂无知识库，请先创建
              </div>
            )}
          </div>

          <div className="pt-4 mt-4 border-t flex justify-end">
            <button 
              onClick={handleSave}
              disabled={processing || !selectedUser}
              className="px-6 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors flex items-center gap-2"
            >
              {processing && <Loader2 size={16} className="animate-spin" />}
              保存权限
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}

function RouteSampleManager() {
  const [samples, setSamples] = useState([])
  const [sourceOptions, setSourceOptions] = useState([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [limit, setLimit] = useState('100')
  const [userId, setUserId] = useState('')
  const [source, setSource] = useState('')

  const loadSamples = async (nextFilters) => {
    setLoading(true)
    setError('')
    try {
      const data = await fetchRouteSamples(nextFilters)
      setSamples(data)
    } catch (e) {
      setError(e.message || '加载失败')
      setSamples([])
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadSamples({ limit: 100 })
    fetchRouteSampleSources()
      .then(data => setSourceOptions(data))
      .catch(() => setSourceOptions([]))
  }, [])

  const normalizedLimit = () => {
    const n = Number.parseInt(limit, 10)
    if (Number.isNaN(n)) return 100
    return Math.max(1, Math.min(n, 500))
  }

  const handleSearch = () => {
    loadSamples({
      limit: normalizedLimit(),
      userId: userId.trim() ? userId.trim() : undefined,
      source: source.trim() ? source.trim() : undefined
    })
  }

  const handleReset = () => {
    setLimit('100')
    setUserId('')
    setSource('')
    loadSamples({ limit: 100 })
  }

  const formatTime = (value) => {
    if (!value) return '-'
    const dt = new Date(value)
    if (Number.isNaN(dt.getTime())) return String(value)
    return dt.toLocaleString()
  }

  const csvEscape = (value) => `"${String(value ?? '').replaceAll('"', '""')}"`

  const handleExportCsv = () => {
    const headers = [
      'id', 'created_at', 'conversation_id', 'user_id', 'source',
      'chosen_route', 'chosen_confidence', 'chosen_tool',
      'local_route', 'local_confidence',
      'planner_route', 'planner_confidence', 'query_text'
    ]
    const rows = samples.map(item => ([
      item.id,
      item.createdAt,
      item.conversationId,
      item.userId,
      item.source,
      item.chosenRoute,
      item.chosenConfidence,
      item.chosenTool,
      item.localRoute,
      item.localConfidence,
      item.plannerRoute,
      item.plannerConfidence,
      item.queryText
    ].map(csvEscape).join(',')))
    const content = [headers.join(','), ...rows].join('\n')
    const blob = new Blob([content], { type: 'text/csv;charset=utf-8;' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    const stamp = new Date().toISOString().replaceAll(':', '-')
    link.href = url
    link.download = `route_samples_${stamp}.csv`
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
  }

  return (
    <div className="p-8 h-full overflow-y-auto">
      <div className="max-w-[1400px] mx-auto space-y-6">
        <div className="flex items-center justify-between">
          <div>
            <h2 className="text-2xl font-bold text-slate-800">路由样本</h2>
            <p className="text-sm text-slate-500 mt-1">查看 Planner 与本地 Router 决策样本，支持筛选与导出</p>
          </div>
          <button
            onClick={handleExportCsv}
            disabled={samples.length === 0}
            className="px-4 py-2 bg-emerald-600 text-white rounded-lg hover:bg-emerald-700 disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2"
          >
            <Download size={16} />
            导出 CSV
          </button>
        </div>

        <div className="bg-white p-4 rounded-xl border shadow-sm flex flex-wrap items-end gap-3">
          <div>
            <label className="block text-xs text-slate-500 mb-1">条数</label>
            <input
              type="number"
              min="1"
              max="500"
              value={limit}
              onChange={(e) => setLimit(e.target.value)}
              className="w-28 px-3 py-2 border rounded-lg bg-slate-50 focus:bg-white focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>
          <div>
            <label className="block text-xs text-slate-500 mb-1">用户 ID</label>
            <input
              type="text"
              value={userId}
              onChange={(e) => setUserId(e.target.value)}
              placeholder="如 1"
              className="w-36 px-3 py-2 border rounded-lg bg-slate-50 focus:bg-white focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>
          <div>
            <label className="block text-xs text-slate-500 mb-1">来源</label>
            <select
              value={source}
              onChange={(e) => setSource(e.target.value)}
              className="w-48 px-3 py-2 border rounded-lg bg-slate-50 focus:bg-white focus:outline-none focus:ring-2 focus:ring-blue-500"
            >
              <option value="">全部来源</option>
              {sourceOptions.map(option => (
                <option key={option} value={option}>{option}</option>
              ))}
            </select>
          </div>
          <button
            onClick={handleSearch}
            disabled={loading}
            className="px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50 flex items-center gap-2"
          >
            <Search size={16} />
            查询
          </button>
          <button
            onClick={handleReset}
            disabled={loading}
            className="px-4 py-2 border border-slate-300 text-slate-700 rounded-lg hover:bg-slate-100 disabled:opacity-50 flex items-center gap-2"
          >
            <RefreshCw size={16} />
            重置
          </button>
        </div>

        <div className="bg-white rounded-xl border shadow-sm overflow-hidden">
          {error && (
            <div className="px-4 py-3 text-sm text-red-600 border-b bg-red-50">
              {error}
            </div>
          )}
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-slate-50 border-b">
                <tr className="text-slate-600">
                  <th className="px-3 py-2 text-left font-semibold whitespace-nowrap">时间</th>
                  <th className="px-3 py-2 text-left font-semibold whitespace-nowrap">会话</th>
                  <th className="px-3 py-2 text-left font-semibold whitespace-nowrap">用户</th>
                  <th className="px-3 py-2 text-left font-semibold whitespace-nowrap">来源</th>
                  <th className="px-3 py-2 text-left font-semibold whitespace-nowrap">最终</th>
                  <th className="px-3 py-2 text-left font-semibold whitespace-nowrap">本地候选</th>
                  <th className="px-3 py-2 text-left font-semibold whitespace-nowrap">Planner候选</th>
                  <th className="px-3 py-2 text-left font-semibold">Query</th>
                </tr>
              </thead>
              <tbody>
                {loading && (
                  <tr>
                    <td className="px-3 py-8 text-center text-slate-400" colSpan={8}>
                      <div className="inline-flex items-center gap-2">
                        <Loader2 className="animate-spin" size={16} />
                        加载中...
                      </div>
                    </td>
                  </tr>
                )}
                {!loading && samples.length === 0 && (
                  <tr>
                    <td className="px-3 py-8 text-center text-slate-400" colSpan={8}>
                      暂无样本
                    </td>
                  </tr>
                )}
                {!loading && samples.map(item => (
                  <tr key={item.id || `${item.conversationId}-${item.createdAt}`} className="border-b last:border-b-0 hover:bg-slate-50">
                    <td className="px-3 py-2 align-top whitespace-nowrap text-slate-600">{formatTime(item.createdAt)}</td>
                    <td className="px-3 py-2 align-top font-mono text-xs text-slate-700">{item.conversationId || '-'}</td>
                    <td className="px-3 py-2 align-top text-slate-700">{item.userId ?? '-'}</td>
                    <td className="px-3 py-2 align-top">
                      <span className="px-2 py-0.5 rounded bg-indigo-100 text-indigo-700 text-xs font-medium">
                        {item.source || '-'}
                      </span>
                    </td>
                    <td className="px-3 py-2 align-top text-xs">
                      <div className="font-medium text-slate-800">{item.chosenRoute || '-'}</div>
                      <div className="text-slate-500">conf: {item.chosenConfidence ?? '-'}</div>
                      <div className="text-slate-500">tool: {item.chosenTool || '-'}</div>
                    </td>
                    <td className="px-3 py-2 align-top text-xs">
                      <div className="font-medium text-slate-800">{item.localRoute || '-'}</div>
                      <div className="text-slate-500">conf: {item.localConfidence ?? '-'}</div>
                    </td>
                    <td className="px-3 py-2 align-top text-xs">
                      <div className="font-medium text-slate-800">{item.plannerRoute || '-'}</div>
                      <div className="text-slate-500">conf: {item.plannerConfidence ?? '-'}</div>
                    </td>
                    <td className="px-3 py-2 align-top text-slate-700 break-all min-w-[280px]">{item.queryText || '-'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>
  )
}

function SkillManager() {
  const [skills, setSkills] = useState([])
  const [auditRows, setAuditRows] = useState([])
  const [skillsLoading, setSkillsLoading] = useState(false)
  const [auditLoading, setAuditLoading] = useState(false)
  const [skillsError, setSkillsError] = useState('')
  const [auditError, setAuditError] = useState('')
  const [onlineOnly, setOnlineOnly] = useState(false)
  const [auditLimit, setAuditLimit] = useState('100')
  const [actionPending, setActionPending] = useState({})
  const [registerPending, setRegisterPending] = useState(false)
  const [registerForm, setRegisterForm] = useState({
    toolCode: '',
    toolName: '',
    description: '',
    protocolType: 'HTTP',
    invokeUrl: '',
    manifestUrl: '',
    healthUrl: '',
    triggerKeywords: '',
    inputMode: 'FILE_AND_PARAMS',
    outputMode: 'MIXED',
    uploadRequired: true,
    acceptedFileTypes: '.dxf',
    maxFiles: '200',
    parametersSchema: '',
    draftArgsTemplate: '',
    status: 'ONLINE'
  })

  const loadSkills = useCallback(async (nextOnlineOnly = onlineOnly) => {
    setSkillsLoading(true)
    setSkillsError('')
    try {
      const data = await fetchAdminSkills(nextOnlineOnly)
      setSkills(data)
    } catch (e) {
      setSkills([])
      setSkillsError(e?.message || '加载失败')
    } finally {
      setSkillsLoading(false)
    }
  }, [onlineOnly])

  const loadAudit = useCallback(async (nextLimit = auditLimit) => {
    setAuditLoading(true)
    setAuditError('')
    try {
      const data = await fetchSkillAudit(nextLimit)
      setAuditRows(data)
    } catch (e) {
      setAuditRows([])
      setAuditError(e?.message || '加载失败')
    } finally {
      setAuditLoading(false)
    }
  }, [auditLimit])

  useEffect(() => {
    loadSkills(onlineOnly)
    loadAudit(auditLimit)
  }, [onlineOnly, auditLimit, loadSkills, loadAudit])

  const formatTime = (value) => {
    if (!value) return '-'
    const dt = new Date(value)
    if (Number.isNaN(dt.getTime())) return String(value)
    return dt.toLocaleString()
  }

  const handleOffline = async (toolCode) => {
    if (!toolCode) return
    const confirmed = window.confirm(`确认下线技能 ${toolCode} 吗？`)
    if (!confirmed) return
    setActionPending(prev => ({ ...prev, [`offline:${toolCode}`]: true }))
    try {
      await offlineSkill(toolCode)
      await loadSkills(onlineOnly)
    } catch (e) {
      alert(e?.message || '下线失败')
    } finally {
      setActionPending(prev => ({ ...prev, [`offline:${toolCode}`]: false }))
    }
  }

  const handleOnline = async (toolCode) => {
    if (!toolCode) return
    const confirmed = window.confirm(`确认上线技能 ${toolCode} 吗？`)
    if (!confirmed) return
    setActionPending(prev => ({ ...prev, [`online:${toolCode}`]: true }))
    try {
      await onlineSkill(toolCode)
      await loadSkills(onlineOnly)
    } catch (e) {
      alert(e?.message || '上线失败')
    } finally {
      setActionPending(prev => ({ ...prev, [`online:${toolCode}`]: false }))
    }
  }

  const handleDelete = async (toolCode) => {
    if (!toolCode) return
    const confirmed = window.confirm(`确认删除技能 ${toolCode} 吗？此操作不可恢复。`)
    if (!confirmed) return
    setActionPending(prev => ({ ...prev, [`delete:${toolCode}`]: true }))
    try {
      await deleteAdminSkill(toolCode)
      await loadSkills(onlineOnly)
    } catch (e) {
      alert(e?.message || '删除失败')
    } finally {
      setActionPending(prev => ({ ...prev, [`delete:${toolCode}`]: false }))
    }
  }

  const handleRegister = async (e) => {
    e.preventDefault()
    if (!registerForm.toolCode.trim()) {
      alert('tool_code 不能为空')
      return
    }
    if (!registerForm.toolName.trim()) {
      alert('tool_name 不能为空')
      return
    }
    if (!registerForm.invokeUrl.trim()) {
      alert('invoke_url 不能为空')
      return
    }
    setRegisterPending(true)
    try {
      await registerAdminSkill({
        toolCode: registerForm.toolCode.trim(),
        toolName: registerForm.toolName.trim(),
        description: registerForm.description.trim(),
        protocolType: registerForm.protocolType,
        invokeUrl: registerForm.invokeUrl.trim(),
        manifestUrl: registerForm.manifestUrl.trim(),
        healthUrl: registerForm.healthUrl.trim(),
        triggerKeywords: registerForm.triggerKeywords.trim(),
        inputMode: registerForm.inputMode,
        outputMode: registerForm.outputMode,
        uploadRequired: Boolean(registerForm.uploadRequired),
        acceptedFileTypes: registerForm.acceptedFileTypes.trim(),
        maxFiles: Number(registerForm.maxFiles || 0),
        parametersSchema: registerForm.parametersSchema.trim(),
        draftArgsTemplate: registerForm.draftArgsTemplate.trim(),
        status: registerForm.status
      })
      setRegisterForm(prev => ({
        ...prev,
        toolCode: '',
        toolName: '',
        description: '',
        invokeUrl: '',
        manifestUrl: '',
        healthUrl: '',
        triggerKeywords: '',
        parametersSchema: '',
        draftArgsTemplate: ''
      }))
      await loadSkills(onlineOnly)
    } catch (e2) {
      alert(e2?.message || '新增技能失败')
    } finally {
      setRegisterPending(false)
    }
  }

  const handleSearchAudit = async () => {
    await loadAudit(auditLimit)
  }

  return (
    <div className="p-8 h-full overflow-y-auto">
      <div className="max-w-[1400px] mx-auto space-y-6">
        <div className="flex items-center justify-between">
          <div>
            <h2 className="text-2xl font-bold text-slate-800">技能管理</h2>
            <p className="text-sm text-slate-500 mt-1">管理员可新增、上线/下线、删除技能，并查询调用审计</p>
          </div>
          <div className="flex items-center gap-3">
            <label className="inline-flex items-center gap-2 text-sm text-slate-600">
              <input
                type="checkbox"
                checked={onlineOnly}
                onChange={(e) => setOnlineOnly(e.target.checked)}
                className="rounded border-slate-300"
              />
              仅显示在线技能
            </label>
            <button
              onClick={() => loadSkills(onlineOnly)}
              disabled={skillsLoading}
              className="px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50 flex items-center gap-2"
            >
              <RefreshCw size={16} />
              刷新技能
            </button>
          </div>
        </div>

        <form onSubmit={handleRegister} className="bg-white rounded-xl border shadow-sm p-4">
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-3">
            <input value={registerForm.toolCode} onChange={(e) => setRegisterForm(prev => ({ ...prev, toolCode: e.target.value }))} placeholder="tool_code *" className="px-3 py-2 border rounded-lg bg-slate-50 focus:bg-white focus:outline-none focus:ring-2 focus:ring-blue-500" />
            <input value={registerForm.toolName} onChange={(e) => setRegisterForm(prev => ({ ...prev, toolName: e.target.value }))} placeholder="tool_name *" className="px-3 py-2 border rounded-lg bg-slate-50 focus:bg-white focus:outline-none focus:ring-2 focus:ring-blue-500" />
            <input value={registerForm.invokeUrl} onChange={(e) => setRegisterForm(prev => ({ ...prev, invokeUrl: e.target.value }))} placeholder="invoke_url *" className="px-3 py-2 border rounded-lg bg-slate-50 focus:bg-white focus:outline-none focus:ring-2 focus:ring-blue-500" />
            <input value={registerForm.description} onChange={(e) => setRegisterForm(prev => ({ ...prev, description: e.target.value }))} placeholder="description" className="px-3 py-2 border rounded-lg bg-slate-50 focus:bg-white focus:outline-none focus:ring-2 focus:ring-blue-500" />
            <input value={registerForm.manifestUrl} onChange={(e) => setRegisterForm(prev => ({ ...prev, manifestUrl: e.target.value }))} placeholder="manifest_url" className="px-3 py-2 border rounded-lg bg-slate-50 focus:bg-white focus:outline-none focus:ring-2 focus:ring-blue-500" />
            <input value={registerForm.healthUrl} onChange={(e) => setRegisterForm(prev => ({ ...prev, healthUrl: e.target.value }))} placeholder="health_url" className="px-3 py-2 border rounded-lg bg-slate-50 focus:bg-white focus:outline-none focus:ring-2 focus:ring-blue-500" />
            <input value={registerForm.triggerKeywords} onChange={(e) => setRegisterForm(prev => ({ ...prev, triggerKeywords: e.target.value }))} placeholder="trigger_keywords(逗号分隔)" className="px-3 py-2 border rounded-lg bg-slate-50 focus:bg-white focus:outline-none focus:ring-2 focus:ring-blue-500" />
            <input value={registerForm.acceptedFileTypes} onChange={(e) => setRegisterForm(prev => ({ ...prev, acceptedFileTypes: e.target.value }))} placeholder="accepted_file_types" className="px-3 py-2 border rounded-lg bg-slate-50 focus:bg-white focus:outline-none focus:ring-2 focus:ring-blue-500" />
            <select value={registerForm.protocolType} onChange={(e) => setRegisterForm(prev => ({ ...prev, protocolType: e.target.value }))} className="px-3 py-2 border rounded-lg bg-slate-50 focus:bg-white focus:outline-none focus:ring-2 focus:ring-blue-500">
              <option value="HTTP">HTTP</option>
            </select>
            <select value={registerForm.inputMode} onChange={(e) => setRegisterForm(prev => ({ ...prev, inputMode: e.target.value }))} className="px-3 py-2 border rounded-lg bg-slate-50 focus:bg-white focus:outline-none focus:ring-2 focus:ring-blue-500">
              <option value="FILE_AND_PARAMS">FILE_AND_PARAMS</option>
              <option value="PARAMS_ONLY">PARAMS_ONLY</option>
            </select>
            <select value={registerForm.outputMode} onChange={(e) => setRegisterForm(prev => ({ ...prev, outputMode: e.target.value }))} className="px-3 py-2 border rounded-lg bg-slate-50 focus:bg-white focus:outline-none focus:ring-2 focus:ring-blue-500">
              <option value="MIXED">MIXED</option>
              <option value="TEXT">TEXT</option>
              <option value="FILE">FILE</option>
            </select>
            <select value={registerForm.status} onChange={(e) => setRegisterForm(prev => ({ ...prev, status: e.target.value }))} className="px-3 py-2 border rounded-lg bg-slate-50 focus:bg-white focus:outline-none focus:ring-2 focus:ring-blue-500">
              <option value="ONLINE">ONLINE</option>
              <option value="OFFLINE">OFFLINE</option>
            </select>
            <input type="number" min="1" value={registerForm.maxFiles} onChange={(e) => setRegisterForm(prev => ({ ...prev, maxFiles: e.target.value }))} placeholder="max_files" className="px-3 py-2 border rounded-lg bg-slate-50 focus:bg-white focus:outline-none focus:ring-2 focus:ring-blue-500" />
            <label className="inline-flex items-center gap-2 text-sm text-slate-600 px-3 py-2 border rounded-lg bg-slate-50">
              <input type="checkbox" checked={registerForm.uploadRequired} onChange={(e) => setRegisterForm(prev => ({ ...prev, uploadRequired: e.target.checked }))} />
              upload_required
            </label>
          </div>
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-3 mt-3">
            <textarea value={registerForm.parametersSchema} onChange={(e) => setRegisterForm(prev => ({ ...prev, parametersSchema: e.target.value }))} placeholder="parameters_schema(JSON字符串)" rows={4} className="px-3 py-2 border rounded-lg bg-slate-50 focus:bg-white focus:outline-none focus:ring-2 focus:ring-blue-500" />
            <textarea value={registerForm.draftArgsTemplate} onChange={(e) => setRegisterForm(prev => ({ ...prev, draftArgsTemplate: e.target.value }))} placeholder="draft_args_template(JSON字符串)" rows={4} className="px-3 py-2 border rounded-lg bg-slate-50 focus:bg-white focus:outline-none focus:ring-2 focus:ring-blue-500" />
          </div>
          <div className="mt-3 flex justify-end">
            <button type="submit" disabled={registerPending} className="px-4 py-2 bg-emerald-600 text-white rounded-lg hover:bg-emerald-700 disabled:opacity-50">
              {registerPending ? '提交中...' : '新增/更新技能'}
            </button>
          </div>
        </form>

        <div className="bg-white rounded-xl border shadow-sm overflow-hidden">
          {skillsError && (
            <div className="px-4 py-3 text-sm text-red-600 border-b bg-red-50">
              {skillsError}
            </div>
          )}
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-slate-50 border-b">
                <tr className="text-slate-600">
                  <th className="px-3 py-2 text-left font-semibold whitespace-nowrap">tool_code</th>
                  <th className="px-3 py-2 text-left font-semibold whitespace-nowrap">tool_name</th>
                  <th className="px-3 py-2 text-left font-semibold whitespace-nowrap">状态</th>
                  <th className="px-3 py-2 text-left font-semibold whitespace-nowrap">版本</th>
                  <th className="px-3 py-2 text-left font-semibold whitespace-nowrap">更新时间</th>
                  <th className="px-3 py-2 text-left font-semibold">描述</th>
                  <th className="px-3 py-2 text-left font-semibold whitespace-nowrap">操作</th>
                </tr>
              </thead>
              <tbody>
                {skillsLoading && (
                  <tr>
                    <td className="px-3 py-8 text-center text-slate-400" colSpan={7}>
                      <div className="inline-flex items-center gap-2">
                        <Loader2 className="animate-spin" size={16} />
                        加载中...
                      </div>
                    </td>
                  </tr>
                )}
                {!skillsLoading && skills.length === 0 && (
                  <tr>
                    <td className="px-3 py-8 text-center text-slate-400" colSpan={7}>
                      暂无技能
                    </td>
                  </tr>
                )}
                {!skillsLoading && skills.map((item) => (
                  <tr key={item.tool_code || item.id} className="border-b last:border-b-0 hover:bg-slate-50">
                    <td className="px-3 py-2 align-top font-mono text-xs text-slate-700">{item.tool_code || '-'}</td>
                    <td className="px-3 py-2 align-top text-slate-800">{item.tool_name || '-'}</td>
                    <td className="px-3 py-2 align-top">
                      <span className={cn(
                        'px-2 py-0.5 rounded text-xs font-medium',
                        String(item.status || '').toUpperCase() === 'ONLINE'
                          ? 'bg-emerald-100 text-emerald-700'
                          : 'bg-slate-200 text-slate-700'
                      )}>
                        {item.status || '-'}
                      </span>
                    </td>
                    <td className="px-3 py-2 align-top text-slate-700">{item.version ?? '-'}</td>
                    <td className="px-3 py-2 align-top text-slate-600 whitespace-nowrap">{formatTime(item.updated_at)}</td>
                    <td className="px-3 py-2 align-top text-slate-700 min-w-[320px]">{item.description || '-'}</td>
                    <td className="px-3 py-2 align-top">
                      <div className="flex items-center gap-2">
                        <button
                          onClick={() => handleOnline(item.tool_code)}
                          disabled={String(item.status || '').toUpperCase() === 'ONLINE' || !!actionPending[`online:${item.tool_code}`]}
                          className="px-3 py-1.5 rounded border border-emerald-300 text-emerald-600 hover:bg-emerald-50 disabled:opacity-50 disabled:cursor-not-allowed text-xs"
                        >
                          {actionPending[`online:${item.tool_code}`] ? '处理中...' : '上线'}
                        </button>
                        <button
                          onClick={() => handleOffline(item.tool_code)}
                          disabled={String(item.status || '').toUpperCase() !== 'ONLINE' || !!actionPending[`offline:${item.tool_code}`]}
                          className="px-3 py-1.5 rounded border border-amber-300 text-amber-600 hover:bg-amber-50 disabled:opacity-50 disabled:cursor-not-allowed text-xs"
                        >
                          {actionPending[`offline:${item.tool_code}`] ? '处理中...' : '下线'}
                        </button>
                        <button
                          onClick={() => handleDelete(item.tool_code)}
                          disabled={!!actionPending[`delete:${item.tool_code}`]}
                          className="px-3 py-1.5 rounded border border-rose-300 text-rose-600 hover:bg-rose-50 disabled:opacity-50 disabled:cursor-not-allowed text-xs"
                        >
                          {actionPending[`delete:${item.tool_code}`] ? '处理中...' : '删除'}
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>

        <div className="bg-white p-4 rounded-xl border shadow-sm flex flex-wrap items-end gap-3">
          <div>
            <label className="block text-xs text-slate-500 mb-1">审计条数</label>
            <input
              type="number"
              min="1"
              max="500"
              value={auditLimit}
              onChange={(e) => setAuditLimit(e.target.value)}
              className="w-28 px-3 py-2 border rounded-lg bg-slate-50 focus:bg-white focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>
          <button
            onClick={handleSearchAudit}
            disabled={auditLoading}
            className="px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50 flex items-center gap-2"
          >
            <Search size={16} />
            查询审计
          </button>
          <button
            onClick={() => loadAudit(auditLimit)}
            disabled={auditLoading}
            className="px-4 py-2 border border-slate-300 text-slate-700 rounded-lg hover:bg-slate-100 disabled:opacity-50 flex items-center gap-2"
          >
            <RefreshCw size={16} />
            刷新
          </button>
        </div>

        <div className="bg-white rounded-xl border shadow-sm overflow-hidden">
          {auditError && (
            <div className="px-4 py-3 text-sm text-red-600 border-b bg-red-50">
              {auditError}
            </div>
          )}
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-slate-50 border-b">
                <tr className="text-slate-600">
                  <th className="px-3 py-2 text-left font-semibold whitespace-nowrap">时间</th>
                  <th className="px-3 py-2 text-left font-semibold whitespace-nowrap">tool_call_id</th>
                  <th className="px-3 py-2 text-left font-semibold whitespace-nowrap">tool_code</th>
                  <th className="px-3 py-2 text-left font-semibold whitespace-nowrap">状态</th>
                  <th className="px-3 py-2 text-left font-semibold whitespace-nowrap">耗时(ms)</th>
                  <th className="px-3 py-2 text-left font-semibold whitespace-nowrap">用户</th>
                  <th className="px-3 py-2 text-left font-semibold">错误</th>
                </tr>
              </thead>
              <tbody>
                {auditLoading && (
                  <tr>
                    <td className="px-3 py-8 text-center text-slate-400" colSpan={7}>
                      <div className="inline-flex items-center gap-2">
                        <Loader2 className="animate-spin" size={16} />
                        加载中...
                      </div>
                    </td>
                  </tr>
                )}
                {!auditLoading && auditRows.length === 0 && (
                  <tr>
                    <td className="px-3 py-8 text-center text-slate-400" colSpan={7}>
                      暂无审计记录
                    </td>
                  </tr>
                )}
                {!auditLoading && auditRows.map((row) => (
                  <tr key={row.id || row.tool_call_id} className="border-b last:border-b-0 hover:bg-slate-50">
                    <td className="px-3 py-2 align-top whitespace-nowrap text-slate-600">{formatTime(row.created_at)}</td>
                    <td className="px-3 py-2 align-top font-mono text-xs text-slate-700">{row.tool_call_id || '-'}</td>
                    <td className="px-3 py-2 align-top font-mono text-xs text-slate-700">{row.tool_code || '-'}</td>
                    <td className="px-3 py-2 align-top">
                      <span className={cn(
                        'px-2 py-0.5 rounded text-xs font-medium',
                        String(row.status || '').toUpperCase() === 'SUCCESS'
                          ? 'bg-emerald-100 text-emerald-700'
                          : String(row.status || '').toUpperCase() === 'FAILED'
                            ? 'bg-rose-100 text-rose-700'
                            : 'bg-indigo-100 text-indigo-700'
                      )}>
                        {row.status || '-'}
                      </span>
                    </td>
                    <td className="px-3 py-2 align-top text-slate-700">{row.latency_ms ?? '-'}</td>
                    <td className="px-3 py-2 align-top text-slate-700">{row.username || row.user_id || '-'}</td>
                    <td className="px-3 py-2 align-top text-slate-600 min-w-[260px] break-all">{row.error_message || '-'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>
  )
}

function SuperAdminOverview() {
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [admins, setAdmins] = useState([])
  const [generatedAt, setGeneratedAt] = useState('')
  const [expandedDatasets, setExpandedDatasets] = useState({})
  const [expandedConversations, setExpandedConversations] = useState({})
  const [expandedConversationRecords, setExpandedConversationRecords] = useState({})

  const loadOverview = async () => {
    setLoading(true)
    setError('')
    try {
      const data = await fetchSuperAdminOverview()
      setAdmins(Array.isArray(data?.admins) ? data.admins : [])
      setGeneratedAt(data?.generatedAt || '')
    } catch (e) {
      setError(e?.message || '加载失败')
      setAdmins([])
      setGeneratedAt('')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadOverview()
  }, [])

  const formatTime = (value) => {
    if (!value) return '-'
    const dt = new Date(value)
    if (Number.isNaN(dt.getTime())) return String(value)
    return dt.toLocaleString()
  }

  const toggleDataset = (adminKey, datasetId) => {
    const key = `${adminKey}-${datasetId}`
    setExpandedDatasets(prev => ({ ...prev, [key]: !prev[key] }))
  }

  const toggleConversation = (adminKey, conversationId) => {
    const key = `${adminKey}-${conversationId}`
    setExpandedConversations(prev => ({ ...prev, [key]: !prev[key] }))
  }

  const toggleConversationRecords = (adminKey, conversationId) => {
    const key = `${adminKey}-${conversationId}`
    setExpandedConversationRecords(prev => ({ ...prev, [key]: !prev[key] }))
  }

  return (
    <div className="p-8 h-full overflow-y-auto">
      <div className="max-w-[1400px] mx-auto space-y-6">
        <div className="flex items-center justify-between">
          <div>
            <h2 className="text-2xl font-bold text-slate-800">超级管理员总览</h2>
            <p className="text-sm text-slate-500 mt-1">
              查看管理员的知识库、授权时间、会话与对话记录明细
            </p>
          </div>
          <button
            onClick={loadOverview}
            disabled={loading}
            className="px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50 flex items-center gap-2"
          >
            <RefreshCw size={16} />
            刷新
          </button>
        </div>

        <div className="bg-white rounded-xl border shadow-sm px-4 py-3 text-sm text-slate-600">
          最近生成时间：{formatTime(generatedAt)}
        </div>

        {error && (
          <div className="bg-red-50 border border-red-200 rounded-xl px-4 py-3 text-sm text-red-700">
            {error}
          </div>
        )}

        {loading && (
          <div className="bg-white rounded-xl border shadow-sm px-4 py-10 text-slate-500 flex items-center justify-center gap-2">
            <Loader2 size={16} className="animate-spin" />
            加载中...
          </div>
        )}

        {!loading && admins.length === 0 && !error && (
          <div className="bg-white rounded-xl border shadow-sm px-4 py-10 text-center text-slate-400">
            暂无管理员资产数据
          </div>
        )}

        {!loading && admins.map((admin) => (
          <div key={admin.adminUserId || admin.adminUsername} className="bg-white rounded-xl border shadow-sm overflow-hidden">
            <div className="px-5 py-4 border-b bg-slate-50">
              <div className="flex items-center justify-between gap-3">
                <div className="flex items-center gap-2">
                  <Users size={18} className="text-indigo-600" />
                  <div className="font-semibold text-slate-800">{admin.adminUsername || '-'}</div>
                  <span className="text-xs px-2 py-0.5 rounded bg-indigo-100 text-indigo-700">admin</span>
                </div>
                <div className="text-xs text-slate-600">
                  知识库 {admin.ownedDatasetCount || 0} · 授权记录 {admin.totalGrantedPermissionCount || 0} · 用户总览 {admin.userOverviewCount || 0} · 会话 {admin.conversationOverviewCount || 0} · 对话记录 {admin.conversationRecordCount || 0}
                </div>
              </div>
            </div>

            <div className="p-4 space-y-3">
              {(!Array.isArray(admin.ownedDatasets) || admin.ownedDatasets.length === 0) && (
                <div className="text-sm text-slate-400 px-1">该管理员暂无登记的知识库。</div>
              )}
              {Array.isArray(admin.ownedDatasets) && admin.ownedDatasets.map((dataset) => (
                <div key={`${admin.adminUserId}-${dataset.datasetId}`} className="rounded-lg border border-slate-200">
                  <div className="px-4 py-3 bg-slate-50 border-b flex items-center justify-between gap-2">
                    <div className="flex items-center gap-2 text-slate-800 font-medium">
                      <Database size={16} className="text-blue-600" />
                      <span>{dataset.datasetName || dataset.datasetId}</span>
                    </div>
                    <div className="flex items-center gap-3">
                      <div className="text-xs text-slate-600">
                        文档数 {dataset.documentCount ?? 0}
                      </div>
                      <button
                        onClick={() => toggleDataset(admin.adminUserId || admin.adminUsername, dataset.datasetId)}
                        className="text-xs text-blue-600 hover:text-blue-800"
                      >
                        {expandedDatasets[`${admin.adminUserId || admin.adminUsername}-${dataset.datasetId}`] ? '收起' : '展开'}
                      </button>
                    </div>
                  </div>
                  {expandedDatasets[`${admin.adminUserId || admin.adminUsername}-${dataset.datasetId}`] && (
                    <div className="px-4 py-3 space-y-2">
                      <div className="text-xs text-slate-600">
                        知识库创建时间：{formatTime(dataset.datasetCreatedAt)}
                      </div>
                      <div className="text-xs text-slate-500">已授权用户</div>
                      {(!Array.isArray(dataset.grantedUsers) || dataset.grantedUsers.length === 0) ? (
                        <div className="text-sm text-slate-400">暂无授权用户</div>
                      ) : (
                        <div className="space-y-1">
                          {dataset.grantedUsers.map((u) => (
                            <div key={`${dataset.datasetId}-${u.userId}`} className="text-xs text-slate-700">
                              {u.username} ({u.role}) · 授权时间 {formatTime(u.authorizedAt)}
                            </div>
                          ))}
                        </div>
                      )}
                    </div>
                  )}
                </div>
              ))}
              <div className="rounded-lg border border-slate-200">
                <div className="px-4 py-3 bg-slate-50 border-b text-sm font-medium text-slate-800">
                  管理员会话总览
                </div>
                <div className="px-4 py-3 space-y-2">
                  {(!Array.isArray(admin.conversations) || admin.conversations.length === 0) ? (
                    <div className="text-sm text-slate-400">暂无会话记录</div>
                  ) : (
                    admin.conversations.map((conversation) => {
                      const conversationKey = `${admin.adminUserId || admin.adminUsername}-${conversation.conversationId}`
                      const expanded = !!expandedConversations[conversationKey]
                      const recordExpanded = !!expandedConversationRecords[conversationKey]
                      return (
                        <div key={conversationKey} className="rounded border border-slate-200">
                          <div className="px-3 py-2 flex items-center justify-between bg-white">
                            <div className="text-xs text-slate-800">
                              {conversation.title || conversation.conversationId} · 消息 {conversation.messageCount || 0}
                            </div>
                            <button
                              onClick={() => toggleConversation(admin.adminUserId || admin.adminUsername, conversation.conversationId)}
                              className="text-xs text-blue-600 hover:text-blue-800"
                            >
                              {expanded ? '收起' : '展开'}
                            </button>
                          </div>
                          {expanded && (
                            <div className="px-3 py-2 border-t bg-slate-50 space-y-2">
                              <div className="text-xs text-slate-600">
                                创建时间：{formatTime(conversation.createdAt)} · 更新时间：{formatTime(conversation.updatedAt)}
                              </div>
                              <button
                                onClick={() => toggleConversationRecords(admin.adminUserId || admin.adminUsername, conversation.conversationId)}
                                className="text-xs text-indigo-600 hover:text-indigo-800"
                              >
                                {recordExpanded ? '收起对话细节' : '展开对话细节'}
                              </button>
                              {recordExpanded && (
                                <div className="space-y-1">
                                  {(!Array.isArray(conversation.records) || conversation.records.length === 0) ? (
                                    <div className="text-xs text-slate-400">暂无对话明细</div>
                                  ) : (
                                    conversation.records.map((record) => (
                                      <div key={`${conversationKey}-${record.id}`} className="text-xs text-slate-700 bg-white border border-slate-200 rounded px-2 py-1.5">
                                        [{record.role}] {record.content} · {formatTime(record.recordTime)}
                                      </div>
                                    ))
                                  )}
                                </div>
                              )}
                            </div>
                          )}
                        </div>
                      )
                    })
                  )}
                </div>
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}

function SourceViewer({ reference, onClose }) {
  const [activeTab, setActiveTab] = useState('summary') // 'summary' | 'pdf'
  const [numPages, setNumPages] = useState(null)
  const [scale, setScale] = useState(1.0)
  const [imageError, setImageError] = useState(false)
  const [pdfUrl, setPdfUrl] = useState('')
  const [pdfLoading, setPdfLoading] = useState(false)
  const [pdfLoadError, setPdfLoadError] = useState('')
  const pdfUrlRef = useRef('')

  useEffect(() => {
    setActiveTab('summary')
    setImageError(false)
    setScale(1.0)
    setNumPages(null)
    setPdfLoadError('')
    if (pdfUrlRef.current) {
      URL.revokeObjectURL(pdfUrlRef.current)
      pdfUrlRef.current = ''
    }
    setPdfUrl('')
  }, [reference])

  useEffect(() => {
    if (activeTab !== 'pdf' || !reference?.document_id) {
      return
    }
    let disposed = false
    let nextPdfUrl = ''
    setPdfLoading(true)
    setPdfLoadError('')
    ;(async () => {
      try {
        const res = await apiFetch(`/document/get/${encodeURIComponent(reference.document_id)}`)
        if (!res.ok) {
          throw new Error(`HTTP ${res.status}`)
        }
        const blob = await res.blob()
        if (!blob || blob.size === 0) {
          throw new Error('EMPTY_BLOB')
        }
        nextPdfUrl = URL.createObjectURL(blob)
        if (disposed) {
          URL.revokeObjectURL(nextPdfUrl)
          return
        }
        if (pdfUrlRef.current) {
          URL.revokeObjectURL(pdfUrlRef.current)
        }
        pdfUrlRef.current = nextPdfUrl
        setPdfUrl(nextPdfUrl)
      } catch {
        if (!disposed) {
          setPdfLoadError('Failed to load PDF. Please check permissions.')
          if (pdfUrlRef.current) {
            URL.revokeObjectURL(pdfUrlRef.current)
            pdfUrlRef.current = ''
          }
          setPdfUrl('')
        }
      } finally {
        if (!disposed) {
          setPdfLoading(false)
        }
      }
    })()
    return () => {
      disposed = true
      if (nextPdfUrl) {
        URL.revokeObjectURL(nextPdfUrl)
      }
    }
  }, [activeTab, reference?.document_id])

  useEffect(() => {
    if (activeTab === 'pdf' && numPages && reference?.positions?.[0]) {
        // Delay slightly to allow rendering
        setTimeout(() => {
            const pageNum = reference.positions[0][0];
            const pageElement = document.getElementById(`pdf-page-${pageNum}`);
            if (pageElement) {
                pageElement.scrollIntoView({ behavior: 'smooth', block: 'start' });
            }
        }, 100);
    }
  }, [activeTab, numPages, reference])

  if (!reference) return null

  const imageId = reference.image_id || reference.img_id
  const hasImage = !!imageId
  const hasPdf = !!reference.document_id

  const onDocumentLoadSuccess = ({ numPages }) => {
    setNumPages(numPages)
  }

  return (
    <div 
      className="fixed inset-0 bg-black/50 z-[100] flex items-center justify-center p-4 backdrop-blur-sm animate-in fade-in duration-200"
      onClick={onClose}
    >
      <div 
        className="bg-white rounded-xl shadow-2xl w-full max-w-5xl h-[85vh] flex flex-col animate-in zoom-in-95 duration-200 overflow-hidden"
        onClick={e => e.stopPropagation()}
      >
        {/* Header */}
        <div className="p-3 border-b flex items-center justify-between bg-slate-50 shrink-0">
          <div className="flex items-center gap-4 overflow-hidden">
            <h3 className="font-semibold text-slate-800 flex items-center gap-2 text-sm truncate pr-4 max-w-[300px]">
              <FileText size={18} className="text-blue-600 flex-shrink-0" />
              <span className="truncate" title={reference.document_name}>{reference.document_name}</span>
            </h3>
            
            <div className="flex bg-slate-200 p-1 rounded-lg shrink-0">
              <button
                onClick={() => setActiveTab('summary')}
                className={cn(
                  "px-3 py-1 rounded-md text-xs font-medium transition-all",
                  activeTab === 'summary' ? "bg-white shadow text-slate-900" : "text-slate-500 hover:text-slate-700"
                )}
              >
                Summary
              </button>
              {hasPdf && (
                <button
                  onClick={() => setActiveTab('pdf')}
                  className={cn(
                    "px-3 py-1 rounded-md text-xs font-medium transition-all",
                    activeTab === 'pdf' ? "bg-white shadow text-slate-900" : "text-slate-500 hover:text-slate-700"
                  )}
                >
                  Full PDF
                </button>
              )}
            </div>
          </div>

          <button 
            onClick={onClose}
            className="p-1.5 hover:bg-slate-200 rounded-full text-slate-500 transition-colors"
          >
            <X size={18} />
          </button>
        </div>

        {/* Content */}
        <div className="flex-1 overflow-hidden relative bg-slate-100/50">
          {activeTab === 'summary' && (
            <div className="h-full overflow-y-auto p-6">
              <div className="max-w-3xl mx-auto space-y-6">
                {/* Meta Info */}
                <div className="flex items-center gap-4 text-xs text-slate-500 uppercase tracking-wider font-semibold">
                  <span>Matched Content</span>
                  {reference.similarity && (
                    <span className="bg-emerald-100 text-emerald-700 px-2 py-0.5 rounded-full">
                      Score: {(reference.similarity * 100).toFixed(1)}%
                    </span>
                  )}
                </div>

                {/* Text Content */}
                <div className="p-5 bg-white rounded-xl border border-slate-200 shadow-sm text-slate-700 whitespace-pre-wrap leading-relaxed text-sm font-mono">
                  {reference.content_with_weight ? (
                     <div dangerouslySetInnerHTML={{ __html: reference.content_with_weight }} />
                  ) : (
                     reference.content || "No content preview available."
                  )}
                </div>

                {/* Image Preview */}
                {hasImage && !imageError && (
                  <div className="space-y-2">
                    <div className="text-xs text-slate-500 uppercase tracking-wider font-semibold flex items-center gap-2">
                      <ImageIcon size={14} />
                      <span>Page Snapshot</span>
                    </div>
                    <div className="rounded-xl overflow-hidden border border-slate-200 shadow-sm bg-white group relative">
                      <img 
                        src={`/api/document/image/${imageId}`}
                        alt="Document Snapshot"
                        className="w-full h-auto object-contain max-h-[500px]"
                        onError={() => setImageError(true)}
                      />
                      {hasPdf && (
                        <div className="absolute inset-0 bg-black/0 group-hover:bg-black/10 transition-colors flex items-center justify-center opacity-0 group-hover:opacity-100">
                           <button 
                             onClick={() => setActiveTab('pdf')}
                             className="px-4 py-2 bg-white text-slate-900 rounded-lg shadow-lg font-medium text-sm transform translate-y-2 group-hover:translate-y-0 transition-all"
                           >
                             View in PDF
                           </button>
                        </div>
                      )}
                    </div>
                  </div>
                )}
              </div>
            </div>
          )}

          {activeTab === 'pdf' && (
            <div className="h-full flex flex-col">
               {/* PDF Toolbar */}
               <div className="p-2 border-b bg-white flex items-center justify-between shrink-0 z-10 shadow-sm">
                 <div className="flex items-center gap-2">
                   <span className="text-xs font-mono text-slate-500 px-2">
                     Total {numPages || '--'} Pages
                   </span>
                 </div>
                 <div className="flex items-center gap-2">
                    <button onClick={() => setScale(s => Math.max(0.5, s - 0.1))} className="p-1.5 hover:bg-slate-100 rounded"><ZoomOut size={16} /></button>
                    <span className="text-xs font-mono w-12 text-center select-none">{(scale * 100).toFixed(0)}%</span>
                    <button onClick={() => setScale(s => Math.min(2.0, s + 0.1))} className="p-1.5 hover:bg-slate-100 rounded"><ZoomIn size={16} /></button>
                 </div>
               </div>
               
               {/* PDF View */}
               <div className="flex-1 overflow-auto bg-slate-500/10 flex justify-center p-8">
                 <Document
                   file={pdfUrl || undefined}
                   onLoadSuccess={onDocumentLoadSuccess}
                   className="shadow-xl flex flex-col gap-4"
                   loading={<div className="flex items-center gap-2 text-slate-500"><Loader2 className="animate-spin" /> Loading PDF...</div>}
                   error={<div className="text-red-500 text-sm p-4 bg-red-50 rounded">{pdfLoadError || 'Failed to load PDF. Please check permissions.'}</div>}
                 >
                   {numPages && Array.from(new Array(numPages), (el, index) => {
                        const pageNum = index + 1;
                        return (
                            <div key={`page_${pageNum}`} id={`pdf-page-${pageNum}`} className="relative">
                                <Page 
                                    pageNumber={pageNum} 
                                    scale={scale}
                                    renderTextLayer={false}
                                    renderAnnotationLayer={false}
                                    className="shadow-md"
                                >
                                    {/* Highlight Overlay */}
                                    {reference.positions && reference.positions.map((pos, idx) => {
                                        const [p, x_min, x_max, y_min, y_max] = pos; 
                                        if (p !== pageNum) return null;
                                        return (
                                            <div
                                                key={idx}
                                                style={{
                                                    position: 'absolute',
                                                    left: x_min * scale,
                                                    top: y_min * scale,
                                                    width: (x_max - x_min) * scale,
                                                    height: (y_max - y_min) * scale,
                                                    backgroundColor: 'rgba(255, 255, 0, 0.2)',
                                                    border: '1px solid rgba(255, 200, 0, 0.4)',
                                                    pointerEvents: 'none'
                                                }}
                                            />
                                        )
                                    })}
                                </Page>
                            </div>
                        );
                   })}
                 </Document>
                 {pdfLoading && (
                   <div className="absolute top-4 right-4 px-3 py-1.5 rounded bg-white text-xs text-slate-500 border border-slate-200 shadow-sm">
                     PDF 加载中...
                   </div>
                 )}
               </div>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

function MarkdownWithCitations({ content, references, onViewReference }) {
  if (!content) return null;

  const formattedContent = content.replace(/\[(?:ID:\s*)?(\d+)\]/gi, (match, id) => ` [${parseInt(id) + 1}](#citation-${id})`);

  return (
    <Markdown
      components={{
        pre: ({_node, ...props}) => <div className="overflow-auto w-full my-2 bg-slate-800 text-slate-100 p-2 rounded" {...props} />,
        code: ({_node, ...props}) => <code className="bg-slate-100 text-slate-800 px-1 py-0.5 rounded text-xs" {...props} />,
        a: ({_node, href, children, ...props}) => {
          if (href?.startsWith('#citation-')) {
            const index = parseInt(href.replace('#citation-', ''));
            const ref = references?.[index];
            if (ref) {
              return (
                <button 
                  onClick={(e) => { e.preventDefault(); onViewReference(ref); }}
                  className="inline-flex items-center justify-center min-w-[1.25rem] h-5 px-1 ml-0.5 text-[10px] font-bold text-blue-600 bg-blue-50 rounded-full border border-blue-200 hover:bg-blue-100 align-top transition-colors transform -translate-y-0.5 cursor-pointer select-none"
                  title={ref.document_name}
                >
                  {index + 1}
                </button>
              );
            }
            return <span className="text-gray-400 text-[10px] ml-0.5">[{index + 1}]</span>;
          }
          return <a href={href} className="text-blue-600 hover:underline" {...props}>{children}</a>
        }
      }}
    >
      {formattedContent}
    </Markdown>
  );
}

function ThoughtBlock({ content, references, onViewReference, isStreaming }) {
  const [expanded, setExpanded] = useState(true);

  // Auto-collapse when streaming finishes, expand when streaming starts
  useEffect(() => {
    if (isStreaming) {
      setExpanded(true)
    } else {
      setExpanded(false)
    }
  }, [isStreaming])
  
  return (
    <div className="mb-4 rounded-lg overflow-hidden border border-amber-200 bg-amber-50">
        <button 
            onClick={() => setExpanded(!expanded)}
            className="w-full flex items-center gap-2 px-3 py-2 bg-amber-100/50 hover:bg-amber-100 transition-colors text-xs font-semibold text-amber-700 uppercase tracking-wide select-none"
        >
            <Brain size={14} className="text-amber-600" />
            <span>深度思考过程 (Deep Thinking)</span>
            <span className="ml-auto text-amber-500 text-[10px]">
                {expanded ? '收起' : '展开'}
            </span>
        </button>
        
        {expanded && (
            <div className="p-3 text-sm text-slate-600 italic leading-relaxed border-t border-amber-100 bg-white/50">
                <MarkdownWithCitations 
                    content={content} 
                    references={references} 
                    onViewReference={onViewReference} 
                />
            </div>
        )}
    </div>
  )
}

function ChatInterface() {
  const createDefaultAssistantMessage = useCallback(() => ({
    role: 'assistant',
    content: '你好！我是 AI 助手，请问有什么可以帮你？'
  }), [])
  const [messages, setMessages] = useState(() => [
    createDefaultAssistantMessage()
  ])
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  const [conversationLoading, setConversationLoading] = useState(false)
  const [conversationError, setConversationError] = useState('')
  const [conversations, setConversations] = useState([])
  const [activeConversationTitle, setActiveConversationTitle] = useState('')
  const [renamingConversationId, setRenamingConversationId] = useState('')
  const [renamingTitle, setRenamingTitle] = useState('')
  const [conversationActionLoading, setConversationActionLoading] = useState(false)
  const [viewingRef, setViewingRef] = useState(null)
  const [toolForms, setToolForms] = useState({})
  const [toolPending, setToolPending] = useState({})
  const [toolResults, setToolResults] = useState({})
  const [toolCatalog, setToolCatalog] = useState([])
  const [toolCatalogLoading, setToolCatalogLoading] = useState(false)
  const [toolCatalogError, setToolCatalogError] = useState('')
  const [manualDraftPending, setManualDraftPending] = useState('')
  const [planDrafts, setPlanDrafts] = useState({})
  const [planUiStates, setPlanUiStates] = useState({})
  const messagesEndRef = useRef(null)
  const abortControllerRef = useRef(null)
  const currentRequestIdRef = useRef(0)
  const conversationIdRef = useRef('')
  const quickRouteExamples = [
    '什么是指标校核',
    '请使用指标校核',
    '请帮我指标校核',
    '什么是大模型',
    '如何进行半面积计算'
  ]

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" })
  }, [messages])

  const normalizeConversationTitle = useCallback((item) => {
    return item?.name || item?.title || item?.conversationTitle || item?.conversation_id || '未命名会话'
  }, [])

  const normalizeConversationId = useCallback((item) => {
    return item?.conversationId || item?.conversation_id || item?.id || ''
  }, [])

  const normalizeMessageRole = useCallback((item) => {
    const rawRole = String(item?.role || item?.message_role || item?.sender || '').toLowerCase()
    if (rawRole === 'user') return 'user'
    if (rawRole === 'assistant') return 'assistant'
    return 'assistant'
  }, [])

  const normalizeMessageContent = useCallback((item) => {
    return item?.content || item?.message || item?.text || ''
  }, [])

  const normalizeStoredMessagePayload = useCallback((item) => {
    const rawPayload = item?.messagePayload ?? item?.message_payload ?? ''
    if (!rawPayload) return {}
    if (typeof rawPayload === 'object') return rawPayload
    if (typeof rawPayload !== 'string') return {}
    try {
      return JSON.parse(rawPayload)
    } catch {
      return {}
    }
  }, [])

  const normalizeMessageFromHistory = useCallback((item) => {
    const payload = normalizeStoredMessagePayload(item)
    const fallbackContent = typeof payload?.content === 'string' ? payload.content : ''
    const refs = Array.isArray(payload?.references)
      ? payload.references
      : (Array.isArray(item?.references) ? item.references : [])
    const sourceTag = typeof payload?.sourceTag === 'string'
      ? payload.sourceTag
      : (typeof item?.sourceTag === 'string' ? item.sourceTag : '')
    const logicFlow = typeof payload?.logicFlow === 'string'
      ? payload.logicFlow
      : (typeof item?.logicFlow === 'string' ? item.logicFlow : '')
    const analysisPlan = payload?.analysisPlan && typeof payload.analysisPlan === 'object'
      ? payload.analysisPlan
      : (item?.analysisPlan && typeof item.analysisPlan === 'object' ? item.analysisPlan : null)
    const analysisSteps = Array.isArray(payload?.analysisSteps)
      ? payload.analysisSteps
      : (Array.isArray(item?.analysisSteps) ? item.analysisSteps : [])
    const analysisSummary = payload?.analysisSummary && typeof payload.analysisSummary === 'object'
      ? payload.analysisSummary
      : (item?.analysisSummary && typeof item.analysisSummary === 'object' ? item.analysisSummary : null)
    const toolDraft = payload?.toolDraft && typeof payload.toolDraft === 'object'
      ? payload.toolDraft
      : (item?.toolDraft && typeof item.toolDraft === 'object' ? item.toolDraft : null)
    const clarify = payload?.clarify && typeof payload.clarify === 'object'
      ? payload.clarify
      : (item?.clarify && typeof item.clarify === 'object' ? item.clarify : null)
    const messageId = item?.id ?? item?.messageId ?? item?.message_id ?? item?.recordId ?? item?.record_id ?? null
    return {
      id: messageId,
      role: normalizeMessageRole(item),
      content: normalizeMessageContent(item) || fallbackContent,
      references: refs,
      sourceTag,
      logicFlow,
      analysisPlan,
      analysisSteps,
      analysisSummary,
      toolDraft,
      clarify
    }
  }, [normalizeMessageContent, normalizeMessageRole, normalizeStoredMessagePayload])

  const buildMessagePayloadForSave = (msg) => {
    if (!msg || typeof msg !== 'object') return ''
    const payload = {
      content: String(msg.content || ''),
      references: Array.isArray(msg.references) ? msg.references : [],
      sourceTag: String(msg.sourceTag || ''),
      logicFlow: String(msg.logicFlow || ''),
      analysisPlan: msg.analysisPlan && typeof msg.analysisPlan === 'object' ? msg.analysisPlan : null,
      analysisSteps: Array.isArray(msg.analysisSteps) ? msg.analysisSteps : [],
      analysisSummary: msg.analysisSummary && typeof msg.analysisSummary === 'object' ? msg.analysisSummary : null,
      toolDraft: msg.toolDraft && typeof msg.toolDraft === 'object' ? msg.toolDraft : null,
      clarify: msg.clarify && typeof msg.clarify === 'object' ? msg.clarify : null
    }
    const hasExtra = payload.references.length > 0
      || payload.sourceTag
      || payload.logicFlow
      || payload.analysisPlan
      || payload.analysisSteps.length > 0
      || payload.analysisSummary
      || payload.toolDraft
      || payload.clarify
    if (!hasExtra) return ''
    return JSON.stringify(payload)
  }

  const loadConversationListAndMessages = useCallback(async (preferConversationId = '') => {
    setConversationLoading(true)
    setConversationError('')
    try {
      let list = await fetchConversations()
      if (list.length === 0) {
        const created = await createConversation('新对话')
        if (created) {
          list = await fetchConversations()
        }
      }
      const normalizedList = list
        .map(item => ({
          id: normalizeConversationId(item),
          title: normalizeConversationTitle(item),
          createTime: item?.createTime || item?.create_time || ''
        }))
        .filter(item => item.id)
      setConversations(normalizedList)
      const preferred = normalizedList.find(item => item.id === preferConversationId)
      const current = preferred || normalizedList[0]
      if (!current) {
        conversationIdRef.current = ''
        setActiveConversationTitle('')
        setMessages([createDefaultAssistantMessage()])
        return
      }
      conversationIdRef.current = current.id
      setActiveConversationTitle(current.title || '')
      const history = await fetchConversationMessages(current.id)
      const mappedMessages = history
        .map(item => normalizeMessageFromHistory(item))
        .filter(item => item.content)
      const recoveredPlanDrafts = {}
      mappedMessages.forEach((msg, index) => {
        if (!msg.analysisPlan || typeof msg.analysisPlan !== 'object') return
        const messageId = msg.id ?? `${current.id}-history-${index}`
        msg.id = messageId
        const steps = Array.isArray(msg.analysisPlan.steps) ? msg.analysisPlan.steps.map(normalizePlanStep) : []
        const historyAnalysisSteps = Array.isArray(msg.analysisSteps) ? msg.analysisSteps : []
        recoveredPlanDrafts[messageId] = {
          planId: msg.analysisPlan.planId || msg.analysisPlan.plan_id || '',
          version: msg.analysisPlan.version || 1,
          query: msg.analysisPlan.query || '',
          deepThinking: msg.analysisPlan.deepThinking || msg.analysisPlan.deep_thinking || '',
          questionType: msg.analysisPlan.questionType || msg.analysisPlan.question_type || '',
          summary: msg.analysisPlan.summary || '',
          rerunMode: msg.analysisPlan.rerunMode || msg.analysisPlan.rerun_mode || 'AUTO',
          restartFromStep: Number(msg.analysisPlan.restartFromStep || msg.analysisPlan.restart_from_step || 1),
          adjustmentInstruction: msg.analysisPlan.adjustmentInstruction || '',
          editedSteps: steps,
          analysisSteps: historyAnalysisSteps,
          analysisSummary: msg.analysisSummary || null
        }
      })
      setPlanDrafts(recoveredPlanDrafts)
      setPlanUiStates({})
      setMessages(mappedMessages.length > 0 ? mappedMessages : [createDefaultAssistantMessage()])
    } catch (err) {
      setConversationError(err?.message || '会话加载失败')
      setMessages([createDefaultAssistantMessage()])
    } finally {
      setConversationLoading(false)
    }
  }, [createDefaultAssistantMessage, normalizeConversationId, normalizeConversationTitle, normalizeMessageFromHistory])

  useEffect(() => {
    loadConversationListAndMessages()
  }, [loadConversationListAndMessages])

  const loadToolCatalog = useCallback(async () => {
    setToolCatalogLoading(true)
    setToolCatalogError('')
    try {
      const list = await fetchToolCatalog()
      setToolCatalog(list)
    } catch (err) {
      setToolCatalog([])
      setToolCatalogError(err?.message || '加载技能目录失败')
    } finally {
      setToolCatalogLoading(false)
    }
  }, [])

  useEffect(() => {
    loadToolCatalog()
  }, [loadToolCatalog])

  const parseJsonSafe = (text, fallback = null) => {
    try {
      return JSON.parse(text)
    } catch {
      return fallback
    }
  }

  const normalizeRefs = (payload) => {
    const rawRefs = payload?.reference || payload?.data?.reference
    if (Array.isArray(rawRefs)) return rawRefs
    if (rawRefs && Array.isArray(rawRefs.chunks)) return rawRefs.chunks
    return []
  }

  const buildReferenceOnlyNotice = (refs) => {
    if (!Array.isArray(refs) || refs.length === 0) return ''
    const docNames = Array.from(new Set(
      refs
        .map((ref, idx) => ref?.document_name || ref?.doc_name || `文档${idx + 1}`)
        .filter(Boolean)
    )).slice(0, 3)
    if (docNames.length === 0) {
      return '已检索到相关资料，请查看下方引用原文。'
    }
    return `已检索到相关资料（${docNames.join('、')}），请查看下方引用原文。`
  }

  const normalizeClarifySuggestions = (payload) => {
    if (!payload || !Array.isArray(payload.suggestions)) return []
    return payload.suggestions
      .map(item => typeof item === 'string' ? item.trim() : '')
      .filter(Boolean)
      .slice(0, 3)
  }

  const normalizeLogicFlow = (payload) => {
    const flow = payload?.logicFlow || payload?.data?.logicFlow || ''
    return typeof flow === 'string' ? flow.trim() : ''
  }

  const consumeSse = async (response, onEvent) => {
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    let currentEvent = 'message'
    let dataLines = []
    const flushEvent = async () => {
      if (dataLines.length === 0) {
        currentEvent = 'message'
        return
      }
      const data = dataLines.join('\n')
      dataLines = []
      await onEvent(currentEvent || 'message', data)
      currentEvent = 'message'
    }
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''
      for (const rawLine of lines) {
        const line = rawLine.trimEnd()
        if (!line) {
          await flushEvent()
          continue
        }
        if (line.startsWith('event:')) {
          await flushEvent()
          currentEvent = line.slice(6).trim()
          continue
        }
        if (line.startsWith('data:')) {
          dataLines.push(line.slice(5).replace(/^\s/, ''))
        }
      }
    }
    if (buffer.trim()) {
      if (buffer.trimStart().startsWith('data:')) {
        dataLines.push(buffer.trimStart().slice(5).replace(/^\s/, ''))
      }
    }
    await flushEvent()
  }

  const updateStreamingMessage = (messageId, updater) => {
    setMessages(prev => prev.map(msg => {
      if (msg.id !== messageId) return msg
      return { ...msg, ...updater(msg) }
    }))
  }

  const normalizePlanStep = (step, index) => {
    const stepNo = Number(step?.stepNo ?? step?.step_no ?? index + 1)
    const route = String(step?.route || '').trim().toUpperCase()
    const label = String(step?.label || '').trim() || `步骤${stepNo}`
    return {
      stepNo,
      route,
      label,
      goal: String(step?.goal || '').trim(),
      query: String(step?.query || '').trim(),
      toolName: String(step?.toolName || step?.tool_name || '').trim(),
      continueWhen: String(step?.continueWhen || step?.continue_when || '').trim(),
      stopWhen: String(step?.stopWhen || step?.stop_when || '').trim()
    }
  }

  const reindexPlanSteps = (steps) => {
    return (steps || []).map((step, index) => ({
      ...step,
      stepNo: index + 1
    }))
  }

  const initializePlanDraft = (messageId, payload) => {
    if (!payload) return
    const steps = Array.isArray(payload.steps) ? payload.steps.map(normalizePlanStep) : []
    setPlanDrafts(prev => ({
      ...prev,
      [messageId]: {
        planId: payload.planId || payload.plan_id || '',
        version: payload.version || 1,
        query: payload.query || '',
        deepThinking: payload.deepThinking || payload.deep_thinking || '',
        questionType: payload.questionType || payload.question_type || '',
        summary: payload.summary || '',
        rerunMode: payload.rerunMode || payload.rerun_mode || 'AUTO',
        restartFromStep: Number(payload.restartFromStep || payload.restart_from_step || 1),
        adjustmentInstruction: payload.adjustmentInstruction || '',
        editedSteps: steps,
        analysisSteps: [],
        analysisSummary: null
      }
    }))
    updateStreamingMessage(messageId, old => ({
      analysisPlan: payload,
      content: old.content || ''
    }))
  }

  const updatePlanDraft = (messageId, updater) => {
    setPlanDrafts(prev => {
      const current = prev[messageId]
      if (!current) return prev
      const next = updater(current)
      return { ...prev, [messageId]: next }
    })
  }

  const updatePlanUiState = (messageId, updater) => {
    setPlanUiStates(prev => {
      const current = prev[messageId] || {
        manualExpanded: false,
        manualCollapsed: false,
        editMode: false
      }
      const next = updater(current)
      return { ...prev, [messageId]: next }
    })
  }

  const togglePlanExpanded = (messageId, msg) => {
    updatePlanUiState(messageId, current => {
      const currentlyExpanded = current.manualExpanded
        ? true
        : current.manualCollapsed
          ? false
          : !!msg?.isStreaming
      if (currentlyExpanded) {
        return { ...current, manualExpanded: false, manualCollapsed: true }
      }
      return { ...current, manualExpanded: true, manualCollapsed: false }
    })
  }

  const togglePlanEditMode = (messageId, enabled) => {
    updatePlanUiState(messageId, current => ({
      ...current,
      editMode: enabled,
      manualExpanded: true,
      manualCollapsed: false
    }))
  }

  const handlePlanStepFieldChange = (messageId, index, key, value) => {
    updatePlanDraft(messageId, current => {
      const nextSteps = (current.editedSteps || []).map((step, i) => {
        if (i !== index) return step
        return { ...step, [key]: value }
      })
      return { ...current, editedSteps: nextSteps }
    })
  }

  const handlePlanConfigChange = (messageId, key, value) => {
    updatePlanDraft(messageId, current => ({ ...current, [key]: value }))
  }

  const handlePlanStepMove = (messageId, index, direction) => {
    updatePlanDraft(messageId, current => {
      const steps = [...(current.editedSteps || [])]
      const targetIndex = index + direction
      if (targetIndex < 0 || targetIndex >= steps.length) return current
      const temp = steps[index]
      steps[index] = steps[targetIndex]
      steps[targetIndex] = temp
      return { ...current, editedSteps: reindexPlanSteps(steps) }
    })
  }

  const handlePlanStepAdd = (messageId) => {
    updatePlanDraft(messageId, current => {
      const nextSteps = [...(current.editedSteps || [])]
      nextSteps.push(normalizePlanStep({
        route: 'AUTO',
        label: '新步骤',
        goal: '',
        query: current.query || '',
        toolName: '',
        continueWhen: '',
        stopWhen: ''
      }, nextSteps.length))
      return { ...current, editedSteps: reindexPlanSteps(nextSteps) }
    })
  }

  const handlePlanStepRemove = (messageId, index) => {
    updatePlanDraft(messageId, current => {
      const steps = [...(current.editedSteps || [])]
      if (steps.length <= 1) return current
      steps.splice(index, 1)
      return { ...current, editedSteps: reindexPlanSteps(steps) }
    })
  }

  const buildPlanStreamState = (draft, msg) => {
    const executed = Array.isArray(draft?.analysisSteps) ? draft.analysisSteps : []
    const planned = Array.isArray(draft?.editedSteps) ? draft.editedSteps.length : 0
    const finishedRaw = executed.filter(step => {
      const status = String(step?.status || '').toLowerCase()
      return status === 'completed' || status === 'done'
    }).length
    const finished = planned > 0 ? Math.min(finishedRaw, planned) : finishedRaw
    const latest = executed.length > 0 ? executed[executed.length - 1] : null
    const waitingApproval = executed.some(step => String(step?.status || '').toLowerCase() === 'waiting_approval')
    if (draft?.analysisSummary || msg?.analysisSummary) {
      const total = planned || Math.max(finished, 1)
      if (waitingApproval || (planned > 0 && finished < planned)) {
        const percent = total > 0 ? Math.round((finished / total) * 100) : 20
        return {
          text: `已执行：${finished}/${total} 步（${waitingApproval ? '等待审批' : '未全部完成'}）`,
          progress: Math.max(12, Math.min(99, percent))
        }
      }
      return {
        text: `已完成：${finished}/${total} 步`,
        progress: 100
      }
    }
    if (msg?.isStreaming) {
      if (!latest) {
        return {
          text: `进行中：0/${planned || 1} 步（正在生成链路）`,
          progress: 10
        }
      }
      const latestNoRaw = Number(latest.step_no || executed.length)
      const latestNo = planned > 0 ? Math.min(Math.max(1, latestNoRaw), planned) : Math.max(1, latestNoRaw)
      const latestLabel = latest.label || latest.type || '执行步骤'
      const latestStatus = latest.status || 'streaming'
      const percentBase = planned > 0 ? Math.min(100, Math.round((latestNo / planned) * 100)) : 30
      return {
        text: `进行中：第 ${latestNo} 步 ${latestLabel}（${latestStatus}）`,
        progress: Math.max(12, percentBase)
      }
    }
    return {
      text: `待执行：0/${planned || 1} 步`,
      progress: 0
    }
  }

  const buildStepSectionHeader = (stepPayload) => {
    const stepNoRaw = Number(stepPayload?.step_no || 0)
    const stepNo = Number.isFinite(stepNoRaw) && stepNoRaw > 0 ? stepNoRaw : null
    const label = String(stepPayload?.label || stepPayload?.type || '执行步骤').trim()
    const route = String(stepPayload?.route || stepPayload?.type || '').trim()
    const query = String(stepPayload?.query || '').trim()
    const title = stepNo ? `### 第 ${stepNo} 步：${label}` : `### ${label}`
    const meta = [route ? `路由：${route}` : '', query ? `任务：${query}` : ''].filter(Boolean).join('｜')
    return meta ? `${title}\n${meta}\n\n` : `${title}\n\n`
  }

  const runWithPlanDraft = async (messageId, replanOnly) => {
    const draft = planDrafts[messageId]
    if (!draft) return
    const nextMsgId = Date.now() + Math.floor(Math.random() * 1000)
    setMessages(prev => [...prev, { role: 'assistant', content: '', id: nextMsgId, isStreaming: true }])
    setLoading(true)
    let finalAssistantContent = ''
    let pendingStepPayload = null
    const insertedStepNoSet = new Set()
    let insertedFallbackHeader = false
    const assistantPayloadState = {
      references: [],
      sourceTag: '',
      logicFlow: '',
      analysisPlan: null,
      analysisSteps: [],
      analysisSummary: null,
      toolDraft: null,
      clarify: null
    }
    try {
      const response = await startAgentStream(conversationIdRef.current, draft.query || '', {
        adjustmentInstruction: draft.adjustmentInstruction || '',
        editedSteps: draft.editedSteps || [],
        rerunMode: draft.rerunMode || 'AUTO',
        restartFromStep: draft.restartFromStep || 1,
        replanOnly
      })
      let aiContent = ''
      let hasRenderableOutput = false
      await consumeSse(response, async (eventName, dataStr) => {
        if (dataStr === '[DONE]') return
        if (eventName === 'analysis_plan') {
          hasRenderableOutput = true
          const payload = parseJsonSafe(dataStr, {}) || {}
          assistantPayloadState.analysisPlan = payload
          initializePlanDraft(nextMsgId, payload)
          return
        }
        if (eventName === 'analysis_step') {
          hasRenderableOutput = true
          const payload = parseJsonSafe(dataStr, {}) || {}
          pendingStepPayload = payload
          assistantPayloadState.analysisSteps = [...assistantPayloadState.analysisSteps, payload]
          updatePlanDraft(nextMsgId, current => ({
            ...current,
            analysisSteps: [...(current.analysisSteps || []), payload]
          }))
          updateStreamingMessage(nextMsgId, old => ({
            analysisSteps: [...(old.analysisSteps || []), payload]
          }))
          return
        }
        if (eventName === 'analysis_summary') {
          hasRenderableOutput = true
          const payload = parseJsonSafe(dataStr, {}) || {}
          assistantPayloadState.analysisSummary = payload
          updatePlanDraft(nextMsgId, current => ({ ...current, analysisSummary: payload }))
          updateStreamingMessage(nextMsgId, () => ({ analysisSummary: payload }))
          return
        }
        if (eventName === 'tool_draft') {
          hasRenderableOutput = true
          const draftPayload = parseJsonSafe(dataStr)
          if (!draftPayload) return
          assistantPayloadState.toolDraft = draftPayload
          const draftArgs = parseJsonSafe(draftPayload.draftArgs, {}) || {}
          setToolForms(prev => ({
            ...prev,
            [draftPayload.toolCallId]: {
              args: draftArgs,
              files: []
            }
          }))
          const tip = '已识别到可执行技能，请填写参数并上传文件后执行。'
          updateStreamingMessage(nextMsgId, old => {
            const base = String(old.content || aiContent || '').trim()
            const content = base.includes(tip) ? base : `${base ? `${base}\n\n` : ''}${tip}`
            finalAssistantContent = content
            return {
              content,
              toolDraft: draftPayload
            }
          })
          return
        }
        if (eventName === 'clarify') {
          hasRenderableOutput = true
          const payload = parseJsonSafe(dataStr, {}) || {}
          const question = (payload.question || '我需要你补充一下意图，才能继续。').trim()
          assistantPayloadState.clarify = {
            ...payload,
            suggestions: normalizeClarifySuggestions(payload)
          }
          finalAssistantContent = question
          updateStreamingMessage(nextMsgId, () => ({
            content: question,
            clarify: assistantPayloadState.clarify
          }))
          return
        }
        if (eventName === 'token') {
          hasRenderableOutput = true
          if (pendingStepPayload) {
            const stepNo = Number(pendingStepPayload?.step_no || 0)
            if (stepNo > 0 && !insertedStepNoSet.has(stepNo)) {
              aiContent += `${aiContent.trim() ? '\n\n' : ''}${buildStepSectionHeader(pendingStepPayload)}`
              insertedStepNoSet.add(stepNo)
            } else if (stepNo <= 0 && !insertedFallbackHeader) {
              aiContent += `${aiContent.trim() ? '\n\n' : ''}${buildStepSectionHeader(pendingStepPayload)}`
              insertedFallbackHeader = true
            }
            pendingStepPayload = null
          }
          aiContent += dataStr
          finalAssistantContent = aiContent
          updateStreamingMessage(nextMsgId, () => ({ content: aiContent }))
          return
        }
        if (eventName === 'error') {
          hasRenderableOutput = true
          updateStreamingMessage(nextMsgId, () => ({ content: `**Error**: ${dataStr}` }))
          return
        }
        if (eventName === 'message') {
          const payload = parseJsonSafe(dataStr)
          if (!payload) {
            const plainText = (dataStr || '').trim()
            if (!plainText) return
            hasRenderableOutput = true
            if (pendingStepPayload) {
              const stepNo = Number(pendingStepPayload?.step_no || 0)
              if (stepNo > 0 && !insertedStepNoSet.has(stepNo)) {
                aiContent += `${aiContent.trim() ? '\n\n' : ''}${buildStepSectionHeader(pendingStepPayload)}`
                insertedStepNoSet.add(stepNo)
              } else if (stepNo <= 0 && !insertedFallbackHeader) {
                aiContent += `${aiContent.trim() ? '\n\n' : ''}${buildStepSectionHeader(pendingStepPayload)}`
                insertedFallbackHeader = true
              }
              pendingStepPayload = null
            }
            aiContent += plainText
            finalAssistantContent = aiContent
            updateStreamingMessage(nextMsgId, () => ({ content: aiContent }))
            return
          }
          const delta = payload.answer || payload.data?.answer || ''
          const refs = normalizeRefs(payload)
          const logicFlow = normalizeLogicFlow(payload)
          const sourceTag = payload.sourceLabel || payload.source || (refs.length > 0 ? 'RAG检索' : '')
          if (refs.length > 0) {
            assistantPayloadState.references = refs
          }
          if (logicFlow) {
            assistantPayloadState.logicFlow = logicFlow
          }
          if (sourceTag) {
            assistantPayloadState.sourceTag = sourceTag
          }
          if (!delta && refs.length > 0 && !aiContent.trim()) {
            aiContent += buildReferenceOnlyNotice(refs)
          }
          if (pendingStepPayload && delta) {
            const stepNo = Number(pendingStepPayload?.step_no || 0)
            if (stepNo > 0 && !insertedStepNoSet.has(stepNo)) {
              aiContent += `${aiContent.trim() ? '\n\n' : ''}${buildStepSectionHeader(pendingStepPayload)}`
              insertedStepNoSet.add(stepNo)
            } else if (stepNo <= 0 && !insertedFallbackHeader) {
              aiContent += `${aiContent.trim() ? '\n\n' : ''}${buildStepSectionHeader(pendingStepPayload)}`
              insertedFallbackHeader = true
            }
            pendingStepPayload = null
          }
          aiContent += delta
          finalAssistantContent = aiContent
          updateStreamingMessage(nextMsgId, old => ({
            content: aiContent,
            references: refs.length > 0 ? refs : old.references,
            sourceTag: sourceTag || old.sourceTag,
            logicFlow: logicFlow || old.logicFlow
          }))
          hasRenderableOutput = true
        }
      })
      if (!hasRenderableOutput) {
        finalAssistantContent = '请求已完成，暂未返回可展示内容。'
        updateStreamingMessage(nextMsgId, () => ({ content: finalAssistantContent }))
      }
    } catch (err) {
      finalAssistantContent = `**Error**: ${err.message}`
      updateStreamingMessage(nextMsgId, () => ({ content: `**Error**: ${err.message}` }))
    } finally {
      setLoading(false)
      updateStreamingMessage(nextMsgId, () => ({ isStreaming: false }))
      if (conversationIdRef.current && finalAssistantContent.trim()) {
        try {
          const payloadText = buildMessagePayloadForSave({
            content: finalAssistantContent,
            ...assistantPayloadState
          })
          await saveConversationMessage(conversationIdRef.current, 'assistant', finalAssistantContent, activeConversationTitle, payloadText)
        } catch (persistErr) {
          console.error('保存助手消息失败:', persistErr)
        }
      }
    }
  }

  const handleClarifySuggestionClick = (text) => {
    if (!text) return
    setInput(text)
  }

  const handleManualSkillInvoke = async (tool) => {
    if (!tool?.name) return
    if (loading) return
    setConversationError('')
    setManualDraftPending(tool.name)
    try {
      if (!conversationIdRef.current) {
        await loadConversationListAndMessages()
      }
      if (!conversationIdRef.current) {
        setConversationError('当前无可用会话，请先新建会话')
        return
      }
      const conversationTitle = activeConversationTitle || String(tool?.displayName || tool.name).slice(0, 20)
      const draft = await createToolDraft(
        conversationIdRef.current,
        tool.name,
        tool.description || tool.displayName || tool.name
      )
      const draftArgs = parseJsonSafe(draft?.draftArgs, {}) || {}
      if (draft?.toolCallId) {
        setToolForms(prev => ({
          ...prev,
          [draft.toolCallId]: {
            args: draftArgs,
            files: []
          }
        }))
      }
      const assistantContent = `已选择技能：${tool.displayName || tool.name}\n请填写参数并上传文件后执行。`
      const assistantMsg = {
        role: 'assistant',
        content: assistantContent,
        id: Date.now() + Math.floor(Math.random() * 1000),
        toolDraft: draft
      }
      setMessages(prev => [...prev, assistantMsg])
      const payloadText = buildMessagePayloadForSave({
        content: assistantContent,
        toolDraft: draft
      })
      await saveConversationMessage(conversationIdRef.current, 'assistant', assistantContent, conversationTitle, payloadText)
    } catch (err) {
      setConversationError(err?.message || '创建技能草稿失败')
    } finally {
      setManualDraftPending('')
    }
  }

  const handleSend = async (presetInput) => {
    const mergedInput = typeof presetInput === 'string' ? presetInput : input
    const finalInput = String(mergedInput || '').trim()
    if (!finalInput) return

    if (loading && abortControllerRef.current) {
      abortControllerRef.current.abort()
    }

    const requestId = ++currentRequestIdRef.current
    if (!conversationIdRef.current) {
      await loadConversationListAndMessages()
    }
    if (!conversationIdRef.current) {
      setConversationError('当前无可用会话，请先新建会话')
      return
    }
    const conversationTitle = activeConversationTitle || finalInput.slice(0, 20)
    const userMsg = { role: 'user', content: finalInput }
    setMessages(prev => [...prev, userMsg])
    setInput('')
    setLoading(true)
    try {
      await saveConversationMessage(conversationIdRef.current, 'user', finalInput, conversationTitle)
    } catch (persistErr) {
      console.error('保存用户消息失败:', persistErr)
    }

    const aiMsgId = Date.now()
    setMessages(prev => [...prev, { role: 'assistant', content: '', id: aiMsgId, isStreaming: true }])
    abortControllerRef.current = new AbortController()
    let finalAssistantContent = ''
    let pendingStepPayload = null
    const insertedStepNoSet = new Set()
    let insertedFallbackHeader = false
    const assistantPayloadState = {
      references: [],
      sourceTag: '',
      logicFlow: '',
      analysisPlan: null,
      analysisSteps: [],
      analysisSummary: null,
      toolDraft: null,
      clarify: null
    }

    try {
      const response = await startAgentStream(conversationIdRef.current, userMsg.content)
      let aiContent = ''
      let hasRenderableOutput = false

      await consumeSse(response, async (eventName, dataStr) => {
        if (dataStr === '[DONE]') return
        if (eventName === 'analysis_plan') {
          hasRenderableOutput = true
          const payload = parseJsonSafe(dataStr, {}) || {}
          assistantPayloadState.analysisPlan = payload
          initializePlanDraft(aiMsgId, payload)
          return
        }
        if (eventName === 'analysis_step') {
          hasRenderableOutput = true
          const payload = parseJsonSafe(dataStr, {}) || {}
          pendingStepPayload = payload
          assistantPayloadState.analysisSteps = [...assistantPayloadState.analysisSteps, payload]
          updatePlanDraft(aiMsgId, current => ({
            ...current,
            analysisSteps: [...(current.analysisSteps || []), payload]
          }))
          updateStreamingMessage(aiMsgId, old => ({
            analysisSteps: [...(old.analysisSteps || []), payload]
          }))
          return
        }
        if (eventName === 'analysis_summary') {
          hasRenderableOutput = true
          const payload = parseJsonSafe(dataStr, {}) || {}
          assistantPayloadState.analysisSummary = payload
          updatePlanDraft(aiMsgId, current => ({ ...current, analysisSummary: payload }))
          updateStreamingMessage(aiMsgId, () => ({ analysisSummary: payload }))
          return
        }
        if (eventName === 'token') {
          hasRenderableOutput = true
          if (pendingStepPayload) {
            const stepNo = Number(pendingStepPayload?.step_no || 0)
            if (stepNo > 0 && !insertedStepNoSet.has(stepNo)) {
              aiContent += `${aiContent.trim() ? '\n\n' : ''}${buildStepSectionHeader(pendingStepPayload)}`
              insertedStepNoSet.add(stepNo)
            } else if (stepNo <= 0 && !insertedFallbackHeader) {
              aiContent += `${aiContent.trim() ? '\n\n' : ''}${buildStepSectionHeader(pendingStepPayload)}`
              insertedFallbackHeader = true
            }
            pendingStepPayload = null
          }
          aiContent += dataStr
          finalAssistantContent = aiContent
          updateStreamingMessage(aiMsgId, () => ({ content: aiContent }))
          return
        }
        if (eventName === 'tool_draft') {
          hasRenderableOutput = true
          const draft = parseJsonSafe(dataStr)
          if (!draft) return
          assistantPayloadState.toolDraft = draft
          const draftArgs = parseJsonSafe(draft.draftArgs, {}) || {}
          setToolForms(prev => ({
            ...prev,
            [draft.toolCallId]: {
              args: draftArgs,
              files: []
            }
          }))
          const tip = '已识别到可执行技能，请填写参数并上传文件后执行。'
          updateStreamingMessage(aiMsgId, old => {
            const base = String(old.content || aiContent || '').trim()
            const content = base.includes(tip) ? base : `${base ? `${base}\n\n` : ''}${tip}`
            finalAssistantContent = content
            return {
              content,
              toolDraft: draft
            }
          })
          return
        }
        if (eventName === 'clarify') {
          hasRenderableOutput = true
          const payload = parseJsonSafe(dataStr, {}) || {}
          const question = (payload.question || '我需要你补充一下意图，才能继续。').trim()
          assistantPayloadState.clarify = {
            ...payload,
            suggestions: normalizeClarifySuggestions(payload)
          }
          updateStreamingMessage(aiMsgId, () => ({
            content: question,
            clarify: assistantPayloadState.clarify
          }))
          return
        }
        if (eventName === 'error') {
          hasRenderableOutput = true
          updateStreamingMessage(aiMsgId, () => ({ content: `**Error**: ${dataStr}` }))
          return
        }
        if (eventName === 'message') {
          const payload = parseJsonSafe(dataStr)
          if (!payload) {
            const plainText = (dataStr || '').trim()
            if (!plainText) return
            hasRenderableOutput = true
            if (pendingStepPayload) {
              const stepNo = Number(pendingStepPayload?.step_no || 0)
              if (stepNo > 0 && !insertedStepNoSet.has(stepNo)) {
                aiContent += `${aiContent.trim() ? '\n\n' : ''}${buildStepSectionHeader(pendingStepPayload)}`
                insertedStepNoSet.add(stepNo)
              } else if (stepNo <= 0 && !insertedFallbackHeader) {
                aiContent += `${aiContent.trim() ? '\n\n' : ''}${buildStepSectionHeader(pendingStepPayload)}`
                insertedFallbackHeader = true
              }
              pendingStepPayload = null
            }
            aiContent += plainText
            finalAssistantContent = aiContent
            updateStreamingMessage(aiMsgId, () => ({ content: aiContent }))
            return
          }
          const delta = payload.answer || payload.data?.answer || ''
          const refs = normalizeRefs(payload)
          const logicFlow = normalizeLogicFlow(payload)
          const sourceTag = payload.sourceLabel || payload.source || (refs.length > 0 ? 'RAG检索' : '')
          if (refs.length > 0) {
            assistantPayloadState.references = refs
          }
          if (logicFlow) {
            assistantPayloadState.logicFlow = logicFlow
          }
          if (sourceTag) {
            assistantPayloadState.sourceTag = sourceTag
          }
          if (delta || refs.length > 0 || logicFlow) {
            hasRenderableOutput = true
          }
          if (!delta && refs.length > 0 && !aiContent.trim()) {
            aiContent += buildReferenceOnlyNotice(refs)
          }
          if (pendingStepPayload && delta) {
            const stepNo = Number(pendingStepPayload?.step_no || 0)
            if (stepNo > 0 && !insertedStepNoSet.has(stepNo)) {
              aiContent += `${aiContent.trim() ? '\n\n' : ''}${buildStepSectionHeader(pendingStepPayload)}`
              insertedStepNoSet.add(stepNo)
            } else if (stepNo <= 0 && !insertedFallbackHeader) {
              aiContent += `${aiContent.trim() ? '\n\n' : ''}${buildStepSectionHeader(pendingStepPayload)}`
              insertedFallbackHeader = true
            }
            pendingStepPayload = null
          }
          aiContent += delta
          finalAssistantContent = aiContent
          updateStreamingMessage(aiMsgId, old => ({
            content: aiContent,
            references: refs.length > 0 ? refs : old.references,
            sourceTag: sourceTag || old.sourceTag,
            logicFlow: logicFlow || old.logicFlow
          }))
        }
      })
      if (!hasRenderableOutput) {
        finalAssistantContent = '请求已完成，暂未返回可展示内容。'
        updateStreamingMessage(aiMsgId, () => ({ content: '请求已完成，暂未返回可展示内容。' }))
      }
    } catch (err) {
      if (err.name === 'AbortError') {
        return
      }
      finalAssistantContent = `**Error**: ${err.message}`
      updateStreamingMessage(aiMsgId, () => ({ content: `**Error**: ${err.message}` }))
    } finally {
      if (currentRequestIdRef.current === requestId) {
        setLoading(false)
      }
      updateStreamingMessage(aiMsgId, () => ({ isStreaming: false }))
      if (conversationIdRef.current && finalAssistantContent.trim()) {
        try {
          const payloadText = buildMessagePayloadForSave({
            content: finalAssistantContent,
            ...assistantPayloadState
          })
          await saveConversationMessage(conversationIdRef.current, 'assistant', finalAssistantContent, conversationTitle, payloadText)
        } catch (persistErr) {
          console.error('保存助手消息失败:', persistErr)
        }
      }
    }
  }

  const handleCreateConversation = async () => {
    if (conversationLoading || conversationActionLoading) return
    setConversationLoading(true)
    setConversationError('')
    try {
      const created = await createConversation('新对话')
      const nextConversationId = normalizeConversationId(created)
      await loadConversationListAndMessages(nextConversationId)
    } catch (err) {
      setConversationError(err?.message || '新建会话失败')
      setConversationLoading(false)
    }
  }

  const handleSwitchConversation = async (targetConversationId) => {
    if (!targetConversationId || targetConversationId === conversationIdRef.current) return
    if (abortControllerRef.current) {
      abortControllerRef.current.abort()
    }
    await loadConversationListAndMessages(targetConversationId)
  }

  const handleStopGeneration = () => {
    if (!loading || !abortControllerRef.current) return
    abortControllerRef.current.abort()
    setLoading(false)
  }

  const handleStartRenameConversation = (item) => {
    if (!item?.id) return
    setRenamingConversationId(item.id)
    setRenamingTitle(item.title || '')
  }

  const handleCancelRenameConversation = () => {
    setRenamingConversationId('')
    setRenamingTitle('')
  }

  const handleSubmitRenameConversation = async (conversationId) => {
    const nextTitle = String(renamingTitle || '').trim()
    if (!conversationId || !nextTitle) {
      setConversationError('会话名称不能为空')
      return
    }
    setConversationActionLoading(true)
    setConversationError('')
    try {
      await renameConversation(conversationId, nextTitle)
      setRenamingConversationId('')
      setRenamingTitle('')
      await loadConversationListAndMessages(conversationId)
    } catch (err) {
      setConversationError(err?.message || '重命名会话失败')
    } finally {
      setConversationActionLoading(false)
    }
  }

  const handleDeleteConversation = async (conversationId) => {
    if (!conversationId || conversationActionLoading) return
    if (!window.confirm('确定删除该会话及全部消息吗？')) return
    setConversationActionLoading(true)
    setConversationError('')
    try {
      await deleteConversation(conversationId)
      const rest = conversations.filter(item => item.id !== conversationId)
      const nextConversationId = rest[0]?.id || ''
      await loadConversationListAndMessages(nextConversationId)
    } catch (err) {
      setConversationError(err?.message || '删除会话失败')
    } finally {
      setConversationActionLoading(false)
    }
  }

  const handleToolArgChange = (toolCallId, key, value) => {
    setToolForms(prev => ({
      ...prev,
      [toolCallId]: {
        ...(prev[toolCallId] || { args: {}, files: [] }),
        args: {
          ...((prev[toolCallId] && prev[toolCallId].args) || {}),
          [key]: value
        }
      }
    }))
  }

  const handleToolFileChange = (toolCallId, files) => {
    setToolForms(prev => ({
      ...prev,
      [toolCallId]: {
        ...(prev[toolCallId] || { args: {}, files: [] }),
        files: Array.from(files || [])
      }
    }))
  }

  const handleApproveTool = async (toolDraft) => {
    const toolCallId = toolDraft.toolCallId
    const form = toolForms[toolCallId] || { args: {}, files: [] }
    const files = form.files || []
    const args = form.args || {}
    if (toolDraft.toolSpec?.upload_required && files.length === 0) {
      alert('该技能需要先上传文件')
      return
    }
    setToolPending(prev => ({ ...prev, [toolCallId]: true }))
    const aiMsgId = Date.now() + Math.floor(Math.random() * 1000)
    setMessages(prev => [...prev, { role: 'assistant', content: '', id: aiMsgId, isStreaming: true }])
    let finalAssistantContent = ''
    const assistantPayloadState = {
      references: [],
      sourceTag: '',
      logicFlow: ''
    }
    try {
      for (const file of files) {
        await uploadToolInputFile(toolCallId, file)
      }
      const response = await approveToolCall(
        conversationIdRef.current,
        toolCallId,
        JSON.stringify(args)
      )
      let aiContent = ''
      let latestToolResult = null
      await consumeSse(response, async (eventName, dataStr) => {
        if (dataStr === '[DONE]') return
        if (eventName === 'tool_result') {
          const result = parseJsonSafe(dataStr)
          if (result) {
            latestToolResult = result
            setToolResults(prev => ({ ...prev, [toolCallId]: result }))
          }
          return
        }
        if (eventName === 'token') {
          aiContent += dataStr
          finalAssistantContent = aiContent
          updateStreamingMessage(aiMsgId, () => ({ content: aiContent }))
          return
        }
        if (eventName === 'error') {
          finalAssistantContent = `**Error**: ${dataStr}`
          updateStreamingMessage(aiMsgId, () => ({ content: `**Error**: ${dataStr}` }))
          return
        }
        if (eventName === 'message') {
          const payload = parseJsonSafe(dataStr)
          if (!payload) {
            const plainText = (dataStr || '').trim()
            if (!plainText) return
            aiContent += plainText
            finalAssistantContent = aiContent
            updateStreamingMessage(aiMsgId, () => ({ content: aiContent }))
            return
          }
          const delta = payload.answer || payload.data?.answer || ''
          const refs = normalizeRefs(payload)
          const logicFlow = normalizeLogicFlow(payload)
          const sourceTag = payload.sourceLabel || payload.source || (refs.length > 0 ? 'RAG检索' : '')
          if (refs.length > 0) {
            assistantPayloadState.references = refs
          }
          if (logicFlow) {
            assistantPayloadState.logicFlow = logicFlow
          }
          if (sourceTag) {
            assistantPayloadState.sourceTag = sourceTag
          }
          if (!delta && refs.length > 0 && !aiContent.trim()) {
            aiContent += buildReferenceOnlyNotice(refs)
          }
          aiContent += delta
          finalAssistantContent = aiContent
          updateStreamingMessage(aiMsgId, old => ({
            content: aiContent,
            references: refs.length > 0 ? refs : old.references,
            sourceTag: sourceTag || old.sourceTag,
            logicFlow: logicFlow || old.logicFlow
          }))
        }
      })
      if (!aiContent) {
        finalAssistantContent = latestToolResult?.summary || '工具执行完成。'
        updateStreamingMessage(aiMsgId, () => ({ content: finalAssistantContent }))
      }
    } catch (err) {
      finalAssistantContent = `**Error**: ${err.message}`
      updateStreamingMessage(aiMsgId, () => ({ content: `**Error**: ${err.message}` }))
    } finally {
      setToolPending(prev => ({ ...prev, [toolCallId]: false }))
      updateStreamingMessage(aiMsgId, () => ({ isStreaming: false }))
      if (conversationIdRef.current && finalAssistantContent.trim()) {
        try {
          const payloadText = buildMessagePayloadForSave({
            content: finalAssistantContent,
            ...assistantPayloadState
          })
          await saveConversationMessage(conversationIdRef.current, 'assistant', finalAssistantContent, activeConversationTitle, payloadText)
        } catch (persistErr) {
          console.error('保存助手消息失败:', persistErr)
        }
      }
    }
  }

  return (
    <div className="flex flex-1 h-full overflow-hidden bg-slate-50 relative">
      <div className="w-72 h-full border-r border-slate-200 bg-white flex flex-col shrink-0">
        <div className="p-3 border-b border-slate-100">
          <button
            onClick={handleCreateConversation}
            disabled={conversationLoading || conversationActionLoading}
            className="w-full inline-flex items-center justify-center gap-1 px-3 py-2 rounded-lg bg-blue-600 text-white text-sm hover:bg-blue-700 disabled:opacity-50"
          >
            <Plus size={14} />
            新建对话
          </button>
        </div>
        <div className="flex-1 overflow-y-auto p-2 space-y-1">
          {conversations.map((item) => {
            const active = item.id === conversationIdRef.current
            const renaming = renamingConversationId === item.id
            return (
              <div key={item.id} className={cn("rounded-lg border", active ? "border-blue-200 bg-blue-50" : "border-transparent hover:border-slate-200 hover:bg-slate-50")}>
                {renaming ? (
                  <div className="p-2 space-y-2">
                    <input
                      value={renamingTitle}
                      onChange={(e) => setRenamingTitle(e.target.value)}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') {
                          e.preventDefault()
                          handleSubmitRenameConversation(item.id)
                        }
                        if (e.key === 'Escape') {
                          e.preventDefault()
                          handleCancelRenameConversation()
                        }
                      }}
                      maxLength={120}
                      className="w-full px-2 py-1.5 text-sm rounded border border-slate-300 bg-white focus:outline-none focus:ring-2 focus:ring-blue-500"
                    />
                    <div className="flex items-center justify-end gap-1">
                      <button
                        onClick={handleCancelRenameConversation}
                        className="px-2 py-1 text-xs rounded border border-slate-300 text-slate-600 hover:bg-slate-100"
                      >
                        取消
                      </button>
                      <button
                        onClick={() => handleSubmitRenameConversation(item.id)}
                        disabled={conversationActionLoading}
                        className="px-2 py-1 text-xs rounded bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-50"
                      >
                        保存
                      </button>
                    </div>
                  </div>
                ) : (
                  <div className="flex items-center gap-1 p-2">
                    <button
                      onClick={() => handleSwitchConversation(item.id)}
                      className={cn("flex-1 min-w-0 text-left text-sm truncate", active ? "text-blue-700 font-medium" : "text-slate-700")}
                    >
                      {item.title || '未命名会话'}
                    </button>
                    <button
                      onClick={() => handleStartRenameConversation(item)}
                      disabled={conversationActionLoading}
                      className="p-1.5 rounded text-slate-500 hover:bg-slate-200 hover:text-slate-700 disabled:opacity-50"
                    >
                      <Edit size={14} />
                    </button>
                    <button
                      onClick={() => handleDeleteConversation(item.id)}
                      disabled={conversationActionLoading}
                      className="p-1.5 rounded text-rose-500 hover:bg-rose-100 hover:text-rose-700 disabled:opacity-50"
                    >
                      <Trash2 size={14} />
                    </button>
                  </div>
                )}
              </div>
            )
          })}
        </div>
        <div className="border-t border-slate-100 p-2">
          <div className="flex items-center justify-between px-1 mb-2">
            <div className="text-xs font-semibold text-slate-600">技能快捷调用</div>
            <button
              onClick={loadToolCatalog}
              disabled={toolCatalogLoading}
              className="p-1 rounded text-slate-500 hover:bg-slate-100 disabled:opacity-50"
              title="刷新技能目录"
            >
              <RefreshCw size={12} className={toolCatalogLoading ? 'animate-spin' : ''} />
            </button>
          </div>
          {toolCatalogError && (
            <div className="px-2 py-1.5 text-[11px] text-rose-600 bg-rose-50 border border-rose-100 rounded mb-2">
              {toolCatalogError}
            </div>
          )}
          <div className="max-h-52 overflow-y-auto space-y-1">
            {toolCatalogLoading && (
              <div className="px-2 py-2 text-[11px] text-slate-400 flex items-center gap-1">
                <Loader2 size={12} className="animate-spin" />
                加载中...
              </div>
            )}
            {!toolCatalogLoading && toolCatalog.length === 0 && (
              <div className="px-2 py-2 text-[11px] text-slate-400">暂无可用技能</div>
            )}
            {!toolCatalogLoading && toolCatalog.map((tool) => (
              <button
                key={tool.name}
                onClick={() => handleManualSkillInvoke(tool)}
                disabled={loading || manualDraftPending === tool.name}
                className="w-full text-left px-2 py-1.5 rounded border border-slate-200 hover:border-blue-300 hover:bg-blue-50 disabled:opacity-50"
              >
                <div className="text-xs font-medium text-slate-700 truncate">
                  {tool.displayName || tool.name}
                </div>
                <div className="text-[10px] text-slate-500 truncate">
                  {manualDraftPending === tool.name ? '创建草稿中...' : (tool.description || tool.name)}
                </div>
              </button>
            ))}
          </div>
        </div>
      </div>

      <div className="flex-1 flex flex-col h-full shadow-sm bg-white">
        <div className="p-4 border-b bg-white/80 backdrop-blur z-10 sticky top-0">
          <div className="flex items-center justify-between gap-3">
            <h2 className="font-semibold text-slate-800 flex items-center gap-2">
              <Bot size={20} className="text-blue-500" />
              智能问答助手
            </h2>
            <div className="text-xs text-slate-500">
              {activeConversationTitle || '未命名会话'}
            </div>
          </div>
          {conversationError && (
            <div className="mt-2 text-xs text-rose-600">{conversationError}</div>
          )}
        </div>

        <div className="flex-1 overflow-y-auto p-4 space-y-6 scroll-smooth">
          {messages.map((msg, idx) => (
            <div key={idx} className={cn(
              "flex gap-4",
              msg.role === 'user' ? "flex-row-reverse" : ""
            )}>
              <div className={cn(
                "w-8 h-8 rounded-full flex items-center justify-center shrink-0 shadow-sm",
                msg.role === 'user' ? "bg-blue-600 text-white" : "bg-emerald-500 text-white"
              )}>
                {msg.role === 'user' ? <User size={16} /> : <Bot size={16} />}
              </div>
              <div className={cn(
                "px-5 py-3 rounded-2xl max-w-[85%] text-sm leading-relaxed shadow-sm",
                msg.role === 'user' 
                  ? "bg-blue-600 text-white rounded-tr-sm" 
                  : "bg-white border border-slate-100 text-slate-700 rounded-tl-sm"
              )}>
                {msg.toolDraft && (
                  <div className="mb-3 rounded-xl border border-blue-200 bg-blue-50 p-3">
                    <div className="text-xs text-blue-700 font-semibold mb-2">
                      已识别技能：{msg.toolDraft.toolName}
                    </div>
                    <div className="text-xs text-slate-600 mb-3">
                      {msg.toolDraft.toolSpec?.description || '请填写参数并执行'}
                    </div>
                    {msg.toolDraft.toolSpec?.parameters_schema?.properties && (
                      <div className="grid grid-cols-1 md:grid-cols-2 gap-2 mb-3">
                        {Object.keys(msg.toolDraft.toolSpec.parameters_schema.properties).map((key) => (
                          <input
                            key={key}
                            value={toolForms[msg.toolDraft.toolCallId]?.args?.[key] || ''}
                            onChange={(e) => handleToolArgChange(msg.toolDraft.toolCallId, key, e.target.value)}
                            placeholder={key}
                            className="px-2 py-1.5 rounded border border-slate-300 text-xs bg-white"
                          />
                        ))}
                      </div>
                    )}
                    {msg.toolDraft.toolSpec?.upload_required && (
                      <div className="mb-3">
                        <div className="text-[11px] text-slate-500 mb-1">
                          支持文件：{(msg.toolDraft.toolSpec.accepted_file_types || []).join(', ') || '不限'}
                        </div>
                        <input
                          type="file"
                          multiple
                          onChange={(e) => handleToolFileChange(msg.toolDraft.toolCallId, e.target.files)}
                          className="text-xs"
                        />
                        {toolForms[msg.toolDraft.toolCallId]?.files?.length > 0 && (
                          <div className="mt-1 text-[11px] text-slate-600">
                            已选择 {toolForms[msg.toolDraft.toolCallId].files.length} 个文件
                          </div>
                        )}
                      </div>
                    )}
                    <button
                      onClick={() => handleApproveTool(msg.toolDraft)}
                      disabled={!!toolPending[msg.toolDraft.toolCallId]}
                      className="px-3 py-1.5 rounded bg-blue-600 text-white text-xs hover:bg-blue-700 disabled:opacity-50"
                    >
                      {toolPending[msg.toolDraft.toolCallId] ? '执行中...' : '执行技能'}
                    </button>
                    {toolResults[msg.toolDraft.toolCallId] && (
                      <div className="mt-3 rounded-lg border border-emerald-200 bg-emerald-50 p-2">
                        <div className="text-xs font-semibold text-emerald-700">
                          {toolResults[msg.toolDraft.toolCallId].summary || '执行完成'}
                        </div>
                        {toolResults[msg.toolDraft.toolCallId].error_message && (
                          <div className="text-xs text-rose-600 mt-1">
                            {toolResults[msg.toolDraft.toolCallId].error_message}
                          </div>
                        )}
                        {toolResults[msg.toolDraft.toolCallId].files && toolResults[msg.toolDraft.toolCallId].files.length > 0 && (
                          <div className="mt-2 space-y-1">
                            {toolResults[msg.toolDraft.toolCallId].files.map((f) => (
                              <a
                                key={f.file_id}
                                href={f.download_url}
                                target="_blank"
                                rel="noreferrer"
                                className="flex items-center gap-1 text-xs text-blue-700 hover:underline"
                              >
                                <Download size={12} />
                                <span>{f.file_name}</span>
                              </a>
                            ))}
                          </div>
                        )}
                      </div>
                    )}
                  </div>
                )}
                {msg.clarify && (
                  <div className="mb-3 rounded-xl border border-amber-200 bg-amber-50 p-3">
                    <div className="text-xs text-amber-700 font-semibold mb-2">
                      需要补充意图
                    </div>
                    <div className="text-xs text-slate-700 mb-2">
                      {msg.clarify.question || '请补充你的目标，我再继续执行。'}
                    </div>
                    {Array.isArray(msg.clarify.suggestions) && msg.clarify.suggestions.length > 0 && (
                      <div className="flex flex-wrap gap-2">
                        {msg.clarify.suggestions.map((item, i) => (
                          <button
                            key={`${item}-${i}`}
                            onClick={() => handleClarifySuggestionClick(item)}
                            className="px-2.5 py-1 rounded-full bg-white border border-amber-300 text-[11px] text-amber-800 hover:bg-amber-100"
                          >
                            {item}
                          </button>
                        ))}
                      </div>
                    )}
                  </div>
                )}
                {planDrafts[msg.id] && (
                  <div className="mb-3 rounded-xl border border-slate-200 bg-slate-50 p-3 space-y-3">
                    {(() => {
                      const draft = planDrafts[msg.id]
                      const uiState = planUiStates[msg.id] || {}
                      const expanded = uiState.manualExpanded
                        ? true
                        : uiState.manualCollapsed
                          ? false
                          : !!msg.isStreaming
                      const editMode = !!uiState.editMode
                      const streamState = buildPlanStreamState(draft, msg)
                      const analysisStatusByNo = (draft.analysisSteps || []).reduce((acc, step) => {
                        const no = Number(step?.step_no || 0)
                        if (no > 0) acc[no] = step?.status || 'streaming'
                        return acc
                      }, {})
                      const plannedSteps = Array.isArray(draft.editedSteps) ? draft.editedSteps : []
                      const normalizedStatuses = plannedSteps.map((step, sIdx) => String(analysisStatusByNo[step.stepNo || sIdx + 1] || 'pending').toLowerCase())
                      const plannedCount = plannedSteps.length
                      const executedCount = normalizedStatuses.filter(status => status !== 'pending').length
                      const completedCount = normalizedStatuses.filter(status => status === 'completed' || status === 'done').length
                      const hasWaitingApproval = normalizedStatuses.includes('waiting_approval')
                      const hasHardPending = normalizedStatuses.some(status => status === 'pending' || status === 'streaming')
                      const summaryFallbackText = plannedCount <= 0
                        ? '已输出阶段汇总'
                        : hasHardPending
                          ? `已按顺序执行 ${executedCount}/${plannedCount} 步，正在继续执行后续步骤。`
                          : hasWaitingApproval
                            ? `已按顺序执行 ${executedCount}/${plannedCount} 步，工具步骤待审批，阶段汇总已生成。`
                            : completedCount >= plannedCount
                              ? `已按顺序执行 ${completedCount}/${plannedCount} 步，最终汇总已生成。`
                              : `已按顺序执行 ${executedCount}/${plannedCount} 步，阶段汇总已生成。`
                      return (
                        <>
                          <div className="flex items-center justify-between">
                            <div className="text-xs font-semibold text-slate-700">分析链路总览</div>
                            <button
                              onClick={() => togglePlanExpanded(msg.id, msg)}
                              className="text-[11px] text-slate-600 hover:text-slate-800"
                            >
                              {expanded ? '收起' : '展开'}
                            </button>
                          </div>
                          {expanded && (
                            <>
                              <div className="space-y-1">
                                <div className="text-xs text-slate-700">{streamState.text}</div>
                                <div className="h-1.5 w-full rounded bg-slate-200 overflow-hidden">
                                  <div
                                    className="h-full rounded bg-blue-500 transition-all duration-300"
                                    style={{ width: `${streamState.progress}%` }}
                                  />
                                </div>
                              </div>
                              <div className="text-xs text-slate-600 whitespace-pre-wrap">
                                深度思考：{draft.deepThinking || '暂无'}
                              </div>
                              {!editMode && (
                                <div className="space-y-1">
                                  {(draft.editedSteps || []).map((step, sIdx) => (
                                    <div key={`${msg.id}-step-text-${sIdx}`} className="text-xs text-slate-700">
                                      {`${step.stepNo || sIdx + 1}. [${step.route || 'AUTO'}] ${step.label || '步骤'}：${step.goal || '无目标'}${step.query ? `（查询：${step.query}）` : ''}${step.toolName ? `（工具：${step.toolName}）` : ''}（状态：${analysisStatusByNo[step.stepNo || sIdx + 1] || 'pending'}）`}
                                    </div>
                                  ))}
                                </div>
                              )}
                              {editMode && (
                                <>
                                  <div>
                                    <button
                                      onClick={() => handlePlanStepAdd(msg.id)}
                                      className="px-2.5 py-1 rounded bg-emerald-600 text-white text-xs hover:bg-emerald-700"
                                    >
                                      新增步骤
                                    </button>
                                  </div>
                                  <div className="space-y-2">
                                    {(draft.editedSteps || []).map((step, sIdx) => (
                                      <div key={`${msg.id}-step-${sIdx}`} className="rounded-lg border border-slate-200 bg-white p-2">
                                        <div className="flex items-center justify-between mb-1">
                                          <div className="text-xs font-semibold text-slate-700">步骤 {step.stepNo}</div>
                                          <div className="text-[10px] px-2 py-0.5 rounded-full border border-blue-200 bg-blue-50 text-blue-700">
                                            {step.route || 'AUTO'}
                                          </div>
                                        </div>
                                        <input
                                          value={step.label}
                                          onChange={(e) => handlePlanStepFieldChange(msg.id, sIdx, 'label', e.target.value)}
                                          className="w-full mb-1 px-2 py-1 rounded border border-slate-300 text-xs"
                                          placeholder="步骤标签"
                                        />
                                        <textarea
                                          value={step.goal}
                                          onChange={(e) => handlePlanStepFieldChange(msg.id, sIdx, 'goal', e.target.value)}
                                          className="w-full mb-1 px-2 py-1 rounded border border-slate-300 text-xs h-14 resize-none"
                                          placeholder="步骤目标"
                                        />
                                        <input
                                          value={step.query}
                                          onChange={(e) => handlePlanStepFieldChange(msg.id, sIdx, 'query', e.target.value)}
                                          className="w-full mb-1 px-2 py-1 rounded border border-slate-300 text-xs"
                                          placeholder="步骤查询词"
                                        />
                                        <input
                                          value={step.toolName}
                                          onChange={(e) => handlePlanStepFieldChange(msg.id, sIdx, 'toolName', e.target.value)}
                                          className="w-full px-2 py-1 rounded border border-slate-300 text-xs"
                                          placeholder="工具名（可选）"
                                        />
                                        <div className="mt-2 flex gap-2 flex-wrap">
                                          <button
                                            onClick={() => handlePlanStepMove(msg.id, sIdx, -1)}
                                            disabled={sIdx === 0}
                                            className="px-2 py-1 rounded bg-slate-100 text-slate-700 text-xs hover:bg-slate-200 disabled:opacity-40"
                                          >
                                            上移
                                          </button>
                                          <button
                                            onClick={() => handlePlanStepMove(msg.id, sIdx, 1)}
                                            disabled={sIdx === (draft.editedSteps || []).length - 1}
                                            className="px-2 py-1 rounded bg-slate-100 text-slate-700 text-xs hover:bg-slate-200 disabled:opacity-40"
                                          >
                                            下移
                                          </button>
                                          <button
                                            onClick={() => handlePlanStepRemove(msg.id, sIdx)}
                                            disabled={(draft.editedSteps || []).length <= 1}
                                            className="px-2 py-1 rounded bg-rose-100 text-rose-700 text-xs hover:bg-rose-200 disabled:opacity-40"
                                          >
                                            删除
                                          </button>
                                        </div>
                                      </div>
                                    ))}
                                  </div>
                                  <div className="grid grid-cols-1 md:grid-cols-3 gap-2">
                                    <select
                                      value={draft.rerunMode || 'AUTO'}
                                      onChange={(e) => handlePlanConfigChange(msg.id, 'rerunMode', e.target.value)}
                                      className="px-2 py-1 rounded border border-slate-300 text-xs bg-white"
                                    >
                                      <option value="AUTO">AUTO</option>
                                      <option value="PARTIAL_RERUN">PARTIAL_RERUN</option>
                                      <option value="FULL_RERUN">FULL_RERUN</option>
                                    </select>
                                    <input
                                      type="number"
                                      min="1"
                                      value={draft.restartFromStep || 1}
                                      onChange={(e) => handlePlanConfigChange(msg.id, 'restartFromStep', Number(e.target.value) || 1)}
                                      className="px-2 py-1 rounded border border-slate-300 text-xs"
                                      placeholder="从第几步开始"
                                    />
                                    <input
                                      value={draft.adjustmentInstruction || ''}
                                      onChange={(e) => handlePlanConfigChange(msg.id, 'adjustmentInstruction', e.target.value)}
                                      className="px-2 py-1 rounded border border-slate-300 text-xs"
                                      placeholder="人工修改说明"
                                    />
                                  </div>
                                </>
                              )}
                              <div className="flex gap-2 flex-wrap">
                                {!editMode ? (
                                  <button
                                    onClick={() => togglePlanEditMode(msg.id, true)}
                                    className="px-3 py-1.5 rounded bg-slate-700 text-white text-xs hover:bg-slate-800"
                                  >
                                    我要修改计划
                                  </button>
                                ) : (
                                  <button
                                    onClick={() => togglePlanEditMode(msg.id, false)}
                                    className="px-3 py-1.5 rounded bg-slate-200 text-slate-700 text-xs hover:bg-slate-300"
                                  >
                                    退出编辑
                                  </button>
                                )}
                                <button
                                  onClick={() => runWithPlanDraft(msg.id, true)}
                                  disabled={loading}
                                  className="px-3 py-1.5 rounded bg-slate-700 text-white text-xs hover:bg-slate-800 disabled:opacity-50"
                                >
                                  仅重规划
                                </button>
                                <button
                                  onClick={() => runWithPlanDraft(msg.id, false)}
                                  disabled={loading}
                                  className="px-3 py-1.5 rounded bg-blue-600 text-white text-xs hover:bg-blue-700 disabled:opacity-50"
                                >
                                  按新计划执行
                                </button>
                              </div>
                              {msg.analysisSummary && (
                                <div className="text-xs text-emerald-800 whitespace-pre-wrap">
                                  总结：{msg.analysisSummary.final_answer || summaryFallbackText}
                                </div>
                              )}
                            </>
                          )}
                        </>
                      )
                    })()}
                  </div>
                )}
                {(() => {
                  let rawContent = msg.content || '';
                  rawContent = rawContent.replace(/[\r\n]+(?=\s*\[(?:ID:\s*)?\d+\])/g, ' ');

                  let thought = null;
                  let answer = rawContent;
                  const start = answer.indexOf('<think>');
                  const end = answer.indexOf('</think>');
                  if (start !== -1 && end > start) {
                    thought = answer.substring(start + 7, end);
                    answer = answer.substring(0, start) + answer.substring(end + 8);
                  } else if (start !== -1 && msg.isStreaming) {
                    thought = answer.substring(start + 7);
                    answer = answer.substring(0, start);
                  } else if (start !== -1) {
                    answer = answer.replace('<think>', '')
                  } else if (end !== -1) {
                    answer = answer.replace('</think>', '')
                  }
                  answer = answer.replace(/<\/?think>/g, '')

                  return (
                    <>
                      {msg.sourceTag && (
                        <div className="mb-2 inline-flex px-2 py-0.5 rounded-full bg-indigo-50 border border-indigo-200 text-[11px] text-indigo-700">
                          来源：{msg.sourceTag}
                        </div>
                      )}
                      {msg.logicFlow && (
                        <div className="mb-3 rounded-lg border border-slate-200 bg-slate-50 px-3 py-2">
                          <div className="text-[11px] font-semibold text-slate-600 mb-1">分析链路</div>
                          <div className="space-y-1">
                            {msg.logicFlow.split('\n').map((line, i) => {
                              const text = (line || '').trim()
                              if (!text) return null
                              return (
                                <div key={`${text}-${i}`} className="text-xs text-slate-600">
                                  {text}
                                </div>
                              )
                            })}
                          </div>
                        </div>
                      )}
                      {thought && (
                        <ThoughtBlock 
                          content={thought} 
                          references={msg.references} 
                          onViewReference={setViewingRef}
                          isStreaming={msg.isStreaming} 
                        />
                      )}
                      <MarkdownWithCitations 
                        content={answer} 
                        references={msg.references} 
                        onViewReference={setViewingRef} 
                      />
                      {msg.isStreaming && <span className="inline-block w-1.5 h-4 bg-emerald-400 animate-pulse ml-1 align-middle"/>}
                    </>
                  )
                })()}
                {msg.references && msg.references.length > 0 && !msg.isStreaming && (
                  <div className="mt-4 pt-3 border-t border-slate-100">
                    <div className="text-xs font-semibold text-slate-500 mb-2 flex items-center gap-1">
                      <BookOpen size={14} />
                      参考资料
                    </div>
                    <div className="flex flex-col gap-2">
                      {msg.references.map((ref, i) => (
                        <button 
                          key={i}
                          onClick={() => setViewingRef(ref)}
                          className="flex items-start gap-2 p-2 bg-slate-50 hover:bg-slate-100 border border-slate-200 rounded-lg text-left transition-colors group"
                        >
                          <FileText size={16} className="text-blue-500 mt-0.5 shrink-0" />
                          <div className="flex-1 min-w-0">
                            <div className="text-xs font-medium text-slate-700 group-hover:text-blue-700 truncate">
                              {ref.document_name}
                            </div>
                            <div className="text-[10px] text-slate-400 mt-0.5 flex items-center gap-2">
                              <span className="bg-slate-200 px-1.5 rounded text-slate-600">
                                {(ref.similarity * 100).toFixed(0)}%
                              </span>
                              <span className="truncate max-w-[200px]">
                                {ref.content ? ref.content.slice(0, 50) + "..." : "No preview"}
                              </span>
                            </div>
                          </div>
                        </button>
                      ))}
                    </div>
                  </div>
                )}
              </div>
            </div>
          ))}
          <div ref={messagesEndRef} />
        </div>
        
        {viewingRef && (
          <SourceViewer 
            reference={viewingRef} 
            onClose={() => setViewingRef(null)} 
          />
        )}

        <div className="p-4 bg-white border-t">
          <div className="mb-3 flex flex-wrap gap-2">
            {quickRouteExamples.map((item) => (
              <button
                key={item}
                onClick={() => handleSend(item)}
                disabled={conversationLoading}
                className="px-2.5 py-1 text-xs rounded-full border border-slate-300 bg-slate-50 hover:bg-slate-100 text-slate-700 disabled:opacity-50"
              >
                {item}
              </button>
            ))}
          </div>
          <div className="relative">
            <textarea
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault()
                  handleSend()
                }
              }}
              placeholder="请输入您的问题..."
              className="w-full pl-4 pr-12 py-3 bg-slate-50 rounded-xl border border-slate-200 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent resize-none h-[56px] text-sm"
              disabled={conversationLoading}
            />
            <button
              onClick={() => handleSend()}
              disabled={!input.trim() || conversationLoading}
              className="absolute right-2 top-2 p-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-all shadow-sm"
            >
              {(loading || conversationLoading) ? <Loader2 size={18} className="animate-spin" /> : <Send size={18} />}
            </button>
          </div>
          {loading && (
            <div className="mt-2 flex justify-end">
              <button
                onClick={handleStopGeneration}
                className="px-3 py-1.5 text-xs rounded border border-rose-300 text-rose-600 hover:bg-rose-50"
              >
                中断生成
              </button>
            </div>
          )}
          <p className="text-center text-xs text-slate-400 mt-2">
            AI 生成内容仅供参考，请以原始文档为准。
          </p>
        </div>
      </div>
    </div>
  )
}

function App() {
  const [authSession, setAuthSession] = useState(() => loadAuthSession())
  const [activeTab, setActiveTab] = useState('chat')
  const role = authSession?.user?.role || null
  const username = authSession?.user?.username || ''

  useEffect(() => {
    const syncAuthExpired = () => {
      clearAuthSession()
      setAuthSession(null)
      setActiveTab('chat')
    }
    window.addEventListener('ai4kb-auth-expired', syncAuthExpired)
    return () => window.removeEventListener('ai4kb-auth-expired', syncAuthExpired)
  }, [])

  const handleLogin = async ({ username: loginUsername, password }) => {
    const data = await loginByPassword(loginUsername, password)
    const nextSession = {
      token: data?.token,
      user: data?.user
    }
    if (!nextSession.token || !nextSession.user?.role) {
      throw new Error('登录返回数据不完整')
    }
    saveAuthSession(nextSession)
    setAuthSession(nextSession)
    setActiveTab(isSuperAdminRole(nextSession.user.role) ? 'super_overview' : (isAdminLikeRole(nextSession.user.role) ? 'datasets' : 'chat'))
  }

  const handleLogout = () => {
    clearAuthSession()
    setAuthSession(null)
    setActiveTab('chat')
  }

  if (!role) {
    return <LoginScreen onLogin={handleLogin} />
  }

  return (
    <div className="flex h-screen bg-slate-50">
      <Sidebar 
        role={role} 
        username={username}
        activeTab={activeTab} 
        setActiveTab={setActiveTab} 
        onLogout={handleLogout}
      />
      <main className="flex-1 h-full overflow-hidden relative">
        {activeTab === 'chat' && <ChatInterface role={role} />}
        {activeTab === 'super_overview' && isSuperAdminRole(role) && <SuperAdminOverview />}
        {activeTab === 'datasets' && isAdminLikeRole(role) && <DatasetManager />}
        {activeTab === 'permissions' && isAdminLikeRole(role) && <PermissionManager />}
        {activeTab === 'skills' && isAdminLikeRole(role) && <SkillManager />}
        {activeTab === 'route_samples' && isSuperAdminRole(role) && <RouteSampleManager />}
      </main>
    </div>
  )
}

export default App
