# AKINE-07.06 — Reportes y tableros del MVP (M23)

> Etapa: **AKINE-07.06**. Módulo nuevo: `reporting`. Contrato **0.41.0**. Migración **V59**
> (dos índices, ninguna tabla). Rama `akine-07.06-reportes`, sobre `akine-f7-integracion`.
>
> Revisión adversarial obligatoria: `AKINE-07.06-challenge.md`. **Se escribió antes del código.**

---

## 0. La frase que ordena toda la etapa

**Un reporte es una lectura, y una lectura no puede convertirse en una segunda copia de la
verdad.**

El repositorio viene sosteniendo esa regla en cinco lugares y ninguno es casual:

| Dónde | Qué se calcula al leer |
|---|---|
| 02.04 | La disponibilidad efectiva. No se materializa. |
| 03.02 | El Paciente 360. Cada módulo aporta lo suyo en el momento de la consulta. |
| 04.02 | El timeline clínico. Sin tabla de timeline. |
| 05.01 | El slot. Se calcula y no se persiste. |
| 06.03 | La comparación de mediciones. |

**Esta etapa no materializa ningún agregado.** No hay tabla de snapshot diario, no hay tabla de
KPI, no hay proyección precalculada. Lo único que V59 agrega son **dos índices**, que no son una
copia de la verdad: son la misma verdad, ordenada para poder leerla.

La defensa de por qué no se materializa, y qué evidencia haría cambiar esa decisión, está en la
pregunta 4 del challenge. Adelanto la condición de salida, porque es lo que hace que esta decisión
sea revisable y no un dogma: **se materializa cuando exista una medición, contra un dataset
representativo y contra MySQL real, que muestre que un reporte del MVP no entra en el tiempo
interactivo con los índices de V59.** Hoy esa medición no existe y no se puede tomar: Docker está
caído.

## 1. Alcance — qué entra y qué no

### Entra (RF-M23-001 a RF-M23-006)

| RF | Reporte | Estado |
|---|---|---|
| RF-M23-001 | Dashboard operativo | **Completo** |
| RF-M23-002 | Reporte de turnos | **Completo** |
| RF-M23-003 | Reporte clínico | **Completo**, agregado y sin contenido clínico |
| RF-M23-004 | Reporte económico | **Completo**, con los cinco conceptos separados |
| RF-M23-005 | Reporte financiadores | **Estructuralmente completo, con un cero declarado.** Ver §7 |
| RF-M23-006 | Exportar reporte | **CSV sincrónico y acotado.** El export asíncrono se difiere |

### No entra, y por qué

| RF | Por qué no |
|---|---|
| RF-M23-007 — ocupación de clases y espacios | El módulo `activity` (M28/M29) **no existe**. Es segunda entrega. |
| RF-M23-008 — continuidad del circuito de salud | Necesita alta clínica *y* servicio preventivo, o sea M28. |
| RF-M23-009 — rentabilidad por servicio | **No hay cimiento.** `egreso` no se liga a una oferta ni a un servicio: su beneficiario es un colaborador o un externo y su categoría es contable, no de catálogo. Imputar costo por servicio exige un modelo de costeo que ninguna etapa construyó. Construirlo acá sería inventar el dato. |
| RF-M23-010 — estado de pases y abonos | M29. No existe. |

**No se construye ningún tablero de segunda entrega con ceros.** Un reporte que muestra cero
porque su fuente no existe es peor que un reporte ausente: el operador no distingue "no hubo" de
"no lo sé".

## 2. Arquitectura — la dependencia va al revés

### El reflejo, y por qué está mal

El reflejo es que `reporting` le pregunte a `billing`, a `scheduling`, a `encounter` y a
`clinical`. Eso convierte a `reporting` en el módulo que importa a todos, y lo pone a **un solo
paso de cerrar un ciclo con cualquiera de ellos**: basta que alguno quiera, alguna vez, exponer un
indicador propio o leer una definición de métrica.

Además obliga a `reporting` a conocer el modelo de los otros cinco módulos —qué estados suman,
qué columna lleva la fecha de corte, qué es una baja lógica en cada tabla— y eso ya se probó que
sale mal: **el que sabe cómo se cuenta una obligación es `billing`, no el que la muestra.**

