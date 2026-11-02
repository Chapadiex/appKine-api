# AKINE — Flujo único de turnos

**30/10/2026** · ramas `akine-turnos-api` y `akine-turnos-web` · migraciones `V125` a `V128` ·
contrato `0.150.0` · especificación: `TURNO.md` y `TURNO - Tecnico.md` (carpeta raíz del workspace,
fuera de los repos) · módulo M12.

Todos los puntos de entrada terminan en el mismo camino:

```
punto de entrada → contexto → disponibilidad → validaciones → confirmación → turno
```

Lo único que cambia entre uno y otro es lo que ya se sabe: la agenda sabe fecha, hora y profesional;
un plan sabe paciente, caso, plan y prestación. Lo que se sabe no se vuelve a preguntar, y las reglas
son siempre las del backend.

## 1. Cómo se traduce la especificación

| TURNO | AKINE | Nota |
|---|---|---|
| Prestación | Oferta | `offering` |
| Sucursal | Consultorio (sede) | No hay "todas mis sucursales" |
| Box | Espacio | Los elegibles son las habilitaciones de la oferta |
| Equipamiento | Habilitación de espacio por oferta | No hay entidad de equipo |
| Profesional | Membership con rol `PROFESIONAL` | |
| Paciente | Persona con perfil de paciente | Regla de 03.01: son dos cosas |
| Bloqueo | `disponibilidad_excepcion` de tipo `CIERRE` | Motivo nuevo `CAPACITACION` (`V127`) |
| Duración | La de la oferta, o la propia del profesional | No se elige turno por turno. Ver §3 bis |

## 2. Modelo

**`turno`** (`V125`) gana `caso_id`, `plan_id`, `origen`, `sobreturno`, `sobreturno_motivo`,
`sobreturno_autorizado_por_cuenta_id`, `origen_cancelacion` y `ausencia_consume_sesion`.

- `caso_id` y `plan_id` son referencias sin FK cruzada de módulo: `scheduling` lee `clinical` sólo por
  `clinical.spi.ContextoClinicoDeAgenda`, que devuelve **números y conteos, nunca contenido clínico**.
- `origen` es trazabilidad. **No cambia ninguna regla.**
- Reprogramar sigue moviendo el mismo turno, y limpia la marca de sobreturno: en el horario nuevo no
  hay nada que autorizar.

**`lista_espera_turno`** (`V126`): quién espera, qué prestación, qué le sirve (fechas, horas, días,
profesional preferido), prioridad, estado `ACTIVA` / `ASIGNADA` / `DESCARTADA`. Sin borrado físico.

**El estado operativo no se guarda.** `RESERVADO`, `CONFIRMADO`, `PRESENTE`, `RETIRADO`, `EN_ATENCION`,
`ATENDIDO`, `AUSENTE` y `CANCELADO` se derivan al leer de tres máquinas independientes —turno, recepción
y sesión—, como manda DP-05 (`RecepcionService.operativoDe`, en lote).

## 3. Disponibilidad

`DisponibilidadDeTurnosService` y `POST /disponibilidad/busqueda` buscan por profesional + sede +
horario + prestación + duración + box + bloqueos + licencias + turnos. Sin `hasta`, recorre hasta 62
días o hasta llenar `limite`; con `limite = 1` es el primer disponible.

Cambió la cuenta de ocupación (`AgendaService`, `RevalidadorDeSlot`):

- **El cupo es por profesional**, no por oferta + inicio. Antes, el primer turno de las 09:00 le sacaba
  el horario a otro profesional.
- **Un profesional ocupado por otra oferta o por una clase da cupo 0.** Antes se ofrecía el horario y la
  reserva lo rechazaba.
- **Sin box libre, cupo 0**; si hay, se sugiere uno y se puede cambiar.
- **Un box se comparte sólo dentro del grupo de un profesional.** Dos profesionales a la misma hora son
  dos grupos: al pasar a cupo por profesional, el segundo caía en el box del primero.
- **Una franja se define por el inicio.** Con el fin, un turno de 12:45 a 13:30 no estaba ni a la mañana
  ni a la tarde.

`POST /disponibilidad/validacion` es informativa: no reserva ni toma lock. Responde estado, motivo,
profesionales y boxes libres, avisos (responsable distinto: **aviso, no bloqueo**) y alternativas reales.
**La palabra final es de la reserva**, que revalida bajo el lock de `agenda_sede`.

