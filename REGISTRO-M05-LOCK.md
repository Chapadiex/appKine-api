# M05 — el lock de sede: defecto latente corregido y el segundo descartado con evidencia

## Defecto 1 — corregido

`BloqueoDeSede` creaba la fila de `consultorio_calendario` **dentro** de la transaccion que la iba
a bloquear. Dos primeras escrituras concurrentes de una sede leen las dos "no existe", insertan las
dos, y el perdedor se lleva un **500** en un pedido legitimo. El javadoc lo llamaba "una ventana
angosta" cuyo "remedio es un reintento del cliente": eso era lo falso.

Ahora `CalendarioSedeIniciador` (`REQUIRES_NEW`) asegura la fila **antes** del lock con
`INSERT ... ON DUPLICATE KEY UPDATE id = id` nativo — la excepcion se evita, no se atrapa; un
try/catch habria terminado en `UnexpectedRollbackException`. `BloqueoDeSede.tomar` ya no crea nada
y falla ruidosamente si la fila no esta. Lo llaman los tres escritores: `DisponibilidadService`,
`ExcepcionService` y `CalendarioService`.

**Evidencia.** Test nuevo `DisponibilidadIT#las_dos_primeras_altas_de_la_sede_no_pasan_las_dos`:
dos altas solapadas, **sin** crear el calendario de antemano. En verde con el fix. Revirtiendo
`BloqueoDeSede` al alta perezosa, falla: el perdedor muere con
`DataIntegrityViolationException: Duplicate entry '1-1' for key uk_consultorio_calendario_sede`.
El desenlace no es determinista — el mismo patron en `scheduling` dio deadlock — pero es un 500 en
los dos casos.

## Defecto 2 — NO existe en `resource`

`DisponibilidadService`, `ExcepcionService` y `CalendarioService` ya declaran
`@Transactional(isolation = Isolation.READ_COMMITTED)` en las cinco mutaciones, **desde su commit
de origen** (`01bdc99`, AKINE-02.04). No se toco nada.

**Evidencia, no lectura de codigo.** Sacandole el `READ_COMMITTED` a `crear`, el test nuevo y
`dos_altas_concurrentes_solapadas_no_pasan_las_dos` fallan **los dos** con `[OK bloque N, OK bloque
M]`: las dos altas solapadas entran, que es el sintoma exacto del defecto de `TurnoService`. Con el
nivel puesto, los dos en verde. El aislamiento es la pieza que sostiene el lock, y ya estaba.

## Verificacion

`./mvnw verify` — ver la salida en el informe de la tarea. Sin migraciones, sin cambios de contrato
ni de controllers, sin tocar `scheduling`.
