<script setup lang="ts">
import { computed, ref, watch } from 'vue'

type Preview = {
  headers: string[]
  rows: string[][]
}

type ImportResult = {
  fileName?: string
  tableName?: string
  totalRows?: number
  importedRows?: number
  skippedRows?: number
  timeCostMs?: number
  status?: 'success' | 'partial' | 'failed' | string
  message?: string
}

type ApiEnvelope<T> = {
  code: number
  message?: string
  data?: T
}

type ImportResponse =
  | ({ ok: true } & ImportResult)
  | { ok: false; error: string }

const file = ref<File | null>(null)
const hasHeader = ref(true)

const loading = ref(false)
const error = ref<string | null>(null)
const result = ref<ImportResponse | null>(null)

const preview = ref<Preview | null>(null)
const previewing = ref(false)

function resolveImportUrl(): string {
  const raw = import.meta.env.VITE_CSV_IMPORT_URL
  const base = typeof raw === 'string' ? raw.replace(/\/$/, '') : ''
  if (base) return base
  return '/api/data/import/upload'
}

function normalizeRow(row: string[], width: number) {
  if (row.length === width) return row
  if (row.length > width) return row.slice(0, width)
  return [...row, ...Array.from({ length: width - row.length }, () => '')]
}

function parseCsv(text: string, d: string): string[][] {
  const rows: string[][] = []
  let row: string[] = []
  let field = ''
  let i = 0
  let inQuotes = false

  const pushField = () => {
    row.push(field)
    field = ''
  }
  const pushRow = () => {
    if (row.length === 1 && row[0] === '' && rows.length === 0) {
      row = []
      return
    }
    rows.push(row)
    row = []
  }

  while (i < text.length) {
    const c = text[i]
    if (inQuotes) {
      if (c === '"') {
        const next = text[i + 1]
        if (next === '"') {
          field += '"'
          i += 2
          continue
        }
        inQuotes = false
        i += 1
        continue
      }
      field += c
      i += 1
      continue
    }

    if (c === '"') {
      inQuotes = true
      i += 1
      continue
    }
    if (c === d) {
      pushField()
      i += 1
      continue
    }
    if (c === '\r') {
      i += 1
      continue
    }
    if (c === '\n') {
      pushField()
      pushRow()
      i += 1
      continue
    }
    field += c
    i += 1
  }

  pushField()
  if (row.length > 1 || row[0] !== '' || rows.length > 0) pushRow()

  return rows
}

async function buildPreview() {
  error.value = null
  result.value = null
  preview.value = null

  const f = file.value
  if (!f) return
  previewing.value = true
  try {
    const chunk = await f.slice(0, 256 * 1024).text()
    const allRows = parseCsv(chunk, ',')
      .map((r) => r.map((x) => x.trim()))
      .filter((r) => r.some((x) => x.length > 0))

    if (allRows.length === 0) {
      preview.value = { headers: [], rows: [] }
      return
    }

    const first = allRows[0] ?? []
    const headers = hasHeader.value
      ? first.map((h, idx) => (h.length ? h : `列${idx + 1}`))
      : first.map((_, idx) => `列${idx + 1}`)

    const dataRows = hasHeader.value ? allRows.slice(1) : allRows
    const width = Math.max(headers.length, ...dataRows.map((r) => r.length))
    const normalizedHeaders = normalizeRow(headers, width)
    const normalizedRows = dataRows.slice(0, 10).map((r) => normalizeRow(r, width))

    preview.value = { headers: normalizedHeaders, rows: normalizedRows }
  } catch (e) {
    const msg = e instanceof Error ? e.message : '预览失败'
    error.value = msg
  } finally {
    previewing.value = false
  }
}

watch([file, hasHeader], () => {
  void buildPreview()
})

const canImport = computed(() => !!file.value && !loading.value)

function onPickFile(e: Event) {
  const input = e.target as HTMLInputElement
  const f = input.files?.[0] ?? null
  file.value = f
}

