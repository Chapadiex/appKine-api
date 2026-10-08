# AKINE E-7 — "Atender como Particular" llega a la obligación (RF-M08-007)

**07/10/2026** · rama `akine-E-7-particular-en-obligacion` · **sin migración** · **sin cambio de
contrato** (sigue `0.66.0`) · paquete E-7 de
[01-trabajo-en-paralelo](../fases/01-trabajo-en-paralelo.md). Cierra el hueco que declararon E-4
(§10, "`billing` no lee la modalidad de la recepción") y B-3 (§8, hallazgo 1).

## 1. Qué se cubre

| Fuente | Qué dice | Cómo se cubre |
|---|---|---|
| **RF-M08-007** | Continuar bajo condición particular sin alterar la cobertura general; la selección particular se refleja en la obligación económica | El cierre de una sesión cuyo turno se recibió como Particular devenga **sólo** la obligación `PARTICULAR`, al precio particular del día (B-3) |
| RF-M13-005 / DP-16 | Particular es una decisión explícita del mostrador, con motivo | Es la única entrada: `ModalidadRecepcion.PARTICULAR` en la recepción vigente del turno |
| RN-M13-004 | Particular no modifica la cobertura maestra | No se toca `cobertura_paciente`; la decisión vive en la recepción y viaja como un booleano en el hecho del cierre |
| RF-M17-004, DP-12 | El cierre consume una unidad por autorización involucrada | Atendida como Particular **no consume**: el financiador no paga esa sesión |

**Qué no cambia:** sesión sin turno (atención sin recepción, DP-05), turno sin recepción,
recepción anulada, recepción `LLEGO`/`OBSERVADA` sin resolver y recepción validada con
`COBERTURA`. En todos esos casos el devengo de F-4 y el consumo de C-4 siguen exactamente igual.

## 2. Diseño

```
scheduling.spi.TurnoDirectory#atendidoComoParticular(org, sede, turno)   ← nuevo método
        │   (SchedulingTurnoDirectory: recepción vigente del turno, misma sede,
        │    modalidad == PARTICULAR; cualquier otro caso → false)
        ▼
encounter.SesionService#notificarCierre  — lo lee UNA vez, junto al precio
        │
        ▼
SesionCerrada.particularPorRecepcion  (componente nuevo, al final; overload de 15 args = false)
        │
        ├──▶ billing.ObligacionDevengador: si true, no busca cobertura → devengarParticular
        │        (precioDeLaOferta ya es el precio particular del día, B-3 OfertaDirectory#precioEn)
        └──▶ encounter.ConsumoDeAutorizacionEnCierre: si true, sale sin consumir
```

**Por qué en `SesionCerrada` y no una sonda que pregunte `billing`.** Hay dos consumidores —el
devengo (`billing`) y el consumo de autorizaciones (`encounter.infrastructure`)— y tienen que ver
**la misma** decisión. Es el criterio con el que `encounter` ya lee el precio y la obra social en
un solo punto: si cada observador consultara la recepción por su cuenta, uno podría devengar
particular y el otro consumir la autorización. Y la arista `encounter → scheduling.spi`
(`TurnoDirectory`) ya existía: **no se agrega ninguna arista entre módulos**.

**Por qué un método en `TurnoDirectory` y no una interfaz nueva.** `TurnoDirectory` ya es "lo que
otro módulo necesita saber de un turno sin depender de su entidad", ya lo inyecta `SesionService`
y su única implementación es `SchedulingTurnoDirectory`. Una interfaz aparte obligaba a cambiar el
constructor de `SesionService` por un booleano.

**Precio.** No se lee nada nuevo: `SesionCerrada.precioDeLaOferta` ya es el precio particular del
día del cierre (`OfertaDirectory#precioEn`, B-3: precio por vigencia si lo hay, si no
`precio_base`). Sin precio rige la regla 2 de 07.01: se loguea y no se devenga.

## 3. Design challenge (CLAUDE.md §3)

1. **Ownership.** No hay tablas nuevas ni columnas nuevas. `recepcion` es de `scheduling` y sólo la
   lee su propio adaptador; `encounter` recibe un booleano, `billing` no ve la recepción.
