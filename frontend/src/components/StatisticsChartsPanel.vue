<script setup lang="ts">
import * as echarts from 'echarts'
import {
  computed,
  nextTick,
  onMounted,
  onUnmounted,
  ref,
  shallowRef,
  watch,
} from 'vue'
import {
  type ChartPayload,
  type ChartRequest,
  type EnergyQueryExportBody,
  type StatisticsExportBody,
  downloadBlob,
  getChartPie,
  getChartRanking,
  getChartTrend,
  postChart,
  postChartExport,
  postEnergyQueryExport,
  postStatisticsExport,
} from '../api/statistics'

type ChartTab = 'trend' | 'ranking' | 'pie' | 'generic'
type ExportTab = 'report' | 'chart' | 'raw'

const chartTab = ref<ChartTab>('trend')
const exportTab = ref<ExportTab>('report')
const mainTab = ref<'view' | 'export'>('view')

const loading = ref(false)
const error = ref<string | null>(null)
const chartPayload = shallowRef<ChartPayload | null>(null)

const chartEl = ref<HTMLDivElement | null>(null)
let chartInst: echarts.ECharts | null = null

// —— 快捷：趋势
const trendEnergy = ref('electricity')
const trendGranularity = ref('month')
const trendStart = ref('2023-01-01')
const trendEnd = ref('2023-12-31')
const trendBuilding = ref('')

// —— 快捷：排名
const rankEnergy = ref('electricity')
const rankTopN = ref(10)
const rankStart = ref('')
const rankEnd = ref('')
const rankBuilding = ref('')

// —— 快捷：饼图
const pieEnergy = ref('water')
const pieBuildingType = ref('')
const pieStart = ref('')
const pieEnd = ref('')

// —— 通用 POST
const genBody = ref<ChartRequest>({
  chartType: 'line',
  energyType: 'electricity',
  granularity: 'month',
  startTime: '2023-01-01',
  endTime: '2023-12-31',
})

// —— 导出：统计报表
const expReport = ref<StatisticsExportBody>({
  analysisType: 'summary',
  energyType: 'electricity',
  granularity: 'month',
  startTime: '2023-01-01 00:00:00',
  endTime: '2023-12-31 23:59:59',
})

// —— 导出：单图表 Excel
const expChart = ref<ChartRequest>({
  chartType: 'line',
  energyType: 'electricity',
  granularity: 'month',
  startTime: '2023-01-01',
  endTime: '2023-12-31',
})

// —— 导出：原始记录
const expRaw = ref<EnergyQueryExportBody>({
  energyType: 'electricity',
  format: 'xlsx',
  page: 1,
  pageSize: 5000,
  sortBy: 'monitor_time',
  sortOrder: 'desc',
})

const chartTitle = computed(() => chartPayload.value?.title ?? '图表预览')

function disposeChart() {
  chartInst?.dispose()
  chartInst = null
}

