<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteGuestbookEntry, getGuestbook } from '@/api/portal'
import { ApiError } from '@/api/request'
import PaginationBar from '@/components/PaginationBar.vue'
import { formatDateTime } from '@/utils/format'
import type { GuestbookEntryDTO } from '@/types/api'

/**
 * 留言墙管理（§5.6，message.wallModerate）：
 * 复用公开 GET /guestbook（visible 一级 + 两级回复）；
 * 删除 DELETE /guestbook/{id}（一级连带回复逻辑删；回复传其评论 id）。
 * 形态：对话式留言卡流 —— 每条一级留言一张卡片，其回复以缩进小卡嵌入。
 */

const records = ref<GuestbookEntryDTO[]>([])
const loading = ref(false)
const errorMessage = ref('')

const page = ref(1)
const pageSize = 10

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    records.value = await getGuestbook()
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '加载失败'
    records.value = []
  } finally {
    loading.value = false
  }
}

/** 公开接口全量返回，前端分页 */
const paged = computed(() => {
  const start = (page.value - 1) * pageSize
  return records.value.slice(start, start + pageSize)
})

interface ReplyRow {
  replyId: number
  userName: string
  body: string
  createdAt: string
}

function replyRows(row: GuestbookEntryDTO): ReplyRow[] {
  return row.replies.map((reply) => ({
    replyId: reply.id,
    userName: reply.replyToUserName ? `${reply.authorName} 回复 @${reply.replyToUserName}` : reply.authorName,
    body: reply.body,
    createdAt: reply.createdAt ?? '',
  }))
}

/** 头像圈首字母 */
function initial(name: string): string {
  const trimmed = (name ?? '').trim()
  return trimmed ? trimmed.slice(0, 1).toUpperCase() : '?'
}

