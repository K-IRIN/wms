import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    // 允许任意 Host 访问（花生壳/内网穿透的公网域名转发到本地时，Vite 默认会拦截陌生域名）
    allowedHosts: true,
    proxy: {
      // 前端 /api 请求代理到后端 8080，解决开发环境跨域
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
