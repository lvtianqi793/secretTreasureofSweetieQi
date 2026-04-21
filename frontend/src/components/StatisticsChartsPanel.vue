<script setup lang="ts">
import {
  computed,
  onMounted,
  onUnmounted,
  ref,
  watch,
} from 'vue'
import ChartPreviewCard from './ChartPreviewCard.vue'
import {
  type BuildingInfo,
  type ChartPayload,
  type ChartRequest,
  type EnergyQueryExportBody,
  type EnergyTypeOption,
  getBuildings,
  getEnergyOptions,
  type StatisticsExportBody,
  downloadBlob,
  getChartPie,
  getChartRanking,
  getChartTrend,
  postChartExport,
  postEnergyQueryExport,
  postStatisticsExport,
} from '../api/statistics'

type ChartTab = 'trend' | 'ranking' | 'pie'
type ExportTab = 'report' | 'chart' | 'raw'

const chartTab = ref<ChartTab>('trend')
const exportTab = ref<ExportTab>('report')
const mainTab = ref<'view' | 'export'>('view')

const loading = ref(false)
const error = ref<string | null>(null)
const currentChart = ref<{ id: string; payload: ChartPayload; createdAt: number } | null>(null)
const exporting = ref<null | 'report' | 'chart' | 'raw'>(null)

const energyTypeOptions = ref<EnergyTypeOption[]>([])
const buildingTypeOptions = ref<string[]>([])
const buildingOptions = ref<BuildingInfo[]>([])

/** 与后端 ChartService 支持的粒度一致 */
const GRANULARITY_OPTIONS = [
  { value: 'hour', label: '小时' },
  { value: 'day', label: '日' },
  { value: 'week', label: '周' },
  { value: 'month', label: '月' },
  { value: 'year', label: '年' },
] as const

const ANALYSIS_OPTIONS = [
  { value: 'summary', label: '时段汇总' },
  { value: 'cop', label: 'COP 计算' },
  { value: 'anomaly', label: '异常检测' },
] as const

// —— 快捷：趋势
const trendEnergy = ref('electricity')
const trendGranularity = ref('month')
const trendStart = ref('2016-01-01T00:00')
const trendEnd = ref('2016-12-31T23:59')
const trendBuilding = ref('')

// —— 快捷：排名
const rankEnergy = ref('electricity')
const rankTopN = ref(10)
const rankStart = ref('2016-01-01T00:00')
const rankEnd = ref('2016-12-31T23:59')
const rankBuilding = ref('')

// —— 快捷：饼图
const pieEnergy = ref('water')
const pieStart = ref('2016-01-01T00:00')
const pieEnd = ref('2016-12-31T23:59')

// —— 导出：统计报表
const expReport = ref<StatisticsExportBody>({
  analysisType: 'summary',
  energyType: 'electricity',
  granularity: 'month',
  startTime: '2016-01-01T00:00',
  endTime: '2016-12-31T23:59',
})

// —— 导出：单图表 Excel
const expChart = ref<ChartRequest>({
  chartType: 'line',
  energyType: 'electricity',
  granularity: 'month',
  startTime: '2016-01-01T00:00',
  endTime: '2016-12-31T23:59',
  dimension: 'time',
  topN: 10,
})

const CHART_TYPE_OPTIONS = [
  { value: 'line', label: '折线图' },
  { value: 'bar', label: '柱状图' },
  { value: 'pie', label: '饼图' },
] as const

const PIE_GROUP_OPTIONS = [
  { value: 'building', label: '建筑' },
  { value: 'type', label: '建筑类型' },
] as const

watch(
  () => expChart.value.chartType,
  (t) => {
    if (t === 'pie') {
      if (expChart.value.dimension !== 'building' && expChart.value.dimension !== 'type') {
        expChart.value.dimension = 'building'
      }
    } else {
      expChart.value.dimension = 'time'
    }
  }
)

// —— 导出：原始记录
const expRaw = ref<EnergyQueryExportBody>({
  energyType: 'electricity',
  format: 'xlsx',
  startTime: '2016-01-01T00:00',
  endTime: '2016-12-31T23:59',
  maxRows: 10000,
  sortBy: 'monitor_time',
  sortOrder: 'desc',
})

