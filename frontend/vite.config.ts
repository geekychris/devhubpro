import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        // Point the dev UI at another backend with DEVPORTAL_API_URL=http://host:port.
        target: process.env.DEVPORTAL_API_URL ?? 'http://localhost:8081',
        changeOrigin: true,
      },
    },
  },
});
