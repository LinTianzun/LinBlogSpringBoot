<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { createGuestbookEntry, deleteGuestbookEntry, getGuestbook } from '@/api/portal'
import { ApiError } from '@/api/request'
import { useSessionStore } from '@/stores/session'
import { formatRelative } from '@/utils/format'
import type { GuestbookEntryDTO } from '@/types/api'

/**
 * 留言墙（§3.4 / §5.6）：GET /guestbook 全量一级 + 两级回复；
 * 新留言 POST /guestbook {body}；回复 POST /guestbook {body, replyToId}；
 * 删除 DELETE /guestbook/{id}（作者本人或 message.wallModerate）。
 * 端点失败 → 错误态降级，不白屏。
 */

const session = useSessionStore()

const entries = ref<GuestbookEntryDTO[]>([])
const loading = ref(false)
const errorMessage = ref('')

const newBody = ref('')
const posting = ref(false)

/** replyToId → 回复输入框打开在哪条留言/回复下 */
const replyTo = ref<{ id: number; nickname: string; body: string } | null>(null)
const replyBody = ref('')
const replySubmitting = ref(false)

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    entries.value = await getGuestbook()
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '留言加载失败'
    entries.value = []
  } finally {
    loading.value = false
  }
}

async function submitNew(): Promise<void> {
  const body = newBody.value.trim()
  if (body.length === 0) return
  posting.value = true
  try {
    await createGuestbookEntry(body)
    newBody.value = ''
    await load()
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '留言失败'
  } finally {
    posting.value = false
  }
}

function openReply(entry: GuestbookEntryDTO, replyId?: number, nickname?: string): void {
  if (!session.isLoggedIn) return
  replyTo.value = {
    id: replyId ?? entry.id,
    nickname: nickname ?? entry.userName,
    body: `${entry.userName}：${entry.body.slice(0, 40)}`,
  }
  replyBody.value = ''
}

function cancelReply(): void {
  replyTo.value = null
  replyBody.value = ''
}

async function submitReply(): Promise<void> {
  if (!replyTo.value) return
  const body = replyBody.value.trim()
  if (body.length === 0) return
  replySubmitting.value = true
  try {
    await createGuestbookEntry(body, replyTo.value.id)
    cancelReply()
    await load()
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '回复失败'
  } finally {
    replySubmitting.value = false
  }
}

const canModerateWall = computed(() => session.can('message.wallModerate'))

function canRemoveEntry(entry: GuestbookEntryDTO): boolean {
  return canModerateWall.value || session.user?.id === entry.userId
}

async function removeEntry(entry: GuestbookEntryDTO): Promise<void> {
  try {
    await deleteGuestbookEntry(entry.id)
    await load()
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '删除失败'
  }
}

onMounted(load)
</script>