### La decisión: contribuyentes

`reporting` publica la interfaz y **cada módulo río abajo la implementa**:

```
com.akine.reporting.spi.ReporteContributor     ← la declara reporting
        ▲            ▲            ▲            ▲
        │            │            │            │
   scheduling    encounter    clinical      billing        ← la implementan
```

`reporting` depende de `organization.spi` (permisos, sede) y de `platform.spi` (auditoría,
tenant). **De ningún módulo de negocio.** Nadie depende de `reporting` salvo por su `spi`, que no
importa nada de negocio.

Es exactamente el patrón de `person.spi.ResumenDePersonaContributor` (03.02) y de
`clinical.spi.EventoClinicoContributor` (04.02). Su javadoc ya explica el porqué con todas las
letras: *"el reflejo es que `person` le pregunte a `scheduling` y a `billing`. Eso es un ciclo y
ArchUnit lo rechaza"*. Acá el riesgo es **mayor**, porque los módulos fuente son cinco y no dos.

Consecuencia buscada, la misma que en el 360: **un reporte nuevo, o una sección nueva de un
reporte existente, no toca `reporting`.** Cuando exista `activity`, RF-M23-007 agrega su
contribuyente y aparece solo.

### Paquetes

```
com.akine.reporting.spi              ReporteContributor · ConsultaDeReporte · AporteDeReporte
                                     IndicadorDeReporte · FilaDeReporte · ReporteCode
                                     AdvertenciaDeReporte
com.akine.reporting.api              ReporteController · ReportingApiActor
                                     ReportingProblemHandler · dto/
com.akine.reporting.application      ReporteService · OperatingActor · ReporteView
                                     RangoDeReporte · ExportadorCsv
com.akine.reporting.domain           PermissionCodes · exception/
com.akine.reporting.infrastructure   — vacío: reporting no tiene tablas propias
```

**`reporting` no tiene `infrastructure` porque no tiene ninguna tabla.** No es una omisión: es la
prueba estructural de que no materializa nada.

## 3. El contrato interno

```java
public enum ReporteCode { OPERATIVO, TURNOS, CLINICO, ECONOMICO, FINANCIADORES }

public record ConsultaDeReporte(
        long organizationId,
        long consultorioId,
        LocalDate desde,          // inclusive, en la zona de la sede
        LocalDate hasta,          // inclusive, en la zona de la sede
        ZoneId zona,              // consultorio.timezone — NUNCA la del servidor
        Instant desdeInstante,    // desde 00:00:00 en `zona`, ya convertido
        Instant hastaInstante,    // hasta+1 día 00:00:00 en `zona`, exclusivo
        int limiteFilas) { }

public record IndicadorDeReporte(
        String clave, String etiqueta, Tipo tipo,
        BigDecimal valor, String moneda,
        String fuente,            // "M18 obligacion", "M20 movimiento_caja"
        String criterioDeFecha) { // "devengada_en, zona de la sede"
    public enum Tipo { DINERO, CONTEO, PORCENTAJE }
}

public record AporteDeReporte(
        String seccion, String titulo,
        List<IndicadorDeReporte> indicadores,
        List<String> columnas, List<FilaDeReporte> filas,
        List<AdvertenciaDeReporte> advertencias) { }

public interface ReporteContributor {
    Set<ReporteCode> reportes();     // a qué reportes aporta
    String seccion();                // nombre estable; viaja al cliente
    String permisoRequerido();       // null = alcanza con pertenencia al tenant
    AporteDeReporte aportar(ConsultaDeReporte consulta);
}
```

Dos campos que no son decorativos y que RF-M23 pide explícitamente
(*"Cada métrica define fuente, zona horaria, moneda, estados incluidos y fecha de corte"*):

- **`fuente`** — de qué tabla sale el número. Es lo que permite que alguien que ve dos números
  parecidos sepa que son dos cosas distintas antes de intentar sumarlos.
- **`criterioDeFecha`** — con qué columna se recortó el período, y si es un instante proyectado a
  la zona de la sede o una fecha de negocio ya local.

