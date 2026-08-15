import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import type { FormEvent, KeyboardEvent, ReactNode } from 'react'

type DimensionSelection = { dimensionId: string; dimensionName: string; userTerm: string; rationale: string; mappingHint: string; confidence: 'HIGH' | 'MEDIUM' | 'LOW' }
type DimensionLayer = { level: number; dimensions: DimensionSelection[] }
type FilterOperator = 'EQUALS' | 'IN' | 'NOT_EQUALS' | 'NOT_IN' | 'GREATER' | 'GREATER_EQUALS' | 'LESS' | 'LESS_EQUALS' | 'BETWEEN' | 'CONTAINS'
type TemplateFilter = { dimensionId: string; dimensionName: string; userTerm: string; operator: FilterOperator; values: string[]; rationale: string; confidence: 'HIGH' | 'MEDIUM' | 'LOW' }
type DimensionTemplate = {
  name: string; mode: 'AUTO' | 'USER_DEFINED' | 'HYBRID'; metricId: string; metricName: string
  currentPeriod: string; comparisonPeriod: string; filters: TemplateFilter[]; levels: DimensionLayer[]
  continuationMode: 'AUTO' | 'STOP'; status: 'DRAFT' | 'CONFIRMED'; summary: string
}
type TemplateResponse = {
  status: 'READY_TO_CONFIRM' | 'NEEDS_CLARIFICATION'; reply: string; template: DimensionTemplate
  unmappedTerms: string[]
  mappingIssues: { userTerm: string; reason: string; candidateDimensionIds: string[] }[]
}
type ChatMessage = { role: 'user' | 'assistant'; text: string }
type StoredAttributionConversation = {
  conversationId: string
  title: string
  updatedAt: string
  messages: ChatMessage[]
  template: DimensionTemplate | null
  response: TemplateResponse | null
}

const CURRENT_USER_ID = 'demo-user'
const ATTRIBUTION_HISTORY_KEY = `payment-analysis:attribution-conversations:${CURRENT_USER_ID}`
const ACTIVE_ATTRIBUTION_KEY = `payment-analysis:active-attribution-conversation:${CURRENT_USER_ID}`
const MAX_ATTRIBUTION_HISTORY = 30

const EXAMPLE = '分析2026年7月对比6月的人民币总金额，只看卡品牌为银联。第一层同时看卡品牌、卡性质和交易渠道，第二层看IP用法和移动支付，第三层看响应码名称；后面自由探索。'

const FILTER_OPERATOR_NAMES: Record<string, string> = {
  EQUALS: '等于', IN: '属于', NOT_EQUALS: '不等于', NOT_IN: '不属于', GREATER: '大于',
  GREATER_EQUALS: '大于等于', LESS: '小于', LESS_EQUALS: '小于等于', BETWEEN: '介于', CONTAINS: '包含',
}

function createConversationId() { return `attr-template-${crypto.randomUUID()}` }

function readConversations(): StoredAttributionConversation[] {
  try {
    const value = JSON.parse(localStorage.getItem(ATTRIBUTION_HISTORY_KEY) || '[]') as StoredAttributionConversation[]
    return Array.isArray(value) ? value.filter((item) => item?.conversationId && Array.isArray(item.messages)) : []
  } catch { return [] }
}

function activeConversation() {
  const history = readConversations()
  const activeId = localStorage.getItem(ACTIVE_ATTRIBUTION_KEY)
  return history.find((item) => item.conversationId === activeId) || history[0] || null
}

function conversationTitle(messages: ChatMessage[]) {
  const text = messages.find((item) => item.role === 'user')?.text.replace(/\s+/g, ' ').trim() || '新归因分析'
  return text.length <= 22 ? text : `${text.slice(0, 22)}…`
}

function formatHistoryTime(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '' : new Intl.DateTimeFormat('zh-CN', {
    month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit',
  }).format(date)
}

async function errorText(response: Response) {
  try { const body = await response.json() as { detail?: string; message?: string }; return body.detail || body.message || `请求失败（HTTP ${response.status}）` }
  catch { return `请求失败（HTTP ${response.status}）` }
}

