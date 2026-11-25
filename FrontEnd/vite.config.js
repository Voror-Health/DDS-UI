import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'path'
import { fileURLToPath } from 'url'

import { KNOWN_ROUTES } from './src/services/routes.js'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

// With base './' the asset URLs in index.html are relative, which breaks on deep links such as
// /vhp-dds-ui/serviceEdit/edit/<id> (the browser would look for .../serviceEdit/edit/assets/...).
// So index.html first works out the context path (same rule as services/routes.js) and sets <base href>.
function contextPathBase() {
  // In the WAR, SpaForwardFilter already puts a <base> in index.html; this is the fallback (dev server, "/").
  const script = `<script>(function(){if(document.querySelector('base'))return;var r=${JSON.stringify(KNOWN_ROUTES)};var p=location.pathname,i=-1;` +
    `r.forEach(function(x){var j=p.indexOf('/'+x);if(j>0&&(i<0||j<i))i=j;});var b;` +
    `if(i>0){b=p.substring(0,i)+'/';}else{var s=p.split('/').filter(Boolean);` +
    `b=(s.length&&r.indexOf(s[0])<0&&s[0].indexOf('.')<0)?'/'+s[0]+'/':'/';}` +
    `var e=document.createElement('base');e.href=b;document.head.appendChild(e);})();</script>`
  return {
    name: 'context-path-base',
    transformIndexHtml(html) {
      return html.replace('<head>', '<head>' + script)
    },
  }
}

// The SPA is built to dist/ and bundled into the WAR root by maven-war-plugin (see ../pom.xml).
// base './' keeps asset URLs relative, so the WAR works under any context path (e.g. /vhp-dds-ui/).
export default defineConfig({
  plugins: [vue(), contextPathBase()],
  resolve: {
    alias: { '@': path.resolve(__dirname, 'src') },
  },
  base: './',
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    chunkSizeWarningLimit: 1500,
    rollupOptions: {
      output: {
        manualChunks: {
          vendor: ['vue', 'vue-router', 'axios'],
          primevue: ['primevue/config', 'primevue/datatable', 'primevue/column', 'primevue/dialog'],
        },
      },
    },
  },
  server: {
    port: 3200,
    // dev: run the WAR on a local Tomcat at /vhp-dds-ui and open http://localhost:3200/vhp-dds-ui/
    proxy: {
      '/vhp-dds-ui/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/vhp-dds-ui/public': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
})
