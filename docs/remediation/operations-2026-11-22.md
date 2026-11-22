# Verificación operativa — 2026-11-22

Entorno: `192.168.1.72`, servicio `mercurius.service`.
Build desplegado: jar del 2026-11-16, tag registrado `v3.17.3`.
**Producción no ejecuta 3.20.x**: el updater solo instala GitHub Releases.

Las comprobaciones fueron de solo lectura sobre producción. No se usaron
credenciales reales de Tributación/Hacienda ni se disparó ningún envío o
reintento contra Hacienda.

## 1. Cola asíncrona (`envio_fuera_linea`)

- La tabla existe y tiene **0 filas**: 0 pendientes, 0 vencidas.
- Job único cada 15 minutos (`EnvioFueraLineaRetryJob`), con
  `ConcurrentExecution.SKIP` y lote máximo.
- No se ejecutó ningún reintento manual ni envío real.
- La lógica se verificó con `EnvioFueraLineaTest`: **20/20 verde** (Hacienda
  simulada, sin credenciales reales).

Resultado: no hay trabajo pendiente en producción; no fue posible observar un
reintento real porque la cola está vacía. Una prueba de reintento real requiere
un entorno con Hacienda simulada, no producción.

## 2. Backups

Hallazgo crítico: **los backups diarios del 2026-11-09 al 2026-11-22 están
corruptos**.

- `gzip -t` falla con `invalid compressed data--format violated`.
- El más reciente (`mercurius_20261122_030001.sql.gz`, 113 MB) solo descomprime
  **131.072 bytes** de ~926 MB antes de fallar.
- No existe ningún sidecar `.sha256` (el build desplegado es anterior al
  checksum).
- `backupultimoejecutado` = 2026-11-22 03:00:21, es decir, la app marcó el
  respaldo como exitoso.
- El único backup válido es `mercurius_20261108_163000.sql.gz`:
  766.859.564 bytes descomprimidos, 83 tablas, marcador
  `PostgreSQL database dump complete`.

Causa raíz: **dos schedulers disparan a las 03:00 y escriben el mismo archivo**:

- `Utils.ProgramadorTareas.ejecutarBackupProgramado()` (cada hora en el minuto 0).
- `Services.BackupScheduler.respaldoAutomatico()` (cada 10 minutos).

Los logs muestran el mismo `Backup completado: mercurius_YYYYMMDD_030000.sql.gz`
dos veces, desde hilos distintos, con milisegundos de diferencia. Dos
`GZIPOutputStream` concurrentes sobre el mismo archivo producen un gzip
inválido.

Retención: 14 días, funcionando (borra los archivos antiguos). El único backup
válido estaba en el borde de la retención, por lo que se preservó fuera del
directorio escaneado:

```text
/home/alvaro/mercurius-backup-preserved/mercurius_20261108_163000.sql.gz
/home/alvaro/mercurius-backup-preserved/mercurius_20261108_163000.sql.gz.sha256
sha256 = 31535cb17b11b749daa02fcd8c78fc19beb009e27f05fe6e4d45f66135d64fbf
```

Corrección (3.20.1):

- Se eliminó el scheduler legado de `ProgramadorTareas`.
- `BackupService.ejecutarBackup()` quedó `synchronized`.
- El archivo temporal `.part` lleva un UUID para que dos ejecuciones no puedan
  compartir el mismo destino.
- `BackupSchedulingTest` fija el invariante de un solo scheduler.
- `BackupServiceIntegrationTest` sigue verificando gzip + SHA-256.

Pendiente operativo: desplegar 3.20.1 y ejecutar un backup manual para
regenerar un punto de restauración válido y su sidecar.

## 3. Secretos y CORS

- `EnvironmentFile=/home/alvaro/Mercurius/.env`, permisos `600 alvaro:alvaro`.
- Claves presentes (sin imprimir valores): `DB_USERNAME`, `DB_PASSWORD`,
  `DB_URL`, `MERCATUS_JWT_SECRET`, `MERCATUS_CORS_ORIGINS`, flags de proxy.
- `%prod` en `application.properties` no tiene defaults para `DB_*`; el arranque
  falla si faltan.