## 4. Los cinco conceptos económicos, que son cinco

Esta es la parte donde el proyecto ya pagó caro. **Deuda, cobro, caja, presentación a financiador
y egreso son cinco cosas distintas.** El reporte económico las muestra **en cinco bloques con
cinco fuentes declaradas** y **no publica ningún total que las sume.**

| Indicador | Fuente | Corte | Estados que incluye |
|---|---|---|---|
| `devengado` | `obligacion.importe_original` (M18) | `devengada_en` | todos menos `ANULADA`, `deleted_at IS NULL` |
| `devengado-anulado` | `obligacion.importe_original` | `devengada_en` | sólo `ANULADA` |
| `deuda-vigente` | `obligacion.saldo` | **saldo actual, no reconstruido** | `PENDIENTE`, `PARCIAL` |
| `cobrado` | `cobro.total` (M19) | `cobrado_en` | `deleted_at IS NULL` |
| `caja-ingresos-efectivo` | `movimiento_caja.importe` (M20) | `fecha_negocio` | `tipo = INGRESO`, `afecta_arqueo = 1` |
| `caja-egresos-efectivo` | `movimiento_caja.importe` | `fecha_negocio` | `tipo = EGRESO`, `afecta_arqueo = 1` |
| `caja-diferencias-de-arqueo` | `jornada_caja.diferencia` (M20) | `fecha_negocio` | `estado = CERRADA` |
| `egresos-confirmados` | `egreso.importe_total` (M22) | `registrado_en` | `CONFIRMADO`, `PAGADO` |
| `egresos-saldo-pendiente` | `egreso.saldo_pendiente` | saldo actual | `CONFIRMADO` |

**Por qué `deuda-vigente` declara "saldo actual, no reconstruido":** `obligacion.saldo` es un
derivado materializado que refleja el estado de hoy, no el del día de corte. Reconstruir el saldo a
una fecha exigiría restar las imputaciones posteriores, que es una segunda fórmula de la misma cosa
y la clase de divergencia que esta etapa se propuso no crear. Se declara en `criterioDeFecha` y el
cliente lo muestra. **Un número honesto y explicado vale más que uno exacto y silencioso.**

### La reconciliación, que es una comparación y no una suma

El plan pide que *"los totales económicos reconcilien"*. Reconciliar **no** es sumar: es poner dos
números que deberían coincidir uno al lado del otro y mostrar la diferencia.

| Indicador | Qué compara |
|---|---|
| `conciliacion-cobrado-efectivo` | `SUM(cobro_medio.importe)` con `medio = EFECTIVO` en el período |
| `conciliacion-caja-de-cobros` | `SUM(movimiento_caja.importe)` con `tipo_origen = COBRO` y `afecta_arqueo = 1` |
| `conciliacion-diferencia` | la resta de los dos. **Debería ser cero.** |

Si `conciliacion-diferencia` no es cero, hay plata cobrada en efectivo que no entró a ninguna caja,
o al revés. Es el único lugar del reporte donde dos fuentes se combinan, y se combinan **restando,
declarando que se restan, y esperando cero.**

> **El error que esto evita, dicho sin eufemismos:** un tablero con un campo "ingresos totales" que
> sume `cobrado` + `caja-ingresos` cuenta **dos veces** cada cobro en efectivo, porque el
> movimiento de caja de origen `COBRO` *es* ese cobro visto desde el cajón. No existe tal campo en
> esta etapa y no debe existir en ninguna futura.

## 5. Las secciones, por módulo

### `scheduling` → `TurnosEnElReporte` — RF-M23-002, y aporta a OPERATIVO

Permiso: `turno:read`. Fuente `turno` (M12), corte por `inicio` proyectado a la zona de la sede,
`deleted_at IS NULL`.

Indicadores: `turnos-totales`, `turnos-reservados`, `turnos-confirmados`, `turnos-cancelados`,
`turnos-ausentes`, `turnos-en-espera`, `turnos-reprogramados` (`reprogramado_en IS NOT NULL`),
`tasa-ausentismo` (ausentes sobre totales menos cancelados; **cero cuando el denominador es cero**,
nunca una división por cero disfrazada).

