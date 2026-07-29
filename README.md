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
- El botón **Iniciar test** de nPerf recibe un toque Android real.
- nPerf obtiene sus métricas y se genera un único TXT combinado.
- La siguiente repetición vuelve a abrir Speedtest con la interfaz de escritorio.

### Estados esperados durante nPerf

La pantalla debe avanzar por estados similares a estos:

1. `Cargando nperf.com...`
2. `Revisando consentimiento nperf...`
3. `Aceptando cookies nperf...` cuando el banner está visible.
4. `Buscando inicio nperf (1/8)...`
5. `Toque Android sobre nperf (button)...` o `(canvas)...`
6. `nperf iniciado. Esperando resultados...`
7. `nperf prueba X/Y — Ns`
8. `nperf completado. Guardando...`

Si no aparecen métricas después del primer toque, la aplicación envía un segundo toque Android de confirmación a los 12 segundos. Los resultados de nPerf incluyen, cuando están disponibles, su identificador y URL `/r/...` en el TXT combinado.

El Pull Request debe permanecer en borrador mientras la prueba completa en un dispositivo Android no haya terminado correctamente.
