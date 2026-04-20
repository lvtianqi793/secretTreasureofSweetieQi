<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import MarkdownMessage from './MarkdownMessage.vue'
import CsvImportPanel from './CsvImportPanel.vue'

type Role = 'user' | 'assistant'

type ChatMessage = {
  id: string
  role: Role
  content: string
}

type QaMode = 'kb' | 'db'

/** 知识库 → ops；数据库 → chat */
function endpointForMode(m: QaMode): 'ops' | 'chat' {
  return m === 'kb' ? 'ops' : 'chat'
}

const mode = ref<QaMode>('kb')

const messagesKb = ref<ChatMessage[]>([])
const messagesDb = ref<ChatMessage[]>([])

function messagesForActiveMode() {
  return mode.value === 'kb' ? messagesKb : messagesDb
}

function newId() {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 9)}`
}

function resolveRequestUrl(): string {
  const ep = endpointForMode(mode.value)
  const raw = import.meta.env.VITE_AI_API_URL
  const base = typeof raw === 'string' ? raw.replace(/\/$/, '') : ''
  if (base) return `${base}/${ep}`
  return `/api/ai/${ep}`
}

/**
 * 后端可接 POST JSON：{ messages: { role, content }[] }
 * 返回 JSON：{ reply: string } 或纯文本。
 * 同源开发时请求 `/api/ai/chat` 或 `/api/ai/ops`，由 Vite 代理到后端；也可设置 VITE_AI_API_URL（到 `/api/ai` 为止）直连。
 */
async function requestAssistant(messages: ChatMessage[]): Promise<string> {
  const url = resolveRequestUrl()
  // 后端 AiChatRequest 使用 history 字段 (已 @JsonAlias 接受 messages)
  // question = 当前轮用户输入；history = 之前所有轮次（不含当前这条 user）
  const lastUserMessage = messages.filter((m) => m.role === 'user').at(-1)
  const history = messages
    .slice(0, -1)
    .filter((m) => m.role === 'user' || m.role === 'assistant')
    .map((m) => ({ role: m.role, content: m.content }))
  const payload = {
    question: lastUserMessage?.content ?? '',
    history,
  }

  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })

  if (!res.ok) {
    const t = await res.text()
    throw new Error(t || `请求失败（${res.status}）`)
  }

  const ct = res.headers.get('content-type') ?? ''
  if (ct.includes('application/json')) {
    const response = (await res.json()) as any

    if (typeof response?.data?.answer === 'string' && response.data.answer.length > 0) {
      return response.data.answer
    }

    const data = response as { reply?: string; message?: string; content?: string }
    const text = data.reply ?? data.message ?? data.content
    if (typeof text === 'string' && text.length > 0) return text
    throw new Error('响应 JSON 中未找到 answer / reply / message / content 字段')
  }

  return (await res.text()).trim() || '（空回复）'
}

const input = ref('')
const loading = ref(false)
const error = ref<string | null>(null)
const listRef = ref<HTMLDivElement | null>(null)
const csvOpen = ref(false)

const activeMessages = computed(() => (mode.value === 'kb' ? messagesKb.value : messagesDb.value))

const quickQuestionsOps = [
  '查询今天各车间/产线的能耗汇总（电/水/气）',
  '查询某设备近 24 小时能耗曲线，并给出峰值时段',
  '某设备当前运行状态是什么？最近一次告警是什么？',
  '分析本周能耗异常的可能原因，并给出排查步骤',
  '对比本月与上月能耗变化，定位主要增量来源',
  '给出节能优化建议：优先级、预估收益、风险点',
] as const

const quickQuestionsDb = [
  '查询今天总能耗、同比/环比变化',
  '按设备统计本周能耗 TOP10',
  '找出近 7 天能耗异常的设备与发生时间',
  '统计各区域今日峰谷用电量与峰谷比',
] as const

const quickQuestionsTop3 = computed(() =>
  (mode.value === 'kb' ? quickQuestionsOps : quickQuestionsDb).slice(0, 3),
)

function sendQuickQuestion(q: string) {
  if (loading.value) return
  error.value = null
  input.value = q
  void nextTick(() => send())
}

function scrollBottom() {
  const el = listRef.value
  if (el) el.scrollTop = el.scrollHeight
}

watch(mode, () => {
  error.value = null
})

watch(
  [mode, messagesKb, messagesDb, loading],
  () => {
    void nextTick(() => scrollBottom())
  },
  { deep: true },
)

async function send() {
  const text = input.value.trim()
  if (!text || loading.value) return
  error.value = null
  const userMsg: ChatMessage = { id: newId(), role: 'user', content: text }
  const bucket = messagesForActiveMode()
  bucket.value = [...bucket.value, userMsg]
  input.value = ''
  loading.value = true
  try {
    const history = [...bucket.value]
    const reply = await requestAssistant(history)
    bucket.value = [...bucket.value, { id: newId(), role: 'assistant', content: reply }]
  } catch (e) {
    const msg = e instanceof Error ? e.message : '未知错误'
    error.value = msg
  } finally {
    loading.value = false
  }
}

const modeSubtitle = computed(() =>
  mode.value === 'kb'
    ? '当前：知识库（api/ai/ops）；Shift+Enter 换行'
    : '当前：数据库（api/ai/chat）；Shift+Enter 换行',
)

const inputPlaceholder = computed(() =>
  mode.value === 'kb' ? '向知识库提问…' : '向数据库问答提问…',
)

function onKeyDown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    void send()
  }
}
</script>

<template>
  <section class="ai-panel" aria-label="AI 问答">
    <header class="ai-panel__header">
      <div class="ai-panel__header-top">
        <h1 class="ai-panel__title">AI 问答</h1>
        <button type="button" class="ai-panel__action" @click="csvOpen = true">CSV 导入</button>
        <div class="ai-mode-switch" role="tablist" aria-label="问答模式">
          <button
            type="button"
            role="tab"
            class="ai-mode-switch__btn"
            :class="{ 'ai-mode-switch__btn--active': mode === 'kb' }"
            :aria-selected="mode === 'kb'"
            @click="mode = 'kb'"
          >
            知识库问答
          </button>
          <button
            type="button"
            role="tab"
            class="ai-mode-switch__btn"
            :class="{ 'ai-mode-switch__btn--active': mode === 'db' }"
            :aria-selected="mode === 'db'"
            @click="mode = 'db'"
          >
            数据库问答
          </button>
        </div>
      </div>
      <p class="ai-panel__subtitle">{{ modeSubtitle }}</p>
    </header>

    <div class="ai-panel__body">
      <div ref="listRef" class="ai-messages" role="log" aria-live="polite">
        <p v-if="activeMessages.length === 0 && !loading" class="ai-messages__empty">
          在下方输入问题开始对话。
        </p>
        <div
          v-for="m in activeMessages"
          :key="m.id"
          class="ai-bubble"
          :class="[`ai-bubble--${m.role}`]"
          :data-role="m.role"
        >
          <span class="ai-bubble__label">{{ m.role === 'user' ? '我' : 'AI' }}</span>
          <MarkdownMessage class="ai-bubble__text" :content="m.content" />
        </div>
        <div v-if="loading" class="ai-bubble ai-bubble--assistant ai-bubble--pending">
          <span class="ai-bubble__label">AI</span>
          <div class="ai-bubble__typing" aria-hidden="true">
            <span />
            <span />
            <span />
          </div>
        </div>
      </div>

      <div v-if="error" class="ai-error" role="alert">
        {{ error }}
      </div>
    </div>

    <footer class="ai-search-dock">
      <div
        v-if="!loading"
        class="ai-quick-questions ai-quick-questions--dock"
        aria-label="快捷问题"
      >
        <button
          v-for="q in quickQuestionsTop3"
          :key="q"
          type="button"
          class="ai-quick-questions__item"
          @click="sendQuickQuestion(q)"
        >
          {{ q }}
        </button>
      </div>
      <div class="ai-search-box">
        <span class="ai-search-box__icon" aria-hidden="true">
          <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="11" cy="11" r="8" />
            <path d="m21 21-4.3-4.3" />
          </svg>
        </span>
        <textarea
          v-model="input"
          class="ai-search-box__input"
          rows="1"
          :placeholder="inputPlaceholder"
          :disabled="loading"
          aria-label="问答输入"
          @keydown="onKeyDown"
        />
        <button
          type="button"
          class="ai-search-box__send"
          :disabled="loading || !input.trim()"
          :aria-busy="loading"
          :aria-label="loading ? '正在发送' : '发送询问'"
          @click="send()"
        >
          <span class="ai-search-box__send-label">{{ loading ? '发送中' : '发送' }}</span>
          <svg class="ai-search-box__send-icon" xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M22 2 11 13" />
            <path d="M22 2l-7 20-4-9-9-4 18-9" />
          </svg>
        </button>
      </div>
    </footer>

    <div v-if="csvOpen" class="csv-modal" role="dialog" aria-modal="true" aria-label="CSV 导入">
      <div class="csv-modal__backdrop" @click="csvOpen = false" />
      <div class="csv-modal__panel">
        <div class="csv-modal__top">
          <div class="csv-modal__title">CSV 导入</div>
          <button type="button" class="csv-modal__close" @click="csvOpen = false">关闭</button>
        </div>
        <div class="csv-modal__content">
          <CsvImportPanel />
        </div>
      </div>
    </div>
  </section>
</template>
