<script setup lang="ts">
import { computed } from 'vue'
import { markdownToSafeHtml } from '../utils/markdown'

const props = defineProps<{
  content: string
}>()

const html = computed(() => markdownToSafeHtml(props.content))
</script>

<template>
  <div class="ai-md" v-html="html" />
</template>
