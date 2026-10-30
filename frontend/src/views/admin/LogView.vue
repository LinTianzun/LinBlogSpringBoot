<script setup lang="ts">
import AdminPlaceholder from '@/components/AdminPlaceholder.vue'
</script>

<template>
  <AdminPlaceholder
    title="系统日志"
    description="日志接口（M4）尚未交付，待后端就绪后接入日志查询。"
    :permission="['systemLog.view']"
  />
</template>
