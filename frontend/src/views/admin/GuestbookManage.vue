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
  <div>
    <div class="lb-page-header">
      <h2>留言墙管理</h2>
      <span class="lb-muted">删除留言与回复（message.wallModerate）</span>
    </div>

    <el-card shadow="never">
      <el-alert v-if="errorMessage" type="warning" :title="errorMessage" class="lb-load-alert" :closable="false">
        <el-button size="small" link @click="load">重试</el-button>
      </el-alert>

      <el-table v-loading="loading" :data="paged" row-key="id">
        <el-table-column type="expand">
          <template #default="{ row }">
            <el-table v-if="replyRows(row as GuestbookEntryDTO).length > 0" :data="replyRows(row as GuestbookEntryDTO)" row-key="replyId" size="small" class="lb-reply-table">
              <el-table-column label="回复人" prop="userName" width="200" />
              <el-table-column label="内容" prop="body" show-overflow-tooltip />
              <el-table-column label="时间" width="170">
                <template #default="{ row: reply }">{{ formatDateTime(reply.createdAt) }}</template>
              </el-table-column>
              <el-table-column label="操作" width="90">
                <template #default="{ row: reply }">
                  <el-button link type="danger" size="small" @click="confirmRemoveReply(reply.replyId)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
            <span v-else class="lb-muted">暂无回复</span>
          </template>
        </el-table-column>
        <el-table-column label="ID" prop="id" width="70" />
        <el-table-column label="留言人" prop="userName" width="120" />
        <el-table-column label="内容" prop="body" show-overflow-tooltip />
        <el-table-column label="回复数" width="80">
          <template #default="{ row }">{{ row.replies.length }}</template>
        </el-table-column>
        <el-table-column label="发布时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="danger" size="small" @click="confirmRemoveEntry(row as GuestbookEntryDTO)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="lb-manage-pagination">
        <PaginationBar v-model:page="page" :total="records.length" :page-size="pageSize" />
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.lb-page-header {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 16px;
}

.lb-page-header h2 {
  margin: 0;
  font-size: 18px;
}

.lb-load-alert {
  margin: 0 0 12px;
}

.lb-reply-table {
  width: calc(100% - 48px);
  margin: 6px 0 10px 48px;
}

.lb-manage-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
