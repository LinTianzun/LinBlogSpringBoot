<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  ChatDotRound,
  CircleCheck,
  Document,
  Lock,
  Odometer,
  Setting,
  User,
  UserFilled,
  View,
} from '@element-plus/icons-vue'
import type { Component } from 'vue'
import { getPermissionCatalog, savePermissionMatrix } from '@/api/permission'
import { ApiError } from '@/api/request'
import { useSessionStore } from '@/stores/session'
import type {
  PermissionCatalogGroupDTO,
  PermissionKey,
  PermissionMatrix,
  RoleCode,
} from '@/types/api'

/**
 * 权限管理（§5.6，后端 M5 已实现）：catalog 分组渲染 + 矩阵编辑 + PUT 保存。
 * 超管固定拥有全部键，不在矩阵内编辑；受保护键（permission.edit/user.assignAdmin）
 * 仅超管可见入口（catalog 仍返回，编辑按 isProtected 禁用）。
 * task-21：视觉重做为亮卡片矩阵。
 * task-26：按「权限设计稿」进一步美化——分组图标块、双行权限键、角色色签、粘性保存条。
 */

const EDITABLE_ROLES: RoleCode[] = ['admin', 'editor', 'viewer']

/**
 * 初始默认授权（仅当矩阵为空时预填，让页面一打开就有可读内容）。
 * 只作用于非受保护键：管理员拿「读+管」全集，编辑拿「读+创建/管理本人内容」，访客只读。
 * 逻辑不受影响——用户仍可自由勾选后保存覆盖。
 */
const DEFAULT_ADMIN_KEYS = [
  'overview.view',
  'user.view', 'user.update', 'user.disable', 'user.resetPassword', 'permission.view',
  'article.viewAll', 'article.create', 'article.manageAll', 'article.publish',
  'gallery.viewAll', 'gallery.create', 'gallery.manageAll',
  'diary.viewAll', 'diary.create', 'diary.manageAll',
  'label.manage',
  'comment.viewAll', 'comment.moderate',
  'message.private', 'message.wallModerate',
  'file.view', 'file.upload', 'file.delete',
  'systemLog.view', 'system.setting',
] as PermissionKey[]

const DEFAULT_EDITOR_KEYS = [
  'overview.view',
  'article.viewAll', 'article.create', 'article.manageOwn',
  'gallery.viewAll', 'gallery.create', 'gallery.manageOwn',
  'diary.viewAll', 'diary.create', 'diary.manageOwn',
  'comment.viewAll', 'file.view', 'file.upload',
] as PermissionKey[]

function buildDefaultMatrix(groupsList: PermissionCatalogGroupDTO[]): PermissionMatrix {
  const valid = new Set<string>()
  groupsList.forEach((g) => g.items.forEach((it) => valid.add(it.key)))
  const keep = (keys: PermissionKey[]) => keys.filter((k) => valid.has(k))
  return {
    admin: keep(DEFAULT_ADMIN_KEYS),
    editor: keep(DEFAULT_EDITOR_KEYS),
    viewer: [],
  }
}

const ROLE_LABELS: Record<RoleCode, string> = {
  super_admin: '超级管理员',
  admin: '管理员',
  editor: '编辑',
  viewer: '访客',
}
const ROLE_ICONS: Record<RoleCode, Component> = {
  super_admin: UserFilled,
  admin: Lock,
  editor: User,
  viewer: View,
}
/** 角色语义色：管理员=主题色，编辑=正向绿，访客=中性 */
const ROLE_TONE: Record<string, string> = {
  admin: 'accent',
  editor: 'positive',
  viewer: 'neutral',
}

