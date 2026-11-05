<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Check, Document, InfoFilled, OfficeBuilding, Search, View } from '@element-plus/icons-vue'
import type { Component } from 'vue'
import { getSettings, updateSettings } from '@/api/admin'
import { ApiError } from '@/api/request'
import type { SettingsDTO } from '@/types/api'

/**
 * 系统设置（§5.2/§6.1，M4）：GET /admin/settings 返回全部白名单键（空串=未设置），
 * PUT 全量提交。键与限长对齐 backend SettingService.LIMITS：
 * site_title 50 / site_description 200 / site_keywords 200 / icp 50 / footer_text 200 / announcement 500。
 */

interface SettingsForm extends Record<string, string> {
  site_title: string
  site_description: string
  site_keywords: string
  icp: string
  footer_text: string
  announcement: string
}

interface FieldDef {
  key: keyof SettingsForm
  label: string
  hint: string
  max: number
  type: 'input' | 'textarea'
  rows?: number
  placeholder: string
}

const SETTINGS_FIELDS: FieldDef[] = [
  { key: 'site_title', label: '站点标题', hint: '显示在站点名称与浏览器标签页', max: 50, type: 'input', placeholder: '例如：LinBlog' },
  { key: 'site_description', label: '站点描述', hint: 'SEO description 与站点介绍', max: 200, type: 'textarea', rows: 3, placeholder: '一句话说明这个站点写些什么' },
  { key: 'site_keywords', label: 'SEO 关键词', hint: '多个关键词用英文逗号分隔', max: 200, type: 'input', placeholder: '博客,技术,随笔' },
  { key: 'icp', label: 'ICP 备案号', hint: '展示在站点页脚（可留空）', max: 50, type: 'input', placeholder: '京ICP备xxxxxxxx号' },
  { key: 'footer_text', label: '页脚文字', hint: '展示在站点页脚（可留空）', max: 200, type: 'textarea', rows: 2, placeholder: '© 2026 LinBlog' },
  { key: 'announcement', label: '站点公告', hint: '面向全站访客的公告（可留空）', max: 500, type: 'textarea', rows: 3, placeholder: '近期更新或活动告知' },
]

/** 分组渲染（视觉分组，不影响提交逻辑：仍全量提交） */
const SETTINGS_GROUPS: Array<{ title: string; desc: string; icon: Component; fields: Array<keyof SettingsForm> }> = [
  { title: '基础信息', desc: '站点名称与品牌展示，访客第一眼看到的内容', icon: OfficeBuilding, fields: ['site_title', 'site_description'] },
  { title: 'SEO 与搜索', desc: '影响搜索引擎收录与分享卡片的元信息', icon: Search, fields: ['site_keywords'] },
  { title: '备案与页脚', desc: '合规信息、页脚与全站公告', icon: Document, fields: ['icp', 'footer_text', 'announcement'] },
]

const fieldByKey = (key: keyof SettingsForm): FieldDef => SETTINGS_FIELDS.find((field) => field.key === key)!

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

