# Deuda: `OPTIMISTIC_FORCE_INCREMENT` sobre una lectura no avanza la versión

> Origen: obra G2 (tramo 1), escenario 41 de C-7. Registro completo en
> `docs/producto/AKINE_IMPLEMENTATION_PLAN.md`, registro de cierre del fix de C-7 (§7 y §9).
> **Si vas a tocar alguno de los sitios de abajo, leé esto antes.**

## Qué se encontró

Patrón usado en varios módulos para que una escritura que **sólo toca tablas hijas** haga chocar
a dos escritores concurrentes sobre el padre:

1. leer el padre con un método `findWithLock…` anotado `@Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)`;
2. `save(padre)` sin cambiar ninguna de sus columnas, "para materializar el force-increment";
3. comparar `expectedVersion` del cliente y confiar en que la versión avanza al commit.

**Contra MySQL, con Hibernate, la versión no avanza.** Con `-Dspring.jpa.show-sql=true` no sale
ningún `UPDATE` del padre al commitear: el force-increment aplicado a una lectura por consulta no
incrementa, y el `save()` de una entidad gestionada sin cambios no la ensucia. Se probó también
con consulta derivada (sin `@Query`), igual que la de los sitios de abajo, y con el mismo
resultado. Consecuencia: **el control optimista no serializa nada**. Dos escritores con la misma
`expectedVersion` commitean los dos.

Los tests con mocks no lo ven. Sólo lo detecta un IT contra la base que **lea `version` de la
tabla** después de la operación.

## Cómo se resolvió en `Sesion` (encounter, fix de C-7)

`TratamientoService.registrar` dejó de usar `findWithLockByIdInScope` y pasó a un
`UPDATE … SET version = version + 1 WHERE id = :id AND version = :esperada` propio en el
repositorio. Ese `UPDATE`:

- da conflicto real si otro se adelantó (0 filas → 409);
- toma el lock de la fila en el acto, así que serializa las altas concurrentes;
- no ensucia la entidad, así que la versión avanza una sola vez.

La vista anuncia `expectedVersion + 1`. Escenario cubierto en `TratamientoRealizadoIT`
(`la_version_avanza_una_sola_vez`, `dos_altas_concurrentes_una_sola_entra`,
`version_vieja_es_conflicto_y_no_inserta`). Descartado: `EntityManager.lock(…)` en el servicio.

## Sitios pendientes (verificado contra el código el 2026-10-04, sin IT que lo confirme)

| Módulo | Lectura con force-increment | Escritura que depende de ella |
|---|---|---|
| `clinical` | `CasoClinicoRepository#findWithLockByIdAndOrganizationId` | `CasoClinicoService#cambiarEquipo`: escribe en `caso_profesional` y hace `save(caso)` sin cambios |
| `offering` | `OfertaRepository#findWithLockByIdAndOrganizationIdAndConsultorioId` | `OfertaHabilitacionService` (reemplazo de habilitaciones, vía `exigirOfertaConfigurable`): no toca columnas de `oferta` |

No están afectados, porque sus comentarios lo descartan a propósito y la escritura ensucia la
cabecera: `EntradaClinica`, `PlanTratamiento`, `MedicionService`, `SesionService` (enmienda) y
`billing/Egreso`. En el registro de A7 aparecían como sospechosos; no lo son.

`SesionRepositoryPort#findWithLockByIdInScope` quedó sin usos de producción. Sólo lo usa un
stub de `MedicionServiceTest`. Se puede borrar junto con ese stub.

## Cómo encararlo

Un paquete propio por sitio, en este orden:

1. **Primero el IT en rojo:** dos operaciones concurrentes con la misma `expectedVersion`. Una
   sola entra y la `version` leída de la base avanza exactamente 1. Escenario 41 como modelo.
2. Fix con el mismo `UPDATE` condicionado que `Sesion`, o la alternativa que el diseño de la etapa
   justifique.
3. Registro de cierre en el plan.