- CORS configurado por despliegue:
  `http://localhost:8081,http://192.168.100.99:8081,https://music.playdeca.com`.

Hallazgo: la versión desplegada registra

```text
Unrecognized configuration key "quarkus.http.cors" was provided; it will be ignored
```

y no emite `Access-Control-Allow-*` en las respuestas ni en el preflight. El
fix `quarkus.http.cors.enabled=true` está en 3.20.x, **no desplegado**.

Resultado: secretos correctos y fuera del repositorio; CORS **no operativo** en
la versión actual de producción.

## 4. Auditoría de logs

Se escanearon `mercurius.log` (115 MB), los rotados de `logs/` y el journal:

- Ocurrencias del `DB_PASSWORD` exacto: **0**.
- Ocurrencias del `MERCATUS_JWT_SECRET` exacto: **0**.
- `BEGIN ... PRIVATE KEY`: **0**.
- `Bearer <token>`: **0**.
- `password=`, `passwd=`, `token=`, `secret=`, `apiKey=`: **0**.
- `DiffUtilsRedactionTest`: **2/2 verde**.

Resultado: sin evidencia de contraseñas, tokens ni claves en los logs
verificados.

## 5. Ensayo de rollback (base de datos)

Fuente: backup válido del 2026-11-08 16:30 (SHA-256 verificado). Se usó una base
descartable; no se tocó `mercurius`.

- Base: `mercurius_rehearsal_20261122_040628`.
- Pass 1 (base vacía, dump `--clean`): 309 errores esperados
  `does not exist`, 0 errores distintos.
- Pass 2 (rollback sobre base existente, `ON_ERROR_STOP=1`): **exit 0, 0
  errores**.
- Verificación: 104 tablas, 22.854 artículos, 1 fila `appsettings`, tabla
  `envio_fuera_linea` presente con 0 filas.
- Base descartable eliminada.

Resultado: el procedimiento de restauración funciona desde el backup del
2026-11-08. Los backups diarios posteriores no son restaurables, así que el RPO
real hoy es **2026-11-08**.

Nota: los backups son SQL plano (`pg_dump -F p`), por lo que la restauración
correcta es `psql`, no `pg_restore`. El ensayo usó el camino correcto.

## 6. Despliegue

- `mercurius-updater.timer` consulta `releases/latest` de GitHub y descarga
  `Mercurius-runner.jar`; **no despliega commits de `master`**.
- 3.20.0 y 3.20.1 no están en producción.

Para cerrar los hallazgos:

1. Publicar un GitHub Release con `Mercurius-runner.jar` (3.20.1).
2. Dejar que `mercurius-updater.timer` lo instale y verificar el arranque.
3. Ejecutar un backup manual y confirmar `BackupService.estadoBackup()`:
   gzip válido, sidecar SHA-256 y fecha fresca.
4. Verificar CORS real contra `https://music.playdeca.com`.
5. Confirmar que solo un `Backup completado` aparece por día.

## 7. Pendientes de producto (no implementados)

- Carritos durables multi-instancia: siguen en memoria.
- Reconciliación automática de stock: diagnóstica, no corrige.
- Joins por ID en todos los reportes: parcial (`ProductPerformanceService`);
  quedan joins por nombre en reportes históricos.
- Shadow replay de pronóstico: no ejecutado.
- Tablero de cobertura de intervalos de predicción: no implementado.

## 8. Veredicto

| Área | Estado |
|---|---|
| Cola asíncrona | Sin pendientes; lógica 20/20; sin prueba real por cola vacía |
| Creación de backups | Ejecuta a diario, pero **corrupta** en el build desplegado |
| Checksum | No existe en el build desplegado; implementado en 3.20.1 |
| Retención | Funciona (14 días) |
| Secretos | Correctos, `.env` 600, fuera del repo |
| CORS | **No operativo** en el build desplegado |
| Logs | Sin secretos detectados |
| Rollback DB | Procedimiento válido desde 2026-11-08; backups recientes no sirven |
| Build desplegado | `v3.17.3`, no 3.20.x |