<template>
  <div class="lb-guestbook">
    <h3 class="lb-gb-title">留言墙</h3>

    <!-- 新留言 -->
    <el-card shadow="never" class="lb-gb-composer">
      <template v-if="session.isLoggedIn">
        <el-input
          v-model="newBody"
          type="textarea"
          :rows="3"
          maxlength="2000"
          show-word-limit
          placeholder="写下你的留言（≤2000 字）"
        />
        <div class="lb-gb-composer-actions">
          <el-button type="primary" size="small" :loading="posting" @click="submitNew">发布留言</el-button>
        </div>
      </template>
      <template v-else>
        <div class="lb-gb-login-row">
          <span class="lb-muted">登录后即可留言与回复</span>
          <RouterLink to="/login-register">
            <el-button size="small" type="primary" plain>去登录</el-button>
          </RouterLink>
        </div>
      </template>
    </el-card>

    <div v-loading="loading">
      <el-alert v-if="errorMessage" type="warning" class="lb-gb-alert" closable @close="errorMessage = ''">
        {{ errorMessage }}
      </el-alert>
      <el-empty v-if="entries.length === 0 && !loading && !errorMessage" description="还没有留言，来抢占第一条～" class="lb-gb-empty" />

      <div class="lb-gb-stack">
        <el-card v-for="entry in entries" :key="entry.id" shadow="never">
          <div class="lb-gb-entry">
            <el-avatar :size="28">{{ entry.userName.slice(0, 1) }}</el-avatar>
            <div class="lb-gb-main">
              <div class="lb-gb-meta">
                <span class="lb-gb-author">{{ entry.userName }}</span>
                <span class="lb-muted">{{ formatRelative(entry.createdAt) }}</span>
              </div>
              <p class="lb-gb-body">{{ entry.body }}</p>
              <div class="lb-gb-actions">
                <el-button v-if="session.isLoggedIn" link size="small" @click="openReply(entry)">回复</el-button>
                <el-popconfirm v-if="canRemoveEntry(entry)" title="删除该留言及其全部回复？" @confirm="removeEntry(entry)">
                  <template #reference>
                    <el-button link size="small" type="danger">删除</el-button>
                  </template>
                </el-popconfirm>
              </div>
            </div>
          </div>

          <!-- 两级回复 -->
          <div v-if="entry.replies.length > 0" class="lb-gb-replies">
            <div v-for="reply in entry.replies" :key="reply.id" class="lb-gb-reply">
              <el-avatar :size="26">{{ reply.authorName.slice(0, 1) }}</el-avatar>
              <div class="lb-gb-main">
                <div class="lb-gb-meta">
                  <span class="lb-gb-author">{{ reply.authorName }}</span>
                  <span v-if="reply.replyToUserName" class="lb-muted">回复 @{{ reply.replyToUserName }}</span>
                  <span class="lb-muted">{{ formatRelative(reply.createdAt) }}</span>
                </div>
                <p class="lb-gb-body">{{ reply.body }}</p>
                <div class="lb-gb-actions">
                  <el-button
                    v-if="session.isLoggedIn"
                    link
                    size="small"
                    @click="openReply(entry, reply.id, reply.authorName)"
                  >
                    回复
                  </el-button>
                </div>
              </div>
            </div>
          </div>
        </el-card>
      </div>

      <!-- 行内回复框 -->
      <el-card v-if="replyTo" shadow="never" class="lb-gb-reply-card">
        <template #header>回复 @{{ replyTo.nickname }}</template>
        <el-input
          v-model="replyBody"
          type="textarea"
          :rows="2"
          maxlength="2000"
          show-word-limit
          placeholder="写下你的回复（≤2000 字）"
        />
        <div class="lb-gb-composer-actions">
          <el-button size="small" @click="cancelReply">取消</el-button>
          <el-button size="small" type="primary" :loading="replySubmitting" @click="submitReply">发送</el-button>
        </div>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.lb-gb-title {
  margin-top: 0;
}

.lb-gb-composer {
  margin-bottom: 16px;
}

.lb-gb-composer-actions {
  margin-top: 8px;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.lb-gb-login-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.lb-gb-alert {
  margin-bottom: 16px;
}

.lb-gb-empty {
  padding: 60px 0;
}

.lb-gb-stack {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.lb-gb-entry,
.lb-gb-reply {
  display: flex;
  gap: 10px;
}

.lb-gb-main {
  flex: 1;
  min-width: 0;
}

.lb-gb-meta {
  display: flex;
  gap: 8px;
  align-items: baseline;
}

.lb-gb-author {
  font-weight: 600;
  font-size: 13px;
}

.lb-gb-body {
  margin: 4px 0;
  white-space: pre-wrap;
  word-break: break-word;
}

.lb-gb-actions {
  display: flex;
  gap: 8px;
}

.lb-gb-replies {
  margin: 12px 0 0 36px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding-top: 12px;
  border-top: 1px dashed var(--lb-border);
}

.lb-gb-reply-card {
  margin-top: 16px;
}
</style>
