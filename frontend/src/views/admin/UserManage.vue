<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue'
import {
  NButton,
  NCard,
  NForm,
  NFormItem,
  NInput,
  NModal,
  NPopconfirm,
  NSelect,
  NSpace,
  NTag,
  useMessage,
} from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { createAdminUser, listAdminUsers, resetAdminUserPassword, updateAdminUser, updateAdminUserRole, updateAdminUserStatus } from '@/api/user'
import { ApiError } from '@/api/request'
import { useSessionStore } from '@/stores/session'
import { formatDate } from '@/utils/format'
import type { AdminUserView, RoleCode } from '@/types/api'

/**
 * 用户管理（§5.6，后端 M1 已实现）：列表/搜索/新建/编辑/停启用/重置密码/改角色。
 * 按钮级权限由 session.can() 控制（页面级由路由守卫 user.view 把守）。
 */

const ROLE_OPTIONS: Array<{ label: string; value: RoleCode }> = [
  { label: '超级管理员', value: 'super_admin' },
  { label: '管理员', value: 'admin' },
  { label: '编辑', value: 'editor' },
  { label: '访客', value: 'viewer' },
]

const STATE_TAG_TYPE = { 0: 'error', 1: 'success' } as const

const session = useSessionStore()
const message = useMessage()

const records = ref<AdminUserView[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 10
const keyword = ref('')
const status = ref<number | null>(null)
const loading = ref(false)
const errorMessage = ref('')

const canDisable = computed(() => session.can('user.disable'))
const canReset = computed(() => session.can('user.resetPassword'))
const canAssignRole = computed(() => session.can('user.assignAdmin'))
const canEdit = computed(() => session.can('user.update'))
const canCreate = computed(() => session.can('user.update'))

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listAdminUsers({
      page: page.value,
      pageSize,
      keyword: keyword.value || undefined,
      status: status.value ?? undefined,
    })
    records.value = result.records
    total.value = result.total
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '加载失败'
    records.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function search(): void {
  page.value = 1
  void load()
}

// —— 新建 ——
const showCreate = ref(false)
const createForm = reactive({ username: '', email: '', password: '', nickname: '' })
const createErrors = ref<Record<string, string>>({})
const creating = ref(false)

function openCreate(): void {
  createForm.username = ''
  createForm.email = ''
  createForm.password = ''
  createForm.nickname = ''
  createErrors.value = {}
  showCreate.value = true
}

async function submitCreate(): Promise<void> {
  createErrors.value = {}
  creating.value = true
  try {
    await createAdminUser({
      username: createForm.username.trim(),
      email: createForm.email.trim(),
      password: createForm.password,
      nickname: createForm.nickname.trim() || undefined,
    })
    message.success('用户已创建')
    showCreate.value = false
    await load()
  } catch (error) {
    if (error instanceof ApiError && error.code === 40001 && error.fieldErrors) {
      const map: Record<string, string> = {}
      for (const item of error.fieldErrors) map[item.field] = item.message
      createErrors.value = map
    } else {
      message.error(error instanceof ApiError ? error.message : '创建失败')
    }
  } finally {
    creating.value = false
  }
}

// —— 编辑（昵称/邮箱） ——
const editing = ref<AdminUserView | null>(null)
const editForm = reactive({ nickname: '', email: '' })
const editErrors = ref<Record<string, string>>({})

function openEdit(row: AdminUserView): void {
  editing.value = row
  editForm.nickname = row.nickname ?? ''
  editForm.email = row.email
  editErrors.value = {}
}

async function submitEdit(): Promise<void> {
  if (!editing.value) return
  try {
    await updateAdminUser(editing.value.id, {
      nickname: editForm.nickname.trim() || undefined,
      email: editForm.email.trim() || undefined,
    })
    message.success('已保存')
    editing.value = null
    await load()
  } catch (error) {
    if (error instanceof ApiError && error.code === 40001 && error.fieldErrors) {
      const map: Record<string, string> = {}
      for (const item of error.fieldErrors) map[item.field] = item.message
      editErrors.value = map
    } else {
      message.error(error instanceof ApiError ? error.message : '保存失败')
    }
  }
}

// —— 停用/启用 ——
async function toggleStatus(row: AdminUserView): Promise<void> {
  const next = row.status === 1 ? 0 : 1
  try {
    await updateAdminUserStatus(row.id, { status: next })
    message.success(next === 1 ? '已启用' : '已停用')
    await load()
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '操作失败')
  }
}

