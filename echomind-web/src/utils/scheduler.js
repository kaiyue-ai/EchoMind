export function runAfterPaint(callback) {
  let frame = 0
  let timeout = 0
  frame = window.requestAnimationFrame(() => {
    timeout = window.setTimeout(callback, 0)
  })
  return () => {
    if (frame) window.cancelAnimationFrame(frame)
    if (timeout) window.clearTimeout(timeout)
  }
}

export function runWhenIdle(callback, timeout = 1200) {
  if ('requestIdleCallback' in window) {
    const idleId = window.requestIdleCallback(callback, { timeout })
    return () => window.cancelIdleCallback(idleId)
  }
  return runAfterPaint(callback)
}
