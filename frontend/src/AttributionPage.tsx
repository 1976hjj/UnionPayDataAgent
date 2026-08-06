import { useEffect, useMemo, useState } from 'react'
import type { FormEvent } from 'react'

type Metric = { id: string; name: string }
type Dimension = {
  id: string
  name: string
  category: string
  description: string
  attributionEnabled: boolean
}
type Limits = {
  defaultMaxDepth: number
  hardMaxDepth: number
  defaultMaxQueries: number
  hardMaxQueries: number
  defaultTopN: number
  hardTopN: number
}
type AttributionMetadata = { metrics: Metric[]; dimensions: Dimension[]; limits: Limits }
type FilterDraft = { key: number; dimensionId: string; operator: 'EQUALS' | 'IN'; value: string }
type OverallEvidence = {
  currentValue: number
  comparisonValue: number
  changeAmount: number
  changeRate: number | null
  smartBiComparisonRate: number | null
  direction: Direction
}
type Direction = 'UP' | 'DOWN' | 'FLAT'
type MemberEvidence = {
  rank: number
  memberValue: string
  currentValue: number
  comparisonValue: number
  changeAmount: number
  changeRate: number | null
  contributionRate: number
  direction: Direction
  alignedWithOverall: boolean
}
type Evidence = {
  id: string
  depth: number
  hypothesis: string
  dimensionId: string
  dimensionName: string
  pathFilters: { dimensionId: string; operator: string; values: string[] }[]
  members: MemberEvidence[]
  primaryDriver: MemberEvidence
  topNCoverageRate: number
  dataConsistent: boolean
}
type PathNode = {
  depth: number
  dimensionId: string
  dimensionName: string
  memberValue: string
  changeAmount: number
  contributionRate: number
}
type ReasoningStep = {
  depth: number
  phase: 'PLAN' | 'REASON' | 'REPORT'
  hypothesis: string | null
  proposedDimensions: string[]
  selectedEvidenceId: string | null
  selectedMember: string | null
  nextDimension: string | null
  reason: string
}
type WorkflowStep = { node: string; name: string; status: string; detail: string }
type QueryTrace = {
  stage: string
  dimensionCode: string | null
  sqlPreview: string
  request: { rows: string[]; columns: string[]; filters: { name: string; operation: string; values: string[] }[] }
}
type AttributionResponse = {
  status: string
  metricId: string
  metricName: string
  currentPeriod: string
  comparisonPeriod: string
  overall: OverallEvidence
  evidence: Evidence[]
  primaryPath: PathNode[]
  reasoning: ReasoningStep[]
  stop: { code: string; detail: string }
  report: { summary: string; findings: string[]; recommendations: string[] }
  queryCount: number
  executionEngine: string
  workflowSteps: WorkflowStep[]
  smartBiQueries: QueryTrace[]
}

const EMPTY_LIMITS: Limits = {
  defaultMaxDepth: 2,
  hardMaxDepth: 3,
  defaultMaxQueries: 8,
  hardMaxQueries: 12,
  defaultTopN: 5,
  hardTopN: 10,
}

const numberFormatter = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 })

function formatNumber(value: number | null | undefined) {
  return value == null ? '—' : numberFormatter.format(value)
}

function formatSigned(value: number | null | undefined) {
  if (value == null) return '—'
  return `${value > 0 ? '+' : ''}${formatNumber(value)}`
}

function formatPercent(value: number | null | undefined) {
  if (value == null) return '—'
  return `${value > 0 ? '+' : ''}${formatNumber(value)}%`
}

function directionLabel(direction: Direction) {
  return direction === 'UP' ? '上升' : direction === 'DOWN' ? '下降' : '持平'
}

