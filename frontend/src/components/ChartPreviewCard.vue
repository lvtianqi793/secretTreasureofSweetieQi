<script setup lang="ts">
import * as echarts from 'echarts'
import { nextTick, onMounted, onUnmounted, ref, watch } from 'vue'

type ChartPayload = {
  chartType: 'line' | 'bar' | 'pie'
  title: string
  categories?: string[]
  series: Array<{ name: string; data: number[] }>
  unit?: string
}

const props = defineProps<{
  payload: ChartPayload
  createdAt: number
}>()

const chartEl = ref<HTMLDivElement | null>(null)
let chartInst: echarts.ECharts | null = null

function inferGranularityFromTitle(title?: string) {
  const t = title ?? ''
  const m = t.match(/\\((hour|day|week|month|year)\\)/i)
  return (m?.[1]?.toLowerCase() as 'hour' | 'day' | 'week' | 'month' | 'year' | undefined) ?? undefined
}

function formatTimeLabel(raw: string, granularity?: string) {
  const s = String(raw ?? '')
  if (!s) return ''
  const cleaned = s.replace('T', ' ').replace('.0', '')
  const [datePart, timePart = ''] = cleaned.split(' ')
  const [y, mo, d] = datePart.split('-')
  const hhmm = timePart.slice(0, 5)
  if (!y || !mo || !d) return s

  switch (granularity) {
    case 'year':
      return `${y}`
    case 'month':
      return `${y}-${mo}`
    case 'week':
      return `${mo}/${d}`
    case 'hour':
      return hhmm ? `${mo}/${d} ${hhmm}` : `${mo}/${d}`
    case 'day':
    default:
      return `${mo}/${d}`
  }
}

function calcLabelInterval(len: number) {
  if (len <= 12) return 0
  if (len <= 24) return 1
  if (len <= 60) return 2
  return Math.ceil(len / 20)
}

function formatCompactNumber(v: number): string {
  if (!Number.isFinite(v)) return ''
  const abs = Math.abs(v)
  const sign = v < 0 ? '-' : ''
  if (abs >= 1e9) return `${sign}${(abs / 1e9).toFixed(abs >= 1e10 ? 1 : 2)}B`
  if (abs >= 1e6) return `${sign}${(abs / 1e6).toFixed(abs >= 1e7 ? 1 : 2)}M`
  if (abs >= 1e3) return `${sign}${(abs / 1e3).toFixed(abs >= 1e4 ? 1 : 2)}K`
  if (abs >= 1) return `${sign}${abs.toFixed(abs >= 100 ? 0 : abs >= 10 ? 1 : 2)}`
  if (abs === 0) return '0'
  return `${sign}${abs.toPrecision(2)}`
}

