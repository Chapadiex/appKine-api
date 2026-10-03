# AKINE — Plan incremental de implementación

**Versión:** 2.0 FINAL — guía integral de implementación  
**Fecha de análisis:** 14/08/2026  
**Estado:** FINAL / LISTO PARA EJECUCIÓN DESDE AKINE-00.01  
**Regla de uso:** AKINE todavía no posee repositorios ni código. La Etapa AKINE-00.01 creará los proyectos backend y frontend con la arquitectura aprobada y registrará sus primeras rutas, contratos, migraciones y comandos. Las etapas posteriores se ejecutarán en orden sobre esos baselines reales y coordinados.

**Fuente funcional primaria del roadmap:** `C:\Users\santo\Desktop\AKINE\AKINE_Requerimientos_Integrados_Parte_1_M01-M13.md`. Aunque su nombre indica M01–M13, el contenido inspeccionado integra M01–M29, la visión consolidada de Servicios/Clases y las reglas transversales de las secciones 30–45. Toda etapa, dependencia y trazabilidad funcional de este plan deriva de ese contenido. Los demás documentos se utilizan únicamente como antecedentes para detectar contradicciones; no incorporan alcance adicional por sí solos.

**Decisión documental confirmada:** no existe una “Parte 2”. El archivo integrado indicado arriba constituye la especificación funcional completa y canónica para este plan. No se requiere ningún documento adicional para completar el alcance funcional M01–M29.

**Decisión de proyecto confirmada:** AKINE se construirá como producto greenfield. No existe una implementación previa que deba conservarse, migrarse o auditarse. Las referencias históricas se utilizarán únicamente para comprender el negocio; no definen estructura de código ni arquitectura vigente.

**Decisión tecnológica confirmada:** frontend Angular 21; backend Java 21 con Spring Boot 4.1.x; base de datos MySQL 8.4 LTS; API REST documentada con OpenAPI 3; autenticación JWT con refresh tokens; contenedores Docker; CI/CD con GitHub Actions y SonarQube; E2E con Playwright; observabilidad con Prometheus y OpenTelemetry.

**Decisión arquitectónica confirmada:** AKINE se implementará como monolito modular. El backend será un único desplegable Spring Boot, dividido en módulos de dominio con APIs internas explícitas, ownership de datos y dependencias controladas. El frontend Angular será una aplicación desplegable separada que consumirá exclusivamente la API REST. No se introducirán microservicios en el alcance inicial.

**Decisión de repositorios confirmada:** AKINE utilizará dos repositorios Git independientes: uno para backend y otro para frontend. Cada repositorio tendrá su propio versionado, pipeline, pruebas y artefactos. La coordinación se realizará mediante el contrato OpenAPI versionado y una matriz explícita de compatibilidad; ninguna etapa asumirá commits atómicos entre ambos repositorios.

**Decisión de contrato API confirmada:** el repositorio backend es propietario de la especificación OpenAPI 3 canónica. Su pipeline debe validarla y publicarla como artefacto versionado. El repositorio frontend debe generar y fijar un cliente TypeScript tipado desde una versión explícita del contrato; quedan prohibidos los DTO manuales duplicados y el consumo de contratos no publicados.

**Decisión de alcance y continuidad confirmada:** el MVP inicial entregará el circuito clínico, administrativo y económico M01–M27. Su aceptación no finaliza el roadmap: después del cierre productivo y la estabilización definida, deberá comenzar obligatoriamente la segunda entrega M28–M29 para clases, inscripciones, asistencias, pases y abonos. La segunda entrega no podrá eliminarse ni quedar como backlog opcional sin una nueva decisión formal de producto.

> El estado inicial está confirmado: no existen repositorios, manifiestos de build, fuentes, migraciones, contratos API ni tests de AKINE. En consecuencia, las capacidades funcionales se clasifican como **NO IMPLEMENTADAS**. El plan debe definir su construcción incremental sin inventar archivos antes de que AKINE-00.01 establezca las convenciones y estructuras reales de ambos proyectos.

# 1. Resumen ejecutivo

AKINE está definido por la especificación integrada como un SaaS multi-tenant para centros de kinesiología, fisioterapia, rehabilitación y actividades de salud/bienestar. El roadmap cubre la totalidad de M01–M29, sus RF/RNF, reglas maestras, casos borde y requisitos transversales. Distingue flujos clínicos y no clínicos y separa explícitamente reserva, atención, deuda, cobro y caja.

La construcción recomendada sigue una ruta crítica de dominio:

`Tenant → Consultorio → Membership/Permisos → Espacios/Disponibilidad → Persona/Paciente → Cobertura/Convenio → Historia Clínica → Caso → Plan → Turno → Check-in → Sesión → Obligación → Cobro → Caja → Presentación/Reportes`.

En paralelo, una vez estabilizados identidad, ofertas, agenda y economía:

`Persona → Oferta grupal → Clase → Inscripción → Asistencia → Pase/Abono o cargo → Cobro/Caja/Reportes`.

El primer flujo constituye el MVP inicial M01–M27. El segundo constituye una entrega posterior obligatoria M28–M29. El hito de MVP habilita el inicio de esa segunda construcción; no representa el cierre del producto ni del plan.

El plan evita tres errores estructurales: construir módulos consumidores antes de sus cimientos, modelar `Paciente → Sesión` como atajo suficiente y reducir economía a `Sesión → Pago`. Primero crea un baseline técnico reproducible, fija convenciones y verifica el esqueleto del producto. Solo entonces habilita las etapas funcionales.

# 2. Arquitectura detectada

## 2.1 Arquitectura inicial realmente verificable

AKINE no tiene arquitectura implementada porque es un proyecto greenfield. Todavía no existen capas, módulos, patrones, DTOs, endpoints, repositorios, guards, migraciones, componentes, rutas, pruebas ni despliegue. La arquitectura aprobada deberá crearse y documentarse en AKINE-00.01, evitando que cada etapa adopte convenciones diferentes.

## 2.2 Arquitectura objetivo aprobada

- SaaS multi-tenant con Organización/Empresa como tenant y uno o más Consultorios.
- Monolito modular backend como único desplegable, organizado por capacidades de negocio y no por una única estructura técnica global.
- Cada módulo es propietario de su modelo, servicios y tablas; otros módulos acceden mediante contratos internos explícitos y no mediante repositorios o tablas ajenas.
- Dependencias entre módulos unidireccionales, verificadas con pruebas de arquitectura; los ciclos están prohibidos.
- Transacciones locales dentro del monolito para invariantes fuertes; eventos internos posteriores al commit para efectos derivados cuando corresponda.
- Frontend Angular como SPA desplegable separada y organizada por dominios/rutas funcionales compatibles con los módulos backend.
- Backend como autoridad de permisos, tenant, estados y reglas.
- API REST documentada con OpenAPI 3 y autenticación basada en JWT/refresh tokens, decisión confirmada para el greenfield.
- OpenAPI canónico propiedad del backend, publicado como artefacto versionado; cliente TypeScript del frontend generado desde una versión fijada.
- Separación clara entre identidad global, memberships contextuales y habilitación profesional.
- Dominio clínico separado de agenda y economía.
- Bajas lógicas, vigencias, snapshots históricos y auditoría.
- Eventos de dominio sugeridos para desacoplar efectos; no implican microservicios.
- Evolución incremental hacia `EventoAgenda` y `PrestacionRealizada`, sin reemplazar anticipadamente Turno/Sesión.

## 2.3 Evidencia histórica

`C:\Users\santo\.codex\.chatgpt-projects\g-p-693731f0f05c81919b990f8a5f1c435e\sources\DiagramaDeClases.pdf` (2019) muestra un modelo orientado a `Paciente`, `Turno`, `Atencion`, `HistoriaClinica`, `Cobro`, `ObraSocial`, `Convenio`, `Profesional`, `Consultorio` y catálogos. No contiene Organización/Tenant, Membership, Caso Clínico, Plan de Tratamiento, Obligación Económica, Caja ni los modelos de clases/pases. Debe tratarse como antecedente de negocio, no como esquema vigente.

`C:\Users\santo\.codex\.chatgpt-projects\g-p-693731f0f05c81919b990f8a5f1c435e\sources\DTE.pdf` (2019) documenta una máquina de estados antigua de Turno (`Disponible`, `Asignado`, `Confirmado`, `Asistido`, `EnEspera`, `ConvocandoAConsultorio`, `EnAtencion`, `Finalizado`, `Anulado`, `NoAsignado`). La especificación actual exige separar estados de Turno, check-in y Sesión; no debe copiarse esa máquina sin una decisión explícita.

## 2.4 Convenciones greenfield y rutas planificadas

AKINE-00.01 debe crear dos repositorios lógicos —los nombres remotos pueden ajustarse al crear Git, pero las responsabilidades no—:

### Repositorio backend

- Build: Maven Wrapper (`mvnw`, `mvnw.cmd`, `pom.xml`) sobre Java 21 y Spring Boot 4.1.x.
- Código: `src/main/java/com/akine/`.
- Configuración: `src/main/resources/`, con perfiles sin secretos versionados.
- Migraciones: `src/main/resources/db/migration/` mediante Flyway y MySQL 8.4 LTS.
- OpenAPI canónico: `openapi/akine-api.yaml`, validado contra la implementación y publicado por CI con versión SemVer.
- Pruebas: `src/test/java/com/akine/`, JUnit, Spring Boot Test, Testcontainers/MySQL y ArchUnit.
- Operación local: `compose.yaml` para MySQL y dependencias locales; observabilidad y servicios externos se incorporan por perfiles.
- Documentación transversal/ADRs: `docs/` y `docs/adr/`.

El package raíz se divide por módulo de negocio. Cada módulo puede contener `api`, `application`, `domain` e `infrastructure`, pero queda prohibido crear una capa global que permita acceso irrestricto a repositorios ajenos.

| Módulo backend | Responsabilidad principal | Módulos funcionales |
|---|---|---|
| `platform` | configuración, errores, tenancy técnico, auditoría base, observabilidad | transversal |
| `identity` | cuentas, autenticación, tokens y sesiones | M02 |
| `organization` | organización, suscripción, consultorio y memberships | M01, M03, M05 |
| `resource` | espacios, disponibilidad y catálogos operativos | M04–M06 |
| `person` | Persona, Paciente, coberturas y adjuntos administrativos | M07–M08, M15, M25 |
| `contracting` | financiadores, planes, convenios, aranceles y autorizaciones | M15–M17 |
| `clinical` | HC, timeline, Caso, Plan, Sesión y evolución | M09–M14 |
| `scheduling` | slots, Turno, series, check-in y agenda | M12–M13 |
| `billing` | obligaciones, anticipos, cobros, caja, claims y egresos | M18–M22 |
| `offering` | Servicio, Oferta y habilitaciones | M27 |
| `activity` | clases, inscripciones, participación, pases y abonos | M28–M29 |
| `notification` | outbox, entrega, resultados, reintentos y templates | M26 |
| `reporting` | consultas, proyecciones y exports | M23 |

Cada tabla tiene un módulo propietario. Los demás módulos acceden por servicios/puertos internos o eventos posteriores al commit, nunca importando repositorios ni escribiendo tablas ajenas. Toda tabla de negocio incluye `organization_id`; incluye `consultorio_id` cuando el hecho pertenece a una sede. Índices y uniques deben incorporar el alcance tenant adecuado. Importes usan `DECIMAL`/`BigDecimal`; fechas se persisten como instantes UTC y las reglas locales usan una zona IANA explícita.

### Repositorio frontend

- Package manager: npm con `package-lock.json`; comandos canónicos mediante scripts de `package.json`.
- Proyecto Angular: `angular.json`, `src/main.ts` y `src/app/`.
- Infraestructura transversal: `src/app/core/` para autenticación, contexto, guards, interceptores, configuración y errores.
- Reutilización visual: `src/app/shared/`, sin reglas de dominio.
- Funcionalidad: `src/app/features/<dominio>/`, alineada con los módulos backend.
- Cliente generado: `src/app/api/generated/`; no se edita manualmente y se regenera desde una versión fijada de `akine-api.yaml`.
- Pruebas: junto a componentes/servicios y E2E Playwright en `e2e/`.
- Documentación/ADRs específicos de UI: `docs/`.

### Reglas entre repositorios

1. Backend publica OpenAPI versionado; frontend declara la versión consumida.
2. Un cambio aditivo puede publicarse antes del consumidor. Un cambio incompatible exige nueva versión mayor y ventana de compatibilidad.
3. Cada etapa que cambie API actualiza backend, publica contrato, regenera cliente frontend y ejecuta contract/E2E sin asumir un commit atómico.
4. `main` de ambos repositorios permanece desplegable; trabajo incompleto se protege con feature flag/configuración, no con contratos rotos.
5. Los pipelines producen imágenes Docker inmutables, SBOM, resultados Sonar y evidencia de pruebas.

# 3. Stack tecnológico detectado

| Capa | Objetivo documental | Estado verificable |
|---|---|---|
| Frontend | Angular 21, responsive, WCAG 2.1 AA | APROBADO |
| Backend | Java 21 + Spring Boot 4.1.x, capas separadas | APROBADO; fijar última revisión estable 4.1.x en AKINE-00.01 |
| Base de datos | MySQL 8.4 LTS | APROBADO |
| API | REST, OpenAPI 3, JWT + refresh | APROBADO |
| Tests | JUnit/Spring Boot Test/Testcontainers/ArchUnit, Angular y E2E Playwright | APROBADO; cobertura ≥80 % nuevo y ≥90 % módulos críticos |
| CI/CD | GitHub Actions, SonarQube | APROBADO |
| Runtime | Docker | APROBADO |
| Observabilidad | Logging JSON, Prometheus, OpenTelemetry | APROBADO |

El stack fue confirmado expresamente para el proyecto greenfield y reemplaza el target histórico de `sources\AKINE.pdf`. AKINE-00.01 debe fijar versiones exactas soportadas, generar lockfiles y dejar builds reproducibles.

# 4. Estado actual del proyecto

## 4.1 Inventario inspeccionado

- `C:\Users\santo\Desktop\AKINE\AKN_Estudio_inicial.pdf` — estudio inicial, 8 páginas.
- `C:\Users\santo\Desktop\AKINE\AkinePN.docx` — análisis funcional y alcance; existe PDF equivalente en el espejo, 11 páginas.
- `C:\Users\santo\Desktop\AKINE\plan_sesiones.txt` — rediseño clínico/UX de sesiones, 1.297 líneas.
- `C:\Users\santo\Desktop\AKINE\AKINE_info.txt` — definición funcional histórica, 164 líneas.
- `C:\Users\santo\Desktop\AKINE\AKINE_Requerimientos_Integrados_Parte_1_M01-M13.md` — pese al nombre, contiene M01–M29 y reglas transversales, 20.421 líneas.
- Fuentes adicionales de solo lectura del espejo: `C:\Users\santo\.codex\.chatgpt-projects\g-p-693731f0f05c81919b990f8a5f1c435e\sources\AKINE.pdf`, `C:\Users\santo\.codex\.chatgpt-projects\g-p-693731f0f05c81919b990f8a5f1c435e\sources\AkinePN.pdf`, `C:\Users\santo\.codex\.chatgpt-projects\g-p-693731f0f05c81919b990f8a5f1c435e\sources\DiagramaDeClases.pdf`, `C:\Users\santo\.codex\.chatgpt-projects\g-p-693731f0f05c81919b990f8a5f1c435e\sources\DTE.pdf`, `C:\Users\santo\.codex\.chatgpt-projects\g-p-693731f0f05c81919b990f8a5f1c435e\sources\Adicional.txt`.

## 4.2 Condiciones previas para crear el proyecto

Las decisiones necesarias para comenzar quedaron cerradas: fuente funcional M01–M29, condición greenfield, dos repositorios, monolito modular, stack, contrato OpenAPI, DP-01–DP-09, alcance del MVP y continuidad M28–M29. No existe un bloqueo documental previo. Cuando comience el desarrollo, AKINE-00.01 deberá:

- crear ambos repositorios Git y sus protecciones de rama;
- crear backend, frontend, primera migración y pipelines mínimos;
- registrar comandos reproducibles de build, test y ejecución local;
- usar exclusivamente datos sintéticos;
- actualizar las rutas planificadas por las rutas efectivamente creadas.

# 5. Matriz requerimientos vs implementación

El proyecto es greenfield confirmado. “Encontrado” se refiere únicamente a evidencia documental; ninguna capacidad se marca como implementada.

| Área | Requerido | Encontrado | Estado | Evidencia | Etapas planificadas |
|---|---|---|---|---|---|
| SaaS/tenancy | Organización, suscripción, límites, contexto | Especificación | NO IMPLEMENTADO | M01 | 01.01, 01.03, 02.01 |
| Usuarios/auth | Cuenta, login, reset, bloqueo | Especificación + antecedente | NO IMPLEMENTADO | M02; `AKINE_info.txt` | 01.02 |
| Roles/permisos | Roles contextuales y matriz granular | Sección transversal | NO IMPLEMENTADO | §32 | 01.03; hardening 07.07/09.02 |
| Consultorios | Alta, configuración, selección, baja | Especificación | NO IMPLEMENTADO | M03 | 02.01 |
| Memberships/personal | Invitación, vínculo, baja y habilitación | Especificación | NO IMPLEMENTADO | M05 | 01.03, 02.03–02.04 |
| Espacios/boxes | CRUD, capacidad, asignación y disponibilidad | UML legado parcial | NO IMPLEMENTADO | M04 | 02.02, 02.07, 05.01–05.02, 06.04 |
| Horarios/disponibilidad | Semanal, excepciones, feriados y slots | Especificación | NO IMPLEMENTADO | M05/M12 | 02.04, 05.01 |
| Catálogos | Especialidades, prácticas y nomencladores | UML histórico | NO IMPLEMENTADO | M06 | 02.05 |
| Servicios/ofertas | Servicio global y Oferta contextual | Especificación integrada | NO IMPLEMENTADO | M27 | 02.06–02.07 |
| Personas/pacientes | Identidad, deduplicación, 360 y baja | Especificación + UML | NO IMPLEMENTADO | M07 | 03.01–03.02 |
| Financiadores/planes | Catálogo, estado y vigencias | Especificación | NO IMPLEMENTADO | M15 | 03.03 |
| Coberturas | Afiliación, Particular, vigencias e histórico | Especificación | NO IMPLEMENTADO | M08 | 03.04 |
| Convenios/aranceles | Alcance, prioridad, vigencias y snapshots | Especificación | NO IMPLEMENTADO | M16 | 03.05 |
| Órdenes/autorizaciones | Documentos, estado, vigencia y consumo | Especificación | NO IMPLEMENTADO | M17 | 03.06, 04.05 |
| Historia Clínica | Alcance organizacional, resumen y timeline | UML simplificado | NO IMPLEMENTADO | M09; DP-03 | 04.01–04.02 |
| Casos Clínicos | Problema terapéutico, estado y numeración | Solo especificación | NO IMPLEMENTADO | M10 | 04.03 |
| Plan de Tratamiento | Objetivos, frecuencia, cantidades y progreso | Solo especificación | NO IMPLEMENTADO | M11 | 04.04–04.05 |
| Turnos/agenda | Slots, reserva, recursos, series y estados | UML/DTE históricos | NO IMPLEMENTADO | M12; DP-04/05 | 05.01–05.03 |
| Recepción/check-in | Llegada, validación, espera y ausencia | Proceso histórico | NO IMPLEMENTADO | M13 | 05.04; extensión 08.03 |
| Sesiones | Atención, evaluación, examen, tratamiento y cierre | Diseño detallado | NO IMPLEMENTADO | M14 | 06.01–06.06; extensión 08.05 |
| Obligaciones | Deuda paciente/financiador/mixta | Solo especificación | NO IMPLEMENTADO | M18 | 07.01; extensión 08.03/06/08 |
| Cobros/anticipos | Medios, imputación, saldo a favor y comprobante | UML acoplado | NO IMPLEMENTADO | M19; DP-06 | 07.02; extensión 08.03/06/07 |
| Caja | Apertura, movimientos, cierre y reintegros | Alcance histórico | NO IMPLEMENTADO | M20 | 07.03; extensión 08.06–08.08 |
| Presentaciones | Prestado→presentado→facturado→cobrado | Proceso histórico | NO IMPLEMENTADO | M21 | 07.04, 08.09 |
| Egresos/pagos | Egresos y pagos a profesional | Especificación | NO IMPLEMENTADO | M22 | 07.05, 08.09 |
| Clases grupales | Clase, cupos, inscripción y asistencia | Especificación integrada | NO IMPLEMENTADO | M28 | 08.01–08.05 |
| Pases/abonos | Producto, compra, créditos, ciclos y vencimiento | Especificación integrada | NO IMPLEMENTADO | M29 | 08.06–08.08 |
| Reportes | Operativos, clínicos y económicos | Especificación | NO IMPLEMENTADO | M23 | 07.06, 09.01 |
| Auditoría | Eventos, accesos, enmiendas y anulaciones | Especificación transversal | NO IMPLEMENTADO | M24/§42 | 01.03 y todas las etapas; 07.07/09.02 |
| Adjuntos | Metadata, acceso, storage y baja lógica | Especificación | NO IMPLEMENTADO | M25 | 03.02, 03.06, 04.02 |
| Notificaciones | Entrega, resultado, retry y disparadores | Especificación | NO IMPLEMENTADO | M26 | 01.02 y etapas disparadoras |
| Infra/observabilidad | Docker, CI, Sonar, métricas y tracing | Stack aprobado | NO IMPLEMENTADO | DP-09 | 00.01–00.02, 07.08, 09.03 |
| Tests/calidad | Unit, integración, contrato, frontend, E2E y carga | Reglas transversales | NO IMPLEMENTADO | §§35, 40–41 | Todas; gates 07.09/09.04 |

# 6. Deuda técnica detectada

La siguiente es deuda documental/modelística. No existe deuda de código previa porque AKINE todavía no fue creado.

1. El nombre del archivo de requisitos contradice su contenido: anuncia M01–M13, pero contiene M01–M29 y las secciones 30–45.
2. El UML de 2019 mezcla Turno, Atención y Cobro y carece de entidades maestras actuales.
3. La máquina de estados histórica de Turno absorbe recepción y ejecución clínica.
4. El alcance histórico usa “sesión/consulta/atención” de forma intercambiable; la especificación moderna los separa.
5. Los RF detallados repiten flujos genéricos; varios contratos concretos siguen sin decisión.
6. El stack y los SLO figuran como objetivos, no como decisiones verificadas.
7. “Seguimiento de familia”, alquiler de consultorios, domicilio y derivación externa aparecen en fuentes históricas, pero no están trazados como módulos core actuales.

# 7. Contradicciones o decisiones pendientes

## DP-01 — Registro profesional y creación de consultorio

**Estado:** RESUELTA.  
**Fuente A:** `AKINE_info.txt` y `AkinePN.docx` vinculan registro profesional con creación automática del primer consultorio.  
**Fuente B:** M01/M03 separan Organización, suscripción, Consultorio y onboarding.  
**Implementación actual:** no implementado; proyecto greenfield.  
**Impacto:** transacción de alta, rollback, plan SaaS y ownership.  
**Decisión:** el registro del primer propietario ejecutará un onboarding compuesto, transaccional e idempotente que creará Cuenta, Organización, primer Consultorio y Membership de propietario. Las cuatro entidades conservarán identidad, responsabilidades y ciclos de vida separados. Un reintento debe devolver el resultado previamente creado y un fallo parcial debe revertir la operación completa o quedar compensado de forma verificable.

## DP-02 — Login con selección previa de rol

**Estado:** RESUELTA.  
**Fuente A:** `AKINE_info.txt` propone seleccionar Paciente/Profesional antes del login.  
**Fuente B:** M02 establece una identidad con roles distintos por contexto.  
**Implementación actual:** no implementado; proyecto greenfield.  
**Impacto:** seguridad, UX y autorización.  
**Decisión:** autenticar una identidad única sin selección previa de rol. Después del login, el usuario seleccionará una Organización y un Consultorio entre sus contextos autorizados. El backend calculará los permisos efectivos a partir de memberships y roles vigentes en ese contexto; la interfaz no podrá elevar privilegios. El cambio de contexto autorizado no requerirá un nuevo login, pero sí emitirá o renovará un access token acotado al contexto seleccionado y quedará auditado.

## DP-03 — Alcance de Historia Clínica

**Estado:** RESUELTA.  
**Fuente A:** documentos históricos hablan de una HC única por paciente.  
**Fuente B:** multi-tenancy exige aislamiento y permisos contextuales; M09 no define inequívocamente si la HC es global, por organización o por consultorio.  
**Impacto:** privacidad, duplicación e intercambio clínico.  
**Decisión:** la Historia Clínica pertenece a la Organización y constituye el contexto longitudinal del paciente dentro de ese tenant. Puede consultarse desde distintos Consultorios de la misma Organización únicamente por actores con membership vigente, permiso clínico y relación asistencial o justificación autorizada. Nunca se comparte automáticamente entre Organizaciones diferentes. Todo acceso clínico sensible y toda excepción quedan auditados.

## DP-04 — Turnos seriados y primera ausencia

**Estado:** RESUELTA.  
**Fuente A:** `AKINE_info.txt` genera sesiones/turnos futuros y elimina la serie si falta a la primera.  
**Fuente B:** M11/M12 separan plan y turno, y prohíben borrado físico.  
**Impacto:** recurrencia, auditoría, cupos y notificaciones.  
**Decisión:** los turnos recurrentes se vincularán mediante un modelo explícito de serie/regla, pero cada Turno conservará identidad, estado e historial propios. Una ausencia al primer turno se registrará como `AUSENTE` y nunca eliminará la serie. Un actor autorizado podrá cancelar los turnos futuros pendientes mediante confirmación explícita, motivo obligatorio y auditoría; los turnos pasados o ya ejecutados permanecerán inalterables.

## DP-05 — Estados de Turno vs check-in vs Sesión

**Estado:** RESUELTA.  
**Fuente A:** `DTE.pdf` concentra estados desde disponibilidad hasta atención finalizada.  
**Fuente B:** M12, M13 y M14 exigen máquinas separadas.  
**Impacto:** migración de estados y UI de agenda.  
**Decisión:** Turno, Check-in/Recepción y Sesión tendrán máquinas de estado independientes y controladas por backend. Turno representa exclusivamente la reserva; Check-in/Recepción representa llegada, validación administrativa, espera y llamado; Sesión representa la atención clínica real. Se coordinarán mediante comandos y eventos explícitos, pero ninguna transición administrativa probará por sí sola que una prestación ocurrió. Cada transición registrará actor, fecha, estado anterior, estado nuevo y motivo cuando corresponda.

## DP-06 — Cobro antes de atención

**Estado:** RESUELTA.  
**Fuente A:** flujo histórico cobra particular antes de enviarlo a espera.  
**Fuente B:** RN-M14-005 indica que el cierre clínico no depende del cobro; M18 genera obligación al concretar prestación.  
**Impacto:** prepago, devoluciones, no-show y caja.  
**Decisión:** el prepago será una política configurable por Consultorio y Oferta, nunca una condición global del dominio clínico. Una Sesión podrá iniciarse, documentarse y cerrarse con independencia del pago. El dinero recibido antes de existir una obligación se registrará como anticipo/saldo a favor mediante un ledger trazable y movimiento real de Caja; posteriormente podrá imputarse a una obligación compatible. Cancelaciones, no-show y devoluciones aplicarán reglas explícitas, sin borrar el movimiento original.

## DP-07 — Alcance MVP vs extensión M27–M29

**Estado:** RESUELTA.  
**Fuente A:** `AkinePN.docx` excluye consultorios externos y limita el MVP.  
**Fuente B:** la especificación integrada incorpora servicios, clases, pases y abonos como evolución.  
**Impacto:** secuencia y tiempo de entrega.  
**Decisión:** el MVP inicial incluirá M01–M27 y deberá entregar el circuito clínico, administrativo y económico completo, incluyendo la base de Servicio/Oferta necesaria para operar. M28–M29 conformarán una segunda entrega obligatoria. Esta comenzará después de que el MVP cumpla su Definition of Done, sea desplegado y complete una ventana de estabilización con bloqueantes resueltos. El equipo no podrá cerrar el roadmap en el MVP ni convertir la segunda entrega en opcional sin una nueva decisión formal de producto.

## DP-08 — Requisitos clínicos y legales

**Estado:** RESUELTA.  
**Fuente A:** `plan_sesiones.txt` y `Adicional.txt` proponen campos, orden médica y autorizaciones.  
**Fuente B:** no se adjunta validación normativa vigente ni política por financiador.  
**Impacto:** datos sensibles, obligatoriedad y facturación.  
**Decisión:** los requisitos de orden médica, autorización, documentación y campos obligatorios se configurarán por Financiador, Plan, Convenio y Prestación, con vigencia y trazabilidad. Ningún ejemplo de las fuentes históricas será obligatorio global sin una regla confirmada. Antes de liberar el MVP, un responsable clínico y un responsable legal deberán aprobar el conjunto de reglas, consentimientos, retención, privacidad y documentación aplicable; la evidencia de aprobación formará parte del gate de release.

## DP-09 — Versiones técnicas y SLO

**Estado:** RESUELTA.  
**Fuente histórica:** `C:\Users\santo\.codex\.chatgpt-projects\g-p-693731f0f05c81919b990f8a5f1c435e\sources\AKINE.pdf` proponía Angular 18, Java 17 y PostgreSQL 15.  
**Decisión confirmada:** Angular 21, Java 21 con Spring Boot 4.1.x, MySQL 8.4 LTS, REST/OpenAPI 3, JWT con refresh, Docker, GitHub Actions, SonarQube, Playwright, Prometheus y OpenTelemetry.  
**Decisión SLO:** disponibilidad mensual ≥99,9 %; API interactiva p95 ≤300 ms y p99 ≤800 ms, excluyendo exportaciones y proveedores externos; 500 usuarios concurrentes con tasa de error técnico <1 %; LCP frontend ≤2,5 s en p75; cobertura de código nuevo ≥80 % y ≥90 % para módulos clínicos, seguridad y economía; cero vulnerabilidades o problemas bloqueantes/críticos de SonarQube en código nuevo; duplicación de código nuevo ≤3 %.  
**Impacto:** reproducibilidad, dimensionamiento y criterios de aceptación no funcionales.  
**Aplicación:** AKINE-00.01 fija versiones reproducibles; AKINE-00.02 crea medición base; cada etapa protege el presupuesto de calidad y AKINE-09.03 valida los SLO con carga representativa antes de release.

## DP-10 — Recorte de alcance por fecha objetivo (Paquete B)

**Estado:** RESUELTA el 31/08/2026 por el dueño del producto.
**Motivo.** Al 31/08/2026 hay 14 de 56 etapas cerradas y **ninguna pertenece al circuito
operativo**: todo lo construido es configuración. La fecha objetivo se fijó en **15/09/2026**,
que a la velocidad medida son ~11,5 días efectivos. Las 42 etapas restantes no entran.

**Decisión.** Se ejecutan **nueve etapas**: 05.01, 05.02, 05.03, 04.01 reducida, 06.01, 06.02,
06.05, 07.01 y 07.02. Entregan la vertical **paciente → turno → sesión → obligación → cobro**,
que cubre las tres patas comprometidas —clínica, administrativa y económica—.

**Fuera de alcance, diferido con modelo intacto.** M15 financiadores (03.03), M16 convenios y
aranceles (03.05), M17 órdenes y autorizaciones (03.06, 04.05), M21 presentaciones (07.04),
M22 egresos (07.05), M13 check-in (05.04), M20 caja (07.03), M14 examen completo, tratamientos
y enmiendas (06.03, 06.04, 06.06), M07 Paciente 360 (03.02), M28–M29 (08.01–08.09) y F10
(09.01–09.04). **DP-07 no se deroga:** M28–M29 siguen siendo segunda entrega obligatoria.

**Regla que hace defendible el recorte: se corta alcance, no modelo.** `Cobertura` se implementa
solo con `PARTICULAR` pero el tipo y su vigencia quedan; `Obligacion` guarda el importe de la
Oferta pero conserva el campo de snapshot arancelario; la Sesión conserva la costura hacia
autorizaciones aunque hoy devuelva vacío. Toda capacidad diferida debe quedar representada en el
modelo, no comentada en el código.

**Recableado de dependencias.** El recorte rompe cadenas declaradas en §14 y se reemplazan así:

| Etapa | Dependía de | Pasa a depender de | Consecuencia |
|---|---|---|---|
| 05.01 | 02.04, 02.07, **03.04** | 02.04, 02.07 | Los slots no filtran por cobertura; el precio sale de la Oferta. |
| 06.01 | **05.04**, **04.05** | 05.03, 04.01 | La Sesión arranca desde el Turno confirmado, sin paso de recepción. |
| 07.01 | 06.05, **03.05**, **04.05** | 06.05 | La obligación es siempre del paciente, con el importe de la Oferta. |
| 04.01 | 03.02 | 03.01 | La HC cuelga de la Persona con perfil de paciente activo. |

**Riesgo aceptado.** Sin 05.04 no hay evidencia administrativa de llegada, y sin 07.03 el cobro
no impacta una caja real. Las dos son etapas de una sesión cada una cuando se retomen; ninguna
cambia el modelo que se construye ahora.

**Versiones de migración reservadas.** El trabajo en paralelo ya rompió Flyway una vez —02.07 y
03.01 nacieron las dos como `V26`, la versión duplicada fue rechazada y la aplicación no
arrancaba—. Para el Paquete B las versiones se **reservan antes de escribir código**, las use o
no la etapa. `V26` sigue vacía a propósito; Flyway no exige versiones contiguas.

| Etapa | Versión | Carril |
|---|---|---|
| 05.01 Motor de slots | `V29` | principal |
| 05.02 Reserva de Turno | `V30` | principal |
| 05.03 Ciclo y estados de Turno | `V31` | principal |
| 04.01 Historia Clínica reducida | `V32` | **paralelo** |
| 06.01 Sesión | `V33` | principal |
| 06.02 Evaluación base | `V34` | principal |
| 06.05 Cierre idempotente | `V35` | principal |
| 07.01 Obligación económica | `V36` | principal |
| 07.02 Cobro | `V37` | principal |

**Reglas de paralelismo.** Un worktree por carril, nunca dos sesiones en un árbol —además de
Flyway, dos builds de Maven sobre un mismo `target/` produjeron 368 `ClassNotFoundException`
sobre clases presentes en disco—. **Un solo carril escribe el OpenAPI a la vez**: el contrato es
propiedad del backend y no admite dos escritores. El carril de tokenización del design system
toca `src/design-system.css` y los siete CSS de feature, y ningún `.ts`.

**Refinamiento visual.** No entra en la ventana de septiembre. Queda como tramo propio, y la
tokenización del design system —hoy sin variables CSS— debe ejecutarse antes del pulido.

# 8. Modelo funcional consolidado

## 8.1 Núcleo organizacional

- Usuario/Identidad global.
- Organización/Tenant y suscripción SaaS.
- Membership de organización y de consultorio, con rol, vigencia y permisos.
- Consultorio, configuración, zona horaria, espacios y recursos.
- Profesional/Administrativo como perfiles o vínculos, no necesariamente identidades duplicadas.

## 8.2 Núcleo clínico

- Persona puede existir sin PerfilPaciente.
- PerfilPaciente habilita contexto administrativo clínico.
- Historia Clínica mantiene contexto longitudinal dentro del alcance de privacidad confirmado.
- Caso Clínico representa un problema concreto y posee estado propio.
- Plan de Tratamiento representa intención, objetivos, frecuencia y cantidades.
- Turno reserva agenda; no demuestra atención.
- Sesión/Atención registra lo ocurrido y se numera dentro del Caso.
- Evaluación, examen, tratamientos, resultado y enmiendas deben ser estructurados y auditables.

## 8.3 Núcleo económico

- Cobertura del paciente no equivale a convenio del consultorio.
- La condición aplicada debe guardar snapshot de convenio/arancel.
- Una prestación puede generar una o varias Obligaciones, con responsable paciente/financiador/mixto.
- Cobro recibe dinero e imputa obligaciones.
- Caja refleja movimientos monetarios confirmados.
- Presentación, factura y pago del financiador son estados distintos.

## 8.4 Servicios y actividades

- Servicio global ≠ OfertaServicioConsultorio.
- Turno individual ≠ ClaseProgramada.
- Inscripción/asistencia no clínica ≠ Sesión clínica.
- Clase clínica grupal puede producir atenciones individuales por participante/caso.
- Suscripción SaaS ≠ Pase/Paquete/Abono de servicio.

# 9. Mapa de dependencias

```text
Identidad/Usuario
├── Organización/Tenant ── Suscripción SaaS
│   └── Membership/Permisos
│       └── Consultorio
│           ├── Espacios/Capacidad
│           ├── Colaboradores/Habilitaciones
│           ├── Disponibilidad/Excepciones/Feriados
│           ├── Catálogos ── Servicio ── OfertaServicioConsultorio
│           ├── Convenios/Aranceles
│           └── Persona
│               ├── Consumo no clínico
│               │   └── Clase ── Inscripción ── Asistencia ── Pase/Abono
│               └── PerfilPaciente
│                   ├── Coberturas/Autorizaciones
│                   └── Historia Clínica
│                       └── Caso Clínico
│                           ├── Plan de Tratamiento
│                           ├── Turno ── Check-in/Espera
│                           └── Sesión/Atención
│                               ├── Evaluación/Examen
│                               ├── Tratamientos/Evolución
│                               └── Obligación Económica
│                                   ├── Deuda paciente
│                                   ├── Cuenta financiador
│                                   └── Cobro ── Movimiento de Caja
└── Auditoría/Notificaciones/Adjuntos/Observabilidad (transversales)
```

# 10. Estrategia incremental

1. Abrir la compuerta de evidencia: incorporar repo, inventariar y ejecutar baseline.
2. Estabilizar contratos transversales antes de ampliar dominio.
3. Entregar verticales pequeñas con migración compatible, API, UI, permisos y tests en la misma etapa.
4. Mantener Turno/Sesión existentes mientras se incorporan Oferta, Clase y Prestación superior.
5. Aplicar patrón expandir–migrar–contraer para datos; no ejecutar drops destructivos en etapas funcionales.
6. Usar eventos/outbox solo si la arquitectura real lo admite; no forzar microservicios.
7. Actualizar este documento al terminar cada etapa con evidencia y decisiones.

## 10.1 Forma de uso del plan

Este documento es la especificación operativa para construir AKINE por incrementos. Cada etapa debe interpretarse como una unidad de trabajo autónoma y normativa: sus apartados de objetivo, alcance técnico, reglas, seguridad, validaciones, casos borde, pruebas y aceptación definen todo lo que debe realizarse. No se necesita texto adicional para iniciar una etapa.

El agente ejecutor debe trabajar sobre una sola etapa por vez, en el orden definido por las dependencias. Una etapa solo puede comenzar cuando sus predecesoras estén aceptadas o cuando exista una decisión técnica documentada que permita desacoplarla. Si falta evidencia del repositorio, una definición de producto o una decisión con impacto irreversible, la etapa debe marcarse como bloqueada y registrar exactamente la información faltante; no se completará mediante supuestos.

Los apartados Backend, Base de datos, API y Frontend son dimensiones del mismo incremento. Cuando una dimensión no aplique, debe quedar documentado el motivo. Cuando aplique, no puede diferirse silenciosamente a otra etapa.

## 10.2 Instrucciones obligatorias antes de modificar código

1. Leer completa la etapa seleccionada, sus dependencias, el contexto heredado, la fuente funcional primaria y las decisiones pendientes relacionadas.
2. Leer las instrucciones `AGENTS.md` aplicables en cada repositorio afectado por la etapa.
3. Revisar el estado de Git y preservar todos los cambios existentes que no pertenezcan a la etapa.
4. Inspeccionar la implementación real: manifiestos, arquitectura, módulos, entidades, migraciones, contratos API, permisos, UI, pruebas, configuración y observabilidad vinculados al alcance.
5. Confirmar las rutas y símbolos registrados por AKINE-00.01. Si aún figuran como pendientes, completar primero la compuerta de evidencia o detener la etapa con un bloqueo explícito.
6. Analizar consumidores y dependencias antes de cambiar contratos, esquemas, estados, eventos o componentes compartidos.
7. Reutilizar abstracciones, patrones, componentes y utilidades existentes siempre que sean compatibles con las reglas de AKINE.
8. Definir antes de implementar qué criterios de aceptación y pruebas demostrarán la finalización de la etapa.

## 10.3 Instrucciones de implementación

- Mantener el alcance en la etapa seleccionada y construir un corte vertical verificable. El incremento debe incluir todas las capas aplicables y no limitarse a una pantalla o a persistencia aislada.
- Implementar las reglas invariantes en el dominio o servicio de aplicación correspondiente. Las validaciones de interfaz son complementarias y nunca la única protección.
- Aplicar aislamiento por organización y consultorio en almacenamiento, consultas, comandos, cachés, archivos, reportes, eventos y procesos en segundo plano.
- Autorizar cada operación en el servidor mediante rol, permiso, pertenencia, contexto de consultorio y ownership cuando corresponda. La visibilidad de la interfaz no constituye autorización.
- Tratar datos clínicos, personales, económicos y adjuntos según su sensibilidad: mínimo privilegio, trazabilidad, protección contra enumeración y ausencia de secretos o datos sensibles en logs.
- Para cambios de esquema, preferir expandir–migrar–contraer. Toda migración debe contemplar compatibilidad temporal, datos históricos, backfill, restricciones, índices, reversibilidad práctica y despliegue seguro.
- Mantener contratos API compatibles entre repositorios. El backend valida y publica OpenAPI; el frontend genera el cliente tipado desde una versión fijada. Los cambios incompatibles requieren versión, adaptación coordinada de consumidores, matriz de compatibilidad y decisión documentada.
- Diseñar operaciones reintentables e idempotentes cuando puedan repetirse por red, jobs, webhooks o acciones del usuario. Las transacciones deben cubrir las invariantes del caso de uso.
- En frontend, cubrir estados de carga, vacío, error, permiso insuficiente, conflicto y éxito; mantener accesibilidad, navegación por teclado, diseño responsivo y mensajes accionables.
- Agregar o actualizar pruebas unitarias, integración, contrato, autorización y recorrido crítico según el riesgo. Ejecutar primero las pruebas focalizadas y luego la suite definida por el repositorio.
- Actualizar documentación, contratos, datos de ejemplo, configuración y observabilidad cuando formen parte del cambio.

## 10.4 Restricciones generales

- No modificar funcionalidades fuera del alcance de la etapa ni realizar refactorizaciones generales no justificadas por sus criterios de aceptación.
- No inventar archivos, clases, entidades, endpoints, eventos, dependencias o comandos. Toda referencia debe existir o quedar declarada como elemento nuevo previsto por la etapa.
- No duplicar lógica, modelos, validadores ni componentes que ya tengan una fuente de verdad reutilizable.
- No cambiar contratos públicos, estados persistidos o semántica de negocio sin analizar consumidores y compatibilidad.
- No eliminar compatibilidad ni ejecutar cambios destructivos sin migración, respaldo, validación y decisión explícita.
- No eludir el aislamiento multi-tenant, permisos, auditoría o reglas clínicas para simplificar una entrega.
- No incluir credenciales, tokens, datos personales o información clínica real en código, fixtures, capturas, logs o documentación.
- No declarar una etapa terminada con criterios de aceptación incumplidos, migraciones no verificadas o pruebas críticas fallando.
- No ocultar incertidumbre: toda contradicción, deuda o decisión pendiente debe registrarse en las secciones correspondientes del plan.

## 10.5 Cierre obligatorio de cada etapa

Una etapa queda cerrada únicamente cuando se cumplen sus criterios de aceptación, se ejecutan las pruebas aplicables y se registra evidencia reproducible. El registro de cierre debe contener:

1. Resumen del incremento implementado y comportamiento observable.
2. Archivos creados.
3. Archivos modificados.
4. Migraciones, backfills o cambios de datos ejecutados y su resultado.
5. Endpoints, contratos, eventos o integraciones creados o modificados.
6. Pruebas agregadas o modificadas, comandos ejecutados y resultados.
7. Decisiones técnicas adoptadas y alternativas descartadas.
8. Problemas, riesgos o bloqueos encontrados.
9. Deuda técnica descubierta o deliberadamente diferida, con etapa destino.
10. Contexto concreto que debe conocer la etapa siguiente.

Además, debe actualizarse este plan con el estado real, las rutas y símbolos verificados, las desviaciones respecto de lo previsto y cualquier cambio de dependencia. La evidencia de cierre debe permitir a otro agente continuar sin reconstruir el razonamiento anterior.

## 10.6 Recomendaciones de ingeniería

- Favorecer incrementos pequeños, compatibles y desplegables detrás de configuración o feature flags cuando una capacidad aún no deba habilitarse.
- Separar comandos y consultas solo donde aporte claridad; no introducir patrones o infraestructura que el repositorio no necesite.
- Usar eventos, outbox, colas o jobs únicamente cuando exista asincronía real, definiendo entrega, duplicados, reintentos y compensación.
- Normalizar fechas con zona horaria explícita, importes con moneda y precisión definida, e identificadores sin significado de negocio.
- Diseñar índices y paginación a partir de consultas reales y medir antes de optimizar.
- Incorporar logs estructurados, métricas, trazas y auditoría con identificadores de correlación, sin exponer datos sensibles.
- Preferir mensajes de error de dominio estables, accionables y desacoplados de detalles internos.
- Mantener una única fuente de verdad para estados y transiciones de Turno, Sesión, Obligación, Pago, Caja, Clase, Inscripción y Pase.

# 11. Fases

| Fase | Nombre | Etapas | Resultado |
|---|---|---|---|
| F0 | Fundación greenfield | 00.01–00.03 | Dos repositorios reproducibles, arquitectura y decisiones cerradas |
| F1 | Plataforma segura | 01.01–01.03 | Tenant, identidad, notificaciones, memberships, permisos y auditoría base |
| F2 | Operación del consultorio | 02.01–02.07 | Onboarding, espacios, personal, disponibilidad, catálogos y ofertas |
| F3 | Personas y cobertura | 03.01–03.06 | Paciente 360, financiadores, coberturas, convenios, órdenes y autorizaciones administrativas |
| F4 | Dominio clínico | 04.01–04.05 | HC, timeline, Caso, Plan y consumo de autorizaciones |
| F5 | Agenda y recepción | 05.01–05.04 | Slots, reserva de recursos, ciclo de Turno y check-in |
| F6 | Atención clínica | 06.01–06.06 | Sesión, evaluación, examen, tratamientos, cierre y enmiendas |
| F7 | Economía del MVP | 07.01–07.05 | Obligaciones, anticipos/cobros, caja, financiadores y egresos |
| F8 | Cierre productivo del MVP | 07.06–07.09 | Reportes core, hardening, SLO y release M01–M27 |
| F9 | Segunda entrega obligatoria | 08.01–08.09 | Clases, participación clínica/no clínica, pases, créditos, abonos e integración |
| F10 | Consolidación final | 09.01–09.04 | Reporting ampliado, hardening y release completo M01–M29 |

# 12. Sprints

**Supuesto de planificación:** sprint de dos semanas, equipo mínimo de dos perfiles backend, dos frontend y QA compartido, con disponibilidad de Product Owner y referentes clínico/legal. Es una estimación de secuencia, no un compromiso: S00 debe recalibrar capacidad y velocidad. Con un equipo menor se conserva el orden y se amplía calendario; con varios equipos solo se paralelizan etapas sin dependencia directa.

| Sprint | Fase | Etapas | Capacidad verificable | Estimación base |
|---|---|---|---|---|
| S00 | F0 | 00.01–00.03 | Repositorios, baseline, ADRs y plan rebaselinado | 2 semanas |
| S01 | F1 | 01.01–01.03 | Tenancy, identidad, notificaciones y permisos | 2 semanas |
| S02 | F2 | 02.01–02.04 | Onboarding, consultorio, espacios y personal | 2 semanas |
| S03 | F2 | 02.05–02.07 | Catálogos, servicios/ofertas y habilitaciones | 2 semanas |
| S04 | F3 | 03.01–03.04 | Persona, Paciente 360, financiadores y coberturas | 2 semanas |
| S05 | F3 | 03.05–03.06 | Convenios, aranceles, órdenes y autorizaciones | 2 semanas |
| S06 | F4 | 04.01–04.03 | HC, timeline y Caso Clínico | 2 semanas |
| S07 | F4 | 04.04–04.05 | Plan y consumo de autorizaciones | 2 semanas |
| S08 | F5 | 05.01–05.02 | Slots y reserva atómica de Turno/Espacio | 2 semanas |
| S09 | F5 | 05.03–05.04 | Ciclo de Turno, series y recepción | 2 semanas |
| S10 | F6 | 06.01–06.03 | Inicio, evaluación y examen clínico | 2 semanas |
| S11 | F6 | 06.04–06.06 | Tratamientos, cierre y enmiendas | 2 semanas |
| S12 | F7 | 07.01–07.02 | Obligaciones, anticipos y cobros | 2 semanas |
| S13 | F7 | 07.03–07.05 | Caja, financiadores y egresos | 2 semanas |
| S14 | F8 | 07.06–07.07 | Reportes core y hardening de seguridad/a11y | 2 semanas |
| S15 | F8 | 07.08–07.09 | SLO, observabilidad y release MVP | 2 semanas |
| S16 | F9 | 08.01–08.03 | Clases, inscripciones y asistencia | 2 semanas |
| S17 | F9 | 08.04–08.05 | Derivación y atención clínica grupal | 2 semanas |
| S18 | F9 | 08.06–08.07 | Productos, venta y ledger de créditos | 2 semanas |
| S19 | F9 | 08.08–08.09 | Abonos e integración económica | 2 semanas |
| S20 | F10 | 09.01–09.02 | Reporting ampliado y hardening final | 2 semanas |
| S21 | F10 | 09.03–09.04 | SLO completo y release M01–M29 | 2 semanas |

# 13. Etapas detalladas

## Regla de evidencia aplicable a AKINE-00.02 en adelante

**ESTADO: compuerta de evidencia ABIERTA.** AKINE-00.01 se ejecutó y verificó el 22/08/2026. Las rutas, comandos y convenciones reales están registrados en el "Registro de cierre — AKINE-00.01" al final de este documento, y en los archivos `AGENT.md` y `CLAUDE.md` de cada repositorio.

Las etapas siguientes deben leer esas rutas reales antes de modificar código. Ya no rige la prohibición de determinar archivos: la estructura existe y es verificable.

- **Archivos existentes a modificar:** determinables. Ver el registro de cierre.
- **Archivos nuevos previstos:** cada etapa los define siguiendo las convenciones fijadas: paquete por módulo bajo `com.akine.<modulo>` con las capas `spi`/`api`/`application`/`domain`/`infrastructure`; feature por dominio bajo `src/app/features/<dominio>` en el frontend.
- **API:** el contrato canónico existe en `appKine-api/openapi/akine-api.yaml`, versión SemVer `0.1.0`. Se genera code-first desde los controllers y se valida con un gate de drift en CI. Cada etapa que cambie la API debe regenerarlo y coordinar la regeneración del cliente del frontend.
- **Verificación obligatoria antes de codificar:** correr `./mvnw verify` en el backend. Si los tests de arquitectura fallan antes de tocar código, detenerse y reportar.

## Etapa AKINE-00.01 — Crear los repositorios y baselines técnicos

### Objetivo

Crear los repositorios greenfield de backend y frontend con el stack aprobado, estructura de monolito modular, ejecución local coordinada, primera migración vacía/controlada y pipelines mínimos de calidad.

### Requerimientos relacionados

Decisión greenfield confirmada; RNF de infraestructura y calidad; secciones 40–44; todas las etapas posteriores.

### Dependencias

Ninguna.

### Estado actual detectado

No existen repositorios ni código. Esta etapa constituye el inicio técnico controlado de ambos proyectos.

### Instrucciones previas para el agente

- Leer este archivo completo.
- Leer `C:\Users\santo\Desktop\AKINE\AKINE_Requerimientos_Integrados_Parte_1_M01-M13.md`.
- Leer las decisiones aprobadas de stack, organización del repositorio, arquitectura, seguridad y despliegue.
- Confirmar las dos ubicaciones vacías destinadas a backend y frontend antes de crear archivos.
- Registrar desde el primer commit las convenciones, comandos y límites de módulos adoptados.

### Archivos existentes a modificar

- `AKINE_IMPLEMENTATION_PLAN.md`, para registrar las rutas y comandos creados.

### Archivos nuevos previstos

- Repositorio Git backend con el monolito modular Spring Boot.
- Repositorio Git frontend con la SPA Angular.
- Configuración de base de datos y primera migración reproducible.
- Infraestructura local y documentación de arranque coordinado.
- Pipeline mínimo independiente en cada repositorio.
- Suites de smoke test correspondientes al esqueleto creado.

### Backend

Crear el proyecto backend mínimo, estructura de módulos, health check, configuración por entorno, manejo seguro de secretos y prueba de arranque. No implementar todavía dominio funcional M01–M29.

### Base de datos

Configurar el motor aprobado, herramienta de migraciones y una migración inicial verificable sobre una base vacía. No crear todavía tablas funcionales no aprobadas por sus etapas.

### API

Configurar la especificación OpenAPI canónica en backend, su validación/publicación versionada y un contrato técnico mínimo de salud/versionado, sin anticipar endpoints de negocio. Configurar en frontend la generación reproducible del cliente TypeScript desde el artefacto publicado.

### Frontend

Crear la aplicación frontend mínima, shell accesible, configuración por entorno, cliente HTTP base y pruebas de arranque, sin pantallas funcionales.

### Reglas de negocio

- No anticipar entidades ni módulos funcionales para “ganar tiempo”.
- Toda dependencia debe quedar fijada mediante lockfile o mecanismo equivalente.
- Los comandos documentados deben funcionar desde un clon limpio.

### Seguridad / permisos

Configurar secretos fuera del repositorio, análisis de dependencias, headers/base segura y placeholders sin datos reales. Identidad, tenant y permisos se implementan en F1.

### Validaciones

- Confirmar que el destino de creación es correcto y no contiene archivos ajenos.
- Verificar clon limpio, instalación reproducible, build, arranque, migración y pruebas.
- Verificar exclusiones de Git, ausencia de secretos y separación de configuración por entorno.

### Casos borde

Destino no vacío; versión de runtime incorrecta; puertos ocupados; base no disponible; secreto accidental; lockfile inconsistente; pipeline diferente al entorno local.

### Tests necesarios

- Unitarios: smoke del backend y frontend según los frameworks aprobados.
- Integración: arranque de base limpia y aplicación de migraciones.
- Frontend: build, lint y prueba mínima de shell.
- E2E: smoke de aplicación y health check si el entorno local completo queda disponible.

### Criterios de aceptación

- Cada repositorio puede clonarse, instalarse, compilarse y ejecutarse con comandos documentados.
- Backend, frontend y base arrancan de forma reproducible sin secretos versionados.
- La primera migración se aplica desde una base vacía.
- Cada pipeline ejecuta los controles aplicables de build, lint y pruebas mínimas.
- El pipeline backend publica OpenAPI versionado y el pipeline frontend valida el cliente generado y su compatibilidad sin requerir commits atómicos.
- Este plan registra las rutas reales creadas en ambos repositorios para las etapas siguientes.

### Resultado esperado

Dos baselines técnicos greenfield creados, seguros, reproducibles y contractualmente coordinados.

### Contexto que deja disponible para la etapa siguiente

Stack fijado, mapa inicial de módulos, rutas reales de ambos repositorios, comandos canónicos, contratos y pipelines mínimos.


## Etapa AKINE-00.02 — Establecer baseline ejecutable y arquitectura comprobada

### Objetivo

Validar el baseline recién creado, formalizar la arquitectura operativa y fijar convenciones transversales antes de implementar dominio funcional.

### Requerimientos relacionados

RNF de M01–M29; secciones 32–41; objetivos históricos de calidad e infraestructura.

### Dependencias

AKINE-00.01.

### Estado actual detectado

Baseline pendiente de creación por AKINE-00.01.

### Instrucciones previas para el agente

- Leer rutas reales creadas por AKINE-00.01.
- Inspeccionar manifiestos, configuración, pipeline, contenedores, migraciones y suites iniciales.
- Leer las decisiones arquitectónicas aprobadas y comprobar que el baseline las cumple.

### Archivos existentes a modificar

Manifiestos, configuración y documentación creados en AKINE-00.01 cuando la verificación detecte una brecha concreta.

### Archivos nuevos previstos

ADRs, diagramas y pruebas de arquitectura conforme a la convención creada en AKINE-00.01.

### Backend

Verificar dependencias, compilar, ejecutar pruebas y fijar límites de módulos, capas, transacciones, errores y configuración. Incorporar pruebas de arquitectura que impidan ciclos y accesos entre módulos fuera de sus contratos públicos internos.

### Base de datos

Validar migraciones desde una base vacía, naming, ownership de esquema y estrategia expandir–migrar–contraer para etapas posteriores.

### API

Verificar sincronización entre OpenAPI y rutas técnicas iniciales y establecer validación automática de contratos.

### Frontend

Instalar de forma reproducible, compilar, ejecutar lint/pruebas y fijar estructura de rutas, layout, errores y cliente API.

### Reglas de negocio

El baseline fallido se documenta de forma reproducible; no se oculta ni se “arregla de paso”.

### Seguridad / permisos

Ejecutar análisis de configuración y tests sin exponer secretos; no imprimir variables sensibles.

### Validaciones

Versiones de runtime, lockfiles, build limpio, migrations, tests, lint, cobertura y artefactos.

### Casos borde

Dependencias privadas; servicios externos; tests flaky; fixtures destructivos; configuración local no versionada.

### Tests necesarios

- Unitarios/integración/frontend/E2E: ejecutar los existentes.
- Carga: solo identificar harness; no lanzar contra entornos compartidos.

### Criterios de aceptación

Existe un reporte reproducible de build/test, arquitectura y fallos; no se modificó comportamiento.

### Resultado esperado

Baseline técnico medible y comandos canónicos para todas las etapas.

### Contexto que deja disponible para la etapa siguiente

Stack real, suite confiable, deuda priorizada y puntos de extensión verificados.


## Etapa AKINE-00.03 — Formalizar decisiones aprobadas y rebaselinar el plan

### Objetivo

Formalizar DP-01–DP-09 como ADRs/decisiones operativas, verificar que el baseline las respeta y rebaselinar rutas/estimaciones.

### Requerimientos relacionados

Reglas maestras 1–30; secciones 30 y 32–44.

### Dependencias

AKINE-00.02.

### Estado actual detectado

DP-01–DP-09 están resueltas documentalmente; falta convertirlas en ADRs del repositorio y verificar el baseline greenfield.

### Instrucciones previas para el agente

- Este plan, sección 7.
- Requisitos integrados, `AKINE_info.txt`, `plan_sesiones.txt`, UML y DTE.
- Evidencia real y ADRs/decisiones existentes incorporados en 00.01.

### Archivos existentes a modificar

- `AKINE_IMPLEMENTATION_PLAN.md`.
- ADRs/documentación real verificada, solo si corresponde.

### Archivos nuevos previstos

ADRs solo con nombres y ubicación conformes al repositorio.

### Backend

No implementar; determinar impacto de decisiones sobre agregados y transacciones.

### Base de datos

Definir alcance y estrategia conceptual, sin migrar.

### API

Definir decisiones de contrato, no endpoints finales si faltan modelos.

### Frontend

Definir navegación contextual y separación de flujos, sin construir pantallas.

### Reglas de negocio

Registrar en ADRs las decisiones confirmadas y su consecuencia operativa; toda modificación posterior exige una nueva decisión formal con owner, fecha y justificación.

### Seguridad / permisos

Aplicar las resoluciones DP-02/DP-03 y completar la matriz mínima de permisos.

### Validaciones

Cada ADR debe incluir contexto, fuentes, decisión, impacto, consecuencias, estado `ACEPTADA` y vínculos a las etapas afectadas.

### Casos borde

Baseline que contradice una decisión aprobada; dos ADRs incompatibles; nueva exigencia legal; consecuencia no contemplada en una etapa.

### Tests necesarios

No aplica; definir escenarios de regresión derivados.

### Criterios de aceptación

DP-01–DP-09 están versionadas como ADRs aceptadas, la matriz de permisos mínima existe y las etapas reflejan el baseline real sin ambigüedades silenciosas.

### Resultado esperado

ADRs versionadas, matriz de permisos aprobada y plan rebaselado contra los dos repositorios creados.

### Contexto que deja disponible para la etapa siguiente

Modelo, contratos y prioridades aprobados.


## Etapa AKINE-01.01 — Tenancy, organizaciones y suscripción SaaS

### Objetivo

Estabilizar tenant, organización, suscripción, límites y selección de contexto organizacional.

### Requerimientos relacionados

RF-M01-001..005; RN-M01-001..004; RNF-M01-001..008.

### Dependencias

AKINE-00.03.

### Estado actual detectado

NO IMPLEMENTADO — proyecto greenfield; el UML legado no contiene tenant ni suscripción.

### Instrucciones previas para el agente

- Rutas reales de tenant/organización/suscripción agregadas por 00.01.
- Migraciones y filtros de acceso reales.
- M01, líneas 88–496, y reglas transversales.

### Archivos existentes a modificar

Los verificados por AKINE-00.01 para M01; si no están listados, detenerse.

### Archivos nuevos previstos

Solo los indispensables según arquitectura real.

### Backend

Completar agregados, transiciones, límites y resolución server-side de contexto.

### Base de datos

Migración expand-first, claves/índices tenant y backfill seguro si existen datos.

### API

Contrato a definir durante esta etapa a partir del modelo existente; evitar confiar en tenant arbitrario del cliente.

### Frontend

Selector contextual, estados de suscripción/límites y errores accionables.

### Reglas de negocio

Históricos no se borran; límites no invalidan datos previos; backend valida membership.

### Seguridad / permisos

Admin plataforma vs admin organización; aislamiento estricto por tenant.

### Validaciones

Estado, vigencia, límites, idempotencia de alta y referencias cross-tenant.

### Casos borde

Usuario multi-org; tenant suspendido; downgrade bajo datos existentes; retry de creación.

### Tests necesarios

- Unitarios: transiciones y límites.
- Integración: filtros tenant, constraints y reintentos.
- Frontend: selección/errores.
- E2E: crear org y cambiar contexto sin fuga.

### Criterios de aceptación

RF-M01 y CA asociados pasan; ninguna consulta M01 cruza tenants; históricos sobreviven a cambios de plan.

### Resultado esperado

Contexto organizacional seguro y estable para todos los módulos.

### Contexto que deja disponible para la etapa siguiente

Identificadores, resolver de tenant, estados y feature gates.


## Etapa AKINE-01.02 — Identidad, autenticación y recuperación

### Objetivo

Completar registro, activación/invitación, login, refresh, recuperación y bloqueo seguro de cuentas.

### Requerimientos relacionados

RF-M02-001..003, RF-M02-005, RF-M26-001, RF-M26-004..005; RN-M02-001..004; RN-M26-001..006.

### Dependencias

AKINE-01.01. DP-02 ya está resuelta y define autenticación única con selección contextual posterior.

### Estado actual detectado

NO IMPLEMENTADO — proyecto greenfield; los flujos históricos son únicamente antecedentes.

### Instrucciones previas para el agente

- Rutas reales de identidad, security, notificaciones, outbox/cola, jobs y tests creadas/listadas por 00.01.
- M02 y M26; configuración de secretos y expiraciones.

### Archivos existentes a modificar

Los verificados para autenticación; detenerse si no están enumerados.

### Archivos nuevos previstos

Solo adaptadores/templates imprescindibles conforme a patrones reales.

### Backend

Registro idempotente, hashing, tokens de un solo uso/rotación, revocación y bloqueo. Incorporar el lifecycle de entrega de notificaciones: pendiente, procesando, enviada, fallida, reintentable y agotada, con worker idempotente y backoff acotado.

### Base de datos

Tokens/estados/índices de identidad; outbox de notificaciones, intentos, próxima ejecución, error sanitizado y claves idempotentes.

### API

Contratos de identidad sin enumeración de cuentas. El reintento manual de notificaciones será administrativo y no expondrá destinatarios o payloads fuera de su tenant.

### Frontend

Formularios accesibles, estados de activación/reset y sesión expirada.

### Reglas de negocio

Identidad única; invitación no duplica usuario; acciones históricas sobreviven al bloqueo. Un fallo de notificación no revierte la operación de negocio confirmada; registrar resultado y reintentar nunca duplica la activación, invitación o evento origen.

### Seguridad / permisos

Rate limit, hashing robusto, refresh rotation, no logs de secretos, cookies/storage según decisión real.

### Validaciones

Email, contraseña, expiración, replay, cuenta bloqueada/inactiva y concurrencia.

### Casos borde

Doble submit; invitación a email existente; token usado/expirado; enumeración; cambio de email pendiente; proveedor caído; timeout tras envío; respuesta duplicada; reintentos agotados; notificación obsoleta.

### Tests necesarios

Unitarios criptográficos/estados/backoff; integración de tokens, outbox e idempotencia; frontend de errores; E2E registro-login-reset; contract test del adaptador de notificaciones.

### Criterios de aceptación

RF y CA M02/M26-001/004/005 pasan sin exposición de secretos; todo intento queda trazable y los reintentos no duplican efectos de negocio.

### Resultado esperado

Identidad segura lista para autorización contextual.

### Contexto que deja disponible para la etapa siguiente

Principal autenticado, lifecycle de cuenta, mecanismo de sesión y servicio confiable de entrega/reintento reutilizable por las etapas posteriores.


## Etapa AKINE-01.03 — Memberships, roles, permisos y auditoría base

### Objetivo

Autorizar por tenant/consultorio y vigencia, con auditoría inmutable de operaciones sensibles.

### Requerimientos relacionados

RF-M01-002, RF-M02-004, RF-M05-001..002, RF-M24-001..004; sección 32.

### Dependencias

AKINE-01.02.

### Estado actual detectado

NO IMPLEMENTADO — proyecto greenfield; la matriz funcional define el comportamiento esperado.

### Instrucciones previas para el agente

- Security config, memberships, policies/guards, audit y tests reales.
- Matriz de permisos de la sección 32 y reglas M24.

### Archivos existentes a modificar

Rutas `organization`, `identity` y `platform` creadas según la sección 2.4.

### Archivos nuevos previstos

Solo policies/permisos/eventos faltantes coherentes con la arquitectura.

### Backend

Resolver permisos granulares y registrar actor, entidad, acción, tenant, consultorio y cambios.

### Base de datos

Vigencias/constraints de membership e índice de auditoría; no almacenar secretos.

### API

403/404 consistentes para referencias no accesibles; filtros de auditoría autorizados.

### Frontend

Ocultar/deshabilitar acciones como ayuda UX, nunca como control único.

### Reglas de negocio

Un usuario puede tener roles distintos por consultorio; revocar no borra autoría.

### Seguridad / permisos

Backend obligatorio, mínimo privilegio, permisos clínicos/económicos reforzados.

### Validaciones

Vigencia, último admin, self-revoke, cross-tenant, auditoría de antes/después.

### Casos borde

Usuario sin contexto; múltiples roles; membership vencida durante sesión; soporte de plataforma con acceso restringido.

### Tests necesarios

Unitarios de policy; integración por matriz; frontend; E2E de acceso denegado y auditoría.

### Criterios de aceptación

La matriz mínima se cumple server-side y toda mutación sensible es reconstruible.

### Resultado esperado

Base transversal segura para módulos operativos.

### Contexto que deja disponible para la etapa siguiente

Permisos reutilizables, contexto de consultorio y auditoría consultable.


## Etapa AKINE-02.01 — Consultorios, onboarding y contexto operativo

### Objetivo

Completar alta, onboarding mínimo, edición, baja lógica y selección de consultorio.

### Requerimientos relacionados

RF-M03-001..005; RN-M03-001..004; DP-01.

### Dependencias

AKINE-01.03.

### Estado actual detectado

NO IMPLEMENTADO — proyecto greenfield; DP-01 define el onboarding compuesto.

### Instrucciones previas para el agente

- Rutas `organization` backend y `features/organization` frontend creadas según la sección 2.4.
- Modelo tenant/membership, migraciones, rutas, formularios y tests.
- M03, líneas 907–1313.

### Archivos existentes a modificar

Módulos de organización, identidad y membership creados en F1.

### Archivos nuevos previstos

Casos de uso, persistencia, contratos y UI de Consultorio/onboarding conforme a la arquitectura aprobada.

### Backend

Alta transaccional/idempotente de Cuenta, Organización, primer Consultorio y Membership propietario conforme DP-01; configuración, estados y contexto de trabajo permanecen en agregados separados.

### Base de datos

Tenant FK, zona horaria, vigencia/activo, índices y backfill seguro.

### API

Definir contrato OpenAPI del onboarding compuesto y recursos separados de Consultorio/contexto.

### Frontend

Wizard breve, selector de consultorio y tratamiento de sede inactiva.

### Reglas de negocio

Todo consultorio pertenece a organización; inactivo no recibe nuevas operaciones; horario general no sustituye disponibilidad profesional.

### Seguridad / permisos

Admin organización/consultorio; filtro tenant y contexto validado en backend.

### Validaciones

Unicidad contextual, zona horaria, estado, reintentos y referencias activas.

### Casos borde

Fallo parcial de onboarding; primer consultorio; multi-sede; baja con turnos futuros.

### Tests necesarios

Unitarios de estados; integración transaccional/tenant; frontend del wizard; E2E alta-selección-baja.

### Criterios de aceptación

RF/CA M03 pasan sin datos parciales ni acceso cross-tenant.

### Resultado esperado

Sede operativa y seleccionable.

### Contexto que deja disponible para la etapa siguiente

Consultorio activo, timezone y configuración estable.


## Etapa AKINE-02.02 — Espacios, boxes y capacidad física

### Objetivo

Gestionar recursos físicos, baja lógica, disponibilidad base y capacidad.

### Requerimientos relacionados

RF-M04-001..003, RF-M04-006..007; RN-M04-001..004.

### Dependencias

AKINE-02.01.

### Estado actual detectado

NO IMPLEMENTADO — proyecto greenfield; el UML histórico solo aporta un antecedente de Consultorio.

### Instrucciones previas para el agente

- Entidades/repositorios/servicios/componentes reales de espacios.
- Migraciones y consultas de agenda que ya consuman recursos.
- M04, líneas 1481–2187.

### Archivos existentes a modificar

Rutas `resource` backend y feature de espacios frontend creadas según la sección 2.4.

### Archivos nuevos previstos

Modelo, migración, contratos y feature UI de Espacio/Box.

### Backend

CRUD con vigencia, tipo, capacidad y consulta base de disponibilidad.

### Base de datos

Constraints capacidad >0, tenant/consultorio, baja lógica e índices temporales futuros.

### API

Contrato a partir del modelo real; listados paginados y filtros de activos.

### Frontend

Gestión compacta, estados vacíos, capacidad y confirmación de baja con impacto.

### Reglas de negocio

Un recurso pertenece a un consultorio; inactivo no se reserva; históricos conservan nombre/estado.

### Seguridad / permisos

Admin consultorio; profesionales/administrativos solo consulta según permiso.

### Validaciones

Nombre contextual, capacidad, estado, referencias cross-tenant.

### Casos borde

Baja con reservas futuras; capacidad reducida bajo ocupación; recurso renombrado histórico.

### Tests necesarios

Unitarios; integración de constraints/tenant; frontend; E2E CRUD y baja.

### Criterios de aceptación

Recursos históricos no se pierden y nuevas selecciones excluyen inactivos.

### Resultado esperado

Catálogo físico preparado para disponibilidad y sesiones.

### Contexto que deja disponible para la etapa siguiente

IDs, capacidad, vigencia y consultas reutilizables.


## Etapa AKINE-02.03 — Ciclo de vida de colaboradores

### Objetivo

Completar invitación, aceptación/rechazo, vinculación y desvinculación de profesionales/administrativos.

### Requerimientos relacionados

RF-M05-001..002, RF-M05-006, RF-M26-001; RN-M05-003..004.

### Dependencias

AKINE-02.01 y AKINE-01.03.

### Estado actual detectado

NO IMPLEMENTADO — proyecto greenfield; el flujo histórico se usa como antecedente.

### Instrucciones previas para el agente

- Archivos reales de invitations, memberships, users, mail, turnos y tests.
- M05 y proceso de `AKINE_info.txt:120–144`.

### Archivos existentes a modificar

Rutas `organization`/staff backend y feature de personal frontend.

### Archivos nuevos previstos

Solo adaptadores o estados faltantes según arquitectura.

### Backend

Invitación idempotente, tokens seguros, estados, vínculo contextual y análisis de impacto al desvincular.

### Base de datos

Unicidad de invitación activa, vigencia y referencias históricas.

### API

Aceptar/rechazar sin enumerar cuentas; conflicto funcional en replay.

### Frontend

Listado/estado de invitaciones y resolución de turnos afectados.

### Reglas de negocio

No duplicar usuario; rechazo y baja son históricos; turnos futuros quedan visibles para resolución.

### Seguridad / permisos

Solo admin consultorio invita/desvincula; invitado opera su token.

### Validaciones

Email, rol permitido, expiración, consultorio activo, último admin y self-removal.

### Casos borde

Usuario existente; múltiples consultorios; token repetido; desvinculación con agenda futura.

### Tests necesarios

Unitarios de estados; integración/idempotencia; frontend; E2E invitación y baja.

### Criterios de aceptación

No hay identidades duplicadas ni pérdida de autoría; impacto futuro es procesable.

### Resultado esperado

Personal correctamente vinculado por contexto.

### Contexto que deja disponible para la etapa siguiente

Profesionales/administrativos activos por consultorio.


## Etapa AKINE-02.04 — Disponibilidad semanal, excepciones y feriados

### Objetivo

Modelar disponibilidad por Profesional + Consultorio, excepciones y calendario operativo.

### Requerimientos relacionados

RF-M05-003..005; RN-M05-001..002; `Adicional.txt` §2–3.

### Dependencias

AKINE-02.03.

### Estado actual detectado

NO IMPLEMENTADO — proyecto greenfield; solo existen reglas y ejemplos documentales.

### Instrucciones previas para el agente

- Modelos/servicios/UI reales de horarios, timezone y agenda.
- M05, configuración del consultorio y tests temporales existentes.

### Archivos existentes a modificar

Rutas `resource` backend y feature de disponibilidad frontend.

### Archivos nuevos previstos

Solo modelos de recurrencia/excepción faltantes compatibles.

### Backend

Bloques recurrentes con vigencia, excepciones de cierre/apertura y detección de conflictos.

### Base de datos

Rangos temporales, timezone del consultorio, versionado y constraints de solapamiento según motor real.

### API

Consulta/edición por período; contrato a definir desde lo existente.

### Frontend

Editor semanal compacto, excepciones y preview de turnos afectados.

### Reglas de negocio

Excepción prevalece; disponibilidad es contextual; feriado/bloqueo participa del cálculo.

### Seguridad / permisos

Admin configura; profesional puede ver/solicitar cambio según política confirmada.

### Validaciones

Desde < hasta, timezone/DST, solapamientos, vigencias y turnos futuros.

### Casos borde

Bloques cruzando medianoche; excepción parcial; cambio de timezone; agenda ya ocupada.

### Tests necesarios

Unitarios de intervalos; integración temporal; frontend; E2E edición con conflictos.

### Criterios de aceptación

La disponibilidad efectiva es determinista y explica qué regla la afecta.

### Resultado esperado

Fuente confiable para el motor de slots.

### Contexto que deja disponible para la etapa siguiente

Contrato de disponibilidad efectiva y conflictos.


## Etapa AKINE-02.05 — Especialidades, prácticas y nomencladores

### Objetivo

Estabilizar catálogos clínicos globales/contextuales, búsqueda y vigencias históricas.

### Requerimientos relacionados

RF-M06-001..005; RN-M06-001..003.

### Dependencias

AKINE-01.03.

### Estado actual detectado

NO IMPLEMENTADO — proyecto greenfield; el UML legado solo aporta catálogos históricos.

### Instrucciones previas para el agente

- Entidades/repositorios/DTOs/componentes reales de catálogos.
- Migraciones, referencias desde convenios/sesiones y tests.

### Archivos existentes a modificar

Rutas M06 verificadas por 00.01.

### Archivos nuevos previstos

Modelo versionado de disponibilidad, excepciones y feriados, con migraciones y componentes UI.

### Backend

CRUD/baja lógica, búsqueda incremental, nomenclador y solicitudes de alta.

### Base de datos

Vigencias, claves estables e imposibilidad de borrar referencias históricas.

### API

Paginación, búsqueda normalizada y filtros de activos/vigentes.

### Frontend

Selects con búsqueda, gestión de vigencias y nombres inactivos en históricos.

### Reglas de negocio

Significado histórico inmutable; convenios referencian versión/vigencia aplicable.

### Seguridad / permisos

Admin plataforma para global; admin consultorio para conceptos contextuales/solicitudes.

### Validaciones

Duplicados normalizados, vigencias superpuestas y referencias de otro tenant.

### Casos borde

Renombre usado históricamente; baja con plan activo; importación duplicada.

### Tests necesarios

Unitarios, integración de vigencia, frontend de búsqueda, E2E administración.

### Criterios de aceptación

Catálogos históricos siguen resolviendo y nuevas selecciones excluyen inactivos.

### Resultado esperado

Catálogo clínico reutilizable.

### Contexto que deja disponible para la etapa siguiente

IDs/versiones de especialidad, práctica y nomenclador.


## Etapa AKINE-02.06 — Servicio global y oferta por consultorio

### Objetivo

Crear la distinción Servicio ≠ OfertaServicioConsultorio sin reemplazar Turno ni Sesión.

### Requerimientos relacionados

RF-M03-006..007, RF-M06-006..008, RF-M27-001..003; reglas maestras 13–20 y 27.

### Dependencias

AKINE-02.01 y AKINE-02.05.

### Estado actual detectado

NO IMPLEMENTADO — proyecto greenfield; existe modelo conceptual en la sección 30.

### Instrucciones previas para el agente

- Catálogos/servicios/consultorios actuales y puntos donde se selecciona práctica/tipo de turno.
- M27 y sección 30.5–30.7.

### Archivos existentes a modificar

Rutas `resource`/catálogos backend y features administrativas frontend.

### Archivos nuevos previstos

Modelos/migraciones solo si el código no posee equivalentes correctos.

### Backend

Servicio global, oferta contextual, defaults no vinculantes, vigencia y baja lógica.

### Base de datos

FK tenant/consultorio/servicio, modalidad/configuración explícita e índices.

### API

CRUD conforme a contratos existentes; no hardcodear por nombre.

### Frontend

Catálogo global separado de configuración comercial/operativa del consultorio.

### Reglas de negocio

Oferta sobrescribe defaults; una oferta inactiva preserva históricos; no inferir clínica/modalidad por nombre.

### Seguridad / permisos

Admin plataforma gestiona Servicio; admin consultorio gestiona Oferta.

### Validaciones

Servicio activo, duplicados contextuales, modalidad válida, duración/capacidad/importes básicos.

### Casos borde

Mismo servicio con configuraciones distintas; baja del catálogo con ofertas activas; rollout con datos existentes.

### Tests necesarios

Unitarios de resolución; integración/migración; frontend; E2E alta de oferta.

### Criterios de aceptación

Fase 1 de sección 30.7 funciona sin romper Turno/Sesión actuales.

### Resultado esperado

Abstracción configurable para servicios individuales y grupales.

### Contexto que deja disponible para la etapa siguiente

Oferta estable y referenciable por agenda/economía.


## Etapa AKINE-02.07 — Configuración operativa de ofertas y habilitaciones

### Objetivo

Definir comportamiento clínico, modalidad, capacidad, cobro, profesionales y espacios habilitados por oferta.

### Requerimientos relacionados

RF-M04-008..009, RF-M05-007..009, RF-M27-004..008.

### Dependencias

AKINE-02.02, AKINE-02.04 y AKINE-02.06.

### Estado actual detectado

NO IMPLEMENTADO — proyecto greenfield; disciplina profesional y rol de seguridad deben permanecer separados.

### Instrucciones previas para el agente

- Oferta, profesionales, espacios, disponibilidad, convenios y permisos reales.
- M04/M05/M27 y reglas maestras 21–23.

### Archivos existentes a modificar

Rutas reales agregadas por 00.01 para esos módulos.

### Archivos nuevos previstos

Relaciones de habilitación Oferta–Profesional–Espacio, migraciones, contratos y UI.

### Backend

Resolver requisitos de caso/registro clínico, modalidad, capacidad efectiva, esquema de cobro y habilitaciones.

### Base de datos

Tablas/relaciones con vigencia y constraints; no duplicar rol profesional.

### API

Contrato a definir desde el modelo existente; endpoint de validación explicable si encaja.

### Frontend

Formulario progresivo que muestre campos según modalidad/esquema, no por nombre.

### Reglas de negocio

Capacidad efectiva = mínimo de oferta/clase/espacio; habilitación ≠ permiso; configuración explícita.

### Seguridad / permisos

Admin consultorio configura; backend valida tenant, disciplina y oferta.

### Validaciones

Combinaciones coherentes, vigencia, profesional/espacio activo y referencias contextuales.

### Casos borde

Oferta clínica grupal; sin profesional; espacio de menor capacidad; cambio con clases futuras.

### Tests necesarios

Unitarios de matriz de configuración; integración; frontend condicional; E2E oferta válida/inválida.

### Criterios de aceptación

RF asociados pasan y ninguna regla depende del texto del nombre del servicio.

### Resultado esperado

Ofertas listas para agenda, clínica y economía.

### Contexto que deja disponible para la etapa siguiente

Contrato de elegibilidad profesional/espacio/cobertura/esquema.


## Etapa AKINE-03.01 — Persona, PerfilPaciente, búsqueda y deduplicación

### Objetivo

Estabilizar identidad de Persona y activación opcional de PerfilPaciente sin duplicar datos.

### Requerimientos relacionados

RF-M07-001..003, RF-M07-007..010; RN-M07-001..004; regla maestra 13.

### Dependencias

AKINE-01.03.

### Estado actual detectado

NO IMPLEMENTADO — proyecto greenfield; el UML legado une Persona/Paciente y la especificación exige separarlos.

### Instrucciones previas para el agente

- Modelos, repositorios, búsquedas, DTOs, formularios y migraciones reales de persona/paciente.
- M07 y usos desde turnos, clases, usuarios y HC.

### Archivos existentes a modificar

Rutas M07 verificadas por 00.01.

### Archivos nuevos previstos

Solo adaptaciones expand-first si el modelo actual no permite perfil opcional.

### Backend

Búsqueda previa, deduplicación, alta mínima y activación clínica idempotente.

### Base de datos

Identidad estable, claves normalizadas y migración sin duplicar personas existentes.

### API

Búsqueda paginada y alta/activación desde contratos reales.

### Frontend

Búsqueda antes de alta, advertencia de coincidencias y diferenciación no clínica/clínica.

### Reglas de negocio

Usuario portal no equivale a paciente; consumo no clínico no crea HC/Caso/Sesión.

### Seguridad / permisos

Tenant/consultorio y acceso a PII; autoservicio limitado a datos propios.

### Validaciones

DNI/identificador, normalización, coincidencias, baja lógica y reintentos.

### Casos borde

Persona sin DNI; menor; duplicado entre sedes; usuario existente sin perfil; perfil dado de baja.

### Tests necesarios

Unitarios de matching; integración/migración; frontend; E2E persona→paciente.

### Criterios de aceptación

Una persona se reutiliza y ninguna actividad no clínica crea artefactos clínicos.

### Resultado esperado

Identidad única consumible por ambos flujos.

### Contexto que deja disponible para la etapa siguiente

PersonaId y PerfilPacienteId con reglas claras.


## Etapa AKINE-03.02 — Paciente 360, baja y adjuntos administrativos

### Objetivo

Entregar ficha administrativa 360, edición/baja segura y adjuntos no clínicos.

### Requerimientos relacionados

RF-M07-003..006, RF-M25-001..005 para entidad paciente.

### Dependencias

AKINE-03.01.

### Estado actual detectado

NO IMPLEMENTADO — proyecto greenfield; storage y ownership se definen en esta etapa conforme a la sección 2.4.

### Instrucciones previas para el agente

- Paciente, turnos, coberturas, deuda, adjuntos/storage, permisos y UI reales.
- M07/M25 y límites de datos clínicos.

### Archivos existentes a modificar

Rutas `person` backend y feature Paciente 360 frontend.

### Archivos nuevos previstos

Metadata y puerto de storage de adjuntos, adaptador local seguro inicial y componentes de Paciente 360.

### Backend

Agregación 360 por permisos, edición optimista, baja lógica y adjuntos con metadata.

### Base de datos

Metadata, clasificación, checksum/estado y referencias; binarios fuera de DB si así lo define arquitectura.

### API

Resumen paginado y descarga autorizada sin rutas internas.

### Frontend

Vista 360 densa y navegable, empty/loading/error states, adjuntos por categoría.

### Reglas de negocio

No mezclar ni duplicar HC; baja no borra historial; adjunto no sustituye dato estructurado.

### Seguridad / permisos

Acceso hereda entidad; URLs temporales; validación tipo/tamaño; PII.

### Validaciones

Versionado/concurrencia, tipos MIME reales, tamaño, tenant y estado.

### Casos borde

Archivo malicioso; descarga tras baja; paciente fusionado; relaciones inactivas.

### Tests necesarios

Unitarios de agregación; integración storage/permisos; frontend; E2E 360/adjunto.

### Criterios de aceptación

Información autorizada coherente, sin fuga ni rutas internas; historial preservado.

### Resultado esperado

Ficha administrativa útil para recepción.

### Contexto que deja disponible para la etapa siguiente

Paciente 360 y servicio transversal de adjuntos.


## Etapa AKINE-03.03 — Financiadores y planes de cobertura

### Objetivo

Construir el catálogo administrable de financiadores y planes, con vigencias y referencias históricas estables.

### Requerimientos relacionados

RF-M15-001..008; RN-M15-001..008; RNF-M15-001..011.

### Dependencias

AKINE-02.01 y AKINE-01.03.

### Estado actual detectado

NO IMPLEMENTADO — proyecto greenfield; solo existe la definición M15.

### Instrucciones previas para el agente

- Revisar convenciones de módulos, tenancy, catálogos, vigencias, auditoría y errores creadas en 00.01–01.04.
- Revisar M15 completo y los consumidores previstos M08/M16/M17.

### Archivos existentes a modificar

Estructura backend/frontend compartida creada por las etapas base; registrar aquí las rutas reales al iniciar la etapa.

### Archivos nuevos previstos

Módulo backend de financiadores/planes, feature frontend administrativa, migraciones y pruebas M15 conforme al mapa de rutas.

### Backend

CRUD y consultas de Financiador/Plan, activación, baja lógica, vigencias, búsqueda y referencias históricas sin acoplar todavía coberturas de pacientes.

### Base de datos

Tablas con tenant/alcance aprobado, vigencias no solapadas cuando corresponda, claves estables, bajas lógicas e índices de búsqueda.

### API

Recursos REST paginados y comandos de activación/baja; OpenAPI canónico y cliente TypeScript regenerado.

### Frontend

Listado, filtros, formularios compactos, detalle de vigencias y advertencias de referencias activas.

### Reglas de negocio

Una referencia inactiva no puede elegirse para nuevas operaciones, pero permanece visible en históricos; nombres/códigos deben ser únicos dentro del alcance definido.

### Seguridad / permisos

Administración contextual; lectura operativa limitada; aislamiento por Organización y auditoría de cambios.

### Validaciones

Identidad fiscal/código, nombre, vigencias, duplicados, estado, referencias existentes y concurrencia.

### Casos borde

Plan sin nuevas altas pero con pacientes vigentes; financiador duplicado; baja con convenios; cambio de nombre histórico.

### Tests necesarios

Unitarios de vigencias/estado; integración de constraints y tenant; frontend; E2E CRUD/baja/histórico.

### Criterios de aceptación

RF/CA M15 pasan; no se crean duplicados ni se pierden referencias históricas y otro tenant no puede acceder.

### Resultado esperado

Catálogo confiable de financiadores y planes disponible para coberturas y convenios.

### Contexto que deja disponible para la etapa siguiente

Identificadores, estados y vigencias de Financiador/Plan estabilizados.

## Etapa AKINE-03.04 — Coberturas del paciente

### Objetivo

Asociar al paciente coberturas particulares o financiadas con vigencia, credencial y selección operativa trazable.

### Requerimientos relacionados

RF-M08-001..007; RN-M08-001..007; RNF-M08-001..011.

### Dependencias

AKINE-03.01 y AKINE-03.03.

### Estado actual detectado

NO IMPLEMENTADO — proyecto greenfield; solo existe la definición M08.

### Instrucciones previas para el agente

- Revisar Persona/PerfilPaciente, Financiador/Plan, adjuntos, vigencias, permisos y auditoría.
- Revisar M08 completo y la distinción Cobertura ≠ Convenio.

### Archivos existentes a modificar

Módulos reales de paciente y financiadores creados por 03.01/03.03.

### Archivos nuevos previstos

Componentes backend/frontend, migraciones y pruebas de cobertura del paciente según convenciones.

### Backend

Alta, edición, baja lógica, historial, cobertura principal y fallback Particular sin modificar catálogos maestros.

### Base de datos

Relación paciente-plan con número de afiliado, vigencias, principal, metadata de credencial y constraints contextuales.

### API

Comandos y consultas de coberturas del paciente con errores específicos; contrato OpenAPI versionado.

### Frontend

Sección de coberturas en Paciente 360, historial, selección principal y alertas de vigencia.

### Reglas de negocio

Particular es una modalidad operativa, no un borrado de cobertura; históricos preservan la cobertura utilizada en cada hecho.

### Seguridad / permisos

Personal administrativo autorizado; profesional con lectura mínima necesaria; tenant/consultorio y PII protegida.

### Validaciones

Paciente, financiador/plan activo al alta, número de afiliado, vigencias, duplicados y única cobertura principal vigente.

### Casos borde

Coberturas superpuestas; credencial vencida; cambio de plan; baja con turnos; Particular temporal.

### Tests necesarios

Unitarios de vigencia/principal; integración tenant/constraints; frontend; E2E alta/cambio/historial.

### Criterios de aceptación

RF/CA M08 pasan; la selección vigente es determinista y los históricos no cambian retroactivamente.

### Resultado esperado

Coberturas del paciente listas para convenios, agenda, recepción y economía.

### Contexto que deja disponible para la etapa siguiente

Cobertura vigente seleccionable y snapshots de identidad de afiliación.

## Etapa AKINE-03.05 — Convenios, aranceles y vigencias

### Objetivo

Configurar convenios y aranceles por consultorio, financiador, plan, práctica/oferta y período, preservando snapshots históricos.

### Requerimientos relacionados

RF-M16-001..010; RN-M16-001..008; RNF-M16-001..011; reglas monetarias §37.

### Dependencias

AKINE-03.03, AKINE-02.05 y AKINE-02.07.

### Estado actual detectado

NO IMPLEMENTADO — proyecto greenfield; solo existe la definición M16.

### Instrucciones previas para el agente

- Revisar Financiador/Plan, Consultorio, prácticas/ofertas, precisión monetaria, vigencias y auditoría.
- Definir consultas de precio efectivas por fecha sin reescribir hechos históricos.

### Archivos existentes a modificar

Módulos reales de financiadores, catálogos, ofertas y consultorio.

### Archivos nuevos previstos

Módulo de convenios/aranceles, feature administrativa, migraciones, OpenAPI y pruebas.

### Backend

CRUD, versionado de vigencias, resolución determinista de convenio/arancel aplicable y generación de snapshot inmutable para consumidores.

### Base de datos

Importes DECIMAL, moneda, período, prioridad, alcance y constraints contra solapamientos ambiguos; índices por fecha/contexto.

### API

Administración paginada y consulta de arancel efectivo con explicación de la regla aplicada.

### Frontend

Grillas de vigencias/aranceles, filtros, clonación controlada y previsualización de impacto.

### Reglas de negocio

Nunca recalcular hechos históricos con el arancel actual; resolver por fecha, contexto y prioridad aprobada; sin floats.

### Seguridad / permisos

Administración económica contextual; lectura operativa mínima; auditoría reforzada de importes/vigencias.

### Validaciones

Importe/moneda, fechas, alcance, duplicados, solapamientos, práctica/oferta habilitada y referencias activas.

### Casos borde

Cambio de arancel futuro; convenio vencido; dos reglas candidatas; atención retroactiva; plan dado de baja.

### Tests necesarios

Unitarios de resolución/decimales; integración de constraints; frontend; E2E vigencia/snapshot; concurrencia.

### Criterios de aceptación

RF/CA M16 pasan y, para una fecha/contexto, el sistema devuelve un resultado único, explicable e históricamente estable.

### Resultado esperado

Motor de convenio/arancel disponible para autorización, recepción y obligaciones.

### Contexto que deja disponible para la etapa siguiente

Resolución económica y snapshot contractual estabilizados.

## Etapa AKINE-03.06 — Órdenes, autorizaciones y documentación administrativa

### Objetivo

Registrar órdenes médicas, autorizaciones y documentos con vigencia/estado, dejando el consumo clínico para una integración posterior.

### Requerimientos relacionados

RF-M17-001..003, RF-M17-006..008; RF-M25-006; RN-M17-001..008; RNF-M17-001..011; DP-08 resuelta.

### Dependencias

AKINE-03.04 y AKINE-03.05.

### Estado actual detectado

NO IMPLEMENTADO — proyecto greenfield; solo existen requisitos y ejemplos históricos.

### Instrucciones previas para el agente

- Revisar cobertura, convenio, adjuntos, reglas configurables y decisión DP-08.
- No convertir ejemplos documentales en obligatoriedad global.

### Archivos existentes a modificar

Módulos reales de paciente, cobertura, convenio y adjuntos.

### Archivos nuevos previstos

Módulo administrativo de órdenes/autorizaciones, migraciones, UI, OpenAPI y pruebas.

### Backend

Alta, validación, observación, aprobación/rechazo, vigencia, documentos y consulta de elegibilidad preliminar, sin consumir sesiones todavía.

### Base de datos

Estado e historial, vigencias, cantidad autorizada, metadata documental, claves externas e índices; baja lógica.

### API

Comandos específicos de estado y consultas por paciente/cobertura/caso futuro, sin asignación arbitraria de estado.

### Frontend

Panel administrativo con vencimientos, documentos, cantidades y motivos de rechazo/observación.

### Reglas de negocio

Obligatoriedad configurable por financiador/plan/convenio/prestación; un documento vencido no desaparece; estado controla elegibilidad.

### Seguridad / permisos

Administrativo autorizado; profesional con lectura justificada; adjuntos con URLs temporales; auditoría sensible.

### Validaciones

Cobertura, emisor, número, fechas, cantidad, tipo/tamaño de adjunto, duplicados y transición.

### Casos borde

Autorización parcial; renovación; documento ilegible; cambio de cobertura; aprobación concurrente; regla no configurada.

### Tests necesarios

Unitarios de estados/vigencias; integración documental/tenant; frontend; E2E alta-aprobación-vencimiento.

### Criterios de aceptación

RF/CA asignados pasan; reglas configurables, vigencias y documentos quedan trazables sin consumo prematuro.

### Resultado esperado

Base administrativa de órdenes/autorizaciones lista para Caso/Plan/Sesión.

### Contexto que deja disponible para la etapa siguiente

Autorización elegible, saldo autorizado inicial y documentos accesibles con permisos.

## Etapa AKINE-04.01 — Historia Clínica organizacional y acceso

### Objetivo

Crear la Historia Clínica longitudinal con alcance organizacional, resumen básico y autorización clínica conforme a DP-03.

### Requerimientos relacionados

RF-M09-001..003; RN-M09-001..007; RNF-M09-001..011; DP-03 resuelta.

### Dependencias

AKINE-03.02.

### Estado actual detectado

NO IMPLEMENTADO — proyecto greenfield; el UML legado no satisface tenancy ni permisos actuales.

### Instrucciones previas para el agente

- Revisar Persona/Paciente, memberships, permisos, auditoría y resolución DP-03.
- Revisar M09 y matriz mínima de permisos antes de modelar acceso.

### Archivos existentes a modificar

Módulos reales de paciente, seguridad, tenant y auditoría.

### Archivos nuevos previstos

Módulo clínico base, migraciones, API, feature frontend y pruebas de aislamiento/acceso.

### Backend

Crear/obtener HC por paciente y Organización, resumen clínico mínimo y policy de acceso por membership, permiso y relación asistencial.

### Base de datos

Identidad única por paciente/Organización, metadatos, timestamps, versionado y auditoría de acceso sensible.

### API

Consulta/creación idempotente y resumen con minimización de datos; otro tenant responde como recurso no accesible.

### Frontend

Entrada clínica desde Paciente 360, resumen y mensajes diferenciados de ausencia de permiso/contexto.

### Reglas de negocio

Una HC por paciente y Organización; nunca compartir automáticamente entre Organizaciones; acceso interconsultorio solo autorizado.

### Seguridad / permisos

Permiso clínico, membership vigente, relación asistencial/justificación, tenant y auditoría de lectura/escritura.

### Validaciones

Paciente activo, Organización, contexto, relación, duplicado concurrente y versión.

### Casos borde

Paciente en dos Organizaciones; profesional en dos consultorios; acceso de emergencia autorizado; membership vencida.

### Tests necesarios

Unitarios de policy; integración tenant/unique/auditoría; frontend; E2E acceso permitido/denegado.

### Criterios de aceptación

RF/CA asignados pasan y ninguna identidad/contexto no autorizado puede descubrir o leer la HC.

### Resultado esperado

Historia Clínica organizacional segura disponible para timeline y casos.

### Contexto que deja disponible para la etapa siguiente

Identificador de HC, policy de acceso y resumen estable.

## Etapa AKINE-04.02 — Timeline clínico y adjuntos

### Objetivo

Incorporar timeline longitudinal, entradas versionadas y adjuntos clínicos seguros sin sobrescritura destructiva.

### Requerimientos relacionados

RF-M09-004..006; RF-M25-001..005 clínicos; RN-M25-001..006; RNF-M25-001..011.

### Dependencias

AKINE-04.01 y AKINE-03.02.

### Estado actual detectado

NO IMPLEMENTADO — proyecto greenfield; solo existen lineamientos documentales.

### Instrucciones previas para el agente

- Revisar HC base, storage/metadata de adjuntos administrativos, permisos y auditoría.
- Definir entradas inmutables/enmendables, no un textarea global mutable.

### Archivos existentes a modificar

Módulos reales de HC, adjuntos, auditoría y Paciente 360.

### Archivos nuevos previstos

Timeline clínico, adaptador de storage si falta, componentes UI, contratos y pruebas.

### Backend

Consulta paginada/filtrada, registro de entradas y adjuntos, baja lógica, versión/enmienda y proyección de timeline.

### Base de datos

Entradas con tipo/origen/autor/fecha/versión; metadata de adjunto, checksum, clasificación y referencias sin blobs en logs.

### API

Timeline paginado; alta/consulta/baja lógica de adjuntos; descarga mediante URL temporal/autorizada.

### Frontend

Timeline denso y filtrable, preview seguro, estados loading/empty/error y acceso según permisos.

### Reglas de negocio

Los hechos clínicos no se borran ni sobrescriben; enmiendas preservan original; permisos se heredan del contexto clínico.

### Seguridad / permisos

Autorización por objeto, tipo/tamaño permitidos, análisis antimalware cuando exista infraestructura y auditoría de descargas.

### Validaciones

Tipo, tamaño, checksum, pertenencia, versión, paginación/rango y contenido mínimo de entrada.

### Casos borde

Carga duplicada; archivo infectado; storage caído; entrada enmendada; autor desvinculado; adjunto huérfano.

### Tests necesarios

Unitarios de versión/policy; integración storage/tenant; frontend; E2E timeline/adjunto/enmienda; seguridad de descarga.

### Criterios de aceptación

Timeline reproducible, paginado y seguro; adjuntos inaccesibles fuera del contexto; originales preservados.

### Resultado esperado

Contexto longitudinal utilizable por Caso, Plan y Sesión.

### Contexto que deja disponible para la etapa siguiente

Timeline, contratos de entrada/adjunto y mecanismo de versionado.

## Etapa AKINE-04.03 — Caso Clínico y numeración contextual

### Objetivo

Modelar problemas terapéuticos concretos dentro de la HC con estado, equipo y numeración contextual.

### Requerimientos relacionados

RF-M10-001..008; RN-M10-001..007; RNF-M10-001..011.

### Dependencias

AKINE-04.02 y AKINE-02.07.

### Estado actual detectado

NO IMPLEMENTADO — Caso Clínico no existe en el modelo histórico.

### Instrucciones previas para el agente

- Revisar HC/timeline, ofertas/especialidades, profesionales y reglas de estados/concurrencia.
- No numerar futuras sesiones globalmente por paciente.

### Archivos existentes a modificar

Módulos reales de clínica, catálogos, staff y frontend clínico.

### Archivos nuevos previstos

Agregado Caso, migraciones, API, UI y pruebas según arquitectura modular.

### Backend

Alta, edición controlada, cierre/reapertura, equipo/diagnóstico/objetivo y numerador estable dentro de HC/Organización.

### Base de datos

Caso ligado a HC, estado/historial, correlativo contextual con unique y datos clínicos versionables.

### API

Comandos de estado y consultas por HC con optimistic locking y errores de conflicto.

### Frontend

Listado de casos activos/históricos, alta compacta, detalle, estado y timeline relacionado.

### Reglas de negocio

Caso ≠ HC ≠ Sesión; puede contener múltiples sesiones/planes; cierre no borra; numeración contextual y concurrente.

### Seguridad / permisos

Profesional autorizado y relación asistencial; administrativos sin contenido clínico salvo permiso explícito.

### Validaciones

HC, estado, especialidad/oferta, fechas, duplicados razonables, versión y transición.

### Casos borde

Dos altas concurrentes; reapertura; caso sin plan; profesional desvinculado; paciente con casos similares.

### Tests necesarios

Unitarios de estados/numeración; integración concurrente/tenant; frontend; E2E alta-cierre-reapertura.

### Criterios de aceptación

RF/CA M10 pasan; correlativos no colisionan y el caso queda aislado/autorizado.

### Resultado esperado

Caso Clínico estable como eje de planificación y sesiones.

### Contexto que deja disponible para la etapa siguiente

Caso activo, estado, equipo y numeración contextual.

## Etapa AKINE-04.04 — Plan de Tratamiento

### Objetivo

Planificar objetivos, frecuencia, cantidad y prácticas para un Caso Clínico sin confundir planificado con realizado.

### Requerimientos relacionados

RF-M11-001..008; RN-M11-001..008; RNF-M11-001..011.

### Dependencias

AKINE-04.03 y AKINE-02.07.

### Estado actual detectado

NO IMPLEMENTADO — Plan de Tratamiento no existe en el modelo histórico.

### Instrucciones previas para el agente

- Revisar Caso, ofertas/prácticas, disponibilidad futura, estados, auditoría y autorizaciones administrativas disponibles.
- Mantener cantidades planificadas, autorizadas, realizadas y canceladas separadas.

### Archivos existentes a modificar

Módulos reales de Caso, catálogos/ofertas y frontend clínico.

### Archivos nuevos previstos

Agregado Plan, objetivos/ítems, migraciones, API, UI y pruebas.

### Backend

Crear/versionar/activar/cerrar plan, objetivos, frecuencia, duración estimada, prácticas y reglas de recurrencia propuestas.

### Base de datos

Plan/versión/estado, objetivos e ítems con cantidades; snapshots de práctica/oferta y constraints.

### API

Comandos de ciclo de vida y consulta de progreso planificado, sin aceptar contadores realizados desde frontend.

### Frontend

Editor estructurado, resumen de cantidades/progreso y advertencias de vigencia/autorización.

### Reglas de negocio

Plan ≠ agenda ≠ sesión; no crear sesiones realizadas; cambios relevantes versionan; progreso se deriva de hechos.

### Seguridad / permisos

Profesional tratante/autorizado; firma/autoría y auditoría; lectura administrativa mínima.

### Validaciones

Caso activo, objetivos, frecuencia, cantidades, prácticas habilitadas, fechas, versión y transición.

### Casos borde

Plan sin autorización; cambio de frecuencia; segundo plan; cierre con turnos futuros; tratamiento discontinuado.

### Tests necesarios

Unitarios de estados/cantidades; integración versionado/tenant; frontend; E2E crear-activar-modificar-cerrar.

### Criterios de aceptación

RF/CA M11 pasan y los contadores planificados/autorizados/realizados nunca se confunden.

### Resultado esperado

Plan clínico versionado disponible para agenda y autorización.

### Contexto que deja disponible para la etapa siguiente

Plan activo, ítems, cantidades y regla de recurrencia propuesta.

## Etapa AKINE-04.05 — Integración y consumo de autorizaciones

### Objetivo

Vincular órdenes/autorizaciones con Caso, Plan y prestaciones, validando y consumiendo saldo de forma idempotente.

### Requerimientos relacionados

RF-M17-001..008; RN-M17-001..008; RNF-M17-001..011; RF-M11-007..008.

### Dependencias

AKINE-04.04 y AKINE-03.06.

### Estado actual detectado

NO IMPLEMENTADO — existe base administrativa planificada, sin integración clínica.

### Instrucciones previas para el agente

- Revisar órdenes/autorizaciones, Caso/Plan, ofertas/prácticas, estados, concurrencia e idempotencia.
- Definir cuándo reservar, consumir y revertir sin asociarlo al mero Turno.

### Archivos existentes a modificar

Módulos reales de autorización, Caso, Plan y oferta.

### Archivos nuevos previstos

Vínculos/ledger de consumo, endpoints/UI de asignación y pruebas concurrentes.

### Backend

Asignar autorización elegible, calcular saldo, reservar si la regla lo exige, consumir al hecho facturable y revertir mediante compensación idempotente.

### Base de datos

Ledger de movimientos de autorización, referencias únicas de origen, saldo derivado, locks/versiones e historial.

### API

Consulta de elegibilidad/saldo y comandos de asociación/consumo/reversión con conflictos tipados.

### Frontend

Selector explicable de autorización, saldo/vigencia y alertas bloqueantes o informativas según configuración.

### Reglas de negocio

No consumir por reservar turno salvo regla explícita; consumo único por hecho; reversión no borra; autorización vencida no admite nuevo consumo.

### Seguridad / permisos

Administrativo gestiona; profesional consulta lo necesario; tenant, cobertura, caso y ownership validados.

### Validaciones

Vigencia, estado, saldo, prestación, cobertura, caso/plan, duplicado, concurrencia y motivo de reversión.

### Casos borde

Última unidad concurrente; sesión anulada; cambio de autorización; consumo parcial; autorización renovada.

### Tests necesarios

Unitarios de saldo/elegibilidad; integración concurrente/idempotente; frontend; E2E asignar-consumir-revertir.

### Criterios de aceptación

Cada consumo/reversión es trazable, saldo nunca negativo y retries no duplican movimientos.

### Resultado esperado

Autorizaciones integradas y listas para agenda, sesión y obligación económica.

### Contexto que deja disponible para la etapa siguiente

Contrato de elegibilidad, saldo y ledger de autorización.

## Etapa AKINE-05.01 — Motor de disponibilidad y slots explicables

### Objetivo

Calcular slots libres combinando consultorio, profesional, oferta, espacio, capacidad, feriados y excepciones.

### Requerimientos relacionados

RF-M12-001; RF-M04-003; RF-M05-008..009; RN-M12-004..005; regla maestra 29.

### Dependencias

AKINE-02.04, AKINE-02.07 y AKINE-03.04.

### Estado actual detectado

NO IMPLEMENTADO — proyecto greenfield; solo existen reglas documentales del motor.

### Instrucciones previas para el agente

- Consultas reales de agenda/disponibilidad/espacios/ofertas/timezone.
- Índices/migraciones y tests temporales/concurrentes.

### Archivos existentes a modificar

Rutas `resource`/`scheduling` backend y feature de agenda frontend.

### Archivos nuevos previstos

Motor de intervalos/slots explicables, contratos, UI e índices necesarios.

### Backend

Composición determinista de restricciones y explicación de indisponibilidad.

### Base de datos

Índices por rango/contexto; consultas eficientes sin persistir slots efímeros salvo decisión existente.

### API

Consulta por rango, oferta/profesional/consultorio con paginación o ventana acotada.

### Frontend

Selector de filtros, loading/empty/conflict y timezone visible.

### Reglas de negocio

La disponibilidad se recalcula; capacidad y recursos se validan simultáneamente; backend es autoridad.

### Seguridad / permisos

Vista pública minimiza PII; vista privada respeta tenant/consultorio.

### Validaciones

Rango máximo, duración, timezone, oferta activa y habilitaciones.

### Casos borde

DST; medianoche; excepción parcial; espacios opcionales; capacidad >1; profesional desvinculado.

### Tests necesarios

Unitarios de intervalos; integración de queries; frontend; E2E búsqueda; benchmark de rango típico.

### Criterios de aceptación

Para entradas iguales retorna slots deterministas y no ofrece recursos inválidos.

### Resultado esperado

Contrato de slots reusable por reserva individual.

### Contexto que deja disponible para la etapa siguiente

Slot candidato y evidencia de restricciones aplicadas.


## Etapa AKINE-05.02 — Reserva y confirmación de Turno

### Objetivo

Reservar/confirmar un slot individual con revalidación atómica e idempotencia.

### Requerimientos relacionados

RF-M12-002..003; RF-M04-004; RF-M26-002; RN-M12-001 y RN-M12-004.

### Dependencias

AKINE-05.01 y AKINE-03.01.

### Estado actual detectado

NO IMPLEMENTADO — proyecto greenfield; el Turno histórico es solo antecedente.

### Instrucciones previas para el agente

- Turno/estado/repository/service/DTO/UI/notificación reales.
- Constraints de agenda, idempotencia y tests de concurrencia.

### Archivos existentes a modificar

Rutas M12 verificadas.

### Archivos nuevos previstos

Solo los necesarios para estado/idempotencia/constraint faltante.

### Backend

Crear y confirmar con transacción, revalidación, asignación/reserva del Espacio cuando la Oferta lo requiera y evento/notificación desacoplada.

### Base de datos

Constraints de doble reserva de profesional y Espacio apropiadas al motor, vínculo del recurso reservado y clave idempotente.

### API

Conflict específico si slot fue tomado; no devolver error genérico.

### Frontend

Flujos online y administrativo, resumen antes de confirmar y recuperación del conflicto.

### Reglas de negocio

Turno es reserva; no crea sesión realizada; el Espacio requerido se reserva atómicamente con el slot; fallo de email no revierte reserva.

### Seguridad / permisos

Paciente reserva propio; administrativo/profesional según permiso; tenant y consultorio.

### Validaciones

Paciente/persona, caso cuando oferta lo exige, slot, cobertura elegida, Espacio requerido/capacidad y duplicados.

### Casos borde

Doble click; dos usuarios compiten por el último slot o Espacio; timeout tras commit; paciente no portal; oferta o asignación de recurso cambia.

### Tests necesarios

Unitarios de estados; integración concurrente/idempotente de profesional y Espacio; frontend; E2E reserva/conflicto.

### Criterios de aceptación

Una sola reserva gana, retry devuelve resultado consistente y notificación no es fuente de verdad.

### Resultado esperado

Turno individual confirmado y trazable.

### Contexto que deja disponible para la etapa siguiente

Estado inicial/confirmado, historial y recursos reservados.


## Etapa AKINE-05.03 — Cancelación, reprogramación e historial de Turno

### Objetivo

Implementar transiciones trazables sin borrado físico y resolver impacto en recursos/notificaciones.

### Requerimientos relacionados

RF-M12-004..008; RF-M26-003; RN-M12-002..003; DP-04/DP-05.

### Dependencias

AKINE-05.02.

### Estado actual detectado

NO IMPLEMENTADO — proyecto greenfield; DP-04 exige historial y prohíbe eliminar turnos.

### Instrucciones previas para el agente

- Máquina de estados real, DTE histórico, servicios de agenda/notificaciones y tests.
- Resoluciones DP-04/DP-05.

### Archivos existentes a modificar

Rutas `scheduling` backend y feature de agenda/Turno frontend.

### Archivos nuevos previstos

Máquina de estados, historial, modelo de serie/regla y comandos/UI correspondientes.

### Backend

Transiciones permitidas, reprogramación atómica, manejo de serie/regla recurrente, cancelación explícita de futuros, liberación/reserva de recursos y auditoría.

### Base de datos

Historial inmutable, versionado/optimistic lock, vínculo de serie/regla y relación original-nuevo si aplica. Ninguna ausencia o cancelación ejecuta borrado físico.

### API

Comandos específicos para cada transición y para cancelar futuros de una serie; frontend no asigna estados arbitrarios.

### Frontend

Acciones según estado, motivo y resolución de conflictos; timeline visible; confirmación con alcance y cantidad antes de cancelar turnos futuros.

### Reglas de negocio

Cancelar no borra; reprogramar conserva trazabilidad; ausencia es estado distinto. La primera ausencia no altera automáticamente los turnos futuros. La cancelación masiva solo afecta turnos futuros pendientes y registra actor, motivo, fecha y alcance.

### Seguridad / permisos

Ownership/política de ventana para paciente; staff contextual; auditoría.

### Validaciones

Estado, ventana, nuevo slot, motivo y retry.

### Casos borde

Reprogramación compite; cancelación tras check-in; primera ausencia de una serie; algún turno futuro ya atendido/cancelado; cancelación parcial; notificación fallida.

### Tests necesarios

Unitarios de máquina y alcance de serie; integración concurrente; frontend; E2E cancelar/reprogramar/ausencia/cancelación de futuros/historial.

### Criterios de aceptación

No hay borrado ni doble ocupación; la primera ausencia preserva futuros; una cancelación de serie modifica únicamente turnos futuros pendientes y todo cambio registra actor/fecha/motivo.

### Resultado esperado

Ciclo de vida de reserva estable.

### Contexto que deja disponible para la etapa siguiente

Turnos del día y estados confiables para recepción.


## Etapa AKINE-05.04 — Recepción, check-in y espera

### Objetivo

Buscar turnos del día, registrar llegada real, validar condición administrativa y enviar a espera sin invadir clínica.

### Requerimientos relacionados

RF-M13-001..006; RN-M13-001..004; RF-M12-006..007; DP-06.

### Dependencias

AKINE-05.03, AKINE-03.06 y AKINE-04.05.

### Estado actual detectado

NO IMPLEMENTADO — proyecto greenfield; el proceso histórico es un antecedente operativo.

### Instrucciones previas para el agente

- Turnos/recepción/cobertura/convenio/autorización/cola/UI reales.
- M13 y bloque administrativo de `plan_sesiones.txt`.

### Archivos existentes a modificar

Rutas `scheduling` backend y feature de recepción frontend.

### Archivos nuevos previstos

Registro y máquina de estado de Check-in/Recepción, contratos y UI de espera.

### Backend

Búsqueda por día/persona, hora real server-side, validaciones y transición a espera idempotente.

### Base de datos

Timestamps reales, snapshot administrativo preliminar e historial.

### API

Comandos check-in/ausencia/particular/espera con errores funcionales.

### Frontend

Lista rápida de recepción, alertas compactas, fallback Particular y acciones por permiso.

### Reglas de negocio

Faltante puede advertir sin bloquear según política; Particular no modifica cobertura maestra. Una política de prepago puede ofrecer cobro administrativo, pero su incumplimiento no impide iniciar ni cerrar la atención clínica.

### Seguridad / permisos

Administrativo contextual; profesional consulta limitada; PHI mínima en recepción.

### Validaciones

Turno del consultorio/fecha, estado, cobertura/convenio/documentos y replay.

### Casos borde

Sin turno; llegada temprana/tarde; cobertura inválida; check-in duplicado; cancelado.

### Tests necesarios

Unitarios de transiciones; integración con cobertura; frontend; E2E recepción→espera.

### Criterios de aceptación

Hora real y condición administrativa quedan trazables; clínica no depende del cobro y cualquier prepago queda separado como anticipo hasta su imputación.

### Resultado esperado

Paciente listo para iniciar atención con contexto validado.

### Contexto que deja disponible para la etapa siguiente

Check-in, espera y snapshot administrativo.


## Etapa AKINE-06.01 — Agregado Sesión, inicio, contexto y autosave

### Objetivo

Abrir una atención real asociada a un Caso, autocompletar contexto y guardar borradores sin duplicar sesiones.

### Requerimientos relacionados

RF-M14-001..002, RF-M14-009; RN-M14-001..003; sección 34.

### Dependencias

AKINE-05.04 y AKINE-04.05.

### Estado actual detectado

NO IMPLEMENTADO — proyecto greenfield; Atención del UML legado no satisface Caso/Plan/Sesión actuales.

### Instrucciones previas para el agente

- Entidad/servicio/repositorio/DTO/UI/tests reales de atención/sesión.
- Turno, check-in, caso, plan, cobertura, espacio y sesión previa.
- M14 y `plan_sesiones.txt:2–248`.

### Archivos existentes a modificar

Rutas clínicas verificadas para Sesión.

### Archivos nuevos previstos

Solo agregado/borrador/idempotency faltantes según arquitectura.

### Backend

Inicio idempotente, ownership profesional, snapshot de contexto y guardado parcial versionado.

### Base de datos

FK única a caso, relación opcional con turno, estado, timestamps, versión y borrador.

### API

Comandos iniciar/guardar y consulta de contexto; contrato desde lo existente.

### Frontend

Header sticky, estados de guardado, recuperación y alertas no invasivas.

### Reglas de negocio

Sesión ≠ Turno; una sesión pertenece a un caso; cobertura/contexto se reutilizan, no duplican arbitrariamente.

### Seguridad / permisos

Profesional actuante o reemplazo autorizado; tenant/consultorio/caso y bloqueo de edición ajena.

### Validaciones

Check-in/atención sin turno según política, caso activo, profesional, estado y replay.

### Casos borde

Doble inicio; reconexión; cambio de profesional; atención sin turno; caso cerrado entre check-in e inicio.

### Tests necesarios

Unitarios de estados; integración idempotencia/versionado; frontend autosave; E2E inicio/recuperación.

### Criterios de aceptación

Un retry no crea otra sesión y el borrador se recupera con contexto correcto.

### Resultado esperado

Sesión en ejecución, estable y editable.

### Contexto que deja disponible para la etapa siguiente

SessionId, versión, contexto y modo clínico.


## Etapa AKINE-06.02 — Evaluación base y modo Sesión rápida

### Objetivo

Capturar dolor, evolución, objetivo y limitación funcional con carga rápida y datos estructurados.

### Requerimientos relacionados

RF-M14-003; `plan_sesiones.txt` §§8, 13.1 y 15.

### Dependencias

AKINE-06.01.

### Estado actual detectado

NO IMPLEMENTADO — proyecto greenfield; existe diseño clínico/UX documental.

### Instrucciones previas para el agente

- Formulario/estado/modelos/validadores reales de sesión.
- Catálogos de diagnósticos/objetivos y sesión previa.
- `plan_sesiones.txt:249–442,789–876,921–962`.

### Archivos existentes a modificar

Rutas `clinical` backend y feature de Sesión frontend.

### Archivos nuevos previstos

Casos de uso de inicio/autoguardado y componentes de contexto/shell de Sesión.

### Backend

Persistir evaluación base tipada y comparación con sesión previa.

### Base de datos

Campos/estructura que permitan consultar dolor, función y cambio; migración compatible.

### API

Guardar/recuperar borrador usando contrato de 06.01.

### Frontend

Escala de dolor, chips de evolución, selects buscables y texto breve; máximo 5–7 elementos visibles.

### Reglas de negocio

Seguimiento no exige examen completo; motivo clínico se distingue de diagnóstico médico.

### Seguridad / permisos

Solo rol clínico edita; administrativos no ven detalle innecesario.

### Validaciones

Dolor 0–10, zona/lateralidad si aplica, objetivo/limitación y coherencia de modo.

### Casos borde

Sin sesión previa; paciente sin dolor; empeoramiento; datos copiados y no revisados.

### Tests necesarios

Unitarios de validación; integración de persistencia; frontend de interacción; E2E sesión rápida.

### Criterios de aceptación

Un seguimiento común se documenta sin abrir examen completo y conserva datos comparables.

### Resultado esperado

Evaluación base clínica útil y rápida.

### Contexto que deja disponible para la etapa siguiente

Medidas base y disparadores de re-evaluación.


## Etapa AKINE-06.03 — Evaluación completa, examen y mediciones

### Objetivo

Registrar examen físico progresivo, tests/medidas y comparación histórica en primera evaluación o re-evaluación.

### Requerimientos relacionados

RF-M14-004; `plan_sesiones.txt` §§9, 13.2 y 14.

### Dependencias

AKINE-06.02.

### Estado actual detectado

NO IMPLEMENTADO — proyecto greenfield; existen campos y UX propuestos.

### Instrucciones previas para el agente

- Modelos/componentes reales de evaluación, tests, medidas y catálogos.
- Plan/caso, sesión previa y patrones de formularios.

### Archivos existentes a modificar

Rutas `clinical` y componentes de evaluación/examen creados en 06.01–06.02.

### Archivos nuevos previstos

Estructuras tipadas y componentes plegables solo si faltan.

### Backend

ROM, fuerza, función, marcha, signos, neuro/respiratorio y tests extensibles.

### Base de datos

Valores estructurados, unidad, lateralidad, catálogo/versionado y orden.

### API

Persistencia parcial y comparación anterior/actual.

### Frontend

Subbloques plegables, copiar-previo-y-ajustar con confirmación, resumen de completitud.

### Reglas de negocio

Completo solo cuando corresponde; copiar nunca guarda sin revisión; unidad y significado históricos.

### Seguridad / permisos

Rol clínico; trazabilidad de cambios; PHI.

### Validaciones

Unidad/tipo/rango, lado, fecha, catálogo vigente y obligatorios por modo/política clínica aprobada.

### Casos borde

Test discontinuado; unidad cambia; medición bilateral; re-evaluación sin baseline.

### Tests necesarios

Unitarios de tipos/rangos; integración/versiones; frontend; E2E evaluación completa.

### Criterios de aceptación

Primera evaluación cumple mínimos aprobados y seguimientos permanecen compactos.

### Resultado esperado

Examen clínico comparable y extensible.

### Contexto que deja disponible para la etapa siguiente

Hallazgos objetivos y catálogo de medidas.


## Etapa AKINE-06.04 — Tratamientos realizados y espacios usados

### Objetivo

Registrar múltiples intervenciones realmente aplicadas, parámetros por técnica y recursos efectivamente utilizados.

### Requerimientos relacionados

RF-M14-005; RF-M04-005; `plan_sesiones.txt` §10.

### Dependencias

AKINE-06.03, AKINE-02.05 y AKINE-02.02.

### Estado actual detectado

NO IMPLEMENTADO — proyecto greenfield; PracticaEnAtencion es un antecedente insuficiente.

### Instrucciones previas para el agente

- Tratamientos/prácticas/sesión/espacios reales, parámetros existentes y tests.
- Plan previsto y catálogo M06.

### Archivos existentes a modificar

Rutas `clinical`, `resource` y componentes de tratamiento/espacios.

### Archivos nuevos previstos

Modelo estructurado de intervención, parámetros tipados/unidades y selección de Espacios.

### Backend

Colección ordenada de intervenciones, profesional/co-atención, duración, zona y parámetros tipados.

### Base de datos

Relación 1:N, orden único por sesión, snapshot de práctica/técnica y parámetros compatibles.

### API

Operaciones de borrador consistentes; evitar endpoint por cada tipo si modelo polimórfico existente resuelve.

### Frontend

Filas repetibles compactas; campos condicionales por configuración/tipo, no textarea universal.

### Reglas de negocio

Planificado ≠ realizado; varias intervenciones; espacio real puede diferir del reservado con validación.

### Seguridad / permisos

Profesional actuante; cambio de espacio autorizado y tenant.

### Validaciones

Práctica vigente, parámetros requeridos, duración, orden, profesional y capacidad/ocupación.

### Casos borde

Co-atención; mismo tratamiento repetido; cambio de box; parámetro legado sin tipo/unidad que debe rechazarse o normalizarse explícitamente.

### Tests necesarios

Unitarios; integración colección/espacio; frontend dinámico; E2E múltiples tratamientos.

### Criterios de aceptación

La sesión reproduce qué se hizo, dónde, por quién y con qué parámetros.

### Resultado esperado

Intervenciones realizadas estructuradas.

### Contexto que deja disponible para la etapa siguiente

Tratamientos y duraciones listos para cierre clínico/económico.


## Etapa AKINE-06.05 — Resultado, próxima conducta y cierre idempotente

### Objetivo

Cerrar clínicamente con respuesta, tolerancia, evolución, indicaciones y conducta, asignando correlativo por Caso una sola vez.

### Requerimientos relacionados

RF-M14-006..008; RN-M14-002, RN-M14-005; secciones 33–35; `plan_sesiones.txt` §§11, 17.2–17.4.

### Dependencias

AKINE-06.04.

### Estado actual detectado

NO IMPLEMENTADO — proyecto greenfield; correlativo y efectos transaccionales se definen en esta etapa.

### Instrucciones previas para el agente

- Cierre real de sesión, numeradores, HC/timeline, autorización, eventos/outbox y tests concurrentes.
- M14/M18 y reglas de idempotencia.

### Archivos existentes a modificar

Rutas `clinical` y consumidores internos de cierre creados en etapas previas.

### Archivos nuevos previstos

Constraint/outbox/evento solo conforme a patrones reales.

### Backend

Validar mínimos, finalizar atómicamente, correlativo por caso, timestamps y disparar efectos idempotentes.

### Base de datos

Unique `(caso, numeroSesion)`, lock/version apropiado y marca de finalización.

### API

Comando idempotente; resultado estable ante retry.

### Frontend

Resumen de pendientes, cierre express y resultado claro sin bloquear por cobro.

### Reglas de negocio

Cierre clínico ≠ cobro; HC se actualiza; obligación se deriva después; sesión cerrada no se edita silenciosamente.

### Seguridad / permisos

Profesional autorizado; auditoría de actor/hora y datos clínicos.

### Validaciones

Tratamiento o nota equivalente, resultado, profesional, duración, asistencia, caso y versión.

### Casos borde

Doble cierre; dos sesiones del mismo caso cierran simultáneamente; fallo de consumidor; sin cobertura válida.

### Tests necesarios

Unitarios de mínimos; integración transaccional/concurrente/idempotente; frontend; E2E cierre→timeline.

### Criterios de aceptación

Correlativo único por Caso, cierre retry-safe y HC consistente aunque economía/notificación falle y reintente.

### Resultado esperado

Atención clínica finalizada y publicable.

### Contexto que deja disponible para la etapa siguiente

SessionCompleted estable, snapshot clínico y correlativo.


## Etapa AKINE-06.06 — Enmiendas y versionado de sesión cerrada

### Objetivo

Corregir una sesión finalizada mediante enmienda auditable, sin sobrescritura silenciosa ni recalcular históricos.

### Requerimientos relacionados

RF-M14-010; RF-M24-005; RN-M14-006; `plan_sesiones.txt` §18.3.

### Dependencias

AKINE-06.05.

### Estado actual detectado

NO IMPLEMENTADO — proyecto greenfield; requisito de enmienda explícito.

### Instrucciones previas para el agente

- Versionado/auditoría clínica reales, sesión, timeline, permisos y efectos económicos.
- Políticas de firma/corrección si existen.

### Archivos existentes a modificar

Rutas `clinical` y `platform`/auditoría.

### Archivos nuevos previstos

Modelo de enmienda/versionado, comandos, UI comparativa y auditoría.

### Backend

Crear nueva versión/enmienda con motivo, actor, diff seguro y política de campos editables.

### Base de datos

Versiones inmutables y puntero a vigente; no actualizar snapshot económico salvo proceso explícito separado.

### API

Comando de enmienda con optimistic lock; consulta de historial autorizada.

### Frontend

Comparación antes/después, motivo obligatorio y etiqueta de corregida.

### Reglas de negocio

No borrar original; no renumerar; efectos derivados requieren compensación explícita.

### Seguridad / permisos

Permiso clínico reforzado, ownership/política temporal y auditoría.

### Validaciones

Versión vigente, motivo, campos bloqueados, concurrencia y sesión finalizada.

### Casos borde

Dos enmiendas concurrentes; corrección tras presentación OS; dato clínico vs administrativo.

### Tests necesarios

Unitarios de policy; integración/versionado; frontend diff; E2E enmienda/historial.

### Criterios de aceptación

Se reconstruyen todas las versiones y no hay cambios económicos implícitos.

### Resultado esperado

Corrección clínica segura y auditable.

### Contexto que deja disponible para la etapa siguiente

Versión clínica definitiva y eventos compensables.


## Etapa AKINE-07.01 — Obligaciones económicas, snapshots y responsables

### Objetivo

Generar deuda desde prestaciones facturables, separando paciente/financiador/mixto sin mover caja.

### Requerimientos relacionados

RF-M18-001..007; RF-M17-004..005; RN-M18-001..005; sección 37.

### Dependencias

AKINE-06.05, AKINE-03.05 y AKINE-04.05.

### Estado actual detectado

NO IMPLEMENTADO — proyecto greenfield; el antecedente económico está acoplado a Turno/Atención.

### Instrucciones previas para el agente

- Modelos reales de sesión, convenio/arancel, autorización, deuda/cobro/caja y eventos.
- M18 y reglas monetarias/idempotencia.

### Archivos existentes a modificar

Rutas `billing`, `clinical` y `contracting` creadas en etapas previas.

### Archivos nuevos previstos

Agregado Obligación, componentes por responsable, snapshots, migraciones, API y UI.

### Backend

Resolver snapshot aplicado, componentes por responsable, saldo derivado, anulación y consumo/reversión idempotente de autorización.

### Base de datos

Decimal exacto, importe original, saldo derivado, referencia única de origen y snapshot.

### API

Consultas de deuda paciente/financiador y comando de anulación; no exponer float.

### Frontend

Detalle comprensible de responsabilidad y estados pendiente/parcial/pagada/anulada.

### Reglas de negocio

Deuda ≠ cobro ≠ caja; una sesión puede generar varias obligaciones; no cobrar saldo cero. Una obligación puede imputar un anticipo disponible compatible sin alterar el cierre clínico ni duplicar movimientos de Caja.

### Seguridad / permisos

Administrativo/admin; profesional solo consulta limitada si corresponde; tenant/consultorio.

### Validaciones

Idempotencia por prestación, importes, suma de componentes, vigencia/snapshot y estado.

### Casos borde

Coseguro; cambio a Particular; reintento de evento; sesión enmendada; autorización revertida.

### Tests necesarios

Unitarios de cálculo; integración transaccional/idempotente/decimal; frontend; E2E cierre→deuda.

### Criterios de aceptación

Una prestación genera exactamente las obligaciones correctas y caja permanece intacta.

### Resultado esperado

Cuenta por cobrar confiable.

### Contexto que deja disponible para la etapa siguiente

ObligationId, saldos y responsables.


## Etapa AKINE-07.02 — Cobros, medios, imputaciones y comprobantes

### Objetivo

Recibir dinero como cobro o anticipo, combinar medios, imputar obligaciones presentes o posteriores y emitir comprobante correlativo de forma atómica.

### Requerimientos relacionados

RF-M19-001..007; RN-M19-001..004; secciones 34–35 y 37.

### Dependencias

AKINE-07.01.

### Estado actual detectado

NO IMPLEMENTADO — proyecto greenfield; el UML legado no contempla imputación/anticipos modernos.

### Instrucciones previas para el agente

- Cobros/medios/comprobantes/imputaciones/caja reales, numeradores y tests concurrentes.
- M19 y contratos de error.

### Archivos existentes a modificar

Rutas `billing` de obligación/cobro y feature económica frontend.

### Archivos nuevos previstos

Entidades Cobro/Medio/Imputación/Anticipo/Comprobante, constraints, contratos y UI.

### Backend

Borrador, medios, distribución, anticipo no imputado, imputación posterior, confirmación idempotente, correlativo y anulación/reintegro compensatorio.

### Base de datos

Decimal exacto, suma consistente, saldo a favor derivado de ledger, vínculo de imputación, unique de comprobante por contexto y locks/versiones.

### API

Comandos específicos y retry-safe; comprobante recuperable sin crear nuevo cobro.

### Frontend

Selección de deudas, múltiples medios, registro identificado de anticipo cuando no existe deuda, saldo restante/a favor, confirmación y reimpresión.

### Reglas de negocio

Suma medios = total; cobro confirmado no se edita; anticipo no es obligación ni ingreso duplicado; la imputación posterior no vuelve a mover Caja; anulación o reintegro compensa imputaciones y movimientos sin borrar históricos.

### Seguridad / permisos

Operador económico contextual; correlativos y anulaciones con permiso reforzado.

### Validaciones

Saldo de obligación o anticipo, titular, contexto, medios, importe, moneda, comprobante, concurrencia e idempotency key.

### Casos borde

Pago parcial; sobrante; dos cajas imputan misma deuda; timeout tras confirmación; anulación con cierre de caja.

### Tests necesarios

Unitarios de distribución; integración concurrente/idempotente; frontend; E2E cobro/anulación.

### Criterios de aceptación

No hay sobreimputación, comprobantes duplicados ni efectos parciales.

### Resultado esperado

Cobro confirmado y trazable, listo para caja.

### Contexto que deja disponible para la etapa siguiente

PaymentConfirmed e imputaciones consistentes.


## Etapa AKINE-07.03 — Caja diaria y movimientos reales

### Objetivo

Abrir, operar y cerrar caja usando únicamente movimientos monetarios confirmados.

### Requerimientos relacionados

RF-M20-001..007; RN-M20-001..004; RF-M24-006.

### Dependencias

AKINE-07.02.

### Estado actual detectado

NO IMPLEMENTADO — proyecto greenfield; el antecedente describe registro en papel.

### Instrucciones previas para el agente

- Caja/movimientos/cobros/egresos reales, timezone/fecha de negocio y tests.
- M20 y DP-06.

### Archivos existentes a modificar

Rutas `billing` de cobros/caja y feature de Caja frontend.

### Archivos nuevos previstos

Agregados JornadaCaja/Movimiento, ledger, migraciones, contratos y UI.

### Backend

Apertura, ingresos/egresos, saldo teórico, cierre, diferencia y reversión trazable.

### Base de datos

Una caja abierta por política/contexto, fecha de negocio, movimiento inmutable y referencias de origen.

### API

Comandos y consulta paginada; cierre con optimistic/pessimistic protection.

### Frontend

Vista operativa densa, saldo esperado/declarado, filtros y cierre con motivo de diferencia.

### Reglas de negocio

Deuda no afecta caja; caja cerrada no se edita; solo dinero real cambia saldo.

### Seguridad / permisos

Administrativo/admin contextual; apertura/cierre/anulación auditadas.

### Validaciones

Caja abierta, medio, fecha, monto, duplicado de evento, diferencia y concurrencia.

### Casos borde

Cobro sin caja abierta; cierre concurrente; movimiento tardío; timezone; reversión posterior.

### Tests necesarios

Unitarios de saldo; integración concurrente/eventos; frontend; E2E apertura→cobro→cierre.

### Criterios de aceptación

Saldo se reconstruye desde movimientos y toda diferencia queda justificada.

### Resultado esperado

Caja diaria auditable.

### Contexto que deja disponible para la etapa siguiente

CashMovement y cierres históricos confiables.


## Etapa AKINE-07.04 — Presentaciones y cuenta corriente de financiadores

### Objetivo

Gestionar prestaciones elegibles, presentación, factura, rechazo, pago y conciliación sin perder la sesión original.

### Requerimientos relacionados

RF-M21-001..008; RN-M21-001..004.

### Dependencias

AKINE-07.01 y AKINE-07.03.

### Estado actual detectado

NO IMPLEMENTADO — proyecto greenfield; existe antecedente de proceso manual.

### Instrucciones previas para el agente

- Obligaciones financiador, convenios, autorizaciones, presentaciones, adjuntos, caja y exports reales.
- M21 y requisitos administrativos por financiador.

### Archivos existentes a modificar

Rutas `billing` de obligaciones/caja y feature de financiadores.

### Archivos nuevos previstos

Solo agregados/estados faltantes según arquitectura.

### Backend

Elegibilidad, agrupación, validación, confirmación, factura externa, débitos, pago parcial y conciliación.

### Base de datos

Estados separados, unicidad de inclusión incompatible, importes/saldos exactos y referencias.

### API

Listados filtrados/exportables y comandos de transición server-side.

### Frontend

Bandejas por estado, validaciones documentales, totales y conciliación de diferencias.

### Reglas de negocio

Prestado ≠ presentado ≠ facturado ≠ cobrado; pago recibido recién genera caja.

### Seguridad / permisos

Admin/administrativo autorizado; tenant/consultorio/financiador; PHI mínima en exports.

### Validaciones

Período, documentación, duplicado, total, estado y pago parcial.

### Casos borde

Rechazo parcial; sesión enmendada; factura externa duplicada; pago menor/mayor; reapertura.

### Tests necesarios

Unitarios de estados/totales; integración con caja; frontend; E2E presentar→pagar→conciliar.

### Criterios de aceptación

Cuenta corriente concilia saldos y la sesión permanece inmutable/referenciada.

### Resultado esperado

Ciclo de financiador completo.

### Contexto que deja disponible para la etapa siguiente

Estados de producción/facturación/cobro para reportes.


## Etapa AKINE-07.05 — Egresos y pagos a profesionales

### Objetivo

Registrar salidas y pagos a profesionales con comprobante, período/prestaciones y reversión en caja.

### Requerimientos relacionados

RF-M22-001..005; RN-M22-001..003; RF-M25 para comprobantes.

### Dependencias

AKINE-07.03 y AKINE-02.03.

### Estado actual detectado

NO IMPLEMENTADO — proyecto greenfield; las políticas de liquidación deberán configurarse por contrato/profesional.

### Instrucciones previas para el agente

- Egresos/caja/profesionales/adjuntos/categorías reales y tests.
- M22 y políticas de pago existentes.

### Archivos existentes a modificar

Rutas `billing` de caja/pagos y módulos de staff.

### Archivos nuevos previstos

Solo modelo de liquidación/referencia faltante, sin inventar regla remunerativa.

### Backend

Alta, confirmación, consulta, adjunto y anulación compensatoria.

### Base de datos

Decimal exacto, beneficiario, categoría, período/referencias y movimiento de caja.

### API

CRUD de borrador/comandos de confirmar/anular y filtros.

### Frontend

Formulario, filtros, comprobante y confirmación de impacto.

### Reglas de negocio

Egreso confirmado afecta caja; anular no borra; no modifica sesiones.

### Seguridad / permisos

Admin/administrativo económico; adjuntos heredados; auditoría.

### Validaciones

Beneficiario, categoría, importe, caja abierta, duplicado y comprobante.

### Casos borde

Pago parcial; profesional desvinculado; período superpuesto; caja cerrada; reversión.

### Tests necesarios

Unitarios; integración con caja/storage; frontend; E2E egreso/anulación.

### Criterios de aceptación

Salida y reversión se reflejan una vez y mantienen trazabilidad al beneficiario.

### Resultado esperado

Egresos económicos completos.

### Contexto que deja disponible para la etapa siguiente

Costos y pagos disponibles para reportes y clases.


## Etapa AKINE-07.06 — Reportes y tableros del MVP

### Objetivo

Entregar reportes operativos, clínicos y económicos M01–M27 reconciliados antes del gate del MVP.

### Requerimientos relacionados

RF-M23-001..010; RN-M23-001..007; RNF-M23-001..011. El requisito M07-009 ya queda cubierto por AKINE-03.01 y se consume aquí como dato maestro, sin reabrir su alcance.

### Dependencias

AKINE-07.04 y AKINE-07.05.

### Estado actual detectado

NO IMPLEMENTADO — proyecto greenfield; las fuentes core deben estar estabilizadas.

### Instrucciones previas para el agente

- Revisar fuentes transaccionales, permisos, snapshots, conciliación, paginación/export y SLO.
- Definir dueño y fórmula de cada indicador antes de construir proyecciones.

### Archivos existentes a modificar

Módulos core M01–M27 y estructura reporting creada por 00.01.

### Archivos nuevos previstos

Módulo reporting, proyecciones/queries, endpoints, dashboards Angular, exports y pruebas.

### Backend

Consultas/proyecciones para agenda, pacientes, clínica, autorizaciones, ingresos, caja, financiadores y profesionales, con filtros comunes.

### Base de datos

Índices/proyecciones medidos, sin duplicar fuentes de verdad; reconciliación y estrategia de refresco explícita.

### API

Reportes paginados y exports acotados/asíncronos cuando excedan el tiempo interactivo; OpenAPI versionado.

### Frontend

Dashboards accesibles, filtros persistibles, estados loading/empty/error y descarga con progreso.

### Reglas de negocio

Cada métrica define fuente, zona horaria, moneda, estados incluidos y fecha de corte; totales económicos deben reconciliar.

### Seguridad / permisos

Permisos por reporte, tenant/consultorio, minimización clínica y protección contra inferencia/exportación masiva.

### Validaciones

Rangos, filtros, volumen máximo, formato, zona horaria, moneda y acceso.

### Casos borde

Sin datos; período abierto; correcciones posteriores; export grande; timezone; datos anulados.

### Tests necesarios

Unitarios de fórmulas; integración/reconciliación; frontend; E2E dashboard/export; carga con dataset representativo.

### Criterios de aceptación

RF/CA M23 pasan, totales se reconcilian y ningún reporte cruza tenant o permisos.

### Resultado esperado

Visibilidad operativa/clinicoeconómica suficiente para aceptar el MVP.

### Contexto que deja disponible para la etapa siguiente

Catálogo de métricas, proyecciones e indicadores core estabilizados.

## Etapa AKINE-07.07 — Hardening de seguridad, privacidad y accesibilidad del MVP

### Objetivo

Auditar y cerrar transversalmente seguridad, privacidad y WCAG 2.1 AA para M01–M27 sin delegar correcciones al final global.

### Requerimientos relacionados

Todos los RNF de Seguridad/Usabilidad M01–M27; sección 32; M24; DP-03 y DP-08 resueltas.

### Dependencias

AKINE-07.06.

### Estado actual detectado

NO IMPLEMENTADO — controles deben existir incrementalmente y verificarse como conjunto.

### Instrucciones previas para el agente

- Revisar matriz de permisos, endpoints, queries, archivos, logs, exports, Angular, dependencias y auditoría.
- Ejecutar threat model por flujo crítico y pruebas negativas entre tenants.

### Archivos existentes a modificar

Solo rutas con brechas demostradas en backend/frontend/CI/infra/documentación.

### Archivos nuevos previstos

Pruebas negativas, reglas automáticas, reportes de accesibilidad y runbooks cuando falten.

### Backend

Cerrar BOLA/IDOR, mass assignment, rate limits, headers, validación, redacción de logs, secretos y auditoría.

### Base de datos

Verificar tenant/consultorio en claves/índices/consultas, privilegios mínimos, cifrado aplicable y backups.

### API

Errores seguros, límites, paginación, autorización por objeto y OpenAPI sin exposición sensible.

### Frontend

WCAG 2.1 AA, teclado/foco, labels, contraste, errores, responsive y ausencia de autorización confiada a UI.

### Reglas de negocio

Hardening no cambia semántica; toda excepción de acceso tiene policy, justificación y auditoría.

### Seguridad / permisos

Matriz completa por recurso/acción/contexto; tests negativos obligatorios.

### Validaciones

OWASP aplicable, dependencias, secretos, CSP/CORS/CSRF según arquitectura, PHI/PII y a11y automatizada/manual.

### Casos borde

ID enumerado; rol combinado; contexto cambiado; export sensible; URL expirada; sesión/token revocado.

### Tests necesarios

Unitarios de policies; integración negativa; DAST/SCA/SAST; frontend a11y; E2E entre tenants/roles.

### Criterios de aceptación

Sin hallazgos críticos/altos abiertos, WCAG AA en recorridos core y matriz de permisos respaldada por tests.

### Resultado esperado

MVP endurecido y apto para validación de carga/release.

### Contexto que deja disponible para la etapa siguiente

Threat model, matriz, excepciones y evidencia de seguridad/a11y.

## Etapa AKINE-07.08 — Rendimiento, concurrencia y observabilidad del MVP

### Objetivo

Validar invariantes concurrentes y SLO aprobados para M01–M27 con telemetría accionable.

### Requerimientos relacionados

RNF de Concurrencia/Rendimiento/Observabilidad M01–M27; secciones 34–35; DP-09 resuelta.

### Dependencias

AKINE-07.07.

### Estado actual detectado

NO IMPLEMENTADO — SLO aprobado sin baseline medido.

### Instrucciones previas para el agente

- Revisar instrumentación, queries, locks, jobs/outbox, dashboards y harness de carga.
- Usar un mix documentado de agenda, pacientes, sesión, economía y reportes.

### Archivos existentes a modificar

Rutas con evidencia de cuello, carrera o señal insuficiente.

### Archivos nuevos previstos

Escenarios de carga, dashboards, alertas y runbooks del MVP.

### Backend

Correlación request/evento, métricas RED, trazas, perfiles y correcciones medidas sin relajar invariantes.

### Base de datos

Planes/índices/locks medidos en MySQL 8.4 LTS; consultas tenant-safe y sin N+1.

### API

Medir p50/p95/p99, errores, timeouts, payloads e idempotencia de operaciones interactivas.

### Frontend

Medir LCP p75, bundles, rutas core y feedback de operaciones asíncronas.

### Reglas de negocio

Rendimiento no justifica duplicados, saldos negativos, overbooking ni pérdida de auditoría.

### Seguridad / permisos

Telemetría sin PHI/PII/secrets y dashboards restringidos.

### Validaciones

Disponibilidad ≥99,9 % proyectada/medida; p95 ≤300 ms; p99 ≤800 ms; 500 concurrentes; error técnico <1 %; LCP ≤2,5 s p75.

### Casos borde

Pico de agenda; último slot; cierre simultáneo; proveedor caído; DB lenta; export grande.

### Tests necesarios

Carga/soak/concurrencia con datos sintéticos; regresión; caos acotado de dependencias; verificación de alertas.

### Criterios de aceptación

SLO DP-09 medidos y aprobados o bloqueo explícito; invariantes sobreviven a carreras y alertas son accionables.

### Resultado esperado

MVP observable con rendimiento y capacidad conocidos.

### Contexto que deja disponible para la etapa siguiente

Resultados SLO, dashboards, alertas y runbooks verificables.

## Etapa AKINE-07.09 — Gate y release productivo del MVP M01–M27

### Objetivo

Liberar el MVP clínico-administrativo-económico de forma reproducible y abrir obligatoriamente la segunda entrega M28–M29.

### Requerimientos relacionados

Secciones 40–41; Definition of Done; decisiones DP-01–DP-09; M01–M27.

### Dependencias

AKINE-07.08.

### Estado actual detectado

NO IMPLEMENTADO — constituye el primer gate productivo greenfield.

### Instrucciones previas para el agente

- Revisar CI/CD de ambos repositorios, OpenAPI publicado, migraciones, backups, E2E, SLO, seguridad y aprobaciones clínica/legal.
- Preparar release notes, rollback, smoke y handoff operativo.

### Archivos existentes a modificar

Rutas reales de CI, deployment, documentación, migraciones y tests de ambos repositorios.

### Archivos nuevos previstos

Runbooks, release notes, checklist/evidencia del gate y artefactos faltantes.

### Backend

Build/versionado, health/readiness, configuración, OpenAPI, migraciones y artefacto Docker inmutable.

### Base de datos

Ensayo desde vacío y upgrade, backup/restore, rollback lógico, tiempos y verificación de datos.

### API

Contrato publicado compatible con la versión frontend; smoke y deprecaciones documentadas.

### Frontend

Build production/Docker, configuración, rutas core, manejo de errores y versión de cliente API fijada.

### Reglas de negocio

Aceptar el MVP no cierra el roadmap; tras la ventana de estabilización comienza F8 M28–M29 obligatoriamente.

### Seguridad / permisos

Secretos, scans/gates, mínimo privilegio, auditoría y accesos operativos aprobados.

### Validaciones

Pipelines verdes, Sonar, cobertura, E2E, migración/restore, SLO, OpenAPI, runbooks y aprobaciones clínica/legal.

### Casos borde

Rollback de un repo; frontend/backend incompatibles; migración larga; proveedor externo caído; incidente en estabilización.

### Tests necesarios

E2E core M01–M27, smoke post-deploy, restauración, compatibilidad cross-repo y ensayo de rollback.

### Criterios de aceptación

DoD completo, cero P1, SLO y aprobaciones cumplidos, despliegue/rollback ensayados y segunda entrega registrada como siguiente trabajo obligatorio.

### Resultado esperado

MVP productivo, estabilizado y formalmente entregado sin cerrar el plan.

### Contexto que deja disponible para la etapa siguiente

Baseline productivo, métricas, deuda aceptada y autorización para iniciar AKINE-08.01.

## Etapa AKINE-08.01 — Clases programadas y agenda unificada

### Objetivo

Programar un evento grupal único con capacidad/profesional/espacio y mostrarlo junto a turnos sin unificar su semántica.

### Requerimientos relacionados

RF-M12-009..013; RF-M28-001, RF-M28-005..006; RN-M28-001..002.

### Dependencias

AKINE-07.09, AKINE-05.03 y AKINE-02.07.

### Estado actual detectado

NO IMPLEMENTADO — primera etapa de la segunda entrega obligatoria posterior al MVP.

### Instrucciones previas para el agente

- Agenda/Turno/Oferta/espacio/profesional/calendario/UI reales.
- M12/M28 y cualquier abstracción de evento ya existente.

### Archivos existentes a modificar

Rutas `activity`, `scheduling` y `offering` según la sección 2.4.

### Archivos nuevos previstos

ClaseProgramada, proyección de agenda, migraciones, contratos y feature de clases.

### Backend

Crear/reprogramar/cancelar clase, capacidad efectiva, conflictos y proyección unificada de lectura.

### Base de datos

Evento único, estado, timestamps, oferta/profesional/espacio y constraints temporales.

### API

Comandos específicos de clase y consulta unificada discriminada; no polimorfismo prematuro destructivo.

### Frontend

Agenda diferencia visualmente turno/clase y acciones/capacidad propias.

### Reglas de negocio

Clase ≠ múltiples turnos; capacidad propia limitada por espacio; reprogramar conserva participantes.

### Seguridad / permisos

Staff autorizado; vista pública sin lista de participantes; tenant/consultorio.

### Validaciones

Oferta grupal activa, habilitación, espacio, rango, capacidad y conflictos.

### Casos borde

Cambio a espacio menor; clase con inscriptos; cancelación tardía; Turno y clase compiten por recurso.

### Tests necesarios

Unitarios; integración de conflictos/capacidad; frontend agenda; E2E programar/reprogramar/cancelar.

### Criterios de aceptación

Un evento grupal ocupa agenda una vez y no rompe Turno existente.

### Resultado esperado

Fase 2 de agenda grupal estable.

### Contexto que deja disponible para la etapa siguiente

ClassId, capacidad y proyección unificada.


## Etapa AKINE-08.02 — Inscripciones, cupos y lista de espera

### Objetivo

Reservar/cancelar cupos por Persona, gestionar espera y notificar liberaciones con concurrencia segura.

### Requerimientos relacionados

RF-M28-002..004; RF-M12-010; RF-M26-006..007; RN-M28-003..005.

### Dependencias

AKINE-08.01 y AKINE-03.01.

### Estado actual detectado

Solo requerimientos; no existe evidencia de inscripción/cupos.

### Instrucciones previas para el agente

- Clase/persona/capacidad/notificaciones/outbox/UI/tests concurrentes reales.
- M28 y sección 35.

### Archivos existentes a modificar

Rutas `activity`, `person` y `notification` creadas en etapas previas.

### Archivos nuevos previstos

Inscripción/lista de espera solo si no hay equivalente.

### Backend

Inscribir idempotente, contar estados que consumen cupo, cancelar, ordenar espera y ofrecer vacante.

### Base de datos

Unicidad clase-persona activa, locks/constraints de capacidad, posición/prioridad y expiración de oferta.

### API

Comandos retry-safe y detalle de cupos; error específico de clase completa.

### Frontend

Lista compacta, cupos en tiempo real, estado de espera y ventana de aceptación.

### Reglas de negocio

Espera no consume cupo; notificación no reserva salvo política; cancelar conserva historial.

### Seguridad / permisos

Autoservicio propio o staff; lista de participantes protegida.

### Validaciones

Clase activa, duplicado, capacidad, elegibilidad/persona y ventana.

### Casos borde

Último cupo concurrente; no responde aviso; múltiples cancelaciones; clase reprogramada.

### Tests necesarios

Unitarios de estados/prioridad; integración concurrente/idempotente; frontend; E2E espera→cupo.

### Criterios de aceptación

Nunca se supera capacidad efectiva y la prioridad es reproducible.

### Resultado esperado

Inscripción grupal segura.

### Contexto que deja disponible para la etapa siguiente

Participantes y estados individuales.


## Etapa AKINE-08.03 — Asistencia y operación de clases

### Objetivo

Registrar asistencia individual y detalle operativo de una clase, generando la consecuencia económica correspondiente sin crear turnos por participante.

### Requerimientos relacionados

RF-M13-007..009; RF-M28-007, RF-M28-009; RF-M18-008; RF-M19-009; RN-M28-001..009; RNF-M28-001..005.

### Dependencias

AKINE-08.02, AKINE-07.01 y AKINE-07.02.

### Estado actual detectado

NO IMPLEMENTADO — segunda entrega obligatoria greenfield.

### Instrucciones previas para el agente

- Revisar Clase, Inscripción, Persona, recepción, obligaciones/cobros y máquinas de estado.
- Mantener asistencia por participante separada del estado global de Clase.

### Archivos existentes a modificar

Módulos reales de clases, inscripciones, recepción y economía.

### Archivos nuevos previstos

Registro de participación/asistencia, UI operativa, migraciones, contratos y pruebas.

### Backend

Check-in grupal, presente/ausente/tarde/cancelado por participante, cierre operativo idempotente y obligación por clase individual cuando aplique.

### Base de datos

Participación única por Clase/Persona, timestamps reales, estado/historial, actor y referencia económica única.

### API

Detalle operativo paginado y comandos por participante/lote con resultados parciales explícitos.

### Frontend

Lista rápida de participantes, filtros, acciones masivas seguras, confirmaciones y resumen de cupo/asistencia.

### Reglas de negocio

Una Clase no crea un Turno por persona; asistencia individual es el hecho operativo; cerrar dos veces no duplica obligaciones.

### Seguridad / permisos

Instructor/administrativo contextual; PII mínima; tenant, consultorio y ownership de clase.

### Validaciones

Inscripción vigente, clase/estado/horario, duplicado, actor, política de no-show y referencia económica.

### Casos borde

Participante sin inscripción; lista de espera promovida; cierre parcial; clase cancelada; retry tras timeout.

### Tests necesarios

Unitarios de estados; integración idempotente/económica; frontend; E2E check-in-asistencia-cargo.

### Criterios de aceptación

Cada participante tiene un resultado único y trazable; retries no duplican asistencia, obligación ni cobro.

### Resultado esperado

Operación grupal completa con asistencia y consecuencias económicas individuales.

### Contexto que deja disponible para la etapa siguiente

Participación individual cerrada y elegible para derivación clínica o consumo de producto.

## Etapa AKINE-08.04 — Derivación de participante al circuito clínico

### Objetivo

Derivar una participación grupal a Historia Clínica, Caso y Plan únicamente cuando la Oferta sea clínica y exista autorización válida.

### Requerimientos relacionados

RF-M28-008; RF-M09-007..008; RF-M10-007..008; RF-M11-007..008.

### Dependencias

AKINE-08.03, AKINE-04.04 y AKINE-04.05.

### Estado actual detectado

NO IMPLEMENTADO — segunda entrega obligatoria greenfield.

### Instrucciones previas para el agente

- Revisar participación, Oferta clínica/no clínica, HC, Caso, Plan, autorización y permisos.
- No crear información clínica para actividades no clínicas.

### Archivos existentes a modificar

Módulos reales de clases, clínica, ofertas y autorizaciones.

### Archivos nuevos previstos

Adaptador/caso de uso de derivación, UI contextual, contratos y pruebas de privacidad.

### Backend

Resolver paciente/HC, seleccionar o crear Caso/Plan mediante comandos autorizados y vincular participación sin duplicar hechos.

### Base de datos

Vínculo auditable participación–contexto clínico con referencia única y sin copiar PHI al módulo grupal.

### API

Comando explícito de derivación y consulta de estado con errores de elegibilidad/permiso.

### Frontend

Acción visible solo para Oferta clínica y actor autorizado; selección de Caso/Plan y explicación de bloqueos.

### Reglas de negocio

Actividad no clínica nunca toca HC; derivación no equivale a Sesión realizada; una participación se deriva una sola vez por destino.

### Seguridad / permisos

Permiso clínico y relación asistencial; instructor no clínico sin acceso a HC; auditoría reforzada.

### Validaciones

Oferta clínica, PerfilPaciente, HC, Caso/Plan, autorización, consentimiento/configuración y duplicado.

### Casos borde

Persona aún no paciente; clase mixta; caso cerrado; autorización agotada; derivación concurrente.

### Tests necesarios

Unitarios de elegibilidad; integración tenant/idempotencia; frontend; E2E permitido/denegado; privacidad negativa.

### Criterios de aceptación

Solo participaciones clínicas autorizadas se vinculan y ninguna PHI queda expuesta a roles no clínicos.

### Resultado esperado

Puente seguro entre participación grupal y dominio clínico.

### Contexto que deja disponible para la etapa siguiente

Participación clínica vinculada a HC/Caso/Plan sin registrar aún la atención detallada.

## Etapa AKINE-08.05 — Atención clínica grupal por participante

### Objetivo

Documentar la atención clínica derivada de una clase para cada participante mediante Sesiones independientes y trazables.

### Requerimientos relacionados

RF-M14-011..015; RN-M14-001..011; RNF-M14-001..011.

### Dependencias

AKINE-08.04 y AKINE-06.05.

### Estado actual detectado

NO IMPLEMENTADO — segunda entrega obligatoria greenfield; la Sesión individual base ya debe existir.

### Instrucciones previas para el agente

- Revisar Sesión individual, participación derivada, profesional actuante, tratamiento, cierre y enmiendas.
- Reutilizar dominio clínico sin una Sesión compartida entre pacientes.

### Archivos existentes a modificar

Módulos reales de Sesión, clases, participación y UI clínica.

### Archivos nuevos previstos

Orquestación grupal y vistas de lote; no duplicar el agregado Sesión.

### Backend

Crear/iniciar/cerrar Sesión por participante con referencia única a participación, profesional actuante y operación de lote con resultados individualizados.

### Base de datos

Referencia única participación–Sesión y metadata de origen grupal; clínica permanece en tablas propias de Sesión.

### API

Comandos individuales y lote seguro; un fallo individual no oculta resultados de los demás.

### Frontend

Panel de atención grupal con acceso a cada ficha, progreso y errores por participante, sin mezclar datos clínicos.

### Reglas de negocio

Una Sesión por participante/caso; numeración dentro del Caso; clase cerrada no sustituye cierre clínico individual.

### Seguridad / permisos

Profesional clínico autorizado; aislamiento visual y de API entre pacientes; auditoría de lote e individual.

### Validaciones

Participación clínica, Caso/Plan, autorización, profesional, estado, duplicado y cierre obligatorio.

### Casos borde

Un participante sin autorización; atención parcial; cambio de profesional; retry de lote; enmienda posterior.

### Tests necesarios

Unitarios de orquestación; integración/idempotencia; frontend; E2E lote con éxito parcial; privacidad.

### Criterios de aceptación

Cada atención genera una Sesión independiente, numerada por Caso y accesible solo en su contexto.

### Resultado esperado

Atención clínica grupal documentada correctamente por paciente.

### Contexto que deja disponible para la etapa siguiente

Sesiones grupales cerradas disponibles para economía, reportes y auditoría.

## Etapa AKINE-08.06 — Productos, packs y venta inicial

### Objetivo

Definir productos de pack/pase por Oferta y venderlos generando obligación, cobro y caja sin crear todavía consumo de créditos.

### Requerimientos relacionados

RF-M29-001..002; RF-M18-009; RF-M19-008; RF-M20-008; RN-M29-001..009; RNF-M29-001..005.

### Dependencias

AKINE-08.03 y AKINE-07.03.

### Estado actual detectado

NO IMPLEMENTADO — segunda entrega obligatoria greenfield.

### Instrucciones previas para el agente

- Revisar Oferta, persona, obligaciones, cobros, caja, precios y snapshots.
- Distinguir producto de servicio de suscripción SaaS M01.

### Archivos existentes a modificar

Módulos reales de ofertas, personas y economía.

### Archivos nuevos previstos

Catálogo de productos, PaseServicio comprado, UI comercial, migraciones, API y pruebas.

### Backend

CRUD/versionado de producto, compra idempotente, snapshot comercial, obligación/cobro/caja y activación tras confirmación correspondiente.

### Base de datos

Producto/vigencia/precio/créditos; compra/Pase con titular, saldo inicial, estado y referencia económica única.

### API

Administración de productos y comando de compra con cotización/snapshot y retry-safe.

### Frontend

Catálogo, formulario de venta, resumen de precio/créditos/vigencia y resultado económico.

### Reglas de negocio

Producto ≠ Pase comprado; cambio de producto no altera compras; una compra no duplica obligación/caja.

### Seguridad / permisos

Administración de producto y operador económico separados; tenant/consultorio/titular/auditoría.

### Validaciones

Oferta, vigencia, precio/moneda, créditos, titular, duplicado, medios y contexto.

### Casos borde

Producto inactivo durante checkout; pago parcial/fallido; timeout; compra duplicada; precio futuro.

### Tests necesarios

Unitarios de snapshot/estado; integración económica/idempotente; frontend; E2E crear-vender-activar.

### Criterios de aceptación

Compra única, importes reconciliados y Pase activo con saldo inicial exacto.

### Resultado esperado

Productos vendibles y pases activos listos para ledger de créditos.

### Contexto que deja disponible para la etapa siguiente

Producto versionado, Pase comprado y referencia económica estable.

## Etapa AKINE-08.07 — Ledger y ciclo de créditos

### Objetivo

Consumir, devolver, ajustar y vencer créditos mediante un ledger inmutable y concurrentemente seguro.

### Requerimientos relacionados

RF-M29-003..006; RF-M19-010; RF-M20-010; reglas maestras 23–26.

### Dependencias

AKINE-08.06.

### Estado actual detectado

NO IMPLEMENTADO — segunda entrega obligatoria greenfield.

### Instrucciones previas para el agente

- Revisar Pase, asistencia, cancelaciones, economía, idempotencia, locks y auditoría.
- No tratar consumo de crédito como movimiento monetario.

### Archivos existentes a modificar

Módulos reales de pases, participación, cobros y caja.

### Archivos nuevos previstos

Ledger de créditos, políticas/commands, UI de movimientos y pruebas concurrentes.

### Backend

Consumir por asistencia, devolver según política, ajuste manual reforzado, vencimiento programado y saldo derivado.

### Base de datos

Movimientos inmutables con tipo, cantidad, origen único, actor, motivo y timestamps; saldo no negativo protegido.

### API

Consulta paginada de saldo/movimientos y comandos específicos idempotentes; sin edición de movimiento.

### Frontend

Estado del Pase, historial, consumo/devolución y ajuste manual con confirmación reforzada.

### Reglas de negocio

Saldo se deriva del ledger; retry no duplica; devolución no borra consumo; crédito no mueve Caja; reintegro monetario es operación separada.

### Seguridad / permisos

Consumo por operación autorizada; ajuste/reintegro con permiso reforzado; titular y tenant validados.

### Validaciones

Saldo, vigencia, Oferta compatible, origen, cantidad, política de devolución, motivo e idempotency key.

### Casos borde

Último crédito concurrente; asistencia revertida; Pase vencido; ajuste y consumo simultáneos; retry tardío.

### Tests necesarios

Unitarios de ledger; integración concurrente/idempotente; frontend; E2E consumir-devolver-vencer; reconciliación.

### Criterios de aceptación

Saldo nunca negativo, movimientos inmutables y Caja no cambia al consumir/devolver crédito salvo reintegro explícito.

### Resultado esperado

Pases con crédito operativo confiable y auditable.

### Contexto que deja disponible para la etapa siguiente

Ledger, saldo y policies de consumo/devolución estabilizados.

## Etapa AKINE-08.08 — Abonos, renovaciones y vencimientos

### Objetivo

Crear/vender abonos, validar cobertura periódica, generar obligaciones recurrentes y notificar vencimientos.

### Requerimientos relacionados

RF-M29-007..009; RF-M18-010..011; RF-M20-009; RF-M26-008.

### Dependencias

AKINE-08.07 y AKINE-01.02.

### Estado actual detectado

NO IMPLEMENTADO — segunda entrega obligatoria greenfield.

### Instrucciones previas para el agente

- Revisar productos/pases, ledger, obligaciones, cobros/caja, notificaciones, scheduler y políticas de cancelación.
- No reutilizar Suscripción SaaS M01.

### Archivos existentes a modificar

Módulos reales de productos, economía, notificaciones y jobs.

### Archivos nuevos previstos

AbonoServicio, ciclo periódico, UI, migraciones, API, jobs y pruebas de reloj.

### Backend

Alta/venta, período, renovación idempotente, obligación mixta, estado, cancelación, vencimiento y notificación programada.

### Base de datos

Abono, ciclos, próxima ejecución, movimientos/obligaciones únicas por período, estado e historial.

### API

Administración/venta/consulta/cancelación y simulación de próximo ciclo; jobs no expuestos públicamente.

### Frontend

Detalle de abono, períodos, cobertura, movimientos, próxima renovación y cancelación.

### Reglas de negocio

Un período genera como máximo una obligación; cancelación no borra ciclos; fallo de notificación no revierte renovación; reintegros compensan Caja.

### Seguridad / permisos

Operador económico; titular; tenant/consultorio; jobs con identidad técnica mínima y auditoría.

### Validaciones

Producto, fechas, período, cobertura, responsables mixtos, duplicado, estado y política de cancelación.

### Casos borde

Job duplicado; cobro fallido; renovación tras cancelación; cambio de precio; vencimiento en zona horaria; proveedor caído.

### Tests necesarios

Unitarios de ciclos/reloj; integración idempotente/económica; frontend; E2E alta-renovación-cancelación; notificación.

### Criterios de aceptación

No hay ciclos u obligaciones duplicadas y el estado/movimiento del abono es explicable y reconciliable.

### Resultado esperado

Abonos periódicos operativos, económicos y notificables.

### Contexto que deja disponible para la etapa siguiente

Productos M29 completos y movimientos listos para integración/reportes.

## Etapa AKINE-08.09 — Integración económica y auditoría de servicios

### Objetivo

Integrar clases/productos con presentaciones a financiadores, pagos profesionales, auditoría y conciliación final de la segunda entrega.

### Requerimientos relacionados

RF-M21-009..010; RF-M22-006..007; RF-M24-007..008.

### Dependencias

AKINE-08.05, AKINE-08.08, AKINE-07.04 y AKINE-07.05.

### Estado actual detectado

NO IMPLEMENTADO — segunda entrega obligatoria greenfield.

### Instrucciones previas para el agente

- Revisar prestaciones grupales, obligaciones, claims, liquidaciones profesionales y auditoría.
- Reconciliar sin reescribir hechos de la primera entrega.

### Archivos existentes a modificar

Módulos reales de servicios, financiadores, egresos/pagos y auditoría.

### Archivos nuevos previstos

Proyecciones/adaptadores de integración, UI de conciliación y pruebas.

### Backend

Incluir prestaciones grupales elegibles en claims/liquidaciones, distribuir importes y emitir eventos/auditoría idempotentes.

### Base de datos

Referencias únicas entre participación/prestación y líneas de presentación/liquidación; snapshots e índices.

### API

Consultas de conciliación y errores de elegibilidad; contratos compatibles con consumidores core.

### Frontend

Detalle de origen grupal en presentaciones, liquidaciones y auditoría, con filtros.

### Reglas de negocio

Una prestación se presenta/liquida una vez por versión válida; ajustes se compensan; productos no prestados no se presentan como atención.

### Seguridad / permisos

Roles económicos y auditoría; tenant/consultorio/profesional; minimización clínica.

### Validaciones

Elegibilidad, profesional, convenio, importes, período, duplicado y estado.

### Casos borde

Corrección clínica posterior; claim cerrado; instructor múltiple; obligación mixta; reversión.

### Tests necesarios

Unitarios de elegibilidad/distribución; integración/reconciliación; frontend; E2E clase→claim/pago; auditoría.

### Criterios de aceptación

Prestaciones y productos se reconcilian sin duplicados y todos los importes conservan origen/snapshot.

### Resultado esperado

Segunda entrega integrada con economía y auditoría core.

### Contexto que deja disponible para la etapa siguiente

Fuentes completas para reportes y release final.

## Etapa AKINE-09.01 — Ampliar reportes para clases y productos

### Objetivo

Extender el reporting core con métricas de clases, participación, derivación clínica, pases, créditos y abonos, preservando la semántica y reconciliación existentes.

### Requerimientos relacionados

M23 sobre nuevas fuentes M28–M29; RF-M28-009; RF-M29-009; RN/RNF de reporting, privacidad y economía aplicables.

### Dependencias

AKINE-08.09 y AKINE-07.06.

### Estado actual detectado

NO IMPLEMENTADO — el reporting core se crea en AKINE-07.06 y debe ampliarse sin alterar sus métricas históricas.

### Instrucciones previas para el agente

- Revisar reporting/proyecciones/exports/UI/tests creados en AKINE-07.06.
- Revisar fuentes de Clase, Participación, Sesión grupal, Pase, crédito y Abono.

### Archivos existentes a modificar

Rutas reales de reporting core y módulos M28–M29.

### Archivos nuevos previstos

Proyecciones o export jobs únicamente para las nuevas fuentes de la segunda entrega.

### Backend

Extender KPIs, filtros y exports con definiciones explícitas y reconciliación de participación, economía y ledger.

### Base de datos

Índices/proyecciones medidos para M28–M29, sin duplicar fuentes ni recalcular métricas core.

### API

Agregar filtros Oferta/Clase/Producto/estado y contratos compatibles con denominadores/unidades explícitos.

### Frontend

Ampliar dashboards con cupo, asistencia, conversión, utilización de créditos, vencimientos e ingresos.

### Reglas de negocio

Créditos ≠ dinero; clase ≠ sesión; participación y sesión clínica no se duplican; importes reconcilian con obligación/caja.

### Seguridad / permisos

Tenant/consultorio, permisos por dominio y minimización clínica/económica en dashboards y exports.

### Validaciones

Rangos, filtros, timezone, moneda, volumen y conciliación entre participación, ledger, obligación y caja.

### Casos borde

Sin clases/productos; asistencia corregida; crédito devuelto; abono cancelado; período abierto; export grande.

### Tests necesarios

Unitarios de fórmulas; integración/reconciliación; frontend; E2E reporte/export M28–M29; carga incremental.

### Criterios de aceptación

Métricas M28–M29 reconciliadas, seguridad por contexto y rendimiento dentro del presupuesto sin regresión core.

### Resultado esperado

Reporting confiable de ambas entregas.

### Contexto que deja disponible para la etapa siguiente

Métricas completas, volúmenes y fuentes para hardening final.

## Etapa AKINE-09.02 — Hardening de seguridad, privacidad y accesibilidad

### Objetivo

Auditar y cerrar brechas transversales de tenant, permisos, secretos, archivos, PHI/PII y WCAG.

### Requerimientos relacionados

Todos los RNF de Seguridad/Usabilidad; sección 32; RN-M24; objetivo WCAG 2.1 AA.

### Dependencias

AKINE-09.01.

### Estado actual detectado

NO IMPLEMENTADO — segunda entrega completa pendiente de hardening final.

### Instrucciones previas para el agente

- Security config/policies, queries, logs, storage, frontend y suites reales.
- Threat model/scan/a11y existentes.

### Archivos existentes a modificar

Rutas de ambos repositorios donde los controles de seguridad/a11y detecten brechas demostradas.

### Archivos nuevos previstos

Tests/policies/documentación faltantes según patrones reales.

### Backend

Revisar autorización objeto-fila, mass assignment, validación, redacción de logs y headers/secretos.

### Base de datos

Roles mínimos, cifrado/backup según infraestructura y aislamiento de consultas.

### API

Errores sin filtración, límites, CORS/CSRF según mecanismo y archivos seguros.

### Frontend

Teclado, foco, labels, contraste, errores, responsive y no almacenar secretos indebidamente.

### Reglas de negocio

Backend autoridad; soporte/admin no accede a clínica por defecto; auditoría no guarda secretos.

### Seguridad / permisos

Es el alcance central: matriz completa, pruebas negativas y threat model.

### Validaciones

Cross-tenant, IDOR, escalation, uploads, export, session fixation/replay y accesibilidad automatizada/manual.

### Casos borde

Usuario multirol; recurso histórico inactivo; URL firmada filtrada; sesión expirada durante edición.

### Tests necesarios

Unitarios de policy; integración negativa por matriz; frontend a11y; E2E de accesos; scans disponibles.

### Criterios de aceptación

Sin fugas P1/P2 conocidas, matriz cubierta y flujos críticos WCAG AA según herramientas/QA disponible.

### Resultado esperado

Superficie segura y accesible.

### Contexto que deja disponible para la etapa siguiente

Threat model, matriz de controles y deuda residual priorizada.


## Etapa AKINE-09.03 — Concurrencia, rendimiento y observabilidad

### Objetivo

Validar invariantes concurrentes, medir SLO real y asegurar trazas/métricas/logs accionables.

### Requerimientos relacionados

RNF de Concurrencia/Rendimiento/Observabilidad; secciones 34–35; target p95 histórico.

### Dependencias

AKINE-09.02.

### Estado actual detectado

NO IMPLEMENTADO — SLO DP-09 aprobado, pendiente de medición sobre el sistema completo.

### Instrucciones previas para el agente

- Instrumentación, queries, locks, colas/outbox, dashboards y harness de carga reales.
- Invariantes de turno, espacio, sesión, autorización, cobro, caja y créditos.

### Archivos existentes a modificar

Rutas de ambos repositorios con evidencia de cuello, carrera o señal insuficiente.

### Archivos nuevos previstos

Tests de carga/dashboards/runbooks conforme a infraestructura real.

### Backend

Optimizar solo con perfil; correlación de requests/eventos; métricas de negocio técnicas.

### Base de datos

Planes de ejecución/índices/locks medidos; no sobreindexar por intuición.

### API

Latencias/errores por operación, idempotency y timeouts consistentes.

### Frontend

Medir carga de rutas, payloads y feedback de operaciones lentas.

### Reglas de negocio

No sacrificar invariantes por rendimiento; retry no duplica efectos.

### Seguridad / permisos

Telemetría sin PHI/secretos; acceso restringido a dashboards.

### Validaciones

Carreras críticas, p50/p95/p99, error rate, saturación, consultas N+1 y resiliencia de consumidores.

### Casos borde

Pico de agenda; cierre simultáneo; último cupo/crédito; proveedor email caído; DB lenta.

### Tests necesarios

Concurrencia de cada invariante; carga representativa; chaos/fallo acotado si existe harness; regresión.

### Criterios de aceptación

Invariantes sobreviven a carreras y SLO queda medido/aprobado o con deuda explícita.

### Resultado esperado

Sistema observable con rendimiento conocido.

### Contexto que deja disponible para la etapa siguiente

SLO, alertas y runbooks verificables.


## Etapa AKINE-09.04 — Release candidate, E2E, CI/CD y documentación

### Objetivo

Cerrar un release reproducible con flujos críticos E2E, migraciones seguras, pipeline, OpenAPI y handoff operativo.

### Requerimientos relacionados

Secciones 40–41; entregables y criterios globales de `C:\Users\santo\.codex\.chatgpt-projects\g-p-693731f0f05c81919b990f8a5f1c435e\sources\AKINE.pdf`.

### Dependencias

AKINE-09.03.

### Estado actual detectado

NO IMPLEMENTADO — release final pendiente; herramientas y SLO ya están aprobados.

### Instrucciones previas para el agente

- Pipeline, contenedores, deployment, backups, OpenAPI, docs y suites reales.
- Definition of Done y comandos canónicos de 00.02.
- Evidencia de aprobación clínica y legal exigida por DP-08 para las reglas incluidas en la entrega.

### Archivos existentes a modificar

Rutas de CI, Docker, OpenAPI, documentación y tests creadas desde AKINE-00.01.

### Archivos nuevos previstos

Solo gaps necesarios para release conforme a plataforma existente.

### Backend

Build reproducible, health/readiness, configuración por entorno y OpenAPI sincronizada.

### Base de datos

Ensayo de migración/rollback lógico, backup/restore y compatibilidad durante deploy.

### API

Contrato versionado, ejemplos/errores y no breaking changes no documentados.

### Frontend

Build production, rutas críticas, fallback/error y artefacto versionado.

### Reglas de negocio

Los E2E prueban flujos, no sustituyen unit/integración; release no borra históricos.

### Seguridad / permisos

Secretos fuera de repo, scans/gates y acceso mínimo de despliegue.

### Validaciones

Pipeline limpio, artefactos, migración, restore, smoke, compatibilidad, documentación y aprobaciones clínica/legal vigentes.

### Casos borde

Deploy con versión anterior viva; migración larga; rollback de app; servicio externo caído.

### Tests necesarios

E2E: tenant/login; consultorio/personal; paciente/cobertura; caso/plan; turno/check-in/sesión; deuda/cobro/caja; clase/pase; reportes. Smoke post-deploy.

### Criterios de aceptación

Pipeline verde, migraciones ensayadas, OpenAPI coherente, E2E críticos verdes, runbook usable y aprobación clínica/legal documentada sin bloqueos abiertos.

### Resultado esperado

Release candidate desplegable y mantenible.

### Contexto que deja disponible para la etapa siguiente

Baseline de producción, versionado y backlog residual.


# 14. Dependencias entre etapas

| Etapa | Dependencias directas |
|---|---|
| 00.01 | — |
| 00.02 | 00.01 |
| 00.03 | 00.02 |
| 01.01 | 00.03 |
| 01.02 | 01.01 |
| 01.03 | 01.02 |
| 02.01 | 01.03 |
| 02.02 | 02.01 |
| 02.03 | 01.03, 02.01 |
| 02.04 | 02.03 |
| 02.05 | 01.03 |
| 02.06 | 02.01, 02.05 |
| 02.07 | 02.02, 02.04, 02.06 |
| 03.01 | 01.03 |
| 03.02 | 03.01 |
| 03.03 | 01.03, 02.01 |
| 03.04 | 03.01, 03.03 |
| 03.05 | 02.05, 02.07, 03.03 |
| 03.06 | 03.04, 03.05 |
| 04.01 | 03.02 |
| 04.02 | 03.02, 04.01 |
| 04.03 | 02.07, 04.02 |
| 04.04 | 02.07, 04.03 |
| 04.05 | 03.06, 04.04 |
| 05.01 | 02.04, 02.07, 03.04 |
| 05.02 | 03.01, 05.01 |
| 05.03 | 05.02 |
| 05.04 | 03.06, 04.05, 05.03 |
| 06.01 | 04.05, 05.04 |
| 06.02 | 06.01 |
| 06.03 | 06.02 |
| 06.04 | 02.02, 02.05, 06.03 |
| 06.05 | 06.04 |
| 06.06 | 06.05 |
| 07.01 | 03.05, 04.05, 06.05 |
| 07.02 | 07.01 |
| 07.03 | 07.02 |
| 07.04 | 07.01, 07.03 |
| 07.05 | 02.03, 07.03 |
| 07.06 | 07.04, 07.05 |
| 07.07 | 07.06 |
| 07.08 | 07.07 |
| 07.09 | 07.08 |
| 08.01 | 02.07, 05.03, 07.09 |
| 08.02 | 03.01, 08.01 |
| 08.03 | 07.01, 07.02, 08.02 |
| 08.04 | 04.04, 04.05, 08.03 |
| 08.05 | 06.05, 08.04 |
| 08.06 | 07.03, 08.03 |
| 08.07 | 08.06 |
| 08.08 | 01.02, 08.07 |
| 08.09 | 07.04, 07.05, 08.05, 08.08 |
| 09.01 | 07.06, 08.09 |
| 09.02 | 09.01 |
| 09.03 | 09.02 |
| 09.04 | 09.03 |

# 15. Ruta crítica

Con un único equipo y sin paralelismo, la secuencia serial segura del MVP es:

`00.01 → 00.02 → 00.03 → 01.01 → 01.02 → 01.03 → 02.01 → 02.02 → 02.03 → 02.04 → 02.05 → 02.06 → 02.07 → 03.01 → 03.02 → 03.03 → 03.04 → 03.05 → 03.06 → 04.01 → 04.02 → 04.03 → 04.04 → 04.05 → 05.01 → 05.02 → 05.03 → 05.04 → 06.01 → 06.02 → 06.03 → 06.04 → 06.05 → 06.06 → 07.01 → 07.02 → 07.03 → 07.04 → 07.05 → 07.06 → 07.07 → 07.08 → 07.09`.

El gate de primera salida es **AKINE-07.09**. Ninguna etapa M28–M29 puede comenzar antes de ese gate; después, la continuación obligatoria es:

`08.01 → 08.02 → 08.03 → 08.04 → 08.05 → 08.06 → 08.07 → 08.08 → 08.09 → 09.01 → 09.02 → 09.03 → 09.04`.

Con varios equipos pueden ejecutarse ramas en paralelo únicamente cuando la tabla de la sección 14 lo permita. La ruta crítica de calendario debe recalcularse al asignar capacidad real; la secuencia anterior es el baseline conservador y contiene el cierre completo de prerrequisitos, no solo una cadena ilustrativa.

# 16. Riesgos técnicos

| Riesgo | Probabilidad | Impacto | Mitigación |
|---|---:|---:|---|
| Desincronización entre repositorios backend/frontend | Media/alta | Alto | OpenAPI canónico versionado, cliente generado, contract tests y matriz de compatibilidad |
| Erosión del monolito modular y ciclos entre dominios | Media | Alto | Ownership de tablas/APIs internas y ArchUnit en CI desde 00.02 |
| Omisión de tenant/consultorio en query, cache, job o export | Media | Crítico | Contexto server-side, índices/constraints con alcance y tests negativos por etapa |
| Doble obligación, cobro, caja, autorización o crédito por retry | Alta | Crítico | Idempotency keys, referencias únicas, ledger y outbox transaccional |
| Carreras de último slot, Espacio, cupo, saldo o correlativo | Alta | Alto | Constraints MySQL, transacciones, locks medidos y pruebas concurrentes |
| Consultas lentas en agenda, timeline, 360 o reportes | Media | Alto | Paginación, límites, EXPLAIN/índices medidos y SLO DP-09 |
| Migración Flyway incompatible entre versiones desplegadas | Media | Crítico | Expandir–migrar–contraer, ensayo N-1/N y rollback de aplicación |
| Adjuntos inseguros o PHI/PII en logs/telemetría | Media | Crítico | Metadata/URLs temporales, scanning, redacción allowlist y revisión de señales |
| Proveedor de email/notificación caído | Alta | Medio | Outbox, backoff, estado agotado, reintento manual e idempotencia |
| Falsa confianza por cobertura porcentual | Media | Alto | Mutation/risk-based testing, quality gate de código nuevo y E2E críticos |
| Feature flags o contratos abandonados | Media | Medio | Owner, fecha de retiro, telemetría y etapa explícita de limpieza |
| Backup no restaurable o runbook incompleto | Baja/media | Crítico | Ensayos automáticos/periódicos de restore en 07.09 y 09.04 |

# 17. Riesgos funcionales

- Configurar incorrectamente qué orden, autorización o documento exige cada Financiador/Plan/Convenio/Prestación; requiere aprobación clínica/legal DP-08.
- Confundir rol de seguridad, profesión, disciplina y habilitación de Oferta; deben permanecer conceptos separados.
- Cancelar una serie con alcance equivocado; la UI debe previsualizar turnos afectados y DP-04 prohíbe borrado.
- Confundir anticipo, obligación, cobro, crédito y movimiento de Caja; DP-06 exige ledgers y eventos separados.
- Corregir una Sesión ya presentada/facturada sin compensar consecuencias económicas y administrativas.
- Aplicar devoluciones de crédito/dinero o no-show sin una política versionada por Oferta/producto.
- Mezclar Persona no clínica, Usuario y PerfilPaciente o exigir datos clínicos innecesarios.
- Exponer HC a otro tenant o a un rol sin relación asistencial; DP-03 fija alcance organizacional y auditoría.
- Retrasar indefinidamente M28–M29 después del MVP; DP-07 y el gate 07.09 obligan a iniciar la segunda entrega.
- Incorporar “seguimiento de familia”, alquiler, domicilio o derivación externa sin cambio formal de alcance: no forman parte del canónico M01–M29 actual.

# 18. Estrategia de migraciones

AKINE inicia sin datos productivos ni esquema legado. La estrategia aplica a la evolución entre releases del nuevo producto:

1. **Baseline mínimo:** AKINE-00.01 configura Flyway y una primera migración técnica sobre MySQL 8.4 LTS; las tablas funcionales se agregan únicamente en su etapa.
2. **Inmutabilidad:** una migración aplicada no se edita ni renumera. Toda corrección se realiza mediante una nueva migración versionada.
3. **Prueba desde vacío:** CI aplica el historial completo sobre una base MySQL limpia mediante Testcontainers.
4. **Prueba de actualización:** desde la primera release, CI/ensayo de release actualiza una copia sintética de N-1 a N y verifica invariantes, tiempos y compatibilidad.
5. **Expandir–migrar–contraer:** columnas/tablas nuevas y compatibles primero; backfill idempotente después; constraints tras validar; retiro destructivo en una etapa independiente y posterior a la ventana de compatibilidad.
6. **Dos repositorios:** backend mantiene migraciones y compatibilidad API; frontend puede desplegar antes/después dentro de la matriz soportada sin depender de una migración atómica.
7. **Rollback:** se revierte la aplicación a una versión compatible o se compensa funcionalmente; no se confía en down migrations destructivas en producción.
8. **Datos de referencia:** seeds de catálogos mínimos son versionados, idempotentes y sintéticos; datos de prueba no se ejecutan en producción.
9. **MySQL:** medir locks y duración de DDL, crear índices compatibles y evitar conversiones de tabla bloqueantes sin estrategia operacional.
10. **Release:** antes de 07.09 y 09.04 se ensayan backup, restore, migración, smoke y reconciliación.

Si en el futuro se importa información de otro sistema, se planificará como iniciativa separada con perfilado, mapeo, staging, reconciliación y rollback; este plan no inventa una migración legado inexistente.

# 19. Estrategia de testing

## 19.1 Por cada RF

Cada RF debe vincularse en el gestor de trabajo y en los tests con sus `CA-Mxx-nnn-*`. Como mínimo se cubren: happy path, obligatorios, formatos y límites, permiso ausente, otro tenant/consultorio, estado incompatible, concurrencia, idempotencia/reintento, referencias inactivas, auditoría, paginación/filtros y error interno sin filtración. Si un caso no aplica, se registra la justificación; no se elimina silenciosamente.

## 19.2 Capas

- **Unitarios:** reglas de estados, intervalos, cálculos decimales, elegibilidad y policies puras.
- **Integración backend:** JUnit/Spring Boot Test con MySQL 8.4 mediante Testcontainers para constraints, transacciones, Flyway, storage, outbox y autorización de objeto.
- **Arquitectura:** ArchUnit impide ciclos, acceso a repositorios/tablas de otro módulo y dependencias no aprobadas.
- **Frontend:** formularios, estados de carga/error/vacío, guards de UX, accesibilidad, cliente generado y componentes dinámicos.
- **E2E Playwright:** verticales de negocio y permisos críticos sobre un entorno reproducible; no detalles internos.
- **Contrato:** el backend valida/publica OpenAPI; contract tests verifican implementación y compatibilidad; el frontend regenera el cliente desde una versión fijada y falla si existen cambios no incorporados.
- **Concurrencia:** último slot, box, correlativo, autorización, saldo, comprobante, cierre de caja, cupo y crédito.
- **Migración:** historial completo desde base vacía y actualización sintética N-1→N; backfill idempotente, rollback de aplicación/compensación y reconciliación.
- **Rendimiento:** agenda, Paciente 360, timeline, reportes y exports con volumen representativo; 07.08 y 09.03 validan los SLO de DP-09.
- **Seguridad y privacidad:** OWASP, dependencias, secretos, JWT/refresh, autorización horizontal/vertical, adjuntos, redacción de logs y exportaciones.
- **Resiliencia:** retries, timeouts, circuitos externos, outbox, recuperación de jobs y fallos parciales.

## 19.3 Datos de prueba

Usar factories/fixtures sintéticos por tenant y consultorio; incluir referencias inactivas, vigencias, zona horaria de Argentina, importes decimales, PII ficticia y casos clínicos separados. Deben existir datasets pequeños deterministas para CI y datasets de volumen reproducibles para rendimiento. Nunca usar datos reales en suites automáticas ni copiar producción sin anonimización aprobada.

## 19.4 Gates cuantitativos

- Cobertura de código nuevo ≥80 % y ≥90 % en seguridad, clínica y economía.
- Cero hallazgos blocker/critical de SonarQube en código nuevo y duplicación ≤3 %.
- Todo CA crítico tiene un test automático o evidencia manual aprobada cuando la automatización no sea técnicamente razonable.
- Las rutas críticas E2E, tests de aislamiento tenant, tests de arquitectura y contract tests son bloqueantes en CI.
- Los resultados de disponibilidad, latencia, concurrencia, LCP y tasa de error se comparan con DP-09 en los gates 07.08 y 09.03.

# 20. Estrategia de integración

- Mantener transacciones locales para invariantes fuertes: reserva, correlativo, consumo, imputación, comprobante y caja.
- Publicar efectos derivados únicamente después del commit. El outbox transaccional es obligatorio para notificaciones, integraciones externas y efectos recuperables que no puedan perderse; el procesamiento debe admitir backoff, reintento manual y estado agotado.
- Mantener contratos internos explícitos entre módulos del monolito. Un módulo no accede a tablas, entidades JPA ni repositorios privados de otro módulo.
- Diseñar consumidores y handlers idempotentes para HC, obligaciones, notificaciones, reportes, presentaciones y créditos.
- OpenAPI del backend como contrato canónico verificable y artefacto versionado; cliente TypeScript frontend generado desde una versión fijada; compatibilidad hacia atrás durante migraciones.
- Frontend consume errores funcionales tipados (`VALIDATION_ERROR`, `NOT_FOUND`, `FORBIDDEN`, `CONFLICT`, `BUSINESS_RULE_VIOLATION`, `INTERNAL_ERROR`).
- Eventos internos base: `OrganizationCreated`, `MembershipActivated`, `AvailabilityChanged`, `AppointmentBooked`, `PatientCheckedIn`, `SessionCompleted`, `EconomicObligationCreated`, `PaymentConfirmed`, `CashMovementCreated` y `ClaimSubmitted`. Cada evento define versión, emisor, payload mínimo, idempotency key, trazabilidad y política de evolución.
- Propagar `traceId`, actor y contexto tenant/consultorio sin incluir PHI/PII en atributos de telemetría.
- No convertir el sistema en microservicios sin evidencia arquitectónica y operacional.

# 21. Orden recomendado de ejecución

1. Ejecutar 00.01 para crear los dos repositorios greenfield y verificar sus baselines reproducibles.
2. Completar 00.02–00.03, formalizar DP-01–DP-09 como ADRs aceptadas y fijar la matriz mínima de permisos.
3. Ejecutar fases F1–F4 en orden, permitiendo paralelismo solo entre etapas sin dependencia.
4. Entregar agenda/recepción F5 y luego sesiones F6.
5. Completar economía/reportes F7 y ejecutar hardening, rendimiento y release del MVP en F8.
6. Cerrar y estabilizar el MVP M01–M27 mediante 07.09; registrar la aceptación sin archivar ni finalizar el roadmap.
7. Iniciar obligatoriamente F9 para M28–M29 una vez cumplido el gate 07.09; no constituyen backlog opcional.
8. Ejecutar F10 como consolidación y release final de la segunda entrega, manteniendo controles de seguridad/testing dentro de cada etapa.

# 22. Definition of Done

Una etapa está terminada solo si:

- las rutas, símbolos y contratos reales de ambos repositorios afectados fueron inspeccionados y registrados;
- alcance y dependencias se respetaron;
- migración desde vacío y N-1→N, backfill y estrategia de rollback/compensación aplicables están probados;
- backend, DB, API y frontend necesarios están integrados;
- tenant, consultorio, rol, permiso, ownership y privacidad están cubiertos;
- estados/transiciones son server-side;
- errores funcionales son específicos y seguros;
- auditoría, idempotencia y concurrencia aplicables están probadas;
- unitarios, integración con MySQL, ArchUnit, frontend, contrato y E2E aplicables están verdes;
- cada RF/RN/RNF/CA afectado está enlazado a evidencia de implementación y test;
- OpenAPI canónico fue actualizado, validado, publicado con versión compatible y el cliente TypeScript fue regenerado/fijado en frontend;
- pipelines independientes de backend y frontend están verdes y SonarQube cumple DP-09;
- observabilidad, métricas, trazas, logs redactados, alertas y runbook aplicables fueron incorporados;
- las reglas clínicas, de privacidad, retención y documentación incluidas en una salida productiva cuentan con aprobación clínica/legal documentada;
- históricos, invariantes económicas y contratos compatibles no se rompieron;
- no existen defectos P1/P2 abiertos que contradigan el criterio de salida y toda deuda aceptada tiene owner y fecha;
- los SLO de DP-09 se cumplen cuando la etapa es 07.08, 07.09, 09.03 o 09.04;
- este plan se actualizó con estado y contexto para la siguiente etapa.

# 23. Tabla final de trazabilidad

| Etapa | Sprint | Módulo | Requerimientos | Depende de | Backend | Frontend | DB | Tests | Estado actual |
|---|---|---|---|---|---|---|---|---|---|
| 00.01 | S00 | Baseline | Greenfield, stack, §§40–44 | — | Crear | Crear | Inicial | Smoke/I | NO IMPLEMENTADO |
| 00.02 | S00 | Arquitectura | RNF M01–M29, §§32–41 | 00.01 | Sí | Sí | Sí | Arch/I/F | NO IMPLEMENTADO |
| 00.03 | S00 | Decisiones | DP-01–DP-09, reglas 1–30 | 00.02 | ADR | ADR | Sin cambio | Escenarios | NO IMPLEMENTADO |
| 01.01 | S01 | M01 | RF/RN/RNF M01 | 00.03 | Sí | Sí | Sí | U/I/F/E2E | NO IMPLEMENTADO |
| 01.02 | S01 | M02/M26 | Identidad, auth, recuperación, notificaciones | 01.01 | Sí | Sí | Sí | U/I/Sec/E2E | NO IMPLEMENTADO |
| 01.03 | S01 | M01/M02/M05/M24 | Memberships, RBAC, auditoría | 01.02 | Sí | Sí | Sí | U/I/Sec/E2E | NO IMPLEMENTADO |
| 02.01 | S02 | M03 | Consultorio y onboarding DP-01 | 01.03 | Sí | Sí | Sí | U/I/F/E2E | NO IMPLEMENTADO |
| 02.02 | S02 | M04 | Espacios y capacidad | 02.01 | Sí | Sí | Sí | U/I/F/E2E | NO IMPLEMENTADO |
| 02.03 | S02 | M05/M26 | Colaboradores e invitaciones | 01.03, 02.01 | Sí | Sí | Sí | U/I/F/E2E | NO IMPLEMENTADO |
| 02.04 | S02 | M05 | Disponibilidad y excepciones | 02.03 | Sí | Sí | Sí | U/I/F/E2E | NO IMPLEMENTADO |
| 02.05 | S03 | M06 | Especialidades/prácticas | 01.03 | Sí | Sí | Sí | U/I/F/E2E | NO IMPLEMENTADO |
| 02.06 | S03 | M03/M06/M27 | Servicio y Oferta | 02.01, 02.05 | Sí | Sí | Sí | U/I/F/E2E | NO IMPLEMENTADO |
| 02.07 | S03 | M04/M05/M27 | Configuración y habilitaciones | 02.02, 02.04, 02.06 | Sí | Sí | Sí | U/I/F/E2E | NO IMPLEMENTADO |
| 03.01 | S04 | M07 | Persona y PerfilPaciente | 01.03 | Sí | Sí | Sí | U/I/F/E2E | NO IMPLEMENTADO |
| 03.02 | S04 | M07/M25 | Paciente 360 y adjuntos | 03.01 | Sí | Sí | Sí | U/I/F/E2E | NO IMPLEMENTADO |
| 03.03 | S04 | M15 | Financiadores y planes | 01.03, 02.01 | Sí | Sí | Sí | U/I/F/E2E | NO IMPLEMENTADO |
| 03.04 | S04 | M08 | Coberturas de paciente | 03.01, 03.03 | Sí | Sí | Sí | U/I/F/E2E | NO IMPLEMENTADO |
| 03.05 | S05 | M16 | Convenios y aranceles | 02.05, 02.07, 03.03 | Sí | Sí | Sí | U/I/F/E2E | NO IMPLEMENTADO |
| 03.06 | S05 | M17/M25 | Órdenes, autorizaciones y documentos | 03.04, 03.05 | Sí | Sí | Sí | U/I/Sec/E2E | NO IMPLEMENTADO |
| 04.01 | S06 | M09 | HC organizacional y acceso | 03.02 | Sí | Sí | Sí | U/I/Sec/E2E | NO IMPLEMENTADO |
| 04.02 | S06 | M09/M25 | Timeline y adjuntos clínicos | 03.02, 04.01 | Sí | Sí | Sí | U/I/Sec/E2E | NO IMPLEMENTADO |
| 04.03 | S06 | M10 | Caso Clínico | 02.07, 04.02 | Sí | Sí | Sí | U/I/F/E2E | NO IMPLEMENTADO |
| 04.04 | S07 | M11 | Plan de Tratamiento | 02.07, 04.03 | Sí | Sí | Sí | U/I/F/E2E | NO IMPLEMENTADO |
| 04.05 | S07 | M11/M17 | Consumo de autorizaciones | 03.06, 04.04 | Sí | Sí | Sí | U/I/Conc/E2E | NO IMPLEMENTADO |
| 05.01 | S08 | M04/M05/M12 | Motor de slots | 02.04, 02.07, 03.04 | Sí | Sí | Índices | U/I/Perf/E2E | NO IMPLEMENTADO |
| 05.02 | S08 | M04/M12/M26 | Reserva y confirmación | 03.01, 05.01 | Sí | Sí | Sí | U/I/Conc/E2E | NO IMPLEMENTADO |
| 05.03 | S09 | M12/M26 | Ciclo e historial de Turno | 05.02 | Sí | Sí | Sí | U/I/F/E2E | NO IMPLEMENTADO |
| 05.04 | S09 | M13 | Check-in y espera | 03.06, 04.05, 05.03 | Sí | Sí | Sí | U/I/F/E2E | NO IMPLEMENTADO |
| 06.01 | S10 | M14 | Inicio y autosave de Sesión | 04.05, 05.04 | Sí | Sí | Sí | U/I/Idem/E2E | NO IMPLEMENTADO |
| 06.02 | S10 | M14 | Evaluación base | 06.01 | Sí | Sí | Sí | U/I/F/E2E | NO IMPLEMENTADO |
| 06.03 | S10 | M14 | Examen y mediciones | 06.02 | Sí | Sí | Sí | U/I/F/E2E | NO IMPLEMENTADO |
| 06.04 | S11 | M04/M06/M14 | Tratamientos y espacios usados | 02.02, 02.05, 06.03 | Sí | Sí | Sí | U/I/F/E2E | NO IMPLEMENTADO |
| 06.05 | S11 | M14 | Resultado y cierre | 06.04 | Sí | Sí | Sí | U/I/Conc/E2E | NO IMPLEMENTADO |
| 06.06 | S11 | M14/M24 | Enmiendas y versionado | 06.05 | Sí | Sí | Sí | U/I/Audit/E2E | NO IMPLEMENTADO |
| 07.01 | S12 | M17/M18 | Obligaciones y snapshots | 03.05, 04.05, 06.05 | Sí | Sí | Sí | U/I/Idem/E2E | NO IMPLEMENTADO |
| 07.02 | S12 | M19 | Cobros e imputaciones | 07.01 | Sí | Sí | Sí | U/I/Conc/E2E | NO IMPLEMENTADO |
| 07.03 | S13 | M20 | Caja y movimientos | 07.02 | Sí | Sí | Sí | U/I/Conc/E2E | NO IMPLEMENTADO |
| 07.04 | S13 | M21 | Presentaciones financiadores | 07.01, 07.03 | Sí | Sí | Sí | U/I/Rec/E2E | NO IMPLEMENTADO |
| 07.05 | S13 | M22 | Egresos y pagos | 02.03, 07.03 | Sí | Sí | Sí | U/I/F/E2E | NO IMPLEMENTADO |
| 07.06 | S14 | M23 | Reportes M01–M27 | 07.04, 07.05 | Sí | Sí | Índices | U/I/Rec/Perf | NO IMPLEMENTADO |
| 07.07 | S14 | Transversal | Seguridad, privacidad, a11y MVP | 07.06 | Sí | Sí | Revisar | Sec/A11y/E2E | NO IMPLEMENTADO |
| 07.08 | S15 | Transversal | SLO, concurrencia, observabilidad MVP | 07.07 | Sí | Sí | Optimizar | Perf/Conc/Res | NO IMPLEMENTADO |
| 07.09 | S15 | Gate MVP | Release M01–M27 | 07.08 | Sí | Sí | Ensayo | E2E/Smoke/Restore | NO IMPLEMENTADO |
| 08.01 | S16 | M12/M28 | Clases y agenda unificada | 02.07, 05.03, 07.09 | Sí | Sí | Sí | U/I/Conc/E2E | NO IMPLEMENTADO |
| 08.02 | S16 | M26/M28 | Inscripciones y espera | 03.01, 08.01 | Sí | Sí | Sí | U/I/Conc/E2E | NO IMPLEMENTADO |
| 08.03 | S16 | M13/M18/M19/M28 | Asistencia y operación | 07.01, 07.02, 08.02 | Sí | Sí | Sí | U/I/F/E2E | NO IMPLEMENTADO |
| 08.04 | S17 | M09/M10/M11/M28 | Derivación clínica | 04.04, 04.05, 08.03 | Sí | Sí | Sí | U/I/Sec/E2E | NO IMPLEMENTADO |
| 08.05 | S17 | M14/M28 | Atención grupal individualizada | 06.05, 08.04 | Sí | Sí | Sí | U/I/Priv/E2E | NO IMPLEMENTADO |
| 08.06 | S18 | M18/M19/M20/M29 | Productos, packs y venta | 07.03, 08.03 | Sí | Sí | Sí | U/I/Idem/E2E | NO IMPLEMENTADO |
| 08.07 | S18 | M19/M20/M29 | Ledger de créditos | 08.06 | Sí | Sí | Sí | U/I/Conc/E2E | NO IMPLEMENTADO |
| 08.08 | S19 | M20/M26/M29 | Abonos y vencimientos | 01.02, 08.07 | Sí | Sí | Sí | U/I/Jobs/E2E | NO IMPLEMENTADO |
| 08.09 | S19 | M21/M22/M24 | Economía y auditoría servicios | 07.04, 07.05, 08.05, 08.08 | Sí | Sí | Sí | U/I/Rec/E2E | NO IMPLEMENTADO |
| 09.01 | S20 | M23/M28/M29 | Reportes ampliados | 07.06, 08.09 | Sí | Sí | Índices | U/I/Rec/Perf | NO IMPLEMENTADO |
| 09.02 | S20 | Transversal | Seguridad, privacidad y a11y final | 09.01 | Sí | Sí | Revisar | Sec/A11y/E2E | NO IMPLEMENTADO |
| 09.03 | S21 | Transversal | SLO, concurrencia y observabilidad final | 09.02 | Sí | Sí | Optimizar | Perf/Conc/Res | NO IMPLEMENTADO |
| 09.04 | S21 | Gate final | Release M01–M29 | 09.03 | Sí | Sí | Ensayo | E2E/Smoke/Restore | NO IMPLEMENTADO |

**Leyenda:** U = unitarios; I = integración; F = frontend; Arch = arquitectura; Sec = seguridad; Conc = concurrencia; Idem = idempotencia; Rec = reconciliación; Perf = rendimiento; Res = resiliencia; Priv = privacidad.

## 23.1 Política de trazabilidad ejecutable

El canónico contiene **253 RF, 201 RN, 295 RNF y 1.518 CA**. El doble control documental establece estas reglas obligatorias:

1. Todo `RF-Mxx-nnn` aparece de forma explícita o dentro de un rango explícito en al menos una etapa; la expansión automática de rangos debe devolver 253/253.
2. Todo `CA-Mxx-nnn-*` hereda la etapa del `RF-Mxx-nnn` padre y debe quedar enlazado a uno o más tests/evidencias antes de cerrar esa etapa.
3. Toda etapa que implemente un módulo Mxx debe aplicar además los RN y RNF Mxx relacionados, aunque la celda de la tabla use una síntesis. Los RNF transversales se verifican adicionalmente en 07.07–07.09 y 09.02–09.04.
4. Si un RF/CA se implementa en más de una etapa, una tiene ownership primario y las demás solo integran o consumen; no se acepta doble interpretación de la regla.
5. La matriz se valida en cada cambio del plan: IDs de etapa únicos, dependencias existentes y acíclicas, cobertura RF completa y coincidencia entre sección 14 y sección 23.

## 23.2 Cobertura por módulo

| Módulo | Etapas con ownership o integración explícita |
|---|---|
| M01 | 01.01, 01.03, 02.01 |
| M02 | 01.02, 01.03 |
| M03 | 02.01, 02.06 |
| M04 | 02.02, 02.07, 05.01, 05.02, 06.04 |
| M05 | 01.03, 02.03, 02.04, 02.07, 05.01 |
| M06 | 02.05, 02.06, 06.04 |
| M07 | 03.01, 03.02, 07.06 |
| M08 | 03.04 |
| M09 | 04.01, 04.02, 08.04 |
| M10 | 04.03, 08.04 |
| M11 | 04.04, 04.05, 08.04 |
| M12 | 05.01, 05.02, 05.03, 08.01 |
| M13 | 05.04, 08.03 |
| M14 | 06.01, 06.02, 06.03, 06.04, 06.05, 06.06, 08.05 |
| M15 | 03.03 |
| M16 | 03.05 |
| M17 | 03.06, 04.05, 07.01 |
| M18 | 07.01, 08.03, 08.06, 08.09 |
| M19 | 07.02, 08.03, 08.06, 08.07 |
| M20 | 07.03, 08.06, 08.07, 08.08 |
| M21 | 07.04, 08.09 |
| M22 | 07.05, 08.09 |
| M23 | 07.06, 09.01 |
| M24 | 01.03, 06.06, 07.07, 08.09, 09.02 |
| M25 | 03.02, 03.06, 04.02 |
| M26 | 01.02, 02.03, 05.02, 05.03, 08.02, 08.08 |
| M27 | 02.06, 02.07 |
| M28 | 08.01, 08.02, 08.03, 08.04, 08.05 |
| M29 | 08.06, 08.07, 08.08, 08.09 |

---

## Cierre y condición de plan terminado

Este documento queda terminado como guía integral de construcción greenfield de AKINE: cubre M01–M29, define 56 etapas, dependencias, 22 sprints de referencia, arquitectura, stack, seguridad, testing, migraciones, SLO, gates y trazabilidad. No requiere una “Parte 2” documental ni prompts adicionales para ser ejecutable.

La implementación comienza en AKINE-00.01 con la creación de los repositorios backend y frontend. Esa etapa debe registrar en este plan las rutas y comandos efectivamente creados; las etapas posteriores actualizarán únicamente su evidencia y estado, sin reinterpretar el alcance. El gate 07.09 acepta y despliega el MVP M01–M27 y, una vez cumplida su estabilización, inicia obligatoriamente 08.01. El trabajo solo concluye cuando 09.04 libera M01–M29 y satisface la Definition of Done final.

---

# Registro de cierre — AKINE-00.01

**Fecha de ejecución:** 22/08/2026
**Estado:** COMPLETADA Y VERIFICADA
**Ejecutado sobre:** Windows 11, Java 21.0.10 (Zulu), Maven 3.9.0, Node 24.13.0, npm 11.6.2, Angular CLI 21.2.6, Docker 29.2.0 / Compose v5.0.2

## 1. Resumen del incremento y comportamiento observable

Se crearon los dos baselines técnicos greenfield. El stack completo levanta de forma reproducible y se comunica de punta a punta: MySQL 8.4 en Docker, backend Spring Boot con Flyway aplicando la migración inicial sobre base vacía, contrato OpenAPI publicado y versionado, y SPA Angular consumiendo la API a través del proxy de desarrollo con un cliente TypeScript generado desde ese contrato.

Comportamiento observable: `http://localhost:4200` muestra el shell accesible con el estado de conexión al backend, la versión de la aplicación y la comparación entre el contrato publicado y el consumido. `GET /api/v1/version` y `GET /actuator/health` responden. No existe ninguna pantalla ni endpoint de negocio: M01–M29 se construyen en sus etapas.

## 2. Versiones fijadas

| Componente | Versión | Cómo se determinó |
|---|---|---|
| Spring Boot | **4.1.1** | Última estable de la línea 4.1.x según `start.spring.io/metadata/client` |
| springdoc-openapi | **3.1.0** | Única línea construida sobre `spring-boot-starter-parent` 4.1.x |
| ArchUnit | 1.4.1 | Última estable en Maven Central |
| JaCoCo | 0.8.13 | Última estable |
| MySQL | 8.4 (imagen fijada, no `latest`) | Aprobado por el plan |
| Angular | 21.2.6 | CLI instalado en la máquina |
| Node | 24.13.0 | LTS instalado |
| openapi-generator | 7.24.0 | Resuelto por `@openapitools/openapi-generator-cli` |

## 3. Archivos creados — backend (`appKine-api`)

```
pom.xml · mvnw · mvnw.cmd · .mvn/wrapper/maven-wrapper.properties · .gitattributes · .gitignore
compose.yaml
openapi/akine-api.yaml
.github/workflows/ci.yml
src/main/java/com/akine/AkineApiApplication.java
src/main/java/com/akine/platform/api/VersionController.java
src/main/java/com/akine/platform/api/GlobalExceptionHandler.java
src/main/java/com/akine/platform/api/dto/VersionResponse.java
src/main/java/com/akine/platform/application/VersionService.java
src/main/java/com/akine/platform/domain/BuildVersion.java
src/main/java/com/akine/platform/infrastructure/config/SecurityConfig.java
src/main/java/com/akine/platform/infrastructure/config/OpenApiConfig.java
src/main/resources/application.yml · application-local.yml
src/main/resources/db/migration/V1__baseline_tecnico.sql
src/test/java/com/akine/architecture/ModuleArchitectureTest.java
src/test/java/com/akine/architecture/CodingConventionsTest.java
src/test/java/com/akine/platform/api/VersionControllerTest.java
src/test/java/com/akine/AkineApiApplicationIT.java
src/test/java/com/akine/OpenApiContractIT.java
src/test/java/com/akine/TestcontainersConfiguration.java
AGENT.md · CLAUDE.md · docs/ai-setup.md · .claude/qa-config.md (gitignored)
```

## 4. Archivos creados — frontend (`appKine-web`)

```
angular.json · package.json · package-lock.json · tsconfig{,.app,.spec}.json
.editorconfig · .prettierrc · .prettierignore · .gitignore · .vscode/
proxy.conf.json · openapi-generator.json · playwright.config.ts
scripts/check-contract-version.mjs
.github/workflows/ci.yml
e2e/smoke.spec.ts
src/index.html · src/main.ts · src/styles.css · public/favicon.ico
src/environments/environment.ts · environment.prod.ts
src/app/app.ts · app.html · app.css · app.config.ts · app.routes.ts · app.spec.ts
src/app/core/services/auth-token.store.ts · tenant-context.store.ts
src/app/core/interceptors/auth.interceptor.ts · error.interceptor.ts
src/app/core/{guards,models}/ · src/app/shared/{components,pipes,directives}/ · src/app/features/
src/app/api/generated/  (generado — no se edita a mano)
AGENT.md · CLAUDE.md · docs/ai-setup.md · .claude/qa-config.md (gitignored)
```

## 5. Migraciones ejecutadas

`V1__baseline_tecnico.sql` — crea `platform_schema_info` (tabla técnica de procedencia del esquema, propiedad del módulo `platform`) e inserta la fila de baseline. **No crea tablas funcionales.**

Fija las convenciones obligatorias para toda migración posterior: `utf8mb4`/`utf8mb4_0900_ai_ci`, `organization_id NOT NULL` en toda tabla de negocio, alcance tenant en índices y uniques, `DECIMAL` para importes, `DATETIME(6)` en UTC, baja lógica con `active`/`deleted_at`, expandir-migrar-contraer, y prohibición de editar una migración ya aplicada.

**Resultado verificado contra la base real** (no solo Testcontainers):

```
flyway_schema_history: version=1, description='baseline tecnico', success=1
platform_schema_info:  baseline_stage='AKINE-00.01', contract_version='0.1.0'
```

## 6. Contratos y endpoints

Contrato canónico: `appKine-api/openapi/akine-api.yaml`, OpenAPI 3.1.0, versión SemVer **0.1.0**.

| Endpoint | Descripción |
|---|---|
| `GET /api/v1/version` | Contrato técnico de versionado. Devuelve `application`, `version`, `contract` |
| `GET /actuator/health` | Health con estado de la conexión a la DB |
| `GET /v3/api-docs.yaml` | Contrato en runtime |
| `GET /swagger-ui.html` | Swagger UI |

Cliente TypeScript generado en `appKine-web/src/app/api/generated/` desde la versión 0.1.0 del contrato, declarada en `src/environments/environment.ts` y verificable con `npm run api:check`.

## 7. Pruebas ejecutadas y resultados

| Suite | Comando | Resultado |
|---|---|---|
| Backend — convenciones de código (ArchUnit) | `./mvnw test` | **12 / 12** |
| Backend — arquitectura de módulos (ArchUnit) | `./mvnw test` | **5 / 5** |
| Backend — slice web | `./mvnw test` | **2 / 2** |
| Backend — integración (Testcontainers MySQL 8.4) | `./mvnw verify` | **7 / 7** |
| Backend — gate de drift del contrato | `./mvnw verify` | **2 / 2** |
| Frontend — unitarios (Vitest) | `npm run test:ci` | **4 / 4** |
| Frontend — build de producción | `npm run build` | OK — 221 kB, 60 kB transferidos |
| Frontend — formato | `npm run format:check` | OK |
| Frontend — alineación de contrato | `npm run api:check` | OK — 0.1.0 |
| **E2E contra el stack real** (MySQL + backend + Angular) | `npm run e2e` | **5 / 5** |

**Total: 37 pruebas en verde.** Los E2E corrieron contra el stack real levantado en esta máquina, no contra mocks.

## 8. Decisiones técnicas adoptadas y alternativas descartadas

| Decisión | Elegido | Descartado | Razón |
|---|---|---|---|
| Producción del contrato OpenAPI | **Code-first (springdoc) + YAML commiteado + gate de drift en CI** | Contract-first puro; code-first sin gate | Contract-first obligaba a escribir a mano el YAML de 29 módulos. Code-first sin gate convierte el contrato en subproducto y los breaking changes se descubren después de mergear. El gate da el diff revisable en el PR sin la ceremonia |
| Generador del cliente TS | **openapi-generator `typescript-angular`** | `ng-openapi-gen`; `orval` | El más maduro y con output nativo de Angular. `orval` está orientado a react-query |
| Custodia del JWT | **Access en memoria + refresh en cookie `httpOnly`+`SameSite`** | `localStorage`; `sessionStorage` | AKINE maneja historia clínica. Un XSS lee todo el storage; no puede leer memoria ni una cookie `httpOnly` |
| Estructura de módulos en el baseline | **Solo `platform`, con las 5 capas + ArchUnit** | Los 13 módulos con sus capas; `platform` plano | Crear 12 módulos vacíos contradice "no anticipar módulos funcionales". ArchUnit obliga a la convención sin necesidad de carpetas vacías |
| Contrato entre módulos | **Paquete `spi` como único punto público** | `api` como puerto interno; sin regla explícita | El plan exige "puerto interno explícito" sin decir dónde vive. `spi` lo fija y ArchUnit lo verifica |
| Switch de API del frontend | **Proxy de dev (`proxy.conf.json`)** | `environment.ts` con URL | Elimina la clase entera de bug "commiteé una URL de localhost" y no hay nada que revertir tras el QA |
| Cliente MySQL para QA | **`docker compose exec`** | Instalar cliente MySQL en la máquina | Cero instalación y siempre la versión que matchea el servidor |
| `servers` del contrato | **Relativo (`/`), declarado explícito** | Dejar que springdoc infiera la URL | Inferida, toma el puerto aleatorio de los tests y el gate de drift falla en cada corrida |

## 9. Problemas encontrados y cómo se resolvieron

Spring Boot 4 introduce rupturas respecto de 3.x que costaron varias iteraciones. Quedan documentadas porque afectarán a toda etapa futura:

1. **Starters reorganizados.** `spring-boot-starter-webmvc` (no `-web`), `spring-boot-starter-flyway`, y un artefacto `-test` por starter en lugar de `spring-boot-starter-test` único.
2. **Jackson 3.** Boot 4 usa `tools.jackson`, no `com.fasterxml.jackson`. `WRITE_DATES_AS_TIMESTAMPS` dejó de existir en `SerializationFeature` y pasó a `DateTimeFeature`: la clave correcta es `spring.jackson.datatype.datetime.write-dates-as-timestamps`. La clave vieja **rompe el arranque del contexto** con un error de binding que no menciona la propiedad culpable.
3. **`@WebMvcTest` se mudó** a `org.springframework.boot.webmvc.test.autoconfigure`.
4. **`TestRestTemplate` se mudó** a `org.springframework.boot.resttestclient`, ya no se autoconfigura con `@SpringBootTest(RANDOM_PORT)` y requiere `spring-boot-restclient` en el classpath. Se adoptó **`RestTestClient` + `@AutoConfigureRestTestClient`**, que es la API nativa de Boot 4 y no necesita dependencia extra.
5. **Testcontainers 2.x:** `MySQLContainer` dejó de ser genérico y vive en `org.testcontainers.mysql`.
6. **ArchUnit y reglas sin clases que verificar.** Con un solo módulo, varias reglas no encuentran nada y fallan por `failOnEmptyShould`. Se usó `allowEmptyShould(true)` por regla —con el motivo documentado— en lugar de desactivarlo globalmente, para no perder la señal cuando esas clases existan.
7. **Validación OpenAPI 3.1:** el bloque `license` sin identificador SPDX hace fallar a openapi-generator. Se eliminó.
8. **Prettier y el cliente generado.** Prettier reformateaba `src/app/api/generated/`, lo que rompía el gate de drift en cada corrida. Se agregó `.prettierignore`.
9. **Conflicto de puertos.** Contenedores de otro proyecto de la máquina ocupaban 3306 y 8080. Se detuvieron con autorización explícita del usuario, y se parametrizó el puerto del host en `compose.yaml` (`AKINE_DB_PORT`) para que la convivencia sea posible.

## 10. Deuda técnica diferida, con etapa destino

| Deuda | Etapa destino |
|---|---|
| Job de SonarQube declarado pero comentado en ambos pipelines — falta instancia y token | AKINE-00.02 |
| Umbral de cobertura sin activar en JaCoCo (80 % general, 90 % en módulos críticos) | AKINE-00.02 |
| Sin ESLint / `angular-eslint` en el frontend: hoy solo hay Prettier | AKINE-00.02 |
| Job E2E del pipeline comentado: espera la imagen Docker del backend para no compilar Java en el job del frontend | AKINE-00.02 |
| Observabilidad pendiente: logging JSON, Prometheus, OpenTelemetry | AKINE-00.02 |
| Sin ADRs en `docs/adr/` | AKINE-00.02 |
| `SecurityConfig` con `permitAll` explícito y CSRF deshabilitado. CSRF debe reactivarse para el endpoint de refresh cuando exista la cookie `httpOnly` | F1 / M02 |
| `errorInterceptor` no reintenta con refresh ante un 401, ni encola peticiones concurrentes | F1 / M02 |
| Cuentas de prueba, endpoints de auth y seed QA marcados `PENDIENTE(F1)` en ambos `qa-config.md` | F1 |
| Ningún repositorio tiene commits ni protección de rama. El frontend no tiene remote configurado | AKINE-00.02 |

## 11. Contexto para la etapa siguiente

- **Convención de módulo:** `com.akine.<modulo>` con `spi` (único público), `api`, `application`, `domain`, `infrastructure`. Verificada por `ModuleArchitectureTest`.
- **Al agregar el primer módulo funcional**, `optionalLayer("SPI")` puede volver a `layer("SPI")`, y `allowEmptyShould(true)` puede retirarse de las reglas de entities y repositorios.
- **Toda tabla nueva** lleva `organization_id NOT NULL` y lo incluye en sus uniques e índices.
- **Al cambiar la API:** `./mvnw verify -Dakine.contract.update=true`, revisar el diff del contrato, subir `akine.contract.version` (minor si es aditivo, major si es incompatible), y en el frontend `npm run api:generate` + actualizar `contractVersion` en `environment*.ts`.
- **Los tests de arquitectura no se relajan** para hacer pasar un cambio. Si estorban, el diseño del cambio está mal.

---

# Registro de cierre — AKINE-00.02

**Fecha de ejecución:** 22/08/2026
**Estado:** COMPLETADA Y VERIFICADA
**Rama:** `akine-00.02-baseline-ejecutable` en ambos repositorios
**Reporte reproducible:** `docs/baseline-report.md`

## 1. Resumen del incremento y comportamiento observable

Etapa de **verificación y formalización**, no de dominio funcional. El baseline creado en
AKINE-00.01 quedó medido, sus decisiones documentadas como ADRs, y las convenciones que solo
vivían en prosa pasaron a ser gates automáticos que fallan el build.

Comportamiento observable: idéntico al de AKINE-00.01 en `/`. Lo que cambió es lo que ocurre
cuando alguien intenta violar una convención: antes pasaba, ahora el build falla.

**69 pruebas en verde** (32 backend, 37 frontend incluidos 7 E2E contra el stack real).

## 2. Archivos creados

**Backend (`appKine-api`)**

```
docs/adr/README.md
docs/adr/0001-monolito-modular-con-paquete-spi.md
docs/adr/0002-contrato-openapi-code-first-con-gate-de-drift.md
docs/adr/0003-flyway-como-unica-autoridad-del-esquema.md
docs/adr/0004-convenciones-de-persistencia-multi-tenant.md
docs/adr/0005-errores-como-problem-details.md
docs/adr/0006-arquitectura-verificada-por-tests.md
docs/adr/0007-expandir-migrar-contraer.md
src/test/java/com/akine/platform/api/GlobalExceptionHandlerTest.java
```

**Frontend (`appKine-web`)**

```
docs/adr/README.md
docs/adr/0001-token-en-memoria-refresh-en-cookie-httponly.md
docs/adr/0002-cliente-api-generado.md
docs/adr/0003-proxy-de-dev-sin-url-de-api-versionada.md
docs/adr/0004-estructura-core-shared-features.md
docs/adr/0005-estados-obligatorios-y-accesibilidad.md
eslint.config.js
scripts/check-coverage.mjs
src/app/features/platform/pages/estado/estado.{ts,html,css,spec.ts}
src/app/shared/pages/not-found/not-found.{ts,html,css}
src/app/core/services/auth-token.store.spec.ts
src/app/core/services/tenant-context.store.spec.ts
src/app/core/interceptors/auth.interceptor.spec.ts
src/app/core/interceptors/error.interceptor.spec.ts
```

**Workspace (`docs/`)**

```
docs/baseline-report.md
docs/pruebas-de-carga.md
```

## 3. Archivos modificados

| Archivo | Cambio |
|---|---|
| `appKine-api/pom.xml` | `maven-enforcer-plugin` + umbral de cobertura JaCoCo |
| `appKine-api/CLAUDE.md`, `AGENT.md` | Estado y comandos reales |
| `appKine-web/angular.json` | `coverage`, `coverageExclude`, `coverageReporters` |
| `appKine-web/package.json` | Scripts `lint`, `coverage:check`; `test:ci` encadena el gate |
| `appKine-web/src/app/app.{ts,html,css}` | `App` pasa a ser layout puro |
| `appKine-web/src/app/app.routes.ts` | Rutas lazy + comodín 404 + convención documentada |
| `appKine-web/src/app/app.spec.ts` | Reorientado a verificar el layout y su accesibilidad |
| `appKine-web/.github/workflows/ci.yml` | Pasos de lint y cobertura |
| `appKine-web/CLAUDE.md`, `AGENT.md` | Estado, comandos y reglas de lint |
| `docs/AKINE_IMPLEMENTATION_PLAN.md` | Este registro |

## 4. Migraciones ejecutadas

**Ninguna.** La etapa no crea ni altera esquema. Se **verificó** que `V1__baseline_tecnico.sql`
aplica sobre una base MySQL vacía, contra la base real además de Testcontainers:

```
flyway_schema_history: version=1, description='baseline tecnico', success=1
platform_schema_info:  baseline_stage='AKINE-00.01', contract_version='0.1.0'
```

La estrategia expandir–migrar–contraer quedó documentada en el ADR-0007 del backend, para que
las etapas funcionales no la improvisen.

## 5. Contratos y endpoints

**Sin cambios.** El contrato sigue en SemVer `0.1.0` y el gate de drift confirma que
implementación y contrato están alineados. Se verificó `npm run api:check`: la versión que
declara el frontend coincide con la que publica el backend.

## 6. Pruebas agregadas y resultados

| Suite | Antes | Después |
|---|---|---|
| Backend — unitarias y arquitectura | 19 | **23** |
| Backend — integración y contrato | 9 | **9** |
| Frontend — unitarias | 4 | **30** |
| Frontend — E2E | 5 | **7** |
| **Total** | **37** | **69** |

Cobertura:

| Repositorio | Antes | Después | Piso |
|---|---|---|---|
| Backend (líneas) | 79,7 % | **100 %** | 80 % |
| Frontend (statements) | sin medir | **98,69 %** | 80 % |
| Frontend (branches) | sin medir | **92,30 %** | 80 % |

La cobertura del backend subió **escribiendo tests para `GlobalExceptionHandler`**, no
excluyéndolo. Los del frontend cubren donde viven las decisiones de seguridad: custodia del
token, contexto multi-tenant y ambos interceptores.

## 7. Gates automáticos activados

| Gate | Dónde | Qué impide |
|---|---|---|
| `requireJavaVersion [21,22)` | `pom.xml` | Compilar con un JDK distinto al de producción |
| `dependencyConvergence` | `pom.xml` | Conflictos de versión que aparecen en runtime |
| `jacoco:check` 80 % | `pom.xml` | Que la cobertura del backend caiga |
| `check-coverage.mjs` 80 % | `package.json` | Que la cobertura del frontend caiga |
| `no-restricted-globals` | `eslint.config.js` | Guardar el token en `localStorage`/`sessionStorage` |
| `no-console` | `eslint.config.js` | Dejar un dato clínico en la consola |
| `templateAccessibility` | `eslint.config.js` | Incumplir WCAG 2.1 AA |

**Todos se verificaron en ambos sentidos:** que pasan cuando deben y que **fallan** cuando
deben. Un gate que nunca falla no es un gate.

## 8. Decisiones técnicas adoptadas y alternativas descartadas

Formalizadas como **12 ADRs** (7 backend, 5 frontend), cada uno con contexto, alternativas
descartadas y consecuencias —incluidas las malas—. Ver los índices en `docs/adr/README.md`
de cada repositorio.

Decisiones nuevas de esta etapa:

| Decisión | Elegido | Descartado | Razón |
|---|---|---|---|
| Cambios de esquema | Expandir–migrar–contraer siempre | Migración directa con ventana de mantenimiento; solo para tablas grandes | AKINE es 24 h. El riesgo no es el volumen sino la compatibilidad durante el despliegue, idéntica con 20 filas o 20 millones |
| Gate de cobertura del frontend | Script propio sobre `coverage-summary.json` | `vitest.config.ts`; confiar en el builder | El builder de Angular 21 no expone umbrales: publica el reporte y sale con éxito aunque la cobertura sea del 5 % |
| Estado de pantalla | Unión discriminada | Booleanos `loading`/`error` | Los booleanos permiten estados imposibles (`cargando && error`) que el compilador no puede descartar |
| Layout vs página | `App` como layout puro, páginas en `features/` | Todo en `App` | Sin la separación, M01 no tendría patrón que copiar |
| Harness de carga | k6 | Gatling; JMeter; Artillery; reusar Playwright | JS como el resto del equipo, umbrales nativos que fallan la corrida, y escenarios revisables en un diff |

## 9. Problemas encontrados

Cinco fallos, todos documentados en `docs/baseline-report.md` §9. El más relevante:

**Falso verde en `GlobalExceptionHandlerTest`.** Dos tests esperaban `500` y lo obtenían,
pero por un 404 de recurso estático: `@WebMvcTest(clase)` no registra un controller anidado
del propio test. Pasaban **sin ejercitar el handler**. Se detectó al notar que el resto de
los tests del mismo archivo fallaban por la misma causa.

Es el recordatorio de que un test verde no prueba nada por sí solo: hay que verificar que
falle cuando debe.

Ninguno de los cinco se resolvió relajando una verificación.

## 10. Deuda técnica

**Saldada en esta etapa:** umbrales de cobertura, ESLint, ADRs, commit inicial del baseline.

**Pendiente, con etapa destino:**

| Deuda | Etapa destino |
|---|---|
| Job de SonarQube comentado — falta instancia y token | AKINE-00.03 |
| Job E2E del pipeline comentado — espera imagen Docker del backend | AKINE-00.03 |
| Observabilidad: logging JSON, Prometheus, OpenTelemetry | AKINE-00.03 |
| Protección de rama en `main`; remote del frontend sin configurar | AKINE-00.03 |
| Regla de ESLint que prohíba imports entre features (ADR-0004 del frontend) | AKINE-00.03 |
| Reglas `PACKAGE` de cobertura al 90 % para módulos críticos | F1, cuando existan |
| CSRF y refresh con cola de peticiones | F1 / M02 — por diseño |
| Cuentas de prueba, endpoints de auth y seed QA | F1 |
| Pruebas de carga: harness elegido (k6), sin ejecutar | AKINE-09.03 |

## 11. Cambios de comportamiento

El criterio de aceptación exige *"no se modificó comportamiento"*. Se cumple, con **una
excepción declarada**: la reestructuración de rutas y layout del frontend, que está
explícitamente dentro del alcance de la sección Frontend de esta etapa (*"fijar estructura de
rutas, layout, errores y cliente API"*).

Lo observable en `/` es idéntico: los 5 E2E de AKINE-00.01 pasan **sin modificación**. Los 2
E2E nuevos cubren la ruta 404 y la persistencia del layout entre navegaciones.

Todo lo demás de la etapa —ADRs, reglas de lint, umbrales, verificación de versiones, tests
adicionales— es verificación pura.

## 12. Contexto para la etapa siguiente

- **Los ADRs son la fuente de las decisiones ya tomadas.** Antes de rediscutir una, leer si
  ya existe. Un ADR aceptado no se edita: se supersede con uno nuevo.
- **AKINE-00.03 formaliza `DP-01`–`DP-09` como ADRs.** La convención, la plantilla y la
  numeración ya están definidas en `docs/adr/README.md` de cada repo; solo hay que continuar
  la serie.
- **Los gates son el contrato de calidad.** Cuando uno estorbe, el diseño del cambio está mal.
  Modificar un umbral o relajar una regla exige una decisión documentada en este plan.
- **Patrón a copiar para la primera pantalla funcional:** `features/platform/pages/estado/`
  —estado como unión discriminada, tres casos cubiertos, spec que los ejercita— y la
  convención de `loadChildren` documentada en `app.routes.ts`.
- **Al crear el primer módulo funcional del backend:** devolver `optionalLayer("SPI")` a
  `layer("SPI")` y retirar los `allowEmptyShould(true)` que ya no hagan falta.

---

# Registro de cierre — AKINE-00.03

**Fecha de ejecución:** 22/08/2026
**Estado:** COMPLETADA
**Rama:** `akine-00.03-decisiones` (backend). El frontend no cambia en esta etapa.
**Modo de ejecución:** multi-agente — 1 agente redactor de ADRs + 3 agentes de relevamiento de la especificación (M01, M02, M05/§32/M24/M26), en paralelo, orquestados y verificados por el agente principal.

## 1. Resumen del incremento

Etapa documental, sin código. Cierra la Fase F0:

- **DP-01 a DP-09 versionadas como ADRs aceptadas** (`appKine-api/docs/adr/0008`–`0016`), cada una con contexto (Fuente A histórica vs Fuente B especificación), decisión, alternativas descartadas, consecuencias —incluidas las negativas— y obligaciones aterrizadas en módulos y etapas.
- **Matriz mínima de permisos aprobada** (`appKine-api/docs/seguridad/matriz-permisos-minima.md`): 6 roles, la matriz literal de §32 (12 acciones × 6 roles), y la decisión que §32 deja abierta: semántica ejecutable de cada valor no binario, catálogo de permisos `dominio:acción`, asignación base para F1, invariantes y huecos con etapa destino.
- **Baseline verificado contra las decisiones** (sección 4).
- **Plan rebaselinado** (secciones 5 y 6): huecos funcionales detectados en la especificación y recalibración de la estimación.

## 2. Archivos creados / modificados

```
appKine-api/docs/adr/0008-onboarding-compuesto-transaccional.md            (DP-01)
appKine-api/docs/adr/0009-identidad-unica-con-seleccion-de-contexto.md     (DP-02)
appKine-api/docs/adr/0010-historia-clinica-de-alcance-organizacional.md    (DP-03)
appKine-api/docs/adr/0011-series-de-turnos-sin-borrado.md                  (DP-04)
appKine-api/docs/adr/0012-maquinas-de-estado-separadas-turno-checkin-sesion.md (DP-05)
appKine-api/docs/adr/0013-prepago-como-politica-configurable.md            (DP-06)
appKine-api/docs/adr/0014-alcance-mvp-y-segunda-entrega-m28-m29.md         (DP-07)
appKine-api/docs/adr/0015-requisitos-clinicos-y-legales.md                 (DP-08)
appKine-api/docs/adr/0016-versiones-tecnicas-y-slo.md                      (DP-09)
appKine-api/docs/adr/README.md                                             (índice)
appKine-api/docs/seguridad/matriz-permisos-minima.md                       (nuevo)
appKine-api/AGENT.md                                                       (referencias a ADRs y matriz)
appKine-api/CLAUDE.md                                                      (estado)
docs/AKINE_IMPLEMENTATION_PLAN.md                                          (este registro)
```

Dos slugs propuestos se corrigieron al leer el contenido real de la DP: **0013** (la decisión de DP-06 es "prepago configurable por consultorio y oferta, sesión independiente del pago", no "cobro antes de atención", que es la postura histórica descartada) y **0014** (M27 queda dentro del MVP; la segunda entrega es M28–M29).

## 3. Migraciones, contratos, pruebas

Ninguna. La etapa lo prohíbe expresamente ("No implementar"). Las 69 pruebas del baseline siguen en verde sin cambios.

## 4. Verificación del baseline contra las decisiones

| Decisión | Qué se verificó en el baseline | Resultado |
|---|---|---|
| DP-01 / ADR-0008 | Ownership: `identity` → Cuenta; `organization` → Organización, Consultorio, Membership; onboarding orquestado por `spi` | Compatible: ArchUnit ya impide acceso entre módulos fuera de `spi` |
| DP-02 / ADR-0009 | Token acotado al contexto, en memoria; `TenantContextStore` con `contextEpoch` | Compatible: `appKine-web/core` ya lo implementa; backend sin auth todavía (F1) |
| DP-03 / ADR-0010 | HC de alcance organizacional | Sin impacto en baseline (F4) |
| DP-04, DP-05 / ADR-0011, 0012 | Sin borrado físico; máquinas de estado separadas y auditadas | Compatible con ADR-0004 (baja lógica) y §33 |
| DP-06 / ADR-0013 | Anticipos como ledger, movimientos nunca se borran | Compatible con `DECIMAL`/`BigDecimal` (ArchUnit lo verifica) |
| DP-07, DP-08 / ADR-0014, 0015 | Alcance y requisitos clínico-legales | Sin impacto en baseline |
| DP-09 / ADR-0016 | Java 21 exacto, Boot 4.1.x, MySQL 8.4, Angular 21; medición base; observabilidad en 07.08; SLO en 09.03 | Compatible: enforcer, JaCoCo y k6 ya existen; observabilidad queda correctamente diferida |
| Matriz de permisos | `SecurityConfig` con `permitAll` explícito | Transicional y documentado; F1 lo reemplaza |

**Ninguna contradicción entre el baseline y las decisiones aprobadas.** Caso borde "dos ADRs incompatibles": no se detectó; 0008 y 0009 reparten la misma frontera identity/organization de forma coherente.

## 5. Rebaseline — huecos funcionales de la especificación

El relevamiento exhaustivo de M01, M02, M05, §30–45, M24 y M26 (tres digests, 627 líneas, en el scratchpad de sesión) mostró que **la especificación es una plantilla**: el 95 % del texto de cada RF es boilerplate compartido y el contenido diferencial es una línea. Conforme a §44 del documento de requerimientos, cada hueco se resuelve como **decisión documentada**, nunca en silencio. Etapa destino de cada uno:

| Hueco | Etapa que lo decide |
|---|---|
| Estados y transiciones de **suscripción** y de **organización** (§33 exige máquina; la spec no los enumera) | 01.01 — ADR |
| Campos de entidades, valores de límites y feature gates de plan | 01.01 — ADR |
| Estados de **membership** (§33 exige; no enumerados) | 01.03 — ADR |
| Modelo de permisos granulares (códigos, grants, herencia) | **Resuelto en 00.03** (matriz mínima) |
| TTL de access/refresh, rotación, revocación, sesiones concurrentes, logout | 01.02 — ADR |
| Política de contraseñas, hashing, rate limiting | 01.02 — ADR |
| **Lockout automático** por intentos (la spec NO lo pide) | 01.02 — decisión explícita |
| **Anti-enumeración**: la spec lista `NOT_FOUND` como error de login y reset; implementado literal filtra qué emails existen | 01.02 — respuesta uniforme, ADR |
| "Bloquear" vs "desactivar" cuenta (nombrados, no definidos) | 01.02 — ADR |
| "Verificación de email" (no existe en la spec; solo activación/invitación con enlace seguro) | 01.02 — decidir si se incorpora |
| RF derivado de DP-02: selección/cambio de contexto (sin RF propio en M02) | 01.02 — se especifica como derivado para trazabilidad |
| Patrón **outbox** (la palabra no aparece; lo normativo es RN-M26-001/003, RF-M26-004/005, §34), política de reintentos/backoff, canales | 01.02 — ADR |
| Modelo de "acceso de soporte" de `PLATFORM_ADMIN` | 01.03 (mínimo) / F8 |
| Retención y archivado del log de auditoría | F8 |

**Corrección de referencias del plan contra el documento real:** la matriz de permisos está en **§32** (subsección titulada "27.1" por inconsistencia del documento fuente); **§42 es "Eventos de dominio sugeridos", no auditoría** — la auditoría es el módulo **M24**. Las etapas 01.03 y posteriores deben citar M24 para auditoría.

## 6. Rebaseline — estimación

El plan asume (§12) "dos perfiles backend, dos frontend y QA compartido". La realidad del proyecto es **un responsable humano con agentes de IA orquestados**. Según el propio plan, con equipo menor "se conserva el orden y se amplía calendario". Decisión de rebaseline:

- **El orden de etapas y sus dependencias no cambian.**
- El paralelismo se aplica **dentro** de cada etapa (relevamiento, diseño por etapa, implementación por módulo/repo no colisionante), no entre etapas con dependencia directa.
- No se fija calendario en semanas: la unidad de avance es la etapa cerrada con su registro y sus gates en verde. F0 se cerró en una sesión de trabajo.

## 7. Deuda y pendientes

| Pendiente | Etapa |
|---|---|
| Protección de rama en `main` y remote del frontend | Operativo, fuera del plan |
| SonarQube (job listo, comentado) | Cuando exista instancia |
| Observabilidad | 07.08 según ADR-0016 |

## 8. Contexto para la etapa siguiente (01.01)

- Los tres digests de la especificación y las propuestas de diseño de 01.01/01.02/01.03 están en el scratchpad de sesión; las decisiones que sobrevivan al design challenge se formalizan como ADRs en su etapa.
- Frontera fijada por ADR-0008/0009: `identity` orquesta el onboarding compuesto vía `spi` de `organization`; `organization` es dueño de Organización, Consultorio y Membership; el cálculo de permisos consume `organization` vía `spi`.
- La matriz mínima de permisos es vinculante para 01.03.

---

# Registro de cierre — AKINE-01.01

**Fecha de ejecución:** 22/08/2026
**Estado:** COMPLETADA CON VERIFICACIÓN PARCIAL — ver §11
**Ramas:** `akine-01.01-tenancy` en ambos repositorios
**Modo:** multi-agente — 3 agentes de relevamiento, 3 de diseño, 6 de implementación, orquestados y verificados por el agente principal, con design challenge previo a escribir código.

## 1. Resumen del incremento y comportamiento observable

Primer módulo de negocio de AKINE. Existe el tenant: organización con suscripción, catálogo de planes con límites y feature gates, consultorio y membership mínimos, y la resolución de contexto multi-tenant que **revalida contra la base en cada request**.

Comportamiento observable: el backend expone 10 endpoints REST y publica el contrato **0.2.0**; el frontend consume ese contrato con cliente generado y tiene tres pantallas construidas. **Ninguna operación es ejercitable por un usuario todavía**: no hay login, y todo endpoint de negocio exige contexto autenticado. Eso llega en 01.02.

Lo que sí está probado y funciona: las migraciones aplican sobre MySQL vacío, el esquema valida contra los mapeos JPA, el contrato se regenera sin drift, el cliente TypeScript se genera desde él, y los 7 E2E del baseline siguen pasando contra el stack real.

## 2. Archivos creados

**Backend (147):** módulo `organization` completo —`domain` con 10 entities, enums, `SubscriptionStateMachine` y 6 excepciones; `domain/port` con 11 puertos; `application` con 8 servicios y sus vistas; `spi` con 11 contratos; `infrastructure` con 10 repositorios y el directorio de membership; `api` con 4 controllers, 15 DTOs y el advice del módulo—. En `platform`: `spi/tenant` (5 tipos), `spi/audit` (2), `infrastructure/tenant` (filtro, holder, config), `infrastructure/audit`, entidad y repositorio de auditoría. Migraciones `V2`–`V5`. Documento `docs/tests-diferidos.md`. Tests: 40 clases.

**Frontend (35):** feature `organization` con 3 páginas lazy loaded y sus specs; cliente generado completo (4 servicios, 17 modelos).

## 3. Archivos modificados

| Archivo | Cambio |
|---|---|
| `platform/api/GlobalExceptionHandler.java` | Manejadores genéricos: validación, header ausente, permisos, concurrencia, integridad |
| `pom.xml`, `application.yml` | `akine.contract.version` 0.1.0 → 0.2.0 |
| `openapi/akine-api.yaml` | Regenerado |
| `platform/infrastructure/config/SecurityConfig.java` | Registro del filtro de contexto |
| `core/interceptors/error.interceptor.ts` | `problemType` y los siete tipos conocidos |
| `environments/environment{,.prod}.ts` | `contractVersion` 0.2.0 |
| `app.routes.ts` | Rutas de la feature |

## 4. Migraciones ejecutadas

| Migración | Contenido | Resultado |
|---|---|---|
| `V2` | `organization`, `plan`, `plan_limit`, `plan_feature`, `subscription`, `subscription_transition` | ✅ |
| `V3` | `consultorio`, `membership`, `account_active_context`, `organization_onboarding` | ✅ |
| `V4` | Seed de planes: BASICO (1 consultorio, 5 miembros) y PROFESIONAL (ilimitado) | ✅ |
| `V5` | `audit_event` — append-only, sin `updated_at`, sin baja lógica, sin `version` | ✅ |

Aplicadas sobre base vacía y validadas contra los mapeos JPA (`ddl-auto: validate`) mediante Testcontainers con MySQL 8.4. Todas puramente aditivas: sin fase migrar ni contraer pendiente.

Convenciones respetadas: `utf8mb4_0900_ai_ci`, `organization_id NOT NULL` en toda tabla de negocio, uniques e índices con alcance tenant, `DATETIME(6)` en UTC, baja lógica. Excepciones documentadas: `organization` (es el tenant), `plan`/`plan_limit`/`plan_feature` (catálogo global), y los uniques globales de `slug`, `idempotency_key` y `account_active_context.account_id`.

## 5. Contratos y endpoints

Contrato **0.2.0** (aditivo desde 0.1.0). Endpoints publicados:

`GET /plans` · `POST /organizations` · `GET|PATCH /organizations/{orgId}` · `GET /organizations/{orgId}/subscription` · `POST|GET /organizations/{orgId}/subscription/transitions` · `POST /organizations/{orgId}/subscription/plan-changes` · `GET /organizations/{orgId}/consultorios` · `GET /me/contexts`

**No publicado a propósito:** `PUT /me/active-context`. Cambiar de contexto renueva el token y el token es de `identity`; nace en 01.02 como `POST /auth/context`. Publicarlo aquí habría obligado a retirarlo, y retirar un endpoint es un cambio incompatible.

`spi` expuesto para 01.02 y 01.03: `InitialOrganizationProvisioning` (onboarding compuesto idempotente), `AccountContextDirectory`, `PlanGate`, y en `platform.spi`: `MembershipDirectory`, `AuditTrail`, `AuthenticatedPrincipal`, `TenantContextHolder`.

## 6. Pruebas ejecutadas

| Suite | Comando | Resultado |
|---|---|---|
| Backend — unitarias y arquitectura | `./mvnw test` | **348 / 348** |
| Backend — integración (Testcontainers) | `./mvnw verify` | **9 / 9** |
| Frontend — unitarias | `npm run test:ci` | **66 / 66** |
| Frontend — E2E contra stack real | `npx playwright test` | **7 / 7** |
| **Total** | | **430** |

| Gate | Backend | Frontend |
|---|---|---|
| Cobertura | 81,89 % instr / 83,57 % líneas | 98,75 % statements |
| Arquitectura | 17 reglas ✅ | ESLint ✅ |
| Enforcer | 4 reglas ✅ | — |
| Contrato | sin drift ✅ | alineado 0.2.0 ✅ |

**El margen de cobertura del backend es de 1,89 puntos.** Bajó de 98,35 % al agregar la capa API sin tests, por decisión explícita. El próximo bloque de código sin cobertura rompe el gate.

## 7. Decisiones técnicas y alternativas descartadas

| Decisión | Elegido | Descartado | Razón |
|---|---|---|---|
| Estados de suscripción | `ACTIVA ⇄ SUSPENDIDA`, ambas → `CANCELADA` terminal | Trial, morosidad, reactivación de canceladas | Mínimo que satisface RF-M01-003 y RN-M01-002; extensible por expand |
| Estado de la organización | Deriva de la suscripción | Máquina de estados propia | Duplicarlo habilita la contradicción "organización activa con suscripción cancelada" sin nadie que decida cuál gana |
| Historia de la suscripción | 1 fila mutable + `subscription_transition` append-only | Filas por período | Más simple, cumple RN-M01-002 |
| Rol del primer propietario | `ORG_ADMIN` + atributo `is_founder` | `OWNER` como rol | La matriz aprobada no tiene `OWNER`; RN-M05-006 prohíbe roles por denominación. Ser fundador es un atributo |
| Evaluación de límites | Bloqueo pesimista sobre la suscripción **antes** de contar | Contar fuera de la transacción | La firma original permitía que dos altas concurrentes leyeran 4 contra un límite de 5 e insertaran 6 filas |
| Acceso a repositorios desde `application` | Puertos planos en `domain`, extendidos por las interfaces de Spring Data | Inyectar repositorios directamente | `AGENT.md` documenta `infrastructure → application`; ArchUnit lo verifica |
| Mapeo de excepciones del módulo | Advice propio en `organization.api` con precedencia | Ampliar `GlobalExceptionHandler` | `platform.api → organization.domain` cierra un ciclo: rompe dos reglas de ArchUnit |
| Auditoría | Síncrona, en la transacción del negocio | Listener `AFTER_COMMIT` | Un listener que falla deja la mutación hecha sin rastro |
| Falta de contexto | `403` | `401` | El interceptor del frontend borra el token ante cualquier 401: el usuario quedaría en bucle de login |
| Referencia de otro tenant | `404` | `403` | Un 403 confirma que existe; bastaría probar ids consecutivos para enumerar los clientes del SaaS |
| Validación de contexto | Contra la base en **cada** request, sin caché | Confiar en los claims del token | Ventana de revocación cero. Los claims son una pista, nunca autoridad (RN-M01-003) |

## 8. Problemas encontrados

| # | Problema | Cómo se detectó | Resolución |
|---|---|---|---|
| 1 | El diseño usaba `role_code = OWNER`, que **no existe** en la matriz aprobada | Design challenge | `ORG_ADMIN` + `is_founder`, con test parametrizado que falla si alguien lo reintroduce |
| 2 | `401` ante falta de contexto → bucle de login cerrado | Design challenge, leyendo el interceptor del frontend | `403` con `type` ramificable |
| 3 | Carrera TOCTOU en los límites de plan | Design challenge | Bloqueo pesimista; la firma se rediseñó para que sea imposible usarla mal |
| 4 | `application → infrastructure` violaba la regla de capas | Un agente detectó el código de otro | Puertos en `domain` |
| 5 | **Instrucción equivocada del orquestador**: mapear excepciones del módulo en `GlobalExceptionHandler` cerraba un ciclo | El agente de API lo verificó con una sonda y corrigió su código en vez de la regla | Advice propio del módulo |
| 6 | Conflicto entre diseños: auditoría post-commit vs síncrona | Comparación cruzada de los tres diseños | Síncrona; la tabla se adelantó a 01.01 |
| 7 | Ciclo potencial `identity ⇄ organization` al invitar colaboradores | El diseñador de 01.02 lo anticipó | Regla dura: `organization` nunca depende de `identity`; la aceptación de invitaciones la orquesta `identity` |
| 8 | `operationId: find_1` autogenerado, inestable entre builds | El agente de API lo notó al revisar el diff del contrato | Corregido: habría hecho cambiar el cliente del frontend sin que cambiara la API |
| 9 | Un E2E fallaba por un proceso backend viejo sirviendo 0.1.0 | Diagnóstico del agente de frontend, verificado con `curl` | Reinicio. **El gate de contrato compara el código, no el proceso corriendo** |

Ninguno se resolvió relajando una verificación. El #5 es un error del orquestador que un agente corrigió: la instrucción de "si ArchUnit falla, arreglá tu código, no la regla" funcionó incluso contra una indicación equivocada.

## 9. Deuda técnica, con etapa destino

| Deuda | Etapa |
|---|---|
| **11 escenarios de verificación diferidos** — cross-tenant, concurrencia, idempotencia por HTTP, límites bajo carga, E2E de cambio de contexto. Detallados en `appKine-api/docs/tests-diferidos.md` | **01.02** |
| Los `type` de error no están enumerados en el contrato: el frontend los dedujo leyendo el backend | 01.02 |
| El `ProblemDetail` generado no coincide con el cable (`properties` anidado vs plano); el interceptor usa un tipo a mano, que ADR-0002 prohíbe | 01.02 |
| El motivo de la suspensión no está en `SubscriptionResponse`; la pantalla hace una segunda llamada al histórico | 01.02 |
| `GET /consultorios` pagina en memoria: el puerto no expone paginación | 02.01 |
| Guard provisional de permisos (`ProvisionalAuthorizationGuard`), centralizado con `TODO(AKINE-01.03)` | 01.03 |
| `POST /organizations` no registra idempotencia; el duplicado lo cierra el unique del slug | 01.03 |
| Sin snapshot del contenido del plan al momento de suscribir | F7 |
| Margen de cobertura del backend reducido a 1,89 puntos | 01.02 |
| Ramas `akine-00.02`, `akine-00.03` y `akine-01.01` sin mergear a `main`; sin protección de rama; frontend sin remote | Operativo |

## 10. Contexto para la etapa siguiente (01.02)

- **`identity` orquesta, `organization` provee.** `InitialOrganizationProvisioning.provision(...)` es `@Transactional(REQUIRED)`: `identity` abre la transacción del onboarding y llama a este método. Nunca `REQUIRES_NEW`, o un fallo posterior dejaría la organización creada con la cuenta revertida.
- **`organization` jamás importa `identity`.** Las cuentas se referencian por `accountId`. La aceptación de invitaciones se orquesta desde `identity`.
- **`AuthenticatedPrincipal`** (`platform.spi.tenant`) es el contrato que 01.02 implementa desde el JWT: `accountId()`, `organizationId()`, `consultorioId()`, `platformAdmin()`.
- **`POST /api/v1/auth/context`** es de 01.02: valida y persiste vía `AccountContextDirectory.selectContext(...)` y devuelve el token nuevo. Con eso la auto-selección del frontend empieza a funcionar de punta a punta.
- **Los 11 tests diferidos se vuelven ejecutables** en cuanto exista login. El registro de cierre de 01.02 debe listarlos como ejecutados o justificar por qué siguen diferidos.
- **El `ProvisionalAuthorizationGuard` se reemplaza en 01.03**, en un solo lugar.

## 11. Verificación parcial — qué NO se probó

La etapa **no puede declarar cubiertos** sus criterios de aceptación funcionales, y esto es deliberado:

> "RF-M01 y CA asociados pasan; ninguna consulta M01 cruza tenants; históricos sobreviven a cambios de plan."

Lo verificado es la **lógica**: las reglas de negocio, las transiciones, la idempotencia y el aislamiento están cubiertos por 430 pruebas, incluidas las de integración contra MySQL real. Lo **no verificado** es el comportamiento **de punta a punta con un usuario autenticado**, porque el login no existe hasta 01.02.

Ninguna de las tres pantallas del frontend se ejercitó contra el backend real: sin sesión, todo responde 403. No se construyó un stub de autenticación para que los tests parecieran verdes.

El criterio de aceptación queda **pendiente de verificación**, no cumplido. Se cierra en 01.02 junto con los 11 escenarios diferidos.

---

# Registro de cierre — AKINE-01.02 (Identidad, autenticación y recuperación)

Cerrada el **2026-08-23**. Registro conforme a §10.5.

## 1. Resumen del incremento y comportamiento observable

Un usuario puede registrarse, activar su cuenta, iniciar sesión, elegir su contexto de trabajo,
operar sobre los endpoints de negocio, renovar su sesión sin volver a autenticarse, recuperar su
contraseña y cerrar sesión. **Es el primer incremento que un usuario puede recorrer de punta a
punta**: hasta acá ningún endpoint de negocio era ejercitable, porque no había forma de
autenticarse.

Módulos nuevos: `identity` (dominio, aplicación y capa `api` con 4 controllers, 12 DTOs y advice
propio) y `notification` (outbox transaccional con backoff, reintentos idempotentes y
agotamiento). En `platform`, la cadena de seguridad pasó de `anyRequest().permitAll()` a
**autenticado por default y público por excepción**, con filtro JWT, rate limit y validación de
`Origin` sobre los dos endpoints que se autentican por cookie.

## 2. Archivos creados

`identity/` completo (dominio, `application`, `api`, `infrastructure`, `spi`);
`notification/` completo; `platform/infrastructure/security/` (filtro JWT, rate limit,
entry point y access denied handler como Problem Details, `RequestPaths`);
`platform/spi/security/` y `platform/spi/config/PerfilesDeEjecucion`;
ADRs `0017`–`0020`; `docs/diseno/AKINE-01.03-permisos.md` y `AKINE-02.01-consultorios.md`;
`src/test/java/com/akine/diferidos/` (los escenarios diferidos de 01.01) y
`src/test/java/com/akine/PerfilesDeTest.java`.
En el frontend: `features/auth/` (7 pantallas), `core/services/session.service.ts`,
`core/guards/`, `core/models/rutas.ts`, `core/testing/axe.ts`, y 4 specs de E2E.

## 3. Archivos modificados

`SecurityConfig`, `TenantContextFilter`, `GlobalExceptionHandler`, `ProvisionalAuthorizationGuard`,
`OrganizationService`, ambos `OnboardingService`, `SubscriptionService`, `PlanGateService`,
`MembershipRepositoryPort` y sus cuatro llamadores, `application.yml`, `pom.xml`.
En el frontend: `app.routes.ts`, `app.config.ts`, ambos interceptores, `context-selector-page`,
`login-page`, `environment*.ts`, `angular.json` y el cliente generado.

## 4. Migraciones ejecutadas

`V6`–`V9` (cuenta, tokens de verificación, refresh token, registro de onboarding, outbox de
notificaciones) y **`V10`**, que expande el unique de `membership`. Flyway valida 10 migraciones
contra MySQL 8.4 real en cada corrida de integración.

`V10` merece explicación: `uk_membership_org_account UNIQUE (organization_id, account_id)`
**contradecía RN-M02-002**, que exige que el mismo usuario pueda tener roles distintos en
consultorios distintos. El discriminador es una columna generada
`consultorio_scope BIGINT AS (IFNULL(consultorio_id, 0)) STORED`, y no `consultorio_id` a secas:
en MySQL varios NULL no colisionan en un unique, así que la versión ingenua habría permitido dos
memberships de alcance organización para la misma cuenta — justamente el caso que decide quién
administra el tenant.

## 5. Endpoints, contrato y eventos

Contrato **0.3.0**: 13 endpoints nuevos sobre los 11 previos, **24 operaciones en 22 paths**,
sin drift contra los mappings. Estrictamente aditivo respecto de 0.2.0.

## 6. Pruebas y comandos

`./mvnw -o verify` con Docker: **1042 unitarias + 37 de integración**, 1 diferida. Cobertura
JaCoCo **97,76 % instrucción · 87,92 % rama** sobre un piso de 80 %, que al empezar la etapa
estaba **en rojo al 76 %**. 832 anotaciones de test en 91 clases.
Frontend: `npx ng test --watch=false` → **212 tests**, 97,28 % statements; `npm run lint`,
`npm run build` y `npm run format:check` en verde.
E2E: `npm run e2e` contra el stack real → **39 tests**, tres corridas consecutivas.

## 7. Decisiones adoptadas y alternativas descartadas

- **Secreto de firma sin default, con perfil de desarrollo declarado por nombre positivo.** Se
  descartó la lista de perfiles de producción: es una lista negra disfrazada que falla del lado
  inseguro ante cualquier nombre que nadie previó.
- **Rotación de refresh serializada con lock pesimista**, no con columna `version`: evita tocar
  el esquema de una tabla de credenciales viva.
- **Isolation `READ_COMMITTED` acotada al `PlanGate`**, no global. `@Transactional(isolation=…)`
  sobre un método `MANDATORY` no hace nada: la isolation la fija quien abre la transacción.
- **Orden de bloqueo único `subscription → organization`**, fijado para todo el sistema.
- **Puertos `IdentityClock` y `JitterSource`** para que el tiempo y la aleatoriedad sean
  verificables, en vez de incrustar `Instant.now()` y `ThreadLocalRandom`.

## 8. Problemas encontrados

**Veintiocho defectos reales**, ninguno buscado a propósito. Los que cambiaron el resultado de la
etapa: el secreto de firma con default público; el rate limit evadible codificando el path
(`%6cogin`, medido: 40 de 40 requests sin un solo 429); dos oráculos de enumeración de cuentas
—por `planCode` y por `firstName`— que respondían distinto según el email existiera o no; el
límite de plan evadible por concurrencia; **la respuesta al reuso de refresh que nunca se
persistía**, porque la excepción de negocio provocaba rollback de la revocación escrita una línea
antes; y el canje de contexto que el frontend nunca invocaba, con backend y servicio correctos a
ambos lados de la línea faltante.

**Casi ninguno estaba dentro de una pieza.** Vivían entre dos módulos, entre dos etapas, entre el
código y su supuesto sobre MySQL, o entre lo que el log afirmaba y lo que la base guardaba.

## 9. Deuda diferida, con etapa destino

| Deuda | Destino |
|---|---|
| Escenario 7b: `Idempotency-Key` con payload distinto necesita `request_hash` en `onboarding_registro` | 01.03 |
| `SecureLinkVault` en memoria: no sobrevive multi-instancia ni reinicio | Observabilidad e infraestructura |
| Rate limit en memoria; `X-Forwarded-For` sin proxy de confianza declarado | Observabilidad e infraestructura |
| `SmtpEmailSender` no existe: en modo `log` ningún correo se envía, y ADR-0018 supone que el canal funciona | Antes de producción |
| `PLATFORM_ADMIN` sin origen de dato productivo; `platformAdmin()` autoriza desde un claim | 01.03 |
| Ventana de gracia de 10 s del refresh: inimplementable con la custodia de ADR-0017 | Necesita ADR que la supersede |
| Purga de `refresh_token` vencidos | Declarada en ADR-0017 |

## 10. Contexto para la etapa siguiente

- **La cadena de seguridad está cerrada**: autenticado por default, público por excepción escrita
  ruta por ruta. Un endpoint nuevo nace protegido.
- **El orden de bloqueo del sistema es `subscription → organization`.** 01.03 tiene que respetarlo:
  tomar `organization` primero produce deadlock contra el alta de sede de 02.01.
- **`ProvisionalAuthorizationGuard` sigue centralizado** — los 8 puntos de autorización del módulo
  son 8 llamadas al guard desde 2 controllers. Su reemplazo por el evaluador de la matriz es un
  cambio en un solo archivo.
- **`MembershipSelection` fija el criterio** cuando una cuenta tiene varias memberships: gana la
  más específica, no la más privilegiada, porque no existe jerarquía de roles hasta 01.03 y
  elegirla por privilegio sería inventarla.
- **Todo controller declarado en fuentes de test lleva `@Profile(SOLO_SLICE)`.** Sin eso el
  component scan lo publica en el contrato OpenAPI y rompe el cliente del frontend. Ya pasó.
- **Los tests que importan afirman sobre estado persistido, no sobre códigos de respuesta.** El
  defecto más grave de la etapa tenía el test en verde, el log correcto y el HTTP correcto.

---

# Registro de cierre — AKINE-01.03 (Memberships, roles, permisos y auditoría base)

Cerrada el **2026-08-24**. Registro conforme a §10.5.

## 1. Resumen del incremento y comportamiento observable

La autorización dejó de ser un guard provisional y pasó a ser un **evaluador de la matriz de
permisos**. Un administrador puede sumar colaboradores a su organización, cambiarles el rol y el
alcance, suspenderlos, reactivarlos, revocarlos y otorgarles permisos adicionales acotados. Toda
mutación sensible queda auditada con actor, motivo y estado anterior, y la auditoría es
consultable por entidad, por actor y por período.

## 2. Archivos creados

`organization/domain`: `MembershipEstado`, `PermissionCode`, `PermissionScope`,
`RolePermissions`, `MembershipGrant`, `PlatformRole`, `SupportAccess` y 11 excepciones.
`organization/spi`: `PermissionEvaluator`, `PermissionGuard`, `PermissionQuery`,
`PermissionDecision`, `DenialKind`, `MembershipProvisioning`, `DirectMembershipCommand`.
`platform/spi`: `tenant/PlatformRoleDirectory`, `identity/AccountIdentity`,
`identity/AccountIdentityDirectory`, `audit/AuditQuery`, `problem/ProblemType`.
`organization/application`: `PermissionEvaluatorService`, `AuthorizationGuard`,
`MembershipService`, `SupportAccessService`, `PlatformRoleService`, `AuditQueryService`,
`SupportAccessReadAuditor`, `OperatingActor`.
Capa `api`: `MembershipController`, `AuditEventController`, `PlatformRoleController`,
`PlatformSupportAccessController`, el controller de alta directa en `identity/api` y sus DTOs.
Frontend: `PermissionsStore`, `permissionGuard`, la directiva de permiso, y las pantallas de
colaboradores, alta de colaborador, auditoría y permiso insuficiente.

## 3. Archivos modificados

`ProvisionalAuthorizationGuard` pasó a `AuthorizationGuard`, vaciado por dentro con **firmas y
call sites intactos**. Además `Membership`, `MembershipSelection`, `AccountAdminService`,
`JwtAuthenticationFilter`, `AuthenticatedJwtPrincipal`, `OrganizationProblemHandler`,
`IdentityProblemHandler`, `GlobalExceptionHandler`, `TenantContextFilter`, `SecurityConfig`,
`ProblemResponses`, `compose.yaml` y `TestcontainersConfiguration`.

## 4. Migraciones ejecutadas

`V11` estado y trazas de revocación en `membership`, más el CHECK que prohíbe `PLATFORM_ADMIN`
en `role_code` — que el diseño daba por aplicado en `V10` y no existía.
`V12` `membership_grant` y `platform_role`. `V13` `support_access`. `V14` índices de auditoría y
triggers de inmutabilidad. `V15` seed del primer administrador de plataforma, que **crea la
cuenta y el rol juntos**: resolver el email contra `cuenta` sobre una base limpia insertaba cero
filas sin fallar, y el despliegue inicial quedaba sin ningún administrador en silencio.

`V12` usa columnas generadas que valen NULL cuando el registro está inactivo, para que un grant
revocado salga del unique y se pueda re-otorgar conservando el historial. Es la inversión del
mecanismo de `V10`, donde el NULL era el problema y se materializó con un centinela.

**Trampa de infraestructura:** crear un trigger con binlog activo — el default de MySQL 8.4 —
exige SUPER. Se resolvió con `--log-bin-trust-function-creators=1` en `compose.yaml` y en
Testcontainers. Sin eso no arranca ningún test de integración.

## 5. Endpoints y contrato

18 endpoints nuevos. Contrato **0.4.0** y después **0.6.0**, aditivo en los dos saltos.
`MembershipResponse` gana `accountName` y `accountEmail`, que es lo que sacó a la pantalla de
colaboradores de mostrar "Cuenta 100". Schema `ProblemType` con las **29** URIs del sistema.

## 6. Pruebas y comandos

`./mvnw -o verify` con Docker: **1288 unitarias + 72 de integración**, 1 diferida.
Cobertura JaCoCo **92,97 % instrucción y 84,27 % rama** sobre un piso de 80 %.
Frontend: **296 tests**, 86,97 % líneas, con `lint`, `build`, `format:check` y `coverage:check`
en verde.

## 7. Decisiones adoptadas

- **`PLATFORM_ADMIN` desde tabla propia con seed que crea cuenta y rol.** El usuario aceptó el
  email versionado a cambio de un alta auditable y reproducible.
- **Sin flujo de invitación por mail: alta directa por un admin.** Desviación declarada de
  RF-M05-001 y RF-M05-002. Sin ella la etapa quedaba **sin ninguna vía para crear memberships**.
- **El alta directa recibe un email y responde 404 si no existe cuenta**, con rate limit propio
  de 10 por minuto y **auditoría de cada intento fallido en transacción propia**: el 404 hace
  rollback y se llevaría justo el rastro que la decisión exige conservar.
- **Orden de bloqueo único `subscription` y después `organization`**, respetado aunque la
  operación no consuma cupo de plan. Un orden que se respeta a veces no es un orden.
- **`MembershipSelection` conserva su criterio**: gana la más específica, no la más privilegiada.
- **Las lecturas amparadas por soporte se auditan en `REQUIRES_NEW`.** La regla T-2 existe para
  que no quede una *mutación* confirmada sin rastro; en una lectura no hay mutación que
  confirmar, y el 404 sobre un id ajeno se llevaría la fila.

## 8. Problemas encontrados

**El modelo de acceso de soporte estaba completo en la base, en el dominio y en el documento, y
no se aplicaba en ningún lado.** Ninguna celda de la tabla de roles decía `SOPORTE`, así que esa
rama del evaluador era código muerto y **nada exigía nunca un `support_access` vigente**. La
matriz se contradice a sí misma: §6 da Global a toda la columna de plataforma y §7 declara que
solo se accede por soporte justificado y auditado. Se resolvió fail-closed, con la enmienda
escrita en §9.7.

El criterio que quedó fijado, y que resuelve los casos futuros sin volver a discutir: **¿la
operación deja por sí misma una fila que diga quién la hizo y por qué?** Las mutaciones la dejan;
las lecturas no. Por eso las tres lecturas de datos de un tenant quedaron en `SOPORTE` y las
mutaciones en `GLOBAL`.

También: la auditoría de soporte se perdía en las lecturas por el flush manual de una transacción
`readOnly`; el `PLATFORM_ADMIN` se salteaba el evaluador entero con un `if` temprano; y cuatro
servicios no consultaban el flag de soporte, entre ellos **la propia consulta de auditoría**, así
que se podía leer el rastro de un tenant sin dejar rastro de haberlo leído.

## 9. Deuda diferida

| Deuda | Destino |
|---|---|
| Escenario 7b: `request_hash` en `onboarding_registro` | Sigue abierto |
| `consultorio:manage` y `colaborador:manage` siguen GLOBAL para plataforma | Decisión tomada, revisable si dejan de auditar con actor y motivo |
| `ProblemDetail.type` no referencia a `ProblemType`: el catálogo quedó publicado pero suelto | Próxima que toque contrato |
| `PlatformRoleController` da de alta por `accountId` y no por email | Falta un `spi` que permita moverlo a `identity` |
| `AccountAdminController` promete una distinción de permisos que 01.03 no entregó | Documentado |
| Contradicción §4 contra §5 y §6 de la matriz sobre editar la propia organización | Abierta |

## 10. Contexto para la etapa siguiente

- **El evaluador es la única autoridad de permisos.** `AuthorizationGuard` quedó vacío por dentro
  con sus firmas intactas: los call sites no se tocaron.
- **`OperatingActor.consultorioId` sale del contexto validado, nunca del cliente.** Cuando hace
  falta evaluar sobre un alcance distinto del actor — el alta directa — va por `exigirEnAlcance`
  como parámetro con nombre propio, no disfrazado de contexto.
- **Los tests de concurrencia siembran vigencias un minuto en el pasado.** El reloj del
  contenedor MySQL deriva respecto del de la JVM y **cambia de signo dentro de una misma
  corrida**: se midió un salto de 1,7 segundos en 29, con el motor retrocediendo entre dos
  INSERT consecutivos. Sembrar con `UTC_TIMESTAMP(6)` exacto produce rojos falsos en los dos
  sentidos, y uno de ellos parecía un invariante de seguridad roto cuando el invariante se había
  cumplido.

---

# Registro de cierre — AKINE-02.01 (Consultorios, onboarding y contexto operativo)

Cerrada el **2026-08-24**. Registro conforme a §10.5.

## 1. Resumen del incremento

Una organización puede abrir sedes adicionales, editarlas, darlas de baja lógicamente y
seleccionarlas como contexto de trabajo. Cada sede tiene zona horaria IANA propia e intervalo de
agenda. La baja exige motivo y **no puede dejar al tenant sin ninguna sede activa**.

## 2. Archivos creados y modificados

Creados: `ConsultorioAlta`, cinco excepciones de dominio, `ConsultorioAltaRepositoryPort` y su
repositorio, `ConsultorioService`, `ConsultorioAltaCommand`, `ConsultorioEdicionCommand`,
`ConsultorioEstadoFiltro`, `ZonasHorarias`, `spi/ConsultorioDeactivationProbe`,
`ConsultorioController` y sus DTOs. En el frontend: listado de sedes, wizard de alta de dos
pasos, paneles de edición y de baja, catálogo de zonas y mapeo de errores del módulo.

Modificados: `Consultorio`, `ConsultorioRepositoryPort`, `OrganizationService`,
`OrganizationController`, `OrganizationProblemHandler`, `ConsultorioResponse`, y en el frontend
el selector de contexto y `SedesDelContexto`.

## 3. Migraciones

`V16` expandir — zona horaria, intervalo, motivo de baja, campos institucionales y la tabla
`consultorio_alta` para idempotencia. `V17` backfill de zona desde la organización. `V18`
contraer.

La verificación del backfill es un `CHECK (timezone IS NOT NULL)`: MySQL 8 lo valida contra las
filas existentes al agregarlo, así que una fila sin zona detiene Flyway. Es la única forma
portable de hacer fallar una migración en SQL plano.

El unique de nombre usa **centinela**, no NULL: `UNIQUE(org, name, deleted_at)` habría sido la
inversión exacta de lo buscado, porque todas las sedes activas tienen `deleted_at IS NULL` y
varios NULL no colisionan en MySQL — dos sedes activas homónimas dejarían de chocar.

**Desviación declarada de ADR-0007:** las tres migraciones van juntas en vez de contraer en la
release siguiente, porque no hay despliegue en producción. Escrito en la cabecera de `V16`.

## 4. Endpoints y contrato

4 operaciones nuevas más el filtro `estado` en el listado. Contrato **0.5.0**, aditivo:
`listOrganizationConsultorios` conserva `operationId`, ruta y schema, y el parámetro nuevo tiene
`default: ACTIVO`, así que un cliente viejo ve exactamente lo mismo.

## 5. Criterios de aceptación — cobertura parcial declarada

**CA-M03-002 NO está cubierto.** RF-M03-002 pide crear consultorio, primer box y horario en un
acto: se entrega el consultorio y el intervalo; el **box va a 02.02** — crearlo desde
`organization` viola el ownership de módulos y ArchUnit lo rechaza — y el **horario general va a
F5**. Detalle pieza por pieza en `appKine-api/docs/tests-diferidos.md`, sección AKINE-02.01.

## 6. Pruebas

Incluidas en el `verify` de 01.03. La carrera de la última sede se probó con hilos reales,
**15 ejecuciones**, afirmando sobre el estado final de la base.

## 7. Decisiones adoptadas

- **Baja de la última sede activa: 409 `last-consultorio-required`.** Sin ninguna sede el tenant
  no ofrece contexto seleccionable y solo se recupera con SQL manual.
- **Horario general: solo el intervalo, como columna.** Sin tabla de horarios, porque RN-M03-004
  dice que el horario general no sustituye la disponibilidad profesional y una tabla acá invita
  a que la agenda la tome como fuente de verdad en F5. **Revisable**, anotada en `V16`, en el
  COMMENT de la columna y en el diseño §13.1.
- **Solo `ORG_ADMIN` da de baja una sede**, contra lo que la matriz literalmente permite al
  `CONSULTORIO_ADMIN` sobre la suya: es el mismo caso del self-revoke, nadie destruye el alcance
  desde el que opera. **Enmienda pendiente de confirmación**, anotada en el javadoc y en la
  descripción OpenAPI, así que le llega al frontend.

## 8. Problemas encontrados

**El plan `BASICO` permite una sola sede y es el default del alta self-service**, con el cambio
de plan reservado a `PLATFORM_ADMIN`: un centro que se registra hoy no puede abrir su segunda
sede **ni salir del tope por sí mismo**. Decidido el 24/08/2026 que un `ORG_ADMIN` pueda cambiar
el plan de su organización; **sin implementar**, y queda pendiente qué pasa con el cobro.

`Intl.supportedValuesOf('timeZone')` devuelve solo IDs canónicos y cuál es el canónico depende de
la versión de ICU: en Node 24 la lista trae los alias viejos `America/Cordoba` y
`America/Buenos_Aires`, así que un filtro por prefijo mandaba las dos zonas más usadas del país
al fondo del grupo del resto del mundo.

## 9. Deuda diferida

| Deuda | Destino |
|---|---|
| Primer box del onboarding de sede | 02.02 |
| Horario general | F5 |
| `ConsultorioDeactivationProbe` sin implementación: la baja no consulta turnos futuros | F5 |
| `ORG_ADMIN` cambiando su propio plan | Decidido, sin implementar |
| El primer consultorio del onboarding no pasa por `PlanGate` | Inocuo hoy; silencioso si un plan tuviera `MAX_CONSULTORIOS = 0` |

## 10. Contexto para la etapa siguiente

- **`consultorio` ya tiene zona horaria propia y estado derivado** de `active` y `deleted_at`.
  La zona de la organización quedó como default de alta, no como fuente de verdad.
- **RF-M03-005 no tiene endpoint propio**: la selección de contexto la cumple
  `POST /api/v1/auth/context` desde 01.02.
- **El orden de bloqueo `subscription` y después `organization` también aplica a la baja de
  sede**, que no toca el plan y bloquea igual.


# Registro de cierre — AKINE-02.02 (Espacios, boxes y capacidad física)

> **Este registro es una RECONSTRUCCIÓN, no un acta.** La etapa se commiteó el **25/08/2026**
> sin escribir su registro, y esto se redactó el **26/08/2026**, un día después, por alguien que
> **no estuvo en esa sesión**. No hay diseño de etapa en `appKine-api/docs/diseno/` —02.02 es la
> única etapa cerrada que no lo tiene— ni ADR propia, así que la única evidencia disponible fue:
> el mensaje del commit backend `80fb846` y el del frontend `294006a`, sus diffs completos, la
> cabecera de `V19__m04_espacio.sql`, los javadoc de `resource`, la sección 10 de
> `docs/seguridad/matriz-permisos-minima.md`, `docs/tests-diferidos.md` y lo que las etapas
> posteriores (02.03, 02.04, 02.05) escribieron sobre ella.
>
> **Lo que sigue está marcado.** Los hechos llevan el artefacto que los sostiene. Lo que es
> lectura de intención a partir del código se dice como tal. Lo que no se pudo recuperar está en
> §13 y se declara como no recuperable, no como inexistente.

**Fecha del trabajo:** 25/08/2026. **Fecha de este registro:** 26/08/2026.
**Commits:** backend `80fb846` (10:56 -0300), frontend `294006a` (12:12 -0300), ambos en
`akine-01.02-identidad`. El backend es el commit inmediatamente posterior a `454646c`
(01.03 y 02.01), lo que fija el orden de ejecución sin ambigüedad.

## 1. Qué se entregó

El módulo `resource`, **el primero de M04–M06**, con el catálogo físico de una sede de punta a
punta: alta, lectura, edición, baja lógica con motivo, listado paginado con filtro de estado y la
consulta base de disponibilidad de RF-M04-003.

- **Migración `V19`** — la tabla `espacio`, una sola migración y no tres.
- **Contrato `0.7.0`** — 6 operaciones nuevas, aditivas. El mensaje del commit deja constancia de
  que el diff del YAML tiene **una sola eliminación, la línea de versión**.
- **Cinco `ProblemType` nuevos**, dos de ellos **reservados sin emisor** (`ProblemType.java`).
- **Frontend**: `/espacios` (listado), `/espacios/nuevo` (alta) y `/espacios/disponibilidad`,
  más los modelos `situacion-de-servicio`, `tipos-de-espacio` y `espacio-errors`.

Tamaño del incremento, de `git show --stat`: **47 archivos, +5200/−4** en el backend y
**42 archivos, +4942/−83** en el frontend.

Endpoints, todos bajo `/api/v1/organizations/{orgId}/consultorios/{consultorioId}/espacios`
(`EspacioController`): `POST` (alta), `GET` (listado), `GET /{espacioId}`, `PATCH /{espacioId}`,
`POST /{espacioId}/deactivate` y `GET /availability`.

## 2. La decisión que estructura la tabla: dos ejes de vigencia, no uno

Está escrita en la cabecera de `V19`, así que es **registro y no reconstrucción**.

La etapa pedía "CRUD con vigencia" e "índices temporales futuros", y eso son **dos cosas
distintas** que la tabla separa deliberadamente:

- `active` / `deleted_at` / `deactivation_reason` — **ciclo de vida administrativo**. "Este box
  ya no forma parte del catálogo". Irreversible, con motivo, decidido por una persona.
- `valid_from` / `valid_until` — **ventana operativa**. "Este box entra en servicio el 1 de
  marzo". Es planificación, no baja, y es lo que RF-M04-003 necesita para responder si el recurso
  está disponible **para una fecha**, que no es lo mismo que si existe hoy.

Un espacio se ofrece en el instante T si y solo si `active = 1 AND valid_from <= T AND
(valid_until IS NULL OR T < valid_until)`. El límite superior es **exclusivo** para que dos
ventanas consecutivas del mismo recurso no se solapen en el microsegundo del borde, y el
`CHECK (valid_until IS NULL OR valid_until > valid_from)` prohíbe además la ventana de duración
cero.

Esa separación es la que el frontend tuvo que respetar sin aplanarla: **`estado` y `enServicio`
no son el mismo dato**. Un box cargado hoy que abre el mes que viene figura `ACTIVO` y
`enServicio = false`. El commit del frontend dice por qué se muestra el motivo y la fecha en la
fila: *"si no aparece en un selector de reserva alguien lo va a reportar como bug"*.

## 3. La trampa de los NULL en los UNIQUE de MySQL — 02.02 es una de sus fuentes

`espacio` lleva una columna generada:

```sql
deleted_key DATETIME(6) AS (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')) STORED NOT NULL
```

y el único unique de la tabla es `UNIQUE (organization_id, consultorio_id, name, deleted_key)`.

**Lo que hay que proteger:** dos espacios **vigentes** de la misma sede no pueden llamarse igual
—"Box 1" dos veces es un error de carga que después nadie distingue en una agenda—.
**Lo que no hay que romper:** RN-M04-003, la baja es lógica y los históricos conservan su nombre,
así que un unique de tres columnas condenaría el nombre "Box 1" para siempre.

El reflejo —`UNIQUE (..., name, deleted_at)`— **está roto**: en MySQL, como en el estándar,
varios `NULL` no colisionan, y **todas** las filas vigentes tienen `deleted_at IS NULL`. Es la
inversión exacta de lo buscado: protege el histórico y desprotege lo vigente.

La cabecera de `V19` deja algo que ninguna otra migración del repositorio dejó: el **catálogo de
las tres formas** que el proyecto ya usaba y la constancia de que **no son intercambiables** —
centinela de columna generada (`V10`, `membership.consultorio_scope`), NULL a propósito (`V12`,
`membership_grant.grant_activo`) y centinela de fecha (`V18`, `consultorio.deleted_key`)—. Elige
la tercera porque lo único que distingue dos bajas homónimas del mismo box es el **instante** en
que ocurrieron, y con la forma de `V12` ese discriminador se perdería entero.

Descartadas y verificadas como imposibles, no como peores: usar `id` como discriminador —MySQL
prohíbe que una columna generada referencie una `AUTO_INCREMENT`— y el índice único parcial
`UNIQUE ... WHERE`, que es de PostgreSQL y no existe en MySQL 8.4.

El unique empieza por `organization_id` aunque `consultorio_id` ya determine el tenant por la FK:
un unique sin la columna de tenant es un bug de aislamiento aunque sea redundante hoy.

**Riesgo anotado y no resuelto, en la propia migración:** una vez que exista un espacio dado de
baja y otro activo con el mismo nombre en la misma sede, volver al unique de tres columnas es
imposible sin renombrar uno.

> **Esta etapa es una de las fuentes canónicas del patrón.** 02.03 lo usó dos veces en sentidos
> opuestos (`consultorio_key` y `resuelta_key`) y 02.05 chocó con su variante de filtrado
> (`owner_key = IFNULL(organization_id, 0)`, ADR-0021). Quien lea uno solo de los tres va a creer
> que hay una única forma correcta; hay tres y la elección depende de qué se quiere que salga del
> índice.

## 4. Una sola migración, contra la forma de ADR-0007

`V19` crea la tabla con **todas sus constraints definitivas desde el minuto cero**, sin
expandir-migrar-contraer. La cabecera lo justifica: ADR-0007 exige el ciclo de tres para
**cambios sobre datos existentes**, y acá no hay ninguno — la tabla nace vacía, no hay backfill
que verificar y no hay código viejo corriendo contra un esquema anterior. Las tres migraciones de
02.01 hicieron falta porque `consultorio` **ya tenía filas**.

Constraints que quedaron en la tabla, con su motivo escrito en el DDL:

- `CHECK (capacidad > 0)` — RN-M04-005. Capacidad cero **no es menos cupo**: es exactamente lo
  que la baja lógica expresa, con motivo y con auditoría. Permitir 0 daría dos formas de decir lo
  mismo, una sin rastro de quién la decidió. No hay tope superior en la base: cuánta gente entra
  en un gimnasio es dato del centro, y el acotamiento operativo vive donde se cambia sin migrar.
- `CHECK (tipo IN ('BOX','GIMNASIO','GABINETE','SALA_GRUPAL','PILETA','OTRO'))` — lista cerrada y
  **no tabla de catálogo**, porque RN-M04-007 prohíbe explícitamente "roles o tipos rígidos por
  nombre" y el conjunto es del producto, no del tenant. `OTRO` es el fallback declarado. El tipo
  clasifica el recurso físico y **no decide qué servicios se prestan ahí**: eso es RF-M04-008,
  del módulo `offering`.
- `CHECK` de baja coherente — los tres campos se mueven juntos. Sin él es posible `active = 0` con
  `deleted_at NULL`, que además **rompe el centinela** porque esa fila cae en 1970 junto con las
  activas, y también `active = 1` con motivo de baja cargado.
- Dos índices: `ix_espacio_sede_estado` (listado, con `name` adentro para que el `ORDER BY` salga
  del índice) e `ix_espacio_vigencia`, que es el **índice temporal** que la etapa pedía y que
  consumirá la agenda de F5.

## 5. Dos costuras hacia una fase que no existe, y cómo se documentaron

Esta es la parte de 02.02 que las etapas siguientes citan más.

### 5.1 `EspacioOccupancyProbe` — puerto invertido sin ninguna implementación

La ocupación real de un box son sus turnos (`scheduling`, M12, F5) y sus inscripciones
(`activity`, M28). Ninguno existe. Que `resource` los consultara violaría la regla 1 de
`AGENT.md` §4 y dibujaría la flecha `resource → scheduling`, del cimiento hacia el consumidor:
ciclo, y ArchUnit lo rechaza. Invertido, la flecha va `scheduling → resource.spi`, que es el
mismo patrón de `organization.spi.ConsultorioDeactivationProbe` de 02.01.

Dos operaciones preguntan y **preguntan cosas distintas**: reducir la capacidad necesita el
**pico** de ocupación simultánea hacia adelante —dos turnos consecutivos de una persona ocupan un
lugar, no dos, y sumarlos rechazaría reducciones válidas—; dar de baja solo necesita saber si
queda algo, sin importar cuánto. El javadoc fija además que la sonda se invoca **dentro de la
transacción que ya bloqueó la fila con `FOR UPDATE`**, y que una implementación que abra su propia
transacción rompe esa garantía y reintroduce la carrera. Y que solo mira hacia adelante: reducir
la capacidad no invalida nada de lo ya ocurrido (RN-M04-003).

En F2 la lista de implementaciones es **vacía** y las dos operaciones proceden siempre. El
commit lo dice sin adornos: *"no se simuló con una sonda falsa"*. Los códigos
`espacio-capacity-below-occupancy` y `espacio-has-active-references` se **reservan en el contrato
desde ya** para que su aparición en F5 no sea un cambio de comportamiento sorpresivo.

### 5.2 `EspacioAvailabilityResponse` — cómo se documenta un cero estructural

El javadoc de este record es el **ejemplo de referencia del proyecto** para documentar un valor
que hoy es estructuralmente cero. Dice las tres cosas: qué significa hoy —responde si el recurso
está **en servicio** para la ventana—, qué **no** responde —si está libre de reservas, porque
`lugaresComprometidos` es siempre 0 y `lugaresDisponibles` siempre igual a `capacidad`— y que
**un cliente escrito hoy sigue funcionando** cuando esos números cambien solos, sin que el
contrato cambie.

Y nombra la trampa: *"Lo que NO hay que hacer es rotular `disponible = true` como 'el box está
libre': eso va a ser mentira en cuanto exista la agenda, y el bug no va a parecer de esta etapa."*
El frontend lo tomó literalmente — hay un test que **verifica que las palabras "libre" y
"disponible" no se apliquen a un espacio**.

### 5.3 `EspacioDirectory` — el puerto que convierte la etapa en cimiento

`scheduling` (RF-M04-004) y `clinical` (RF-M04-005) son los consumidores previstos y ninguno
existe. El puerto se declara igual, y su javadoc explica que la alternativa —que F5 lea `espacio`
directamente— habría que reescribirla igual el día que llegue, *"con la diferencia de que ya
habría código escrito contra el atajo"*. Ninguno de sus métodos autoriza nada; el aislamiento de
tenant sí se aplica y ninguna consulta resuelve por id pelado. `find` devuelve también los dados
de baja, porque el consumidor necesita distinguir "ese espacio no es tuyo" (404) de "ese espacio
ya no se usa" (409): colapsarlos haría imposible mostrar un histórico.

> **La asignación de un espacio a un turno y a una sesión NO vive en esta tabla**, y el `COMMENT`
> de `espacio` lo deja escrito: son hechos de `scheduling` y `clinical` y llegan en F5.

## 6. El peligro de nombre: hay DOS "disponibilidad" en este proyecto

Vale la pena decirlo en el registro de la etapa que nombró la primera, porque el choque es real y
sigue vivo:

| Cuál | De qué habla | Dueño |
|---|---|---|
| **Disponibilidad del espacio físico** (RF-M04-003) | Si un box está **en servicio** en una ventana: existe, no está de baja y su vigencia la cubre entera | **M04 — esta etapa** |
| **Disponibilidad del profesional** (M05) | Cuándo atiende una persona: horario semanal, excepciones y feriados | **AKINE-02.04** |

Son **ortogonales**, y el motor de slots de 05.01 va a intersecar las dos. El proyecto ya pagó
por no confundirlas en dos lugares concretos: 02.04 evitó que ninguna clase suya se llamara
`Disponibilidad*` a secas (`BloqueDisponibilidad`, `DisponibilidadEfectiva`, `FranjaEfectiva`), y
el javadoc de `PermissionCodes.COLABORADOR_READ` deja constancia de que **deliberadamente no se
usa `espacio:read`** para leer horarios de personas, aunque su descripción hable de "la
disponibilidad de una sede": *"mezclarlos daría acceso al horario de las personas a quien solo
pidió ver los boxes"*.

En el frontend el choque es visible en las URLs: `/espacios/disponibilidad` es M04 y `/horarios`
es M05.

## 7. Permisos — lo que la etapa hizo y lo que se resolvió después

**Condición original, al construir la etapa.** El catálogo de la matriz §5 **no tenía ningún
código de lectura de espacios**. El único aplicable al recurso físico era `consultorio:manage`, y
la §6 se lo niega justamente a `PROFESIONAL` y `ADMINISTRATIVO`, que son los dos roles que la
etapa declara que deben poder consultar. La etapa **no inventó el código**: las mutaciones
exigieron `consultorio:manage` con alcance CONSULTORIO —lo que la matriz dice— y las **lecturas
autorizaron por pertenencia**, membership vigente con cualquier rol. `espacio:read` quedó escrito
como **PROPUESTO** en la §10.1 de la matriz, sin aplicar. Es el mismo razonamiento con el que
01.03 se negó a inventar un código de edición de organización (§9.2) y con el que 02.05 dejaría
`catalogo:read` y `catalogo:manage` propuestos: **el catálogo es vinculante y agregarle una fila
es una decisión de la matriz, no de una etapa.**

**Resolución.** `espacio:read` fue **aprobado el 25/08/2026** por el dueño del producto, el mismo
día, sobre la propuesta que 02.02 dejó escrita. Está en el catálogo §5 y en la asignación §6, y
la aplicación al código **no ocurrió en el commit de esta etapa**: entró en `c93284a`, posterior.
Hoy las tres lecturas —detalle, listado y disponibilidad— lo exigen con la sede como alcance.

Consecuencias que la aprobación trajo y que hay que decir:

- **El contrato no cambió**: los códigos HTTP de rechazo son los mismos (404 fuera de alcance,
  403 sin contexto o sin permiso).
- **Una membership con rol `PACIENTE` antes leía el catálogo físico por pertenencia y ahora
  recibe 403.** Es el comportamiento que la matriz pide, y es el motivo por el que hacía falta.

**Orden de las dos comprobaciones, y no es cosmético** (javadoc de `EspacioService`): primero
pertenencia, después permiso. Un tenant ajeno tiene que salir por 404 y el evaluador de permisos
responde 403, así que invertirlo convertiría la lectura en un oráculo de existencia de
organizaciones.

**Lo que 02.02 deliberadamente no habilita** (matriz §10.2):

- **`PLATFORM_ADMIN` no puede mutar espacios.** No tiene contexto de tenant y la mutación lo
  exige: 403. Se aparta de la columna `Global` que la §6 le da a *Gestionar consultorio*, y se
  aparta **hacia el lado que no concede de más**. Para las lecturas su alcance es `Soporte`: sin
  `support_access` vigente da 403, y con él escribe `SUPPORT_ACCESS_USED` en la auditoría del
  tenant leído.
- **La mutación exige que la sede de la ruta sea la del contexto validado, incluso para un
  `ORG_ADMIN`.** Es más estricto que la matriz: la del contexto es la única sede que el sistema
  revalidó contra la base en ese request. Un `ORG_ADMIN` que quiera administrar otra sede cambia
  de contexto primero.

En el frontend la contracara es que **el listado y la disponibilidad no llevan `permissionGuard`
y el alta sí**, con el motivo escrito en `resource.routes.ts`: exigir `consultorio:manage` para
mirar la tabla dejaría afuera a medio equipo, mientras que un alta en modo lectura es un
formulario que siempre iba a terminar en 403. **El guard es UX; la autoridad sigue siendo el
backend.** Tampoco hay `:consultorioId` en ninguna URL: la sede es la del contexto, y un id en la
URL sería un segundo lugar desde donde elegir tenant.

## 8. Bloqueos: lo que se bloquea y lo que a propósito no

Del javadoc de `EspacioService`, que lo argumenta explícitamente:

- **Ninguna operación de espacios bloquea `subscription`.** El orden de bloqueo único del sistema
  aplica a las operaciones que consumen o liberan cupo de plan, y **ninguna de estas lo hace: no
  existe ningún `LimitCode` de espacios y la etapa deliberadamente no crea uno**, porque ningún RF
  de M04 declara un tope e inventarlo bloquearía en silencio a los tenants que ya existen. Tomar
  el lock "por las dudas" tampoco es gratis: serializaría toda la administración de un centro
  contra una fila que la operación no lee ni escribe.
- **Sí se bloquea la fila del propio espacio con `FOR UPDATE`**, en las dos mutaciones que deciden
  contra un conteo externo: `update` y `deactivate`.
- **La suspensión de la suscripción no se comprueba acá y no es un hueco**: `TenantContextFilter`
  rechaza con 409 `subscription-suspended` antes de que el request llegue a ningún controller.
  Duplicarlo daría dos reglas que se olvidan por separado.

## 9. Verificación — y qué parte de esto es número verificado

| | Resultado | De dónde sale |
|---|---|---|
| Backend al momento del commit | **1294 unitarias + 81 de integración** | Mensaje de `80fb846`. **No re-ejecutado** al escribir este registro |
| `EspaciosIT` | 4 escenarios más 1 `@RepeatedTest(5)` | Conteo directo sobre `src/test/java/com/akine/diferidos/EspaciosIT.java` |
| `EspacioTest` (dominio) | 6 pruebas | Conteo directo |
| Frontend al momento del commit | **320 pruebas, cinco gates en verde** | Mensaje de `294006a`. **No re-ejecutado** |
| Contrato | `0.7.0`, aditivo, sin drift | Mensaje del commit y diff del YAML |
| **Cobertura de la etapa** | **No se puede establecer** | Ningún commit ni reporte conservado la declara para 02.02 aisladamente |
| **E2E de Playwright** | **NO EXISTEN** | `appKine-web/e2e/` tiene cuatro specs y **ninguno menciona espacios**. No es "no corridos": nunca se escribieron |
| **QA manual contra la base** | **NO CORRIDO** | `docs/tests-diferidos.md`, ítems 17 y 18 |

Los cinco escenarios de `EspaciosIT` corren contra MySQL real y son, por su `@DisplayName`: que
el unique proteja lo vigente y libere lo histórico; que un espacio inactivo se lea con 200 pero
rechace edición y segunda baja con 409; que la ventana operativa sea un eje distinto de la baja;
**cross-tenant es 404, nunca 403**; y dos reducciones simultáneas de capacidad donde gana
exactamente una y la perdedora recibe 409. El `@RepeatedTest(5)` está justificado en el propio
test: la concurrencia no se puede forzar de forma absoluta desde el cliente.

> **La mitad de la carrera de capacidad que involucra ocupación no se pudo ejercer**, porque el
> pico siempre es 0 sin implementaciones de la sonda. El commit lo declara y **no la simuló**.

### 9.1 Sobre "las pantallas nunca se abrieron en un navegador"

Esto hay que matizarlo, porque el `CLAUDE.md` de la raíz afirma lo contrario para 02.02. El
commit `294006a` agrega `scripts/mirar-espacios.mjs`, `scripts/mirar-paneles.mjs` y
`scripts/api-simulada.mjs`: levantan **Chromium real** contra el dev server con `/api` respondida
por fixtures sintéticos y sacan capturas. Sus propias cabeceras dicen que **no son tests y no
afirman nada** — existen para *mirar* el layout, que es lo único que jsdom no ve. Con eso se
encontraron **tres defectos que los cinco gates daban por verdes**:

1. **La columna Acciones estaba vacía y el botón de alta no existía, con el permiso concedido.**
   El único que llamaba a `PermissionsStore.cargar()` era `permissionGuard`, y el listado no lleva
   ese guard porque solo exige ser miembro: nadie pedía los permisos, `cargados()` quedaba en
   `false` y la directiva escondía todo. **Ningún test lo vio porque todos los specs siembran el
   store a mano.** `/organizacion/sedes` tenía el mismo defecto latente. Se corrigió en `33425d9`.
2. La observación del espacio se dibujaba en negrita: vive dentro del `th` de la fila y heredaba
   el peso, así que se leía como parte del nombre del box.
3. Abrir *Editar* no movía el foco y *Dar de baja* sí: dos acciones de la misma fila con
   comportamiento distinto por teclado. Necesita `afterNextRender`; `queueMicrotask` no alcanza
   porque en zoneless el campo todavía no existe.

Y un bug de datos aparecido al diseñar el test del `PATCH`: `validFrom` vuelve del control **con
milisegundos** y el backend lo manda sin ellos, así que comparado como texto el campo cambiaba en
cada guardado y se reenviaba siempre — justo lo que la semántica de campo omitido evita.

> **La distinción que corresponde, entonces:** hubo **inspección visual con navegador real contra
> una API simulada**, y **no** hubo E2E ni QA manual contra el stack real y la base. Las dos cosas
> son ciertas y no son la misma, y la segunda es la que bloquea el deploy.

## 10. Problemas encontrados y no arreglados

- **Jackson 3 pasa `null` por cada componente ausente de un record y `FAIL_ON_NULL_FOR_PRIMITIVES`
  lo rechaza**, así que un JSON válido devuelve 400 sin nombrar el campo. `UpdateConsultorioRequest`
  tiene `long version` y hoy **no explota solo porque el cliente siempre manda ese campo**.
  Encontrado por esta etapa, declarado en el commit, **no arreglado**.
- **El contrato 0.7.0 promete `concurrent-modification` y el código devuelve `conflict`.**
  Detectado recién en 02.04: `resource` lanza el `OptimisticLockingFailureException` **plano**,
  que `GlobalExceptionHandler` mapea a `conflict`; `concurrent-modification` lo emite solo
  `OrganizationProblemHandler`, para la subclase de JPA. Un frontend que switchee sobre ese tipo
  en espacios nunca matchea y el error cae al camino genérico. **Decisión de contrato transversal,
  pendiente del usuario.**
- **Carrera de respuesta rancia en el selector de sede** (detectada en 02.04, presente acá): se
  elige A, se elige B mientras la respuesta de A todavía viaja, A llega última y la pantalla
  rotula los datos de A con el nombre de B.
- **Tres specs de `resource` usan un helper `enviar()` que falla abierto** —usa `?.` y no tira si
  el selector no matchea—, así que renombrar un formulario lo vuelve un no-op y las aserciones
  `expectNone` pasan **vacuamente**. Corregido solo en `horarios` por 02.04; los tres de acá
  siguen así.
- **Las URLs de `/espacios` están escritas a mano en las plantillas.** El pineo de prefijos de
  ruta contra el árbol real solo se aplicó a `/horarios`. Este proyecto ya pagó el bug que eso
  previene: los enlaces de correo a `/activar` cayendo en el comodín `**` con un token válido.

## 11. Deuda diferida

| Deuda | Destino |
|---|---|
| `EspacioOccupancyProbe` sin implementación: pico siempre 0, edición y baja proceden siempre | F5 (`scheduling`, M12; `activity`, M28) |
| `EspacioDirectory` sin consumidores | F5 |
| `espacio-capacity-below-occupancy` y `espacio-has-active-references`: publicados y sin emisor | F5 |
| Mitad de la carrera de capacidad reducida bajo ocupación: no ejercitable | F5 |
| **E2E de Playwright de las tres pantallas: nunca escritos** | Sesión de verificación de interfaz, junto con 02.03, 02.04 y 02.05 |
| **QA manual contra la base: no corrido.** `CLAUDE.md` §6 lo declara bloqueante para deploy | Ídem |
| Escenario diferido 13 — alta de sede concurrente contra mutación de membership: el orden de bloqueo lo sostienen dos comentarios que se citan mutuamente y **ninguna herramienta los compara** | Asignado a 02.02 en `tests-diferidos.md`; **no cerrado** |
| Escenario diferido 14 — `PROFESIONAL` acotado a una sede que no ve la sede nueva y recibe 403 al crear por API | Ídem, **no cerrado** |
| `FAIL_ON_NULL_FOR_PRIMITIVES` sobre `UpdateConsultorioRequest` | Sin etapa asignada |
| `concurrent-modification` contra `conflict` en el YAML | Decisión de contrato transversal del usuario |
| Carrera de respuesta rancia en los selectores | Transversal a 02.02, 02.04 y 02.05 |
| Helper `enviar()` que falla abierto en tres specs de `resource` | Sin etapa asignada |

> **CA-M03-002 sigue parcialmente cubierto después de esta etapa.** 02.01 lo dejó abierto
> esperando el box; 02.02 entrega el box **como recurso propio**, pero el "primer box en el mismo
> acto del alta de sede" **no se implementó**: `tests-diferidos.md` lo sigue marcando **NO
> cubierto** con destino 02.02. Que `organization` cree una fila de `espacio` viola el ownership
> de módulos, así que el acto compuesto necesita orquestación por SPI que nadie escribió. **El
> horario general sigue en F5.**

## 12. Contexto para la etapa siguiente

- **`resource` existe y es el módulo propietario de `espacio`.** Ningún otro módulo la lee ni la
  escribe: lo que se necesite sale por `com.akine.resource.spi`. 02.04 y 02.05 se montaron encima
  de este módulo.
- **Hay dos costuras declaradas y vacías hacia F5** —`EspacioOccupancyProbe` y `EspacioDirectory`—
  y ninguna cambia el contrato al implementarse. Ese es el punto de haberlas escrito ahora.
- **`estado` y `enServicio` son datos distintos y el frontend no puede aplanarlos.** Toda pantalla
  que ofrezca un espacio para reservar tiene que preguntar por el segundo.
- **`lugaresComprometidos` es cero estructural, no cero medido.** Cualquier interfaz que rotule
  "libre" hoy va a mentir cuando llegue la agenda, sin que el contrato cambie.
- **`espacio:read` ya está aprobado y aplicado.** Quien agregue una lectura nueva sobre el
  catálogo físico lo usa; quien necesite leer horarios de personas usa `colaborador:read`, que es
  otro concepto y otra tabla.

## 13. Lo que NO se pudo recuperar

Se declara explícitamente para que nadie lo dé por inexistente:

1. **El diseño de la etapa.** No hay `appKine-api/docs/diseno/AKINE-02.02-espacios.md`. Las otras
   cinco etapas cerradas de F1–F2 tienen el suyo. Las alternativas evaluadas y descartadas **que
   no quedaron escritas en el DDL o en un javadoc se perdieron**.
2. **El *design challenge* de la sesión** —los ocho puntos que `CLAUDE.md` §3 exige por escrito
   antes de pasar a `tasks`—. No quedó ningún artefacto.
3. **Las decisiones que el usuario cerró antes de escribir código**, si las hubo. 02.03 y 02.04
   tienen esa sección y nombran a quien decidió; acá no hay evidencia de qué se preguntó ni qué se
   respondió. Lo único fechado y atribuible es la aprobación de `espacio:read`, y es **posterior**
   al commit.
4. **La cobertura de la etapa.** Ningún reporte conservado la aísla.
5. **Por qué la lista cerrada de tipos es exactamente esa.** El DDL justifica la *forma* —lista
   cerrada y no tabla— pero no la *composición*: por qué `PILETA` está y otras clasificaciones no.
6. **Si hubo `mem_save` de la sesión.** El registro de Engram no se consultó al escribir esto, así
   que puede haber contexto ahí que este documento no incorpora.


# Registro de cierre — AKINE-02.05 (Especialidades, prácticas y nomencladores)

**Fecha:** 25/08/2026. **Diseño completo:** `appKine-api/docs/diseno/AKINE-02.05-catalogos.md`.

> **Nota de orden.** Esta etapa se ejecutó **después de 02.02 y antes de 02.03 y 02.04**. No es
> un salto arbitrario: §14 declara que 02.05 depende únicamente de 01.03, y ni el ciclo de vida
> de colaboradores ni la disponibilidad semanal son prerrequisitos suyos. 02.03 y 02.04 siguen
> pendientes.

## 1. Qué se entregó

Catálogo clínico de M06 completo, backend y frontend: `especialidad`, `practica`, `nomenclador`,
`nomenclador_item` y `catalogo_solicitud`, con alta, edición, baja lógica, búsqueda incremental
paginada, vigencias históricas y el pedido de RF-M06-005.

- **Migración `V20`** — cinco tablas nuevas.
- **Contrato `0.9.0`** — 11 operaciones nuevas, aditivas; el cliente del frontend regenerado y
  fijado en la misma versión.
- **ADR-0021** — las cuatro tablas de catálogo llevan `organization_id` nullable.
- **Matriz de permisos §11** — `catalogo:read` y `catalogo:manage` **propuestos**, con la
  autorización interina que la etapa usó mientras tanto.
- **Frontend**: `features/catalog` con tres pantallas montadas en `/catalogo`.

## 2. La decisión de fondo: dos catálogos en la misma tabla

Un concepto es **de la plataforma** (`organization_id IS NULL`, lo ven todos los centros) o **de
un centro** (`organization_id = <id>`, no lo ve nadie más), y los dos se consultan juntos porque
así los pide el selector clínico. Es una excepción a ADR-0004 y por eso exigió ADR-0021, que
además fija lo que la hace segura: **ninguna consulta filtra por `organization_id`**, se filtra
por `owner_key = IFNULL(organization_id, 0)`, con la lista de dueños armada por el servicio
después de validar el contexto.

El `UNIQUE` va sobre `owner_key` y no sobre `organization_id` porque en MySQL varios `NULL` no
colisionan: la forma ingenua habría dejado sin protección contra duplicados exactamente al
catálogo global, que es el único que ven todos los tenants a la vez.

## 3. Decisiones adoptadas

- **Una vigencia de nomenclador no se edita.** El contrato no publica `PATCH` sobre
  `nomenclador_item`: lo que se presentó a un financiador conserva el código y el valor que
  regían entonces (RN-M06-002, RN-M06-003). Se cierra la vigente y se abre otra. Dos vigencias
  del mismo código que se pisen dan `409 nomenclador-vigencia-overlap`.
- **El código de un concepto no se edita.** Nombre y descripción sí; el código es la clave
  estable con la que el histórico resuelve.
- **`PLATFORM_ADMIN` no ve ni muta conceptos contextuales de ningún tenant**, y por eso el módulo
  no necesita `support_access`: lo único que la plataforma toca es el catálogo común, que no es
  dato de nadie. Se apartó hacia el lado que no concede de más, igual que hizo 02.02 con los
  espacios.
- **Aprobar una solicitud no crea el concepto global.** La aprobación es una decisión registrada;
  la publicación es un alta aparte, con el rol de plataforma.
- **El frontend no crea conceptos globales ni resuelve solicitudes.** Las dos exigen rol de
  plataforma y hoy **no hay ningún endpoint que le diga al frontend si quien mira lo tiene**. Un
  selector de alcance o un botón de aprobar serían, para casi todos, acciones que terminan en
  `403`.

## 4. Permisos — interinos y declarados

M06 necesita dos códigos que el catálogo vinculante de la matriz no tiene, y la etapa **no los
inventó**: mismo criterio que 01.03 (§9.2) y 02.02 (§10.1). Mientras tanto las lecturas se
autorizan por pertenencia, las mutaciones contextuales por `consultorio:manage`, y las globales
por rol de plataforma. La propuesta y las dos diferencias que su aprobación introduciría están en
`appKine-api/docs/seguridad/matriz-permisos-minima.md` §11.

## 5. Verificación

| | Resultado |
|---|---|
| Backend `./mvnw verify` | **VERDE** — 90 tests de integración contra MySQL real, 1 diferido; gates de cobertura cumplidos |
| Tests de catálogo | 46 (unitarios, de aplicación e integración), `CatalogosIT` incluido |
| Frontend `npm run build` | **VERDE** |
| Frontend `npm run lint` | **VERDE** |
| Frontend `npm run test:ci` | **VERDE** — 361 tests, 85,97 % instrucción · 80,25 % rama (piso 80 %) |
| Contrato | `npm run api:check` alineado en 0.9.0 |
| **E2E de Playwright** | **NO CORRIDOS** |
| **QA manual contra la base** | **NO CORRIDO** |

Las tres pantallas nuevas auditan con axe en sus specs, pero **nunca se abrieron en un navegador
real**: la verificación de esta etapa es de tests, no de uso.

## 6. Problemas encontrados

- **El frontend no puede saber si quien lo usa es administrador de plataforma.** No hay endpoint
  que lo exponga, y `GET /me/permissions` devuelve permisos de tenant. Es lo que dejó sin
  consumidor a `resolveCatalogoSolicitud` y sin selector de alcance al alta. Es un pedido concreto
  al backend, no un parche de frontend.
- **AKINE-02.02 se commiteó sin registro de cierre en este plan.** Esta etapa no lo escribió por
  ella: nadie que no haya estado en esa sesión puede reconstruir sus decisiones con fidelidad.
  Queda anotado como hueco de documentación.

## 7. Deuda diferida

| Deuda | Destino |
|---|---|
| Consola de plataforma: resolver solicitudes y administrar el catálogo global desde la interfaz | Etapa propia. **Bloquea RF-M06-005 de punta a punta**: hoy una solicitud se resuelve por API |
| Endpoint que exponga el rol de plataforma al frontend | Prerrequisito del anterior |
| `catalogo:read` y `catalogo:manage` aprobados en la matriz §5 | Pendiente de decisión |
| ADR que consolide y supersede a ADR-0019, ADR-0020 y ADR-0021 | Antes de la cuarta excepción a ADR-0004 |
| E2E y QA manual de las tres pantallas | Antes de considerar la etapa verificada en uso |
| Importación masiva de un nomenclador nacional | Sin etapa asignada |
| Registro de cierre de AKINE-02.02 | Hueco de documentación |

## 8. Contexto para la etapa siguiente

- **`com.akine.resource.spi.CatalogoDirectory` es el único punto de entrada** de otros módulos al
  catálogo. M14 y M16 no leen las tablas: piden un `CatalogoSnapshot`.
- **02.06 hereda la pareja global/contextual entera**: el servicio global y la oferta por
  consultorio se construyen sobre el mismo `owner_key`.
- **Toda tabla futura que referencie un concepto tiene que aceptar que la referencia puede ser
  global**: no puede asumir que el `organization_id` del concepto coincide con el suyo.
- **Siguen pendientes 02.03** (ciclo de vida de colaboradores) **y 02.04** (disponibilidad
  semanal, excepciones y feriados).


# Registro de cierre — AKINE-02.03 (Ciclo de vida de colaboradores)

**Fecha:** 25/08/2026. **Diseño completo:** `appKine-api/docs/diseno/AKINE-02.03-colaboradores.md`.

## 1. Qué se entregó

La invitación a colaborar de M05, de punta a punta: un administrador invita por email a alguien
que **puede no tener cuenta**, esa persona acepta o rechaza con el token del enlace como única
autoridad, y al aceptar se crean cuenta y vínculo en una transacción.

- **Migración `V21`** — `colaborador_invitacion`, propiedad de `identity`.
- **Contrato `0.10.0`** — 8 operaciones nuevas, aditivas; cliente del frontend regenerado y
  fijado en la misma versión.
- **Frontend**: la pantalla del administrador (`/organizacion/colaboradores/invitaciones`) y la
  pública del invitado (`/auth/invitacion`).

## 2. Las tres decisiones que el usuario cerró antes de escribir código

| Pregunta | Decisión |
|---|---|
| Qué pasa con el alta directa, que exige cuenta existente | **Conviven**: el alta directa es un click para quien ya está en AKINE, la invitación es el camino para quien no. 02.03 queda **puramente aditiva** |
| Cómo obtiene cuenta un invitado que no la tiene | **Registro y aceptación en un acto**: el enlace pide nombre y contraseña, crea la cuenta ya activa y acepta, todo junto |
| RN-M05-004 con M12 inexistente | **Puerto sin implementación**, como el `ConsultorioDeactivationProbe` de 02.01 |

## 3. La decisión de seguridad de la etapa

**Hay dos formas de autorizar y no es una excepción cómoda: es la única posible.** Emitir,
listar, reenviar y cancelar exigen `colaborador:manage`. Consultar, aceptar y rechazar **no
exigen nada**, porque quien acepta no pertenece al tenant y puede no tener cuenta: exigirle
`colaborador:manage` sobre una organización en la que no tiene membership haría que ninguna
invitación pudiera aceptarse nunca.

Lo que autoriza es el token, verificado contra su SHA-256. La autorización real ocurrió antes —el
administrador emitió la invitación **con** el permiso, y quedó auditado como
`INVITACION_EMITIDA`—. Por eso `createFromInvitation` es un método aparte de `createDirect`, y su
javadoc dice, en el SPI y en la implementación, que es de uso exclusivo del servicio de
invitaciones.

Dos consecuencias declaradas:

- **La cuenta del invitado nace ACTIVA**, sin segundo correo de activación: el token ya probó la
  dirección, y verificarla dos veces agrega el único paso donde la mitad de la gente abandona.
- **Aceptar NO devuelve sesión.** Quien acepta con una cuenta que ya tenía probó que llega al
  buzón, no que la cuenta sea suya. Devolver sesión ahí sería un login sin contraseña.

## 4. Decisiones adoptadas

- **Expirar no es un estado.** `EstadoInvitacion` tiene cuatro valores y EXPIRADA no es uno: se
  deriva de `expira_en` en cada lectura. Consecuencia deliberada: una invitación vencida sigue
  ocupando el `UNIQUE`, porque reinvitar a alguien cuyo enlace venció es un **reenvío**.
- **Una sola invitación pendiente por persona y alcance**, sostenido por dos centinelas
  —`consultorio_key` y `resuelta_key`— porque en MySQL varios `NULL` no colisionan y esta tabla
  se cruza con esa trampa dos veces, en sentidos opuestos.
- **Una vigencia de token no se comparte**: reenviar rota el token y el anterior deja de servir
  en el mismo acto.
- **El motivo es obligatorio al cancelar y opcional al rechazar.** Cancelar es la decisión del
  administrador sobre alguien a quien ya le escribió; al invitado no se le exige explicar por qué
  no quiere entrar a trabajar a un lado.
- **El token vencido responde 409 y no el 404 uniforme.** Es la única excepción a ADR-0018 del
  módulo, y es segura: quien presenta el token ya demostró que es el destinatario, así que no hay
  nada que enumerar.

## 5. Dos cosas que la etapa arregló y no estaban en su alcance

### 5.1 `MAX_MIEMBROS_ACTIVOS` estaba configurado y no se aplicaba

El límite existe en `LimitCode` desde 00.01 y `TenantUsageCounter` sabe contarlo desde 01.03,
pero **ninguna alta lo consultaba**: un plan que declaraba cinco miembros admitía quinientos. Se
cierra acá porque 02.03 es la etapa que convierte el alta de colaboradores en un flujo real.

**Emitir no consume cupo; aceptar sí.** Si emitir lo consumiera, un administrador podría dejar
sin cupo a su organización invitando a diez personas que nunca respondan. La contracara
declarada: cinco invitaciones pendientes con un solo lugar libre significan que cuatro reciben
409 al aceptar. Entra el que llega primero.

### 5.2 Los enlaces de correo apuntaban a rutas que el SPA no sirve

`IdentityProperties.Links` traía `/activar` y `/restablecer`; el frontend monta esas pantallas
bajo `/auth`. Un enlace a `/activar` pelado cae en el comodín `**` y el usuario ve "página no
encontrada" **con un token perfectamente válido en la URL**. Está así desde 01.02: activación y
recuperación de contraseña no se podían completar desde el correo. Corregido a `/auth/activar`,
`/auth/restablecer` y `/auth/invitacion`.

> Ningún test lo cubría porque los tres casos del builder fijaban el valor por defecto que estaba
> mal, y el resto configuraba su propia ruta. El test ahora fija el prefijo y dice por qué.

## 6. Verificación

| | Resultado |
|---|---|
| Backend `./mvnw verify` | **VERDE** — 1379 unitarias + 94 de integración contra MySQL real, 1 diferida; gates de cobertura cumplidos |
| `InvitacionesIT` | 4 escenarios: circuito completo del invitado nuevo, los dos centinelas del `UNIQUE`, aislamiento cross-tenant, reenvío y cancelación |
| Frontend `npm run build` · `lint` · `test:ci` | **VERDE** — 382 tests, 86,02 % instrucción · 80,06 % rama (piso 80 %) |
| Contrato | `npm run api:check` alineado en 0.10.0 |
| **E2E de Playwright** | **NO CORRIDOS** |
| **QA manual contra la base** | **NO CORRIDO** |

Las dos pantallas nuevas auditan con axe, pero **nunca se abrieron en un navegador real**.

## 7. Problemas encontrados

- **Los dos controllers declaraban el mismo tag de OpenAPI con descripciones distintas**, y el
  generador del cliente rechaza esa spec como inválida. Se separaron en `Invitaciones` y
  `Invitaciones recibidas`, que además es la distinción correcta: son dos públicos.
- **El límite de plan obligó a reordenar `createDirect`**: el gate bloquea `subscription`, que es
  el primero del orden de bloqueo del sistema, así que va antes de `bloquearTenant`. Invertirlo
  reintroduciría el deadlock que 02.01 documentó.

## 8. Deuda diferida

| Deuda | Destino |
|---|---|
| `ColaboradorDesvinculacionProbe` sin implementación: el impacto siempre responde cero | Etapa de agenda (M12) |
| Si algún día hay más de una sonda, el impacto pasa a ser un array | Cambio de contrato, anotado en `impactoDe` |
| E2E de Playwright de las dos pantallas | No corridos |
| Purga de invitaciones pendientes viejas | Sin etapa asignada: hoy quedan para siempre, ocupando su `UNIQUE` |
| Que un `ORG_ADMIN` pueda subir de plan cuando el tope de miembros lo frena | Arrastrada de 02.01, ahora también alcanzable desde acá |

## 9. Contexto para la etapa siguiente

- **Hay dos vías de alta de colaborador y las dos pasan por `MembershipProvisioning`**: quien
  agregue una tercera tiene que decidir explícitamente qué la autoriza.
- **`MAX_MIEMBROS_ACTIVOS` ahora se aplica**: cualquier etapa que cree memberships puede recibir
  409 por tope de plan.
- **02.04** (disponibilidad semanal, excepciones y feriados) hereda los profesionales vinculados
  por sede, que es el contexto que declaraba necesitar. Es el próximo paso.


# Registro de cierre — AKINE-02.04 (Disponibilidad semanal, excepciones y feriados)

**Fecha:** 26/08/2026. **Diseño completo:** `appKine-api/docs/diseno/AKINE-02.04-disponibilidad.md`.

## 1. Qué se entregó

La disponibilidad de un profesional en una sede, de punta a punta: cuándo atiende, qué la
recorta y por qué un día quedó vacío. Trazabilidad: RF-M05-003, RF-M05-004, RF-M05-005;
RN-M05-001, RN-M05-002, RN-M05-003.

- **Migraciones `V22` y `V23`** — cuatro tablas: `feriado` (global, sin `organization_id`),
  `consultorio_calendario` (la política de feriados de la sede), `profesional_disponibilidad`
  (los bloques recurrentes) y `disponibilidad_excepcion` (cierres y aperturas).
- **ADR-0022** — `feriado` es global y no lleva `organization_id`, con el precedente de
  ADR-0019 (`identity`) y ADR-0021 (catálogos clínicos).
- **Contrato `0.11.0`** — diez operaciones nuevas, aditivas; cliente del frontend regenerado y
  fijado en la misma versión.
- **Un calculador puro** de disponibilidad efectiva, sin acceso a base ni a reloj, que recibe
  reglas y devuelve días resueltos con la regla que produjo cada franja y la que la recortó.
- **Frontend**: cuatro pantallas montadas bajo `/horarios` —horario semanal, cierres y
  aperturas, horario efectivo y feriados de la sede—, más el modo lectura para `PROFESIONAL`.

## 2. Las decisiones que el usuario cerró antes de escribir código

| Pregunta | Decisión | Por qué |
|---|---|---|
| Módulo propietario | **`resource`** | La disponibilidad es un recurso del centro, y `resource` ya consume `organization.spi`. No agrega ninguna flecha nueva entre módulos |
| Recurrencia | **Bloques recurrentes con vigencia; la efectiva se calcula al leer** | Una tabla materializada obliga a un job, y entre que una regla cambia y el job corre la base dice una cosa distinta de la verdad |
| Husos | **Hora local + día de semana; el huso sale de `consultorio.timezone`** | Un "lunes 09:00" tiene que seguir siendo 09:00 después de un cambio de huso. Guardado como instante UTC se corre solo, y el corrimiento aparece meses después como turnos desfasados una hora sin que nadie haya tocado nada |
| Feriados | **Calendario nacional seedeado + cierres manuales de la sede** | Un feriado es un hecho del calendario, no una decisión operativa: muchos centros de kinesiología atienden los feriados. La decisión es de la sede y vive en `consultorio_calendario.cierra_por_feriado` |
| Permiso del `PROFESIONAL` | **Solo lectura** | Sale de la matriz sin inventar códigos: `colaborador:read` la matriz §6 se lo da, `consultorio:manage` se lo niega. Cero permisos nuevos en esta etapa |

Sobre el cruce de medianoche: `hora_hasta <= '24:00:00'` lo resuelve **sin permitirlo**. Un
bloque nocturno se carga como dos filas. Si se permitiera `22:00–02:00` en una sola, toda
comparación `desde < hasta` mentiría y el solapamiento dejaría de ser detectable con
aritmética simple.

## 3. Cuatro reglas que cambiaron el diseño en el medio de la etapa

**R10 — una apertura en un feriado REEMPLAZA el horario base, no cancela el cierre.** El
diseño decía lo segundo y estaba mal. El caso que lo rompe: un centro trabaja los lunes de
08:00 a 18:00 y su política es cerrar los feriados; el 25 de diciembre cae lunes y el admin
declara "este año abrimos de 10:00 a 14:00". Con la regla vieja el día resolvía a
`08:00–18:00 ∪ 10:00–14:00 = 08:00–18:00`: la apertura no servía para nada y el sistema
ofrecía ocho horas de turnos un día que el centro pensaba abrir cuatro. Declarar una apertura
especial tiene que significar que la apertura *es* el día. **Consecuencia que hay que tener
presente:** una apertura de alcance sede en un feriado descarta el horario base de *todos* los
profesionales de esa sede ese día, y por eso la pantalla avisa cuántos quedan afectados antes
de guardar.

**R13 — la vigencia del vínculo se evalúa día por día, no contra la ventana.** Con una
comprobación gruesa, un vínculo que termina el 15 de marzo devuelve sus bloques todo marzo: la
pantalla ofrece turnos el 20 con alguien que ya no trabaja en el centro, y el motor de slots de
F5 los va a reservar. El día que el vínculo no cubre se vacía con `VINCULO` como motivo, así
que **`razonVacio` tiene cuatro valores** —`FERIADO`, `CIERRE`, `VINCULO` y `null`— y el
frontend escribe un texto propio para cada uno: *"ese día no atiende"* y *"ya no trabaja acá"*
no son lo mismo para quien mira la agenda, y el mismo cartel manda al admin a buscar un cierre
que no existe. La comprobación se hace sobre el día entero `[F, F+1)` y no sobre el instante de
arranque, porque `validAt(00:00)` descartaría la tarde del día en que alguien se incorpora a
las 14:00.

**R14 — `GET /api/v1/feriados` salió del contrato.** Eran once endpoints y son diez. Ningún
servicio de la etapa podía servirlo: `CalendarioService.ver` es por sede, exige `consultorioId`
y autoriza con `colaborador:read`, y ninguna tarea creaba una lectura global. Habría obligado a
inventar un método fuera de plan o a publicar un controller sin nada detrás. Y no hace falta:
`GET /consultorios/{cid}/calendario` ya devuelve la política de la sede junto con los feriados
de la ventana, que es lo que consumen las pantallas.

**R16 — se quitaron los enums de `razonVacio` y `recortadoPor`.** Springdoc renderiza un campo
nullable con `allowableValues` en OpenAPI 3.1 como `type: [string, "null"]` con un `enum` que
**no** incluye `"null"`: el tipo permite el nulo y el enum lo prohíbe. Un cliente generado que
valide estricto habría rechazado nuestra propia respuesta la primera vez que un día volviera
sin motivo — y cinco tareas de frontend dependían de esa generación. Los dos campos quedan
`nullable` y sus valores pasan a la descripción en prosa. **Costo aceptado y declarado:** las
pantallas pierden el chequeo en tiempo de compilación de los cuatro valores de `razonVacio` y
los dos de `recortadoPor`, y hay que copiarlos del contrato a mano.

## 4. Lo que encontraron los reviews, y no los tests

Cuatro defectos reales que ninguna suite en verde estaba señalando. Se anotan porque los cuatro
son formas que se repiten.

- **`ConsultorioMembershipSnapshot.validAt` era ciego al estado.** Copiaba a `MembershipSnapshot`
  y miraba solo `active` más la vigencia, mientras que `Membership.validAt` en el dominio sí
  consulta `estado.habilita()`. Una membership **`SUSPENDIDA` leía como válida**: un profesional
  suspendido habría seguido ofreciendo horarios, exactamente lo contrario de para qué sirve
  suspender a alguien. `habilitada` lo calcula ahora el adaptador desde `MembershipEstado`, nunca
  comparando strings dentro del record.
- **La sonda de impacto de la edición preguntaba por la ventana equivocada.** `editar` dimensionaba
  la consulta con el bloque **ya editado**: un bloque martes 09–12 sin fin, con turnos hasta
  diciembre, al que se le pone `vigenciaHasta` el 1 de septiembre consultaba `[hoy, 2026-09-01)`, y
  los turnos que la edición deja huérfanos son justo los **posteriores** a esa fecha. Habría
  devuelto cero siempre. Se resolvió con la **unión** de la vigencia previa y la nueva, y cambiando
  la firma a `impactoDe(finPrevio, finNuevo)` para que el método no pueda volver a leer el estado
  equivocado por accidente.
- **El contador de la pantalla de desvinculación inflaba el número.** Contaba todos los bloques
  activos, incluidos los de vigencia ya vencida. Un admin veía "quedan N bloques colgando" con un
  número plausible que nadie iba a auditar. Pasó a contar solo los vigentes.
- **Dos tests de integración iban a empezar a fallar solos.** El fixture sembraba `valid_from` un
  minuto atrás —deslizándose con el reloj de pared— mientras los escenarios usan fechas fijas de
  2026: `desvincular_no_borra_la_disponibilidad` fallaba a partir del **2 de septiembre de 2026** y
  el del feriado a partir del **26 de diciembre**. Los dos habrían fallado con el motivo `VINCULO`,
  o sea rojo sin nada roto. La causa raíz era el fixture, no las constantes: `valid_from` pasa a
  sembrarse cinco años atrás, lo que cumple el mismo propósito de tolerancia al skew de reloj y
  desacopla la clase entera del calendario de forma permanente.

## 5. Verificación — y dónde está exactamente la línea

| | Resultado |
|---|---|
| Backend `./mvnw verify` | **VERDE** — **1527 unitarias + 118 de integración** contra MySQL 8.4 real vía Testcontainers, 1 diferida, 0 fallos |
| Cobertura backend | 89,69 % línea · 88,94 % instrucción · **78,03 % rama** (ver §6) |
| Frontend `npm run build` · `lint` · `test:ci` | **VERDE** — **516 tests** en 59 archivos; 87,72 % statements · **81,63 % branches** · 87,12 % functions · 89,03 % lines, gate de 80 % cumplido en las cuatro métricas |
| Contrato | `0.11.0` publicado y **validado contra el generador real**: el cliente se regenera y compila, sin drift |
| **E2E de Playwright** | **NO CORRIDOS** |
| **QA manual contra la base** | **NO CORRIDO** |

**Tres cosas están medidas y no razonadas, que es la diferencia que importa.**

- **El lock de concurrencia.** Con `lockByScope` sobre la fila de `consultorio_calendario`, el
  escenario de dos altas solapadas simultáneas da 8/8 verde. Reemplazándolo por una lectura sin
  bloqueo, el test falla con "Desenlaces: [OK bloque 2, OK bloque 1]", expected 1 but was 2: **los
  dos hilos guardaron**. La mutación se corrió, se reportó y se revirtió. El revisor descartó una a
  una las explicaciones alternativas —no hay unique en la tabla, no hay retry en el camino, el par
  09–13 / 11–15 solapa sin coincidir así que la rama idempotente no puede tragarse al perdedor, y
  un deadlock de ambos fallando también rompería el test—.
- **El orden de etapas del calculador.** Romper la etapa de aperturas hace fallar exactamente el
  test que pinea el orden, y ninguno de los otros 25. Uno de ellos muestra el defecto de R10 en su
  forma exacta: el 25/12 resolviendo a 08:00–18:00.
- **El comparador de determinismo.** El test viejo de shuffle no discriminaba: pasaba igual con los
  dos comparadores borrados. El nuevo sí — borrarlos produce `reglaVacio = 50` donde el test espera
  `51`, o sea el mismo input reportando distinta regla según el orden de filas que devuelva la base.

**Lo que NO está verificado, sin suavizarlo:**

- **Ninguna de las cuatro pantallas se abrió jamás en un navegador real.** No se corrió ningún E2E
  y no se hizo QA manual contra la base. `appKine-api/CLAUDE.md` §6 declara ese QA **bloqueante
  para deploy**: esta etapa no lo tiene. 02.02, 02.03 y 02.05 están en la misma condición; esta al
  menos lo dice por escrito.
- **El contraste de color no está verificado en ninguna parte.** La regla `color-contrast` de axe
  **nunca reporta bajo jsdom**: siempre vuelve `incomplete`, porque jsdom no calcula layout. Las
  auditorías de accesibilidad de todo el repositorio comparten esa ceguera. Además, los estados
  nuevos de modo lectura nunca pasaron por axe, y no hay regla CSS para `input:disabled` en
  `resource.css`, así que la casilla deshabilitada renderiza con el estilo por defecto del
  navegador, sin revisar.
- **`DisponibilidadImpactProbe` devuelve cero por construcción.** RN-M05-004 —los turnos futuros
  afectados quedan visibles para resolución— **no cierra** y no es cerrable acá: no existe ningún
  turno que pueda estar afectado. La costura está declarada y **cableada** en `editar` y
  `darDeBaja`, así que cuando `scheduling` la implemente el escenario se ejecuta sin cambiar el
  contrato.

## 6. Deuda que excede esta etapa

**La cobertura de rama del backend está en 78,03 % (2135 de 2736), por debajo del 80 que el
proyecto cree tener.** El build pasa **legítimamente**: `pom.xml` gatea `LINE` (89,69 %) e
`INSTRUCTION` (88,94 %) sobre el BUNDLE y **no gatea `BRANCH`**. Nadie bajó ningún gate. El
registro de cierre de 01.02 declaraba **87,42 % de rama**: cayó nueve puntos a lo largo de
02.01–02.04 y nada lo detectó, porque el gate de rama que el proyecto cree tener sobre el
backend no existe. El contraste que vale la pena anotar: **el frontend sí gatea rama al 80 %**
y está en 81,63 %, y por eso la suya no pudo caer en silencio. Agregar la regla hoy **rompe el
build**, así que exige primero subir la cobertura: es una decisión del usuario, no un fix de
cierre.

**`concurrent-modification` contra `conflict`: los contratos ya publicados de 02.02 y 02.05
prometen un tipo que su código nunca devuelve.** `resource`, `espacio` y `catalogo` lanzan el
`OptimisticLockingFailureException` **plano**, que `GlobalExceptionHandler` mapea a `conflict`.
`concurrent-modification` lo emite únicamente `OrganizationProblemHandler`, y solo para la
subclase de JPA. Dos revisores independientes llegaron a esto por caminos distintos. Quedan **13
ocurrencias** de `concurrent-modification` en el YAML, todas preexistentes. Se corrigió **solo
para M05**: unificar los dos tipos cambia respuestas de todos los módulos en silencio y es una
decisión de contrato transversal, no algo que se haga desde el advice de un módulo. Consecuencia
mientras tanto: un frontend que switchee sobre `concurrent-modification` en espacios o catálogos
nunca matchea, y el error cae al camino genérico.

**El contrato entero no declara ningún `securityScheme`.** Es preexistente y consistente en
todos los módulos, no lo introduce esta etapa. El frontend funciona porque agrega la
autenticación por interceptor, pero **un cliente generado no sabe que la API pide token**.

**Nueve specs de otras features tienen un helper `enviar()` que falla abierto.** Usa `?.` y no
tira si el selector no matchea, así que renombrar un formulario lo vuelve un no-op y las
aserciones `expectNone` pasan **vacuamente**. Están en `auth`, `catalog`, `organization` y tres
de 02.02 en `resource`. Corregido solo en esta feature.

**El pineo de prefijos de ruta se aplicó solo a `/horarios`.** `/auth`, `/organizacion`,
`/espacios` y `/catalogo` siguen con sus URLs escritas a mano en las plantillas. El patrón es una
constante más un spec que resuelve las URLs contra el árbol real de rutas: barato de replicar, y
**este proyecto ya pagó el bug que previene** —los enlaces de correo a `/activar` cayendo en el
comodín `**` con un token perfectamente válido en la URL, durante meses—.

**Carrera de respuesta rancia en los selectores de profesional**, presente en 02.02, 02.04 y
02.05: se elige A, se elige B mientras la respuesta de A todavía viaja, A llega última y la
pantalla rotula los datos de A con el nombre de B. Todas las páginas del repositorio tienen la
misma forma; no se arregló en esta etapa.

**El seed de feriados es asimétrico**: 11 filas para 2026 y 9 para 2027, porque los trasladables
de 2027 dependen de un decreto todavía no publicado y sembrar una fecha no decretada cierra
centros el día equivocado. Falta además el **20 de noviembre de 2026** (Soberanía Nacional,
corrido al 23), que es un `INSERT`. El seed **envejece por diseño** y por eso no es la autoridad:
la sede siempre puede cargar la excepción a mano.

## 7. Deuda diferida

| Deuda | Destino |
|---|---|
| RN-M05-004: turnos futuros visibles al desvincular; `DisponibilidadImpactProbe` con implementación real | **F5** (`scheduling`, M12) — escenario 16 de `docs/tests-diferidos.md` |
| E2E y QA manual contra la base de las cuatro pantallas | Antes de considerar la etapa verificada en uso |
| Contraste de color: axe no lo puede medir bajo jsdom, en ningún test del repositorio | Sin herramienta asignada |
| Gate de `BRANCH` en JaCoCo, previa subida de cobertura | **Decisión del usuario** |
| Unificar `concurrent-modification` y `conflict` en todos los módulos | **Decisión de contrato transversal** |
| `securityScheme` en el OpenAPI | Transversal, preexistente |
| `enviar()` que falla abierto en nueve specs; pineo de rutas en las otras cuatro features | Higiene de frontend, replicable |
| Carrera de respuesta rancia en los selectores de profesional | Transversal a 02.02, 02.04 y 02.05 |
| Feriado 20/11/2026 y trasladables de 2027 | Mantenimiento anual del seed |
| Precisión intra-día de la vigencia del vínculo: hoy el día del alta se ofrece entero | Exige que el calculador intersecte el bloque con la vigencia; cambio de firma |
| Una membership `REVOCADA` sin `validUntil` vacía toda su historia, no solo su futuro | Exige que el SPI devuelva la vigencia del estado, no su valor actual |
| Soporte de plataforma no puede diagnosticar la disponibilidad de un centro | Decisión de contrato: las rutas de M05 no llevan `organizationId` y el rol de plataforma cae en el 403 uniforme |
| Horario general del consultorio (`RF-M03-002`, `CA-M03-002` parcial) | F5 |
| Registro de cierre de AKINE-02.02 | Hueco de documentación, sigue abierto |

## 8. Las dos decisiones que quedan al usuario

1. **¿Se agrega el gate de `BRANCH` al backend?** Hoy agregarlo rompe el build, así que es un
   tramo de cobertura antes que una línea de `pom.xml`. Mientras no esté, la rama puede seguir
   cayendo sin que nada avise, que es exactamente lo que ya pasó.
2. **¿Cómo se unifican `concurrent-modification` y `conflict`?** Las dos salidas son hacer que
   todos los módulos emitan el mismo tipo, o corregir las 13 ocurrencias del YAML para que digan
   `conflict`. La primera cambia respuestas ya publicadas; la segunda cambia contratos ya
   publicados. Ninguna se decide desde el advice de un módulo.

## 9. Contexto para la etapa siguiente

- **Disponibilidad ≠ turno.** La disponibilidad es *oferta*; el turno es *reserva*. F5 no debe
  fusionarlas, y el motor de slots de 05.01 va a intersecar **dos** disponibilidades distintas: la
  del profesional (M05, esta etapa) y la del espacio físico (M04, 02.02). Por eso los tipos de acá
  se llaman `BloqueDisponibilidad`, `DisponibilidadEfectiva` y `FranjaEfectiva`, y ninguna clase
  nueva se llama `Disponibilidad*` a secas.
- **La disponibilidad efectiva no se materializa y no se va a materializar.** Se calcula al leer,
  con un calculador puro sin base ni reloj. Quien necesite rendimiento cachea la salida; no la
  guarda como fuente de verdad.
- **El lock de escritura de disponibilidad es la fila de `consultorio_calendario` de la sede**, que
  se crea a demanda y por eso nunca se da de baja. Cualquier escritura futura sobre estas tablas
  tiene que tomarlo **antes** de leer nada: leer primero y bloquear después es una escalada S→X, o
  sea un deadlock y no una espera.
- **`resource.spi.DisponibilidadImpactProbe` es la costura hacia `scheduling`** y ya está
  cableada en los dos caminos que la necesitan. Implementarla no cambia el contrato.
- **Próximo paso: AKINE-02.06.**

---

# Registro de cierre — AKINE-02.06 (Servicio global y oferta por consultorio)

**Fecha:** 27/08/2026. **Rama:** `akine-01.02-identidad` en los dos repos.
**Commits backend:** `e1fce26` → `b578b15` (11). **Commits frontend:** `5d85e37` y `2f6836c`.

> **La etapa se ejecutó en dos tandas y este registro cubre las dos.** Los primeros ocho commits
> del backend —dominio, puertos, repositorios, `V24`, ADR-0023 y los dos servicios de
> aplicación— ya estaban hechos al empezar la sesión del 27/08. Lo que faltaba era todo lo que
> hace que ese código sea alcanzable: la capa REST, el contrato y el frontend.

## 1. Qué entregó

| Pieza | Dónde |
|---|---|
| Dominio, puertos y repositorios de `offering` | 33 clases en `com.akine.offering` |
| `servicio` y `oferta_servicio_consultorio` | `V24` |
| **Capa REST**: 2 controllers, 7 DTOs, `OfferingProblemHandler`, `OfferingApiActor` | `com.akine.offering.api` |
| **Contrato 0.12.0** — 89 operaciones, 8 más que 0.11.0, aditivo | `openapi/akine-api.yaml` |
| **Frontend** `features/offering/` — `CatalogoDeServiciosPage` y `OfertasDeLaSedePage` | `appKine-web` |
| Seed del catálogo clínico global — 6 especialidades, 16 prácticas | `V25` |
| Fix transversal del 406 en 7 operaciones sin cuerpo | `identity` y `organization` |

Los ocho endpoints son los que el diseño §5 fijó, sin desviaciones: `GET/POST/PUT/DELETE`
sobre `/api/v1/servicios` y sobre `/api/v1/consultorios/{cid}/ofertas`.

## 2. Lo que la etapa dejó fijado y las siguientes heredan

| Regla | Por qué |
|---|---|
| **La baja de un `servicio` global NO cascadea** | RF-M27-002 prohíbe el borrado físico con referencias y RN-M03-006 prohíbe afectar históricos. Las ofertas que ya lo prestaban siguen operando; lo único que se impide es crear ofertas nuevas, con 409 `servicio-inactivo` |
| **`/api/v1/servicios` está exceptuado de `TenantContextFilter`** | El catálogo global es idéntico para todos los centros y no hay fila que acotar. Exigir contexto dejaba afuera al rol de plataforma, que por definición no tiene sede elegida y es quien lo administra. **Las ofertas no pueden estar ahí**: su tenant sale de ese filtro |
| **Un `ApiActor` por módulo, con nombre propio** | `resource.api.ApiActor` y el de `offering` resuelven al mismo nombre de bean y Spring no arranca. Compartir el de `resource` ata `offering` a la capa de aplicación del vecino y ArchUnit lo rechaza |
| **El 409 de concurrencia es `conflict`, y el contrato lo dice así** | `offering` lanza el `OptimisticLockingFailureException` plano, que mapea el handler global. No se repite la inexactitud que arrastran los contratos de 02.02 y 02.05 |
| **Lo que se omite en el alta de una Oferta, hereda del Servicio** | Modalidad, caso clínico y registro clínico. Omitirlos no es apagarlos, y el DTO lo declara |

## 3. Verificación

**Contra el servidor real**, con MySQL 8.4 del compose y el frontend en el navegador:

- Alta de servicio global, 409 de código repetido, baja con motivo, 409 de baja repetida.
- Alta de oferta con herencia: omitiendo `modalidad` y `generaRegistroClinico`, la oferta salió
  con `INDIVIDUAL` y `true` tomados del servicio.
- 409 de nombre comercial repetido, insensible a mayúsculas y acentos.
- 409 de `expectedVersion` desactualizada, con `type` **`conflict`**.
- **La regla central**: dado de baja el servicio, la oferta existente quedó `estado=ACTIVO,
  vigenteHoy=true` y una oferta nueva sobre él recibió 409 `servicio-inactivo`.
- Las 7 operaciones del fix del 406, con el `Accept` exacto que manda el cliente generado:
  ninguna devuelve 406.
- `V25` aplicada: 6 especialidades y 16 prácticas en la base.

**Gates.** Backend `./mvnw verify` **BUILD SUCCESS**: 1291 unitarias, 135 de integración, 1
diferida, cobertura sobre el piso. Frontend: **602 tests en 70 archivos**, lint limpio, y los
cuatro pisos de cobertura verdes.

> **La cobertura de rama del frontend cayó a 79,02 % al agregar la feature y rompió el build.**
> Se recuperó a **80,04 %** con specs de las dos funciones puras de `offering` y dos casos de
> página. Es el primer gate del repositorio que efectivamente frenó una etapa, y conviene que
> quede dicho: el piso de rama es lo que le pone un límite duro a la política de "menos tests".

## 4. Lo que se descubrió verificando y no leyendo el código

Tres defaults que el DTO prometía y el servicio no daba. Los tres se corrigieron en el contrato
y en el formulario, y ninguno se ve leyendo el dominio:

1. **`capacidad` es obligatoria** y no se deriva de la modalidad. RF-M27-003 la pide explícita
   porque un box con dos camillas puede atender de a dos en individual.
2. **`precioBase` y `moneda` viajan juntos o ninguno.** No hay default de moneda, y no puede
   haberlo: un importe sin moneda no significa nada y asumir ARS convertiría en pesos el precio
   de un centro que cobra en otra cosa.
3. **`requiereProfesional` y `requiereEspacio` default a `false`**, no a `true`.

Y un defecto que sólo aparece en un navegador: la pantalla de ofertas mostraba `Servicio #1` —el
id pelado— cuando el servicio estaba dado de baja, porque pedía sólo los ACTIVOS. Es exactamente
el caso que la etapa existe para sostener. Corregido: resuelve nombres contra el catálogo
completo y ofrece sólo los activos.

## 5. Deuda que esta etapa deja abierta

- **Sin E2E y sin QA manual**, igual que 02.02 a 02.05. `appKine-api/CLAUDE.md` §6 lo declara
  bloqueante para deploy.
- **La consola de plataforma sigue sin existir**, y con ella la pantalla que resuelve las
  solicitudes de catálogo de 02.05. Su prerrequisito es un endpoint que le diga al frontend si
  quien mira tiene rol de plataforma; mientras no exista, la pantalla de servicios muestra las
  acciones y deja que el 403 del servidor sea la respuesta.
- **`esquema_cobro` se guarda y nadie lo resuelve** (RN-M27-006): M15/M16/M18 no existen.
- **Relación Oferta ↔ Práctica** (RF-M06-008) y **habilitación de profesionales y espacios por
  oferta** (RF-M03-006 paso 7): son de 02.07 y de la etapa de agenda.

## 6. Hallazgo que excede la etapa — el `PLATFORM_ADMIN` sembrado es inalcanzable

`V15` siembra `plataforma@akine.app` con `password_hash` NULL a propósito, y su cabecera afirma
que *"la única vía para tomar posesión es el flujo de recuperación de contraseña de 01.02"*.

**Eso no es cierto.** `PasswordResetService.solicitar` corta en `!cuenta.puedeAutenticarse()`,
que es `false` justamente para una cuenta sin credencial, y devuelve sin emitir token ni correo.
La respuesta sigue siendo 202 por ADR-0018, así que **falla en silencio**: no hay fila en
`notification_outbox` ni mensaje en Mailpit. El otro camino, `POST /api/v1/platform/roles`,
exige el rol que se quiere obtener.

En un despliegue nuevo, **ningún endpoint de `/api/v1/platform` es alcanzable jamás**, y tampoco
las mutaciones del catálogo global de servicios. Se destapó al querer verificar `createServicio`;
para esa verificación se otorgó la fila de `platform_role` por SQL y se revocó al terminar.

Arreglarlo es una decisión de diseño y **no se tocó**: permitir el reset para una cuenta sin
credencial toca ADR-0018 y el flujo de invitación, que tiene su propio camino de token. Las
alternativas son sembrar de otra forma o agregar un comando de bootstrap.

Dato relacionado que conviene saber antes de probar: **otorgar `PLATFORM_ADMIN` a una cuenta le
quita sus permisos de tenant.** Con el rol, la misma cuenta recibió 403 en espacios y en ofertas;
sin él, 200 en las dos. Es coherente con el modelo de support access, pero significa que no se
puede usar una sola cuenta para probar el catálogo global y las ofertas de una sede.

**Próximo paso: AKINE-02.07.**

---

# Registro de cierre — AKINE-02.07 (Habilitación de profesionales y espacios por oferta)

**Fecha de implementación:** 28/08/2026. **Registro escrito:** 31/08/2026. **Rama:** `akine-01.02-identidad`.
**Commits backend:** `41ce5f5`, `726541c`, `310da79` y `b8bbc67`. **Commit frontend:** `5f61241`.
**Diseño:** `appKine-api/docs/diseno/AKINE-02.07-habilitaciones.md`.

> **Este registro se escribió tres días después de que la etapa se commiteara, y eso importa.**
> 02.07 fue la única de las once que quedó sin acta al cerrar, igual que le pasó a 02.02. A
> diferencia de aquel, este sí lo escribió quien trabajó sobre la etapa —terminando su frontend y
> corrigiendo un defecto suyo— así que no es una reconstrucción a ciegas; pero la parte de la
> sesión original está reconstruida desde el código, los commits y el diseño, no desde la memoria
> de quien la escribió.

> **Se implementó en paralelo con 03.01, en el mismo árbol de trabajo.** Las dos migraciones
> nacieron como `V26`: Flyway rechaza versiones duplicadas y la aplicación no arranca. 03.01 tomó
> `V27` y esta terminó en `V28`, así que **`V26` queda vacía a propósito**. La cabecera de `V28` lo
> deja escrito. **Si dos etapas se escriben a la vez, van en worktrees separados.**

## 1. Qué entregó

| Pieza | Dónde |
|---|---|
| `oferta_profesional_habilitado` y `oferta_espacio_habilitado` | `V28` |
| `OfertaHabilitacionService` y `HabilitacionController` | `com.akine.offering` |
| 4 operaciones, aditivas al contrato **0.13.0** | `openapi/akine-api.yaml` |
| Pantalla de habilitaciones con su spec | `appKine-web`, `features/offering` |

Las cuatro operaciones: `getHabilitaciones`, `reemplazarProfesionalesHabilitados`,
`reemplazarEspaciosHabilitados` y `validarOferta`.

## 2. Lo que la etapa dejó fijado y las siguientes heredan

| Regla | Por qué |
|---|---|
| **Lista vacía significa TODOS, no ninguno** | Una oferta sin habilitaciones no está prohibida para nadie: está **sin restringir**. Es lo que hace que el alta de 02.06 —que a propósito no pide quince datos— produzca una oferta usable. Si la regla se invierte, toda oferta recién creada queda inutilizable y **el síntoma es silencioso**: la agenda no ofrece nada y nadie ve un error |
| **Habilitación no es permiso** | Nada de esto se consulta desde `PermissionGuard` ni otorga acceso. Un profesional habilitado sigue necesitando su membership; un `ORG_ADMIN` sin habilitación administra la oferta y no la presta |
| **Todo se calcula al leer** | La capacidad efectiva y la validez de cada habilitación salen de mirar las filas en el momento de la consulta. Materializarlas obligaría a recalcular cuando cambia un espacio o se desvincula un colaborador —filas de otros módulos— y la primera vez que alguien olvide hacerlo **la agenda sobrevende un box** |
| **Dos tablas y no una polimórfica** | Las columnas se parecen y el tipo de cosa no. Una tabla con `tipo_recurso` obligaría a una FK nullable por destino, haría imposible el unique que impide habilitar dos veces el mismo recurso, y convertiría cada consulta de la agenda en un filtro por discriminador |
| **Los reemplazos son de conjunto completo** | El servidor hace el diff. Endpoints de alta y baja de a uno obligarían al cliente a diffear, y un cliente que diffea mal **produce bajas que nadie pidió** |
| **Una habilitación cuyo recurso ya no sirve se muestra, no se esconde** | Un vínculo caído o un espacio fuera de servicio siguen apareciendo con su advertencia. Esconderlos deja al administrador sin entender por qué la capacidad efectiva cambió sola |

## 3. El defecto que esta etapa tuvo abierto tres días

**El control optimista no serializaba nada, y su javadoc afirmaba que sí.** Corregido en
`b8bbc67`, el 30/08/2026.

El servicio documentaba que `expectedVersion` impide que "el segundo en guardar borre en silencio
lo que agregó el primero". No lo hacía: **las habilitaciones viven en sus propias tablas y un
reemplazo no toca ninguna columna de `oferta`**, así que JPA nunca movía su `@Version`. Dos
administradores abrían la misma oferta, leían los dos la versión 0, los dos pasaban el control, y
el diff del segundo daba de baja lo que había agregado el primero — respondiendo 200.

La escritura pasa a cargar la oferta con `OPTIMISTIC_FORCE_INCREMENT`. El incremento ocurre al
cerrar la transacción y no al leer, así que la comparación sigue viendo la versión previa. Las
lecturas siguen con el método sin lock: mover la versión por consultar sería una escritura
disfrazada de lectura.

> **Por qué el test que había no lo agarró, que es la parte que se generaliza.**
> `el_reemplazo_respeta_el_control_optimista` pasaba una versión desactualizada **a mano** y
> verificaba que lanzara. Eso prueba que la **comparación** funciona; no prueba el escenario que su
> propio comentario describe, porque nunca simula dos guardados seguidos — y en el escenario real
> la versión del segundo administrador **no está desactualizada**. Es la misma familia que las
> trampas de concurrencia ya documentadas: el test miraba el código de respuesta, no el escenario.

> **La regla general para las etapas que vienen:** un `@Version` sobre el agregado padre **no
> protege nada si la escritura solo toca tablas hijas**. Cualquier configuración que cuelgue de un
> agregado y use la versión del padre como control optimista necesita el incremento forzado.

**Consecuencia en el frontend, ya aplicada.** Guardar ahora mueve la versión y
`HabilitacionesResponse` no la trae, así que la pantalla vuelve a pedir la oferta después de
guardar. Sin eso, guardar profesionales y después espacios daría un 409 que le echa la culpa a una
edición ajena que nunca existió. **El arreglo de fondo, pendiente y aditivo al contrato, es agregar
`ofertaVersion` a la respuesta.**

## 4. Verificación

| Qué | Estado |
|---|---|
| Backend, suite unitaria | **1632 en verde**, ArchUnit incluido |
| Frontend | **650 en verde** en 75 archivos; los cuatro pisos de cobertura sobre 80 % |
| Integración `V28` contra MySQL real | Corrió en su momento (`310da79`) |
| **Que la versión efectivamente avance** | **NO verificado.** Escenario diferido 20 |
| E2E de la pantalla | **No existe** |
| QA manual contra la base | **No corrió** |

> **El gate de cobertura de rama del frontend hizo su trabajo.** Agregar la pantalla lo bajó a
> 79,80 % y **rompió el build**; recuperarlo exigió seis casos y no dos. Es la diferencia práctica
> con el backend, que no gatea `BRANCH` y por eso dejó caer nueve puntos sin que nadie se enterara.

## 5. Lo que queda abierto

- **El escenario diferido 20**: que la versión avance de verdad exige MySQL por Testcontainers, y
  el motor de Docker de esta máquina no arranca sin elevación.
- **`ofertaVersion` en `HabilitacionesResponse`**, para que la pantalla no tenga que repedir la
  oferta después de cada guardado.
- **E2E y QA manual**, la misma deuda que arrastran 02.02 a 02.06.

# Registro de cierre — AKINE-03.01 (Persona, PerfilPaciente, búsqueda y deduplicación)

**Fecha:** 28/08/2026. **Rama:** `akine-01.02-identidad`.
**Commits backend:** `8667d63` → `f439583` (4). **Commits frontend:** `ec20d70` y `0a87c27`.
**Diseño:** `appKine-api/docs/diseno/AKINE-03.01-persona-y-perfil-paciente.md`.

> **La etapa está implementada de punta a punta y su QA manual corrió, y aun así NO se declara
> cerrada.** El circuito contra el stack real cubrió **cuatro de sus cinco escenarios** —§5— y
> quedó afuera la activación del perfil, por una limitación de la herramienta y no del código.
> Tampoco hay E2E: la suite sigue teniendo solo los cuatro de F1.

> **La etapa se ejecutó en paralelo con 02.07, en el mismo árbol de trabajo, y eso dejó marcas.**
> Las dos migraciones nacieron como `V26` y Flyway rechaza versiones duplicadas —la aplicación
> directamente no arranca—. Esta corrió a `V27`, 02.07 terminó en `V28`, y **`V26` queda vacía a
> propósito**: Flyway no exige versiones contiguas y renumerar algo ya aplicado es peor que un
> número sin usar. Hubo además dos builds de Maven compartiendo un `target/`, con una corrida que
> falló con 368 `ClassNotFoundException` sobre clases que existían en disco. No era código: era
> un `clean` ajeno en el medio.

## 1. Qué entregó

| Pieza | Dónde |
|---|---|
| Módulo `person` completo: dominio, aplicación, API, persistencia | 28 clases en `com.akine.person` |
| `persona` y `perfil_paciente` | `V27` |
| **Contrato 0.13.0** — 98 operaciones, 5 más de esta etapa, aditivo | `openapi/akine-api.yaml` |
| `paciente:manage` **cableado por primera vez** | `RolePermissions`, matriz §12 |
| 3 `type` nuevos | `persona-documento-taken`, `persona-posible-duplicado`, `persona-inactiva` |

Los cinco endpoints: `GET/POST /api/v1/personas`, `GET/PATCH /api/v1/personas/{id}` y
`POST /api/v1/personas/{id}/perfil-paciente`.

## 2. Lo que la etapa dejó fijado y las siguientes heredan

| Regla | Por qué |
|---|---|
| **Persona ≠ Paciente son dos filas, no un booleano** | No existe columna `es_paciente`. La única forma de crear un paciente es insertar en `perfil_paciente`, y eso vive en una sola clase. Es RF-M07-010 sostenido por la estructura: cuando F5 traiga turnos y F9 inscripciones, esos caminos van a poder crear personas **sin poder crear pacientes** |
| **Activar el perfil NO crea Historia Clínica** | La HC es M09, de un módulo que no existe. `perfil_paciente` no tiene `historia_clinica_id` y hay un IT que lo verifica contra el esquema real |
| **Persona es de la ORGANIZACIÓN, no de la sede** | DP-03. El caso borde "duplicado entre sedes" no se detecta: **no se puede crear**, lo impide el unique |
| **RN-M07-001 lo hace cumplir el backend, no la pantalla** | Un alta que coincide en nombre completo o teléfono se rechaza con 409 y la lista de candidatos; se confirma reenviando. Con el formulario como única defensa, la regla dura hasta el primer cliente nuevo |
| **El documento repetido es duro y el posible duplicado es advertencia** | Son dos capas distintas y confundirlas es el error a evitar. El primero sale del unique y no se puede confirmar; el segundo sí |
| **`paciente:manage` se evalúa CON la sede del contexto** | Con `consultorioId = null`, `alcanceCubre` deja afuera a `CONSULTORIO_ADMIN` y `ADMINISTRATIVO`: el recepcionista no podría dar de alta a nadie. Sin contexto de sede no se muta el padrón |
| **El padrón se pagina en la BASE** | A diferencia de espacios y ofertas, que recortan en memoria. Una sede tiene decenas de espacios; el padrón tiene decenas de miles de personas |
| **La auditoría del padrón dice QUÉ cambió, nunca a qué valor** | Los detalles se leen con `auditoria:read`, que no es `paciente:manage`. Una fila que reproduce el DNI se lo entrega a quien no debería verlo |

## 3. Verificación

**`./mvnw verify` BUILD SUCCESS**: 1630 unitarias y 142 de integración en verde, cobertura sobre
el piso, contrato sin drift.

Lo que el IT de migración prueba contra MySQL real y los unitarios no pueden:

- Dos personas vigentes con el mismo documento **no entran** en una organización, y **sí** entran
  en organizaciones distintas.
- **Varias personas SIN documento conviven sin chocar** — los `NULL` no colisionan en un unique, y
  es exactamente el comportamiento que el caso borde "persona sin DNI" necesita.
- Un documento liberado por una baja lógica **se puede reusar** (`deleted_key`).
- Una persona no puede tener dos perfiles vigentes, y un perfil dado de baja **sí** se reactiva.
- Los tres CHECK: documento indivisible, baja con motivo, tipo de documento como lista cerrada.

**No verificado:** QA manual contra el servidor real, y ningún E2E. Igual que 02.02–02.05.

## 4. El frontend

Una pantalla, `/pacientes`, con la feature `person`. Tres decisiones de interfaz que no son
cosméticas:

- **El alta no tiene ninguna casilla "es paciente"**, porque el contrato tampoco la ofrece. Y la
  columna de perfil dice **"Persona"**, no "Sin perfil" ni "Pendiente": alguien que viene a una
  clase es una ficha completa y correcta, y un "pendiente" empuja al operador a activarle un
  perfil clínico para "terminarla" — que es justo lo que RF-M07-010 existe para evitar.
- **Los dos 409 del alta se ven distintos.** El documento repetido **no ofrece confirmar** —sería
  un callejón: el operador lo apretaría y recibiría el mismo error— y en cambio resuelve y muestra
  la ficha dueña del documento. El posible duplicado trae las fichas candidatas con nombre,
  documento y teléfono, y ofrece las dos salidas reales.
- **El buscador está arriba del botón de alta**, no en una barra de acciones: el orden visual es
  el orden del procedimiento. Eso es UX; la regla la hace cumplir el backend.

**Verificado:** 24 unitarias de la pantalla y sus dos modelos, auditoría axe, y los cuatro pisos
de cobertura en verde — el de rama estuvo por debajo hasta que se cubrieron los dos modelos, y
quedó en 80,29 %. Inspección visual en Chromium real a 1280 y 900 de ancho, sin desborde de body
ni de tabla (`scripts/mirar-padron.mjs`, mismo criterio que `mirar-espacios`).

## 5. QA manual contra el stack real — cuatro de cinco escenarios

Ejecutado el 28/08/2026 desde la pantalla, con sesión real, contra Spring Boot + MySQL del
compose. La secuencia de peticiones quedó registrada en el panel de red:

| Escenario | Resultado |
|---|---|
| Listado inicial | `GET /personas` → **200**, con el botón de alta visible: `paciente:manage` llega por `/me/permissions` y el cableado del permiso funciona de punta a punta |
| Alta de persona | `POST` → **201**. La ficha quedó como **"Persona"**, no como paciente |
| Documento repetido | `POST` → **409**. Se cargó `27888999` contra una ficha guardada como `27.888.999`: **la normalización del documento funciona contra la base real**. El aviso salió sin botón de confirmar |
| Posible duplicado | `POST` → **409** con `candidatos`. La pantalla resolvió la ficha con `GET /personas/1` → **200** y la mostró con nombre y documento, no con un id |
| Confirmar el duplicado | `POST` → **201**. Quedaron las dos fichas homónimas, una con documento y otra sin — que es el caso legítimo que el diseño §7 punto 8 describe |

**El quinto —activar el perfil de paciente— no se pudo ejercer, y sí era un defecto del código.**

> **Corrección del 30/08/2026.** Este párrafo decía que el botón de confirmación no se podía
> accionar desde la herramienta de navegador, y que por lo tanto no había defecto. **Las dos
> afirmaciones eran falsas.** El botón no hacía nada porque `ConfirmacionConMotivo` validaba el
> motivo como obligatorio **siempre**, con `Validators.required` fijo en el control, mientras el
> panel del padrón rotula ese campo "Motivo (opcional)" y el contrato lo declara con
> `RequiredMode.NOT_REQUIRED`. Con el campo vacío, `confirmar()` cortaba antes de emitir: no salía
> ninguna petición, no había error en consola y RF-M07-008 era **inejecutable desde la pantalla**.
>
> Corregido con un input `motivoObligatorio` que por defecto vale `true`, así las cuatro pantallas
> de baja que usan el componente no cambian; el padrón pasa `false`. El validador se aplica en un
> `effect` y no al construir el `FormControl`, porque el valor de un signal input no está
> disponible en el inicializador del campo.
>
> **Por qué ningún test lo agarraba:** el spec del padrón verificaba solamente el *texto* del panel
> de activación y nunca enviaba el formulario; y ninguna de las cuatro pantallas de baja afirmaba
> que el motivo vacío **bloquea** el envío, así que tampoco había nada guardando el default en la
> dirección contraria. Ahora hay un spec propio del componente con los dos casos.
>
> Verificado en un navegador real contra el dev server con la API simulada
> (`scripts/mirar-padron.mjs`): con el campo vacío sale
> `POST /api/v1/personas/40/perfil-paciente` con cuerpo `{}` y la pantalla confirma. **Sigue sin
> verificarse contra MySQL**, porque el motor de Docker de esta máquina no arranca sin elevación.
> El escenario queda pendiente, ahora por el entorno y no por el código.

> **Dos personas sintéticas quedaron en la base local** —las dos "Quiroga, Marta Elena"—. No se
> pueden borrar desde la interfaz: la baja lógica es RF-M07-005 y llega en 03.02.

## 6. Lo que queda abierto
- **`personaExistenteId` viaja casi siempre en `null`** en el 409 de documento repetido. Después
  de un flush fallido no se puede volver a consultar la sesión JPA —sale un 500 en vez del 409—,
  así que averiguar quién tiene ese documento exige otra transacción. La propiedad se publica
  igual: el día que se resuelva, el contrato no cambia.
- **No existe `paciente:read`.** Las lecturas se autorizan por pertenencia, lo que concede de más:
  una membership con rol `PACIENTE` lee el padrón entero. Aprobar el permiso **no lo resolvería**
  —el problema es el alcance `OWN`, que no está implementado en ninguna parte, porque no hay
  vínculo entre cuenta y persona—. Detalle en la matriz §12.3.
- **Baja lógica y fusión de fichas** (RF-M07-005): 03.02. Las columnas ya existen.
- **Búsqueda por número de afiliado** (RF-M07-001): el afiliado es dato de la cobertura, 03.04.
- **El caso que el diseño no puede resolver:** dos altas simultáneas de la misma persona **sin
  documento** y con el mismo nombre entran las dos. Ninguna protección aplica —sin documento no
  hay clave, y las dos transacciones ven el padrón sin la otra—. Es correcto: dos hermanos
  homónimos sin DNI son dos personas legítimas. La corrección es administrativa y su herramienta
  es de 03.02.

**Próximo paso: el QA manual de 03.01 contra la base, o AKINE-03.02 (Paciente 360, baja lógica
y adjuntos administrativos), que es la que trae la fusión de fichas que este diseño difiere.**

# Registro de cierre — AKINE-05.01 (Motor de disponibilidad y slots explicables)

**Fecha:** 31/08/2026. **Rama:** `akine-01.02-identidad`. **Commit:** `ed32dba`. Primera etapa del
**Paquete B** de DP-10 y primera del circuito operativo: hasta acá todo lo construido era configuración.

- **Módulo `scheduling`, sin ninguna tabla.** Un slot se calcula al leer y se descarta — misma regla
  que la disponibilidad efectiva de 02.04. Persistirlo lo haría envejecer y la agenda ofrecería
  huecos que ya no existen sin que nada falle. **`V29` queda reservada y sin usar.**
- **La grilla ancla en el inicio de cada franja**, no en una grilla global. Con grilla global, una
  apertura excepcional a las 09:20 perdería sus primeros 25 minutos y quien la cargó no podría
  entender por qué. Consecuencia aceptada: mañana y tarde no comparten grilla, y es correcto.
- **El motor no clava un espacio.** Verifica que haya al menos uno habilitado, en servicio y vigente
  ese día; elegir cuál es de 05.02, bajo el lock que crea el turno. Hacerlo acá sería una promesa
  que dos búsquedas concurrentes rompen.
- **Ventana máxima de 62 días**, contra los 366 de M05: la disponibilidad se pide una vez por
  profesional habilitado y una oferta de 30 min con cinco profesionales da 80 slots diarios.
- **Las tres vigencias —oferta, habilitación y vínculo— se evalúan día por día**, extendiendo el
  ruling R13 de 02.04. **`MotivoSinSlots` tiene nueve razones y un día vacío nunca viaja sin una.**
- **Costuras nuevas:** `scheduling.spi.ReservaProbe` (devuelve vacío hasta 05.02),
  `offering.spi.OfertaDirectory` (`offering` no tenía spi) y `resource.spi.DisponibilidadDirectory`.
  `DisponibilidadEfectivaService` se partió: `efectiva()` autoriza, `sinAutorizar()` calcula — no se
  pudo reusar porque exige `colaborador:read`, que es de quien administra personal.
- **`TramoLocal` propio y no `IntervaloLocal` de M05.** ArchUnit lo rechazó con razón: publicarlo por
  el spi ataría todo módulo que corte horas a la representación que M05 eligió para *componer reglas*.
- **Permisos:** `turno:read` con asignación base y `turno:manage` declarado sin asignación (la activa
  05.02). **La matriz §32 no tiene fila de turnos**, así que el alcance sale de las filas vecinas y
  queda como **enmienda §13 pendiente de aprobación**.
- **Contrato `0.14.0`, 99 operaciones, sin drift.** El `produces` declara `application/problem+json`
  además de `application/json`: declarar sólo el segundo es lo que causó los siete 406 de `be14ba5`.
- **`./mvnw verify` VERDE** — 1650 unitarias + 154 de integración contra MySQL 8.4 real, 1 diferida.
- **El test de medianoche encontró un defecto real antes de llegar a ninguna pantalla:**
  `LocalTime.plus` da la vuelta al reloj en silencio, así que el último slot de toda franja que cierra
  a las 24:00 salía invertido. Corregido en `avanzar()`.
- **Sin frontend y sin E2E.** La pantalla de agenda va con 05.02, que es la que puede reservar.
# Registro de cierre — AKINE-04.01 · Historia Clínica reducida (M09)

Carril paralelo, worktree `.worktrees/api-04.01`, rama `akine-04.01-historia-clinica`.
Alcance recortado por DP-10: **dominio + `V32` + puertos + servicios**. Sin REST y sin OpenAPI
(la superficie HTTP la publica el carril principal con 06.01). Sin timeline (04.02) ni Caso (04.03).

- **Módulo `clinical`.** `V32` crea `historia_clinica` y `historia_clinica_antecedente`. La HC es
  de la **organización** (DP-03): lleva `organization_id` y **no** `consultorio_id`; la sede del
  acceso vive en `audit_event`. `uk_historia_clinica_persona_vigente (organization_id, persona_id,
  deleted_key)` es "una HC por paciente y organización" **y** la idempotencia de la apertura.
- **Recableado DP-10 aplicado:** la HC cuelga de la `Persona` con `PerfilPaciente` vigente, leído
  por el nuevo `person.spi.PacienteDirectory`. La precondición se verifica también en el camino
  del spi, no solo en el humano.
- **Antecedentes como filas, no como columnas de texto** (RN-M09-004): se registran y se dan de
  baja con motivo; no se editan. Corregir uno es darlo de baja y registrar el nuevo.
- **Política de acceso (`AutorizacionClinica`)**: contexto + permiso + **relación asistencial o
  justificación**. `RelacionAsistencialProbe` no tiene implementación real hasta que haya turnos y
  sesiones, así que **hoy todo acceso clínico exige justificación declarada**. Toda lectura se
  audita, no solo las mutaciones.
- **Costuras diferidas representadas, no comentadas:** `clinical.spi.EventoClinicoContributor`
  (timeline, `List<>` vacía con consumidor real), `RelacionAsistencialProbe` (bean por defecto en
  `RelacionAsistencialSinAgenda`), `HistoriaClinicaDirectory.asegurar` (lo que 06.01 va a usar) y
  `HistoriaClinicaView.soloMetadatos()` (el "Limitado" del `ADMINISTRATIVO`).
- **Matriz de permisos, enmienda:** `hc:read`/`hc:write` pasan a **base `CONSULTORIO` para
  `PROFESIONAL`**, `hc:read` a `RESTRINGIDO` para `PLATFORM_ADMIN` (el evaluador lo deniega
  siempre) y los dos entran en `otorgablesComoGrant()`. **`ADMINISTRATIVO` NO recibe `hc:read`**:
  su celda es "Limitado — nunca contenido clínico" y no hay código de permiso que exprese ese
  recorte. Fail-closed hasta que exista.
- **`./mvnw verify` VERDE:** 1660 unitarias + 165 de integración (1 diferida), 0 fallos. La etapa
  suma 25 unitarias (`clinical`) y 11 de integración (`HistoriaClinicaMigrationIT`, contra MySQL
  8.4 real). Cobertura 84,74 % instrucción · 85,80 % línea · 74,22 % rama (sin gate de rama,
  preexistente). `OpenApiContractIT` en verde: **cero drift, el contrato no se tocó.**
- **Pendiente para 06.01 / carril principal:** publicar la HC por REST (controllers, DTOs, advice
  propio, contrato), decidir cómo se otorga la lectura limitada del `ADMINISTRATIVO`, e
  implementar `RelacionAsistencialProbe` sobre turnos/sesiones — sin eso la fricción de la
  justificación obligatoria es permanente.

# Tokens del design system — appKine-web

**66 tokens** en `:root` de `src/design-system.css`: 33 de color/sombra, 15 de tipografía, 9 de espaciado, 4 de radio, 5 de borde/foco. Nombres por rol, no por valor. Modo oscuro por `prefers-color-scheme` redefiniendo **solo tokens**: ninguna regla de componente se repite.
Grep: **0 literales `#rrggbb` fuera de `:root`** en todo `src/` (61 ocurrencias, todas en la capa de tokens). Los 66 tokens se usan y ningún `var()` queda sin definición.
Suite: **650/650 en 75 archivos**; cobertura 87,48 st · 80,17 rama · 85,90 fn · 88,59 ln — los cuatro gates verdes, sin movimiento respecto del baseline.

## Contraste WCAG 2.1, calculado a mano — primera verificación del repositorio (axe da siempre `incomplete` bajo jsdom, que no calcula layout)

| Par de tokens (rol)                             | Claro | Oscuro | Mín |
| ----------------------------------------------- | ----- | ------ | --- |
| `texto` / `superficie`                          | 15,32 | 14,86  | 4,5 |
| `texto-suave` / `superficie`                    | 7,09  | 8,18   | 4,5 |
| `accion-texto` / `accion-fondo` (botón)         | 15,32 | 6,33   | 4,5 |
| `accion-texto` / `deshabilitado`                | 4,63  | 5,00   | 4,5 |
| `enlace` / `superficie`                         | 15,32 | 8,18   | 4,5 |
| `marca-texto-suave` / `marca-fondo` (cabecera)  | 10,55 | 9,68   | 4,5 |
| `peligro` / `superficie` (`.estado--error`)     | 7,54  | 6,43   | 4,5 |
| `exito-texto` / `exito-fondo` (marca activa)    | 8,57  | 9,70   | 4,5 |
| `aviso-texto` / `aviso-fondo` (suspendida)      | 7,75  | 10,03  | 4,5 |
| `peligro-texto` / `peligro-fondo` (revocada)    | 9,22  | 10,17  | 4,5 |
| `borde-control` / `superficie` (borde de input) | 3,54  | 5,64   | 3   |
| `foco` / `superficie` (anillo de foco)          | 11,63 | 8,18   | 3   |

## Colores ajustados

- **Un solo ajuste por contraste:** `--color-deshabilitado` en **oscuro** es `#778593`, no el `#6a7684` del claro, que daba **2,84:1** contra la superficie y **3,06:1** contra el texto del botón; ahora 4,65 y 5,00. En modo claro no se ajustó ningún color: los 37 pares medidos ya pasaban.
- **25 consolidaciones** de valores casi idénticos que existían por deriva entre hojas, cada una re-verificada y sobre el mínimo: bordes `#d5dde5`/`#d8dee5`/`#d6dee6`→`#d5dee7` y `#e6ebf0`→`#e3e9ef`; deshabilitado `#6b7a8a`→`#6a7684`; éxito `#1c6b3a`/`#1f6f43`→`#1d6b3f`, fondo `#eef7f1`→`#eaf6ee`, texto `#14532b`→`#135029`; aviso `#8a5a00`/`#8a5300`→`#7a4b00`, fondos `#fdf3e2`/`#fdf8f0`→`#fdf5e6`, texto `#6b4000`→`#6b4600`; peligro `#9b2226`→`#a32020`, fondo `#fdf3f3`→`#fdeded`; texto suave `#4a5568`→`#4a5a6a`; atenuada `#fafbfc`→`#f7f8fa`; y las dos pilas monoespaciadas en un solo `--fuente-mono`.
- `color-scheme: light` pasó a `light dark` en `styles.css`: sin eso los controles nativos siguen blancos sobre la página oscura.
- **El resto del modo claro es idéntico y está probado**, no afirmado: se resolvió cada `var()` contra los valores de `:root` y se comparó declaración por declaración contra `HEAD`; las 26 diferencias son exactamente las de los dos puntos anteriores.
- Ningún `.ts` ni `.html` tocado, y ninguno hizo falta —el modo oscuro es automático; un conmutador manual sí exigiría template—. Sin verificación en navegador: esto mide la paleta, no píxeles renderizados.


# Registro de cierre — AKINE-05.02 (Reserva y confirmación de Turno)

**31/08/2026** · rama `akine-01.02-identidad` · commits `425b4ec` y `4e3d666`. `V30` (`turno`, `agenda_sede`), dos endpoints, contrato `0.15.0`.
**Este registro se escribió el 01/09/2026, después de 06.01–07.01: la etapa se commiteó sin él y su ausencia no se notó hasta que se auditaron los registros. Está reconstruido desde los mensajes de commit y el código, no es un acta de sesión.**

- **Ningún unique de la base puede hacer cumplir "una sola reserva gana", y conviene tenerlo escrito donde alguien lo busque:** un unique compara igualdad y dos turnos se pisan cuando sus **intervalos** se cruzan. 09:00–10:00 y 09:30–10:00 no comparten un solo valor de columna, y el caso es real porque dos ofertas de duración distinta producen slots fuera de la misma grilla. MySQL 8.4 no tiene exclusion constraints. Lo que hace cumplir la regla es la validación bajo el lock de `agenda_sede`, una fila por sede que no guarda nada y existe sólo para ser tomada con `FOR UPDATE`.
- **`TurnoConcurrenteIT` destapó tres defectos que ningún test unitario podía ver, los tres corregidos.** (1) Crear la fila de `agenda_sede` perezosamente dentro de la transacción de la reserva produce **deadlock**, no una violación de unique que alguien pierda limpiamente. (2) Envolverlo en `try/catch` no alcanza: atrapar una excepción de persistencia no des-marca la transacción y el llamador recibe `UnexpectedRollbackException`; la excepción hay que **evitarla** con `INSERT ... ON DUPLICATE KEY UPDATE`. (3) **Con `REPEATABLE READ` el lock no alcanza**: InnoDB fija la foto en la primera lectura consistente, que ocurre *antes* del lock, así que la segunda reserva no ve el turno recién commiteado y las dos entran con 201. La transacción pasa a `READ_COMMITTED`.
- **El mismo patrón estaba latente en `BloqueoDeSede` de 02.04 y se cerró en `4e3d666`**, que además corrige un javadoc falso: llamaba al agujero "una ventana angosta" cuyo "remedio es un reintento del cliente", y un 500 no es un remedio. **Su segundo defecto NO existía en M05 y se comprobó en vez de leerse**: las cinco mutaciones de 02.04 ya declaraban `READ_COMMITTED` desde `01bdc99`.
- **El turno congela su propio intervalo y clava el espacio dentro de la transacción que lo crea.** Editar la duración de una oferta no puede mover un turno tomado, y elegir el box en la búsqueda de 05.01 sería una promesa que dos búsquedas concurrentes rompen.
- **Idempotencia con hash del pedido desde el primer día**, que cierra para este endpoint el escenario diferido 7b de 01.01: reusar la clave con otro contenido es 409 explícito, no un replay silencioso del turno anterior.
- **Cuatro tipos de conflicto y no un `conflict` genérico**, porque llevan a la pantalla a acciones distintas: `slot-no-disponible` recarga, `slot-completo` ofrece el siguiente, `recurso-ocupado` cambia de horario y `persona-sin-perfil-paciente` activa el perfil. El turno cuelga de `persona` y exige perfil vigente que **este camino no crea** (RF-M07-010).
- **Fuera de alcance, declarado:** sin cancelación ni reprogramación —son de 05.03, cortada, así que **un turno reservado hoy no se puede deshacer desde ninguna pantalla**— y sin notificación.
- **Deudas:** **sin QA manual contra la base** del §6. Los E2E **sí existen**, pero llegaron después y del lado del frontend: `e2e/agenda-buscador.spec.ts` y `e2e/agenda-reserva.spec.ts` (11 tests, `e306c4f`), sobre un harness que sintetiza la respuesta HTTP — **no prueban que el backend emita esos `problemType`**; eso lo fijan sus tests de integración.
# Registro de cierre — AKINE-06.01 (Agregado Sesión, inicio y autosave)

**31/08/2026** · rama `akine-01.02-identidad` · commit `8684f39`. Módulo nuevo `encounter`, `V33` y contrato `0.16.0` con tres endpoints.

- **Recableado DP-10: tres dependencias cortadas.** El plan escribe la etapa contra check-in (05.04), consumo de autorizaciones (04.05) y Caso (04.03). La Sesión cuelga de la **Historia Clínica** de 04.01 y arranca directo desde el Turno; `caso_id` se agrega nullable cuando 04.03 llegue. `turno_id` es opcional igual: RF-M14-002 admite atención sin turno.
- **Sesión no es Turno** (DP-05). La sesión guarda **su propio** profesional y su propia oferta en vez de leerlos del turno: ninguna transición de turno prueba por sí sola que la prestación ocurrió.
- **El doble inicio es idempotente y no es una concesión.** RN-M14-001 es una igualdad, así que `uk_sesion_turno` sí puede sostenerla del lado del motor —a diferencia del solapamiento de turnos de `V30`—.
- **La propiedad no es un permiso.** Escribir en la atención ajena es **409 y no 403**: los dos profesionales tienen `sesion:register`, lo que falta es la sesión. Un 403 lo mandaría a pedir un permiso que ya tiene.
- **El control optimista ES el autosave.** Dos pestañas del mismo profesional son el caso normal; sin versión, la segunda pisa a la primera en silencio.
- **El borrador es JSON opaco a propósito.** Qué campos tiene una evaluación lo decide 06.02; darle esquema hoy fijaría en la base un formulario no decidido, y cambiarlo después cuesta una migración sobre datos clínicos.
- **`sesion:register` con asignación base para `PROFESIONAL`, alcance de sede** (matriz §2). El test que fijaba "ningún rol lo tiene antes de que exista su módulo" hizo lo que prometía y se puso rojo: se reemplazó por la aserción real.
- **Fuera de alcance, declarado:** no cierra la sesión (06.05), no modela la evaluación (06.02) y **no publica la Historia Clínica por REST** — lo que 04.01 le dejó pendiente a esta etapa **sigue pendiente**, y con él la fricción de la justificación obligatoria.
- **Deudas:** **sin QA manual contra la base** (§6 de `appKine-api/CLAUDE.md` lo declara bloqueante para deploy) y **sin E2E propio** — los dos únicos E2E nuevos del repositorio web cubren la vertical de turnos, no la de sesión. El backend **sigue sin gate de `BRANCH`**.

# Registro de cierre — AKINE-06.02 (Evaluación base y modo Sesión rápida)

**31/08/2026** · rama `akine-01.02-identidad` · commit `ac46230`. `V34` sobre `sesion`, un endpoint y contrato `0.17.0`.

- **El JSON opaco de 06.01 pasa a columnas, y el requisito lo obliga:** "campos/estructura que permitan consultar dolor, función y cambio". Un JSON no se consulta ni se indexa. `borrador` **sobrevive** como bloc de notas del formulario en curso: el examen completo y las mediciones son 06.03, que el Paquete B dejó afuera.
- **Todo lo clínico es nullable, y es regla de negocio y no comodidad.** "Seguimiento no exige examen completo": un `NOT NULL` obligaría al profesional a inventar datos clínicos para poder guardar.
- **Por eso se valida lo que sería FALSO, no lo que falta.** Dolor fuera de 0..10 —en la aplicación *y* con `CHECK`, que es el que impide la fila corrupta si alguien escribe por fuera de JPA— y lateralidad sin zona. La implicación va en un solo sentido: zona sin lateralidad **sí** es legítima. Los dos son **400 y no 409**: son del cuerpo enviado, y reintentar no los arregla.
- **`NO_APLICA` no es lo mismo que vacío.** Sin ese valor la pantalla no puede saber si volver a preguntar.
- **El modo no condiciona nada.** `RAPIDA`/`COMPLETA` es una decisión de la pantalla sobre cuánto mostrar; si exigiera campos, cambiar de modo en medio de una atención recargaría el formulario.
- **`motivo_clinico` es texto libre y no una FK al nomenclador**, a propósito: el motivo lo escribe el kinesiólogo, el diagnóstico es un acto médico que este sistema no registra, y una FK los confundiría en el esquema.
- **La evaluación anterior viaja CON la sesión**, no en un endpoint aparte: mostrar "la vez pasada tenía 7" al lado del campo es lo que hace que se cargue evolución real. El índice `ix_sesion_comparacion` la sostiene.
- **Trampa que costó un ciclo de `verify` y quedó escrita en la migración:** columna `TINYINT` contra mapeo `Integer`. Con `ddl-auto: validate` Hibernate rechaza el tipo y el contexto no arranca — el síntoma son **169 tests de integración fallando a la vez sin que ninguno mencione la columna**.
- **Deudas:** **sin QA manual contra la base** y **sin E2E**. La pantalla que consume esta etapa existe en `appKine-web` (`b97bf3b`) y tampoco los tiene.

# Registro de cierre — AKINE-06.05 (Resultado, próxima conducta y cierre idempotente)

**01/09/2026** · rama `akine-01.02-identidad` · commit `2e5c121`. `V35`, un endpoint y contrato `0.18.0`. La sesión abierta pasa a ser prestación hecha, que es lo que 07.01 lee.

- **Recableado DP-10: el correlativo cuelga de la Historia Clínica, no del Caso** (04.03, cortada). Es "la sesión número 8 de este paciente", que además es lo que un kinesiólogo cuenta en voz alta. Cuando 04.03 llegue, el número por Caso se agrega **al lado**: renumerar sesiones cerradas es reescribir historia clínica, y ADR-0011 lo prohíbe.
- **`sesion_numerador` no es un cache de `COUNT(*)`.** Un `SELECT MAX+1` deja una ventana y dos cierres del mismo paciente se llevan el mismo número; el `UPDATE ... SET ultimo_numero = ultimo_numero + 1` toma lock exclusivo de fila sin leer nada antes. El unique es respaldo, no mecanismo.
- **La fila del numerador se crea con `INSERT ... ON DUPLICATE KEY UPDATE` en transacción aparte.** Es la lección de 05.02, ya pagada dos veces: la creación perezosa dentro de la transacción produce deadlock, y el `try/catch` no alcanza porque atrapar una excepción de persistencia no des-marca la transacción.
- **La idempotencia se evalúa ANTES de pedir número.** Al revés, cada reintento consumiría un correlativo y la numeración quedaría con huecos que parecen sesiones borradas.
- **Acá SÍ hay mínimos, al revés que en 06.02, y por una razón:** el cierre declara que la prestación ocurrió y de él se deriva un cobro. Son dos —asistencia, y nota de cierre si el paciente vino—. **`AUSENTE` es un cierre legítimo** y con él nada más es obligatorio: pedir resultado de una atención que no ocurrió sería pedir que se invente.
- **Editar una sesión cerrada es 409.** Corregirla es una enmienda con actor y motivo (06.06, cortada); hasta que exista, **es preferible no poder corregir a corregir sin dejar rastro**.
- **Cerrar no cobra** (DP-06): la tabla no tiene una sola columna económica. Atarlas haría que un problema de facturación bloquee una historia clínica.
- **Lo que sí se verificó contra MySQL real:** `CierreConcurrenteIT` prueba que dos cierres simultáneos del mismo paciente reciben 1 y 2 —sin repetir y sin huecos— y que cerrar dos veces no avanza el numerador.
- **Deudas:** **sin frontend** —la pantalla de atención no tiene botón de cerrar—, **sin E2E** y **sin QA manual** del §6.

# Registro de cierre — AKINE-07.01 (Obligaciones económicas)

**01/09/2026** · rama `akine-01.02-identidad` · commit `c9a3b68`. Módulo nuevo `billing`, `V36` y contrato `0.19.0` con tres endpoints —listado, detalle y anulación—. Última pieza antes del cobro.

- **Recableado DP-10: un solo responsable posible.** 03.05 (convenios y aranceles) y 04.05 quedaron afuera y el Paquete B fija cobertura PARTICULAR, así que el importe sale del `precio_base` de la Oferta. `responsable` existe igual con `FINANCIADOR` entre sus valores y los snapshots de convenio quedan **reservados en NULL**: cortar alcance no es cortar modelo, y la obligación mixta futura son dos filas que el unique de `V36` ya admite.
- **Deuda, cobro y caja son tres cosas.** Es la regla maestra M18/M19/M20 y el error del UML de 2019, donde Turno, Atención y Cobro eran lo mismo. Esta tabla no tiene medio de pago, ni comprobante, ni movimiento.
- **Decimal exacto de punta a punta:** `DECIMAL(12,2)`, `BigDecimal`, y ni un número de punto flotante en la API. Un `double` de 0.1 + 0.2 no da 0.3, y sobre una cuenta corriente eso son centavos que nadie puede explicar seis meses después.
- **La deuda se deriva DENTRO de la transacción del cierre**, por `encounter.spi.CierreDeSesionObserver` que implementa `billing.infrastructure.ObligacionDevengador`: la dirección es `billing → encounter` y el módulo clínico no sabe que existe facturación. **La contrapartida está asumida y escrita: un observador que falla hace fallar el cierre clínico.** La alternativa —derivar después— tiene una falla peor: una prestación sin deuda no se nota, porque nadie reclama una factura que nunca existió. No contradice DP-06, que separa el cierre del **pago** y no de la deuda.
- **Tres reglas decide el devengador.** Sin asistencia no hay deuda —cobrar un no-show es una política de centro que no existe, y aplicarla por defecto sería decidirla por el usuario—; sin `precio_base` tampoco, y eso **se loguea sin hacer fallar el cierre**, porque dejar al profesional sin terminar su atención por un dato administrativo ajeno sería peor; y el devengo es idempotente porque el cierre lo es.
- **El snapshot se congela y el saldo se materializa.** Editar el precio mañana no puede cambiar lo que se debe por una sesión de hoy. Calcular el saldo al leer obligaría a 07.02 a sumar cobros dentro del lock, y dos imputaciones concurrentes lo dejarían en negativo; con la columna, imputar es un `UPDATE` condicional y el `CHECK` impide el estado imposible.
- **No hay endpoint para crear una obligación a mano** —una deuda sin prestación que la respalde es un cargo injustificable—; anular exige motivo, y una obligación con cobros imputados **no se anula**: eso es una devolución y es M19.
- **`offering.spi.PrecioDeOferta` va separado de `OfertaSnapshot`**, que excluye lo económico a propósito: si el precio viajara ahí, cualquier módulo que pida una oferta para saber su duración se llevaría el dato económico de arrastre.
- **`cobro:register` con asignación base para `ORG_ADMIN`, `CONSULTORIO_ADMIN` y `ADMINISTRATIVO`.** El `PROFESIONAL` no lo recibe (matriz §2, "No por defecto") y el `PLATFORM_ADMIN` tampoco: soporte mira, no opera.
- **Deudas:** **sin frontend, sin E2E y sin QA manual** del §6 — la deuda existe y se puede leer por API, pero nadie la ve desde una pantalla. La cadena entre módulos sí se verificó contra MySQL real en `CierreConcurrenteIT` —cerrar devenga con precio congelado, cerrar dos veces no devenga dos, y una sesión ausente no devenga ninguna—.


# Registro de cierre — AKINE-07.02 (Cobros, medios, imputaciones y comprobantes)

> **Este registro es una RECONSTRUCCIÓN, no un acta.** La etapa se commiteó el **01/09/2026** en
> los dos repos y **nadie escribió su registro**; esto se redactó el **19/09/2026** por alguien que
> no estuvo en esa sesión. Es la quinta vez que pasa —02.02, 02.07, 05.02 y tres del Paquete B—.
> La evidencia usada, toda en disco: el mensaje y el diff del commit backend `8297c9b`, el del
> frontend `b9eb9fa` y su merge `2c2070b`, la cabecera de `V37__m19_cobro.sql`, el módulo
> `com.akine.billing` (`Cobro`, `CobroMedio`, `CobroImputacion`, `ComprobanteNumerador`,
> `CobroService`, `CobroController`, `BillingProblemHandler`), `CobroConcurrenteIT`, las tres
> operaciones de `Cobros` en `openapi/akine-api.yaml`, y §"Etapa AKINE-07.02" de este plan.
> **Los números de tests y de cobertura que aparecen abajo están citados de los mensajes de commit
> y NO se remidieron en esta reconstrucción** — no se corrió Maven ni npm para escribir esto.

**01/09/2026** · rama `akine-01.02-identidad` · backend `8297c9b`, frontend `b9eb9fa` mergeado en
`2c2070b`. `V37` con cuatro tablas, contrato **0.20.0** y tres operaciones —`registrar`,
`cobrosDeLaPersona` y `verCobro`—. Cierra el Paquete B en el backend.

- **Recableado DP-10: anticipos y anulación con reintegro quedan afuera enteros, no a medias.**
  DP-06 exige que un anticipo se registre con **movimiento real de caja**, y la Caja es 07.03, que
  el Paquete B no incluye. Un anticipo sin caja es plata que entró y que ningún arqueo encuentra;
  un reintegro saca dinero de una caja que no existe. Media funcionalidad miente más que la falta.
- **El saldo se descuenta con un UPDATE condicional y no con un lock:**
  `SET saldo = saldo - :importe WHERE saldo >= :importe`. Es atómico, **no lee antes** —ahí es
  donde se cuela la ventana— y no puede dejar el saldo negativo aunque dos cobros lleguen juntos.
  Cero filas afectadas es un **409**, no un error técnico. Es deliberadamente distinto del lock
  pesimista de 05.02: allá había que impedir el solapamiento de intervalos, que ningún predicado
  de igualdad expresa; acá hay que garantizar una resta que no baje de cero, y eso lo hace el motor
  en una sentencia.
- **Tres tablas y no una.** El cobro es el hecho, los medios son **cómo** entró la plata y las
  imputaciones **contra qué** se aplica. Aplanarlas obligaría a una fila por combinación —dos
  deudas con efectivo y tarjeta serían cuatro filas— y ninguna de las dos sumas se podría verificar.
- **Las dos sumas son invariantes de la aplicación, no del esquema**, y la migración lo deja
  escrito para quien venga a buscar "el CHECK que falta": MySQL no admite subconsultas en un
  `CHECK`. Sin la primera, un cobro de 8500 con un medio de 850 —un cero de menos— entra igual, la
  deuda queda saldada y en la caja falta plata que nadie puede explicar. La comparación usa
  `compareTo` y **no** `equals`: `BigDecimal.equals` distingue `8500` de `8500.00`, y con eso un
  cobro correcto sería rechazado según cómo el cliente escribió el número.
- **El comprobante es correlativo por sede y se asigna una sola vez**, con el mismo numerador que
  las sesiones de 06.05 y por la misma razón. **La idempotencia se evalúa ANTES de tocarlo:** una
  numeración fiscal con huecos es peor que un cobro repetido, porque nadie la puede explicar
  después.
- **El control que hay que vigilar es que la deuda sea de quien paga.** Sin él basta un id
  equivocado en el cuerpo para saldar la deuda de otro paciente, y las dos cuentas corrientes
  quedan mal **sin que nada falle**: una con plata que no pagó y la otra con deuda que sí pagó.
- **`cobro_medio` y `cobro_imputacion` llevan `organization_id` aunque sean tablas hijas.** ArchUnit
  lo exigió y tiene razón: ADR-0004 dice "sin excepción" y se podría llegar al tenant por
  `cobro_id` — precisamente por eso, una consulta que se olvide del JOIN cruzaría tenants sin
  fallar. Misma convención que `oferta_profesional_habilitado`.
- **El frontend representa la plata como enteros de centavos y parsea DESDE EL TEXTO**, sin
  `parseFloat`. El servidor rechaza con 400 `cobro-no-cuadra` una diferencia de un centavo, y
  verificarlo antes de mandar obliga a sumar: con `number`, un cobro de $0,30 pagado con $0,10 y
  $0,20 quedaría bloqueado sin razón visible. El decimal aparece una sola vez por importe, al armar
  el cuerpo.
- **El saldo insuficiente se muestra como la garantía funcionando, no como error inesperado.** La
  pantalla no deja imputar más que el saldo leído, y el 409 `saldo-insuficiente` se presenta con la
  única salida útil —recargar los saldos—; rotularlo "error inesperado" haría que el administrativo
  reintente lo mismo, que va a fallar igual.
- **Tres pantallas y no pestañas de una** (deuda, cobro, caja siguen siendo tres cosas). Fusionarlas
  es como se termina leyendo un cobro como si fuera una deuda negativa. `cobrar` lleva
  `permissionGuard` y las dos lecturas no: es una pantalla cuyo único propósito es mover dinero, y
  sin permiso solo habría un formulario para llenar entero y recibir un 403 al confirmar. **Sigue
  sin ser seguridad: la autoridad es el backend.**
- **`cobro:register` es el permiso** —ya asignado por 07.01 a `ORG_ADMIN`, `CONSULTORIO_ADMIN` y
  `ADMINISTRATIVO`—; esta etapa no lo amplió.
- **Defecto de contrato que esta etapa destapó y NO corrigió *entonces*:** el YAML declaraba el
  mismo `operationId` `deLaPersona` en Cobros y en Obligaciones, el generador desambiguaba a
  `deLaPersona1` y `billing-api.ts` tuvo que arrastrar el arreglo en el mismo commit.
  > **Ya no está abierto, verificado el 19/09/2026 sobre el contrato `0.29.0`:** el identificador
  > `deLaPersona` no aparece ni una vez en `openapi/akine-api.yaml`, y `dc1777c` regeneró el
  > cliente "contra 0.22.0, con los `operationId` ya únicos". Lo cerró una etapa posterior, no
  > esta; queda anotado acá porque el defecto nació acá y porque un registro que declara pendiente
  > algo ya resuelto envía a alguien a arreglar lo que está arreglado.

## Lo que NO se verificó

- **Sin QA manual del §6 de `appKine-api/CLAUDE.md`, que ese archivo declara bloqueante para
  deploy.** No corrió para ninguna etapa desde 02.02, y esta no es la excepción: no hay evidencia
  en disco de un cobro registrado desde la pantalla contra el stack real.
- **Sin E2E.** Los seis specs de `appKine-web/e2e/` cubren auth y la vertical de turnos; ninguno
  toca cobros.
- **Sin anticipos, sin anulación de cobro y sin reintegro**, por la decisión declarada arriba. La
  etapa **no cumple su definición normativa completa** de §"Etapa AKINE-07.02", que los pide.
- **Los números no se remidieron.** El commit backend declara `./mvnw verify` en verde con **1701
  unitarias y 178 de integración** contra MySQL 8.4 real, cero fallos y una diferida preexistente
  (7b); el del frontend declara **776 tests**, lint limpio y cobertura **87,88 st / 80,52 rama /
  85,54 fn / 88,88 ln**. Son citas de los mensajes de commit: **no se corrió nada para escribir
  este registro** y nadie verificó que sigan valiendo — 05.03, posterior, ya reporta otros totales.
- **De los tests de backend, en disco solo hay dos archivos bajo `billing`:** `CobroConcurrenteIT`
  y `EconomiaEnElResumenDePersonaTest`. `CobroConcurrenteIT` es lo que sostiene la etapa contra la
  base real —dos cobros simultáneos de la misma deuda dejan uno adentro y el saldo en cero, nunca
  negativo; dos parciales concurrentes entran los dos y cuadran; dos comprobantes concurrentes
  reciben números distintos; y un cobro cuyos medios no suman revierte sin tocar la deuda—.
  **No hay tests de códigos HTTP de este módulo.**


# Registro de cierre — AKINE-05.03 (Cancelación, reprogramación e historial de Turno)

**01/09/2026** · worktree `api-05-03`, rama `akine-05-03-ciclo-turno` · `V38`, cuatro endpoints y contrato **0.21.0** (no 0.20.0: esa versión ya la había publicado 07.02 en esta rama).

- **Cancelar libera el lugar; una ausencia no, y esa es toda la diferencia.** `V30` ya había dejado `deleted_at` y su `deleted_key` generada, y los tres índices de solapamiento ya los contemplaban: la baja lógica devuelve el hueco a la agenda sin borrar una fila. `AUSENTE` deja `deleted_at` en NULL porque la hora se consumió igual —el profesional estuvo ahí— y liberarla haría que la agenda del pasado mintiera.
- **Un turno con Sesión registrada no se cancela, no se mueve y no se marca ausente: 409 `turno-con-atencion`.** Es la decisión que pedía el enunciado. Cancelar en silencio dejaría un registro clínico —y, desde 07.01, una obligación económica— colgando de una reserva que según la agenda nunca existió. Resolver la atención es de M14 y de quien atiende. La costura es `scheduling.spi.AtencionProbe`, **declarada en scheduling e implementada en encounter**, porque la dependencia entre esos dos módulos ya va en ese sentido e invertirla rompe ArchUnit.
- **Reprogramar MUEVE el turno, no cancela y crea otro.** DP-04 exige identidad e historial propios, y además `uk_sesion_turno` cuelga la Sesión del `turno_id` y la obligación de 07.01 cuelga de la Sesión: un reemplazo cortaría esa cadena y obligaría a repuntar filas clínicas y económicas. La trazabilidad la da `turno_evento`, con el intervalo anterior (RN-M12-003). Un turno confirmado **vuelve a `RESERVADO`**: lo confirmado era otro horario.
- **El modelo de serie se difiere, declarado.** Ninguna etapa del Paquete B produce turnos recurrentes —la reserva de 05.02 es de un slot— así que una tabla `serie` no tendría escritor y el comando "cancelar los futuros de la serie" no tendría sobre qué actuar. Es alcance sin modelo muerto: agregar `serie_id` nullable después es una migración aditiva.
- **Reprogramar es una escritura de agenda y toma el mismo lock que reservar**, en `READ_COMMITTED` y con el mismo orden: asegurar la fila en transacción aparte, `FOR UPDATE` **antes** de leer un turno, revalidar, escribir. Cancelar y marcar ausencia **no** lo toman: liberan o dejan igual, no pueden crear un solapamiento, y serializarlas contra toda la sede sería contención sin protección. Las tres comparan `expectedVersion` antes de mutar.
- **La revalidación del slot se extrajo a `RevalidadorDeSlot`**, compartida por reservar y reprogramar. Dos copias divergirían y el síntoma sería una agenda con dos turnos encima. Lleva `turnoExcluidoId`: el turno que se mueve sigue vivo en su horario viejo y sin excluirlo **chocaría contra sí mismo** al correrse media hora.
- **Solo se agregaron los estados que alguna transición alcanza**: `CANCELADO` y `AUSENTE`. No hay estado de llegada (05.04, fuera de DP-10) ni `REPROGRAMADO`.
- **`turno_evento` es append-only** —sin `version`, sin `updated_at`, sin baja— y su puerto no declara `delete` ni `update`. `V38` **rellena** el historial de los turnos que ya existían desde su propia fila; lo único que no se puede reconstruir es quién confirmó, porque `V30` no lo guardaba.
- **Tests: 3 unitarios de servicio, 11 de la máquina de estados y 4 de integración contra MySQL real** —dos reprogramaciones concurrentes al mismo hueco, reprogramar contra reservar, cancelar y volver a reservar el hueco, y la ausencia que no libera—. Cero tests de códigos HTTP.
- **`./mvnw verify` verde en 10:52**: **1715 unitarias + 182 de integración**, 0 fallos, 1 diferida (7b). Cobertura **instrucción 81,62 % · línea 82,87 % · rama 72,25 %**: el gate de 80 se cumple pero el margen bajó de 2,35 a **1,62 puntos**.
- **Deudas declaradas:** sin QA manual del §6, sin E2E y **sin notificación** (RF-M26-003 sigue diferido: toca plantillas de `notification` y el `SecureLinkResolver` con defecto abierto). **Defecto ajeno encontrado y CORREGIDO despues (28/09/2026, en la integracion a `main`):** `AgendaService.descontarReservas` devolvia los slots sin tocar y nunca llamaba a `ReservaProbe`, cuya unica implementacion seguia siendo la provisoria que devolvia el mapa vacio. Hoy responde `ReservaProbeSobreTurnos`, que cuenta los turnos vivos (`deleted_at IS NULL`, el mismo predicado que usa la reserva: cancelado libera el lugar, ausente no) agrupados por instante de inicio, y los slots sin lugar viajan con `cupoLibre` en cero en vez de filtrarse, para que la pantalla pueda decir "14:00 completo". **Sin IT de agenda que lo ejerza contra MySQL:** lo cubierto son unitarios.


# Registro de cierre — AKINE-05.04 reducida (Recepción y check-in)

**02/09/2026** · rama `akine-01.02-identidad`. `V39`, cuatro endpoints y contrato `0.23.0`.
Primera etapa fuera del Paquete B: DP-10 la había diferido nombrándola "riesgo aceptado".

- **Recableado DP-10, tercera vez.** El plan la hace depender de 03.06 (órdenes) y 04.05 (consumo
  de autorizaciones), las dos fuera de alcance. Con cobertura PARTICULAR única **no hay condición
  administrativa que validar**, así que la etapa se reduce a lo que sí tiene sentido hoy: buscar
  los turnos del día, registrar la llegada real y poder deshacerla. El snapshot administrativo
  preliminar que pide el plan es una tabla que colgará de esta misma fila cuando exista.
- **Tapa un hueco que el QA del 01/09 dejó a la vista: no existía ninguna lectura de un turno.**
  El contrato sólo publicaba slots libres de una oferta y el historial de transiciones, así que la
  pantalla de ciclo de vida de 05.03 tuvo que **deducir el estado desde los eventos**. Ahora hay
  `GET /turnos/{id}` y `GET /turnos?fecha=`.
- **El check-in es un estado de la RESERVA, no de la atención** (DP-05). La recepcionista marca la
  llegada sin abrir ninguna atención y el profesional abre la atención sin depender de que alguien
  la haya marcado. Son dos actos de dos personas y ninguno bloquea al otro.
- **La hora la pone el servidor y el cuerpo va vacío.** Es evidencia administrativa: aceptar una
  hora del cliente significaría que el reloj del mostrador decide a qué hora llegó un paciente.
- **El listado incluye los cancelados, con su motivo.** Es la única consulta de `TurnoRepository`
  sin filtro de baja lógica, y es deliberado: alguien se presenta con un turno que se canceló y
  una lista que los esconda deja a la recepción sin nada que decirle.
- **Un turno EN_ESPERA se puede cancelar sin deshacer antes la llegada.** Es el caso "el paciente
  vino y el profesional no lo pudo atender". Obligar a deshacer primero borraría la evidencia de
  que vino, que es lo que la recepción existe para registrar. Reprogramar y marcar ausencia sí
  quedan fuera desde EN_ESPERA: mover un turno cuya hora ya llegó no tiene sentido, y afirmar que
  no vino alguien que está en la sala es falso.
- **El `CHECK` de `V39` nació mal y lo mostró el test, no la revisión.** La primera versión exigía
  que *sólo* un turno EN_ESPERA tuviera hora de llegada, lo que hace imposible el punto anterior.
  La invariante correcta va en un solo sentido: **en espera ⟹ hay llegada**, nunca la vuelta.
- **Check-in idempotente; deshacerlo no.** El doble click en el mostrador es el caso normal.
  Deshacer dos veces no lo es: entre medio el turno pudo cambiar de estado, y un 200 en silencio
  le haría creer al operador que revirtió algo.
- **PHI mínima:** nombre, documento y nombre comercial de la oferta. **Nada clínico.** Por eso
  `TurnoDelDiaView` es un tipo aparte de `TurnoView` y no campos opcionales sobre el mismo: dos
  respuestas con necesidades distintas de PHI no deben compartir forma, porque el día que alguien
  agregue un campo se lo agrega a las dos.
- **`PacienteDirectory.findAll` nace para que la lista no haga N consultas.** Un día de agenda son
  decenas de turnos y cada uno necesita nombre y documento; resolverlos de a uno convierte la
  pantalla en tantas consultas como turnos haya — justo el día en que más se la necesita.
- **Hallazgo de plataforma, no de la etapa: MySQL REDONDEA `DATETIME(6)`, no trunca.** La respuesta
  del primer check-in sale de la entidad en memoria con nanos y la del segundo de la fila
  redondeada, así que difieren en dígitos que la base nunca guardó. El test compara **lo
  almacenado**, que es lo único que el sistema promete. Vale para toda marca de tiempo del
  proyecto.
- **Verificado contra MySQL real:** `RecepcionIT`, 5 escenarios — la fila refleja el check-in con
  su responsable, el doble click no mueve la hora guardada, deshacer limpia la hora y el historial
  conserva los dos eventos, la agenda del día trae los cancelados con motivo y el paciente
  resuelto, y un EN_ESPERA se cancela conservando su llegada.
- **Deudas: sin frontend, sin E2E y sin QA manual** del §6. La pantalla de recepción no existe
  todavía.


# Registro de cierre — AKINE-03.03 (Financiadores y planes de cobertura)

**02/09/2026** · rama `akine-03.03-financiadores`, worktree propio. Módulo nuevo `contracting`,
migración `V41` y contrato `0.24.0` con nueve endpoints. Primer cimiento de M15–M17: 03.04
(coberturas del paciente) y 03.05 (convenios) referencian lo que esta etapa deja fijado.

- **`contracting` nace como módulo propio**, que es lo que AGENT.md §4 ya declaraba para M15–M17.
  Dos tablas —`financiador` y `plan_cobertura`—, las dos con `organization_id NOT NULL`: **no hay
  excepción de ADR-0023 que declarar**, a diferencia de `servicio`.
- **La estabilidad de las referencias históricas tiene dos mitades y sólo una vive acá.** La
  estructural sí: `codigo` inmutable en las dos entidades (`updatable = false`) y sin borrado
  físico (RN-M15-003). La otra —que el consumidor **copie** en vez de leer vivo— la entrega
  `contracting.spi.ReferenciaDeCobertura`, un record de valores y no un puntero, exactamente como
  `obligacion` congela el precio de la oferta en 07.01. **El spi separa las dos mitades a
  propósito:** `find*`/`planesSeleccionables` son lecturas VIVAS para decidir, y `congelar` es la
  copia para guardar. Confundirlas es el defecto que 03.04 no puede cometer, y
  `ContractingCoberturaDirectoryTest` lo hace ejecutable —congelar, mutar el plan, comprobar que
  la copia no cambió—.
- **No hay ninguna regla de no-solapamiento de vigencias, y eso no es un olvido.** Un financiador
  tiene varios planes vigentes a la vez: 210, 310 y 450 conviven, y esa convivencia es el caso
  normal. La vigencia acota **cuándo se puede elegir** un plan, no un turno de exclusividad entre
  planes. Por eso la etapa **no necesitó ninguna fila-lock ni ningún `READ_COMMITTED`**: no hay
  escritura que serializar. Si M16 llega a necesitar que dos convenios del mismo financiador no se
  solapen, esa regla la va a hacer cumplir un lock —nunca un índice— y en su propia migración.
- **`vigencia_hasta` es INCLUSIVA y se declara.** V24 dejó una ambigüedad real: su cabecera llama
  "EXCLUSIVA" a `oferta.vigencia_hasta` y `OfertaSnapshot.vigenteEl` la evalúa inclusiva. Acá las
  dos mitades dicen lo mismo, el CHECK admite `hasta = desde` —un plan que vale un solo día es un
  estado real— y el test lo fija.
- **Ciclo de vida ≠ vigencia, y la API devuelve los dos campos por separado.** `estado`
  (ACTIVO/INACTIVO) y `vigente`. Un plan ACTIVO con la vigencia cerrada es el caso borde de la
  etapa —"plan sin nuevas altas pero con pacientes vigentes"— y colapsarlos dejaría a la pantalla
  sin poder explicar por qué ese plan no se ofrece. Por eso **cerrar la vigencia es el PUT, no la
  baja**: RF-M15-005 pide las dos operaciones y son distintas.
- **La baja de un financiador NO cascadea.** Sus planes conservan sus filas y las coberturas ya
  firmadas siguen resolviendo; lo único que se impide es crear planes NUEVOS (409
  `financiador-inactivo`) y que sus planes se ofrezcan. Igual que la baja de un `servicio` en
  02.06, y la garantía es estructural: `Financiador` no tiene ninguna relación JPA hacia sus
  planes y `FinanciadorService` recibe el puerto de planes **sólo para contarlos**. Tener planes
  activos **no bloquea** la baja —obligar a darlos de baja uno por uno es burocracia sin garantía a
  cambio— pero el número queda en la auditoría.
- **El CUIT se normaliza a 11 dígitos antes de guardar y de comparar**, con un CHECK que lo hace
  cumplir también desde la base. Es la lección de 03.01 con el documento: `27888999` chocó contra
  una ficha guardada como `27.888.999`. Los NULL sí se aprovechan: varios financiadores **sin**
  CUIT conviven sin chocar, que es lo que hace falta en el mostrador.
- **`convenio:manage` pasa de denegar siempre a tener asignación base** para `ORG_ADMIN`
  (ORGANIZACION) y `CONSULTORIO_ADMIN` (CONSULTORIO). **No es un código nuevo** —estaba en el
  catálogo de la matriz §5 desde 01.03, declarado para F3—; la enmienda está en la matriz §13. Se
  evalúa **con la sede del contexto** aunque el financiador sea de la organización: sin sede el
  evaluador deja afuera al `CONSULTORIO_ADMIN`, misma trampa que 03.01 documentó para
  `paciente:manage`.
- **El `PLATFORM_ADMIN` NO lo recibe, y es una decisión declarada.** Su celda dice "Catálogo
  global", que la matriz §3 acota a *"financiadores/planes globales, nunca convenios de un
  tenant"*. **Ese catálogo global no existe**: 03.03 modela el financiador como dato de la
  organización. Dárselo hoy no cumpliría su celda, la violaría. Queda **sin cumplirse**, y el
  camino de migración —`organization_id` nullable más el centinela `owner_key` de ADR-0021— está
  escrito en la cabecera de V41. El bloqueo práctico es el mismo que arrastra RF-M06-005 desde
  02.05: ningún endpoint le dice al frontend si quien mira tiene rol de plataforma.
- **Hueco conocido que la etapa NO cierra:** no existe `convenio:read` y las lecturas se autorizan
  por pertenencia, así que una membership con rol `PACIENTE` lee el catálogo de financiadores de su
  organización. Mismo hueco, y misma causa de fondo —el alcance `OWN` no está implementado—, que
  03.01 dejó abierto en el padrón. Matriz §13.4.
- **`PARTICULAR` no es una fila de `financiador`** (RN-M15-004) y la migración no siembra nada.
  Sembrarlo lo volvería borrable, renombrable y duplicable, y obligaría a que alguna decisión del
  sistema dependiera de un nombre —lo que la regla maestra 15 prohíbe—. La cobertura particular se
  modela en M08 como la **ausencia** de plan financiado.
- **Verificado contra MySQL real:** `FinanciadorYPlanMigrationIT`, 12 escenarios — el código único
  por organización y reusable tras la baja lógica, los NULL de CUIT que no colisionan, el código de
  plan único **dentro del financiador** y no de la organización, la vigencia de un solo día
  admitida y la invertida rechazada, el copago sin moneda rechazado, la baja incoherente rechazada,
  y la FK RESTRICT que impide el borrado físico de un financiador con planes.
- **`./mvnw -o verify` VERDE, medido el 02/09/2026** (17:22 min): **1794 unitarias + 196 de
  integración** contra MySQL 8.4 real, **0 fallos, 0 errores, 1 diferida** —el escenario 7b de
  siempre—. `jacoco:check`: *All coverage checks have been met*.
- **Deudas: sin frontend, sin E2E y sin QA manual** del §6 — el catálogo existe y se puede operar
  por API, pero nadie lo ve desde una pantalla. **Y la cobertura bajó de nuevo, poco pero bajó**:
  instrucción **81,91 %** (venía de 82,35 %), línea **83,16 %** (de 83,48 %) y rama **71,77 %**
  (de 72,56 %). El gate es 0,80 en línea e instrucción y sigue en verde, con **1,91 puntos de
  margen**. La caída habría sido de 1,5 puntos sin el slice de los controllers
  (`ContractingControllersTest`), que se escribió justamente para no pagarla: la capa `api` sin
  tests es de dónde viene toda la erosión, etapa tras etapa.

# Registro de cierre — AKINE-03.02 (Paciente 360, baja lógica y adjuntos administrativos)

**Fecha:** 02/09/2026. **Rama:** `akine-03.02-ficha` (worktree `.wt/akine-03.02-ficha`, desde `4a32175`).
**Commits:** `ed6d83b` (backend, 61 archivos) y `e9616c2` (diseño).
**Diseño:** `appKine-api/docs/diseno/AKINE-03.02-ficha-360-baja-y-adjuntos.md`.
**Migración:** `V40`. **Contrato:** `0.23.0`, 124 operaciones, aditivo.

> **La etapa está implementada y verificada en el backend, y NO se declara cerrada.**
> No hay frontend, no hay E2E y **el QA manual del §6 no corrió** — la misma deuda escrita,
> no saldada, que arrastran todas las etapas desde 02.02.

## 1. Qué entregó

| Pieza | RF | Dónde |
|---|---|---|
| Ficha 360 consolidada | RF-M07-004 | `ResumenDePersonaService` + `person.spi.ResumenDePersonaContributor` |
| Baja lógica de persona y de perfil | RF-M07-005 | `PersonaService.darDeBaja`, `PerfilPacienteService.desactivar` |
| Adjuntos administrativos | RF-M07-006, RF-M25-001..005 | `AdjuntoService`, `AdjuntoStoragePort`, `V40` |

**Diez operaciones nuevas.** Tres sobre `/api/v1/personas/{id}` —`verResumenDePersona`,
`darDeBajaPersona`, `darDeBajaPerfilPaciente`— y cinco de adjuntos bajo
`/api/v1/personas/{id}/adjuntos`. Tres `type` nuevos: `archivo-no-aceptado`,
`adjunto-no-disponible`, `adjunto-inactivo`.

**`V40`, no `V39`:** V39 quedó reservada por otra etapa en vuelo. Es la colisión que 02.07 y
03.01 pagaron con `V26`; reservar cuesta un hueco, descubrirlo al mergear cuesta renumerar
algo ya aplicado.

## 2. Lo que la etapa dejó fijado y hereda lo que siga

| Regla | Por qué |
|---|---|
| **El 360 crece por contribuyentes, no por campos** | `scheduling` y `clinical` ya dependen de `person.spi`: que `person` les preguntara cerraría un ciclo que ArchUnit rechaza. La dependencia se invierte y 03.03/03.04 agregan su sección sin tocar `person` ni subir la versión mayor del contrato |
| **Cada contribuyente declara SU permiso** | El que sabe con qué permiso se leen los turnos es `scheduling`. Si `person` mantuviera esa tabla, el día que un módulo cambie su permiso el 360 seguiría mostrando lo que ya no corresponde |
| **"Según permisos" recorta, no rechaza** | Una sección sin permiso **no se pide** y viaja en `seccionesOmitidas`. Omitirla en silencio haría leer "sin turnos" donde dice "no podés ver los turnos"; un 403 sobre la ficha entera dejaría al profesional sin poder abrir a nadie |
| **La baja de una persona da de baja su perfil** | Los consumidores preguntan por `esPacienteVigente`, no por `activa`: un perfil vivo sobre una ficha cerrada deja reservar turnos a alguien que el padrón considera cerrado. El estado "persona cerrada, paciente vigente" no existe |
| **Fila antes que blob, siempre** | Un huérfano invisible es preferible a una referencia rota, que es el único de los dos errores que el usuario ve |
| **El tipo de un archivo lo deciden sus bytes** | La extensión y el `Content-Type` los elige quien sube. Vale para todo adjunto que venga después |
| **El acceso a un adjunto hereda el de su entidad** (RN-M25-003) | Leer y **descargar** por pertenencia; mutar con `paciente:manage`. Exigir `paciente:manage` para leer dejaría al `PROFESIONAL` sin el consentimiento del paciente que está por atender |

## 3. Decisiones que conviene no rediscutir sin leer primero

- **No hay URLs temporales firmadas**, y la etapa las menciona. Con almacenamiento local el
  binario pasa por la aplicación igual, y firmar una URL agregaría **un segundo camino de
  autorización más débil**: un token en la query string que se copia, queda en los logs del
  proxy y no se puede revocar. RN-M25-002 se cumple por otra vía: la descarga autoriza cada
  llamada y la `storageKey` no sale del backend.
- **El 360 no trae nada clínico**, y no es que falte. AKINE-04.01 exige justificación
  declarada para todo acceso clínico; mostrarlo al abrir una ficha de mostrador convertiría
  ese control en un formalismo.
- **La baja no valida turnos futuros ni deuda.** Es la diferencia con la baja de un
  consultorio o de un espacio. Dar de baja a alguien que se fue debiendo es legítimo;
  bloquearlo obligaría a condonar para poder cerrar la ficha.
- **Ninguna categoría de adjunto es clínica**, y el `CHECK` de `V40` lo hace cumplir
  (RN-M25-005).

## 4. Verificación

**`./mvnw -o verify` BUILD SUCCESS**, 17:35 min: **1784 unitarias + 190 de integración**
contra MySQL 8.4 real, **0 fallos, 0 errores, 1 diferida** (`IdempotenciaYUniquesIT`,
escenario 7b, el de siempre). `jacoco:check`: *All coverage checks have been met*.

**Cobertura: instrucción 82,56 % · línea 83,59 % · rama 72,47 %.** Las dos gateadas
**subieron** respecto de la medición del 01/09 (82,35 % y 83,48 %); la rama bajó 0,09 puntos.

Lo que `AdjuntoMigrationIT` prueba contra MySQL real y ningún doble puede simular:

- El mismo contenido **no entra dos veces vigente** para la misma persona, y **sí entra**
  para dos personas distintas.
- Un archivo dado de baja **se puede volver a subir** (`deleted_key`).
- Dos filas no pueden apuntar al mismo binario.
- Las categorías clínicas —`ESTUDIO`, `INFORME`, `RADIOGRAFIA`, `EVOLUCION`— **no pasan el
  CHECK**, y un adjunto de tamaño cero ni una baja sin motivo tampoco.

## 5. Lo que NO se verificó — declarado, no saldado

- **El QA manual del §6 no corrió.** Ninguna de las diez operaciones se ejerció desde un
  cliente real contra el servidor real. En particular **la subida multipart nunca pasó por
  un `MultipartFile` de verdad**: los tests usan `MockMultipartFile`, y el límite del
  contenedor (`spring.servlet.multipart`) contra el de la aplicación sólo se probó por
  lectura del código.
- **El adaptador de almacenamiento nunca escribió fuera de un `@TempDir`.** El directorio
  por defecto —`./var/adjuntos`— no se ejerció en ningún despliegue.
- **No hay E2E** de ninguna de las tres piezas, ni frontend.
- **La concurrencia de la subida idempotente está probada por el unique**, no por dos hilos:
  no hay un `AdjuntoConcurrenteIT` como sí lo tienen turnos, disponibilidad y cierre.
- **El caso borde "paciente fusionado" NO se implementa**, y se declara en el diseño y en el
  javadoc de `PersonaController`: fusionar exige reapuntar turnos, sesiones, obligaciones y
  adjuntos en cuatro módulos.
- **El cliente del frontend queda dos versiones atrás** (`0.21.0` contra `0.23.0`).

# Registro de cierre — AKINE-03.04 (Coberturas del paciente)

**02/09/2026** · rama `akine-03.04-coberturas`, worktree propio, desde `339eac4`.
**Commit:** `440c0cd` (39 archivos). **Diseño:**
`appKine-api/docs/diseno/AKINE-03.04-coberturas-del-paciente.md`.
**Migración:** `V42`. **Contrato:** `0.26.0`, 131 operaciones, aditivo.

> **Implementada y verificada en el backend, y NO se declara cerrada.** Sin frontend, sin E2E,
> **sin el QA manual del §6** y **sin test de concurrencia sobre el lock** — la misma deuda
> escrita, no saldada, que arrastran todas las etapas desde 02.02, más una propia.

## 1. Qué entregó

**Seis operaciones** bajo `/api/v1/personas/{personaId}/coberturas`: `list`, `seleccion`,
`create`, `update`, `{id}/principal` y `DELETE {id}`. Dos tablas —`cobertura_paciente` y
`cobertura_persona_lock`— y cinco `problemType` nuevos: `plan-no-seleccionable`,
`cobertura-superpuesta`, `cobertura-principal-superpuesta`, `cobertura-inactiva`,
`cobertura-already-inactive`.

**Sin módulo nuevo.** M08 vive en `person`, que es lo que AGENT.md §4 ya declaraba, y reusa
`PersonApiActor`, `PersonProblemHandler`, `AutorizacionDePadron` y `paciente:manage`. La
dependencia nueva es `person → contracting.spi`, unidireccional y verificada por ArchUnit.

## 2. Lo más importante: es el primer consumidor de `contracting.spi`, y no confundió sus mitades

03.03 separó a propósito la **lectura viva** —`find*`, `planesSeleccionables`, para decidir— de
la **copia** —`congelar`, para guardar—. Esta etapa usa **sólo la segunda**, y sólo en el alta.

`cobertura_paciente` tiene **nueve columnas de copia** más `referencia_capturada_el`, todas
`updatable = false`. Después del alta **ninguna lectura vuelve a tocar `contracting`**: ni el
listado, ni la selección del día, ni la edición. Esa ausencia de llamadas *es* la garantía, y
`CoberturaPacienteServiceTest` la hace ejecutable con `verifyNoMoreInteractions(catalogo)`.
`financiador_id` y `plan_id` se guardan igual, para trazabilidad y para el unique del afiliado —
nunca para resolver texto.

`CoberturaPaciente.financiada(...)` **recibe el record entero y no un `planId`**: olvidar la
copia no compila.

## 3. Ninguna regla temporal está en el esquema, y es deliberado

MySQL 8.4 no tiene exclusion constraints. Las dos invariantes entre filas las hace cumplir el
lock de `cobertura_persona_lock`, creado en transacción aparte con
`INSERT ... ON DUPLICATE KEY UPDATE` y tomado en **`READ_COMMITTED`**:

1. Dos coberturas activas del **mismo plan** con vigencias solapadas → 409.
2. Dos coberturas activas marcadas **principal** con vigencias solapadas → 409. Es lo que hace
   determinista la selección del día: el `findFirst` que la resuelve no está eligiendo entre
   candidatas.

Cuarta aplicación del patrón —`agenda_sede`, `consultorio_calendario`, `sesion_numerador`— y la
primera escrita así desde el principio en vez de descubrirlo con un deadlock.

**Lo que NO es regla:** dos coberturas de financiadores **distintos** solapadas son legítimas
—obra social y prepaga a la vez es el caso normal— y **la baja no toma el lock**, porque quitar
una fila no puede crear un solapamiento.

## 4. Lo que la etapa dejó fijado y hereda lo que siga

| Regla | Por qué |
|---|---|
| **PARTICULAR es la ausencia de plan, no una fila** | RN-M08-001 no necesita ningún dato para ser verdad, así que no hay nada que dar de baja. `GET /seleccion` devuelve `particularSiempreDisponible` como **campo**: si fuera una lista a secas, cada pantalla tendría que acordarse de agregarlo y la primera que lo olvide deja al mostrador sin poder cobrar una consulta a un paciente con obra social — RF-M08-005 exactamente |
| **Finalizar la vigencia ≠ dar de baja** | Finalizar es "el paciente cambió de obra social": queda ACTIVA y sigue explicando el pasado. La baja es "nunca debió cargarse". Mismo par que M15 |
| **El plan no se puede cambiar** | Cambiar de plan es OTRA cobertura. Editarla en el lugar reescribiría con qué cobertura se atendió al paciente el mes pasado (RN-M08-003) |
| **Marcar principal no desmarca a la otra en silencio** | Un click que cambia dos coberturas deja una que después nadie puede explicar. 409 con el id de la que ya es principal |
| **Una credencial vencida no invalida nada** | Se informa como `credencialVencida`. Vencerla automáticamente daría de baja coberturas reales por un dato que el mostrador copia a mano |
| **Una Persona sin perfil de paciente no puede tener cobertura** | RF-M07-010 sostenido desde M08. Reusa el `type` `persona-sin-perfil-paciente` que 05.02 ya declaró: es la misma condición y darle uno propio obligaría al frontend a manejar dos códigos para la misma acción |
| **Las cinco causas de "plan no elegible" responden igual** | Distinguir "no existe" de "es de otro tenant" volvería el alta un oráculo del catálogo ajeno |

## 5. Permisos

**Sin códigos nuevos y sin enmienda a la matriz.** Leer por **pertenencia**, mutar con
**`paciente:manage`** sobre la sede del contexto — el mismo criterio de 03.01. Hereda su hueco
abierto: no existe `paciente:read`, así que una membership con rol `PACIENTE` lee las coberturas
de cualquier paciente de su organización. Misma causa de fondo: el alcance `OWN` no está
implementado.

## 6. Verificación

**`./mvnw -o verify` BUILD SUCCESS**, 12:40 min: **1819 unitarias + 205 de integración** contra
MySQL 8.4 real, **0 fallos, 0 errores, 1 diferida** (`IdempotenciaYUniquesIT`, escenario 7b, el
de siempre). `jacoco:check`: *All coverage checks have been met*.

**Cobertura: instrucción 82,07 % · línea 83,37 % · rama 71,22 %.** Las dos gateadas **subieron**
respecto de 03.03 (81,91 % y 83,16 %); la rama bajó 0,55 puntos. Subieron porque el controller
llegó con su slice de tests: es la lección de 03.03, aplicada.

`CoberturaPacienteMigrationIT` prueba contra MySQL real, en nueve escenarios, lo que ningún doble
puede simular: que una FINANCIADA **sin la copia completa** la rechace la base, que una PARTICULAR
con plan o credencial también, que el mismo afiliado no entre dos veces vigente bajo el mismo plan
y **sí** después de la baja lógica (`deleted_key`), que varias PARTICULAR sin afiliado convivan,
que la vigencia de un solo día se admita y la invertida no, que una baja sin motivo se rechace,
que **el borrado físico de un plan referenciado sea imposible** (RESTRICT), y que el
`ON DUPLICATE KEY UPDATE` del candado sea idempotente.

## 7. Lo que NO se verificó — declarado, no saldado

- ~~**No hay `CoberturaConcurrenteIT`.**~~ **SALDADA el 02/09/2026, commit `f98d184`.** El
  registro original declaraba esta deuda como la más importante de la etapa: las dos reglas del
  lock estaban probadas sólo con dobles —que verifican el **orden de las llamadas**, no que dos
  transacciones se esperen— y **nunca habían corrido dos hilos** contra MySQL.

  `CoberturaConcurrenteIT` ejerce tres escenarios contra MySQL real: dos altas del mismo plan con
  vigencias cruzadas (enero–diciembre contra marzo–junio, que ningún unique distingue porque el
  solapamiento compara intervalos y no valores), dos altas de planes distintos ambas marcadas
  principal, y una ráfaga de cuatro altas simultáneas sobre una persona **sin candado previo** —
  el escenario que destapa el deadlock de la fila-lock creada perezosamente, que este proyecto ya
  pagó en `agenda_sede`, `consultorio_calendario` y `sesion_numerador`.

  **Verificado por mutación, y esto es lo que hay que recordar.** Los tres pasaron a la primera,
  que en concurrencia es motivo de sospecha: es exactamente lo que hacía el test de 02.07, que
  miraba el código de respuesta sin simular nunca el escenario. Removiendo
  `BloqueoDeCoberturas.tomar` de `agregar()`, los dos primeros **fallan con las dos altas
  aceptadas** — dos coberturas del mismo plan solapadas, y dos principales a la vez. El test tiene
  dientes; restaurado el lock, vuelven a verde (3 tests, 0 fallos, 89 s).
- **El QA manual del §6 no corrió.** Ninguna de las seis operaciones se ejerció desde un cliente
  real contra el servidor real.
- **No hay E2E ni frontend.** El cliente TypeScript queda **tres versiones atrás**
  (`0.21.0` contra `0.26.0`).
- **RF-M08-006 y RF-M08-007 no se implementan.** Resolver la cobertura aplicable *por Oferta de
  Servicio* necesita M16 (convenios) y M17 (autorizaciones), que no existen. Se declara en el
  diseño y en el javadoc del controller. RN-M08-004 se respeta por omisión: nada de lo que la API
  devuelve afirma que la prestación sea facturable a ese financiador.
- **La cobertura no aparece en ninguna otra vertical todavía.** Ni el turno, ni la sesión, ni la
  obligación la copian: `GET /seleccion` es una lectura y no persiste nada. Cablearla es de las
  etapas que la consuman.
- **El caso borde "baja con turnos" no se valida**, con el mismo criterio que 03.02 usó para la
  baja de persona: dar de baja una cobertura mal cargada de alguien con turnos futuros es
  legítimo, y bloquearlo obligaría a cancelar turnos para poder corregir un dato administrativo.


# Registro de cierre — AKINE-03.05 (Convenios, aranceles y vigencias)

**02/09/2026** · rama `akine-03.05-convenios`, worktree propio, desde `339eac4` (03.03). Un commit,
`4200195`. Extiende el módulo `contracting` con M16: migración **`V43`** con tres tablas y contrato
**`0.27.0`** con **135 operaciones** (125 antes; **diez nuevas**, todas aditivas).

> **La etapa está implementada y verificada en el backend, y NO se declara cerrada.** No hay
> frontend, no hay E2E y **el QA manual del §6 no corrió** — la misma deuda escrita, no saldada,
> que arrastran todas las etapas desde 02.02.

## 1. Qué entregó

| Pieza | RF | Dónde |
|---|---|---|
| Convenio de la sede con un plan | RF-M16-001, RF-M16-002, RF-M16-003 | `ConvenioService`, `V43` |
| Requisitos del convenio (orden, autorización, credencial, tope, documentación) | RF-M16-005 | columnas de `convenio` — **declarados, no resueltos**: los interpreta M17 |
| Arancel por práctica con vigencia propia | RF-M16-004 | `ArancelService`, `convenio_arancel` |
| Resolución explicable del arancel efectivo | RF-M16-006, RF-M16-010 | `ResolutorDeArancel`, `GET /aranceles/efectivo` |
| Snapshot económico congelado para consumidores | RN-M16-004 | `contracting.spi.ArancelDirectory#congelar` → `ArancelCongelado` |

**Diez operaciones**, todas bajo `/api/v1/consultorios/{consultorioId}`: cinco de convenio
(`listConvenios`, `getConvenio`, `createConvenio`, `updateConvenio`, `deactivateConvenio`), cuatro
de arancel (`listAranceles`, `createArancel`, `updateArancel`, `deactivateArancel`) y
`resolveArancelEfectivo`. Siete `problemType` nuevos: `convenio-codigo-taken`, `convenio-solapado`,
`convenio-inactivo`, `convenio-already-inactive`, `arancel-solapado`, `arancel-inactivo`,
`arancel-already-inactive`.

**Tres tablas en `V43`:** `convenio`, `convenio_arancel` y `convenio_lock`. **`V43` y no `V39`–`V42`:**
esas quedaron reservadas por otras etapas en vuelo, y la renumeración final la hace quien mergea. Es
la colisión que 02.07 y 03.01 pagaron con `V26`.

## 2. La regla que define la etapa, y por qué hicieron falta tres cosas y no una

**RN-M16-002.** Dos convenios de la misma `(sede, financiador, plan)` no pueden solaparse en el
tiempo, y dos aranceles de la misma práctica dentro de un convenio tampoco.

**Ningún índice de MySQL expresa eso**, y el `ConvenioYArancelMigrationIT` lo deja escrito con un
test que comprueba que la base **acepta** los dos convenios solapados: 01/01–30/06 y 01/03–31/12 se
pisan sin compartir un solo valor de columna, y MySQL 8.4 no tiene exclusion constraints. Si alguien
intenta "arreglarlo" con un índice, va a descubrir ahí que el índice no expresa la regla.

La hace cumplir un lock de fila sobre `convenio_lock`, con **las tres condiciones que este proyecto
ya pagó tres veces**:

1. **`READ_COMMITTED` en las mutaciones.** Con el `REPEATABLE READ` por defecto de InnoDB el segundo
   hilo toma el lock correctamente y después lee una foto anterior en la que el convenio del primero
   todavía no existe: entran los dos.
2. **La fila-lock creada en una transacción aparte**, con `INSERT ... ON DUPLICATE KEY UPDATE`
   (`ConvenioLockIniciador`, `REQUIRES_NEW`). Crearla perezosamente dentro de la transacción que la
   bloquea produce deadlock entre las primeras N escrituras de una sede, y el `try/catch` no salva.
3. **El lock tomado ANTES de leer** el conjunto que se valida. Leer primero y bloquear después es una
   escalada S→X entre dos transacciones simétricas.

**El orden asegurar → bloquear → leer es la garantía**, y está fijado por un test de orden en
`ConvenioServiceTest` además de por el IT.

**Y la edición también toma el lock**, no sólo el alta: estirar el fin de un convenio hasta pisar al
siguiente es exactamente lo mismo, y una etapa que validara el solapamiento sólo al crear dejaría
abierta la puerta más ancha. La **baja no lo toma**: quitar una fila del conjunto activo nunca puede
crear un solapamiento.

**Un solo lock por SEDE para las dos tablas.** Con locks separados —uno de convenio y otro de
arancel— habría que fijar un orden total entre ellos para no deadlockear. Se eligió correctitud
sobre paralelismo, mismo criterio que `agenda_sede`: son unas pocas escrituras por mes.

## 3. Lo que la etapa dejó fijado y hereda lo que siga

| Regla | Por qué |
|---|---|
| **El convenio es de la SEDE; el financiador y el plan son de la organización** | RN-M16-001. Dos sedes de la misma organización pueden tener aranceles distintos para la misma práctica bajo el mismo plan, y eso es el caso normal de una cadena. `consultorio_id` es `updatable = false` |
| **`plan_id` es NOT NULL, y eso es lo que hace determinista la resolución** | Un convenio "para todo el financiador, sin plan" sería una segunda regla candidata y obligaría a inventar una prioridad — el caso borde "dos reglas candidatas". **El resultado único sale de que no existan dos candidatas, no de un desempate**, y por eso es explicable |
| **El permiso se evalúa sobre la sede de la RUTA, no la del contexto** | Es lo que impide que alguien con `convenio:manage` en la sede A modifique los convenios de la B. `AutorizacionDeCatalogo.exigirGestionDeLaSede`. Y la pertenencia de la sede se comprueba **antes** que el permiso: al revés, una sede ajena daría 403 y eso confirmaría que existe |
| **Tres importes explícitos y ningún porcentaje de cobertura** | §37. Un porcentaje obliga a multiplicar y redondear, y el redondeo de un arancel es la diferencia de un centavo que aparece seis meses después en una presentación rechazada. Con `importe_total`, `importe_financiador` y `coseguro` no hay nada que redondear: la suma de dos `DECIMAL(12,2)` es exacta, y `ck_arancel_partes_suman_total` lo hace cumplir desde la base |
| **La comparación de importes es `compareTo`, nunca `equals`** | `12000.0` y `12000.00` son el mismo importe y distintos `BigDecimal`. Con `equals` se rechazaría un arancel legítimo |
| **La edición valida los importes COMO TERNA aunque llegue uno solo** | Subir el total y olvidarse de repartir la diferencia es el descuido con el que se rompe la invariante económica sin darse cuenta. Lo mismo con la vigencia |
| **Ciclo de vida ≠ vigencia, y la API devuelve los dos campos** | Un convenio ACTIVO con la vigencia cerrada es el caso borde "convenio vencido". **Cerrar la vigencia es el PUT** (RF-M16-003); dar de baja es el DELETE, exige motivo y no tiene vuelta atrás |
| **La baja de un convenio NO cascadea a sus aranceles y no se bloquea por tenerlos** | Dejan de resolver porque su convenio dejó de resolver, que alcanza. El número queda en la auditoría, mismo criterio que la baja de un financiador en 03.03 |
| **La baja libera el código Y EL PERÍODO** | El no-solapamiento sólo mira los activos: se puede volver a firmar con el mismo plan para las mismas fechas |
| **La vigencia del arancel tiene que estar contenida en la del convenio** | Fuera de ella nunca podría resolver, porque la resolución exige primero un convenio aplicable. Aceptarlo dejaría en la grilla filas que prometen un precio que el motor no va a usar |
| **La resolución responde 200 aunque no haya arancel, y con motivo** | RN-M16-005: sin convenio válido no se asume cobertura — y para no asumirla hay que poder verlo. No encontrar convenio es el desenlace **más frecuente** (el paciente se atiende como particular) y un 404 obligaría a la pantalla a tratar el caso normal como excepción. Los dos motivos son distintos y hacen falta los dos: `SIN_CONVENIO_VIGENTE` manda a cobrar como particular, `SIN_ARANCEL_VIGENTE` manda a cargar el precio de esa práctica. Misma lección que `MotivoSinSlots` en 05.01 |
| **`resolver` y `congelar` hacen cosas OPUESTAS** | Lectura viva para decidir contra copia congelada para guardar. Es el mismo patrón que `ReferenciaDeCobertura` de 03.03 y que el snapshot de precio de `obligacion` en 07.01. **Confundirlas es lo que RN-M16-003 prohíbe:** guardar un `arancelId` y resolver el importe al mostrar hace que subir el arancel en enero reescriba lo que se debía en diciembre |
| **La resolución vive en el dominio, compartida por el `spi` y por el endpoint** | Si cada uno la escribiera, el importe que la pantalla muestra y el que `billing` devenga podrían divergir sin que ninguna prueba lo note |

**`convenio:manage` no cambia de asignación.** 03.03 ya se la dio a `ORG_ADMIN` (ORGANIZACIÓN) y
`CONSULTORIO_ADMIN` (CONSULTORIO), y esta etapa no toca la matriz. El `ConvenioConcurrenteIT` corre
contra el evaluador **real** con un `CONSULTORIO_ADMIN` sintético, así que también falla si esa
asignación se quita por descuido.

## 4. Verificación

**`./mvnw -o verify` BUILD SUCCESS**, 10:04 min (sin ningun flag, con el gate de drift del contrato activo): **1900 unitarias + 215 de integración** contra MySQL
8.4 real, **0 fallos, 0 errores, 1 diferida** (`IdempotenciaYUniquesIT`, escenario 7b, el de
siempre). `jacoco:check`: *All coverage checks have been met*.

**La cobertura SUBIÓ**, que es la novedad después de nueve etapas bajando: **instrucción 82,88 %**
(venía de 81,91 % en 03.03), **línea 84,11 %** (de 83,16 %) y **rama 72,16 %** (de 71,77 %). El
margen sobre el gate de 0,80 pasa de 1,91 a **2,88 puntos**. Subió porque la capa `api` entró con su
slice desde el principio (`ConvenioControllersTest`, 24 casos): es exactamente la erosión que 03.03
había identificado.

**`ConvenioConcurrenteIT` existe y pasa, con cinco escenarios contra MySQL real** — es la prueba de
la etapa y sin dos hilos la regla no estaría verificada:

- Dos convenios solapados a la vez: **entra uno solo**, y el otro recibe `ConvenioSolapadoException`
  y **no** un deadlock ni un 500 genérico.
- Dos convenios **consecutivos** a la vez: **entran los dos**. Es la otra mitad de la regla, y la que
  hace que valga la pena serializar en vez de prohibir — renovar un convenio es el caso normal.
- **Tres** altas abiertas simultáneas sobre una sede **sin fila de `convenio_lock`**: entra una y las
  otras dos reciben el 409, no `CannotAcquireLockException` ni `UnexpectedRollbackException`. Es el
  camino de creación concurrente de la fila-lock, que es donde 05.02 encontró su deadlock.
- Dos aranceles solapados de la misma práctica: entra uno solo.
- Dos aranceles de prácticas distintas: entran los dos, aunque compitan por el mismo lock.

`ConvenioYArancelMigrationIT` (14 escenarios) prueba contra MySQL lo que ningún doble puede simular:
el código único por sede y reusable tras la baja lógica, **que la base acepta dos convenios
solapados**, que dos aranceles de la misma práctica conviven, que la fila-lock es única por sede,
los CHECK de vigencia coherente y de un solo día, la invariante `financiador + coseguro = total`, el
tope mensual en cero rechazado, la modalidad fuera de la lista cerrada, y la FK RESTRICT que impide
el borrado físico de un convenio con aranceles.

## 5. Lo que NO se verificó — declarado, no saldado

- **El QA manual del §6 no corrió.** Ninguna de las diez operaciones se ejerció desde un cliente real
  contra el servidor real. Todo lo verificado del camino HTTP es el slice con `MockMvc`.
- **No hay frontend ni E2E.** El cliente TypeScript queda **varias versiones atrás**.
- **La disciplina del snapshot no tiene consumidor todavía.** `ArancelDirectory#congelar` está
  escrito, probado y sin nadie que lo llame: **ningún módulo copia el arancel a sus columnas**, porque
  cablearlo es de M17 y de la etapa que conecte M16 con `obligacion`. Que RN-M16-003 se cumpla
  end-to-end **depende de quien escriba esa etapa**, y este módulo sólo puede hacer que el camino
  correcto sea el más corto.
- **El desempate por `vigencia_desde DESC, id DESC` nunca se ejercitó**, porque para provocarlo haría
  falta escribir dos convenios solapados a mano contra la base, saltando el servicio. Es una defensa
  contra un estado que el código no puede producir, y está sin probar.
- **RF-M16-007 (importación masiva con preview) y RF-M16-008/009 (asociar Oferta a convenio y arancel
  particular por Oferta) NO se implementan.** El arancel se lleva por **práctica** (M06), que es el
  eje sobre el que se negocia con un financiador. El precio particular ya vive en
  `offering.PrecioDeOferta` desde 02.06 y esta etapa no lo duplica. Queda declarado como hueco, no
  como olvido.
- **Los requisitos del convenio se declaran y nadie los interpreta.** `requiere_orden`,
  `requiere_autorizacion`, `requiere_credencial` y `limite_sesiones_mensual` viajan en la resolución
  y **no bloquean nada**: quien los aplique es M17, que no existe. Mismo estado que
  `plan_cobertura.requiere_autorizacion` desde 03.03.
- **La moneda no se valida contra ISO 4217**, sólo contra su longitud de tres. Un convenio en "XXX"
  entra.
- **El caso borde "plan dado de baja" se resuelve al ALTA y no después.** Un convenio cuyo plan se da
  de baja después sigue resolviendo, a propósito (RN-M15-003), y eso significa que un arancel puede
  seguir aplicándose bajo un plan que ya no se ofrece. Es coherente con no reescribir históricos, y
  vale la pena saberlo antes de que aparezca como sorpresa.
- **`concurrent-modification` contra `conflict`**: la versión desactualizada sale como `conflict`,
  igual que en 03.03. Unificarlo sigue siendo la decisión de contrato transversal pendiente.

---

# Registro de cierre — AKINE-03.06 (Órdenes, autorizaciones y documentación administrativa)

> **Cabecera honesta.** La etapa la escribió un agente que se cortó **tres veces** por errores de
> API (dos `server_error`, un 529). Commiteó en tramos, así que no se perdió nada: `a002b1a`
> dominio y aplicación, `4158085` capa REST. **Los tests los dejó escritos y sin correr**, y esta
> última parte —correrlos, la verificación por mutación, el `verify` completo y este registro— la
> hizo la sesión principal, no él. Lo que sigue es lo que se verificó, no lo que se supone.

**Commits:** `a002b1a`, `4158085`, `df0e7b1` sobre `akine-f3-integracion` (`8bdf098`).
**59 archivos, +10.379 líneas.** Sin push, sin merge.

## 1. Qué se construyó

**Módulo `person` extendido** —no uno nuevo—, reusando `PersonApiActor` y `PersonProblemHandler`.

**Migración `V44`**, tres tablas: `orden_medica`, `autorizacion` y `autorizacion_persona_lock`.

**13 operaciones nuevas**, contrato **0.29.0** con **166 operaciones** y sin `operationId`
duplicados: alta/edición/baja/listado de órdenes y autorizaciones, `resolverAutorizacion`,
`vincularDocumentoDeOrden`, `vincularDocumentoDeAutorizacion` y `consultarElegibilidadAdministrativa`.
11 `ProblemType` nuevos.

## 2. El hueco de 03.05 quedó cerrado

El registro de 03.05 declaró que los requisitos del convenio —orden, autorización, credencial,
tope— **se declaraban y nadie los aplicaba**, y que eso era M17. Esta etapa lo cierra con
`RequisitoAdministrativo`, `TipoRequisito` y `ElegibilidadAdministrativa`: la orden o autorización
registrada es lo que satisface el requisito, y `consultarElegibilidadAdministrativa` es el endpoint
que lo responde. **El consumo clínico sigue afuera a propósito** —la etapa lo declara— así que
nadie lo invoca todavía desde la sesión.

**Los adjuntos se reusaron, no se reinventaron.** `vincularDocumento*` se apoya en el
`AdjuntoStoragePort` de 03.02: binarios fuera de la base, descarga que autoriza cada llamada y
`storageKey` que no sale del backend.

## 3. La concurrencia, y la parte que importa

`AutorizacionPersonaLock` + `AutorizacionLockIniciador` + `BloqueoDeAutorizaciones`, calcados de
`CoberturaLockIniciador` (03.04): fila-lock en `REQUIRES_NEW` con `INSERT ... ON DUPLICATE KEY
UPDATE`, `READ_COMMITTED` en las mutaciones, lock tomado antes de leer. Tres call sites en
`AutorizacionService`.

`AutorizacionConcurrenteIT`, cuatro escenarios contra MySQL real:

1. Dos altas aprobadas de la misma práctica con vigencias solapadas → una entra, la otra 409.
2. Dos aprobaciones concurrentes de dos pendientes solapadas → sólo una queda `APROBADA`.
3. Ráfaga de cuatro altas sobre una persona **sin candado previo** — el escenario del deadlock.
4. **Dos aprobadas solapadas de prácticas distintas → entran las dos.** Control negativo: prueba
   que el lock serializa **sin** rechazar lo legítimo. Es el escenario que a 03.04 le faltaba.

**Verificado por mutación.** Removidos los tres `BloqueoDeAutorizaciones.tomar`, **2 de los 4
fallan**; restaurados (`git diff` vacío), los 4 en verde. El test tiene dientes, que es lo que a
02.07 le faltó.

`OrdenYAutorizacionMigrationIT` (14 escenarios) fija los CHECK de `V44` contra el motor. Durante su
escritura apareció un **MySQL 3819** —CHECK violado— que es exactamente la clase de hallazgo que no
aparece contra dobles.

## 4. `./mvnw -o verify` — resultado exacto

```
Tests run: 2069, Failures: 0, Errors: 0, Skipped: 0     (unitarias)
Tests run: 257,  Failures: 0, Errors: 0, Skipped: 1     (integración, MySQL 8.4 real)
jacoco:check — All coverage checks have been met
BUILD SUCCESS · Total time: 12:35 min
Contrato regenerado sin drift · 166 operaciones · 0 operationId duplicados
```

**Cobertura: instrucción 84,17 % · línea 85,30 % · rama 71,91 %.** Las dos gateadas **subieron**
otra vez (venían de 83,82 % y 84,99 % con F3 integrada hasta 03.05). El margen sobre el gate de
0,80 es de **4,17 puntos**, contra los **2,35** con que F3 arrancó: la fase revirtió la erosión
que venía etapa a etapa desde 02.02, y la causa es que las últimas cinco metieron su slice de
tests de la capa `api` desde el principio.

> **Trampa de medición, anotada porque costó un reporte equivocado.** Una corrida de `verify`
> cortada por timeout deja `jacoco.csv` y los reportes de surefire **parciales**, y leerlos da
> números plausibles y falsos —78,94 % de instrucción en este caso, o sea "gate roto"—. Un reporte
> parcial no distingue *no cubierto* de *no ejecutado*. Antes de leer cobertura, confirmar que el
> log tiene la línea `BUILD`.

## 5. Lo que NO se verificó — declarado, no saldado

- **El QA manual del §6 no corrió.** Ninguna de las 13 operaciones se ejerció desde un cliente
  real contra el servidor real. Es la misma deuda que arrastran todas las etapas desde 02.02.
- **Sin frontend y sin E2E.** Ninguna pantalla de F3 existe: las 13 operaciones de esta etapa —y
  las de 03.02, 03.03, 03.04 y 03.05— no tienen un solo consumidor.

  > **Corrección del 03/09/2026:** este registro decía que el cliente TypeScript "sigue en
  > `0.21.0`, ocho versiones atrás". Dejó de ser cierto: `68190d0` lo regeneró contra `0.29.0` y
  > `environment.ts` está alineado. **Lo que falta no es el cliente, son las pantallas**, y la
  > distinción importa porque el cliente generado no cuesta nada y las pantallas son la mitad del
  > trabajo restante. Que el cliente compile contra 166 operaciones no acerca la fecha.
- **La elegibilidad no tiene consumidor.** `consultarElegibilidadAdministrativa` responde, pero
  ni turno ni sesión ni obligación la consultan. Que sirva para lo que la integración clínica va a
  necesitar es una afirmación de diseño, no un hecho medido — el mismo hueco que 03.03 tuvo hasta
  que 03.04 consumió su SPI, y que `ArancelDirectory#congelar` sigue teniendo.
- **`concurrent-modification` contra `conflict`**: igual que en 03.03 y 03.05. Sigue siendo la
  decisión de contrato transversal pendiente.

## 6. Estado de la fase

**F3 completa: 03.01 → 03.06.** Las cinco últimas viven en `akine-f3-integracion`, verificadas
juntas. Migraciones `V39`–`V44` consecutivas, sin una sola colisión, porque las versiones se
reservaron **antes** de arrancar cada etapa y no durante — que es la lección de `V26`.

**Integración pendiente:** llevar `akine-f3-integracion` a `akine-01.02-identidad`. Esa rama la
está usando otra sesión y avanzó varias veces durante F3, así que el merge exige coordinación.

# Registro de avance — AKINE-04.02 (Timeline clínico, entradas versionadas y adjuntos clínicos)

> **LA ETAPA NO ESTÁ CERRADA, y la cabecera lo dice antes que nada.** Esto es un registro de
> avance, no un acta de cierre: §10.5 exige criterios de aceptación cumplidos y evidencia
> reproducible, y **el contrato OpenAPI no se pudo regenerar**. El backend está escrito, compila y
> sus 2.165 unitarias pasan; nada de eso alcanza. Lo que falta está en §6, con su causa.
>
> Escrito el **19/09/2026** por la sesión que hizo el trabajo, con números medidos en esa sesión.

**Rama `akine-04.02-timeline`** (worktree `.wt/akine-04.02-timeline`), seis commits sobre `df0e7b1`:
`3b3c567`, `66097f6`, `351e644`, `be23328`, `bcfb0b0`, `2fe5689`. **79 archivos, +9.025 líneas.**
Sin push y **sin merge a `akine-01.02-identidad`** — a propósito: una rama con el contrato en drift
no se integra.

## 1. Qué se construyó

Las tres cosas que 04.01 había dejado declaradas como su contexto pendiente.

**El timeline, y la decisión que ordena la etapa: no tiene tabla.** Se calcula al leer agregando
`clinical.spi.EventoClinicoContributor`, la costura que 04.01 dejó cableada con cero
implementaciones. Una tabla de timeline es una segunda copia de la verdad que miente el día que
alguien enmienda una entrada sin avisarle al proyector — el mismo argumento con el que V40 no
materializó el Paciente 360 y 05.01 no persistió los slots. `TimelineService` pagina por **keyset
descendente con cursor opaco**, no por offset: un offset sobre un agregado de fuentes heterogéneas
se desordena en cuanto una fuente inserta.

**Cuatro contribuyentes:** entradas clínicas, adjuntos clínicos y antecedentes (los tres en
`clinical`), y **sesiones cerradas** en `encounter`. **El Turno no contribuye, y es decisión, no
olvido:** RN-M09-005 y DP-05 — un turno reservado, cancelado o ausente es un hecho de agenda, y
ninguna transición administrativa prueba que una prestación ocurrió.

**La entrada clínica versionada** (`V45`): cabecera inmutable + una fila por versión. No es un
`@Version` que sobrescribe — eso resuelve otro problema. La v1 es el original, cada enmienda agrega
una fila, **la enmienda exige motivo** (400 si falta, no 409) y las versiones **no se borran nunca,
ni lógicamente**: no tienen `active`, a propósito. La numeración sale del contador
`ultimo_numero_version` con `UPDATE`, **nunca de un `MAX+1`** —dos `MAX` simultáneos dan el mismo
número—, con `OPTIMISTIC_FORCE_INCREMENT` sobre la cabecera y el unique
`(organization_id, entrada_clinica_id, numero_version)` como red debajo. Es la lección de 02.07
—un `@Version` sobre el padre no protege escrituras que sólo tocan tablas hijas— combinada con la
de 06.05.

**El adjunto clínico** (`V46`): tabla propia, módulo propio, **raíz de disco propia**. No se reusó
`adjunto_administrativo` y la cabecera de V40 ya lo había decidido: RN-M25-005 prohíbe que una
clase no clínica se vuelva contenedor clínico, y V40 dejó escrito que el día que hicieran falta
categorías clínicas "la tabla es otra y el módulo también". Se copió el mecanismo, no el archivo:
`storage_key` opaca generada por el servidor, checksum en el unique para que **el reintento de una
subida devuelva 200 con el adjunto que ya existe**, fila con flush primero y blob después, `estado`
`NO_DISPONIBLE` para que un binario perdido dé **409 y no 404**, y tipo detectado por bytes.

**Doce operaciones REST** y el advice propio del módulo (`ClinicalProblemHandler`), que además
cubrió **cuatro excepciones que 04.01 había dejado sin handler**: una historia de otro tenant daba
500 en vez de 404.

## 2. Migraciones

| Versión | Contenido | Estado |
|---|---|---|
| `V45` | `entrada_clinica` + `entrada_clinica_version` | **Nunca ejecutada** |
| `V46` | `adjunto_clinico` | **Nunca ejecutada** |

Reservadas antes de escribir código, que es la lección de `V26` y lo que F3 hizo bien. **Flyway no
las aplicó en ningún lado**: ni los CHECK, ni los uniques, ni las columnas generadas se ejercieron
contra el motor.

## 3. Decisiones tomadas y alternativas descartadas

- **El adaptador de storage se duplica en vez de extraerse a `platform`.** Contra extraerlo:
  `person` ya lo tiene cerrado y verificado, y moverlo es retrabajo sobre una etapa cerrada con
  riesgo de regresión a cambio de ~120 líneas. A favor de duplicar: raíces separadas son
  **segregación física de binarios clínicos**, que es un control real. **Condición de salida escrita
  en el javadoc: tercer consumidor → se extrae.** Lo mismo pasó con la detección de tipo por bytes
  (`TipoDeContenidoClinico`), por el mismo motivo y con la misma condición.
- **No se creó ningún Caso implícito.** La entrada cuelga de la HC. Un "caso por defecto" para que
  el timeline pudiera agrupar es un Caso mal hecho que 04.03 tendría que desarmar. `caso_id` llega
  nullable cuando 04.03 llegue, y es un `ADD COLUMN` barato.
- **`EventoClinico` no ganó un campo `detalle`**, aunque el diseño lo proponía: el challenge §4
  manda y prohíbe todo texto clínico en el índice. Ni el título del adjunto —que suele traer el
  diagnóstico— ni el nombre de archivo viajan. Viaja la categoría.
- **La firma de `EventoClinicoContributor` se cambió ahora** (lleva un tope temporal) porque hoy
  tiene cero implementaciones. El día que haya cinco, cambiarla es tocar cinco módulos.
- **Las rutas de adjunto se anidaron bajo su historia** (`2fe5689`). Estaban planas y recibían
  `historiaClinicaId` como query param **obligatorio** — la misma jerarquía escrita de una forma
  que el cliente se puede olvidar. Las entradas **sí** quedan planas y no es inconsistencia: su
  servicio resuelve entrada → historia → persona por sí solo.
- **La justificación de acceso viaja en la cabecera `X-Justificacion-Acceso`, nunca en query
  string:** un motivo clínico en la URL termina en los logs de acceso de cualquier proxy.

## 4. Auditoría

Ocho eventos nuevos, y la lectura se audita igual que la escritura (DP-03). **El que justifica la
etapa desde el lado de seguridad es `ADJUNTO_CLINICO_DOWNLOADED`:** un estudio descargado y
reenviado es la fuga más barata que tiene un sistema clínico, y sin ese evento no hay forma de
revisarla después. Verificado por lectura de código: `storage_key` **no sale del backend por ningún
campo** de ningún DTO ni vista, y la ruta en disco se deriva exclusivamente de esa clave, validada
contra un patrón antes de tocar `Path.resolve`.

## 5. Pruebas ejecutadas — números medidos, no copiados

```
./mvnw -o test -DskipITs   (sobre df0e7b1, ANTES de la etapa)
Tests run: 2086, Failures: 0, Errors: 0, Skipped: 0 · BUILD SUCCESS

./mvnw -o test -DskipITs   (sobre 2fe5689, la etapa entera)
Tests run: 2165, Failures: 0, Errors: 0, Skipped: 0 · BUILD SUCCESS
```

**+79 tests unitarios.** ArchUnit (`ModuleArchitectureTest` 5, `CodingConventionsTest` 12) en verde
**sin tocar una sola regla**. El registro de 03.06 declaraba 2.069 unitarias medidas con `verify`;
la diferencia con las 2.086 de hoy no se reconcilió y no se afirma nada sobre ella.

## 6. Lo que NO se verificó, y por qué — esto es lo que impide cerrar

**La causa raíz es una sola: Docker no arranca en esta máquina.** `com.docker.service` está
detenido y `Start-Service` falla con denegación de acceso al SCM; lanzar la GUI no levanta el
servicio. Arrancarlo **exige elevación, que una sesión autónoma no tiene**.

De ahí salen todas las deudas de esta etapa:

1. **El contrato OpenAPI no se regeneró.** `OpenApiContractIT` es un test de integración: levanta
   la aplicación con Testcontainers, le pide el YAML a springdoc y lo compara con el commiteado.
   `pom.xml`, `application.yml` y el `info.version` del YAML dicen **`0.30.0`**, pero **los `paths`
   siguen siendo los de `0.29.0`**. Consecuencia concreta: **`OpenApiContractIT` va a fallar por
   drift** hasta que alguien corra `./mvnw verify -Dakine.contract.update=true` con Docker arriba.
2. **Cero tests de integración.** Ninguno se escribió, porque escribir tests que no se pueden
   correr es declarar cubierto lo que no se corrió. Queda sin ejercer: los CHECK y uniques de `V45`
   y `V46`, la columna generada `deleted_key`, **que la numeración de versiones realmente se
   serialice** (dos enmiendas concurrentes → una 409; lo decide Hibernate contra una base real, es
   el mismo hueco que el escenario diferido 20 de 02.07), la idempotencia de la subida por choque
   de checksum, y **las cinco consultas nativas del timeline**, cuyo mapeo de columnas y binding de
   `Instant` no valida Hibernate al arrancar.
3. **Cobertura no medida.** `jacoco:check` corre en `verify`. El margen sobre el gate de 0,80 era
   de 4,17 puntos al cierre de 03.06 y **no se sabe dónde quedó**.
4. **Sin frontend, y está bloqueado por lo mismo.** El cliente TypeScript se genera desde el
   contrato; sin contrato regenerado no hay cliente, y sin cliente no hay pantalla. Las doce
   operaciones no tienen un solo consumidor.
5. **Sin QA manual del §6 y sin E2E**, igual que todas las etapas desde 02.02.

## 7. Límites declarados del diseño, no resueltos

- **El salteo en el borde del cursor:** si una sola fuente tiene más de `limite` eventos en el
  instante exacto del cursor, la página siguiente puede saltear alguno. `ocurrio_en` es
  `DATETIME(6)`, así que es improbable; cerrarlo cuesta un `WHERE` lexicográfico de tres columnas
  replicado en cada contribuyente, que es complejidad que después nadie mantiene igual en las cinco
  fuentes.
- **El costo del timeline crece linealmente con la cantidad de fuentes.** Hoy son cuatro. El día
  que sean ocho y el percentil 95 se note, la respuesta es una proyección — **y recién ahí** una
  tabla con su escritor.
- `vigenteDe(...)` lee todas las versiones de una entrada y toma el máximo en memoria. Barato hoy
  —enmendar es excepcional—, primero a cambiar si una entrada acumula decenas.

## 8. Contexto para quien siga

- **El próximo paso no es escribir código: es levantar Docker.** Con el motor arriba, en orden:
  `./mvnw verify -Dakine.contract.update=true`, después `./mvnw -o verify` completo para leer
  cobertura, después los tests de integración que esta etapa no escribió, y recién ahí el frontend.
- **No mergear `akine-04.02-timeline` a `akine-01.02-identidad` hasta que el contrato esté
  regenerado.** Una rama con el contrato en drift rompe el build de cualquiera que la integre.
- **04.03 (Caso Clínico) hereda dos ganchos ya puestos:** `caso_id` nullable en `entrada_clinica` es
  un `ADD COLUMN`, y el filtro por caso del timeline es un parámetro más en los contribuyentes de
  `clinical`. Renumerar o reasignar entradas cerradas, no: ADR-0011.
- **Trampa de medición heredada de 03.06 y que sigue valiendo:** una corrida de `verify` cortada por
  timeout deja `jacoco.csv` y los reportes de surefire parciales, y leerlos da números plausibles y
  falsos. Antes de leer cobertura, confirmar que el log tiene la línea `BUILD`.

# Addendum al registro de AKINE-04.02 — la revisión que encontró seis defectos

> Escrito el **19/09/2026**, después del registro de avance de más arriba. Lo que sigue pasó
> **después** de que la etapa se diera por escrita, y es la parte que más vale conservar.

El backend de 04.02 quedó escrito con 2.165 unitarias en verde y **cero tests de integración**,
porque Docker no arranca. Sobre ese estado corrieron dos trabajos independientes: una **revisión
adversarial** de sólo lectura y un agente que **escribió los tests de integración sin poder
correrlos**. Los dos, sin conocerse, encontraron los mismos dos primeros defectos.

## Los seis defectos, y por qué importan más allá de esta etapa

1. **`enmendar` devolvía la `version` vieja** (`save()` es un `merge`, no un flush; con
   `OPTIMISTIC_FORCE_INCREMENT` el `@Version` avanza al commit, después de construir la vista). El
   cliente mandaba esa versión como `expectedVersion` y **la segunda enmienda siempre daba 409
   espurio**. Es la regla 5 del `CLAUDE.md` del workspace, escrita hace semanas y repetida igual.
   `darDeBaja` se salvaba **por accidente**: una consulta JPQL posterior disparaba el flush AUTO.
2. **La auditoría de descarga copiaba `nombreArchivo`.** `audit_event` se lee con `auditoria:read`,
   que **no es un permiso clínico**: cualquiera con ese permiso leía `rmn-rodilla-rotura-menisco.pdf`
   junto al `personaId`. El mismo servicio ya argumentaba eso 140 líneas más abajo para no copiar el
   `titulo`, y el módulo administrativo —el menos sensible— pasa `Map.of()`.
3. **Las validaciones de longitud no coincidían con las columnas** (`@Size(20000)` contra
   `VARCHAR(8000)`; el título de la subida sin tope). Una evolución larga pasaba la validación y
   moría en el `INSERT` → **409 "choca con un dato ya existente"** para lo que es un 400, con el
   texto clínico perdido y, en la subida, el binario ya escrito y huérfano.
4. **`NO_DISPONIBLE` nunca se persistía**: se marcaba y se lanzaba la excepción en la misma
   transacción, que revertía el `save`. El listado no mostraba nunca el adjunto roto, contra lo que
   prometían el `@Operation` **y** la cabecera de `V46`.
5. **El `Location` del 201 apuntaba a una ruta que ya no existía** — residuo del refactor que anidó
   las rutas de adjunto bajo su historia.
6. **El `catch` de `DataIntegrityViolationException` tras un flush fallido no puede consultar
   después.** Hibernate marca la transacción `rollbackOnly` y Spring lanza
   `UnexpectedRollbackException` al commit: **500 en vez de la idempotencia prometida**.

## El sexto era heredado, y la evidencia estaba en el propio repositorio

`OnboardingService` (~148-155) ya documenta que **ese mismo re-read** producía `AssertionFailure` o
"Transaction marked as rollbackOnly"; los javadoc de `ComprobanteIniciador` y `ConvenioLockIniciador`
dicen lo mismo. O sea: **el proyecto ya había pagado y documentado este error, y el patrón se volvió
a escribir dos veces más.** Se corrigieron las tres ocurrencias:

- `clinical.AdjuntoClinicoService` (04.02),
- **`clinical.HistoriaClinicaService.abrirIdempotente`** (04.01) — la apertura de Historia Clínica,
  o sea la ruta crítica: dos aperturas simultáneas daban 500 en vez de devolver la que ganó,
- **`person.AdjuntoService.subir`** (03.02, etapa cerrada).

Las tres con el INSERT en `REQUIRES_NEW` y la consecuencia acotada: la fila sobrevive al rollback,
así que si falla el blob se marca `NO_DISPONIBLE` en vez de mentir con `DISPONIBLE`.

## El residuo que casi deja el defecto 1 sin arreglar

El primer arreglo cambió `save` por `saveAndFlush` en los cuatro lugares. **No alcanzaba:** en
`enmendar` la cabecera se leía con `OPTIMISTIC_FORCE_INCREMENT` **y además quedaba sucia**, así que
la versión podía avanzar **dos** veces y el 409 espurio volvía por otra puerta.

Se sacó el force-increment de esa lectura, y la regla quedó enunciada donde el próximo la va a
leer: **force-increment sólo donde la escritura NO toca ninguna columna del padre.** La lección de
02.07 no aplica cuando el padre sí se toca — ahí el `UPDATE` versionado normal ya da la garantía.
La única ocurrencia legítima que queda en el repositorio es
`offering.OfertaHabilitacionService`, y por eso se dejó.

**Estado del backend de 04.02 tras todo esto: `./mvnw -o test -DskipITs` → 2.179 unitarias, 0
fallos.** Más **47 escenarios de integración escritos y nunca ejecutados** en cuatro clases
(`EntradaClinicaConcurrenteIT`, `AdjuntoClinicoIT`, `TimelineIT`,
`EntradaYAdjuntoClinicoMigrationIT`), anotados como escenarios 21 a 27 de `docs/tests-diferidos.md`
con destino "primera sesión con Docker disponible".

---

# Registro de avance — AKINE-04.03 (Caso Clínico y numeración contextual)

> **LA ETAPA NO ESTÁ CERRADA**, por la misma causa que 04.02: sin Docker no hay contrato
> regenerado, ni tests de integración, ni cobertura medida, ni frontend. Registro de avance, no
> acta de cierre. Escrito el **19/09/2026** por la sesión que hizo el trabajo.

**Rama `akine-04.03-caso`**, que **contiene también 04.02**: salió de `2fe5689` y las dos se
integraron en `a1ea71e`. Siete commits propios más el merge.

## 1. La deuda que esta etapa cobra

La regla maestra 3 dice que **las sesiones se numeran dentro del Caso Clínico**, y el sistema las
numeraba dentro de la Historia, porque DP-10 cortó 04.03 y 06.05 tuvo que colgar el correlativo de
algo que existiera. El registro de 06.05 lo dejó escrito: *"cuando 04.03 llegue, el número por Caso
se agrega al lado"*.

**Se cobró agregando, no corrigiendo.** `sesion` conserva `numero_sesion` —impreso en informes y
visto por usuarios— y gana `caso_id` y `numero_en_caso`, los dos nullable. **No hay backfill**: un
backfill tendría que inventar a qué caso pertenece cada sesión ya cerrada, y no hay dato que lo
decida. Las sesiones anteriores quedan sin caso y sin número de caso, que es lo que fueron.

> **Consecuencia que hay que saber leer:** desde acá, "la sesión 8" es ambigua si no se dice de
> qué. Los DTO devuelven los dos números con nombres distintos.

## 2. Qué se construyó

`V47` (propietario `clinical`): `caso_clinico`, `caso_numerador`, `caso_sesion_numerador`,
`caso_profesional`, `caso_evento`. `V48` (propietario `encounter`): las dos columnas de `sesion`,
su unique y su CHECK. **Dos migraciones y no una porque son módulos distintos**, y la regla 1 de
`AGENT.md` §4 no admite que la migración de un módulo toque la tabla de otro.

`clinical.spi.CasoDirectory` + `CasoSnapshot`, pobres a propósito. **`encounter` pide el número, no
lo calcula**: `clinical` no escribe una columna de `sesion` y `encounter` no lee una tabla de caso.

Ocho operaciones REST, contrato **`0.31.0`**, y un filtro opcional por caso en el timeline de 04.02.

## 3. Las cinco condiciones vinculantes del challenge

Las cinco aplicadas. La que importa y que ninguna etapa anterior había enfrentado:

> **El cierre de sesión pasa a tomar DOS numeradores en la misma transacción** —el de la historia y
> el del caso—. **El orden tiene que ser siempre el mismo** (historia primero) o dos cierres
> concurrentes de sesiones de casos cruzados se bloquean mutuamente. Está escrito en cinco lugares
> —`SesionService#cerrar`, `V47`, `V48`, `CasoSesionNumerador` y `CasoDirectory`— y fijado por un
> test con `InOrder`. **Razonado y no probado**: probarlo exige MySQL.

También: correlativo por `UPDATE ultimo_numero + 1` con la fila asegurada en `REQUIRES_NEW` antes
del lock; **sin unique de caso activo** —RN-M10-002 permite varios, un unique ahí sería un bug
disfrazado de protección—, con 409 + candidatos y confirmación por reenvío; y reabrir **no**
reinicia el numerador de sesiones del caso.

## 4. Decisiones que el diseño no fijaba

- **`iniciarSesion` gana un `casoId` opcional.** Sin él, `sesion.caso_id` sería una columna
  inalcanzable y `numero_en_caso` código muerto. Es aditivo y **no enciende RF-M10-007**.
- **El cierre de sesión no revalida el caso**: si se cerró mientras la atención transcurría, la
  sesión cierra igual. La atención ocurrió.
- `caso_clinico` guarda `oferta_consultorio_id` porque `OfertaDirectory.find` exige la sede y las
  ofertas son por sede: se guarda la sede **de la oferta**, no del caso.
- `diagnostico_presuntivo` es `NOT NULL`, `objetivo_terapeutico` nullable.
- **La vigencia de la oferta se evalúa contra la fecha UTC**, no contra la zona IANA de la sede.
  Diferencia asumida y documentada.

## 5. Pruebas — números medidos

```
./mvnw -o test -DskipITs   (akine-04.03-caso, sólo 04.03 sobre 2fe5689)
Tests run: 2182, Failures: 0, Errors: 0, Skipped: 0 · BUILD SUCCESS

./mvnw -o test -DskipITs   (a1ea71e, las dos etapas integradas)
Tests run: 2196, Failures: 0, Errors: 0, Skipped: 0 · BUILD SUCCESS
```

2.179 de 04.02 + 17 de 04.03 = 2.196. ArchUnit y convenciones en verde **sin tocar una regla**.

> **El merge no tuvo un solo conflicto Y NO COMPILABA.** `TimelineIT` se escribió contra la firma
> de `TimelineService` de 04.02 y 04.03 le agregó el filtro por Caso: git no puede ver eso. **Un
> merge limpio no dice nada sobre la compilación cuando una rama cambia una firma y la otra agrega
> un llamador.** Lo agarró `test-compile` después del merge; sin ese paso, entraba en silencio.

## 6. Lo que NO se verificó

Misma causa única: **Docker no arranca sin elevación.**

- **Nada contra MySQL real**, y esta vez el hueco duele más: `V47` y `V48` nunca se aplicaron, y
  **el deadlock de los dos numeradores está razonado y no probado**. No se escribió ningún `*IT`.
- **El contrato quedó en drift por segunda vez.** `info.version` dice `0.31.0`, los `paths` son los
  de `0.29.0`. Hay que regenerar **una sola vez desde `akine-04.03-caso`**, que es la rama que
  tiene las dos etapas: `./mvnw verify -Dakine.contract.update=true`.
- **Cobertura no medida**, **sin frontend**, **sin QA manual del §6**.

## 7. Contexto para quien siga

- **El próximo paso no es escribir código: es levantar Docker.** Después, en orden: regenerar el
  contrato desde `akine-04.03-caso`, `./mvnw -o verify` completo, correr los 47 escenarios de
  integración que 04.02 dejó escritos, escribir los que 04.03 no escribió, y recién ahí el frontend
  de las dos etapas.
- **04.04 (Plan de Tratamiento) ya tiene su cimiento**: el Caso existe, tiene estado, equipo y
  numeración. Lo que **no** existe y 04.04 va a querer es el gate de RF-M10-007 —exigir caso al
  reservar o al atender—, que se dejó afuera a propósito: hoy todas las sesiones del sistema no
  tienen caso, así que encenderlo rompe la vertical que funciona. Es una etapa propia con ventana
  de migración de datos.

## Addendum al registro de AKINE-04.03 — lo que aparecio al escribir sus tests

> Escrito el **19/09/2026**, después del registro de arriba. Los tests de integración de 04.03 se
> escribieron sabiendo que no se podían correr, y **escribirlos valió igual por dos cosas que
> encontraron sin ejecutarse una sola vez.**

**61 escenarios en cuatro clases** —`CierreConDosNumeradoresIT` (8), `CasoClinicoConcurrenteIT` (8),
`CasoClinicoMigrationIT` (33), `CasoClinicoCicloIT` (12)—, escenarios **28 a 32** de
`docs/tests-diferidos.md`, destino "primera sesión con Docker disponible".

### 1. El deadlock de los dos numeradores es estructuralmente imposible

Construir el ciclo de espera para probarlo destapó que **no se puede construir con datos válidos**.
Un Caso pertenece a **exactamente una** Historia: dos sesiones que comparten Caso comparten
Historia y ya se serializan en el primer numerador; dos de Historias distintas no comparten
ninguno. El ciclo exigiría una sesión de la Historia B con un Caso de la Historia A, y `iniciar` lo
rechaza.

**No deroga la condición vinculante del challenge §8.4, la reclasifica.** El orden fijo —historia
primero, caso después— deja de ser *lo que evita un deadlock posible* y pasa a ser *lo que mantiene
imposible uno que hoy lo es por la forma del modelo*. **El día que entre un tercer numerador en esa
transacción, o que algo permita que un Caso cuelgue de más de una Historia, la garantía se cae y el
orden fijo vuelve a ser lo único que queda.**

Lo que los tests sí ejercen es lo observable, más el escenario que **sí** puede fallar: cinco
cierres simultáneos sobre un caso **sin fila de `caso_sesion_numerador`** — el deadlock del
lazy-create que este repositorio ya pagó cuatro veces.

### 2. Un defecto real, encontrado por comparación y corregido (`86af654`)

**`SesionService.cerrar` era la única de las once mutaciones del sistema que toman un numerador que
no declaraba `Isolation.READ_COMMITTED`.** Las otras diez sí —`TurnoService`, `CicloDeTurnoService`,
`CasoClinicoService`, `CoberturaPacienteService`, `AutorizacionService`, `ConvenioService`,
`ArancelService`, `MembershipService`—.

Que el descuido no se notara tiene explicación y **no sirve como defensa**: una transacción lee
siempre sus propias escrituras, así que leer el numerador después de incrementarlo devuelve el
valor nuevo aun bajo `REPEATABLE READ`. Depender de eso es depender del orden de dos líneas dentro
del método, no de una garantía declarada — y 04.03 acaba de convertir ese método en el que toma
**dos** numeradores. Corregido, con el porqué en el javadoc. Suite tras el arreglo: **2.196
unitarias, 0 fallos**.

> **La lección de método, que vale más que el arreglo:** el defecto no lo encontró un test que
> falló —ninguno corrió—. Lo encontró **enumerar las once mutaciones que toman un numerador y
> comparar sus anotaciones**. Escribir tests que no se pueden ejecutar sigue obligando a leer el
> código con la pregunta correcta, y eso ya paga.

# Registro de avance — AKINE-04.04 (Plan de Tratamiento)

> **NO ESTÁ CERRADA**, por la misma causa que 04.02 y 04.03: sin Docker no hay contrato
> regenerado, ni integración, ni cobertura, ni frontend. Escrito el **19/09/2026**.

**Rama `akine-04.04-plan`**, cinco commits propios más el merge de 04.03 (`1c0d2f4`).
**`./mvnw -o test -DskipITs` → 2.227 unitarias, 0 fallos.** `V49`, diez operaciones, contrato
`0.32.0`.

## 1. La frase que ordena la etapa, y su consecuencia estructural

**Planificado no es realizado** (RN-M11-001). De ahí sale la decisión que la hace defendible:

> **`cantidad_realizada` y `cantidad_cancelada` NO TIENEN COLUMNA.**

Si la tuvieran, su dueño real sería `encounter` —que es quien sabe cuándo una sesión se cerró— y
`clinical` tendría una columna que **sólo otro módulo puede mantener correcta**. Ese es el camino
por el que un contador se desincroniza: no por mala fe, sino porque el dueño del dato y el dueño de
la fila son distintos. **Derivar el avance al leer elimina la pregunta en vez de contestarla.**

La sonda es `clinical.spi.RealizadoEnElCasoProbe`, declarada en `clinical` e implementada en
`encounter` — el patrón de `AtencionProbe` (05.03): se declara donde se consume y la implementa
quien tiene el dato, porque `encounter → clinical.spi` ya existe.

`PlanSinContadoresTest` verifica la ausencia **por reflexión** sobre entidades, DTOs de entrada,
commands y vistas. Una ausencia no se prueba llamando al método que falta.

## 2. Los ítems cuelgan de la versión, no del plan

Colgados del plan, cambiar una cantidad estimada **reescribe el pasado**: el avance de hace dos
meses se recalcularía contra un plan que entonces no existía. Colgados de la versión, cada versión
tiene su foto y el histórico queda legible. El precio es duplicar filas al versionar, que es barato
porque versionar es excepcional.

**Consecuencia que la pantalla va a tener que mostrar:** el avance "8 de 20" de la versión 1 y el
"8 de 24" de la versión 2 son **dos números distintos y los dos correctos**.

## 3. Decisiones que el diseño no fijaba

- **`activo_key` no podía ser lo obvio.** MySQL prohíbe que una columna generada referencie un
  `AUTO_INCREMENT`, así que el discriminador del unique de "un solo plan activo por caso" es
  `numero_plan` —ya único por `(org, caso)` y arrancando en 1—, y el centinela 0 nunca colisiona.
  Es la clase de cosa que sólo aparece al escribir el DDL.
- **"Realizada" = sesión `CERRADA` con asistencia `PRESENTE`; "cancelada" = `CERRADA` con
  `AUSENTE`.** `encounter` no tiene estado `CANCELADA` y el cierre exige asistencia, así que no hay
  un tercer grupo. Los turnos cancelados no llegan hasta acá (DP-05).
- **Editar un borrador borra físicamente los ítems de su versión 1.** Es el **único borrado físico
  del módulo**, declarado en el puerto, en el repositorio y en la cabecera de `V49`: un plan que
  nunca se activó no es información histórica. La alternativa era versionar cada tecleo.
- **Un plan puede activarse sin ítems.** Ningún RF lo prohíbe y no se inventó la regla.
- **Sin `OPTIMISTIC_FORCE_INCREMENT`**: la cabecera queda sucia, así que el `UPDATE` versionado
  normal ya da la garantía. Es la recíproca que 04.02 pagó horas antes.

## 4. Sin verificar

Todo lo que depende de MySQL —`V49` nunca se aplicó, los ocho CHECK sin ejercer, la columna
generada `activo_key` sin compilar en el motor, la numeración concurrente sin probar—, la cobertura,
el contrato (tercera tanda en drift) y el frontend. **Concurrencia declarada y no cubierta:** dos
ediciones simultáneas del mismo **borrador** commitean las dos y gana la última, porque esa
escritura no ensucia la cabecera; `expectedVersion` sí protege el caso que importa, que alguien
active el plan en el medio.

---

# Registro de avance — AKINE-04.05 (Integración y consumo de autorizaciones) · **cierra F4**

> **NO ESTÁ CERRADA**, misma causa. Escrito el **19/09/2026**.

**Rama `akine-04.05-autorizaciones`**, seis commits, 55 archivos, +4.087 líneas.
**`./mvnw -o test -DskipITs` → 2.244 unitarias, 0 fallos**, ArchUnit 5/5. `V50`, cinco operaciones,
contrato `0.33.0`.

**Con esto F4 está completa en el backend: 04.01 → 04.05.**

## 1. Qué cierra

03.06 declaró en su propio registro que *"la elegibilidad no tiene consumidor"*. 04.04 declaró que
su cantidad autorizada se registra **declarada** y que la costura real era ésta. **Esta etapa
estrena las dos.**

## 2. El consumo es un hecho, no un contador

`autorizacion.cantidad_consumida` existía desde 03.06 y **nadie la movía**. Incrementarla a secas
daría un número sin historia: nadie podría decir qué la consumió, cuándo, ni deshacer una unidad.

`V50` agrega **`autorizacion_movimiento`, append-only**, un movimiento por hecho, con la cantidad
**siempre positiva** —el signo lo da el `tipo`—. El ledger es la fuente de verdad y
`cantidad_consumida` queda como **saldo materializado escrito en la misma transacción**.

> **Esto parece contradecir a 04.04 y no la contradice.** Allá el dueño del hecho era **otro
> módulo**; acá el ledger y la autorización son **la misma tabla y el mismo módulo**, así que se
> escriben juntos o no se escribe ninguno. Es el criterio de 07.01 con el saldo de la obligación.
> La diferencia no es de gusto: es **quién puede garantizar la coherencia dentro de una
> transacción**. Y hay una razón práctica que sola alcanzaría: `cantidad_consumida` **ya tiene
> consumidores** —la elegibilidad de 03.06 la lee— y quitarla sería romper una etapa cerrada.

**El saldo nunca negativo lo decide la base**, no un `if`:
`UPDATE ... WHERE cantidad_autorizada - cantidad_consumida >= :n`. Cero filas es "no hay saldo".
Es el patrón que 07.02 usó para imputar —*"una resta que no puede pasar de cero, y eso una
condición lo expresa"*— y resuelve el caso borde de la **última unidad concurrente sin tomar un
lock**, o sea sin deadlock posible.

**La idempotencia es un unique**, no un chequeo: un reintento del mismo cierre devuelve el
movimiento que ya existe. **La reversión no borra**: compensa con un movimiento propio más el
`UPDATE` inverso, en la misma transacción.

## 3. La decisión que se aparta del precedente a propósito

**Un cierre de sesión sin saldo NO hace fallar el cierre.**

`billing.ObligacionDevengador` **sí** hace fallar el cierre clínico si no puede devengar, y está
bien: una prestación sin deuda es plata perdida. **Acá es al revés: la atención ocurrió.** Que el
financiador no tenga saldo es administrativo —se resuelve con otra autorización o facturándole al
paciente— y **bloquear el cierre de una historia clínica por eso es lo que DP-06 prohíbe.**

El observador **no lanza**. El saldo insuficiente es un desenlace registrado, no una excepción. Está
escrito en su javadoc con la comparación explícita, porque el próximo que lo lea va a querer
"arreglarlo".

## 4. El challenge de esta etapa tenía un error, y el agente lo encontró

**§2 del challenge daba por verificado que `person → encounter.spi` no cerraba ningún ciclo. Es
falso.** La cadena completa es:

```
clinical  → person.spi      (HistoriaClinicaService usa PacienteDirectory, 04.01)
encounter → clinical.spi    (SesionService usa CasoDirectory, 06.01/04.03)
person    → encounter.spi   ← la arista que el challenge pedía
```

`clinical → person → encounter → clinical`. El challenge verificó **una** mitad —que `encounter` no
importa `person`— y dio la otra por buena sin mirarla.

**Cómo se detectó, que es lo que hay que copiar:** el agente puso una **clase sonda** en
`person.infrastructure` importando `encounter.spi.SesionCerrada` y dejó que ArchUnit hablara.
`ModuleArchitectureTest` falló con `Cycle detected: Slice clinical -> Slice person -> Slice
encounter -> clinical`. **`SlicesRuleDefinition` busca ciclos de cualquier longitud; razonar el
grafo de memoria sólo encuentra los de dos.**

La arista se invirtió: `person.spi.ConsumoDeAutorizaciones` y el observador en
`encounter.infrastructure`, junto a sus dos hermanos (`EncounterRealizadoEnElCasoProbe`,
`SesionEventoContributor`). Se conserva lo que el challenge exigía de verdad: `SesionService` habla
sólo con `CierreDeSesionObserver`, `person` sigue siendo el único que escribe sus tablas, y nadie
importa `encounter.domain`.

## 5. Decisiones que el diseño no fijaba

- **Cuál autorización se consume.** Ni `Sesion` ni `SesionCerrada` traen cobertura ni práctica, y
  **no existe tabla puente Oferta↔Práctica** (V24 la dejó afuera). Se elige entre las aprobadas
  vigentes del paciente la de vencimiento más próximo — el desempate de `ElegibilidadAdministrativa`.
  **Puede consumir una autorización de otra práctica**, y es el límite declarado de la etapa: se
  unifica en 06.04.
- **La idempotencia se pre-consulta y el choque NO se atrapa.** Atraparlo obligaría a `REQUIRES_NEW`
  y eso separaría el `INSERT` del movimiento del `UPDATE` del saldo en dos transacciones,
  rompiendo lo único que sostiene la coherencia. La ventana residual la cierra el `@Version` de
  `Sesion`.
- **Ni `READ_COMMITTED` ni force-increment**, argumentado: el `UPDATE` condicional **no toma lock**,
  así que la trampa de la foto de InnoDB no aplica —un `UPDATE` lee la versión actual, no la foto—;
  y toca una columna del padre, así que el force-increment chocaría con él.
- **`GET /saldo` devuelve el saldo dos veces** —la columna y el recálculo del ledger— con un campo
  `coherente`. Es la única mitigación posible sin Docker al riesgo de divergencia. **No corrige:**
  una mutación escondida en un `GET` taparía el síntoma.
- **RF-M11-007 no crea versión de plan ni `plan_evento`.** Vincular una autorización no cambia lo
  planificado sino la fuente del mismo número, y el vocabulario de `TipoEventoPlan` está cerrado por
  el CHECK de `V49`: meterlo en `EDICION` haría que el historial afirme algo falso.

## 6. Sin verificar

- **`V50` nunca se aplicó.** Sus cinco CHECK, el unique de idempotencia y la FK autorreferencial,
  sin ejercer.
- **El `UPDATE` condicional nunca corrió contra la base.** La "última unidad concurrente" está
  probada con un mock que devuelve cero filas, **no con dos transacciones peleándose**. Es lo
  primero a cubrir cuando haya Docker.
- **Nada compara la suma del ledger contra `cantidad_consumida`.** Si alguna vez divergen, **nada
  lo detecta**. Ese test es lo segundo.
- **Contrato en drift por cuarta vez**, cobertura sin medir, sin frontend, QA manual del §6 sin
  correr — 21 etapas de deuda.

## 7. Contexto para quien siga

**F4 está completa en el backend y ninguna de sus cinco etapas está cerrada.** Lo que falta es
siempre lo mismo y tiene un solo desbloqueo: **Docker**. Con el motor arriba, en orden:

1. `./mvnw verify -Dakine.contract.update=true` **desde `akine-04.05-autorizaciones`**, que es la
   rama que tiene las cuatro tandas. Una sola vez.
2. `./mvnw -o verify` completo → se ejecutan **por primera vez** los 108 escenarios de integración
   que 04.02 y 04.03 dejaron escritos, y se mide la cobertura.
3. Escribir los ITs que 04.04 y 04.05 no escribieron.
4. Merge y frontend de las cinco etapas.

---

# Registro de avance — AKINE-06.03 (Evaluación completa, examen y mediciones)

> **NO ESTÁ CERRADA.** Misma causa que toda F4: Docker no arranca en esta máquina. Escrito el
> **20/09/2026**.

**Rama `akine-06.03-examen`**, siete commits, **52 archivos, +5.491 líneas**.
**`./mvnw -o -B test -DskipITs` → 2.249 unitarias, 0 fallos**, ArchUnit 5/5, convenciones 12/12.
`V51` y `V52`, ocho operaciones, contrato declarado en **`0.34.0`**.

## 1. Dos tablas y dos módulos, a propósito

El **catálogo de definiciones de medición** vive en `resource` —es configuración de la sede, se
administra fuera de la atención y sobrevive a cualquier sesión—. La **medición registrada** vive en
`encounter`, porque es un hecho de la atención. Colapsarlas en una sola tabla haría que dar de baja
un test borrara la historia que lo usó.

De ahí sale la consecuencia que más se va a querer "arreglar": **la baja de una definición no
cascadea.** Las mediciones que ya la usaban siguen legibles y siguen entrando en la comparación; lo
único que se impide es registrar nuevas. Es el mismo criterio que 02.06 fijó para el servicio dado
de baja y sus ofertas.

## 2. `PUT` y no `POST` para registrar

Una medición por test y por lado: la operación es **idempotente por naturaleza**, y el autosave de
la sesión la va a repetir. `POST` obligaría a inventar una regla de deduplicación que la forma del
recurso ya expresa.

## 3. El rango valida al registrar, y el rechazo es 400

Un EVA de 12 en una escala de 0 a 10 es un problema del **cuerpo enviado**: no depende de nada que
pueda cambiar entre dos peticiones. Un 409 sugiere reintentar y mandaría al cliente a repetir algo
que va a fallar igual.

## 4. La comparación se calcula al leer

No hay tabla de resumen. Es la misma regla que la disponibilidad efectiva, el Paciente 360 y el
timeline clínico de 04.02: **una tabla de resumen es una segunda copia de la verdad.**

## 5. Un `ProblemType` compartido por dos módulos

`medicion-definicion-no-accesible` lo emiten **`resource`** —desde la administración del catálogo— y
**`encounter`** —desde el registro de una medición—. Un solo `type` para los dos, porque para el
cliente la situación es la misma. Las **excepciones** sí son dos, una por módulo, y cada una se
mapea en el advice de su módulo: meterlas en `GlobalExceptionHandler` cerraría un ciclo.

## 6. Una desviación del diseño, deliberada

§9 escribe las rutas de sesión como `/api/v1/sesiones/{id}/mediciones`. **Se implementaron bajo
`/api/v1/consultorios/{consultorioId}/sesiones/{sesionId}/mediciones`**: `MedicionService` resuelve
alcance y `sesion:register` contra la sede, y `SesionController` ya vive bajo ese prefijo. La ruta
corta obligaría a mandar el consultorio como parámetro suelto. Las ocho operaciones están; lo que
cambia es el prefijo.

## 7. Lo que NO se verificó

Cero integración, cobertura sin medir y **contrato sin regenerar**: `pom.xml` y `application.yml`
dicen `0.34.0`, los `paths` son los de `0.29.0`. Es la **quinta tanda apilada sin regenerar**, y el
riesgo que crece con cada una es que nadie sabe si springdoc desambiguó algún `operationId` —y ése
es justamente el gate que no se puede correr—.

---

# Registro de avance — AKINE-06.06 (Enmiendas y versionado de sesión cerrada)

> **NO ESTÁ CERRADA.** Misma causa. Escrito el **20/09/2026**.

**Rama `akine-06.06-enmiendas`**, tres commits, **25 archivos, +2.246 líneas**.
**`./mvnw -o -B test -DskipITs` → 2.247 unitarias, 0 fallos**, ArchUnit 5/5. `V53`, contrato
declarado en **`0.35.0`**.

## 1. La corrección se versiona; no se pisa

Una sesión cerrada que cambia en el lugar destruye el documento que se cerró. La enmienda escribe
una **versión nueva** con su motivo, y la anterior queda legible. Es la regla 10 del repositorio
—información histórica relevante no se elimina— aplicada a un agregado que ya estaba cerrado.

Detalle que el test dejó fijado: la versión que se escribe lleva el contenido **ya enmendado**.
Copiarlo antes de aplicar produciría un historial que miente sobre lo que cada versión decía.

## 2. La enmienda no re-dispara lo económico

`CierreDeSesionObserver` **no** se vuelve a notificar. El cierre ya devengó su obligación y ya
consumió su autorización; enmendar el texto de una evaluación no es una segunda prestación.
Notificar de nuevo duplicaría deuda y consumo, que es el peor desenlace posible de esta etapa.

El test lo afirma con un observador mockeado en la lista del servicio. **Con `List.of()` pasaría
igual aunque el servicio volviera a notificar**, así que ese detalle no es decorativo.

## 3. Sin motivo no hay enmienda, y es 400

Lo hace cumplir la **entidad**, no el DTO: una validación que vive sólo en el borde se saltea desde
cualquier otro llamador. 400 y no 409 por el mismo criterio que el rango de 06.03 — el cuerpo está
mal y reintentarlo no lo arregla.

## 4. Lo que NO se verificó

Además de la integración, la cobertura y el contrato: **quedaron sin test, a propósito**, el mapeo
HTTP de las dos excepciones, el permiso, el evento de auditoría `SESION_AMENDED` —y que el motivo
**no** viaje en él—, el tope de 280 del motivo, enmendar una sesión abierta, y la sesión ajena
(409). Todo eso está implementado y sin cubrir: es una decisión explícita del usuario de esta
sesión, no un olvido.

---

# Registro de avance — AKINE-07.03 (Caja diaria y movimientos reales)

> **NO ESTÁ CERRADA.** Misma causa. Escrito el **20/09/2026**.

**Rama `akine-07.03-caja`**, cuatro commits, **47 archivos, +4.090 líneas**.
**`./mvnw -o -B test -DskipITs` → 2.250 unitarias, 0 fallos**, ArchUnit 5/5. `V54`, siete
operaciones, contrato declarado en **`0.36.0`**.

## 1. Cierra el tercer término de la regla maestra

Deuda, cobro y caja son tres cosas. 07.01 devengó la deuda, 07.02 la cobró e imputó, y faltaba el
**hecho monetario**: entró o salió plata de un lugar físico, en una jornada, con un responsable.

**La relación cobro–movimiento no es uno a uno**, y por eso son dos tablas y no una columna: un
cobro con tarjeta no mueve el cajón, y un egreso por insumos no tiene cobro detrás. **El medio de
pago es lo que decide** si el cobro genera movimiento, no el cobro en sí.

## 2. La caja es de la sede

Dos sedes son dos cajones a distancia física. Un saldo que los sume no se puede contar ni arquear.
`organization_id` **y** `consultorio_id` en las dos tablas, y en sus uniques.

## 3. El saldo se mueve con una condición, no con un lock

`UPDATE ... WHERE saldo >= :importe`. Cero filas es "no alcanza". Es una resta que no puede pasar de
cero, y eso una condición lo expresa mejor que un lock —mismo patrón que la imputación de 07.02—.

## 4. El cierre no se revierte: se compensa

RN-M20-003. Una jornada cerrada que cambia en silencio deja un arqueo que no prueba nada. Y toda
diferencia queda registrada **y justificada** (RN-M20-004): cerrar con diferencia y sin motivo es un
rechazo, y `jornadas.cerrar` ni se intenta.

## 5. `caja:operate` no es `cobro:register`

Es la confusión que esta etapa tiene que impedir. Cobrar es un acto **comercial**; abrir, arquear y
cerrar una caja es responsabilidad sobre **dinero físico**. Colapsarlos haría que cualquiera que
pueda cobrar pudiera declarar un arqueo, que es justo el control que M20 existe para tener.

Por la razón simétrica, **el cobro NO exige `caja:operate`**: exigirlo haría que **poder cobrar
dependiera del medio de pago elegido**, que es absurdo desde el mostrador.

## 6. Los dos tests de la matriz que fallaron, fallaron por diseño propio

`RolePermissionsTest` enumeraba `CAJA_OPERATE` como *"permiso de fase futura que no tiene ningún
rol"*, y su comentario dice textual: *"el día que alguien los conecte sin su módulo, este test se lo
dice"*. **Su módulo ya existe.** El permiso salió de esa lista y pasó a tener su propio test, que
afirma la fila entera de la matriz. `PermissionEvaluatorServiceTest` necesitó el mismo agregado.
Esto es el test haciendo su trabajo, no una regla relajada.

## 7. ArchUnit dijo que no hay aristas nuevas

`billing` ya dependía de `organization.spi`; lo único que no usaba de `platform.spi` era
`AuditTrail`, y `platform` es la base del grafo. **No se dio por verificado razonando**: lo dijo
`ModuleArchitectureTest`, que es la lección que 04.05 dejó cara.

## 8. Lo que NO se verificó — y los tres que un mock no prueba

Integración, cobertura y contrato, como todas. Y **tres escenarios anotados en
`docs/tests-diferidos.md`** para la primera sesión con Docker:

- **#33** — la concurrencia real del `UPDATE` condicional del saldo. Hoy está probada con un mock
  que devuelve cero filas, que **no es probarla**.
- **#34** — `saldoTeoricoEsperado` y el cierre concurrente.
- **#35** — **que `V54` siquiera ejecute**, y que sus `CHECK` y columnas generadas hagan lo que
  dicen. Ninguna migración de F5 en adelante se aplicó jamás contra un motor.

---

# Registro de avance — AKINE-06.04 (Tratamientos realizados y espacios usados)

> **NO ESTÁ CERRADA.** Misma causa: Docker no arranca. Escrito el **20/09/2026**.

**Rama `akine-06.04-tratamientos`**, cinco commits. **`./mvnw -o -B test -DskipITs` → 2.249
unitarias, 0 fallos**, ArchUnit 5/5, convenciones 12/12. `V55` (dos tablas), cuatro operaciones,
contrato declarado en **`0.37.0`**.

## 1. Encontró un defecto real, y lo corrigió

**Era reproducible y le costaba unidades al paciente.** Un paciente con dos autorizaciones vigentes
—fonoaudiología al 30/09 y kinesiología al 31/12— cerraba una sesión **de kinesiología** y el
sistema consumía **la de fonoaudiología**: el desempate era *"la que vence antes"* y **nadie miraba
la práctica**. Le come al paciente unidades que sí necesita, y le declara al financiador algo que no
se prestó.

Es exactamente el límite que 04.04 y 04.05 declararon por escrito y difirieron a esta etapa.

La corrección: `SesionCerrada` y `ConsumoPorSesion` llevan ahora `practicasRealizadas`, y sólo se
imputa contra una práctica **realmente aplicada**. Si ninguna autorización cubre la práctica, **no
se consume nada** —`SIN_AUTORIZACION_PARA_LA_PRACTICA`— en vez de gastar la equivocada.

> **El conjunto vacío conserva el comportamiento anterior, y es deliberado.** Toda sesión anterior a
> 06.04 no tiene tratamientos registrados: filtrar igual habría apagado el consumo de autorizaciones
> **en todo el sistema**.

## 2. La otra mitad del límite NO cierra, y no es por falta de ganas

**El avance del plan se sigue contando por oferta.** Verificado contra el esquema, no de memoria:
`plan_item` se lleva por `oferta_id`/`servicio_id`, la autorización por `practica_id`, y **ni
`servicio` ni `oferta` tienen `practica_id`: no existe puente Oferta↔Práctica.**

`tratamiento_realizado` es un puente **observado**, no **configurado**, y un plan planifica antes de
que exista ninguna sesión. Cerrarlo exige una decisión del usuario (§5).

## 3. El challenge corrigió al diseño en la regla maestra 4

El diseño validaba **ocupación del espacio** al registrar el tratamiento, porque el plan la lista.
El challenge lo dio vuelta: **rechazar un hecho consumado por ocupación no evita la sobreocupación,
impide documentarla.** La sesión ya ocurrió; lo que se registra es qué pasó, no qué estaba
permitido. Se sacó.

## 4. La arista nueva se comprobó con una sonda

`encounter → resource.spi`. **Clase sonda + ArchUnit, no razonamiento** —el precedente de 04.05—:
`Tests run: 5, Failures: 0`, sonda borrada después. Las FK hacia `practica` y `espacio` cruzan
módulos con el precedente explícito de `fk_sesion_caso` en `V48`: la regla prohíbe **leer o escribir
la tabla ajena**, no que el motor proteja integridad.

**El plan está desactualizado acá**: dice *"rutas `clinical`"*, escrito cuando se asumía que la
Sesión viviría en `clinical`. Vive en `encounter` desde `V33`.

## 5. Tres decisiones que quedan para el usuario

1. **¿El plan pasa a contarse por práctica?** Exige `plan_item.practica_id`, altera una tabla de
   `clinical`, **reabre 04.04** y define si un plan se planifica por oferta o por práctica —RF-M11-002
   dice "prácticas", `V49` implementó ofertas—. Con ventana de migración de datos.
2. **¿Qué parámetros exige cada práctica?** `plan_sesiones.txt` §10.4 los pide condicionales por
   tipo y **no existe configuración que los declare**. Hoy se valida que estén **tipados**, no cuáles
   son obligatorios.
3. **¿Una sesión con cinco tratamientos consume cinco unidades o una?** Quedó en **una**. Cambiarlo
   es una decisión económica y no hay RF que la resuelva.

## 6. Una excepción declarada a la baja lógica

Los **parámetros** se borran físicamente en el `PUT`: son atributos sin identidad y sólo editables
con la sesión en borrador. **Queda anotado que si 06.06 habilita editar sesiones cerradas, la
excepción cae.**

## 7. Lo que NO se verificó

`V55` nunca se aplicó contra un motor, cero ITs, cobertura sin medir, contrato en drift
(`0.37.0` con los `paths` de `0.29.0`). Cinco escenarios diferidos en `docs/tests-diferidos.md`
(39–43).

**El hueco unitario más grande, declarado:** la validación de tipado de `ParametroAplicado` —lógica
pura y barata— quedó sin test por el corte de alcance de esta sesión; la mitiga a medias el `CHECK`
de `V55`. Tampoco hay tests de `TratamientoService` ni de la capa REST.

---

# Registro de avance — AKINE-07.05 (Egresos y pagos a profesionales)

> **NO ESTÁ CERRADA.** Misma causa: Docker no arranca. Escrito el **20/09/2026**.

**Rama `akine-07.05-egresos`**, cinco commits, sale de `akine-07.03-caja`.
**`./mvnw -o -B test -DskipITs` → 2.249 unitarias, 0 fallos**, ArchUnit 5/5, convenciones 12/12.
`V57`, ocho operaciones, contrato declarado en **`0.39.0`**.

> **El número lo midió el orquestador, no el agente.** El agente reportó *2.232 sobre un baseline de
> 2.227*; la corrida real sobre su misma rama da **2.249 sobre 2.244**, que es la aritmética que
> cierra —5 tests nuevos—. El build está verde en los dos relatos; lo que estaba mal era el conteo.
> **Vale como recordatorio de que un número reportado por un agente se vuelve a medir.**

## 1. Del lado del egreso también son tres cosas

Del lado del paciente son deuda, cobro y caja. Acá son **egreso** (la obligación de la
organización), **pago** (lo que la salda) y **movimiento de caja** (el hecho monetario que lo
ejecuta). El diseño lo hace cumplir con estructura, no con disciplina:

- `egreso` **no tiene** `movimiento_caja_id` ni `jornada_caja_id`, y existe con saldo intacto: eso
  es una deuda viva.
- `pago_egreso` tiene estado y anulación propios, y hay **varios por egreso** —pago parcial—.
- El movimiento apunta al pago, **no al revés**.

Y RN-M22-003 se cumple porque el modelo **no tiene cómo nombrar la sesión**: `egreso` no lleva
`sesion_id`, ni `obligacion_id`, ni `persona_id`, ni `cobro_id`.

## 2. El caso adverso necesita dos condiciones, no una

80.000 en el cajón; dos administrativos pagan 50.000 cada uno. Dos `WHERE`, uno por invariante:
`saldo_pendiente >= :importe` en el egreso **y** `saldo_arqueo >= :importe` en la caja. **Sin la
segunda, el cajón queda en −20.000 y el arqueo registra un faltante que nunca existió.**

## 3. Un solo dueño para `movimiento_caja`

El egreso asienta **por el mismo método de `billing.application`** que usa el cobro
(`MovimientoCajaService.asentar`/`.revertir`), y `CajaDeEgreso` es el gemelo de `CajaDeCobro`. Si el
egreso hubiera asentado por su cuenta, **`saldo_arqueo` tendría dos dueños**, que es la forma más
barata de que el dinero deje de cuadrar.

## 4. Un defecto heredado de 07.03, encontrado y corregido

`MovimientoCajaService.revertir` exigía caja abierta **siempre**, incluso para revertir una
transferencia. **Contradice su propio diseño**: `AKINE-07.03-caja.md` §7 dice *"si no hay jornada
abierta **y el movimiento a revertir era en efectivo**"*.

No se notó en 07.03 porque allí las reversiones nacían de movimientos manuales, que ya exigen caja
abierta. Anular un pago por transferencia no puede depender de que alguien haya abierto el cajón. El
cambio **amplía** lo aceptado y nunca lo rechazado, así que no rompe a nadie.

## 5. Cero aristas nuevas, y la que parecía peligrosa no se toma

Resolver el nombre del colaborador **no** crea `billing → identity`:
`AccountIdentityDirectory` vive en `platform.spi` como **puerto invertido**. Confirmado con
`ModuleArchitectureTest` después de cada bloque, no razonando el grafo.

## 6. Permisos: se reusa `caja:operate`, no se inventó uno

La matriz §32 no tiene fila *"Registrar Egreso"*, y la más cercana —*Operar Caja*— tiene exactamente
los actores que M22 declara. **Queda abierto** si M22 merece `egreso:manage` propio: la matriz dice
que el backend debe implementar permisos más granulares, y cambiarlo después es un código en el
catálogo y una línea en `CajaAcceso` — no toca el modelo.

## 7. Dos decisiones que quedan para el usuario

1. **RF-M22-003, el adjunto binario.** Se entregó la **referencia** documental —tipo, número,
   fecha— y **no** el archivo. Esta etapa sería el **tercer consumidor** del storage duplicado, y la
   condición de salida escrita desde 04.02 es extraerlo a `platform.spi`: refactor de `person` y
   `clinical`, dos módulos cerrados, ~1.300 líneas por copia. **¿RF-M22-003 se declara cubierto por
   la referencia, o se abre la etapa de extracción?**
2. **¿`egreso:manage` propio?** Ver §6.

## 8. Lo que NO se verificó

Cero ITs, cobertura sin medir, contrato en drift, y **`V57` nunca se aplicó contra un motor**. Ocho
escenarios en `docs/tests-diferidos.md`. **Los tres que deciden si el dinero cuadra**: los dos pagos
concurrentes contra un cajón que no alcanza, los dos contra el mismo egreso, y **la suma del ledger
contra `saldo_pendiente`, que hoy no confronta nadie** —mismo hueco que 04.05 dejó abierto—.

> **No mergear a `akine-07.03-caja` ni a `akine-01.02-identidad` hasta regenerar el contrato**, y
> `test-compile` después de cualquier merge: el merge limpio que no compila ya pasó una vez.


# Registro de avance — AKINE-08.01 (Clases programadas y agenda unificada) · **abre F9**

> **NO ESTÁ CERRADA.** Misma causa: Docker no arranca. Escrito el **20/09/2026**.

**Rama `akine-08.01-clases`**, tres commits, sale de `akine-04.05-autorizaciones`.
**`./mvnw -o -B test -DskipITs` → 2.249 unitarias, 0 fallos**, ArchUnit 5/5, convenciones 12/12.
Baseline medido en la misma rama antes de tocar nada: **2.244**. Cinco tests nuevos, la aritmética
cierra. `V58`, **siete operaciones**, contrato declarado en **`0.40.0`**.

Módulo nuevo: **`activity`**, el decimotercero. Diseño y design challenge en
`appKine-api/docs/diseno/AKINE-08.01-*.md`.

## 1. La pregunta cara, y cómo se resuelve

Una clase ocupa un espacio y un profesional durante una franja, **igual que un turno**. Ningún
unique puede impedir que se pisen —09:00–10:00 y 09:30–10:00 no comparten un valor de columna— y
MySQL 8.4 no tiene exclusion constraints. La regla la hace cumplir el lock de `agenda_sede`.

El problema nuevo es que ahora las dos escrituras viven en **módulos distintos**, y la salida
natural —que cada módulo serialice lo suyo— es exactamente la trampa: **dos locks distintos no se
ven entre sí, las dos transacciones ganan, el box queda doblemente vendido y nada falla.** Un
defecto silencioso.

**Decisión: la clase se disputa la MISMA fila de `agenda_sede` que el turno.** `scheduling` la
presta por `spi` (`AgendaDeSede`: `asegurar`, `bloquear`, y las dos lecturas de ocupación de
turnos). Los cuatro pares quedan cubiertos por el mismo punto de serialización:

| | cómo |
|---|---|
| turno vs turno | ya estaba, 05.02 |
| clase vs turno | `ClaseService` lee turnos por `scheduling.spi`, bajo el lock |
| **turno vs clase** | `RevalidadorDeSlot` lee clases por `OcupacionExternaProbe`, bajo el mismo lock |
| clase vs clase | repositorio propio, bajo el mismo lock |

**Que un módulo bloquee una fila de otro es acoplamiento, y está declarado como tal.** La
alternativa no es menos acoplamiento: es el mismo sin exclusión. Esta forma lo deja visible en el
`spi`.

## 2. Cero aristas nuevas hacia `activity`, y se comprobó antes de escribir dominio

`scheduling` **no importa nada de `activity`**. Las dos costuras de vuelta
—`OcupacionExternaProbe` para la exclusión y `EventoExternoDeAgenda` para la grilla— se declaran en
**`scheduling.spi`** y las implementa `activity`. Una interfaz implementada no es una arista de
compilación, y `activity` es hoja.

**Se verificó con una clase sonda y `ModuleArchitectureTest`, no razonando el grafo.** Es el
precedente de 04.05, que dio por verificada de memoria una arista que sí cerraba ciclo:
`SlicesRuleDefinition` busca ciclos de cualquier longitud y la cabeza encuentra los de dos.

**Son dos interfaces y no una** aunque las implemente la misma clase: una se consulta bajo el lock,
en el camino que decide la correctitud, y la otra fuera de toda transacción, en el que dibuja una
pantalla.

## 3. La agenda unificada es una proyección, y eso es todo lo que es

`GET /consultorios/{id}/agenda?fecha=` devuelve una lista **discriminada por `tipo`**
(`TURNO` | `CLASE`). **No hay tabla `evento_agenda`, no hay herencia y no se migró un solo turno.**
RF-M12-013 lo pide literalmente y el plan de la etapa lo repite: *"no polimorfismo prematuro
destructivo"*.

**`GET /turnos?fecha=` no se tocó.** La unificada es un endpoint nuevo al lado: meterle clases a la
respuesta vieja habría sido incompatible, y la recepción tiene casos donde quiere ver sólo turnos.

> **El id no es único entre tipos.** Un turno 7 y una clase 7 existen a la vez: la clave de una fila
> es el par `(tipo, eventoId)`. Está escrito en el contrato y en el javadoc porque un cliente que
> use sólo el id mezcla dos eventos distintos.

## 4. Una trampa que el esquema ya tenía y nadie había nombrado

**GRUPAL no se deduce de la capacidad.** `V24` obliga a que una oferta `GRUPAL` tenga capacidad
mayor a 1, pero **no la recíproca**: una oferta `INDIVIDUAL` en un box de dos camillas puede tener
capacidad 2 y sigue siendo individual. RN-M28-001 exige que la clase cuelgue de una oferta GRUPAL,
así que deducirlo del cupo **dejaría programar clases sobre ofertas individuales**.

Por eso `offering.spi.OfertaSnapshot` gana `grupal`. Es un campo nuevo en un record del `spi`: tocó
cuatro construcciones en tests de otras etapas y ninguna lógica. El test de la regla usa una oferta
de capacidad 4 que **no** es grupal, justamente para que un código que dedujera la modalidad del
cupo no pase en verde.

## 5. Lo que entra acá y lo que queda para 08.02 y 08.03 — explícito, porque abre la fase

**Entra:** la clase como evento único (programar, reprogramar, cancelar, historial append-only),
capacidad efectiva calculada al leer, exclusión mutua con el turno, y la grilla unificada.

**08.02 — inscripciones y cupos:** `InscripcionClase`, los seis estados de RN-M28-004, la ocupación
real, la lista de espera, la concurrencia del último cupo y las notificaciones.

**08.03 — asistencia:** asistencia por participante y su consecuencia económica, **sin crear un
turno por participante**.

Dos límites que quedan escritos para que las siguientes no se sorprendan:

- **La regla de RF-M12-012 —no bajar la capacidad por debajo de los confirmados— YA está
  implementada y hoy lee cero.** `ClaseService#contarOcupacion` devuelve `0` desde un único lugar
  con la etapa destino al lado. 08.02 la enciende sin escribir una línea nueva. Escribirla después
  habría sido escribirla en el momento en que empieza a poder romperse.
- **Ninguna respuesta de 08.01 lleva lista de participantes.** Es la decisión de seguridad de la
  etapa, y está repetida en tres javadoc para que 08.02 no la deshaga por inercia al proyectar.

> **Y una que vale la pena aislar:** cancelar una clase **es idempotente**, a diferencia de cancelar
> un turno. Hoy eso parece una comodidad de pantalla. Cuando 08.02 y 08.07 cuelguen de esa operación
> la devolución de créditos y las reversas, **es lo que impide devolver plata dos veces** —
> CA-M28-006-06 lo pide con esas palabras. La regla se fijó antes que el dinero que protege.

## 6. Permisos: dos códigos nuevos, y por qué no se reusaron los del turno

`clase:read` y `clase:manage`, con el mismo reparto que `turno:read`/`turno:manage`. **No se reusan
los del turno aunque hoy los repartan igual:** 08.02 va a colgar de `clase:read` la lista de
participantes, que es exactamente el dato que alguien podría querer cerrar sin cerrar la agenda de
turnos. Fusionarlos hoy hace imposible separarlos después sin un cambio incompatible.

`PLATFORM_ADMIN` recibe `clase:read` con alcance SOPORTE y **no** recibe `clase:manage`: programar
una clase es operar, y la matriz §32 le dice "No" a todas las filas operativas.

> **Efecto colateral menor:** la fila de `CONSULTORIO_ADMIN` en `RolePermissions` pasó de `Map.of` a
> `Map.ofEntries`. `Map.of` tiene un tope de diez pares y la fila lo alcanzó. No cambia semántica.

## 7. Desvío del plan, declarado

La etapa declara dependencia de **AKINE-07.09** (gate y release del MVP), que **no está cerrada** —
varias etapas de F7 siguen en vuelo en otros worktrees. Se avanzó igual porque esa dependencia es de
**release, no técnica**: 08.01 no consume ninguna capacidad que 07.09 produzca. Queda anotado como
desvío, no como algo resuelto.

## 8. Lo que NO se verificó

Cero ITs, cobertura sin medir, contrato en drift —ahora también el de `0.40.0`, sobre los de
`0.30.0` a `0.33.0`—, y **`V58` nunca se aplicó contra un motor**. Sin frontend, porque el cliente
TypeScript se genera desde el contrato.

**Y el agravante propio de esta etapa, que hay que decir sin rodeos: lo único que necesita probarse
de verdad es lo que un unitario no puede contestar.** Un mock que devuelve cero filas no reproduce
el gestor de locks de InnoDB. Por eso se dejó **un archivo de test con cinco casos** y el resto
quedó en `docs/tests-diferidos.md`, escenarios **39 a 42**, **no simulado**.

El 39 es el que decide si la etapa hizo lo que dice, y lleva su **verificación por mutación**
escrita: sacarle a `ClaseService` la llamada a `agenda.bloquear(...)` y comprobar que los dos
primeros escenarios empiecen a vender el box dos veces. **Si siguen pasando, el test no está
probando lo que dice.**

El 40 persigue un modo de falla peor que un error de sintaxis: si `deleted_key` de `V58` compilara
pero calculara mal, la tabla se crea, los cinco unitarios pasan y **las consultas de solapamiento
dejan de ver clases vivas** — la exclusión entera se apaga en silencio.

> **No mergear a `akine-01.02-identidad` hasta regenerar el contrato**, y `test-compile` después de
> cualquier merge: el merge limpio que no compila ya pasó una vez.

---

# Registro de avance — AKINE-07.04 (Presentaciones y cuenta corriente de financiadores)

> **NO ESTÁ CERRADA.** Misma causa: Docker no arranca. Escrito el **20/09/2026**, por el
> orquestador y no por el agente que la implementó —el agente pidió no escribir en este archivo
> porque hay otras etapas en vuelo apendeando al mismo lugar, y tenía razón—.

**Rama `akine-07.04-presentaciones`**, cinco commits, sale de `akine-07.03-caja`.
**`./mvnw -o -B test -DskipITs` → 2.249 unitarias, 0 fallos**, ArchUnit 5/5, convenciones 12/12.
`V56`, **trece operaciones**, contrato declarado en **`0.38.0`**.

## 1. El cimiento que falta, y que manda sobre todo lo demás

**No existe ninguna obligación con `responsable = FINANCIADOR`, y nada la produce.**

`ObligacionDevengador` sigue devengando **una sola** obligación a nombre del paciente —es el recorte
de DP-10—, y el enchufe que `V36` reservó (`snapshot_convenio_id`) **nunca se conectó**, aunque
03.05 se implementó como el módulo `contracting`.

Consecuencias medibles, no hipotéticas:

- `GET /prestaciones-elegibles` devuelve **lista vacía en un despliegue real**.
- **RF-M21-003 no puede validar** orden, autorización ni credencial: los tres requisitos
  documentales del convenio viven en `contracting.spi.ArancelCongelado` —`requeriaOrden`,
  `requeriaAutorizacion`, `requeriaCredencial`— y el devengado no los copia.

**No es deuda de verificación: es un cimiento que falta.** Su destino no es "la primera sesión con
Docker" sino **una etapa propia** —recablear el devengado, con migración y cambio de contrato—, que
necesita la cobertura vigente del paciente (`person` no la expone por `spi`), la `practicaId` de la
oferta (`SesionCerrada` no la lleva) y copiar el `ArancelCongelado` entero.

La etapa se implementó igual porque **su modelo no cambia cuando el productor llegue**, y agregó una
sola columna estructuralmente imprescindible: `obligacion.financiador_id`. **Es una decisión
pendiente del usuario.**

## 2. La obligación se salda al conciliar, no al pagar

Un pago de un financiador es **global**: llega un importe por un lote, no una imputación
prestación por prestación. Repartirlo automáticamente **inventaría una imputación que el financiador
no informó**, y esa invención después no se puede distinguir de un dato real.

## 3. Conciliar exige saldo cero y no ofrece ajuste

Nombra el residual y **se niega a fingir**. Es exactamente el criterio que 07.03 aplicó al arqueo:
una diferencia se registra y se justifica, no se emparcha para que el número cierre.

## 4. El caso que rompe, y por qué necesita tres defensas

El aviso de débito por 15.000 y la transferencia por 100.000 sobre el mismo lote de 100.000, a la
vez. Sin condición el saldo queda negativo **y el lote concilia solo**.

Tres defensas, no una: `UPDATE ... WHERE saldo >= :importe`, `CHECK (saldo >= 0)` y
`CHECK (saldo = presentado − debitado − cobrado)`. La tercera es la que impide que los cuatro
importes dejen de cuadrar entre sí por un camino que nadie previó.

## 5. La arista nueva se comprobó con sonda

`billing → contracting.spi`. Clase sonda **antes de escribir nada**, `ModuleArchitectureTest` 5/5,
sonda borrada. El precedente de 04.05 lo exige y esta etapa lo respetó.

La FK a `financiador` **no es acceso**: el nombre se pide por `contracting.spi`. Y la cuenta
corriente del financiador **no es la caja** — un pago por transferencia asienta con
`jornada_caja_id` NULL y `afecta_arqueo = 0`.

## 6. Un `DELETE` físico, declarado y defendido

Uno solo, alcanzable **únicamente desde `BORRADOR`**, donde no hay historia que preservar.
`financiador_pago` es append-only y `presentacion` usa `deleted_at`/`deleted_key` y nunca se borra.

## 7. Permisos: se reusa `cobro:register`

En vez de crear un permiso que repartiría **los mismos roles con el mismo alcance**. Mismo criterio
que 07.05 tomó con `caja:operate`.

## 8. Lo que NO se verificó

Cero ITs —**a propósito**: la etapa decidió no escribir tests de integración que no se pueden
correr, porque 04.02 y 04.03 dejaron 108 escritos y nunca ejecutados, y un test que no corre
**parece verificación sin serlo**—. `V56` nunca se aplicó contra un motor, así que no hay evidencia
de que sus `CHECK` ni la columna generada `ocupa_marca` siquiera ejecuten. Cobertura sin medir,
contrato en drift, sin frontend.

Cinco escenarios en `docs/tests-diferidos.md`, renumerados **36 a 40** al integrar. El más
importante es el **38**: el caso de §4 con dos transacciones de verdad, el único que puede confirmar
que el `UPDATE ... WHERE saldo >= :importe` sirve para lo que se lo eligió. El test unitario que
existe simula cero filas con un mock: **prueba la traducción a 409, no que el motor serialice.**

---

# Registro de avance — AKINE-07.06 (Reportes y tableros del MVP)

> **NO ESTÁ CERRADA.** Misma causa: Docker no arranca. Escrito el **20/09/2026** por el orquestador.

**Rama `akine-07.06-reportes`**, cuatro commits, sale de `akine-f7-integracion`.
Baseline confirmado por el agente: **2.260**. Final **2.265 unitarias, 0 fallos**, ArchUnit 5/5,
convenciones 12/12. `V59`, tres operaciones, contrato declarado en **`0.41.0`**.

## 1. No materializó nada, y lo defendió con un argumento de falla

`V59` son **dos `CREATE INDEX` y ningún `CREATE TABLE`**. El módulo `reporting` **no tiene capa
`infrastructure` ni un solo repositorio**: cada sección la calcula el módulo dueño de la tabla.

El argumento no es de pureza sino de falla: **los caminos que van a escribir están anunciados** —el
devengado de financiador se va a recablear, 06.04 cambia cómo avanza el plan, una anulación
retroactiva toca un período ya "cerrado"—, y cada uno sería un backfill que **alguien tiene que
acordarse de correr**.

**Condición de salida escrita:** se materializa el día que exista una medición contra MySQL real que
muestre que un reporte no entra en el tiempo interactivo. **Hoy esa medición no se puede tomar.**

Es el sexto lugar donde el repositorio decide lo mismo, después de 02.04, 03.02, 04.02, 05.01 y
06.03.

## 2. La prueba de ciclos que hay que copiar

El agente no razonó el grafo. Puso cinco clases sonda y corrió `ModuleArchitectureTest`: **5/5 en
verde**. Y después **agregó a propósito una sola línea** —un campo `CasoDirectory` en el servicio de
`reporting`, que es lo que sale solo si nadie mira— y obtuvo:

```
Cycle detected: Slice clinical -> Slice reporting
BUILD FAILURE
```

**Las dos corridas quedaron pegadas en el challenge.** Eso es lo que convierte a la sonda en
evidencia y no en ceremonia: la segunda corrida prueba que la primera medía algo.

La dependencia va **al revés** de lo que sale natural: `reporting` publica `ReporteContributor` y
los cuatro módulos fuente lo implementan. **Quince agregaciones, ninguna en `reporting`.**

## 3. Cinco conceptos, nueve indicadores, cero totales

Deuda, cobro, caja, presentación a financiador y egreso son cinco cosas, y **un tablero que sume un
cobro con un movimiento de caja cuenta la misma plata dos veces**. No hay un solo total que los
cruce.

## 4. Lo dado de baja se excluye del total y se cuenta aparte

Las dos alternativas eran defendibles y la tercera no: **lo que desaparece es inauditable, lo que
suma es incorrecto.**

## 5. Encontró un defecto real de F7 que no era de su etapa

Ver el registro de la corrección, más abajo: `V57` borraba el `PAGO_FINANCIADOR` que `V56` acababa
de agregar. **No lo arregló**, y el motivo es bueno: `V59` es suya y meterle el arreglo de otra
etapa la vuelve un cajón de sastre. Lo elevó, y lo corrigió el orquestador en la rama de
integración.

## 6. Dos cosas que quedan para el usuario

1. **El alcance `OWN` en reportes.** La matriz dice que un `PROFESIONAL` ve *"sólo reportes de su
   propia actividad"*. **`OWN` no está implementado en ningún lado del repositorio** y la matriz
   misma lo declara hueco de F8. **No inventó una versión.** El recorte que sí aplica es por sección.
2. **La sección de financiadores da cero** mientras el devengado no se recablee —sin obligación no
   hay elegible, sin elegible no hay presentación—. Se construyó igual, porque el día que se
   recablee da números sin tocar una línea, y **emite la advertencia `sin-devengado-de-financiador`
   cuando sus totales son cero** en vez de mostrar ceros mudos.

## 7. Lo que NO se verificó

Todo lo que necesita motor: los números, la reconciliación, `CONVERT_TZ`, que `V59` ejecute, **que
los índices se usen**, el aislamiento de tenant real y los bordes del rango. Escenarios **49–53** de
`docs/tests-diferidos.md`. Sin ITs, contrato en drift, cobertura sin medir.

---

# Registro de avance — AKINE-08.02 (Inscripciones, cupos y lista de espera)

> **NO ESTÁ CERRADA.** Misma causa. Escrito el **20/09/2026** por el orquestador.

**Rama `akine-08.02-inscripciones`**, cinco commits, sale de `akine-08.01-clases`.
Baseline confirmado por el agente: **2.249**. Final **2.256 unitarias, 0 fallos**, ArchUnit 5/5.
`V60`, cinco operaciones, contrato declarado en **`0.42.0`**.

## 1. La última vacante disputada: el cupo se otorga con UNA sentencia

**Ningún unique puede expresar "quedan vacantes"**: es un conteo contra un tope. `SELECT count(*)`
seguido de `INSERT` deja una ventana, y **la ventana es el bug** — dos transacciones cuentan 7 de 8,
las dos insertan, quedan 9 en 8 lugares y **nada falla**.

```sql
UPDATE clase_programada SET cupo_ocupado = cupo_ocupado + 1
 WHERE id=? AND organization_id=? AND deleted_at IS NULL AND estado='PROGRAMADA'
   AND cupo_ocupado < LEAST(capacidad, :capacidadEfectiva)
```

Una fila = tenés el lugar; cero = no hay. **Sin ventana, porque no hay dos sentencias.**

Los dos términos del `LEAST` cumplen funciones distintas: `capacidad` es **la columna que ningún
llamador puede saltearse**, y `:capacidadEfectiva` agrega los límites de oferta y box, que viven en
otros módulos y **ninguna base puede comprobar sola**.

## 2. La desviación declarada: `cupo_ocupado` materializado

**08.01 había escrito que no existiría**, y 08.02 lo agrega. El argumento: **no es una caché, es el
asignador.** El lugar lo otorga el `UPDATE` y la inscripción es el **recibo**. Es exactamente la
forma de `cantidad_consumida` más ledger que 04.05 fijó.

Dos detalles de implementación que sostienen eso y que no se pueden tocar:

- La columna se mapea **`insertable=false, updatable=false`**: si JPA pudiera escribirla, cualquier
  `save()` la pisaría con un valor viejo y **la sobreventa volvería por la puerta de atrás**.
- **No hace avanzar el `@Version`**, o el formulario de reprogramación abierto en el mostrador
  comería un 409 cada vez que alguien se anota.

## 3. La baja libera el lugar ANTES de leer la cola

Ese `UPDATE` toma el lock X de la fila de la clase y **lo retiene hasta el commit**, así que dos
bajas simultáneas se serializan ahí y la segunda lee una cola **de la que ya salió** el promovido por
la primera. Leerla antes sería leer una foto vieja: el mismo error que leer antes de bloquear.

Y promover es `UPDATE ... WHERE estado='LISTA_ESPERA'`, cuyo cero significa **"ya la promovió otro"**
— y en ese caso **se devuelve el lugar tomado**.

**Orden de locks fijado: `agenda_sede → clase_programada → inscripcion_clase`.** La inscripción
**nunca** toma `agenda_sede`: tomarlo después de la fila de la clase sería una inversión contra
`ClaseService`.

## 4. Un tercer caso que apareció al desarmar los dos primeros

**Bajar la capacidad mientras alguien se inscribe.** `reprogramar` y `cancelar` de clase ahora leen
`FOR UPDATE`; sin eso leían la ocupación con un `SELECT` plano y una inscripción concurrente dejaba
**`cupo_ocupado > capacidad`**.

## 5. El contacto no sale del snapshot clínico

Los avisos `CLASE_MODIFICADA` y `CUPO_LIBERADO` se encolan **dentro de la transacción del negocio**,
una fila por destinatario. El correo sale de un puerto **nuevo y aparte**,
`person.spi.ContactoDirectory`, **no** de `PacienteSnapshot`: ese snapshot lo consumen los módulos
clínicos y **RN-M09-003 les prohíbe tener contacto**.

## 6. Un gate que hizo exactamente su trabajo

Agregar los dos `NotificationType` **rompió la compilación** de `IdentitySecureLinkResolver`, cuyo
`switch` es exhaustivo **sin `default`**. Es la propiedad que se quiere: obliga a decidir si el tipo
nuevo lleva token, en vez de heredar un default silencioso.

## 7. La decisión que queda para el usuario

**La ventana de aceptación de una vacante ofrecida** (RF-M28-004 paso 7) **no está implementada.** Lo
que se implementó es la **política automática**: al liberarse un lugar, la cabeza de la cola pasa a
`RESERVADA` en el acto y se le avisa.

La ventana exigiría un **séptimo estado `OFRECIDA` que RN-M28-004 no lista**, más una columna de
vencimiento, más un job que expire lo no respondido, más decidir qué pasa si nadie acepta: **es una
máquina de estados con reloj y es una etapa propia.**

La política automática **sí** satisface CA-M28-004-06 y CA-M26-007-06. Lo que **no** satisface es un
centro que quiera que la vacante se *ofrezca* y no se *imponga*.

> Relacionado y ya conocido: **el autoservicio del paciente queda afuera** porque no existe vínculo
> entre `cuenta` y `persona` — el mismo hueco por el que `OWN` no está implementado. Sin él,
> "inscribirme a mí mismo" no se puede autorizar sin abrir "inscribir a cualquiera".

## 8. Lo que NO se verificó

Seis escenarios, **43 a 48**, cada uno **con su verificación por mutación escrita** para que la
primera corrida con Docker pueda comprobar que el test prueba lo que dice. El que más importa es el
**invariante `cupo_ocupado == COUNT(recibos)`**: es el gemelo exacto de la deuda que 04.05 dejó
—la suma del ledger contra `cantidad_consumida`—, y **si divergen, hoy no lo detecta nada**.

Sin ITs, `V60` nunca aplicada contra un motor, cobertura sin medir, contrato en drift.

---

# Registro de avance — AKINE-08.03 (Asistencia y operación de clases)

> **NO ESTÁ CERRADA.** Misma causa: Docker no arranca. Escrito el **20/09/2026** por el orquestador.

**Rama `akine-08.03-asistencia`**, cuatro commits, sale de `akine-08.02-inscripciones`.
Baseline confirmado por el agente: **2.256**. Final **2.263 unitarias, 0 fallos**, ArchUnit 5/5,
convenciones 12/12. `V61`, **siete operaciones**, contrato declarado en **`0.43.0`**, **cero tipos de
problema nuevos**.

## 1. La decisión que esta etapa se negó a tomar, y por qué tiene razón

**RF-M18-008 y RF-M19-009 no se implementaron, y no por alcance sino por precondición.** El RF
condiciona la deuda a que la Oferta use esquema `POR_CLASE` **y** a que *"la política"* defina que el
cargo se devenga con la asistencia. **Ninguna de las dos es evaluable hoy:**

- `V24` declara `esquema_cobro` textualmente como *"DATO DECLARADO, NO RESUELTO… sin lista cerrada a
  propósito, porque el vocabulario lo fija M16/M18"*. **Hoy ese campo puede decir cualquier cosa.**
- **"La política" no existe**: ni tabla, ni columna, ni default. La define **08.06**.
- `OfertaSnapshot` **no expone** `esquemaCobro`, también a propósito.

> Devengar acá habría sido **inventar el vocabulario de M29 desde M28**, y el resultado no sería
> casi correcto: sería **deuda con un arancel que nadie eligió**.

**Decisión pendiente del usuario: ¿el cargo de una clase se devenga con la asistencia, con la
inscripción, o con la venta de un pack? ¿Y el no-show cobra?** Las tres respuestas cambian 08.06 y
08.07.

Consecuencia de segundo orden, declarada: el plan lista 07.01 y 07.02 como dependencias **porque**
la etapa devengaría. Al no devengar, quedan sin usar.

## 2. Las tres decisiones de modelo que el resto hereda

1. **La asistencia es una fila propia, no un estado de la inscripción.** Un estado no tiene actor ni
   admite corrección trazable.
2. **Se corrige pisando el valor y apendeando el evento** — la forma de `turno_evento`, **no** las
   versiones de 04.02. El motivo es del dominio: una asistencia es **un hecho con un solo valor
   vigente**, no un documento.
3. **El cupo no se mueve.** La única excepción es el ingreso sin inscripción, que **crea una
   inscripción de verdad** con el mismo `UPDATE` condicional de 08.02, en vez de abrir un segundo
   camino por el que alguien ocupe un lugar sin recibo.

## 3. La desviación de baja lógica, declarada

`asistencia_actividad` **no lleva `deleted_at`**, a diferencia de `V30`, `V58` y `V60`. El motivo no
es comodidad: con baja lógica el unique necesitaría `deleted_key`, y entonces **dos filas vivas
podrían coexistir para la misma persona en la misma clase** — que es justo lo que la referencia
económica única existe para impedir. **Se corrige, no se da de baja.**

## 4. Cero aristas nuevas, con control negativo

Sonda positiva → `Tests run: 5, Failures: 0`. **Control negativo**: agregó `activity.spi` más un
importador en `notification.infrastructure` → `Cycle detected: Slice activity -> … BUILD FAILURE`.
Las dos corridas pegadas en el challenge; las sondas borradas antes de escribir dominio.

## 5. El riesgo propio del lote, y cómo se cierra

El lote recibe ids **del cliente**, así que cada ítem pasa por `findByIdInScope(org, clase, id)`. Sin
eso sería un **enumerador cross-tenant con resultados parciales**, explicando cuál id existe y cuál
no — exactamente lo que ADR-0018 prohíbe.

## 6. Lo que NO se verificó

Sin ITs. Queda sin ejercer contra motor: **el unique que sostiene toda la idempotencia**, el cierre
concurrente, el walk-in sobre la última vacante, y **la independencia transaccional del lote** —cuyo
modo de falla es el `UnexpectedRollbackException` que este repositorio ya pagó cuatro veces—.
Escenarios **54–59** de `docs/tests-diferidos.md`. **Nada se simuló con mocks.**

`V61` nunca aplicada contra un motor — **ni `V58` ni `V60` tampoco**. Contrato en drift, cobertura
sin medir.

> **Dato de medición que conviene no volver a descubrir:** sumar los `.txt` de surefire da un número
> distinto del real, porque los tests parametrizados se reportan distinto ahí. **El bueno es el
> `Results:` de Maven.**

---

# Registro de avance — AKINE-07.07 (Hardening de seguridad y privacidad del MVP) · backend

> **NO ESTÁ CERRADA.** Misma causa: Docker no arranca. Escrito el **20/09/2026** por el orquestador.
> **La parte de accesibilidad es frontend y se hizo aparte** — ver la rama `akine-a11y-contraste`.

**Rama `akine-07.07-hardening`**, cinco commits, sale de `akine-f7-integracion`.
Baseline confirmado por el agente: **2.265**. Final **2.315 unitarias, 0 fallos** (+50), ArchUnit
5/5, convenciones 12/12, **sin tocar una regla ni un umbral**. **Ninguna migración**: `V62` sigue
libre. Contrato declarado en **`0.44.0`**.

## 1. El hallazgo que el documento no listaba, y es el peor

**`auditoria:read-clinica` estaba en el catálogo, en la matriz §6 y asignado, y NUNCA se evaluó —
ni una línea en todo el repositorio.** `AuditQueryService.alcanceDeLectura` gateaba las tres
consultas con **`auditoria:read` a secas**. El javadoc de `AuditEventRepository` dice que los
permisos de lectura *"los agrega 01.03"*: esa mitad nunca se agregó.

**Por qué duele.** `clinical` se cuida de no volcar contenido clínico en `audit_event` —el resumen
de la historia no va en los detalles, y el nombre del adjunto tampoco, porque
`rmn-rodilla-rotura-menisco.pdf` **es** el diagnóstico—. Pero hay un campo que sí viaja **y tiene que
viajar**, porque DP-03 lo exige como prueba de que el acceso estuvo motivado: la **justificación
declarada**, en `reason`, **texto libre que escribe un profesional**, en cada una de las ~30 filas
que el módulo escribe.

**Consecuencia viva:** el `ORG_ADMIN` tiene `auditoria:read` con alcance `ORGANIZACION` por
asignación base y **no** tiene `hc:read` —la matriz §2 le dice "No por defecto"—. Por la pantalla de
auditoría leía, de **cada paciente de su organización**: `personaId`, `historiaClinicaId`, la vía de
acceso, la categoría del adjunto descargado y **el motivo en texto libre**. El `CONSULTORIO_ADMIN`
lo mismo, acotado a su sede.

**Se redacta al leer, no al escribir.** La justificación tiene que persistirse; lo que se controla es
quién la ve. Se tapan `reason` y `details` y nada más: **el rastro de acceso queda entero, porque es
justamente lo que sirve para detectar un acceso indebido**. `VocabularioClinicoDeAuditoria` vive en
`platform.spi.audit` porque `organization` no puede importar `clinical` sin cerrar un ciclo.

> **Limitación declarada en voz alta: es una lista de prefijos, y una lista se olvida.** Un evento
> clínico nuevo cuyo prefijo no esté declarado **se lee sin redactar y nada falla**. Cerrarlo de
> verdad exige marcar el evento en origen —campo nuevo en `AuditEntry` y tocar a todos sus
> emisores—. El test enumera el vocabulario conocido para que el olvido tenga dónde verse.

## 2. `encounter` no auditaba nada

`grep -rn "Audit" src/main/java/com/akine/encounter` devolvía **cero**. Y es el único módulo que
escribe evolución clínica y la devuelve entera por `GET /sesiones/{id}`. **04.01 fijó que toda
lectura clínica se audita**; `clinical` lo cumple en sus trece lecturas y **la Sesión quedó afuera de
la regla que estableció la etapa inmediatamente anterior**.

Se auditan inicio, lectura y cierre. **El autosave no, a propósito**: una fila por guardado
enterraría los tres eventos que importan. `ver()` **deja de ser `readOnly`** por la misma razón que
las lecturas de `clinical` tampoco lo son — con `readOnly` el flush queda en MANUAL y **la fila no
llega nunca**. La sesión inalcanzable **no** deja rastro, y tiene test: auditarla construiría dentro
de `audit_event` el padrón de existencia que el 404 uniforme existe para no entregar.

## 3. El `securityScheme`, cerrado

`bearerAuth` como requisito **global** —el equivalente contractual del `anyRequest().authenticated()`
de la cadena— y `refreshCookie` en `/auth/refresh` y `/auth/logout`. Las públicas llevan
**`security: []` y no la ausencia del campo**: omitirlo significa "hereda el default", y el cliente
generado **mandaría token al login**.

La lista sale de **las mismas constantes de `SecurityConfig`** que arman la cadena: no hay una
segunda lista que mantener sincronizada. Y hay un test de que el `security: []` **sobrevive a la
serialización** — si el mapper lo descartara por venir vacío, el YAML se vería correcto y el síntoma
sería un 401 en el primer request.

## 4. Tres arreglos más, cada uno con su razón

- **`billing`: dos `UPDATE` nativos de `obligacion` sin tenant.** Eran las únicas dos escrituras de
  dinero del módulo sin discriminante. No explotable hoy —los dos llamadores resuelven con
  `findByIdInScope`—, pero la firma `(obligacionId, importe)` **no le da al próximo llamador ninguna
  forma de equivocarse a favor**. Lo delata el propio repo: `EgresoRepositoryPort` ya lo lleva, y el
  `descontarSaldo` de autorizaciones **cita a éste como precedente**. Faltaba el origen del patrón.
- **`TenantContextFilter` era el último que comparaba la URI cruda.** Único `getRequestURI()` que
  quedaba; los otros tres ya habían migrado a `RequestPaths`, cuyo javadoc documenta el bug medido
  (`%6cogin` saltea el filtro). Acá la dirección de fallo era la segura, pero **la garantía dependía
  de `StrictHttpFirewall` y no del filtro**. Arregla además un **500 latente**: `URI.create` sobre la
  ruta cruda lanzaba **dentro del filtro, fuera del advice**, convirtiendo un 403/404 en 500.
- **`reporting`: el CSV no protegía contra inyección de fórmulas.** Hoy no es explotable **por
  accidente**, porque ninguna celda es texto libre. Pero `FilaDeReporte` no valida nada y el SPI
  invita a que cada módulo aporte su sección: **la primera fila con un nombre de financiador o un
  motivo lo convierte en vector.**

## 5. Los cuatro huecos conocidos, verificados contra el código

1. **`securityScheme`** — confirmado y **cerrado**.
2. **`paciente:read` / alcance `OWN`** — **confirmado, y el diagnóstico del documento era exacto.**
   `PermissionScope.OWN` está declarado y tiene **cero usos** en todo `src/main/java`. No hay vínculo
   `cuenta ↔ persona`: el `accountId` de `PerfilPaciente` es el **del actor que creó el perfil**, no
   el del titular. **No se inventó ninguna implementación.** Destino F8.
3. **`concurrent-modification` vs `conflict`** — **no se tomó la decisión**, se dejó el censo (§6).
4. **`PLATFORM_ADMIN` inalcanzable** — **confirmado línea por línea.** `PasswordResetService` corta
   en `!cuenta.puedeAutenticarse()`, que es `estado.permiteLogin() && active && passwordHash != null`;
   una cuenta sembrada por `V15` sin credencial tiene `passwordHash == null` → return silencioso con
   202.

## 6. El censo de `concurrent-modification`, para que la decisión lleve cinco minutos

- **16 ocurrencias** del string en el YAML: 10 en `paths`, 5 en descripciones de `version`, 1 en el
  enum.
- **Ningún campo estructurado declara el `problemType`**: las 15 no-enum son **prosa dentro de
  `description`**. **La promesa es texto y ninguna herramienta la valida.**
- Lo emite **un solo handler**, que atrapa `ObjectOptimisticLockingFailureException` —la
  **subclase**, la que lanza Hibernate—. `conflict` sale del handler global, que atrapa
  `OptimisticLockingFailureException` —la **clase padre**—.
- **Los 14 advices son `@RestControllerAdvice` sin selectores**, así que **el módulo es
  irrelevante**: el único discriminante es qué clase de excepción llega. Por eso `updateConsultorio`,
  que vive en `organization`, *también* miente.

| Módulo | Prometen | Mienten |
|---|---|---|
| `resource` | 3 | **3** |
| `encounter` | 2 | **2** |
| `organization` | 2 | **1** |
| **Total** | **7** | **6** |

**Dos matices que cambian la decisión y no salen de leer el contrato:**

- Cada una de esas seis tiene un **segundo camino latente**: si dos requests mandan la versión
  *correcta* y corren en paralelo, ambos pasan el `if` y el conflicto lo detecta Hibernate en el
  flush → `concurrent-modification`. **La misma operación puede devolver los dos tipos según el
  timing.**
- **La "corrección de M05" fue de la documentación, no del código.**

## 7. Ocho cosas elevadas y no decididas

1. Unificar `concurrent-modification`/`conflict` — censo arriba.
2. Toma de posesión del `PLATFORM_ADMIN` de `V15` — toca ADR-0018 y el flujo de invitación.
3. `OWN` / `paciente:read` — destino F8.
4. **La justificación declarada en `encounter`.** `clinical` la exige por `X-Justificacion-Acceso`;
   `encounter` no la pide, así que **sus filas de auditoría salen con `reason` nulo**. Sumarla es un
   **header obligatorio nuevo en cinco operaciones que el frontend ya consume**: cambio de contrato,
   no se hizo en silencio.
5. **`reporte:read` no se le otorga a ningún rol ni es otorgable como grant.** `ReporteService` lo
   exige y **nadie puede satisfacerlo**: **los tres endpoints de 07.06 son inalcanzables**. Falla
   cerrado, así que no es fuga, pero **es un módulo entero muerto**. No se cableó porque otorgar
   acceso no es hardening **y** porque la celda "Limitado" del `PROFESIONAL` no tiene mecanismo:
   `ConsultaDeReporte` no lleva actor y **ninguna de las 20 queries filtra por autor**. Dárselo sin
   resolver eso le mostraría los agregados de toda la sede, **incluida la sección `casos`**.
6. **`FinanciadorPagoService.cuentaCorriente` contradice su propio contrato y trunca.** El javadoc
   dice en negrita *"cruza sedes a propósito"* y el código pasa `consultorioId`, que el repositorio
   usa para filtrar. Y `200, 0` hardcodeado: **a partir del lote 201 los cuatro totales son
   silenciosamente incorrectos**. Evidencia de que fue descuido: la agregación org-wide sin límite
   **está escrita justo para esto y no la llama nadie**. Es un defecto de corrección de 07.05, no de
   seguridad.
7. **`caso:create`** existe en el enum y en la matriz y **no lo evalúa nadie**: la apertura de caso se
   autoriza con `hc:write`. O se cablea, o se enmienda la matriz.
8. **`sumarAnuladoEnElReporte`** es la única de las 20 queries de reporte **sin
   `AND o.deletedAt IS NULL`**; sus dos hermanas lo llevan. No es fuga: el indicador **cuenta de
   más**.

## 8. Los `PENDIENTE(F1)` del `pom.xml`: no se encendieron, y éstos son los números

Las reglas `PACKAGE` al 0,90 para `identity`, `organization`, `clinical` y `billing` **no se pueden
encender**. Medido con unitarias solamente —o sea un **piso**, porque los ~257 ITs no corrieron—:

| Módulo crítico | instrucción | línea | rama |
|---|---|---|---|
| `identity` | 84,17 % | 83,38 % | 69,50 % |
| `organization` | 77,25 % | 77,94 % | 72,07 % |
| `clinical` | 72,19 % | 71,88 % | 64,31 % |
| `billing` | **27,36 %** | **29,71 %** | 32,97 % |

Ninguno llega a 90 ni de lejos; `billing` necesitaría que los ITs aportaran sesenta puntos. **Y el
número honesto no se puede tener sin Docker**, porque `jacoco:check` corre en `verify`. **No se bajó
ni se relajó nada.**

## 9. Lo que NO se verificó

Sin ITs. Seis escenarios, **60–65**, en `docs/tests-diferidos.md`. Los dos que más importan son el
**60** y el **61**: los unitarios que los acompañan prueban que el servicio **llamó** al puerto, no
que la fila quedó en la tabla. **El 61 es el más incómodo** — si alguien vuelve a marcar `ver()` como
`readOnly`, la fila de auditoría **no se escribe nunca y el test unitario sigue pasando**.

El contrato queda en **drift a propósito**: se subió `0.44.0` en los tres lugares y **no se
escribieron los `securitySchemes` a mano en el YAML**, porque eso sería taparlo. Hay que correr
**`./mvnw verify -Dakine.contract.update=true` desde esta rama** en cuanto Docker levante.

---

# Registro de cierre — G2·A-2 (ADR que ratifica a `encounter` como dueño de la Sesión)

Cerrada el **2026-10-03**. Rama `symphony/akine-g2/A6`. Registro conforme a §10.5. Paquete sólo documental.

## 1. Resumen del incremento y comportamiento observable

Sin cambio de comportamiento. El desvío «la Sesión vive en `encounter`, no en `clinical` como dice §2.4» estaba documentado sólo en el registro de 06.01; ahora tiene ADR y `AGENT.md` §4 lo refleja.

## 2. Archivos creados

- `docs/adr/0024-encounter-es-duenio-de-la-sesion.md`

## 3. Archivos modificados

- `docs/adr/README.md` (fila 0024 del índice).
- `AGENT.md` §4 (lista de módulos: `encounter` agregado; `clinical` deja de listar la Sesión).
- `docs/fases/F0-F1-fundacion-y-plataforma.md` (ítem tachado y fila de la tabla de desvíos corregida).
- Este plan (este registro).

## 4. Migraciones, backfills o cambios de datos

Ninguno.

## 5. Endpoints, contratos, eventos o integraciones

Ninguno. `akine.contract.version` no cambia.

## 6. Pruebas

Sin pruebas nuevas: no hay código. Criterios del nodo (`sym check A6`): existe `docs/adr/0024-*encounter*.md`, `AGENT.md` contiene «encounter», el ítem de F0-F1 está tachado y el índice contiene «0024».

## 7. Decisiones técnicas y alternativas descartadas

`encounter` queda como dueño de la Sesión, sus mediciones, enmiendas y tratamientos realizados. Descartadas: mover el código a `clinical`, dejar el desvío sin ADR y dividir `encounter`. El ADR describe lo que el código ya hace (verificado en `encounter/`, `encounter.spi.CierreDeSesionObserver`/`SesionCerrada`, `EncounterAtencionProbe`, `ConsumoDeAutorizacionEnCierre`) y no propone cambios.

## 8. Problemas, riesgos o bloqueos

§2.4 del plan sigue diciendo «Sesión» en la fila `clinical` (texto histórico; no se reescribe). El ADR lo declara como consecuencia negativa.

## 9. Deuda técnica

Ninguna nueva. El `CLAUDE.md` del repo no lista módulos, así que no requiere actualización.

## 10. Contexto para la etapa siguiente

- C-3 reemplaza `clinical.infrastructure.RelacionAsistencialSinAgenda` con una implementación en `encounter.infrastructure` (patrón de `EncounterAtencionProbe`); el ADR-0024 fija que esa dependencia va `encounter → clinical.spi` y nunca al revés.
- Una arista `clinical → encounter` o `person → encounter` cierra ciclos: verificarlo con ArchUnit, no de memoria.

---

# Registro de cierre — G2·C-1 (CHECK de lateralidad admite NO_APLICA)

Cerrada el **2026-10-03**. Registro conforme a §10.5.

## 1. Resumen del incremento y comportamiento observable

Registrar un tratamiento con `lateralidad = NO_APLICA` (zona central: lumbar, cervical) deja de violar `ck_tratamiento_lateralidad`. El CHECK admite ahora exactamente los valores del enum `encounter.domain.Lateralidad` (`IZQUIERDA`, `DERECHA`, `BILATERAL`, `NO_APLICA`) y `NULL`.

## 2. Archivos creados

- `src/main/resources/db/migration/V65__c1_tratamiento_lateralidad_no_aplica.sql`
- `src/test/java/com/akine/encounter/infrastructure/TratamientoLateralidadMigrationIT.java`

## 3. Archivos modificados

- `docs/fases/F6-atencion-clinica.md` (ítem "Migración que corrija el CHECK de lateralidad" tachado).
- `docs/producto/AKINE_IMPLEMENTATION_PLAN.md` (este registro).

## 4. Migraciones

`V65`: `DROP CHECK` + `ADD CONSTRAINT ck_tratamiento_lateralidad` sobre `tratamiento_realizado`, mismo nombre. Sin backfill: ampliar el CHECK no invalida ninguna fila existente. `V55` no se tocó.

## 5. Endpoints, contratos, eventos

Ninguno. DTOs, enum y servicios de `encounter` ya aceptaban `NO_APLICA`; el contrato OpenAPI no cambia.

## 6. Pruebas

`TratamientoLateralidadMigrationIT` (5 tests): admite todos los valores del enum y `NULL`; `NO_APLICA` se acepta; un valor fuera del enum sigue rechazado; `NO_APLICA` sin zona sigue rechazado por `ck_tratamiento_lateralidad_con_zona`; el constraint conserva su nombre. Rojo primero: sin `V65` fallaron 3 de 5 (`Check constraint 'ck_tratamiento_lateralidad' is violated`). Con `V65`, ver los criterios AC-1 a AC-3 de la partichela.

## 7. Decisiones y alternativas descartadas

- Recrear el CHECK en una migración nueva en vez de editar `V55` (una migración aplicada es historia).
- El IT inserta directo con `FOREIGN_KEY_CHECKS = 0` en la conexión del test, para ejercer el CHECK sin sembrar sesión, práctica y espacio. Descartado: sembrar todo el grafo, que es el trabajo de C-7 (A5).

## 8. Problemas, riesgos o bloqueos

Ninguno.

## 9. Deuda técnica

Los ITs de tratamientos a través del servicio (escenarios 39–43 de `docs/tests-diferidos.md`) siguen pendientes: C-7.

## 10. Contexto para la etapa siguiente

C-7 puede registrar tratamientos con `NO_APLICA` desde el servicio y depende de `V65`. `V67` queda vacía por decisión de C-5; la siguiente migración reservada del grupo es `V71`.

---

# Registro de cierre — G2·C-5 (04.04: un plan de tratamiento no se activa sin ítems)

Registro conforme a §10.5. Paquete C-5 de la obra `akine-g2`, recortado por decisión del usuario: el
"unique de caso activo" **no se hace**.

## 1. Resumen del incremento y comportamiento observable

Activar un plan en `BORRADOR` cuya versión vigente tiene **0 ítems** responde **409** con `type`
`PLAN_TRANSICION_INVALIDA`, título "El plan de tratamiento no se puede activar sin items" y la
propiedad `planId`. Activar un plan ya `ACTIVO` sigue siendo 200 (la idempotencia se evalúa antes).
El rechazo ocurre **antes de finalizar el plan vigente** del caso: un plan vacío no se lleva puesto
el de otro.

## 2. Archivos creados

- `clinical/domain/exception/PlanSinItemsException.java` (`RuntimeException`, ctor `(Long planId)`, `getPlanId()`).

## 3. Archivos modificados

- `clinical/application/PlanTratamientoService.java` (`activar`).
- `clinical/api/ClinicalProblemHandler.java` (mapeo 409).
- `docs/fases/F4-dominio-clinico.md` (tres líneas: dos ítems y la fila de desvíos).
- Este plan.

## 4. Migraciones, backfills o cambios de datos

**Ninguno.** `V67` (reservada al paquete) **queda vacía**: no existe `V67__*.sql`.

## 5. Endpoints, contratos, eventos o integraciones

Ninguno nuevo. El 409 de activar ya estaba en el contrato OpenAPI; no cambia
`akine.contract.version`.

## 6. Pruebas

Este nodo no escribe tests (los hace A4.T1, que depende de `PlanSinItemsException`). Criterios del
nodo corridos: compilación, `ModuleArchitectureTest`, `CodingConventionsTest` y los chequeos
documentales (04.04 y 04.03 tachados, ausencia de `V67__*`).

## 7. Decisiones técnicas y alternativas descartadas

- **Sin unique de caso activo:** RN-M10-002 dice que *puede haber más de un caso activo si
  clínicamente corresponde*; el duplicado razonable se resuelve con 409 + candidatos y lo cubre
  `CasoClinicoConcurrenteIT`. Ver V47 y `docs/diseno/AKINE-04.03-challenge.md`.
- **Sin otro estado de `EstadoCaso`:** M10 solo menciona casos activos y cerrados (RF-M10-002) y el
  estado CERRADO (RF-M10-005). No se inventó ninguno.
- **Chequeo en el servicio y no en la entidad:** `PlanTratamiento.activar` no conoce los ítems (viven
  en otra tabla); pasarlos por parámetro obligaba a tocar a todos los llamadores sin ganar nada.
- **`ProblemType` existente** `PLAN_TRANSICION_INVALIDA`: mismo conflicto, misma acción del cliente.
  Uno nuevo habría tocado `platform/spi/problem`.

## 8. Problemas, riesgos o bloqueos

Ninguno bloqueante.

## 9. Deuda técnica diferida

- Los tests existentes que activan planes sin ítems fallarán hasta que A4.T1 los corrija.
- Este nodo no corrió ITs.

## 10. Contexto para la etapa siguiente

A4.T1 escribe los tests contra `PlanSinItemsException(Long planId)`; esa firma no cambia. Queda
pendiente para el usuario marcar `V67` como "vacía" en la tabla §6 de `01-trabajo-en-paralelo.md`
(rama `docs/trabajo-en-paralelo`).

---

# Registro de cierre — G2 · C-2 (API de la historia clínica) · backend

1. **Incremento.** La historia clínica tiene puerta REST. `HistoriaClinicaController` expone, bajo `/api/v1/historias-clinicas/por-persona/{personaId}`, obtener-o-abrir idempotente, lectura, actualización del resumen y antecedentes (registrar, listar, baja). No se tocó la lógica de dominio: `HistoriaClinicaService` y `AntecedenteClinicoService` ya la tenían y eran código muerto para la API.
2. **Archivos creados.** `clinical/api/HistoriaClinicaController.java`; DTOs en `clinical/api/dto`: `HistoriaClinicaResponse`, `AntecedenteResponse`, `ActualizarResumenRequest`, `RegistrarAntecedenteRequest`, `BajaDeAntecedenteRequest`.
3. **Archivos modificados.** `pom.xml` y `application.yml` (`akine.contract.version` 0.44.0 → 0.45.0), `openapi/akine-api.yaml` (regenerado, no editado a mano), `docs/fases/F4-dominio-clinico.md` (ítem tachado).
4. **Migraciones / datos.** Ninguno.
5. **Endpoints.** `PUT /por-persona/{personaId}` (200, idempotente), `GET /por-persona/{personaId}` (200 / 404), `PUT .../resumen` (`{texto, expectedVersion}`), `GET .../antecedentes?tipo=&soloVigentes=`, `POST .../antecedentes` (201), `POST .../antecedentes/{antecedenteId}/baja`. Cabecera opcional `AccesoClinicoHeaders.JUSTIFICACION`. Contrato 0.45.0. Errores mapeados por `ClinicalProblemHandler` sin cambios.
6. **Pruebas.** Escritas por A2.T1 (nodo tester, sin ver la implementacion): `HistoriaClinicaControllerTest` (slice, 10 tests) y `HistoriaClinicaApiIT` (Testcontainers, 7 tests, incluye 8 PUT concurrentes sobre la misma persona). `HistoriaClinicaServiceTest` actualizado (12 tests). Comando: `.\mvnw.cmd -q -Djacoco.skip=true -Dtest=HistoriaClinicaControllerTest,HistoriaClinicaServiceTest,ModuleArchitectureTest,CodingConventionsTest -Dit.test=HistoriaClinicaApiIT,OpenApiContractIT verify` -> exit 0. El IT concurrente encontro un bug real: la relectura de la ganadora corria en la transaccion de negocio y, con REPEATABLE READ, no veia la fila ya commiteada, asi que la apertura concurrente salia como 409; se corrigio con `HistoriaClinicaEscrituraAparte.releerVigente` en `REQUIRES_NEW` (afecta tambien a `asegurar`, que usan otros modulos).
7. **Decisiones.** Se direcciona por `personaId` (no por `historiaClinicaId`) porque los servicios ya trabajan por persona y no choca con `/{historiaClinicaId}/...`. `PUT` para obtener-o-abrir por ser idempotente. `ActualizarResumenRequest.texto` limitado a 2000 = largo de la columna.
8. **Problemas.** `OpenApiContractIT` exige que `application.yml` y `pom.xml` coincidan en la versión del contrato; `application.yml` estaba fuera del territorio y D lo habilitó (sólo esa línea).
9. **Deuda diferida.** Lectura limitada para `ADMINISTRATIVO` (ítem aparte de F4); relación asistencial real (C-3); consumo desde el frontend (Paciente 360).
10. **Para la etapa siguiente.** El front (G3) puede consumir las rutas `/por-persona/{personaId}` desde el contrato 0.45.0. Otros paquetes que suban la versión del contrato van a chocar en `pom.xml`/`application.yml`/YAML: resolver al integrar.

---

# Registro de cierre — G2 · C-3 (`RelacionAsistencialProbe` real) · backend

Cerrado el **2026-10-03** en la rama `symphony/akine-g2/A3`. Registro conforme a §10.5.

## 1. Resumen del incremento y comportamiento observable

El stub `RelacionAsistencialSinAgenda` (siempre `false`: **toda** lectura clínica exigía justificación) se reemplazó por `EncounterRelacionAsistencialProbe`. Ahora hay relación asistencial si el actor, con alguno de sus **vínculos habilitados** que cubra la sede, tiene una sesión no borrada de esa persona en esa sede (la atendió la membership o la inició la cuenta) o un turno vivo con ella (`RESERVADO`, `CONFIRMADO`, `EN_ESPERA`; `CANCELADO` y `AUSENTE` no cuentan). Queda **un solo bean** de `RelacionAsistencialProbe`. Efecto visible: un profesional con atención o agenda con la persona lee su historia sin declarar justificación; el resto sigue exigiéndola.

## 2. Archivos creados

- `src/main/java/com/akine/encounter/infrastructure/EncounterRelacionAsistencialProbe.java`
- `src/test/java/com/akine/encounter/infrastructure/EncounterRelacionAsistencialProbeTest.java`
- `src/test/java/com/akine/encounter/RelacionAsistencialIT.java`

## 3. Archivos modificados

- `scheduling/spi/TurnoDirectory.java`: método nuevo y aditivo `existeTurnoVivoDeProfesionalConPersona(organizationId, consultorioId, profesionalMembershipId, personaId)`.
- `scheduling/infrastructure/SchedulingTurnoDirectory.java` y `TurnoRepository.java`: su implementación y la query (filtra por organización, sede, profesional, persona, `deletedAt IS NULL` y estados vivos).
- `encounter/domain/port/SesionRepositoryPort.java` y `encounter/infrastructure/SesionRepository.java`: `existeSesionDelActor(organizationId, consultorioId, historiaClinicaId, profesionalMembershipId, actorAccountId)`.
- `docs/fases/F4-dominio-clinico.md`: ítem «`RelacionAsistencialProbe` real» tachado (sólo esa línea).
- **Borrado:** `clinical/infrastructure/RelacionAsistencialSinAgenda.java`.

## 4. Migraciones, backfills o cambios de datos

Ninguno. Las queries usan columnas e índices existentes; no se agregó migración.

## 5. Endpoints, contratos, eventos o integraciones

Ningún endpoint ni evento. `clinical/spi/RelacionAsistencialProbe` **no cambió**. Único cambio de contrato entre módulos: el método nuevo en `scheduling.spi.TurnoDirectory` (aditivo; `scheduling` es del grupo G5, no se tocó ninguna firma existente). `encounter` ahora consume además `organization.spi.ConsultorioMembershipDirectory` y `clinical.spi.HistoriaClinicaDirectory`, ya permitidos por `ModuleArchitectureTest`.

## 6. Pruebas y resultados

- `EncounterRelacionAsistencialProbeTest`: 17 unitarios (Mockito sobre los puertos, con `ConsultorioMembershipSnapshot` reales). Cubre vínculos que no cuentan, historia ausente, sesión/turno, alcance de sede y de organización, y el cortocircuito.
- `RelacionAsistencialIT`: 22 casos contra base real (Testcontainers): bean único; los cinco estados de turno; otro profesional, otra sede, otra organización; sesión por cuenta o por membership, de otro actor, de otra sede, borrada; profesional de **una sola sede**, de alcance organización, y membership suspendida, revocada, vencida o dada de baja.
- Criterios de A3, todos en 0 el 2026-10-03 con `sym check A3`: AC-1 (`.\mvnw.cmd -q -Djacoco.skip=true -Dtest=*RelacionAsistencial*Test,ModuleArchitectureTest,CodingConventionsTest -Dit.test=RelacionAsistencialIT,TimelineIT verify`), AC-2 (F4 tachado), AC-3 (stub borrado).
- Verificación adversaria: sacar el filtro `validAt` del probe hace fallar 5 de los 17 unitarios (suspendido, revocado, vencido, futuro, baja lógica).
- **No se corrió la suite completa** (la corre el director). `TimelineIT` pasa con el comportamiento nuevo.

## 7. Decisiones técnicas y alternativas descartadas

- **Actor por vínculos, no por `AccountContextDirectory.membership`.** Ese método devuelve a propósito sólo la membership de alcance organización (evita escalar permisos): un profesional con membership de una sola sede nunca habría tenido relación. Se usa `ConsultorioMembershipDirectory.findByAccount` + `validAt` + `cubreConsultorio`.
- **Sólo cuenta un vínculo habilitado** (decisión del usuario): suspendido, revocado, vencido o dado de baja no da relación, ni siquiera por sesiones ya atendidas. Se prefirió exigir justificación antes que abrir una historia a quien ya no es profesional activo.
- **`@Lazy` sobre `HistoriaClinicaDirectory`** en el constructor, para cortar el ciclo `probe → HistoriaClinicaDirectory → HistoriaClinicaService → AdjuntoClinicoService → probe`. Descartado `ObjectProvider`: cambiaba la firma del constructor y rompía los tests unitarios.
- Sesión del actor por `iniciadaPorCuentaId` **o** `profesionalMembershipId`; sesión consultada antes que turnos, con cortocircuito.
- Descartado `@Primary` / `@ConditionalOnMissingBean` (ver el javadoc del stub): el stub se borró.

## 8. Problemas, riesgos o bloqueos

- El primer diseño (A3.T2) usaba `AccountContextDirectory.membership` y no levantaba el contexto de Spring (ciclo de beans). Lo detectó el IT de A3.T1; el criterio de T2 no arrancaba el contexto, falla de especificación del atril, no del modelo. Se corrigió con A3.T3 (`@Lazy`) y A3.T4 (vínculos); el IT de T1 pasó a usar membership de sede y se rechazó su primera iteración por `spec`.
- `sym check` ejecutaba `mvnw.cmd` sin `.\` y fallaba por el PATH de cmd; D corrigió los criterios.
- `sym check A3` marca como «fuera del territorio» 27 archivos que son de A1, A2, A4 y A6 ya integrados en la rama de D: se compara contra una base que avanzó. El diff propio de A3 contra su base (`2cdf28f`) son 10 archivos, todos en territorio.

## 9. Deuda técnica

- La consulta de sesiones y turnos se hace por vínculo en un bucle (normalmente uno o dos). Si un actor llegara a tener muchos vínculos en la organización habría que agruparlas; sin etapa asignada, medir antes.
- La relación se evalúa en cada lectura clínica sin caché. Etapa destino: la de endurecimiento/rendimiento del dominio clínico (F4/F8), si las mediciones lo piden.

## 10. Contexto para la etapa siguiente

- **Cambio de comportamiento global:** al dejar de responder siempre `false`, cualquier IT de `clinical` que asumiera «toda lectura exige justificación» puede cambiar. Con A3 sólo se verificaron `TimelineIT` y los propios; la suite completa la corre el director y puede destapar otros.
- La relación se evalúa por **vínculo de la cuenta en la organización**, no por la membership del contexto del request; un actor que opera con un vínculo distinto sigue contando si alguno de los suyos cubre la sede.
- Cuando exista el módulo de agenda completo (F5), `TurnoDirectory#existeTurnoVivoDeProfesionalConPersona` es el punto de extensión; no hay que tocar el probe.
- No se actualizó `openapi/akine-api.yaml`: no hay contrato HTTP nuevo.

# Registro de cierre — G2 · C-7 (fix) — registrar tratamiento avanza la versión de la sesión

Cerrado el **2026-10-03** en la rama `symphony/akine-g2/A7`. Registro conforme a §10.5.

## 1. Resumen del incremento y comportamiento observable

Registrar, editar o quitar un tratamiento realizado ahora avanza `sesion.version` **en la base, una sola vez**. Antes la base quedaba en la versión leída mientras la vista informaba `leida+1`: el cliente no tenía control optimista real y dos altas con la misma versión entraban las dos. Ahora una versión vieja es conflicto (`OptimisticLockingFailureException`, el que ya mapea el handler) y **no inserta**, y de dos altas concurrentes con la misma versión entra una sola. La API pública de `TratamientoService` y `TratamientoView` no cambia.

## 2. Archivos creados

Ninguno.

## 3. Archivos modificados

- `encounter/application/TratamientoService.java`: `sesionParaEscribir` lee con `findByIdInScope` y avanza la versión con `avanzarVersion(..., expectedVersion)`; si afecta cero filas, lanza el conflicto. Se actualizaron la cabecera (punto 2) y el comentario del método.
- `encounter/domain/port/SesionRepositoryPort.java` y `encounter/infrastructure/SesionRepository.java`: método nuevo `avanzarVersion` (`UPDATE Sesion SET version = version + 1 WHERE ... AND version = :versionEsperada`, `@Modifying(flushAutomatically = true)`). `findWithLockByIdInScope` **no se tocó**: ya no lo usa ningún código de producción, pero `MedicionServiceTest` (fuera de este territorio) lo stubbea y borrarlo rompería su compilación.
- `encounter/application/TratamientoServiceTest.java`: las colaboraciones mockeadas pasan de `findWithLockByIdInScope` + `save` a `findByIdInScope` + `avanzarVersion`; el test del `save` se reemplazó por dos (llama a `avanzarVersion` con la versión leída; cero filas afectadas es conflicto sin escribir).

## 4. Migraciones, backfills o cambios de datos

Ninguno.

## 5. Endpoints, contratos, eventos o integraciones

Ninguno. No cambia `openapi/akine-api.yaml` ni la versión del contrato.

## 6. Pruebas y resultados

- AC-1 en 0: `TratamientoRealizadoIT` (de la rama de A5, prestado con `it-prestado.js`, no copiado), 47 de 47. Los tres que estaban rojos pasan: `la_version_avanza_una_sola_vez`, `dos_altas_concurrentes_una_sola_entra`, `version_vieja_es_conflicto_y_no_inserta`.
- AC-2 en 0: `TratamientoServiceTest`, `SesionServiceTest`, `MedicionServiceTest`, `ModuleArchitectureTest`, `CodingConventionsTest`, `CierreConcurrenteIT`, `CierreConDosNumeradoresIT`.
- **No se corrió la suite completa** (la corre el director).

## 7. Decisiones técnicas y alternativas descartadas

- **Diagnóstico con el SQL de Hibernate** (`-Dspring.jpa.show-sql=true`): durante `registrar` no sale ningún `UPDATE sesion` al commitear. El `OPTIMISTIC_FORCE_INCREMENT` aplicado a una lectura por consulta no incrementa la versión, y `save()` de una entidad gestionada sin cambios no la ensucia.
- **Descartada la hipótesis de que el `@Query` JPQL era la causa:** se probó una consulta derivada con el mismo `@Lock` (el patrón de `CasoClinicoRepository`) y la base seguía en la versión leída.
- **Elegido un `UPDATE ... WHERE version = :esperada` propio** (en el repositorio, sin `EntityManager` en el servicio): da conflicto real cuando alguien se adelantó, toma el lock de la fila en el acto (serializa las altas concurrentes, también el `MAX(orden)+1`) y no ensucia la sesión, así que la versión avanza una vez. La entidad leída conserva la versión vieja y las vistas anuncian `expectedVersion + 1`.
- Descartado `EntityManager.lock(sesion, OPTIMISTIC_FORCE_INCREMENT)`: exigía inyectar el `EntityManager` en el servicio o crear un fragmento de repositorio fuera del territorio.

## 8. Problemas, riesgos o bloqueos

Ninguno bloqueante.

## 9. Deuda técnica

- **La causa parece general, no propia de `Sesion`:** el mismo patrón (`@Lock(OPTIMISTIC_FORCE_INCREMENT)` sobre una lectura y después `save` de la entidad sin cambios) según los comentarios, está en `clinical` (`CasoClinicoRepository`, `EntradaClinica*`, `PlanTratamiento*`, `CasoClinicoService`, `PlanTratamientoService`, `EntradaClinicaService`) y `billing/domain/Egreso`. No se verificó cada sitio ni se tocó ninguno. Decide D si abre otro nodo; conviene un IT que lea `version` de la base, como el escenario 41.
- `SesionRepositoryPort#findWithLockByIdInScope` quedó sin usos de producción; se puede borrar junto con el stub de `MedicionServiceTest` (que no es de este nodo).

## 10. Contexto para la etapa siguiente

- El PR a `main` lo abre D con título `[G2·C-7 fix] ...`. A5 puede tachar el ítem «ITs de tratamientos (escenarios 39–43…)» de F6 una vez integrado este nodo.
- Cualquier otra escritura que sólo toque tablas hijas de `sesion` puede usar `SesionRepositoryPort#avanzarVersion`.

# Registro de cierre — G2 · C-7 (ITs de tratamientos, mediciones y enmiendas)

Cerrado el **2026-10-03** en la rama `symphony/akine-g2/A5`. Registro conforme a §10.5.

## 1. Resumen del incremento y comportamiento observable

Tres ITs contra MySQL real cubren lo que `encounter` nunca había ejecutado: tratamientos realizados (escenarios 39–43 de 06.04), mediciones y enmiendas de sesión, con la auditoría `SESION_AMENDED` y el permiso de enmendar. Sin cambios en código productivo. Los ITs prueban el comportamiento **actual** (no versionan tratamientos ni mediciones en la enmienda: eso es C-6).

## 2. Archivos creados

- `src/test/java/com/akine/encounter/TratamientoRealizadoIT.java` (CHECK y unique de `V55` incl. `NO_APLICA`, `deleted_key`, orden no reutilizado, versión de sesión una sola vez, borrado de parámetros en el PUT, tenant 404).
- `src/test/java/com/akine/encounter/MedicionIT.java` (17 tests).
- `src/test/java/com/akine/encounter/EnmiendaDeSesionIT.java` (13 tests: v1/v2, ráfaga, concurrencia, `SESION_AMENDED` con y sin motivo, permiso 403, sesión ajena 409, tenant 404, sin cambios económicos).
- `src/test/java/com/akine/encounter/support/EncounterFixtures.java`.

## 3. Archivos modificados

- `docs/tests-diferidos.md`: filas 39–43 de 06.04 marcadas cubiertas.
- `docs/fases/F6-atencion-clinica.md`: tachados «ITs de tratamientos (escenarios 39–43…)» y «Test de permiso y de auditoría `SESION_AMENDED`».

## 4. Migraciones, backfills o cambios de datos

Ninguno. Depende de `V65` (C-1): un tratamiento con `NO_APLICA` entra.

## 5. Endpoints, contratos, eventos o integraciones

Ninguno. No cambia `openapi/akine-api.yaml`.

## 6. Pruebas y resultados

- AC-1 en 0 (`.\mvnw.cmd -q -Djacoco.skip=true -Dtest=ModuleArchitectureTest,CodingConventionsTest -Dit.test=TratamientoRealizadoIT,MedicionIT,EnmiendaDeSesionIT verify`): los tres ITs verdes, incluidos los tres del escenario 41 tras el arreglo de A7.
- AC-2 y AC-3 (`checks.js tachado` sobre F6) verdes.
- **No se corrió la suite completa** (la corre el director).

## 7. Decisiones técnicas y alternativas descartadas

- El primer corrido dejó rojo el escenario 41 (la versión de `sesion` no avanzaba al registrar). No se tocó producción ni se desactivó ningún test: se dejó el IT rojo y se bloqueó el nodo; D abrió A7, que lo arregló.
- Fixtures compartidos en `encounter/support` para no copiar la siembra entre los tres ITs.

## 8. Problemas, riesgos o bloqueos

Resuelto: el defecto del escenario 41 (ver punto 7).

## 9. Deuda técnica

- Los ITs no cubren versionado de tratamientos/mediciones en la enmienda ni el permiso reforzado (C-6, fuera de la obra).
- El patrón `OPTIMISTIC_FORCE_INCREMENT` sobre lecturas puede estar roto en otros módulos (ver deuda de A7); falta un IT por sitio que lea `version` de la base.

## 10. Contexto para la etapa siguiente

- El PR a `main` lo abre D con título `[G2·C-7] ITs de tratamientos, mediciones y enmiendas`; va después del fix de A7 (el IT del escenario 41 no pasa sin él).
- `EncounterFixtures` es el punto de partida para ITs futuros de `encounter`.
