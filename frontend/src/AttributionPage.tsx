import { useState } from 'react'
import type { FormEvent } from 'react'

type Dimension = { id: string; name: string }
type MemberContribution = {
  memberName: string
  currentValue: string
  comparisonValue: string
  changeAmount: string
  changeRate: string
  contributionRate: number
  direction: 'UP' | 'DOWN'
}
type ContributionResult = {
  dimensionCode: string
  dimensionName: string
  members: MemberContribution[]
}
type SmartBiQueryTrace = {
  stage: string
  dimensionCode: string | null
  sqlPreview: string
}
type LlmResultMessage = {
  model: string
  role: string
  content: string
  requestMessages: { role: string; content: string }[]
}
type AttributionResult = {
  metricName: string
  currentPeriod: string
  comparisonPeriod: string
  overallChange: string
  level1DimensionCode: string
  level1DimensionName: string
  level1Driver: {
    memberCode: string
    memberName: string
    absoluteChangeAmount: string
    direction: string
    selectionReason: string
  }
  level2Results: Record<string, ContributionResult>
  totalQueryCount: number
  periodsCombinedInSingleQuery: boolean
  reportNotice: string
  executionEngine: string
  workflowSteps: {
    node: string
    name: string
    status: string
    detail: string
  }[]
  smartBiQueries: SmartBiQueryTrace[]
  llmMessage: LlmResultMessage
}

function numericValue(value: string) {
  const parsed = Number(value.replace(/[^\d.-]/g, ''))
  return Number.isFinite(parsed) ? Math.abs(parsed) : 0
}

function DimensionCharts({
  members,
  currentPeriod,
  comparisonPeriod,
}: {
  members: MemberContribution[]
  currentPeriod: string
  comparisonPeriod: string
}) {
  const valueMaximum = Math.max(
    ...members.flatMap((member) => [numericValue(member.currentValue), numericValue(member.comparisonValue)]),
    1,
  )
  const contributionMaximum = Math.max(...members.map((member) => Math.abs(member.contributionRate)), 1)

  return (
    <div className="dimension-charts">
      <div className="chart-panel">
        <div className="chart-heading">
          <div><strong>本期与对比期</strong><span>直观看各成员规模变化</span></div>
          <div className="chart-legend">
            <span><i className="legend-dot comparison" />{comparisonPeriod}</span>
            <span><i className="legend-dot current" />{currentPeriod}</span>
          </div>
        </div>
        <div className="period-bar-chart" role="img" aria-label={`${currentPeriod}与${comparisonPeriod}成员数值对比图`}>
          {members.map((member) => (
            <div className="period-bar-row" key={member.memberName}>
              <span className="chart-member">{member.memberName}</span>
              <div className="period-bars">
                <div className="period-bar-line">
                  <i className="period-bar comparison" style={{ width: `${numericValue(member.comparisonValue) / valueMaximum * 100}%` }} />
                  <b>{member.comparisonValue}</b>
                </div>
                <div className="period-bar-line">
                  <i className="period-bar current" style={{ width: `${numericValue(member.currentValue) / valueMaximum * 100}%` }} />
                  <b>{member.currentValue}</b>
                </div>
              </div>
            </div>
          ))}
        </div>
      </div>

      <div className="chart-panel">
        <div className="chart-heading">
          <div><strong>变化贡献度</strong><span>右侧推动上涨，左侧抵消上涨</span></div>
          <span className="zero-axis-note">0%</span>
        </div>
        <div className="contribution-chart" role="img" aria-label="各成员正负贡献度图">
          {members.map((member) => {
            const positive = member.contributionRate >= 0
            return (
              <div className="contribution-row" key={member.memberName}>
                <span className="chart-member">{member.memberName}</span>
                <div className="contribution-track">
                  <div className="contribution-half negative-half">
                    {!positive && <i style={{ width: `${Math.abs(member.contributionRate) / contributionMaximum * 100}%` }} />}
                  </div>
                  <div className="contribution-half positive-half">
                    {positive && <i style={{ width: `${member.contributionRate / contributionMaximum * 100}%` }} />}
                  </div>
                </div>
                <b className={positive ? 'positive' : 'negative'}>
                  {positive && member.contributionRate > 0 ? '+' : ''}{member.contributionRate}%
                </b>
              </div>
            )
          })}
        </div>
      </div>
    </div>
  )
}

const METRICS = [
  { id: 'rmbAmount', name: '人民币总金额' },
  { id: 'transactionCount', name: '交易笔数' },
  { id: 'successRate', name: '支付成功率' },
]

const PERIODS = [
  { id: '2026-07', name: '2026年7月' },
  { id: '2026-06', name: '2026年6月' },
  { id: '2026-05', name: '2026年5月' },
]

