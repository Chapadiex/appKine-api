# Zonas horarias — "hoy" en el servidor, en la sede y en la organización

> Escrito el **14/10/2026** (rama `akine-hoy-en-la-zona-de-la-sede`). Inventario hecho con
> `grep -rnE "LocalDate\.now\(|LocalDateTime\.now\(|LocalTime\.now\(|ZoneId\.systemDefault\("`
> sobre `src/main`, más los `LocalDate.now(ZoneOffset.UTC)` y `ofInstant(…, ZoneOffset.UTC)` que
> tienen el mismo efecto. La decisión abierta es **U-38** de
> [`fases/decisiones-pendientes.md`](fases/decisiones-pendientes.md).

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
   autorizaciones, órdenes, catálogo de planes— **no hay zona definida**: una organización con
   sedes en dos husos no tiene un "hoy" único. Eso es la decisión **U-38**, y esos sitios quedan
   como están (UTC en la imagen) hasta que se decida.
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

## (b) De la organización, sin sede — pendientes de U-38

Siguen con `LocalDate.now()` (zona de la JVM, UTC en la imagen). Lista exacta, con la línea de
la rama:

| Sitio | Qué evalúa |
|---|---|
| `contracting/application/PlanCoberturaService.java:107` | vigencia de planes del financiador sin `fecha` (listar) |
| `PlanCoberturaService.java:180`, `:235`, `:268` | `vigente` de la vista al crear, editar y dar de baja |
| `person/application/AutorizacionService.java:175`, `:201`, `:246` | vigencia de autorizaciones sin `fecha` (listar, ver, historial) |
| `AutorizacionService.java:345`, `:421`, `:517`, `:555`, `:589` | `vigente` de la vista al crear y en cada mutación |
| `person/application/CoberturaPacienteService.java:152`, `:180` | coberturas vigentes de la persona sin `fecha` |
| `CoberturaPacienteService.java:281`, `:355`, `:408`, `:445` | `vigente` de la vista en cada mutación |
| `person/application/OrdenMedicaService.java:124`, `:180`, `:284` | vigencia y situación de la orden médica |
| `person/application/ConsumoDeAutorizacionService.java:598` | saldo de una autorización sin `fecha` |
| `ConsumoDeAutorizacionService.java:650` | autorizaciones elegibles del paciente sin `fecha` |
| `ConsumoDeAutorizacionService.java:179` | respaldo si el cierre no trae fecha. **Hoy inalcanzable**: el único llamador (`ConsumoDeAutorizacionEnCierre`) siempre manda el día local de la sede |
| `person/infrastructure/PersonAutorizacionDirectory.java:44` | respaldo del `spi` si el llamador no manda fecha. **Hoy inalcanzable**: los dos llamadores (`PlanTratamientoService`, `DerivacionClinicaService`) mandan fecha |
| `person/application/CoberturasEnElResumenDePersona.java:108` | coberturas vigentes en el Paciente 360. Usa `Clock`; en esta rama pasó de `systemDefaultZone()` a `systemUTC()` (ver (c)) |
| `resource/infrastructure/ResourceDesvinculacionProbe.java:93` y `:117` | bloques y excepciones vigentes de una membership al desvincularla: la membership puede abarcar varias sedes |

**28 sitios** en 8 archivos: 25 en `person` y `contracting`, uno en el 360 y los dos de la sonda de
desvinculación (uno calcula el día y el otro lo proyecta a instante).

Las opciones que U-38 tiene que elegir, sin recomendación propia de este documento:

- **zona por organización** (columna nueva en `organization`, migración), o
- **zona de la sede del contexto cuando la hay**, y UTC (o la de la organización) cuando no, o
- **que el cliente mande siempre `fecha`** en las lecturas, y "hoy" del servidor sólo en las
  mutaciones.

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
