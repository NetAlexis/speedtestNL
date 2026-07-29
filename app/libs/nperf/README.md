# nPerf Engine SDK privado

Este directorio está preparado para recibir el AAR/JAR licenciado que nPerf entregue para Android.

## Archivos que debe entregar nPerf

1. AAR o JAR del Engine SDK para Android.
2. Dependencias transitivas o repositorio Maven privado, cuando aplique.
3. Guía de inicialización y autenticación/licencia.
4. Nombre de los callbacks de estado, métricas y finalización.
5. Modelo de resultado: descarga, subida, latencia, jitter, servidor, operador, ID y URL.
6. Reglas de ProGuard/R8.
7. Requisitos de permisos, ABI y versión mínima de Android.
8. Condiciones para distribuir el binario mediante CI y repositorios privados.

## Instalación local

Coloque el archivo autorizado en este directorio, por ejemplo:

```text
app/libs/nperf/nperf-engine-sdk.aar
```

Los archivos `*.aar` y `*.jar` están ignorados por Git porque el repositorio es público. El código de la aplicación carga un adaptador propio denominado:

```text
com.netlife.speedtestnl.nperf.NperfVendorAdapter
```

Ese adaptador debe implementar `NperfEngine` y encapsular exclusivamente las clases del SDK entregado por nPerf.

No se deben inventar nombres de paquetes ni métodos del proveedor. `NperfVendorAdapter` se implementará cuando estén disponibles el binario y la documentación oficial.
