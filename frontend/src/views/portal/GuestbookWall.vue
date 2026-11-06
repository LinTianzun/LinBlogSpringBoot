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
const replyTo = ref<{ id: number; nickname: string } | null>(null)
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

function openReply(id: number, nickname: string): void {
  if (!session.isLoggedIn) return
  replyTo.value = { id, nickname }
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

/** 调色板：给不同留言的头像取主题色点缀，制造节奏 */
const AVATAR_TONES = 5
function toneOf(id: number): number {
  return (Math.abs(id) % AVATAR_TONES) + 1
}
function initial(name: string): string {
  return (name || '?').trim().slice(0, 1).toUpperCase()
}

const totalReplies = computed(() => entries.value.reduce((sum, e) => sum + (e.replies?.length ?? 0), 0))

onMounted(load)
</script>

<template>
  <div class="lb-ed lb-ed-wrap lb-gb">
    <!-- 页头 -->
    <header class="lb-gb-hero">
      <div class="lb-gb-hero-top">
        <span class="lb-gb-eyebrow"><span class="lb-ed-dot"></span>留言墙 · 来宾簿</span>
        <span class="lb-gb-count">{{ entries.length }} 条留言 · {{ totalReplies }} 条回复</span>
      </div>
      <h1 class="lb-ed-h1">留下<span class="lb-ed-em">只言片语</span>，让这座小站记得你来过。</h1>
      <p class="lb-ed-lead">读完一篇有所得？路过留一句。这里没有算法，只有人和人之间慢慢积累的痕迹。</p>
    </header>

    <!-- 发表 -->
    <section class="lb-gb-compose lb-ed-card">
      <template v-if="session.isLoggedIn">
        <div class="lb-gb-compose-head">
          <span class="lb-gb-compose-avatar">{{ initial(session.user?.nickname || session.user?.username || '我') }}</span>
          <span class="lb-gb-compose-lbl">写下你的留言</span>
        </div>
        <textarea
          v-model="newBody"
          class="lb-gb-textarea"
          rows="3"
          maxlength="2000"
          placeholder="说点什么吧……（≤2000 字）"
        ></textarea>
        <div class="lb-gb-compose-foot">
          <span class="lb-gb-hint">{{ newBody.length }} / 2000</span>
          <button class="lb-ed-btn lb-ed-btn--primary" :disabled="posting || newBody.trim().length === 0" @click="submitNew">
            {{ posting ? '发布中…' : '发布留言' }}
          </button>
        </div>
      </template>
      <template v-else>
        <div class="lb-gb-login">
          <div>
            <p class="lb-gb-login-title">登录后即可留言与回复</p>
            <p class="lb-gb-login-sub">和其它读者一起，在这面墙上留下你的痕迹。</p>
          </div>
          <RouterLink to="/login-register" class="lb-ed-btn lb-ed-btn--primary">去登录</RouterLink>
        </div>
      </template>
    </section>

    <!-- 错误提示 -->
    <div v-if="errorMessage" class="lb-gb-error">
      <span>{{ errorMessage }}</span>
      <button class="lb-ed-btn lb-ed-btn--ghost" @click="load">重试</button>
    </div>

    <!-- 骨架 -->
    <div v-if="loading && entries.length === 0" class="lb-gb-thread">
      <div v-for="i in 3" :key="i" class="lb-ed-skel lb-gb-skel"></div>
    </div>

    <!-- 空态 -->
    <div v-else-if="entries.length === 0 && !loading && !errorMessage" class="lb-gb-empty">
      <div class="lb-gb-empty-mark">✳</div>
      <p>还没有留言，来抢占第一条吧。</p>
    </div>

    <!-- 留言流 -->
    <section v-else class="lb-gb-thread">
      <article v-for="entry in entries" :key="entry.id" class="lb-gb-item">
        <span class="lb-gb-avatar" :class="`lb-gb-avatar--${toneOf(entry.id)}`">{{ initial(entry.userName) }}</span>
        <div class="lb-gb-item-body">
          <div class="lb-gb-card lb-ed-card">
            <div class="lb-gb-meta">
              <span class="lb-gb-author">{{ entry.userName }}</span>
              <span class="lb-gb-time">{{ formatRelative(entry.createdAt) }}</span>
            </div>
            <p class="lb-gb-text">{{ entry.body }}</p>
            <div class="lb-gb-actions">
              <button v-if="session.isLoggedIn" class="lb-gb-action" @click="openReply(entry.id, entry.userName)">回复</button>
              <el-popconfirm
                v-if="canRemoveEntry(entry)"
                title="删除该留言及其全部回复？"
                confirm-button-text="删除"
                cancel-button-text="取消"
                @confirm="removeEntry(entry)"
              >
                <template #reference>
                  <button class="lb-gb-action lb-gb-action--danger">删除</button>
                </template>
              </el-popconfirm>
            </div>
          </div>

          <!-- 两级回复 -->
          <div v-if="entry.replies.length > 0" class="lb-gb-replies">
            <div v-for="reply in entry.replies" :key="reply.id" class="lb-gb-reply">
              <span class="lb-gb-avatar lb-gb-avatar--sm" :class="`lb-gb-avatar--${toneOf(reply.id + 2)}`">{{ initial(reply.authorName) }}</span>
              <div class="lb-gb-reply-body lb-gb-card lb-ed-card">
                <div class="lb-gb-meta">
                  <span class="lb-gb-author">{{ reply.authorName }}</span>
                  <span v-if="reply.replyToUserName" class="lb-gb-replyto">回复 @{{ reply.replyToUserName }}</span>
                  <span class="lb-gb-time">{{ formatRelative(reply.createdAt) }}</span>
                </div>
                <p class="lb-gb-text">{{ reply.body }}</p>
                <div class="lb-gb-actions">
                  <button v-if="session.isLoggedIn" class="lb-gb-action" @click="openReply(reply.id, reply.authorName)">回复</button>
                </div>
              </div>
            </div>
          </div>

          <!-- 行内回复框 -->
          <div v-if="replyTo && replyTo.id === entry.id" class="lb-gb-reply-compose lb-gb-card lb-ed-card">
            <div class="lb-gb-compose-head">
              <span class="lb-gb-compose-avatar lb-gb-compose-avatar--sm">{{ initial(session.user?.nickname || session.user?.username || '我') }}</span>
              <span class="lb-gb-compose-lbl">回复 <b>@{{ replyTo.nickname }}</b></span>
            </div>
            <textarea
              v-model="replyBody"
              class="lb-gb-textarea lb-gb-textarea--sm"
              rows="2"
              maxlength="2000"
              placeholder="写下你的回复……"
            ></textarea>
            <div class="lb-gb-compose-foot">
              <span class="lb-gb-hint">{{ replyBody.length }} / 2000</span>
              <div class="lb-gb-compose-btns">
                <button class="lb-ed-btn lb-ed-btn--ghost" @click="cancelReply">取消</button>
                <button class="lb-ed-btn lb-ed-btn--primary" :disabled="replySubmitting || replyBody.trim().length === 0" @click="submitReply">
                  {{ replySubmitting ? '发送中…' : '发送' }}
                </button>
              </div>
            </div>
          </div>
        </div>
      </article>
    </section>
  </div>
</template>

<style scoped>
.lb-gb {
  display: flex;
  flex-direction: column;
  gap: clamp(28px, 4vw, 44px);
}

/* Hero */
.lb-gb-hero {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.lb-gb-hero-top {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 6px 14px;
}
.lb-gb-count {
  font-size: var(--ed-step-small);
  color: var(--ed-ink-soft);
  font-variant-numeric: tabular-nums;
}
.lb-gb-count::before {
  content: '·';
  margin-right: 14px;
  opacity: 0.45;
}
.lb-gb-eyebrow {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: var(--ed-step-caption, 13px);
  letter-spacing: 0.04em;
  color: var(--ed-ink-soft);
}

/* Composer */
.lb-gb-compose {
  padding: clamp(18px, 2.4vw, 26px);
}
.lb-gb-compose-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.lb-gb-compose-avatar {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: var(--ed-accent);
  color: #fff;
  font-weight: 600;
  font-size: 14px;
  flex: none;
}
.lb-gb-compose-avatar--sm {
  width: 28px;
  height: 28px;
  font-size: 12px;
}
.lb-gb-compose-lbl {
  font-size: var(--ed-step-small);
  color: var(--ed-ink-soft);
}
.lb-gb-textarea {
  width: 100%;
  box-sizing: border-box;
  border: 1px solid var(--ed-rule-soft, rgba(0, 0, 0, 0.12));
  border-radius: var(--ed-radius-md, 14px);
  background: var(--ed-paper, #f6f4ef);
  padding: 12px 14px;
  font: inherit;
  font-size: var(--ed-step-body, 16px);
  line-height: 1.6;
  color: var(--ed-ink);
  resize: vertical;
  transition: border-color 0.2s, box-shadow 0.2s;
}
.lb-gb-textarea:focus {
  outline: none;
  border-color: var(--ed-accent);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--ed-accent) 16%, transparent);
}
.lb-gb-textarea--sm {
  min-height: 62px;
}
.lb-gb-compose-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 12px;
}
.lb-gb-compose-btns {
  display: flex;
  gap: 8px;
}
.lb-gb-hint {
  font-size: 12px;
  color: var(--ed-ink-soft);
  font-variant-numeric: tabular-nums;
}
.lb-gb-login {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.lb-gb-login-title {
  margin: 0 0 4px;
  font-weight: 600;
}
.lb-gb-login-sub {
  margin: 0;
  font-size: var(--ed-step-small);
  color: var(--ed-ink-soft);
}

/* Error / empty */
.lb-gb-error {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border-radius: var(--ed-radius-md, 14px);
  background: color-mix(in srgb, #d9a441 12%, var(--ed-card, #fff));
  color: var(--ed-ink);
  font-size: var(--ed-step-small);
}
.lb-gb-empty {
  text-align: center;
  padding: 64px 0;
  color: var(--ed-ink-soft);
}
.lb-gb-empty-mark {
  font-size: 40px;
  color: var(--ed-accent);
  opacity: 0.6;
  margin-bottom: 12px;
}

/* Thread */
.lb-gb-thread {
  display: flex;
  flex-direction: column;
  gap: clamp(18px, 2.4vw, 26px);
}
.lb-gb-item {
  display: flex;
  gap: 14px;
}
.lb-gb-avatar {
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  border-radius: 50%;
  flex: none;
  font-weight: 600;
  font-size: 16px;
  color: #fff;
}
.lb-gb-avatar--sm {
  width: 30px;
  height: 30px;
  font-size: 12px;
}
.lb-gb-avatar--1 { background: var(--ed-accent); }
.lb-gb-avatar--2 { background: #6b8f71; }
.lb-gb-avatar--3 { background: #b06a4a; }
.lb-gb-avatar--4 { background: #6a7aa8; }
.lb-gb-avatar--5 { background: #a56a8f; }

.lb-gb-item-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.lb-gb-card {
  padding: 14px 16px;
}
.lb-gb-meta {
  display: flex;
  gap: 10px;
  align-items: baseline;
  flex-wrap: wrap;
}
.lb-gb-author {
  font-weight: 600;
  font-size: 14px;
  color: var(--ed-ink);
}
.lb-gb-time {
  font-size: 12px;
  color: var(--ed-ink-soft);
}
.lb-gb-replyto {
  font-size: 12px;
  color: var(--ed-accent);
}
.lb-gb-text {
  margin: 8px 0 0;
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.7;
  color: var(--ed-ink);
}
.lb-gb-actions {
  display: flex;
  gap: 14px;
  margin-top: 10px;
}
.lb-gb-action {
  border: none;
  background: none;
  padding: 0;
  cursor: pointer;
  font-size: 13px;
  color: var(--ed-ink-soft);
  transition: color 0.18s;
}
.lb-gb-action:hover {
  color: var(--ed-accent);
}
.lb-gb-action--danger:hover {
  color: #c0453a;
}

/* Replies */
.lb-gb-replies {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-left: 6px;
  padding-left: 16px;
  border-left: 2px solid color-mix(in srgb, var(--ed-accent) 18%, transparent);
}
.lb-gb-reply {
  display: flex;
  gap: 10px;
}
.lb-gb-reply-body {
  flex: 1;
  min-width: 0;
  padding: 10px 14px;
  background: color-mix(in srgb, var(--ed-ink) 3%, var(--ed-card, #fff));
}
.lb-gb-reply-compose {
  margin-left: 6px;
}

.lb-gb-skel {
  height: 96px;
  border-radius: var(--ed-radius-md, 14px);
}

.lb-ed-btn {
  cursor: pointer;
  border: none;
  font: inherit;
}

@media (max-width: 640px) {
  .lb-gb-avatar {
    width: 34px;
    height: 34px;
    font-size: 14px;
  }
  .lb-gb-item {
    gap: 10px;
  }
  .lb-gb-replies {
    margin-left: 0;
  }
}
</style>
