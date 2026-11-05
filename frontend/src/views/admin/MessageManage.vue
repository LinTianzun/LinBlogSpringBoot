<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ChatDotRound } from '@element-plus/icons-vue'
import {
  createConversation,
  getUnreadCount,
  listConversations,
  listMessages,
  markConversationRead,
  sendMessage,
} from '@/api/admin'
import { ApiError } from '@/api/request'
import { formatDateTime, formatRelative } from '@/utils/format'
import { useSessionStore } from '@/stores/session'
import type { ConversationDTO, MessageDTO } from '@/types/api'

/**
 * 私信（§5.6，M4）：左侧会话列表（message.private），右侧消息流。
 * 消息为游标倒查（?before= 首条 id），后端每页 20 条正序返回；
 * 进入会话即 markRead 清未读；SSE 实时推送本期不接，仅手动刷新。
 */

const CONVERSATION_PAGE_SIZE = 50

const session = useSessionStore()

const conversations = ref<ConversationDTO[]>([])
const listLoading = ref(false)
const listError = ref('')

const unreadCount = ref(0)

const activeId = ref<number | null>(null)
const activeTitle = computed(() => {
  const current = conversations.value.find((conversation) => conversation.id === activeId.value)
  if (!current) return ''
  return current.peer.nickname || current.peer.username
})
const messages = ref<MessageDTO[]>([])
const hasMore = ref(false)
const msgLoading = ref(false)
const msgError = ref('')
const draft = ref('')
const sending = ref(false)

const showCompose = ref(false)
const composeRecipientId = ref<number | null>(null)
const composeBody = ref('')
const composeSending = ref(false)

const myId = computed(() => session.user?.id ?? null)

async function loadConversations(): Promise<void> {
  listLoading.value = true
  listError.value = ''
  try {
    const result = await listConversations({ page: 1, pageSize: CONVERSATION_PAGE_SIZE })
    conversations.value = result.records
    // 默认展开最近一条会话，避免右侧大片空场
    if (activeId.value === null && result.records.length > 0) {
      void openConversation(result.records[0])
    }
  } catch (error) {
    listError.value = error instanceof ApiError ? error.message : '加载失败'
    conversations.value = []
  } finally {
    listLoading.value = false
  }
}

async function refreshUnread(): Promise<void> {
  try {
    unreadCount.value = await getUnreadCount()
  } catch {
    unreadCount.value = 0
  }
}

async function loadMessages(): Promise<void> {
  if (activeId.value === null) return
  msgLoading.value = true
  msgError.value = ''
  try {
    const result = await listMessages(activeId.value)
    messages.value = result.records
    hasMore.value = result.hasMore
  } catch (error) {
    msgError.value = error instanceof ApiError ? error.message : '加载失败'
    messages.value = []
    hasMore.value = false
  } finally {
    msgLoading.value = false
  }
}

async function openConversation(conversation: ConversationDTO): Promise<void> {
  activeId.value = conversation.id
  await loadMessages()
  if (conversation.unread > 0) {
    // 走响应式数组里的同一项，徽标才会即时消失
    const current = conversations.value.find((item) => item.id === conversation.id)
    if (current) current.unread = 0
    try {
      await markConversationRead(conversation.id)
      void refreshUnread()
    } catch {
      // 清未读失败不打断会话浏览
    }
  }
}

async function loadEarlier(): Promise<void> {
  if (activeId.value === null || messages.value.length === 0 || !hasMore.value) return
  const before = messages.value[0].id
  msgLoading.value = true
  try {
    const result = await listMessages(activeId.value, before)
    messages.value = [...result.records, ...messages.value]
    hasMore.value = result.hasMore
  } catch (error) {
    msgError.value = error instanceof ApiError ? error.message : '加载失败'
  } finally {
    msgLoading.value = false
  }
}

async function send(): Promise<void> {
  const body = draft.value.trim()
  if (activeId.value === null) return
  if (body.length === 0) {
    ElMessage.warning('请输入消息内容')
    return
  }
  if (body.length > 2000) {
    ElMessage.error('消息不能超过 2000 个字符')
    return
  }
  sending.value = true
  try {
    const message = await sendMessage(activeId.value, { body })
    messages.value.push(message)
    draft.value = ''
    void loadConversations()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '发送失败')
  } finally {
    sending.value = false
  }
}

