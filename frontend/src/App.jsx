import { useState, useEffect, useRef } from 'react'
import { MessageSquare, Database, Send, User, Bot, Layers, CheckSquare, Square, Loader2, LogOut, Shield, Users, Lock, BookOpen, FileText, X, ChevronLeft, ChevronRight, ZoomIn, ZoomOut, Image as ImageIcon, Upload, Trash2, Clock, Search, RefreshCw, Brain } from 'lucide-react'
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

async function fetchDatasets() {
  const res = await fetch(`${API_BASE}/admin/datasets`)
  if (!res.ok) throw new Error('Failed to fetch datasets')
  const json = await res.json()
  return json.data || []
}

async function createDataset(name) {
  const res = await fetch(`${API_BASE}/admin/datasets`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name })
  })
  if (!res.ok) throw new Error('Failed to create dataset')
  return res.json()
}

async function grantPermission(username, datasetId) {
  const res = await fetch(`${API_BASE}/admin/permission/grant`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      username,
      resource_type: 'DATASET',
      resource_id: datasetId
    })
  })
  if (!res.ok) throw new Error('Failed to grant permission')
  const data = await res.json()
  if (data.status !== 'ok') throw new Error(data.status || 'Unknown error')
  return data
}

async function deleteDataset(id) {
  const res = await fetch(`${API_BASE}/admin/datasets/${id}`, {
    method: 'DELETE'
  })
  if (!res.ok) throw new Error('Failed to delete dataset')
  return res.json()
}

async function fetchUsers() {
  const res = await fetch(`${API_BASE}/admin/users`)
  if (!res.ok) throw new Error('Failed to fetch users')
  return res.json()
}

async function fetchUserPermissions(username) {
  const res = await fetch(`${API_BASE}/admin/permission/${username}`)
  if (!res.ok) throw new Error('Failed to fetch permissions')
  return res.json()
}

async function syncPermissions(username, datasetIds) {
  const res = await fetch(`${API_BASE}/admin/permission/sync`, {
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
  const res = await fetch(`${API_BASE}/admin/datasets/${datasetId}/documents?page=${page}&page_size=${pageSize}&t=${Date.now()}`)
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
  
  const res = await fetch(`${API_BASE}/admin/datasets/${datasetId}/documents`, {
    method: 'POST',
    body: formData
  })
  if (!res.ok) throw new Error('Failed to upload document')
  return res.json()
}

async function deleteDocuments(datasetId, ids) {
  const res = await fetch(`${API_BASE}/admin/datasets/${datasetId}/documents`, {
    method: 'DELETE',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ids })
  })
  if (!res.ok) throw new Error('Failed to delete documents')
  return res.json()
}

async function runDocuments(datasetId, docIds) {
  const res = await fetch(`${API_BASE}/admin/datasets/${datasetId}/documents/run`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ doc_ids: docIds })
  })
  if (!res.ok) throw new Error('Failed to run documents')
  return res.json()
}

async function getDocumentFile(datasetId, docId) {
  const res = await fetch(`${API_BASE}/admin/datasets/${datasetId}/documents/${docId}/file`)
  if (!res.ok) throw new Error('Failed to fetch document file')
  return res.blob()
}