/** 分组图标 + 色阶（按组名关键字匹配，未命中走默认） */
const GROUP_META: Array<{ match: string; icon: Component; tone: string }> = [
  { match: '概览', icon: Odometer, tone: 'accent' },
  { match: '用户', icon: UserFilled, tone: 'positive' },
  { match: '内容', icon: Document, tone: 'accent' },
  { match: '互动', icon: ChatDotRound, tone: 'warning' },
  { match: '系统', icon: Setting, tone: 'neutral' },
]
function groupMeta(name: string): { icon: Component; tone: string } {
  const hit = GROUP_META.find((m) => name.includes(m.match))
  return hit ?? { icon: Document, tone: 'accent' }
}

const session = useSessionStore()

const groups = ref<PermissionCatalogGroupDTO[]>([])
const protectedKeys = ref<string[]>([])
const matrix = ref<PermissionMatrix>({})
const loading = ref(false)
const saving = ref(false)
const errorMessage = ref('')
const canEdit = computed(() => session.can('permission.edit'))

const totalKeys = computed(() => groups.value.reduce((sum, g) => sum + g.items.length, 0))

/** 已授权总数（三个可编辑角色当前勾选之和） */
const grantedCount = computed(() =>
  EDITABLE_ROLES.reduce((sum, role) => sum + (matrix.value[role]?.length ?? 0), 0),
)

/** 每个角色在全部键中的勾选率（用于角色列头的小进度感） */
function roleCheckedCount(role: RoleCode): number {
  return matrix.value[role]?.length ?? 0
}

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const catalog = await getPermissionCatalog()
    groups.value = catalog.groups
    protectedKeys.value = catalog.protectedKeys
    // 矩阵为空时预填一组默认授权，让页面一打开就有内容（用户可再改并保存覆盖）
    const isEmpty = EDITABLE_ROLES.every((role) => (matrix.value[role]?.length ?? 0) === 0)
    if (isEmpty) matrix.value = buildDefaultMatrix(catalog.groups)
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '加载失败'
  } finally {
    loading.value = false
  }
}

function isChecked(role: RoleCode, key: PermissionKey): boolean {
  return matrix.value[role]?.includes(key) ?? false
}

function toggle(role: RoleCode, key: PermissionKey, checked: boolean): void {
  const current = new Set(matrix.value[role] ?? [])
  if (checked) current.add(key)
  else current.delete(key)
  matrix.value = { ...matrix.value, [role]: [...current] }
}

async function save(): Promise<void> {
  saving.value = true
  try {
    const result = await savePermissionMatrix(matrix.value)
    ElMessage.success(`矩阵已保存（${Object.values(result).reduce((sum, n) => sum + n, 0)} 项授权）`)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '保存失败')
  } finally {
    saving.value = false
  }
}

function isProtected(key: string): boolean {
  return protectedKeys.value.includes(key)
}

onMounted(load)
</script>

