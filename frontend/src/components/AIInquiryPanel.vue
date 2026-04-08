<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import MarkdownMessage from './MarkdownMessage.vue'

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
  // 只发送最后一条用户消息作为问题
  const lastUserMessage = messages.filter(m => m.role === 'user').pop()
  if (!lastUserMessage) {
    throw new Error('没有用户消息')
  }
  
  const payload = {
    question: lastUserMessage.content
  const payload = {
    messages: messages.map((m) => ({ role: m.role, content: m.content })),
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
    const response = (await res.json()) 
    // 检查是否是ApiResponse格式
    if (response.data && response.data.answer) {
      return response.data.answer
    }
    // 兼容其他格式
    const data = response as { reply?: string; message?: string; content?: string }
    const text = data.reply ?? data.message ?? data.content
    if (typeof text === 'string' && text.length > 0) return text
    throw new Error('响应 JSON 格式不正确')
    const data = (await res.json()) as { reply?: string; message?: string; content?: string }
    const text = data.reply ?? data.message ?? data.content
    if (typeof text === 'string' && text.length > 0) return text
    throw new Error('响应 JSON 中未找到 reply / message / content 字段')
  }

  return (await res.text()).trim() || '（空回复）'
}

const input = ref('')
const loading = ref(false)
const error = ref<string | null>(null)
const listRef = ref<HTMLDivElement | null>(null)

const activeMessages = computed(() => (mode.value === 'kb' ? messagesKb.value : messagesDb.value))

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
          <div class="ai-bubble__text">{{ m.content }}</div>
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
  </section>
</template>
