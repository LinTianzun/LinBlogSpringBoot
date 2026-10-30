<script setup lang="ts">
import AdminPlaceholder from '@/components/AdminPlaceholder.vue'
</script>

<template>
  <AdminPlaceholder
    title="日记管理"
    description="日记域接口（M2/M4）尚未交付，待后端就绪后接入列表与状态操作。"
    :permission="['diary.viewAll', 'diary.manageOwn']"
  />
</template>
