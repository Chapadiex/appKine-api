# ADR-0006 — La arquitectura se verifica con tests, no con revisión

- **Estado:** Aceptado
- **Fecha:** 2026-08-22
- **Etapa:** AKINE-00.01

## Contexto

El plan describe con precisión la arquitectura que AKINE debe tener: monolito modular,
ownership de tablas por módulo, dependencias unidireccionales, ciclos prohibidos, entities
que no salen del backend, capas con responsabilidades separadas.

También dice, explícitamente, que los ciclos "están prohibidos y se verifican con pruebas
de arquitectura".

El problema de fondo: **una regla arquitectónica que solo vive en un documento se viola el
día que hay apuro**, y el apuro es exactamente cuando más importa que no se viole. Ese día
nadie relee `AGENT.md`; se importa el repositorio del módulo vecino porque es más rápido,
el PR pasa porque el revisor mira la lógica y no el `import`, y la erosión empieza.

La erosión arquitectónica es acumulativa y asimétrica: cada violación individual parece
inocua, y el costo de revertirlas aparece años después, todo junto, cuando ya es carísimo.
AKINE tiene 29 módulos por delante.

## Decisión

**Las reglas arquitectónicas se expresan como tests que fallan el build.**

Dos suites en `src/test/java/com/akine/architecture/`:

**`ModuleArchitectureTest`** — límites entre módulos y capas:

| Regla | Qué previene |
|---|---|
| `sin_ciclos_entre_modulos` | Dos módulos que en realidad son uno mal separado |
| `modulos_solo_se_alcanzan_por_su_spi` | Que se pierda el ownership de datos ([ADR-0001](0001-monolito-modular-con-paquete-spi.md)) |
| `capas_respetan_su_direccion` | Que `domain` dependa de la infraestructura |
| `application_no_conoce_http` | Reglas de negocio inejecutables fuera de un controller |
| `domain_no_conoce_web` | Un dominio atado al framework |

**`CodingConventionsTest`** — convenciones con consecuencia concreta:

| Regla | Qué previene |
|---|---|
| `entities_no_salen_por_la_api` | Filtrar columnas internas y atar el contrato HTTP al esquema |
| `sin_punto_flotante_en_el_dominio` | Errores de redondeo que descuadran la caja ([ADR-0004](0004-convenciones-de-persistencia-multi-tenant.md)) |
| `sin_apis_de_fecha_obsoletas` | `Date`/`Calendar`: mutables y sin zona explícita |
| `sin_inyeccion_por_campo` | Objetos construibles en estado incompleto |
| `sin_escribir_a_consola` | Un `println` con un dato clínico, sin nivel ni trace id |
| `controllers_solo_en_api`, `entities_solo_en_domain`, `repositorios_solo_en_infrastructure`, `servicios_solo_en_application`, `configuracion_en_infrastructure`, `dtos_agrupados` | Que cada módulo invente su propio layout |
| `campos_de_servicios_son_finales` | Estado mutable compartido entre hilos en un singleton |

**Regla de oro: cuando un test de arquitectura estorba, el diseño del cambio está mal.**
Modificar una de estas reglas exige una decisión documentada en el plan de implementación,
no una línea en un PR.

## Alternativas consideradas

**Documentar y confiar en la revisión de código.** Cero infraestructura. Descartada: la
revisión humana es buena detectando errores de lógica y mala detectando un `import` de más
en un diff de 400 líneas. Además no escala con la rotación de gente ni con el volumen de
29 módulos.

**Módulos Maven separados.** El compilador garantizaría los límites, más fuerte que
cualquier test. Descartada en [ADR-0001](0001-monolito-modular-con-paquete-spi.md) por el
costo de build y de coordinación en esta etapa.

**Spring Modulith.** Trae verificación de límites lista para usar. Descartada en
[ADR-0001](0001-monolito-modular-con-paquete-spi.md): su soporte sobre Boot 4.1 no está
consolidado y su convención de paquetes no coincide con la del plan.

**Solo reglas de SonarQube.** Sonar detecta bien complejidad y bugs, y mal dependencias
arquitectónicas entre paquetes propios. Descartada como sustituto; se usará como complemento.

**Verificar únicamente los ciclos, que es lo que el plan pide literalmente.** Descartada por
insuficiente: el plan también prohíbe el acceso a repositorios ajenos, que las entities
salgan del service y el punto flotante en importes. Verificar solo los ciclos deja fuera
las reglas más fáciles de violar sin darse cuenta.

## Consecuencias

### Positivas

- La arquitectura es **ejecutable**: el build falla ante una violación, en el momento en que
  se introduce y no meses después.
- Las reglas se documentan solas. El mensaje de error de `modulos_solo_se_alcanzan_por_su_spi`
  dice exactamente qué hacer en su lugar.
- Un integrante nuevo aprende las convenciones por el feedback del build, sin memorizar
  un documento.
- El costo de detección se mueve al momento más barato posible.

### Negativas

- Los tests corren sobre el bytecode completo: agregan unos segundos a cada build, que
  crecerán con el proyecto.
- Con un solo módulo, varias reglas no encuentran clases que verificar y ArchUnit falla por
  `failOnEmptyShould`. Se resolvió con `allowEmptyShould(true)` **por regla**, con el motivo
  escrito al lado, en lugar de desactivarlo globalmente: así la señal vuelve sola cuando
  esas clases existan.
- Una regla mal calibrada genera fricción y tienta a relajarla. De ahí la regla de oro.
- ArchUnit trabaja sobre paquetes y tipos: no puede verificar reglas semánticas como "toda
  consulta filtra por `organization_id`". Esas siguen dependiendo de la revisión y del QA
  contra la base.

### Qué obliga a hacer

- Correr `./mvnw test` antes de abrir un PR: incluye las 17 reglas y no necesita Docker.
- Al crear el primer módulo funcional: devolver `optionalLayer("SPI")` a `layer("SPI")` y
  retirar los `allowEmptyShould(true)` que ya no hagan falta.
- Al agregar una regla nueva, documentar en el `because(...)` **qué bug concreto previene**.
  Una regla sin consecuencia explicada es una preferencia estética y no pertenece acá.
- **Nunca** relajar una regla para que pase un cambio. Primero revisar el diseño.
