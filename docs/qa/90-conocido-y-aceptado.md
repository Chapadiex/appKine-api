# 90 · Conocido y aceptado — lo que NO se reporta como bug

Antes de registrar un hallazgo, buscalo acá. Si está, **no es hallazgo**: es una decisión tomada
(DP), un criterio provisorio que espera confirmación del dueño del producto, o un riesgo residual
declarado. Si lo que viste **contradice** lo que dice acá —por ejemplo, el criterio dice "avisa y no
bloquea" y bloqueó—, entonces sí es hallazgo.

Fuentes: `docs/producto/AKINE_IMPLEMENTATION_PLAN.md` (DP-01 a DP-23), §7 del `CLAUDE.md`
("Decisiones pendientes del usuario" y "Huecos funcionales conocidos"),
`docs/fases/01-trabajo-en-paralelo.md` §5 (DU-10 a DU-13) y `docs/seguridad/threat-model.md` §3.

## 1. Decisiones firmes que cambian lo que "parece un bug"

| Lo que vas a ver | Por qué es así | Fuente |
|---|---|---|
| Una sesión **particular** sin precio vigente **no cierra** (409 `oferta-sin-precio`) | Toda prestación cerrada tiene que generar deuda; el 409 se decide antes de numerar | DP-17 |
| Elegir "Particular" en la recepción manda sobre el convenio aunque haya obra social | El mostrador decide (RF-M08-007) | DP-17 (confirma E-7 a) |
| Una práctica sin cargo bajo el convenio (arancel total 0) cierra sin deuda | Es lo que el convenio pacta, no un dato faltante | DP-17 |
| Aprobar una solicitud de catálogo **crea** el concepto global en el mismo acto | Decidido; si la publicación choca, la solicitud sigue pendiente | DP-18 |
| La agenda no ofrece turnos fuera del horario general de la sede, aunque el profesional tenga horario | El horario de la sede **recorta** la disponibilidad. Sin horario cargado, no limita nada | DP-19 |
| Una serie cuyo último turno se canceló figura `CANCELADA` aunque haya tenido atenciones | Lo que define el estado es cómo terminó | DP-20 |
| Versión vieja en cualquier módulo → 409 `concurrent-modification`; choques de negocio → 409 `conflict` | Unificado. La **cantidad confirmada** de una serie desactualizada es `conflict` (hay que volver a previsualizar) | DP-21 |
| El rol `PACIENTE` recibe 403 en **todo** el padrón, incluso sobre sus propios datos | `paciente:read` es solo del personal; el autoservicio (`OWN`) es post-MVP | DP-22 |
| El historial de una autorización sale de `autorizacion_evento`, no de la auditoría | Dos datos, dos lectores; el vencimiento no es un evento, se calcula al leer | DP-23 |
| Una sesión consume **una** unidad por autorización involucrada, no una por práctica | | DP-12 |
| Anular una deuda **no** devuelve la unidad de autorización: deja una alerta "consumo a revisar" | La reversión es manual, con motivo | DP-13 |
| El turno no pasa a "atendido" ni a "en espera" | Turno = reserva; recepción y sesión tienen máquinas propias | DP-05, DP-16 |
| Cerrar la sesión no cobra | Deuda, cobro y caja son tres cosas | DP-06, 07.01 |
| Cancelar un turno libera el lugar; marcarlo ausente no | La hora del ausente se consumió | 05.03 |
| Escribir en la sesión de otro profesional es **409**, no 403 | La propiedad no es un permiso | 06.01 |
| Fuera de alcance (otra organización) es **404**; sin contexto es **403**, nunca 401 | Un 403 confirmaría que existe; un 401 hace que el frontend borre el token | 01.01 |
| `PLATFORM_ADMIN` no lee datos de un centro sin acceso de soporte vigente, ni siquiera reportes (matriz: "Global") | Lecturas de plataforma en alcance `SOPORTE`, auditadas en el centro | DP-15, matriz §9.7 y §14.4 |
| Otorgar `PLATFORM_ADMIN` a una cuenta le quita los permisos de sus memberships | Coherente con el modelo de soporte | `CLAUDE.md` §7 |
| El login no bloquea la cuenta por intentos fallidos | Bloquear por intentos habilita un DoS dirigido; frena el límite de 30/min por IP | `AuthenticationService` |
| El alta directa de colaborador responde 404 si el email no tiene cuenta (y el resto de la identidad responde uniforme) | Excepción declarada, con cupo propio de 10/min y `MEMBERSHIP_ALTA_RECHAZADA` | threat model §2.1 |
| Las lecturas de `contracting` (financiadores, planes, convenios) se autorizan por pertenencia: cualquier rol las ve | Hueco declarado en la matriz; no tiene fila de lectura | matriz §13.4 |
| Una oferta sin habilitaciones la presta cualquier `PROFESIONAL` de la sede | Es la regla, no un vacío | 02.07 |
| Una oferta sin prácticas no es error; "lista vacía" = la oferta no declara prácticas | Sin práctica no hay arancel de convenio posible | A-9 |
| El precio particular no se edita: solo se cierra o reabre su vigencia | | B-3 (d) |
| La suscripción suspendida permite leer y administrar pero no mutaciones de negocio (409 `subscription-suspended`) | | `TenantContextFilter` |
| Un grant que el rol actual ya no admite **sigue listado** pero no concede nada | Limpiarlo es una migración de datos | G-5, riesgo R11 |
| La deuda del financiador se salda al **conciliar** el lote, no al registrar el pago | Un pago es un importe global | 07.04 §8 |
| Un pago del financiador por transferencia no entra al arqueo de la caja | Fue al banco, no al cajón | 07.03, 07.04 §9 |
| La cuenta corriente del paciente no muestra la deuda del financiador | La debe la obra social | F-4 (#49) |
| El selector de permisos adicionales ofrece a un rol permisos que **ya tiene por base** (`hc:read`/`hc:write` al profesional, `paciente:manage` al administrativo) | Redundantes e inocuos; rechazarlos rompería un grant previo a un cambio de rol. Lo que la matriz prohíbe sigue siendo 400 `permiso-no-otorgable-al-rol` | api #85, [ORG-07](02-organizacion-sedes-colaboradores.md) |
| Con la base caída, el pedido tarda ~30 s y responde **503** `service-unavailable`; la sesión no se cierra | Es el `connection-timeout` del pool; 503 solo para fallas de **conexión** (un bug de base es 500), nunca 401 | api #91, web #62, [ON-12](01-onboarding-y-cuentas.md) |
| Un egreso con un pago parcial sigue `CONFIRMADO` (no hay estado "parcial") | El pago parcial se ve en `saldo_pendiente`; `PAGADO` es saldo cero | 07.05, [EGR-04](12-egresos-y-pagos.md) |
| Un pago de egreso por transferencia pasa con la caja cerrada y no deja movimiento de arqueo | Fue al banco, no al cajón (mismo criterio que el cobro) | 07.03, 07.05 |
| Un egreso se registra sin comprobante (queda `BORRADOR`) pero no se confirma sin él (400 `egreso-sin-comprobante`) | El borrador es para cargar de a partes | 07.05, [EGR-02](12-egresos-y-pagos.md) |
| La cobertura elegida al reservar se acepta aunque no aplique a la práctica (sin arancel, sin convenio): la reserva solo exige que sea financiada, activa, vigente ese día y **de esa persona** | Es una preferencia; si aplica lo decide la recepción, que deja `OBSERVADA` y nunca un 4xx. Rechazarla por convenio haría que un arancel cargado tarde impida reservar | api #107, [AGE-14](05-agenda-turnos-recepcion.md) |
| Una cobertura de otra persona, de otra organización o vencida el día del turno da **404** al reservar, no 403 ni 409 | Distinguir "no existe" de "es de otro" sería un oráculo (ADR-0018) | api #107, [AGE-14](05-agenda-turnos-recepcion.md) |
| Reservar, reprogramar o mover una serie en una sede de baja es 409 `consultorio-inactive`; con el contexto puesto en **esa** sede es 404 (el contexto ya no vale). Cancelar, marcar ausente y confirmar sí funcionan | Liberar la agenda de una sede de baja es lo que hay que poder hacer | api #96, [ORG-03c](02-organizacion-sedes-colaboradores.md) |
| Una membership suspendida (o revocada) recibe **404** en el pedido siguiente, no 403 ni 401, y la sesión no se cierra | Contexto vencido = 404 (`TenantContextFilter`); un 401 haría que el frontend borre el token | 01.01, [ORG-17](02-organizacion-sedes-colaboradores.md) |
| Un pedido que **ya estaba en curso** cuando commiteó una suspensión o la revocación de un grant puede terminar con lo que leyó al empezar (las mutaciones que evalúan el permiso antes de abrir su transacción, como registrar un cobro). El pedido **siguiente** ya no | Memoria de lecturas por pedido: la ventana es la de un pedido y nunca cruza al siguiente | api #112, `docs/carga.md` §9, [ORG-17](02-organizacion-sedes-colaboradores.md) |
| El administrativo ve en la ficha si la persona tiene historia clínica, sus casos y el equipo vigente, sin justificación y sin `HISTORIA_CLINICA_ACCESSED`; el contenido de la historia le sigue dando 403 | Matriz §32, "Ver HC: Limitado" = metadatos administrativos, nunca contenido. Se autoriza con `paciente:read` | api #109, [CLI-16](06-atencion-clinica.md) |
| Reintentar el alta self-service con la misma `Idempotency-Key` y **solo otra contraseña** responde 202 (replay) y no crea nada | La contraseña no entra al hash del pedido: un SHA-256 sin sal de esa fila sería crackeable | api #104, [ON-13](01-onboarding-y-cuentas.md) |
| Corregir un egreso con una versión vieja da 409 `concurrent-modification` aunque el egreso ya esté confirmado; con la versión vigente, 409 `egreso-no-editable` | La versión se controla primero | api #105, [EGR-09](12-egresos-y-pagos.md) |

## 2. Criterios provisorios: implementados así, **a confirmar** por el dueño del producto

No se reportan como bug; si el QA cree que el criterio es malo, se anota como **pregunta**, no como
defecto.

| Criterio | Fuente |
|---|---|
| **RF-M21-003 no bloquea**: una presentación con orden, autorización o credencial faltante avisa y se confirma igual. El dato viaja congelado en cada obligación | `CLAUDE.md` §7 #17 — la más cara de postergar |
| Una obligación por responsable y por sesión, no por práctica; no se cobra la diferencia particular − arancel; el copago del plan no interviene (el coseguro sale del arancel) | #17 (F-4) |
| La deuda es una **foto del cierre**: si la recepción cambia después, no se recalcula | #12 c (E-7) |
| El prepago **avisa y no bloquea**; con cobertura no hay aviso; se imputa solo a la deuda de esa sesión y si la imputación posterior falla solo queda en el log | #16 (E-6) |
| Antes del check-in el prepago se ve `PENDIENTE` aunque el paciente después resulte con cobertura | #14 (E-8) |
| Recepción: no hay salida "se retiró sin ser atendido"; no se pasa a Particular desde `EN_ESPERA` | #18 (E-4) |
| Series: alta todo o nada (un feriado la hace fallar entera), tope de 52, un aviso por ocurrencia, confirmación por cantidad y no por lista | #19 (E-3) |
| No se puede descartar una alerta de consumo sin revertirlo; una sesión sin caso consume una autorización atada a un caso; una autorización puede quedar atada a planes de dos casos | #19 (C-4) |
| Enmendar una sesión no tiene ventana temporal y no exige permiso reforzado: `sesion:register` + ser el profesional de la sesión | #19 (C-6) |
| El avance del plan de tratamiento cuenta **por oferta**, no por práctica | `CLAUDE.md` decisión 6 |
| Una cobertura aplica con la primera práctica que resuelve (la principal primero), sin desempate entre aranceles del mismo nivel; la elegibilidad y la autorización resuelven por práctica, sin oferta | #15 (B-3) |
| Confirmar un turno y marcarlo ausente **no notifican**; el aviso de cancelación sale aunque la oferta ya no resuelva | #7 (E-5) |
| El impacto de desvinculación muestra una sola fuente (la cota superior del impacto de un bloque la corrigió A-11, y la carrera de la baja de sede contra la reserva, #96: ver [ORG-03c](02-organizacion-sedes-colaboradores.md)) | #8 (E-1) |
| Anular y reintegrar un cobro exigen `cobro:register` **y** `caja:operate`; el anticipo se declara, no se infiere del sobrante; un cobro con reintegros no se anula | #9 (F-3) |
| El reintento de notificaciones reusa `colaborador:manage` | #10 (A-3) |
| El alcance limitado del profesional en reportes viaja como **advertencia**, no como campo | #19 (G-1) |
| El `ORG_ADMIN` no puede cambiar el plan de su organización (con `BASICO`, una sola sede) | `CLAUDE.md` decisión 4 |
| Si la casilla de `AKINE_BOOTSTRAP_ADMIN_EMAIL` ya es de otra cuenta, el bootstrap no hace nada y no otorga el rol | DP-14 / A-4 |

## 3. Decisiones abiertas (DU) y huecos declarados

| Tema | Estado |
|---|---|
| **DU-10**: egresos con `caja:operate` (no hay `egreso:manage`) y comprobante del egreso como **referencia** —tipo, número, fecha—, sin archivo adjunto | Abierta |
| **DU-11**: qué parámetros exige cada práctica (hoy se validan **tipados**, no obligatorios) y si un observador que falla debe hacer fallar el cierre | Abierta |
| **DU-12**: F9 (clases, inscripciones, asistencia, pases) congelada hasta el gate del MVP | Abierta — **F9 no entra en este QA** |
| **DU-13**: catálogo global de financiadores (referencia o copia): hoy cada centro carga los suyos | Abierta |
| **SAST/SCA/DAST** fuera del CI | Riesgo R1 |
| **`encounter` no exige justificación**: `SESION_*` y `TRATAMIENTO_*` salen con `reason` NULL | Riesgo R2 |
| Rate limit por IP del socket (detrás de un proxy todos comparten la IP) y `SecureLinkVault` en memoria | Riesgos R3, R4 |
| Sin antivirus sobre adjuntos | Riesgo R5 |
| Ventana de hasta 10 minutos en la que una cuenta bloqueada conserva su access token | Riesgo R6, aceptado (ADR-0017) |
| `caso:create` no se evalúa: abrir un caso se autoriza con `hc:write` | Riesgo R10 |
| El OpenAPI no declara `securityScheme`: Swagger UI no tiene botón para el token | Hueco conocido |
| **Pantallas que no existen en `main`**: historia clínica (PR #4 de `appKine-web`), casos, planes, tratamientos y mediciones de la sesión, enmiendas, soporte y roles de plataforma, bloqueo de cuentas, cambio de plan y suspensión, y el listado de sesiones abiertas de una cuenta (la IP de cada sesión solo se ve en `refresh_token`). **Ya tienen pantalla** desde el 11/10: egresos y pagos (web #63), alertas y reversión de consumo (web #58), notificaciones fallidas (web #46); y desde el 13/10 (web #73, #74): la cobertura elegida al reservar, el export CSV del lote, los consumos a revisar por fila, los datos administrativos de la historia en la ficha, la corrección de un egreso con versión y los cobros anulados en el listado | Se prueban por la API (README §5). Que falte la pantalla **no** es hallazgo del QA |
| El onboarding de la **primera** sede no crea box ni horario general (se cargan después) | A-8 (13 b) |
| **El pago a profesionales no existe como liquidación.** "Honorarios profesionales" es un egreso cargado a mano con un colaborador como beneficiario: nada calcula lo que se le debe a partir de sus sesiones, ni lo concilia con lo cobrado, ni impide pagar dos veces el mismo período (dos egresos con distinto comprobante entran) | Hueco declarado (F-5, espera DU-10). Que no haya liquidación **no** es hallazgo; que el egreso a un colaborador no se pueda cargar, sí |
| ~~Escenario diferido **7b**: conflicto de `Idempotency-Key` del alta self-service con otro cuerpo~~ | **Cerrado en #104** (`V87`, `request_hash`): 409 `idempotency-key-conflict`, ver [ON-13](01-onboarding-y-cuentas.md) |
| ~~`cambiarEquipo` de un caso devuelve la versión leída~~ | **Corregido en #109**: devuelve `leída + 1` y encadenar ya no da 409, ver [CLI-17](06-atencion-clinica.md). Un 409 al encadenar es hallazgo |

## 4. Cosas que **no** son decisión: defectos conocidos sin corregir

Estos sí son bugs. Si los ves, registralos **vinculados** a esta lista (no como nuevos) para que el
gate del MVP los cuente.

| Defecto | Dónde se ve | Origen |
|---|---|---|
| ~~**Descargar un documento administrativo de una persona responde 500**~~ — **corregido en #82 (`a09a596`)**: `AdjuntoService.contenido` auditaba dentro de una transacción `readOnly` | [PER-04](04-personas-coberturas-autorizaciones.md) | Lo destapó G-13 (#77); se reprodujo sobre `5bbf065` al validar esta guía |
| ~~**El indicador "Cancelados" del reporte de turnos da siempre 0**~~ — **corregido en #86**: la consulta filtraba `deleted_at IS NULL` y cancelar llena `deleted_at` | [REP-01](09-reportes.md) | Encontrado al validar esta guía |
| ~~Con reservas y cobros concurrentes, el pool de Hikari se traba~~ — **corregido en #91**: las filas-lock se aseguran antes de abrir la transacción de negocio | Solo bajo carga | G-10, `docs/carga.md` §6.1 |
| ~~**El filtro "Desde"/"Hasta" de egresos compara el día UTC del registro, no el de la sede.**~~ — **corregido en #105**: el backend filtra por el rango de instantes del día de la sede, y lo mismo la bandeja de prestaciones elegibles ([PRE-11](08-presentaciones-facturacion-pagos.md)). Revalidado el 14/10 con la sede en UTC+14. Lo que sigue es la historia. `EgresoRepository` filtra `DATE(registrado_en) >= :desde` y `DATE(registrado_en) <= :hasta` con `registrado_en` en UTC. En Córdoba (UTC−3), un egreso cargado entre las 21:00 y las 24:00 queda **afuera** de "Hasta = hoy" y **adentro** de "Desde = mañana"; el primer día del mes, los de la noche anterior del mes pasado entran en el mes nuevo. Reproducido al validar (12/10) con una sede en UTC+14: un egreso recién cargado no aparece con "Desde = Hasta = hoy de la sede" y sí con "Hasta = hoy". La pantalla manda bien el día de la sede (web #60); el corte lo hace el backend | [EGR-07](12-egresos-y-pagos.md) | Validación de esta guía (12/10); PR #105 |
| ~~**Un cobro anulado desaparece del listado de cobros del paciente.**~~ — **corregido en #101** (contrato 0.90.0, web #73): el listado trae los anulados con `estado = ANULADO` y la pantalla tiene columna Estado. Lo que sigue es la historia. `CobroRepository.findDeLaPersona` filtra `deleted_at IS NULL`, así que `/pacientes/<id>/cuenta-corriente/cobros` no muestra los comprobantes anulados (deja huecos en la numeración visible y no hay forma de ver un anulado por pantalla). El detalle por id sí trae `estado`, `anuladoEn` y `motivoAnulacion` | [ECO-10](07-obligaciones-cobros-caja.md) | Validación de esta guía (12/10); PR #101 |
| ~~**El correo de una invitación cancelada sale igual si estaba en reintento.**~~ — **corregido en #100**: antes de cada intento el worker pregunta si el hecho sigue en pie (`notification.spi.VigenciaDeNotificacion`). Una invitación cancelada, aceptada, rechazada, vencida o reemplazada por un reenvío deja la fila **`AGOTADA`** con `error_sanitizado = 'No se envia: la invitacion fue cancelada'` (o el motivo que corresponda) y no sale ningún correo. Lo mismo para el aviso de reserva o de reprogramación de un turno que se canceló mientras esperaba. Además, una fila que termina `ENVIADA` ya no conserva el error del intento anterior | [ORG-15](02-organizacion-sedes-colaboradores.md) | Validación de esta guía (12/10) |
| ~~**Los datos administrativos de la historia responden 200 para una persona que no es paciente.**~~ — **corregido en #115**: el servicio exige ficha activa y perfil vigente, como `HistoriaClinicaService.exigirPacienteVigente`, y las dos variantes (nunca paciente, perfil dado de baja) dan 404; `MatrizDePermisosIT` las cubre contra MySQL. Lo que sigue es la historia. El contrato de `verDatosAdministrativosDeHistoriaClinica` (0.97.0) dice que una persona "sin perfil de paciente vigente" es **404**, pero `HistoriaClinicaAdministrativaService` solo controla `pacientes.find(...).isEmpty()`, y `PersonPacienteDirectory.find` devuelve la persona del tenant **aunque no tenga perfil vigente** (con `esPacienteVigente = false`). Resultado: una persona sin perfil responde 200 `{tieneHistoriaClinica: false, casos: []}`, y una persona a la que se le **dio de baja el perfil** responde 200 con su `historiaClinicaId`, `abiertaEn` y sus casos. El unitario `persona_ajena_es_404` mockea `find` devolviendo vacío, que es justo lo que el directorio real no hace para una persona propia. No cruza tenants (otra organización sí da 404) y la pantalla no lo muestra (la tarjeta solo aparece si la ficha dice `esPaciente`): se ve por la API. Reproducido el 14/10 sobre `c827c72` | [CLI-16](06-atencion-clinica.md) | Validación de esta guía (14/10); PR #115 |

## 5. Preguntas abiertas que el QA puede encontrar

No son defectos ni decisiones tomadas: hay que llevarlas al dueño del producto.

| Pregunta | Dónde se ve |
|---|---|
| **Débito parcial**: debitar 1000 de un ítem de 6000 deja el ítem `DEBITADO` entero y, al conciliar, su obligación queda `PENDIENTE` por 6000 aunque el financiador pagó 5000. El diseño lo dice ("un ítem DEBITADO no salda nada", 07.04 §8) pensando en el rechazo total; para el parcial, la cuenta corriente del financiador y los reportes muestran como pendiente lo que ya se cobró | [PRE-06](08-presentaciones-facturacion-pagos.md), [PRE-08](08-presentaciones-facturacion-pagos.md) |
| Para el `PACIENTE`, un pedido con un parámetro obligatorio faltante responde 400 antes que 403 (la validación corre antes que el permiso). No filtra datos | [PAC-01](11-aislamiento-y-rol-paciente.md) |
