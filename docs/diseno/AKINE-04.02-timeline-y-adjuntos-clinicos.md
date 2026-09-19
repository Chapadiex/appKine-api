# Diseño — AKINE-04.02 · Timeline clínico, entradas versionadas y adjuntos clínicos

> **Alcance de este documento.** Decisiones de arquitectura de la etapa, antes de escribir código.
> El *qué* normativo está en `docs/AKINE_IMPLEMENTATION_PLAN.md` §"Etapa AKINE-04.02"; las reglas
> vinculantes en `AGENT.md`. Lo que sigue es el *cómo*, y lo que la etapa decide **no** hacer.
>
> El desafío adversarial de este diseño está en `docs/diseno/AKINE-04.02-challenge.md` y **manda
> sobre este archivo** donde discrepen.

Trazabilidad: RF-M09-004 (timeline), RF-M09-006 (versiones/cambios), RF-M25-001..005 aplicados a
lo **clínico**; RN-M09-001, RN-M09-004, RN-M09-005; RN-M25-001..006; regla maestra 10.

---

## 1. Qué construye la etapa, en una frase

Tres cosas que no existían y que la Historia Clínica de 04.01 declaró como su contexto pendiente:
el **timeline longitudinal** (un índice de hechos, no un visor), la **entrada clínica versionada**
(un hecho que se enmienda sin sobrescribirse) y el **adjunto clínico** (un binario que cuelga de la
HC, no de la Persona).

---

## 2. La decisión que ordena todas las demás: el timeline es un índice, no una tabla

**No hay tabla de timeline.** Se calcula al leer, agregando los aportes de
`clinical.spi.EventoClinicoContributor`, que 04.01 dejó cableado con cero implementaciones.

Tres razones, en orden:

1. **Una tabla de timeline es una segunda copia de la verdad.** El mismo argumento que V40 usó
   para no materializar el Paciente 360 y que 05.01 usó para no persistir slots: el día que
   alguien enmiende una entrada, dé de baja un adjunto o anule una sesión sin avisarle al
   proyector, el timeline miente y nadie se entera.
2. **El contribuyente ya sabe filtrar por permiso y por tenant.** Un proyector escribiría filas sin
   contexto de quién puede verlas, y la reconstrucción de esa decisión al leer es exactamente el
   trabajo que la agregación ya hace.
3. `EventoClinicoContributor` **ya tiene consumidor real** —`HistoriaClinicaService` lo inyecta
   como lista— y su javadoc declara que el ancla del timeline futuro es `historia_clinica.id`.
   Esta etapa no cambia el ancla: llena la costura.

**La contrapartida está asumida y hay que conocerla:** el costo de una página de timeline es la
suma de las consultas de todos los contribuyentes, y crece con cada módulo que aporte. El tope por
contribuyente (`EVENTOS_POR_CONTRIBUYENTE`, ya presente) lo acota; el día que sean ocho fuentes y
el percentil 95 se note, la respuesta es una proyección, **y recién ahí** una tabla con su escritor.

### 2.1 Paginación: cursor opaco y sobre-lectura deliberada

`GET .../timeline` pagina por **keyset descendente**, no por offset: un offset sobre un agregado de
fuentes heterogéneas se desordena en cuanto una fuente inserta.

El cursor es opaco para el cliente (base64 de `ocurrioEn|origen|referencia`). El agregador le pide
a cada contribuyente hasta `limite` eventos con `ocurrioEn <= cursor.ocurrioEn`, mezcla, descarta
todo lo que no sea estrictamente anterior al cursor en el orden total
`(ocurrioEn DESC, origen ASC, referencia DESC)`, y recorta a `limite`.

> **El borde que esto deja abierto, dicho de frente.** Si un solo contribuyente tiene más de
> `limite` eventos **en el mismo instante exacto** que el cursor, la página siguiente puede saltear
> alguno. Es improbable —`ocurrioEn` es `DATETIME(6)`— y el precio de cerrarlo es un `WHERE`
> lexicográfico de tres columnas replicado en cada contribuyente, que es la clase de complejidad
> que después nadie mantiene igual en las cinco fuentes. Queda declarado, no resuelto.

### 2.2 El cambio de firma del SPI, y por qué es gratis hoy

`EventoClinicoContributor.eventosDe(organizationId, historiaClinicaId, limite)` pasa a
`eventosDe(organizationId, historiaClinicaId, Instant hasta, int limite)`.

Hoy tiene **cero implementaciones**, así que cambiarla no rompe nada. El día que haya cinco,
cambiarla es tocar cinco módulos. Se cambia ahora.

`EventoClinico` gana dos campos: `origen` ya existe; se agrega `detalle` (una línea corta, sin
contenido clínico) y `referenciaSecundaria` **no** se agrega — quien necesite más va al módulo
dueño. El record sigue siendo pobre a propósito: su javadoc de 04.01 lo argumenta y esta etapa no
lo contradice.