const DIMENSIONS: Dimension[] = [
  { id: 'acquiringRegion', name: '收单地区' },
  { id: 'issuingRegion', name: '发卡地区' },
  { id: 'acquiringInstitution', name: '收单机构' },
  { id: 'transactionMedia', name: '交易介质' },
]

const BUSINESS_SCOPES = [
  { id: '', name: '全部业务' },
  { id: 'foreignCardDomestic', name: '外卡内用' },
  { id: 'domesticCardOverseas', name: '内卡外用' },
  { id: 'domestic', name: '境内业务' },
]

function comparisonPeriod(period: string, comparison: string) {
  const [year, month] = period.split('-').map(Number)
  if (comparison === 'yearOnYear') return `${year - 1}年${month}月`
  const date = new Date(year, month - 2, 1)
  return `${date.getFullYear()}年${date.getMonth() + 1}月`
}

function Level2MultiSelect({
  options,
  selected,
  onChange,
}: {
  options: Dimension[]
  selected: string[]
  onChange: (next: string[]) => void
}) {
  const allSelected = selected.length === options.length

  function toggle(id: string) {
    if (selected.includes(id)) {
      onChange(selected.filter((item) => item !== id))
    } else if (selected.length < 3) {
      onChange([...selected, id])
    }
  }

  return (
    <div className="level2-selector">
      <details>
        <summary>
          <span>{selected.length ? `已选择 ${selected.length} 个二级维度` : '请选择二级维度'}</span>
          <small>{selected.length}/3</small>
          <i>⌄</i>
        </summary>
        <div className="level2-menu">
          <label className="level2-select-all">
            <input type="checkbox" checked={allSelected} onChange={() => onChange(allSelected ? [] : options.map((option) => option.id))} />
            <span>{allSelected ? '✓' : ''}</span>
            选择全部剩余维度
          </label>
          {options.map((option) => {
            const checked = selected.includes(option.id)
            return (
              <label className="level2-option" key={option.id}>
                <input type="checkbox" checked={checked} onChange={() => toggle(option.id)} />
                <span>{checked ? '✓' : ''}</span>
                {option.name}
              </label>
            )
          })}
        </div>
      </details>
      {selected.length > 0 && (
        <div className="selected-dimension-tags" aria-label="已选二级维度">
          {selected.map((id) => {
            const name = options.find((option) => option.id === id)?.name ?? id
            return <button type="button" key={id} onClick={() => toggle(id)}>{name}<span>×</span></button>
          })}
        </div>
      )}
    </div>
  )
}

