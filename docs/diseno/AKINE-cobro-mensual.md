# AKINE — Cobro mensual de la suscripción y avisos de vencimiento

19/11/2026. Decisiones del usuario en la sesión de la consola de plataforma.

## Qué pide

1. La plataforma marca, mes a mes, si un centro pagó. El pago habilita el acceso.
2. Al centro se le avisa que la suscripción está por vencer **15, 10, 5, 2 y 1 días antes**, y que se va a cerrar.
3. Avisos **por correo a los administradores del centro y con un cartel en la aplicación**.

## Línea de tiempo de un período pagado

`V` = `pagado_hasta` (instante UTC hasta el que está pagado).

| Momento | Estado efectivo | Qué puede hacer el centro | Aviso (correo) |
|---|---|---|---|
| hasta `V − 15 d` | ACTIVA | todo | — |
| `V − 15/10/5/2/1 d` | ACTIVA | todo, con cartel "vence el …" | `SUSCRIPCION_POR_VENCER` (uno por hito) |
| `V` … `V + 3 d` (gracia) | ACTIVA | todo, con cartel "venció, quedan N días" | `SUSCRIPCION_VENCIDA` |
| `V + 3 d` … `V + 18 d` | SUSPENDIDA | sólo lectura, como hoy (RN-M01-002) | `SUSCRIPCION_SUSPENDIDA` (dice la fecha de cierre) |
| desde `V + 18 d` | SIN ACCESO | ninguno: el contexto se rechaza | `SUSCRIPCION_CERRADA` |

- **Se calcula al leer**, igual que la prueba vencida (`Subscription.estadoEfectivo`): no hay tarea que mueva el
  estado. El worker sólo manda correos.
- **Registrar un cobro** devuelve todo a ACTIVA en el acto.
- **`pagado_hasta` nulo = sin control de cobro.** Los centros que existen hoy siguen como están hasta que la
  plataforma registre su primer cobro. Así el cambio no le cierra el acceso a nadie.
- **La prueba gratis no cambia de regla**: vencida queda en sólo lectura sin límite (lo promete la landing). Sólo
  cambian sus avisos: pasan de uno a 7 días a 15/10/5/2/1 días, más el de vencida.
- Una SUSPENDIDA o CANCELADA guardada (manual de la plataforma) manda sobre el cobro.

## Registrar un cobro

`POST /api/v1/platform/organizations/{orgId}/cobros` (rol de plataforma), cuerpo `{ "meses": 1..12, "nota"?: string ≤ 200 }`.

- `desde = max(pagado_hasta, ahora)`; `hasta = desde + meses` (meses calendario, en la zona del centro).
  Pagar antes de vencer conserva el ciclo; pagar tarde lo reinicia desde hoy.
- Sobre una suscripción en PRUEBA: pasa a ACTIVA (transición existente) y arranca el ciclo desde ahora.
- SUSPENDIDA o CANCELADA guardadas: 409 (primero se reactiva por la vía de siempre).
- Fila en `subscription_cobro` (historial) + transición/auditoría como el resto de la plataforma.
- `GET /api/v1/platform/organizations/{orgId}/cobros`: historial, el más nuevo primero.

## Lo que se expone

- `PlatformOrganizationResponse` (listado de centros) y `ContratacionResponse` (el centro mirándose):
  `pagadoHasta` y `situacionDeCobro`:
  `SIN_CONTROL | AL_DIA | POR_VENCER (≤ 15 d) | EN_GRACIA | SOLO_LECTURA | SIN_ACCESO`, más `cierreEl` (instante
  de `V + 18 d`) cuando hay control.
- `soloLectura` de `ContratacionResponse` también es `true` en `SOLO_LECTURA`.

## Sin acceso

Estado operativo nuevo `SIN_ACCESO` (dominio y `platform.spi`): `allowsContextUsage() == false`, igual que
CANCELADA. El selector de contexto lo muestra como "Sin acceso: suscripción cerrada por falta de pago" en vez
de esconder el centro.

## Números reservados

Migración **V45**, contrato **0.191.0** (nació como V30 y 0.181.0; renumerada al integrar sobre planes, que dejó V44 y 0.190.0).