// —— 重置密码 ——
const resetting = ref<AdminUserView | null>(null)
const resetPassword = ref('')

function openReset(row: AdminUserView): void {
  resetting.value = row
  resetPassword.value = ''
}

async function submitReset(): Promise<void> {
  if (!resetting.value) return
  if (resetPassword.value.length < 8 || resetPassword.value.length > 72) {
    message.error('新密码长度须为 8-72 位')
    return
  }
  try {
    await resetAdminUserPassword(resetting.value.id, { password: resetPassword.value })
    message.success('密码已重置')
    resetting.value = null
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '重置失败')
  }
}

// —— 改角色 ——
const roleEditing = ref<AdminUserView | null>(null)
const nextRole = ref<RoleCode>('viewer')

function openRole(row: AdminUserView): void {
  roleEditing.value = row
  nextRole.value = row.role
}

async function submitRole(): Promise<void> {
  if (!roleEditing.value) return
  try {
    await updateAdminUserRole(roleEditing.value.id, { role: nextRole.value })
    message.success('角色已更新')
    roleEditing.value = null
    await load()
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '更新失败')
  }
}

const columns = computed<DataTableColumns<AdminUserView>>(() => [
  { title: 'ID', key: 'id', width: 72 },
  { title: '用户名', key: 'username' },
  { title: '昵称', key: 'nickname', render: (row) => row.nickname || '—' },
  { title: '邮箱', key: 'email' },
  {
    title: '角色',
    key: 'role',
    render: (row) => {
      const option = ROLE_OPTIONS.find((item) => item.value === row.role)
      return h(NTag, { size: 'small', bordered: false, type: row.role === 'super_admin' ? 'warning' : 'info' }, {
        default: () => option?.label ?? row.role,
      })
    },
  },
  {
    title: '状态',
    key: 'status',
    width: 88,
    render: (row) =>
      h(NTag, { size: 'small', bordered: false, type: STATE_TAG_TYPE[row.status as 0 | 1] }, {
        default: () => (row.status === 1 ? '正常' : '已停用'),
      }),
  },
  {
    title: '创建时间',
    key: 'createdAt',
    render: (row) => formatDate(row.createdAt),
  },
  {
    title: '操作',
    key: 'actions',
    width: 260,
    render: (row) => {
      const actions: ReturnType<typeof h>[] = []
      if (canEdit.value) {
        actions.push(h(NButton, { size: 'tiny', quaternary: true, onClick: () => openEdit(row) }, { default: () => '编辑' }))
      }
      if (canDisable.value) {
        actions.push(
          h(NPopconfirm, { onPositiveClick: () => void toggleStatus(row) }, {
            trigger: () =>
              h(NButton, { size: 'tiny', type: row.status === 1 ? 'warning' : 'primary', quaternary: true }, {
                default: () => (row.status === 1 ? '停用' : '启用'),
              }),
            default: () => (row.status === 1 ? '确定停用该用户？' : '确定启用该用户？'),
          }),
        )
      }
      if (canReset.value) {
        actions.push(h(NButton, { size: 'tiny', quaternary: true, onClick: () => openReset(row) }, { default: () => '重置密码' }))
      }
      if (canAssignRole.value) {
        actions.push(h(NButton, { size: 'tiny', quaternary: true, onClick: () => openRole(row) }, { default: () => '角色' }))
      }
      return h(NSpace, { size: 'small' }, { default: () => actions })
    },
  },
])

onMounted(load)
</script>

