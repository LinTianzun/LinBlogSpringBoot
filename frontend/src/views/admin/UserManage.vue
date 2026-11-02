<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { createAdminUser, listAdminUsers, resetAdminUserPassword, updateAdminUser, updateAdminUserRole, updateAdminUserStatus } from '@/api/user'
import { ApiError } from '@/api/request'
import { useSessionStore } from '@/stores/session'
import PaginationBar from '@/components/PaginationBar.vue'
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

const STATE_TAG_TYPE = { 0: 'danger', 1: 'success' } as const

const session = useSessionStore()

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
    ElMessage.success('用户已创建')
    showCreate.value = false
    await load()
  } catch (error) {
    if (error instanceof ApiError && error.code === 40001 && error.fieldErrors) {
      const map: Record<string, string> = {}
      for (const item of error.fieldErrors) map[item.field] = item.message
      createErrors.value = map
    } else {
      ElMessage.error(error instanceof ApiError ? error.message : '创建失败')
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
    ElMessage.success('已保存')
    editing.value = null
    await load()
  } catch (error) {
    if (error instanceof ApiError && error.code === 40001 && error.fieldErrors) {
      const map: Record<string, string> = {}
      for (const item of error.fieldErrors) map[item.field] = item.message
      editErrors.value = map
    } else {
      ElMessage.error(error instanceof ApiError ? error.message : '保存失败')
    }
  }
}

// —— 停用/启用 ——
async function toggleStatus(row: AdminUserView): Promise<void> {
  const next = row.status === 1 ? 0 : 1
  try {
    await updateAdminUserStatus(row.id, { status: next })
    ElMessage.success(next === 1 ? '已启用' : '已停用')
    await load()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '操作失败')
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
    ElMessage.error('新密码长度须为 8-72 位')
    return
  }
  try {
    await resetAdminUserPassword(resetting.value.id, { password: resetPassword.value })
    ElMessage.success('密码已重置')
    resetting.value = null
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '重置失败')
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
    ElMessage.success('角色已更新')
    roleEditing.value = null
    await load()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '更新失败')
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="lb-page-header">
      <h2>用户管理</h2>
      <el-button v-if="canCreate" type="primary" @click="openCreate">新建用户</el-button>
    </div>

    <el-card shadow="never">
      <div class="lb-manage-filters">
        <el-input
          v-model="keyword"
          placeholder="用户名 / 邮箱 / 昵称"
          clearable
          style="width: 260px"
          @keyup.enter="search"
        />
        <el-select
          v-model="status"
          placeholder="状态"
          clearable
          style="width: 140px"
          @update:model-value="search"
        >
          <el-option label="正常" :value="1" />
          <el-option label="已停用" :value="0" />
        </el-select>
        <el-button size="small" @click="search">搜索</el-button>
      </div>

      <el-alert v-if="errorMessage" type="warning" :title="errorMessage" class="lb-load-alert" :closable="false">
        <el-button size="small" link @click="load">重试</el-button>
      </el-alert>

      <el-table v-loading="loading" :data="records" row-key="id">
        <el-table-column label="ID" prop="id" width="72" />
        <el-table-column label="用户名" prop="username" min-width="110" />
        <el-table-column label="昵称" min-width="100">
          <template #default="{ row }">{{ row.nickname || '—' }}</template>
        </el-table-column>
        <el-table-column label="邮箱" prop="email" min-width="160" />
        <el-table-column label="角色" min-width="100">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="row.role === 'super_admin' ? 'warning' : 'info'">
              {{ ROLE_OPTIONS.find((item) => item.value === row.role)?.label ?? row.role }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="88">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="STATE_TAG_TYPE[row.status as 0 | 1]">
              {{ row.status === 1 ? '正常' : '已停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="120">
          <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="250">
          <template #default="{ row }">
            <el-button v-if="canEdit" link size="small" @click="openEdit(row as AdminUserView)">编辑</el-button>
            <el-popconfirm
              v-if="canDisable"
              :title="row.status === 1 ? '确定停用该用户？' : '确定启用该用户？'"
              @confirm="toggleStatus(row as AdminUserView)"
            >
              <template #reference>
                <el-button link size="small" :type="row.status === 1 ? 'warning' : 'primary'">
                  {{ row.status === 1 ? '停用' : '启用' }}
                </el-button>
              </template>
            </el-popconfirm>
            <el-button v-if="canReset" link size="small" @click="openReset(row as AdminUserView)">重置密码</el-button>
            <el-button v-if="canAssignRole" link size="small" @click="openRole(row as AdminUserView)">角色</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="lb-manage-pagination">
        <PaginationBar v-model:page="page" :total="total" :page-size="pageSize" @update:page="load" />
      </div>
    </el-card>

    <!-- 新建 -->
    <el-dialog v-model="showCreate" title="新建用户" width="480px" @update:model-value="(show: boolean) => { if (!show) showCreate = false }">
      <el-form label-position="top">
        <el-form-item label="用户名" :error="createErrors.username">
          <el-input v-model="createForm.username" placeholder="3-20 位字母、数字或下划线" />
        </el-form-item>
        <el-form-item label="邮箱" :error="createErrors.email">
          <el-input v-model="createForm.email" placeholder="user@example.com" />
        </el-form-item>
        <el-form-item label="初始密码" :error="createErrors.password">
          <el-input v-model="createForm.password" type="password" show-password placeholder="8-72 位" />
        </el-form-item>
        <el-form-item label="昵称（可空）" :error="createErrors.nickname">
          <el-input v-model="createForm.nickname" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="lb-dialog-actions">
          <el-button size="small" @click="showCreate = false">取消</el-button>
          <el-button size="small" type="primary" :loading="creating" @click="submitCreate">创建</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 编辑 -->
    <el-dialog :model-value="editing !== null" title="编辑用户" width="440px" @update:model-value="editing = null">
      <el-form label-position="top">
        <el-form-item label="昵称" :error="editErrors.nickname">
          <el-input v-model="editForm.nickname" />
        </el-form-item>
        <el-form-item label="邮箱" :error="editErrors.email">
          <el-input v-model="editForm.email" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="lb-dialog-actions">
          <el-button size="small" @click="editing = null">取消</el-button>
          <el-button size="small" type="primary" @click="submitEdit">保存</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 重置密码 -->
    <el-dialog
      :model-value="resetting !== null"
      :title="`重置密码：${resetting?.username ?? ''}`"
      width="420px"
      @update:model-value="resetting = null"
    >
      <el-form label-position="top">
        <el-form-item label="新密码">
          <el-input v-model="resetPassword" type="password" show-password placeholder="8-72 位" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="lb-dialog-actions">
          <el-button size="small" @click="resetting = null">取消</el-button>
          <el-button size="small" type="primary" @click="submitReset">重置</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 角色 -->
    <el-dialog
      :model-value="roleEditing !== null"
      :title="`调整角色：${roleEditing?.username ?? ''}`"
      width="400px"
      @update:model-value="roleEditing = null"
    >
      <el-form label-position="top">
        <el-form-item label="角色">
          <el-select v-model="nextRole" style="width: 100%">
            <el-option v-for="option in ROLE_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="lb-dialog-actions">
          <el-button size="small" @click="roleEditing = null">取消</el-button>
          <el-button size="small" type="primary" @click="submitRole">保存</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.lb-page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.lb-page-header h2 {
  margin: 0;
  font-size: 18px;
}

.lb-manage-filters {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.lb-load-alert {
  margin: 0 0 12px;
}

.lb-manage-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.lb-dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
