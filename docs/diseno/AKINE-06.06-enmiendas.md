# AKINE-06.06 — Enmiendas y versionado de sesión cerrada

**Módulo:** `encounter` (M14) · **Migración:** `V53` · **Contrato:** `0.35.0`
**Trazabilidad:** RF-M14-010, RF-M24-005, RN-M14-006, `plan_sesiones.txt` §18.3
**Depende de:** AKINE-06.05 (cierre idempotente y correlativo)

---

## 1. El problema, en una frase

Una sesión cerrada es historia clínica. ADR-0011 y la regla maestra 10 prohíben reescribirla, y
06.05 lo dejó fail-closed: hoy escribir sobre una sesión cerrada es **409**. Pero una sesión
cerrada con un dato mal también es historia clínica mal registrada, y no poder corregirla obliga a
elegir entre dos cosas malas. Enmendar es la salida: **una versión nueva que preserva el original**,
con motivo obligatorio y autoría propia.

**Enmendar no es un `UPDATE`.** Es un `INSERT` de contenido nuevo más el avance de un contador.

---

## 2. No se inventa una tercera forma: se copia la de 04.02

Este repositorio ya resolvió esto dos veces y las dos están en esta rama.

| Precedente | Forma | Qué se toma |
|---|---|---|
| **04.02** `entrada_clinica` + `entrada_clinica_version` | cabecera con contador + una fila por versión | **Todo**: numeración por contador del padre, motivo obligatorio desde la v2, versiones sin `active` ni `deleted_at`, motivo faltante = **400 y no 409** |
| **04.04** `plan_tratamiento` + `plan_tratamiento_version` + `plan_item` | los ítems cuelgan de la **versión** | El principio: lo que puede cambiar cuelga de la versión, no del padre |

La única diferencia estructural con 04.02 es que **acá la cabecera ya existe y ya tiene contenido**:
`sesion` guarda sus columnas clínicas desde `V34`/`V35` y hay consumidores que las leen. Esa
diferencia se resuelve en §4 y se paga con una duplicación declarada, no con un modelo distinto.

---

## 3. Qué se puede enmendar, y qué no

La lista **no es negociable por configuración**: está en el tipo del request, que no tiene campos
para lo demás. No hace falta un error de "campo bloqueado" porque el campo no existe en la puerta.

### Enmendable — el relato clínico

De la evaluación base (`V34`): `motivoClinico`, `dolorEva`, `dolorZona`, `dolorLateralidad`,
`evolucion`, `objetivoSesion`, `limitacionFuncional`.

Del cierre (`V35`): `notaDeCierre`, `respuestaTratamiento`, `tolerancia`, `indicaciones`,
`proximaConducta`.

### NO enmendable, y por qué cada uno

| Campo | Por qué no |
|---|---|
| `numeroSesion` | Renumerar sesiones cerradas es lo que **04.03 rechazó explícitamente** y lo que 06.05 dejó escrito: el número está impreso en informes. `uk_sesion_numero` lo respalda |
| `numeroEnCaso` | Mismo argumento, y además no hay operación que mueva una sesión de caso |
| `casoId`, `ofertaId`, `turnoId`, `profesionalMembershipId` | Son `updatable = false` desde `V33`/`V48`. No son contenido clínico: son la identidad del hecho |
| `iniciadaEn`, `cerradaEn`, `cerradaPorCuentaId`, `estado` | Cuándo pasó y quién lo asentó. Corregir eso no es enmendar, es falsificar |
| **`asistencia`** | **Es la decisión filosa de la etapa. Ver §5** |
| `modo` | No es contenido clínico: es una decisión de la pantalla sobre cuánto mostrar (06.02). Versionarlo sería versionar una preferencia de UI |

---

## 4. Modelo de datos — `V53`

### 4.1 `sesion_version` (tabla nueva, propietario `encounter`)

Una fila por versión del contenido enmendable. **La v1 es el original**, escrita en el cierre.

