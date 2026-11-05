/// <reference types="vitest/config" />
import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import Icons from 'unplugin-icons/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [
    vue(),
    Components({
      dts: 'components.d.ts',
      resolvers: [ElementPlusResolver()],
    }),
    Icons({ compiler: 'vue3' }),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    // 固定 dev 端口：51xx 段（5173/5174/5180…）已被本机其他项目长期占用，
    // vite 默认会静默漂移端口，导致「打开旧端口连的是别的应用」。选冷门口 +
    // strictPort：被占即报错，绝不静默漂移。
    port: 5730,
    strictPort: true,
    proxy: {
      // 后端 Spring Boot 默认 8080；SSE 一并透传（本地 dev 无 Caddy）。
      // 端口被占用时可用 VITE_API_TARGET 覆盖（如 VITE_API_TARGET=http://localhost:8081）。
      '/api': {
        target: process.env.VITE_API_TARGET || 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    rollupOptions: {
      output: {
        // 路由级动态 import 已保证视图独立 chunk；这里只做 vendor 归组，
        // 让 size-limit 的首屏/总预算 glob 有稳定目标。element-plus 单独成
        // chunk（uilib），不并入首屏 vendor（admin 专用大件不占首屏预算）。
        manualChunks(id) {
          if (!id.includes('node_modules')) return undefined
          if (/[\\/]node_modules[\\/](?:element-plus|@element-plus)[\\/]/.test(id)) {
            return 'uilib'
          }
          // Tiptap/ProseMirror 只被懒加载的编辑页引用，禁止并入首屏 vendor（§6.4 editor ≤500KB）
          // 注意 pnpm realpath 下包名带连字符（prosemirror-model），不能要求后跟路径分隔符
          if (/[\\/]node_modules[\\/](?:@tiptap[\\/]|prosemirror-)/.test(id)) return 'editor'
          return 'vendor'
        },
      },
    },
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.ts'],
    // element-plus 经 ElementPlusResolver 注入 theme-chalk CSS import；
    // 若按依赖外置交给 Node 原生加载会报 Unknown file extension ".css"，
    // 必须内联进 Vite 转换管道让 CSS 被吞掉。
    deps: {
      inline: ['element-plus', '@element-plus/icons-vue'],
    },
  },
})