async function fetchChunks(datasetId, docId, page = 1, pageSize = 10000) {
  const res = await fetch(`${API_BASE}/admin/datasets/${datasetId}/documents/${docId}/chunks?page=${page}&page_size=${pageSize}`)
  if (!res.ok) throw new Error('Failed to fetch chunks')
  const json = await res.json()
  return json.data || []
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
      const [p, x1, x2, y1, y2] = pos
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
  const [pageNumber, setPageNumber] = useState(1)
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
  return (
    <div className="flex min-h-screen items-center justify-center bg-slate-100">
      <div className="w-full max-w-md p-8 bg-white rounded-xl shadow-lg">
        <div className="flex justify-center mb-6">
          <div className="p-3 bg-blue-100 rounded-full">
            <Layers className="w-8 h-8 text-blue-600" />
          </div>
        </div>
        <h2 className="text-2xl font-bold text-center text-slate-800 mb-2">AI4KB 知识库系统</h2>
        <p className="text-center text-slate-500 mb-8">请选择登录角色</p>
        
        <div className="space-y-4">
          <button
            onClick={() => onLogin('admin')}
            className="w-full flex items-center justify-center gap-3 p-4 border-2 border-slate-100 rounded-xl hover:border-blue-500 hover:bg-blue-50 transition-all group"
          >
            <div className="p-2 bg-slate-100 rounded-lg group-hover:bg-blue-200 transition-colors">
              <Shield className="w-5 h-5 text-slate-600 group-hover:text-blue-700" />
            </div>
            <div className="text-left flex-1">
              <div className="font-semibold text-slate-700">管理员 (Admin)</div>
              <div className="text-xs text-slate-500">管理知识库与用户权限</div>
            </div>
          </button>

          <button
            onClick={() => onLogin('user')}
            className="w-full flex items-center justify-center gap-3 p-4 border-2 border-slate-100 rounded-xl hover:border-emerald-500 hover:bg-emerald-50 transition-all group"
          >
            <div className="p-2 bg-slate-100 rounded-lg group-hover:bg-emerald-200 transition-colors">
              <User className="w-5 h-5 text-slate-600 group-hover:text-emerald-700" />
            </div>
            <div className="text-left flex-1">
              <div className="font-semibold text-slate-700">普通用户 (User)</div>
              <div className="text-xs text-slate-500">访问知识库进行智能问答</div>
            </div>
          </button>
        </div>
      </div>
    </div>
  )
}

function Sidebar({ role, activeTab, setActiveTab, onLogout }) {
  const menuItems = role === 'admin' ? [
    { id: 'datasets', label: '知识库管理', icon: Database },
    { id: 'permissions', label: '权限分配', icon: Lock },
    { id: 'chat', label: '调试对话', icon: MessageSquare },
  ] : [
    { id: 'chat', label: '智能问答', icon: MessageSquare },
  ]

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
            role === 'admin' ? "bg-purple-500" : "bg-emerald-500"
          )}>
            {role === 'admin' ? 'AD' : 'US'}
          </div>
          <div className="overflow-hidden">
            <p className="text-sm font-medium truncate">{role === 'admin' ? 'Administrator' : 'zhangsan'}</p>
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

