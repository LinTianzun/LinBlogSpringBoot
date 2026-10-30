<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { NAlert, NButton, NCard, NCheckbox, NSpin, NTag, useMessage } from 'naive-ui'
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
const message = useMessage()

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
    message.success(`矩阵已保存（${Object.values(result).reduce((sum, n) => sum + n, 0)} 项授权）`)
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '保存失败')
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
    <n-space justify="space-between" align="center" style="margin-bottom: 16px">
      <n-page-header title="权限管理" subtitle="按角色分配权限键" />
      <n-space align="center">
        <n-tag v-if="!canEdit" type="warning" size="small">只读（需 permission.edit）</n-tag>
        <NButton type="primary" :loading="saving" :disabled="!canEdit" @click="save">保存矩阵</NButton>
      </n-space>
    </n-space>

    <n-alert type="info" style="margin-bottom: 16px" :bordered="false">
      超级管理员固定拥有全部权限，不参与矩阵分配；受保护键仅超管可持有。矩阵读取接口未提供，
      表格初始为空，保存会覆盖后端当前矩阵——请完整勾选后再保存。
    </n-alert>

    <n-spin :show="loading">
      <n-alert v-if="errorMessage" type="warning" style="margin-bottom: 16px">
        {{ errorMessage }}（服务暂未就绪）
        <n-button size="tiny" quaternary @click="load">重试</n-button>
      </n-alert>

      <n-card v-for="group in groups" :key="group.name" :title="group.name" style="margin-bottom: 16px">
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
                <n-tag size="small" :bordered="false" :type="isProtected(item.key) ? 'warning' : 'default'">
                  {{ item.key }}
                </n-tag>
              </td>
              <td class="lb-matrix-label">{{ item.label }}</td>
              <td v-for="role in EDITABLE_ROLES" :key="role">
                <NCheckbox
                  :checked="isChecked(role, item.key)"
                  :disabled="!canEdit"
                  @update:checked="(checked: boolean) => toggle(role, item.key, checked)"
                />
              </td>
            </tr>
          </tbody>
        </table>
      </n-card>
    </n-spin>
  </div>
</template>

<style scoped>
.lb-matrix-table {
  width: 100%;
  border-collapse: collapse;
}

.lb-matrix-table th,
.lb-matrix-table td {
  text-align: left;
  padding: 6px 12px;
  border-bottom: 1px solid var(--lb-border, rgba(0, 0, 0, 0.08));
  font-size: 13px;
}

.lb-matrix-label {
  color: var(--lb-muted-color, #888);
}
</style>
