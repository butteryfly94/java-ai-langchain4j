import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [
    vue()
  ],
  // server: {
  //   proxy: {
  //     '/api': {
  //       target: ' http://localhost:8080',
  //       changeOrigin: true,
  //       //rewrite: (path) => path.replace(/^\/api/, ''), // 去掉 /api 前缀
  //     },
  //   },
  // },
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  // server: {
  //   allowedHosts: [
  //     '550ffd32.nat123.top', // 添加你的特定主机
  //     '.nat123.top' // 或者允许整个域名
  //   ],
  // }
  server: {
    // 允许的域名配置
    allowedHosts: [
      '550ffd32.nat123.top', // 你的特定域名
      '.nat123.top' // 允许所有子域名
    ],
    
    // 代理配置
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // rewrite: (path) => path.replace(/^\/api/, ''),
      }
    },
    
    // 可选：其他服务器配置
    host: true, // 监听所有网络接口
    port: 5173, // 指定端口
    strictPort: true // 如果端口被占用则报错
  }
})
