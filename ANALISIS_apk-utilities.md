# Análisis del repositorio: virb3/apk-utilities

**Fecha de análisis:** 2026-10-03 · **Commit analizado:** `36f76c1` ("Match any APK for sign script", 2025-08-22) · **Rama:** master

## 1. Propósito
Suite multiplataforma (Linux/Windows vía WSL) de scripts bash para el ciclo completo de **ingeniería inversa y repaquetado de APKs**: extraer desde el dispositivo, descompilar, parchear, recompilar, firmar e reinstalar. Orientada a reverse engineering / modding (incluye integración con Lucky Patcher y Frida).

## 2. Estructura (74 archivos rastreados, ~76 MB en clone)
```
├── .config.sh            # Núcleo común: rutas, JARs, detección WSL, select_file()
├── *.sh (15)             # Scripts de la suite (ver tabla §3)
├── bin/                  # Herramientas de terceros empaquetadas (~40 MB)
│   ├── apktool_2.12.0.jar        Main-Class: brut.apktool.Main
│   ├── baksmali-2.5.2.jar        Main-Class: org.jf.baksmali.Main
│   ├── smali-2.5.2.jar           Main-Class: org.jf.smali.Main
│   ├── uber-apk-signer-1.3.0.jar Main-Class: at.favre.tools.apksigner.SignTool
│   ├── APKEditor-1.4.5.jar       Main-Class: com.reandroid.apkeditor.Main
│   └── enjarify/                 # Código fuente Python (Google, Apache-2.0, ~3610 líneas)
├── project/              # Directorio de trabajo único (todo entra/sale aquí)
└── templates/            # Snippets Frida (.js), plantillas smali, formato de parche binario
```

## 3. Los 15 scripts y su código
Todos siguen el mismo patrón minimalista (6–32 líneas): shebang → `. ".config.sh"` → `SRC=$(select_file ...)` → invocar herramienta → `echo "Saved to: ..."`.

| Script | Fase | Qué hace | Detalle de implementación |
|---|---|---|---|
| adb-pull.sh | Pull | Extrae APK(s) de una app del dispositivo | Bucle interactivo: filtra `pm list packages` con `grep`; repite hasta que hay **exactamente 1** coincidencia; luego `pm path` + `adb pull` de cada split APK. Limpia `\r` de salida CRLF |
| adb-lp-pull.sh | Pull | Saca `Modified/` de Lucky Patcher (`/sdcard/Android/data/___.lp/...`) | `adb pull --sync` |
| adb-lp-push.sh | Push | Sube `$1` al directorio LP | Sin validación de argumento |
| adb-install.sh | Push | Instala el APK seleccionado del proyecto | `select_file` + `adb install "$SRC" "$@"` |
| adb-install-multiple.sh | Push | Instala splits | Pasa `$@` directo a `adb install-multiple` (no usa select_file) |
| aapt-dump.sh | Extract | Vuelca recursos+manifest | `aapt l -a` → `<nombre>-aapt.txt` |
| apktool-decode.sh | Extract | Descompila APK → fuentes | `apktool d -f -o *-sources` (borra destino antes) |
| apktool-build.sh | Build | Recompila | `apktool b -o *-patched.apk` |
| baksmali.sh | Extract | dex → smali | `baksmali d --use-locals` (nombres de variables legibles) |
| smali.sh | Build | smali → dex parcheado | `smali a -o *-patched.dex` |
| enjarify.sh | Extract | dex/apk → jar (bytecode JVM) | Delega en `bin/enjarify.sh` (requiere Python 3) |
| dexify.sh | Build | jar → dex | Usa `dx --dex --min-sdk-version 26` del SDK Android |
| merge.sh | Build | Fusiona APKs (xapk/split) | Requiere ≥2 argumentos; copia a `merge-tmp/`, `APKEditor m`, borra tmp → `project_merged.apk` |
| sign.sh | Build | Firma | `uber-apk-signer -a` sobre el APK elegido (usa keystore debug por defecto) |
| clean.sh | Pull | Limpia proyecto | `rm -r "${WORKDIR:?}/"*` — protege contra WORKDIR vacío |

## 4. .config.sh (núcleo compartido)
- Exporta `BINDIR`, rutas de cada JAR versionado, `WORKDIR=project` y `LPDIR` (Lucky Patcher).
- **Detección WSL**: si `uname -r` contiene "microsoft" usa binarios nativos Windows (`adb.exe`, `aapt.exe`, `java.exe`, `cmd.exe /c dx.bat`); esto permite que ADB vea el USB del host. Punto clave de portabilidad.
- **`select_file(dir, glob)`**: menú interactivo `select` de bash; imprime la ruta elegida por stdout para captura con `$(...)` (los mensajes de error van a stderr y los avisos a `/dev/tty`).