<template>
  <div class="lb-perm">
    <!-- 页头：名词标题 + 一句说明 + 保存 -->
    <header class="lb-page-header">
      <div>
        <h2>权限管理</h2>
        <span class="lb-page-sub">按角色分配权限键 · 共 {{ totalKeys }} 个权限键</span>
      </div>
      <div class="lb-page-actions">
        <span v-if="!canEdit" class="lb-pill lb-pill--warning">
          <el-icon :size="13"><Lock /></el-icon> 只读（需 permission.edit）
        </span>
        <el-button type="primary" :loading="saving" :disabled="!canEdit" @click="save">
          保存矩阵
        </el-button>
      </div>
    </header>

    <!-- 友好说明条（亮卡片 note，非生硬 el-alert） -->
    <div class="lb-perm-note">
      <span class="lb-perm-note-icon" aria-hidden="true">
        <el-icon :size="18"><CircleCheck /></el-icon>
      </span>
      <div class="lb-perm-note-body">
        <p class="lb-perm-note-title">谁拥有全部权限？</p>
        <p class="lb-perm-note-text">
          <strong>超级管理员</strong>拥有系统全部 {{ totalKeys }} 个权限键，全库仅有一个、权限固定不可在此修改；
          <strong>受保护键</strong>（标记
          <span class="lb-pill lb-pill--warning lb-perm-inline-pill">受保护</span>）仅超管可持有，矩阵内不可勾选。
          下方按「权限分组 × 角色」编辑管理员/编辑/访客三行，保存会覆盖后端当前矩阵——请完整勾选后再保存。
        </p>
      </div>
    </div>

    <div v-loading="loading" class="lb-perm-groups">
      <div v-if="errorMessage" class="lb-perm-error">
        <span>{{ errorMessage }}（服务暂未就绪）</span>
        <el-button link type="primary" @click="load">重试</el-button>
      </div>

      <el-empty
        v-else-if="!loading && groups.length === 0"
        description="暂无权限目录数据"
        :image-size="90"
      />

      <!-- 每组一张白卡 -->
      <section
        v-for="group in groups"
        :key="group.name"
        class="lb-ed-card lb-perm-group"
        :class="`is-tone-${groupMeta(group.name).tone}`"
      >
        <div class="lb-perm-group-head">
          <span class="lb-perm-group-icon" aria-hidden="true">
            <el-icon :size="17"><component :is="groupMeta(group.name).icon" /></el-icon>
          </span>
          <div class="lb-perm-group-titles">
            <h3 class="lb-perm-group-name">{{ group.name }}</h3>
            <span class="lb-perm-group-sub">{{ group.items.length }} 个权限键</span>
          </div>
          <span class="lb-perm-group-count lb-num">{{ group.items.length }}</span>
        </div>

        <div class="lb-perm-table-wrap">
          <table class="lb-matrix-table">
            <thead>
              <tr>
                <th class="lb-col-key">权限键</th>
                <th class="lb-col-role lb-col-super" title="全库唯一，不可编辑">
                  <span class="lb-role-head">
                    <span class="lb-role-badge" aria-hidden="true">
                      <el-icon :size="13"><UserFilled /></el-icon>
                    </span>
                    <span class="lb-role-name">超级管理员</span>
                    <span class="lb-super-badge">唯一</span>
                  </span>
                </th>
                <th
                  v-for="role in EDITABLE_ROLES"
                  :key="role"
                  class="lb-col-role"
                  :class="`is-tone-${ROLE_TONE[role]}`"
                >
                  <span class="lb-role-head">
                    <span class="lb-role-badge" aria-hidden="true">
                      <el-icon :size="13"><component :is="ROLE_ICONS[role]" /></el-icon>
                    </span>
                    <span class="lb-role-name">{{ ROLE_LABELS[role] }}</span>
                    <span class="lb-role-count lb-num">{{ roleCheckedCount(role) }}</span>
                  </span>
                </th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="item in group.items"
                :key="item.key"
                :class="{ 'is-locked': isProtected(item.key) }"
              >
                <td class="lb-col-key">
                  <div class="lb-key-cell">
                    <span class="lb-key-head">
                      <span class="lb-key-name">{{ item.label }}</span>
                      <span
                        v-if="isProtected(item.key)"
                        class="lb-pill lb-pill--warning lb-key-protected"
                      >
                        <el-icon :size="11"><Lock /></el-icon> 受保护
                      </span>
                    </span>
                    <span class="lb-key-chip lb-num">{{ item.key }}</span>
                  </div>
                </td>
                <td class="lb-col-role lb-col-super">
                  <el-tooltip content="超级管理员拥有全部权限；全库唯一，不可在此修改" placement="top">
                    <el-checkbox
                      class="lb-super-check"
                      :model-value="true"
                      :disabled="true"
                    />
                  </el-tooltip>
                </td>
                <td v-for="role in EDITABLE_ROLES" :key="role" class="lb-col-role">
                  <el-checkbox
                    :model-value="isChecked(role, item.key)"
                    :disabled="!canEdit || isProtected(item.key)"
                    @update:model-value="toggle(role, item.key, $event as boolean)"
                  />
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </div>

    <!-- 底部粘性保存条（大列表滚动后仍可保存） -->
    <div v-if="groups.length > 0" class="lb-perm-foot">
      <span class="lb-perm-foot-hint">
        当前已勾选 <strong class="lb-num">{{ grantedCount }}</strong> 项授权
        <template v-if="!canEdit"> · 无编辑权限，无法保存</template>
      </span>
      <el-button type="primary" :loading="saving" :disabled="!canEdit" @click="save">
        保存矩阵
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.lb-perm {
  --perm-gap: var(--app-gap);
  padding-bottom: 84px; /* 给粘性保存条留出视觉空间 */
}