function TemplateArtifact({ template, response, pending, onConfirm, onReset, onContinuationChange, onRemoveDimension }: {
  template: DimensionTemplate; response: TemplateResponse | null; pending: boolean; onConfirm: () => void; onReset: () => void
  onContinuationChange: (mode: 'AUTO' | 'STOP') => void
  onRemoveDimension: (level: number, dimensionId: string) => void
}) {
  const requiredReady = Boolean(template.metricId && template.currentPeriod && template.comparisonPeriod)
  return <section className="chat-template-artifact">
    <header><div><small>归因模板</small><h3>{template.name}</h3></div><span className={template.status.toLowerCase()}>{template.status === 'CONFIRMED' ? '已确认' : '草稿'}</span></header>
    <div className="chat-template-facts">
      <div className={template.metricId ? '' : 'missing'}><span>度量</span><strong>{template.metricName || '待补充'}</strong><small>{template.metricId || '未识别'}</small></div>
      <div className={template.currentPeriod ? '' : 'missing'}><span>当前周期</span><strong>{template.currentPeriod || '待补充'}</strong></div>
      <div className={template.comparisonPeriod ? '' : 'missing'}><span>对比周期</span><strong>{template.comparisonPeriod || '待补充'}</strong></div>
    </div>
    {!!template.filters?.length && <div className="chat-template-filters">
      <strong>过滤条件</strong>
      <div>{template.filters.map((filter, index) => <span key={`${filter.dimensionId}-${index}`} title={filter.rationale}>
        <b>{filter.dimensionName}</b> {FILTER_OPERATOR_NAMES[filter.operator] || filter.operator} {filter.values.join('、')}
      </span>)}</div>
    </div>}
    <div className="chat-template-path">
      {template.levels.map((layer, index) => <div className="chat-template-layer" key={layer.level}>
        {index > 0 && <i aria-hidden="true">↓</i>}
        <div className="chat-layer-number">{layer.level}</div>
        <section><header><strong>第 {layer.level} 层</strong><div>{layer.dimensions.map((item) => <article key={item.dimensionId} title={item.rationale}>
            <span>{item.dimensionName}</span><code>{item.dimensionId}</code><button type="button" aria-label={`删除${item.dimensionName}`} title={`删除${item.dimensionName}`} disabled={pending} onClick={() => onRemoveDimension(layer.level, item.dimensionId)}>×</button><small>{item.userTerm}</small>
          </article>)}</div><span>{layer.dimensions.length}/5</span></header>
        </section>
      </div>)}
      {!template.levels.length && <div className="chat-template-auto">未指定层级，全部由 Agent 自由探索</div>}
    </div>
    <footer>
      {!!template.levels.length && <div className="template-continuation-choice" role="group" aria-label="模板完成后的分析方式">
        <span>模板完成后</span>
        <button className={template.continuationMode === 'STOP' ? 'selected' : ''} type="button" disabled={pending || template.status === 'CONFIRMED'} onClick={() => onContinuationChange('STOP')}>就此结束</button>
        <button className={template.continuationMode === 'AUTO' ? 'selected' : ''} type="button" disabled={pending || template.status === 'CONFIRMED'} onClick={() => onContinuationChange('AUTO')}>继续自由探索</button>
      </div>}
      {!!response?.mappingIssues.length && <div className="chat-template-warning">{response.mappingIssues.map((issue) => <p key={issue.userTerm}><b>{issue.userTerm}</b>：{issue.reason}</p>)}</div>}
      <div className="chat-template-actions"><button type="button" onClick={onReset}>重新开始</button><button className="primary-button" type="button" onClick={onConfirm} disabled={pending || response?.status !== 'READY_TO_CONFIRM' || template.status === 'CONFIRMED'}>{template.status === 'CONFIRMED' ? '已确认' : !requiredReady ? '请在对话中补充任务要素' : response?.status === 'NEEDS_CLARIFICATION' ? '请继续澄清' : '确认模板'}</button></div>
    </footer>
  </section>
}

