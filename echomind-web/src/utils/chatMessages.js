export function normalizeChatMessages(raw) {
  if (!Array.isArray(raw)) return []
  return raw.map((m, i) => {
    if (!m || typeof m !== 'object') return null
    return {
      clientId: m.clientId || `hist-${i}`,
      role: m.role || 'unknown',
      content: typeof m.content === 'string' ? m.content : String(m.content || ''),
      timestamp: m.timestamp || new Date().toISOString(),
      ...(m.pending !== undefined ? { pending: !!m.pending } : {}),
      ...(m.streaming !== undefined ? { streaming: !!m.streaming } : {}),
      ...(m.variant ? { variant: m.variant } : {}),
      ...(m.metadata ? { metadata: m.metadata } : {}),
      ...(m.attachments ? { attachments: m.attachments } : {})
    }
  }).filter(Boolean)
}

export function markdownPreviewText(msg) {
  if (!msg) return ''
  const raw = typeof msg === 'string' ? msg : (msg.content || '')
  if (!raw) return ''
  return raw
    .replace(/^#{1,6}\s+/gm, '')
    .replace(/\*\*(.+?)\*\*/g, '$1')
    .replace(/\*(.+?)\*/g, '$1')
    .replace(/\[(.+?)]\(.+?\)/g, '$1')
    .replace(/!\[.*?]\(.+?\)/g, '')
    .replace(/```[\s\S]*?```/g, '[代码]')
    .replace(/`(.+?)`/g, '$1')
    .replace(/^>\s+/gm, '')
    .replace(/^[-*+]\s+/gm, '')
    .replace(/^\d+\.\s+/gm, '')
    .replace(/^---+$/gm, '')
    .replace(/\n{2,}/g, '\n')
    .trim()
}
