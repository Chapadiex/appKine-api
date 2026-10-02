# Orden recomendado para retomar el plan

> Foto del **01/10/2026** (backend `37ff201`, frontend `4c6ae8b`). Cumplimiento estimado:
> **~54 % del plan completo** y **~65 % del MVP (F0–F8)**.

## El criterio

Las fases del plan son secuenciales porque cada una es cimiento de la siguiente. El problema
actual no es que falten fases enteras al final: es que **cada fase dejó deudas que la siguiente
asumió cableadas**. Las bajas de F2 no consultan los turnos de F5, el arancel de F3 no llega a la
obligación de F7, la HC de F4 no tiene puerta de entrada. Avanzar sobre eso es apilar.

Por eso el orden es **cerrar las fases de arriba hacia abajo, cada una con su backend, su
frontend y sus E2E**, antes de abrir la siguiente. Dentro de una fase, backend y frontend se
pueden paralelizar; entre fases, no.

**Una fase se da por cerrada** cuando:
- cada etapa cumple sus criterios de aceptación del plan, o el desvío está escrito como decisión;
- tiene pantallas y al menos un E2E **contra el backend real** (no `route.fulfill`);
- sus ITs corren en `./mvnw verify`;
- su registro de cierre en el plan tiene los diez puntos del §10.5;
- su ficha en esta carpeta quedó actualizada.

## Secuencia

| Paso | Qué | Tamaño | Ficha |
|---|---|---|---|
| **0** | Handoff: cerrar el PR grande, integrar la rama de tests en curso, estacionar 08.06 | chico | ver abajo |
| **1** | Arreglos rápidos con defecto confirmado | chico (1 día) | ver abajo |
| **2** | F0–F1: cierre mínimo (ADR de `encounter`, bootstrap de `PLATFORM_ADMIN`, retry de notificaciones) | chico | [F0-F1](F0-F1-fundacion-y-plataforma.md) |
| **3** | F2: conectar las cuatro sondas de impacto a turnos reales; puente Oferta↔Práctica | medio | [F2](F2-operacion-del-consultorio.md) |
| **4** | F3: E2E de la vertical persona→cobertura→autorización; búsqueda por afiliado | medio | [F3](F3-personas-y-cobertura.md) |
| **5** | F4: API de la HC, relación asistencial real, **todo el frontend clínico** | grande | [F4](F4-dominio-clinico.md) |
| **6** | F5: elegibilidad en recepción, series de turnos, E2E reales de agenda | grande | [F5](F5-agenda-y-recepcion.md) |
| **7** | F6: caso obligatorio, atención sin turno, enmiendas completas, pantallas de examen/tratamientos | grande | [F6](F6-atencion-clinica.md) |
| **8** | F7: **obligación del financiador**, snapshot de convenio, anticipos, pantallas económicas | grande | [F7](F7-economia-del-mvp.md) |
| **9** | F8: hallazgos altos de seguridad, observabilidad, carga, release del MVP | grande | [F8](F8-cierre-del-mvp.md) |
| **10** | Gate: decisión formal sobre F9 (DP-07) | decisión | [F9](F9-segunda-entrega.md) |
| **11** | F9 en orden 08.04 → 08.09 | grande | [F9](F9-segunda-entrega.md) |
| **12** | F10 | grande | [F10](F10-consolidacion-final.md) |

### Por qué este orden y no otro

- **El paso 1 va antes que todo** porque son defectos que rompen funcionalidad hoy y se arreglan en
  horas.
- **F2 antes que F3** aunque F2 esté más completa: las sondas de impacto (dar de baja una sede,
  un espacio o un profesional con turnos futuros sin aviso) son corrupción operativa silenciosa, y
  el puente Oferta↔Práctica lo necesitan F4 (plan de tratamiento), F6 (consumo) y F7 (arancel).
- **F4 es la fase más cara** porque no tiene una sola pantalla. Pero sin HC accesible no hay
  forma de probar F5–F7 de punta a punta en la UI.
- **La obligación del financiador espera a F7** aunque sea el hueco funcional más grave, porque
  necesita la cobertura vigente (F3), la práctica (F2) y el arancel congelado (F3) bien cableados.
  Si se hace antes, se hace dos veces.
