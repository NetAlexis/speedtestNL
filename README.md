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

## Validación en dispositivo

Antes de fusionar cambios del flujo automático deben comprobarse estos puntos:

- Speedtest inicia y obtiene descarga, subida, ping, jitter, Result ID y URL.
- La transición a nPerf ocurre sin subir un TXT intermedio.
- El consentimiento de cookies de nPerf se acepta automáticamente.
- El botón **Iniciar test** de nPerf recibe una activación nativa.
- nPerf obtiene sus métricas y se genera un único TXT combinado.
- La siguiente repetición vuelve a abrir Speedtest con la interfaz de escritorio.

### Compatibilidad del motor nPerf

La configuración de nPerf es independiente de la configuración funcional de Speedtest:

- usa la versión real de Chrome del Android System WebView en el user agent de escritorio;
- habilita almacenamiento DOM y base de datos web;
- conserva cookies y permite cookies de terceros;
- habilita aceleración por hardware;
- permite contenido mixto únicamente durante la fase nPerf;
- autoriza tráfico HTTP/HTTPS para `nperf.com` y `nperf.net`, manteniendo bloqueado el texto claro para los demás dominios;
- registra errores de consola y respuestas HTTP para diagnóstico.

Cuando aparece un error como **“no fue posible inicializar”**, la aplicación cancela los callbacks de la sesión, aplica una sola vez un perfil alternativo compatible y vuelve a cargar nPerf. Si el segundo intento tampoco inicializa, muestra el error y no entra en un ciclo infinito.

### Controlador de activación nPerf

La automatización está separada en `NperfAutomation.java` y aplica estas reglas:

- interpreta `evaluateJavascript()` con `JSONObject`/`JSONTokener`;
- espera mientras nPerf muestra `Inicializando`;
- detecta el banner y activa `OK` mediante `ENTER` y toque Android;
- enfoca y activa **Iniciar test** mediante `ENTER` y toque Android;
- utiliza el canvas o un barrido visual limitado como respaldo;
- calcula coordenadas con el viewport visible y el tamaño real del WebView;
- invalida callbacks de sesiones anteriores;
- detiene la automatización si el motor no responde tras intentos limitados;
- evita recargas continuas y pollings duplicados.

### Estados esperados durante nPerf

La pantalla debe avanzar por estados similares a estos:

1. `Cargando nperf.com...`
2. `Preparando automatización nperf...`
3. `nperf inicializando motor y servidor...`
4. `Aceptando cookies nperf...` cuando el banner está visible.
5. `Activando Iniciar test (1/5, button)...` o `(canvas)...`
6. `Activación enviada a nperf; verificando motor...`
7. `nperf prueba X/Y — Ns`
8. `nperf completado. Guardando...`

Para diagnóstico con Android Studio o ADB, los eventos se registran bajo `SpeedtestNL-nPerf` y `SpeedtestNL-Web`.

El Pull Request debe permanecer en borrador mientras la prueba completa en un dispositivo Android no haya terminado correctamente.