async function startConversation(): Promise<void> {
  const recipientId = composeRecipientId.value
  const body = composeBody.value.trim()
  if (recipientId === null || recipientId <= 0) {
    ElMessage.warning('请填写有效的收件人用户 ID')
    return
  }
  if (body.length === 0) {
    ElMessage.warning('请输入首条消息内容')
    return
  }
  composeSending.value = true
  try {
    const message = await createConversation({ recipientId, body })
    showCompose.value = false
    composeRecipientId.value = null
    composeBody.value = ''
    ElMessage.success('会话已创建')
    await loadConversations()
    activeId.value = message.conversationId
    await loadMessages()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '创建会话失败')
  } finally {
    composeSending.value = false
  }
}

onMounted(() => {
  void loadConversations()
  void refreshUnread()
})
</script>

<template>
  <div class="lb-msg-page">
    <div class="lb-page-header">
      <h2>私信</h2>
      <span v-if="unreadCount > 0" class="lb-message-unread">未读 {{ unreadCount }} 条</span>
      <span class="lb-header-spacer" />
      <el-button type="primary" data-testid="message-compose" @click="showCompose = true">发起会话</el-button>
    </div>

    <el-alert v-if="listError" type="warning" :closable="false" class="lb-message-alert">
      {{ listError }}
      <el-button link size="small" @click="loadConversations">重试</el-button>
    </el-alert>

    <div class="lb-message-layout">
      <!-- 左栏：会话列表 -->
      <section class="lb-msg-pane lb-msg-col" aria-label="会话列表">
        <header class="lb-msg-col-head">
          <span class="lb-msg-col-title">会话</span>
          <span class="lb-msg-col-count lb-muted">{{ conversations.length }}</span>
        </header>

        <div v-loading="listLoading" class="lb-msg-col-body">
          <p v-if="conversations.length === 0 && !listError" class="lb-msg-empty">
            还没有会话，点右上角「发起会话」开始。
          </p>
          <ul v-else class="lb-conversation-list">
            <li
              v-for="conversation in conversations"
              :key="conversation.id"
              :class="['lb-conversation', { 'is-active': conversation.id === activeId }]"
              :data-testid="conversation.id === activeId ? 'message-conversation-active' : 'message-conversation'"
              @click="openConversation(conversation)"
            >
              <span class="lb-conv-avatar">{{ (conversation.peer.nickname || conversation.peer.username).slice(0, 1).toUpperCase() }}</span>
              <div class="lb-conv-main">
                <div class="lb-conv-top">
                  <span class="lb-conv-name">{{ conversation.peer.nickname || conversation.peer.username }}</span>
                  <span class="lb-conv-time lb-muted">{{ conversation.lastMessageAt ? formatRelative(conversation.lastMessageAt) : '—' }}</span>
                </div>
                <div class="lb-conv-bottom">
                  <span class="lb-conv-preview">{{ conversation.lastMessagePreview ?? '（无消息）' }}</span>
                  <span v-if="conversation.unread > 0" class="lb-conv-badge">{{ conversation.unread > 99 ? '99+' : conversation.unread }}</span>
                </div>
              </div>
            </li>
          </ul>
        </div>
      </section>

      <!-- 右栏：对话窗 -->
      <section class="lb-msg-pane lb-msg-thread" aria-label="对话">
        <template v-if="activeId === null">
          <div class="lb-thread-placeholder">
            <span class="lb-thread-ph-mark" aria-hidden="true">
              <el-icon :size="20"><ChatDotRound /></el-icon>
            </span>
            <p class="lb-thread-ph-title">选择一位聊聊</p>
            <p class="lb-thread-ph-desc">从左侧列表挑一个会话，继续你们的对话。</p>
          </div>
        </template>
        <template v-else>
          <!-- 顶部对方信息条 -->
          <header class="lb-thread-head">
            <span class="lb-thread-head-avatar">{{ activeTitle.slice(0, 1).toUpperCase() }}</span>
            <div class="lb-thread-head-meta">
              <strong class="lb-thread-head-name">{{ activeTitle }}</strong>
              <span class="lb-thread-head-sub lb-muted">私信对话</span>
            </div>
          </header>

          <!-- 消息气泡流（独立滚动） -->
          <div v-loading="msgLoading" class="lb-thread-body">
            <el-alert v-if="msgError" type="warning" :closable="false" class="lb-thread-alert">
              {{ msgError }}
              <el-button link size="small" @click="loadMessages">重试</el-button>
            </el-alert>

            <div v-if="hasMore" class="lb-thread-earlier">
              <el-button link size="small" data-testid="message-earlier" @click="loadEarlier">加载更早的消息</el-button>
            </div>
            <el-empty v-if="messages.length === 0 && !msgError" description="还没有消息，说点什么吧" :image-size="60" />
            <div
              v-for="message in messages"
              :key="message.id"
              :class="['lb-msg', message.senderId === myId ? 'lb-msg-self' : 'lb-msg-other']"
            >
              <div class="lb-msg-bubble">{{ message.body }}</div>
              <span class="lb-msg-time lb-muted">{{ formatDateTime(message.createdAt) }}</span>
            </div>
          </div>

          <!-- 底部输入框 -->
          <div class="lb-thread-composer">
            <el-input
              v-model="draft"
              type="textarea"
              :rows="2"
              resize="none"
              maxlength="2000"
              show-word-limit
              placeholder="输入消息（≤2000 字），Enter 发送"
              data-testid="message-draft"
              @keydown.enter.exact.prevent="send"
            />
            <el-button type="primary" :loading="sending" data-testid="message-send" @click="send">发送</el-button>
          </div>
        </template>
      </section>
    </div>

    <!-- 发起会话 -->
    <el-dialog v-model="showCompose" title="发起会话" width="440px" :teleported="false">
      <el-form label-width="90px">
        <el-form-item label="收件人 ID">
          <el-input-number
            v-model="composeRecipientId"
            :min="1"
            :step="1"
            data-testid="compose-recipient"
            placeholder="对方的用户 ID"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="首条消息">
          <el-input
            v-model="composeBody"
            type="textarea"
            :rows="4"
            maxlength="2000"
            show-word-limit
            data-testid="compose-body"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCompose = false">取消</el-button>
        <el-button type="primary" :loading="composeSending" data-testid="compose-submit" @click="startConversation">发送</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* ============================================================================
 * 私信：左会话列表 + 右对话窗（双栏 · 撑满高度 · 各自独立滚动）。
 * 颜色全部走 --ed-* / --app-*，随 4 主题 + 暗色自适应。
 * ========================================================================== */

