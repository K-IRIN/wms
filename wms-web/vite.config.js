import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    // 强制监听 IPv4 回环地址（新版 Node 会把 localhost 解析成 IPv6 ::1，导致花生壳连 127.0.0.1 失败）
    host: '127.0.0.1',
    // 允许任意 Host 访问（花生壳/内网穿透的公网域名转发到本地时，Vite 默认会拦截陌生域名）
    allowedHosts: true,
    proxy: {
      // 前端 /api 请求代理到后端 8080，解决开发环境跨域
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // AGV 实时推送走 WebSocket（/api/ws/agv），需要升级协议支持
        ws: true
      }
    }
  },
  // ========== 预览模式（花生壳 / 演示用）==========
  // 直接提供 dist/ 构建产物，而不是 dev 模式的几百个源码碎片
  // 花生壳免费隧道下加载速度从几十秒降到秒开
  preview: {
    port: 5173,
    host: '127.0.0.1',
    allowedHosts: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        ws: true
      }
    }
  }
})
