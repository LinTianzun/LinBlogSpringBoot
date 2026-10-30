<script setup lang="ts">
import AdminPlaceholder from '@/components/AdminPlaceholder.vue'
</script>

<template>
  <AdminPlaceholder
    title="私信"
    description="私信接口（M4）尚未交付，待后端就绪后接入会话与消息列表。"
    :permission="['message.private']"
  />
</template>
