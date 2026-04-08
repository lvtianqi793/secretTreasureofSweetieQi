/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<object, object, unknown>
  export default component
}

interface ImportMetaEnv {
  /**
   * AI 接口前缀，需包含到 `/api/ai` 为止，例如 `http://127.0.0.1:8000/api/ai`。
   * 未设置时前端请求同源相对路径 `/api/ai/chat`、`/api/ai/ops`（由 Vite 代理到后端）。
   */
  readonly VITE_AI_API_URL?: string

  /**
   * CSV 导入接口地址（完整 URL），例如 `http://127.0.0.1:8000/api/data`。
   * 未设置时前端请求同源相对路径 `/api/data`（由 Vite 代理到后端）。
   */
  readonly VITE_CSV_IMPORT_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
