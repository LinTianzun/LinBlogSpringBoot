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
  <div class="lb-reset-page">
    <n-card class="lb-reset-card">
      <n-h3 class="lb-reset-title">重置密码</n-h3>

      <n-alert v-if="topMessage" type="success" class="lb-reset-alert">{{ topMessage }}</n-alert>
      <n-alert v-else-if="topError" type="error" class="lb-reset-alert">{{ topError }}</n-alert>

      <!-- 请求重置邮件 -->
      <template v-if="!token">
        <n-form label-placement="left" label-width="70">
          <n-form-item label="邮箱" :validation-status="fieldErrors.email ? 'error' : undefined" :feedback="fieldErrors.email">
            <n-input v-model:value="requestForm.email" placeholder="注册时使用的邮箱" data-testid="reset-email" @keyup.enter="handleRequest" />
          </n-form-item>
          <n-button type="primary" block :loading="submitting" data-testid="reset-request-submit" @click="handleRequest">
            发送重置链接
          </n-button>
        </n-form>
      </template>

      <!-- 携带 token 设置新密码 -->
      <template v-else>
        <n-form label-placement="left" label-width="90">
          <n-form-item label="新密码" :validation-status="fieldErrors.newPassword || fieldErrors.password ? 'error' : undefined" :feedback="fieldErrors.newPassword ?? fieldErrors.password">
            <n-input v-model:value="resetForm.password" type="password" show-password-on="click" placeholder="8-72 位" data-testid="reset-password" />
          </n-form-item>
          <n-form-item label="确认密码" :validation-status="fieldErrors.confirm ? 'error' : undefined" :feedback="fieldErrors.confirm">
            <n-input v-model:value="resetForm.confirm" type="password" show-password-on="click" placeholder="再次输入" data-testid="reset-confirm" @keyup.enter="handleReset" />
          </n-form-item>
          <n-button type="primary" block :loading="submitting" data-testid="reset-submit" @click="handleReset">
            设置新密码
          </n-button>
        </n-form>
      </template>
    </n-card>
  </div>
</template>

<style scoped>
.lb-reset-page {
  display: flex;
  justify-content: center;
  padding: 40px 0;
}

.lb-reset-card {
  width: 100%;
  max-width: 460px;
}

.lb-reset-title {
  margin-top: 0;
}

.lb-reset-alert {
  margin-bottom: 12px;
}
</style>
