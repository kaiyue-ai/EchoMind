import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

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
  plugins: [adminHistoryFallback(), vue(), tailwindcss()],
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
        manualChunks: {
          vue: ['vue', 'vue-router', 'pinia'],
          element: ['element-plus', '@element-plus/icons-vue']
        }
      }
    }
  }
})
