# F3 — Personas y cobertura

> Auditoría del 01/10/2026 · backend `37ff201` · frontend `4c6ae8b` · **~79 %**
> Etapas del plan: 03.01–03.06 (§13, líneas ~1632–2107).

## Estado por etapa

| Etapa | Estado | % | Lo que falta, en una línea |
|---|---|---|---|
| 03.01 Persona, perfil paciente, dedup | PARCIAL | 85 | Búsqueda por número de afiliado; alcance `OWN` |
| 03.02 Paciente 360 y adjuntos | PARCIAL | 80 | Cobertura como sección del 360; IT de subida concurrente |
| 03.03 Financiadores y planes | PARCIAL | 85 | Catálogo global de plataforma; `convenio:read` |
| 03.04 Coberturas del paciente | PARCIAL | 80 | ~~RF-M08-006/007~~ (B-3); la cobertura no viaja a turno/sesión/obligación |
| 03.05 Convenios y aranceles | PARCIAL | 70 | `congelar` del arancel sin consumidor; importación masiva (diseñada en B-3); ~~arancel por oferta~~ (B-3) |
| 03.06 Órdenes y autorizaciones | PARCIAL | 75 | Historial de estados de la autorización; estado de la orden |

Todas tienen backend y pantallas (los registros de cierre de 03.02–03.06 dicen "sin frontend";
**eso ya no es cierto**, el frontend llegó después).

## El hallazgo central: F3 está aislada

El motor contractual funciona por sí solo y **no está conectado con nada de lo que vino después**:

- `ArancelDirectory#congelar` no tiene consumidor: el devengado de F7 no copia el arancel.
- La cobertura elegida no se copia en el turno, ni en la sesión, ni en la obligación.
- No existe obligación con responsable `FINANCIADOR` (ver [F7](F7-economia-del-mvp.md)).

El recableado se hace **en F7**, pero sus piezas de este lado se preparan acá: el `spi` de
`person`/`contracting` tiene que poder responder "¿cuál es la cobertura vigente de esta persona
para esta práctica en esta fecha?".

## Faltantes

### Backend / API
- [x] ~~Búsqueda por número de afiliado (RF-M07-001). `PersonaBusqueda` solo tiene `texto/estado/perfil`.
  Se difirió a 03.04 y nunca se cableó.~~ → B-1: `q` también busca por `numero_afiliado` (contrato 0.46.0).
- [x] ~~RF-M08-006/007: resolver la cobertura aplicable por oferta.~~ → B-3 (backend): `GET /api/v1/personas/{personaId}/cobertura-aplicable?ofertaId=` con condición sugerida y precio particular del día (contrato 0.65.0). La selección particular la hace la recepción (E-4); que llegue a la obligación queda abierto (`docs/diseno/AKINE-B-3-cobertura-por-oferta.md` §8). Falta la pantalla.
- [x] ~~RF-M16-008/009 (oferta asociada al convenio, arancel particular por oferta).~~ → B-3
  (backend, `V80`, contrato 0.65.0): `convenio_arancel.oferta_id` (el arancel de la oferta manda
  sobre el general al resolver con oferta, y F-4 lo congela) y `oferta_precio_particular` (precio
  por vigencia que manda sobre `precio_base`). Falta la pantalla.
- [ ] RF-M16-007 (importación masiva con preview). **Diseñada y fuera de B-3**:
  `docs/diseno/AKINE-B-3-cobertura-por-oferta.md` §7.
- [x] ~~Validar moneda contra ISO 4217.~~ → `contracting.domain.MonedaIso4217` (`java.util.Currency`), en convenio y plan.
- [ ] Historial de estados de la autorización: hoy solo queda el evento `AUTORIZACION_RESUELTA`
  en auditoría. **Decidir** tabla propia o auditoría, y escribirlo.
- [ ] Estado de la orden médica (hoy solo vigencia).
- [ ] Obligatoriedad de requisitos configurable por financiador o por prestación (hoy solo por convenio).
- [x] ~~Contribuyente de cobertura al Paciente 360.~~ → B-5 (backend):
  `person.application.CoberturasEnElResumenDePersona`, sección `coberturas`, por pertenencia
  (sin permiso propio, igual que `GET .../coberturas`), solo vigentes, afiliado enmascarado. Sin
  cambio de contrato: viaja en la estructura genérica de indicadores e hitos. Falta la parte web.
- [ ] Catálogo global de financiadores para `PLATFORM_ADMIN` (depende del bootstrap de F1).
  **Diseñado en A-7 y no implementado: decisión pendiente DU-13** —el RF no dice si el centro
  *referencia* la fila global (`owner_key`, el camino de la cabecera de `V41`, que reabre todo
  `contracting`) o la *adopta* como copia (tabla global aparte, recomendada)—. Opciones, costos y
  alcance mínimo en [AKINE-A-7-plataforma.md](../diseno/AKINE-A-7-plataforma.md) §3.

### Seguridad
- [ ] Alcance `OWN` / `paciente:read`: hoy una membership con rol `PACIENTE` lee el padrón entero
  de su organización. **Requiere decisión** (vínculo cuenta↔persona).

### Frontend
- [ ] Clonación controlada de convenio y previsualización de impacto.

### Tests
- [ ] **No hay un solo E2E de F3.** Mínimo: persona → activar paciente → cobertura → orden →
  autorización aprobada → vencimiento.
- [ ] `AdjuntoConcurrenteIT`; probar la subida multipart de verdad.
- [ ] QA manual del §6: solo 03.01 hizo un QA parcial (4 de 5 escenarios).

## Desvíos

| Desvío | Documentado |
|---|---|
| Historial de autorización reemplazado por auditoría | **No — silencioso** |
| Afiliado diferido a 03.04 y nunca cableado | Sí, javadoc de `PersonaController` y registro |
| Sin URLs firmadas: cada descarga se autoriza en la aplicación | Sí |
| Paciente fusionado no implementado; la baja no valida turnos ni deuda | Sí |
| Financiador como dato de la organización, sin alcance global | Sí, registro y cabecera de `V41` |
| Única principal y no-solapamiento por lock, no por constraint | Sí, intencional |
| Sin columna `prioridad` en convenio; ~~arancel por práctica y no por oferta~~ (B-3: `convenio_arancel.oferta_id`, `V80`) | Sí, cabecera de `V43` y `V80` |
| Faltan diseño y challenge de 03.03, 03.05 y 03.06 en `docs/diseno/` | — |

## Para cerrar la fase

- [ ] E2E de la vertical persona → cobertura → autorización contra backend real.
- [x] ~~Búsqueda por afiliado~~ (B-1) y ~~RF-M08-006/007~~ (B-3, backend).
- [ ] Decisión sobre el historial de autorización.
- [x] ~~`spi` de cobertura vigente listo para que F5 (recepción) y F7 (devengado) lo consuman.~~ → B-2: `person.spi.CoberturasAplicablesDirectory`.
- [ ] Corregir los registros de cierre (frontend entregado) y escribir los diseños que faltan.
