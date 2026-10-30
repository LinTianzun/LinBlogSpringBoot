<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { login, register } from '@/api/auth'
import { ApiError } from '@/api/request'
import { useSessionStore } from '@/stores/session'
import type { FieldError } from '@/types/api'

/**
 * 登录 / 注册（§6.3 Tab 复用单页）：
 * - 登录 {account, password}；注册 {username, email, password, nickname?}；
 * - 40001 fieldErrors 逐字段标红；40300/40900/42900 顶部错误条；
 * - 成功后 session.reload() 再按 redirect 参数回跳。
 */

const route = useRoute()
const router = useRouter()
const session = useSessionStore()

const tab = ref<'login' | 'register'>('login')
const topError = ref('')
const submitting = ref(false)

/* ---------- 登录 ---------- */
const loginForm = reactive({ account: '', password: '' })
const loginErrors = reactive<Record<string, string>>({})

/* ---------- 注册 ---------- */
const registerForm = reactive({ username: '', email: '', nickname: '', password: '', confirm: '' })
const registerErrors = reactive<Record<string, string>>({})

/** 密码强度：长度≥8、含数字、含大小写混合、含符号 → 0-4 */
const passwordScore = computed(() => {
  const pwd = registerForm.password
  if (pwd.length === 0) return 0
  let score = 0
  if (pwd.length >= 8) score += 1
  if (/[0-9]/.test(pwd) && /[a-zA-Z]/.test(pwd)) score += 1
  if (/[a-z]/.test(pwd) && /[A-Z]/.test(pwd)) score += 1
  if (/[^0-9a-zA-Z]/.test(pwd)) score += 1
  return score
})

const strengthLabel = computed(() => ['过短', '弱', '一般', '较强', '强'][passwordScore.value] ?? '')
const strengthType = computed(() => (['error', 'error', 'warning', 'info', 'success'][passwordScore.value] ?? 'error') as 'error' | 'warning' | 'info' | 'success')

function applyFieldErrors(errors: Record<string, string>, fieldErrors: FieldError[] | undefined): void {
  for (const key of Object.keys(errors)) delete errors[key]
  for (const item of fieldErrors ?? []) errors[item.field] = item.message
}

async function handleLogin(): Promise<void> {
  topError.value = ''
  applyFieldErrors(loginErrors, [])
  submitting.value = true
  try {
    await login({ account: loginForm.account, password: loginForm.password })
    await session.reload()
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    await router.push(redirect)
  } catch (error) {
    if (error instanceof ApiError) {
      if (error.code === 40001) applyFieldErrors(loginErrors, error.fieldErrors)
      else topError.value = error.message
    } else {
      topError.value = '登录失败，请稍后重试'
    }
  } finally {
    submitting.value = false
  }
}

async function handleRegister(): Promise<void> {
  topError.value = ''
  applyFieldErrors(registerErrors, [])
  if (registerForm.password !== registerForm.confirm) {
    registerErrors.confirm = '两次输入的密码不一致'
    return
  }
  submitting.value = true
  try {
    await register({
      username: registerForm.username,
      email: registerForm.email,
      password: registerForm.password,
      nickname: registerForm.nickname || undefined,
    })
    await session.reload()
    await router.push('/')
  } catch (error) {
    if (error instanceof ApiError) {
      if (error.code === 40001) applyFieldErrors(registerErrors, error.fieldErrors)
      else topError.value = error.message
    } else {
      topError.value = '注册失败，请稍后重试'
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="lb-auth-page">
    <n-card class="lb-auth-card">
      <n-tabs v-model:value="tab" size="large" justify-content="space-evenly">
        <n-tab-pane name="login" tab="登录">
          <n-alert v-if="topError && tab === 'login'" type="error" class="lb-auth-alert">{{ topError }}</n-alert>
          <n-form label-placement="left" label-width="70">
            <n-form-item label="账号" :validation-status="loginErrors.account ? 'error' : undefined" :feedback="loginErrors.account">
              <n-input v-model:value="loginForm.account" placeholder="用户名 / 邮箱" data-testid="login-account" @keyup.enter="handleLogin" />
            </n-form-item>
            <n-form-item label="密码" :validation-status="loginErrors.password ? 'error' : undefined" :feedback="loginErrors.password">
              <n-input
                v-model:value="loginForm.password"
                type="password"
                show-password-on="click"
                placeholder="密码"
                data-testid="login-password"
                @keyup.enter="handleLogin"
              />
            </n-form-item>
            <n-space vertical size="small">
              <n-button type="primary" block :loading="submitting" data-testid="login-submit" @click="handleLogin">
                登录
              </n-button>
              <span class="lb-muted lb-auth-hint">
                忘记密码？<a href="/reset-password" style="color: var(--lb-primary)">重置密码</a>
              </span>
            </n-space>
          </n-form>
        </n-tab-pane>

        <n-tab-pane name="register" tab="注册">
          <n-alert v-if="topError && tab === 'register'" type="error" class="lb-auth-alert">{{ topError }}</n-alert>
          <n-form label-placement="left" label-width="70">
            <n-form-item label="用户名" :validation-status="registerErrors.username ? 'error' : undefined" :feedback="registerErrors.username">
              <n-input v-model:value="registerForm.username" placeholder="3-20 位字母、数字或下划线" data-testid="register-username" />
            </n-form-item>
            <n-form-item label="邮箱" :validation-status="registerErrors.email ? 'error' : undefined" :feedback="registerErrors.email">
              <n-input v-model:value="registerForm.email" placeholder="name@example.com" data-testid="register-email" />
            </n-form-item>
            <n-form-item label="昵称" :validation-status="registerErrors.nickname ? 'error' : undefined" :feedback="registerErrors.nickname">
              <n-input v-model:value="registerForm.nickname" placeholder="选填" data-testid="register-nickname" />
            </n-form-item>
            <n-form-item label="密码" :validation-status="registerErrors.password ? 'error' : undefined" :feedback="registerErrors.password">
              <n-space vertical class="lb-strength-wrap">
                <n-input
                  v-model:value="registerForm.password"
                  type="password"
                  show-password-on="click"
                  placeholder="8-72 位"
                  data-testid="register-password"
                />
                <n-progress
                  v-if="registerForm.password"
                  type="line"
                  :percentage="(passwordScore / 4) * 100"
                  :show-indicator="false"
                  :status="strengthType"
                  :height="6"
                />
                <span class="lb-muted">强度：{{ strengthLabel }}</span>
              </n-space>
            </n-form-item>
            <n-form-item label="确认密码" :validation-status="registerErrors.confirm ? 'error' : undefined" :feedback="registerErrors.confirm">
              <n-input
                v-model:value="registerForm.confirm"
                type="password"
                show-password-on="click"
                placeholder="再次输入密码"
                data-testid="register-confirm"
                @keyup.enter="handleRegister"
              />
            </n-form-item>
            <n-button type="primary" block :loading="submitting" data-testid="register-submit" @click="handleRegister">
              注册
            </n-button>
          </n-form>
        </n-tab-pane>
      </n-tabs>
    </n-card>
  </div>
</template>

<style scoped>
.lb-auth-page {
  display: flex;
  justify-content: center;
  padding: 40px 0;
}

.lb-auth-card {
  width: 100%;
  max-width: 460px;
}

.lb-auth-alert {
  margin-bottom: 12px;
}

.lb-auth-hint {
  text-align: center;
}

.lb-strength-wrap {
  width: 100%;
}
</style>
