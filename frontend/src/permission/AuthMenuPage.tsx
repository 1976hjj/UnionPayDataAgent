import { useEffect, useMemo, useState } from 'react'
import type { FormEvent, WheelEvent } from 'react'
import { useNavigate } from 'react-router-dom'

type DimensionId = 'acq_reg_ch' | 'iss_dq_ch'
type Scope = {
  id?: string
  loginUsername: string
  dimensionId: DimensionId
  dimensionValue: string
  enabled: boolean
  updatedAt: string
}

type ManagedDimension = { id: DimensionId; name: string; required: boolean; values: string[] }
type DimensionOption = { name: string; required: boolean; values: string[] }

const DATA_SCOPE_VALUES = ['拉美', '韩国', '日本', '中东', '南亚', '欧洲', '香港', '其它', '俄罗斯', '北美', '中亚', '台湾', '东南亚', '蒙古', '南太', '非洲', '中国大陆']

const DIMENSIONS: Record<DimensionId, DimensionOption> = {
  acq_reg_ch: { name: '收单分公司', required: true, values: DATA_SCOPE_VALUES },
  iss_dq_ch: { name: '发卡分公司', required: true, values: DATA_SCOPE_VALUES },
}

function now() {
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
  }).format(new Date()).replaceAll('/', '-')
}

function scopeKey(scope: Scope) {
  return `${scope.loginUsername}\u0000${scope.dimensionId}\u0000${scope.dimensionValue}`
}

function scopeReference(scope: Scope) {
  return {
    loginUsername: scope.loginUsername,
    dimensionId: scope.dimensionId,
    dimensionValue: scope.dimensionValue,
  }
}

