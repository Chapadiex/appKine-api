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
| El impacto de editar un bloque de disponibilidad es una **cota superior**; el de desvinculación muestra una sola fuente; carrera residual en la baja de sede | #8 (E-1) |
| Anular y reintegrar un cobro exigen `cobro:register` **y** `caja:operate`; el anticipo se declara, no se infiere del sobrante; un cobro con reintegros no se anula | #9 (F-3) |
| El reintento de notificaciones reusa `colaborador:manage` | #10 (A-3) |
| `cambiarEquipo` de un caso devuelve la versión **leída**: encadenar otra escritura da 409 | `CLAUDE.md` §7, 29/09 |
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
| **Pantallas que no existen en `main`**: historia clínica (PR #4 de `appKine-web`), casos, planes, tratamientos y mediciones de la sesión, enmiendas, soporte y roles de plataforma, bloqueo de cuentas, cambio de plan y suspensión, y el listado de sesiones abiertas de una cuenta (la IP de cada sesión solo se ve en `refresh_token`). **Ya tienen pantalla** desde el 11/10: egresos y pagos (web #63), alertas y reversión de consumo (web #58), notificaciones fallidas (web #46) | Se prueban por la API (README §5). Que falte la pantalla **no** es hallazgo del QA |
| El onboarding de la **primera** sede no crea box ni horario general (se cargan después) | A-8 (13 b) |
| **El pago a profesionales no existe como liquidación.** "Honorarios profesionales" es un egreso cargado a mano con un colaborador como beneficiario: nada calcula lo que se le debe a partir de sus sesiones, ni lo concilia con lo cobrado, ni impide pagar dos veces el mismo período (dos egresos con distinto comprobante entran) | Hueco declarado (F-5, espera DU-10). Que no haya liquidación **no** es hallazgo; que el egreso a un colaborador no se pueda cargar, sí |
| Escenario diferido **7b**: conflicto de `Idempotency-Key` del alta self-service con otro cuerpo | `docs/tests-diferidos.md` |

## 4. Cosas que **no** son decisión: defectos conocidos sin corregir

Estos sí son bugs. Si los ves, registralos **vinculados** a esta lista (no como nuevos) para que el
gate del MVP los cuente.

| Defecto | Dónde se ve | Origen |
|---|---|---|
| ~~**Descargar un documento administrativo de una persona responde 500**~~ — **corregido en #82 (`a09a596`)**: `AdjuntoService.contenido` auditaba dentro de una transacción `readOnly` | [PER-04](04-personas-coberturas-autorizaciones.md) | Lo destapó G-13 (#77); se reprodujo sobre `5bbf065` al validar esta guía |
| ~~**El indicador "Cancelados" del reporte de turnos da siempre 0**~~ — **corregido en #86**: la consulta filtraba `deleted_at IS NULL` y cancelar llena `deleted_at` | [REP-01](09-reportes.md) | Encontrado al validar esta guía |
| ~~Con reservas y cobros concurrentes, el pool de Hikari se traba~~ — **corregido en #91**: las filas-lock se aseguran antes de abrir la transacción de negocio | Solo bajo carga | G-10, `docs/carga.md` §6.1 |
| **El filtro "Desde"/"Hasta" de egresos compara el día UTC del registro, no el de la sede.** `EgresoRepository` filtra `DATE(registrado_en) >= :desde` y `DATE(registrado_en) <= :hasta` con `registrado_en` en UTC. En Córdoba (UTC−3), un egreso cargado entre las 21:00 y las 24:00 queda **afuera** de "Hasta = hoy" y **adentro** de "Desde = mañana"; el primer día del mes, los de la noche anterior del mes pasado entran en el mes nuevo. Reproducido al validar (12/10) con una sede en UTC+14: un egreso recién cargado no aparece con "Desde = Hasta = hoy de la sede" y sí con "Hasta = hoy". La pantalla manda bien el día de la sede (web #60); el corte lo hace el backend | [EGR-07](12-egresos-y-pagos.md) | Validación de esta guía; sin PR al 12/10 |
| **Un cobro anulado desaparece del listado de cobros del paciente.** `CobroRepository.findDeLaPersona` filtra `deleted_at IS NULL`, así que `/pacientes/<id>/cuenta-corriente/cobros` no muestra los comprobantes anulados (deja huecos en la numeración visible y no hay forma de ver un anulado por pantalla). El detalle por id sí trae `estado`, `anuladoEn` y `motivoAnulacion` | [ECO-10](07-obligaciones-cobros-caja.md) | Validación de esta guía; sin PR al 12/10 |
| **El correo de una invitación cancelada sale igual si estaba en reintento.** Con el servidor de correo caído, invitar → la notificación queda `REINTENTABLE` → cancelar la invitación → al volver el correo, el outbox la envía (`ENVIADA`) con un enlace que ya no sirve (`POST /auth/invitations/preview` responde 404). El diseño (`V9`, T-11) promete `AGOTADA` con motivo para un enlace muerto. Severidad media: no filtra datos, pero el destinatario recibe una invitación revocada | [ORG-15](02-organizacion-sedes-colaboradores.md) | Validación de esta guía (12/10); sin PR |

## 5. Preguntas abiertas que el QA puede encontrar

No son defectos ni decisiones tomadas: hay que llevarlas al dueño del producto.

| Pregunta | Dónde se ve |
|---|---|
| **Débito parcial**: debitar 1000 de un ítem de 6000 deja el ítem `DEBITADO` entero y, al conciliar, su obligación queda `PENDIENTE` por 6000 aunque el financiador pagó 5000. El diseño lo dice ("un ítem DEBITADO no salda nada", 07.04 §8) pensando en el rechazo total; para el parcial, la cuenta corriente del financiador y los reportes muestran como pendiente lo que ya se cobró | [PRE-06](08-presentaciones-facturacion-pagos.md), [PRE-08](08-presentaciones-facturacion-pagos.md) |
| Para el `PACIENTE`, un pedido con un parámetro obligatorio faltante responde 400 antes que 403 (la validación corre antes que el permiso). No filtra datos | [PAC-01](11-aislamiento-y-rol-paciente.md) |
