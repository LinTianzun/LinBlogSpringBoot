<script setup lang="ts">
import AdminPlaceholder from '@/components/AdminPlaceholder.vue'
</script>

<template>
  <AdminPlaceholder
    title="举报处理"
    description="举报接口（M3/M4）尚未交付，待后端就绪后接入处理流。"
    :permission="['comment.moderate']"
  />
</template>