function previousMonth(period: string) {
  const [year, month] = period.split('-').map(Number)
  if (!year || !month) return ''
  const date = new Date(year, month - 2, 1)
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`
}

async function responseError(response: Response) {
  try {
    const body = await response.json() as { detail?: string; message?: string; error?: string }
    return body.detail || body.message || body.error || `请求失败（HTTP ${response.status}）`
  } catch {
    return `请求失败（HTTP ${response.status}）`
  }
}

function EvidenceChart({ evidence }: { evidence: Evidence }) {
  const maximum = Math.max(...evidence.members.map((member) => Math.abs(member.contributionRate)), 1)
  return (
    <div className="attribution-evidence-chart" aria-label={`${evidence.dimensionName}贡献度图`}>
      {evidence.members.map((member) => {
        const width = Math.abs(member.contributionRate) / maximum * 100
        return (
          <div className="attribution-evidence-bar" key={member.memberValue}>
            <span title={member.memberValue}>{member.rank}. {member.memberValue}</span>
            <div className="attribution-bar-track">
              <i className={member.contributionRate >= 0 ? 'positive' : 'negative'} style={{ width: `${width}%` }} />
            </div>
            <b className={member.contributionRate >= 0 ? 'positive' : 'negative'}>{formatPercent(member.contributionRate)}</b>
          </div>
        )
      })}
    </div>
  )
}

function EvidenceCard({ evidence, periods }: { evidence: Evidence; periods: [string, string] }) {
  const [currentPeriod, comparisonPeriod] = periods
  return (
    <article className="attribution-evidence-card">
      <header>
        <div>
          <small>第 {evidence.depth} 层 · {evidence.id}</small>
          <h3>{evidence.dimensionName}</h3>
          <p>{evidence.hypothesis}</p>
        </div>
        <div className="evidence-badges">
          <span>TopN覆盖 {formatPercent(evidence.topNCoverageRate)}</span>
          <span className={evidence.dataConsistent ? 'verified' : 'warning'}>{evidence.dataConsistent ? '数据一致' : '数据异常'}</span>
        </div>
      </header>
      {evidence.pathFilters.length > 0 && (
        <div className="evidence-scope">分析范围：{evidence.pathFilters.map((filter) => `${filter.dimensionId}=${filter.values.join('/')}`).join(' · ')}</div>
      )}
      <EvidenceChart evidence={evidence} />
      <div className="comparison-table-wrap">
        <table className="comparison-table attribution-evidence-table">
          <thead><tr><th>排名 / 成员</th><th className="numeric">{comparisonPeriod}</th><th className="numeric">{currentPeriod}</th><th className="numeric">变化量</th><th className="numeric">变化率</th><th className="numeric">贡献度</th></tr></thead>
          <tbody>{evidence.members.map((member) => (
            <tr className={member.memberValue === evidence.primaryDriver.memberValue ? 'primary-driver-row' : ''} key={member.memberValue}>
              <td><b>#{member.rank}</b> {member.memberValue}{member.memberValue === evidence.primaryDriver.memberValue && <em>主驱动</em>}</td>
              <td className="numeric muted-value">{formatNumber(member.comparisonValue)}</td>
              <td className="numeric">{formatNumber(member.currentValue)}</td>
              <td className={`numeric change-cell ${member.direction.toLowerCase()}`}>{formatSigned(member.changeAmount)}</td>
              <td className={`numeric change-cell ${member.direction.toLowerCase()}`}>{formatPercent(member.changeRate)}</td>
              <td className={`numeric contribution-cell ${member.contributionRate >= 0 ? 'positive' : 'negative'}`}>{formatPercent(member.contributionRate)}</td>
            </tr>
          ))}</tbody>
        </table>
      </div>
    </article>
  )
}

function reportMarkdown(result: AttributionResponse) {
  const path = result.primaryPath.map((node) => `${node.dimensionName}：${node.memberValue}`).join(' → ') || '未形成下钻路径'
  return [
    `# ${result.metricName}归因分析报告`,
    '',
    `- 周期：${result.currentPeriod} 对比 ${result.comparisonPeriod}`,
    `- 整体变化：${formatSigned(result.overall.changeAmount)}（${formatPercent(result.overall.changeRate)}）`,
    `- 主路径：${path}`,
    `- 停止原因：${result.stop.detail}`,
    '',
    '## 结论摘要', result.report.summary,
    '',
    '## 关键发现', ...result.report.findings.map((item) => `- ${item}`),
    '',
    '## 建议', ...result.report.recommendations.map((item) => `- ${item}`),
  ].join('\n')
}

