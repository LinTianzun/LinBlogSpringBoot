<script setup lang="ts">
import { computed } from 'vue'
import { weatherEmoji } from '@/utils/weather'

const props = defineProps<{
  weather: unknown
}>()

const emoji = computed(() => weatherEmoji(props.weather))
</script>

<template>
  <span v-if="emoji" class="lb-weather-badge">
    <span class="lb-weather-emoji">{{ emoji }}</span>
  </span>
  <span v-else class="lb-muted">—</span>
</template>

<style scoped>
.lb-weather-badge {
  display: inline-flex;
  align-items: center;
}

.lb-weather-emoji {
  font-size: 16px;
  line-height: 1;
}
</style>
