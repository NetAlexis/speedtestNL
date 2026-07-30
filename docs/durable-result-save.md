# Guardado durable de resultados

Desde la versión 2.3.1, cada TXT combinado se escribe primero en `files/pending_results` dentro del almacenamiento privado de Speedtest NL.

La siguiente repetición solo puede comenzar después de una confirmación explícita del endpoint de Google Drive. Si la carga falla:

- el TXT pendiente no se elimina;
- se realizan tres intentos controlados;
- se muestra el detalle HTTP o de conexión;
- el usuario puede reintentar únicamente la subida;
- Speedtest y nPerf no se repiten;
- no se inicia la siguiente prueba.
