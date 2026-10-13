# F7 — Economía del MVP

> **Reverificada el 12–13/10/2026** contra `main` del backend (`8979519`, contrato 0.90.0) y del
> frontend (`2cdd3bb`), y contra los PR mergeados de los dos repos (API de GitHub). **~94 %.** Lo
> que falta es **decisión del usuario** o QA manual: código sin decisión pendiente no queda nada.
> La auditoría original es del 01/10/2026 (backend `37ff201` · frontend `4c6ae8b` · ~52 %); su
> historia queda tachada abajo, ítem por ítem, con el PR que la cerró.
> Etapas del plan: 07.01–07.05 (§13, líneas ~3634–4045). Leer también DP-06, DP-10, DP-12, DP-13 y
> DP-17 (§7).

## Estado por etapa

| Etapa | Estado | % | Lo que falta, en una línea |
|---|---|---|---|
| 07.01 Obligaciones | CUMPLIDA con decisiones abiertas | 95 | ~~Solo deuda del paciente~~ (F-4, api #49) · ~~pantalla~~ (cuenta corriente con concepto, web #42) · ~~auditoría del devengo y la anulación~~ (api #86) · ~~Particular sin precio~~ (DP-17, api #72). Falta: crédito y bono de RF-M18-011 (no hay modelo) y la decisión del arancel 0 |
| 07.02 Cobros | CUMPLIDA | 100 | ~~Anticipos, imputación posterior, anulación, reintegro~~ (F-3, api #39 · web #33) · ~~E2E~~ (web #35) · ~~auditoría del cobro~~ (api #86) · ~~pool y cobros concurrentes~~ (api #91) · ~~el cobro anulado desaparecía del listado~~ (api #101) |
| 07.03 Caja diaria | CUMPLIDA | 100 | ~~Sin frontend~~ (F-6, web #7) · ~~ITs concurrentes~~ (escenarios 33–35, api #28 y #57) · ~~E2E~~ (web #35) |
| 07.04 Presentaciones a financiadores | CUMPLIDA con decisiones abiertas | 90 | ~~Bandeja de elegibles vacía~~ (F-4) · ~~pantallas~~ (F-7, web #9) · ~~E2E~~ (web #35) · ~~ITs~~ (36–40, api #30 y #57) · ~~listado exportable~~ (**este PR**, contrato 0.95.0; el botón espera el cliente). Falta decidir: hallazgos de RF-M21-003, débito parcial y permiso propio |
| 07.05 Egresos y pagos | CUMPLIDA con decisiones abiertas | 90 | ~~Sin frontend~~ (F-5, web #63) · ~~E2E~~ (web #63, `flujo-caja`) · ~~ITs~~ (41–48, api #19, #24, #57 y **este PR**). Falta decidir: adjunto binario (RF-M22-003) y `egreso:manage` (DU-10). RF-M22-006/007 son de la segunda entrega |

**Total de la fase: ~94 %** (promedio simple de las cinco). Ninguno de los puntos que faltan se
cierra con código sin que antes se decida algo.

Frontend en `appKine-web` `main`: `features/billing/pages/{cuenta-corriente, registro-de-cobro,
cobros, caja, presentaciones, presentacion-detalle, egresos, egreso-detalle}`. E2E contra el backend
real en `e2e/flujo-caja.spec.ts` (caja, cierre → deuda → cobro → caja, egreso pagado y anulado),
`flujo-cobros-posteriores.spec.ts`, `flujo-prepago.spec.ts` y `flujo-presentaciones.spec.ts`
(borrador → confirmar → factura → pago → conciliar).

## ~~El hallazgo central: no existe la obligación del financiador~~ → cerrado por F-4

> **Cerrado el 07/10/2026 por F-4** (api #49, `V77`, contrato 0.60.0, diseño en
> `docs/diseno/AKINE-F-4-obligacion-financiador.md`). El cierre de una sesión cuya oferta admite
> obra social, con cobertura vigente del paciente (B-2) y un convenio de la sede con arancel para la
> práctica (DP-11), devenga **dos** filas: `FINANCIADOR` por `importe_financiador` y `COSEGURO` (a
> cargo del paciente) por `coseguro`, con el snapshot entero del convenio y del arancel. Sin
> cobertura, sigue la `PARTICULAR`. `ObligacionDelFinanciadorIT` prueba contra MySQL que la fila
> aparece en la bandeja de elegibles, se presenta en un lote y suma en "prestado" del reporte.

Los cuatro pasos que pedía, todos hechos: cobertura vigente por `spi` (B-2), práctica en
`SesionCerrada` (06.04 + A-9), `ArancelCongelado` copiado (`snapshot_*` de `V77`), y financiador y
coseguro como obligaciones separadas.

## Defectos — verificados

- [x] ~~La cuenta corriente del paciente listaba todas las obligaciones y un cobro podía saldar la
  parte del financiador.~~ → F-4 (api #49, 07/10): lista sólo `responsable = PACIENTE` y el cobro
  contra el financiador es `409 obligacion-no-cobrable`.
- [x] ~~`FinanciadorPagoService.cuentaCorriente` filtra por sede y pagina fijo en 200.~~ → F-1
  (api #14, 06/10): suma en la base, cruza sedes; `CuentaCorrienteDeFinanciadorIT`.
- [x] ~~`revisar` colapsa deuda de otro financiador y del paciente.~~ → F-2 (api #18, 06/10):
  `DEUDA_DEL_PACIENTE`, contrato 0.48.0.
- [x] ~~`debitar` mueve el saldo antes de validar.~~ → F-2 (api #16, 06/10).
- [x] ~~El rechazo sale por `GlobalExceptionHandler`.~~ → F-2 (api #16, 06/10).
- [x] ~~El CSV de financiadores puede no sumar a `prestado`.~~ → F-2 (api #17, 06/10).
- [x] ~~El débito no persistía la marca del ítem (`DEBITADO` en la respuesta, `INCLUIDO` en la
  base).~~ → F-8 (api #57, 07/10), `DebitoYPagoConcurrenteIT`.
- [x] ~~Cobrar, devengar y anular deuda no escribían `audit_event`.~~ → api #86 (11/10).
- [x] ~~El cierre de sesión y el cobro retenían una conexión y pedían otra (`REQUIRES_NEW`
  anidado): diez cobros a la vez trababan el pool.~~ → api #91 (11/10), `docs/carga.md` §8.
- [x] ~~Un cobro anulado desaparecía del listado de cobros de la persona.~~ → api #101 (13/10),
  contrato 0.90.0.

## Faltantes

### Backend / API
- [x] ~~Obligación del financiador.~~ → F-4, api #49 (07/10).
- [x] ~~07.02: anticipos, imputación posterior, anulación y reintegro.~~ → F-3, api #39 (06/10),
  `V69`, contrato 0.54.0, `AnticipoYAnulacionIT`. El prepago de recepción como anticipo entró en
  E-6 (api #54, 07/10).
- [x] ~~07.04: listados exportables.~~ → **este PR** (13/10): `GET
  .../presentaciones/{presentacionId}/export` (`exportarPresentacion`, contrato **0.95.0**) devuelve
  el detalle del lote como CSV. Serializa la misma vista que el detalle —sin consulta propia—, con
  PHI mínima (`personaId`, no nombre ni documento) y las celdas que una planilla tomaría por fórmula
  neutralizadas con un apóstrofo. `PresentacionCsvTest` y `BillingControllersTest`. **El botón en
  la pantalla espera el cliente 0.95.0**, que se regenera cuando este contrato entre a `main`.
- [ ] 07.04: permiso propio (hoy reusa `cobro:register`) — **pendiente del usuario**, ver abajo.
- [ ] 07.04: hallazgos de orden, autorización y credencial (RF-M21-003) — **pendiente del usuario**.
- [ ] 07.04: débito parcial de una presentación — **pendiente del usuario**.
- [ ] 07.05: adjunto binario del comprobante (RF-M22-003) — **pendiente del usuario**.
- [ ] 07.05: `egreso:manage` propio o seguir con `caja:operate` (DU-10) — **pendiente del usuario**.
- [x] ~~07.05: `totalPagadoVigente` no tiene uso.~~ → api #19 (06/10), `SaldoDeEgresoConfrontadoIT`,
  que además destapó el 500 al anular un pago por transferencia sin caja abierta.

### Frontend
- [x] ~~Caja diaria: abrir, movimientos, cerrar con diferencia, reversión.~~ → F-6, web #7 (07/10).
- [x] ~~Bandejas de presentaciones: elegibles, armado, validación, factura, débito, pagos,
  conciliación.~~ → F-7, web #9 (07/10).
- [x] ~~Egresos y pagos a profesionales.~~ → F-5, web #63 (12/10). Los pagos a profesionales son
  egresos con beneficiario `COLABORADOR`; la liquidación calculada (RF-M22-006/007) es de la segunda
  entrega.
- [x] ~~Anulación, reintegro e imputación posterior de cobros.~~ → F-3 web, web #33 (08/10).

### Tests
- [x] ~~ITs de los escenarios diferidos 33–48.~~ → los dieciséis corridos contra MySQL:
  33, 34 (api #28, 06/10) · 35, 36, 38, 44 (api #57, 07/10) · 37, 39, 40 (api #30, 06/10) · 41, 42,
  45, 46, 48 (api #24, 06/10) · 43 (api #19, 06/10) · **47 (este PR, 13/10:
  `AnulacionDePagoSinCajaIT`)**. Detalle en `docs/tests-diferidos.md`.
- [x] ~~E2E económicos: cierre → deuda → cobro → caja; presentación de punta a punta.~~ → web #35
  (08/10: caja, cobro, presentaciones, prepago), web #63 (12/10: egreso pagado y anulado en
  `flujo-caja`) y web #64 (12/10: las pantallas del 08 al 11/10). Corren en el CI contra el backend real (G-9, web #30).

## Desvíos

| Desvío | Documentado |
|---|---|
| ~~Solo obligación del paciente (DP-10)~~ | Cerrado por F-4 (`V77`) |
| ~~Sin anticipos aunque la caja ya existe~~ | Cerrado por F-3 |
| Registro de 07.02 es reconstrucción, no acta | Sí |
| El cobro no exige `caja:operate` | Sí |
| Presentaciones reusa `cobro:register`; DELETE físico de ítems en BORRADOR | Sí — el permiso es decisión abierta |
| Egresos reusa `caja:operate` | Sí — DU-10 |
| Una obligación por responsable y por sesión; la diferencia particular − arancel no se cobra | Sí (F-4, decisión 17 del `CLAUDE.md`) |

## Para cerrar la fase

- [x] ~~Obligación del financiador con snapshot de convenio, con ITs.~~ → F-4 (api #49, 07/10).
- [x] ~~Anticipos y anulación con reintegro.~~ → F-3 (api #39, 06/10 · web #33, 08/10).
- [x] ~~Pantallas de caja, presentaciones y egresos con E2E.~~ → web #7, #9, #63 · E2E web #35, #63 y #64.
- [x] ~~ITs diferidos 33–48.~~ → los dieciséis en verde; el último, 47, en este PR.
- [ ] Registros de 07.03–07.05 promovidos a cierre en `docs/producto/AKINE_IMPLEMENTATION_PLAN.md`:
  **queda para quien mantiene el plan** (este paquete no lo edita). El registro de cierre de la fase
  es el de abajo.
- [ ] QA manual del §6 con `docs/qa/` — **del usuario**.

---

# Registro de cierre — 12/10/2026

> Verificado entre el 12 y el 13/10/2026, rama `akine-cierre-F7` sobre `main` `7112e1f` (contrato 0.91.0).

## Evidencia

| Ítem | Evidencia |
|---|---|
| Obligación del financiador + coseguro + snapshot | api #49 (07/10), `V77`, `ObligacionDelFinanciadorIT` |
| Toda prestación cerrada genera deuda; sin precio, 409 | api #72 (08/10, DP-17), `oferta-sin-precio` |
| Auditoría de cobro, devengo y anulación de deuda | api #86 (11/10) |
| Anticipos, imputación, anulación, reintegro | api #39 (06/10), `AnticipoYAnulacionIT` · web #33 (08/10) · E2E `flujo-cobros-posteriores` (web #35) |
| Cobros concurrentes sin trabar el pool | api #91 (11/10), `docs/carga.md` §8 |
| Cobro anulado visible en el listado | api #101 (13/10), contrato 0.90.0 |
| Caja diaria | web #7 (07/10) · ITs 33–35 (api #28, #57) · E2E `flujo-caja` (web #35) |
| Presentaciones | web #9 (07/10) · ITs 36–40 (api #30, #57) · E2E `flujo-presentaciones` (web #35) |
| Listado exportable del lote | **este PR**: `exportarPresentacion`, contrato 0.95.0, `PresentacionCsvTest` (2) y `BillingControllersTest` (98, con el export en la tabla de operaciones y un caso propio), `OpenApiContractIT` regenerado y en verde |
| Egresos y pagos | web #63 (12/10) · ITs 41–48 (api #19, #24, #57 y este PR) · E2E de egresos en `flujo-caja` (web #63) |
| Escenario 47 | **este PR**: `AnulacionDePagoSinCajaIT`, 2 casos en verde contra MySQL (Testcontainers) |
| ArchUnit y convenciones | `ModuleArchitectureTest` 5/5, `CodingConventionsTest` 12/12, `SchemaNameCollisionTest` 1/1 sobre la rama |

Sólo se corrieron los tests que este PR toca (máquina cargada): la suite completa queda para el CI.

## Pendiente del usuario

Ninguna se decidió acá. Cada una lleva la pregunta y la opción que se recomienda.

1. **DU-10 · Permiso propio de egresos.** ¿`egreso:manage` propio o seguir reusando `caja:operate`?
   *Recomendada:* seguir con `caja:operate` en el MVP —la matriz §32 no tiene fila "Registrar
   Egreso" y hoy los dos permisos los tienen los mismos roles—, y abrir `egreso:manage` cuando
   aparezca un rol que opere la caja sin poder egresar. Es un código en el catálogo y una línea en
   `CajaAcceso`, sin modelo.
2. **Permiso propio de presentaciones (07.04).** Misma pregunta para `cobro:register`.
   *Recomendada:* igual que DU-10, mantenerlo en el MVP.
3. **RF-M21-003 · Orden, autorización y credencial.** El dato está congelado en la obligación del
   financiador (F-4). ¿Un faltante es **reparo** (bloquea confirmar el lote) o **aviso** (se
   confirma igual)? *Recomendada:* reparo para orden y autorización cuando el convenio las exige
   —el financiador las va a debitar de todos modos—, y aviso para la credencial vencida, que se
   suele regularizar después. Es un valor nuevo en `HallazgoDeValidacion`: cambio de contrato
   aditivo.
4. **Débito parcial de una presentación.** Hoy debitar deja el ítem `DEBITADO` entero y, al
   conciliar, la obligación del financiador sigue `PENDIENTE` por el total aunque el resto se haya
   cobrado (07.04 §8, decisión 24 del `CLAUDE.md`). ¿Qué pasa con lo debitado: se re-presenta, se
   pasa al paciente o se da de baja como pérdida? *Recomendada:* la conciliación salda lo cobrado
   (presentado − debitado) y deja `PENDIENTE` sólo lo debitado, re-presentable en otro lote; pasarlo
   al paciente que lo decida el administrativo, no el sistema. Necesita migración.
5. **Arancel 0.** Con arancel total cero no se devenga deuda ni se exige precio (E-7b). ¿Es una
   prestación sin cargo legítima o debe exigir precio? *Recomendada:* aceptarla sin deuda, como
   hoy, pero dejar el rastro en el reporte (prestación sin cargo) para que no se confunda con una
   deuda que faltó devengar.
6. **Pagos a profesionales como liquidación (RF-M22-006/007).** ¿Se confirman fuera del MVP?
   *Recomendada:* sí, segunda entrega; el MVP registra el pago como egreso con beneficiario
   `COLABORADOR`, *"sin inventar regla remunerativa"*.
7. **Adjunto binario del comprobante de egreso (RF-M22-003).** ¿Alcanza la referencia documental
   (tipo, número, fecha) o se extrae el storage a `platform.spi` (refactor de `person` y
   `clinical`)? *Recomendada:* declarar el MVP cubierto por la referencia y hacer la extracción como
   etapa propia después del gate.
8. **Crédito y bono de RF-M18-011.** No existe modelo para ninguno. ¿Entran en el MVP?
   *Recomendada:* no; el esquema mixto del MVP es financiador + coseguro + particular, que es lo que
   F-4 implementa.

## QA manual

El QA manual del §6 **no se corrió**: es del usuario. La guía está en `docs/qa/`:
`07-obligaciones-cobros-caja.md`, `08-presentaciones-facturacion-pagos.md` (con **PRE-10**, el
export, agregado en este PR) y `12-egresos-y-pagos.md`, con las consultas para validar contra la
base después de cada escritura.

## Lo que dejó este paquete

- **Contrato 0.95.0** (aditivo, una operación nueva: 310). `main` está en 0.91.0 (#104) y el PR
  abierto #105 también toma 0.91.0; 0.95.0 no choca con ninguno.
- **Sin migración.**
- **Frontend:** regenerar el cliente contra 0.95.0 cuando entre y agregar el botón "Exportar CSV"
  en `presentacion-detalle-page`.