```
id · organization_id · sesion_id · numero_version
motivo_clinico · dolor_eva · dolor_zona · dolor_lateralidad · evolucion
objetivo_sesion · limitacion_funcional
nota_de_cierre · respuesta_tratamiento · tolerancia · indicaciones · proxima_conducta
motivo_enmienda   -- NULL sólo en la v1
registrada_en · registrada_por
created_at · updated_at
UNIQUE (organization_id, sesion_id, numero_version)
```

**Sin `active`, sin `deleted_at`, sin `@Version`.** Las cuatro ausencias son la misma decisión que
en `entrada_clinica_version`: una versión es un hecho pasado. Darla de baja sería reescribir
historia clínica.

Los `CHECK` de contenido son **los mismos que `V34` y `V35` le pusieron a `sesion`** —EVA 0..10,
lateralidad con zona, enums cerrados—. Repetirlos no es redundancia: una versión que la cabecera no
aceptaría no debería poder existir tampoco acá.

`ck_sesion_version_motivo_de_enmienda`: `(numero_version = 1 AND motivo_enmienda IS NULL) OR
(numero_version > 1 AND motivo_enmienda IS NOT NULL)`. Igual que `V45`.

### 4.2 `sesion.ultimo_numero_version`

`INT NOT NULL DEFAULT 0`. Vale `0` mientras la sesión está abierta y `1` desde el cierre.

`ck_sesion_version_coherente`: `(numero_sesion IS NULL AND ultimo_numero_version = 0) OR
(numero_sesion IS NOT NULL AND ultimo_numero_version >= 1)`.

**Es el numerador, y no hay tabla aparte.** La regla del repositorio —`UPDATE ... ultimo_numero + 1`
y nunca `MAX+1`, con la fila del numerador creada en transacción aparte— se cumple con el contador
de la propia cabecera, que es lo que hizo 04.02: **la fila ya existe** (la creó el cierre), así que
no hay creación perezosa que pueda producir deadlock y no hace falta `REQUIRES_NEW`.

### 4.3 Backfill

`V53` inserta la v1 de **todas las sesiones ya cerradas**, con `registrada_en = cerrada_en` y
`registrada_por = cerrada_por_cuenta_id` —los dos son `NOT NULL` para una sesión cerrada por
`ck_sesion_cierre_completo`— y después pone `ultimo_numero_version = 1` en esas filas. El orden
importa: primero el backfill, después el `UPDATE`, y el `CHECK` al final.

Sin backfill, el historial de una sesión cerrada antes de esta etapa arrancaría en la v2 y el
original no existiría en ningún lado — exactamente lo que la etapa viene a evitar.

---

## 5. La decisión que manda sobre todo: la enmienda NO re-dispara los observadores del cierre

Al cerrar corren dos observadores **dentro** de la transacción:

- `billing.ObligacionDevengador` — devenga la deuda con precio congelado
- `encounter.ConsumoDeAutorizacionEnCierre` — consume una unidad autorizada

**La enmienda no los llama. Nunca.**

No porque re-llamarlos duplicaría la deuda —**no lo haría**: los dos son idempotentes por el hecho
de origen, y el challenge §8 lo verificó contra el código en vez de suponerlo— sino porque
re-llamarlos sólo puede **agregar** efectos económicos y nunca sacarlos, y porque la idempotencia
del consumo de autorizaciones es **por autorización**, no por sesión: si cambió cuál es la
autorización elegible, el unique no choca y se gasta una segunda unidad.

Y sobre todo, **no hace falta llamarlos, porque nada de lo enmendable los afecta**. Los dos observadores leen
exactamente tres cosas de `SesionCerrada`: `asistio`, `ofertaId` y el precio de la oferta. Ninguna
es enmendable. **Por eso `asistencia` está congelada: es la única bisagra entre el relato clínico y
el dinero, y dejarla enmendable obligaría a decidir en esta etapa una compensación económica que
tiene dueño en otro módulo.**

