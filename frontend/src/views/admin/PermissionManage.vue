<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getPermissionCatalog, savePermissionMatrix } from '@/api/permission'
import { ApiError } from '@/api/request'
import { useSessionStore } from '@/stores/session'
import type { PermissionCatalogGroupDTO, PermissionKey, PermissionMatrix, RoleCode } from '@/types/api'

/**
 * 权限管理（§5.6，后端 M5 已实现）：catalog 分组渲染 + 矩阵编辑 + PUT 保存。
 * 超管固定拥有全部键，不在矩阵内编辑；受保护键（permission.edit/user.assignAdmin）
 * 仅超管可见入口（catalog 仍返回，编辑按 isProtected 禁用）。
 */

const EDITABLE_ROLES: RoleCode[] = ['admin', 'editor', 'viewer']
const ROLE_LABELS: Record<RoleCode, string> = {
  super_admin: '超级管理员',
  admin: '管理员',
  editor: '编辑',
  viewer: '访客',
}

const session = useSessionStore()

const groups = ref<PermissionCatalogGroupDTO[]>([])
const protectedKeys = ref<string[]>([])
const matrix = ref<PermissionMatrix>({})
const loading = ref(false)
const saving = ref(false)
const errorMessage = ref('')
const canEdit = computed(() => session.can('permission.edit'))

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const catalog = await getPermissionCatalog()
    groups.value = catalog.groups
    protectedKeys.value = catalog.protectedKeys
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '加载失败'
  } finally {
    loading.value = false
  }
}

function isChecked(role: RoleCode, key: PermissionKey): boolean {
  return matrix.value[role]?.includes(key) ?? false
}

function toggle(role: RoleCode, key: PermissionKey, checked: boolean): void {
  const current = new Set(matrix.value[role] ?? [])
  if (checked) current.add(key)
  else current.delete(key)
  matrix.value = { ...matrix.value, [role]: [...current] }
}

async function save(): Promise<void> {
  saving.value = true
  try {
    const result = await savePermissionMatrix(matrix.value)
    ElMessage.success(`矩阵已保存（${Object.values(result).reduce((sum, n) => sum + n, 0)} 项授权）`)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '保存失败')
  } finally {
    saving.value = false
  }
}

function isProtected(key: string): boolean {
  return protectedKeys.value.includes(key)
}

onMounted(load)
</script>

<template>
  <div>
    <div class="lb-page-header">
      <div class="lb-page-title">
        <h2>权限管理</h2>
        <span class="lb-muted">按角色分配权限键</span>
      </div>
      <div class="lb-page-actions">
        <el-tag v-if="!canEdit" type="warning" size="small">只读（需 permission.edit）</el-tag>
        <el-button type="primary" :loading="saving" :disabled="!canEdit" @click="save">保存矩阵</el-button>
      </div>
    </div>

    <el-alert
      type="info"
      :closable="false"
      class="lb-perm-note"
      title="超级管理员固定拥有全部权限，不参与矩阵分配；受保护键仅超管可持有。矩阵读取接口未提供，表格初始为空，保存会覆盖后端当前矩阵——请完整勾选后再保存。"
    />

    <div v-loading="loading">
      <el-alert v-if="errorMessage" type="warning" class="lb-perm-error" :closable="false">
        <template #title>
          {{ errorMessage }}（服务暂未就绪）
          <el-button size="small" link @click="load">重试</el-button>
        </template>
      </el-alert>

      <el-card v-for="group in groups" :key="group.name" shadow="never" class="lb-perm-group">
        <template #header>
          {{ group.name }}
        </template>
        <table class="lb-matrix-table">
          <thead>
            <tr>
              <th>权限键</th>
              <th>说明</th>
              <th v-for="role in EDITABLE_ROLES" :key="role">{{ ROLE_LABELS[role] }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in group.items" :key="item.key">
              <td>
                <el-tag size="small" effect="plain" :type="isProtected(item.key) ? 'warning' : 'info'">
                  {{ item.key }}
                </el-tag>
              </td>
              <td class="lb-matrix-label">{{ item.label }}</td>
              <td v-for="role in EDITABLE_ROLES" :key="role">
                <el-checkbox
                  :model-value="isChecked(role, item.key)"
                  :disabled="!canEdit"
                  @update:model-value="toggle(role, item.key, $event as boolean)"
                />
              </td>
            </tr>
          </tbody>
        </table>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.lb-page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.lb-page-title {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.lb-page-title h2 {
  margin: 0;
  font-size: 18px;
}

.lb-page-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.lb-perm-note {
  margin-bottom: 16px;
}

.lb-perm-error {
  margin-bottom: 16px;
}

.lb-perm-group {
  margin-bottom: 16px;
}

.lb-matrix-table {
  width: 100%;
  border-collapse: collapse;
}

.lb-matrix-table th,
.lb-matrix-table td {
  text-align: left;
  padding: 6px 12px;
  border-bottom: 1px solid var(--lb-border);
  font-size: 13px;
}

.lb-matrix-label {
  color: var(--lb-muted);
}
</style>
