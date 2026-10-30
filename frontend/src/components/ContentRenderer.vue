<script setup lang="ts">
import { computed, h } from 'vue'
import type { TiptapDoc } from '@/types/api'
import { renderProseMirror } from './renderContent'

const props = defineProps<{
  doc: TiptapDoc | null | undefined
}>()

const vnodes = computed(() => renderProseMirror(props.doc))

const render = () => (vnodes.value.length > 0 ? h('div', { class: 'lb-content' }, vnodes.value) : null)
</script>

<template>
  <render />
</template>
