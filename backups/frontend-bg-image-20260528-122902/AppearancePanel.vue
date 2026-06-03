<template>
  <el-popover
    placement="top-start"
    trigger="click"
    width="288"
    popper-class="appearance-popover"
  >
    <template #reference>
      <slot>
        <el-button text :title="themeToggleLabel">
          <el-icon><Setting /></el-icon>
        </el-button>
      </slot>
    </template>

    <div class="appearance-panel">
      <header class="appearance-panel-head">
        <div>
          <strong>外观</strong>
          <span>{{ themeLabel }} · {{ backgroundModeLabel }}</span>
        </div>
        <el-button text size="small" title="恢复默认背景" @click="uiStore.resetBackground()">
          <el-icon><RefreshLeft /></el-icon>
        </el-button>
      </header>

      <section class="appearance-section">
        <span class="appearance-label">主题</span>
        <el-radio-group
          class="appearance-choice-group"
          :model-value="theme"
          size="small"
          @update:model-value="uiStore.setTheme"
        >
          <el-radio-button
            v-for="option in themeOptions"
            :key="option.value"
            :value="option.value"
          >
            {{ option.label }}
          </el-radio-button>
        </el-radio-group>
      </section>

      <section class="appearance-section">
        <span class="appearance-label">背景填充</span>
        <el-radio-group
          class="appearance-choice-group"
          :model-value="background.mode"
          size="small"
          @update:model-value="mode => uiStore.setBackground({ mode })"
        >
          <el-radio-button
            v-for="option in backgroundOptions"
            :key="option.value"
            :value="option.value"
          >
            {{ option.label }}
          </el-radio-button>
        </el-radio-group>
      </section>

      <section v-if="background.mode === 'solid'" class="appearance-section">
        <span class="appearance-label">填充色</span>
        <label class="color-field">
          <input
            :value="background.solidColor"
            type="color"
            @input="event => uiStore.setBackground({ solidColor: event.target.value })"
          >
          <span>{{ background.solidColor }}</span>
        </label>
      </section>

      <section v-if="background.mode === 'gradient'" class="appearance-section">
        <span class="appearance-label">渐变色</span>
        <div class="color-grid">
          <label class="color-field">
            <input
              :value="background.gradientFrom"
              type="color"
              @input="event => uiStore.setBackground({ gradientFrom: event.target.value })"
            >
            <span>起点</span>
          </label>
          <label class="color-field">
            <input
              :value="background.gradientTo"
              type="color"
              @input="event => uiStore.setBackground({ gradientTo: event.target.value })"
            >
            <span>终点</span>
          </label>
        </div>
      </section>

      <section v-if="background.mode !== 'theme'" class="appearance-section">
        <span class="appearance-label">透明度 {{ background.opacity }}%</span>
        <el-slider
          :model-value="background.opacity"
          :min="0"
          :max="100"
          :step="5"
          size="small"
          @update:model-value="opacity => uiStore.setBackground({ opacity })"
        />
      </section>
    </div>
  </el-popover>
</template>

<script setup>
import { storeToRefs } from 'pinia'
import { RefreshLeft, Setting } from '@element-plus/icons-vue'
import { useUiStore } from '../../stores/ui'

const uiStore = useUiStore()
const { background, backgroundModeLabel, theme, themeLabel, themeToggleLabel } = storeToRefs(uiStore)

const themeOptions = [
  { label: '浅色', value: 'light' },
  { label: '深色', value: 'dark' }
]

const backgroundOptions = [
  { label: '主题', value: 'theme' },
  { label: '纯色', value: 'solid' },
  { label: '渐变', value: 'gradient' }
]
</script>
