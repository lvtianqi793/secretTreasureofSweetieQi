/**
 * 能耗统计图表 API（与后端 context-path `/api` 对齐，开发环境由 Vite 代理到 8080）
 */

export type ChartPayload = {
  chartType: 'line' | 'bar' | 'pie'
  title: string
  categories?: string[]
  series: Array<{ name: string; data: number[] }>
  unit?: string
}

export type ChartRequest = {
  chartType: 'line' | 'bar' | 'pie'
  energyType: string
  granularity?: string
  startTime?: string
  endTime?: string
  buildingId?: string
  buildingType?: string
  topN?: number
  dimension?: 'time' | 'building' | 'type'
}

export type StatisticsExportBody = {
  energyType?: string
  buildingId?: string
  buildingType?: string
  startTime?: string
  endTime?: string
  granularity?: string
  analysisType: 'summary' | 'cop' | 'anomaly'
}

export type EnergyQueryExportBody = EnergyQueryExportFields & {
  format?: 'xlsx' | 'csv'
}

export type EnergyQueryExportFields = {
  energyType: string
  buildingId?: string
  buildingType?: string
  startTime?: string
  endTime?: string
  minValue?: number
  maxValue?: number
  maxRows?: number
  sortBy?: string
  sortOrder?: string
}

type ApiEnvelope<T> = { code: number; message?: string; data: T }

function assertOk<T>(json: ApiEnvelope<T>, res: Response): T {
  if (!res.ok) {
    throw new Error(json?.message || `请求失败（${res.status}）`)
  }
  if (json.code !== 200) {
    throw new Error(json.message || `业务错误（${json.code}）`)
  }
  return json.data
}

export async function fetchJson<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, init)
  const json = (await res.json()) as ApiEnvelope<T>
  return assertOk(json, res)
}

export async function postJson<T>(path: string, body: unknown): Promise<T> {
  return fetchJson<T>(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export type EnergyTypeOption = { value: string; label: string; unit?: string }
export type EnergyOptionsPayload = { energyTypes: EnergyTypeOption[]; buildingTypes: string[] }

/** GET /api/energy/options — 一次性返回能源类型 + 建筑类型选项 */
export function getEnergyOptions() {
  return fetchJson<EnergyOptionsPayload>('/api/energy/options')
}

export type BuildingInfo = { buildingId: string; buildingType: string }

/** GET /api/energy/buildings — 建筑列表（含建筑类型，供下拉选择） */
export function getBuildings() {
  return fetchJson<BuildingInfo[]>('/api/energy/buildings')
}

function buildQuery(params: Record<string, string | number | undefined | null>) {
  const q = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === '') continue
    q.set(k, String(v))
  }
  return q.toString()
}

/** GET /api/statistics/chart/trend */
export function getChartTrend(params: {
  energyType: string
  granularity?: string
  startTime?: string
  endTime?: string
  buildingId?: string
}) {
  const qs = buildQuery(params)
  return fetchJson<ChartPayload>(`/api/statistics/chart/trend?${qs}`)
}

/** GET /api/statistics/chart/ranking */
export function getChartRanking(params: {
  energyType: string
  topN?: number
  startTime?: string
  endTime?: string
  buildingId?: string
}) {
  const qs = buildQuery(params)
  return fetchJson<ChartPayload>(`/api/statistics/chart/ranking?${qs}`)
}

/** GET /api/statistics/chart/pie */
export function getChartPie(params: {
  energyType: string
  buildingType?: string
  startTime?: string
  endTime?: string
}) {
  const qs = buildQuery(params)
  return fetchJson<ChartPayload>(`/api/statistics/chart/pie?${qs}`)
}

/** POST /api/statistics/chart */
export function postChart(body: ChartRequest) {
  return postJson<ChartPayload>('/api/statistics/chart', body)
}

/** POST /api/statistics/export — 报表 xlsx（含内嵌图表等由后端决定） */
export async function postStatisticsExport(body: StatisticsExportBody) {
  const res = await fetch('/api/statistics/export', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!res.ok) {
    const t = await res.text()
    throw new Error(t || `导出失败（${res.status}）`)
  }
  return res.blob()
}

/** POST /api/statistics/chart/export — 单图表 Excel */
export async function postChartExport(body: ChartRequest) {
  const res = await fetch('/api/statistics/chart/export', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!res.ok) {
    const t = await res.text()
    throw new Error(t || `导出失败（${res.status}）`)
  }
  return res.blob()
}

/** POST /api/energy/query/export — 原始记录 */
export async function postEnergyQueryExport(body: EnergyQueryExportBody) {
  const { format, maxRows, ...rest } = body
  const qs = buildQuery({ format, maxRows })
  const url = qs ? `/api/energy/query/export?${qs}` : '/api/energy/query/export'
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(rest),
  })
  if (!res.ok) {
    const t = await res.text()
    throw new Error(t || `导出失败（${res.status}）`)
  }
  return res.blob()
}

export function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}