async function confirmRemoveEntry(row: GuestbookEntryDTO): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除「${row.userName}」的这条留言吗？其下回复将一并隐藏。`,
      '删除留言',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await deleteGuestbookEntry(row.id)
    ElMessage.success('留言已删除')
    await load()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '删除失败')
  }
}

async function confirmRemoveReply(replyId: number): Promise<void> {
  try {
    await ElMessageBox.confirm('确定删除这条回复吗？', '删除回复', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await deleteGuestbookEntry(replyId)
    ElMessage.success('回复已删除')
    await load()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '删除失败')
  }
}

onMounted(load)
</script>

<template>
  <div class="lb-gb">
    <div class="lb-page-header">
      <div>
        <h2>留言墙管理</h2>
        <span class="lb-muted">删除留言与回复（message.wallModerate）</span>
      </div>
      <span class="lb-ed-pill lb-ed-pill--soft">{{ records.length }} 条留言</span>
    </div>

    <el-alert v-if="errorMessage" type="warning" :title="errorMessage" class="lb-load-alert" :closable="false">
      <el-button size="small" link @click="load">重试</el-button>
    </el-alert>

    <!-- 加载骨架 -->
    <div v-if="loading" class="lb-gb-flow">
      <div v-for="n in 3" :key="n" class="lb-gb-card lb-ed-card">
        <div class="lb-gb-head">
          <span class="lb-ed-skel lb-gb-avatar-skel" />
          <span class="lb-ed-skel lb-gb-line" style="width: 160px; height: 14px" />
        </div>
        <div class="lb-ed-skel lb-gb-line" style="width: 92%; height: 13px" />
        <div class="lb-ed-skel lb-gb-line" style="width: 64%; height: 13px" />
      </div>
    </div>

    <!-- 空态 -->
    <div v-else-if="paged.length === 0" class="lb-ed-empty lb-ed-card lb-gb-empty">
      <p>还没有留言</p>
      <span class="lb-ed-caption">门户「留言墙」有新留言后会出现在这里</span>
    </div>

    <!-- 对话式留言卡流 -->
    <div v-else class="lb-gb-flow">
      <article
        v-for="row in paged"
        :key="row.id"
        class="lb-gb-card lb-ed-card"
      >
        <div class="lb-gb-head">
          <span class="lb-ed-avatar lb-gb-avatar">{{ initial(row.userName) }}</span>
          <div class="lb-gb-meta">
            <span class="lb-gb-name">{{ row.userName }}</span>
            <span class="lb-ed-caption">{{ formatDateTime(row.createdAt) }}</span>
          </div>
          <span v-if="row.replies.length > 0" class="lb-ed-pill lb-ed-pill--soft lb-gb-count">
            {{ row.replies.length }} 回复
          </span>
          <el-button
            class="lb-gb-del"
            link
            type="danger"
            size="small"
            @click="confirmRemoveEntry(row)"
          >
            删除
          </el-button>
        </div>

        <p class="lb-gb-body">{{ row.body }}</p>

        <!-- 内嵌回复列表（缩进对话） -->
        <div v-if="row.replies.length > 0" class="lb-gb-replies">
          <div
            v-for="reply in replyRows(row)"
            :key="reply.replyId"
            class="lb-gb-reply"
          >
            <span class="lb-ed-avatar lb-gb-avatar lb-gb-avatar--sm">{{ initial(reply.userName) }}</span>
            <div class="lb-gb-reply-main">
              <div class="lb-gb-reply-head">
                <span class="lb-gb-reply-name">{{ reply.userName }}</span>
                <span class="lb-ed-caption">{{ formatDateTime(reply.createdAt) }}</span>
                <button type="button" class="lb-gb-reply-del" @click="confirmRemoveReply(reply.replyId)">删除</button>
              </div>
              <p class="lb-gb-reply-body">{{ reply.body }}</p>
            </div>
          </div>
        </div>
      </article>
    </div>

    <div class="lb-manage-pagination">
      <PaginationBar v-model:page="page" :total="records.length" :page-size="pageSize" />
    </div>
  </div>
</template>

<style scoped>
.lb-gb-flow {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.lb-gb-card {
  padding: clamp(16px, 2vw, 22px);
  overflow: visible;
}

.lb-gb-empty {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.lb-gb-empty p {
  margin: 0;
  font-weight: 600;
  color: var(--ed-ink);
}

.lb-gb-head {
  display: flex;
  align-items: center;
  gap: 12px;
}

.lb-gb-avatar {
  width: 38px;
  height: 38px;
  font-size: 0.9rem;
}
.lb-gb-avatar--sm {
  width: 28px;
  height: 28px;
  font-size: 0.72rem;
  background: color-mix(in srgb, var(--ed-accent) 70%, var(--ed-ink));
}
.lb-gb-avatar-skel {
  width: 38px;
  height: 38px;
  border-radius: var(--ed-radius-pill);
}

.lb-gb-meta {
  display: flex;
  flex-direction: column;
  min-width: 0;
  gap: 2px;
}

.lb-gb-name {
  font-weight: 600;
  color: var(--ed-ink);
}

.lb-gb-count {
  margin-left: auto;
}

.lb-gb-del {
  flex: none;
}

.lb-gb-body {
  margin: 14px 0 0;
  color: var(--ed-ink);
  white-space: pre-wrap;
  word-break: break-word;
  line-height: var(--ed-leading-body);
}

.lb-gb-line {
  margin-top: 10px;
}

/* 回复：缩进的小卡对话 */
.lb-gb-replies {
  margin-top: 16px;
  padding-left: 18px;
  border-left: 2px solid var(--ed-rule-soft);
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.lb-gb-reply {
  display: flex;
  gap: 10px;
  padding: 10px 12px;
  border-radius: var(--ed-radius-sm);
  background: color-mix(in srgb, var(--ed-ink) 4%, var(--ed-paper));
}

.lb-gb-reply-main {
  min-width: 0;
  flex: 1;
}

.lb-gb-reply-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.lb-gb-reply-name {
  font-size: var(--ed-step-small);
  font-weight: 600;
  color: var(--ed-ink);
}

.lb-gb-reply-del {
  margin-left: auto;
  padding: 0;
  border: 0;
  background: transparent;
  cursor: pointer;
  font-size: var(--ed-step-caption);
  color: var(--app-negative, #c0392b);
  font-family: inherit;
}
.lb-gb-reply-del:hover {
  text-decoration: underline;
}

.lb-gb-reply-body {
  margin: 6px 0 0;
  color: var(--ed-ink-soft);
  white-space: pre-wrap;
  word-break: break-word;
  line-height: var(--ed-leading-body);
}

@media (max-width: 640px) {
  .lb-gb-head {
    flex-wrap: wrap;
  }
  .lb-gb-count {
    margin-left: 0;
  }
  .lb-gb-replies {
    padding-left: 12px;
  }
}
</style>
