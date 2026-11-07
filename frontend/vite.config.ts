import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// En desarrollo (npm run dev) las llamadas a /api se envían al servidor Java en el puerto 8080.
export default defineConfig({
  plugins: [react()],
  server: { port: 5173, proxy: { '/api': { target: 'http://localhost:8080', changeOrigin: true } } },
  build: { outDir: 'dist', sourcemap: false },
});
