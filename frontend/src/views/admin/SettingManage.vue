<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getSettings, updateSettings } from '@/api/admin'
import { ApiError } from '@/api/request'
import type { SettingsDTO } from '@/types/api'

/**
 * 系统设置（§5.2/§6.1，M4）：GET /admin/settings 返回全部白名单键（空串=未设置），
 * PUT 全量提交。键与限长对齐 backend SettingService.LIMITS：
 * site_title 50 / site_description 200 / site_keywords 200 / icp 50 / footer_text 200 / announcement 500。
 */

const SETTINGS_FIELDS: Array<{ key: keyof SettingsForm; label: string; max: number; type: 'input' | 'textarea'; placeholder: string }> = [
  { key: 'site_title', label: '站点标题', max: 50, type: 'input', placeholder: '显示在站点名称与浏览器标签页' },
  { key: 'site_description', label: '站点描述', max: 200, type: 'textarea', placeholder: 'SEO description 与站点介绍' },
  { key: 'site_keywords', label: 'SEO 关键词', max: 200, type: 'input', placeholder: '多个关键词用英文逗号分隔' },
  { key: 'icp', label: 'ICP 备案号', max: 50, type: 'input', placeholder: '如：京ICP备xxxxxxxx号（可留空）' },
  { key: 'footer_text', label: '页脚文字', max: 200, type: 'textarea', placeholder: '展示在站点页脚（可留空）' },
  { key: 'announcement', label: '站点公告', max: 500, type: 'textarea', placeholder: '面向全站访客的公告（可留空）' },
]

interface SettingsForm extends Record<string, string> {
  site_title: string
  site_description: string
  site_keywords: string
  icp: string
  footer_text: string
  announcement: string
}

const form = reactive<SettingsForm>({
  site_title: '',
  site_description: '',
  site_keywords: '',
  icp: '',
  footer_text: '',
  announcement: '',
})

const loading = ref(false)
const saving = ref(false)
const errorMessage = ref('')

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const settings: SettingsDTO = await getSettings()
    for (const field of SETTINGS_FIELDS) {
      form[field.key] = settings[field.key] ?? ''
    }
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function save(): Promise<void> {
  saving.value = true
  errorMessage.value = ''
  try {
    const payload: SettingsDTO = {}
    for (const field of SETTINGS_FIELDS) {
      payload[field.key] = form[field.key].trim()
    }
    await updateSettings(payload)
    ElMessage.success('设置已保存')
    await load()
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '保存失败'
    ElMessage.error(errorMessage.value)
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="lb-page-header">
      <h2>系统设置</h2>
      <span class="lb-muted">站点元信息与展示配置</span>
    </div>

    <el-alert v-if="errorMessage" type="warning" :closable="false" class="lb-setting-alert">
      {{ errorMessage }}
      <el-button link size="small" @click="load">重试</el-button>
    </el-alert>

    <el-card shadow="never" v-loading="loading">
      <el-form label-position="top" class="lb-setting-form" data-testid="setting-form">
        <template v-for="field in SETTINGS_FIELDS" :key="field.key">
          <el-input
            v-if="field.type === 'input'"
            v-model="form[field.key]"
            :maxlength="field.max"
            show-word-limit
            :placeholder="field.placeholder"
            :data-testid="`setting-${field.key}`"
          />
          <el-input
            v-else
            v-model="form[field.key]"
            type="textarea"
            :rows="3"
            :maxlength="field.max"
            show-word-limit
            :placeholder="field.placeholder"
            :data-testid="`setting-${field.key}`"
          />
        </template>
        <el-form-item>
          <el-button type="primary" :loading="saving" data-testid="setting-save" @click="save">保存设置</el-button>
          <span class="lb-muted lb-setting-hint">留空表示清除该项；保存后立即生效。</span>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.lb-page-header {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 16px;
}

.lb-page-header h2 {
  margin: 0;
  font-size: 18px;
}

.lb-setting-alert {
  margin-bottom: 16px;
}

.lb-setting-form {
  max-width: 640px;
}

.lb-setting-form .el-input {
  margin-bottom: 16px;
  width: 100%;
}

.lb-setting-hint {
  margin-left: 12px;
  font-size: 12px;
}
</style>
