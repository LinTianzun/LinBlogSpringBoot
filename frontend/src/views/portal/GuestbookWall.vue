<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { createGuestbookEntry, getGuestbook } from '@/api/portal'
import { ApiError } from '@/api/request'
import { useSessionStore } from '@/stores/session'
import { formatRelative } from '@/utils/format'
import type { GuestbookEntryDTO } from '@/types/api'

/**
 * 留言墙（§3.4 / §5.6）：GET /guestbook 全量一级 + 两级回复；
 * 新留言 POST /guestbook {body}；回复 POST /guestbook {body, replyToId}。
 * 后端端点未实现 → 错误态降级，不白屏。
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
    nickname: nickname ?? entry.user.nickname,
    body: `${entry.user.nickname}：${entry.body.slice(0, 40)}`,
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

onMounted(load)
</script>

<template>
  <div class="lb-guestbook">
    <n-h3 class="lb-gb-title">留言墙</n-h3>

    <!-- 新留言 -->
    <n-card class="lb-gb-composer">
      <template v-if="session.isLoggedIn">
        <n-input
          v-model:value="newBody"
          type="textarea"
          :rows="3"
          maxlength="2000"
          show-count
          placeholder="写下你的留言（≤2000 字）"
        />
        <n-space justify="end" class="lb-gb-composer-actions">
          <n-button type="primary" size="small" :loading="posting" @click="submitNew">发布留言</n-button>
        </n-space>
      </template>
      <template v-else>
        <n-space align="center" justify="space-between">
          <span class="lb-muted">登录后即可留言与回复</span>
          <RouterLink to="/login-register">
            <n-button size="small" type="primary" ghost>去登录</n-button>
          </RouterLink>
        </n-space>
      </template>
    </n-card>

    <n-spin :show="loading">
      <n-alert v-if="errorMessage" type="warning" class="lb-gb-alert" closable @close="errorMessage = ''">
        {{ errorMessage }}（服务暂未就绪）
      </n-alert>
      <n-empty v-if="entries.length === 0 && !loading && !errorMessage" description="还没有留言，来抢占第一条～" class="lb-gb-empty" />

      <n-space vertical size="medium">
        <n-card v-for="entry in entries" :key="entry.id" size="small">
          <div class="lb-gb-entry">
            <n-avatar round size="small">{{ entry.user.nickname.slice(0, 1) }}</n-avatar>
            <div class="lb-gb-main">
              <div class="lb-gb-meta">
                <span class="lb-gb-author">{{ entry.user.nickname }}</span>
                <span class="lb-muted">{{ formatRelative(entry.createdAt) }}</span>
              </div>
              <p class="lb-gb-body">{{ entry.body }}</p>
              <n-space size="small">
                <n-button v-if="session.isLoggedIn" quaternary size="tiny" @click="openReply(entry)">回复</n-button>
              </n-space>
            </div>
          </div>

          <!-- 两级回复 -->
          <div v-if="entry.replies.length > 0" class="lb-gb-replies">
            <div v-for="reply in entry.replies" :key="reply.id" class="lb-gb-reply">
              <n-avatar round size="small">{{ reply.author.nickname.slice(0, 1) }}</n-avatar>
              <div class="lb-gb-main">
                <div class="lb-gb-meta">
                  <span class="lb-gb-author">{{ reply.author.nickname }}</span>
                  <span v-if="reply.replyToNickname" class="lb-muted">回复 @{{ reply.replyToNickname }}</span>
                  <span class="lb-muted">{{ formatRelative(reply.createdAt) }}</span>
                </div>
                <p class="lb-gb-body">{{ reply.body }}</p>
                <n-space size="small">
                  <n-button
                    v-if="session.isLoggedIn"
                    quaternary
                    size="tiny"
                    @click="openReply(entry, reply.id, reply.author.nickname)"
                  >
                    回复
                  </n-button>
                </n-space>
              </div>
            </div>
          </div>
        </n-card>
      </n-space>

      <!-- 行内回复框 -->
      <n-card v-if="replyTo" size="small" class="lb-gb-reply-card">
        <template #header>回复 @{{ replyTo.nickname }}</template>
        <n-input
          v-model:value="replyBody"
          type="textarea"
          :rows="2"
          maxlength="2000"
          show-count
          placeholder="写下你的回复（≤2000 字）"
        />
        <template #action>
          <n-space justify="end">
            <n-button size="small" @click="cancelReply">取消</n-button>
            <n-button size="small" type="primary" :loading="replySubmitting" @click="submitReply">发送</n-button>
          </n-space>
        </template>
      </n-card>
    </n-spin>
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
}

.lb-gb-alert {
  margin-bottom: 16px;
}

.lb-gb-empty {
  padding: 60px 0;
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

.lb-gb-replies {
  margin: 12px 0 0 30px;
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
