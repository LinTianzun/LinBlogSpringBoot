<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteComment, listComments, listPortalContents } from '@/api/portal'
import { ApiError } from '@/api/request'
import PaginationBar from '@/components/PaginationBar.vue'
import { useSessionStore } from '@/stores/session'
import { formatDateTime } from '@/utils/format'
import type { CommentTargetType, CommentViewDTO, ContentView } from '@/types/api'

/**
 * 评论管理（§5.5，comment.viewAll / comment.moderate）：
 * 后端无「全站评论分页」端点，按内容查评论 —— 从「内容选择」下拉挑一篇文章/图片
 * （listPortalContents 拉已发布列表，选中即带出 targetType + id）→ GET /portal/{t}/{id}/comments，
 * 亮卡片式两段评论列表 + 删除（DELETE /comments/{id}，comment.moderate）。
 * 保留高级手输（targetType + id）作为兜底。
 */

const session = useSessionStore()

const TARGET_OPTIONS: Array<{ label: string; value: CommentTargetType }> = [
  { label: '文章', value: 'article' },
  { label: '图片', value: 'image' },
  { label: '日记', value: 'diary' },
  { label: '留言墙', value: 'guestbook' },
]

const TARGET_LABEL: Record<CommentTargetType, string> = {
  article: '文章',
  image: '图片',
  diary: '日记',
  guestbook: '留言墙',
}

/** 当前查询目标 */
const targetType = ref<CommentTargetType>('article')
const targetId = ref<number | null>(null)

/** 内容下拉：合并文章 + 图片（已发布） */
interface ContentOption {
  value: number
  label: string
  targetType: CommentTargetType
}
const contentOptions = ref<ContentOption[]>([])
const contentLoading = ref(false)
const contentLoadError = ref('')
/** 下拉选中值 = `${targetType}:${id}` */
const selectedContentKey = ref<string>('')

const loading = ref(false)
const errorMessage = ref('')
const searched = ref(false)

const rootComments = ref<CommentViewDTO[]>([])
const replyComments = ref<CommentViewDTO[]>([])
const page = ref(1)
const pageSize = 10

const canModerate = computed(() => session.can('comment.moderate'))

/** 高级手输区开关 */
const showAdvanced = ref(false)

interface Grouped {
  root: CommentViewDTO
  replies: CommentViewDTO[]
}

const grouped = computed<Grouped[]>(() => {
  const byParent = new Map<number, CommentViewDTO[]>()
  for (const reply of replyComments.value) {
    const key = reply.parentId ?? 0
    const bucket = byParent.get(key)
    if (bucket) bucket.push(reply)
    else byParent.set(key, [reply])
  }
  return rootComments.value.map((root) => ({ root, replies: byParent.get(root.id) ?? [] }))
})

/** 当前选中内容的可读标题（用于空态/提示） */
const currentContentLabel = computed(() => {
  const opt = contentOptions.value.find((o) => o.value === targetId.value && o.targetType === targetType.value)
  return opt?.label ?? ''
})

function initial(name: string): string {
  const trimmed = (name ?? '').trim()
  return trimmed ? trimmed.slice(0, 1).toUpperCase() : '?'
}

/** 加载内容下拉候选（文章 + 图片，各取前 50 条） */
async function loadContentOptions(): Promise<void> {
  contentLoading.value = true
  contentLoadError.value = ''
  try {
    const [articles, images] = await Promise.all([
      listPortalContents({ contentType: 'article', page: 1, pageSize: 50 }),
      listPortalContents({ contentType: 'image', page: 1, pageSize: 50 }),
    ])
    const opts: ContentOption[] = []
    for (const item of articles.records as ContentView[]) {
      opts.push({ value: item.id, label: item.title, targetType: 'article' })
    }
    for (const item of images.records as ContentView[]) {
      opts.push({ value: item.id, label: item.title, targetType: 'image' })
    }
    contentOptions.value = opts
  } catch (error) {
    contentLoadError.value = error instanceof ApiError ? error.message : '加载内容列表失败'
    contentOptions.value = []
  } finally {
    contentLoading.value = false
  }
}

