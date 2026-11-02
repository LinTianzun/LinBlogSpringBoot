<script setup lang="ts">
import type { PermissionKey } from '@/types/api'

/**
 * 后台占位页通用外壳：「权限门 + 空态」（element-plus 版）。
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
    <div class="lb-placeholder-header">{{ title }}</div>
    <el-card class="lb-placeholder-card" shadow="never">
      <el-empty :description="description ?? '功能建设中：本轮仅交付骨架，后续里程碑接入接口。'">
        <div class="lb-placeholder-perms">
          <span class="lb-muted">所需权限（任一即可）</span>
          <div class="lb-placeholder-tags">
            <el-tag v-for="key in permission" :key="key" type="info" size="small">{{ key }}</el-tag>
          </div>
        </div>
      </el-empty>
    </el-card>
  </div>
</template>

<style scoped>
.lb-admin-placeholder {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.lb-placeholder-header {
  font-size: 20px;
  font-weight: 600;
  color: var(--lb-text);
}

.lb-placeholder-card {
  min-height: 320px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.lb-placeholder-perms {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
}

.lb-placeholder-tags {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 6px;
}
</style>
