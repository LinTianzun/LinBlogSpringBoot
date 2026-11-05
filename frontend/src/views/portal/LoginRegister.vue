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

/** 强度 → 进度条颜色（element-plus 无语义 status，用色值对齐 art token） */
const strengthColor = computed(() => {
  const colors = ['#f56c6c', '#f56c6c', '#e6a23c', '#909399', '#67c23a']
  return colors[passwordScore.value] ?? '#f56c6c'
})

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
  <div class="lb-ed lb-auth">
    <section class="lb-auth-stage">
      <p class="lb-auth-eyebrow">
        <span class="lb-ed-dot" aria-hidden="true"></span>
        LinBlog · 欢迎回来
      </p>
      <h1 class="lb-auth-title">把话说给懂的人听。</h1>
      <p class="lb-auth-sub">登录后继续你的写作与阅读，新朋友一分钟就能加入。</p>

      <el-card shadow="never" class="lb-auth-card">
        <el-tabs v-model="tab">
          <el-tab-pane label="登录" name="login">
            <el-alert v-if="topError && tab === 'login'" type="error" class="lb-auth-alert" :title="topError" :closable="false" />
            <el-form label-position="left" label-width="70px">
              <el-form-item label="账号">
                <el-input
                  v-model="loginForm.account"
                  placeholder="用户名 / 邮箱"
                  data-testid="login-account"
                  @keyup.enter="handleLogin"
                />
                <span v-if="loginErrors.account" class="lb-field-error">{{ loginErrors.account }}</span>
              </el-form-item>
              <el-form-item label="密码">
                <el-input
                  v-model="loginForm.password"
                  type="password"
                  show-password
                  placeholder="密码"
                  data-testid="login-password"
                  @keyup.enter="handleLogin"
                />
                <span v-if="loginErrors.password" class="lb-field-error">{{ loginErrors.password }}</span>
              </el-form-item>
              <el-button
                type="primary"
                class="lb-auth-submit"
                :loading="submitting"
                data-testid="login-submit"
                @click="handleLogin"
              >
                登录
              </el-button>
              <p class="lb-auth-hint">
                忘记密码？<a href="/reset-password">重置密码</a>
              </p>
            </el-form>
          </el-tab-pane>

          <el-tab-pane label="注册" name="register">
            <el-alert v-if="topError && tab === 'register'" type="error" class="lb-auth-alert" :title="topError" :closable="false" />
            <el-form label-position="left" label-width="70px">
              <el-form-item label="用户名">
                <el-input
                  v-model="registerForm.username"
                  placeholder="3-20 位字母、数字或下划线"
                  data-testid="register-username"
                />
                <span v-if="registerErrors.username" class="lb-field-error">{{ registerErrors.username }}</span>
              </el-form-item>
              <el-form-item label="邮箱">
                <el-input
                  v-model="registerForm.email"
                  placeholder="name@example.com"
                  data-testid="register-email"
                />
                <span v-if="registerErrors.email" class="lb-field-error">{{ registerErrors.email }}</span>
              </el-form-item>
              <el-form-item label="昵称">
                <el-input
                  v-model="registerForm.nickname"
                  placeholder="选填"
                  data-testid="register-nickname"
                />
                <span v-if="registerErrors.nickname" class="lb-field-error">{{ registerErrors.nickname }}</span>
              </el-form-item>
              <el-form-item label="密码">
                <div class="lb-strength-wrap">
                  <el-input
                    v-model="registerForm.password"
                    type="password"
                    show-password
                    placeholder="8-72 位"
                    data-testid="register-password"
                  />
                  <span v-if="registerErrors.password" class="lb-field-error">{{ registerErrors.password }}</span>
                  <el-progress
                    v-if="registerForm.password"
                    :percentage="(passwordScore / 4) * 100"
                    :show-text="false"
                    :stroke-width="6"
                    :color="strengthColor"
                    class="lb-strength-bar"
                  />
                  <span class="lb-auth-strength">强度：{{ strengthLabel }}</span>
                </div>
              </el-form-item>
              <el-form-item label="确认密码">
                <el-input
                  v-model="registerForm.confirm"
                  type="password"
                  show-password
                  placeholder="再次输入密码"
                  data-testid="register-confirm"
                  @keyup.enter="handleRegister"
                />
                <span v-if="registerErrors.confirm" class="lb-field-error">{{ registerErrors.confirm }}</span>
              </el-form-item>
              <el-button
                type="primary"
                class="lb-auth-submit"
                :loading="submitting"
                data-testid="register-submit"
                @click="handleRegister"
              >
                注册
              </el-button>
            </el-form>
          </el-tab-pane>
        </el-tabs>
      </el-card>
    </section>
  </div>
</template>

<style scoped>
.lb-auth {
  min-height: 62vh;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: clamp(36px, 6vw, 88px) var(--ed-margin) clamp(56px, 8vw, 120px);
}

.lb-auth-stage {
  width: 100%;
  max-width: 480px;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  text-align: center;
}

.lb-auth-eyebrow {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin: 0 0 14px;
  font-family: var(--ed-font-ui);
  font-size: var(--ed-step-caption);
  letter-spacing: 0.08em;
  color: var(--ed-ink-soft);
}

.lb-auth-title {
  margin: 0 0 10px;
  font-family: var(--ed-font-display);
  font-weight: 400;
  font-size: var(--ed-step-h2);
  line-height: var(--ed-leading-tight);
  letter-spacing: var(--ed-tracking-display);
  color: var(--ed-ink);
}

.lb-auth-sub {
  margin: 0 0 26px;
  font-size: var(--ed-step-small);
  line-height: 1.7;
  color: var(--ed-ink-soft);
}

.lb-auth-card {
  border-radius: var(--ed-radius-lg);
  border-color: var(--ed-rule-soft);
  background: var(--ed-card);
  box-shadow: var(--ed-shadow-sm);
  text-align: left;
}

.lb-auth-card :deep(.el-card__body) {
  padding: clamp(20px, 3vw, 30px);
}

.lb-auth-alert {
  margin-bottom: 12px;
}

/* 40001 fieldErrors 显式错误文本（el-form-item :error 依赖内部校验状态，外部赋值不显示） */
.lb-field-error {
  display: block;
  width: 100%;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-color-danger, #f56c6c);
}

.lb-auth-submit {
  width: 100%;
  border-radius: var(--ed-radius-pill);
}

.lb-auth-hint {
  text-align: center;
  margin: 12px 0 0;
  font-size: var(--ed-step-caption);
  color: var(--ed-ink-soft);
}

.lb-auth-hint a {
  color: var(--ed-accent);
  font-weight: 500;
}

.lb-auth-hint a:hover {
  opacity: 0.75;
}

.lb-strength-wrap {
  width: 100%;
}

.lb-strength-bar {
  margin: 6px 0 2px;
}

.lb-auth-strength {
  font-size: var(--ed-step-caption);
  color: var(--ed-ink-soft);
}
</style>
