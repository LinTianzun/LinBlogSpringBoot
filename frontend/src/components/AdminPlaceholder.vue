<script setup lang="ts">
import type { PermissionKey } from '@/types/api'

/**
 * 后台占位页通用外壳：「权限门 + 空态」。
 * 实际权限拦截由路由守卫完成（无权限根本进不来），此处只做展示与语义标注。
 */
defineProps<{
  title: string
  description?: string
  permission: PermissionKey[]
}>()
</script>

<template>
  <div class="lb-admin-placeholder">
    <n-page-header :title="title" />
    <n-card class="lb-placeholder-card">
      <n-empty size="large">
        <template #icon>
          <n-icon>
            <span>🚧</span>
          </n-icon>
        </template>
        {{ description ?? '功能建设中：本轮仅交付骨架，后续里程碑接入接口。' }}
        <template #extra>
          <n-space vertical size="small" align="center">
            <n-text depth="3">所需权限（任一即可）</n-text>
            <n-space size="small">
              <n-tag v-for="key in permission" :key="key" type="info" size="small">{{ key }}</n-tag>
            </n-space>
          </n-space>
        </template>
      </n-empty>
    </n-card>
  </div>
</template>

<style scoped>
.lb-admin-placeholder {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.lb-placeholder-card {
  min-height: 320px;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