Es el criterio de aceptación del plan —"no hay cambios económicos implícitos"— convertido en una
propiedad del esquema en vez de en una promesa del servicio: no hay camino por el que una enmienda
mueva plata, porque el request no tiene el campo que la movería.

> **La contracara, declarada:** una sesión cerrada con la asistencia equivocada **no se arregla con
> una enmienda**. Ver §9.

---

## 6. Quién puede enmendar

`sesion:register` **más propiedad de la atención**, exactamente igual que guardar el borrador,
evaluar y cerrar.

- Sin el permiso → **403**.
- Con el permiso pero sobre la atención de otro profesional → **409 `sesion-ajena`**, nunca 403.
  Es la regla que 06.01 dejó fijada: la propiedad no es un permiso, y un 403 mandaría al usuario a
  pedir un permiso que ya tiene.

**No se crea un `sesion:amend`.** Un código nuevo en `PermissionCode` nace sin asignación base —o
sea, denegado para todos— y dejaría la operación inalcanzable; y dárselo por defecto al
`PROFESIONAL` produciría exactamente la misma población que `sesion:register`. El "permiso clínico
reforzado" que pide el plan lo dan las tres cosas que sí se agregan: **propiedad, motivo obligatorio
y auditoría**. Si el usuario quiere separarlos —por ejemplo, que un supervisor enmiende sin ser el
autor— es una decisión de matriz de permisos y una etapa propia, porque además necesita el registro
de "quién reemplaza a quién" que 06.01 declaró inexistente.

---

## 7. Ventana temporal: **no hay**

Se puede enmendar una sesión de hace dos años.

El argumento en contra —"pasado cierto tiempo el registro se congela"— suena prudente y es peor: el
error clínico que más necesita corrección es justamente el que se descubre tarde, cuando alguien
relee la historia. Una ventana no borra el error, sólo obliga a convivir con él.

Lo que hace segura la ausencia de ventana es que la enmienda **no puede ocultar nada**: el original
queda, con su autor y su fecha, y la enmienda queda al lado con las suyas. Quien lea la sesión ve
que fue enmendada y cuándo. Una corrección tardía **se nota más**, no menos.

Lo que sí está congelado es lo económico (§5), así que una enmienda tardía no puede tocar una
obligación ya presentada ni un cobro ya imputado.

---

## 8. API — contrato `0.35.0`, aditivo

Dos operaciones nuevas bajo el controller que ya existe.

| Verbo | Ruta | Qué hace |
|---|---|---|
| `POST` | `/api/v1/consultorios/{consultorioId}/sesiones/{sesionId}/enmiendas` | Enmienda: escribe la versión siguiente. Devuelve la sesión vigente |
| `GET` | `/api/v1/consultorios/{consultorioId}/sesiones/{sesionId}/versiones` | Historial completo, de la v1 a la última (RF-M24-005) |

El request de enmienda lleva **los once campos enmendables, el motivo y `version`** —la del
`@Version` de la cabecera, no el número de versión de contenido—.

**La enmienda es un reemplazo completo, no un parche.** Un campo ausente significa "queda vacío", no
"dejalo como estaba". Un parche obligaría a distinguir "no lo mandé" de "lo borré" sobre campos que
son legítimamente nulos —toda la evaluación base lo es— y esa distinción no se puede expresar en
JSON sin inventar un centinela. La pantalla manda el formulario completo, que es lo que ya hace al
evaluar.

`SesionResponse` gana dos campos: `ultimoNumeroVersion` y `fueEnmendada`. Aditivos.

Errores:

| Situación | Código | `type` |
|---|---|---|
| La sesión sigue abierta | 409 | **`sesion-no-cerrada`** (nuevo) |
| La atención es de otro profesional | 409 | `sesion-ajena` |
| La versión quedó vieja | 409 | `concurrent-modification` |
| Falta el motivo | **400** | `enmienda-sin-motivo` (ya existe, de 04.02) |
| EVA fuera de escala, lateralidad sin zona, o nota faltante con paciente presente | 400 | `validation-error` |
| Sesión o sede de otro tenant | 404 | `not-found` |