/** 下拉选中 → 带出 targetType + id 并立即查询 */
function onPickContent(key: string | null): void {
  selectedContentKey.value = key ?? ''
  if (!key) return
  const opt = contentOptions.value.find((o) => `${o.targetType}:${o.value}` === key)
  if (!opt) return
  targetType.value = opt.targetType
  targetId.value = opt.value
  page.value = 1
  void load()
}

async function search(): Promise<void> {
  if (targetId.value === null || targetId.value <= 0) {
    ElMessage.warning('请先选择一篇内容，或填写内容 id')
    return
  }
  page.value = 1
  await load()
}

async function load(): Promise<void> {
  if (targetId.value === null) return
  loading.value = true
  errorMessage.value = ''
  try {
    const thread = await listComments(targetType.value, targetId.value, page.value)
    rootComments.value = thread.rootComments
    replyComments.value = thread.replyComments
    searched.value = true
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '加载失败'
    rootComments.value = []
    replyComments.value = []
  } finally {
    loading.value = false
  }
}

async function confirmRemove(item: CommentViewDTO): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定删除 ${item.authorName} 的这条评论吗？一级评论会连带其回复。`, '删除评论', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await deleteComment(item.id)
    ElMessage.success('评论已删除')
    await load()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '删除失败')
  }
}

onMounted(loadContentOptions)
</script>

<template>
  <div class="lb-cm">
    <div class="lb-page-header">
      <div>
        <h2>评论管理</h2>
        <span class="lb-muted">选择一篇内容，查看与删除其评论（comment.viewAll）</span>
      </div>
      <el-button link size="small" data-testid="comment-toggle-advanced" @click="showAdvanced = !showAdvanced">
        {{ showAdvanced ? '收起高级输入' : '高级：手动输入' }}
      </el-button>
    </div>

    <!-- 选择工具栏 -->
    <el-card shadow="never" class="lb-cm-toolbar">
      <div class="lb-manage-filters">
        <el-select
          v-model="selectedContentKey"
          filterable
          :loading="contentLoading"
          :placeholder="contentLoading ? '内容加载中…' : '选择一篇内容（文章 / 图片）'"
          style="width: 320px; max-width: 100%"
          data-testid="comment-content-select"
          @update:model-value="(v: any) => onPickContent(v as string | null)"
        >
          <el-option
            v-for="opt in contentOptions"
            :key="`${opt.targetType}:${opt.value}`"
            :label="`[${TARGET_LABEL[opt.targetType]}] ${opt.label}`"
            :value="`${opt.targetType}:${opt.value}`"
          />
        </el-select>
        <el-button
          type="primary"
          size="small"
          :loading="loading"
          :disabled="selectedContentKey === ''"
          data-testid="comment-search"
          @click="search"
        >
          查询
        </el-button>
        <span v-if="contentLoadError" class="lb-cm-tool-error lb-ed-caption">
          {{ contentLoadError }}
          <el-button link size="small" @click="loadContentOptions">重试</el-button>
        </span>
      </div>

      <!-- 高级手输（兜底） -->
      <div v-if="showAdvanced" class="lb-manage-filters lb-cm-advanced">
        <el-select
          v-model="targetType"
          style="width: 140px"
          data-testid="comment-target-type"
        >
          <el-option v-for="option in TARGET_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
        <el-input-number
          v-model="targetId"
          placeholder="内容 id"
          :min="1"
          style="width: 160px"
          data-testid="comment-target-id"
        />
        <el-button type="primary" size="small" :loading="loading" data-testid="comment-search-manual" @click="search">
          按 id 查询
        </el-button>
      </div>
    </el-card>

    <el-alert v-if="errorMessage" type="warning" style="margin-bottom: 16px" :closable="false">
      {{ errorMessage }}
      <el-button link size="small" @click="load">重试</el-button>
    </el-alert>

    <!-- 加载骨架 -->
    <div v-if="loading" class="lb-cm-flow">
      <div v-for="n in 3" :key="n" class="lb-cm-card lb-ed-card">
        <div class="lb-cm-card-head">
          <span class="lb-ed-skel lb-cm-avatar-skel" />
          <span class="lb-ed-skel" style="width: 140px; height: 14px" />
        </div>
        <div class="lb-ed-skel" style="width: 90%; height: 13px; margin-top: 12px" />
        <div class="lb-ed-skel" style="width: 58%; height: 13px; margin-top: 8px" />
      </div>
    </div>

    <!-- 空态：未选择 -->
    <div v-else-if="!searched && targetId === null && !errorMessage" class="lb-ed-card lb-cm-empty">
      <span class="lb-cm-empty-dot" aria-hidden="true">
        <span class="lb-ed-dot" />
      </span>
      <h3 class="lb-cm-empty-title">先选择一篇内容</h3>
      <p class="lb-ed-caption lb-cm-empty-sub">从上方下拉选择文章或图片，即可查看并管理它的评论</p>
    </div>

    <!-- 空态：无评论 -->
    <div v-else-if="searched && grouped.length === 0 && !errorMessage" class="lb-ed-card lb-cm-empty">
      <span class="lb-cm-empty-dot" aria-hidden="true">
        <span class="lb-ed-dot" />
      </span>
      <h3 class="lb-cm-empty-title">暂无评论</h3>
      <p class="lb-ed-caption lb-cm-empty-sub">
        {{ currentContentLabel ? `「${currentContentLabel}」还没有可见评论` : '该内容还没有可见评论' }}
      </p>
    </div>

    <!-- 空态：错误（无内联重试时兜底） -->
    <div v-else-if="errorMessage && !loading" class="lb-ed-card lb-cm-empty">
      <span class="lb-cm-empty-dot lb-cm-empty-dot--error" aria-hidden="true">
        <span class="lb-ed-dot" />
      </span>
      <h3 class="lb-cm-empty-title">加载失败</h3>
      <p class="lb-ed-caption lb-cm-empty-sub">{{ errorMessage }}</p>
      <el-button type="primary" size="small" @click="load">重试</el-button>
    </div>

    <!-- 评论卡片流 -->
    <div v-else-if="grouped.length > 0" class="lb-cm-flow" data-testid="comment-list">
      <article v-for="group in grouped" :key="group.root.id" class="lb-cm-card lb-ed-card">
        <div class="lb-cm-card-head">
          <span class="lb-ed-avatar lb-cm-avatar">{{ initial(group.root.authorName) }}</span>
          <div class="lb-cm-card-meta">
            <span class="lb-cm-author">{{ group.root.authorName }}</span>
            <span class="lb-ed-caption">{{ formatDateTime(group.root.createdAt) }}</span>
          </div>
          <span v-if="group.replies.length > 0" class="lb-ed-pill lb-ed-pill--soft lb-cm-count">
            {{ group.replies.length }} 回复
          </span>
          <el-button
            v-if="canModerate"
            link
            size="small"
            type="danger"
            class="lb-cm-del"
            @click="confirmRemove(group.root)"
          >
            删除
          </el-button>
          <span v-else class="lb-ed-caption lb-cm-del">—</span>
        </div>

        <p class="lb-cm-body">{{ group.root.body }}</p>

        <div v-if="group.replies.length > 0" class="lb-cm-replies">
          <div v-for="reply in group.replies" :key="reply.id" class="lb-cm-reply">
            <span class="lb-ed-avatar lb-cm-avatar lb-cm-avatar--sm">{{ initial(reply.authorName) }}</span>
            <div class="lb-cm-reply-main">
              <div class="lb-cm-reply-head">
                <span class="lb-cm-author lb-cm-author--sm">{{ reply.authorName }}</span>
                <span v-if="reply.replyToUserName" class="lb-ed-caption">回复 @{{ reply.replyToUserName }}</span>
                <span class="lb-ed-caption">{{ formatDateTime(reply.createdAt) }}</span>
                <el-button
                  v-if="canModerate"
                  link
                  size="small"
                  type="danger"
                  class="lb-cm-reply-del"
                  @click="confirmRemove(reply)"
                >
                  删除
                </el-button>
                <span v-else class="lb-ed-caption lb-cm-reply-del">—</span>
              </div>
              <p class="lb-cm-reply-body">{{ reply.body }}</p>
            </div>
          </div>
        </div>
      </article>
    </div>

    <div v-if="grouped.length > 0" class="lb-manage-pagination">
      <PaginationBar v-model:page="page" :total="grouped.length" :page-size="pageSize" @update:page="load" />
    </div>
  </div>
</template>

<style scoped>
.lb-cm-toolbar {
  margin-bottom: 16px;
}
.lb-cm-toolbar :deep(.el-card__body) {
  padding: clamp(14px, 2vw, 20px);
}
.lb-cm-toolbar .lb-manage-filters {
  margin-bottom: 0;
}
.lb-cm-advanced {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--ed-rule-soft);
}
.lb-cm-tool-error {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--app-negative, #c0392b);
}

/* 评论卡片流 */
.lb-cm-flow {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.lb-cm-card {
  padding: clamp(16px, 2vw, 20px);
  overflow: visible;
}

.lb-cm-card-head {
  display: flex;
  align-items: center;
  gap: 12px;
}

.lb-cm-avatar {
  width: 38px;
  height: 38px;
  font-size: 0.9rem;
}
.lb-cm-avatar--sm {
  width: 28px;
  height: 28px;
  font-size: 0.72rem;
  background: color-mix(in srgb, var(--ed-accent) 70%, var(--ed-ink));
}
.lb-cm-avatar-skel {
  width: 38px;
  height: 38px;
  border-radius: var(--ed-radius-pill);
}

.lb-cm-card-meta {
  display: flex;
  flex-direction: column;
  min-width: 0;
  gap: 2px;
}

.lb-cm-author {
  font-weight: 600;
  color: var(--ed-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.lb-cm-author--sm {
  font-size: var(--ed-step-small);
}

.lb-cm-count {
  margin-left: auto;
}

.lb-cm-del {
  flex: none;
}

.lb-cm-body {
  margin: 14px 0 0;
  color: var(--ed-ink);
  white-space: pre-wrap;
  word-break: break-word;
  line-height: var(--ed-leading-body);
}

/* 回复：缩进小卡 */
.lb-cm-replies {
  margin-top: 16px;
  padding-left: 18px;
  border-left: 2px solid var(--ed-rule-soft);
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.lb-cm-reply {
  display: flex;
  gap: 10px;
  padding: 10px 12px;
  border-radius: var(--ed-radius-sm);
  background: color-mix(in srgb, var(--ed-ink) 4%, var(--ed-paper));
}

.lb-cm-reply-main {
  min-width: 0;
  flex: 1;
}

.lb-cm-reply-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.lb-cm-reply-del {
  margin-left: auto;
}

.lb-cm-reply-body {
  margin: 6px 0 0;
  color: var(--ed-ink-soft);
  white-space: pre-wrap;
  word-break: break-word;
  line-height: var(--ed-leading-body);
}

/* 亮卡片空态（禁 EP 插画，自写） */
.lb-cm-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: clamp(40px, 7vw, 64px) 24px;
  text-align: center;
}
.lb-cm-empty-dot {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: var(--ed-accent-soft);
  margin-bottom: 4px;
}
.lb-cm-empty-dot .lb-ed-dot {
  width: 12px;
  height: 12px;
}
.lb-cm-empty-dot--error {
  background: color-mix(in srgb, var(--app-negative, #c0392b) 12%, var(--ed-card));
}
.lb-cm-empty-dot--error .lb-ed-dot {
  background: var(--app-negative, #c0392b);
}
.lb-cm-empty-title {
  margin: 0;
  font-family: var(--ed-font-display);
  font-size: var(--ed-step-h3);
  font-weight: 500;
  color: var(--ed-ink);
}
.lb-cm-empty-sub {
  margin: 0;
  max-width: 42ch;
}

@media (max-width: 640px) {
  .lb-cm-card-head {
    flex-wrap: wrap;
  }
  .lb-cm-count {
    margin-left: 0;
  }
  .lb-cm-replies {
    padding-left: 12px;
  }
}
</style>