export default function AttributionTemplateChat({ selectedModel, executionControls, onTemplateChange }: {
  selectedModel: string
  executionControls?: ReactNode
  onTemplateChange?: (template: DimensionTemplate) => void
}) {
  const initialConversation = useMemo(activeConversation, [])
  const [message, setMessage] = useState('')
  const [messages, setMessages] = useState<ChatMessage[]>(initialConversation?.messages ?? [])
  const [template, setTemplate] = useState<DimensionTemplate | null>(initialConversation?.template ?? null)
  const [response, setResponse] = useState<TemplateResponse | null>(initialConversation?.response ?? null)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const [conversationId, setConversationId] = useState(initialConversation?.conversationId ?? createConversationId)
  const [conversations, setConversations] = useState<StoredAttributionConversation[]>(readConversations)
  const [historyOpen, setHistoryOpen] = useState(false)
  const inputRef = useRef<HTMLTextAreaElement>(null)

  useEffect(() => {
    if (initialConversation?.template) onTemplateChange?.(initialConversation.template)
  }, [])

  useEffect(() => {
    if (!messages.some((item) => item.role === 'user')) return
    const saved: StoredAttributionConversation = {
      conversationId,
      title: conversationTitle(messages),
      updatedAt: new Date().toISOString(),
      messages,
      template,
      response,
    }
    const history = [saved, ...readConversations().filter((item) => item.conversationId !== conversationId)]
      .sort((left, right) => right.updatedAt.localeCompare(left.updatedAt))
      .slice(0, MAX_ATTRIBUTION_HISTORY)
    localStorage.setItem(ATTRIBUTION_HISTORY_KEY, JSON.stringify(history))
    localStorage.setItem(ACTIVE_ATTRIBUTION_KEY, conversationId)
    setConversations(history)
  }, [conversationId, messages, template, response])

  function resizeComposer() {
    const input = inputRef.current
    if (!input) return
    const minimumHeight = 28
    const maximumHeight = 168
    input.style.height = 'auto'
    const nextHeight = Math.min(Math.max(input.scrollHeight, minimumHeight), maximumHeight)
    input.style.height = `${nextHeight}px`
    input.style.overflowY = input.scrollHeight > maximumHeight ? 'auto' : 'hidden'
  }

  useLayoutEffect(() => { resizeComposer() }, [message])

  async function submit(event: FormEvent) {
    event.preventDefault(); const content = message.trim(); if (!content || pending) return
    setPending(true); setError(''); setMessages((items) => [...items, { role: 'user', text: content }]); setMessage('')
    try {
      const raw = await fetch('/api/attribution/template/chat', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ userId: 'demo-user', conversationId, message: content, conversationHistory: messages, currentTemplate: template, model: selectedModel }) })
      if (!raw.ok) throw new Error(await errorText(raw))
      const result = await raw.json() as TemplateResponse
      const storedResponse: TemplateResponse = {
        status: result.status, reply: result.reply, template: result.template,
        unmappedTerms: result.unmappedTerms,
        mappingIssues: result.mappingIssues,
      }
      setTemplate(result.template); setResponse(storedResponse); onTemplateChange?.(result.template); setMessages((items) => [...items, { role: 'assistant', text: result.reply }])
      window.dispatchEvent(new Event('model-health-changed'))
    } catch (reason) { setError(reason instanceof Error ? reason.message : '分析模板理解失败') }
    finally { setPending(false); window.setTimeout(() => inputRef.current?.focus(), 0) }
  }

  async function confirm() {
    if (!template || response?.status !== 'READY_TO_CONFIRM') return
    setPending(true); setError('')
    try {
      const raw = await fetch('/api/attribution/template/confirm', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(template) })
      if (!raw.ok) throw new Error(await errorText(raw))
      const confirmed = await raw.json() as DimensionTemplate
      setTemplate(confirmed); onTemplateChange?.(confirmed); setMessages((items) => [...items, { role: 'assistant', text: '模板已确认，可以开始归因分析。' }])
    } catch (reason) { setError(reason instanceof Error ? reason.message : '确认模板失败') }
    finally { setPending(false) }
  }

  function changeContinuation(continuationMode: 'AUTO' | 'STOP') {
    if (!template || template.status === 'CONFIRMED' || !template.levels.length) return
    const next: DimensionTemplate = {
      ...template,
      continuationMode,
      mode: continuationMode === 'AUTO' ? 'HYBRID' : 'USER_DEFINED',
      summary: continuationMode === 'AUTO' ? '完成指定层级后继续自由探索。' : '完成指定层级后结束分析。',
    }
    setTemplate(next)
    setResponse((current) => current ? { ...current, template: next } : current)
    onTemplateChange?.(next)
  }

  function removeDimension(level: number, dimensionId: string) {
    if (!template || pending) return
    const removed = template.levels.find((item) => item.level === level)?.dimensions
      .find((item) => item.dimensionId === dimensionId)
    if (!removed) return
    const levels = template.levels
      .map((item) => ({ ...item, dimensions: item.dimensions.filter((dimension) => dimension.dimensionId !== dimensionId) }))
      .filter((item) => item.dimensions.length)
      .map((item, index) => ({ ...item, level: index + 1 }))
    const continuationMode = levels.length ? template.continuationMode : 'AUTO'
    const next: DimensionTemplate = {
      ...template,
      levels,
      continuationMode,
      mode: !levels.length ? 'AUTO' : continuationMode === 'AUTO' ? 'HYBRID' : 'USER_DEFINED',
      status: 'DRAFT',
      summary: levels.length ? `已删除${removed.dimensionName}，请确认调整后的分析层级。` : '未指定维度，由 Agent 自由探索。',
    }
    const mappingIssues = (response?.mappingIssues ?? []).filter((issue) => issue.userTerm !== removed.userTerm)
    const unmappedTerms = (response?.unmappedTerms ?? []).filter((term) => term !== removed.userTerm)
    const nextResponse: TemplateResponse = {
      status: next.metricId && next.currentPeriod && next.comparisonPeriod && !mappingIssues.length && !unmappedTerms.length
        ? 'READY_TO_CONFIRM' : 'NEEDS_CLARIFICATION',
      reply: `已删除${removed.dimensionName}。`,
      template: next,
      unmappedTerms,
      mappingIssues,
    }
    setTemplate(next)
    setResponse(nextResponse)
    onTemplateChange?.(next)
  }

  function startNewConversation() {
    const nextId = createConversationId()
    setConversationId(nextId); setTemplate(null); setResponse(null); setMessages([]); setMessage(''); setError(''); setPending(false); setHistoryOpen(false)
    localStorage.setItem(ACTIVE_ATTRIBUTION_KEY, nextId)
    window.setTimeout(() => inputRef.current?.focus(), 0)
  }

  function restoreConversation(item: StoredAttributionConversation) {
    setConversationId(item.conversationId); setMessages(item.messages); setTemplate(item.template); setResponse(item.response)
    setMessage(''); setError(''); setPending(false); setHistoryOpen(false)
    localStorage.setItem(ACTIVE_ATTRIBUTION_KEY, item.conversationId)
    if (item.template) onTemplateChange?.(item.template)
  }

  function deleteConversation(item: StoredAttributionConversation) {
    if (!window.confirm(`确定删除归因历史“${item.title}”吗？`)) return
    const next = readConversations().filter((value) => value.conversationId !== item.conversationId)
    localStorage.setItem(ATTRIBUTION_HISTORY_KEY, JSON.stringify(next))
    setConversations(next)
    if (item.conversationId === conversationId) {
      const replacement = next[0]
      if (replacement) restoreConversation(replacement)
      else startNewConversation()
    }
  }
  function keyboardSubmit(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault()
      event.currentTarget.form?.requestSubmit()
    }
  }

  return <section className="workspace-card attribution-template-workbench chatgpt-template-workbench">
    <div className="attribution-chat-layout">
      {historyOpen && <button className="attribution-history-backdrop" type="button" aria-label="关闭归因历史" onClick={() => setHistoryOpen(false)} />}
      <aside className={`attribution-history-panel ${historyOpen ? 'is-open' : ''}`}>
        <div className="history-heading"><div><strong>归因历史</strong></div><button type="button" onClick={startNewConversation} aria-label="新建归因对话">＋</button></div>
        <div className="history-list">
          {!conversations.length && <p className="history-empty">还没有归因历史</p>}
          {conversations.map((item) => <div className={item.conversationId === conversationId ? 'active' : ''} key={item.conversationId}>
            <button className="history-open-button" type="button" disabled={pending} onClick={() => restoreConversation(item)}><strong>{item.title}</strong><span>{formatHistoryTime(item.updatedAt)} · {Math.ceil(item.messages.length / 2)} 轮</span></button>
            <button className="history-delete-button" type="button" disabled={pending} aria-label={`删除归因历史：${item.title}`} onClick={() => deleteConversation(item)}>×</button>
          </div>)}
        </div>
      </aside>

      <div className="attribution-chat-main">
        <div className={`template-chat-thread ${messages.length ? 'has-messages' : 'is-empty'}`}>
          {!messages.length && <div className="template-chat-hero"><div className="template-agent-mark">归</div><h3>想怎样分析这次指标变化？</h3><p>告诉我度量、两段时间、每层维度；有分析范围时也可以直接说过滤条件。</p><button type="button" onClick={() => { setMessage(EXAMPLE); window.setTimeout(() => inputRef.current?.focus(), 0) }}>使用示例</button></div>}
          {messages.map((item, index) => <div className={`template-thread-message ${item.role}`} key={`${item.role}-${index}`}><div className="template-message-avatar">{item.role === 'user' ? '你' : '归'}</div><div><p>{item.text}</p></div></div>)}
          {template && <div className="template-persistent-artifact"><TemplateArtifact template={template} response={response} pending={pending} onConfirm={confirm} onReset={startNewConversation} onContinuationChange={changeContinuation} onRemoveDimension={removeDimension} /></div>}
          {pending && <div className="template-thread-message assistant thinking"><div className="template-message-avatar">归</div><div><span /><span /><span /></div></div>}
        </div>

        <div className="template-composer-dock"><form className="template-chat-composer" onSubmit={submit}>
          <textarea ref={inputRef} aria-label="描述归因模板" rows={1} value={message} onKeyDown={keyboardSubmit} onChange={(event) => setMessage(event.target.value)} placeholder="给归因 Agent 发消息……" />
          <div className="template-composer-tools"><button className="template-example-button" type="button" onClick={() => { setMessage(EXAMPLE); window.setTimeout(() => inputRef.current?.focus(), 0) }}>示例</button><span>Enter 发送 · Shift+Enter 换行</span><button className="template-send-button" aria-label="发送" type="submit" disabled={pending || !message.trim()}>↑</button></div>
        </form>{error && <div className="template-chat-error" role="alert">{error}</div>}<small className="template-chat-notice">模板由模型理解生成，请确认度量、周期和层级后再使用。</small></div>
        {executionControls}
      </div>
    </div>
  </section>
}
