# ADR-0024 — `encounter` es el módulo dueño de la Sesión (no `clinical`)

- **Estado:** Aceptado
- **Fecha:** 2026-10-03
- **Etapa:** AKINE-06.01 (decisión tomada ahí; ratificada acá)

## Contexto

La sección 2.4 del plan de implementación asigna al módulo `clinical` los módulos funcionales
M09–M14: «HC, timeline, Caso, Plan, Sesión y evolución». Al implementar AKINE-06.01 (`V33`,
agosto de 2026) la Sesión se creó en un módulo nuevo, `encounter`. Ese desvío quedó documentado
**sólo** en el registro de cierre de 06.01; no había ADR, y `AGENT.md` §4 siguió sin listar
`encounter`. Quien lee §2.4 o `AGENT.md` concluye que la Sesión vive en `clinical`, y el código
dice otra cosa.

Este ADR no cambia código: ratifica lo que ya existe y deja escrito el porqué, para que nadie
«corrija» la estructura hacia lo que dice §2.4.

Lo que el código ya hace (verificado al 2026-10-03):

- `com.akine.encounter` es dueño de las tablas `sesion` (`V33`), `sesion_version` (enmiendas,
  `V53`), `sesion_medicion` (`V52`) y `tratamiento_realizado` / `tratamiento_parametro` (`V55`),
  y de las columnas de `sesion` agregadas por `V48`. Los controllers `SesionController`,
  `SesionMedicionController` y `TratamientoController` están en `encounter.api`.
- `clinical` no importa nada de `encounter`. La única dirección de dependencia entre ambos es
  `encounter → clinical.spi`: `SesionService` usa `CasoDirectory` y `HistoriaClinicaDirectory`;
  `EncounterRealizadoEnElCasoProbe` implementa `clinical.spi.RealizadoEnElCasoProbe`;
  `SesionEventoContributor` implementa `clinical.spi.EventoClinicoContributor`.
- `encounter` **pide** a `clinical` el número de caso y no lo calcula; `clinical` no escribe
  columnas de `sesion` y `encounter` no lee tablas de caso (registro de 04.03/06.01).
- Quien reacciona al cierre de una sesión lo hace por `encounter.spi.CierreDeSesionObserver` /
  `SesionCerrada`. `billing.infrastructure.ObligacionDevengador` lo implementa: la dirección es
  `billing → encounter`, y `encounter` no conoce a ningún consumidor.
- La arista `person → encounter` **está invertida a propósito**. Una dependencia
  `person → encounter.spi` cerraba el ciclo `clinical → person → encounter → clinical`
  (ArchUnit lo detectó con una clase sonda en 04.05). Se resolvió declarando
  `person.spi.ConsumoDeAutorizaciones` e implementando el observador en `encounter.infrastructure`
  (`ConsumoDeAutorizacionEnCierre`): `encounter → person.spi`.
- `scheduling.spi.AtencionProbe` se declara en `scheduling` y la implementa
  `encounter.infrastructure.EncounterAtencionProbe`, porque la dependencia entre esos módulos ya
  iba en ese sentido.
- La relación asistencial que `clinical` necesita para decidir si exige justificación de acceso
  se consulta por `clinical.spi.RelacionAsistencialProbe`. Hoy la implementa un stub
  (`clinical.infrastructure.RelacionAsistencialSinAgenda`, devuelve `false`); su reemplazo por una
  implementación real sobre turnos y sesiones es el paquete C-3 y **no** es parte de este ADR.

## Decisión

**La Sesión (la atención clínica real: su ciclo de vida, su contenido, sus enmiendas, sus
mediciones y los tratamientos realizados) pertenece al módulo `encounter`.** `clinical` conserva
Historia Clínica, timeline, antecedentes, Caso, Plan de tratamiento, derivaciones y adjuntos
clínicos (M09–M13 y la parte de M11/M14 que no es la Sesión).

- Las tablas de la Sesión tienen a `encounter` como único propietario. Ningún otro módulo las
  lee ni las escribe (regla 1 de §4 de `AGENT.md`).
- La comunicación `clinical ↔ encounter` va sólo por `spi`, y **sólo en el sentido
  `encounter → clinical.spi`**. Ofrecer a `clinical` datos de la Sesión se hace con un puerto
  declarado en `clinical.spi` e implementado en `encounter.infrastructure`, no importando
  `encounter`.
- Toda reacción ajena al cierre de una sesión (facturación, consumo de autorizaciones, reportes)
  se enchufa por `encounter.spi`; `encounter` no sabe quién lo consume.
- La lista de módulos de `AGENT.md` §4 pasa a incluir `encounter`. La fila `clinical` de §2.4 del
  plan queda como estaba (histórica) y este ADR la corrige.

## Alternativas consideradas

1. **Mantener la Sesión en `clinical` como dice §2.4.** Descartada. `clinical` ya concentra
   historia, caso, plan, timeline y adjuntos; sumarle la Sesión (con numeración, versionado de
   enmiendas, mediciones, tratamientos y la integración con facturación, autorizaciones y
   reportes) lo haría el módulo dominante, y todo consumidor del cierre dependería de él. Además,
   la Sesión tiene un ciclo propio (DP-05, ADR-0012) distinto del de la historia.
2. **Mover ahora el código a `clinical` para cumplir §2.4.** Descartada: es un cambio grande sin
   beneficio y destruye los puertos ya probados (`CierreDeSesionObserver`, `AtencionProbe`).
3. **Dejar el desvío sin ADR, sólo en el registro de 06.01.** Descartada: es el estado actual y
   es justamente el problema (el desvío es silencioso y contradice `AGENT.md`).
4. **Dividir `encounter` en `session` y `treatment`.** Descartada por ahora: los tratamientos
   realizados cuelgan de la Sesión y comparten transacción y permisos. Se reconsidera si crece.

## Consecuencias

### Positivas
- La estructura real del código, §4 de `AGENT.md` y este registro dicen lo mismo.
- `clinical` queda sin dependencias hacia la atención; el grafo conserva su dirección
  (`billing → encounter → clinical`, `encounter → person`, `encounter → scheduling`).
- El cierre de sesión es un punto de extensión explícito (`encounter.spi`).

### Negativas
- §2.4 y los textos de etapas anteriores seguirán diciendo «Sesión en `clinical`»; hay que leerlos
  con este ADR al lado.
- Las reglas que necesitan datos de la Sesión desde `clinical` (p. ej. relación asistencial)
  exigen un puerto en `clinical.spi` con implementación en `encounter.infrastructure`, más
  indirecto que una llamada directa.
- La arista invertida `person ← encounter` (observador en `encounter.infrastructure`) es menos
  evidente que un listener en `person`.

### Qué obliga a hacer
- Todo código nuevo de la Sesión, sus mediciones y tratamientos va en `encounter`.
- Nadie importa `encounter.domain`, `.application`, `.api` ni `.infrastructure` desde otro
  módulo; `ModuleArchitectureTest` lo verifica.
- Una dependencia nueva `clinical → encounter` o `person → encounter` está prohibida (cierra
  ciclos). Se verifica con ArchUnit, no de memoria.
- `AGENT.md` §4 lista `encounter` entre los módulos.
- Lo que no se tocó: `CLAUDE.md` del repo no lista módulos, así que no requiere cambio.
