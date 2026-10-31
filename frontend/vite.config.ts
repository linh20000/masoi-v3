import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const backend = env.VITE_BACKEND_URL || 'http://127.0.0.1:8080';
  const websocket = backend.replace(/^http/, 'ws');

  return {
    plugins: [react()],
    server: {
      proxy: {
        '/api': { target: backend, changeOrigin: true },
        '/ws': { target: websocket, changeOrigin: true, ws: true },
      },
    },
  };
});
