import { useEffect, useState } from 'react'
import { NavLink, Navigate, Route, Routes, useLocation } from 'react-router-dom'
import AttributionPage from './AttributionPage'
import QueryChatPage from './QueryChatPage'

type DependencyStatus = {
  code: string
  name: string
  status: 'UP' | 'DOWN' | 'MOCK' | 'READY'
  detail: string
  checkedAt: string | null
}

type ModelOption = {
  id: string
  displayName: string
}

const INITIAL_DEPENDENCIES: DependencyStatus[] = [
  { code: 'redis', name: 'Redis', status: 'READY', detail: '正在检查', checkedAt: null },
  { code: 'llm', name: 'LLM', status: 'READY', detail: '正在检查', checkedAt: null },
  { code: 'smartbi', name: 'SmartBI', status: 'READY', detail: '正在检查', checkedAt: null },
]

const FALLBACK_MODELS: ModelOption[] = [
  { id: 'glm-4.7', displayName: 'GLM-4.7' },
  { id: 'glm-4.7-flashx', displayName: 'GLM-4.7-FlashX' },
  { id: 'glm-4.7-flash', displayName: 'GLM-4.7-Flash' },
  { id: 'glm-4-flash-250414', displayName: 'GLM-4-Flash-250414' },
  { id: 'deepseek-v3', displayName: 'DeepSeek-V3（公司）' },
  { id: 'glm-4.6-fp8', displayName: 'GLM-4.6-FP8（公司）' },
]
const MODEL_STORAGE_KEY = 'payment-analysis:selected-model'

export default function App() {
  const location = useLocation()
  const [dependencies, setDependencies] = useState<DependencyStatus[]>(INITIAL_DEPENDENCIES)
  const [models, setModels] = useState<ModelOption[]>(FALLBACK_MODELS)
  const [selectedModel, setSelectedModel] = useState(
    () => {
      const saved = localStorage.getItem(MODEL_STORAGE_KEY)
      return saved && FALLBACK_MODELS.some((model) => model.id === saved) ? saved : 'glm-4.7-flash'
    },
  )

  useEffect(() => {
    fetch('/api/system/models')
      .then((response) => response.ok ? response.json() as Promise<{ defaultModel: string, models: ModelOption[] }> : Promise.reject())
      .then((data) => {
        const available = data.models.length ? data.models : FALLBACK_MODELS
        setModels(available)
        setSelectedModel((current) => available.some((model) => model.id === current) ? current : data.defaultModel)
      })
      .catch(() => setModels(FALLBACK_MODELS))
  }, [])

  useEffect(() => {
    let active = true
    async function refreshDependencies() {
      try {
        const response = await fetch(`/api/system/dependencies?model=${encodeURIComponent(selectedModel)}`)
        if (!response.ok) throw new Error()
        const data = await response.json() as { dependencies: DependencyStatus[] }
        if (active) setDependencies(data.dependencies)
      } catch {
        if (active) {
          setDependencies((current) =>
            current.map((item) => ({ ...item, status: 'DOWN', detail: '健康检查接口不可用' })))
        }
      }
    }
    void refreshDependencies()
    const handleModelActivity = () => void refreshDependencies()
    window.addEventListener('model-health-changed', handleModelActivity)
    return () => {
      active = false
      window.removeEventListener('model-health-changed', handleModelActivity)
    }
  }, [selectedModel])

  function selectModel(model: string) {
    setSelectedModel(model)
    localStorage.setItem(MODEL_STORAGE_KEY, model)
  }

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark">U</div>
          <div><strong>数据智能分析</strong><small>PAYMENT INSIGHT</small></div>
        </div>
        <nav aria-label="主导航">
          <NavLink to="/query"><span className="nav-icon">查</span><span>对话查数<small>多轮自然语言查询</small></span></NavLink>
          <NavLink to="/attribution"><span className="nav-icon">归</span><span>归因分析<small>指标变化拆解</small></span></NavLink>
        </nav>
        <div className="sidebar-footer"><span>●</span> 测试环境 · v0.3.0</div>
      </aside>

      <main>
        <header>
          <div><strong>支付数据智能分析平台</strong><span>测试环境</span></div>
          <div className="header-actions">
            {(location.pathname.startsWith('/query') || location.pathname.startsWith('/attribution')) && (
              <div className="model-selector">
                <label htmlFor="llm-model">模型</label>
                <select
                  aria-label="选择大模型"
                  id="llm-model"
                  onChange={(event) => selectModel(event.target.value)}
                  value={selectedModel}
                >
                  {models.map((model) => <option key={model.id} value={model.id}>{model.displayName}</option>)}
                </select>
              </div>
            )}
            <div className="dependency-health" aria-label="中间件运行状态">
              {dependencies.map((dependency) => (
                <span
                  className={`dependency-item ${dependency.status.toLowerCase()}`}
                  key={dependency.code}
                  title={`${dependency.name}：${dependency.detail}`}
                >
                  <i />{dependency.name}
                </span>
              ))}
            </div>
            <button aria-label="帮助" type="button">?</button>
            <div className="avatar">演</div>
            <div className="user">演示用户<small>数据分析员</small></div>
          </div>
        </header>
        <div className="content">
          <Routes>
            <Route path="/query" element={<QueryChatPage selectedModel={selectedModel} />} />
            <Route path="/attribution" element={<AttributionPage selectedModel={selectedModel} />} />
            <Route path="*" element={<Navigate to="/query" replace />} />
          </Routes>
        </div>
      </main>
    </div>
  )
}
