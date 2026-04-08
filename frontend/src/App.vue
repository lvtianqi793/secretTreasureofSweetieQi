<script setup lang="ts">
import AIInquiryPanel from './components/AIInquiryPanel.vue'
import CsvImportPanel from './components/CsvImportPanel.vue'
import { ref } from 'vue'

type TopTab = 'ai' | 'csv'
const tab = ref<TopTab>('ai')
</script>

<template>
  <div class="app-shell">
    <AIInquiryPanel />
    <div class="app-tabs" role="tablist" aria-label="功能切换">
      <button
        type="button"
        role="tab"
        class="app-tabs__btn"
        :class="{ 'app-tabs__btn--active': tab === 'ai' }"
        :aria-selected="tab === 'ai'"
        @click="tab = 'ai'"
      >
        AI 问答
      </button>
      <button
        type="button"
        role="tab"
        class="app-tabs__btn"
        :class="{ 'app-tabs__btn--active': tab === 'csv' }"
        :aria-selected="tab === 'csv'"
        @click="tab = 'csv'"
      >
        CSV 导入
      </button>
    </div>

    <AIInquiryPanel v-if="tab === 'ai'" />
    <CsvImportPanel v-else />
  </div>
</template>
