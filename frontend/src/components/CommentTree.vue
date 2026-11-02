<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import {
  createComment,
  deleteComment,
  listComments,
  praiseComment,
  reportComment,
} from '@/api/portal'
import { ApiError } from '@/api/request'
import { useSessionStore } from '@/stores/session'
import { formatRelative } from '@/utils/format'
import type { CommentListDTO, CommentTargetType, CommentViewDTO, ReportReason } from '@/types/api'

/**
 * 两段式评论树（§3.3/§5.5，element-plus 版）：
 * - 一级评论 + 楼中楼（replyComments 按 parentId 归组到对应一级）；
 * - 回复一级：parentId=root.id、replyToCommentId 空；回复楼中楼：parentId=root.id、replyToCommentId=reply.id；
 * - 回复时定位：行内打开回复框并滚动到目标评论；
 * - 点赞 toggle：POST /comments/{id}/praise → {active,count} 本地更新；
 * - 举报：POST /comments/{id}/report（reason 白名单，detail≤200）；删除：作者或 comment.moderate。
 */

const REASON_OPTIONS: Array<{ label: string; value: ReportReason }> = [
  { label: '垃圾广告', value: 'spam' },
  { label: '人身攻击', value: 'abuse' },
  { label: '色情低俗', value: 'porn' },
  { label: '违法违规', value: 'illegal' },
  { label: '虚假信息', value: 'misinformation' },
  { label: '其他', value: 'other' },
]

const props = defineProps<{
  targetType: CommentTargetType
  targetId: number
}>()

const session = useSessionStore()

const data = ref<CommentListDTO | null>(null)
const loading = ref(false)
const loadError = ref('')

const replyTarget = ref<{ rootId: number; replyToCommentId: number | null; nickname: string } | null>(null)
const replyBody = ref('')
const submitting = ref(false)

interface RootWithReplies {
  root: CommentViewDTO
  replies: CommentViewDTO[]
}

const grouped = computed<RootWithReplies[]>(() => {
  const list = data.value
  if (!list) return []
  const byParent = new Map<number, CommentViewDTO[]>()
  for (const reply of list.replyComments) {
    const bucket = byParent.get(reply.parentId ?? 0)
    if (bucket) bucket.push(reply)
    else byParent.set(reply.parentId ?? 0, [reply])
  }
  return list.rootComments.map((root) => ({ root, replies: byParent.get(root.id) ?? [] }))
})

const totalLabel = computed(() => {
  const list = data.value
  if (!list) return ''
  return `${list.rootComments.length + list.replyComments.length} 条评论`
})

async function load(): Promise<void> {
  loading.value = true
  loadError.value = ''
  try {
    data.value = await listComments(props.targetType, props.targetId, 1)
  } catch (error) {
    loadError.value = error instanceof ApiError ? error.message : '评论加载失败'
  } finally {
    loading.value = false
  }
}

function openReply(rootId: number, reply: CommentViewDTO | null): void {
  if (!session.isLoggedIn) return
  replyTarget.value = {
    rootId,
    replyToCommentId: reply?.id ?? null,
    nickname: reply ? reply.authorName : (grouped.value.find((item) => item.root.id === rootId)?.root.authorName ?? ''),
  }
  void nextTick(() => {
    document.getElementById(commentDomId(replyTarget.value?.rootId ?? 0))?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  })
}

function cancelReply(): void {
  replyTarget.value = null
  replyBody.value = ''
}

async function submitReply(): Promise<void> {
  if (!replyTarget.value || replyBody.value.trim().length === 0) return
  submitting.value = true
  try {
    await createComment({
      targetType: props.targetType,
      targetId: props.targetId,
      parentId: replyTarget.value.rootId,
      replyToCommentId: replyTarget.value.replyToCommentId,
      body: replyBody.value.trim(),
    })
    replyTarget.value = null
    replyBody.value = ''
    await load()
  } catch (error) {
    loadError.value = error instanceof ApiError ? error.message : '回复失败'
  } finally {
    submitting.value = false
  }
}