/* 页头 */
.lb-page-title {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.lb-page-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

/* ---------- 说明条（亮卡片 note） ---------- */
.lb-perm-note {
  display: flex;
  gap: 14px;
  align-items: flex-start;
  padding: 16px 20px;
  margin-bottom: var(--perm-gap);
  background: var(--ed-card);
  border: 1px solid color-mix(in srgb, var(--ed-ink) 5%, transparent);
  border-radius: var(--ed-radius-lg);
  box-shadow: var(--ed-shadow-sm);
}

.lb-perm-note-icon {
  flex: none;
  width: 36px;
  height: 36px;
  border-radius: var(--ed-radius-md);
  display: grid;
  place-items: center;
  color: var(--ed-accent-ink);
  background: var(--ed-accent-soft);
}

.lb-perm-note-title {
  margin: 0 0 4px;
  font-size: var(--ed-step-small);
  font-weight: 700;
  color: var(--ed-ink);
}

.lb-perm-note-text {
  margin: 0;
  font-size: var(--ed-step-caption);
  line-height: 1.6;
  color: var(--ed-ink-soft);
}

.lb-perm-note-text strong {
  color: var(--ed-ink);
  font-weight: 600;
}

.lb-perm-inline-pill {
  transform: translateY(1px);
}

/* ---------- 分组卡片 ---------- */
.lb-perm-groups {
  display: flex;
  flex-direction: column;
  gap: var(--perm-gap);
  min-height: 80px;
}

.lb-perm-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 18px;
  border-radius: var(--ed-radius-md);
  background: var(--app-pill-warning-bg);
  color: var(--app-pill-warning-ink);
  font-size: var(--ed-step-small);
}

.lb-perm-group {
  /* .lb-ed-card 提供白底/圆角/阴影；矩阵是操作区，取消 hover 上浮 */
  display: block;
  overflow: hidden;
}

.lb-perm-group:hover {
  transform: none;
  box-shadow: var(--ed-shadow-sm);
}

/* 分组色阶：图标块随组变色 */
.lb-perm-group.is-tone-positive {
  --perm-tone: var(--app-pill-positive-ink);
  --perm-tone-soft: var(--app-pill-positive-bg);
}
.lb-perm-group.is-tone-warning {
  --perm-tone: var(--app-pill-warning-ink);
  --perm-tone-soft: var(--app-pill-warning-bg);
}
.lb-perm-group.is-tone-neutral {
  --perm-tone: var(--ed-ink-soft);
  --perm-tone-soft: color-mix(in srgb, var(--ed-ink) 7%, var(--ed-paper));
}
.lb-perm-group.is-tone-accent,
.lb-perm-group {
  --perm-tone: var(--ed-accent-ink);
  --perm-tone-soft: var(--ed-accent-soft);
}

.lb-perm-group-head {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px clamp(18px, 2vw, 24px);
  border-bottom: 1px solid var(--ed-rule-soft);
  background: linear-gradient(
    180deg,
    color-mix(in srgb, var(--perm-tone-soft) 55%, var(--ed-card)),
    var(--ed-card)
  );
}

.lb-perm-group-icon {
  flex: none;
  width: 36px;
  height: 36px;
  border-radius: var(--ed-radius-md);
  display: grid;
  place-items: center;
  color: var(--perm-tone);
  background: var(--perm-tone-soft);
  box-shadow: 0 6px 14px -8px var(--perm-tone);
}

