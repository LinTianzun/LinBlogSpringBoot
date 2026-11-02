<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteComment, listComments } from '@/api/portal'
import { ApiError } from '@/api/request'
import PaginationBar from '@/components/PaginationBar.vue'
import { useSessionStore } from '@/stores/session'
import { formatDateTime } from '@/utils/format'
import type { CommentTargetType, CommentViewDTO } from '@/types/api'

/**
 * 评论管理（§5.5，comment.viewAll）：
 * 后端无「全站评论分页」端点，按 lead 口径降级为「按内容查评论」——
 * 选 targetType（article/image/diary/guestbook）+ 内容 id → GET /portal/{t}/{id}/comments，
 * 展示两段式列表并提供删除（DELETE /comments/{id}，作者或 comment.moderate）。
 */

const TARGET_OPTIONS: Array<{ label: string; value: CommentTargetType }> = [
  { label: '文章', value: 'article' },
  { label: '图片', value: 'image' },
  { label: '日记', value: 'diary' },
  { label: '留言墙', value: 'guestbook' },
]

const session = useSessionStore()

const targetType = ref<CommentTargetType>('article')
const targetId = ref<number | null>(null)
const loading = ref(false)
const errorMessage = ref('')
const searched = ref(false)

const rootComments = ref<CommentViewDTO[]>([])
const replyComments = ref<CommentViewDTO[]>([])
const page = ref(1)

const canModerate = computed(() => session.can('comment.moderate'))

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

async function search(): Promise<void> {
  if (targetId.value === null || targetId.value <= 0) {
    ElMessage.warning('请输入内容 id')
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
</script>

<template>
  <div>
    <div class="lb-page-header">
      <h2>评论管理</h2>
      <span class="lb-muted">按内容查询与删除评论（comment.viewAll）</span>
    </div>

    <el-card shadow="never">
      <div class="lb-manage-filters">
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
        <el-button type="primary" size="small" :loading="loading" data-testid="comment-search" @click="search">
          查询
        </el-button>
      </div>

      <el-alert v-if="errorMessage" type="warning" style="margin: 12px 0" :closable="false">
        {{ errorMessage }}
        <el-button link size="small" @click="load">重试</el-button>
      </el-alert>

      <el-empty
        v-if="searched && grouped.length === 0 && !errorMessage"
        description="该内容暂无可见评论"
        style="padding: 40px 0"
      />

      <el-empty v-if="!searched" description="输入 targetType 与内容 id 后查询" style="padding: 40px 0" />

      <div v-if="grouped.length > 0" class="lb-cm-list" data-testid="comment-list">
        <div v-for="group in grouped" :key="group.root.id" class="lb-cm-root">
          <div class="lb-cm-row">
            <span class="lb-cm-author">{{ group.root.authorName }}</span>
            <el-tag size="small" effect="plain" :type="group.root.parentId === null ? 'info' : 'info'">
              {{ group.root.parentId === null ? '一级' : '回复' }}
            </el-tag>
            <span class="lb-muted">{{ formatDateTime(group.root.createdAt) }}</span>
            <span class="lb-cm-body">{{ group.root.body }}</span>
            <el-button v-if="canModerate" link size="small" type="danger" @click="confirmRemove(group.root)">删除</el-button>
            <span v-else class="lb-muted">—</span>
          </div>
          <div v-for="reply in group.replies" :key="reply.id" class="lb-cm-row lb-cm-reply">
            <span class="lb-cm-author">{{ reply.authorName }}</span>
            <span v-if="reply.replyToUserName" class="lb-muted">回复 @{{ reply.replyToUserName }}</span>
            <span class="lb-muted">{{ formatDateTime(reply.createdAt) }}</span>
            <span class="lb-cm-body">{{ reply.body }}</span>
            <el-button v-if="canModerate" link size="small" type="danger" @click="confirmRemove(reply)">删除</el-button>
            <span v-else class="lb-muted">—</span>
          </div>
        </div>
      </div>

      <div v-if="grouped.length > 0" class="lb-manage-pagination">
        <PaginationBar v-model:page="page" :total="grouped.length" :page-size="10" @update:page="load" />
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.lb-page-header {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 16px;
}

.lb-page-header h2 {
  margin: 0;
  font-size: 18px;
}

.lb-manage-filters {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.lb-manage-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.lb-cm-root {
  padding: 10px 0;
  border-bottom: 1px dashed var(--lb-border);
}

.lb-cm-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 4px 0;
}

.lb-cm-reply {
  padding-left: 28px;
}

.lb-cm-author {
  font-weight: 600;
  font-size: 13px;
  white-space: nowrap;
}

.lb-cm-body {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
