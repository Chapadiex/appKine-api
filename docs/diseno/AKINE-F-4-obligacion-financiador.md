# AKINE F-4 — Obligación del financiador, coseguro y snapshot de convenio (M16, M18, M21)

> Paquete **F-4** de `docs/fases/01-trabajo-en-paralelo.md` (ola 3, **ruta crítica del MVP**:
> A-9 → B-2 → C-4 → F-4). Cierra "El hallazgo central: no existe la obligación del financiador" de
> `docs/fases/F7-economia-del-mvp.md` y deshace el recorte de DP-10 sobre 07.01.
> Migración: **`V77`** (la reserva original `V72` quedó por debajo de `V76`, ya mergeada, y Flyway
> corre sin `outOfOrder`; `V72` queda vacía). Contrato: **0.60.0**.

## 1. Qué se cubre y de dónde sale

| Requisito | Qué dice | Cómo se cubre |
|---|---|---|
| RF-M18-001 | Crear deuda al cerrar una sesión facturable | Sigue siendo `ObligacionDevengador`, dentro de la transacción del cierre (07.01) |
| **RF-M18-002** | "Crear componentes paciente/financiador cuando corresponde" | §3: con cobertura y arancel, **dos filas**: la parte del financiador y el coseguro del paciente |
| RN-M18-002 | Una sesión puede generar múltiples obligaciones | Mismo `sesion_id`, responsables distintos: lo que `uk_obligacion_prestacion` (V36) ya admitía |
| RN-M16-003 / 004 / 008 | Cambiar arancel no recalcula; se guarda el snapshot económico aplicado | §4: `ArancelDirectory#congelar` y se **copia** a columnas de `obligacion`. Nunca se vuelve a leer |
| RN-M16-005 | Sin convenio válido no se asume cobertura | §2.3: sin cobertura aplicable → Particular, como hoy |
| RN-M16-007 | Una Oferta puede tener precio particular y arancel financiador a la vez | §2.1: el precio de la oferta manda en Particular; el arancel, con cobertura |
| DP-11 | Manda la práctica realizada; la principal sólo si la sesión cerró sin tratamientos; realizada no habilitada = alerta | §2.2 |
| RF-M21-001 / 003 | Bandeja de elegibles; validar orden, autorización y credencial | §5: la bandeja recibe filas reales y cada una trae los requisitos congelados |
| RF-M23-005 | Reporte de financiadores: prestado | §6: `sumarPrestadoPorFinanciadorEnElReporte` empieza a sumar sin tocarse |
| RN-M18-001 (idempotencia por prestación) | Una prestación genera su deuda una sola vez | §7 |

### Lo que queda AFUERA, y por qué

- **Cobrarle al paciente la diferencia entre el precio particular y el arancel** ("plus"). Ningún RF lo
  pide como regla por defecto; RF-M18-011 exige "configuración explícita" para combinar financiador,
  coseguro y cargo particular, y esa configuración no existe. Cobrarlo por defecto sería decidirlo por
  el usuario (y muchos convenios lo prohíben). **Decisión a revisar** (§10).
- **Los hallazgos de RF-M21-003 que miran evidencia viva** (¿se cargó la orden?, ¿se consumió la
  autorización?). F-4 congela **qué exigía** el convenio el día de la prestación y si la credencial
  estaba vencida; decidir si cada faltante **bloquea** el lote o sólo avisa es una decisión de M21 con
  su propio cambio de enum. Queda como paso siguiente de 07.04 (§10).
- **Re-devengar sesiones ya cerradas.** No se re-devengan: §8.
- **Pantalla.** F-4 es "api + web"; el frontend regenera el cliente contra 0.60.0 en su rama.

## 2. Qué se devenga: la decisión, en orden

```
alCerrar(cierre)
  1. ¿asistió?                      no  → nada (07.01)
  2. ¿la sesión ya tiene deuda?     sí  → nada (idempotencia por hecho de origen, §7)
  3. ¿la oferta admite obra social? no  → PARTICULAR
  4. prácticas candidatas (DP-11)        → §2.2
  5. por cada práctica candidata, en orden:
       por cada cobertura APLICABLE (B-2: principal primero, después por id):
         congelar(financiador, plan, práctica, día de la sede)
         si hay arancel → CONVENIO con esa práctica y esa cobertura. FIN
  6. ninguna cubrió                       → PARTICULAR (precio de la oferta, como 07.01)
```

### 2.1 Particular

Es exactamente 07.01: una fila `responsable = PACIENTE`, `concepto = PARTICULAR`, por el
`precio_base` de la oferta. Sin precio no hay deuda y se loguea sin hacer fallar el cierre.

