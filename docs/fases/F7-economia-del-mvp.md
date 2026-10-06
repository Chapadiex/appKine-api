# F7 — Economía del MVP

> Auditoría del 01/10/2026 · backend `37ff201` · frontend `4c6ae8b` · **~52 %**
> Etapas del plan: 07.01–07.05 (§13, líneas ~3305–3704). Leer también DP-06 y DP-10 (§7).

## Estado por etapa

| Etapa | Estado | % | Lo que falta, en una línea |
|---|---|---|---|
| 07.01 Obligaciones | PARCIAL | 60 | **Solo deuda del paciente**: sin financiador, mixto, coseguro ni snapshot de convenio |
| 07.02 Cobros | PARCIAL | 55 | Anticipos, imputación posterior, anulación, reintegro |
| 07.03 Caja diaria | PARCIAL | 55 | Sin frontend; ITs concurrentes |
| 07.04 Presentaciones a financiadores | DESVIADA / PARCIAL | 40 | **La bandeja de elegibles queda vacía en un despliegue real** |
| 07.05 Egresos y pagos | PARCIAL | 50 | Adjunto binario; sin frontend |

Frontend existe solo para deuda (`cuenta-corriente`) y cobros (`registro-de-cobro`, `cobros`).
Caja, presentaciones y egresos: **nada**. No hay ningún E2E económico.

## El hallazgo central: no existe la obligación del financiador

`ObligacionDevengador` devenga **una sola** obligación, con `Responsable.PACIENTE`. Es el recorte
de DP-10, que **cortó alcance y no modelo**: la columna `responsable` admite `FINANCIADOR` y
`snapshot_convenio_id` existe, pero queda en NULL y nunca se conectó. Consecuencias medibles:

- `prestaciones-elegibles` (07.04) **devuelve lista vacía** en un despliegue real.
- RF-M21-003 no puede validar orden, autorización ni credencial: viven en el `ArancelCongelado` y
  el devengado no los copia.
- La sección de financiadores del reporte (07.06) siempre da cero.

**No es deuda de tests: es un cimiento que falta.** Necesita, en este orden:

1. Cobertura vigente del paciente por `spi` (preparado en [F3](F3-personas-y-cobertura.md)).
2. `practicaId` en `SesionCerrada` (hoy no la lleva) — depende del puente Oferta↔Práctica de [F2](F2-operacion-del-consultorio.md).
3. Copiar el `ArancelCongelado` entero al devengar (`ArancelDirectory#congelar`, hoy sin consumidor).
4. Devengar la parte del financiador, del paciente y el coseguro como obligaciones separadas.

Migración + cambio de contrato. Etapa propia con design challenge.

## Defectos — a verificar

- [x] ~~`FinanciadorPagoService.cuentaCorriente` filtra por `consultorioId` y pagina fijo `200, 0`
  (alrededor de la línea 191) → desde el lote 201 los totales salen mal.~~ → F-1: la suma la hace
  la base (`sumarCuentaCorriente`), cruza sedes y no pagina; `CuentaCorrienteDeFinanciadorIT`.

