<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  activateAiModel,
  aiTestRun,
  bindAiTemplate,
  createAiModel,
  createAiTemplate,
  deleteAiModel,
  deleteAiTemplate,
  getAiSettings,
  getAiUsage,
  listAiModels,
  listAiTemplates,
  toggleAiTemplate,
  updateAiModel,
  updateAiSettings,
  updateAiTemplate,
} from '@/api/ai'
import { ApiError } from '@/api/request'
import type { AiModelDTO, AiPromptTemplateDTO, AiSettingDTO, AiUsageSummaryDTO } from '@/types/api'

/**
 * AI 助手（§AI，system.setting）：模型接入 / 策略 / 提示词模板（含试跑）/ 用量。
 * 原则沿自原版：AI 只产出建议文本，是否采用永远由作者确认。
 */

const tab = ref<'models' | 'templates' | 'usage'>('models')

/* ---------- 模型接入 ---------- */
const models = ref<AiModelDTO[]>([])
const modelsLoading = ref(false)
const modelDialog = ref(false)
const modelEditingId = ref<number | null>(null)
const modelForm = reactive({
  name: '',
  baseUrl: '',
  apiKey: '',
  chatModel: '',
  imageModel: '',
  imageSize: '1344x768',
  timeoutMs: 20000,
  enabled: true,
  remark: '',
})

async function loadModels(): Promise<void> {
  modelsLoading.value = true
  try {
    models.value = await listAiModels()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加载失败')
  } finally {
    modelsLoading.value = false
  }
}

function openModelDialog(model?: AiModelDTO): void {
  modelEditingId.value = model?.id ?? null
  modelForm.name = model?.name ?? ''
  modelForm.baseUrl = model?.baseUrl ?? ''
  modelForm.apiKey = ''
  modelForm.chatModel = model?.chatModel ?? ''
  modelForm.imageModel = model?.imageModel ?? ''
  modelForm.imageSize = model?.imageSize ?? '1344x768'
  modelForm.timeoutMs = model?.timeoutMs ?? 20000
  modelForm.enabled = model?.enabled ?? true
  modelForm.remark = model?.remark ?? ''
  modelDialog.value = true
}

async function saveModel(): Promise<void> {
  try {
    if (modelEditingId.value === null) {
      await createAiModel({ ...modelForm })
    } else {
      await updateAiModel(modelEditingId.value, { ...modelForm })
    }
    ElMessage.success('已保存')
    modelDialog.value = false
    await loadModels()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '保存失败')
  }
}

async function activateModel(model: AiModelDTO): Promise<void> {
  try {
    await activateAiModel(model.id)
    ElMessage.success(`已将「${model.name}」设为默认模型`)
    await loadModels()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '操作失败')
  }
}

