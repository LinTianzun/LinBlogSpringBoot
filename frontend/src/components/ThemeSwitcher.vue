<script setup lang="ts">
import { computed } from 'vue'
import { Brush, Sunny, Moon } from '@element-plus/icons-vue'
import { useThemeStore } from '@/stores/theme'

/**
 * 主题切换器：色卡下拉（4 套主题色）+ 亮/暗切换。
 * 复用 theme store；可用于门户顶栏，也可用于 admin。
 */
const theme = useThemeStore()

const darkLabel = computed(() => (theme.isDark ? '切到亮色' : '切到暗色'))

function selectTheme(id: string): void {
  theme.setTheme(id)
}
</script>

<template>
  <el-popover
    placement="bottom-end"
    :width="200"
    trigger="click"
    popper-class="lb-theme-popover"
  >
    <template #reference>
      <button class="lb-theme-trigger" type="button" aria-label="切换主题">
        <el-icon :size="18"><Brush /></el-icon>
        <span class="lb-theme-trigger-dot" :style="{ background: theme.currentTheme.color }" />
      </button>
    </template>

    <div class="lb-theme-panel">
      <div class="lb-theme-panel-title">主题色</div>
      <div class="lb-theme-swatches">
        <button
          v-for="t in theme.themes"
          :key="t.id"
          type="button"
          class="lb-theme-swatch"
          :class="{ active: theme.themeId === t.id }"
          :style="{ '--swatch': t.color }"
          :title="t.label"
          :aria-label="t.label"
          @click="selectTheme(t.id)"
        >
          <span class="lb-theme-swatch-fill" :style="{ background: t.color }" />
          <span class="lb-theme-swatch-label">{{ t.label }}</span>
        </button>
      </div>

      <div class="lb-theme-divider" />

      <button type="button" class="lb-theme-dark-toggle" @click="theme.toggle()">
        <el-icon :size="16"><component :is="theme.isDark ? Sunny : Moon" /></el-icon>
        <span>{{ darkLabel }}</span>
      </button>
    </div>
  </el-popover>
</template>

<style scoped>
.lb-theme-trigger {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 8px;
  border: 1px solid var(--lb-border);
  border-radius: 8px;
  background: transparent;
  color: var(--lb-text-secondary);
  cursor: pointer;
  transition: color 0.2s, border-color 0.2s, background 0.2s;
}

.lb-theme-trigger:hover {
  color: var(--lb-primary);
  border-color: color-mix(in srgb, var(--lb-primary) 40%, var(--lb-border));
  background: color-mix(in srgb, var(--lb-primary) 8%, transparent);
}

.lb-theme-trigger-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--lb-card-bg) 80%, transparent);
}

.lb-theme-panel-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--lb-text-secondary);
  margin-bottom: 10px;
}

.lb-theme-swatches {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
}

.lb-theme-swatch {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border: 1px solid var(--lb-border);
  border-radius: 8px;
  background: transparent;
  color: var(--lb-text);
  font-size: 12px;
  cursor: pointer;
  transition: border-color 0.2s, background 0.2s;
}

.lb-theme-swatch:hover {
  background: color-mix(in srgb, var(--swatch) 8%, transparent);
}

.lb-theme-swatch.active {
  border-color: var(--swatch);
  background: color-mix(in srgb, var(--swatch) 12%, transparent);
  font-weight: 600;
}

.lb-theme-swatch-fill {
  width: 16px;
  height: 16px;
  border-radius: 4px;
  flex: none;
  box-shadow: inset 0 0 0 1px rgba(0, 0, 0, 0.08);
}

.lb-theme-swatch-label {
  white-space: nowrap;
}

.lb-theme-divider {
  height: 1px;
  margin: 12px 0;
  background: var(--lb-border);
}

.lb-theme-dark-toggle {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 8px 10px;
  border: 1px solid var(--lb-border);
  border-radius: 8px;
  background: transparent;
  color: var(--lb-text);
  font-size: 13px;
  cursor: pointer;
  transition: border-color 0.2s, background 0.2s;
}

.lb-theme-dark-toggle:hover {
  border-color: color-mix(in srgb, var(--lb-primary) 40%, var(--lb-border));
  background: color-mix(in srgb, var(--lb-primary) 8%, transparent);
}
</style>