<template>
  <div>
    <n-space justify="space-between" align="center" style="margin-bottom: 16px">
      <n-page-header title="用户管理" />
      <NButton v-if="canCreate" type="primary" @click="openCreate">新建用户</NButton>
    </n-space>

    <n-card>
      <n-space size="small" align="center" style="margin-bottom: 12px">
        <n-input
          v-model:value="keyword"
          placeholder="用户名 / 邮箱 / 昵称"
          clearable
          style="width: 260px"
          @keyup.enter="search"
        />
        <n-select
          v-model:value="status"
          placeholder="状态"
          clearable
          style="width: 140px"
          :options="[
            { label: '正常', value: 1 },
            { label: '已停用', value: 0 },
          ]"
          @update:value="search"
        />
        <NButton size="small" @click="search">搜索</NButton>
      </n-space>

      <n-alert v-if="errorMessage" type="warning" style="margin: 12px 0">
        {{ errorMessage }}
        <n-button size="tiny" quaternary @click="load">重试</n-button>
      </n-alert>

      <n-data-table :columns="columns" :data="records" :loading="loading" :row-key="(row: AdminUserView) => row.id" />

      <div style="display: flex; justify-content: flex-end; margin-top: 16px">
        <PaginationBar v-model:page="page" :total="total" :page-size="pageSize" @update:page="load" />
      </div>
    </n-card>

    <!-- 新建 -->
    <n-modal v-model:show="showCreate" preset="card" title="新建用户" style="width: 480px">
      <n-form label-placement="top">
        <n-form-item label="用户名" :validation-status="createErrors.username ? 'error' : undefined" :feedback="createErrors.username">
          <n-input v-model:value="createForm.username" placeholder="3-20 位字母、数字或下划线" />
        </n-form-item>
        <n-form-item label="邮箱" :validation-status="createErrors.email ? 'error' : undefined" :feedback="createErrors.email">
          <n-input v-model:value="createForm.email" placeholder="user@example.com" />
        </n-form-item>
        <n-form-item label="初始密码" :validation-status="createErrors.password ? 'error' : undefined" :feedback="createErrors.password">
          <n-input v-model:value="createForm.password" type="password" show-password-on="click" placeholder="8-72 位" />
        </n-form-item>
        <n-form-item label="昵称（可空）" :validation-status="createErrors.nickname ? 'error' : undefined" :feedback="createErrors.nickname">
          <n-input v-model:value="createForm.nickname" />
        </n-form-item>
        <n-space justify="end">
          <NButton @click="showCreate = false">取消</NButton>
          <NButton type="primary" :loading="creating" @click="submitCreate">创建</NButton>
        </n-space>
      </n-form>
    </n-modal>

    <!-- 编辑 -->
    <n-modal :show="editing !== null" preset="card" title="编辑用户" style="width: 440px" @update:show="editing = null">
      <n-form label-placement="top">
        <n-form-item label="昵称" :validation-status="editErrors.nickname ? 'error' : undefined" :feedback="editErrors.nickname">
          <n-input v-model:value="editForm.nickname" />
        </n-form-item>
        <n-form-item label="邮箱" :validation-status="editErrors.email ? 'error' : undefined" :feedback="editErrors.email">
          <n-input v-model:value="editForm.email" />
        </n-form-item>
        <n-space justify="end">
          <NButton @click="editing = null">取消</NButton>
          <NButton type="primary" @click="submitEdit">保存</NButton>
        </n-space>
      </n-form>
    </n-modal>

    <!-- 重置密码 -->
    <n-modal :show="resetting !== null" preset="card" :title="`重置密码：${resetting?.username ?? ''}`" style="width: 420px" @update:show="resetting = null">
      <n-form label-placement="top">
        <n-form-item label="新密码">
          <n-input v-model:value="resetPassword" type="password" show-password-on="click" placeholder="8-72 位" />
        </n-form-item>
        <n-space justify="end">
          <NButton @click="resetting = null">取消</NButton>
          <NButton type="primary" @click="submitReset">重置</NButton>
        </n-space>
      </n-form>
    </n-modal>

    <!-- 角色 -->
    <n-modal :show="roleEditing !== null" preset="card" :title="`调整角色：${roleEditing?.username ?? ''}`" style="width: 400px" @update:show="roleEditing = null">
      <n-form label-placement="top">
        <n-form-item label="角色">
          <n-select v-model:value="nextRole" :options="ROLE_OPTIONS" />
        </n-form-item>
        <n-space justify="end">
          <NButton @click="roleEditing = null">取消</NButton>
          <NButton type="primary" @click="submitRole">保存</NButton>
        </n-space>
      </n-form>
    </n-modal>
  </div>
</template>