2. **Ciclos.** Ninguna arista nueva: `encounter → scheduling.spi` existía (`TurnoDirectory`,
   `AtencionProbe`), `billing → encounter.spi` existía. `ModuleArchitectureTest` en verde sin
   tocar reglas.
3. **Tenant.** La recepción se busca por `(organization_id, turno_id)` —como la indexa
   `uk_recepcion_turno_vigente`— y además se exige la sede de la sesión: un turno de otra sede u
   otra organización responde `false` (comportamiento anterior), nunca `true`.
4. **Reglas maestras.** Turno ≠ Recepción ≠ Sesión: la sesión no cambia de comportamiento clínico
   ni la recepción se mueve por el cierre; sólo el hecho económico lleva la decisión del mostrador.
   Cobertura ≠ Convenio ≠ Obligación: la cobertura maestra queda intacta (RN-M13-004), el convenio
   no se consulta, la obligación es particular.
5. **Baja lógica.** Nada se borra ni se escribe en scheduling.
6. **Contrato.** Sin cambio: no hay endpoint nuevo ni campo nuevo. `SesionCerrada` es un `spi`
   interno; el overload de 15 argumentos conserva la forma de E-6.
7. **Ruta crítica.** Cimientos en `main`: recepción con modalidad (E-4), precio particular por
   vigencia (B-3), devengo por convenio (F-4), consumo por autorización (C-4), turno en el hecho
   del cierre (E-6). No se adelanta nada.
8. **El caso que rompe el diseño.**
   - *El mostrador resuelve Particular después de que el profesional cerró.* El cierre ya devengó
     por convenio y la decisión llega tarde. E-4 no permite Particular desde `EN_ESPERA` ni
     `LLAMADA`, así que en el flujo normal la decisión precede a la atención; si igual pasa
     (recepción registrada tarde), la deuda **no se recalcula**: es una foto del cierre, como el
     precio. Corregirla es anular y redevengar a mano, igual que cualquier otra deuda mal nacida.
   - *Recepción Particular anulada y vuelta a registrar sin resolver.* `findVigente` excluye las
     `ANULADA`: manda la recepción nueva, que no es Particular → devengo por convenio.
   - *Oferta sin precio y atendida como Particular.* Antes de E-7 el convenio igual devengaba;
     ahora no se devenga nada y queda el warning de 07.01. Es lo que el mostrador eligió: sin
     precio particular no hay importe que cobrarle. Ver §5.
   - *Prepago de E-6.* Sin cambios: la imputación busca la deuda del paciente de la sesión, que
     ahora es la `PARTICULAR`.

## 4. Verificación

- Unitarios: `ObligacionDevengadorTest` (Particular por recepción con convenio disponible → una
  sola `PARTICULAR` y ni se consulta cobertura; recepción con cobertura → financiador + coseguro;
  los casos sin turno de F-4 siguen), `ConsumoDeAutorizacionEnCierreTest` (Particular → ninguna
  interacción con el consumo), `SesionServiceTest` (el aviso lleva la decisión leída de
  `TurnoDirectory`).
- IT contra MySQL: `ParticularEnObligacionIT` — paciente con cobertura, convenio, arancel y
  autorización vigente; recepción Particular por `CicloDeRecepcionService` → cierre real → una sola
  obligación `PARTICULAR` de 8500, ninguna del financiador, `cantidad_consumida = 0` y ningún
  movimiento de consumo. El caso de control (misma fixture, recepción sin resolver) devenga
  financiador + coseguro y consume 1: prueba que el primero no pasa en vacío.

## 5. Decisiones a revisar

1. **Particular manda sobre el convenio** (RF-M08-007). E-4 lo había dejado como pregunta de
   producto ("¿manda el mostrador o el convenio?"); se resolvió por el RF. Si se quisiera lo
   contrario, es un `if` en `ObligacionDevengador`.
2. **Oferta sin precio + Particular → sin deuda** (regla 2 de 07.01), en vez de caer al convenio.
   **Reemplazada por DP-17 (08/10/2026, E-7b):** ahora el cierre se bloquea con 409
   `oferta-sin-precio` hasta que se cargue el precio. Ver
   [AKINE-E-7b-cierre-sin-precio](AKINE-E-7b-cierre-sin-precio.md). La (1) quedó confirmada.
3. **Sólo cuenta la recepción vigente al momento del cierre**; no hay recálculo posterior.
