# Speedtest NL

Aplicación Android que automatiza una medición combinada de conectividad:

1. Ejecuta Speedtest.
2. Conserva sus métricas en memoria.
3. Ejecuta nPerf.
4. Genera un único archivo TXT combinado.
5. Sube el resultado a Google Drive mediante el endpoint configurado.

## Compilación

El workflow **Android Build** utiliza JDK 17 y Gradle 8.4. En cada cambio ejecuta:

- `lintRelease`
- `assembleRelease`
- publicación del artefacto `SpeedtestNL-release`
- publicación del reporte `SpeedtestNL-lint`

La versión del controlador nPerf sin recargas fue validada en **Android Build #63**.

## Validación en dispositivo

Antes de fusionar cambios del flujo automático deben comprobarse estos puntos:

- Speedtest inicia y obtiene descarga, subida, ping, jitter, Result ID y URL.
- La transición a nPerf ocurre sin subir un TXT intermedio.
- El consentimiento de cookies de nPerf se acepta automáticamente.
- El botón **Iniciar test** de nPerf recibe un toque Android real.
- nPerf obtiene sus métricas y se genera un único TXT combinado.
- La siguiente repetición vuelve a abrir Speedtest con la interfaz de escritorio.

### Controlador nPerf sin recargas

La automatización de nPerf está separada en `NperfAutomation.java` y aplica estas reglas:

- interpreta la respuesta de `evaluateJavascript()` con `JSONObject`/`JSONTokener`;
- calcula el toque usando el tamaño real del viewport y del WebView;
- usa eventos Android `ACTION_DOWN` y `ACTION_UP`;
- invalida callbacks de sesiones anteriores;
- mantiene la página abierta cuando no encuentra el inicio, permitiendo un toque manual sin reinicios;
- limita los toques automáticos y evita pollings duplicados.

### Estados esperados durante nPerf

La pantalla debe avanzar por estados similares a estos:

1. `Cargando nperf.com...`
2. `Preparando automatización nperf...`
3. `Aceptando cookies nperf...` cuando el banner está visible.
4. `Enviando toque a Iniciar test (1/4)...`
5. `Toque enviado a nperf; verificando inicio...`
6. `nperf prueba X/Y — Ns`
7. `nperf completado. Guardando...`

Si el inicio automático no se confirma, la pantalla mostrará que se puede tocar **Iniciar test** manualmente y la página permanecerá abierta, sin ejecutar el ciclo de recarga anterior. Los resultados de nPerf incluyen, cuando están disponibles, su identificador y URL `/r/...` en el TXT combinado.

Para diagnóstico con Android Studio o ADB, el controlador registra detecciones, coordenadas y toques con la etiqueta de Logcat `SpeedtestNL-nPerf`.

El Pull Request debe permanecer en borrador mientras la prueba completa en un dispositivo Android no haya terminado correctamente.