.lb-perm-group-titles {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.lb-perm-group-name {
  margin: 0;
  font-family: var(--ed-font-text);
  font-size: var(--ed-step-small);
  font-weight: 700;
  color: var(--ed-ink);
}

.lb-perm-group-sub {
  font-size: 12px;
  color: var(--ed-ink-soft);
}

.lb-perm-group-count {
  margin-left: auto;
  flex: none;
  min-width: 26px;
  height: 24px;
  padding: 0 9px;
  border-radius: var(--ed-radius-pill);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 700;
  color: var(--perm-tone);
  background: var(--perm-tone-soft);
}

/* ---------- 矩阵表（.lb-app 表格规范：12px 灰表头、行 hover 淡色） ---------- */
.lb-perm-table-wrap {
  padding: 2px clamp(18px, 2vw, 24px) 16px;
  overflow-x: auto;
}

.lb-matrix-table {
  width: 100%;
  border-collapse: collapse;
}

.lb-matrix-table th,
.lb-matrix-table td {
  text-align: left;
  padding: 12px 14px;
  border-bottom: 1px solid var(--ed-rule-soft);
  font-size: var(--ed-step-small);
  vertical-align: middle;
}

.lb-matrix-table thead th {
  color: var(--ed-ink-soft);
  font-weight: 600;
  font-size: 12px;
  letter-spacing: 0.01em;
  padding-top: 14px;
  padding-bottom: 10px;
}

.lb-col-key {
  width: 46%;
  min-width: 240px;
}

/* 角色列居中 */
.lb-col-role {
  text-align: center;
  width: 118px;
}

/* 超级管理员列：首位 + 主题色深墨底 + 「唯一」徽标 */
.lb-col-super {
  width: 132px;
  background: color-mix(in srgb, var(--ed-accent) 4%, var(--ed-card));
}

.lb-col-super .lb-role-badge {
  color: var(--ed-accent-ink);
  background: var(--ed-accent);
}

.lb-col-super .lb-role-name {
  color: var(--ed-accent-ink);
}

.lb-super-badge {
  flex: none;
  font-size: 10px;
  font-weight: 700;
  line-height: 1;
  padding: 3px 7px;
  border-radius: var(--ed-radius-pill);
  color: var(--ed-accent-ink);
  background: var(--ed-accent-soft);
  border: 1px solid color-mix(in srgb, var(--ed-accent) 35%, transparent);
}

/* 超管列 checkbox：永远全选且不可改，用主题色表示固定持有 */
.lb-super-check :deep(.el-checkbox__input.is-checked .el-checkbox__inner) {
  background: var(--ed-accent);
  border-color: var(--ed-accent);
  opacity: 0.55;
}

.lb-super-check :deep(.el-checkbox__input.is-disabled .el-checkbox__inner) {
  cursor: not-allowed;
}

/* 角色列头：色签 + 图标 + 已授权计数 */
.lb-role-head {
  display: inline-flex;
  align-items: center;
  gap: 7px;
}

.lb-role-badge {
  width: 24px;
  height: 24px;
  border-radius: var(--ed-radius-sm);
  display: grid;
  place-items: center;
  flex: none;
}

.lb-role-name {
  font-weight: 600;
}

.lb-role-count {
  font-size: 11px;
  font-weight: 700;
  min-width: 20px;
  padding: 1px 6px;
  border-radius: var(--ed-radius-pill);
  text-align: center;
}

/* 角色色（列头 badge + count + 勾选选中色） */
.lb-col-role.is-tone-accent .lb-role-badge {
  color: var(--ed-accent-ink);
  background: var(--ed-accent-soft);
}
.lb-col-role.is-tone-accent .lb-role-count {
  color: var(--ed-accent-ink);
  background: var(--ed-accent-soft);
}
.lb-col-role.is-tone-positive .lb-role-badge {
  color: var(--app-pill-positive-ink);
  background: var(--app-pill-positive-bg);
}
.lb-col-role.is-tone-positive .lb-role-count {
  color: var(--app-pill-positive-ink);
  background: var(--app-pill-positive-bg);
}
.lb-col-role.is-tone-neutral .lb-role-badge {
  color: var(--ed-ink-soft);
  background: color-mix(in srgb, var(--ed-ink) 7%, var(--ed-paper));
}
.lb-col-role.is-tone-neutral .lb-role-count {
  color: var(--ed-ink-soft);
  background: color-mix(in srgb, var(--ed-ink) 7%, var(--ed-paper));
}

/* 勾选框选中色跟随角色语义色 */
.lb-col-role.is-tone-accent :deep(.el-checkbox__input.is-checked .el-checkbox__inner) {
  background: var(--ed-accent);
  border-color: var(--ed-accent);
}
.lb-col-role.is-tone-positive :deep(.el-checkbox__input.is-checked .el-checkbox__inner) {
  background: var(--app-pill-positive-ink);
  border-color: var(--app-pill-positive-ink);
}
.lb-col-role.is-tone-neutral :deep(.el-checkbox__input.is-checked .el-checkbox__inner) {
  background: var(--ed-ink-soft);
  border-color: var(--ed-ink-soft);
}

/* 行 hover / 受保护行 */
.lb-matrix-table tbody tr {
  transition: background 160ms ease;
}

.lb-matrix-table tbody tr:hover {
  background: color-mix(in srgb, var(--ed-accent) 5%, var(--ed-card));
}

.lb-matrix-table tbody tr.is-locked {
  background: color-mix(in srgb, var(--app-pill-warning-bg) 55%, var(--ed-card));
  box-shadow: inset 3px 0 0 var(--app-pill-warning-ink);
}

.lb-matrix-table tbody tr.is-locked:hover {
  background: color-mix(in srgb, var(--app-pill-warning-bg) 80%, var(--ed-card));
}

.lb-matrix-table tbody tr:last-child td {
  border-bottom: none;
}

/* ---------- 权限键单元格（中文名 + mono 键 chip 两行） ---------- */
.lb-key-cell {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
}

.lb-key-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.lb-key-name {
  display: block;
  font-size: var(--ed-step-small);
  font-weight: 600;
  color: var(--ed-ink);
  line-height: 1.35;
}

.lb-key-chip {
  display: inline-block;
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 12px;
  color: var(--ed-ink);
  padding: 3px 9px;
  border-radius: var(--ed-radius-sm);
  background: color-mix(in srgb, var(--ed-ink) 5%, var(--ed-paper));
  border: 1px solid var(--ed-rule-soft);
}

.lb-key-protected {
  flex: none;
}

/* 勾选框居中 */
.lb-col-role :deep(.el-checkbox) {
  margin: 0;
}

/* ---------- 底部粘性保存条 ---------- */
.lb-perm-foot {
  position: sticky;
  bottom: 16px;
  z-index: 20;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: var(--perm-gap);
  padding: 14px 20px;
  background: color-mix(in srgb, var(--ed-card) 92%, transparent);
  backdrop-filter: blur(10px);
  border: 1px solid color-mix(in srgb, var(--ed-ink) 6%, transparent);
  border-radius: var(--ed-radius-lg);
  box-shadow: var(--ed-shadow-lg);
}

.lb-perm-foot-hint {
  font-size: var(--ed-step-caption);
  color: var(--ed-ink-soft);
}

.lb-perm-foot-hint strong {
  color: var(--ed-ink);
  font-weight: 700;
}

/* ---------- 响应式：窄屏收窄权限键列 ---------- */
@media (max-width: 720px) {
  .lb-col-key {
    width: auto;
    min-width: 180px;
  }

  .lb-col-role {
    width: 88px;
  }

  .lb-col-super {
    width: 96px;
  }

  .lb-role-head {
    flex-direction: column;
    gap: 3px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .lb-matrix-table tbody tr,
  .lb-perm-group {
    transition: none;
  }
}
</style>