## 5. Enjarify (el único código no-bash propio del flujo)
Código original de Google (Apache-2.0) incluido como fuente: traduce bytecode Dalvik a bytecode JVM capa a capa: `parsedex.py` (parser del formato DEX: header, maps, ULEB128, encodedValues/anotaciones), `jvm/writeir.py` (**pasada central**: convierte instrucciones Dalvik + registros a un IR JVM con pila), optimizadores (`stack.py` reconstruye tipos de la pila, `registers.py` reasigna registros, `jumps.py` arregla branch targets tras cambio de tamaño de instrucciones), `writeclass.py`/`writebytecode.py` (emiten .class válidos dentro de un JAR). `main.py` soporta `-f/--force`, `--fast`, maneja clases duplicadas y errores por clase sin abortar. Compilación verificada OK con Python 3.

## 6. Plantillas
- **frida-snippets/**: 6 scripts JS. El más importante es `ssl-unpin.js` (544 líneas, base de httptoolkit/frida-android-unpinning): hooks estáticos a TrustManager/OkHostnameVerifier/Conscrypt/BouncyCastle/OkHttp3 + **mecanismo dinámico**: intercepta el constructor de `SSLPeerUnverifiedException`, lee la traza de pila y auto-parchea en runtime el método que lanzó el error (estrategia "hook-and-disable"). Resto: volcados estáticos/de instancia, enumerar clases, hook preservando comportamiento, stack trace en Toast.show.
- **frida-gadget/**: `libgadget.config.so` es JSON puro (interacción tipo script) — técnica para inyectar Frida Gadget repaqueteando el APK.
- **smali-print-log.smali / smali-print-stack-trace/**: fragmentos listos para pegar en métodos smali (Log.e con TAG "ABC-TAG", Tracer.logStackTrace vía `getStackTraceString`).
- **aaa.com.package.name.txt**: formato propietario de parche binario (secciones `[PACKAGE]/[CLASSES]`, reglas `{"original":"12 34 ?? 78"}→{"replaced":...}` con comodines estilo IDA) — orientado a parches hex sobre las fuentes decodificadas.

## 7. Verificaciones realizadas
- `bash -n` en los 16 shell scripts: **sin errores de sintaxis**.
- `python3 -m py_compile` en módulos clave de enjarify: **OK**.
- Los 5 JARs son artefactos válidos con las Main-Class esperadas (nombres y firmas coinciden con las herramientas oficiales upstream).
- Permisos ejecutables correctos, salvo **merge.sh = 644** (no ejecutable: bug menor).

## 8. Hallazgos / riesgos
1. **Inconsistencia en sign.sh**: calcula `DEST` (`*-signed.apk`) pero nunca la usa — uber-apk-signer escribe junto al APK original con su propio sufijo. El "Saved to:" puede mentir (arreglado parcialmente en el último commit al aceptar cualquier `*.apk`).
2. **select_file usa `eval ls -d`**: vulnerable ante espacios/globs raros en rutas; el menú también se rompe con nombres con espacios (palabras sin comillar en `select`).
3. **adb-pull.sh**: `grep "$PKG"` sin anclar → subcadena; podría quedar vacío si un grep falla bajo `set -e`; dependiente de orden del dispositivo.
4. **Versiones fijas y algo antiguas**: smali/baksmali 2.5.2 (2019), uber-apk-signer 1.3.0 (2019), APKEditor 1.4.5; apktool 2.12.0 sí es reciente. uber-apk-signer viejo puede fallar con APKs targetSdk≥30/ES256; falta soporte de apksigner (v2/v3/v4 oficial) más allá del que provee uber.
5. **`dx` está deprecado** en build-tools recientes (reemplazado por `d8`); dexify.sh fallará en SDKs modernos.
6. **JARs (~40 MB) commiteados en el repo**: sin checksums ni firma verificable; supply-chain confiable solo si se confía en el autor. No hay tests ni CI.
7. **Sin validación de argumentos** en adb-lp-push/merge/install-multiple; `clean.sh` borra todo con glob (aunque con `${WORKDIR:?}` que es buena práctica).
8. **Enfoque dual legal/ético**: útil para auditoría de seguridad y parches propios, pero ssl-unpin + templates de parcheo binario + integración Lucky Patcher lo sitúan también en terreno de bypass de SSL pinning y modificación de apps de terceros.

## 9. Calidad global
Código muy simple, cohesivo e intencionalmente minimalista: convención consistente (sufijos `-sources`, `-smali`, `-patched` que encadenan el workflow `adb-pull > apktool-decode > editar > apktool-build > sign > adb-install`), detección WSL bien resuelta y documentación honesta en README. Debilidades: ausencia de manejo de errores más allá de `set -e`, hardening insuficiente de `select_file`, dependencias empaquetadas sin verificación y herramientas datadas. Nota razonable: **7/10** como toolkit personal; le faltarían CI, tests y actualización de JARs para producción compartida.
