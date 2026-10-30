<script setup lang="ts">
import AdminPlaceholder from '@/components/AdminPlaceholder.vue'
</script>

<template>
  <AdminPlaceholder
    title="站点设置"
    description="设置接口（M4）尚未交付，待后端就绪后接入站点配置。"
    :permission="['system.setting']"
  />
</template>
