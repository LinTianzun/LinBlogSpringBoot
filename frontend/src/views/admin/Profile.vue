<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Message, Calendar, Key, Lock, EditPen, RefreshRight } from '@element-plus/icons-vue'
import { useSessionStore } from '@/stores/session'
import { changePassword } from '@/api/auth'
import { ApiError } from '@/api/request'
import { formatDateTime } from '@/utils/format'
import type { FieldError, RoleCode } from '@/types/api'

/**
 * 后台「个人中心」：只读展示当前登录用户资料（头像/昵称/用户名/邮箱/角色/注册时间），
 * 并提供内嵌的修改密码入口（复用 POST /auth/password/change）。改密成功后服务端吊销全部
 * 刷新令牌，本地会话失效 → 清空会话并回登录页重登（与强制首改页一致）。
 */

const router = useRouter()
const session = useSessionStore()

const ROLE_LABELS: Record<RoleCode, string> = {
  super_admin: '超级管理员',
  admin: '管理员',
  editor: '编辑',
  viewer: '访客',
}

const user = computed(() => session.user)

const displayName = computed(() => user.value?.nickname || user.value?.username || '未登录')
const username = computed(() => user.value?.username ?? '')
const email = computed(() => user.value?.email || '—')
const roleLabel = computed(() => (user.value ? ROLE_LABELS[user.value.roleCode] : '—'))
const isSuperAdmin = computed(() => user.value?.isSuperAdmin === true || user.value?.roleCode === 'super_admin')
const createdAt = computed(() => (user.value?.createdAt ? formatDateTime(user.value.createdAt) : '—'))
const initial = computed(() => (displayName.value ? displayName.value.slice(0, 1).toUpperCase() : '?'))

/* ---- 修改密码表单 ---- */
const pwdOpen = ref(false)
const submitting = ref(false)
const topError = ref('')
const fieldErrors = reactive<Record<string, string>>({})
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirm: '' })

const confirmError = computed(() => fieldErrors.confirm ?? '')

function resetPwdForm(): void {
  pwdForm.oldPassword = ''
  pwdForm.newPassword = ''
  pwdForm.confirm = ''
  topError.value = ''
  for (const key of Object.keys(fieldErrors)) delete fieldErrors[key]
}

function togglePwd(): void {
  pwdOpen.value = !pwdOpen.value
  if (pwdOpen.value) resetPwdForm()
}

function applyFieldErrors(errors: FieldError[] | undefined): void {
  for (const key of Object.keys(fieldErrors)) delete fieldErrors[key]
  for (const item of errors ?? []) fieldErrors[item.field] = item.message
}

