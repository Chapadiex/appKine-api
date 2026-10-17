# AKINE — Release candidate 1 (M01–M29)

> **Borrador del 16/10/2026, etapa AKINE-09.04.** Medido sobre `appKine-api` `main` `eef0c87` y
> `appKine-web` `main` `c5d803d`, por git y por la API de GitHub. **No está taggeado ni
> desplegado**: el tag se pone sobre el merge que se despliegue (`docs/operaciones/release-notes.md`).
> Este RC **no pasa el gate** de 07.09/09.04: falta el QA manual, la aprobación clínica y legal y
> el deploy con su ventana de estabilización (ver "Lo que condiciona el RC").

| | Valor |
|---|---|
| Backend | `eef0c87` · 767 commits · PR hasta **#143** · Java 21, Spring Boot 4.1.1, MySQL 8.4 |
| Contrato | `openapi/akine-api.yaml` **0.118.0**, **333 operaciones**, ningún `operationId` repetido, sin drift |
| Frontend | `c5d803d` · PR hasta **#105** · Angular 21 · cliente generado **0.118.0** |
| Migraciones | `V1`–**`V103`**, 79 archivos (hay números salteados a propósito; Flyway corre sin `outOfOrder`) |
| CI de `eef0c87` | Los cuatro checks en verde: build/test/contrato (**4.009 unitarias + 880 de integración, 0 fallos**, gates de JaCoCo cumplidos), imagen Docker y SBOM, OWASP/OSV, Sonar |
| E2E | 60 specs en `appKine-web/e2e/`, el job contra el backend real en el CI |

## Qué hace el sistema, por circuito

AKINE es un SaaS multi-tenant para centros de kinesiología, fisioterapia y rehabilitación. Cada
centro es una organización con una o más sedes; todo lo de un centro es invisible para otro (un
dato ajeno responde 404, nunca 403).

### MVP (primera entrega, M01–M27)

| Circuito | Qué puede hacer un centro | Módulos |
|---|---|---|
| **Alta y cuentas** | Registrarse solo (alta self-service idempotente), activar la cuenta por correo, entrar, recuperar la contraseña, elegir organización y sede (recuerda la última). Sesión con token en memoria y refresh rotativo en cookie | M01, M02 |
| **Organización, sedes y personal** | Plan y suscripción con límites (sedes, miembros), sedes con horario general y primer box en un acto, espacios, invitar colaboradores por correo, roles por sede u organización, permisos adicionales acotados a lo que el rol admite, desvinculación con impacto previo, auditoría | M01, M03, M04, M05 |
| **Catálogo y oferta** | Catálogo clínico global y del centro (con pedido de alta a la plataforma), servicios, ofertas por sede con prácticas, habilitaciones de profesionales, precios particulares con vigencia, política de prepago y esquema de cobro | M06, M27 |
| **Personas y cobertura** | Padrón de personas (una persona no es un paciente), perfil de paciente, Paciente 360, documentos adjuntos, financiadores y planes, coberturas del paciente, convenios y aranceles (con importación masiva), órdenes médicas y autorizaciones con su historial y consumo | M07, M15, M16, M17 |
| **Agenda, turnos y recepción** | Disponibilidad por profesional y sede, agenda con motivo cuando un día no tiene lugar, reserva atómica (sin doble reserva), series de turnos, reprogramar, cancelar, ausente, cobertura elegida al reservar, recepción del día (llegada, espera, atención, prepago) | M10, M12, M13 |
| **Atención clínica** | Historia clínica de la organización con justificación de acceso auditada, timeline paginado y filtrable, Caso clínico con equipo, plan de tratamiento versionado, Sesión de evaluación con examen, mediciones y tratamientos, cierre con numeración, enmiendas versionadas, adjuntos clínicos | M08, M09, M10, M11, M14 |
| **Deuda, cobro y caja** | Obligación al cerrar la sesión (paciente y financiador por separado, importes congelados), cuenta corriente, cobro con varios medios e idempotente, anticipos y saldo a favor, anulación y reintegro, caja diaria con arqueo y cierre | M18, M19, M20 |
| **Financiadores** | Bandeja de prestaciones elegibles, lotes de presentación con reparos, confirmación numerada, débito, pagos del financiador, conciliación, cuenta corriente del financiador, export CSV del lote | M21 |
| **Egresos** | Egresos de la sede con comprobante, confirmación, pagos parciales en efectivo o transferencia, anulación | M22 |
| **Reportes** | Tablero de turnos, sesiones, casos, economía y financiadores por rango, con export CSV acotado | M23 |
| **Notificaciones** | Correo por outbox transaccional (activación, recuperación, invitación, avisos de turno), listado y reintento administrativo | M26 |
| **Plataforma** | Consola del administrador de plataforma: listado de centros, suscripción y cambio de plan, catálogo global, acceso de soporte auditado | M01, M06 |

