/// <reference types="vitest/config" />
import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import Icons from 'unplugin-icons/vite'
import Components from 'unplugin-vue-components/vite'
import { NaiveUiResolver } from 'unplugin-vue-components/resolvers'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [
    vue(),
    Components({
      dts: 'components.d.ts',
      resolvers: [NaiveUiResolver()],
    }),
    Icons({ compiler: 'vue3' }),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    proxy: {
      // 后端 Spring Boot 8080；SSE 一并透传（本地 dev 无 Caddy）
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    rollupOptions: {
      output: {
        // 路由级动态 import 已保证视图独立 chunk；这里只做 vendor 归组，
        // 让 size-limit 的首屏/总预算 glob 有稳定目标。echarts 只允许被
        // 概览页动态 import，禁止并入首屏 vendor。
        manualChunks(id) {
          if (!id.includes('node_modules')) return undefined
          if (/naive-ui|[\\/]css-render|[\\/]vueuc|[\\/]seemly|[\\/]treemate|[\\/]vooks|[\\/]vdirs|[\\/]date-fns/.test(id)) {
            return 'naive'
          }
          if (/[\\/]node_modules[\\/]echarts/.test(id)) return 'echarts'
          return 'vendor'
        },
      },
    },
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.ts'],
  },
})