La oferta tiene que **admitir obra social** (`oferta_servicio_consultorio.admite_obra_social`, M27)
para que se busque cobertura. Es un dato que el centro ya carga y que hasta hoy nadie leía: una
oferta que el centro declara como solo particular no se le factura a un financiador aunque el
paciente tenga cobertura. Viaja en `SesionCerrada.ofertaAdmiteObraSocial`, leído **en el mismo
punto** que el precio (`SesionService#notificarCierre`), por la misma razón que el precio: dos
observadores no pueden ver dos ofertas distintas.

### 2.2 Qué práctica (DP-11)

- **Con tratamientos:** las prácticas realizadas. Si la principal de la oferta está entre ellas va
  primero; el resto por id ascendente. El orden es determinista para que dos corridas del mismo
  hecho elijan lo mismo.
- **Sin tratamientos:** la práctica principal de la oferta (`offering.spi.PracticasDeOfertaDirectory`).
  Si la oferta no declara prácticas, no hay práctica → Particular.
- **Realizada no habilitada:** si la oferta declara prácticas y la elegida no está entre ellas, **no
  se rechaza** (DP-11): la obligación se devenga igual y queda marcada con
  `alerta_practica_no_habilitada = 1`, visible en la API, además del `WARN` en el log. Si la oferta
  no declara ninguna práctica, no hay contra qué comparar y no se marca (son todas las ofertas
  anteriores a A-9).