async function togglePraise(item: CommentViewDTO): Promise<void> {
  if (!session.isLoggedIn) return
  try {
    const result = await praiseComment(item.id)
    item.liked = result.active
    item.praiseCount = result.count
  } catch {
    // 点赞失败不打断浏览
  }
}

/* ---------- 举报（登录）与删除（作者或 comment.moderate） ---------- */

const reportTarget = ref<CommentViewDTO | null>(null)
const reportReason = ref<ReportReason | undefined>(undefined)
const reportDetail = ref('')
const reportError = ref('')
const reporting = ref(false)

function openReport(item: CommentViewDTO): void {
  if (!session.isLoggedIn) return
  reportTarget.value = item
  reportReason.value = undefined
  reportDetail.value = ''
  reportError.value = ''
}

function cancelReport(): void {
  reportTarget.value = null
}

async function submitReport(): Promise<void> {
  const target = reportTarget.value
  if (!target || !reportReason.value) {
    reportError.value = '请选择举报原因'
    return
  }
  reporting.value = true
  reportError.value = ''
  try {
    await reportComment(target.id, {
      reason: reportReason.value,
      detail: reportDetail.value.trim() || undefined,
    })
    reportTarget.value = null
  } catch (error) {
    reportError.value = error instanceof ApiError ? error.message : '举报提交失败'
  } finally {
    reporting.value = false
  }
}

const canModerate = computed(() => session.can('comment.moderate'))

function canRemove(item: CommentViewDTO): boolean {
  return canModerate.value || session.user?.id === item.authorId
}

async function removeComment(item: CommentViewDTO): Promise<void> {
  try {
    await deleteComment(item.id)
    await load()
  } catch (error) {
    loadError.value = error instanceof ApiError ? error.message : '删除失败'
  }
}

function commentDomId(id: number): string {
  return `comment-${id}`
}

void load()
</script>

