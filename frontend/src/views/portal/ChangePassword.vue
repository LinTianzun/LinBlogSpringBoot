<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { changePassword } from '@/api/auth'
import { ApiError } from '@/api/request'
import { useSessionStore } from '@/stores/session'
import type { FieldError } from '@/types/api'

/**
 * 强制首改页（P3 合规）：种子超管 mustChangePassword=1 时由路由守卫强制跳入，
 * 改密成功前无法访问其他任何页面。服务端成功后清标记 + tv+1 吊销全部刷新令牌
 * + 清 access/refresh 双 Cookie → 本地会话已失效，clear() 后回登录页重登。
 */

const router = useRouter()
const session = useSessionStore()

const submitting = ref(false)
const topError = ref('')
const fieldErrors = reactive<Record<string, string>>({})
const form = reactive({ oldPassword: '', newPassword: '', confirm: '' })

function applyFieldErrors(errors: FieldError[] | undefined): void {
  for (const key of Object.keys(fieldErrors)) delete fieldErrors[key]
  for (const item of errors ?? []) fieldErrors[item.field] = item.message
}

async function handleSubmit(): Promise<void> {
  topError.value = ''
  if (form.newPassword !== form.confirm) {
    fieldErrors.confirm = '两次输入的密码不一致'
    return
  }
  submitting.value = true
  try {
    await changePassword({ oldPassword: form.oldPassword, newPassword: form.newPassword })
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
  <div class="lb-ed lb-change">
    <section class="lb-change-stage">
      <p class="lb-change-eyebrow">
        <span class="lb-ed-dot" aria-hidden="true"></span>
        LinBlog · 安全检查
      </p>
      <h1 class="lb-change-title">换个新密码</h1>
      <p class="lb-change-sub">检测到您仍在使用初始密码，为保障安全请先设置新密码，完成后需重新登录。</p>

      <el-card shadow="never" class="lb-change-card">
        <el-alert v-if="topError" type="error" class="lb-change-alert" :title="topError" :closable="false" />

        <el-form label-position="left" label-width="90px">
          <el-form-item label="当前密码">
            <el-input
              v-model="form.oldPassword"
              type="password"
              show-password
              placeholder="初始密码"
              data-testid="change-old"
            />
            <span v-if="fieldErrors.oldPassword" class="lb-field-error">{{ fieldErrors.oldPassword }}</span>
          </el-form-item>
          <el-form-item label="新密码">
            <el-input
              v-model="form.newPassword"
              type="password"
              show-password
              placeholder="8-72 位"
              data-testid="change-new"
            />
            <span v-if="fieldErrors.newPassword" class="lb-field-error">{{ fieldErrors.newPassword }}</span>
          </el-form-item>
          <el-form-item label="确认密码">
            <el-input
              v-model="form.confirm"
              type="password"
              show-password
              placeholder="再次输入"
              data-testid="change-confirm"
              @keyup.enter="handleSubmit"
            />
            <span v-if="fieldErrors.confirm" class="lb-field-error">{{ fieldErrors.confirm }}</span>
          </el-form-item>
          <el-button
            type="primary"
            class="lb-change-submit"
            :loading="submitting"
            data-testid="change-submit"
            @click="handleSubmit"
          >
            修改密码
          </el-button>
        </el-form>
      </el-card>
    </section>
  </div>
</template>

<style scoped>
.lb-change {
  min-height: 62vh;
  display: flex;
  justify-content: center;
  padding: clamp(36px, 6vw, 88px) var(--ed-margin) clamp(56px, 8vw, 120px);
}

.lb-change-stage {
  width: 100%;
  max-width: 480px;
  display: flex;
  flex-direction: column;
  text-align: center;
}

.lb-change-eyebrow {
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

.lb-change-title {
  margin: 0 0 10px;
  font-family: var(--ed-font-display);
  font-weight: 400;
  font-size: var(--ed-step-h2);
  line-height: var(--ed-leading-tight);
  letter-spacing: var(--ed-tracking-display);
  color: var(--ed-ink);
}

.lb-change-sub {
  margin: 0 0 26px;
  font-size: var(--ed-step-small);
  line-height: 1.7;
  color: var(--ed-ink-soft);
}

.lb-change-card {
  border-radius: var(--ed-radius-lg);
  border-color: var(--ed-rule-soft);
  background: var(--ed-card);
  box-shadow: var(--ed-shadow-sm);
  text-align: left;
}

.lb-change-card :deep(.el-card__body) {
  padding: clamp(20px, 3vw, 30px);
}

.lb-change-alert {
  margin-bottom: 12px;
}

.lb-change-submit {
  width: 100%;
  border-radius: var(--ed-radius-pill);
}

/* 40001 fieldErrors 显式错误文本（同 ResetPassword 页约定） */
.lb-field-error {
  display: block;
  width: 100%;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-color-danger, #f56c6c);
}
</style>
