import type { CSSProperties } from 'react'
import { artifactDownloadUrl } from './api'
import type {
  ArtifactView,
  AttributionArtifactPayload,
  ChartArtifactPayload,
  FileArtifactPayload,
  QueryArtifactPayload,
} from './types'

const COLORS = ['#3979d5', '#23a47b', '#f29f3d', '#8d63d8', '#e45c72', '#3aa6b9']

function formatValue(value: unknown, unit?: string | null) {
  if (value === null || value === undefined || value === '') return '—'
  if (typeof value === 'number') {
    const formatted = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 4 }).format(value)
    return unit ? `${formatted} ${unit}` : formatted
  }
  return String(value)
}

function QueryArtifact({ artifact }: { artifact: ArtifactView<QueryArtifactPayload> }) {
  const { payload } = artifact
  return (
    <section className="artifact-card artifact-table-card">
      <header className="artifact-card-header">
        <div><span>查询结果</span><strong>{artifact.title || '数据结果'}</strong></div>
        <small>{payload.totalRows ?? payload.rows.length} 条数据</small>
      </header>
      {payload.summary && <p className="artifact-summary">{payload.summary}</p>}
      <div className="artifact-table-wrap">
        <table>
          <thead><tr>{payload.columns.map((column) => <th key={column.id}>{column.displayName}</th>)}</tr></thead>
          <tbody>
            {payload.rows.map((row, index) => (
              <tr key={index}>{payload.columns.map((column) => (
                <td className={column.role === 'METRIC' ? 'numeric' : ''} key={column.id}>
                  {formatValue(row[column.id], column.role === 'METRIC' ? column.unit : null)}
                </td>
              ))}</tr>
            ))}
          </tbody>
        </table>
      </div>
      {payload.truncated && <footer>结果数据较多，当前仅展示部分记录。</footer>}
    </section>
  )
}

function chartBounds(payload: ChartArtifactPayload) {
  const values = payload.series.flatMap((series) => series.values).filter((value): value is number => value !== null)
  const min = Math.min(0, ...values)
  const max = Math.max(0, ...values)
  return { min, max: max === min ? min + 1 : max }
}

function axisLabelIndexes(length: number) {
  if (length <= 8) return new Set(Array.from({ length }, (_, index) => index))
  const step = Math.ceil(length / 6)
  return new Set(Array.from({ length }, (_, index) => index).filter((index) => index % step === 0 || index === length - 1))
}

function LineChart({ payload }: { payload: ChartArtifactPayload }) {
  const width = 760
  const height = 290
  const left = 58
  const right = 24
  const top = 24
  const bottom = 52
  const plotWidth = width - left - right
  const plotHeight = height - top - bottom
  const { min, max } = chartBounds(payload)
  const labels = axisLabelIndexes(payload.categories.length)
  const x = (index: number) => left + (payload.categories.length <= 1 ? plotWidth / 2 : index * plotWidth / (payload.categories.length - 1))
  const y = (value: number) => top + (max - value) * plotHeight / (max - min)
  return (
    <svg className="artifact-chart-svg" role="img" viewBox={`0 0 ${width} ${height}`} aria-label="折线图">
      {[0, 1, 2, 3, 4].map((tick) => {
        const value = max - tick * (max - min) / 4
        const position = top + tick * plotHeight / 4
        return <g key={tick}><line x1={left} x2={width - right} y1={position} y2={position} className="chart-grid" /><text x={left - 10} y={position + 4} textAnchor="end">{formatValue(value)}</text></g>
      })}
      {payload.series.map((series, seriesIndex) => {
        const points = series.values.map((value, index) => value === null ? null : `${x(index)},${y(value)}`).filter(Boolean).join(' ')
        return <g key={series.metricId}>
          <polyline points={points} fill="none" stroke={COLORS[seriesIndex % COLORS.length]} strokeWidth="3" strokeLinejoin="round" strokeLinecap="round" />
          {series.values.map((value, index) => value === null ? null : <circle key={index} cx={x(index)} cy={y(value)} r="4" fill={COLORS[seriesIndex % COLORS.length]}><title>{`${payload.categories[index]}：${formatValue(value, series.unit)}`}</title></circle>)}
        </g>
      })}
      {payload.categories.map((category, index) => labels.has(index) ? <text key={index} x={x(index)} y={height - 20} textAnchor="middle">{category}</text> : null)}
    </svg>
  )
}