### 2.3 Quién contribuye en esta etapa

| Contribuyente | Módulo | Qué aporta |
|---|---|---|
| `EntradaClinicaContributor` | `clinical` | Entradas clínicas vigentes, con su versión vigente |
| `AdjuntoClinicoContributor` | `clinical` | Altas de adjunto clínico |
| `AntecedenteClinicoContributor` | `clinical` | Antecedentes registrados (ya existían, nunca se indexaron) |
| `SesionEventoContributor` | `encounter` | Sesiones **cerradas** |

**El Turno no contribuye, y es una decisión, no un olvido.** RN-M09-005: las asistencias no
clínicas no forman parte de la Historia Clínica, y DP-05 es explícito en que ninguna transición
administrativa prueba que una prestación ocurrió. Un turno reservado, cancelado o ausente es un
hecho de agenda. La sesión **cerrada** sí es un hecho clínico.

**Sólo sesiones cerradas.** Una sesión en curso es un borrador; indexarla pondría en el timeline de
un paciente crónico una fila que cambia de contenido mientras alguien la mira.

---

## 3. Entrada clínica: cabecera inmutable + versiones

Dos tablas, `V45`.

```
entrada_clinica            ← identidad del hecho: qué es, cuándo ocurrió, de quién cuelga
entrada_clinica_version    ← el contenido, una fila por versión. La v1 es el original
```

**Por qué dos tablas y no una columna `version` que se sobrescribe.** Porque el requisito es
"enmiendas preservan original" y RF-M09-006 pide *consultar versiones*. Un `@Version` optimista
sobre una fila única protege contra escrituras concurrentes y **borra el texto anterior**: resuelve
un problema distinto del que la etapa tiene. Las dos cosas conviven: la cabecera lleva `version`
para el control optimista de su propio ciclo de vida (baja lógica, reclasificación), y las
versiones de contenido son filas.

**La enmienda exige motivo.** Sin motivo, una enmienda es indistinguible de una corrección de
tipeo, y el historial deja de servir para lo único que sirve: entender por qué el texto cambió.
Se rechaza con 400, no con 409.

**La entrada no se borra.** `active`/`deleted_at`/`deactivation_reason`/`deleted_key`, el mismo
patrón de `persona`, `perfil_paciente` y `adjunto_administrativo`. Dar de baja una entrada no borra
ninguna de sus versiones, y la entrada de baja **sale del timeline** pero sigue siendo consultable
por su id — que es lo que distingue "no lo muestres" de "no existió".

**`origen` y `referencia_origen`.** Una entrada puede nacer a mano (`MANUAL`) o ser el registro de
un hecho de otro módulo. Hoy sólo existe `MANUAL`; la columna está desde ahora porque agregarla
después de que haya entradas obliga a decidir qué valor llevan las viejas, y ninguna respuesta es
buena. Es la regla de DP-10: se corta alcance, no modelo.

---

## 4. Adjunto clínico: tabla propia, módulo propio, storage propio

`V46`, tabla `adjunto_clinico`, propietario `clinical`.

**No se reusa `adjunto_administrativo`, y la cabecera de V40 ya lo había decidido:** RN-M25-005
prohíbe que una clase no clínica se convierta en contenedor clínico, y V40 dejó escrito que el día
que hicieran falta categorías clínicas "la tabla es otra y el módulo también". Esta es esa tabla.

Diferencias que importan, no cosméticas:

| | `adjunto_administrativo` (person) | `adjunto_clinico` (clinical) |
|---|---|---|
| Ancla | `persona_id` | `historia_clinica_id` (+ `entrada_clinica_id` opcional) |
| Permiso | pertenencia a la persona | `hc:read`/`hc:write` **con acceso justificado** |
| Descarga | autorizada | autorizada **y auditada como acceso clínico** |
| Categorías | administrativas | ESTUDIO, INFORME, IMAGEN, CONSENTIMIENTO_CLINICO, EVOLUCION_ESCANEADA, OTRO |

**El storage.** `clinical.domain.port.ContenidoClinicoStoragePort` con su propio adaptador local y
su **propia raíz de disco**, separada de la de los adjuntos administrativos.

> **Por qué no se extrae el adaptador a `platform` y se comparte.** Se evaluó. Contra: `person` ya
> tiene el suyo cerrado y verificado, y moverlo es retrabajo sobre una etapa cerrada con riesgo de
> regresión a cambio de ahorrar ~120 líneas. A favor de duplicar: raíces separadas son
> **segregación física de binarios clínicos**, que es un control real y no una consecuencia. La
> duplicación queda anotada: **si aparece un tercer consumidor, se extrae a `platform.spi` y los
> tres adaptadores pasan a ser configuración.**

