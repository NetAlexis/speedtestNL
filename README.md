# Speedtest NL

Aplicación Android que automatiza una medición combinada de conectividad:

1. Ejecuta Speedtest en el WebView existente.
2. Conserva sus métricas en memoria.
3. Ejecuta el test web público de nPerf dentro de GeckoView.
4. Genera un único archivo TXT combinado.
5. Sube el resultado a Google Drive mediante el endpoint configurado.

## nPerf integrado con GeckoView

Esta rama incluye el motor Firefox GeckoView dentro de SpeedtestNL. El teléfono no necesita instalar Firefox ni la aplicación oficial de nPerf.

La fase nPerf usa:

- `NperfGeckoActivity` como contenedor independiente;
- GeckoView estable 152 con interfaz de escritorio;
- una WebExtension integrada que solo se ejecuta en `nperf.com`;
- mensajería nativa entre la página y Android;
- eventos táctiles Android para cookies, botón y canvas;
- extracción de descarga, subida, latencia, jitter, servidor, operador, ID y URL;
- devolución de resultados a `MainActivity` antes de crear el TXT combinado.

La extensión aplica intentos limitados y devuelve un error explícito si nPerf no inicializa o no presenta un control operativo. No utiliza ciclos infinitos.

## Ubicación

SpeedtestNL solicita el permiso de ubicación de Android cuando aún no fue concedido.

El diálogo del sistema operativo debe ser confirmado por el usuario una sola vez; Android no permite que una aplicación apruebe su propio permiso. Después de concederlo, las solicitudes de geolocalización realizadas por `nperf.com` se aceptan automáticamente dentro de GeckoView.

La automatización rechaza solicitudes de ubicación o mensajes procedentes de dominios distintos de `nperf.com` y `nperf.net`.

## Compatibilidad

- Android mínimo: Android 8.0, API 26.
- `targetSdk`: 34.
- `compileSdk`: 36.
- Java: 17.
- Android Gradle Plugin: 8.9.1.
- Gradle: 8.11.1.
- GeckoView: `152.0.20260713164047`.

Se generan APK separados para:

- `arm64-v8a`: teléfonos Android modernos de 64 bits;
- `armeabi-v7a`: teléfonos Android de 32 bits.

## Compilación

El workflow **Android Build** instala Android API 36 y ejecuta:

- `lintRelease`;
- `assembleRelease`;
- publicación del artefacto `SpeedtestNL-GeckoView-release`;
- publicación del reporte `SpeedtestNL-GeckoView-lint`.

## Secuencia esperada

Después de terminar Speedtest, la interfaz debe avanzar por estados similares a:

1. `Abriendo nPerf en GeckoView...`
2. `Preparando GeckoView para nPerf...`
3. `Instalando automatización nPerf...`
4. `Cargando nPerf en GeckoView...`
5. `Inicializando motor y servidor nPerf`
6. `Aceptando cookies nPerf`, cuando el banner esté visible.
7. `Activando Iniciar test` o `Activando medidor nPerf`.
8. `nPerf midiendo conexión`.
9. `Resultado nPerf detectado`.
10. `nPerf GeckoView completado. Guardando...`

## Validación obligatoria en dispositivo

Antes de fusionar el PR deben comprobarse:

- Speedtest obtiene descarga, subida, ping, jitter, Result ID y URL.
- La transición a GeckoView ocurre sin crear un TXT intermedio.
- La ubicación Android se concede una vez y el permiso del sitio se acepta automáticamente.
- Las cookies se aceptan automáticamente.
- El motor nPerf abandona `Inicializando` y comienza la medición.
- Se reciben descarga, subida, latencia y jitter.
- Se genera y sube un único TXT combinado.
- La siguiente repetición vuelve correctamente a Speedtest.

Para diagnóstico se usan las etiquetas Logcat `SpeedtestNL-Gecko` y los estados visibles de la extensión integrada.

El Pull Request debe permanecer en borrador hasta completar el recorrido real en un teléfono Android.