**Varias prácticas realizadas: UNA obligación por responsable y por sesión, no una por práctica.**
No hay RF que diga si el financiador paga por sesión o por práctica, así que se toma la más
conservadora: se le factura **una** práctica —la primera candidata que tiene cobertura y arancel—.
Facturar de menos es dinero que el centro reclama después; facturar de más a un financiador es un
débito, y con dos prácticas por sesión pasaría en cada lote. Es coherente con DP-12 ("los
financiadores otorgan por sesión, no por técnica") y con el unique de V36, que se conserva intacto.
**Decisión a revisar** (§10): si se decide una por práctica, el unique pasa a
`(sesion_id, responsable, practica_id, deleted_key)` y es otra migración.

### 2.3 Qué cobertura (B-2)

`person.spi.CoberturasAplicablesDirectory#aplicables`: coberturas **financiadas**, activas y vigentes
el día de la prestación **con arancel resuelto** por un convenio de la sede; la principal primero. Se
toma la primera para la que `congelar` devuelve arancel. Que `aplicables` y `congelar` puedan
discrepar (alguien da de baja el arancel entre las dos lecturas) se resuelve probando la siguiente, y
en el peor caso cayendo a Particular: nunca se lanza.

**Sin cobertura vigente, o sin convenio, o sin arancel → todo a cargo del paciente, como hoy.**

La credencial vencida **no excluye** la cobertura (B-2: es alerta). Se congela como
`snapshot_credencial_vencida` para que la presentación lo pueda mostrar.

El **día** es el día local de la sede del instante de cierre, igual que el consumo de C-4: la vigencia
de un convenio es un día de calendario, y resolverlo en UTC corre el día después de las 21 h.

## 3. Cómo se reparte

`convenio_arancel` ya tiene tres importes explícitos y un CHECK (`ck_arancel_partes_suman_total`):
`importe_financiador + coseguro = importe_total`. El reparto **no calcula nada**: copia.

| Fila | `responsable` | `concepto` | `financiador_id` | Importe |
|---|---|---|---|---|
| Parte del financiador | `FINANCIADOR` | `FINANCIADOR` | el de la cobertura | `importe_financiador` |
| Coseguro | `PACIENTE` | `COSEGURO` | NULL | `coseguro` |
| (sin cobertura) | `PACIENTE` | `PARTICULAR` | NULL | `precio_base` de la oferta |

Una parte en cero **no** genera fila (V36: no se devengan obligaciones de cero). Coseguro cero es
cobertura total: sólo la fila del financiador. Financiador cero es una práctica que el financiador
no cubre bajo ese convenio: sólo el coseguro. Total cero es una práctica sin cargo: ninguna fila.

**Ejemplo.** Oferta "Kinesiología" a $8.500 particular. El paciente tiene OSDE 210 y el convenio de
la sede arancela la práctica en total $12.000 = $10.500 financiador + $1.500 coseguro. Al cerrar:

- `FINANCIADOR` · OSDE · $10.500 → aparece en la bandeja de elegibles de OSDE y en "prestado".
- `PACIENTE` · `COSEGURO` · $1.500 → aparece en la cuenta corriente del paciente.
- Suman $12.000 = el arancel total. Los $8.500 de la oferta no intervienen.

Sin la cobertura, la misma sesión devenga una sola fila `PACIENTE`/`PARTICULAR` de $8.500.

**La moneda** de las dos filas es la del arancel (que es la del convenio). La del precio de la oferta
no interviene en ese camino.

## 4. Qué se congela

Las dos filas de una sesión con convenio llevan **el mismo** snapshot, copiado del `ArancelCongelado`
y de la cobertura aplicada. Columnas nuevas en `obligacion` (V77), todas propias de `billing`:

| Columna | De dónde |
|---|---|
| `concepto` | `PARTICULAR` / `FINANCIADOR` / `COSEGURO` |
| `practica_id` | la práctica facturada (NULL si no se pudo determinar: Particular sin práctica) |
| `cobertura_id` | la cobertura del paciente que se aplicó (para ir a buscar la credencial) |
| `snapshot_convenio_id`, `snapshot_arancel_id` | **existían desde V36, reservadas en NULL**: ahora se escriben |
| `snapshot_convenio_codigo`, `snapshot_convenio_nombre`, `snapshot_plan_id` | identidad y texto del convenio ese día |
| `snapshot_importe_total`, `snapshot_importe_financiador`, `snapshot_coseguro` | los tres importes del arancel |
| `snapshot_requeria_orden`, `snapshot_requeria_autorizacion`, `snapshot_requeria_credencial` | **RF-M21-003**: lo que el convenio exigía el día de la prestación |
| `snapshot_credencial_vencida` | la credencial estaba vencida ese día (B-2) |
| `snapshot_vigente_el`, `snapshot_capturado_en` | el día contra el que se resolvió y el instante en que se congeló |
| `alerta_practica_no_habilitada` | DP-11 |

Las CHECK de V77 hacen imposible la fila incoherente, no sólo improbable:

- `ck_obligacion_concepto`: lista cerrada.
- `ck_obligacion_concepto_responsable`: `FINANCIADOR` ⇔ `responsable = FINANCIADOR`.
- `ck_obligacion_snapshot_convenio_coherente`: `PARTICULAR` sin snapshot de convenio; los otros dos
  con el snapshot **entero** (los ids, los tres importes, los requisitos, la cobertura y las fechas).
- `ck_obligacion_snapshot_partes_suman`: la invariante del arancel, también en la copia.
- `ck_obligacion_importe_segun_concepto`: el importe de la fila **es** la parte que le toca.

No se agregan FK sobre las columnas de snapshot, igual que V36: son una **copia**, y lo que importa
es que sigan diciendo lo mismo aunque el convenio se dé de baja.

## 5. La bandeja de presentaciones (07.04) y RF-M21-003

`findElegiblesParaPresentar` ya filtraba `responsable = 'FINANCIADOR'` y `financiador_id`: **no se
toca**. Empieza a devolver filas porque ahora existen. `revisar` (RF-M21-003) ya distinguía
`DEUDA_DEL_PACIENTE` y `FINANCIADOR_DISTINTO`, así que el coseguro nunca entra en un lote.

`Obligacion` (la respuesta de la API, que es también la de la bandeja) gana `concepto`,
`practicaId`, `alertaPracticaNoHabilitada` y un objeto `convenio` con el snapshot y los tres
requisitos: es lo que el administrativo necesita ver para saber si tiene que juntar la orden antes
de presentar. **Aditivo**: ningún campo cambia de tipo ni desaparece.

## 6. El reporte de financiadores (07.06)

`sumarPrestadoPorFinanciadorEnElReporte` ya agrupaba por `financiador_id` las filas `FINANCIADOR`:
**no se toca**. La advertencia `sin-devengado-de-financiador` cambia de texto: deja de decir que el
devengado no está cableado —ya lo está— y pasa a decir que en el periodo no se devengó nada a nombre
de un financiador, que es lo que un cero significa ahora.

El indicador `devengado` del reporte económico suma las dos filas: financiador + coseguro = arancel
total, que es lo producido.

## 7. Idempotencia y concurrencia

- **Por hecho de origen.** Antes de devengar se pregunta si la sesión ya tiene **alguna** obligación
  viva (`findDeLaSesion`), no sólo la del paciente: si ya devengó el par convenio, un re-disparo no
  agrega una fila `PARTICULAR`, y si devengó Particular, no agrega un financiador.
- **El respaldo es la base.** `uk_obligacion_prestacion (sesion_id, responsable, deleted_key)` impide
  dos filas del mismo responsable. Las dos filas de un convenio tienen responsables distintos, y
  `PARTICULAR` y `COSEGURO` comparten `PACIENTE`: el unique impide además que una sesión quede con
  las dos formas a la vez.
- **Dos cierres concurrentes** de la misma sesión ya se serializan en el numerador de la historia
  clínica (06.05) y el perdedor pierde por `@Version` de la sesión: el que llega segundo, bajo
  `READ_COMMITTED`, ve las filas del primero en el paso 2 y no devenga. Si aun así llegara a
  insertar, el unique lo frena y el cierre perdedor revierte entero.

## 8. Compatibilidad con lo ya cerrado

- Las filas existentes reciben `concepto = 'PARTICULAR'` por el `DEFAULT` de V77 y quedan
  coherentes con todas las CHECK nuevas (todas tienen `responsable = PACIENTE` y snapshot NULL).
- Una sesión cerrada antes de F-4 **no se re-devenga**: el observador sólo corre en un cierre real
  (un segundo cierre sale temprano, RN-M14-005) y, si corriera, el paso 2 encuentra su obligación.
  Pasar a financiador lo ya cerrado es una corrección económica explícita (anular y re-devengar), no
  un efecto de desplegar este paquete.

## 9. La contrapartida del observador que falla

F-4 **la mantiene**, y por la misma razón que 07.01: una prestación sin deuda no se nota. Lo que
cambia es que ahora hay más motivos *de negocio* para no encontrar arancel, y **ninguno** lanza:
sin cobertura, sin convenio, sin arancel o sin práctica, el devengo cae a Particular. Lo único que
hace fallar el cierre es un fallo técnico (la base) o una fila que viola un CHECK, que sería un
defecto de este código y no del dato del centro.

## 10. Design challenge

1. **Ownership.** No hay tablas nuevas: todas las columnas son de `obligacion`, de `billing`.
   `billing` no escribe nada de `person`, `contracting` ni `offering`: les pregunta por su `spi` y
   copia.
2. **Ciclos.** Arista nueva: `billing → offering.spi` (`PracticasDeOfertaDirectory`). `offering`
   depende de `organization`, `platform` y `resource`, y ninguno de ellos alcanza a `billing`; además
   **nadie depende de `billing`**. `billing → person.spi` y `billing → contracting.spi` ya existían.
   Verificado con `ModuleArchitectureTest`.
3. **Tenant.** `obligacion` ya lleva `organization_id`; las columnas nuevas son atributos de la fila.
   Toda llamada a un `spi` pasa organización **y** sede. Un convenio de otra sede o de otro tenant no
   resuelve nunca (contrato de `ArancelDirectory`).
4. **Reglas maestras.** Deuda ≠ cobro ≠ caja: F-4 sólo escribe deuda. Cobertura del paciente ≠
   convenio del consultorio (regla 9): la cobertura dice *quién* puede pagar; el convenio de la sede
   dice *cuánto*; se necesitan las dos. Sesión ≠ Turno: se devenga desde el cierre, nunca desde el
   turno.
5. **Baja lógica.** Ninguna. La migración sólo agrega columnas y CHECK.
6. **Contrato.** Aditivo: cuatro propiedades nuevas en `Obligacion` y un objeto nuevo
   `ConvenioAplicado`. Minor: **0.60.0**.
7. **Ruta crítica.** Los cimientos están: B-2 (cobertura aplicable), A-9 (práctica principal), C-4
   (consumo por práctica y sesión), 03.05 (`congelar`). No se adelanta nada.
8. **El caso que rompe el diseño.** *La cuenta corriente del paciente empieza a sumar la deuda de la
   obra social.* `findDeLaPersona` devolvía **todas** las obligaciones de la persona, y la pantalla de
   cuenta corriente y el resumen del Paciente 360 suman su saldo: con F-4, un paciente con cobertura
   vería que "debe" $12.000 cuando debe $1.500. Peor: el registro de cobro aceptaba imputar un pago
   del paciente **contra la fila del financiador** (`exigirCobrable` sólo miraba persona y estado), y
   esa deuda desaparecía de la bandeja de presentaciones. Hasta F-4 era inobservable porque no existía
   la fila; F-4 lo vuelve real. Se resuelve en el mismo paquete: la cuenta corriente del paciente
   (RF-M18-003) lista sólo `responsable = PACIENTE`, y un cobro contra una deuda de financiador es
   `409 obligacion-no-cobrable` ("es deuda del financiador").

   Segundo caso adverso: *dos cierres concurrentes de la misma sesión con cobertura.* §7: el numerador
   los serializa, el segundo ve las filas del primero, y el unique es la red.

### Decisiones a revisar

- **Una obligación por sesión, no por práctica** (§2.2). La más conservadora; cambiarla cambia el
  unique.
- **No se cobra la diferencia particular − arancel** (§1). Necesita la configuración explícita de
  RF-M18-011.
- **`admite_obra_social` decide si se busca cobertura** (§2.1). Una oferta creada sin marcarlo
  devenga siempre Particular. Es lo que el dato dice, pero es la primera vez que algo lo lee.
- **El copago del plan (`plan_cobertura.copago`) no interviene**: el coseguro sale del arancel del
  convenio, que es el que la sede pactó. Si un plan declara copago y el convenio coseguro cero, gana
  el convenio.
- **RF-M21-003 sigue sin bloquear por orden, autorización o credencial**: el dato ya está congelado
  en la fila; falta decidir si cada faltante es reparo (bloquea el lote) o aviso.