Conflicto al confirmar: 409 `recurso-ocupado` con `turnoEnConflictoId`, `conflictoInicio` y
`conflictoFin`, y el panel ofrece alternativas en lugar de un error técnico.

## 3 bis. Duración propia del profesional

Kinesiología dura 45 minutos, pero Carla la hace en 30. `oferta_duracion_profesional` (`V128`) guarda
esa excepción; sin fila vale la de la oferta. Se lee en `duracionesPropias` de `GET habilitaciones` y se
cambia con `PUT habilitaciones/duraciones`, con `consultorio:manage` y la versión de la oferta.

- **Tabla propia y no columna de la habilitación:** una fila de habilitación restringe la oferta, porque
  lista vacía significa todos. Cargarle la duración a Carla en una oferta sin restringir dejaría sin
  agenda a los demás.
- **Manda sobre la de la oferta.** `TURNO - Tecnico §47` sugiere prestación antes que configuración del
  profesional; como toda oferta tiene duración, en ese orden la del profesional no se usaría nunca. No
  hay duración por plan ni modificación manual turno por turno.
- **El fin se calcula con quien atiende**, en un solo lugar: `OfertaSnapshot.duracionCon`. Un mapa por
  oferta (`OfertaDirectory.duracionesPropias`) alcanza para cortar la ventana entera.
- **"Cualquier profesional" no produce turnos con la duración equivocada.** La reserva no asigna a nadie:
  sin profesional, una oferta que lo requiere se rechaza. La agenda corta por profesional, así que cada
  horario trae el suyo. La validación evalúa cada candidato con su fin; con varios libres, `fin` es el de
  la oferta y `profesionales[].duracionPropiaMinutos` dice el de cada uno. Reprogramar sin indicar
  profesional resuelve primero que sigue el mismo, y recién ahí calcula.
- Los turnos ya dados conservan su fin: `turno.fin` se copia al reservar.
- **Un grupo no se reparte.** Si la duración cambió con un grupo armado, quien se suma puede terminar después
  que el resto. Si a esa hora el box es de otro, la agenda no lo ofrece y la reserva responde `recurso-ocupado`.
- **Lo que no cambia no se vuelve a juzgar:** el `PUT` valida la sede del vínculo sólo en lo nuevo o cambiado.
  Si no, alguien que se pasó a otra sede trabaría las duraciones de todos.
- La grilla de la agenda no tiene un número propio que configurar: ofrece horarios cada lo que dura la
  prestación filtrada, o la más corta si no hay filtro, y con filtro cada profesional va a su ritmo. Nunca
  baja de 20 minutos, que a esa escala son los 24 px de un objetivo táctil: los horarios exactos de una
  prestación más corta siguen en la búsqueda de disponibilidad.

## 4. Plan de tratamiento

Pendientes = planificadas − realizadas − programadas − ausencias que consumen (`SesionesDelPlan`). El
tope se controla **bajo el lock de la agenda**, en reserva, serie y `POST turnos/{t}/contexto`, y lo
realizado se relee ahí: dos pedidos simultáneos no pueden pasarse. 409 `plan-sin-sesiones`.

**Realizadas cuenta desde que el plan se activó.** Un caso tiene planes sucesivos: contando por caso, el
Plan 2 de una misma prestación nacía agotado. Vale también para el avance de la pantalla del plan.

Decisión: **no hay override del tope.** Quien necesita más sesiones modifica el plan.

La ausencia consume sesión si lo dice quien la registra (`consumeSesionDelPlan`). No hay regla
institucional configurable.

## 5. Sobreturno

Permiso nuevo `turno:sobreturno` (`ORG_ADMIN` y `CONSULTORIO_ADMIN`), motivo obligatorio, auditoría
`TURNO_SOBRETURNO`. Ver `docs/seguridad/matriz-permisos-minima.md` §20. Sólo salva la ocupación: no salva
un horario fuera de la disponibilidad, un bloqueo ni el tope del plan.

## 6. Bloqueos

`ExcepcionService` rechaza un `CIERRE` con turnos afectados salvo `confirmaTurnosAfectados`: 409
`bloqueo-con-turnos-afectados` con la lista. Los turnos **no se cancelan**: siguen reservados, fuera de
disponibilidad, hasta que alguien los mueva.

Defecto que destapó: el simulador de impacto miraba 90 días **desde hoy** y dejaba pasar en silencio un
bloqueo más lejano. Ahora el horizonte cuenta desde el inicio del cambio.

## 7. Endpoints nuevos