/** 顶部预览用：标题留空时给个占位，避免预览区空洞 */
const previewTitle = computed(() => form.site_title.trim() || '未设置站点标题')
const previewDesc = computed(() => form.site_description.trim() || '未设置站点描述')
const filledCount = computed(() => SETTINGS_FIELDS.filter((field) => form[field.key].trim().length > 0).length)

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
  <div class="lb-set">
    <!-- 页头：标题 + 完成度 + 主行动 -->
    <header class="lb-set-head">
      <div class="lb-set-head-text">
        <h2 class="lb-set-title">系统设置</h2>
        <p class="lb-set-sub">站点元信息与展示配置，保存后立即对全站生效</p>
      </div>
      <div class="lb-set-head-actions">
        <span class="lb-set-progress">{{ filledCount }} / {{ SETTINGS_FIELDS.length }} 项已填</span>
        <el-button type="primary" round :loading="saving" data-testid="setting-save" @click="save">
          <el-icon v-if="!saving" class="lb-set-save-icon"><Check /></el-icon>
          保存设置
        </el-button>
      </div>
    </header>

    <el-alert v-if="errorMessage" type="warning" :closable="false" class="lb-set-alert">
      {{ errorMessage }}
      <el-button link @click="load">重试</el-button>
    </el-alert>

    <div v-loading="loading" class="lb-set-grid" data-testid="setting-form">
      <!-- 主列：分区设置卡 -->
      <div class="lb-set-main">
        <section v-for="(group, index) in SETTINGS_GROUPS" :key="group.title" class="lb-ed-card lb-set-card">
          <header class="lb-set-card-head">
            <span class="lb-set-card-icon" :class="`lb-set-card-icon--${index + 1}`" aria-hidden="true">
              <el-icon :size="18"><component :is="group.icon" /></el-icon>
            </span>
            <div class="lb-set-card-heading">
              <h3 class="lb-set-card-title">{{ group.title }}</h3>
              <p class="lb-set-card-desc">{{ group.desc }}</p>
            </div>
          </header>

          <div class="lb-set-fields">
            <div v-for="key in group.fields" :key="key" class="lb-set-field">
              <div class="lb-set-field-top">
                <label class="lb-set-label" :for="`setting-${key}`">{{ fieldByKey(key).label }}</label>
                <span class="lb-set-hint">{{ fieldByKey(key).hint }}</span>
              </div>
              <el-input
                v-if="fieldByKey(key).type === 'input'"
                :id="`setting-${key}`"
                v-model="form[key]"
                :maxlength="fieldByKey(key).max"
                show-word-limit
                :placeholder="fieldByKey(key).placeholder"
                :data-testid="`setting-${key}`"
              />
              <el-input
                v-else
                :id="`setting-${key}`"
                v-model="form[key]"
                type="textarea"
                :rows="fieldByKey(key).rows ?? 3"
                resize="vertical"
                :maxlength="fieldByKey(key).max"
                show-word-limit
                :placeholder="fieldByKey(key).placeholder"
                :data-testid="`setting-${key}`"
              />
            </div>
          </div>
        </section>
      </div>

      <!-- 侧列：实时预览 + 说明 -->
      <aside class="lb-set-aside">
        <section class="lb-ed-card lb-set-preview">
          <header class="lb-set-preview-head">
            <span class="lb-set-preview-badge"><el-icon :size="14"><View /></el-icon>预览</span>
            <span class="lb-set-preview-hint">站点页头效果</span>
          </header>
          <div class="lb-set-preview-body">
            <p class="lb-set-preview-title">{{ previewTitle }}</p>
            <p class="lb-set-preview-desc">{{ previewDesc }}</p>
            <div class="lb-set-preview-tags">
              <span
                v-for="tag in (form.site_keywords || '').split(',').map((t) => t.trim()).filter(Boolean).slice(0, 5)"
                :key="tag"
                class="lb-set-preview-tag"
              >{{ tag }}</span>
              <span v-if="!form.site_keywords.trim()" class="lb-set-preview-empty">尚无关键词</span>
            </div>
          </div>
          <footer class="lb-set-preview-foot">
            <span v-if="form.icp.trim()">{{ form.icp }}</span>
            <span v-else>未填写备案号</span>
          </footer>
        </section>

        <section class="lb-ed-card lb-set-tips">
          <header class="lb-set-tips-head">
            <el-icon :size="15"><InfoFilled /></el-icon>
            <h3>填写提示</h3>
          </header>
          <ul class="lb-set-tips-list">
            <li>留空的项表示清除该项，不会保留旧值。</li>
            <li>站点标题建议 12 字以内，过长会在页头折行。</li>
            <li>关键词之间用英文逗号分隔，中文逗号不会被识别。</li>
            <li>保存为全量提交，改动一次性生效。</li>
          </ul>
        </section>
      </aside>
    </div>
  </div>
</template>

<style scoped>
/* ---------- 页头 ---------- */
.lb-set-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  flex-wrap: wrap;
  margin-bottom: clamp(18px, 2vw, 26px);
}

.lb-set-title {
  margin: 0;
  font-family: var(--ed-font-display);
  font-size: clamp(26px, 2.6vw, 34px);
  font-weight: 600;
  letter-spacing: -0.02em;
  color: var(--ed-ink);
}

.lb-set-sub {
  margin: 6px 0 0;
  font-size: var(--ed-step-small);
  color: var(--ed-ink-soft);
}

.lb-set-head-actions {
  display: flex;
  align-items: center;
  gap: 14px;
}

.lb-set-progress {
  font-size: var(--ed-step-caption);
  color: var(--ed-ink-soft);
  font-variant-numeric: tabular-nums;
}

.lb-set-save-icon {
  margin-right: 4px;
}

.lb-set-alert {
  margin-bottom: 18px;
  border-radius: var(--ed-radius-md);
}

/* ---------- 两列栅格 ---------- */
.lb-set-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: clamp(16px, 1.8vw, 24px);
  align-items: start;
}

.lb-set-main {
  display: flex;
  flex-direction: column;
  gap: clamp(16px, 1.8vw, 22px);
  min-width: 0;
}

/* ---------- 分区卡片 ---------- */
.lb-set-card {
  padding: clamp(20px, 2.2vw, 28px);
}

.lb-set-card-head {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  padding-bottom: 18px;
  margin-bottom: 18px;
  border-bottom: 1px solid var(--ed-rule-soft);
}

.lb-set-card-icon {
  flex: none;
  width: 38px;
  height: 38px;
  display: grid;
  place-items: center;
  border-radius: var(--ed-radius-sm);
  color: #fff;
  background: var(--ed-accent);
}

