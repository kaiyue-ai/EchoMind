import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

export default defineConfig({
  plugins: [
    vue(),
    Components({
      dts: false,
      resolvers: [ElementPlusResolver({ importStyle: 'css' })]
    }),
    tailwindcss()
  ],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    assetsDir: 'assets',
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) return
          if (id.includes('node_modules/vue')
            || id.includes('node_modules/vue-router')
            || id.includes('node_modules/pinia')) {
            return 'vue'
          }
          if (id.includes('node_modules/mermaid')) return 'mermaid'
          if (id.includes('node_modules/marked')) return 'markdown'
          if (id.includes('node_modules/@element-plus/icons-vue')) return 'element-icons'
          if (id.includes('node_modules/element-plus')) return 'element'
        }
      }
    }
  }
})