.lb-message-unread {
  font-size: 13px;
  font-weight: 600;
  color: var(--app-negative, #c4242a);
}

.lb-header-spacer {
  flex: 1;
}

.lb-message-alert {
  margin-bottom: 16px;
}

/* 页根：撑满内容区高度，双栏才能等齐撑满；min-height 兜底小屏 */
.lb-msg-page {
  display: flex;
  flex-direction: column;
  /* 内容区可视高度减去上下 padding（clamp 20–36px）×2，留出呼吸 */
  min-height: calc(100vh - 2 * clamp(20px, 2.6vw, 36px));
}


/* 页头与双栏之间的间距由 header 自带 margin 控制，双栏占满剩余高度 */
.lb-message-layout {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr);
  gap: var(--app-gap);
  align-items: stretch;
}

/* 两栏共用面板：白卡 + 圆角 + 柔和阴影，自身纵向 flex 撑满 */
.lb-msg-pane {
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--ed-card);
  border: 1px solid color-mix(in srgb, var(--ed-ink) 5%, transparent);
  border-radius: var(--ed-radius-lg);
  box-shadow: var(--ed-shadow-sm);
  overflow: hidden;
}

/* ------------------------- 左栏：会话列表 ------------------------- */
.lb-msg-col-head {
  flex: none;
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  padding: 16px 18px;
  border-bottom: 1px solid var(--ed-rule-soft);
}

.lb-msg-col-title {
  font-family: var(--ed-font-text);
  font-size: var(--ed-step-body);
  font-weight: 700;
  color: var(--ed-ink);
}

.lb-msg-col-count {
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}

/* 会话列表滚动区：独立滚动，超出才滚 */
.lb-msg-col-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 8px;
}

.lb-conversation-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.lb-conversation {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 10px 10px;
  border-radius: var(--ed-radius-sm);
  cursor: pointer;
  transition: background 150ms ease;
}

.lb-conversation:hover {
  background: color-mix(in srgb, var(--ed-accent) 6%, var(--ed-card));
}

.lb-conversation.is-active {
  background: var(--ed-accent-soft);
}