<template>
  <section class="lb-comment-tree">
    <h4 v-if="totalLabel" class="lb-comment-total">{{ totalLabel }}</h4>

    <el-alert v-if="loadError" type="error" :title="loadError" closable @close="loadError = ''" />

    <el-skeleton v-if="loading" :rows="3" animated />

    <el-empty v-if="!loading && grouped.length === 0 && !loadError" description="还没有评论，来抢沙发～" />

    <div v-if="!loading" class="lb-comment-list">
      <div
        v-for="item in grouped"
        :id="commentDomId(item.root.id)"
        :key="item.root.id"
        class="lb-comment-root"
      >
        <div class="lb-comment-main">
          <el-avatar :size="26">{{ item.root.authorName.slice(0, 1) }}</el-avatar>
          <div class="lb-comment-body">
            <div class="lb-comment-meta">
              <span class="lb-comment-author">{{ item.root.authorName }}</span>
              <span class="lb-muted">{{ formatRelative(item.root.createdAt) }}</span>
            </div>
            <p class="lb-comment-text">{{ item.root.body }}</p>
            <div class="lb-comment-actions">
              <el-button
                link
                size="small"
                :type="item.root.liked ? 'primary' : 'default'"
                @click="togglePraise(item.root)"
              >
                👍 {{ item.root.praiseCount }}
              </el-button>
              <el-button link size="small" @click="openReply(item.root.id, null)">回复</el-button>
              <el-button v-if="session.isLoggedIn" link size="small" @click="openReport(item.root)">举报</el-button>
              <el-popconfirm
                v-if="canRemove(item.root)"
                title="确定删除这条评论吗？"
                @confirm="removeComment(item.root)"
              >
                <template #reference>
                  <el-button link size="small" type="danger">删除</el-button>
                </template>
              </el-popconfirm>
            </div>
          </div>
        </div>

        <div v-if="item.replies.length > 0" class="lb-comment-replies">
          <div
            v-for="reply in item.replies"
            :id="commentDomId(reply.id)"
            :key="reply.id"
            class="lb-comment-reply"
          >
            <el-avatar :size="22">{{ reply.authorName.slice(0, 1) }}</el-avatar>
            <div class="lb-comment-body">
              <div class="lb-comment-meta">
                <span class="lb-comment-author">{{ reply.authorName }}</span>
                <span v-if="reply.replyToUserName" class="lb-muted">回复 @{{ reply.replyToUserName }}</span>
                <span class="lb-muted">{{ formatRelative(reply.createdAt) }}</span>
              </div>
              <p class="lb-comment-text">{{ reply.body }}</p>
              <div class="lb-comment-actions">
                <el-button
                  link
                  size="small"
                  :type="reply.liked ? 'primary' : 'default'"
                  @click="togglePraise(reply)"
                >
                  👍 {{ reply.praiseCount }}
                </el-button>
                <el-button link size="small" @click="openReply(item.root.id, reply)">回复</el-button>
                <el-button v-if="session.isLoggedIn" link size="small" @click="openReport(reply)">举报</el-button>
                <el-popconfirm
                  v-if="canRemove(reply)"
                  title="确定删除这条回复吗？"
                  @confirm="removeComment(reply)"
                >
                  <template #reference>
                    <el-button link size="small" type="danger">删除</el-button>
                  </template>
                </el-popconfirm>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 行内回复框：锚定在被回复评论下 -->
    <el-card v-if="replyTarget" shadow="never" class="lb-reply-card">
      <template #header>
        回复 @{{ replyTarget.nickname }}
      </template>
      <el-input
        v-model="replyBody"
        type="textarea"
        maxlength="2000"
        show-word-limit
        :rows="3"
        placeholder="写下你的回复（≤2000 字）"
      />
      <template #footer>
        <div class="lb-reply-actions">
          <el-button size="small" @click="cancelReply">取消</el-button>
          <el-button size="small" type="primary" :loading="submitting" @click="submitReply">发送</el-button>
        </div>
      </template>
    </el-card>

    <!-- 举报弹窗：原因必选（白名单）+ 补充说明选填（≤200） -->
    <el-dialog
      :model-value="reportTarget !== null"
      title="举报评论"
      width="420px"
      @update:model-value="(visible: boolean) => { if (!visible) cancelReport() }"
    >
      <p v-if="reportTarget" class="lb-muted lb-report-target">
        举报对象：{{ reportTarget.authorName }}：{{ reportTarget.body.slice(0, 60) }}
      </p>
      <el-radio-group v-model="reportReason">
        <div class="lb-report-reasons">
          <el-radio v-for="option in REASON_OPTIONS" :key="option.value" :value="option.value">
            {{ option.label }}
          </el-radio>
        </div>
      </el-radio-group>
      <el-input
        v-model="reportDetail"
        class="lb-report-detail"
        type="textarea"
        maxlength="200"
        show-word-limit
        :rows="2"
        placeholder="补充说明（选填，≤200 字）"
      />
      <p v-if="reportError" class="lb-report-error">{{ reportError }}</p>
      <template #footer>
        <div class="lb-reply-actions">
          <el-button size="small" @click="cancelReport">取消</el-button>
          <el-button size="small" type="primary" :loading="reporting" @click="submitReport">提交举报</el-button>
        </div>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.lb-comment-total {
  margin: 0 0 12px;
  font-size: 15px;
  color: var(--lb-text);
}

.lb-comment-list {
  display: flex;
  flex-direction: column;
}

.lb-comment-root {
  padding-bottom: 8px;
  border-bottom: 1px dashed var(--lb-border);
}

.lb-comment-main,
.lb-comment-reply {
  display: flex;
  gap: 10px;
}

.lb-comment-replies {
  margin: 10px 0 0 30px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.lb-comment-body {
  flex: 1;
  min-width: 0;
}

.lb-comment-meta {
  display: flex;
  gap: 8px;
  align-items: baseline;
}

.lb-comment-author {
  font-weight: 600;
  font-size: 13px;
}

.lb-comment-text {
  margin: 4px 0;
  white-space: pre-wrap;
  word-break: break-word;
}

.lb-comment-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.lb-reply-card {
  margin-top: 16px;
}

.lb-reply-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.lb-report-target {
  margin: 0 0 12px;
  font-size: 12px;
}

.lb-report-detail {
  margin-top: 12px;
}

.lb-report-error {
  margin: 8px 0 0;
  color: var(--lb-danger);
  font-size: 12px;
}
</style>