export default function AuthMenuPage() {
  const navigate = useNavigate()
  const [authorized, setAuthorized] = useState(false)
  const [authorizationChecked, setAuthorizationChecked] = useState(false)
  const [password, setPassword] = useState('')
  const [passwordError, setPasswordError] = useState('')
  const [scopes, setScopes] = useState<Scope[]>([])
  const [dimensions, setDimensions] = useState<Record<DimensionId, DimensionOption>>(DIMENSIONS)
  const [keyword, setKeyword] = useState('')
  const [status, setStatus] = useState<'ALL' | 'ENABLED' | 'DISABLED'>('ALL')
  const [editorOpen, setEditorOpen] = useState(false)
  const [selectedKeys, setSelectedKeys] = useState<Set<string>>(() => new Set())
  const [notice, setNotice] = useState('')

  const filteredScopes = useMemo(() => scopes.filter((scope) => {
    const matchesKeyword = `${scope.loginUsername} ${scope.dimensionId} ${scope.dimensionValue}`.toLowerCase().includes(keyword.trim().toLowerCase())
    const matchesStatus = status === 'ALL' || (status === 'ENABLED' ? scope.enabled : !scope.enabled)
    return matchesKeyword && matchesStatus
  }), [keyword, scopes, status])
  const selectedScopes = useMemo(() => scopes.filter((scope) => selectedKeys.has(scopeKey(scope))), [scopes, selectedKeys])
  const allVisibleSelected = filteredScopes.length > 0 && filteredScopes.every((scope) => selectedKeys.has(scopeKey(scope)))

  useEffect(() => {
    fetch('/api/permission/management/session')
      .then((response) => response.ok ? response.json() as Promise<{ authenticated: boolean }> : { authenticated: false })
      .then((session) => setAuthorized(session.authenticated))
      .catch(() => setAuthorized(false))
      .finally(() => setAuthorizationChecked(true))
  }, [])

  useEffect(() => {
    if (!authorized) return
    void loadManagementData()
  }, [authorized])

  async function loadManagementData() {
    try {
      const [metadataResponse, scopesResponse] = await Promise.all([
        fetch('/api/permission/management/metadata', { credentials: 'same-origin' }),
        fetch('/api/permission/management/scopes', { credentials: 'same-origin' }),
      ])
      if (!metadataResponse.ok || !scopesResponse.ok) throw new Error('无法读取权限管理数据')
      const metadata = await metadataResponse.json() as { dimensions: ManagedDimension[] }
      const loadedScopes = await scopesResponse.json() as Scope[]
      const nextDimensions = Object.fromEntries(metadata.dimensions.map((dimension) => [dimension.id, {
        name: dimension.name,
        required: dimension.required,
        values: dimension.values,
      }])) as Partial<Record<DimensionId, DimensionOption>>
      setDimensions({ ...DIMENSIONS, ...nextDimensions })
      setScopes(loadedScopes)
      setSelectedKeys(new Set())
    } catch (error) {
      setNotice(error instanceof Error ? error.message : '无法读取权限管理数据')
    }
  }

  async function unlock(event: FormEvent) {
    event.preventDefault()
    try {
      const response = await fetch('/api/permission/management/authenticate', {
        method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ password }),
      })
      if (!response.ok) {
        setPasswordError('口令不正确，请重试。')
        return
      }
      setAuthorized(true)
      setPasswordError('')
    } catch {
      setPasswordError('权限管理服务暂不可用。')
    }
  }

  async function createScopes(next: Scope[]) {
    const groupedDimensions = (Object.keys(dimensions) as DimensionId[]).map((dimensionId) => ({
      dimensionId,
      dimensionValues: next.filter((scope) => scope.dimensionId === dimensionId).map((scope) => scope.dimensionValue),
    }))
    const response = await fetch('/api/permission/management/scopes', {
      method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        loginUsername: next[0].loginUsername,
        dimensions: groupedDimensions,
        enabled: next[0].enabled,
      }),
    })
    if (!response.ok) throw new Error(await response.text() || '新增授权范围失败')
    setEditorOpen(false)
    setNotice(`已新增 ${next.length} 条授权范围。`)
    await loadManagementData()
  }

  async function updateScope(scope: Scope | Scope[]) {
    const next = Array.isArray(scope) ? scope[0] : scope
    const response = await fetch('/api/permission/management/scopes', {
      method: 'PATCH', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        loginUsername: next.loginUsername, dimensionId: next.dimensionId,
        dimensionValue: next.dimensionValue, enabled: next.enabled,
      }),
    })
    if (!response.ok) throw new Error(await response.text() || '更新授权范围失败')
    setNotice('已更新授权范围。')
    await loadManagementData()
  }

  function toggleSelection(scope: Scope) {
    const key = scopeKey(scope)
    setSelectedKeys((current) => {
      const next = new Set(current)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  }

  function toggleAllVisible() {
    setSelectedKeys((current) => {
      const next = new Set(current)
      if (allVisibleSelected) filteredScopes.forEach((scope) => next.delete(scopeKey(scope)))
      else filteredScopes.forEach((scope) => next.add(scopeKey(scope)))
      return next
    })
  }

  async function batchEnable() {
    if (!selectedScopes.length) return
    try {
      const response = await fetch('/api/permission/management/scopes/batch-status', {
        method: 'PATCH', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ scopes: selectedScopes.map(scopeReference), enabled: true }),
      })
      if (!response.ok) throw new Error(await response.text() || '批量启用失败')
      setNotice('选中的授权已批量启用。')
      await loadManagementData()
    } catch (error) {
      setNotice(error instanceof Error ? error.message : '批量启用失败')
    }
  }

  async function batchDelete() {
    if (!selectedScopes.length || !window.confirm('确认删除选中的授权记录吗？')) return
    try {
      const response = await fetch('/api/permission/management/scopes/batch-delete', {
        method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ scopes: selectedScopes.map(scopeReference) }),
      })
      if (!response.ok) throw new Error(await response.text() || '批量删除失败')
      setNotice('选中的授权已批量删除。')
      await loadManagementData()
    } catch (error) {
      setNotice(error instanceof Error ? error.message : '批量删除失败')
    }
  }

  async function remove(scope: Scope) {
    if (!window.confirm(`确认删除 ${scope.loginUsername} 的「${dimensions[scope.dimensionId].name} · ${scope.dimensionValue}」授权吗？`)) return
    try {
      const parameters = new URLSearchParams({
        loginUsername: scope.loginUsername, dimensionId: scope.dimensionId, dimensionValue: scope.dimensionValue,
      })
      const response = await fetch(`/api/permission/management/scopes?${parameters}`, {
        method: 'DELETE', credentials: 'same-origin',
      })
      if (!response.ok) throw new Error(await response.text() || '删除授权范围失败')
      setNotice('授权范围已删除。')
      await loadManagementData()
    } catch (error) {
      setNotice(error instanceof Error ? error.message : '删除授权范围失败')
    }
  }

  if (!authorizationChecked) {
    return <div className="auth-gate auth-gate-loading">正在验证权限管理会话…</div>
  }

  if (!authorized) {
    return <div className="auth-gate">
      <div className="auth-gate-background">
        <div className="auth-gate-brand"><i>BI</i><span>UnionPay Data Agent</span></div>
        <div className="auth-gate-preview"><span>数据权限管理</span><strong>管理用户可查询的数据范围</strong><p>此入口不会出现在系统侧边栏。</p></div>
      </div>
      <div className="auth-gate-shade" />
      <form className="auth-dialog" onSubmit={unlock}>
        <div className="auth-dialog-icon">⌘</div>
        <p>BI AGENT · RESTRICTED</p>
        <h1>权限管理入口</h1>
        <span>请输入管理口令以进入数据权限维护页面</span>
        <label htmlFor="auth-password">管理口令</label>
        <input
          autoFocus
          id="auth-password"
          onChange={(event) => { setPassword(event.target.value); setPasswordError('') }}
          placeholder="请输入口令"
          type="password"
          value={password}
        />
        {passwordError && <div className="auth-error">{passwordError}</div>}
        <button type="submit">验证并进入 <b>→</b></button>
      </form>
    </div>
  }

  return <main className="permission-console">
    <header className="permission-topbar">
      <div className="permission-brand"><i>BI</i><strong>UnionPay Data Agent</strong><span>CONTROL CENTER</span></div>
      <div><span className="protected-badge">● 已验证管理入口</span><button className="console-exit" onClick={() => navigate('/query')} type="button">退出管理</button></div>
    </header>
    <section className="permission-content">
      <div className="permission-crumb">控制中心 <b>/</b> 数据权限 <b>/</b> 用户数据范围</div>
      <div className="permission-heading">
        <div><h1>用户数据权限</h1></div>
        <button className="permission-primary" onClick={() => setEditorOpen(true)} type="button"><b>＋</b> 新增授权范围</button>
      </div>
      {notice && <div className="permission-notice"><span>✓</span>{notice}<button onClick={() => setNotice('')} type="button">×</button></div>}
      <section className="permission-card">
        <div className="permission-card-title"><div><h2>用户授权范围</h2><p>勾选记录后可批量启用或删除。</p></div></div>
        <div className="permission-toolbar">
          <label className="permission-search"><i>⌕</i><input onChange={(event) => setKeyword(event.target.value)} placeholder="搜索用户名、字段或值域" value={keyword} /></label>
          <div className="permission-filter"><span>状态</span>{(['ALL', 'ENABLED', 'DISABLED'] as const).map((item) => <button className={status === item ? 'active' : ''} key={item} onClick={() => setStatus(item)} type="button">{item === 'ALL' ? '全部' : item === 'ENABLED' ? '启用' : '已停用'}</button>)}</div>
          <div className="permission-batch-actions"><button disabled={!selectedScopes.length} onClick={() => void batchEnable()} type="button">批量启用</button><button className="danger" disabled={!selectedScopes.length} onClick={() => void batchDelete()} type="button">批量删除</button></div>
          <button className="permission-refresh" onClick={() => void loadManagementData()} type="button">↻ 刷新</button>
        </div>
        <div className="permission-table-wrap"><table className="permission-table"><thead><tr><th className="selection-cell"><input aria-label="全选当前记录" checked={allVisibleSelected} onChange={toggleAllVisible} type="checkbox" /></th><th>OA 用户名</th><th>权限字段</th><th>已授权值域</th><th>状态</th><th>最近更新时间</th><th aria-label="操作" /></tr></thead><tbody>
          {filteredScopes.map((scope) => <tr className={selectedKeys.has(scopeKey(scope)) ? 'selected' : ''} key={scopeKey(scope)}><td className="selection-cell"><input aria-label={`选择 ${scope.loginUsername} ${scope.dimensionValue}`} checked={selectedKeys.has(scopeKey(scope))} onChange={() => toggleSelection(scope)} type="checkbox" /></td><td><strong>{scope.loginUsername}</strong></td><td><span className="dimension-code">{dimensions[scope.dimensionId].name}</span></td><td><span className="value-chip">{scope.dimensionValue}</span></td><td><button className={`scope-status ${scope.enabled ? 'enabled' : 'disabled'}`} onClick={() => void updateScope({ ...scope, enabled: !scope.enabled, updatedAt: now() })} type="button">{scope.enabled ? '启用' : '不启用'}</button></td><td className="updated-time">{formatUpdatedAt(scope.updatedAt)}</td><td><div className="row-actions"><button className="danger" onClick={() => void remove(scope)} type="button">删除</button></div></td></tr>)}
          {!filteredScopes.length && <tr><td className="permission-empty" colSpan={7}>没有符合当前筛选条件的授权记录。</td></tr>}
        </tbody></table></div>
      </section>
    </section>
    {editorOpen && <ScopeEditor dimensions={dimensions} onClose={() => setEditorOpen(false)} onSave={createScopes} scopes={scopes} />}
  </main>
}

