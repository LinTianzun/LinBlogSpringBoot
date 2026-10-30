<script setup lang="ts">
import AdminPlaceholder from '@/components/AdminPlaceholder.vue'
</script>

<template>
  <AdminPlaceholder
    title="文件管理"
    description="媒体接口（M4）尚未交付，待后端就绪后接入列表与删除。"
    :permission="['file.view']"
  />
</template>