export default function AttributionPage({ selectedModel }: { selectedModel: string }) {
  const [metadata, setMetadata] = useState<AttributionMetadata | null>(null)
  const [metricId, setMetricId] = useState('trans_rmb_amt_m')
  const [currentPeriod, setCurrentPeriod] = useState('2026-07')
  const [comparisonPeriod, setComparisonPeriod] = useState('2026-06')
  const [maxDepth, setMaxDepth] = useState(2)
  const [maxQueries, setMaxQueries] = useState(8)
  const [topN, setTopN] = useState(5)
  const [filters, setFilters] = useState<FilterDraft[]>([])
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const [result, setResult] = useState<AttributionResponse | null>(null)
  const [tab, setTab] = useState<'report' | 'evidence' | 'process'>('report')
  const [copied, setCopied] = useState(false)

  const limits = metadata?.limits ?? EMPTY_LIMITS
  const dimensionMap = useMemo(() => new Map(metadata?.dimensions.map((dimension) => [dimension.id, dimension]) ?? []), [metadata])

  useEffect(() => {
    let active = true
    fetch('/api/attribution/metadata')
      .then(async (response) => {
        if (!response.ok) throw new Error(await responseError(response))
        return response.json() as Promise<AttributionMetadata>
      })
      .then((value) => {
        if (!active) return
        setMetadata(value)
        if (value.metrics.length && !value.metrics.some((metric) => metric.id === metricId)) setMetricId(value.metrics[0].id)
        setMaxDepth(value.limits.defaultMaxDepth)
        setMaxQueries(value.limits.defaultMaxQueries)
        setTopN(value.limits.defaultTopN)
      })
      .catch((reason) => active && setError(reason instanceof Error ? reason.message : '归因元数据加载失败'))
    return () => { active = false }
  }, [])

  function addFilter() {
    const firstDimension = metadata?.dimensions[0]?.id
    if (!firstDimension) return
    setFilters((items) => [...items, { key: Date.now(), dimensionId: firstDimension, operator: 'EQUALS', value: '' }])
  }

  function updateFilter(key: number, patch: Partial<FilterDraft>) {
    setFilters((items) => items.map((item) => item.key === key ? { ...item, ...patch } : item))
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    setCopied(false)
    if (!metricId || !currentPeriod || !comparisonPeriod) return setError('请完整填写度量与对比周期')
    if (currentPeriod <= comparisonPeriod) return setError('当前周期必须晚于对比周期')
    const emptyFilter = filters.find((filter) => !filter.value.trim())
    if (emptyFilter) return setError('维度过滤值不能为空')
    setError('')
    setPending(true)
    setResult(null)
    try {
      const response = await fetch('/api/attribution/analyze', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          metricId,
          currentPeriod,
          comparisonPeriod,
          maxDepth,
          maxQueries,
          topN,
          model: selectedModel,
          dimensionFilters: filters.map((filter) => ({
            dimensionId: filter.dimensionId,
            operator: filter.operator,
            values: filter.operator === 'IN' ? filter.value.split(',').map((value) => value.trim()).filter(Boolean) : [filter.value.trim()],
          })),
        }),
      })
      if (!response.ok) throw new Error(await responseError(response))
      setResult(await response.json() as AttributionResponse)
      setTab('report')
      window.dispatchEvent(new Event('model-health-changed'))
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '归因分析请求失败')
    } finally {
      setPending(false)
    }
  }

  async function copyReport() {
    if (!result) return
    try {
      await navigator.clipboard.writeText(reportMarkdown(result))
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1800)
    } catch {
      setError('浏览器未授予剪贴板权限，请使用“导出 Markdown”')
    }
  }

  function downloadReport() {
    if (!result) return
    const url = URL.createObjectURL(new Blob([reportMarkdown(result)], { type: 'text/markdown;charset=utf-8' }))
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `归因分析-${result.metricId}-${result.currentPeriod}.md`
    anchor.click()
    URL.revokeObjectURL(url)
  }

  return (
    <section className="attribution-page">
      <div className="page-heading">
        <div><p>支付数据智能分析</p><h1>智能归因分析</h1><span>由大模型提出假设并动态选择下钻方向，所有数值由 Java 基于 SmartBI Evidence 确定性计算</span></div>
        <div className="mock-badge"><i /> LangGraph4j · Mock SmartBI</div>
      </div>

      <form className="workspace-card attribution-form" onSubmit={submit} noValidate>
        <div className="card-title"><div><span className="step-number">1</span><div><h2>设置归因任务</h2><p>选择度量与两个周期；维度由 Agent 在白名单内动态探索</p></div></div></div>
        <div className="attribution-config attribution-mvp-config">
          <div className="config-row"><label htmlFor="analysis-metric">分析度量 <b>*</b></label><select id="analysis-metric" value={metricId} onChange={(event) => setMetricId(event.target.value)} disabled={!metadata}>{metadata?.metrics.map((metric) => <option value={metric.id} key={metric.id}>{metric.name}</option>) ?? <option>加载中…</option>}</select></div>
          <div className="config-row"><label htmlFor="current-period">当前周期 <b>*</b></label><input id="current-period" type="month" value={currentPeriod} onChange={(event) => { setCurrentPeriod(event.target.value); setComparisonPeriod(previousMonth(event.target.value)) }} /></div>
          <div className="config-row"><label htmlFor="comparison-period">对比周期 <b>*</b></label><input id="comparison-period" type="month" value={comparisonPeriod} onChange={(event) => setComparisonPeriod(event.target.value)} /></div>
          <div className="config-divider" />
          <div className="attribution-limit-grid">
            <label>最大下钻深度 <input type="number" min="1" max={limits.hardMaxDepth} value={maxDepth} onChange={(event) => setMaxDepth(Number(event.target.value))} /><small>最多 {limits.hardMaxDepth} 层</small></label>
            <label>最大查询次数 <input type="number" min="2" max={limits.hardMaxQueries} value={maxQueries} onChange={(event) => setMaxQueries(Number(event.target.value))} /><small>最多 {limits.hardMaxQueries} 次</small></label>
            <label>每维展示 TopN <input type="number" min="1" max={limits.hardTopN} value={topN} onChange={(event) => setTopN(Number(event.target.value))} /><small>最多 {limits.hardTopN} 项</small></label>
          </div>
          <div className="config-divider" />
          <div className="filter-heading"><div><strong>业务范围过滤</strong><span>可选；过滤字段不会再被 Agent 用作下钻维度</span></div><button type="button" onClick={addFilter} disabled={!metadata}>+ 添加条件</button></div>
          {filters.map((filter) => (
            <div className="attribution-filter-row" key={filter.key}>
              <select aria-label="过滤维度" value={filter.dimensionId} onChange={(event) => updateFilter(filter.key, { dimensionId: event.target.value })}>{metadata?.dimensions.map((dimension) => <option value={dimension.id} key={dimension.id}>{dimension.name}</option>)}</select>
              <select aria-label="过滤操作" value={filter.operator} onChange={(event) => updateFilter(filter.key, { operator: event.target.value as FilterDraft['operator'] })}><option value="EQUALS">等于</option><option value="IN">属于（逗号分隔）</option></select>
              <input aria-label="过滤值" value={filter.value} placeholder="输入成员值" onChange={(event) => updateFilter(filter.key, { value: event.target.value })} />
              <button type="button" aria-label="删除过滤条件" onClick={() => setFilters((items) => items.filter((item) => item.key !== filter.key))}>×</button>
            </div>
          ))}
        </div>
        {error && <div className="validation-box" role="alert"><span>• {error}</span></div>}
        <div className="form-actions"><span>首轮最多并行探索 3 个维度，之后沿证据最强路径继续下钻</span><button className="primary-button" disabled={pending || !metadata} type="submit">{pending ? 'Agent 分析中…' : '开始归因分析'}</button></div>
        {pending && <div className="attribution-running" aria-live="polite"><i /><div><strong>正在执行智能归因</strong><span>总体查询 → 维度假设 → 并行取证 → 动态下钻 → 生成报告</span></div></div>}
      </form>

      {result && (
        <div className="result-card attribution-agent-result" aria-live="polite">
          <div className="result-heading">
            <div><h2>{result.metricName}归因报告</h2><p>{result.currentPeriod} 对比 {result.comparisonPeriod} · {result.executionEngine}</p></div>
            <div className={`change-value ${result.overall.direction.toLowerCase()}`}><small>整体{directionLabel(result.overall.direction)}</small><strong>{formatSigned(result.overall.changeAmount)}</strong><span>{formatPercent(result.overall.changeRate)}</span></div>
          </div>

          <div className="attribution-kpis">
            <div><span>本期</span><strong>{formatNumber(result.overall.currentValue)}</strong></div>
            <div><span>对比期</span><strong>{formatNumber(result.overall.comparisonValue)}</strong></div>
            <div><span>SmartBI衍生值</span><strong>{formatPercent(result.overall.smartBiComparisonRate)}</strong></div>
            <div><span>实际查询</span><strong>{result.queryCount} 次</strong></div>
            <div><span>停止原因</span><strong>{result.stop.code}</strong><small>{result.stop.detail}</small></div>
          </div>

          <section className="primary-path-panel">
            <header><div><small>Agent 收敛结果</small><h3>主归因路径</h3></div><span>{result.primaryPath.length} 层下钻</span></header>
            <div className="attribution-path">
              <div className="path-start"><small>整体变化</small><strong>{formatSigned(result.overall.changeAmount)}</strong></div>
              {result.primaryPath.map((node) => <div className="path-arrow-node" key={`${node.depth}-${node.dimensionId}`}><i>→</i><article><small>第 {node.depth} 层 · {node.dimensionName}</small><strong>{node.memberValue}</strong><span>贡献度 {formatPercent(node.contributionRate)} · 变化 {formatSigned(node.changeAmount)}</span></article></div>)}
              {!result.primaryPath.length && <div className="empty-path">当前证据未形成有效下钻路径</div>}
            </div>
          </section>

          <div className="attribution-result-tabs" role="tablist">
            <button type="button" role="tab" aria-selected={tab === 'report'} className={tab === 'report' ? 'active' : ''} onClick={() => setTab('report')}>最终报告</button>
            <button type="button" role="tab" aria-selected={tab === 'evidence'} className={tab === 'evidence' ? 'active' : ''} onClick={() => setTab('evidence')}>证据明细 <em>{result.evidence.length}</em></button>
            <button type="button" role="tab" aria-selected={tab === 'process'} className={tab === 'process' ? 'active' : ''} onClick={() => setTab('process')}>Agent 过程</button>
          </div>

          {tab === 'report' && <section className="attribution-report-panel">
            <header><div><small>由 LLM 基于已验证 Evidence 组织，不承担数值计算</small><h3>分析结论</h3></div><div><button type="button" onClick={copyReport}>{copied ? '已复制' : '复制报告'}</button><button type="button" onClick={downloadReport}>导出 Markdown</button></div></header>
            <div className="report-summary"><span>摘要</span><p>{result.report.summary}</p></div>
            <div className="report-columns"><article><h4>关键发现</h4><ol>{result.report.findings.map((finding, index) => <li key={`${index}-${finding}`}>{finding}</li>)}</ol></article><article><h4>建议动作</h4><ul>{result.report.recommendations.map((recommendation, index) => <li key={`${index}-${recommendation}`}>{recommendation}</li>)}</ul></article></div>
          </section>}

          {tab === 'evidence' && <div className="attribution-evidence-list">{result.evidence.map((evidence) => <EvidenceCard evidence={evidence} periods={[result.currentPeriod, result.comparisonPeriod]} key={evidence.id} />)}</div>}

          {tab === 'process' && <section className="attribution-process-panel">
            <div className="reasoning-timeline">
              {result.reasoning.map((step, index) => <article key={`${step.phase}-${index}`}><i>{index + 1}</i><div><header><strong>{step.phase === 'PLAN' ? '制定探索计划' : step.phase === 'REASON' ? `第 ${step.depth} 层证据推理` : '生成最终报告'}</strong><span>{step.proposedDimensions.map((id) => dimensionMap.get(id)?.name ?? id).join(' · ')}</span></header>{step.hypothesis && <p>{step.hypothesis}</p>}<small>{step.reason}</small>{step.selectedMember && <em>选择：{step.selectedMember}{step.nextDimension ? ` → ${dimensionMap.get(step.nextDimension)?.name ?? step.nextDimension}` : ''}</em>}</div></article>)}
            </div>
            <details className="workflow-trace"><summary><div><strong>LangGraph4j 节点轨迹</strong><span>查看每个工作流节点的状态与结果</span></div><small>{result.workflowSteps.length} 个节点</small></summary><div className="workflow-step-list">{result.workflowSteps.map((step, index) => <div className="workflow-step" key={`${step.node}-${index}`}><div className="workflow-step-index">{index + 1}</div><div><strong>{step.name}</strong><code>{step.node}</code><p>{step.detail}</p></div><span>✓ {step.status}</span></div>)}</div></details>
            <details className="sql-preview attribution-sql-preview"><summary>查看 {result.smartBiQueries.length} 次 SmartBI 查询</summary><div className="sql-preview-list">{result.smartBiQueries.map((query, index) => <section key={`${query.stage}-${index}`}><strong>{index + 1}. {query.stage}{query.dimensionCode ? ` · ${dimensionMap.get(query.dimensionCode)?.name ?? query.dimensionCode}` : ''}</strong><pre><code>{query.sqlPreview}</code></pre><small>rows: {query.request.rows.join(', ')} · columns: {query.request.columns.join(', ')}</small></section>)}</div></details>
          </section>}
        </div>
      )}
    </section>
  )
}