function BarChart({ payload }: { payload: ChartArtifactPayload }) {
  const width = 760
  const height = 290
  const left = 58
  const right = 24
  const top = 24
  const bottom = 52
  const plotWidth = width - left - right
  const plotHeight = height - top - bottom
  const { min, max } = chartBounds(payload)
  const groupWidth = plotWidth / Math.max(1, payload.categories.length)
  const barWidth = Math.min(34, groupWidth * .72 / Math.max(1, payload.series.length))
  const zeroY = top + max * plotHeight / (max - min)
  const labels = axisLabelIndexes(payload.categories.length)
  return (
    <svg className="artifact-chart-svg" role="img" viewBox={`0 0 ${width} ${height}`} aria-label="柱状图">
      <line x1={left} x2={width - right} y1={zeroY} y2={zeroY} className="chart-axis" />
      {payload.categories.map((category, categoryIndex) => {
        const groupStart = left + categoryIndex * groupWidth + (groupWidth - barWidth * payload.series.length) / 2
        return <g key={categoryIndex}>
          {payload.series.map((series, seriesIndex) => {
            const value = series.values[categoryIndex]
            if (value === null || value === undefined) return null
            const valueY = top + (max - value) * plotHeight / (max - min)
            return <rect key={series.metricId} x={groupStart + seriesIndex * barWidth} y={Math.min(valueY, zeroY)} width={Math.max(2, barWidth - 3)} height={Math.max(1, Math.abs(zeroY - valueY))} rx="3" fill={COLORS[seriesIndex % COLORS.length]}><title>{`${category}：${formatValue(value, series.unit)}`}</title></rect>
          })}
          {labels.has(categoryIndex) && <text x={left + categoryIndex * groupWidth + groupWidth / 2} y={height - 20} textAnchor="middle">{category}</text>}
        </g>
      })}
    </svg>
  )
}

function PieChart({ payload }: { payload: ChartArtifactPayload }) {
  const series = payload.series[0]
  const values = (series?.values || []).map((value) => Math.max(0, value || 0))
  const total = values.reduce((sum, value) => sum + value, 0)
  let cursor = 0
  const stops = values.map((value, index) => {
    const start = total ? cursor / total * 100 : 0
    cursor += value
    const end = total ? cursor / total * 100 : 0
    return `${COLORS[index % COLORS.length]} ${start}% ${end}%`
  })
  const style = { '--pie-gradient': total ? `conic-gradient(${stops.join(',')})` : '#eef2f6' } as CSSProperties
  return <div className="artifact-pie-layout">
    <div className="artifact-pie" style={style} role="img" aria-label="饼图"><span>{formatValue(total, series?.unit)}</span></div>
    <div className="artifact-pie-legend">{payload.categories.map((category, index) => (
      <div key={index}><i style={{ background: COLORS[index % COLORS.length] }} /><span>{category}</span><strong>{formatValue(values[index], series?.unit)}</strong></div>
    ))}</div>
  </div>
}

