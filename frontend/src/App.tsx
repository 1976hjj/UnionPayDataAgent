import { useEffect, useState } from 'react'
import { NavLink, Navigate, Route, Routes } from 'react-router-dom'
import AttributionPage from './AttributionPage'
import QueryChatPage from './QueryChatPage'

type DependencyStatus = {
  code: string
  name: string
  status: 'UP' | 'DOWN' | 'MOCK' | 'READY'
  detail: string
  checkedAt: string | null
}

const INITIAL_DEPENDENCIES: DependencyStatus[] = [
  { code: 'redis', name: 'Redis', status: 'READY', detail: '正在检查', checkedAt: null },
  { code: 'llm', name: 'LLM', status: 'READY', detail: '正在检查', checkedAt: null },
  { code: 'smartbi', name: 'SmartBI', status: 'READY', detail: '正在检查', checkedAt: null },
]

export default function App() {
  const [dependencies, setDependencies] = useState<DependencyStatus[]>(INITIAL_DEPENDENCIES)

  useEffect(() => {
    let active = true
    async function refreshDependencies() {
      try {
        const response = await fetch('/api/system/dependencies')
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
    const timer = window.setInterval(() => void refreshDependencies(), 15_000)
    return () => {
      active = false
      window.clearInterval(timer)
    }
  }, [])

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
            <Route path="/query" element={<QueryChatPage />} />
            <Route path="/attribution" element={<AttributionPage />} />
            <Route path="*" element={<Navigate to="/query" replace />} />
          </Routes>
        </div>
      </main>
    </div>
  )
}