const chartTitle = computed(() => (currentChart.value ? '已生成图表' : '图表预览'))

const expReportStart = computed<string>({
  get: () => expReport.value.startTime ?? '',
  set: (v) => {
    expReport.value.startTime = v
  },
})
const expReportEnd = computed<string>({
  get: () => expReport.value.endTime ?? '',
  set: (v) => {
    expReport.value.endTime = v
  },
})
const expChartStart = computed<string>({
  get: () => expChart.value.startTime ?? '',
  set: (v) => {
    expChart.value.startTime = v
  },
})
const expChartEnd = computed<string>({
  get: () => expChart.value.endTime ?? '',
  set: (v) => {
    expChart.value.endTime = v
  },
})
const expRawStart = computed<string>({
  get: () => expRaw.value.startTime ?? '',
  set: (v) => {
    expRaw.value.startTime = v
  },
})
const expRawEnd = computed<string>({
  get: () => expRaw.value.endTime ?? '',
  set: (v) => {
    expRaw.value.endTime = v
  },
})

function toBackendDateTime(local: string, edge: 'start' | 'end') {
  if (!local) return ''
  // input[type=datetime-local] => yyyy-MM-ddTHH:mm
  if (local.includes('T')) return `${local.replace('T', ' ')}:00`
  // 兼容旧值(yyyy-MM-dd)
  return `${local} ${edge === 'start' ? '00:00:00' : '23:59:59'}`
}

function renderChart(data: ChartPayload) {
  const now = Date.now()
  currentChart.value = {
    id: `${now}_${Math.random().toString(16).slice(2)}`,
    payload: data,
    createdAt: now,
  }
}

