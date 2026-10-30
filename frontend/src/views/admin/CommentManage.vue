<script setup lang="ts">
import AdminPlaceholder from '@/components/AdminPlaceholder.vue'
</script>

<template>
  <AdminPlaceholder
    title="评论管理"
    description="互动域接口（M3）尚未交付，待后端就绪后接入评论列表与删除。"
    :permission="['comment.viewAll']"
  />
</template>
