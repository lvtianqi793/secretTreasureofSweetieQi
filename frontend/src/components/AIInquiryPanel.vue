<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'

type Role = 'user' | 'assistant'

type ChatMessage = {
  id: string
  role: Role
  content: string
}

function newId() {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 9)}`
}

/**
 * 后端可接 POST JSON：{ messages: { role, content }[] }
 * 返回 JSON：{ reply: string } 或纯文本。
 * 未配置 VITE_AI_API_URL 时使用本地演示回复。
 */
async function requestAssistant(messages: ChatMessage[]): Promise<string> {
  const raw = import.meta.env.VITE_AI_API_URL
  const base = typeof raw === 'string' ? raw.replace(/\/$/, '') : ''
  const payload = {
    messages: messages.map((m) => ({ role: m.role, content: m.content })),
  }

  if (!base) {
    await new Promise((r) => setTimeout(r, 600))
    const last = messages.filter((m) => m.role === 'user').at(-1)?.content ?? ''
    return `（演示模式）您说：「${last}」。在 frontend 目录创建 .env 并设置 VITE_AI_API_URL=你的服务地址 即可接入真实 AI。开发时也可把请求发到同源 /api，由 vite 代理到后端。`
  }

  const res = await fetch(`${base}/chat`, {
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
    const data = (await res.json()) as { reply?: string; message?: string; content?: string }
    const text = data.reply ?? data.message ?? data.content
    if (typeof text === 'string' && text.length > 0) return text
    throw new Error('响应 JSON 中未找到 reply / message / content 字段')
  }

  return (await res.text()).trim() || '（空回复）'
}

const messages = ref<ChatMessage[]>([])
const input = ref('')
const loading = ref(false)
const error = ref<string | null>(null)
const listRef = ref<HTMLDivElement | null>(null)

function scrollBottom() {
  const el = listRef.value
  if (el) el.scrollTop = el.scrollHeight
}

watch(
  [messages, loading],
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
  messages.value = [...messages.value, userMsg]
  input.value = ''
  loading.value = true
  try {
    const history = [...messages.value]
    const reply = await requestAssistant(history)
    messages.value = [...messages.value, { id: newId(), role: 'assistant', content: reply }]
  } catch (e) {
    const msg = e instanceof Error ? e.message : '未知错误'
    error.value = msg
  } finally {
    loading.value = false
  }
}

function onKeyDown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    void send()
  }
}
</script>

<template>
  <section class="ai-panel" aria-label="AI 询问">
    <header class="ai-panel__header">
      <div>
        <h1 class="ai-panel__title">AI 询问</h1>
        <p class="ai-panel__subtitle">在搜索框输入后发送；Shift+Enter 换行</p>
      </div>
    </header>

    <div class="ai-panel__body">
      <div ref="listRef" class="ai-messages" role="log" aria-live="polite">
        <p v-if="messages.length === 0 && !loading" class="ai-messages__empty">
          在下方搜索框输入问题开始对话。
        </p>
        <div
          v-for="m in messages"
          :key="m.id"
          class="ai-bubble"
          :class="[`ai-bubble--${m.role}`]"
          :data-role="m.role"
        >
          <span class="ai-bubble__label">{{ m.role === 'user' ? '我' : 'AI' }}</span>
          <div class="ai-bubble__text">{{ m.content }}</div>
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
          placeholder="向 AI 提问或搜索…"
          :disabled="loading"
          aria-label="AI 搜索与询问"
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