function buildOption(data: ChartPayload): echarts.EChartsOption {
  const unit = data.unit ?? ''
  const textColor = '#e8edf4'
  const muted = '#8b9cb3'
  const granularity = inferGranularityFromTitle(data.title)

  const baseTitle: echarts.TitleComponentOption = {
    text: data.title,
    left: 'center',
    top: 8,
    textStyle: { color: textColor, fontSize: 14 },
  }

  if (data.chartType === 'pie') {
    const cats = data.categories ?? []
    const first = data.series[0]
    const pieData: { name: string; value: number }[] = []
    if (cats.length > 0 && first?.data?.length) {
      for (let i = 0; i < cats.length; i += 1) {
        pieData.push({ name: cats[i] ?? `项${i + 1}`, value: Number(first.data[i] ?? 0) })
      }
    }

    return {
      backgroundColor: 'transparent',
      title: baseTitle,
      tooltip: { trigger: 'item' },
      legend: { bottom: 8, textStyle: { color: muted } },
      series: [
        {
          name: data.series[0]?.name ?? '占比',
          type: 'pie',
          radius: ['38%', '68%'],
          data: pieData.length ? pieData : [{ name: '暂无数据', value: 0 }],
          label: { color: textColor },
        },
      ],
    }
  }

  const categories = data.categories ?? []
  const interval = calcLabelInterval(categories.length)

  if (data.chartType === 'line') {
    return {
      backgroundColor: 'transparent',
      title: baseTitle,
      tooltip: {
        trigger: 'axis',
        valueFormatter: (v: unknown) => {
          const n = typeof v === 'number' ? v : Number(v)
          if (!Number.isFinite(n)) return String(v ?? '')
          return `${n.toLocaleString()}${unit ? ` ${unit}` : ''}`
        },
        axisPointer: { type: 'line' },
      },
      legend: { bottom: 4, textStyle: { color: muted } },
      grid: { left: 64, right: 24, top: 48, bottom: 72 },
      xAxis: {
        type: 'category',
        data: categories,
        boundaryGap: false,
        axisLine: { show: true, lineStyle: { color: 'rgba(255,255,255,0.25)' } },
        axisTick: { show: true, alignWithLabel: true, lineStyle: { color: 'rgba(255,255,255,0.25)' } },
        axisLabel: {
          color: muted,
          rotate: categories.length > 18 ? 35 : 0,
          interval,
          formatter: (v: string) => formatTimeLabel(v, granularity),
        },
      },
      yAxis: {
        type: 'value',
        name: unit,
        nameTextStyle: { color: muted },
        scale: true,
        axisLine: { show: true, lineStyle: { color: 'rgba(255,255,255,0.25)' } },
        axisTick: { show: true, lineStyle: { color: 'rgba(255,255,255,0.25)' } },
        axisLabel: {
          color: muted,
          formatter: (v: number) => formatCompactNumber(v),
        },
        splitLine: { lineStyle: { color: 'rgba(255,255,255,0.06)' } },
      },
      series: data.series.map((s) => ({
        name: s.name,
        type: 'line',
        smooth: true,
        data: s.data,
        symbolSize: 6,
      })),
    }
  }

  return {
    backgroundColor: 'transparent',
    title: baseTitle,
    tooltip: {
      trigger: 'axis',
      valueFormatter: (v: unknown) => {
        const n = typeof v === 'number' ? v : Number(v)
        if (!Number.isFinite(n)) return String(v ?? '')
        return `${n.toLocaleString()}${unit ? ` ${unit}` : ''}`
      },
      axisPointer: { type: 'shadow' },
    },
    legend: { bottom: 4, textStyle: { color: muted } },
    grid: { left: 64, right: 24, top: 48, bottom: 72 },
    xAxis: {
      type: 'category',
      data: categories,
      axisLine: { show: true, lineStyle: { color: 'rgba(255,255,255,0.25)' } },
      axisTick: { show: true, alignWithLabel: true, lineStyle: { color: 'rgba(255,255,255,0.25)' } },
      axisLabel: {
        color: muted,
        rotate: categories.length > 14 ? 30 : 0,
        interval,
        formatter: (v: string) => formatTimeLabel(v, granularity),
      },
    },
    yAxis: {
      type: 'value',
      name: unit,
      nameTextStyle: { color: muted },
      axisLine: { show: true, lineStyle: { color: 'rgba(255,255,255,0.25)' } },
      axisTick: { show: true, lineStyle: { color: 'rgba(255,255,255,0.25)' } },
      axisLabel: {
        color: muted,
        formatter: (v: number) => formatCompactNumber(v),
      },
      splitLine: { lineStyle: { color: 'rgba(255,255,255,0.06)' } },
    },
    series: data.series.map((s) => ({
      name: s.name,
      type: 'bar',
      data: s.data,
      barMaxWidth: 36,
    })),
  }
}

function render() {
  nextTick(() => {
    if (!chartEl.value) return
    if (!chartInst) chartInst = echarts.init(chartEl.value, undefined, { renderer: 'canvas' })
    chartInst.setOption(buildOption(props.payload), true)
    chartInst.resize()
  })
}

function onResize() {
  chartInst?.resize()
}

onMounted(() => {
  window.addEventListener('resize', onResize)
  render()
})

onUnmounted(() => {
  window.removeEventListener('resize', onResize)
  chartInst?.dispose()
  chartInst = null
})

watch(
  () => props.payload,
  () => render(),
  { deep: true }
)
</script>

<template>
  <article class="chart-card">
    <header class="chart-card__head">
      <div class="chart-card__title">{{ payload.title }}</div>
      <div class="chart-card__meta">{{ new Date(createdAt).toLocaleString() }}</div>
    </header>
    <div ref="chartEl" class="chart-card__chart" />
  </article>
</template>

<style scoped>
.chart-card {
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 12px;
  background: rgba(0, 0, 0, 0.08);
  overflow: hidden;
}
.chart-card__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 0.75rem;
  padding: 0.6rem 0.85rem;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(36, 48, 68, 0.25);
}
.chart-card__title {
  font-size: 0.9rem;
  font-weight: 800;
  color: #e8edf4;
}
.chart-card__meta {
  font-size: 0.75rem;
  color: rgba(255, 255, 255, 0.55);
  white-space: nowrap;
}
.chart-card__chart {
  width: 100%;
  height: 360px;
}
</style>