async function loadTrend() {
  loading.value = true
  error.value = null
  currentChart.value = null
  try {
    const data = await getChartTrend({
      energyType: trendEnergy.value,
      granularity: trendGranularity.value,
      startTime: (trendStart.value && toBackendDateTime(trendStart.value, 'start')) || undefined,
      endTime: (trendEnd.value && toBackendDateTime(trendEnd.value, 'end')) || undefined,
      buildingId: trendBuilding.value || undefined,
    })
    renderChart(data)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function loadRanking() {
  loading.value = true
  error.value = null
  currentChart.value = null
  try {
    const data = await getChartRanking({
      energyType: rankEnergy.value,
      topN: rankTopN.value,
      startTime: (rankStart.value && toBackendDateTime(rankStart.value, 'start')) || undefined,
      endTime: (rankEnd.value && toBackendDateTime(rankEnd.value, 'end')) || undefined,
      buildingId: rankBuilding.value || undefined,
    })
    renderChart(data)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function loadPie() {
  loading.value = true
  error.value = null
  currentChart.value = null
  try {
    const data = await getChartPie({
      energyType: pieEnergy.value,
      startTime: (pieStart.value && toBackendDateTime(pieStart.value, 'start')) || undefined,
      endTime: (pieEnd.value && toBackendDateTime(pieEnd.value, 'end')) || undefined,
    })
    renderChart(data)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void (async () => {
    try {
      const opt = await getEnergyOptions()
      energyTypeOptions.value = opt.energyTypes ?? []
      buildingTypeOptions.value = opt.buildingTypes ?? []
    } catch {
      // ignore options load errors; fall back to manual input
    }
  })()
  void (async () => {
    try {
      buildingOptions.value = (await getBuildings()) ?? []
    } catch {
      // ignore; building dropdowns will just be empty
    }
  })()
  void loadTrend()
})

onUnmounted(() => {
})

watch(chartTab, (t) => {
  error.value = null
  currentChart.value = null
  if (t === 'trend') void loadTrend()
  if (t === 'ranking') void loadRanking()
  if (t === 'pie') void loadPie()
})

async function doExportReport() {
  if (exporting.value) return
  exporting.value = 'report'
  error.value = null
  try {
    const blob = await postStatisticsExport({
      ...expReport.value,
      startTime: expReport.value.startTime
        ? toBackendDateTime(expReport.value.startTime, 'start')
        : undefined,
      endTime: expReport.value.endTime ? toBackendDateTime(expReport.value.endTime, 'end') : undefined,
    })
    const name = `能耗统计报表_${expReport.value.analysisType}_${new Date().toISOString().slice(0, 19).replace(/[:T]/g, '')}.xlsx`
    downloadBlob(blob, name)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '导出失败'
  } finally {
    exporting.value = null
  }
}

async function doExportChart() {
  if (exporting.value) return
  exporting.value = 'chart'
  error.value = null
  try {
    const blob = await postChartExport({
      ...expChart.value,
      startTime: expChart.value.startTime ? toBackendDateTime(expChart.value.startTime, 'start') : undefined,
      endTime: expChart.value.endTime ? toBackendDateTime(expChart.value.endTime, 'end') : undefined,
    })
    downloadBlob(blob, `统计图表_${expChart.value.chartType}_${Date.now()}.xlsx`)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '导出失败'
  } finally {
    exporting.value = null
  }
}

async function doExportRaw() {
  if (exporting.value) return
  exporting.value = 'raw'
  error.value = null
  try {
    const blob = await postEnergyQueryExport({
      ...expRaw.value,
      startTime: expRaw.value.startTime ? toBackendDateTime(expRaw.value.startTime, 'start') : undefined,
      endTime: expRaw.value.endTime ? toBackendDateTime(expRaw.value.endTime, 'end') : undefined,
    })
    const ext = expRaw.value.format === 'csv' ? 'csv' : 'xlsx'
    downloadBlob(blob, `能耗原始记录_${expRaw.value.energyType}_${Date.now()}.${ext}`)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '导出失败'
  } finally {
    exporting.value = null
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
        </div>

        <div v-if="chartTab === 'trend'" class="stats-form">
          <label class="stats-field">
            <span>能源类型</span>
            <select v-model="trendEnergy" class="csv-input">
              <option v-for="e in energyTypeOptions" :key="e.value" :value="e.value">
                {{ e.label }}{{ e.unit ? `（${e.unit}）` : '' }}
              </option>
            </select>
          </label>
          <label class="stats-field">
            <span>粒度</span>
            <select v-model="trendGranularity" class="csv-input">
              <option v-for="g in GRANULARITY_OPTIONS" :key="g.value" :value="g.value">
                {{ g.label }}
              </option>
            </select>
          </label>
          <label class="stats-field">
            <span>开始日期</span>
            <input v-model="trendStart" class="csv-input" type="datetime-local" step="900" />
          </label>
          <label class="stats-field">
            <span>结束日期</span>
            <input v-model="trendEnd" class="csv-input" type="datetime-local" step="900" />
          </label>
          <label class="stats-field stats-field--wide">
            <span>建筑（可选）</span>
            <select v-model="trendBuilding" class="csv-input">
              <option value="">全部建筑</option>
              <option v-for="b in buildingOptions" :key="b.buildingId" :value="b.buildingId">
                {{ b.buildingId }}{{ b.buildingType ? `（${b.buildingType}）` : '' }}
              </option>
            </select>
          </label>
          <button type="button" class="stats-btn" :disabled="loading" @click="loadTrend">查询</button>
        </div>

        <div v-if="chartTab === 'ranking'" class="stats-form">
          <label class="stats-field">
            <span>能源类型</span>
            <select v-model="rankEnergy" class="csv-input">
              <option v-for="e in energyTypeOptions" :key="e.value" :value="e.value">
                {{ e.label }}{{ e.unit ? `（${e.unit}）` : '' }}
              </option>
            </select>
          </label>
          <label class="stats-field">
            <span>Top N</span>
            <input v-model.number="rankTopN" class="csv-input" type="number" min="1" max="100" />
          </label>
          <label class="stats-field">
            <span>开始</span>
            <input v-model="rankStart" class="csv-input" type="datetime-local" step="900" />
          </label>
          <label class="stats-field">
            <span>结束</span>
            <input v-model="rankEnd" class="csv-input" type="datetime-local" step="900" />
          </label>
          <label class="stats-field stats-field--wide">
            <span>建筑（可选）</span>
            <select v-model="rankBuilding" class="csv-input">
              <option value="">全部建筑</option>
              <option v-for="b in buildingOptions" :key="b.buildingId" :value="b.buildingId">
                {{ b.buildingId }}{{ b.buildingType ? `（${b.buildingType}）` : '' }}
              </option>
            </select>
          </label>
          <button type="button" class="stats-btn" :disabled="loading" @click="loadRanking">查询</button>
        </div>

        <div v-if="chartTab === 'pie'" class="stats-form">
          <label class="stats-field">
            <span>能源类型</span>
            <select v-model="pieEnergy" class="csv-input">
              <option v-for="e in energyTypeOptions" :key="e.value" :value="e.value">
                {{ e.label }}{{ e.unit ? `（${e.unit}）` : '' }}
              </option>
            </select>
          </label>
          <label class="stats-field">
            <span>开始</span>
            <input v-model="pieStart" class="csv-input" type="datetime-local" step="900" />
          </label>
          <label class="stats-field">
            <span>结束</span>
            <input v-model="pieEnd" class="csv-input" type="datetime-local" step="900" />
          </label>
          <button type="button" class="stats-btn" :disabled="loading" @click="loadPie">查询</button>
        </div>

        <div class="stats-chart-wrap">
          <div class="stats-chart-head">
            <span class="stats-chart-head__title">{{ chartTitle }}</span>
            <span v-if="loading" class="stats-chart-head__meta">加载中…</span>
          </div>
          <div class="stats-chart-slot">
            <div v-if="loading" class="stats-chart-loading" role="status" aria-live="polite">
              <span class="stats-chart-loading__spinner" aria-hidden="true" />
              <span class="stats-chart-loading__text">图表加载中…</span>
            </div>
            <div v-else-if="!currentChart" class="stats-chart-empty">点击上方「查询」生成图表。</div>
            <ChartPreviewCard
              v-else
              :key="currentChart.id"
              :payload="currentChart.payload"
              :created-at="currentChart.createdAt"
            />
          </div>
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
              <option v-for="a in ANALYSIS_OPTIONS" :key="a.value" :value="a.value">
                {{ a.label }}
              </option>
            </select>
          </label>
          <label v-if="expReport.analysisType !== 'cop'" class="stats-field">
            <span>能源类型</span>
            <select v-model="expReport.energyType" class="csv-input">
              <option v-for="e in energyTypeOptions" :key="e.value" :value="e.value">
                {{ e.label }}{{ e.unit ? `（${e.unit}）` : '' }}
              </option>
            </select>
          </label>
          <div v-else class="stats-field stats-field--hint">
            <span>能源类型</span>
            <span class="stats-field-hint">冷冻水+电力</span>
          </div>
          <label class="stats-field">
            <span>粒度</span>
            <select v-model="expReport.granularity" class="csv-input">
              <option v-for="g in GRANULARITY_OPTIONS" :key="g.value" :value="g.value">
                {{ g.label }}
              </option>
            </select>
          </label>
          <label class="stats-field stats-field--wide">
            <span>开始时间</span>
            <input v-model="expReportStart" class="csv-input" type="datetime-local" step="900" />
          </label>
          <label class="stats-field stats-field--wide">
            <span>结束时间</span>
            <input v-model="expReportEnd" class="csv-input" type="datetime-local" step="900" />
          </label>
          <label class="stats-field">
            <span>建筑（可选）</span>
            <select v-model="expReport.buildingId" class="csv-input">
              <option value="">全部建筑</option>
              <option v-for="b in buildingOptions" :key="b.buildingId" :value="b.buildingId">
                {{ b.buildingId }}{{ b.buildingType ? `（${b.buildingType}）` : '' }}
              </option>
            </select>
          </label>
          <button
            type="button"
            class="stats-btn"
            :class="{ 'stats-btn--busy': exporting === 'report' }"
            :disabled="exporting !== null"
            @click="doExportReport"
          >
            <span v-if="exporting === 'report'" class="stats-btn__spinner" aria-hidden="true" />
            {{ exporting === 'report' ? '正在导出中…' : '导出统计报表' }}
          </button>
        </div>

        <div v-if="exportTab === 'chart'" class="stats-form stats-form--generic">
          <label class="stats-field">
            <span>图表类型</span>
            <select v-model="expChart.chartType" class="csv-input">
              <option v-for="c in CHART_TYPE_OPTIONS" :key="c.value" :value="c.value">
                {{ c.label }}
              </option>
            </select>
          </label>
          <label class="stats-field">
            <span>能源类型</span>
            <select v-model="expChart.energyType" class="csv-input">
              <option v-for="e in energyTypeOptions" :key="e.value" :value="e.value">
                {{ e.label }}{{ e.unit ? `（${e.unit}）` : '' }}
              </option>
            </select>
          </label>
          <label v-if="expChart.chartType !== 'pie'" class="stats-field">
            <span>粒度</span>
            <select v-model="expChart.granularity" class="csv-input">
              <option v-for="g in GRANULARITY_OPTIONS" :key="g.value" :value="g.value">
                {{ g.label }}
              </option>
            </select>
          </label>
          <label v-if="expChart.chartType === 'pie'" class="stats-field">
            <span>分组维度</span>
            <select v-model="expChart.dimension" class="csv-input">
              <option v-for="d in PIE_GROUP_OPTIONS" :key="d.value" :value="d.value">
                {{ d.label }}
              </option>
            </select>
          </label>
          <label class="stats-field stats-field--wide">
            <span>开始</span>
            <input v-model="expChartStart" class="csv-input" type="datetime-local" step="900" />
          </label>
          <label class="stats-field stats-field--wide">
            <span>结束</span>
            <input v-model="expChartEnd" class="csv-input" type="datetime-local" step="900" />
          </label>
          <label
            v-if="expChart.chartType === 'pie' && expChart.dimension === 'building'"
            class="stats-field"
          >
            <span>Top N</span>
            <input v-model.number="expChart.topN" class="csv-input" type="number" min="1" max="100" />
          </label>
          <button
            type="button"
            class="stats-btn"
            :class="{ 'stats-btn--busy': exporting === 'chart' }"
            :disabled="exporting !== null"
            @click="doExportChart"
          >
            <span v-if="exporting === 'chart'" class="stats-btn__spinner" aria-hidden="true" />
            {{ exporting === 'chart' ? '正在导出中…' : '导出图表 Excel' }}
          </button>
        </div>

        <div v-if="exportTab === 'raw'" class="stats-form stats-form--generic">
          <label class="stats-field">
            <span>能源类型</span>
            <select v-model="expRaw.energyType" class="csv-input" required>
              <option v-for="e in energyTypeOptions" :key="e.value" :value="e.value">
                {{ e.label }}{{ e.unit ? `（${e.unit}）` : '' }}
              </option>
            </select>
          </label>
          <label class="stats-field">
            <span>格式</span>
            <select v-model="expRaw.format" class="csv-input">
              <option value="xlsx">xlsx</option>
              <option value="csv">csv</option>
            </select>
          </label>
          <label class="stats-field stats-field--wide">
            <span>开始</span>
            <input v-model="expRawStart" class="csv-input" type="datetime-local" step="900" />
          </label>
          <label class="stats-field stats-field--wide">
            <span>结束</span>
            <input v-model="expRawEnd" class="csv-input" type="datetime-local" step="900" />
          </label>
          <label class="stats-field">
            <span>建筑（可选）</span>
            <select v-model="expRaw.buildingId" class="csv-input">
              <option value="">全部建筑</option>
              <option v-for="b in buildingOptions" :key="b.buildingId" :value="b.buildingId">
                {{ b.buildingId }}{{ b.buildingType ? `（${b.buildingType}）` : '' }}
              </option>
            </select>
          </label>
          <label class="stats-field">
            <span>最大导出量</span>
            <input
              v-model.number="expRaw.maxRows"
              class="csv-input"
              type="number"
              min="1"
              max="1000000"
            />
          </label>
          <button
            type="button"
            class="stats-btn"
            :class="{ 'stats-btn--busy': exporting === 'raw' }"
            :disabled="exporting !== null"
            @click="doExportRaw"
          >
            <span v-if="exporting === 'raw'" class="stats-btn__spinner" aria-hidden="true" />
            {{ exporting === 'raw' ? '正在导出中…' : '导出原始记录' }}
          </button>
        </div>
      </template>
    </div>

    <datalist id="energy-types">
      <option
        v-for="e in energyTypeOptions"
        :key="e.value"
        :value="e.value"
        :label="`${e.label}${e.unit ? `（${e.unit}）` : ''}`"
      />
    </datalist>
    <datalist id="building-types">
      <option v-for="t in buildingTypeOptions" :key="t" :value="t" />
    </datalist>
  </section>
</template>