.lb-set-card-icon--1 {
  background: var(--ed-accent);
}

.lb-set-card-icon--2 {
  background: color-mix(in srgb, var(--ed-accent) 72%, #2f7bd6);
}

.lb-set-card-icon--3 {
  background: color-mix(in srgb, var(--ed-accent) 55%, #6b6d76);
}

.lb-set-card-heading {
  min-width: 0;
}

.lb-set-card-title {
  margin: 0;
  font-size: var(--ed-step-h3);
  font-weight: 650;
  color: var(--ed-ink);
}

.lb-set-card-desc {
  margin: 3px 0 0;
  font-size: var(--ed-step-small);
  color: var(--ed-ink-soft);
}

/* ---------- 字段 ---------- */
.lb-set-fields {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.lb-set-field-top {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 7px;
}

.lb-set-label {
  font-size: var(--ed-step-small);
  font-weight: 600;
  color: var(--ed-ink);
}

.lb-set-hint {
  font-size: var(--ed-step-caption);
  color: var(--ed-ink-soft);
  text-align: right;
}

.lb-set-fields :deep(.el-input__wrapper),
.lb-set-fields :deep(.el-textarea__inner) {
  border-radius: var(--ed-radius-sm);
  box-shadow: 0 0 0 1px color-mix(in srgb, var(--ed-ink) 9%, transparent) inset;
  background: color-mix(in srgb, var(--ed-ink) 2.5%, var(--ed-card));
  transition: box-shadow var(--lb-dur-fast, 160ms) var(--lb-ease-out, ease);
}

.lb-set-fields :deep(.el-input__wrapper:hover),
.lb-set-fields :deep(.el-textarea__inner:hover) {
  box-shadow: 0 0 0 1px color-mix(in srgb, var(--ed-ink) 16%, transparent) inset;
}

.lb-set-fields :deep(.el-input__wrapper.is-focus),
.lb-set-fields :deep(.el-textarea__inner:focus) {
  box-shadow: 0 0 0 1.5px var(--ed-accent) inset;
  background: var(--ed-card);
}

/* ---------- 侧列 ---------- */
.lb-set-aside {
  position: sticky;
  top: 24px;
  display: flex;
  flex-direction: column;
  gap: clamp(16px, 1.8vw, 22px);
}

.lb-set-preview {
  overflow: hidden;
  padding: 0;
}

.lb-set-preview-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 14px 18px;
  border-bottom: 1px solid var(--ed-rule-soft);
}

.lb-set-preview-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--ed-step-caption);
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: var(--ed-accent);
}

.lb-set-preview-hint {
  font-size: var(--ed-step-caption);
  color: var(--ed-ink-soft);
}

.lb-set-preview-body {
  padding: 20px 18px;
}

.lb-set-preview-title {
  margin: 0;
  font-family: var(--ed-font-display);
  font-size: 22px;
  font-weight: 600;
  line-height: 1.25;
  color: var(--ed-ink);
  word-break: break-word;
}

.lb-set-preview-desc {
  margin: 8px 0 0;
  font-size: var(--ed-step-small);
  line-height: 1.6;
  color: var(--ed-ink-soft);
  display: -webkit-box;
  -webkit-line-clamp: 3;
  line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.lb-set-preview-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 14px;
}

.lb-set-preview-tag {
  padding: 3px 9px;
  border-radius: var(--ed-radius-pill, 999px);
  font-size: 11px;
  color: var(--ed-accent-ink, var(--ed-accent));
  background: color-mix(in srgb, var(--ed-accent) 12%, transparent);
}

.lb-set-preview-empty {
  font-size: 11px;
  color: var(--ed-ink-soft);
}

.lb-set-preview-foot {
  padding: 12px 18px;
  border-top: 1px solid var(--ed-rule-soft);
  font-size: var(--ed-step-caption);
  color: var(--ed-ink-soft);
  background: color-mix(in srgb, var(--ed-ink) 2%, transparent);
}

.lb-set-tips {
  padding: 18px;
}

.lb-set-tips-head {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--ed-ink-soft);
  margin-bottom: 12px;
}

.lb-set-tips-head h3 {
  margin: 0;
  font-size: var(--ed-step-small);
  font-weight: 650;
  color: var(--ed-ink);
  letter-spacing: 0;
}

.lb-set-tips-list {
  margin: 0;
  padding-left: 18px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: var(--ed-step-caption);
  line-height: 1.65;
  color: var(--ed-ink-soft);
}

.lb-set-tips-list li::marker {
  color: color-mix(in srgb, var(--ed-accent) 60%, transparent);
}

/* ---------- 响应式 ---------- */
@media (max-width: 1080px) {
  .lb-set-grid {
    grid-template-columns: 1fr;
  }

  .lb-set-aside {
    position: static;
    order: -1;
  }
}
</style>
