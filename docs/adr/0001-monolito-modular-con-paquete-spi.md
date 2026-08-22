# ADR-0001 — Monolito modular con `spi` como único contrato entre módulos

- **Estado:** Aceptado
- **Fecha:** 2026-08-22
- **Etapa:** AKINE-00.01

## Contexto

El plan de implementación decide que AKINE sea un **monolito modular**: un único desplegable
Spring Boot dividido en módulos de dominio con ownership de datos y dependencias controladas.
Y exige que el acceso entre módulos ocurra "mediante contratos internos explícitos y no
mediante repositorios o tablas ajenas".

El plan **no dice dónde vive ese contrato**. Enumera los paquetes posibles de un módulo
—`api`, `application`, `domain`, `infrastructure`— pero ninguno de ellos es un lugar
natural para lo que un módulo ofrece a otros:

- `api` es la capa HTTP. Si un módulo llamara al `api` de otro, estaría hablándole por
  HTTP a un objeto que vive en el mismo proceso.
- `application` contiene la lógica interna completa, no una fachada. Abrirlo equivale a
  abrir todo el módulo.

Sin una respuesta explícita, el primer módulo funcional inventaría una convención y el
resto la copiaría. Peor: "ownership de datos" quedaría como una intención documentada que
nada impide violar.

AKINE tendrá 13 módulos y un ciclo de vida largo. El costo de equivocarse acá es alto:
deshacer acoplamiento entre módulos es de las refactorizaciones más caras que existen.

## Decisión

Cada módulo vive bajo `com.akine.<modulo>` y contiene **cinco** paquetes posibles:

```
com.akine.<modulo>.spi              ← ÚNICO punto de entrada desde otros módulos
com.akine.<modulo>.api              ← HTTP: controllers y DTO
com.akine.<modulo>.application      ← reglas de negocio, transacciones
com.akine.<modulo>.domain           ← modelo y entities
com.akine.<modulo>.infrastructure   ← repositorios, configuración, adaptadores
```

`spi` (Service Provider Interface) es **lo único público de un módulo**. Todo lo demás es
privado. Un módulo A puede depender de `com.akine.B.spi`; no puede depender de
`com.akine.B.api`, `.application`, `.domain` ni `.infrastructure`.

La alternativa siempre disponible es un **evento posterior al commit**, cuando el efecto es
derivado y no forma parte de la invariante transaccional.

La regla se verifica en `ModuleArchitectureTest.modulos_solo_se_alcanzan_por_su_spi`.

## Alternativas consideradas

**Módulos Maven separados, uno por dominio.** El compilador impediría por construcción el
acceso indebido: la garantía más fuerte posible. Descartada porque multiplica por 13 la
complejidad del build, ralentiza el ciclo de compilación, y obliga a decidir el grafo de
dependencias completo antes de conocer el dominio. Es una opción razonable para **más
adelante**, si el proyecto crece: los paquetes `spi` ya trazan exactamente dónde estarían
los límites de esos módulos, así que la migración sería mecánica.

**Spring Modulith.** Aporta verificación de límites, documentación generada y un modelo de
eventos entre módulos. Descartada para el baseline por tres motivos: su soporte sobre Spring
Boot 4.1 todavía no está consolidado; impone su propia convención de paquetes, que no coincide
con la del plan; y agrega una dependencia estructural en la etapa donde menos conviene tener
incógnitas. Vale reconsiderarla cuando existan varios módulos reales.

**Usar `api` como puerto interno además de HTTP.** Cero paquetes nuevos. Descartada porque
confunde dos audiencias con requisitos opuestos: los DTO HTTP son parte del contrato público
versionado con el frontend, mientras que un contrato interno puede cambiar libremente en un
mismo commit. Mezclarlos convierte cualquier refactor interno en un cambio de API.

**Solo documentar la regla en `AGENT.md`.** Cero fricción. Descartada: una regla que nada
verifica se viola el día que hay apuro, y el apuro es exactamente cuando más importa. En
un proyecto que va a durar años, la documentación sin enforcement es una intención.

## Consecuencias

### Positivas

- El ownership de datos es **ejecutable**, no aspiracional: el build falla si un módulo
  toca las internals de otro.
- El grafo de dependencias entre módulos queda explícito y auditable.
- Los `spi` marcan de antemano dónde estarían los límites si algún día se extraen servicios
  o módulos Maven.
- Un cambio interno de módulo nunca puede romper a otro sin que el compilador lo diga.

### Negativas

- Un paquete más por módulo, con la disciplina que eso exige.
- Requiere definir tipos propios en `spi` en lugar de reutilizar entities. Es la intención
  —una entity ajena filtra el esquema de otro módulo— pero implica mapeo y algo de duplicación.
- Un módulo con un `spi` enorme señala un límite mal trazado, y detectarlo requiere criterio,
  no un test.

### Qué obliga a hacer

- Todo módulo nuevo respeta los cinco paquetes.
- Todo tipo expuesto en `spi` es un DTO o un record propio del módulo. **Nunca una entity.**
- Antes de agregar una dependencia entre módulos, verificar que no genere ciclo:
  `./mvnw test -Dtest=ModuleArchitectureTest`.
- Si `ModuleArchitectureTest` falla, el diseño del cambio está mal. **No se relaja la regla.**
