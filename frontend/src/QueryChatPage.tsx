import { useEffect, useRef, useState } from 'react'
import type { FormEvent, KeyboardEvent } from 'react'

type Metric = {
  id: string
  name: string
  unit: string
}

type Dimension = {
  id: string
  name: string
}

type Metadata = {
  metrics: Metric[]
  dimensions: Dimension[]
}

type QueryContext = {
  metricIds: string[]
  dimensionIds: string[]
  dimensionFilters: { dimensionId: string; operator: string; values: string[] }[]
  sorts: { fieldId: string; direction: 'ASC' | 'DESC' }[]
}

type ResultColumn = {
  id: string
  name: string
  numeric: boolean
}

type QueryResult = {
  summary: string
  columns: ResultColumn[]
  rows: Record<string, string>[]
}

type WorkflowStep = {
  node: string
  name: string
  status: 'COMPLETED' | 'FAILED' | 'SKIPPED'
  detail: string
}

type QueryFilter = {
  name: string
  operation: string
  values: string[]
}

type ActionOperation = {
  action: 'SET' | 'CLEAR'
  ids: string[]
}

type QueryAction = {
  intent: 'QUERY'
  metricAction: { operations: ActionOperation[] }
  dimensionAction: { operations: ActionOperation[] }
  filterAction: {
    operations: {
      action: 'SET' | 'CLEAR'
      dimensionId: string
      operator: string
      values: string[]
    }[]
  }
  sortAction: {
    action: 'SET' | 'CLEAR'
    items: { fieldId: string; direction: 'ASC' | 'DESC' }[]
  }
}

type SmartBiFilter = {
  id: string
  name: string
  operation: string
  values: string[]
}

type SmartBiRelationNode = {
  childNodes: SmartBiRelationNode[] | null
  filter: SmartBiFilter | null
  relation: string | null
  leaf: boolean
}

type SmartBiQueryRequest = {
  dataSetId: string
  rows: string[]
  columns: string[]
  filters: SmartBiFilter[]
  relationNode: SmartBiRelationNode
  orderBys: { fieldName: string; type: 'ASC' | 'DESC'; orderPriority: number }[]
}

type ChatQueryPlan = {
  dataSource: string
  dataSetId: string
  rows: string[]
  columns: string[]
  filters: QueryFilter[]
  sqlPreview: string
  smartBiRequest: SmartBiQueryRequest
}

type LlmResultMessage = {
  model: string
  role: string
  content: string
  requestMessages: { role: string; content: string }[]
  rawResponse?: string | null
}

type ChatResponse = {
  status: 'clarifying' | 'confirming' | 'completed' | 'rejected'
  reply: string
  suggestions: string[]
  context: QueryContext
  result: QueryResult | null
  executionEngine: string
  workflowSteps: WorkflowStep[]
  queryPlan: ChatQueryPlan | null
  conversationId: string
  queryAction: QueryAction | null
  queryExplanation: string | null
  llmMessage: LlmResultMessage | null
}

type Message = {
  id: number
  role: 'assistant' | 'user'
  text: string
  suggestions?: string[]
  result?: QueryResult | null
  executionEngine?: string
  workflowSteps?: WorkflowStep[]
  queryPlan?: ChatQueryPlan | null
  tone?: 'normal' | 'rejected'
  queryAction?: QueryAction | null
  queryExplanation?: string | null
  llmMessage?: LlmResultMessage | null
  status?: ChatResponse['status'] | null
}

type ConversationSummary = {
  conversationId: string
  title: string
  updatedAt: string
  messageCount: number
}

type ConversationDetail = {
  conversationId: string
  title: string
  createdAt: string
  updatedAt: string
  context: QueryContext
  messages: Message[]
}

const CURRENT_USER_ID = 'demo-user'
const ACTIVE_CONVERSATION_KEY = `payment-analysis:active-conversation:${CURRENT_USER_ID}`

const EMPTY_CONTEXT: QueryContext = {
  metricIds: [],
  dimensionIds: [],
  dimensionFilters: [],
  sorts: [],
}