.lb-conv-avatar {
  flex: none;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-size: 15px;
  font-weight: 600;
  color: #fff;
  background: linear-gradient(140deg, var(--ed-accent), color-mix(in srgb, var(--ed-accent) 55%, #f0a03c));
}

.lb-conv-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.lb-conv-top {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}

.lb-conv-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--ed-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.lb-conv-time {
  flex: none;
  font-size: 11px;
  color: var(--ed-ink-faint);
}

.lb-conv-bottom {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.lb-conv-preview {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12.5px;
  color: var(--ed-ink-soft);
}

.lb-conv-badge {
  flex: none;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 9px;
  background: var(--app-negative, #c4242a);
  color: #fff;
  font-size: 11px;
  line-height: 18px;
  text-align: center;
  font-variant-numeric: tabular-nums;
}

/* ------------------------- 右栏：对话窗 ------------------------- */
.lb-msg-thread {
  position: relative;
}

.lb-thread-placeholder {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 24px;
  text-align: center;
}

.lb-thread-ph-mark {
  display: grid;
  place-items: center;
  width: 46px;
  height: 46px;
  margin-bottom: 6px;
  border-radius: 50%;
  color: var(--ed-accent-ink);
  background: var(--ed-accent-soft);
  border: 1px solid color-mix(in srgb, var(--ed-accent) 18%, transparent);
}

.lb-thread-ph-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--ed-ink);
}

.lb-thread-ph-desc {
  margin: 0;
  font-size: 13px;
  color: var(--ed-ink-soft);
}

.lb-msg-empty {
  margin: 0;
  padding: 28px 16px;
  font-size: 13px;
  line-height: 1.7;
  text-align: center;
  color: var(--ed-ink-soft);
}

/* 顶部对方信息条：贴顶固定，不随消息滚动 */
.lb-thread-head {
  flex: none;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 20px;
  border-bottom: 1px solid var(--ed-rule-soft);
}

.lb-thread-head-avatar {
  flex: none;
  width: 38px;
  height: 38px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-size: 14px;
  font-weight: 600;
  color: #fff;
  background: linear-gradient(140deg, var(--ed-accent), color-mix(in srgb, var(--ed-accent) 55%, #f0a03c));
}

.lb-thread-head-meta {
  display: flex;
  flex-direction: column;
  min-width: 0;
  line-height: 1.3;
}

.lb-thread-head-name {
  font-size: 15px;
  font-weight: 700;
  color: var(--ed-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.lb-thread-head-sub {
  font-size: 12px;
}

/* 消息气泡流：填满中间剩余高度，独立滚动，底部对齐最新消息 */
.lb-thread-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 20px;
}

.lb-thread-alert {
  border-radius: var(--ed-radius-md);
}

.lb-thread-earlier {
  text-align: center;
}

.lb-msg {
  display: flex;
  flex-direction: column;
  max-width: min(78%, 560px);
}

.lb-msg-self {
  align-self: flex-end;
  align-items: flex-end;
}

.lb-msg-other {
  align-self: flex-start;
  align-items: flex-start;
}

.lb-msg-bubble {
  padding: 9px 14px;
  border-radius: 14px;
  font-size: 14px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}

.lb-msg-self .lb-msg-bubble {
  background: var(--ed-accent);
  color: #fff;
  border-bottom-right-radius: 4px;
}

.lb-msg-other .lb-msg-bubble {
  background: color-mix(in srgb, var(--ed-ink) 6%, var(--ed-paper));
  color: var(--ed-ink);
  border-bottom-left-radius: 4px;
}

.lb-msg-time {
  font-size: 11px;
  margin-top: 4px;
}

/* 底部输入框：贴底固定，不随消息滚动 */
.lb-thread-composer {
  flex: none;
  display: flex;
  gap: 12px;
  align-items: flex-end;
  padding: 14px 20px;
  border-top: 1px solid var(--ed-rule-soft);
  background: var(--ed-card);
}

.lb-thread-composer :deep(.el-textarea) {
  flex: 1;
}

/* ------------------------- 窄屏：单栏堆叠 ------------------------- */
@media (max-width: 900px) {
  .lb-msg-page {
    min-height: 0;
  }

  .lb-message-layout {
    grid-template-columns: 1fr;
    grid-auto-rows: minmax(0, auto);
  }

  /* 窄屏两栏各自给固定可滚高度，避免整页被撑长 */
  .lb-msg-col {
    max-height: 40vh;
  }

  .lb-msg-thread {
    min-height: 60vh;
  }
}
</style>
