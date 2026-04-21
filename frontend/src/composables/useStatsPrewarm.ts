import { ref } from 'vue'
import {
  type BuildingInfo,
  type ChartPayload,
  type EnergyOptionsPayload,
  type EnergyTypeOption,
  getBuildings,
  getChartTrend,
  getEnergyOptions,
} from '../api/statistics'

export type PrewarmedChart = {
  id: string
  payload: ChartPayload
  createdAt: number
}

export type TrendKey = {
  energyType: string
  granularity: string
  startTime: string
  endTime: string
  buildingId: string
}

const DEFAULT_TREND: TrendKey = {
  energyType: 'electricity',
  granularity: 'month',
  startTime: '2016-01-01 00:00:00',
  endTime: '2016-12-31 23:59:59',
  buildingId: '',
}

export const energyTypeOptions = ref<EnergyTypeOption[]>([])
export const buildingTypeOptions = ref<string[]>([])
export const buildingOptions = ref<BuildingInfo[]>([])
export const defaultTrendChart = ref<PrewarmedChart | null>(null)
export const defaultTrendKey = ref<TrendKey>({ ...DEFAULT_TREND })

let optionsPromise: Promise<EnergyOptionsPayload | null> | null = null
let buildingsPromise: Promise<BuildingInfo[] | null> | null = null
let trendPromise: Promise<ChartPayload | null> | null = null

function keyOf(k: TrendKey): string {
  return `${k.energyType}|${k.granularity}|${k.startTime}|${k.endTime}|${k.buildingId}`
}

function ensureOptions() {
  if (optionsPromise) return optionsPromise
  optionsPromise = (async () => {
    try {
      const opt = await getEnergyOptions()
      energyTypeOptions.value = opt.energyTypes ?? []
      buildingTypeOptions.value = opt.buildingTypes ?? []
      return opt
    } catch {
      optionsPromise = null
      return null
    }
  })()
  return optionsPromise
}

function ensureBuildings() {
  if (buildingsPromise) return buildingsPromise
  buildingsPromise = (async () => {
    try {
      const list = (await getBuildings()) ?? []
      buildingOptions.value = list
      return list
    } catch {
      buildingsPromise = null
      return null
    }
  })()
  return buildingsPromise
}

function ensureDefaultTrend() {
  if (trendPromise) return trendPromise
  const key = defaultTrendKey.value
  trendPromise = (async () => {
    try {
      const data = await getChartTrend({
        energyType: key.energyType,
        granularity: key.granularity,
        startTime: key.startTime || undefined,
        endTime: key.endTime || undefined,
        buildingId: key.buildingId || undefined,
      })
      const now = Date.now()
      defaultTrendChart.value = {
        id: `prewarm_${now}_${Math.random().toString(16).slice(2)}`,
        payload: data,
        createdAt: now,
      }
      return data
    } catch {
      trendPromise = null
      return null
    }
  })()
  return trendPromise
}

/** 在 AI 问答界面挂载时调用，后台预取统计图表首屏所需数据。幂等，重复调用不会重复请求。 */
export function prewarmStats() {
  void ensureOptions()
  void ensureBuildings()
  void ensureDefaultTrend()
}

/** 统计图表面板读取预取结果；若与期望 key 不一致，会重新触发一次拉取。 */
export function consumeDefaultTrend(wanted: TrendKey): PrewarmedChart | null {
  if (keyOf(wanted) !== keyOf(defaultTrendKey.value)) return null
  return defaultTrendChart.value
}
