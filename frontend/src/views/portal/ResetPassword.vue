<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { requestPasswordReset, resetPassword } from '@/api/auth'
import { ApiError } from '@/api/request'
import { useSessionStore } from '@/stores/session'
import type { FieldError } from '@/types/api'

/**
 * 重置密码（§5.1）：无 token → 请求重置邮件（恒成功不暴露邮箱存在性）；
 * 带 ?token= → 设置新密码（token 一次性，成功后全端下线 → 跳登录）。
 */

const route = useRoute()
const router = useRouter()
const session = useSessionStore()

const token = computed(() => (typeof route.query.token === 'string' && route.query.token.length > 0 ? route.query.token : null))
const submitting = ref(false)
const topMessage = ref('')
const topError = ref('')
const fieldErrors = reactive<Record<string, string>>({})

const requestForm = reactive({ email: '' })
const resetForm = reactive({ password: '', confirm: '' })

function applyFieldErrors(errors: FieldError[] | undefined): void {
  for (const key of Object.keys(fieldErrors)) delete fieldErrors[key]
  for (const item of errors ?? []) fieldErrors[item.field] = item.message
}

async function handleRequest(): Promise<void> {
  topMessage.value = ''
  topError.value = ''
  submitting.value = true
  try {
    await requestPasswordReset({ email: requestForm.email })
    topMessage.value = '如果该邮箱已注册，重置链接已发送，请查收邮件'
  } catch (error) {
    if (error instanceof ApiError) {
      if (error.code === 40001) applyFieldErrors(error.fieldErrors)
      else topError.value = error.message
    } else {
      topError.value = '请求失败，请稍后重试'
    }
  } finally {
    submitting.value = false
  }
}

async function handleReset(): Promise<void> {
  topMessage.value = ''
  topError.value = ''
  if (!token.value) return
  if (resetForm.password !== resetForm.confirm) {
    fieldErrors.confirm = '两次输入的密码不一致'
    return
  }
  submitting.value = true
  try {
    await resetPassword({ token: token.value, newPassword: resetForm.password })
    topMessage.value = '密码已重置，请使用新密码登录'
    session.clear()
    setTimeout(() => void router.push('/login-register'), 1200)
  } catch (error) {
    if (error instanceof ApiError) {
      if (error.code === 40001) applyFieldErrors(error.fieldErrors)
      else topError.value = error.message
    } else {
      topError.value = '重置失败，请稍后重试'
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="lb-ed lb-reset">
    <section class="lb-reset-stage">
      <p class="lb-reset-eyebrow">
        <span class="lb-ed-dot" aria-hidden="true"></span>
        LinBlog · 找回账户
      </p>
      <h1 class="lb-reset-title">重置密码</h1>
      <p class="lb-reset-sub">
        {{ token ? '设置一个新密码，完成后其他设备的登录会全部下线。' : '输入注册时使用的邮箱，我们会把重置链接寄给你。' }}
      </p>

      <el-card shadow="never" class="lb-reset-card">
        <el-alert v-if="topMessage" type="success" class="lb-reset-alert" :title="topMessage" :closable="false" />
        <el-alert v-else-if="topError" type="error" class="lb-reset-alert" :title="topError" :closable="false" />

        <!-- 请求重置邮件 -->
        <template v-if="!token">
          <el-form label-position="left" label-width="70px">
            <el-form-item label="邮箱">
              <el-input
                v-model="requestForm.email"
                placeholder="注册时使用的邮箱"
                data-testid="reset-email"
                @keyup.enter="handleRequest"
              />
              <span v-if="fieldErrors.email" class="lb-field-error">{{ fieldErrors.email }}</span>
            </el-form-item>
            <el-button
              type="primary"
              class="lb-reset-submit"
              :loading="submitting"
              data-testid="reset-request-submit"
              @click="handleRequest"
            >
              发送重置链接
            </el-button>
          </el-form>
        </template>

        <!-- 携带 token 设置新密码 -->
        <template v-else>
          <el-form label-position="left" label-width="90px">
            <el-form-item label="新密码">
              <el-input
                v-model="resetForm.password"
                type="password"
                show-password
                placeholder="8-72 位"
                data-testid="reset-password"
              />
              <span v-if="fieldErrors.newPassword || fieldErrors.password" class="lb-field-error">
                {{ fieldErrors.newPassword ?? fieldErrors.password }}
              </span>
            </el-form-item>
            <el-form-item label="确认密码">
              <el-input
                v-model="resetForm.confirm"
                type="password"
                show-password
                placeholder="再次输入"
                data-testid="reset-confirm"
                @keyup.enter="handleReset"
              />
              <span v-if="fieldErrors.confirm" class="lb-field-error">{{ fieldErrors.confirm }}</span>
            </el-form-item>
            <el-button
              type="primary"
              class="lb-reset-submit"
              :loading="submitting"
              data-testid="reset-submit"
              @click="handleReset"
            >
              设置新密码
            </el-button>
          </el-form>
        </template>
      </el-card>

      <p class="lb-reset-foot">
        想起来了？<RouterLink to="/login-register">返回登录</RouterLink>
      </p>
    </section>
  </div>
</template>

<style scoped>
.lb-reset {
  min-height: 62vh;
  display: flex;
  justify-content: center;
  padding: clamp(36px, 6vw, 88px) var(--ed-margin) clamp(56px, 8vw, 120px);
}

.lb-reset-stage {
  width: 100%;
  max-width: 480px;
  display: flex;
  flex-direction: column;
  text-align: center;
}

.lb-reset-eyebrow {
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

.lb-reset-title {
  margin: 0 0 10px;
  font-family: var(--ed-font-display);
  font-weight: 400;
  font-size: var(--ed-step-h2);
  line-height: var(--ed-leading-tight);
  letter-spacing: var(--ed-tracking-display);
  color: var(--ed-ink);
}

.lb-reset-sub {
  margin: 0 0 26px;
  font-size: var(--ed-step-small);
  line-height: 1.7;
  color: var(--ed-ink-soft);
}

.lb-reset-card {
  border-radius: var(--ed-radius-lg);
  border-color: var(--ed-rule-soft);
  background: var(--ed-card);
  box-shadow: var(--ed-shadow-sm);
  text-align: left;
}

.lb-reset-card :deep(.el-card__body) {
  padding: clamp(20px, 3vw, 30px);
}

.lb-reset-alert {
  margin-bottom: 12px;
}

.lb-reset-submit {
  width: 100%;
  border-radius: var(--ed-radius-pill);
}

.lb-reset-foot {
  margin: 18px 0 0;
  font-size: var(--ed-step-caption);
  color: var(--ed-ink-soft);
}

.lb-reset-foot a {
  color: var(--ed-accent);
  font-weight: 500;
}

.lb-reset-foot a:hover {
  opacity: 0.75;
}

/* 40001 fieldErrors 显式错误文本（el-form-item :error 依赖内部校验状态，外部赋值不显示） */
.lb-field-error {
  display: block;
  width: 100%;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-color-danger, #f56c6c);
}
</style>
