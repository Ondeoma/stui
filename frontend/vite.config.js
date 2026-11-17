import { defineConfig } from 'vite'
import scalaJS from './vite-scalajs.js'

const backend = process.env.BACKEND ?? 'http://localhost:8098'

export default defineConfig({
  // 画面(Scalactive)は sbt の web プロジェクトから読み込む
  plugins: [scalaJS({ cwd: '..', projectID: 'web' })],
  server: {
    // IPv4 で待ち受ける（localhost を 127.0.0.1 に解決する Java などのサーバーから呼べるように）。LAN には公開しない
    host: '127.0.0.1',
    proxy: { '/api': backend, '/auth': backend, '/hooks': backend, '/mcp': backend, '/oauth': backend },
  },
  build: { chunkSizeWarningLimit: 2000 },
})
