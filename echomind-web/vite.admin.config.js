import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

function adminHistoryFallback() {
  return {
    name: 'echomind-admin-history-fallback',
    configureServer(server) {
      server.middlewares.use((req, _res, next) => {
        const pathname = req.url?.split('?')[0] || '/'
        const isAsset = pathname.includes('.')
          || pathname.startsWith('/@')
          || pathname.startsWith('/api')
          || pathname.startsWith('/src/')
          || pathname.startsWith('/node_modules/')

        if (!isAsset) {
          req.url = '/index.admin.html'
        }
        next()
      })
    }
  }
}

export default defineConfig({
  plugins: [
    adminHistoryFallback(),
    vue(),
    Components({
      dts: false,
      resolvers: [ElementPlusResolver({ importStyle: 'css' })]
    }),
    tailwindcss()
  ],
  server: {
    port: 5174,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist-admin',
    assetsDir: 'assets',
    rollupOptions: {
      input: 'index.admin.html',
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) return
          if (id.includes('node_modules/vue')
            || id.includes('node_modules/vue-router')
            || id.includes('node_modules/pinia')) {
            return 'vue'
          }
          if (id.includes('node_modules/echarts')) return 'charts'
          if (id.includes('node_modules/@element-plus/icons-vue')) return 'element-icons'
          if (id.includes('node_modules/element-plus')) return 'element'
        }
      }
    }
  }
})
