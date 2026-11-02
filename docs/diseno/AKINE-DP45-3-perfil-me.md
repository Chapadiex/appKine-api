# AKINE-DP45-3 — Perfil propio (`GET /api/v1/me`)

DP-45 punto 3. Contrato 0.146.0, sin migracion.

## Que agrega
- `GET /api/v1/me` (`getMe`), modulo `identity`. Autenticado, sin permiso ni contexto obligatorio.
- Respuesta: `accountId`, `email`, `firstName`, `lastName` y `context` (organizacion y sede con id y nombre,
  `roleCode`, `roleLabel`). Sin contexto activo `context` se omite (el servicio omite los null) y responde 200.
- `/api/v1/me` entra en `RUTAS_EXACTAS_EXCEPTUADAS` de `TenantContextFilter`: se consulta con el token
  `pre_context`. Como el filtro no resuelve contexto en rutas exceptuadas, el contexto se toma del principal
  (`organizationId`/`consultorioId`, ya revalidado en el request) y el rol de `AccountContextDirectory.membership`.

## Que no hace
No expone hash, tokens, estado ni intentos de la cuenta. No cambia contexto. No acepta id de cuenta: sale del principal.
`roleLabel` es una tabla fija en el servicio (el backend no tenia etiquetas).

## Design challenge
1. Ownership: sin tablas nuevas. Lee `cuenta` (identity).
2. Ciclos: `identity -> organization.spi` ya existia (`SessionService`); organization no importa identity. ArchUnit en verde.
3. Tenant: sin tablas. El contexto sale del principal y se contrasta con los contextos autorizados vigentes de la cuenta.
4. Reglas maestras: no aplica.
5. Baja logica: sin borrados.
6. Contrato: aditivo (una operacion, un schema).
7. Ruta critica: el frontend la pide para el pie de la barra lateral; cimientos (login, contextos) existen.
8. Caso adverso: membership vencida con token vivo -> `membership()` + `authorizedContexts` revalidan contra la base;
   si ya no esta autorizada `context` se omite en vez de mentir el rol. Cuenta de plataforma sin contexto: 200 sin `context`.