function formatUpdatedAt(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
  }).format(date).replaceAll('/', '-')
}

function ScopeEditor({ dimensions, onClose, onSave, scopes }: { dimensions: Record<DimensionId, DimensionOption>; onClose: () => void; onSave: (scope: Scope[]) => Promise<void>; scopes: Scope[] }) {
  const [username, setUsername] = useState('')
  const [activeDimensionId, setActiveDimensionId] = useState<DimensionId>('acq_reg_ch')
  const [valuesByDimension, setValuesByDimension] = useState<Record<DimensionId, string[]>>({
    acq_reg_ch: [],
    iss_dq_ch: [],
  })
  const [enabled, setEnabled] = useState(true)
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const dimensionIds = Object.keys(dimensions) as DimensionId[]
  const existingValuesByDimension = useMemo(() => Object.fromEntries(dimensionIds.map((dimensionId) => [
    dimensionId,
    new Set(scopes
      .filter((scope) => scope.loginUsername === username.trim().toLowerCase() && scope.dimensionId === dimensionId)
      .map((scope) => scope.dimensionValue)),
  ])) as Record<DimensionId, Set<string>>, [dimensions, scopes, username])
  const existingValues = existingValuesByDimension[activeDimensionId]
  const values = valuesByDimension[activeDimensionId]
  const availableValues = dimensions[activeDimensionId].values.filter((value) => !existingValues.has(value))
  const allAvailableSelected = availableValues.length > 0 && availableValues.every((value) => values.includes(value))
  const selectedCount = dimensionIds.reduce((total, dimensionId) => total + valuesByDimension[dimensionId].length, 0)

  useEffect(() => {
    setValuesByDimension((current) => Object.fromEntries(dimensionIds.map((dimensionId) => [
      dimensionId,
      current[dimensionId].filter((value) => !existingValuesByDimension[dimensionId].has(value)),
    ])) as Record<DimensionId, string[]>)
  }, [existingValuesByDimension])

  function toggleValue(value: string) {
    if (existingValues.has(value)) return
    setValuesByDimension((current) => ({
      ...current,
      [activeDimensionId]: current[activeDimensionId].includes(value)
        ? current[activeDimensionId].filter((item) => item !== value)
        : [...current[activeDimensionId], value],
    }))
  }

  function scrollValuePicker(event: WheelEvent<HTMLDivElement>) {
    event.preventDefault()
    event.stopPropagation()
    event.currentTarget.scrollBy({ top: event.deltaY })
  }

  async function save() {
    if (saving) return
    if (!username.trim()) {
      setError('请填写 OA 用户名。')
      return
    }
    const missingDimensions = dimensionIds.filter((dimensionId) => dimensions[dimensionId].required
      && existingValuesByDimension[dimensionId].size === 0
      && valuesByDimension[dimensionId].length === 0)
    if (missingDimensions.length) {
      setError(`每个权限字段至少选择一个值域：${missingDimensions.map((dimensionId) => dimensions[dimensionId].name).join('、')}`)
      return
    }
    if (!selectedCount) {
      setError('至少选择一条新增授权。')
      return
    }
    const updatedAt = now()
    const saved = dimensionIds.flatMap((dimensionId) => valuesByDimension[dimensionId].map((value, index) => ({
      id: `${username.trim()}-${dimensionId}-${value}-${Date.now()}-${index}`,
      loginUsername: username.trim().toLowerCase(), dimensionId, dimensionValue: value, enabled, updatedAt,
    })))
    setError('')
    setSaving(true)
    try {
      await onSave(saved)
    } catch (saveError) {
      setError(saveError instanceof Error ? saveError.message : '保存授权范围失败')
      setSaving(false)
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    void save()
  }

  return <div className="scope-editor-backdrop" role="presentation" onMouseDown={onClose}>
    <form className="scope-editor" onMouseDown={(event) => event.stopPropagation()} onSubmit={submit}>
      <div className="scope-editor-heading"><div><p>CREATE DATA SCOPE</p><h2>新增授权范围</h2><span>字段和值域均从已登记的数据权限目录中选择。</span></div><button aria-label="关闭" onClick={onClose} type="button">×</button></div>
      <div className="scope-editor-body">
        <label className="editor-field"><span>OA 用户名 <b>*</b></span><input onChange={(event) => setUsername(event.target.value)} placeholder="例如：zhangsan" value={username} /></label>
        <div className="editor-field"><span>权限字段 <b>*</b></span><div className="dimension-choices">{dimensionIds.map((dimensionId) => { const complete = existingValuesByDimension[dimensionId].size > 0 || valuesByDimension[dimensionId].length > 0; return <button className={`${activeDimensionId === dimensionId ? 'active' : ''} ${complete ? 'complete' : ''}`} key={dimensionId} onClick={() => setActiveDimensionId(dimensionId)} type="button"><span>{dimensions[dimensionId].name}</span>{complete && <i>✓</i>}</button> })}</div></div>
        <div className="editor-field"><div className="value-picker-heading"><span>{dimensions[activeDimensionId].name}授权值域 <b>*</b></span><button disabled={!availableValues.length} onClick={() => setValuesByDimension((current) => ({ ...current, [activeDimensionId]: allAvailableSelected ? [] : [...availableValues] }))} type="button">{!availableValues.length ? '已全部授权' : allAvailableSelected ? '清空选择' : '全选值域'}</button></div><div className="value-picker-scroll" onWheel={scrollValuePicker}><div className="value-picker">{dimensions[activeDimensionId].values.map((value) => { const existing = existingValues.has(value); const picked = existing || values.includes(value); return <label className={`${picked ? 'picked' : ''} ${existing ? 'existing' : ''}`} key={value}><input checked={picked} disabled={existing} onChange={() => toggleValue(value)} type="checkbox" /><i>{picked ? '✓' : ''}</i><span>{value}</span></label> })}</div></div></div>
        <label className="editor-switch"><input checked={enabled} onChange={(event) => setEnabled(event.target.checked)} type="checkbox" /><i /><span><b>立即启用</b><small>启用后，用户的 SmartBI 查询会自动附加此范围。</small></span></label>
        {error && <div className="editor-error">{error}</div>}
      </div>
      <div className="scope-editor-actions"><button disabled={saving} onClick={onClose} type="button">取消</button><button className="permission-primary" disabled={saving || !username.trim() || !selectedCount} onClick={() => void save()} type="button">{saving ? '正在保存…' : `新增 ${selectedCount || ''} 条授权`}</button></div>
    </form>
  </div>
}
