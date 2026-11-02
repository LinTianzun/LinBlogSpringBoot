<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
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
    conversation.unread = 0
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
  <div>
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
      <!-- 会话列表 -->
      <el-card shadow="never" class="lb-message-conversations" v-loading="listLoading">
        <el-empty v-if="conversations.length === 0 && !listError" description="暂无会话" :image-size="60" />
        <ul v-else class="lb-conversation-list">
          <li
            v-for="conversation in conversations"
            :key="conversation.id"
            :class="['lb-conversation', { 'is-active': conversation.id === activeId }]"
            :data-testid="conversation.id === activeId ? 'message-conversation-active' : 'message-conversation'"
            @click="openConversation(conversation)"
          >
            <div class="lb-conversation-top">
              <strong>{{ conversation.peer.nickname || conversation.peer.username }}</strong>
              <span class="lb-muted">{{ conversation.lastMessageAt ? formatRelative(conversation.lastMessageAt) : '—' }}</span>
            </div>
            <div class="lb-conversation-bottom">
              <span class="lb-conversation-preview">{{ conversation.lastMessagePreview ?? '（无消息）' }}</span>
              <span v-if="conversation.unread > 0" class="lb-conversation-badge">{{ conversation.unread }}</span>
            </div>
          </li>
        </ul>
      </el-card>

      <!-- 消息流 -->
      <el-card shadow="never" class="lb-message-thread" v-loading="msgLoading">
        <template v-if="activeId === null">
          <el-empty description="在左侧选择一个会话" :image-size="80" />
        </template>
        <template v-else>
          <div class="lb-thread-head">
            <strong>{{ activeTitle }}</strong>
          </div>

          <el-alert v-if="msgError" type="warning" :closable="false" class="lb-thread-alert">
            {{ msgError }}
            <el-button link size="small" @click="loadMessages">重试</el-button>
          </el-alert>

          <div class="lb-thread-body">
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

          <div class="lb-thread-composer">
            <el-input
              v-model="draft"
              type="textarea"
              :rows="2"
              maxlength="2000"
              show-word-limit
              placeholder="输入消息（≤2000 字），Enter 发送"
              data-testid="message-draft"
              @keydown.enter.exact.prevent="send"
            />
            <el-button type="primary" :loading="sending" data-testid="message-send" @click="send">发送</el-button>
          </div>
        </template>
      </el-card>
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
.lb-page-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.lb-page-header h2 {
  margin: 0;
  font-size: 18px;
}

.lb-message-unread {
  font-size: 13px;
  color: var(--el-color-danger);
}

.lb-header-spacer {
  flex: 1;
}

.lb-message-alert {
  margin-bottom: 16px;
}

.lb-message-layout {
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

@media (max-width: 900px) {
  .lb-message-layout {
    grid-template-columns: 1fr;
  }
}

.lb-conversation-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.lb-conversation {
  padding: 10px 8px;
  border-radius: 6px;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.lb-conversation:hover {
  background: var(--el-fill-color-light);
}

.lb-conversation.is-active {
  background: color-mix(in srgb, var(--el-color-primary) 10%, transparent);
}

.lb-conversation-top {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 8px;
}

.lb-conversation-bottom {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}

.lb-conversation-preview {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  color: var(--lb-muted, var(--el-text-color-secondary));
}

.lb-conversation-badge {
  flex: none;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 9px;
  background: var(--el-color-danger);
  color: #fff;
  font-size: 12px;
  line-height: 18px;
  text-align: center;
}

.lb-thread-head {
  padding-bottom: 10px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  margin-bottom: 12px;
}

.lb-thread-alert {
  margin-bottom: 12px;
}

.lb-thread-body {
  max-height: 52vh;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 4px;
}

.lb-thread-earlier {
  text-align: center;
}

.lb-msg {
  display: flex;
  flex-direction: column;
  max-width: 78%;
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
  padding: 8px 12px;
  border-radius: 10px;
  font-size: 14px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}

.lb-msg-self .lb-msg-bubble {
  background: var(--el-color-primary);
  color: #fff;
  border-top-right-radius: 2px;
}

.lb-msg-other .lb-msg-bubble {
  background: var(--el-fill-color);
  border-top-left-radius: 2px;
}

.lb-msg-time {
  font-size: 12px;
  margin-top: 2px;
}

.lb-thread-composer {
  display: flex;
  gap: 10px;
  align-items: flex-end;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--el-border-color-lighter);
}
</style>
