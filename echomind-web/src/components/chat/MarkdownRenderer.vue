<template>
  <div class="markdown-body" v-html="html" @click="handleClick"></div>
</template>

<script setup>
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import { marked } from 'marked'

const props = defineProps({
  content: { type: String, default: '' }
})

const renderer = new marked.Renderer()

renderer.html = (html) => escapeHtml(html)
renderer.link = (href, title, text) => {
  const safeText = marked.parseInline(String(text ?? ''))
  const safeHref = safeUrl(href)
  if (!safeHref) return safeText
  const titleAttr = title ? ` title="${escapeHtml(title)}"` : ''
  return `<a href="${safeHref}"${titleAttr} target="_blank" rel="noopener noreferrer">${safeText}</a>`
}
renderer.image = (href, title, text) => {
  const safeHref = safeUrl(href)
  if (!safeHref) return escapeHtml(text)
  const titleAttr = title ? ` title="${escapeHtml(title)}"` : ''
  return `<img src="${safeHref}" alt="${escapeHtml(text)}"${titleAttr}>`
}
renderer.code = (code, language) => {
  const normalizedLanguage = sanitizeCodeLanguage(language)
  const languageClass = normalizedLanguage ? ` class="language-${normalizedLanguage}"` : ''
  const label = normalizedLanguage || 'code'
  return `<div class="code-block">
    <div class="code-block-toolbar">
      <span>${escapeHtml(label)}</span>
      <button type="button" class="code-copy-btn">复制</button>
    </div>
    <pre><code${languageClass}>${escapeHtml(code)}</code></pre>
  </div>`
}

marked.setOptions({ breaks: true, gfm: true, renderer })

const html = computed(() => props.content ? marked.parse(props.content) : '')

async function handleClick(event) {
  const button = event.target?.closest?.('.code-copy-btn')
  if (!button) return
  const block = button.closest('.code-block')
  const code = block?.querySelector('pre code')?.innerText || ''
  if (!code) return
  try {
    await copyText(code)
    button.textContent = '已复制'
    button.classList.add('copied')
    window.setTimeout(() => {
      button.textContent = '复制'
      button.classList.remove('copied')
    }, 1200)
  } catch (e) {
    ElMessage.error('复制失败，请手动选择代码复制')
  }
}

async function copyText(text) {
  if (navigator.clipboard && window.isSecureContext) {
    await navigator.clipboard.writeText(text)
    return
  }
  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.setAttribute('readonly', '')
  textarea.style.position = 'fixed'
  textarea.style.left = '-9999px'
  textarea.style.top = '-9999px'
  document.body.appendChild(textarea)
  textarea.select()
  const copied = document.execCommand('copy')
  document.body.removeChild(textarea)
  if (!copied) throw new Error('copy failed')
}

function escapeHtml(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

function safeUrl(value) {
  const raw = String(value ?? '').trim()
  if (!raw) return ''
  try {
    const parsed = new URL(raw, window.location.origin)
    const protocol = parsed.protocol.toLowerCase()
    if (!['http:', 'https:', 'mailto:'].includes(protocol)) return ''
    return escapeHtml(raw)
  } catch (e) {
    return ''
  }
}

function sanitizeCodeLanguage(value) {
  return String(value ?? '')
    .trim()
    .split(/\s+/)[0]
    .toLowerCase()
    .replace(/[^a-z0-9_+#.-]/g, '')
}
</script>