Las dos operaciones devuelven cuerpo, así que no aplica la trampa del `produces` de las 204.

---

## 9. Lo que esta etapa NO hace, dicho en voz alta

1. **No corrige la asistencia.** Cambiar `AUSENTE → PRESENTE` exige devengar la deuda y consumir la
   autorización que no se consumieron; al revés exige anular una obligación (M18) y revertir un
   consumo (M17, que ya tiene el endpoint de reversión de 04.05). Son **compensaciones explícitas**,
   que es lo que el plan pide, y tienen dueño en otros módulos. Acá sería una tercera
   implementación de la misma regla, escrita por quien no es dueño de ninguna de las dos tablas.
2. **No hay diff.** La versión trae el contenido **completo**, no qué caracteres cambiaron. Es el
   mismo criterio de 04.02: lo que el profesional necesita leer es qué decía la historia en ese
   momento. El diff es trabajo de la pantalla, y la pantalla tiene las dos versiones enteras.
3. **No hay frontend.** El cliente TypeScript se genera desde el contrato y el contrato no se puede
   regenerar sin Docker.
4. **No hay tests de integración ni contrato regenerado.** Docker no está disponible. Ver §11.
5. **No se enmiendan entradas clínicas ni planes desde acá.** Ya tienen su propia enmienda.
6. **No se marca "enmendada después de presentada a la obra social".** M21 no existe. Cuando exista,
   `sesion_version.registrada_en` es todo lo que necesita para calcularlo al leer, sin columna nueva.

---

## 10. Concurrencia — las trampas que ya se pagaron

1. **Sin `OPTIMISTIC_FORCE_INCREMENT`.** Enmendar **ensucia la cabecera**: `ultimo_numero_version`
   cambia, así que el flush ya emite un `UPDATE ... WHERE version = N`. Dos enmiendas concurrentes
   leen la misma versión, las dos ensucian la fila, una gana y la otra recibe 409. La garantía ya
   está. Forzar el incremento dejaría la base en `leída + 2` devolviendo `leída + 1`, y el cliente
   comería un 409 del que no puede salir. **Es lo que 04.02 pagó, y la regla que queda es
   force-increment sólo donde la escritura NO toca ninguna columna del padre.**
2. **`saveAndFlush`, no `save`.** La respuesta lleva la versión de la cabecera, y `save` es un merge
   cuyo `UPDATE` sale recién al cerrar la transacción: el cliente se llevaría la versión vieja.
3. **El unique `(organization_id, sesion_id, numero_version)` es red, no mecanismo.** Lo que
   serializa es el `UPDATE` versionado; el unique existe para que un camino futuro que no pase por
   el servicio choque contra la base en vez de dejar dos "versión 3".
4. **`@Transactional` normal, no `READ_COMMITTED`.** Acá no se toma ningún lock de fila de un
   numerador: el control es optimista de punta a punta, igual que en `EntradaClinicaService`. La
   regla de 05.02 aplica a las mutaciones que **serializan con un lock**, y esta no es una.
5. **Ningún `catch (DataIntegrityViolationException)`.** No hay nada que consultar después de un
   flush fallido en este camino.

---

## 11. Verificación

Sin Docker: unitarios (`-DskipITs`) y ArchUnit. **No se escriben `*IT`.**

Queda **sin verificar y declarado**:

- que `V53` ejecute contra MySQL 8.4, incluido el backfill y el orden `INSERT → UPDATE → CHECK`;
- que dos enmiendas concurrentes reales terminen en 409 y no en dos versiones con el mismo número;
- que el `CHECK` de coherencia no rechace una sesión abierta recién creada;
- el contrato `0.35.0` regenerado —`OpenApiContractIT` va a reportar drift hasta que alguien corra
  `./mvnw verify -Dakine.contract.update=true`—;
- la cobertura.
