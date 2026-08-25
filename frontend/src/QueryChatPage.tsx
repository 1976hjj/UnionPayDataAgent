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

type QueryAction = {
  metricIds: string[]
  dimensionIds: string[]
  dimensionFilters: QueryContext['dimensionFilters']
  sorts: QueryContext['sorts']
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
  derivedFromArtifactIds: string[]
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
  derivedFromArtifactIds?: string[]
  status?: ChatResponse['status'] | null
}

type AgentResponse = {
  status: string
  conversationId: string
  viewModel: { type: 'query'; payload: ChatResponse }
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
const ACTIVE_CONVERSATION_KEY = `payment-analysis:active-query-conversation:${CURRENT_USER_ID}`

const EMPTY_CONTEXT: QueryContext = {
  metricIds: [],
  dimensionIds: [],
  dimensionFilters: [],
  sorts: [],
}

const FALLBACK_METADATA: Metadata = {
  metrics: [],
  dimensions: [],
}

const WELCOME: Message = {
  id: 1,
  role: 'assistant',
  text: '你好，我是支付查数助手。直接说你想查什么即可；未指定时间时默认查本月，未指定维度时直接返回汇总。',
  suggestions: [
    '昨天发卡市场为中国大陆，收单市场为香港，交易介质为二维码主扫，度量为人民币承兑金额，有效标识为1',
    '今年上半年VISA双标芯片卡（内卡）在俄罗斯的POS交易笔数和金额',
    '今年至今VCC总体业务情况，按月分组，有效标识为1、0',
  ],
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
        `/api/chat/conversations?userId=${encodeURIComponent(CURRENT_USER_ID)}&scope=QUERY`,
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
      const response = await fetch(`/api/chat/conversations?userId=${encodeURIComponent(CURRENT_USER_ID)}&scope=QUERY`)
      if (response.ok) setConversations(await response.json() as ConversationSummary[])
    } catch {
      // 当前对话仍可继续，历史列表稍后再次刷新。
    }
  }

  async function restoreConversation(id: string, showLoading = true) {
    if (showLoading) setPending(true)
    try {
      const response = await fetch(
        `/api/chat/conversations/${encodeURIComponent(id)}?userId=${encodeURIComponent(CURRENT_USER_ID)}&scope=QUERY`,
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
    if (!window.confirm(`确定删除会话“${conversation.title}”吗？删除后无法恢复。`)) return
    setDeletingConversationId(conversation.conversationId)
    setValidation('')
    try {
      const response = await fetch(
        `/api/chat/conversations/${encodeURIComponent(conversation.conversationId)}?userId=${encodeURIComponent(CURRENT_USER_ID)}&scope=QUERY`,
        { method: 'DELETE' },
      )
      if (!response.ok) {
        const detail = await response.json().catch(() => null) as { detail?: string } | null
        throw new Error(detail?.detail || '删除会话失败')
      }
      setConversations((current) => current.filter(
        (item) => item.conversationId !== conversation.conversationId,
      ))
      if (conversation.conversationId === conversationId) startNewConversation()
    } catch (error) {
      setValidation(error instanceof Error ? error.message : '删除会话失败')
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
    if (message.length > 2000) {
      setValidation('每次输入不能超过 2000 字')
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
      const response = await fetch('/api/agent/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          userId: CURRENT_USER_ID,
          conversationId,
          message,
          entryMode: 'BI_CHAT',
          model: selectedModel,
          action: confirmed ? 'CONFIRM' : 'MESSAGE',
          queryContext: context,
        }),
      })
      if (!response.ok) {
        const detail = await response.json().catch(() => null) as { detail?: string } | null
        throw new Error(detail?.detail || '对话服务暂不可用')
      }
      const agent = await response.json() as AgentResponse
      if (agent.viewModel.type !== 'query') throw new Error('Agent 返回了不支持的查数结果类型')
      const data = agent.viewModel.payload
      setConversationId(agent.conversationId)
      localStorage.setItem(ACTIVE_CONVERSATION_KEY, agent.conversationId)
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
        derivedFromArtifactIds: data.derivedFromArtifactIds,
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
          <div><strong>会话历史</strong><small>仅显示查数会话</small></div>
            <div className="side-panel-actions">
              <button type="button" onClick={startNewConversation} aria-label="新建对话">＋</button>
              <button
                className="side-panel-close"
                type="button"
                onClick={() => setOpenSidePanel(null)}
                aria-label="关闭会话历史"
              >
                ×
              </button>
            </div>
          </div>
          <div className="history-list">
            {historyLoading && <p className="history-empty">正在读取会话…</p>}
            {!historyLoading && conversations.length === 0 && (
              <p className="history-empty">还没有历史会话<br />发送第一条消息后会保存在这里</p>
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
                  aria-label={`删除会话：${conversation.title}`}
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
                  {message.derivedFromArtifactIds && message.derivedFromArtifactIds.length > 0 && (
                    <div className="artifact-source-note">引用分析产物：{message.derivedFromArtifactIds.join('、')}</div>
                  )}
                  {message.result && <ResultTable result={message.result} />}
                  {message.queryAction && (message.status === 'clarifying' || message.status === 'confirming') && (
                    <div className="query-confirm-actions">
                      <button
                        className="confirm-query-button"
                        disabled={pending || message.status !== 'confirming'}
                        type="button"
                        onClick={() => void sendMessage('确认执行', true)}
                      >
                        {message.status === 'confirming' ? '确认执行' : '请先补齐必填项'}
                      </button>
                      <button
                        disabled={pending}
                        type="button"
                        onClick={() => setInput('请修改为：')}
                      >
                        继续修改
                      </button>
                      <span>{message.status === 'confirming'
                        ? '确认前不会调用 SmartBI'
                        : '还有必填项未补齐，暂不能执行'}</span>
                    </div>
                  )}
                  {message.suggestions && message.suggestions.length > 0 && (
                    <div className="suggestion-list">
                      {message.suggestions.map((suggestion) => (
                        <button disabled={pending} type="button" key={suggestion} onClick={() => { setInput(suggestion); setValidation('') }}>{suggestion}</button>
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

          <div className="template-composer-dock query-composer-dock">
            <form className="template-chat-composer" onSubmit={submit}>
              <textarea
                aria-label="输入查数需求"
                maxLength={2000}
                value={input}
                onChange={(event) => { setInput(event.target.value); setValidation('') }}
                onKeyDown={handleKeyDown}
              />
              <div className="template-composer-tools query-composer-tools">
                <span>Enter 发送 · Shift+Enter 换行</span>
                <span className="query-character-count">{input.length}/2000</span>
                <button className="template-send-button" aria-label="发送" disabled={pending || !input.trim()} type="submit">↑</button>
              </div>
            </form>
            {validation && <div className="template-chat-error" role="alert">{validation}</div>}
          </div>
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
            <strong>支持的 {metadata.metrics.length} 个度量</strong>
            {metadata.metrics.map((metric) => <span key={metric.id}>{metric.name}</span>)}
          </div>
          <div className="capability-card dimension-catalog">
            <strong>支持的 {metadata.dimensions.length} 个维度</strong>
            {metadata.dimensions.map((dimension) => <span key={dimension.id}>{dimension.name}</span>)}
          </div>
        </aside>
      </div>
    </section>
  )
}