### Segunda entrega (M28–M29)

| Circuito | Qué puede hacer un centro | Estado |
|---|---|---|
| **Clases** | Programar clases con box elegido, reprogramar, cancelar; verlas en la agenda unificada junto a los turnos | Completo, con pantalla |
| **Inscripciones** | Inscribir con cupo y lista de espera; la baja promueve al siguiente | Completo, con pantalla |
| **Asistencia** | Tomar lista, corregir, cerrar la clase; la asistencia consume un crédito del pase o del abono | Completo salvo el devengo `POR_CLASE` (U-39) |
| **Derivación clínica de un participante** | Derivar a un participante de una clase clínica a su Caso, ver y revertir derivaciones | Backend completo; **sin pantalla para derivar** (sí para ver y revertir) |
| **Atención clínica grupal** | Abrir una Sesión por participante desde su derivación, de a uno o en lote, con panel de la clase; cada Sesión se cierra y numera en su Caso | Completo, con pantalla |
| **Pases (packs)** | Catálogo de productos, venta con deuda `VENTA_PASE`, consumo por asistencia, devolución al cancelar la clase, ajuste con motivo (`pase:adjust`), anulación junto con su deuda, vencimiento automático, ledger paginado | Completo, con pantalla |
| **Abonos** | Venta por período, renovación automática opcional, avisos de vencimiento próximo y saldo bajo por correo | Completo, con pantalla |
| **Economía de la segunda entrega** | La deuda de una venta se cobra y entra a la caja como cualquier otra; se anula sólo anulando el pase. Una Sesión grupal con cobertura devenga por convenio y entra a la presentación | Completo salvo liquidación de honorarios (U-41) |

## Qué NO hace

- **Autoservicio del paciente.** El rol `PACIENTE` no ve ni sus propios datos (alcance `OWN`,
  post-MVP por DP-22). Tampoco hay vista pública de turnos.
- **Cobrar una clase suelta (`POR_CLASE`).** Declararla responde 400 hasta decidir U-39. Quien
  asiste a una oferta por pack sin pase vigente queda registrado e informado, sin deuda.
- **Liquidar honorarios** a profesionales o instructores por clase, hora o participante (U-41). Un
  egreso de honorarios se carga a mano.
- **Exigir orden, autorización y credencial al presentar** (RF-M21-003): hoy avisa y deja confirmar
  (U-28). El **débito parcial** deja la obligación del financiador pendiente por el total (U-11).
- **Catálogo global de financiadores** (DU-13, U-12): cada centro carga los suyos.
- **Adjuntar el archivo** del comprobante de un egreso: se registra la referencia (U-33).
- **Que un `ORG_ADMIN` cambie el plan** de su organización: sólo la plataforma.
- **Otro canal que no sea correo**, ni preferencias de notificación por persona. Los umbrales de
  aviso de pases son del despliegue, no del centro (U-44).
- **Exportes asíncronos**: el CSV de reportes es síncrono y acotado a 366 días y 500 filas por
  sección (U-36).
- **Derivar desde la pantalla** a un participante de clase (ver arriba): la atención grupal necesita
  la derivación hecha.

## Lo que condiciona el RC: decisiones abiertas

La lista completa —45 entradas— está en
[`../fases/decisiones-pendientes.md`](../fases/decisiones-pendientes.md). Las que mandan sobre este RC:

| | Decisión | Por qué condiciona |
|---|---|---|
| U-01 | Correr el QA manual del §6 | Bloqueante para deploy desde 02.02; nunca se corrió |
| U-02 | Aprobación clínica y legal, deploy y ventana de estabilización | Es el gate (DP-07, DP-08) |
| U-03, U-04 | Justificación de acceso en `encounter`; `caso:create` | Seguridad del circuito clínico (G-5) |
| U-05 | `mysql-connector-j` 9.7.0 con CVE sin corrección | **La excepción vence el 09/11/2026** y el job de vulnerabilidades se pone rojo |
| U-06 | SAST y DAST | Propuesta abierta en api #119; SCA ya está |
| U-28, U-11 | RF-M21-003 y débito parcial | Lo que se le presenta y debita a una obra social. Propuesta en api #122 |
| U-38 | "Hoy" para lo que no tiene sede | Entre las 21 y las 24 de Córdoba una cobertura, orden o autorización que vence ese día se lee vencida |
| U-39, U-41, U-42 | Devengo por clase, honorarios, economía de la atención grupal | Lo que la segunda entrega cobra y paga |
| — | La apertura de F9 sin DP escrita (DU-12) | Trazabilidad: DP-07 pedía el gate del MVP antes de la segunda entrega |

