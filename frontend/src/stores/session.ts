import { defineStore } from 'pinia'
import { ApiError } from '@/api/request'
import { logout as apiLogout, me } from '@/api/auth'
import type { PermissionKey, UserDTO } from '@/types/api'

/**
 * 会话 store（§4.3 / §5.1 / §7.2）：
 * - init() 调 GET /auth/me：成功填充用户与权限并持久化快照；
 *   40100/40102（认证失败）→ 清为访客；网络级瞬时失败 → 保留旧快照（继承原项目语义，
 *   不因一次断网把已登录用户打回游客态），无快照时自然为访客；
 * - can(key)：isSuperAdmin 短路返回 true，否则查权限集合；
 * - localStorage 只缓存首屏快照（避免刷新闪登录态），权限以后端为准。
 */

const SNAPSHOT_KEY = 'linblog.session'

/** init 单飞：并发路由守卫共享同一次 /auth/me */
let initPromise: Promise<void> | null = null

interface SessionSnapshot {
  user: UserDTO
  permissions: PermissionKey[]
  isSuperAdmin: boolean
}

function readSnapshot(): SessionSnapshot | null {
  try {
    const raw = localStorage.getItem(SNAPSHOT_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as Partial<SessionSnapshot>
    if (!parsed || typeof parsed !== 'object' || !parsed.user || !Array.isArray(parsed.permissions)) {
      return null
    }
    return parsed as SessionSnapshot
  } catch {
    return null
  }
}

function writeSnapshot(snapshot: SessionSnapshot): void {
  try {
    localStorage.setItem(SNAPSHOT_KEY, JSON.stringify(snapshot))
  } catch {
    // 存储不可用时静默降级为无快照
  }
}

function clearSnapshot(): void {
  try {
    localStorage.removeItem(SNAPSHOT_KEY)
  } catch {
    // 忽略
  }
}

export const useSessionStore = defineStore('session', {
  state: () => {
    const snapshot = readSnapshot()
    return {
      user: snapshot?.user ?? null,
      permissions: snapshot?.permissions ?? ([] as PermissionKey[]),
      isSuperAdmin: snapshot?.isSuperAdmin ?? false,
      loaded: false,
    }
  },
  getters: {
    isLoggedIn: (state): boolean => state.user !== null,
  },
  actions: {
    /** 超管短路：任意权限键均返回 true */
    can(key: PermissionKey): boolean {
      if (this.isSuperAdmin) return true
      return this.permissions.includes(key)
    },

    /** 任一命中即通过（路由 meta.permission 语义为「或」） */
    canAny(keys: readonly PermissionKey[]): boolean {
      return keys.some((key) => this.can(key))
    },

    /**
     * 全局守卫首次调用：并发场景共享同一次 /auth/me 请求。
     * 成功 → 填充并持久化快照；40100/40102 → 清为访客；
     * 网络级瞬时失败 → 保留旧快照（无快照即访客），loaded 置位不阻塞路由。
     */
    async init(): Promise<void> {
      if (initPromise) return initPromise
      initPromise = (async () => {
        try {
          const result = await me()
          this.user = result.user
          this.permissions = result.permissions
          this.isSuperAdmin = result.isSuperAdmin
          this.loaded = true
          writeSnapshot({ user: result.user, permissions: result.permissions, isSuperAdmin: result.isSuperAdmin })
        } catch (error) {
          if (error instanceof ApiError && (error.code === 40100 || error.code === 40102)) {
            this.clear()
          }
          // 其余失败保留现有状态（快照仍在 → 已登录外观保留）
          this.loaded = true
        }
      })()
      return initPromise
    },

    /** 登录/注册成功后强制重拉 /auth/me（绕过 init 单飞缓存） */
    async reload(): Promise<void> {
      initPromise = null
      this.loaded = false
      await this.init()
    },

    /** 清会话（认证失败/主动退出共用）；运行期瞬时失败不走这里 → 旧快照保留 */
    clear(): void {
      this.user = null
      this.permissions = []
      this.isSuperAdmin = false
      clearSnapshot()
    },

    async logout(): Promise<void> {
      try {
        await apiLogout()
      } catch {
        // 后端不可达也照样清本地会话
      }
      this.clear()
      initPromise = null
    },
  },
})