const FALLBACK_METADATA: Metadata = {
  metrics: [
    { id: 'transactionAmount', name: '交易金额', unit: '元' },
    { id: 'transactionCount', name: '交易笔数', unit: '笔' },
    { id: 'successRate', name: '支付成功率', unit: '%' },
  ],
  dimensions: [
    { id: 'tradeYear', name: '年' },
    { id: 'tradeMonth', name: '月' },
    { id: 'tradeDate', name: '日' },
    { id: 'channel', name: '受理渠道' },
    { id: 'region', name: '地区' },
    { id: 'merchantType', name: '商户类型' },
    { id: 'paymentMethod', name: '支付方式' },
  ],
}

const WELCOME: Message = {
  id: 1,
  role: 'assistant',
  text: '你好，我是支付查数助手。直接说你想查什么即可；未指定时间时默认查本月，未指定维度时直接返回汇总。',
  suggestions: ['查本月交易金额', '看最近7天支付成功率', '查7月各渠道交易金额'],
}

function ContextItem({ label, value, ready }: { label: string; value: string; ready: boolean }) {
  return (
    <div className={`context-item ${ready ? 'ready' : ''}`}>
      <span>{ready ? '✓' : '○'}</span>
      <div><small>{label}</small><strong>{value || '待确认'}</strong></div>
    </div>
  )
}