Reportados el 01/10 por los agentes que escribieron los tests de `billing` en `akine-integracion-total`.
**Corregidos el 06/10/2026** (PR #16, #17 y #18):

- [x] ~~`PresentacionService.revisar` colapsa "deuda de otro financiador" y "deuda del paciente" en
  el mismo hallazgo `FINANCIADOR_DISTINTO`, y el remedio de cada caso es el opuesto. Arreglarlo
  agrega un valor al enum → **cambio de contrato**.~~ → F-2: nuevo hallazgo `DEUDA_DEL_PACIENTE`
  (contrato 0.48.0).
- [x] ~~`debitar` mueve el saldo del lote **antes** de validar el importe del ítem; hoy solo lo salva
  el rollback.~~ → F-2: `PresentacionItem.exigirDebitable` corre antes del UPDATE condicional.
- [x] ~~Ese rechazo sale por `GlobalExceptionHandler` y no por `BillingProblemHandler` (rompe la regla
  "cada módulo mapea sus excepciones en su propio advice").~~ → F-2: `ImporteDeDebitoInvalidoException`,
  mismo `validation-error` 400, ahora con `importe` e `importePresentado`.
- [x] ~~El CSV de financiadores puede no sumar al indicador `prestado`: las filas de detalle iteran
  sobre el resumen de lotes.~~ → F-2: los financiadores con prestado y sin lote en el periodo
  también tienen fila.

## Faltantes

### Backend / API
- [ ] **Obligación del financiador** (ver arriba).
- [ ] 07.02: anticipos, imputación posterior, anulación y reintegro. El recorte se justificó porque
  no existía caja; **la caja existe desde 07.03** y nadie reabrió el tema. `CobroController` lo dice:
  "No hay anticipos ni anulacion todavia". También lo necesita el prepago de recepción ([F5](F5-agenda-y-recepcion.md)).
- [ ] 07.04: listados exportables; permiso propio (hoy reusa `cobro:register`).
- [ ] 07.05: adjunto binario del comprobante (RF-M22-003). **Decisión pendiente**: ¿alcanza la
  referencia, o se extrae el storage a `platform.spi` (refactor de `person` y `clinical`)?
- [ ] 07.05: `egreso:manage` propio o seguir reusando `caja:operate` (**decisión pendiente**).
- [x] ~~07.05: `totalPagadoVigente` no tiene uso.~~ → lo usa `SaldoDeEgresoConfrontadoIT` para
  confrontar `saldo_pendiente`, que es para lo que existía. Ese IT destapó que **anular un pago
  por transferencia sin caja abierta daba 500** (NPE en el log de `MovimientoCajaService.revertir`);
  corregido en el mismo cambio.

### Frontend
- [ ] Caja diaria: abrir, movimientos, cerrar con diferencia, reversión.
- [ ] Bandejas de presentaciones: elegibles, armado, validación, factura, débito, pagos, conciliación.
- [ ] Egresos y pagos a profesionales.

### Tests
- [ ] ITs de los escenarios diferidos 33–48 (caja concurrente, presentaciones, egresos).
- [ ] E2E económicos: cierre → deuda → cobro → caja; presentación de punta a punta.

> **En curso al 01/10:** la rama `akine-integracion-total` agregó unitarios de `ObligacionService`,
> `CobroService`, `CajaService`, `MovimientoCajaService`, `PresentacionService`,
> `FinanciadorPagoService`, `EgresoService`, `PagoEgresoService` y `BillingProblemHandler`.
> Llevan `billing` de 35 % a ~92 % de instrucciones (medido por la sesión que los escribió),
> pero **no reemplazan los ITs concurrentes**.

## Desvíos

| Desvío | Documentado |
|---|---|
| Solo obligación del paciente (DP-10) | Sí; **el recableado nunca se hizo** |
| Sin anticipos aunque la caja ya existe | El recorte sí; **no** que su justificación caducó |
| Registro de 07.02 es reconstrucción, no acta | Sí |
| El cobro no exige `caja:operate` | Sí |
| Presentaciones reusa `cobro:register`; DELETE físico de ítems en BORRADOR | Sí |
| Egresos reusa `caja:operate` | Sí |

## Decisiones del usuario que bloquean

- Adjunto binario del egreso.
- `egreso:manage`.
- N tratamientos = N unidades o 1 (afecta cuánto se le factura al financiador).

## Para cerrar la fase

- [ ] Obligación del financiador con snapshot de convenio, con ITs.
- [ ] Anticipos y anulación con reintegro.
- [ ] Pantallas de caja, presentaciones y egresos con E2E.
- [ ] ITs diferidos 33–48.
- [ ] Registros de 07.03–07.05 promovidos a cierre.
