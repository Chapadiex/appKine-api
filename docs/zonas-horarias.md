# Zonas horarias — "hoy" en el servidor, en la sede y en la organización

> Escrito el **14/10/2026** (rama `akine-hoy-en-la-zona-de-la-sede`). Inventario hecho con
> `grep -rnE "LocalDate\.now\(|LocalDateTime\.now\(|LocalTime\.now\(|ZoneId\.systemDefault\("`
> sobre `src/main`, más los `LocalDate.now(ZoneOffset.UTC)` y `ofInstant(…, ZoneOffset.UTC)` que
> tienen el mismo efecto. La decisión que quedaba abierta, **U-38**, la tomó el usuario el
> **19/10/2026 (DP-43)**: zona por organización. Implementada en la rama
> `akine-dp43-zona-matriz-gate` (migración `V121`, contrato 0.141.0); la sección (b) es el
> inventario corregido.

## El problema

`LocalDate.now()` sin zona usa la de la JVM, y en la imagen Docker la JVM corre en **UTC**. Entre
las **21 y las 24 de Córdoba** (UTC−3) ya es el día siguiente en UTC, así que:

- una vigencia que vence **ese día** —convenio, arancel, precio particular, oferta, cobertura,
  autorización, orden— se lee **vencida** tres horas antes;
- una que empieza **mañana** se lee **vigente** tres horas antes.

En una máquina de desarrollo en Argentina el mismo código da el resultado correcto, que es por lo
que nadie lo ve hasta producción.

## La regla

1. **Si la operación tiene una sede** —el consultorio en la ruta, en la entidad o en el contexto
   del actor— "hoy" es el de **la zona IANA de la sede** (`consultorio.timezone`, AKINE-02.01).
   Se calcula con `organization.spi.ZonaDeSede`, que concentra la política: zona de la sede, y
   **UTC con un WARN** si la sede no declara una válida o no resuelve.
2. **Si la operación es de la organización sin sede** —padrón, coberturas de la persona,
   autorizaciones, órdenes, catálogo de planes— "hoy" es el de **la zona IANA de la organización**
   (`organization.timezone`), por **U-38 / DP-43**. Se calcula con `organization.spi.ZonaDeOrganizacion`
   (implementada en `organization.infrastructure.tenant.OrganizationZonaDirectory`): zona de la
   organización, y **`America/Argentina/Cordoba` con un WARN** si la organización no resuelve o su
   zona no es válida. Por defecto es la de su **primera sede** (la de menor id: `V121` hizo el
   backfill), o Córdoba si no tiene sedes. La edita sólo el `ORG_ADMIN`, con
   `PATCH /organizations/{orgId}` (`tenant:update`, U-15), y se valida contra la base IANA igual
   que la de una sede: un offset fijo o una abreviatura es 400. Una organización con sedes en dos
   husos tiene **un** "hoy" para lo que no tiene sede; lo que tiene sede sigue con la regla 1.
3. Nada depende de la zona de la JVM: el reloj por defecto es `Clock.systemUTC()`, y los servicios
   que calculan "hoy" reciben un `Clock` por un constructor de paquete para que un test fije la hora.

## (a) Con sede — corregidos en esta rama

| Sitio | Qué evalúa | De dónde sale la sede |
|---|---|---|
| `contracting.api.ArancelController#efectivo` (vía `ArancelService.hoyEnLaSede`) | arancel efectivo sin `fecha` | ruta `/consultorios/{c}` |
| `contracting.application.ArancelService#listar`, `#resolver` | vigencia de aranceles sin `fecha` | ruta |
| `ArancelService#crear`, `#editar`, `#darDeBaja` (3 sitios) | `vigente` de la vista devuelta | ruta |
| `contracting.application.ConvenioService#listar`, `#ver` | vigencia de convenios sin `fecha` | ruta |
| `ConvenioService#crear`, `#editar`, `#darDeBaja` (3 sitios) | `vigente` de la vista devuelta | ruta |
| `offering.application.OfertaPrecioParticularService#listar`, `#crear`, `#cambiarFin`, `#darDeBaja` (4 sitios) | vigencia del precio particular | ruta |
| `clinical.application.CasoClinicoService#exigirOfertaVigente` (era `now(UTC)`) | oferta vigente al abrir un caso | sede del contexto |
| `clinical.application.PlanTratamientoService#resolverOfertas` (era `now(UTC)`) | ofertas vigentes del plan | sede del contexto |
| `PlanTratamientoService#vincularAutorizacion` | autorización vigente al vincularla | sede del contexto |
| `person.application.CoberturaParaOfertaService#resolver` | cobertura aplicable sin `fecha` | sede del contexto (obligatoria) |
| `person.application.ElegibilidadAdministrativaService#consultar`, `#evaluar` | elegibilidad sin `fecha` | sede del contexto / parámetro |

**21 sitios.** Además se centralizó la política que estaba copiada: `resource.application.ZonaSede`
y `offering.infrastructure.OfferingOfertaDirectory#precioEn` delegan en `ZonaDeSede`.

Ya estaban bien y no se tocaron: `OfertaService#hoyEn` y `OfertaHabilitacionService` (usan
`sede.timezone()`), `CajaAcceso.fechaDeNegocio`/`inicioDelDia` en `billing`, la agenda, la
disponibilidad, el simulador de impacto, el devengo (`ObligacionDevengador`) y el consumo en el
cierre (`ConsumoDeAutorizacionEnCierre`). El devengo y el consumo en el cierre
resuelven la zona con `ZonaDeSede.de(sede)` desde la rama `akine-zona-de-sede-unica`; conservan
sólo su WARN propio, con el `sesionId`, para la sede que no resuelve.

