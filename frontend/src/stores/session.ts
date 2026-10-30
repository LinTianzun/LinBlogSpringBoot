import { defineStore } from 'pinia'
import { logout as apiLogout, me } from '@/api/auth'
import type { PermissionKey, UserDTO } from '@/types/api'

/**
 * 会话 store（§4.3 / §5.1 / §7.2）：
 * - init() 调 GET /auth/me：成功填充用户与权限；认证失败保持访客（门户游客可浏览）；
 * - can(key)：isSuperAdmin 短路返回 true，否则查权限集合；
 * - localStorage 只缓存首屏快照（避免刷新闪登录态），权限以后端为准；
 * - 运行期刷新失败：网络级瞬时失败不清会话（保留旧快照，继承原项目语义）；
 *   仅 40100/40102 经全局认证错误处理器走 clear()。
 */

const SNAPSHOT_KEY = 'linblog.session'

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
     * 成功 → 填充并持久化快照；认证失败 → 清为访客；其他失败 → 保持访客（快照不回滚 UI）。
     */
    async init(): Promise<void> {
      if (this.initPromise) return this.initPromise
      this.initPromise = (async () => {
        try {
          const result = await me()
          this.user = result.user
          this.permissions = result.permissions
          this.isSuperAdmin = result.isSuperAdmin
          this.loaded = true
          writeSnapshot({ user: result.user, permissions: result.permissions, isSuperAdmin: result.isSuperAdmin })
        } catch {
          // /auth/me 失败一律按访客处理；认证类失败由 request 层静默抛错，不再全局跳转
          this.clear()
          this.loaded = true
        }
      })()
      return this.initPromise
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
    },
  },
})
