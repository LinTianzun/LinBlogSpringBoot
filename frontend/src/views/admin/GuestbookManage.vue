<script setup lang="ts">
import AdminPlaceholder from '@/components/AdminPlaceholder.vue'
</script>

<template>
  <AdminPlaceholder
    title="留言墙管理"
    description="留言墙管理接口（M4）尚未交付，待后端就绪后接入隐藏/删除。"
    :permission="['message.wallModerate']"
  />
</template>