**No se unificaron, y es a propósito:** `OfertaService#hoyEn`, `OfertaHabilitacionService` y
`CajaAcceso` (`fechaDeNegocio`, `inicioDelDia`, `finDelDia`) hacen `ZoneId.of(sede.timezone())` a
secas: ante una zona nula o inválida **fallan** en vez de caer a UTC. Delegarlos en `ZonaDeSede`
cambiaría ese comportamiento —en la caja, un movimiento imputado en silencio al día UTC—, y es una
decisión que queda abierta.

## (b) De la organización, sin sede — corregidos por U-38 (DP-43)

Hasta DP-43 estos sitios usaban `LocalDate.now()` (zona de la JVM, UTC en la imagen). Desde la rama
`akine-dp43-zona-matriz-gate` calculan "hoy" con `ZonaDeOrganizacion`, que cada clase recibe por
constructor. Lista exacta, con la línea de esa rama:

| Sitio | Qué evalúa |
|---|---|
| `contracting/application/PlanCoberturaService.java:113` | vigencia de planes del financiador sin `fecha` (listar) |
| `PlanCoberturaService.java:186`, `:241`, `:274` | `vigente` de la vista al crear, editar y dar de baja |
| `contracting/application/AdopcionDeFinanciadorService.java:181` | `vigente` de los planes de la copia al adoptar un financiador del catálogo global (entró con #168, U-12, después del inventario del 14/10) |
| `person/application/AutorizacionService.java:183`, `:209`, `:254` | vigencia de autorizaciones sin `fecha` (listar, ver, historial) |
| `AutorizacionService.java:353`, `:429`, `:525`, `:563`, `:597` | `vigente` de la vista al crear y en cada mutación |
| `person/application/CoberturaPacienteService.java:160`, `:188` | coberturas vigentes de la persona sin `fecha` |
| `CoberturaPacienteService.java:289`, `:364`, `:417`, `:454` | `vigente` de la vista en cada mutación |
| `person/application/OrdenMedicaService.java:132`, `:188`, `:292` | vigencia y situación de la orden médica |
| `person/application/ConsumoDeAutorizacionService.java:606` | saldo de una autorización sin `fecha` |
| `ConsumoDeAutorizacionService.java:658` | autorizaciones elegibles del paciente sin `fecha` |
| `ConsumoDeAutorizacionService.java:187` | respaldo si el cierre no trae fecha. **Hoy inalcanzable**: el único llamador (`ConsumoDeAutorizacionEnCierre`) siempre manda el día local de la sede |
| `person/infrastructure/PersonAutorizacionDirectory.java:49` | respaldo del `spi` si el llamador no manda fecha. **Hoy inalcanzable**: los dos llamadores (`PlanTratamientoService`, `DerivacionClinicaService`) mandan fecha |
| `person/application/CoberturasEnElResumenDePersona.java:114` | coberturas vigentes en el Paciente 360, con el `Clock` del contribuyente (`hoy(organizationId, clock)`) |
| `resource/infrastructure/ResourceDesvinculacionProbe.java:98` y `:123` | bloques y excepciones vigentes de una membership al desvincularla, y la medianoche de la primera excepción: la membership puede abarcar varias sedes |

**29 sitios** en 9 archivos: los 28 del inventario del 14/10 —25 en `person` y `contracting`, uno en
el 360 y los dos de la sonda de desvinculación— y la adopción de financiadores que agregó #168. Ningún `LocalDate.now()` sin zona queda en `src/main`.

**Lo que no se tocó, a propósito:**

- El instante `ocurrioEn` de los hitos de cobertura del 360
  (`CoberturasEnElResumenDePersona`, `atStartOfDay(ZoneOffset.UTC)`): es una **fecha** viajando en
  el único campo temporal del hito, documentada como medianoche UTC en el javadoc y en el contrato.
  Moverla a la zona de la organización cambiaría el instante publicado sin cambiar el día.
- Los jobs de F9 (`VencimientoDePases`, `AvisosDePases`, `RenovacionDeAbonos`) usan el día UTC
  sólo como **prefiltro** con un día de margen, y deciden después con la zona de cada sede.

**Las otras dos opciones que U-38 tenía** —zona de la sede del contexto cuando la hay, o que el
cliente mande siempre `fecha`— quedaron descartadas por DP-43.

## (c) Instantes y relojes

| Sitio | Cambio |
|---|---|
| `CoberturasEnElResumenDePersona` | `Clock.systemDefaultZone()` → `Clock.systemUTC()`. Inocuo en la imagen (ya era UTC) y deja de depender de la zona de la máquina |

No hay `LocalDateTime.now()`, `LocalTime.now()` ni `ZoneId.systemDefault()` en `src/main`: los
timestamps ya son `Instant`. **1 sitio.**

## Cómo se prueba

Los tests fijan el reloj en **`2026-03-15T01:30:00Z`** (o `2026-07-01T01:30:00Z`), que son las
**22:30 del día anterior en Córdoba**: `ZonaDeSedeTest`, `CasoClinicoServiceTest`
(`oferta_que_vence_hoy_en_la_sede`) y `ConvenioServiceTest` (`sin_fecha_hoy_es_el_de_la_sede`).

Para la organización (DP-43): `OrganizationZonaDirectoryTest` (mismo reloj, Córdoba contra Tokio y
el default ante una zona inválida), `CoberturaPacienteServiceTest`
(`sin_fecha_hoy_es_el_de_la_organizacion`), `ResourceDesvinculacionProbeTest`
(`el_dia_es_el_de_la_organizacion`) y, por HTTP, `MatrizDePermisosIT` (la zona se edita sólo como
`ORG_ADMIN` y un offset fijo es 400).