function ResultTable({ result }: { result: QueryResult }) {
  return (
    <div className="chat-result">
      <div className="chat-result-title">
        <div><strong>查询结果</strong><span>{result.summary}</span></div>
        <small>{result.rows.length} 条数据</small>
      </div>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>{result.columns.map((column) => <th className={column.numeric ? 'numeric' : ''} key={column.id}>{column.name}</th>)}</tr>
          </thead>
          <tbody>
            {result.rows.map((row, index) => (
              <tr key={index}>
                {result.columns.map((column) => <td className={column.numeric ? 'numeric' : ''} key={column.id}>{row[column.id]}</td>)}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="result-footnote">Mock SmartBI 数据 · 大模型负责解析查询条件</div>
    </div>
  )
}

function WorkflowTrace({
  engine,
  steps,
  plan,
  queryAction,
  queryExplanation,
  llmMessage,
}: {
  engine: string
  steps: WorkflowStep[]
  plan?: ChatQueryPlan | null
  queryAction?: QueryAction | null
  queryExplanation?: string | null
  llmMessage?: LlmResultMessage | null
}) {
  return (
    <details className="chat-workflow-trace">
      <summary>
        <span>查看 LangGraph4j 执行过程</span>
        <small>{engine}</small>
      </summary>
      <div className="chat-workflow-body">
        <ol className="chat-workflow-list">
          {steps.map((step, index) => (
            <li className={step.status.toLowerCase()} key={`${step.node}-${index}`}>
              <span>{step.status === 'SKIPPED' ? '—' : step.status === 'FAILED' ? '!' : index + 1}</span>
              <div>
                <strong>{step.name}</strong>
                <small>{step.node}</small>
                <p>{step.detail}</p>
              </div>
            </li>
          ))}
        </ol>
        {queryExplanation && (
          <div className="query-explanation">
            <strong>模型解析说明</strong>
            <p>{queryExplanation}</p>
          </div>
        )}
        {queryAction && (
          <details className="sql-preview" open>
            <summary>1. QueryAction（LLM 交互 JSON）</summary>
            <pre><code>{JSON.stringify(queryAction, null, 2)}</code></pre>
            <small>大模型给出的查询状态变更指令，已通过后端结构和白名单校验。</small>
          </details>
        )}
        {llmMessage && (
          <details className="llm-message-preview">
            <summary>LLM 请求 messages 与原始响应</summary>
            <div><span>model</span><code>{llmMessage.model}</code></div>
            <section className="llm-message-list">
              {llmMessage.requestMessages?.map((message, index) => (
                <article key={`${message.role}-${index}`}>
                  <strong>{index < 2
                    ? `发送 · ${message.role}`
                    : message.role === 'assistant'
                      ? '协议校验 · 模型原输出'
                      : '协议修复指令 · internal'}</strong>
                  <pre><code>{message.content}</code></pre>
                </article>
              ))}
              <article>
                <strong>解析内容 · {llmMessage.role}</strong>
                <pre><code>{llmMessage.content}</code></pre>
              </article>
              {llmMessage.rawResponse && (
                <article>
                  <strong>LLM 完整原始响应（未截断）</strong>
                  <pre><code>{llmMessage.rawResponse}</code></pre>
                </article>
              )}
            </section>
          </details>
        )}
        {plan && (
          <>
            <div className="chat-query-plan">
              <div><span>数据源</span><strong>{plan.dataSource}</strong></div>
              <div><span>数据集</span><strong>{plan.dataSetId}</strong></div>
              <div><span>分组字段</span><strong>{plan.rows.length ? plan.rows.join('、') : '无（汇总查询）'}</strong></div>
              <div><span>度量字段</span><strong>{plan.columns.join('、')}</strong></div>
              <div>
                <span>过滤条件</span>
                <strong>{plan.filters.map((filter) => `${filter.name} ${filter.operation} ${filter.values.join(' ～ ')}`).join('；')}</strong>
              </div>
            </div>
            <details className="sql-preview" open>
              <summary>2. SmartBI QueryRequest（最终接口 JSON）</summary>
              <pre><code>{JSON.stringify(plan.smartBiRequest, null, 2)}</code></pre>
              <small>实际发送给 SmartBI Client 的查询对象；当前由 Mock SmartBI 接收。</small>
            </details>
            {plan.sqlPreview && (
              <details className="sql-preview">
                <summary>辅助：等价 SQL 预览</summary>
                <pre><code>{plan.sqlPreview}</code></pre>
                <small>仅用于理解查询逻辑，SmartBI 实际执行以 JSON 查询计划为准。</small>
              </details>
            )}
          </>
        )}
        <p className="workflow-safe-note">完整展示 messages 与 LLM 原始响应；仅隐藏 Authorization/API Key。</p>
      </div>
    </details>
  )
}

function createConversationId() {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `chat-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function formatConversationTime(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return new Intl.DateTimeFormat('zh-CN', {
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}

export default function QueryChatPage({ selectedModel }: { selectedModel: string }) {
  const [metadata, setMetadata] = useState<Metadata>(FALLBACK_METADATA)
  const [messages, setMessages] = useState<Message[]>([WELCOME])
  const [context, setContext] = useState<QueryContext>(EMPTY_CONTEXT)
  const [input, setInput] = useState('')
  const [pending, setPending] = useState(false)
  const [validation, setValidation] = useState('')
  const [messageId, setMessageId] = useState(2)
  const [conversationId, setConversationId] = useState(createConversationId)
  const [conversations, setConversations] = useState<ConversationSummary[]>([])
  const [historyLoading, setHistoryLoading] = useState(true)
  const [deletingConversationId, setDeletingConversationId] = useState<string | null>(null)
  const [openSidePanel, setOpenSidePanel] = useState<'history' | 'context' | null>(null)
  const messageListRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    fetch('/api/metadata')
      .then((response) => response.ok ? response.json() as Promise<Metadata> : Promise.reject())
      .then(setMetadata)
      .catch(() => setMetadata(FALLBACK_METADATA))
  }, [])

  useEffect(() => {
    void initializeMemory()
  }, [])

  useEffect(() => {
    const list = messageListRef.current
    if (list) list.scrollTop = list.scrollHeight
  }, [messages, pending])

  useEffect(() => {
    function closeOnEscape(event: globalThis.KeyboardEvent) {
      if (event.key === 'Escape') setOpenSidePanel(null)
    }
    window.addEventListener('keydown', closeOnEscape)
    return () => window.removeEventListener('keydown', closeOnEscape)
  }, [])

  const metricNames = context.metricIds
    .map((id) => metadata.metrics.find((metric) => metric.id === id)?.name)
    .filter(Boolean)
    .join('、')
  const dimensionNames = context.dimensionIds
    .map((id) => metadata.dimensions.find((dimension) => dimension.id === id)?.name)
    .filter(Boolean)
    .join('、')
  const filterNames = context.dimensionFilters
    .map((filter) => {
      const name = metadata.dimensions.find((dimension) => dimension.id === filter.dimensionId)?.name || filter.dimensionId
      return `${name} ${filter.operator} ${filter.values.join('、')}`
    })
    .join('；')
  const dimensionFilterNames = filterNames
  const sortNames = context.sorts
    .map((sort) => {
      const name = metadata.metrics.find((metric) => metric.id === sort.fieldId)?.name
        || metadata.dimensions.find((dimension) => dimension.id === sort.fieldId)?.name
        || sort.fieldId
      return `${name} ${sort.direction}`
    })
    .join('；')

  async function initializeMemory() {
    setHistoryLoading(true)
    try {
      const historyResponse = await fetch(
        `/api/chat/conversations?userId=${encodeURIComponent(CURRENT_USER_ID)}`,
      )
      const history = historyResponse.ok
        ? await historyResponse.json() as ConversationSummary[]
        : []
      setConversations(history)
      const savedId = localStorage.getItem(ACTIVE_CONVERSATION_KEY)
      const targetId = savedId || history[0]?.conversationId
      if (targetId) {
        const restored = await restoreConversation(targetId, false)
        if (!restored) startNewConversation()
      }
    } catch {
      setConversations([])
    } finally {
      setHistoryLoading(false)
    }
  }

  async function refreshHistory() {
    try {
      const response = await fetch(`/api/chat/conversations?userId=${encodeURIComponent(CURRENT_USER_ID)}`)
      if (response.ok) setConversations(await response.json() as ConversationSummary[])
    } catch {
      // 当前对话仍可继续，历史列表稍后再次刷新。
    }
  }

  async function restoreConversation(id: string, showLoading = true) {
    if (showLoading) setPending(true)
    try {
      const response = await fetch(
        `/api/chat/conversations/${encodeURIComponent(id)}?userId=${encodeURIComponent(CURRENT_USER_ID)}`,
      )
      if (!response.ok) return false
      const detail = await response.json() as ConversationDetail
      let lastAssistantIndex = -1
      detail.messages.forEach((item, index) => {
        if (item.role === 'assistant') lastAssistantIndex = index
      })
      const restoredMessages = detail.messages.map((item, index) => ({
        ...item,
        suggestions: index === lastAssistantIndex ? item.suggestions : undefined,
        status: index === lastAssistantIndex ? item.status : null,
      }))
      setConversationId(detail.conversationId)
      setContext(detail.context)
      setMessages(restoredMessages.length ? restoredMessages : [WELCOME])
      setMessageId(Math.max(2, ...restoredMessages.map((item) => item.id + 1)))
      setInput('')
      setValidation('')
      setOpenSidePanel(null)
      localStorage.setItem(ACTIVE_CONVERSATION_KEY, detail.conversationId)
      return true
    } catch {
      return false
    } finally {
      if (showLoading) setPending(false)
    }
  }

  function startNewConversation() {
    const nextConversationId = createConversationId()
    setMessages([{ ...WELCOME, id: messageId }])
    setMessageId((current) => current + 1)
    setContext(EMPTY_CONTEXT)
    setConversationId(nextConversationId)
    setInput('')
    setValidation('')
    setPending(false)
    setOpenSidePanel(null)
    localStorage.setItem(ACTIVE_CONVERSATION_KEY, nextConversationId)
  }

  async function deleteConversation(conversation: ConversationSummary) {
    if (!window.confirm(`确定删除历史查询“${conversation.title}”吗？删除后无法恢复。`)) return
    setDeletingConversationId(conversation.conversationId)
    setValidation('')
    try {
      const response = await fetch(
        `/api/chat/conversations/${encodeURIComponent(conversation.conversationId)}?userId=${encodeURIComponent(CURRENT_USER_ID)}`,
        { method: 'DELETE' },
      )
      if (!response.ok) {
        const detail = await response.json().catch(() => null) as { detail?: string } | null
        throw new Error(detail?.detail || '删除历史查询失败')
      }
      setConversations((current) => current.filter(
        (item) => item.conversationId !== conversation.conversationId,
      ))
      if (conversation.conversationId === conversationId) startNewConversation()
    } catch (error) {
      setValidation(error instanceof Error ? error.message : '删除历史查询失败')
    } finally {
      setDeletingConversationId(null)
    }
  }

  async function sendMessage(content: string, confirmed = false) {
    const message = content.trim()
    if (!message) {
      setValidation('请输入查数需求')
      return
    }
    if (message.length > 200) {
      setValidation('每次输入不能超过 200 字')
      return
    }

    const userId = messageId
    setMessageId((current) => current + 1)
    setMessages((current) => [
      ...current.map((item) => ({ ...item, suggestions: undefined, status: null })),
      { id: userId, role: 'user', text: message },
    ])
    setInput('')
    setValidation('')
    setPending(true)

    try {
      const response = await fetch('/api/chat/query', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          userId: CURRENT_USER_ID,
          sessionId: conversationId,
          message,
          context,
          model: selectedModel,
          confirmed,
        }),
      })
      if (!response.ok) {
        const detail = await response.json().catch(() => null) as { detail?: string } | null
        throw new Error(detail?.detail || '对话服务暂不可用')
      }
      const data = await response.json() as ChatResponse
      setConversationId(data.conversationId)
      localStorage.setItem(ACTIVE_CONVERSATION_KEY, data.conversationId)
      setContext(data.context)
      setMessages((current) => [...current, {
        id: userId + 1,
        role: 'assistant',
        text: data.reply,
        suggestions: data.suggestions,
        result: data.result,
        executionEngine: data.executionEngine,
        workflowSteps: data.workflowSteps,
        queryPlan: data.queryPlan,
        queryAction: data.queryAction,
        queryExplanation: data.queryExplanation,
        llmMessage: data.llmMessage,
        status: data.status,
        tone: data.status === 'rejected' ? 'rejected' : 'normal',
      }])
      setMessageId((current) => current + 1)
      void refreshHistory()
    } catch (error) {
      setMessages((current) => [...current, {
        id: userId + 1,
        role: 'assistant',
        text: error instanceof Error ? `${error.message}，请确认后端服务已启动。` : '对话服务暂不可用。',
        tone: 'rejected',
      }])
      setMessageId((current) => current + 1)
    } finally {
      setPending(false)
      window.dispatchEvent(new Event('model-health-changed'))
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    void sendMessage(input)
  }

  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      if (!pending) void sendMessage(input)
    }
  }

  return (
    <section className="query-chat-page">
      <div className="chat-layout">
        {openSidePanel && (
          <button
            aria-label="关闭侧栏"
            className="chat-side-backdrop"
            onClick={() => setOpenSidePanel(null)}
            type="button"
          />
        )}
        <aside className={`conversation-history-panel ${openSidePanel === 'history' ? 'is-open' : ''}`}>
          <div className="history-heading">
            <div><strong>历史查询</strong><small>演示用户的对话</small></div>
            <div className="side-panel-actions">
              <button type="button" onClick={startNewConversation} aria-label="新建对话">＋</button>
              <button
                className="side-panel-close"
                type="button"
                onClick={() => setOpenSidePanel(null)}
                aria-label="关闭历史查询"
              >
                ×
              </button>
            </div>
          </div>
          <div className="history-list">
            {historyLoading && <p className="history-empty">正在读取会话…</p>}
            {!historyLoading && conversations.length === 0 && (
              <p className="history-empty">还没有历史查询<br />发送第一条消息后会保存在这里</p>
            )}
            {conversations.map((conversation) => (
              <div
                className={conversation.conversationId === conversationId ? 'active' : ''}
                key={conversation.conversationId}
              >
                <button
                  className="history-open-button"
                  disabled={pending || deletingConversationId !== null}
                  onClick={() => void restoreConversation(conversation.conversationId)}
                  type="button"
                >
                  <strong>{conversation.title}</strong>
                  <span>{formatConversationTime(conversation.updatedAt)} · {conversation.messageCount / 2} 轮</span>
                </button>
                <button
                  aria-label={`删除历史查询：${conversation.title}`}
                  className="history-delete-button"
                  disabled={pending || deletingConversationId !== null}
                  onClick={() => void deleteConversation(conversation)}
                  title="删除记录"
                  type="button"
                >
                  {deletingConversationId === conversation.conversationId ? '…' : '×'}
                </button>
              </div>
            ))}
          </div>
        </aside>

        <div className="chat-panel">
          <div className="chat-toolbar">
            <div className="chat-toolbar-main">
              <span className="assistant-mark">BI</span>
              <div><strong>支付查数助手</strong><small>真实大模型解析 · Mock SmartBI 取数</small></div>
            </div>
            <div className="chat-toolbar-actions">
              <button
                aria-expanded={openSidePanel === 'history'}
                className="chat-mobile-tool"
                onClick={() => setOpenSidePanel(openSidePanel === 'history' ? null : 'history')}
                type="button"
              >
                历史
              </button>
              <button
                aria-expanded={openSidePanel === 'context'}
                className="chat-mobile-tool"
                onClick={() => setOpenSidePanel(openSidePanel === 'context' ? null : 'context')}
                type="button"
              >
                条件
              </button>
              <button type="button" onClick={startNewConversation}>＋ 新对话</button>
            </div>
          </div>

          <div className="message-list" ref={messageListRef} aria-live="polite">
            {messages.map((message) => (
              <div className={`message-row ${message.role}`} key={message.id}>
                {message.role === 'assistant' && <span className="message-avatar">BI</span>}
                <div className="message-content">
                  <div className={`message-bubble ${message.tone === 'rejected' ? 'rejected' : ''}`}>{message.text}</div>
                  {message.result && <ResultTable result={message.result} />}
                  {message.executionEngine && message.workflowSteps && (
                    <WorkflowTrace
                      engine={message.executionEngine}
                      steps={message.workflowSteps}
                      plan={message.queryPlan}
                      queryAction={message.queryAction}
                      queryExplanation={message.queryExplanation}
                      llmMessage={message.llmMessage}
                    />
                  )}
                  {message.status === 'confirming' && (
                    <div className="query-confirm-actions">
                      <button
                        className="confirm-query-button"
                        disabled={pending}
                        type="button"
                        onClick={() => void sendMessage('确认执行', true)}
                      >
                        确认执行
                      </button>
                      <button
                        disabled={pending}
                        type="button"
                        onClick={() => setInput('请修改为：')}
                      >
                        继续修改
                      </button>
                      <span>确认前不会调用 SmartBI</span>
                    </div>
                  )}
                  {message.suggestions && message.suggestions.length > 0 && (
                    <div className="suggestion-list">
                      {message.suggestions.map((suggestion) => (
                        <button disabled={pending} type="button" key={suggestion} onClick={() => void sendMessage(suggestion)}>{suggestion}</button>
                      ))}
                    </div>
                  )}
                </div>
                {message.role === 'user' && <span className="message-avatar user-message-avatar">演</span>}
              </div>
            ))}
            {pending && (
              <div className="message-row assistant">
                <span className="message-avatar">BI</span>
                <div className="typing" aria-label="正在分析"><i /><i /><i /></div>
              </div>
            )}
          </div>

          <form className="chat-composer" onSubmit={submit}>
            {validation && <span className="composer-error" role="alert">{validation}</span>}
            <textarea
              aria-label="输入查数需求"
              maxLength={200}
              placeholder="例如：查7月交易金额，按受理渠道分组"
              value={input}
              onChange={(event) => { setInput(event.target.value); setValidation('') }}
              onKeyDown={handleKeyDown}
            />
            <div>
              <span>Enter 发送 · Shift + Enter 换行</span>
              <span>{input.length}/200</span>
              <button className="send-button" disabled={pending || !input.trim()} type="submit">发送</button>
            </div>
          </form>
        </div>

        <aside className={`context-panel ${openSidePanel === 'context' ? 'is-open' : ''}`}>
          <div className="context-heading">
            <div><strong>当前查询条件</strong><small>随对话实时更新</small></div>
            <button
              className="side-panel-close"
              type="button"
              onClick={() => setOpenSidePanel(null)}
              aria-label="关闭查询条件"
            >
              ×
            </button>
          </div>
          <div className="context-list">
            <ContextItem label="度量" value={metricNames} ready={Boolean(metricNames)} />
            <ContextItem label="分组维度（可选）" value={dimensionNames || '不分组，返回汇总'} ready={true} />
            <ContextItem
              label="维度过滤（可选）"
              value={dimensionFilterNames || '无'}
              ready={true}
            />
            <ContextItem label="排序（可选）" value={sortNames || '无'} ready={true} />
          </div>
          <div className="capability-card">
            <strong>支持的 3 个度量</strong>
            {metadata.metrics.map((metric) => <span key={metric.id}>{metric.name}</span>)}
          </div>
          <div className="capability-card dimension-catalog">
            <strong>支持的 4 个维度</strong>
            {metadata.dimensions.map((dimension) => <span key={dimension.id}>{dimension.name}</span>)}
          </div>
          <div className="scope-note"><b>范围说明</b><span>当前仅处理支付数据查询。闲聊、写作及其他任务会被拒绝。</span></div>
        </aside>
      </div>
    </section>
  )
}
