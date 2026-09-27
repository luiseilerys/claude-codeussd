# Códigos Cuba — USSD

App web (PWA) con todos los códigos USSD de Cubacel, ETECSA y Transfermóvil (BANMET/BANDEC), con búsqueda, favoritos, marcado directo, copiar código y códigos con parámetros (recarga, transferencias, desvío de llamadas, etc).

## 1. Subir estos archivos a tu repositorio

Clona tu repo y copia estos 5 archivos en la raíz: `index.html`, `manifest.json`, `sw.js`, `icon-192.png`, `icon-512.png`.

```bash
git clone https://github.com/luiseilerys/claude-codeussd.git
cd claude-codeussd
# copia aquí los 5 archivos descargados
git add .
git commit -m "App de códigos USSD de Cuba"
git push origin main
```

## 2. Activar GitHub Pages (gratis)

En el repo: **Settings → Pages → Source: "Deploy from a branch" → Branch: main / (root) → Save**.
En unos minutos tu app estará en:
`https://luiseilerys.github.io/claude-codeussd/`

## 3. Generar el APK con PWABuilder (gratis, sin cuenta)

1. Entra a **https://www.pwabuilder.com**
2. Pega la URL de GitHub Pages del paso 2 y presiona "Start".
3. Cuando termine el análisis, ve a la pestaña **Android** → **Generate Package**.
4. Descarga el `.apk` (o `.aab` si luego quieres subirlo a Play Store).
5. Pásalo a tu teléfono e instálalo (activa "Instalar apps de orígenes desconocidos" si Android lo pide).

## Notas
- El manifest ya incluye los íconos, colores y modo "standalone" (pantalla completa, sin barra del navegador).
- El service worker permite que la lista de códigos funcione sin conexión una vez abierta la primera vez.
- Todos los códigos se marcan como llamada (`tel:`), tal como en un teléfono normal.
