<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import {
  createComment,
  listComments,
  praiseComment,
} from '@/api/portal'
import { ApiError } from '@/api/request'
import { useSessionStore } from '@/stores/session'
import { formatRelative } from '@/utils/format'
import type { CommentListDTO, CommentTargetType, ReplyCommentDTO, RootCommentDTO } from '@/types/api'

/**
 * 两段式评论树（§3.3/§5.5）：
 * - 一级评论 + 楼中楼（replyComments 按 parentId 归组到对应一级）；
 * - 回复一级：parent_id=root.id、replyToCommentId 空；回复楼中楼：parent_id=root.id、replyToCommentId=reply.id；
 * - 回复时定位：行内打开回复框并滚动到目标评论；
 * - 点赞 toggle：POST /comments/{id}/praise → {active,count} 本地更新。
 */

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
  root: RootCommentDTO
  replies: ReplyCommentDTO[]
}

const grouped = computed<RootWithReplies[]>(() => {
  const list = data.value
  if (!list) return []
  const byParent = new Map<number, ReplyCommentDTO[]>()
  for (const reply of list.replyComments) {
    const bucket = byParent.get(reply.parentId)
    if (bucket) bucket.push(reply)
    else byParent.set(reply.parentId, [reply])
  }
  return list.rootComments.map((root) => ({ root, replies: byParent.get(root.id) ?? [] }))
})

const totalLabel = computed(() => (data.value ? `${data.value.total} 条评论` : ''))

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

function openReply(rootId: number, reply: ReplyCommentDTO | null): void {
  if (!session.isLoggedIn) return
  replyTarget.value = {
    rootId,
    replyToCommentId: reply?.id ?? null,
    nickname: reply ? reply.authorNickname : (grouped.value.find((item) => item.root.id === rootId)?.root.authorNickname ?? ''),
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
      parent_id: replyTarget.value.rootId,
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

async function togglePraise(item: RootCommentDTO | ReplyCommentDTO): Promise<void> {
  if (!session.isLoggedIn) return
  try {
    const result = await praiseComment(item.id)
    item.praised = result.active
    item.praiseCount = result.count
  } catch {
    // 点赞失败不打断浏览
  }
}

function commentDomId(id: number): string {
  return `comment-${id}`
}

void load()
</script>

<template>
  <section class="lb-comment-tree">
    <n-spin :show="loading">
      <n-h4 v-if="totalLabel">{{ totalLabel }}</n-h4>

      <n-alert v-if="loadError" type="error" closable @close="loadError = ''">
        {{ loadError }}
      </n-alert>

      <n-empty v-if="!loading && grouped.length === 0 && !loadError" description="还没有评论，来抢沙发～" />

      <n-space vertical size="large">
        <div
          v-for="item in grouped"
          :id="commentDomId(item.root.id)"
          :key="item.root.id"
          class="lb-comment-root"
        >
          <div class="lb-comment-main">
            <n-avatar round size="small">{{ item.root.authorNickname.slice(0, 1) }}</n-avatar>
            <div class="lb-comment-body">
              <div class="lb-comment-meta">
                <span class="lb-comment-author">{{ item.root.authorNickname }}</span>
                <span class="lb-muted">{{ formatRelative(item.root.createdAt) }}</span>
              </div>
              <p class="lb-comment-text">{{ item.root.body }}</p>
              <n-space size="small">
                <n-button
                  quaternary
                  size="tiny"
                  :type="item.root.praised ? 'primary' : 'default'"
                  @click="togglePraise(item.root)"
                >
                  👍 {{ item.root.praiseCount }}
                </n-button>
                <n-button quaternary size="tiny" @click="openReply(item.root.id, null)">回复</n-button>
              </n-space>
            </div>
          </div>

          <div v-if="item.replies.length > 0" class="lb-comment-replies">
            <div
              v-for="reply in item.replies"
              :id="commentDomId(reply.id)"
              :key="reply.id"
              class="lb-comment-reply"
            >
              <n-avatar round size="small">{{ reply.authorNickname.slice(0, 1) }}</n-avatar>
              <div class="lb-comment-body">
                <div class="lb-comment-meta">
                  <span class="lb-comment-author">{{ reply.authorNickname }}</span>
                  <span v-if="reply.replyToNickname" class="lb-muted">回复 @{{ reply.replyToNickname }}</span>
                  <span class="lb-muted">{{ formatRelative(reply.createdAt) }}</span>
                </div>
                <p class="lb-comment-text">{{ reply.body }}</p>
                <n-space size="small">
                  <n-button
                    quaternary
                    size="tiny"
                    :type="reply.praised ? 'primary' : 'default'"
                    @click="togglePraise(reply)"
                  >
                    👍 {{ reply.praiseCount }}
                  </n-button>
                  <n-button quaternary size="tiny" @click="openReply(item.root.id, reply)">回复</n-button>
                </n-space>
              </div>
            </div>
          </div>
        </div>
      </n-space>

      <!-- 行内回复框：锚定在被回复评论下 -->
      <n-card v-if="replyTarget" size="small" class="lb-reply-card">
        <template #header>
          回复 @{{ replyTarget.nickname }}
        </template>
        <n-input
          v-model:value="replyBody"
          type="textarea"
          maxlength="2000"
          show-count
          :rows="3"
          placeholder="写下你的回复（≤2000 字）"
        />
        <template #action>
          <n-space justify="end">
            <n-button size="small" @click="cancelReply">取消</n-button>
            <n-button size="small" type="primary" :loading="submitting" @click="submitReply">发送</n-button>
          </n-space>
        </template>
      </n-card>
    </n-spin>
  </section>
</template>

<style scoped>
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

.lb-reply-card {
  margin-top: 16px;
}
</style>