async function removeModel(model: AiModelDTO): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认删除模型「${model.name}」？`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteAiModel(model.id)
    await loadModels()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '删除失败')
  }
}

/* ---------- 策略 ---------- */
const settings = ref<AiSettingDTO | null>(null)
const settingsSaving = ref(false)

async function loadSettings(): Promise<void> {
  settings.value = await getAiSettings().catch(() => null)
}

async function saveSettings(): Promise<void> {
  if (!settings.value) return
  settingsSaving.value = true
  try {
    settings.value = await updateAiSettings({
      quotaEnabled: settings.value.quotaEnabled,
      dailyLimit: settings.value.dailyLimit,
      monthlyLimit: settings.value.monthlyLimit,
      superAdminUnlimited: settings.value.superAdminUnlimited,
      imageModelId: settings.value.imageModelId,
      removeWatermark: settings.value.removeWatermark,
      watermarkMode: settings.value.watermarkMode,
    })
    ElMessage.success('策略已保存')
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '保存失败')
  } finally {
    settingsSaving.value = false
  }
}

/* ---------- 提示词模板 + 试跑 ---------- */
const templates = ref<AiPromptTemplateDTO[]>([])
const templatesLoading = ref(false)
const templateDialog = ref(false)
const templateEditingId = ref<number | null>(null)
const templateForm = reactive({
  templateKey: '',
  featureKey: '',
  name: '',
  description: '',
  content: '',
  variables: '',
})

const FEATURES = [
  { key: 'suggest', label: '写作建议' },
  { key: 'report-review', label: '举报辅助' },
  { key: 'operations', label: '运营周报' },
]

async function loadTemplates(): Promise<void> {
  templatesLoading.value = true
  try {
    templates.value = await listAiTemplates()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加载失败')
  } finally {
    templatesLoading.value = false
  }
}

function openTemplateDialog(template?: AiPromptTemplateDTO): void {
  templateEditingId.value = template?.id ?? null
  templateForm.templateKey = template?.templateKey ?? ''
  templateForm.featureKey = template?.featureKey ?? ''
  templateForm.name = template?.name ?? ''
  templateForm.description = template?.description ?? ''
  templateForm.content = template?.content ?? ''
  templateForm.variables = template?.variables ?? ''
  templateDialog.value = true
}

async function saveTemplate(): Promise<void> {
  try {
    if (templateEditingId.value === null) {
      await createAiTemplate({ ...templateForm })
    } else {
      await updateAiTemplate(templateEditingId.value, { ...templateForm })
    }
    ElMessage.success('已保存')
    templateDialog.value = false
    await loadTemplates()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '保存失败')
  }
}

async function removeTemplate(template: AiPromptTemplateDTO): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认删除模板「${template.name}」？`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteAiTemplate(template.id)
    await loadTemplates()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '删除失败')
  }
}

async function toggleTemplate(template: AiPromptTemplateDTO): Promise<void> {
  try {
    await toggleAiTemplate(template.id, !template.enabled)
    await loadTemplates()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '操作失败')
  }
}

/** 功能绑定：把某个模板绑到功能（替代内置） */
const bindingSaving = ref('')
async function bindFeature(feature: string, templateKey: string): Promise<void> {
  bindingSaving.value = feature
  try {
    await bindAiTemplate(feature, templateKey)
    ElMessage.success('已绑定')
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '绑定失败')
  } finally {
    bindingSaving.value = ''
  }
}

/** 当前功能绑定的模板名（计算展示） */
const boundTemplate = computed(() => (feature: string): string => {
  const binding = templates.value.find((t) => t.featureKey === feature && t.enabled)
  return binding?.name ?? '内置提示词'
})

/* 试跑 */
const testRunPrompt = ref('')
const testRunVarsRaw = ref('')
const testRunResult = ref('')
const testRunRunning = ref(false)

async function runTest(): Promise<void> {
  let variables: Record<string, string> = {}
  const raw = testRunVarsRaw.value.trim()
  if (raw) {
    try {
      variables = JSON.parse(raw)
    } catch {
      ElMessage.error('变量需为 JSON 对象，如 {"title":"标题"}')
      return
    }
  }
  testRunRunning.value = true
  try {
    const result = await aiTestRun({ prompt: testRunPrompt.value, variables })
    testRunResult.value = `${result.output}\n\n—— ${result.modelName} · ${result.durationMs}ms`
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '试跑失败')
  } finally {
    testRunRunning.value = false
  }
}

function useTemplateForTest(template: AiPromptTemplateDTO): void {
  testRunPrompt.value = template.content
  tab.value = 'templates'
  ElMessage.info('已填入试跑框，可编辑变量后试跑')
}

/* ---------- 用量 ---------- */
const usage = ref<AiUsageSummaryDTO | null>(null)

async function loadUsage(): Promise<void> {
  usage.value = await getAiUsage().catch(() => null)
}

onMounted(() => {
  void loadModels()
  void loadSettings()
  void loadTemplates()
  void loadUsage()
})
</script>

