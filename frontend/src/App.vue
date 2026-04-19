<script setup lang="ts">
import { defineAsyncComponent, ref } from 'vue'
import AIInquiryPanel from './components/AIInquiryPanel.vue'

const StatisticsChartsPanel = defineAsyncComponent(() => import('./components/StatisticsChartsPanel.vue'))

type AppTab = 'ai' | 'stats'
const appTab = ref<AppTab>('ai')
</script>

<template>
  <div class="app-shell">
    <div class="app-tabs" role="tablist" aria-label="功能切换">
      <button
        type="button"
        class="app-tabs__btn"
        :class="{ 'app-tabs__btn--active': appTab === 'ai' }"
        :aria-selected="appTab === 'ai'"
        @click="appTab = 'ai'"
      >
        AI 问答
      </button>
      <button
        type="button"
        class="app-tabs__btn"
        :class="{ 'app-tabs__btn--active': appTab === 'stats' }"
        :aria-selected="appTab === 'stats'"
        @click="appTab = 'stats'"
      >
        统计图表
      </button>
    </div>
    <AIInquiryPanel v-if="appTab === 'ai'" />
    <StatisticsChartsPanel v-else />
  </div>
</template>