function buildOption(data: ChartPayload): echarts.EChartsOption {
  const unit = data.unit ?? ''
  const textColor = '#e8edf4'
  const muted = '#8b9cb3'

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
  if (data.chartType === 'line') {
    return {
      backgroundColor: 'transparent',
      title: baseTitle,
      tooltip: { trigger: 'axis' },
      legend: { bottom: 4, textStyle: { color: muted } },
      grid: { left: 56, right: 20, top: 48, bottom: 72 },
      xAxis: {
        type: 'category',
        data: categories,
        axisLabel: { color: muted, rotate: categories.length > 12 ? 35 : 0 },
      },
      yAxis: {
        type: 'value',
        name: unit,
        nameTextStyle: { color: muted },
        axisLabel: { color: muted },
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

  // bar
  return {
    backgroundColor: 'transparent',
    title: baseTitle,
    tooltip: { trigger: 'axis' },
    legend: { bottom: 4, textStyle: { color: muted } },
    grid: { left: 56, right: 20, top: 48, bottom: 72 },
    xAxis: {
      type: 'category',
      data: categories,
      axisLabel: { color: muted, rotate: categories.length > 10 ? 30 : 0 },
    },
    yAxis: {
      type: 'value',
      name: unit,
      nameTextStyle: { color: muted },
      axisLabel: { color: muted },
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

function renderChart(data: ChartPayload) {
  chartPayload.value = data
  nextTick(() => {
    if (!chartEl.value) return
    if (!chartInst) {
      chartInst = echarts.init(chartEl.value, undefined, { renderer: 'canvas' })
    }
    chartInst.setOption(buildOption(data), true)
    chartInst.resize()
  })
}

async function loadTrend() {
  loading.value = true
  error.value = null
  try {
    const data = await getChartTrend({
      energyType: trendEnergy.value,
      granularity: trendGranularity.value,
      startTime: trendStart.value || undefined,
      endTime: trendEnd.value || undefined,
      buildingId: trendBuilding.value || undefined,
    })
    renderChart(data)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
    chartPayload.value = null
    disposeChart()
  } finally {
    loading.value = false
  }
}

async function loadRanking() {
  loading.value = true
  error.value = null
  try {
    const data = await getChartRanking({
      energyType: rankEnergy.value,
      topN: rankTopN.value,
      startTime: rankStart.value || undefined,
      endTime: rankEnd.value || undefined,
      buildingId: rankBuilding.value || undefined,
    })
    renderChart(data)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
    chartPayload.value = null
    disposeChart()
  } finally {
    loading.value = false
  }
}

async function loadPie() {
  loading.value = true
  error.value = null
  try {
    const data = await getChartPie({
      energyType: pieEnergy.value,
      buildingType: pieBuildingType.value || undefined,
      startTime: pieStart.value || undefined,
      endTime: pieEnd.value || undefined,
    })
    renderChart(data)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
    chartPayload.value = null
    disposeChart()
  } finally {
    loading.value = false
  }
}

async function loadGeneric() {
  loading.value = true
  error.value = null
  try {
    const data = await postChart({ ...genBody.value })
    renderChart(data)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
    chartPayload.value = null
    disposeChart()
  } finally {
    loading.value = false
  }
}

function onResize() {
  chartInst?.resize()
}

onMounted(() => {
  window.addEventListener('resize', onResize)
  void loadTrend()
})

onUnmounted(() => {
  window.removeEventListener('resize', onResize)
  disposeChart()
})

watch(chartTab, (t) => {
  error.value = null
  if (t === 'trend') void loadTrend()
  if (t === 'ranking') void loadRanking()
  if (t === 'pie') void loadPie()
  if (t === 'generic') {
    chartPayload.value = null
    disposeChart()
  }
})

async function doExportReport() {
  loading.value = true
  error.value = null
  try {
    const blob = await postStatisticsExport({ ...expReport.value })
    const name = `能耗统计报表_${expReport.value.analysisType}_${new Date().toISOString().slice(0, 19).replace(/[:T]/g, '')}.xlsx`
    downloadBlob(blob, name)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '导出失败'
  } finally {
    loading.value = false
  }
}

async function doExportChart() {
  loading.value = true
  error.value = null
  try {
    const blob = await postChartExport({ ...expChart.value })
    downloadBlob(blob, `统计图表_${expChart.value.chartType}_${Date.now()}.xlsx`)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '导出失败'
  } finally {
    loading.value = false
  }
}

async function doExportRaw() {
  loading.value = true
  error.value = null
  try {
    const blob = await postEnergyQueryExport({ ...expRaw.value })
    const ext = expRaw.value.format === 'csv' ? 'csv' : 'xlsx'
    downloadBlob(blob, `能耗原始记录_${expRaw.value.energyType}_${Date.now()}.${ext}`)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '导出失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <section class="ai-panel stats-panel" aria-label="能耗统计图表">
    <header class="ai-panel__header">
      <div class="ai-panel__header-top">
        <h1 class="ai-panel__title">能耗统计</h1>
        <div class="ai-mode-switch" role="tablist" aria-label="主功能">
          <button
            type="button"
            role="tab"
            class="ai-mode-switch__btn"
            :class="{ 'ai-mode-switch__btn--active': mainTab === 'view' }"
            :aria-selected="mainTab === 'view'"
            @click="mainTab = 'view'"
          >
            图表预览
          </button>
          <button
            type="button"
            role="tab"
            class="ai-mode-switch__btn"
            :class="{ 'ai-mode-switch__btn--active': mainTab === 'export' }"
            :aria-selected="mainTab === 'export'"
            @click="mainTab = 'export'"
          >
            数据导出
          </button>
        </div>
      </div>
      <p class="ai-panel__subtitle">
        对接 <code>/api/statistics/chart</code> 系列与导出接口；图表使用 ECharts 渲染。
      </p>
    </header>

    <div class="stats-panel__body">
      <div v-if="error" class="ai-error" role="alert">
        {{ error }}
      </div>

      <!-- 图表 -->
      <template v-if="mainTab === 'view'">
        <div class="stats-subtabs" role="tablist">
          <button
            type="button"
            class="stats-subtabs__btn"
            :class="{ 'stats-subtabs__btn--active': chartTab === 'trend' }"
            @click="chartTab = 'trend'"
          >
            时段趋势
          </button>
          <button
            type="button"
            class="stats-subtabs__btn"
            :class="{ 'stats-subtabs__btn--active': chartTab === 'ranking' }"
            @click="chartTab = 'ranking'"
          >
            建筑排名
          </button>
          <button
            type="button"
            class="stats-subtabs__btn"
            :class="{ 'stats-subtabs__btn--active': chartTab === 'pie' }"
            @click="chartTab = 'pie'"
          >
            类型占比
          </button>
          <button
            type="button"
            class="stats-subtabs__btn"
            :class="{ 'stats-subtabs__btn--active': chartTab === 'generic' }"
            @click="chartTab = 'generic'"
          >
            通用请求
          </button>
        </div>

        <div v-if="chartTab === 'trend'" class="stats-form">
          <label class="stats-field">
            <span>能源类型</span>
            <input v-model="trendEnergy" class="csv-input" placeholder="electricity" />
          </label>
          <label class="stats-field">
            <span>粒度</span>
            <select v-model="trendGranularity" class="csv-input">
              <option value="hour">hour</option>
              <option value="day">day</option>
              <option value="week">week</option>
              <option value="month">month</option>
              <option value="year">year</option>
            </select>
          </label>
          <label class="stats-field">
            <span>开始日期</span>
            <input v-model="trendStart" class="csv-input" type="date" />
          </label>
          <label class="stats-field">
            <span>结束日期</span>
            <input v-model="trendEnd" class="csv-input" type="date" />
          </label>
          <label class="stats-field stats-field--wide">
            <span>建筑 ID（可选）</span>
            <input v-model="trendBuilding" class="csv-input" placeholder="留空表示全部" />
          </label>
          <button type="button" class="stats-btn" :disabled="loading" @click="loadTrend">查询</button>
        </div>

        <div v-if="chartTab === 'ranking'" class="stats-form">
          <label class="stats-field">
            <span>能源类型</span>
            <input v-model="rankEnergy" class="csv-input" />
          </label>
          <label class="stats-field">
            <span>Top N</span>
            <input v-model.number="rankTopN" class="csv-input" type="number" min="1" max="100" />
          </label>
          <label class="stats-field">
            <span>开始（可选）</span>
            <input v-model="rankStart" class="csv-input" type="date" />
          </label>
          <label class="stats-field">
            <span>结束（可选）</span>
            <input v-model="rankEnd" class="csv-input" type="date" />
          </label>
          <label class="stats-field stats-field--wide">
            <span>建筑 ID（可选）</span>
            <input v-model="rankBuilding" class="csv-input" />
          </label>
          <button type="button" class="stats-btn" :disabled="loading" @click="loadRanking">查询</button>
        </div>

        <div v-if="chartTab === 'pie'" class="stats-form">
          <label class="stats-field">
            <span>能源类型</span>
            <input v-model="pieEnergy" class="csv-input" />
          </label>
          <label class="stats-field stats-field--wide">
            <span>建筑类型（可选）</span>
            <input v-model="pieBuildingType" class="csv-input" />
          </label>
          <label class="stats-field">
            <span>开始（可选）</span>
            <input v-model="pieStart" class="csv-input" type="date" />
          </label>
          <label class="stats-field">
            <span>结束（可选）</span>
            <input v-model="pieEnd" class="csv-input" type="date" />
          </label>
          <button type="button" class="stats-btn" :disabled="loading" @click="loadPie">查询</button>
        </div>

        <div v-if="chartTab === 'generic'" class="stats-form stats-form--generic">
          <label class="stats-field">
            <span>图表类型</span>
            <select v-model="genBody.chartType" class="csv-input">
              <option value="line">line</option>
              <option value="bar">bar</option>
              <option value="pie">pie</option>
            </select>
          </label>
          <label class="stats-field">
            <span>能源类型</span>
            <input v-model="genBody.energyType" class="csv-input" />
          </label>
          <label class="stats-field">
            <span>粒度</span>
            <input v-model="genBody.granularity" class="csv-input" placeholder="month" />
          </label>
          <label class="stats-field">
            <span>开始</span>
            <input v-model="genBody.startTime" class="csv-input" placeholder="2023-01-01" />
          </label>
          <label class="stats-field">
            <span>结束</span>
            <input v-model="genBody.endTime" class="csv-input" placeholder="2023-12-31" />
          </label>
          <label class="stats-field">
            <span>建筑 ID</span>
            <input v-model="genBody.buildingId" class="csv-input" />
          </label>
          <label class="stats-field">
            <span>建筑类型</span>
            <input v-model="genBody.buildingType" class="csv-input" />
          </label>
          <label class="stats-field">
            <span>Top N</span>
            <input v-model.number="genBody.topN" class="csv-input" type="number" placeholder="排名用" />
          </label>
          <button type="button" class="stats-btn" :disabled="loading" @click="loadGeneric">POST 查询</button>
        </div>

        <div class="stats-chart-wrap">
          <div class="stats-chart-head">
            <span class="stats-chart-head__title">{{ chartTitle }}</span>
            <span v-if="loading" class="stats-chart-head__meta">加载中…</span>
          </div>
          <div ref="chartEl" class="stats-chart" />
          <p v-if="!loading && !chartPayload && chartTab === 'generic'" class="stats-chart-empty">
            填写参数后点击「POST 查询」。
          </p>
        </div>
      </template>

      <!-- 导出 -->
      <template v-else>
        <div class="stats-subtabs" role="tablist">
          <button
            type="button"
            class="stats-subtabs__btn"
            :class="{ 'stats-subtabs__btn--active': exportTab === 'report' }"
            @click="exportTab = 'report'"
          >
            统计报表 xlsx
          </button>
          <button
            type="button"
            class="stats-subtabs__btn"
            :class="{ 'stats-subtabs__btn--active': exportTab === 'chart' }"
            @click="exportTab = 'chart'"
          >
            单图表 xlsx
          </button>
          <button
            type="button"
            class="stats-subtabs__btn"
            :class="{ 'stats-subtabs__btn--active': exportTab === 'raw' }"
            @click="exportTab = 'raw'"
          >
            原始记录
          </button>
        </div>

        <div v-if="exportTab === 'report'" class="stats-form stats-form--generic">
          <label class="stats-field">
            <span>分析类型</span>
            <select v-model="expReport.analysisType" class="csv-input">
              <option value="summary">summary 时段汇总</option>
              <option value="cop">cop</option>
              <option value="anomaly">anomaly</option>
            </select>
          </label>
          <label class="stats-field">
            <span>能源类型</span>
            <input v-model="expReport.energyType" class="csv-input" />
          </label>
          <label class="stats-field">
            <span>粒度</span>
            <input v-model="expReport.granularity" class="csv-input" />
          </label>
          <label class="stats-field stats-field--wide">
            <span>开始时间</span>
            <input v-model="expReport.startTime" class="csv-input" placeholder="yyyy-MM-dd HH:mm:ss" />
          </label>
          <label class="stats-field stats-field--wide">
            <span>结束时间</span>
            <input v-model="expReport.endTime" class="csv-input" />
          </label>
          <label class="stats-field">
            <span>建筑 ID</span>
            <input v-model="expReport.buildingId" class="csv-input" />
          </label>
          <button type="button" class="stats-btn" :disabled="loading" @click="doExportReport">
            POST /api/statistics/export
          </button>
        </div>

        <div v-if="exportTab === 'chart'" class="stats-form stats-form--generic">
          <label class="stats-field">
            <span>图表类型</span>
            <select v-model="expChart.chartType" class="csv-input">
              <option value="line">line</option>
              <option value="bar">bar</option>
              <option value="pie">pie</option>
            </select>
          </label>
          <label class="stats-field">
            <span>能源类型</span>
            <input v-model="expChart.energyType" class="csv-input" />
          </label>
          <label class="stats-field">
            <span>粒度</span>
            <input v-model="expChart.granularity" class="csv-input" />
          </label>
          <label class="stats-field">
            <span>开始</span>
            <input v-model="expChart.startTime" class="csv-input" />
          </label>
          <label class="stats-field">
            <span>结束</span>
            <input v-model="expChart.endTime" class="csv-input" />
          </label>
          <label class="stats-field">
            <span>Top N</span>
            <input v-model.number="expChart.topN" class="csv-input" type="number" />
          </label>
          <button type="button" class="stats-btn" :disabled="loading" @click="doExportChart">
            POST /api/statistics/chart/export
          </button>
        </div>

        <div v-if="exportTab === 'raw'" class="stats-form stats-form--generic">
          <label class="stats-field">
            <span>能源类型</span>
            <input v-model="expRaw.energyType" class="csv-input" required />
          </label>
          <label class="stats-field">
            <span>格式</span>
            <select v-model="expRaw.format" class="csv-input">
              <option value="xlsx">xlsx</option>
              <option value="csv">csv</option>
            </select>
          </label>
          <label class="stats-field stats-field--wide">
            <span>开始时间</span>
            <input v-model="expRaw.startTime" class="csv-input" placeholder="可选" />
          </label>
          <label class="stats-field stats-field--wide">
            <span>结束时间</span>
            <input v-model="expRaw.endTime" class="csv-input" />
          </label>
          <label class="stats-field">
            <span>建筑 ID</span>
            <input v-model="expRaw.buildingId" class="csv-input" />
          </label>
          <label class="stats-field">
            <span>每页条数</span>
            <input v-model.number="expRaw.pageSize" class="csv-input" type="number" min="1" />
          </label>
          <button type="button" class="stats-btn" :disabled="loading" @click="doExportRaw">
            POST /api/energy/query/export
          </button>
        </div>

        <p class="stats-export-hint">
          报表导出对应后端 <code>POST /api/statistics/export</code>；单图表为 <code>POST /api/statistics/chart/export</code>；原始记录为
          <code>POST /api/energy/query/export</code>（需后端实现）。
        </p>
      </template>
    </div>
  </section>
</template>