- **F9 queda congelada hasta pasar el gate de F8.** Es lo que manda DP-07, y la rama de F9 que
  está en `main` está a medias (08.04 sin API, 08.06 solo esquema).

## Paso 0 — Handoff del trabajo en curso (01/10/2026, 22 h)

Lo que había en vuelo al escribir esto:

| Qué | Dónde | Estado | Decisión recomendada |
|---|---|---|---|
| "PR grande" (~176k líneas, 954 archivos) | `main` contra `origin/claude/goofy-goodall-8e6772` | **No existe como PR en GitHub** (la API no muestra ningún PR, ni abierto ni cerrado, en los dos repos). Es el diff entre `main` —ya pusheado, con todo integrado— y la rama por defecto de GitHub, que quedó vieja en `ea3a5b6` (28/09) | Nada que mergear: `main` ya está en el remoto. **Cambiar la rama por defecto del repo a `main`** (GitHub → Settings → Branches); si no, todo PR nuevo apunta a la rama vieja y muestra 176k líneas |
| Tests de billing, encounter, activity, scheduling y offering | `akine-integracion-total`, **25 commits adelante de `main`**, sin pushear, worktree `.claude/worktrees/goofy-goodall-8e6772` | **activo** (último commit 21:58) | **No meterlo en el PR grande.** Dejar que termine en un commit limpio, correr `./mvnw verify`, y subirlo como **PR propio** después del grande. Son 23 archivos, solo tests, +7.253 líneas |
| WIP 08.06 (esquema de cobro y política de devengo) | `akine-08.06-productos`, `3e7d8d1` "SIN TERMINAR", pusheado | parado | **No integrar.** Queda como rama estacionada; F9 está congelada. Ver [F9](F9-segunda-entrega.md) |
| Worktrees `agent-*` (3) | `.claude/worktrees/agent-*` | ya mergeados en `akine-integracion-total` | Borrar los worktrees cuando la rama de tests esté integrada |
| Estos documentos | rama `docs/retomar-por-fases` | sin commitear | PR propio, chico |

## Paso 1 — Arreglos rápidos

| # | Defecto | Estado | Fase |
|---|---|---|---|
| 1 | `V55` `ck_tratamiento_lateralidad` rechaza `NO_APLICA`, que el contrato y el DTO aceptan → registrar un tratamiento en zona central (lumbar, cervical) falla en runtime | **verificado** | F6 |
| 2 | `reporte:read` no está en `RolePermissions` → los 3 endpoints de `reporting` no los puede usar nadie | **verificado** | F8 |
| 3 | `FinanciadorPagoService.cuentaCorriente` filtra por `consultorioId` y pagina fijo `200, 0` → totales erróneos desde el lote 201 | a verificar | F7 |
| 4 | `sumarAnuladoEnElReporte` no filtra `deletedAt` → el indicador de anulados cuenta de más | a verificar | F8 |

Cada uno con su test que reproduce primero (flujo "fix" del `CLAUDE.md`). El 1 necesita una
migración nueva: **no editar `V55`**.

## Decisiones del usuario que bloquean fases

Se listan acá porque frenan el orden. El detalle está en cada ficha.

| Decisión | Bloquea |
|---|---|
| Bootstrap del `PLATFORM_ADMIN` sembrado por `V15` | F1 (superficie de plataforma), F2 (consola de catálogos) |
| Puente Oferta↔Práctica (`oferta.practica_id` / `plan_item.practica_id`) | F2, F4 (plan), F6 (consumo), F7 (arancel) |
| ¿Una sesión con N tratamientos consume N unidades o 1? | F6, F7 |
| Unificar `concurrent-modification` y `conflict` | transversal, contrato |
| `egreso:manage` propio o reusar `caja:operate` | F7 |
| Adjunto binario del egreso: ¿alcanza la referencia o se extrae el storage a `platform.spi`? | F7 |
| Alcance `OWN` / `paciente:read` (vínculo cuenta↔persona) | F3, F5 (autoservicio), F9 |
| ¿F9 espera al gate del MVP? (DP-07) | F9 entera |
| `clase:*` propios o `turno:*` | F9 |