export default function AttributionPage() {
  const [metric, setMetric] = useState('rmbAmount')
  const [currentPeriod, setCurrentPeriod] = useState('2026-07')
  const [comparison, setComparison] = useState('monthOnMonth')
  const [levelOne, setLevelOne] = useState('acquiringRegion')
  const [levelTwoDimensions, setLevelTwoDimensions] = useState<string[]>(['acquiringInstitution'])
  const [businessScope, setBusinessScope] = useState('foreignCardDomestic')
  const [errors, setErrors] = useState<string[]>([])
  const [result, setResult] = useState<AttributionResult | null>(null)
  const [pending, setPending] = useState(false)

  const comparisonLabel = comparisonPeriod(currentPeriod, comparison)
  const availableLevelTwo = DIMENSIONS.filter((item) => item.id !== levelOne)
  const expectedQueryCount = 2 + levelTwoDimensions.length

  function clearResult() {
    setResult(null)
    setErrors([])
  }

  function changeLevelOne(value: string) {
    setLevelOne(value)
    const remaining = DIMENSIONS.filter((item) => item.id !== value)
    const retained = levelTwoDimensions.filter((id) => id !== value)
    setLevelTwoDimensions(retained.length ? retained : [remaining[0].id])
    clearResult()
  }

  function changeLevelTwo(next: string[]) {
    setLevelTwoDimensions([...new Set(next.filter((id) => id !== levelOne))].slice(0, 3))
    clearResult()
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    const nextErrors = [
      metric ? '' : '请选择分析度量',
      currentPeriod ? '' : '请选择当前周期',
      comparison ? '' : '请选择对比方式',
      levelOne ? '' : '请选择一级维度',
      levelTwoDimensions.length < 1 ? '请至少选择 1 个二级维度' : '',
      levelTwoDimensions.length > 3 ? '二级维度最多选择 3 个' : '',
      levelTwoDimensions.includes(levelOne) ? '二级维度不能包含一级维度' : '',
      new Set(levelTwoDimensions).size !== levelTwoDimensions.length ? '二级维度不允许重复' : '',
    ].filter(Boolean)
    setErrors(nextErrors)
    if (nextErrors.length) return

    setPending(true)
    setResult(null)
    try {
      const response = await fetch('/api/attribution/analyze', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          metricCode: metric,
          currentPeriod,
          comparisonType: comparison,
          level1DimensionCode: levelOne,
          level2DimensionCodes: levelTwoDimensions,
          businessScope,
        }),
      })
      if (!response.ok) throw new Error('归因分析请求失败')
      setResult(await response.json() as AttributionResult)
    } catch (error) {
      setErrors([error instanceof Error ? error.message : '归因分析请求失败'])
    } finally {
      setPending(false)
    }
  }

  return (
    <section>
      <div className="page-heading">
        <div><p>支付数据智能分析</p><h1>归因分析</h1><span>选择一个一级维度，并从剩余维度中选择并行的二级分析视角</span></div>
        <div className="mock-badge"><i /> GLM-4-Flash-250414 + Mock SmartBI</div>
      </div>

      <form className="workspace-card attribution-form" onSubmit={submit} noValidate>
        <div className="card-title">
          <div><span className="step-number">1</span><div><h2>设置归因任务</h2><p>单度量、单一级维度、1至3个并行二级维度</p></div></div>
        </div>

        <div className="attribution-config">
          <div className="config-row">
            <label htmlFor="analysis-metric">分析度量 <b>*</b></label>
            <select id="analysis-metric" value={metric} onChange={(event) => { setMetric(event.target.value); clearResult() }}>
              {METRICS.map((item) => <option value={item.id} key={item.id}>{item.name}</option>)}
            </select>
          </div>
          <div className="config-row">
            <label htmlFor="current-period">当前周期 <b>*</b></label>
            <select id="current-period" value={currentPeriod} onChange={(event) => { setCurrentPeriod(event.target.value); clearResult() }}>
              {PERIODS.map((item) => <option value={item.id} key={item.id}>{item.name}</option>)}
            </select>
          </div>
          <div className="config-row">
            <label htmlFor="comparison-method">对比方式 <b>*</b></label>
            <select id="comparison-method" value={comparison} onChange={(event) => { setComparison(event.target.value); clearResult() }}>
              <option value="monthOnMonth">环比</option>
              <option value="yearOnYear">同比</option>
            </select>
          </div>
          <div className="config-row">
            <label>对比周期</label>
            <div className="readonly-field">{comparisonLabel}<span>与本期同次查询返回</span></div>
          </div>

          <div className="config-divider" />

          <div className="config-row">
            <label htmlFor="level-one">一级维度 <b>*</b></label>
            <select id="level-one" value={levelOne} onChange={(event) => changeLevelOne(event.target.value)}>
              {DIMENSIONS.map((item) => <option value={item.id} key={item.id}>{item.name}</option>)}
            </select>
          </div>
          <div className="config-row level2-config-row">
            <label>二级维度 <b>*</b></label>
            <Level2MultiSelect options={availableLevelTwo} selected={levelTwoDimensions} onChange={changeLevelTwo} />
          </div>
          <div className="config-row">
            <label htmlFor="business-scope">业务范围</label>
            <div className="field-with-note">
              <select id="business-scope" value={businessScope} onChange={(event) => { setBusinessScope(event.target.value); clearResult() }}>
                {BUSINESS_SCOPES.map((item) => <option value={item.id} key={item.id}>{item.name}</option>)}
              </select>
              <span>可选过滤条件</span>
            </div>
          </div>
          <div className="config-row">
            <label>分析层级</label>
            <div className="fixed-level"><strong>2层</strong><span>第一版固定</span></div>
          </div>
          <div className="config-row">
            <label>预计查询次数</label>
            <div className="query-count-field">
              <strong>{expectedQueryCount} 次</strong>
              <span>1次整体 + 1次一级 + {levelTwoDimensions.length}次二级</span>
            </div>
          </div>
        </div>

        {errors.length > 0 && <div className="validation-box" role="alert">{errors.map((error) => <span key={error}>• {error}</span>)}</div>}
        <div className="form-actions">
          <span>二级维度是并行分析视角，不形成三级路径</span>
          <button className="primary-button" disabled={pending} type="submit">{pending ? '分析中…' : '开始归因分析'}</button>
        </div>
      </form>

      {result && (
        <div className="result-card attribution-parallel-result" aria-live="polite">
          <div className="result-heading">
            <div><h2>{result.metricName}变化归因</h2><p>{result.currentPeriod} 对比 {result.comparisonPeriod} · {BUSINESS_SCOPES.find((item) => item.id === businessScope)?.name}</p></div>
            <div className="change-value"><small>整体变化</small><strong>{result.overallChange}</strong></div>
          </div>

          <div className="execution-summary">
            <span><b>{result.totalQueryCount}</b> 次查询</span>
            <span>整体 1次</span><i>+</i><span>一级 1次</span><i>+</i><span>二级 {Object.keys(result.level2Results).length}次</span>
            {result.periodsCombinedInSingleQuery && <em>本期/对比期同次返回</em>}
            <em>{result.executionEngine}</em>
          </div>

          <details className="workflow-trace" open>
            <summary>
              <div><strong>LangGraph4j 执行流程</strong><span>本次请求的节点状态与处理结果</span></div>
              <small>{result.workflowSteps.length} 个节点已完成</small>
            </summary>
            <div className="workflow-step-list">
              {result.workflowSteps.map((step, index) => (
                <div className="workflow-step" key={step.node}>
                  <div className="workflow-step-index">{index + 1}</div>
                  <div><strong>{step.name}</strong><code>{step.node}</code><p>{step.detail}</p></div>
                  <span>✓ 已完成</span>
                </div>
              ))}
            </div>
          </details>

          {result.llmMessage && (
            <details className="llm-message-preview">
              <summary>查看大模型 messages</summary>
              <div><span>model</span><code>{result.llmMessage.model}</code></div>
              <section className="llm-message-list">
                {result.llmMessage.requestMessages?.map((message, index) => (
                  <article key={`${message.role}-${index}`}>
                    <strong>发送 · {message.role}</strong>
                    <pre><code>{message.content}</code></pre>
                  </article>
                ))}
                <article>
                  <strong>返回 · {result.llmMessage.role}</strong>
                  <pre><code>{result.llmMessage.content}</code></pre>
                </article>
              </section>
            </details>
          )}

          <details className="sql-preview attribution-sql-preview">
            <summary>查看 {result.smartBiQueries.length} 次查询的等价 SQL</summary>
            <div className="sql-preview-list">
              {result.smartBiQueries.map((query, index) => (
                <section key={`${query.stage}-${query.dimensionCode || index}`}>
                  <strong>{index + 1}. {query.stage}{query.dimensionCode ? ` · ${query.dimensionCode}` : ''}</strong>
                  <pre><code>{query.sqlPreview}</code></pre>
                </section>
              ))}
            </div>
            <small>仅用于理解查询逻辑，SmartBI 实际执行以 JSON 查询计划为准。</small>
          </details>

          <div className="driver-card">
            <div><small>自动选中的一级下钻成员</small><strong>{result.level1Driver.memberName}</strong></div>
            <span>{result.level1DimensionName}</span>
            <p>{result.level1Driver.selectionReason} · 绝对变化额 {result.level1Driver.absoluteChangeAmount}</p>
          </div>

          <div className="parallel-notice">
            <strong>并行视角说明</strong>
            <span>{result.reportNotice}</span>
          </div>

          <div className="parallel-result-grid">
            {Object.entries(result.level2Results).map(([dimensionCode, dimensionResult]) => (
              <div className="parallel-result-block" key={dimensionCode}>
                <div className="parallel-result-heading">
                  <div><small>二级并行视角</small><strong>{dimensionResult.dimensionName}</strong></div>
                  <span>基于：{result.level1Driver.memberName} · 同维度环比</span>
                </div>
                <DimensionCharts
                  members={dimensionResult.members}
                  currentPeriod={result.currentPeriod}
                  comparisonPeriod={result.comparisonPeriod}
                />
                <div className="comparison-table-wrap">
                  <table className="comparison-table">
                    <thead>
                      <tr>
                        <th>成员</th>
                        <th className="numeric">{result.comparisonPeriod}</th>
                        <th className="numeric">{result.currentPeriod}</th>
                        <th className="numeric">变化额</th>
                        <th className="numeric">变化率</th>
                        <th className="numeric">贡献度</th>
                      </tr>
                    </thead>
                    <tbody>
                      {dimensionResult.members.map((member) => (
                        <tr key={member.memberName}>
                          <td>{member.memberName}</td>
                          <td className="numeric muted-value">{member.comparisonValue}</td>
                          <td className="numeric">{member.currentValue}</td>
                          <td className={`numeric change-cell ${member.direction === 'UP' ? 'up' : 'down'}`}>{member.changeAmount}</td>
                          <td className={`numeric change-cell ${member.direction === 'UP' ? 'up' : 'down'}`}>
                            <span>{member.direction === 'UP' ? '↑' : '↓'}</span>{member.changeRate}
                          </td>
                          <td className={`numeric contribution-cell ${member.contributionRate >= 0 ? 'positive' : 'negative'}`}>
                            {member.contributionRate > 0 ? '+' : ''}{member.contributionRate}%
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
    </section>
  )
}