**A confirmar por el dueño del producto** (implementadas con un criterio provisorio que el PR
declara): el `type` del 403 sin justificación clínica pasó de `forbidden` a
`justificacion-de-acceso-requerida` (api #132); elegir contexto deja dos eventos de auditoría
(api #133, D4); la cuenta corriente del financiador sigue sumando los lotes en `BORRADOR`
(api #131); renovar un abono impago (U-40 b), el vendedor del período renovado (U-43) y la regla
del período (U-45). El resto, en [`../qa/90-conocido-y-aceptado.md`](../qa/90-conocido-y-aceptado.md).

## Deuda declarada que viaja con el RC

- **El QA manual no se corrió.** Hay guía (`docs/qa/`, doce circuitos del MVP) y un **pre-QA
  automatizado** por la API (146 pasos, `docs/qa/corridas/2026-10-14-pre-qa.md`: 131 OK, 12 FALLA,
  2 MANUAL, 1 BLOQUEADO) cuyos siete defectos D1–D7 están corregidos (api #131, #133) y cuyas
  discrepancias de guía también (#130). **El pre-QA no reemplaza el QA manual**: lo de pantalla
  quedó MANUAL, y la guía no tiene circuito de clases, pases ni abonos.
- **El SLO de escrituras a 500 usuarios no se cumple.** ADR-0016 pide p95 ≤ 300 ms y p99 ≤ 800 ms.
  Con la mezcla completa a 500 usuarios, `main` midió p95 entre 288 y 1 160 ms según la corrida,
  con el cobro (526–2 056 ms) y la reserva (326–1 450 ms) arriba; cero errores técnicos, sin
  huecos en la numeración ni turnos solapados (`docs/carga.md` §10). El cobro serializa en el
  numerador de comprobantes y la reserva en `agenda_sede`. Las cinco salidas —numeración por punto
  de venta, numerar en lote después del commit, al cerrar caja, lock por profesional, medir en el
  entorno de destino— esperan decisión. La carga de F9 no se midió.
- **Escenarios diferidos de F9** sin IT propio: 39–42, 46–48 y 54–59 de
  [`../tests-diferidos.md`](../tests-diferidos.md) (concurrencia clase/turno, capacidad contra
  inscripción, unique de asistencia, cierre concurrente, lote, aislamiento y REST de 08.01–08.03).
  09.02 y 09.03 están escribiendo parte.
- **Gate de `BRANCH`** en JaCoCo sin poner (U-35). Línea e instrucción gateadas en 0,80 global y por
  módulo crítico.
- **Secretos sin cargar:** `NVD_API_KEY` (el job de vulnerabilidades cae a OSV.dev) y
  `SONAR_TOKEN`/`SONAR_HOST_URL`.
- **Sin registry** de imágenes: el rollback por imagen asume que la anterior sigue en el host
  (`docs/operaciones/rollback.md`).
- **Migraciones `V96`–`V103` no ensayadas sobre una copia restaurada**; el simulacro de restore de
  G-13 es anterior. Cada CI las aplica sobre una base vacía.

## Antes de desplegar

- **Migraciones nuevas respecto del último simulacro:** `V96`, `V101`, `V102`, `V103`, todas
  aditivas: columnas nullables o `NOT NULL` con default (`pase_servicio.tipo` = `'PACK'`,
  `renovacion_automatica` = 0), y CHECK, uniques e índices nuevos que una imagen anterior no viola;
  ninguna reescribe un CHECK. Según `docs/operaciones/rollback.md` §1, eso habilita el **rollback
  por imagen**, pero no se ensayó con estas cuatro. Volver a una imagen anterior a #129 apaga los
  pases y los jobs de vencimiento, renovación y avisos.
- **Variables de entorno nuevas:** ninguna obligatoria. Los tres jobs de F9 tienen defaults y se
  pueden apagar o ajustar por variable (`docs/operaciones/runbooks.md` R7).
- **Cambios de respuesta visibles desde 0.99.0** (declarados en sus PR): `esquemaCobro` es enum en
  el contrato y fuera de vocabulario responde 400, no 500 (#129, #135); `Obligacion.sesionId` es
  opcional (#129); `DELETE` de un tratamiento responde 200 con cuerpo (#127); una asistencia sobre
  una clase que se está cancelando responde 409 (#135); anular la deuda de una venta responde 409
  `obligacion-de-venta` (#139); el 403 sin justificación clínica cambia de `type` (#132); la
  historia clínica de otra organización responde 404 y no 403 (#133).

## Verificación del despliegue

- [ ] Backup previo verificado (`ops/verificar-backup.sh`)
- [ ] Smoke post-deploy verde (`node ops/smoke.mjs`)
- [ ] Sin 5xx nuevos en la primera hora (`http_server_requests_seconds_count{status=~"5.."}`)
- [ ] Los tres jobs de F9 dejaron su primer tick sin `fallo` en el log (R7)
