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

      <section v-if="background.mode === 'image'" class="appearance-section">
        <span class="appearance-label">图片来源</span>
        <div class="background-image-tools">
          <label class="image-upload-field">
            <input type="file" accept="image/*" @change="handleImageSelected">
            <el-icon><Upload /></el-icon>
            <span>上传图片</span>
          </label>
          <el-button
            text
            size="small"
            :disabled="!background.imageUrl"
            @click="uiStore.setBackground({ imageUrl: '' })"
          >
            清除
          </el-button>
        </div>
        <el-input
          :model-value="background.imageUrl"
          class="background-url-input"
          size="small"
          placeholder="粘贴图片 URL，或上传本地图片"
          clearable
          @update:model-value="imageUrl => uiStore.setBackground({ imageUrl })"
        >
          <template #prefix>
            <el-icon><Picture /></el-icon>
          </template>
        </el-input>
        <div
          v-if="hasRenderableImage"
          class="background-image-preview"
          :style="imagePreviewStyle"
        ></div>
      </section>

      <section v-if="background.mode === 'image'" class="appearance-section">
        <span class="appearance-label">图片填充</span>
        <el-radio-group
          class="appearance-choice-group"
          :model-value="background.imageFit"
          size="small"
          @update:model-value="imageFit => uiStore.setBackground({ imageFit })"
        >
          <el-radio-button
            v-for="option in imageFitOptions"
            :key="option.value"
            :value="option.value"
          >
            {{ option.label }}
          </el-radio-button>
        </el-radio-group>
      </section>

      <section v-if="background.mode === 'image'" class="appearance-section">
        <span class="appearance-label">面板质感</span>
        <el-radio-group
          class="appearance-choice-group"
          :model-value="background.glassEffect"
          size="small"
          @update:model-value="glassEffect => uiStore.setBackground({ glassEffect })"
        >
          <el-radio-button
            v-for="option in glassEffectOptions"
            :key="option.value"
            :value="option.value"
          >
            {{ option.label }}
          </el-radio-button>
        </el-radio-group>
      </section>

      <section class="appearance-section">
        <span class="appearance-label">玻璃模糊 {{ background.glassBlur }}%</span>
        <el-slider
          :model-value="background.glassBlur"
          :min="0"
          :max="100"
          :step="5"
          size="small"
          @update:model-value="glassBlur => uiStore.setBackground({ glassBlur })"
        />
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
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import { storeToRefs } from 'pinia'
import { Picture, RefreshLeft, Setting, Upload } from '@element-plus/icons-vue'
import { BACKGROUND_IMAGE_MAX_BYTES, renderableImageUrl, useUiStore } from '../../stores/ui'

const uiStore = useUiStore()
const { background, backgroundModeLabel, theme, themeLabel, themeToggleLabel } = storeToRefs(uiStore)

const themeOptions = [
  { label: '浅色', value: 'light' },
  { label: '深色', value: 'dark' }
]

const backgroundOptions = [
  { label: '主题', value: 'theme' },
  { label: '纯色', value: 'solid' },
  { label: '渐变', value: 'gradient' },
  { label: '图片', value: 'image' }
]

const imageFitOptions = [
  { label: '铺满', value: 'cover' },
  { label: '完整', value: 'contain' },
  { label: '平铺', value: 'repeat' }
]

const glassEffectOptions = [
  { label: '磨砂', value: 'frosted' },
  { label: '清透', value: 'clear' }
]

const imagePreviewStyle = computed(() => {
  const imageUrl = renderableImageUrl(background.value.imageUrl)
  if (!imageUrl) return {}
  return {
    backgroundImage: `url("${imageUrl.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}")`
  }
})
const hasRenderableImage = computed(() => Boolean(imagePreviewStyle.value.backgroundImage))

function handleImageSelected(event) {
  const input = event.target
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (!file.type.startsWith('image/')) {
    ElMessage.warning('请选择图片文件')
    return
  }
  if (file.size > BACKGROUND_IMAGE_MAX_BYTES) {
    ElMessage.warning('图片不能超过 2MB，较大的图片建议使用 URL')
    return
  }

  const reader = new FileReader()
  reader.onload = () => {
    if (typeof reader.result === 'string') {
      uiStore.setBackground({ mode: 'image', imageUrl: reader.result })
    }
  }
  reader.onerror = () => ElMessage.error('图片读取失败，请换一张试试')
  reader.readAsDataURL(file)
}
</script>
