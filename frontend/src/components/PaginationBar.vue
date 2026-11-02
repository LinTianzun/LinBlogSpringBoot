<script setup lang="ts">
import { computed } from 'vue'

/**
 * el-pagination 统一封装：页码从 1 开始，total 由父级传入。
 * 对外 API（page/pageSize/total/disabled + update:page）与库无关，调用方无需感知。
 */
const props = withDefaults(
  defineProps<{
    page: number
    pageSize: number
    total: number
    disabled?: boolean
  }>(),
  { disabled: false },
)

const emit = defineEmits<{
  'update:page': [page: number]
}>()

const pageCount = computed(() => Math.max(1, Math.ceil(props.total / Math.max(1, props.pageSize))))
</script>

<template>
  <div v-if="pageCount > 1" class="lb-pagination">
    <el-pagination
      :current-page="page"
      :page-count="pageCount"
      :page-size="pageSize"
      :disabled="disabled"
      layout="prev, pager, next, jumper"
      @update:current-page="(p: number) => emit('update:page', p)"
    />
  </div>
</template>

<style scoped>
.lb-pagination {
  display: flex;
  justify-content: center;
  padding: 16px 0;
}
</style>
