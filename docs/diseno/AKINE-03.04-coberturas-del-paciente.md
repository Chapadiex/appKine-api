# AKINE-03.04 — Coberturas del paciente (M08)

Diseño de la etapa. Trazabilidad: RF-M08-001..005, RN-M08-001..004, RNF-M08-001..008.
Dependencias declaradas: **AKINE-03.01** (Persona/PerfilPaciente) y **AKINE-03.03**
(Financiador/Plan). Es el **primer consumidor** de `contracting.spi`.

---

## 1. Módulo propietario

`person`. AGENT.md §4 declara `person/` como dueño de M07–M08, y la etapa reusa todo lo que
03.01 dejó: `PersonApiActor`, `PersonProblemHandler`, `AutorizacionDePadron`, `MarcaTemporal`
y el permiso `paciente:manage`. **No se crea ningún módulo nuevo**, así que tampoco hace falta
un `ApiActor` con nombre propio — la trampa que 02.06 documentó.

La dependencia nueva es `person → contracting.spi`, unidireccional: `contracting` no importa
`person` en ninguna parte. ArchUnit lo verifica y pasa.

## 2. La decisión que sostiene toda la etapa: copiar, no apuntar

`contracting.spi` separa a propósito dos mitades, y esta etapa es la que tenía que no
confundirlas:

| Mitad | Para qué | Quién la usa acá |
|---|---|---|
| `find*` / `planesSeleccionables` | **Decidir**: poblar el selector de planes | La pantalla. **No este backend** |
| `congelar(...)` | **Guardar**: devuelve `ReferenciaDeCobertura`, un record de valores | `CoberturaPacienteService.agregar`, y sólo ahí |

`cobertura_paciente` tiene **nueve columnas de copia** —código, nombre y tipo del financiador,
código y nombre del plan, si exigía autorización y credencial, copago y moneda— más
`referencia_capturada_el`. Todas son `updatable = false` en la entidad: una copia que se puede
editar no es una copia congelada, es una cache mal implementada.

**Después del alta, ninguna lectura vuelve a tocar `contracting`.** Ni el listado, ni la
selección del día, ni la edición. Esa ausencia de llamadas *es* la garantía, y
`CoberturaPacienteServiceTest` la hace ejecutable con `verifyNoMoreInteractions(catalogo)`.

`financiador_id` y `plan_id` se guardan igual, y para dos cosas: trazabilidad y el unique del
número de afiliado. Nunca para resolver texto.

## 3. Ninguna regla temporal está en el esquema

MySQL 8.4 no tiene exclusion constraints, y ningún UNIQUE puede decir que dos intervalos se
pisan. Las dos invariantes entre filas las hace cumplir un **lock**:

1. Un paciente no puede tener dos coberturas activas **del mismo plan** con vigencias solapadas
   → 409 `cobertura-superpuesta`.
2. Un paciente no puede tener dos coberturas activas marcadas **principal** con vigencias
   solapadas → 409 `cobertura-principal-superpuesta`. Es lo que hace determinista la selección
   del día.

`cobertura_persona_lock` es una fila por persona cuyo único propósito es ser bloqueada. Se crea
en **transacción aparte** (`CoberturaLockIniciador`, `REQUIRES_NEW`) con
`INSERT ... ON DUPLICATE KEY UPDATE`, y las mutaciones que la toman van en **`READ_COMMITTED`**.
Los dos detalles ya se pagaron tres veces en el repositorio —`agenda_sede`,
`consultorio_calendario`, `sesion_numerador`— y acá están escritos así desde el principio.

**Lo que NO es regla:** dos coberturas de financiadores *distintos* solapadas son legítimas.
Obra social y prepaga a la vez es el caso normal. Y **la baja no toma el lock**: quitar una fila
no puede crear un solapamiento ni una segunda principal.

## 4. Particular es la ausencia de plan

RN-M08-001 —"PARTICULAR siempre debe estar disponible"— **no necesita ninguna fila** para ser
verdad. `tipo = 'PARTICULAR'` con las nueve columnas de referencia en NULL, y
`ck_cobertura_referencia_coherente` lo hace cumplir desde la base. Es la contracara de la
decisión de V41: sembrar un financiador PARTICULAR lo volvería borrable, renombrable y
duplicable.

`GET /seleccion` devuelve `particularSiempreDisponible: true` como **campo** y no como fila. Si
fuera una lista a secas, "atender como particular" sería un caso especial que cada pantalla
tendría que acordarse de agregar, y la primera que lo olvide deja al mostrador sin poder cobrar
una consulta a un paciente con obra social — que es exactamente RF-M08-005.

## 5. Cuatro operaciones, cuatro significados

| Operación | Qué es | Qué NO es |
|---|---|---|
| `POST` | Agregar. Congela el plan contra `vigenciaDesde` | |
| `PUT` con `vigenciaHasta` | **Finalizar la vigencia** (RF-M08-003). La cobertura queda ACTIVA | No es dar de baja |
| `POST /{id}/principal` | Elegir la preferida (RF-M08-004) | No desmarca a la otra en silencio: 409 |
| `DELETE` | Baja lógica, "nunca debió cargarse" | No borra, no toca hechos registrados |

**El plan no se puede cambiar.** No por una validación: los nueve campos son `updatable = false`
y el DTO ni los ofrece. Cambiar de plan es finalizar la vigente y agregar otra, porque editarla
en el lugar reescribiría con qué cobertura se atendió al paciente el mes pasado (RN-M08-003).

## 6. Persona no es Paciente

Una cobertura es del **paciente**. Si la persona existe pero no tiene perfil vigente → 409
`persona-sin-perfil-paciente`, el mismo `type` que 05.02 ya declaró para la reserva de turnos:
es la misma condición y darle uno propio obligaría al frontend a manejar dos códigos para la
misma acción.

## 7. Permisos

Sin permisos nuevos y sin enmienda a la matriz. Leer **por pertenencia** (contexto de
organización), mutar con **`paciente:manage`** evaluado sobre la sede del contexto — el mismo
criterio, y la misma trampa documentada, que 03.01.

Hereda el **hueco abierto de 03.01**: no existe `paciente:read`, así que una membership con rol
`PACIENTE` lee las coberturas de cualquier paciente de su organización. La causa de fondo es la
misma: el alcance `OWN` no está implementado porque no hay vínculo entre cuenta y persona.

## 8. Lo que la etapa NO cierra

- **RF-M08-006 y RF-M08-007** (resolver la cobertura aplicable por Oferta de Servicio) **no se
  implementan**. Necesitan M16 (convenios) y M17 (autorizaciones), que no existen. Adelantarlo
  sería construir un consumidor antes que su cimiento — el primero de los tres errores de
  AGENT.md §9. RN-M08-004 se respeta por omisión: nada de lo que la API devuelve afirma que la
  prestación sea facturable a ese financiador.
- **La credencial vencida no invalida nada.** Se informa como `credencialVencida` y la cobertura
  sigue aplicando. Vencerla automáticamente daría de baja coberturas reales por un dato que el
  mostrador copia a mano.
- **Sin frontend, sin E2E, sin QA manual del §6.**
- **Sin test de concurrencia.** Las dos reglas del lock están probadas con dobles, no con dos
  hilos contra MySQL: no hay un `CoberturaConcurrenteIT` como sí lo tienen turnos, disponibilidad
  y cierre. Es la deuda más importante que la etapa deja abierta, porque el lock es su pieza más
  delicada.
