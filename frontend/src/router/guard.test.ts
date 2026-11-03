import { describe, expect, it } from 'vitest'
import { mustChangePasswordRedirect } from '@/router'

/**
 * P3 强制首改守卫（纯函数）：已登录 + mustChangePassword=1 时，除改密页外
 * 一律重定向 /change-password；未登录或标记已清则放行。
 */
describe('mustChangePasswordRedirect()', () => {
  it('已登录且需首改：访问其他页 → 重定向改密页', () => {
    expect(mustChangePasswordRedirect(true, true, '/')).not.toBe(true)
    expect(mustChangePasswordRedirect(true, true, '/admin/users')).not.toBe(true)
    expect(mustChangePasswordRedirect(true, true, '/login-register')).not.toBe(true)
  })

  it('已登录且需首改：访问改密页本身 → 放行（避免死循环）', () => {
    expect(mustChangePasswordRedirect(true, true, '/change-password')).toBe(true)
  })

  it('未登录：即使快照残留标记也放行（由认证守卫处理）', () => {
    expect(mustChangePasswordRedirect(false, true, '/')).toBe(true)
  })

  it('已登录但标记已清（0）：放行', () => {
    expect(mustChangePasswordRedirect(true, false, '/')).toBe(true)
  })
})