async function importCsv() {
  error.value = null
  result.value = null

  if (!file.value) {
    error.value = '请先选择 CSV 文件'
    return
  }

  loading.value = true
  try {
    const fd = new FormData()
    fd.append('file', file.value)
    fd.append('hasHeader', hasHeader.value ? '1' : '0')

    const res = await fetch(resolveImportUrl(), {
      method: 'POST',
      body: fd,
    })

    const ct = res.headers.get('content-type') ?? ''
    if (!res.ok) {
      const t = await res.text()
      throw new Error(t || `导入失败（${res.status}）`)
    }

    if (ct.includes('application/json')) {
      const envelope = (await res.json()) as ApiEnvelope<ImportResult>
      if (envelope.code !== 200) {
        result.value = {
          ok: false,
          error: envelope.message || `业务错误（${envelope.code}）`,
        }
        return
      }
      const d = envelope.data ?? {}
      if (d.status === 'failed') {
        result.value = {
          ok: false,
          error: d.message || envelope.message || '后端处理失败',
        }
        return
      }
      result.value = {
        ok: true,
        fileName: d.fileName,
        tableName: d.tableName,
        totalRows: d.totalRows,
        importedRows: d.importedRows,
        skippedRows: d.skippedRows,
        timeCostMs: d.timeCostMs,
        status: d.status,
        message: d.message ?? envelope.message,
      }
      return
    }
    const text = (await res.text()).trim()
    result.value = { ok: true, message: text || '导入完成' }
  } catch (e) {
    const msg = e instanceof Error ? e.message : '导入失败'
    error.value = msg
    result.value = { ok: false, error: msg }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <section class="ai-panel" aria-label="CSV 导入数据库">
    <header class="ai-panel__header">
      <div class="ai-panel__header-top">
        <h1 class="ai-panel__title">CSV 导入数据库</h1>
      </div>
      <p class="ai-panel__subtitle">
        选择 CSV 文件后可预览前 10 行。
      </p>
    </header>

    <div class="csv-panel__body">
      <div class="csv-form">
        <label class="csv-field">
          <span class="csv-field__label">CSV 文件</span>
          <input class="csv-input" type="file" accept=".csv,text/csv" @change="onPickFile" />
        </label>

        <div class="csv-grid">
          <label class="csv-check">
            <input v-model="hasHeader" type="checkbox" />
            首行是表头
          </label>
        </div>

        <button class="csv-import-btn" type="button" :disabled="!canImport" @click="importCsv">
          {{ loading ? '导入中…' : '开始导入' }}
        </button>
      </div>

      <div v-if="error" class="ai-error" role="alert">
        {{ error }}
      </div>

      <div class="csv-preview">
        <div class="csv-preview__head">
          <h2 class="csv-preview__title">预览</h2>
          <span class="csv-preview__meta">
            <template v-if="previewing">解析中…</template>
            <template v-else-if="file">已选择：{{ file.name }}（{{ Math.ceil(file.size / 1024) }} KB）</template>
            <template v-else>未选择文件</template>
          </span>
        </div>

        <div v-if="preview && preview.headers.length > 0" class="csv-table-wrap">
          <table class="csv-table">
            <thead>
              <tr>
                <th v-for="(h, i) in preview.headers" :key="i">{{ h }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(r, idx) in preview.rows" :key="idx">
                <td v-for="(c, j) in r" :key="j">{{ c }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-else class="csv-preview__empty">选择文件后会显示前 10 行预览。</p>
      </div>

      <div v-if="result" class="csv-result" role="status" aria-live="polite">
        <template v-if="result.ok">
          <div class="csv-result__title">
            {{ result.status === 'partial' ? '导入部分完成' : '导入完成' }}
          </div>
          <div class="csv-result__content">
            <div v-if="result.fileName">文件：{{ result.fileName }}</div>
            <div v-if="result.tableName">目标表：{{ result.tableName }}</div>
            <div v-if="typeof result.totalRows === 'number'">总行数：{{ result.totalRows }}</div>
            <div v-if="typeof result.importedRows === 'number'">已导入：{{ result.importedRows }}</div>
            <div v-if="typeof result.skippedRows === 'number'">跳过：{{ result.skippedRows }}</div>
            <div v-if="typeof result.timeCostMs === 'number'">耗时：{{ (result.timeCostMs / 1000).toFixed(2) }} s</div>
            <div v-if="result.message">{{ result.message }}</div>
          </div>
        </template>
        <template v-else>
          <div class="csv-result__title csv-result__title--error">导入失败</div>
          <div class="csv-result__content">{{ result.error }}</div>
        </template>
      </div>
    </div>
  </section>
</template>