Todo lo demás se copia porque ya está bien pensado y verificado: `storage_key` opaca y generada por
el servidor, `checksum_sha256` en el unique para que **el reintento de una subida sea idempotente y
devuelva 200 con el adjunto que ya existe**, fila primero con flush y blob después dentro de la
misma transacción, `estado` `DISPONIBLE`/`NO_DISPONIBLE` para que un binario perdido responda 409 y
no 404, `content_type` **detectado por los bytes** y no el declarado por el cliente.

---

## 5. Permisos y auditoría

Sin permisos nuevos. `hc:read` para leer, `hc:write` para escribir, evaluados por
`AutorizacionClinica` igual que el resto del módulo: permiso + relación asistencial o
**justificación declarada**, y auditoría **de la lectura también** (DP-03).

Eventos de auditoría nuevos: `ENTRADA_CLINICA_REGISTERED`, `ENTRADA_CLINICA_AMENDED`,
`ENTRADA_CLINICA_DEACTIVATED`, `ADJUNTO_CLINICO_UPLOADED`, `ADJUNTO_CLINICO_DOWNLOADED`,
`ADJUNTO_CLINICO_RECLASSIFIED`, `ADJUNTO_CLINICO_DEACTIVATED`, `TIMELINE_ACCESSED`.

**`ADJUNTO_CLINICO_DOWNLOADED` es el que justifica la etapa entera desde el lado de seguridad.**
Un estudio descargado y reenviado es la fuga más barata que tiene un sistema clínico, y sin ese
evento no hay forma de revisarla después.

---

## 6. API — contrato `0.30.0`, doce operaciones nuevas

Aditivo puro, ninguna operación existente cambia de forma → **versión menor**, no mayor.

```
GET    /api/v1/historias-clinicas/{id}/timeline
POST   /api/v1/historias-clinicas/{id}/entradas
GET    /api/v1/historias-clinicas/{id}/entradas
GET    /api/v1/entradas-clinicas/{id}
GET    /api/v1/entradas-clinicas/{id}/versiones
POST   /api/v1/entradas-clinicas/{id}/enmiendas
DELETE /api/v1/entradas-clinicas/{id}
POST   /api/v1/historias-clinicas/{id}/adjuntos                        (multipart)
GET    /api/v1/historias-clinicas/{id}/adjuntos
GET    /api/v1/historias-clinicas/{id}/adjuntos/{adjuntoId}/contenido
PATCH  /api/v1/historias-clinicas/{id}/adjuntos/{adjuntoId}
DELETE /api/v1/historias-clinicas/{id}/adjuntos/{adjuntoId}
```

> **Corrección sobre el borrador de este diseño.** Las tres últimas estaban escritas planas
> (`/adjuntos-clinicos/{id}`) y se anidaron al implementarlas. El servicio exige
> `historiaClinicaId` —la autorización clínica se evalúa sobre la persona de esa historia, no
> sobre el archivo (RN-M25-003)— así que la ruta plana obligaba a pedirlo como query param
> **obligatorio**: la misma jerarquía, escrita de una forma que el cliente se puede olvidar. Las
> entradas clínicas **sí** quedan planas (`/entradas-clinicas/{id}`) y no es una inconsistencia:
> su servicio resuelve entrada → historia → persona por sí solo.

La justificación de acceso viaja donde ya viaja en el resto del módulo, **no en query string**:
un motivo clínico en la URL termina en los logs de acceso de cualquier proxy.

`ProblemType` nuevos: `entrada-clinica-no-accesible`, `entrada-clinica-inactiva`,
`enmienda-sin-motivo`, `adjunto-clinico-no-accesible`, `adjunto-clinico-inactivo`,
`adjunto-clinico-no-disponible`, `cursor-invalido`.

---

## 7. Lo que esta etapa NO hace

- **No crea Caso Clínico** (04.03) ni Plan (04.04). El timeline "sin mezclar casos" de RF-M09-004
  se cumple hoy porque **no hay casos**: cuando 04.03 llegue, agrega `caso_id` nullable a
  `entrada_clinica` y un filtro a la consulta. Renumerar o reasignar entradas cerradas no.
- **No consume autorizaciones** (04.05).
- **No hay antivirus.** El plan dice "análisis antimalware cuando exista infraestructura", y no
  existe. Lo que sí hay: lista blanca de tipos detectados por bytes, tope de tamaño, y `Content-
  Disposition: attachment` con `X-Content-Type-Options: nosniff` en la descarga.
- **No hay URL temporal firmada.** El plan la admite como alternativa a "descarga autorizada"; con
  storage local una URL firmada sería una segunda vía de acceso que hay que auditar aparte. Se
  elige la descarga autorizada por endpoint, que es la que deja el evento.

---

## 8. Migraciones reservadas

| Contenido | Versión |
|---|---|
| `entrada_clinica` + `entrada_clinica_version` | `V45` |
| `adjunto_clinico` | `V46` |

Reservadas **antes** de escribir código, que es la lección de `V26` y lo que F3 hizo bien.
