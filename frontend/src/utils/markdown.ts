import DOMPurify from 'dompurify'
import { marked } from 'marked'

/**
 * 将 Markdown 转为可安全用于 v-html 的 HTML（防 XSS）。
 */
export function markdownToSafeHtml(src: string): string {
  const html = marked.parse(src, {
    async: false,
    gfm: true,
    breaks: true,
  }) as string
  return DOMPurify.sanitize(html)
}
