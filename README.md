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
- una WebExtension integrada para `nperf.com` y `nperf.net`;
- mensajería nativa entre la página y Android;
- eventos táctiles Android para cookies, botón, SVG y canvas;
- extracción de descarga, subida, latencia, jitter, servidor, operador, ID y URL;
- devolución de resultados a `MainActivity` antes de crear el TXT combinado.

La extensión aplica intentos limitados y devuelve un error explícito si nPerf no inicializa o no presenta un control operativo. No utiliza ciclos infinitos.

## Control de marcos internos

El medidor público de nPerf puede cargarse dentro de un `iframe` o `frame` distinto de la página exterior. La extensión versión 1.2 usa `frame_controller.js` y:

- se inyecta en todos los marcos coincidentes de `nperf.com` y `nperf.net`;
- cubre marcos `about:blank` que heredan un origen permitido;
- busca controles dentro del DOM y raíces Shadow DOM;
- localiza **Iniciar test** como botón, SVG o canvas;
- transforma las coordenadas del marco interno a la vista GeckoView completa;
- envía eventos DOM y un toque Android real;
- limita el inicio a doce intentos;
- informa en pantalla si actúa en la página principal o dentro del medidor.

## Ubicación

SpeedtestNL solicita el permiso de ubicación de Android cuando aún no fue concedido.

El diálogo del sistema operativo debe ser confirmado por el usuario una sola vez; Android no permite que una aplicación apruebe su propio permiso. Después de concederlo, las solicitudes de geolocalización realizadas por `nperf.com` y `nperf.net` se aceptan automáticamente dentro de GeckoView.

La automatización rechaza solicitudes de ubicación o mensajes procedentes de otros dominios.

## Compatibilidad

- Android mínimo: Android 8.0, API 26.
- `targetSdk`: 34.
- `compileSdk`: 36.
- Java: 17.
- Android Gradle Plugin: 8.9.1.
- Gradle: 8.11.1.
- GeckoView: `152.0.20260713164047`.
- Versión de aplicación de esta corrección: `1.2-gecko-frames`, código 2.

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
2. `Automatización nPerf v2 activa en página principal`
3. `Automatización nPerf v2 activa dentro del medidor`
4. `Aceptando cookies nPerf`, cuando el banner esté visible.
5. `Activando Iniciar test dentro del medidor (..., start)` o `(..., gauge)`.
6. `Enviando toque Android a nPerf...`
7. `nPerf midiendo conexión`.
8. `Resultado nPerf detectado`.
9. `nPerf GeckoView completado. Guardando...`

## Validación obligatoria en dispositivo

Antes de fusionar el PR deben comprobarse:

- Speedtest obtiene descarga, subida, ping, jitter, Result ID y URL.
- La transición a GeckoView ocurre sin crear un TXT intermedio.
- La ubicación Android se concede una vez y el permiso del sitio se acepta automáticamente.
- Las cookies se aceptan automáticamente.
- El estado confirma que la automatización está activa dentro del medidor.
- **Iniciar test** se activa sin intervención manual.
- Se reciben descarga, subida, latencia y jitter.
- Se genera y sube un único TXT combinado.
- La siguiente repetición vuelve correctamente a Speedtest.

Para diagnóstico se usan las etiquetas Logcat `SpeedtestNL-Gecko` y los estados visibles de la extensión integrada.

El Pull Request debe permanecer en borrador hasta completar el recorrido real en un teléfono Android.