function DatasetDetail({ dataset, onBack }) {
  const [docs, setDocs] = useState([])
  const [loading, setLoading] = useState(true)
  const [uploading, setUploading] = useState(false)
  const [deletingId, setDeletingId] = useState(null)
  const [parsingId, setParsingId] = useState(null)
  const [viewingDoc, setViewingDoc] = useState(null)

  const loadDocs = () => {
    // Only set loading on initial load to avoid flickering during polling
    if (docs.length === 0) setLoading(true)
    fetchDocuments(dataset.id)
      .then(data => setDocs(Array.isArray(data) ? data : []))
      .catch(console.error)
      .finally(() => setLoading(false))
  }

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
  }, [dataset.id])

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
          </h2>
          <p className="text-slate-500 text-sm mt-1 font-mono select-all">ID: {dataset.id}</p>
        </div>
        <div className="relative group">
          <input 
            type="file" 
            onChange={handleFileUpload}
            className="absolute inset-0 w-full h-full opacity-0 cursor-pointer z-10"
            disabled={uploading}
          />
          <button className="px-6 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 flex items-center gap-2 disabled:opacity-50 transition-colors shadow-sm">
            {uploading ? <Loader2 size={16} className="animate-spin" /> : <Upload size={16} />}
            上传文件
          </button>
        </div>
      </div>
      
      <div className="bg-white rounded-xl border shadow-sm overflow-hidden">
        <table className="w-full text-sm text-left">
          <thead className="bg-slate-50 text-slate-500 font-medium border-b">
            <tr>
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
                <td colSpan={5} className="px-6 py-8 text-center text-slate-500">
                  <Loader2 className="w-6 h-6 animate-spin mx-auto mb-2" />
                  加载中...
                </td>
              </tr>
            ) : docs.length === 0 ? (
              <tr>
                <td colSpan={5} className="px-6 py-8 text-center text-slate-500">
                  暂无文档，请上传文件
                </td>
              </tr>
            ) : (
              docs.map((doc) => (
                <tr key={doc.id} className="hover:bg-slate-50 transition-colors">
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
    </div>
  )
}

function DatasetCard({ dataset, onClick, onDelete }) {
  return (
    <div 
      onClick={onClick}
      className="bg-white rounded-xl border border-slate-100 p-6 hover:shadow-lg hover:border-blue-200 transition-all cursor-pointer group relative"
    >
      <div className="flex items-start justify-between mb-4">
        <div className="p-3 bg-blue-50 rounded-lg group-hover:bg-blue-100 transition-colors">
          <Database className="w-6 h-6 text-blue-500" />
        </div>
        <button 
          onClick={(e) => onDelete(dataset.id, e)}
          className="p-2 text-slate-300 hover:text-red-500 hover:bg-red-50 rounded-lg transition-all opacity-0 group-hover:opacity-100"
          title="删除知识库"
        >
          <Trash2 size={18} />
        </button>
      </div>
      
      <h3 className="font-bold text-slate-800 mb-1 group-hover:text-blue-600 transition-colors line-clamp-1">{dataset.name}</h3>
      <p className="text-sm text-slate-400 mb-4 line-clamp-2">{dataset.description || '暂无描述'}</p>
      
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
  const [deleting, setDeleting] = useState(null)
  const [newDatasetName, setNewDatasetName] = useState('')
  const [viewingDataset, setViewingDataset] = useState(null)

  const loadData = () => {
    setLoading(true)
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
    setDeleting(id)
    try {
      // Optimistic update to immediately remove from UI
      setDatasets(prev => prev.filter(d => d.id !== id))
      await deleteDataset(id)
      // Wait a bit before reloading to allow backend consistency
      setTimeout(() => loadData(), 500)
    } catch (e) {
      alert('删除失败: ' + e.message)
      loadData() // Revert if failed
    } finally {
      setDeleting(null)
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
          <p className="text-slate-500 text-sm mt-1">创建和管理您的本地知识库</p>
        </div>
        <div className="flex gap-2">
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

function SourceViewer({ reference, onClose }) {
  const [activeTab, setActiveTab] = useState('summary') // 'summary' | 'pdf'
  const [numPages, setNumPages] = useState(null)
  const [pageNumber, setPageNumber] = useState(1)
  const [scale, setScale] = useState(1.0)
  const [imageError, setImageError] = useState(false)

  useEffect(() => {
    if (reference?.positions && reference.positions.length > 0) {
      setPageNumber(reference.positions[0][0])
    } else {
      setPageNumber(1)
    }
    setActiveTab('summary')
    setImageError(false)
    setScale(1.0)
  }, [reference])

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
                  {/* If we have highlight positions, maybe we can show them here too? 
                      For now, just show the content which is the chunk itself. 
                      Ideally, RAGFlow returns the chunk text. */}
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
                   <button 
                     onClick={() => setPageNumber(p => Math.max(1, p - 1))}
                     disabled={pageNumber <= 1}
                     className="p-1.5 hover:bg-slate-100 rounded disabled:opacity-50"
                   >
                     <ChevronLeft size={16} />
                   </button>
                   <span className="text-xs font-mono w-16 text-center select-none">
                     {pageNumber} / {numPages || '--'}
                   </span>
                   <button 
                     onClick={() => setPageNumber(p => Math.min(numPages || Infinity, p + 1))}
                     disabled={pageNumber >= numPages}
                     className="p-1.5 hover:bg-slate-100 rounded disabled:opacity-50"
                   >
                     <ChevronRight size={16} />
                   </button>
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
                   file={`/api/document/get/${reference.document_id}`}
                   onLoadSuccess={onDocumentLoadSuccess}
                   className="shadow-xl"
                   loading={<div className="flex items-center gap-2 text-slate-500"><Loader2 className="animate-spin" /> Loading PDF...</div>}
                   error={<div className="text-red-500 text-sm p-4 bg-red-50 rounded">Failed to load PDF. Please check permissions.</div>}
                 >
                   <Page 
                     pageNumber={pageNumber} 
                     scale={scale}
                     renderTextLayer={false}
                     renderAnnotationLayer={false}
                   >
                     {/* Highlight Overlay */}
                     {reference.positions && reference.positions.map((pos, idx) => {
                        // Position format: [page, x_min, x_max, y_min, y_max] (Assuming RAGFlow standard)
                        const [p, x_min, x_max, y_min, y_max] = pos; 
                        
                        if (p !== pageNumber) return null;
                        
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
                 </Document>
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
        pre: ({node, ...props}) => <div className="overflow-auto w-full my-2 bg-slate-800 text-slate-100 p-2 rounded" {...props} />,
        code: ({node, ...props}) => <code className="bg-slate-100 text-slate-800 px-1 py-0.5 rounded text-xs" {...props} />,
        a: ({node, href, children, ...props}) => {
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

function ThoughtBlock({ content, references, onViewReference }) {
  const [expanded, setExpanded] = useState(true);
  
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

function ChatInterface({ role }) {
  const [messages, setMessages] = useState([
    { role: 'assistant', content: '你好！我是 AI 助手，请问有什么可以帮你？' }
  ])
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  const [viewingRef, setViewingRef] = useState(null)
  const messagesEndRef = useRef(null)

  // Auto-scroll
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" })
  }, [messages])

  const handleSend = async () => {
    if (!input.trim() || loading) return

    const userMsg = { role: 'user', content: input }
    setMessages(prev => [...prev, userMsg])
    setInput('')
    setLoading(true)

    const aiMsgId = Date.now()
    setMessages(prev => [...prev, { role: 'assistant', content: '', id: aiMsgId, isStreaming: true }])

    try {
      const response = await fetch(`${API_BASE}/chat/completions`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-User-Name': role === 'admin' ? 'admin' : 'zhangsan' 
        },
        body: JSON.stringify({
          question: userMsg.content,
          // user role does not select datasets; backend uses assigned permissions
          // admin can potentially select, but let's default to all/auto for now
          stream: true
        })
      })

      if (!response.ok) throw new Error(`HTTP ${response.status}`)

      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let aiContent = ''
      let buffer = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) {
          console.log('Stream done')
          break
        }
        
        const chunk = decoder.decode(value, { stream: true })
        console.log('Received chunk:', chunk)
        buffer += chunk
        const lines = buffer.split('\n')
        
        // Process all complete lines
        buffer = lines.pop() || '' 
        
        for (const line of lines) {
          if (line.startsWith('data:')) {
            const dataStr = line.slice(5).trim()
            if (dataStr === '[DONE]') continue
            
            try {
              const data = JSON.parse(dataStr)
              console.log('Parsed data:', data)
              // Handle RAGFlow SSE format
              const delta = data.answer || data.data?.answer || ''
              aiContent += delta
              
              const newRefs = data.reference
              
              setMessages(prev => prev.map(msg => 
                msg.id === aiMsgId ? { 
                  ...msg, 
                  content: aiContent,
                  references: (newRefs && Array.isArray(newRefs) && newRefs.length > 0) ? newRefs : msg.references
                } : msg
              ))
            } catch (e) {
              console.warn('SSE Parse Error:', e, 'Line:', line)
            }
          }
        }
      }
    } catch (err) {
      console.error(err)
      setMessages(prev => prev.map(msg => 
        msg.id === aiMsgId ? { ...msg, content: `**Error**: ${err.message}` } : msg
      ))
    } finally {
      setLoading(false)
      setMessages(prev => prev.map(msg => 
        msg.id === aiMsgId ? { ...msg, isStreaming: false } : msg
      ))
    }
  }

  return (
    <div className="flex flex-1 h-full overflow-hidden bg-slate-50 relative">
      <div className="flex-1 flex flex-col h-full max-w-5xl mx-auto w-full shadow-sm bg-white border-x">
        {/* Header */}
        <div className="p-4 border-b bg-white/80 backdrop-blur z-10 sticky top-0">
          <h2 className="font-semibold text-slate-800 flex items-center gap-2">
            <Bot size={20} className="text-blue-500" />
            智能问答助手
          </h2>
        </div>

        {/* Messages */}
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
                {(() => {
                  let rawContent = msg.content || '';
                  // Clean up citation newlines: 
                  // 1. Remove newlines before citations to keep them inline with text
                  rawContent = rawContent.replace(/[\r\n]+(?=\s*\[(?:ID:\s*)?\d+\])/g, ' ');

                  let thought = null;
                  let answer = rawContent;
                  let hasStartTag = answer.includes('<think>');
                  let hasEndTag = answer.includes('</think>');
                  let autoAddedThink = false;

                  // Auto-add <think> if missing at start, but implied by </think> or user preference for start
                  if (hasEndTag && !hasStartTag) {
                      answer = '<think>' + answer;
                      hasStartTag = true;
                      autoAddedThink = true;
                  } else if (!hasStartTag && !hasEndTag) {
                      // If streaming, assume thought at start (as requested)
                      // If finished, we'll revert if it wasn't a thought
                      answer = '<think>' + answer;
                      hasStartTag = true;
                      autoAddedThink = true;
                  }

                  if (hasStartTag) {
                      const start = answer.indexOf('<think>');
                      const end = answer.indexOf('</think>');
                      
                      if (end !== -1) {
                          // Closed thought
                          thought = answer.substring(start + 7, end);
                          answer = answer.substring(0, start) + answer.substring(end + 8);
                      } else {
                          // Unclosed thought
                          if (msg.isStreaming) {
                              // While streaming, show as thought
                              thought = answer.substring(start + 7);
                              answer = answer.substring(0, start);
                          } else {
                              // Finished without closing </think>
                              if (autoAddedThink) {
                                  // It wasn't a thought, revert to normal
                                  thought = null;
                                  answer = rawContent;
                              } else {
                                  // Explicit unclosed thought
                                  thought = answer.substring(start + 7);
                                  answer = answer.substring(0, start);
                              }
                          }
                      }
                  }

                  return (
                    <>
                      {thought && (
                        <ThoughtBlock 
                          content={thought} 
                          references={msg.references} 
                          onViewReference={setViewingRef} 
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
                
                {/* References list - Appended at the end as requested */}
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

        {/* Input Area */}
        <div className="p-4 bg-white border-t">
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
              disabled={loading}
            />
            <button
              onClick={handleSend}
              disabled={loading || !input.trim()}
              className="absolute right-2 top-2 p-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-all shadow-sm"
            >
              {loading ? <Loader2 size={18} className="animate-spin" /> : <Send size={18} />}
            </button>
          </div>
          <p className="text-center text-xs text-slate-400 mt-2">
            AI 生成内容仅供参考，请以原始文档为准。
          </p>
        </div>
      </div>
    </div>
  )
}

function App() {
  const [role, setRole] = useState(null) // 'admin' | 'user' | null
  const [activeTab, setActiveTab] = useState('chat')

  if (!role) {
    return <LoginScreen onLogin={(r) => {
      setRole(r)
      // Default tabs
      setActiveTab(r === 'admin' ? 'datasets' : 'chat')
    }} />
  }

  return (
    <div className="flex h-screen bg-slate-50">
      <Sidebar 
        role={role} 
        activeTab={activeTab} 
        setActiveTab={setActiveTab} 
        onLogout={() => setRole(null)} 
      />
      <main className="flex-1 h-full overflow-hidden relative">
        {activeTab === 'chat' && <ChatInterface role={role} />}
        {activeTab === 'datasets' && role === 'admin' && <DatasetManager />}
        {activeTab === 'permissions' && role === 'admin' && <PermissionManager />}
      </main>
    </div>
  )
}

export default App
