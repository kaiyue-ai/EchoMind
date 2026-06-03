import { defineAsyncComponent, h } from 'vue'
import PageSkeleton from '../components/workbench/PageSkeleton.vue'

export function lazyView(loader, name = 'LazyRouteView') {
  let pending = null
  const load = () => {
    if (!pending) {
      pending = loader().catch((error) => {
        pending = null
        throw error
      })
    }
    return pending
  }
  const AsyncView = defineAsyncComponent({
    loader: load,
    loadingComponent: PageSkeleton,
    delay: 80,
    timeout: 30000,
    suspensible: false
  })
  return {
    name,
    preload: load,
    setup() {
      return () => h(AsyncView)
    }
  }
}