async function submitPassword(): Promise<void> {
  topError.value = ''
  for (const key of Object.keys(fieldErrors)) delete fieldErrors[key]
  if (!pwdForm.oldPassword) {
    fieldErrors.oldPassword = '请输入当前密码'
    return
  }
  if (pwdForm.newPassword.length < 8) {
    fieldErrors.newPassword = '新密码至少 8 位'
    return
  }
  if (pwdForm.newPassword !== pwdForm.confirm) {
    fieldErrors.confirm = '两次输入的密码不一致'
    return
  }
  submitting.value = true
  try {
    await changePassword({ oldPassword: pwdForm.oldPassword, newPassword: pwdForm.newPassword })
    ElMessage.success('密码已修改，请使用新密码重新登录')
    session.clear()
    void router.replace('/login-register')
  } catch (error) {
    if (error instanceof ApiError) {
      if (error.code === 40001) applyFieldErrors(error.fieldErrors)
      else topError.value = error.message
    } else {
      topError.value = '修改失败，请稍后重试'
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="lb-profile">
    <div class="lb-page-header">
      <div>
        <h2>个人中心</h2>
        <span class="lb-muted">账号资料与安全设置</span>
      </div>
    </div>

    <div class="lb-profile-grid">
      <!-- 资料卡 -->
      <section class="lb-ed-card lb-profile-card">
        <div class="lb-profile-hero">
          <div class="lb-profile-avatar" aria-hidden="true">
            <img v-if="user?.avatarMediaId" :src="`/api/v1/media/${user.avatarMediaId}`" alt="" />
            <span v-else>{{ initial }}</span>
          </div>
          <div class="lb-profile-identity">
            <p class="lb-ed-card-title">{{ displayName }}</p>
            <p class="lb-profile-handle">@{{ username }}</p>
            <span class="lb-ed-pill lb-ed-pill--soft lb-profile-role">{{ roleLabel }}</span>
            <span v-if="isSuperAdmin" class="lb-ed-pill lb-profile-super">全部权限</span>
          </div>
        </div>

        <dl class="lb-profile-info">
          <div class="lb-profile-info-row">
            <dt><el-icon><User /></el-icon>昵称</dt>
            <dd>{{ user?.nickname || '未设置' }}</dd>
          </div>
          <div class="lb-profile-info-row">
            <dt><el-icon><EditPen /></el-icon>用户名</dt>
            <dd>{{ username || '—' }}</dd>
          </div>
          <div class="lb-profile-info-row">
            <dt><el-icon><Message /></el-icon>邮箱</dt>
            <dd class="lb-profile-ellipsis">{{ email }}</dd>
          </div>
          <div class="lb-profile-info-row">
            <dt><el-icon><Key /></el-icon>角色</dt>
            <dd>{{ roleLabel }}</dd>
          </div>
          <div class="lb-profile-info-row">
            <dt><el-icon><Calendar /></el-icon>注册时间</dt>
            <dd>{{ createdAt }}</dd>
          </div>
        </dl>
      </section>

      <!-- 安全设置 -->
      <section class="lb-ed-card lb-profile-security">
        <header class="lb-profile-security-head">
          <div>
            <h3 class="lb-ed-card-title">账号安全</h3>
            <p class="lb-profile-sub">定期更换密码以保障账号安全</p>
          </div>
          <el-button :icon="pwdOpen ? RefreshRight : Lock" @click="togglePwd">
            {{ pwdOpen ? '收起' : '修改密码' }}
          </el-button>
        </header>

        <div v-if="pwdOpen" class="lb-profile-pwd">
          <el-alert v-if="topError" type="error" :title="topError" :closable="false" class="lb-profile-alert" />
          <el-form label-position="top" @submit.prevent="submitPassword">
            <el-form-item label="当前密码" :error="fieldErrors.oldPassword">
              <el-input
                v-model="pwdForm.oldPassword"
                type="password"
                show-password
                autocomplete="current-password"
                placeholder="请输入当前密码"
                data-testid="profile-old-password"
              />
            </el-form-item>
            <el-form-item label="新密码" :error="fieldErrors.newPassword">
              <el-input
                v-model="pwdForm.newPassword"
                type="password"
                show-password
                autocomplete="new-password"
                placeholder="至少 8 位"
                data-testid="profile-new-password"
              />
            </el-form-item>
            <el-form-item label="确认新密码" :error="confirmError">
              <el-input
                v-model="pwdForm.confirm"
                type="password"
                show-password
                autocomplete="new-password"
                placeholder="再次输入新密码"
                data-testid="profile-confirm-password"
              />
            </el-form-item>
            <div class="lb-profile-pwd-actions">
              <el-button type="primary" :loading="submitting" data-testid="profile-submit-password" @click="submitPassword">
                确认修改
              </el-button>
              <span class="lb-profile-hint">修改成功后需使用新密码重新登录。</span>
            </div>
          </el-form>
        </div>
        <div v-else class="lb-profile-security-idle">
          <p>修改密码后，所有已登录设备将自动退出，需要使用新密码重新登录。</p>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.lb-profile-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(0, 1fr);
  gap: var(--app-gap, 24px);
  align-items: start;
}

.lb-profile-card {
  padding: clamp(20px, 2.4vw, 30px);
}

.lb-profile-hero {
  display: flex;
  align-items: center;
  gap: 18px;
  padding-bottom: 20px;
  border-bottom: 1px solid var(--ed-rule-soft);
}

.lb-profile-avatar {
  flex: none;
  width: 72px;
  height: 72px;
  border-radius: 50%;
  overflow: hidden;
  display: grid;
  place-items: center;
  font-family: var(--ed-font-display);
  font-size: 30px;
  font-weight: 600;
  color: #fff;
  background: linear-gradient(140deg, var(--ed-accent), color-mix(in srgb, var(--ed-accent) 55%, #f0a03c));
  box-shadow: 0 14px 28px -16px var(--ed-accent);
}

.lb-profile-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.lb-profile-identity {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.lb-profile-identity .lb-ed-card-title {
  margin: 0;
}

.lb-profile-handle {
  margin: 0;
  font-size: var(--ed-step-small);
  color: var(--ed-ink-soft);
}

.lb-profile-role {
  align-self: flex-start;
  margin-top: 6px;
}

.lb-profile-super {
  align-self: flex-start;
  margin-top: 6px;
  background: var(--ed-accent);
  color: #fff;
}

.lb-profile-info {
  margin: 8px 0 0;
}

.lb-profile-info-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 0;
  border-bottom: 1px solid var(--ed-rule-soft);
}

.lb-profile-info-row:last-child {
  border-bottom: 0;
}

.lb-profile-info-row dt {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  margin: 0;
  font-size: var(--ed-step-small);
  color: var(--ed-ink-soft);
}

.lb-profile-info-row dd {
  margin: 0;
  font-size: var(--ed-step-body);
  font-weight: 500;
  color: var(--ed-ink);
  text-align: right;
}

.lb-profile-ellipsis {
  max-width: 60%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.lb-profile-security {
  padding: clamp(20px, 2.4vw, 28px);
}

.lb-profile-security-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.lb-profile-sub {
  margin: 3px 0 0;
  font-size: var(--ed-step-small);
  color: var(--ed-ink-soft);
}

.lb-profile-pwd {
  margin-top: 20px;
}

.lb-profile-alert {
  margin-bottom: 16px;
  border-radius: var(--ed-radius-md);
}

.lb-profile-pwd-actions {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  margin-top: 6px;
}

.lb-profile-hint {
  font-size: var(--ed-step-caption);
  color: var(--ed-ink-soft);
}

.lb-profile-security-idle {
  margin-top: 18px;
  padding: 18px 20px;
  border-radius: var(--ed-radius-md);
  background: var(--ed-accent-soft);
  color: var(--ed-ink-soft);
  font-size: var(--ed-step-small);
  line-height: 1.6;
}

.lb-profile-security-idle p {
  margin: 0;
}

@media (max-width: 900px) {
  .lb-profile-grid {
    grid-template-columns: 1fr;
  }
}
</style>