Bajo `/api/v1/consultorios/{consultorioId}`:

| Operación | Para qué |
|---|---|
| `POST disponibilidad/busqueda` | Próximos horarios y primer disponible |
| `POST disponibilidad/validacion` | Validar un horario ya elegido, con alternativas |
| `GET disponibilidad/profesionales` | Quién presta la oferta en la sede |
| `GET personas/{p}/contexto-de-agenda` | Casos y planes agendables con sus conteos |
| `GET agenda/operativa` | Día o semana completos, hasta 14 días |
| `GET turnos/personas/{p}` | Turnos de una persona. Con `soloFuturos`, **el más cercano primero** |
| `POST turnos/{t}/contexto` | Asociar caso o plan a un turno ya dado |
| `POST series-de-turnos/previsualizacion` | Revisar una serie antes de crearla |
| `POST` y `GET lista-de-espera`, `GET lista-de-espera/coincidencias` | Lista de espera |
| `POST lista-de-espera/{id}/asignacion` y `/descarte` | Resolver una entrada |
| `GET sesiones/{id}/proxima-sesion` | Contexto para programar la próxima |

Además: `EventoDeTurno.actorNombre` (el historial decía "Cuenta #12") y, en reserva y serie, `espacioId`,
`casoId`, `planId`, `origen` y `sobreturnoMotivo`.

La lista de espera usa `turno:read` y `turno:manage`: no se creó un permiso propio.

## 8. Frontend

`core/services/flujo-de-turnos.store.ts` publica qué mostrar, y `app.ts` monta el flujo lazy: **125 kB que
no entran al bundle inicial**. Las features no se importan entre sí (ADR-0004): `person`, `clinical` y
`resource` arman un `ContextoDeTurno`, que son sólo ids.

`/agenda` es la grilla operativa: día o semana, por profesional o por box, con la fecha, la vista y los
filtros en la URL. Después de crear, mover o cancelar relee en el lugar, sin tocar fecha, filtros ni
scroll. Debajo de 768 px es una lista cronológica.

Se borraron las pantallas `reserva-de-turno`, `serie-de-turnos-alta` y `calendario-de-turnos`; sus rutas
redirigen.

## 9. Defectos viejos corregidos

- Se podía confirmar un turno cancelado.
- Un turno chocaba contra su propio box al reprogramarse.
- La atención preguntaba el caso aunque el turno ya lo tuviera, o ya tuviera sesión.

## 10. Lo que no se hizo

- **Autogestión y portal del paciente** (TURNO §25-26, Fase 4).
- **"Todas mis sucursales"**: la búsqueda es por sede.
- **Override del tope del plan** y **regla institucional de ausencias**: decisiones de §4.
- **Quitar un bloqueo en la misma agenda**: la tarjeta lleva a Excepciones, que pide el motivo y muestra el impacto.

## 11. Conocido y sin corregir

- Un profesional con una clase en **otra sede** aparece libre en la búsqueda: la validación y la reserva lo
  rechazan y ofrecen alternativas. `EventoExternoDeAgenda.enVentana` mira una sede.
- Al reprogramar, los horarios que se cruzan con el propio turno no se ofrecen aunque se aceptarían.

- Una oferta con `requiereProfesional = false` nunca da horarios.
- `DisponibilidadImpactoSobreTurnos` puede dar NPE con un turno sin profesional
  (`findPendientesEnLaSede`), un caso que hoy no se alcanza por la limitación anterior.
- Dos inicios simultáneos de la misma sesión: el perdedor recibe 409 `conflict`. El unique impide el
  duplicado; el mensaje podría ser mejor.

- Guardar profesionales y duraciones son dos `PUT`, no uno atómico. Si falla el segundo, la pantalla dice cuál
  quedó guardado y conserva lo escrito para reintentar.
- Con "cualquier profesional" y varios libres, el box a la vista es el del primero. Si quien se elige tarda más
  y ese box no le sirve, la reserva da 409 con alternativas: se ve tarde, pero no queda nada mal guardado.
- Una oferta grupal con la duración cambiada deja fines distintos dentro del mismo grupo, igual que editar la
  duración de la oferta.

## 12. Verificación

`FlujoUnicoDeTurnosIT` contra MySQL real. `appKine-web/e2e/flujo-unico-de-turnos.spec.ts` recorre en
navegador, contra el backend real, cada escenario de la especificación y lee el resultado de la API. Ausente
usa una prestación de un minuto y espera a que el turno empiece: **nada se maquilla en la base**.