function ChartArtifact({ artifact }: { artifact: ArtifactView<ChartArtifactPayload> }) {
  const payload = artifact.payload
  const type = payload.chartType === 'AUTO' ? 'LINE' : payload.chartType
  const visible: ChartArtifactPayload = {
    ...payload,
    categories: payload.categories.slice(0, 30),
    series: payload.series.map((series) => ({ ...series, values: series.values.slice(0, 30) })),
  }
  return <section className="artifact-card artifact-chart-card">
    <header className="artifact-card-header">
      <div><span>{type === 'PIE' ? '饼图' : type === 'BAR' ? '柱状图' : '折线图'}</span><strong>{artifact.title || '数据图表'}</strong></div>
      <small>按{payload.dimensionDisplayName}</small>
    </header>
    <div className="artifact-chart-legend">{payload.series.map((series, index) => <span key={series.metricId}><i style={{ background: COLORS[index % COLORS.length] }} />{series.displayName}{series.unit ? `（${series.unit}）` : ''}</span>)}</div>
    {type === 'PIE' ? <PieChart payload={visible} /> : type === 'BAR' ? <BarChart payload={visible} /> : <LineChart payload={visible} />}
    {payload.categories.length > 30 && <footer>图表仅展示前 30 个维度值，原始数据仍完整保留。</footer>}
  </section>
}

function AttributionArtifact({ artifact }: { artifact: ArtifactView<AttributionArtifactPayload> }) {
  const { payload } = artifact
  return <section className="artifact-card artifact-report-card">
    <header className="artifact-card-header"><div><span>归因报告</span><strong>{artifact.title || payload.metricName}</strong></div><small>{payload.currentPeriod} 对比 {payload.comparisonPeriod}</small></header>
    {payload.overall && <div className="artifact-report-kpis">
      <article><span>本期</span><strong>{formatValue(payload.overall.currentValue)}</strong></article>
      <article><span>对比期</span><strong>{formatValue(payload.overall.comparisonValue)}</strong></article>
      <article><span>变化</span><strong>{formatValue(payload.overall.changeAmount)}</strong><small>{payload.overall.changeRate === null ? '' : `${formatValue(payload.overall.changeRate * 100)}%`}</small></article>
    </div>}
    <p className="artifact-report-summary">{payload.summary}</p>
    <div className="artifact-report-columns">
      <div><strong>关键发现</strong>{payload.findings.length ? <ul>{payload.findings.map((item, index) => <li key={index}>{item}</li>)}</ul> : <p>暂无关键发现</p>}</div>
      <div><strong>建议</strong>{payload.recommendations.length ? <ul>{payload.recommendations.map((item, index) => <li key={index}>{item}</li>)}</ul> : <p>暂无建议</p>}</div>
    </div>
  </section>
}

function FileArtifact({ artifact, userId }: { artifact: ArtifactView<FileArtifactPayload>; userId: string }) {
  const { payload } = artifact
  return <section className="artifact-card artifact-file-card">
    <div className={`artifact-file-icon ${payload.format.toLowerCase()}`}>{payload.format}</div>
    <div><span>原始数据文件</span><strong>{payload.fileName}</strong><small>{formatValue(payload.sizeBytes / 1024)} KB · {artifact.rowCount ?? 0} 条数据</small></div>
    <a href={artifactDownloadUrl(userId, artifact.artifactId)} download={payload.fileName}>下载文件</a>
  </section>
}

export default function ArtifactRenderer({ artifact, userId }: { artifact: ArtifactView; userId: string }) {
  if (!artifact.payload) return null
  switch (artifact.artifactType) {
    case 'QUERY_RESULT': return <QueryArtifact artifact={artifact as ArtifactView<QueryArtifactPayload>} />
    case 'CHART': return <ChartArtifact artifact={artifact as ArtifactView<ChartArtifactPayload>} />
    case 'ATTRIBUTION_RESULT': return <AttributionArtifact artifact={artifact as ArtifactView<AttributionArtifactPayload>} />
    case 'FILE': return <FileArtifact artifact={artifact as ArtifactView<FileArtifactPayload>} userId={userId} />
    default: return <section className="artifact-card artifact-unsupported">暂不支持展示该分析产物（{artifact.artifactType}）</section>
  }
}