<template>
  <div>
    <div class="lb-page-header">
      <h2>AI 助手</h2>
      <span class="lb-muted">模型接入 / 策略 / 提示词 / 用量——AI 只产出建议，落库由作者确认</span>
    </div>

    <el-tabs v-model="tab" class="lb-ai-tabs">
      <!-- ============ 模型接入 ============ -->
      <el-tab-pane label="模型接入" name="models">
        <div class="lb-ai-toolbar">
          <el-button type="primary" data-testid="ai-model-create" @click="openModelDialog()">新建模型</el-button>
        </div>
        <el-table v-loading="modelsLoading" :data="models" shadow="never">
          <el-table-column label="名称" min-width="140">
            <template #default="{ row }">
              <span class="lb-ai-name">{{ row.name }}</span>
              <el-tag v-if="row.isDefault" size="small" effect="light" class="lb-ai-tag">默认</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="baseUrl" label="接口地址" min-width="220" show-overflow-tooltip />
          <el-table-column prop="chatModel" label="模型名" min-width="140" />
          <el-table-column label="密钥" width="110">
            <template #default="{ row }">{{ row.apiKeyMasked }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
                {{ row.enabled ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <el-button link size="small" :disabled="row.isDefault" @click="activateModel(row as AiModelDTO)">设为默认</el-button>
              <el-button link size="small" @click="openModelDialog(row as AiModelDTO)">编辑</el-button>
              <el-button link size="small" type="danger" @click="removeModel(row as AiModelDTO)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <!-- 模型编辑弹窗 -->
        <el-dialog
          v-model="modelDialog"
          :title="modelEditingId === null ? '新建模型' : '编辑模型'"
          width="520px"
        >
          <el-form label-position="top">
            <el-form-item label="显示名称" required>
              <el-input v-model="modelForm.name" maxlength="64" placeholder="如 主力模型" />
            </el-form-item>
            <el-form-item label="接口地址（OpenAI 兼容 base_url）" required>
              <el-input v-model="modelForm.baseUrl" placeholder="https://api.openai.com/v1" />
            </el-form-item>
            <el-form-item label="API Key" :required="modelEditingId === null">
              <el-input
                v-model="modelForm.apiKey"
                :placeholder="modelEditingId === null ? 'sk-...' : '留空则保持不变'"
                show-password
              />
            </el-form-item>
            <el-form-item label="模型名" required>
              <el-input v-model="modelForm.chatModel" placeholder="如 gpt-4o-mini" />
            </el-form-item>
            <el-form-item label="图片模型名（选填，配了才能用 AI 生图）">
              <el-input v-model="modelForm.imageModel" placeholder="如 cogview-3-flash" />
            </el-form-item>
            <el-form-item label="生图尺寸">
              <el-input v-model="modelForm.imageSize" placeholder="1344x768" />
            </el-form-item>
            <el-form-item label="超时（毫秒）">
              <el-input-number v-model="modelForm.timeoutMs" :min="1000" :max="120000" :step="1000" />
            </el-form-item>
            <el-form-item label="备注">
              <el-input v-model="modelForm.remark" maxlength="255" />
            </el-form-item>
            <el-form-item label="启用">
              <el-switch v-model="modelForm.enabled" />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="modelDialog = false">取消</el-button>
            <el-button type="primary" data-testid="ai-model-save" @click="saveModel">保存</el-button>
          </template>
        </el-dialog>
      </el-tab-pane>

      <!-- ============ 提示词模板 + 试跑 ============ -->
      <el-tab-pane label="提示词模板" name="templates">
        <div class="lb-ai-toolbar">
          <el-button type="primary" @click="openTemplateDialog()">新建模板</el-button>
          <span class="lb-muted">功能未绑定模板或模板停用时，回退到内置提示词。</span>
        </div>

        <div class="lb-ai-bindings">
          <div v-for="feature in FEATURES" :key="feature.key" class="lb-ai-binding-row">
            <span class="lb-ai-binding-label">{{ feature.label }}</span>
            <span class="lb-ai-binding-current">当前：{{ boundTemplate(feature.key) }}</span>
            <el-select
              :model-value="''"
              placeholder="绑定模板…"
              size="small"
              style="width: 200px"
              filterable
              @change="(key: string) => bindFeature(feature.key, key)"
            >
              <el-option
                v-for="t in templates.filter((t) => t.enabled)"
                :key="t.id"
                :label="t.name"
                :value="t.templateKey"
              />
            </el-select>
          </div>
        </div>

        <el-table v-loading="templatesLoading" :data="templates" shadow="never">
          <el-table-column prop="name" label="名称" min-width="120" />
          <el-table-column prop="templateKey" label="标识" min-width="120" />
          <el-table-column prop="featureKey" label="所属功能" min-width="110" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
                {{ row.enabled ? '启用' : '回退内置' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="230" fixed="right">
            <template #default="{ row }">
              <el-button link size="small" @click="useTemplateForTest(row as AiPromptTemplateDTO)">试跑</el-button>
              <el-button link size="small" @click="openTemplateDialog(row as AiPromptTemplateDTO)">编辑</el-button>
              <el-button link size="small" @click="toggleTemplate(row as AiPromptTemplateDTO)">{{ row.enabled ? '停用' : '启用' }}</el-button>
              <el-button link size="small" type="danger" @click="removeTemplate(row as AiPromptTemplateDTO)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <!-- 模板编辑弹窗 -->
        <el-dialog
          v-model="templateDialog"
          :title="templateEditingId === null ? '新建模板' : '编辑模板'"
          width="640px"
        >
          <el-form label-position="top">
            <el-form-item label="模板标识" required>
              <el-input v-model="templateForm.templateKey" maxlength="64" placeholder="如 suggest-custom-1" />
            </el-form-item>
            <el-form-item label="所属功能">
              <el-select v-model="templateForm.featureKey" placeholder="选填" clearable style="width: 100%">
                <el-option v-for="f in FEATURES" :key="f.key" :label="f.label" :value="f.key" />
              </el-select>
            </el-form-item>
            <el-form-item label="名称" required>
              <el-input v-model="templateForm.name" maxlength="64" />
            </el-form-item>
            <el-form-item label="用途说明">
              <el-input v-model="templateForm.description" maxlength="255" />
            </el-form-item>
            <el-form-item label="提示词正文（支持 {{变量}} 占位）" required>
              <el-input v-model="templateForm.content" type="textarea" :autosize="{ minRows: 6, maxRows: 16 }" />
            </el-form-item>
            <el-form-item label="变量说明">
              <el-input v-model="templateForm.variables" maxlength="500" placeholder="{{title}} 文章标题 …" />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="templateDialog = false">取消</el-button>
            <el-button type="primary" @click="saveTemplate">保存</el-button>
          </template>
        </el-dialog>

        <el-card shadow="never" class="lb-ai-testrun">
          <template #header><span>提示词试跑</span></template>
          <el-input
            v-model="testRunPrompt"
            type="textarea"
            :autosize="{ minRows: 4, maxRows: 10 }"
            placeholder="输入要验证的提示词（支持 {{变量}} 占位）"
          />
          <el-input
            v-model="testRunVarsRaw"
            class="lb-ai-testrun-vars"
            placeholder='变量 JSON，如 {"title":"标题","content":"正文"}'
          />
          <div class="lb-ai-testrun-ops">
            <el-button type="primary" :loading="testRunRunning" @click="runTest">试跑</el-button>
          </div>
          <pre v-if="testRunResult" class="lb-ai-testrun-out">{{ testRunResult }}</pre>
        </el-card>
      </el-tab-pane>

      <!-- ============ 策略 + 用量 ============ -->
      <el-tab-pane label="策略与用量" name="usage">
        <el-card v-if="settings" shadow="never" class="lb-ai-settings">
          <template #header><span>调用策略</span></template>
          <div class="lb-ai-setting-row">
            <span>启用次数限制</span>
            <el-switch v-model="settings.quotaEnabled" />
          </div>
          <div class="lb-ai-setting-row">
            <span>每用户每日上限（0 不限）</span>
            <el-input-number v-model="settings.dailyLimit" :min="0" :max="9999" />
          </div>
          <div class="lb-ai-setting-row">
            <span>每用户每月上限（0 不限）</span>
            <el-input-number v-model="settings.monthlyLimit" :min="0" :max="99999" />
          </div>
          <div class="lb-ai-setting-row">
            <span>超级管理员不限次数</span>
            <el-switch v-model="settings.superAdminUnlimited" />
          </div>
          <div class="lb-ai-setting-row">
            <span>配图模型</span>
            <el-select
              v-model="settings.imageModelId"
              clearable
              placeholder="跟随默认模型"
              style="width: 240px"
            >
              <el-option
                v-for="m in models.filter((m) => m.imageModel)"
                :key="m.id"
                :label="m.name"
                :value="m.id"
              />
            </el-select>
          </div>
          <div class="lb-ai-setting-row">
            <span>裁除成图右下角「AI生成」标识</span>
            <el-switch v-model="settings.removeWatermark" />
          </div>
          <div class="lb-ai-setting-row">
            <span>标识去除方式</span>
            <el-radio-group v-model="settings.watermarkMode">
              <el-radio value="crop">裁掉一角</el-radio>
              <el-radio value="crop-resize">裁后缩放回原尺寸</el-radio>
            </el-radio-group>
          </div>
          <div class="lb-ai-setting-ops">
            <el-button type="primary" :loading="settingsSaving" @click="saveSettings">保存策略</el-button>
          </div>
        </el-card>

        <el-card v-if="usage" shadow="never" class="lb-ai-usage">
          <template #header><span>最近 30 天用量</span></template>
          <div class="lb-ai-usage-grid">
            <div class="lb-ai-usage-cell"><b>{{ usage.total }}</b><span>总调用</span></div>
            <div class="lb-ai-usage-cell"><b>{{ usage.success }}</b><span>成功</span></div>
            <div class="lb-ai-usage-cell"><b>{{ usage.successRate }}%</b><span>成功率</span></div>
            <div class="lb-ai-usage-cell"><b>{{ usage.avgDurationMs }}ms</b><span>平均耗时</span></div>
          </div>
          <p class="lb-muted">
            按功能：{{ Object.entries(usage.byFeature).map(([k, v]) => `${k} ${v}`).join(' · ') || '暂无调用' }}
          </p>
        </el-card>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.lb-ai-tabs {
  margin-top: 4px;
}

.lb-ai-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}

.lb-ai-name {
  font-weight: 600;
  margin-right: 6px;
}

.lb-ai-tag {
  margin-left: 4px;
}

.lb-ai-bindings {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 16px;
}

.lb-ai-binding-row {
  display: flex;
  gap: 12px;
  align-items: baseline;
  font-size: var(--ed-step-small);
}

.lb-ai-binding-label {
  font-weight: 600;
  color: var(--ed-ink);
  min-width: 72px;
}

.lb-ai-binding-current {
  color: var(--ed-ink-soft);
}

.lb-ai-testrun {
  margin-top: 18px;
}

.lb-ai-testrun-vars {
  margin-top: 10px;
}

.lb-ai-testrun-ops {
  margin-top: 10px;
}

.lb-ai-testrun-out {
  margin: 14px 0 0;
  padding: 12px 14px;
  background: var(--ed-paper);
  border-radius: var(--ed-radius-sm);
  white-space: pre-wrap;
  font-size: var(--ed-step-small);
  line-height: 1.7;
}

.lb-ai-settings,
.lb-ai-usage {
  margin-bottom: 16px;
}

.lb-ai-setting-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 8px 0;
}

.lb-ai-setting-ops {
  margin-top: 8px;
}

.lb-ai-usage-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
  gap: 12px;
  margin-bottom: 10px;
}

.lb-ai-usage-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.lb-ai-usage-cell b {
  font-size: var(--ed-step-h3);
}
</style>