Filas para el CSV: una por día y estado.

> **No se lee `turno_evento`.** Las reprogramaciones se cuentan por `turno.reprogramado_en`, que es
> una columna del propio turno y está cubierta por el rango de `ix_turno_sede_dia`. Contarlas desde
> el append-only obligaría a un índice nuevo sobre una tabla que crece sin techo, para responder
> exactamente lo mismo.

### `encounter` → `SesionesEnElReporte` — RF-M23-003, y aporta a OPERATIVO

Permiso: `sesion:register`. Fuente `sesion` (M14), corte por `cerrada_en`.

Indicadores: `sesiones-cerradas`, `sesiones-en-borrador`, `sesiones-con-caso`,
`sesiones-sin-caso`, `asistencia-presente`, `asistencia-ausente`.

`sesiones-con-caso` / `sesiones-sin-caso` es **RN-M23-004 hecho número**: las sesiones de casos
distintos no se mezclan, y las que no tienen caso se cuentan aparte en vez de caer en el mismo
balde. Las filas del CSV agrupan por `caso_id`.

> **Minimización clínica.** La sección aporta conteos y, a lo sumo, un `caso_id`. **Ni una
> evolución, ni un diagnóstico, ni una nota de cierre, ni un nombre de paciente.** Es la misma
> línea que trazó el Paciente 360 en 03.02.

### `clinical` → `CasosEnElReporte` — RF-M23-003

Permiso: `hc:read`. Fuente `caso_clinico` (M10), sede por `oferta_consultorio_id`.

Indicadores: `casos-abiertos-en-el-periodo` (`abierto_en` en rango), `casos-cerrados-en-el-periodo`
(`cerrado_en` en rango), `casos-activos-al-corte` (`estado = ACTIVO` hoy — declarado como corte
actual, igual que `deuda-vigente`).

### `billing` → `EconomiaEnElReporte` — RF-M23-004, y aporta a OPERATIVO

Permiso: `cobro:register`. Es el mismo con el que `EconomiaEnElResumenDePersona` ya deja leer la
cuenta corriente. **Una etapa no amplía la matriz de permisos.**

Los nueve indicadores de §4 más los tres de reconciliación.

### `billing` → `FinanciadoresEnElReporte` — RF-M23-005

Permiso: `cobro:register`. Indicadores `prestado`, `presentado`, `facturado`, `debitado`,
`cobrado-de-financiadores`, `pendiente`. Filas por financiador.

**Trae una advertencia fija.** Ver §7.

## 6. La API — aditiva, tres operaciones

| Método | Ruta | Qué devuelve |
|---|---|---|
| `GET` | `/api/v1/consultorios/{consultorioId}/reportes` | El catálogo de reportes disponibles y, por cada uno, sus secciones y el permiso que cada una pide |
| `GET` | `/api/v1/consultorios/{consultorioId}/reportes/{reporte}` | El reporte: secciones, indicadores, filas, omitidas y advertencias |
| `GET` | `/api/v1/consultorios/{consultorioId}/reportes/{reporte}/export` | **`text/csv`**, mismos filtros, misma computación |

Parámetros: `desde` y `hasta` (`LocalDate`, obligatorios, inclusivos, en la zona de la sede).

**Cambio de contrato: puramente aditivo.** Tres paths nuevos, ningún path existente tocado, ningún
schema existente modificado. Minor: **0.40.0 → 0.41.0**.

### La respuesta declara lo que no muestra

```jsonc
{
  "reporte": "ECONOMICO",
  "desde": "2026-09-01", "hasta": "2026-09-30",
  "zona": "America/Argentina/Cordoba",
  "generadoEn": "2026-09-20T14:02:11Z",
  "secciones": [ /* … */ ],
  "omitidas":  [ { "seccion": "clinica", "permisoRequerido": "hc:read" } ],
  "advertencias": [
    { "seccion": "financiadores", "codigo": "sin-devengado-de-financiador",
      "detalle": "Ninguna obligación se devenga a nombre de un financiador todavía: 'prestado' vale cero por construcción, no por ausencia de actividad." }
  ]
}
```

**`omitidas` y `advertencias` son el corazón del diseño de esta API**, y las dos existen por la
misma razón: *un cero sin explicación es una mentira que el operador no puede detectar.*

- `omitidas` — una sección cuyo permiso el actor no tiene **no se pide, no se calcula y no produce
  403**. Viaja declarada con el código de permiso que falta. Un 403 sobre el reporte entero por no
  poder ver la deuda dejaría al recepcionista sin poder abrir ningún tablero; y omitir en silencio
  sería peor, porque leería "cero turnos" donde dice "no podés ver los turnos". Es literalmente la
  decisión de 03.02, y se repite porque el problema es el mismo.
- `advertencias` — un indicador que vale cero **por construcción** y no por falta de actividad lo
  dice. Es el caso de `prestado`.

### El export es la misma computación

`GET …/export` **no tiene su propia consulta**. Llama a `ReporteService.generar(...)` —el mismo
método— y serializa el mismo `ReporteView` a CSV. La alternativa (una query optimizada para el
export) garantiza que algún día la pantalla y el archivo digan cosas distintas, y nadie sepa cuál
de las dos miente.

Tope duro: `limiteFilas` por sección y ventana máxima de **366 días**. Fuera de eso, `400` con
`problemType` propio. **El export asíncrono con progreso se difiere** —el plan lo pide "cuando
excedan el tiempo interactivo", y sin una medición contra MySQL real no hay forma de saber si eso
pasa. Queda anotado en `docs/tests-diferidos.md`.

## 7. El cero declarado de RF-M23-005

**No existe ninguna obligación con `responsable = FINANCIADOR`, y nada la produce.**
`ObligacionDevengador` devenga una sola obligación a nombre del paciente; el enchufe que `V36`
reservó (`snapshot_convenio_id`, `snapshot_arancel_id`) y el que `V56` agregó (`financiador_id`)
**nunca se conectaron**. Está en el design challenge de 07.04 y es una decisión pendiente del
usuario.

Consecuencias medibles, y las dos se declaran:

1. `prestado` en el reporte de financiadores vale **cero en cualquier despliegue real**.
2. `presentado`, `facturado`, `debitado` y `pendiente` salen de `presentacion`, que sólo puede
   contener items provenientes de obligaciones de financiador: también valen **cero**, por la misma
   causa aguas arriba.

El reporte **se construye igual** —la estructura, la consulta y el CSV son correctos y el día que
el devengado se recablee empiezan a dar números sin tocar una línea— **y emite la advertencia
`sin-devengado-de-financiador` siempre que sus totales sean cero.** No se construye un tablero que
en producción muestre ceros sin explicar por qué.

## 8. Tenant, permisos y auditoría

- **`organizationId` sale del contexto del actor, nunca de un parámetro.** No hay forma de pedir un
  reporte de otra organización: el id no viaja en la request.
- **`consultorioId` viaja en la ruta y se valida contra `ConsultorioDirectory`.** Sede de otro
  tenant → **404**, nunca 403. Falta de contexto → **403**, nunca 401.
- **Gate del reporte: `reporte:read`.** Ya existe en `PermissionCode` (`REPORTE_READ`) desde 01.03
  y **nunca se había usado**. Esta etapa es su primer consumidor. No se inventa ningún permiso
  nuevo.
- **Gate por sección: el permiso que declara el contribuyente.** Se resuelven **todos los permisos
  de una vez** con `PermissionEvaluator.effectivePermissions`, no con un `evaluate` por sección:
  con cinco secciones serían cinco resoluciones de memberships para responder una pantalla.
- **Toda consulta de un reporte que incluye una sección clínica se audita**, dentro de la
  transacción, con `AuditTrail`. Evento `REPORTE_CONSULTADO`. Es la regla de 04.01 —toda lectura
  clínica se audita, no sólo las mutaciones— y se aplica aunque el aporte sea agregado: lo que la
  regla protege es el *acceso*, no el volumen.
  Como consecuencia, `ReporteService.generar` es `@Transactional` **sin** `readOnly`: una
  transacción de sólo lectura no puede escribir el evento.

## 9. Esquema — V59, dos índices y ninguna tabla

```sql
CREATE INDEX ix_sesion_sede_cierre
    ON sesion (organization_id, consultorio_id, cerrada_en);

CREATE INDEX ix_caso_clinico_sede_apertura
    ON caso_clinico (organization_id, oferta_consultorio_id, abierto_en);
```

Por qué exactamente estos dos, y ninguno más:

- `sesion` tiene índices por historia, por profesional y por caso. **Ninguno por sede**, y el
  reporte clínico y el operativo recortan por sede y por rango de cierre.
- `caso_clinico` **no tiene ningún índice no-único**. Su sede es `oferta_consultorio_id`.
- Todo lo demás ya está cubierto: `ix_turno_sede_dia`, `ix_obligacion_sede_estado`,
  `ix_cobro_sede_fecha`, `ix_movimiento_caja_sede_fecha`, `ix_jornada_caja_sede_fecha`,
  `ix_egreso_sede_estado`, `ix_presentacion_bandeja`, `ix_financiador_pago_cuenta_corriente`.

**Los dos empiezan por `organization_id`**, como exige ADR-0004: un índice de reporte que no
empiece por el tenant es una invitación a un plan de ejecución que escanea el SaaS entero.

V59 **no crea ninguna tabla**. Es la prueba en el esquema de que esta etapa no materializó nada.

## 10. Ruta crítica — qué tiene cimientos y qué no

`Tenant → Consultorio → Membership/Permisos → Espacios/Disponibilidad → Persona/Paciente →
Cobertura/Convenio → HC → Caso → Plan → Turno → Check-in → Sesión → Obligación → Cobro → Caja →
Presentación/Reportes`

**Reportes es el último eslabón y esta vez no se está adelantando ninguno.** Todos los módulos
fuente existen, tienen esquema aplicado en migraciones y tienen API: `scheduling` (05.01–05.03),
`encounter` (06.01–06.05), `clinical` (04.01–04.04), `billing` (07.01–07.05).

Lo que **sí** está incompleto aguas arriba, y por eso el reporte lo declara en vez de taparlo:

| Hueco | Efecto en esta etapa |
|---|---|
| No hay obligación con `responsable = FINANCIADOR` | RF-M23-005 devuelve ceros con advertencia |
| No hay modelo de costeo por servicio | RF-M23-009 no se construye |
| No existe `activity` | RF-M23-007, 008 y 010 no se construyen |
| El catálogo de restricciones "Limitado" en reportes es un hueco declarado de F8 | Las restricciones por rol (`PROFESIONAL` ve sólo su actividad) **no se implementan**: hoy el recorte es por permiso de sección, no por alcance `OWN`. Ver el challenge, pregunta 8 |

## 11. Tests

**Uno solo, por decisión explícita del usuario para esta etapa.**
`ReporteServiceTest` — cuatro o cinco casos sobre lo que rompe el negocio si falla:

1. Sin `reporte:read` → `AccessDeniedException`, y **ningún contribuyente se invoca**.
2. Una sección sin su permiso **se omite y se declara**; las demás se calculan igual.
3. Cross-tenant sobre la sede → la excepción que el advice mapea a **404**.
4. Rango inválido (invertido o mayor a la ventana) → rechazo, **sin invocar contribuyentes**.
5. Un reporte que incluye sección clínica **escribe el evento de auditoría**.

Lo que sólo se puede probar contra MySQL real —que las consultas de agregación den los números,
que V59 ejecute, que la reconciliación cierre— va a `docs/tests-diferidos.md`, escenarios **49–53**.

## 12. Lo que esta etapa NO hace

- No materializa ningún agregado. No hay tabla de reporting.
- No publica ningún total que sume conceptos económicos distintos.
- No construye reportes de segunda entrega.
- No implementa el export asíncrono.
- No amplía la matriz de permisos.
- No implementa el alcance `OWN` para reportes de profesional: no existe en ninguna parte del
  repositorio y resolverlo es una etapa propia.
- **No arregla el defecto de `tipo_origen` que se encontró al leer V56/V57.** Está fuera de su
  alcance y se reporta. Ver el challenge, pregunta 8.
