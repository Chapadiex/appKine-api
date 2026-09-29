# AKINE — Especificación Integrada de Requerimientos Funcionales y No Funcionales 
**Proyecto:** AKINE  
**Tipo:** Especificación complementaria de requerimientos  
**Complementa:** `AKINE_Especificacion_Funcional_No_Funcional_Completa.md`  
**Uso:** análisis funcional, desarrollo, UX/UI, QA, arquitectura y agentes de IA.

---

# 1. Propósito

Este documento profundiza cada módulo de AKINE hasta un nivel utilizable para convertir la definición funcional en historias de usuario, tareas técnicas, contratos de API, reglas de backend y casos de prueba.

No reemplaza el documento maestro. Lo complementa.

Para cada módulo se documentan:

- objetivo;
- actores;
- dependencias;
- reglas de negocio;
- requerimientos funcionales detallados;
- precondiciones;
- flujo principal;
- alternativos;
- validaciones;
- postcondiciones;
- errores esperables;
- auditoría;
- criterios de aceptación;
- requerimientos no funcionales específicos;
- casos borde mínimos de QA.

---

# 2. Convenciones

- `RF-MXX-NNN`: requerimiento funcional.
- `RN-MXX-NNN`: regla de negocio.
- `RNF-MXX-NNN`: requerimiento no funcional específico.
- `CA-MXX-NNN-YY`: criterio de aceptación.

---

# 3. Reglas funcionales maestras

1. Historia Clínica ≠ Caso Clínico ≠ Sesión.
2. Plan de Tratamiento ≠ Turno ≠ Sesión.
3. Las sesiones se numeran dentro del Caso Clínico.
4. Turno es reserva; Sesión es atención realizada.
5. Sesión finalizada puede generar obligación económica.
6. Obligación económica ≠ Cobro.
7. Cobro ≠ Caja.
8. Caja registra movimientos monetarios reales.
9. Cobertura del paciente ≠ Convenio del consultorio.
10. Información histórica relevante no se elimina físicamente.
11. Todos los módulos respetan multi-tenancy.
12. Backend es autoridad para permisos, estados y reglas de negocio.

---

## 3.1 Reglas funcionales maestras incorporadas por la extensión de Servicios, Clases y Actividades

13. Persona ≠ PerfilPaciente. Toda persona puede consumir servicios; el perfil clínico solo debe existir cuando corresponde atención clínica.
14. Servicio ≠ OfertaServicioConsultorio. El Servicio define el concepto general; la Oferta define cómo lo presta un consultorio concreto.
15. El nombre de un servicio no determina su comportamiento. Las reglas se resuelven por configuración (`modalidad`, `requiereCasoClinico`, `generaRegistroClinico`, `esquemaCobro`, capacidad y demás parámetros).
16. Turno individual ≠ ClaseProgramada. Ambos consumen agenda, pero poseen cardinalidades y reglas operativas diferentes.
17. Sesión/Atención Clínica ≠ Asistencia/Participación en actividad. Una actividad no clínica nunca debe crear evolución clínica artificial.
18. Una clase grupal clínica puede tener un único evento de agenda y múltiples registros clínicos individuales, uno por participante y por Caso Clínico.
19. Una persona no debe recibir Historia Clínica, Caso Clínico ni diagnóstico solo por consumir un servicio preventivo, de bienestar o no clínico.
20. Una oferta puede ser individual o grupal y su modalidad no debe inferirse por el nombre del servicio.
21. Los espacios físicos deben contemplar capacidad, además de disponibilidad temporal, cuando sean utilizados por actividades grupales.
22. El rol de seguridad de un usuario y su disciplina/habilitación profesional son conceptos independientes.
23. Los servicios pueden utilizar esquemas económicos `POR_SESION`, `POR_CLASE`, `PACK`, `CUOTA_MENSUAL`, `BONO`, `OBRA_SOCIAL` o `MIXTO`.
24. `Suscripción` queda reservada al modelo SaaS de AKINE. Para consumo de servicios se utilizan Pase, Paquete o Abono.
25. La compra anticipada de un pack o abono genera el impacto económico correspondiente al momento de la adquisición; la asistencia posterior no debe generar una segunda deuda si ya está cubierta por créditos o vigencia.
26. Toda afectación de créditos de un pase debe quedar registrada como movimiento auditable con saldo anterior y posterior.
27. La evolución hacia `EventoAgenda` y `PrestacionRealizada` debe ser incremental y no exige reemplazar inmediatamente las entidades `Turno` o `Sesión` vigentes.
28. Los flujos clínico y no clínico deben coexistir sin duplicar identidad, agenda, cobros, caja, auditoría ni reportes.
29. La agenda debe validar simultáneamente disponibilidad de consultorio, profesional, espacio y capacidad cuando el tipo de actividad lo requiera.
30. La extensión funcional debe preservar íntegramente el flujo clínico vigente: Paciente → Historia Clínica → Caso Clínico → Plan de Tratamiento → Turno → Sesión → Obligación económica → Cobro.

---


> **Partición:** esta Parte 1 contiene M01 a M13. La Parte 2 continúa desde M14, incorpora M27–M29 y las reglas transversales. Ambos archivos están preparados para concatenarse en este orden.

# M01 — SaaS, Organizaciones, Tenancy y Suscripciones

## 1. Objetivo

Administrar AKINE como SaaS multi-tenant y controlar organizaciones, memberships, planes, suscripciones, límites y acceso.

## 2. Actores

- Administrador de plataforma
- Administrador de organización
- Sistema

## 3. Dependencias

- Usuarios y seguridad
- Consultorios
- Auditoría

## 4. Reglas de negocio

- **RN-M01-001:** Todo registro operativo debe resolverse dentro del tenant activo.
- **RN-M01-002:** Una suspensión o cambio de plan no debe borrar información histórica.
- **RN-M01-003:** El backend debe validar tenant y membership; nunca confiar solo en el contexto enviado por frontend.
- **RN-M01-004:** Los límites del plan no deben invalidar retrospectivamente datos ya existentes.

## 5. Requerimientos funcionales detallados

### RF-M01-001 — Crear organización

**Descripción:** Crear un tenant nuevo con configuración inicial y asociación a plan/suscripción.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Crear organización** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Crear un tenant nuevo con configuración inicial y asociación a plan/suscripción.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M01-001-01:** el happy path produce el resultado descripto.
- **CA-M01-001-02:** un usuario sin permiso no modifica información.
- **CA-M01-001-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M01-001-04:** una validación fallida no deja datos parciales.
- **CA-M01-001-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M01-001-06:** los históricos relacionados siguen siendo consultables.

### RF-M01-002 — Administrar memberships

**Descripción:** Vincular o desvincular usuarios de una organización con un rol y vigencia.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Administrar memberships** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Vincular o desvincular usuarios de una organización con un rol y vigencia.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M01-002-01:** el happy path produce el resultado descripto.
- **CA-M01-002-02:** un usuario sin permiso no modifica información.
- **CA-M01-002-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M01-002-04:** una validación fallida no deja datos parciales.
- **CA-M01-002-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M01-002-06:** los históricos relacionados siguen siendo consultables.

### RF-M01-003 — Cambiar estado de suscripción

**Descripción:** Aplicar una transición válida de estado contractual/operativo.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Cambiar estado de suscripción** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Aplicar una transición válida de estado contractual/operativo.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M01-003-01:** el happy path produce el resultado descripto.
- **CA-M01-003-02:** un usuario sin permiso no modifica información.
- **CA-M01-003-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M01-003-04:** una validación fallida no deja datos parciales.
- **CA-M01-003-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M01-003-06:** los históricos relacionados siguen siendo consultables.

### RF-M01-004 — Aplicar límites de plan

**Descripción:** Autorizar o rechazar altas/funciones según límites y feature gates.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Aplicar límites de plan** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Autorizar o rechazar altas/funciones según límites y feature gates.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M01-004-01:** el happy path produce el resultado descripto.
- **CA-M01-004-02:** un usuario sin permiso no modifica información.
- **CA-M01-004-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M01-004-04:** una validación fallida no deja datos parciales.
- **CA-M01-004-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M01-004-06:** los históricos relacionados siguen siendo consultables.

### RF-M01-005 — Seleccionar contexto organizacional

**Descripción:** Permitir que usuarios multi-organización trabajen sobre el tenant correcto.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Seleccionar contexto organizacional** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Permitir que usuarios multi-organización trabajen sobre el tenant correcto.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M01-005-01:** el happy path produce el resultado descripto.
- **CA-M01-005-02:** un usuario sin permiso no modifica información.
- **CA-M01-005-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M01-005-04:** una validación fallida no deja datos parciales.
- **CA-M01-005-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M01-005-06:** los históricos relacionados siguen siendo consultables.

## 6. Requerimientos no funcionales específicos

### RNF-M01-001 — Seguridad

Validar tenant, rol y permiso en backend; aplicar mínimo privilegio.

### RNF-M01-002 — Integridad

Evitar estados parcialmente persistidos y proteger relaciones históricas.

### RNF-M01-003 — Concurrencia

Revalidar recursos compartidos y usar locking/restricciones cuando exista riesgo de doble operación.

### RNF-M01-004 — Rendimiento

Listados paginados, filtros indexables y carga solo de información necesaria.

### RNF-M01-005 — Usabilidad

Interacciones compactas, mensajes funcionales y sin exponer identificadores técnicos como dato principal.

### RNF-M01-006 — Auditoría

Registrar operaciones sensibles con actor y fecha.

### RNF-M01-007 — Observabilidad

Errores y tiempos relevantes deben ser medibles sin registrar datos sensibles.

### RNF-M01-008 — Mantenibilidad

Reglas de negocio críticas deben residir en backend/dominio y no duplicarse entre pantallas.

## 7. Casos borde mínimos de QA

- Entidad dada de baja entre la apertura y la confirmación.
- Dos usuarios intentando operar simultáneamente sobre el mismo recurso.
- Cambio de tenant/consultorio antes de confirmar.
- Reintento del request luego de timeout.
- Referencia histórica a catálogos o usuarios inactivos.
- Rol revocado entre lectura y confirmación.
- Paginación, ordenamiento y filtros con volumen alto.
- Datos incompletos provenientes de una migración histórica.

---

# M02 — Usuarios, Autenticación, Roles y Permisos

## 1. Objetivo

Gestionar identidad, autenticación, recuperación de credenciales, roles y autorización contextual.

## 2. Actores

- Usuario
- Administrador de plataforma
- Administrador de organización
- Administrador de consultorio

## 3. Dependencias

- M01 Tenancy
- M24 Auditoría
- M26 Notificaciones

## 4. Reglas de negocio

- **RN-M02-001:** Los permisos se validan siempre en backend.
- **RN-M02-002:** El mismo usuario puede tener roles distintos en consultorios distintos.
- **RN-M02-003:** No almacenar contraseñas ni tokens sensibles en texto plano o logs.
- **RN-M02-004:** Desvincular o bloquear no elimina acciones históricas.

## 5. Requerimientos funcionales detallados

### RF-M02-001 — Registrar cuenta

**Descripción:** Crear una identidad única o asociar correctamente una invitación a una cuenta existente.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Registrar cuenta** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Crear una identidad única o asociar correctamente una invitación a una cuenta existente.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M02-001-01:** el happy path produce el resultado descripto.
- **CA-M02-001-02:** un usuario sin permiso no modifica información.
- **CA-M02-001-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M02-001-04:** una validación fallida no deja datos parciales.
- **CA-M02-001-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M02-001-06:** los históricos relacionados siguen siendo consultables.

### RF-M02-002 — Autenticar usuario

**Descripción:** Validar credenciales, estado de cuenta y emitir una sesión segura.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Autenticar usuario** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Validar credenciales, estado de cuenta y emitir una sesión segura.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M02-002-01:** el happy path produce el resultado descripto.
- **CA-M02-002-02:** un usuario sin permiso no modifica información.
- **CA-M02-002-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M02-002-04:** una validación fallida no deja datos parciales.
- **CA-M02-002-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M02-002-06:** los históricos relacionados siguen siendo consultables.

### RF-M02-003 — Restablecer contraseña

**Descripción:** Emitir enlace temporal, definir nueva credencial e invalidar el token utilizado.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Restablecer contraseña** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Emitir enlace temporal, definir nueva credencial e invalidar el token utilizado.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M02-003-01:** el happy path produce el resultado descripto.
- **CA-M02-003-02:** un usuario sin permiso no modifica información.
- **CA-M02-003-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M02-003-04:** una validación fallida no deja datos parciales.
- **CA-M02-003-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M02-003-06:** los históricos relacionados siguen siendo consultables.

### RF-M02-004 — Asignar o revocar rol

**Descripción:** Modificar permisos de una membership sin afectar autoria histórica.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Asignar o revocar rol** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Modificar permisos de una membership sin afectar autoria histórica.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M02-004-01:** el happy path produce el resultado descripto.
- **CA-M02-004-02:** un usuario sin permiso no modifica información.
- **CA-M02-004-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M02-004-04:** una validación fallida no deja datos parciales.
- **CA-M02-004-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M02-004-06:** los históricos relacionados siguen siendo consultables.

### RF-M02-005 — Bloquear o desactivar cuenta

**Descripción:** Impedir acceso futuro conservando trazabilidad.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Bloquear o desactivar cuenta** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Impedir acceso futuro conservando trazabilidad.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M02-005-01:** el happy path produce el resultado descripto.
- **CA-M02-005-02:** un usuario sin permiso no modifica información.
- **CA-M02-005-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M02-005-04:** una validación fallida no deja datos parciales.
- **CA-M02-005-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M02-005-06:** los históricos relacionados siguen siendo consultables.

## 6. Requerimientos no funcionales específicos

### RNF-M02-001 — Seguridad

Validar tenant, rol y permiso en backend; aplicar mínimo privilegio.

### RNF-M02-002 — Integridad

Evitar estados parcialmente persistidos y proteger relaciones históricas.

### RNF-M02-003 — Concurrencia

Revalidar recursos compartidos y usar locking/restricciones cuando exista riesgo de doble operación.

### RNF-M02-004 — Rendimiento

Listados paginados, filtros indexables y carga solo de información necesaria.

### RNF-M02-005 — Usabilidad

Interacciones compactas, mensajes funcionales y sin exponer identificadores técnicos como dato principal.

### RNF-M02-006 — Auditoría

Registrar operaciones sensibles con actor y fecha.

### RNF-M02-007 — Observabilidad

Errores y tiempos relevantes deben ser medibles sin registrar datos sensibles.

### RNF-M02-008 — Mantenibilidad

Reglas de negocio críticas deben residir en backend/dominio y no duplicarse entre pantallas.

## 7. Casos borde mínimos de QA

- Entidad dada de baja entre la apertura y la confirmación.
- Dos usuarios intentando operar simultáneamente sobre el mismo recurso.
- Cambio de tenant/consultorio antes de confirmar.
- Reintento del request luego de timeout.
- Referencia histórica a catálogos o usuarios inactivos.
- Rol revocado entre lectura y confirmación.
- Paginación, ordenamiento y filtros con volumen alto.
- Datos incompletos provenientes de una migración histórica.

---

# M03 — Consultorios y Configuración Operativa

## 1. Objetivo

Gestionar cada sede de trabajo, su información institucional y configuración mínima de operación.

## 2. Actores

- Administrador de organización
- Administrador de consultorio

## 3. Dependencias

- M01 Organización
- M04 Espacios
- M05 Personal
- M12 Agenda

## 4. Reglas de negocio

- **RN-M03-001:** Todo consultorio pertenece a una organización.
- **RN-M03-002:** La baja es lógica.
- **RN-M03-003:** Un consultorio inactivo no recibe nuevos turnos.
- **RN-M03-004:** El horario general no reemplaza la disponibilidad individual de profesionales.

## 5. Requerimientos funcionales detallados

### RF-M03-001 — Crear consultorio

**Descripción:** Dar de alta una sede perteneciente a la organización.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Crear consultorio** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Dar de alta una sede perteneciente a la organización.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M03-001-01:** el happy path produce el resultado descripto.
- **CA-M03-001-02:** un usuario sin permiso no modifica información.
- **CA-M03-001-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M03-001-04:** una validación fallida no deja datos parciales.
- **CA-M03-001-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M03-001-06:** los históricos relacionados siguen siendo consultables.

### RF-M03-002 — Ejecutar onboarding inicial

**Descripción:** Crear consultorio, primer box, horario general e intervalo inicial.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Ejecutar onboarding inicial** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Crear consultorio, primer box, horario general e intervalo inicial.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M03-002-01:** el happy path produce el resultado descripto.
- **CA-M03-002-02:** un usuario sin permiso no modifica información.
- **CA-M03-002-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M03-002-04:** una validación fallida no deja datos parciales.
- **CA-M03-002-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M03-002-06:** los históricos relacionados siguen siendo consultables.

### RF-M03-003 — Editar configuración

**Descripción:** Modificar datos, horarios y parámetros sin alterar históricos.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Editar configuración** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Modificar datos, horarios y parámetros sin alterar históricos.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M03-003-01:** el happy path produce el resultado descripto.
- **CA-M03-003-02:** un usuario sin permiso no modifica información.
- **CA-M03-003-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M03-003-04:** una validación fallida no deja datos parciales.
- **CA-M03-003-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M03-003-06:** los históricos relacionados siguen siendo consultables.

### RF-M03-004 — Dar de baja lógica

**Descripción:** Deshabilitar nuevas operaciones preservando la información previa.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Dar de baja lógica** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Deshabilitar nuevas operaciones preservando la información previa.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M03-004-01:** el happy path produce el resultado descripto.
- **CA-M03-004-02:** un usuario sin permiso no modifica información.
- **CA-M03-004-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M03-004-04:** una validación fallida no deja datos parciales.
- **CA-M03-004-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M03-004-06:** los históricos relacionados siguen siendo consultables.

### RF-M03-005 — Seleccionar consultorio de trabajo

**Descripción:** Cambiar el contexto operativo dentro de la organización.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Seleccionar consultorio de trabajo** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Cambiar el contexto operativo dentro de la organización.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M03-005-01:** el happy path produce el resultado descripto.
- **CA-M03-005-02:** un usuario sin permiso no modifica información.
- **CA-M03-005-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M03-005-04:** una validación fallida no deja datos parciales.
- **CA-M03-005-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M03-005-06:** los históricos relacionados siguen siendo consultables.

## 6. Requerimientos no funcionales específicos

### RNF-M03-001 — Seguridad

Validar tenant, rol y permiso en backend; aplicar mínimo privilegio.

### RNF-M03-002 — Integridad

Evitar estados parcialmente persistidos y proteger relaciones históricas.

### RNF-M03-003 — Concurrencia

Revalidar recursos compartidos y usar locking/restricciones cuando exista riesgo de doble operación.

### RNF-M03-004 — Rendimiento

Listados paginados, filtros indexables y carga solo de información necesaria.

### RNF-M03-005 — Usabilidad

Interacciones compactas, mensajes funcionales y sin exponer identificadores técnicos como dato principal.

### RNF-M03-006 — Auditoría

Registrar operaciones sensibles con actor y fecha.

### RNF-M03-007 — Observabilidad

Errores y tiempos relevantes deben ser medibles sin registrar datos sensibles.

### RNF-M03-008 — Mantenibilidad

Reglas de negocio críticas deben residir en backend/dominio y no duplicarse entre pantallas.

## 7. Casos borde mínimos de QA

- Entidad dada de baja entre la apertura y la confirmación.
- Dos usuarios intentando operar simultáneamente sobre el mismo recurso.
- Cambio de tenant/consultorio antes de confirmar.
- Reintento del request luego de timeout.
- Referencia histórica a catálogos o usuarios inactivos.
- Rol revocado entre lectura y confirmación.
- Paginación, ordenamiento y filtros con volumen alto.
- Datos incompletos provenientes de una migración histórica.

## 8. Ampliación funcional integrada — Servicios, Clases y Actividades Terapéuticas

Esta sección amplía **Consultorios y Configuración Operativa** con las capacidades requeridas para que AKINE soporte servicios clínicos, terapéuticos, preventivos y de bienestar sin alterar el comportamiento ya definido para el flujo clínico tradicional.

### 8.1 Reglas de negocio adicionales

- **RN-M03-005:** Cada consultorio puede publicar múltiples Ofertas de Servicio con configuración propia y vigencia independiente.
- **RN-M03-006:** La baja o suspensión de una Oferta no debe afectar turnos, clases, asistencias, obligaciones ni comprobantes históricos.
- **RN-M03-007:** La configuración general del consultorio puede definir valores por defecto, pero la Oferta de Servicio puede sobrescribir duración, capacidad, precio y esquema de cobro.

### 8.2 Requerimientos funcionales adicionales

### RF-M03-006 — Configurar servicios ofrecidos por consultorio

**Descripción:** Permitir que el consultorio habilite y configure qué servicios presta efectivamente.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Configurar servicios ofrecidos por consultorio** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Selecciona un Servicio del catálogo global o autorizado.
6. Crea o actualiza la OfertaServicioConsultorio con nombre comercial, modalidad, duración, capacidad, vigencia y reglas operativas.
7. Asocia profesionales y espacios habilitados cuando corresponda.
8. Persiste los cambios de forma atómica y consistente.
9. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
10. Registra auditoría y eventos de dominio aplicables.
11. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- No permitir una oferta activa sin Servicio vigente.
- Capacidad debe ser mayor que cero en ofertas grupales.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M03-006-01:** el happy path produce el resultado descripto.
- **CA-M03-006-02:** un usuario sin permiso no modifica información.
- **CA-M03-006-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M03-006-04:** una validación fallida no deja datos parciales.
- **CA-M03-006-05:** la operación conserva trazabilidad histórica.
- **CA-M03-006-06:** la misma definición de Servicio puede poseer configuraciones diferentes en dos consultorios sin interferencia.

### RF-M03-007 — Definir valores operativos por defecto para nuevas ofertas

**Descripción:** Permitir que el consultorio establezca duración, moneda, capacidad y políticas iniciales reutilizables sin hardcodear servicios.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Definir valores operativos por defecto para nuevas ofertas** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Recupera parámetros por defecto del consultorio.
6. Aplica esos valores únicamente como propuesta inicial editable al crear una Oferta.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Un cambio de default no modifica ofertas históricas o ya configuradas.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M03-007-01:** el happy path produce el resultado descripto.
- **CA-M03-007-02:** un usuario sin permiso no modifica información.
- **CA-M03-007-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M03-007-04:** una validación fallida no deja datos parciales.
- **CA-M03-007-05:** la operación conserva trazabilidad histórica.
- **CA-M03-007-06:** modificar el intervalo o capacidad por defecto no altera retroactivamente ofertas existentes.

### 8.3 Requerimientos no funcionales adicionales

### RNF-M03-009 — Extensión de servicios

Las nuevas reglas deben resolverse en backend/dominio y no inferirse desde nombres visibles del servicio.

### RNF-M03-010 — Extensión de servicios

Las consultas ampliadas deben mantener paginación, índices y tiempos de respuesta compatibles con el volumen operativo.

### RNF-M03-011 — Extensión de servicios

La incorporación de clases/servicios no debe degradar ni alterar datos históricos del flujo clínico existente.

### 8.4 Casos borde adicionales de QA

- Consultorio con Kinesiología individual y Pilates grupal activos simultáneamente.
- Mismo Servicio configurado con duración/capacidad distintas en dos consultorios.

---

# M04 — Espacios, Boxes y Recursos Físicos

## 1. Objetivo

Administrar espacios físicos utilizados para reservar o ejecutar atenciones.

## 2. Actores

- Administrador de consultorio
- Administrativo
- Profesional

## 3. Dependencias

- M03 Consultorios
- M12 Agenda
- M14 Sesiones

## 4. Reglas de negocio

- **RN-M04-001:** Todo espacio pertenece a un consultorio.
- **RN-M04-002:** Un recurso inactivo no se ofrece para nuevas reservas.
- **RN-M04-003:** La baja no modifica sesiones históricas.
- **RN-M04-004:** Debe evitarse doble reserva cuando la capacidad efectiva es uno.

## 5. Requerimientos funcionales detallados

### RF-M04-001 — Crear espacio

**Descripción:** Dar de alta box, gimnasio, gabinete u otro recurso.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Crear espacio** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Dar de alta box, gimnasio, gabinete u otro recurso.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M04-001-01:** el happy path produce el resultado descripto.
- **CA-M04-001-02:** un usuario sin permiso no modifica información.
- **CA-M04-001-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M04-001-04:** una validación fallida no deja datos parciales.
- **CA-M04-001-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M04-001-06:** los históricos relacionados siguen siendo consultables.

### RF-M04-002 — Editar espacio

**Descripción:** Modificar nombre, tipo, capacidad o configuración futura.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Editar espacio** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Modificar nombre, tipo, capacidad o configuración futura.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M04-002-01:** el happy path produce el resultado descripto.
- **CA-M04-002-02:** un usuario sin permiso no modifica información.
- **CA-M04-002-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M04-002-04:** una validación fallida no deja datos parciales.
- **CA-M04-002-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M04-002-06:** los históricos relacionados siguen siendo consultables.

### RF-M04-003 — Consultar disponibilidad

**Descripción:** Determinar si el recurso está libre para fecha, hora y duración.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Consultar disponibilidad** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Determinar si el recurso está libre para fecha, hora y duración.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M04-003-01:** el happy path produce el resultado descripto.
- **CA-M04-003-02:** un usuario sin permiso no modifica información.
- **CA-M04-003-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M04-003-04:** una validación fallida no deja datos parciales.
- **CA-M04-003-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M04-003-06:** los históricos relacionados siguen siendo consultables.

### RF-M04-004 — Asignar espacio a turno

**Descripción:** Reservar un recurso cuando el turno lo requiera.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Asignar espacio a turno** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Reservar un recurso cuando el turno lo requiera.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M04-004-01:** el happy path produce el resultado descripto.
- **CA-M04-004-02:** un usuario sin permiso no modifica información.
- **CA-M04-004-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M04-004-04:** una validación fallida no deja datos parciales.
- **CA-M04-004-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M04-004-06:** los históricos relacionados siguen siendo consultables.

### RF-M04-005 — Asignar/cambiar espacio en sesión

**Descripción:** Registrar el espacio realmente utilizado.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Asignar/cambiar espacio en sesión** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Registrar el espacio realmente utilizado.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M04-005-01:** el happy path produce el resultado descripto.
- **CA-M04-005-02:** un usuario sin permiso no modifica información.
- **CA-M04-005-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M04-005-04:** una validación fallida no deja datos parciales.
- **CA-M04-005-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M04-005-06:** los históricos relacionados siguen siendo consultables.

### RF-M04-006 — Dar de baja espacio

**Descripción:** Retirarlo de nuevas reservas conservando históricos.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Dar de baja espacio** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Retirarlo de nuevas reservas conservando históricos.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M04-006-01:** el happy path produce el resultado descripto.
- **CA-M04-006-02:** un usuario sin permiso no modifica información.
- **CA-M04-006-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M04-006-04:** una validación fallida no deja datos parciales.
- **CA-M04-006-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M04-006-06:** los históricos relacionados siguen siendo consultables.

## 6. Requerimientos no funcionales específicos

### RNF-M04-001 — Seguridad

Validar tenant, rol y permiso en backend; aplicar mínimo privilegio.

### RNF-M04-002 — Integridad

Evitar estados parcialmente persistidos y proteger relaciones históricas.

### RNF-M04-003 — Concurrencia

Revalidar recursos compartidos y usar locking/restricciones cuando exista riesgo de doble operación.

### RNF-M04-004 — Rendimiento

Listados paginados, filtros indexables y carga solo de información necesaria.

### RNF-M04-005 — Usabilidad

Interacciones compactas, mensajes funcionales y sin exponer identificadores técnicos como dato principal.

### RNF-M04-006 — Auditoría

Registrar operaciones sensibles con actor y fecha.

### RNF-M04-007 — Observabilidad

Errores y tiempos relevantes deben ser medibles sin registrar datos sensibles.

### RNF-M04-008 — Mantenibilidad

Reglas de negocio críticas deben residir en backend/dominio y no duplicarse entre pantallas.

## 7. Casos borde mínimos de QA

- Entidad dada de baja entre la apertura y la confirmación.
- Dos usuarios intentando operar simultáneamente sobre el mismo recurso.
- Cambio de tenant/consultorio antes de confirmar.
- Reintento del request luego de timeout.
- Referencia histórica a catálogos o usuarios inactivos.
- Rol revocado entre lectura y confirmación.
- Paginación, ordenamiento y filtros con volumen alto.
- Datos incompletos provenientes de una migración histórica.

## 8. Ampliación funcional integrada — Servicios, Clases y Actividades Terapéuticas

Esta sección amplía **Espacios, Boxes y Recursos Físicos** con las capacidades requeridas para que AKINE soporte servicios clínicos, terapéuticos, preventivos y de bienestar sin alterar el comportamiento ya definido para el flujo clínico tradicional.

### 8.1 Reglas de negocio adicionales

- **RN-M04-005:** Todo espacio debe poseer capacidad operativa configurable; para un box individual normalmente será 1.
- **RN-M04-006:** La capacidad del evento no puede exceder la capacidad efectiva del espacio asignado salvo configuración explícita y autorizada.
- **RN-M04-007:** Un espacio puede habilitarse para determinados servicios sin crear roles o tipos rígidos por nombre.

### 8.2 Requerimientos funcionales adicionales

### RF-M04-007 — Configurar tipo y capacidad de espacio

**Descripción:** Ampliar los espacios para representar boxes, gimnasios, salas de Pilates, salas funcionales y otros recursos con capacidad física.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Configurar tipo y capacidad de espacio** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Registra tipo de espacio y capacidad máxima.
6. Conserva la configuración histórica utilizada por eventos ya realizados.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Capacidad mínima igual a 1.
- No reducir capacidad por debajo de la ocupación de eventos futuros ya confirmados sin resolverlos.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M04-007-01:** el happy path produce el resultado descripto.
- **CA-M04-007-02:** un usuario sin permiso no modifica información.
- **CA-M04-007-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M04-007-04:** una validación fallida no deja datos parciales.
- **CA-M04-007-05:** la operación conserva trazabilidad histórica.
- **CA-M04-007-06:** una Sala Pilates capacidad 8 admite eventos hasta ocho participantes y un Box capacidad 1 conserva uso individual.

### RF-M04-008 — Habilitar espacios por Oferta de Servicio

**Descripción:** Definir qué espacios pueden utilizarse para una oferta sin inferirlo por el nombre del espacio.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Habilitar espacios por Oferta de Servicio** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Asocia uno o más espacios vigentes a la Oferta.
6. La agenda filtra recursos compatibles al programar.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Oferta y espacio deben pertenecer al mismo consultorio.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M04-008-01:** el happy path produce el resultado descripto.
- **CA-M04-008-02:** un usuario sin permiso no modifica información.
- **CA-M04-008-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M04-008-04:** una validación fallida no deja datos parciales.
- **CA-M04-008-05:** la operación conserva trazabilidad histórica.
- **CA-M04-008-06:** Pilates Reformer puede limitarse a Sala Pilates mientras Kinesiología puede utilizar Boxes habilitados.

### RF-M04-009 — Validar capacidad física al reservar actividad grupal

**Descripción:** Impedir que una clase supere la capacidad física del espacio incluso cuando la capacidad comercial de la oferta sea mayor.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Validar capacidad física al reservar actividad grupal** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Calcula capacidad efectiva como el mínimo entre capacidad de la clase y capacidad del espacio cuando ambos límites apliquen.
6. Revalida cupo al confirmar cada inscripción.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- No admitir sobreocupación por concurrencia.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M04-009-01:** el happy path produce el resultado descripto.
- **CA-M04-009-02:** un usuario sin permiso no modifica información.
- **CA-M04-009-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M04-009-04:** una validación fallida no deja datos parciales.
- **CA-M04-009-05:** la operación conserva trazabilidad histórica.
- **CA-M04-009-06:** dos inscripciones concurrentes sobre el último cupo no producen sobreventa.

### 8.3 Requerimientos no funcionales adicionales

### RNF-M04-009 — Extensión de servicios

Las nuevas reglas deben resolverse en backend/dominio y no inferirse desde nombres visibles del servicio.

### RNF-M04-010 — Extensión de servicios

Las consultas ampliadas deben mantener paginación, índices y tiempos de respuesta compatibles con el volumen operativo.

### RNF-M04-011 — Extensión de servicios

La incorporación de clases/servicios no debe degradar ni alterar datos históricos del flujo clínico existente.

### 8.4 Casos borde adicionales de QA

- Cambio de espacio de una clase hacia otro con menor capacidad.
- Espacio inactivo con clases futuras programadas.
- Evento grupal sin espacio cuando la oferta lo marca como obligatorio.

---

# M05 — Profesionales, Administrativos, Memberships y Disponibilidad

## 1. Objetivo

Administrar personal, vínculos por consultorio, horarios recurrentes y excepciones.

## 2. Actores

- Administrador de consultorio
- Profesional
- Administrativo

## 3. Dependencias

- M02 Usuarios
- M03 Consultorios
- M12 Agenda

## 4. Reglas de negocio

- **RN-M05-001:** La disponibilidad pertenece a Profesional + Consultorio.
- **RN-M05-002:** Las excepciones prevalecen sobre el horario base.
- **RN-M05-003:** Desvincular no elimina autoria histórica.
- **RN-M05-004:** Los turnos futuros afectados deben quedar visibles para resolución.

## 5. Requerimientos funcionales detallados

### RF-M05-001 — Invitar colaborador

**Descripción:** Invitar profesional o administrativo y definir rol.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Invitar colaborador** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Invitar profesional o administrativo y definir rol.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M05-001-01:** el happy path produce el resultado descripto.
- **CA-M05-001-02:** un usuario sin permiso no modifica información.
- **CA-M05-001-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M05-001-04:** una validación fallida no deja datos parciales.
- **CA-M05-001-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M05-001-06:** los históricos relacionados siguen siendo consultables.

### RF-M05-002 — Aceptar/rechazar invitación

**Descripción:** Resolver la incorporación sin duplicar usuarios.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Aceptar/rechazar invitación** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Resolver la incorporación sin duplicar usuarios.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M05-002-01:** el happy path produce el resultado descripto.
- **CA-M05-002-02:** un usuario sin permiso no modifica información.
- **CA-M05-002-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M05-002-04:** una validación fallida no deja datos parciales.
- **CA-M05-002-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M05-002-06:** los históricos relacionados siguen siendo consultables.

### RF-M05-003 — Configurar disponibilidad semanal

**Descripción:** Registrar uno o más bloques horarios por día y vigencia.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Configurar disponibilidad semanal** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Registrar uno o más bloques horarios por día y vigencia.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M05-003-01:** el happy path produce el resultado descripto.
- **CA-M05-003-02:** un usuario sin permiso no modifica información.
- **CA-M05-003-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M05-003-04:** una validación fallida no deja datos parciales.
- **CA-M05-003-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M05-003-06:** los históricos relacionados siguen siendo consultables.

### RF-M05-004 — Registrar excepción

**Descripción:** Agregar ausencia, licencia, bloqueo o ampliación excepcional.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Registrar excepción** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Agregar ausencia, licencia, bloqueo o ampliación excepcional.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M05-004-01:** el happy path produce el resultado descripto.
- **CA-M05-004-02:** un usuario sin permiso no modifica información.
- **CA-M05-004-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M05-004-04:** una validación fallida no deja datos parciales.
- **CA-M05-004-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M05-004-06:** los históricos relacionados siguen siendo consultables.

### RF-M05-005 — Modificar disponibilidad

**Descripción:** Cambiar horarios futuros detectando conflictos.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Modificar disponibilidad** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Cambiar horarios futuros detectando conflictos.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M05-005-01:** el happy path produce el resultado descripto.
- **CA-M05-005-02:** un usuario sin permiso no modifica información.
- **CA-M05-005-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M05-005-04:** una validación fallida no deja datos parciales.
- **CA-M05-005-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M05-005-06:** los históricos relacionados siguen siendo consultables.

### RF-M05-006 — Desvincular colaborador

**Descripción:** Finalizar vínculo y detectar turnos futuros afectados.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Desvincular colaborador** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Finalizar vínculo y detectar turnos futuros afectados.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M05-006-01:** el happy path produce el resultado descripto.
- **CA-M05-006-02:** un usuario sin permiso no modifica información.
- **CA-M05-006-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M05-006-04:** una validación fallida no deja datos parciales.
- **CA-M05-006-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M05-006-06:** los históricos relacionados siguen siendo consultables.

## 6. Requerimientos no funcionales específicos

### RNF-M05-001 — Seguridad

Validar tenant, rol y permiso en backend; aplicar mínimo privilegio.

### RNF-M05-002 — Integridad

Evitar estados parcialmente persistidos y proteger relaciones históricas.

### RNF-M05-003 — Concurrencia

Revalidar recursos compartidos y usar locking/restricciones cuando exista riesgo de doble operación.

### RNF-M05-004 — Rendimiento

Listados paginados, filtros indexables y carga solo de información necesaria.

### RNF-M05-005 — Usabilidad

Interacciones compactas, mensajes funcionales y sin exponer identificadores técnicos como dato principal.

### RNF-M05-006 — Auditoría

Registrar operaciones sensibles con actor y fecha.

### RNF-M05-007 — Observabilidad

Errores y tiempos relevantes deben ser medibles sin registrar datos sensibles.

### RNF-M05-008 — Mantenibilidad

Reglas de negocio críticas deben residir en backend/dominio y no duplicarse entre pantallas.

## 7. Casos borde mínimos de QA

- Entidad dada de baja entre la apertura y la confirmación.
- Dos usuarios intentando operar simultáneamente sobre el mismo recurso.
- Cambio de tenant/consultorio antes de confirmar.
- Reintento del request luego de timeout.
- Referencia histórica a catálogos o usuarios inactivos.
- Rol revocado entre lectura y confirmación.
- Paginación, ordenamiento y filtros con volumen alto.
- Datos incompletos provenientes de una migración histórica.

## 8. Ampliación funcional integrada — Servicios, Clases y Actividades Terapéuticas

Esta sección amplía **Profesionales, Administrativos, Memberships y Disponibilidad** con las capacidades requeridas para que AKINE soporte servicios clínicos, terapéuticos, preventivos y de bienestar sin alterar el comportamiento ya definido para el flujo clínico tradicional.

### 8.1 Reglas de negocio adicionales

- **RN-M05-005:** Rol de seguridad, disciplina, especialidad y habilitación para prestar un Servicio son dimensiones diferentes.
- **RN-M05-006:** No se crearán roles de seguridad Instructor, Profesor o Entrenador solo por la denominación profesional.
- **RN-M05-007:** Una persona profesional puede estar habilitada para servicios diferentes según consultorio.

### 8.2 Requerimientos funcionales adicionales

### RF-M05-007 — Administrar disciplinas y servicios habilitados

**Descripción:** Permitir asociar disciplinas y Ofertas de Servicio concretas a cada profesional dentro de un consultorio.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Administrar disciplinas y servicios habilitados** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Selecciona disciplinas informativas del profesional.
6. Asocia ofertas para las cuales está habilitado.
7. Registra vigencia de la habilitación cuando corresponda.
8. Persiste los cambios de forma atómica y consistente.
9. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
10. Registra auditoría y eventos de dominio aplicables.
11. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- No permitir asignar oferta de otro consultorio.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M05-007-01:** el happy path produce el resultado descripto.
- **CA-M05-007-02:** un usuario sin permiso no modifica información.
- **CA-M05-007-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M05-007-04:** una validación fallida no deja datos parciales.
- **CA-M05-007-05:** la operación conserva trazabilidad histórica.
- **CA-M05-007-06:** un usuario con rol PROFESIONAL puede prestar Kinesiología y Pilates sin crear dos roles de seguridad.

### RF-M05-008 — Validar habilitación profesional al programar servicio

**Descripción:** Verificar que el profesional asignado a turno o clase esté habilitado para la Oferta correspondiente.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Validar habilitación profesional al programar servicio** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Consulta habilitación vigente en fecha del evento.
6. Excluye profesionales no habilitados de la búsqueda de disponibilidad.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- La habilitación debe estar vigente para la fecha del evento, no solo para la fecha actual.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M05-008-01:** el happy path produce el resultado descripto.
- **CA-M05-008-02:** un usuario sin permiso no modifica información.
- **CA-M05-008-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M05-008-04:** una validación fallida no deja datos parciales.
- **CA-M05-008-05:** la operación conserva trazabilidad histórica.
- **CA-M05-008-06:** un profesional sin habilitación para Pilates no aparece como opción para esa clase aunque esté horario disponible.

### RF-M05-009 — Mantener disponibilidad por consultorio y servicio

**Descripción:** Permitir que la disponibilidad general del profesional se combine con restricciones específicas de servicios cuando el consultorio lo requiera.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Mantener disponibilidad por consultorio y servicio** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Calcula disponibilidad base del profesional.
6. Aplica restricciones adicionales de la Oferta o agenda.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Las restricciones específicas no pueden habilitar horarios fuera de la disponibilidad base sin autorización explícita.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M05-009-01:** el happy path produce el resultado descripto.
- **CA-M05-009-02:** un usuario sin permiso no modifica información.
- **CA-M05-009-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M05-009-04:** una validación fallida no deja datos parciales.
- **CA-M05-009-05:** la operación conserva trazabilidad histórica.
- **CA-M05-009-06:** un profesional puede atender Kinesiología por la mañana y dictar Pilates solo en franjas configuradas.

### 8.3 Requerimientos no funcionales adicionales

### RNF-M05-009 — Extensión de servicios

Las nuevas reglas deben resolverse en backend/dominio y no inferirse desde nombres visibles del servicio.

### RNF-M05-010 — Extensión de servicios

Las consultas ampliadas deben mantener paginación, índices y tiempos de respuesta compatibles con el volumen operativo.

### RNF-M05-011 — Extensión de servicios

La incorporación de clases/servicios no debe degradar ni alterar datos históricos del flujo clínico existente.

### 8.4 Casos borde adicionales de QA

- Profesional desvinculado con clases futuras.
- Habilitación vencida entre programación y fecha de ejecución.

---

# M06 — Especialidades, Prácticas y Catálogos

## 1. Objetivo

Mantener catálogos clínico-operativos reutilizables, buscables y versionables.

## 2. Actores

- Administrador de plataforma
- Administrador de consultorio

## 3. Dependencias

- M14 Sesiones
- M16 Convenios

## 4. Reglas de negocio

- **RN-M06-001:** Catálogos utilizados históricamente no se eliminan físicamente.
- **RN-M06-002:** Las prácticas conservan significado histórico aunque luego sean dadas de baja.
- **RN-M06-003:** Convenios pueden referenciar prácticas y valores por vigencia.

## 5. Requerimientos funcionales detallados

### RF-M06-001 — Administrar especialidad

**Descripción:** Crear, editar, activar o dar de baja una especialidad.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Administrar especialidad** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Crear, editar, activar o dar de baja una especialidad.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M06-001-01:** el happy path produce el resultado descripto.
- **CA-M06-001-02:** un usuario sin permiso no modifica información.
- **CA-M06-001-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M06-001-04:** una validación fallida no deja datos parciales.
- **CA-M06-001-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M06-001-06:** los históricos relacionados siguen siendo consultables.

### RF-M06-002 — Administrar práctica

**Descripción:** Gestionar prestaciones y asociarlas a especialidades.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Administrar práctica** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Gestionar prestaciones y asociarlas a especialidades.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M06-002-01:** el happy path produce el resultado descripto.
- **CA-M06-002-02:** un usuario sin permiso no modifica información.
- **CA-M06-002-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M06-002-04:** una validación fallida no deja datos parciales.
- **CA-M06-002-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M06-002-06:** los históricos relacionados siguen siendo consultables.

### RF-M06-003 — Gestionar nomenclador

**Descripción:** Definir vigencias de catálogos y prácticas.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Gestionar nomenclador** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Definir vigencias de catálogos y prácticas.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M06-003-01:** el happy path produce el resultado descripto.
- **CA-M06-003-02:** un usuario sin permiso no modifica información.
- **CA-M06-003-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M06-003-04:** una validación fallida no deja datos parciales.
- **CA-M06-003-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M06-003-06:** los históricos relacionados siguen siendo consultables.

### RF-M06-004 — Buscar prácticas

**Descripción:** Permitir selección incremental en formularios clínicos/económicos.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Buscar prácticas** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Permitir selección incremental en formularios clínicos/económicos.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M06-004-01:** el happy path produce el resultado descripto.
- **CA-M06-004-02:** un usuario sin permiso no modifica información.
- **CA-M06-004-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M06-004-04:** una validación fallida no deja datos parciales.
- **CA-M06-004-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M06-004-06:** los históricos relacionados siguen siendo consultables.

### RF-M06-005 — Solicitar alta de catálogo

**Descripción:** Canalizar un concepto global no disponible.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Solicitar alta de catálogo** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Canalizar un concepto global no disponible.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M06-005-01:** el happy path produce el resultado descripto.
- **CA-M06-005-02:** un usuario sin permiso no modifica información.
- **CA-M06-005-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M06-005-04:** una validación fallida no deja datos parciales.
- **CA-M06-005-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M06-005-06:** los históricos relacionados siguen siendo consultables.

## 6. Requerimientos no funcionales específicos

### RNF-M06-001 — Seguridad

Validar tenant, rol y permiso en backend; aplicar mínimo privilegio.

### RNF-M06-002 — Integridad

Evitar estados parcialmente persistidos y proteger relaciones históricas.

### RNF-M06-003 — Concurrencia

Revalidar recursos compartidos y usar locking/restricciones cuando exista riesgo de doble operación.

### RNF-M06-004 — Rendimiento

Listados paginados, filtros indexables y carga solo de información necesaria.

### RNF-M06-005 — Usabilidad

Interacciones compactas, mensajes funcionales y sin exponer identificadores técnicos como dato principal.

### RNF-M06-006 — Auditoría

Registrar operaciones sensibles con actor y fecha.

### RNF-M06-007 — Observabilidad

Errores y tiempos relevantes deben ser medibles sin registrar datos sensibles.

### RNF-M06-008 — Mantenibilidad

Reglas de negocio críticas deben residir en backend/dominio y no duplicarse entre pantallas.

## 7. Casos borde mínimos de QA

- Entidad dada de baja entre la apertura y la confirmación.
- Dos usuarios intentando operar simultáneamente sobre el mismo recurso.
- Cambio de tenant/consultorio antes de confirmar.
- Reintento del request luego de timeout.
- Referencia histórica a catálogos o usuarios inactivos.
- Rol revocado entre lectura y confirmación.
- Paginación, ordenamiento y filtros con volumen alto.
- Datos incompletos provenientes de una migración histórica.

## 8. Ampliación funcional integrada — Servicios, Clases y Actividades Terapéuticas

Esta sección amplía **Especialidades, Prácticas y Catálogos** con las capacidades requeridas para que AKINE soporte servicios clínicos, terapéuticos, preventivos y de bienestar sin alterar el comportamiento ya definido para el flujo clínico tradicional.

### 8.1 Reglas de negocio adicionales

- **RN-M06-004:** Servicio y Práctica son conceptos distintos: el Servicio es lo que el consultorio ofrece; la Práctica es una intervención que puede realizarse durante una atención.
- **RN-M06-005:** La naturaleza del Servicio (`CLINICO`, `TERAPEUTICO`, `PREVENTIVO`, `BIENESTAR`) sirve para clasificación y no debe imponer por sí sola comportamiento clínico.
- **RN-M06-006:** No deben existir condicionales funcionales por nombres como Pilates, RPG, Yoga u Osteopatía.

### 8.2 Requerimientos funcionales adicionales

### RF-M06-006 — Administrar catálogo global de Servicios

**Descripción:** Crear y mantener Servicios reutilizables por múltiples consultorios con atributos de clasificación y defaults no vinculantes.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Administrar catálogo global de Servicios** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Registra nombre, descripción, categoría/naturaleza, modalidad default y defaults clínicos.
6. Permite activar/inactivar conservando referencias históricas.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Nombre identificable y estado válido.
- Los defaults no reemplazan la configuración concreta de cada Oferta.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M06-006-01:** el happy path produce el resultado descripto.
- **CA-M06-006-02:** un usuario sin permiso no modifica información.
- **CA-M06-006-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M06-006-04:** una validación fallida no deja datos parciales.
- **CA-M06-006-05:** la operación conserva trazabilidad histórica.
- **CA-M06-006-06:** Pilates Reformer puede existir una sola vez en catálogo y ser ofrecido con reglas diferentes por consultorio.

### RF-M06-007 — Configurar naturaleza y modalidad default del Servicio

**Descripción:** Clasificar servicios para organización y proponer valores iniciales sin convertirlos en reglas rígidas.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Configurar naturaleza y modalidad default del Servicio** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Asigna naturaleza y modalidad default.
6. Al crear una Oferta copia defaults como valores editables.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Naturaleza no puede usarse como única condición para crear Caso Clínico o Registro Clínico.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M06-007-01:** el happy path produce el resultado descripto.
- **CA-M06-007-02:** un usuario sin permiso no modifica información.
- **CA-M06-007-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M06-007-04:** una validación fallida no deja datos parciales.
- **CA-M06-007-05:** la operación conserva trazabilidad histórica.
- **CA-M06-007-06:** dos ofertas del mismo Servicio pueden diferir en requiereCasoClinico y generaRegistroClinico.

### RF-M06-008 — Diferenciar Servicio de Práctica clínica

**Descripción:** Evitar que actividades completas como Pilates o Yoga sean tratadas automáticamente como una práctica clínica ejecutada dentro de una sesión.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Diferenciar Servicio de Práctica clínica** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Mantiene catálogos y relaciones separados.
6. Permite que una Oferta clínica utilice una o más Prácticas durante sus atenciones cuando corresponda.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- No crear PracticaEnAtencion por el solo hecho de asistir a un servicio.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M06-008-01:** el happy path produce el resultado descripto.
- **CA-M06-008-02:** un usuario sin permiso no modifica información.
- **CA-M06-008-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M06-008-04:** una validación fallida no deja datos parciales.
- **CA-M06-008-05:** la operación conserva trazabilidad histórica.
- **CA-M06-008-06:** asistir a Pilates General no crea una práctica clínica ni una evolución.

### 8.3 Requerimientos no funcionales adicionales

### RNF-M06-009 — Extensión de servicios

Las nuevas reglas deben resolverse en backend/dominio y no inferirse desde nombres visibles del servicio.

### RNF-M06-010 — Extensión de servicios

Las consultas ampliadas deben mantener paginación, índices y tiempos de respuesta compatibles con el volumen operativo.

### RNF-M06-011 — Extensión de servicios

La incorporación de clases/servicios no debe degradar ni alterar datos históricos del flujo clínico existente.

### 8.4 Casos borde adicionales de QA

- Servicio inactivo con ofertas históricas.
- Práctica con nombre coincidente con Servicio sin mezcla de entidades.

---

# M07 — Pacientes

## 1. Objetivo

Gestionar identidad administrativa y ficha 360 del paciente sin mezclarla con el registro clínico.

## 2. Actores

- Administrativo
- Profesional
- Paciente según alcance

## 3. Dependencias

- M08 Coberturas
- M09 Historia Clínica
- M12 Turnos
- M18 Deuda

## 4. Reglas de negocio

- **RN-M07-001:** Debe existir búsqueda previa a la creación.
- **RN-M07-002:** Paciente y usuario de portal no son necesariamente la misma entidad.
- **RN-M07-003:** La información clínica se mantiene en módulos clínicos.
- **RN-M07-004:** Un paciente con historia no se elimina físicamente.

## 5. Requerimientos funcionales detallados

### RF-M07-001 — Buscar paciente

**Descripción:** Buscar por DNI, apellido, nombre, teléfono, afiliado o identificador interno.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Buscar paciente** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Buscar por DNI, apellido, nombre, teléfono, afiliado o identificador interno.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M07-001-01:** el happy path produce el resultado descripto.
- **CA-M07-001-02:** un usuario sin permiso no modifica información.
- **CA-M07-001-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M07-001-04:** una validación fallida no deja datos parciales.
- **CA-M07-001-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M07-001-06:** los históricos relacionados siguen siendo consultables.

### RF-M07-002 — Crear paciente

**Descripción:** Registrar datos mínimos evitando duplicados.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Crear paciente** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Registrar datos mínimos evitando duplicados.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M07-002-01:** el happy path produce el resultado descripto.
- **CA-M07-002-02:** un usuario sin permiso no modifica información.
- **CA-M07-002-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M07-002-04:** una validación fallida no deja datos parciales.
- **CA-M07-002-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M07-002-06:** los históricos relacionados siguen siendo consultables.

### RF-M07-003 — Editar paciente

**Descripción:** Actualizar información administrativa y contacto.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Editar paciente** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Actualizar información administrativa y contacto.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M07-003-01:** el happy path produce el resultado descripto.
- **CA-M07-003-02:** un usuario sin permiso no modifica información.
- **CA-M07-003-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M07-003-04:** una validación fallida no deja datos parciales.
- **CA-M07-003-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M07-003-06:** los históricos relacionados siguen siendo consultables.

### RF-M07-004 — Consultar Paciente 360

**Descripción:** Consolidar cobertura, turnos, casos y situación económica según permisos.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Consultar Paciente 360** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Consolidar cobertura, turnos, casos y situación económica según permisos.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M07-004-01:** el happy path produce el resultado descripto.
- **CA-M07-004-02:** un usuario sin permiso no modifica información.
- **CA-M07-004-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M07-004-04:** una validación fallida no deja datos parciales.
- **CA-M07-004-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M07-004-06:** los históricos relacionados siguen siendo consultables.

### RF-M07-005 — Dar baja lógica

**Descripción:** Desactivar nuevas operaciones sin borrar historia.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Dar baja lógica** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Desactivar nuevas operaciones sin borrar historia.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M07-005-01:** el happy path produce el resultado descripto.
- **CA-M07-005-02:** un usuario sin permiso no modifica información.
- **CA-M07-005-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M07-005-04:** una validación fallida no deja datos parciales.
- **CA-M07-005-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M07-005-06:** los históricos relacionados siguen siendo consultables.

### RF-M07-006 — Gestionar adjuntos administrativos

**Descripción:** Vincular documentos permitidos al paciente.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Gestionar adjuntos administrativos** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Vincular documentos permitidos al paciente.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M07-006-01:** el happy path produce el resultado descripto.
- **CA-M07-006-02:** un usuario sin permiso no modifica información.
- **CA-M07-006-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M07-006-04:** una validación fallida no deja datos parciales.
- **CA-M07-006-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M07-006-06:** los históricos relacionados siguen siendo consultables.

## 6. Requerimientos no funcionales específicos

### RNF-M07-001 — Seguridad

Validar tenant, rol y permiso en backend; aplicar mínimo privilegio.

### RNF-M07-002 — Integridad

Evitar estados parcialmente persistidos y proteger relaciones históricas.

### RNF-M07-003 — Concurrencia

Revalidar recursos compartidos y usar locking/restricciones cuando exista riesgo de doble operación.

### RNF-M07-004 — Rendimiento

Listados paginados, filtros indexables y carga solo de información necesaria.

### RNF-M07-005 — Usabilidad

Interacciones compactas, mensajes funcionales y sin exponer identificadores técnicos como dato principal.

### RNF-M07-006 — Auditoría

Registrar operaciones sensibles con actor y fecha.

### RNF-M07-007 — Observabilidad

Errores y tiempos relevantes deben ser medibles sin registrar datos sensibles.

### RNF-M07-008 — Mantenibilidad

Reglas de negocio críticas deben residir en backend/dominio y no duplicarse entre pantallas.

## 7. Casos borde mínimos de QA

- Entidad dada de baja entre la apertura y la confirmación.
- Dos usuarios intentando operar simultáneamente sobre el mismo recurso.
- Cambio de tenant/consultorio antes de confirmar.
- Reintento del request luego de timeout.
- Referencia histórica a catálogos o usuarios inactivos.
- Rol revocado entre lectura y confirmación.
- Paginación, ordenamiento y filtros con volumen alto.
- Datos incompletos provenientes de una migración histórica.

## 8. Ampliación funcional integrada — Servicios, Clases y Actividades Terapéuticas

Esta sección amplía **Pacientes** con las capacidades requeridas para que AKINE soporte servicios clínicos, terapéuticos, preventivos y de bienestar sin alterar el comportamiento ya definido para el flujo clínico tradicional.

### 8.1 Reglas de negocio adicionales

- **RN-M07-005:** La identidad base debe evolucionar conceptualmente hacia Persona; Paciente representa un perfil clínico de esa persona.
- **RN-M07-006:** Una Persona puede consumir servicios no clínicos sin Historia Clínica ni Caso Clínico.
- **RN-M07-007:** La activación posterior de PerfilPaciente debe reutilizar la misma identidad y no duplicar DNI, contacto ni datos personales.
- **RN-M07-008:** Las pantallas clínicas continuarán trabajando con Paciente cuando el flujo requiera perfil clínico.

### 8.2 Requerimientos funcionales adicionales

### RF-M07-007 — Registrar persona consumidora sin perfil clínico

**Descripción:** Permitir alta mínima de una persona para reservar o participar en servicios no clínicos sin crear Historia Clínica.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Registrar persona consumidora sin perfil clínico** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Busca identidad existente por documento y datos definidos por política.
6. Si no existe, crea Persona con datos personales y contacto mínimos.
7. No crea HistoriaClinica, CasoClinico ni antecedentes automáticamente.
8. Persiste los cambios de forma atómica y consistente.
9. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
10. Registra auditoría y eventos de dominio aplicables.
11. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Evitar duplicados de identidad según reglas vigentes.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M07-007-01:** el happy path produce el resultado descripto.
- **CA-M07-007-02:** un usuario sin permiso no modifica información.
- **CA-M07-007-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M07-007-04:** una validación fallida no deja datos parciales.
- **CA-M07-007-05:** la operación conserva trazabilidad histórica.
- **CA-M07-007-06:** una persona que se registra solo para Yoga puede inscribirse sin convertirse en paciente clínico.

### RF-M07-008 — Activar PerfilPaciente sobre Persona existente

**Descripción:** Convertir funcionalmente a una persona en paciente cuando inicia atención clínica, reutilizando sus datos personales.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Activar PerfilPaciente sobre Persona existente** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Recupera Persona existente.
6. Crea PerfilPaciente y, cuando corresponda, Historia Clínica según las reglas de M09.
7. Mantiene reservas y participaciones anteriores asociadas a la misma identidad.
8. Persiste los cambios de forma atómica y consistente.
9. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
10. Registra auditoría y eventos de dominio aplicables.
11. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- No crear un segundo registro Persona.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M07-008-01:** el happy path produce el resultado descripto.
- **CA-M07-008-02:** un usuario sin permiso no modifica información.
- **CA-M07-008-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M07-008-04:** una validación fallida no deja datos parciales.
- **CA-M07-008-05:** la operación conserva trazabilidad histórica.
- **CA-M07-008-06:** una persona que hacía Pilates puede iniciar Kinesiología manteniendo la misma identidad e historial no clínico.

### RF-M07-009 — Consultar historial integral de servicios de la persona

**Descripción:** Permitir distinguir actividad clínica y no clínica sin mezclar sus semánticas.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Consultar historial integral de servicios de la persona** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Recupera turnos/sesiones clínicas y clases/asistencias no clínicas.
6. Presenta cada tipo con su origen y reglas de privacidad.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Información clínica solo visible para actores autorizados.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M07-009-01:** el happy path produce el resultado descripto.
- **CA-M07-009-02:** un usuario sin permiso no modifica información.
- **CA-M07-009-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M07-009-04:** una validación fallida no deja datos parciales.
- **CA-M07-009-05:** la operación conserva trazabilidad histórica.
- **CA-M07-009-06:** el historial muestra asistencias de actividad sin incorporarlas como evoluciones de Historia Clínica.

### RF-M07-010 — Evitar creación clínica automática por consumo no clínico

**Descripción:** Aplicar una regla de protección de dominio que impida crear Historia Clínica, Caso Clínico o Sesión por una mera inscripción/asistencia no clínica.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Evitar creación clínica automática por consumo no clínico** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Evalúa `requiereCasoClinico` y `generaRegistroClinico` de la Oferta efectiva.
6. Solo deriva al flujo clínico cuando la configuración lo exige.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Nunca inferir por categoría o nombre del servicio.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M07-010-01:** el happy path produce el resultado descripto.
- **CA-M07-010-02:** un usuario sin permiso no modifica información.
- **CA-M07-010-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M07-010-04:** una validación fallida no deja datos parciales.
- **CA-M07-010-05:** la operación conserva trazabilidad histórica.
- **CA-M07-010-06:** Pilates General configurado no clínico no genera entidades clínicas aunque se registre asistencia.

### 8.3 Requerimientos no funcionales adicionales

### RNF-M07-009 — Extensión de servicios

Las nuevas reglas deben resolverse en backend/dominio y no inferirse desde nombres visibles del servicio.

### RNF-M07-010 — Extensión de servicios

Las consultas ampliadas deben mantener paginación, índices y tiempos de respuesta compatibles con el volumen operativo.

### RNF-M07-011 — Extensión de servicios

La incorporación de clases/servicios no debe degradar ni alterar datos históricos del flujo clínico existente.

### 8.4 Casos borde adicionales de QA

- Persona existente sin perfil clínico que luego inicia rehabilitación.
- Paciente clínico que además toma una actividad no clínica.
- Dos altas concurrentes con el mismo documento.

---

# M08 — Coberturas del Paciente

## 1. Objetivo

Registrar coberturas vigentes e históricas y permitir operar siempre como Particular.

## 2. Actores

- Administrativo
- Paciente según alcance

## 3. Dependencias

- M07 Pacientes
- M15 Financiadores
- M16 Convenios
- M17 Autorizaciones

## 4. Reglas de negocio

- **RN-M08-001:** PARTICULAR siempre debe estar disponible.
- **RN-M08-002:** Al seleccionar financiador solo se listan sus planes activos/vigentes.
- **RN-M08-003:** Cambiar cobertura actual no modifica atenciones anteriores.
- **RN-M08-004:** Cobertura del paciente no implica que exista convenio en el consultorio.

## 5. Requerimientos funcionales detallados

### RF-M08-001 — Agregar cobertura

**Descripción:** Vincular paciente, financiador, plan, afiliado y vigencia.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Agregar cobertura** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Vincular paciente, financiador, plan, afiliado y vigencia.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M08-001-01:** el happy path produce el resultado descripto.
- **CA-M08-001-02:** un usuario sin permiso no modifica información.
- **CA-M08-001-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M08-001-04:** una validación fallida no deja datos parciales.
- **CA-M08-001-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M08-001-06:** los históricos relacionados siguen siendo consultables.

### RF-M08-002 — Editar cobertura

**Descripción:** Actualizar datos no históricos permitidos.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Editar cobertura** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Actualizar datos no históricos permitidos.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M08-002-01:** el happy path produce el resultado descripto.
- **CA-M08-002-02:** un usuario sin permiso no modifica información.
- **CA-M08-002-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M08-002-04:** una validación fallida no deja datos parciales.
- **CA-M08-002-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M08-002-06:** los históricos relacionados siguen siendo consultables.

### RF-M08-003 — Finalizar vigencia

**Descripción:** Cerrar cobertura sin borrar referencias previas.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Finalizar vigencia** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Cerrar cobertura sin borrar referencias previas.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M08-003-01:** el happy path produce el resultado descripto.
- **CA-M08-003-02:** un usuario sin permiso no modifica información.
- **CA-M08-003-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M08-003-04:** una validación fallida no deja datos parciales.
- **CA-M08-003-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M08-003-06:** los históricos relacionados siguen siendo consultables.

### RF-M08-004 — Seleccionar cobertura para atención

**Descripción:** Elegir la cobertura que se utilizará en turno/recepción.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Seleccionar cobertura para atención** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Elegir la cobertura que se utilizará en turno/recepción.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M08-004-01:** el happy path produce el resultado descripto.
- **CA-M08-004-02:** un usuario sin permiso no modifica información.
- **CA-M08-004-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M08-004-04:** una validación fallida no deja datos parciales.
- **CA-M08-004-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M08-004-06:** los históricos relacionados siguen siendo consultables.

### RF-M08-005 — Seleccionar Particular

**Descripción:** Atender como particular incluso si el paciente tiene cobertura.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Seleccionar Particular** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Atender como particular incluso si el paciente tiene cobertura.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M08-005-01:** el happy path produce el resultado descripto.
- **CA-M08-005-02:** un usuario sin permiso no modifica información.
- **CA-M08-005-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M08-005-04:** una validación fallida no deja datos parciales.
- **CA-M08-005-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M08-005-06:** los históricos relacionados siguen siendo consultables.

## 6. Requerimientos no funcionales específicos

### RNF-M08-001 — Seguridad

Validar tenant, rol y permiso en backend; aplicar mínimo privilegio.

### RNF-M08-002 — Integridad

Evitar estados parcialmente persistidos y proteger relaciones históricas.

### RNF-M08-003 — Concurrencia

Revalidar recursos compartidos y usar locking/restricciones cuando exista riesgo de doble operación.

### RNF-M08-004 — Rendimiento

Listados paginados, filtros indexables y carga solo de información necesaria.

### RNF-M08-005 — Usabilidad

Interacciones compactas, mensajes funcionales y sin exponer identificadores técnicos como dato principal.

### RNF-M08-006 — Auditoría

Registrar operaciones sensibles con actor y fecha.

### RNF-M08-007 — Observabilidad

Errores y tiempos relevantes deben ser medibles sin registrar datos sensibles.

### RNF-M08-008 — Mantenibilidad

Reglas de negocio críticas deben residir en backend/dominio y no duplicarse entre pantallas.

## 7. Casos borde mínimos de QA

- Entidad dada de baja entre la apertura y la confirmación.
- Dos usuarios intentando operar simultáneamente sobre el mismo recurso.
- Cambio de tenant/consultorio antes de confirmar.
- Reintento del request luego de timeout.
- Referencia histórica a catálogos o usuarios inactivos.
- Rol revocado entre lectura y confirmación.
- Paginación, ordenamiento y filtros con volumen alto.
- Datos incompletos provenientes de una migración histórica.

## 8. Ampliación funcional integrada — Servicios, Clases y Actividades Terapéuticas

Esta sección amplía **Coberturas del Paciente** con las capacidades requeridas para que AKINE soporte servicios clínicos, terapéuticos, preventivos y de bienestar sin alterar el comportamiento ya definido para el flujo clínico tradicional.

### 8.1 Reglas de negocio adicionales

- **RN-M08-005:** La cobertura de una Persona/Paciente no implica que toda Oferta de Servicio sea facturable a esa cobertura.
- **RN-M08-006:** La elegibilidad se resuelve por Oferta, convenio, plan, vigencia y documentación.

### 8.2 Requerimientos funcionales adicionales

### RF-M08-006 — Resolver cobertura aplicable por Oferta de Servicio

**Descripción:** Determinar si una cobertura del paciente puede utilizarse para una Oferta concreta.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Resolver cobertura aplicable por Oferta de Servicio** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Recupera cobertura vigente del paciente.
6. Valida que la Oferta admita obra social/financiador.
7. Resuelve convenio y plan vigente.
8. Persiste los cambios de forma atómica y consistente.
9. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
10. Registra auditoría y eventos de dominio aplicables.
11. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- No aplicar cobertura solo porque el paciente la posee.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M08-006-01:** el happy path produce el resultado descripto.
- **CA-M08-006-02:** un usuario sin permiso no modifica información.
- **CA-M08-006-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M08-006-04:** una validación fallida no deja datos parciales.
- **CA-M08-006-05:** la operación conserva trazabilidad histórica.
- **CA-M08-006-06:** un paciente con obra social puede pagar Pilates particular si la Oferta no admite cobertura.

### RF-M08-007 — Seleccionar condición de pago particular cuando no aplica cobertura

**Descripción:** Permitir continuar la reserva o prestación bajo condición particular sin alterar la cobertura general del paciente.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Seleccionar condición de pago particular cuando no aplica cobertura** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Mantiene cobertura del paciente sin cambios.
6. Marca la operación específica como particular con trazabilidad.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- La selección particular debe reflejarse en la obligación económica correspondiente.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M08-007-01:** el happy path produce el resultado descripto.
- **CA-M08-007-02:** un usuario sin permiso no modifica información.
- **CA-M08-007-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M08-007-04:** una validación fallida no deja datos parciales.
- **CA-M08-007-05:** la operación conserva trazabilidad histórica.
- **CA-M08-007-06:** usar particular para una actividad no elimina ni desactiva la obra social del paciente.

### 8.3 Requerimientos no funcionales adicionales

### RNF-M08-009 — Extensión de servicios

Las nuevas reglas deben resolverse en backend/dominio y no inferirse desde nombres visibles del servicio.

### RNF-M08-010 — Extensión de servicios

Las consultas ampliadas deben mantener paginación, índices y tiempos de respuesta compatibles con el volumen operativo.

### RNF-M08-011 — Extensión de servicios

La incorporación de clases/servicios no debe degradar ni alterar datos históricos del flujo clínico existente.

### 8.4 Casos borde adicionales de QA

- Cobertura vigente pero Oferta no cubierta.
- Cambio de plan entre compra de pack y asistencia.

---

# M09 — Historia Clínica

## 1. Objetivo

Mantener antecedentes y contexto clínico transversal con trazabilidad y permisos.

## 2. Actores

- Profesional
- Administrativo con acceso limitado

## 3. Dependencias

- M07 Pacientes
- M10 Casos
- M25 Adjuntos
- M24 Auditoría

## 4. Reglas de negocio

- **RN-M09-001:** Historia Clínica no es una lista plana de sesiones.
- **RN-M09-002:** Casos Clínicos organizan los problemas concretos.
- **RN-M09-003:** No duplicar datos administrativos del paciente.
- **RN-M09-004:** Cambios clínicos sensibles deben ser trazables.

## 5. Requerimientos funcionales detallados

### RF-M09-001 — Crear Historia Clínica

**Descripción:** Inicializar la HC del paciente cuando corresponda.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Crear Historia Clínica** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Inicializar la HC del paciente cuando corresponda.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M09-001-01:** el happy path produce el resultado descripto.
- **CA-M09-001-02:** un usuario sin permiso no modifica información.
- **CA-M09-001-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M09-001-04:** una validación fallida no deja datos parciales.
- **CA-M09-001-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M09-001-06:** los históricos relacionados siguen siendo consultables.

### RF-M09-002 — Actualizar antecedentes

**Descripción:** Registrar antecedentes médicos, quirúrgicos, alergias y medicación.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Actualizar antecedentes** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Registrar antecedentes médicos, quirúrgicos, alergias y medicación.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M09-002-01:** el happy path produce el resultado descripto.
- **CA-M09-002-02:** un usuario sin permiso no modifica información.
- **CA-M09-002-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M09-002-04:** una validación fallida no deja datos parciales.
- **CA-M09-002-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M09-002-06:** los históricos relacionados siguen siendo consultables.

### RF-M09-003 — Adjuntar estudio

**Descripción:** Vincular documentación clínica a la entidad correcta.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Adjuntar estudio** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Vincular documentación clínica a la entidad correcta.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M09-003-01:** el happy path produce el resultado descripto.
- **CA-M09-003-02:** un usuario sin permiso no modifica información.
- **CA-M09-003-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M09-003-04:** una validación fallida no deja datos parciales.
- **CA-M09-003-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M09-003-06:** los históricos relacionados siguen siendo consultables.

### RF-M09-004 — Consultar timeline clínico

**Descripción:** Ver hitos longitudinales sin mezclar casos.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Consultar timeline clínico** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Ver hitos longitudinales sin mezclar casos.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M09-004-01:** el happy path produce el resultado descripto.
- **CA-M09-004-02:** un usuario sin permiso no modifica información.
- **CA-M09-004-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M09-004-04:** una validación fallida no deja datos parciales.
- **CA-M09-004-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M09-004-06:** los históricos relacionados siguen siendo consultables.

### RF-M09-005 — Consultar casos asociados

**Descripción:** Navegar desde HC a cada Caso Clínico.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Consultar casos asociados** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Navegar desde HC a cada Caso Clínico.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M09-005-01:** el happy path produce el resultado descripto.
- **CA-M09-005-02:** un usuario sin permiso no modifica información.
- **CA-M09-005-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M09-005-04:** una validación fallida no deja datos parciales.
- **CA-M09-005-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M09-005-06:** los históricos relacionados siguen siendo consultables.

### RF-M09-006 — Auditar cambios clínicos

**Descripción:** Consultar versiones/cambios autorizados.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Auditar cambios clínicos** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Consultar versiones/cambios autorizados.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M09-006-01:** el happy path produce el resultado descripto.
- **CA-M09-006-02:** un usuario sin permiso no modifica información.
- **CA-M09-006-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M09-006-04:** una validación fallida no deja datos parciales.
- **CA-M09-006-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M09-006-06:** los históricos relacionados siguen siendo consultables.

## 6. Requerimientos no funcionales específicos

### RNF-M09-001 — Seguridad

Validar tenant, rol y permiso en backend; aplicar mínimo privilegio.

### RNF-M09-002 — Integridad

Evitar estados parcialmente persistidos y proteger relaciones históricas.

### RNF-M09-003 — Concurrencia

Revalidar recursos compartidos y usar locking/restricciones cuando exista riesgo de doble operación.

### RNF-M09-004 — Rendimiento

Listados paginados, filtros indexables y carga solo de información necesaria.

### RNF-M09-005 — Usabilidad

Interacciones compactas, mensajes funcionales y sin exponer identificadores técnicos como dato principal.

### RNF-M09-006 — Auditoría

Registrar operaciones sensibles con actor y fecha.

### RNF-M09-007 — Observabilidad

Errores y tiempos relevantes deben ser medibles sin registrar datos sensibles.

### RNF-M09-008 — Mantenibilidad

Reglas de negocio críticas deben residir en backend/dominio y no duplicarse entre pantallas.

## 7. Casos borde mínimos de QA

- Entidad dada de baja entre la apertura y la confirmación.
- Dos usuarios intentando operar simultáneamente sobre el mismo recurso.
- Cambio de tenant/consultorio antes de confirmar.
- Reintento del request luego de timeout.
- Referencia histórica a catálogos o usuarios inactivos.
- Rol revocado entre lectura y confirmación.
- Paginación, ordenamiento y filtros con volumen alto.
- Datos incompletos provenientes de una migración histórica.

## 8. Ampliación funcional integrada — Servicios, Clases y Actividades Terapéuticas

Esta sección amplía **Historia Clínica** con las capacidades requeridas para que AKINE soporte servicios clínicos, terapéuticos, preventivos y de bienestar sin alterar el comportamiento ya definido para el flujo clínico tradicional.

### 8.1 Reglas de negocio adicionales

- **RN-M09-005:** Las asistencias no clínicas no forman parte de la Historia Clínica.
- **RN-M09-006:** En actividades grupales clínicas, cada participante genera información clínica individual asociada a su propio Caso Clínico.
- **RN-M09-007:** No existe evolución clínica colectiva compartida como reemplazo de los registros individuales.

### 8.2 Requerimientos funcionales adicionales

### RF-M09-007 — Excluir participaciones no clínicas de Historia Clínica

**Descripción:** Evitar que clases preventivas o de bienestar se registren como atenciones clínicas.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Excluir participaciones no clínicas de Historia Clínica** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Clasifica la participación según configuración efectiva de la Oferta.
6. Mantiene la asistencia en su módulo operativo sin crear entrada clínica.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- `generaRegistroClinico=false` impide crear evolución clínica.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M09-007-01:** el happy path produce el resultado descripto.
- **CA-M09-007-02:** un usuario sin permiso no modifica información.
- **CA-M09-007-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M09-007-04:** una validación fallida no deja datos parciales.
- **CA-M09-007-05:** la operación conserva trazabilidad histórica.
- **CA-M09-007-06:** una asistencia a Yoga queda fuera del timeline clínico.

### RF-M09-008 — Registrar evolución individual desde actividad grupal clínica

**Descripción:** Incorporar a la Historia Clínica la atención individual producida dentro de una clase grupal configurada como clínica.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Registrar evolución individual desde actividad grupal clínica** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Para cada participante recupera su Caso Clínico seleccionado.
6. Crea/actualiza el registro clínico individual correspondiente a esa persona.
7. Asocia referencia al evento grupal para trazabilidad sin compartir contenido clínico entre participantes.
8. Persiste los cambios de forma atómica y consistente.
9. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
10. Registra auditoría y eventos de dominio aplicables.
11. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Cada participante clínico debe poseer Caso compatible cuando `requiereCasoClinico=true`.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M09-008-01:** el happy path produce el resultado descripto.
- **CA-M09-008-02:** un usuario sin permiso no modifica información.
- **CA-M09-008-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M09-008-04:** una validación fallida no deja datos parciales.
- **CA-M09-008-05:** la operación conserva trazabilidad histórica.
- **CA-M09-008-06:** dos participantes de la misma clase pueden registrar evoluciones distintas y privadas.

### 8.3 Requerimientos no funcionales adicionales

### RNF-M09-009 — Extensión de servicios

Las nuevas reglas deben resolverse en backend/dominio y no inferirse desde nombres visibles del servicio.

### RNF-M09-010 — Extensión de servicios

Las consultas ampliadas deben mantener paginación, índices y tiempos de respuesta compatibles con el volumen operativo.

### RNF-M09-011 — Extensión de servicios

La incorporación de clases/servicios no debe degradar ni alterar datos históricos del flujo clínico existente.

### 8.4 Casos borde adicionales de QA

- Clase híbrida con un participante sin Caso Clínico válido.
- Consulta de Historia Clínica no debe exponer nombres de otros participantes de la clase.

---

# M10 — Casos Clínicos

## 1. Objetivo

Representar cada problema clínico concreto y contener sus sesiones y plan.

## 2. Actores

- Profesional

## 3. Dependencias

- M09 HC
- M11 Plan
- M14 Sesiones

## 4. Reglas de negocio

- **RN-M10-001:** Un paciente puede tener varios casos.
- **RN-M10-002:** Puede haber más de un caso activo si clínicamente corresponde.
- **RN-M10-003:** Las sesiones se numeran desde 1 dentro de cada caso.
- **RN-M10-004:** Nunca sumar o renumerar sesiones de casos distintos como un único tratamiento.

## 5. Requerimientos funcionales detallados

### RF-M10-001 — Crear Caso Clínico

**Descripción:** Abrir un problema/episodio de tratamiento.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Crear Caso Clínico** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Abrir un problema/episodio de tratamiento.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M10-001-01:** el happy path produce el resultado descripto.
- **CA-M10-001-02:** un usuario sin permiso no modifica información.
- **CA-M10-001-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M10-001-04:** una validación fallida no deja datos parciales.
- **CA-M10-001-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M10-001-06:** los históricos relacionados siguen siendo consultables.

### RF-M10-002 — Listar casos del paciente

**Descripción:** Mostrar activos primero y luego cerrados.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Listar casos del paciente** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Mostrar activos primero y luego cerrados.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M10-002-01:** el happy path produce el resultado descripto.
- **CA-M10-002-02:** un usuario sin permiso no modifica información.
- **CA-M10-002-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M10-002-04:** una validación fallida no deja datos parciales.
- **CA-M10-002-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M10-002-06:** los históricos relacionados siguen siendo consultables.

### RF-M10-003 — Seleccionar caso

**Descripción:** Cargar exclusivamente información y sesiones de ese caso.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Seleccionar caso** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Cargar exclusivamente información y sesiones de ese caso.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M10-003-01:** el happy path produce el resultado descripto.
- **CA-M10-003-02:** un usuario sin permiso no modifica información.
- **CA-M10-003-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M10-003-04:** una validación fallida no deja datos parciales.
- **CA-M10-003-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M10-003-06:** los históricos relacionados siguen siendo consultables.

### RF-M10-004 — Editar caso activo

**Descripción:** Actualizar datos clínicos permitidos.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Editar caso activo** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Actualizar datos clínicos permitidos.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M10-004-01:** el happy path produce el resultado descripto.
- **CA-M10-004-02:** un usuario sin permiso no modifica información.
- **CA-M10-004-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M10-004-04:** una validación fallida no deja datos parciales.
- **CA-M10-004-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M10-004-06:** los históricos relacionados siguen siendo consultables.

### RF-M10-005 — Cerrar caso

**Descripción:** Registrar fecha, resultado/motivo y estado CERRADO.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Cerrar caso** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Registrar fecha, resultado/motivo y estado CERRADO.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M10-005-01:** el happy path produce el resultado descripto.
- **CA-M10-005-02:** un usuario sin permiso no modifica información.
- **CA-M10-005-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M10-005-04:** una validación fallida no deja datos parciales.
- **CA-M10-005-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M10-005-06:** los históricos relacionados siguen siendo consultables.

### RF-M10-006 — Reabrir caso

**Descripción:** Reactivar con motivo y auditoría si la política lo permite.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Reabrir caso** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Reactivar con motivo y auditoría si la política lo permite.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M10-006-01:** el happy path produce el resultado descripto.
- **CA-M10-006-02:** un usuario sin permiso no modifica información.
- **CA-M10-006-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M10-006-04:** una validación fallida no deja datos parciales.
- **CA-M10-006-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M10-006-06:** los históricos relacionados siguen siendo consultables.

## 6. Requerimientos no funcionales específicos

### RNF-M10-001 — Seguridad

Validar tenant, rol y permiso en backend; aplicar mínimo privilegio.

### RNF-M10-002 — Integridad

Evitar estados parcialmente persistidos y proteger relaciones históricas.

### RNF-M10-003 — Concurrencia

Revalidar recursos compartidos y usar locking/restricciones cuando exista riesgo de doble operación.

### RNF-M10-004 — Rendimiento

Listados paginados, filtros indexables y carga solo de información necesaria.

### RNF-M10-005 — Usabilidad

Interacciones compactas, mensajes funcionales y sin exponer identificadores técnicos como dato principal.

### RNF-M10-006 — Auditoría

Registrar operaciones sensibles con actor y fecha.

### RNF-M10-007 — Observabilidad

Errores y tiempos relevantes deben ser medibles sin registrar datos sensibles.

### RNF-M10-008 — Mantenibilidad

Reglas de negocio críticas deben residir en backend/dominio y no duplicarse entre pantallas.

## 7. Casos borde mínimos de QA

- Entidad dada de baja entre la apertura y la confirmación.
- Dos usuarios intentando operar simultáneamente sobre el mismo recurso.
- Cambio de tenant/consultorio antes de confirmar.
- Reintento del request luego de timeout.
- Referencia histórica a catálogos o usuarios inactivos.
- Rol revocado entre lectura y confirmación.
- Paginación, ordenamiento y filtros con volumen alto.
- Datos incompletos provenientes de una migración histórica.

## 8. Ampliación funcional integrada — Servicios, Clases y Actividades Terapéuticas

Esta sección amplía **Casos Clínicos** con las capacidades requeridas para que AKINE soporte servicios clínicos, terapéuticos, preventivos y de bienestar sin alterar el comportamiento ya definido para el flujo clínico tradicional.

### 8.1 Reglas de negocio adicionales

- **RN-M10-005:** No se debe crear Caso Clínico con el nombre de una actividad preventiva únicamente para satisfacer relaciones técnicas.
- **RN-M10-006:** La necesidad de Caso Clínico se determina por la Oferta efectiva.
- **RN-M10-007:** En una clase clínica grupal, cada participante se asocia individualmente a su propio Caso Clínico.

### 8.2 Requerimientos funcionales adicionales

### RF-M10-007 — Validar requisito de Caso Clínico por Oferta

**Descripción:** Exigir o dispensar Caso Clínico según la configuración de la Oferta de Servicio.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Validar requisito de Caso Clínico por Oferta** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Lee `requiereCasoClinico` de la Oferta vigente.
6. Si es verdadero, obliga a seleccionar un Caso activo compatible para la prestación clínica.
7. Si es falso, no crea ni solicita Caso artificial.
8. Persiste los cambios de forma atómica y consistente.
9. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
10. Registra auditoría y eventos de dominio aplicables.
11. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Caso debe pertenecer a la Persona/Paciente participante y al tenant correcto.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M10-007-01:** el happy path produce el resultado descripto.
- **CA-M10-007-02:** un usuario sin permiso no modifica información.
- **CA-M10-007-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M10-007-04:** una validación fallida no deja datos parciales.
- **CA-M10-007-05:** la operación conserva trazabilidad histórica.
- **CA-M10-007-06:** Pilates General no requiere Caso; Readaptación Deportiva configurada clínica sí lo requiere.

### RF-M10-008 — Asociar Caso por participante en actividad grupal clínica

**Descripción:** Permitir que cada inscripción clínica seleccione un Caso distinto sin definir un Caso global para toda la clase.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Asociar Caso por participante en actividad grupal clínica** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Recupera casos activos de cada participante.
6. Registra la asociación a nivel de participante/prestación.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- No reutilizar Caso de otro paciente.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M10-008-01:** el happy path produce el resultado descripto.
- **CA-M10-008-02:** un usuario sin permiso no modifica información.
- **CA-M10-008-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M10-008-04:** una validación fallida no deja datos parciales.
- **CA-M10-008-05:** la operación conserva trazabilidad histórica.
- **CA-M10-008-06:** María puede tratar Lumbalgia y Juan LCA dentro de la misma clase clínica.

### 8.3 Requerimientos no funcionales adicionales

### RNF-M10-009 — Extensión de servicios

Las nuevas reglas deben resolverse en backend/dominio y no inferirse desde nombres visibles del servicio.

### RNF-M10-010 — Extensión de servicios

Las consultas ampliadas deben mantener paginación, índices y tiempos de respuesta compatibles con el volumen operativo.

### RNF-M10-011 — Extensión de servicios

La incorporación de clases/servicios no debe degradar ni alterar datos históricos del flujo clínico existente.

### 8.4 Casos borde adicionales de QA

- Participante con varios casos activos.
- Caso cerrado antes de ejecutarse la clase.

---

# M11 — Plan de Tratamiento

## 1. Objetivo

Definir la intención terapéutica, objetivos, frecuencia y cantidades estimadas/autorizadas.

## 2. Actores

- Profesional
- Administrativo solo para datos de autorización cuando corresponda

## 3. Dependencias

- M10 Caso
- M17 Autorizaciones
- M12 Turnos

## 4. Reglas de negocio

- **RN-M11-001:** Plan no equivale a sesiones realizadas.
- **RN-M11-002:** Cantidad estimada y autorizada son valores distintos.
- **RN-M11-003:** Modificar el plan no cambia tratamientos históricos.
- **RN-M11-004:** Completar cantidad estimada no cierra automáticamente el caso.

## 5. Requerimientos funcionales detallados

### RF-M11-001 — Crear Plan de Tratamiento

**Descripción:** Definir objetivos, frecuencia, duración y prácticas previstas.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Crear Plan de Tratamiento** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Definir objetivos, frecuencia, duración y prácticas previstas.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M11-001-01:** el happy path produce el resultado descripto.
- **CA-M11-001-02:** un usuario sin permiso no modifica información.
- **CA-M11-001-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M11-001-04:** una validación fallida no deja datos parciales.
- **CA-M11-001-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M11-001-06:** los históricos relacionados siguen siendo consultables.

### RF-M11-002 — Activar plan

**Descripción:** Pasar de borrador a plan vigente.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Activar plan** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Pasar de borrador a plan vigente.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M11-002-01:** el happy path produce el resultado descripto.
- **CA-M11-002-02:** un usuario sin permiso no modifica información.
- **CA-M11-002-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M11-002-04:** una validación fallida no deja datos parciales.
- **CA-M11-002-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M11-002-06:** los históricos relacionados siguen siendo consultables.

### RF-M11-003 — Registrar cantidad autorizada

**Descripción:** Vincular límite administrativo sin reemplazar la estimación clínica.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Registrar cantidad autorizada** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Vincular límite administrativo sin reemplazar la estimación clínica.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M11-003-01:** el happy path produce el resultado descripto.
- **CA-M11-003-02:** un usuario sin permiso no modifica información.
- **CA-M11-003-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M11-003-04:** una validación fallida no deja datos parciales.
- **CA-M11-003-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M11-003-06:** los históricos relacionados siguen siendo consultables.

### RF-M11-004 — Modificar plan

**Descripción:** Ajustar estrategia conservando trazabilidad.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Modificar plan** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Ajustar estrategia conservando trazabilidad.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M11-004-01:** el happy path produce el resultado descripto.
- **CA-M11-004-02:** un usuario sin permiso no modifica información.
- **CA-M11-004-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M11-004-04:** una validación fallida no deja datos parciales.
- **CA-M11-004-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M11-004-06:** los históricos relacionados siguen siendo consultables.

### RF-M11-005 — Consultar avance

**Descripción:** Comparar estimado, autorizado y realizado.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Consultar avance** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Comparar estimado, autorizado y realizado.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M11-005-01:** el happy path produce el resultado descripto.
- **CA-M11-005-02:** un usuario sin permiso no modifica información.
- **CA-M11-005-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M11-005-04:** una validación fallida no deja datos parciales.
- **CA-M11-005-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M11-005-06:** los históricos relacionados siguen siendo consultables.

### RF-M11-006 — Finalizar/suspender plan

**Descripción:** Cerrar la planificación sin eliminar sesiones.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Finalizar/suspender plan** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Cerrar la planificación sin eliminar sesiones.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M11-006-01:** el happy path produce el resultado descripto.
- **CA-M11-006-02:** un usuario sin permiso no modifica información.
- **CA-M11-006-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M11-006-04:** una validación fallida no deja datos parciales.
- **CA-M11-006-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M11-006-06:** los históricos relacionados siguen siendo consultables.

## 6. Requerimientos no funcionales específicos

### RNF-M11-001 — Seguridad

Validar tenant, rol y permiso en backend; aplicar mínimo privilegio.

### RNF-M11-002 — Integridad

Evitar estados parcialmente persistidos y proteger relaciones históricas.

### RNF-M11-003 — Concurrencia

Revalidar recursos compartidos y usar locking/restricciones cuando exista riesgo de doble operación.

### RNF-M11-004 — Rendimiento

Listados paginados, filtros indexables y carga solo de información necesaria.

### RNF-M11-005 — Usabilidad

Interacciones compactas, mensajes funcionales y sin exponer identificadores técnicos como dato principal.

### RNF-M11-006 — Auditoría

Registrar operaciones sensibles con actor y fecha.

### RNF-M11-007 — Observabilidad

Errores y tiempos relevantes deben ser medibles sin registrar datos sensibles.

### RNF-M11-008 — Mantenibilidad

Reglas de negocio críticas deben residir en backend/dominio y no duplicarse entre pantallas.

## 7. Casos borde mínimos de QA

- Entidad dada de baja entre la apertura y la confirmación.
- Dos usuarios intentando operar simultáneamente sobre el mismo recurso.
- Cambio de tenant/consultorio antes de confirmar.
- Reintento del request luego de timeout.
- Referencia histórica a catálogos o usuarios inactivos.
- Rol revocado entre lectura y confirmación.
- Paginación, ordenamiento y filtros con volumen alto.
- Datos incompletos provenientes de una migración histórica.

## 8. Ampliación funcional integrada — Servicios, Clases y Actividades Terapéuticas

Esta sección amplía **Plan de Tratamiento** con las capacidades requeridas para que AKINE soporte servicios clínicos, terapéuticos, preventivos y de bienestar sin alterar el comportamiento ya definido para el flujo clínico tradicional.

### 8.1 Reglas de negocio adicionales

- **RN-M11-005:** El Plan de Tratamiento continúa siendo clínico y no es obligatorio para actividades no clínicas.
- **RN-M11-006:** Una Oferta clínica puede formar parte de la estrategia de un Plan sin convertir toda actividad del mismo servicio en clínica para otros usuarios.

### 8.2 Requerimientos funcionales adicionales

### RF-M11-007 — Vincular Oferta clínica a Plan de Tratamiento

**Descripción:** Permitir que un Plan indique uno o más servicios/ofertas como medios para alcanzar objetivos terapéuticos.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Vincular Oferta clínica a Plan de Tratamiento** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Selecciona Oferta vigente y clínicamente compatible.
6. Registra frecuencia/cantidad estimada dentro del plan cuando corresponda.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- No forzar vínculo para ofertas no clínicas.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M11-007-01:** el happy path produce el resultado descripto.
- **CA-M11-007-02:** un usuario sin permiso no modifica información.
- **CA-M11-007-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M11-007-04:** una validación fallida no deja datos parciales.
- **CA-M11-007-05:** la operación conserva trazabilidad histórica.
- **CA-M11-007-06:** Readaptación Deportiva puede formar parte del plan de LCA y Pilates General permanecer fuera de planes.

### RF-M11-008 — Controlar sesiones estimadas/autorizadas en servicios clínicos

**Descripción:** Aplicar cantidades y autorizaciones del Plan también cuando la prestación se ejecuta dentro de una modalidad grupal clínica.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Controlar sesiones estimadas/autorizadas en servicios clínicos** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Cuenta únicamente prestaciones clínicas imputadas al Caso/Plan.
6. No suma asistencias no clínicas del mismo paciente.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- La numeración y conteo permanecen por Caso Clínico.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M11-008-01:** el happy path produce el resultado descripto.
- **CA-M11-008-02:** un usuario sin permiso no modifica información.
- **CA-M11-008-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M11-008-04:** una validación fallida no deja datos parciales.
- **CA-M11-008-05:** la operación conserva trazabilidad histórica.
- **CA-M11-008-06:** asistencias de bienestar no incrementan la cantidad de sesiones del Caso.

### 8.3 Requerimientos no funcionales adicionales

### RNF-M11-009 — Extensión de servicios

Las nuevas reglas deben resolverse en backend/dominio y no inferirse desde nombres visibles del servicio.

### RNF-M11-010 — Extensión de servicios

Las consultas ampliadas deben mantener paginación, índices y tiempos de respuesta compatibles con el volumen operativo.

### RNF-M11-011 — Extensión de servicios

La incorporación de clases/servicios no debe degradar ni alterar datos históricos del flujo clínico existente.

### 8.4 Casos borde adicionales de QA

- Mismo paciente usa versión clínica y general del mismo Servicio.

---

# M12 — Agenda, Disponibilidad y Turnos

## 1. Objetivo

Gestionar reservas de agenda usando disponibilidad real de consultorio, profesional y recursos.

## 2. Actores

- Administrativo
- Profesional
- Paciente

## 3. Dependencias

- M03 Consultorio
- M04 Boxes
- M05 Disponibilidad
- M07 Paciente
- M10 Caso

## 4. Reglas de negocio

- **RN-M12-001:** Turno es reserva, no atención.
- **RN-M12-002:** Cancelar no elimina físicamente.
- **RN-M12-003:** Reprogramar conserva trazabilidad.
- **RN-M12-004:** La confirmación debe prevenir doble reserva.
- **RN-M12-005:** La agenda aplica excepciones, feriados y bloqueos.

## 5. Requerimientos funcionales detallados

### RF-M12-001 — Buscar disponibilidad

**Descripción:** Calcular slots libres según restricciones.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Buscar disponibilidad** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Calcular slots libres según restricciones.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M12-001-01:** el happy path produce el resultado descripto.
- **CA-M12-001-02:** un usuario sin permiso no modifica información.
- **CA-M12-001-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M12-001-04:** una validación fallida no deja datos parciales.
- **CA-M12-001-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M12-001-06:** los históricos relacionados siguen siendo consultables.

### RF-M12-002 — Crear turno

**Descripción:** Reservar un slot luego de revalidar disponibilidad.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Crear turno** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Reservar un slot luego de revalidar disponibilidad.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M12-002-01:** el happy path produce el resultado descripto.
- **CA-M12-002-02:** un usuario sin permiso no modifica información.
- **CA-M12-002-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M12-002-04:** una validación fallida no deja datos parciales.
- **CA-M12-002-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M12-002-06:** los históricos relacionados siguen siendo consultables.

### RF-M12-003 — Confirmar turno

**Descripción:** Cambiar estado y notificar cuando corresponda.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Confirmar turno** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Cambiar estado y notificar cuando corresponda.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M12-003-01:** el happy path produce el resultado descripto.
- **CA-M12-003-02:** un usuario sin permiso no modifica información.
- **CA-M12-003-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M12-003-04:** una validación fallida no deja datos parciales.
- **CA-M12-003-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M12-003-06:** los históricos relacionados siguen siendo consultables.

### RF-M12-004 — Cancelar turno

**Descripción:** Liberar recursos conservando historial.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Cancelar turno** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Liberar recursos conservando historial.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M12-004-01:** el happy path produce el resultado descripto.
- **CA-M12-004-02:** un usuario sin permiso no modifica información.
- **CA-M12-004-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M12-004-04:** una validación fallida no deja datos parciales.
- **CA-M12-004-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M12-004-06:** los históricos relacionados siguen siendo consultables.

### RF-M12-005 — Reprogramar turno

**Descripción:** Mover la reserva de forma atómica y trazable.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Reprogramar turno** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Mover la reserva de forma atómica y trazable.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M12-005-01:** el happy path produce el resultado descripto.
- **CA-M12-005-02:** un usuario sin permiso no modifica información.
- **CA-M12-005-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M12-005-04:** una validación fallida no deja datos parciales.
- **CA-M12-005-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M12-005-06:** los históricos relacionados siguen siendo consultables.

### RF-M12-006 — Marcar asistencia

**Descripción:** Registrar llegada y hora real.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Marcar asistencia** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Registrar llegada y hora real.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M12-006-01:** el happy path produce el resultado descripto.
- **CA-M12-006-02:** un usuario sin permiso no modifica información.
- **CA-M12-006-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M12-006-04:** una validación fallida no deja datos parciales.
- **CA-M12-006-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M12-006-06:** los históricos relacionados siguen siendo consultables.

### RF-M12-007 — Marcar ausencia

**Descripción:** Registrar no asistencia sin borrar el turno.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Marcar ausencia** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Registrar no asistencia sin borrar el turno.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M12-007-01:** el happy path produce el resultado descripto.
- **CA-M12-007-02:** un usuario sin permiso no modifica información.
- **CA-M12-007-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M12-007-04:** una validación fallida no deja datos parciales.
- **CA-M12-007-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M12-007-06:** los históricos relacionados siguen siendo consultables.

### RF-M12-008 — Consultar historial de estados

**Descripción:** Mostrar cambios de estado y actor.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Consultar historial de estados** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Mostrar cambios de estado y actor.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M12-008-01:** el happy path produce el resultado descripto.
- **CA-M12-008-02:** un usuario sin permiso no modifica información.
- **CA-M12-008-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M12-008-04:** una validación fallida no deja datos parciales.
- **CA-M12-008-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M12-008-06:** los históricos relacionados siguen siendo consultables.

## 6. Requerimientos no funcionales específicos

### RNF-M12-001 — Seguridad

Validar tenant, rol y permiso en backend; aplicar mínimo privilegio.

### RNF-M12-002 — Integridad

Evitar estados parcialmente persistidos y proteger relaciones históricas.

### RNF-M12-003 — Concurrencia

Revalidar recursos compartidos y usar locking/restricciones cuando exista riesgo de doble operación.

### RNF-M12-004 — Rendimiento

Listados paginados, filtros indexables y carga solo de información necesaria.

### RNF-M12-005 — Usabilidad

Interacciones compactas, mensajes funcionales y sin exponer identificadores técnicos como dato principal.

### RNF-M12-006 — Auditoría

Registrar operaciones sensibles con actor y fecha.

### RNF-M12-007 — Observabilidad

Errores y tiempos relevantes deben ser medibles sin registrar datos sensibles.

### RNF-M12-008 — Mantenibilidad

Reglas de negocio críticas deben residir en backend/dominio y no duplicarse entre pantallas.

## 7. Casos borde mínimos de QA

- Entidad dada de baja entre la apertura y la confirmación.
- Dos usuarios intentando operar simultáneamente sobre el mismo recurso.
- Cambio de tenant/consultorio antes de confirmar.
- Reintento del request luego de timeout.
- Referencia histórica a catálogos o usuarios inactivos.
- Rol revocado entre lectura y confirmación.
- Paginación, ordenamiento y filtros con volumen alto.
- Datos incompletos provenientes de una migración histórica.

## 8. Ampliación funcional integrada — Servicios, Clases y Actividades Terapéuticas

Esta sección amplía **Agenda, Disponibilidad y Turnos** con las capacidades requeridas para que AKINE soporte servicios clínicos, terapéuticos, preventivos y de bienestar sin alterar el comportamiento ya definido para el flujo clínico tradicional.

### 8.1 Reglas de negocio adicionales

- **RN-M12-006:** Turno individual permanece vigente y no debe reemplazarse abruptamente por EventoAgenda.
- **RN-M12-007:** ClaseProgramada representa un evento grupal con capacidad e inscripciones; no debe modelarse como múltiples turnos desconectados.
- **RN-M12-008:** EventoAgenda se documenta como abstracción evolutiva común para Turno y ClaseProgramada.
- **RN-M12-009:** La agenda debe mostrar servicio, profesional, espacio, horario, ocupación/capacidad y estado para eventos grupales.
- **RN-M12-010:** La disponibilidad grupal depende de horario, profesional, espacio y cupo restante.

### 8.2 Requerimientos funcionales adicionales

### RF-M12-009 — Programar clase grupal

**Descripción:** Crear una ClaseProgramada para una Oferta de modalidad GRUPAL sin generar un Turno por participante.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Programar clase grupal** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Selecciona Oferta, fecha, horario, profesional y espacio.
6. Define capacidad de la clase respetando límites de Oferta y Espacio.
7. Crea un único evento grupal disponible para inscripciones.
8. Persiste los cambios de forma atómica y consistente.
9. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
10. Registra auditoría y eventos de dominio aplicables.
11. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Oferta debe estar activa y ser grupal.
- Profesional/espacio deben estar habilitados y disponibles.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M12-009-01:** el happy path produce el resultado descripto.
- **CA-M12-009-02:** un usuario sin permiso no modifica información.
- **CA-M12-009-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M12-009-04:** una validación fallida no deja datos parciales.
- **CA-M12-009-05:** la operación conserva trazabilidad histórica.
- **CA-M12-009-06:** una clase de capacidad 8 aparece como un evento de agenda con ocupación 0/8.

### RF-M12-010 — Consultar cupos disponibles de clase

**Descripción:** Calcular disponibilidad de una actividad grupal utilizando capacidad efectiva y estados de inscripción.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Consultar cupos disponibles de clase** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Cuenta inscripciones que consumen cupo.
6. Excluye canceladas y otras que la política defina como no consumidoras.
7. Devuelve ocupación, cupos y lista de espera cuando corresponda.
8. Persiste los cambios de forma atómica y consistente.
9. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
10. Registra auditoría y eventos de dominio aplicables.
11. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- El cálculo debe ser transaccionalmente consistente al confirmar inscripción.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M12-010-01:** el happy path produce el resultado descripto.
- **CA-M12-010-02:** un usuario sin permiso no modifica información.
- **CA-M12-010-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M12-010-04:** una validación fallida no deja datos parciales.
- **CA-M12-010-05:** la operación conserva trazabilidad histórica.
- **CA-M12-010-06:** la agenda muestra 6/8 y admite exactamente dos confirmaciones adicionales.

### RF-M12-011 — Visualizar agenda unificada de turnos y clases

**Descripción:** Presentar en una misma agenda los eventos individuales y grupales sin perder sus diferencias operativas.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Visualizar agenda unificada de turnos y clases** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Consulta Turnos y ClasesProgramadas del período.
6. Normaliza datos de visualización sin modificar las entidades de dominio.
7. Diferencia visualmente modalidad, naturaleza/servicio y ocupación.
8. Persiste los cambios de forma atómica y consistente.
9. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
10. Registra auditoría y eventos de dominio aplicables.
11. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Filtros deben poder separar tipo de evento, servicio, profesional y espacio.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M12-011-01:** el happy path produce el resultado descripto.
- **CA-M12-011-02:** un usuario sin permiso no modifica información.
- **CA-M12-011-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M12-011-04:** una validación fallida no deja datos parciales.
- **CA-M12-011-05:** la operación conserva trazabilidad histórica.
- **CA-M12-011-06:** un turno de Kinesiología y una clase de Pilates pueden coexistir en el mismo calendario.

### RF-M12-012 — Reprogramar o cancelar clase grupal

**Descripción:** Modificar el evento grupal conservando inscripciones y aplicando reglas de notificación/reubicación.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Reprogramar o cancelar clase grupal** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Revalida nuevo horario, profesional y espacio.
6. Actualiza el evento sin recrear participantes.
7. Marca impacto sobre inscripciones y dispara notificaciones.
8. Persiste los cambios de forma atómica y consistente.
9. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
10. Registra auditoría y eventos de dominio aplicables.
11. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- No mover a espacio con capacidad inferior a ocupación confirmada sin resolución explícita.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M12-012-01:** el happy path produce el resultado descripto.
- **CA-M12-012-02:** un usuario sin permiso no modifica información.
- **CA-M12-012-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M12-012-04:** una validación fallida no deja datos parciales.
- **CA-M12-012-05:** la operación conserva trazabilidad histórica.
- **CA-M12-012-06:** reprogramar una clase conserva la lista de inscriptos y su trazabilidad.

### RF-M12-013 — Preparar abstracción EventoAgenda sin romper Turno

**Descripción:** Definir un contrato conceptual común para consultas de agenda, manteniendo persistencia y reglas específicas de Turno y ClaseProgramada.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Preparar abstracción EventoAgenda sin romper Turno** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Expone identificador, tipo, oferta/servicio, horario, profesional, espacio, capacidad y estado comunes.
6. Delega operaciones específicas al agregado correspondiente.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- No migrar automáticamente históricos de Turno a una nueva entidad destructiva.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M12-013-01:** el happy path produce el resultado descripto.
- **CA-M12-013-02:** un usuario sin permiso no modifica información.
- **CA-M12-013-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M12-013-04:** una validación fallida no deja datos parciales.
- **CA-M12-013-05:** la operación conserva trazabilidad histórica.
- **CA-M12-013-06:** la UI puede consultar agenda unificada aunque Turno y ClaseProgramada permanezcan entidades separadas.

### 8.3 Requerimientos no funcionales adicionales

### RNF-M12-009 — Extensión de servicios

Las nuevas reglas deben resolverse en backend/dominio y no inferirse desde nombres visibles del servicio.

### RNF-M12-010 — Extensión de servicios

Las consultas ampliadas deben mantener paginación, índices y tiempos de respuesta compatibles con el volumen operativo.

### RNF-M12-011 — Extensión de servicios

La incorporación de clases/servicios no debe degradar ni alterar datos históricos del flujo clínico existente.

### 8.4 Casos borde adicionales de QA

- Último cupo solicitado simultáneamente por dos personas.
- Clase cancelada con participantes que habían pagado por clase.
- Cambio de espacio con capacidad insuficiente.
- Oferta inactivada con clases futuras.

---

# M13 — Recepción y Check-in

## 1. Objetivo

Gestionar llegada, asistencia, cobertura y documentación administrativa previa a la sesión.

## 2. Actores

- Administrativo
- Profesional en consulta

## 3. Dependencias

- M12 Turnos
- M08 Cobertura
- M16 Convenios
- M17 Autorizaciones

## 4. Reglas de negocio

- **RN-M13-001:** El check-in registra hora real.
- **RN-M13-002:** Faltantes administrativos deben verse sin invadir el formulario clínico.
- **RN-M13-003:** Un faltante puede advertir sin bloquear clínicamente según política.
- **RN-M13-004:** Cambiar una atención a Particular no modifica automáticamente la cobertura maestra del paciente.

## 5. Requerimientos funcionales detallados

### RF-M13-001 — Buscar turno de recepción

**Descripción:** Localizar por DNI, paciente, hora o agenda del día.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Buscar turno de recepción** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Localizar por DNI, paciente, hora o agenda del día.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M13-001-01:** el happy path produce el resultado descripto.
- **CA-M13-001-02:** un usuario sin permiso no modifica información.
- **CA-M13-001-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M13-001-04:** una validación fallida no deja datos parciales.
- **CA-M13-001-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M13-001-06:** los históricos relacionados siguen siendo consultables.

### RF-M13-002 — Registrar check-in

**Descripción:** Confirmar llegada y pasar a espera.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Registrar check-in** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Confirmar llegada y pasar a espera.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M13-002-01:** el happy path produce el resultado descripto.
- **CA-M13-002-02:** un usuario sin permiso no modifica información.
- **CA-M13-002-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M13-002-04:** una validación fallida no deja datos parciales.
- **CA-M13-002-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M13-002-06:** los históricos relacionados siguen siendo consultables.

### RF-M13-003 — Validar cobertura

**Descripción:** Resolver financiador, plan y convenio aplicable.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Validar cobertura** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Resolver financiador, plan y convenio aplicable.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M13-003-01:** el happy path produce el resultado descripto.
- **CA-M13-003-02:** un usuario sin permiso no modifica información.
- **CA-M13-003-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M13-003-04:** una validación fallida no deja datos parciales.
- **CA-M13-003-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M13-003-06:** los históricos relacionados siguen siendo consultables.

### RF-M13-004 — Validar documentación

**Descripción:** Controlar orden, autorización y requisitos.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Validar documentación** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Controlar orden, autorización y requisitos.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M13-004-01:** el happy path produce el resultado descripto.
- **CA-M13-004-02:** un usuario sin permiso no modifica información.
- **CA-M13-004-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M13-004-04:** una validación fallida no deja datos parciales.
- **CA-M13-004-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M13-004-06:** los históricos relacionados siguen siendo consultables.

### RF-M13-005 — Cambiar atención a Particular

**Descripción:** Continuar cuando cobertura no resulta aplicable.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Cambiar atención a Particular** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Continuar cuando cobertura no resulta aplicable.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M13-005-01:** el happy path produce el resultado descripto.
- **CA-M13-005-02:** un usuario sin permiso no modifica información.
- **CA-M13-005-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M13-005-04:** una validación fallida no deja datos parciales.
- **CA-M13-005-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M13-005-06:** los históricos relacionados siguen siendo consultables.

### RF-M13-006 — Enviar a espera

**Descripción:** Dejar al paciente disponible para inicio profesional.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Enviar a espera** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Dejar al paciente disponible para inicio profesional.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M13-006-01:** el happy path produce el resultado descripto.
- **CA-M13-006-02:** un usuario sin permiso no modifica información.
- **CA-M13-006-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M13-006-04:** una validación fallida no deja datos parciales.
- **CA-M13-006-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M13-006-06:** los históricos relacionados siguen siendo consultables.

## 6. Requerimientos no funcionales específicos

### RNF-M13-001 — Seguridad

Validar tenant, rol y permiso en backend; aplicar mínimo privilegio.

### RNF-M13-002 — Integridad

Evitar estados parcialmente persistidos y proteger relaciones históricas.

### RNF-M13-003 — Concurrencia

Revalidar recursos compartidos y usar locking/restricciones cuando exista riesgo de doble operación.

### RNF-M13-004 — Rendimiento

Listados paginados, filtros indexables y carga solo de información necesaria.

### RNF-M13-005 — Usabilidad

Interacciones compactas, mensajes funcionales y sin exponer identificadores técnicos como dato principal.

### RNF-M13-006 — Auditoría

Registrar operaciones sensibles con actor y fecha.

### RNF-M13-007 — Observabilidad

Errores y tiempos relevantes deben ser medibles sin registrar datos sensibles.

### RNF-M13-008 — Mantenibilidad

Reglas de negocio críticas deben residir en backend/dominio y no duplicarse entre pantallas.

## 7. Casos borde mínimos de QA

- Entidad dada de baja entre la apertura y la confirmación.
- Dos usuarios intentando operar simultáneamente sobre el mismo recurso.
- Cambio de tenant/consultorio antes de confirmar.
- Reintento del request luego de timeout.
- Referencia histórica a catálogos o usuarios inactivos.
- Rol revocado entre lectura y confirmación.
- Paginación, ordenamiento y filtros con volumen alto.
- Datos incompletos provenientes de una migración histórica.

## 8. Ampliación funcional integrada — Servicios, Clases y Actividades Terapéuticas

Esta sección amplía **Recepción y Check-in** con las capacidades requeridas para que AKINE soporte servicios clínicos, terapéuticos, preventivos y de bienestar sin alterar el comportamiento ya definido para el flujo clínico tradicional.

### 8.1 Reglas de negocio adicionales

- **RN-M13-005:** La recepción debe soportar check-in individual y control de asistencia de clases grupales.
- **RN-M13-006:** Registrar asistencia no implica generar una Sesión Clínica si la Oferta no lo requiere.
- **RN-M13-007:** La condición económica del participante debe poder verificarse sin bloquear la atención clínica salvo política explícita.

### 8.2 Requerimientos funcionales adicionales

### RF-M13-007 — Registrar llegada de participante a clase

**Descripción:** Permitir a recepción confirmar presencia de una persona inscripta en una ClaseProgramada.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Registrar llegada de participante a clase** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Busca inscripción por persona, documento o clase.
6. Registra llegada/check-in preservando el estado de inscripción.
7. Expone situación de pase, abono o pago para gestión administrativa.
8. Persiste los cambios de forma atómica y consistente.
9. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
10. Registra auditoría y eventos de dominio aplicables.
11. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Persona debe estar inscripta o registrarse mediante flujo autorizado de incorporación tardía.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M13-007-01:** el happy path produce el resultado descripto.
- **CA-M13-007-02:** un usuario sin permiso no modifica información.
- **CA-M13-007-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M13-007-04:** una validación fallida no deja datos parciales.
- **CA-M13-007-05:** la operación conserva trazabilidad histórica.
- **CA-M13-007-06:** la llegada a Pilates General actualiza asistencia sin abrir una Sesión Clínica.

### RF-M13-008 — Gestionar asistencia masiva de clase

**Descripción:** Permitir marcar en una lista compacta quién asistió, estuvo ausente o canceló.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Gestionar asistencia masiva de clase** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Carga inscriptos de la clase.
6. Permite cambios por participante con actualización transaccional independiente.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- No aplicar un único estado de asistencia a todos sin confirmación explícita.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M13-008-01:** el happy path produce el resultado descripto.
- **CA-M13-008-02:** un usuario sin permiso no modifica información.
- **CA-M13-008-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M13-008-04:** una validación fallida no deja datos parciales.
- **CA-M13-008-05:** la operación conserva trazabilidad histórica.
- **CA-M13-008-06:** cada participante conserva su estado propio.

### RF-M13-009 — Derivar participante al flujo clínico cuando corresponde

**Descripción:** Al realizar check-in de una Oferta clínica, preparar el acceso a su Caso y registro individual de atención.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Derivar participante al flujo clínico cuando corresponde** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Valida `generaRegistroClinico` y `requiereCasoClinico`.
6. Identifica Caso seleccionado o solicita resolverlo antes de la atención cuando sea obligatorio.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- No exponer datos clínicos de otros participantes.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M13-009-01:** el happy path produce el resultado descripto.
- **CA-M13-009-02:** un usuario sin permiso no modifica información.
- **CA-M13-009-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M13-009-04:** una validación fallida no deja datos parciales.
- **CA-M13-009-05:** la operación conserva trazabilidad histórica.
- **CA-M13-009-06:** en Pilates Clínico cada participante puede ingresar posteriormente a su propia atención clínica.

### 8.3 Requerimientos no funcionales adicionales

### RNF-M13-009 — Extensión de servicios

Las nuevas reglas deben resolverse en backend/dominio y no inferirse desde nombres visibles del servicio.

### RNF-M13-010 — Extensión de servicios

Las consultas ampliadas deben mantener paginación, índices y tiempos de respuesta compatibles con el volumen operativo.

### RNF-M13-011 — Extensión de servicios

La incorporación de clases/servicios no debe degradar ni alterar datos históricos del flujo clínico existente.

### 8.4 Casos borde adicionales de QA

- Persona llega sin inscripción a clase con cupo.
- Inscripto con pase vencido.
- Clase clínica con documentación OS faltante.

---

# M14 — Sesiones y Atenciones Clínicas

## 1. Objetivo

Registrar la atención efectivamente realizada con evaluación, tratamiento, resultado y evolución.

## 2. Actores

- Profesional
- Administrativo solo para datos administrativos restringidos

## 3. Dependencias

- M10 Caso
- M11 Plan
- M12 Turno
- M13 Check-in
- M18 Obligaciones
- M24 Auditoría

## 4. Reglas de negocio

- **RN-M14-001:** Cada sesión pertenece a un único Caso Clínico.
- **RN-M14-002:** NumeroSesion es correlativo dentro del caso.
- **RN-M14-003:** Sesión rápida y evaluación completa requieren distinta profundidad.
- **RN-M14-004:** Tratamiento planificado no equivale a realizado.
- **RN-M14-005:** Cierre clínico no depende del cobro.
- **RN-M14-006:** Una sesión cerrada no se modifica silenciosamente.

## 5. Requerimientos funcionales detallados

### RF-M14-001 — Iniciar sesión

**Descripción:** Abrir la atención y registrar hora de inicio.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Iniciar sesión** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Abrir la atención y registrar hora de inicio.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M14-001-01:** el happy path produce el resultado descripto.
- **CA-M14-001-02:** un usuario sin permiso no modifica información.
- **CA-M14-001-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M14-001-04:** una validación fallida no deja datos parciales.
- **CA-M14-001-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M14-001-06:** los históricos relacionados siguen siendo consultables.

### RF-M14-002 — Autocompletar contexto

**Descripción:** Traer paciente, caso, profesional, cobertura, turno y sesión previa.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Autocompletar contexto** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Traer paciente, caso, profesional, cobertura, turno y sesión previa.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M14-002-01:** el happy path produce el resultado descripto.
- **CA-M14-002-02:** un usuario sin permiso no modifica información.
- **CA-M14-002-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M14-002-04:** una validación fallida no deja datos parciales.
- **CA-M14-002-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M14-002-06:** los históricos relacionados siguen siendo consultables.

### RF-M14-003 — Registrar evaluación base

**Descripción:** Capturar dolor, evolución, objetivo y limitación funcional.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Registrar evaluación base** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Capturar dolor, evolución, objetivo y limitación funcional.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M14-003-01:** el happy path produce el resultado descripto.
- **CA-M14-003-02:** un usuario sin permiso no modifica información.
- **CA-M14-003-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M14-003-04:** una validación fallida no deja datos parciales.
- **CA-M14-003-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M14-003-06:** los históricos relacionados siguen siendo consultables.

### RF-M14-004 — Registrar examen físico

**Descripción:** Cargar ROM, fuerza, función, marcha, tests y medidas cuando aplica.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Registrar examen físico** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Cargar ROM, fuerza, función, marcha, tests y medidas cuando aplica.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M14-004-01:** el happy path produce el resultado descripto.
- **CA-M14-004-02:** un usuario sin permiso no modifica información.
- **CA-M14-004-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M14-004-04:** una validación fallida no deja datos parciales.
- **CA-M14-004-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M14-004-06:** los históricos relacionados siguen siendo consultables.

### RF-M14-005 — Registrar múltiples tratamientos

**Descripción:** Documentar prácticas, técnicas, parámetros, zonas y duración.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Registrar múltiples tratamientos** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Documentar prácticas, técnicas, parámetros, zonas y duración.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M14-005-01:** el happy path produce el resultado descripto.
- **CA-M14-005-02:** un usuario sin permiso no modifica información.
- **CA-M14-005-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M14-005-04:** una validación fallida no deja datos parciales.
- **CA-M14-005-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M14-005-06:** los históricos relacionados siguen siendo consultables.

### RF-M14-006 — Registrar resultado

**Descripción:** Guardar respuesta, tolerancia, evolución e indicaciones.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Registrar resultado** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Guardar respuesta, tolerancia, evolución e indicaciones.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M14-006-01:** el happy path produce el resultado descripto.
- **CA-M14-006-02:** un usuario sin permiso no modifica información.
- **CA-M14-006-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M14-006-04:** una validación fallida no deja datos parciales.
- **CA-M14-006-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M14-006-06:** los históricos relacionados siguen siendo consultables.

### RF-M14-007 — Registrar próxima conducta

**Descripción:** Continuar, ajustar plan, reevaluar, alta, derivar, suspender, etc.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Registrar próxima conducta** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Continuar, ajustar plan, reevaluar, alta, derivar, suspender, etc.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M14-007-01:** el happy path produce el resultado descripto.
- **CA-M14-007-02:** un usuario sin permiso no modifica información.
- **CA-M14-007-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M14-007-04:** una validación fallida no deja datos parciales.
- **CA-M14-007-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M14-007-06:** los históricos relacionados siguen siendo consultables.

### RF-M14-008 — Finalizar sesión

**Descripción:** Validar mínimos, asignar correlativo del caso y cerrar clínicamente.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Finalizar sesión** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Validar mínimos, asignar correlativo del caso y cerrar clínicamente.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M14-008-01:** el happy path produce el resultado descripto.
- **CA-M14-008-02:** un usuario sin permiso no modifica información.
- **CA-M14-008-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M14-008-04:** una validación fallida no deja datos parciales.
- **CA-M14-008-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M14-008-06:** los históricos relacionados siguen siendo consultables.

### RF-M14-009 — Guardar borrador/autosave

**Descripción:** Evitar pérdida de datos durante atención.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Guardar borrador/autosave** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Evitar pérdida de datos durante atención.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M14-009-01:** el happy path produce el resultado descripto.
- **CA-M14-009-02:** un usuario sin permiso no modifica información.
- **CA-M14-009-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M14-009-04:** una validación fallida no deja datos parciales.
- **CA-M14-009-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M14-009-06:** los históricos relacionados siguen siendo consultables.

### RF-M14-010 — Corregir sesión finalizada

**Descripción:** Crear enmienda/versionado con motivo y auditoría.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Corregir sesión finalizada** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Crear enmienda/versionado con motivo y auditoría.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M14-010-01:** el happy path produce el resultado descripto.
- **CA-M14-010-02:** un usuario sin permiso no modifica información.
- **CA-M14-010-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M14-010-04:** una validación fallida no deja datos parciales.
- **CA-M14-010-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M14-010-06:** los históricos relacionados siguen siendo consultables.

## 6. Requerimientos no funcionales específicos

### RNF-M14-001 — Seguridad

Validar tenant, rol y permiso en backend; aplicar mínimo privilegio.

### RNF-M14-002 — Integridad

Evitar estados parcialmente persistidos y proteger relaciones históricas.

### RNF-M14-003 — Concurrencia

Revalidar recursos compartidos y usar locking/restricciones cuando exista riesgo de doble operación.

### RNF-M14-004 — Rendimiento

Listados paginados, filtros indexables y carga solo de información necesaria.

### RNF-M14-005 — Usabilidad

Interacciones compactas, mensajes funcionales y sin exponer identificadores técnicos como dato principal.

### RNF-M14-006 — Auditoría

Registrar operaciones sensibles con actor y fecha.

### RNF-M14-007 — Observabilidad

Errores y tiempos relevantes deben ser medibles sin registrar datos sensibles.

### RNF-M14-008 — Mantenibilidad

Reglas de negocio críticas deben residir en backend/dominio y no duplicarse entre pantallas.

## 7. Casos borde mínimos de QA

- Entidad dada de baja entre la apertura y la confirmación.
- Dos usuarios intentando operar simultáneamente sobre el mismo recurso.
- Cambio de tenant/consultorio antes de confirmar.
- Reintento del request luego de timeout.
- Referencia histórica a catálogos o usuarios inactivos.
- Rol revocado entre lectura y confirmación.
- Paginación, ordenamiento y filtros con volumen alto.
- Datos incompletos provenientes de una migración histórica.

## 8. Ampliación funcional integrada — Servicios, Clases y Actividades Terapéuticas

Esta sección amplía **Sesiones y Atenciones Clínicas** con las capacidades requeridas para que AKINE soporte servicios clínicos, terapéuticos, preventivos y de bienestar sin alterar el comportamiento ya definido para el flujo clínico tradicional.

### 8.1 Reglas de negocio adicionales

- **RN-M14-007:** Sesión/Atención Clínica y ParticipaciónActividad son conceptos diferentes.
- **RN-M14-008:** Una clase no clínica nunca genera automáticamente una Sesión.
- **RN-M14-009:** En una clase grupal clínica puede existir una prestación clínica individual por participante.
- **RN-M14-010:** La numeración de sesiones continúa siendo por Caso Clínico y no por clase, persona global ni servicio.
- **RN-M14-011:** `PrestacionRealizada` puede incorporarse como abstracción futura, sin reemplazo destructivo de Sesión.

### 8.2 Requerimientos funcionales adicionales

### RF-M14-011 — Diferenciar atención clínica de participación no clínica

**Descripción:** Resolver explícitamente qué registro se crea al concretarse una actividad según la configuración de la Oferta.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Diferenciar atención clínica de participación no clínica** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Evalúa `generaRegistroClinico`.
6. Si es falso, deriva a AsistenciaActividad.
7. Si es verdadero, habilita la creación/cierre de Atención Clínica individual.
8. Persiste los cambios de forma atómica y consistente.
9. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
10. Registra auditoría y eventos de dominio aplicables.
11. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- No inferir por modalidad grupal/individual ni por nombre.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M14-011-01:** el happy path produce el resultado descripto.
- **CA-M14-011-02:** un usuario sin permiso no modifica información.
- **CA-M14-011-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M14-011-04:** una validación fallida no deja datos parciales.
- **CA-M14-011-05:** la operación conserva trazabilidad histórica.
- **CA-M14-011-06:** una clase general produce asistencia y una clase clínica puede producir atenciones individuales.

### RF-M14-012 — Crear atención individual desde clase clínica

**Descripción:** Generar una Atención Clínica para un participante de una ClaseProgramada configurada como clínica.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Crear atención individual desde clase clínica** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Recupera participante, Caso y Plan aplicable.
6. Asocia referencia de origen a la ClaseProgramada.
7. Numera la sesión dentro del Caso Clínico.
8. Persiste los cambios de forma atómica y consistente.
9. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
10. Registra auditoría y eventos de dominio aplicables.
11. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Cada atención requiere paciente/perfil clínico y Caso cuando la Oferta lo exige.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M14-012-01:** el happy path produce el resultado descripto.
- **CA-M14-012-02:** un usuario sin permiso no modifica información.
- **CA-M14-012-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M14-012-04:** una validación fallida no deja datos parciales.
- **CA-M14-012-05:** la operación conserva trazabilidad histórica.
- **CA-M14-012-06:** dos pacientes de la misma clase obtienen números de sesión independientes según sus casos.

### RF-M14-013 — Registrar evolución independiente por participante

**Descripción:** Permitir documentar tratamiento, respuesta y evolución sin compartir datos clínicos entre miembros de la clase.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Registrar evolución independiente por participante** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Abre el contexto clínico del participante seleccionado.
6. Registra datos usando las reglas vigentes de sesión rápida/evaluación.
7. Cierra únicamente la atención de ese participante.
8. Persiste los cambios de forma atómica y consistente.
9. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
10. Registra auditoría y eventos de dominio aplicables.
11. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- No existe evolución clínica global que sustituya registros individuales.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M14-013-01:** el happy path produce el resultado descripto.
- **CA-M14-013-02:** un usuario sin permiso no modifica información.
- **CA-M14-013-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M14-013-04:** una validación fallida no deja datos parciales.
- **CA-M14-013-05:** la operación conserva trazabilidad histórica.
- **CA-M14-013-06:** cerrar la atención de María no finaliza la de Juan.

### RF-M14-014 — Registrar participación no clínica sin sesión

**Descripción:** Crear únicamente el registro operativo de asistencia para ofertas que no generan información clínica.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Registrar participación no clínica sin sesión** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Registra persona, clase/evento, fecha, asistencia, instructor y observación operativa.
6. Deriva impacto económico según esquema de cobro.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- No crear HistoriaClinica, CasoClinico, Plan ni Evolución.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M14-014-01:** el happy path produce el resultado descripto.
- **CA-M14-014-02:** un usuario sin permiso no modifica información.
- **CA-M14-014-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M14-014-04:** una validación fallida no deja datos parciales.
- **CA-M14-014-05:** la operación conserva trazabilidad histórica.
- **CA-M14-014-06:** la asistencia queda trazable y facturable sin contaminar la HC.

### RF-M14-015 — Preparar abstracción PrestacionRealizada

**Descripción:** Definir un concepto superior para reporting e integración que pueda referenciar AtenciónClínica o ParticipaciónActividad.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Preparar abstracción PrestacionRealizada** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Expone persona, oferta, fecha, profesional, origen de agenda y estado de realización.
6. Mantiene payload clínico solo en AtenciónClínica.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- La abstracción no debe permitir consultar datos clínicos sin autorización.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M14-015-01:** el happy path produce el resultado descripto.
- **CA-M14-015-02:** un usuario sin permiso no modifica información.
- **CA-M14-015-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M14-015-04:** una validación fallida no deja datos parciales.
- **CA-M14-015-05:** la operación conserva trazabilidad histórica.
- **CA-M14-015-06:** reportes pueden contar prestaciones totales sin convertir participaciones en sesiones clínicas.

### 8.3 Requerimientos no funcionales adicionales

### RNF-M14-009 — Extensión de servicios

Las nuevas reglas deben resolverse en backend/dominio y no inferirse desde nombres visibles del servicio.

### RNF-M14-010 — Extensión de servicios

Las consultas ampliadas deben mantener paginación, índices y tiempos de respuesta compatibles con el volumen operativo.

### RNF-M14-011 — Extensión de servicios

La incorporación de clases/servicios no debe degradar ni alterar datos históricos del flujo clínico existente.

### 8.4 Casos borde adicionales de QA

- Clase clínica con un participante ausente y otros atendidos.
- Atención clínica creada desde clase luego cancelada individualmente.
- Paciente con dos casos activos selecciona caso incorrecto.

---

# M15 — Financiadores, Obras Sociales y Planes

## 1. Objetivo

Mantener catálogo de financiadores y planes reutilizable por pacientes y convenios.

## 2. Actores

- Administrador de plataforma
- Administrador autorizado

## 3. Dependencias

- M08 Coberturas
- M16 Convenios

## 4. Reglas de negocio

- **RN-M15-001:** Plan pertenece a financiador.
- **RN-M15-002:** No mostrar planes inactivos en nuevas altas.
- **RN-M15-003:** No eliminar históricos.
- **RN-M15-004:** PARTICULAR es una modalidad siempre disponible aunque no sea financiador externo.

## 5. Requerimientos funcionales detallados

### RF-M15-001 — Crear financiador

**Descripción:** Registrar obra social, prepaga u otro financiador.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Crear financiador** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Registrar obra social, prepaga u otro financiador.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M15-001-01:** el happy path produce el resultado descripto.
- **CA-M15-001-02:** un usuario sin permiso no modifica información.
- **CA-M15-001-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M15-001-04:** una validación fallida no deja datos parciales.
- **CA-M15-001-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M15-001-06:** los históricos relacionados siguen siendo consultables.

### RF-M15-002 — Editar financiador

**Descripción:** Actualizar datos vigentes.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Editar financiador** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Actualizar datos vigentes.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M15-002-01:** el happy path produce el resultado descripto.
- **CA-M15-002-02:** un usuario sin permiso no modifica información.
- **CA-M15-002-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M15-002-04:** una validación fallida no deja datos parciales.
- **CA-M15-002-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M15-002-06:** los históricos relacionados siguen siendo consultables.

### RF-M15-003 — Dar baja financiador

**Descripción:** Evitar nuevas selecciones conservando históricos.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Dar baja financiador** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Evitar nuevas selecciones conservando históricos.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M15-003-01:** el happy path produce el resultado descripto.
- **CA-M15-003-02:** un usuario sin permiso no modifica información.
- **CA-M15-003-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M15-003-04:** una validación fallida no deja datos parciales.
- **CA-M15-003-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M15-003-06:** los históricos relacionados siguen siendo consultables.

### RF-M15-004 — Crear plan

**Descripción:** Agregar un plan perteneciente a un financiador.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Crear plan** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Agregar un plan perteneciente a un financiador.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M15-004-01:** el happy path produce el resultado descripto.
- **CA-M15-004-02:** un usuario sin permiso no modifica información.
- **CA-M15-004-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M15-004-04:** una validación fallida no deja datos parciales.
- **CA-M15-004-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M15-004-06:** los históricos relacionados siguen siendo consultables.

### RF-M15-005 — Editar/finalizar plan

**Descripción:** Actualizar o cerrar vigencia.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Editar/finalizar plan** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Actualizar o cerrar vigencia.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M15-005-01:** el happy path produce el resultado descripto.
- **CA-M15-005-02:** un usuario sin permiso no modifica información.
- **CA-M15-005-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M15-005-04:** una validación fallida no deja datos parciales.
- **CA-M15-005-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M15-005-06:** los históricos relacionados siguen siendo consultables.

### RF-M15-006 — Buscar financiador

**Descripción:** Ofrecer componente reutilizable de búsqueda.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Buscar financiador** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Ofrecer componente reutilizable de búsqueda.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M15-006-01:** el happy path produce el resultado descripto.
- **CA-M15-006-02:** un usuario sin permiso no modifica información.
- **CA-M15-006-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M15-006-04:** una validación fallida no deja datos parciales.
- **CA-M15-006-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M15-006-06:** los históricos relacionados siguen siendo consultables.

## 6. Requerimientos no funcionales específicos

### RNF-M15-001 — Seguridad

Validar tenant, rol y permiso en backend; aplicar mínimo privilegio.

### RNF-M15-002 — Integridad

Evitar estados parcialmente persistidos y proteger relaciones históricas.

### RNF-M15-003 — Concurrencia

Revalidar recursos compartidos y usar locking/restricciones cuando exista riesgo de doble operación.

### RNF-M15-004 — Rendimiento

Listados paginados, filtros indexables y carga solo de información necesaria.

### RNF-M15-005 — Usabilidad

Interacciones compactas, mensajes funcionales y sin exponer identificadores técnicos como dato principal.

### RNF-M15-006 — Auditoría

Registrar operaciones sensibles con actor y fecha.

### RNF-M15-007 — Observabilidad

Errores y tiempos relevantes deben ser medibles sin registrar datos sensibles.

### RNF-M15-008 — Mantenibilidad

Reglas de negocio críticas deben residir en backend/dominio y no duplicarse entre pantallas.

## 7. Casos borde mínimos de QA

- Entidad dada de baja entre la apertura y la confirmación.
- Dos usuarios intentando operar simultáneamente sobre el mismo recurso.
- Cambio de tenant/consultorio antes de confirmar.
- Reintento del request luego de timeout.
- Referencia histórica a catálogos o usuarios inactivos.
- Rol revocado entre lectura y confirmación.
- Paginación, ordenamiento y filtros con volumen alto.
- Datos incompletos provenientes de una migración histórica.

## 8. Ampliación funcional integrada — Servicios, Clases y Actividades Terapéuticas

Esta sección amplía **Financiadores, Obras Sociales y Planes** con las capacidades requeridas para que AKINE soporte servicios clínicos, terapéuticos, preventivos y de bienestar sin alterar el comportamiento ya definido para el flujo clínico tradicional.

### 8.1 Reglas de negocio adicionales

- **RN-M15-005:** La existencia de un financiador/plan no habilita automáticamente todos los Servicios.
- **RN-M15-006:** Los Servicios y Ofertas deben poder declararse particulares, financiados o mixtos.

### 8.2 Requerimientos funcionales adicionales

### RF-M15-007 — Determinar financiabilidad de Oferta de Servicio

**Descripción:** Permitir consultar si un Servicio/Oferta puede ser cubierto por un financiador y plan.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Determinar financiabilidad de Oferta de Servicio** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Consulta convenios vigentes y reglas de la Oferta.
6. Devuelve condición financiada, particular o mixta.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- No inferir cobertura a partir de categoría del servicio.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M15-007-01:** el happy path produce el resultado descripto.
- **CA-M15-007-02:** un usuario sin permiso no modifica información.
- **CA-M15-007-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M15-007-04:** una validación fallida no deja datos parciales.
- **CA-M15-007-05:** la operación conserva trazabilidad histórica.
- **CA-M15-007-06:** Pilates Clínico puede admitir OS y Pilates General del mismo consultorio ser particular.

### RF-M15-008 — Mantener catálogo de prestaciones financiables por servicio

**Descripción:** Relacionar planes/convenios con ofertas y prestaciones autorizables sin duplicar el catálogo de Servicios.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Mantener catálogo de prestaciones financiables por servicio** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Configura alcance por convenio/plan.
6. Conserva vigencias y valores históricos.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Las relaciones vencidas no se usan para nuevas obligaciones.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M15-008-01:** el happy path produce el resultado descripto.
- **CA-M15-008-02:** un usuario sin permiso no modifica información.
- **CA-M15-008-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M15-008-04:** una validación fallida no deja datos parciales.
- **CA-M15-008-05:** la operación conserva trazabilidad histórica.
- **CA-M15-008-06:** un cambio de cobertura futuro no altera liquidaciones históricas.

### 8.3 Requerimientos no funcionales adicionales

### RNF-M15-009 — Extensión de servicios

Las nuevas reglas deben resolverse en backend/dominio y no inferirse desde nombres visibles del servicio.

### RNF-M15-010 — Extensión de servicios

Las consultas ampliadas deben mantener paginación, índices y tiempos de respuesta compatibles con el volumen operativo.

### RNF-M15-011 — Extensión de servicios

La incorporación de clases/servicios no debe degradar ni alterar datos históricos del flujo clínico existente.

### 8.4 Casos borde adicionales de QA

- Mismo servicio cubierto por un plan y no por otro.

---

# M16 — Convenios y Aranceles

## 1. Objetivo

Definir relación económica entre Consultorio + Financiador + Plan con aranceles, cobertura y requisitos.

## 2. Actores

- Administrador de consultorio

## 3. Dependencias

- M03 Consultorio
- M06 Prácticas
- M15 Financiadores
- M18 Obligaciones

## 4. Reglas de negocio

- **RN-M16-001:** Convenio es contextual al consultorio.
- **RN-M16-002:** Vigencias superpuestas deben controlarse.
- **RN-M16-003:** Cambiar arancel no recalcula sesiones históricas.
- **RN-M16-004:** Debe guardarse snapshot económico aplicado.
- **RN-M16-005:** Sin convenio válido no se debe asumir cobertura.

## 5. Requerimientos funcionales detallados

### RF-M16-001 — Crear convenio

**Descripción:** Definir financiador, plan, vigencia y modalidad.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Crear convenio** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Definir financiador, plan, vigencia y modalidad.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M16-001-01:** el happy path produce el resultado descripto.
- **CA-M16-001-02:** un usuario sin permiso no modifica información.
- **CA-M16-001-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M16-001-04:** una validación fallida no deja datos parciales.
- **CA-M16-001-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M16-001-06:** los históricos relacionados siguen siendo consultables.

### RF-M16-002 — Editar convenio

**Descripción:** Actualizar condiciones futuras permitidas.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Editar convenio** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Actualizar condiciones futuras permitidas.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M16-002-01:** el happy path produce el resultado descripto.
- **CA-M16-002-02:** un usuario sin permiso no modifica información.
- **CA-M16-002-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M16-002-04:** una validación fallida no deja datos parciales.
- **CA-M16-002-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M16-002-06:** los históricos relacionados siguen siendo consultables.

### RF-M16-003 — Cerrar vigencia

**Descripción:** Finalizar convenio sin borrar historial.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Cerrar vigencia** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Finalizar convenio sin borrar historial.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M16-003-01:** el happy path produce el resultado descripto.
- **CA-M16-003-02:** un usuario sin permiso no modifica información.
- **CA-M16-003-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M16-003-04:** una validación fallida no deja datos parciales.
- **CA-M16-003-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M16-003-06:** los históricos relacionados siguen siendo consultables.

### RF-M16-004 — Definir arancel por práctica

**Descripción:** Configurar valor, cobertura, coseguro/copago y vigencia.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Definir arancel por práctica** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Configurar valor, cobertura, coseguro/copago y vigencia.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M16-004-01:** el happy path produce el resultado descripto.
- **CA-M16-004-02:** un usuario sin permiso no modifica información.
- **CA-M16-004-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M16-004-04:** una validación fallida no deja datos parciales.
- **CA-M16-004-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M16-004-06:** los históricos relacionados siguen siendo consultables.

### RF-M16-005 — Definir requisitos

**Descripción:** Configurar orden, autorización, límites y documentación.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Definir requisitos** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Configurar orden, autorización, límites y documentación.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M16-005-01:** el happy path produce el resultado descripto.
- **CA-M16-005-02:** un usuario sin permiso no modifica información.
- **CA-M16-005-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M16-005-04:** una validación fallida no deja datos parciales.
- **CA-M16-005-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M16-005-06:** los históricos relacionados siguen siendo consultables.

### RF-M16-006 — Resolver convenio aplicable

**Descripción:** Determinar condiciones válidas para una fecha y atención.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Resolver convenio aplicable** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Determinar condiciones válidas para una fecha y atención.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M16-006-01:** el happy path produce el resultado descripto.
- **CA-M16-006-02:** un usuario sin permiso no modifica información.
- **CA-M16-006-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M16-006-04:** una validación fallida no deja datos parciales.
- **CA-M16-006-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M16-006-06:** los históricos relacionados siguen siendo consultables.

### RF-M16-007 — Precargar/importar convenios

**Descripción:** Procesar altas masivas con preview y errores por fila.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Precargar/importar convenios** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Procesar altas masivas con preview y errores por fila.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M16-007-01:** el happy path produce el resultado descripto.
- **CA-M16-007-02:** un usuario sin permiso no modifica información.
- **CA-M16-007-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M16-007-04:** una validación fallida no deja datos parciales.
- **CA-M16-007-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M16-007-06:** los históricos relacionados siguen siendo consultables.

## 6. Requerimientos no funcionales específicos

### RNF-M16-001 — Seguridad

Validar tenant, rol y permiso en backend; aplicar mínimo privilegio.

### RNF-M16-002 — Integridad

Evitar estados parcialmente persistidos y proteger relaciones históricas.

### RNF-M16-003 — Concurrencia

Revalidar recursos compartidos y usar locking/restricciones cuando exista riesgo de doble operación.

### RNF-M16-004 — Rendimiento

Listados paginados, filtros indexables y carga solo de información necesaria.

### RNF-M16-005 — Usabilidad

Interacciones compactas, mensajes funcionales y sin exponer identificadores técnicos como dato principal.

### RNF-M16-006 — Auditoría

Registrar operaciones sensibles con actor y fecha.

### RNF-M16-007 — Observabilidad

Errores y tiempos relevantes deben ser medibles sin registrar datos sensibles.

### RNF-M16-008 — Mantenibilidad

Reglas de negocio críticas deben residir en backend/dominio y no duplicarse entre pantallas.

## 7. Casos borde mínimos de QA

- Entidad dada de baja entre la apertura y la confirmación.
- Dos usuarios intentando operar simultáneamente sobre el mismo recurso.
- Cambio de tenant/consultorio antes de confirmar.
- Reintento del request luego de timeout.
- Referencia histórica a catálogos o usuarios inactivos.
- Rol revocado entre lectura y confirmación.
- Paginación, ordenamiento y filtros con volumen alto.
- Datos incompletos provenientes de una migración histórica.

## 8. Ampliación funcional integrada — Servicios, Clases y Actividades Terapéuticas

Esta sección amplía **Convenios y Aranceles** con las capacidades requeridas para que AKINE soporte servicios clínicos, terapéuticos, preventivos y de bienestar sin alterar el comportamiento ya definido para el flujo clínico tradicional.

### 8.1 Reglas de negocio adicionales

- **RN-M16-006:** Los convenios pueden aplicar a Ofertas de Servicio concretas y no solo a prácticas aisladas.
- **RN-M16-007:** Una Oferta puede mantener precio particular y arancel financiador simultáneamente.
- **RN-M16-008:** Los aranceles utilizados deben versionarse por vigencia y conservar snapshot económico en la obligación.

### 8.2 Requerimientos funcionales adicionales

### RF-M16-008 — Asociar Oferta de Servicio a convenio

**Descripción:** Definir que una oferta concreta puede prestarse bajo un convenio y plan determinados.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Asociar Oferta de Servicio a convenio** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Selecciona Oferta, financiador/plan y vigencia.
6. Configura arancel, coseguro y reglas administrativas aplicables.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Oferta y convenio deben pertenecer al contexto permitido.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M16-008-01:** el happy path produce el resultado descripto.
- **CA-M16-008-02:** un usuario sin permiso no modifica información.
- **CA-M16-008-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M16-008-04:** una validación fallida no deja datos parciales.
- **CA-M16-008-05:** la operación conserva trazabilidad histórica.
- **CA-M16-008-06:** la cobertura se resuelve por combinación Oferta + plan + convenio vigente.

### RF-M16-009 — Definir arancel particular por Oferta

**Descripción:** Mantener precio particular base o específico por vigencia para servicios por sesión, clase, pack o abono.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Definir arancel particular por Oferta** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Registra importe, moneda, vigencia y esquema aplicable.
6. Conserva valor histórico utilizado en cada operación.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Importes no negativos y moneda válida.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M16-009-01:** el happy path produce el resultado descripto.
- **CA-M16-009-02:** un usuario sin permiso no modifica información.
- **CA-M16-009-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M16-009-04:** una validación fallida no deja datos parciales.
- **CA-M16-009-05:** la operación conserva trazabilidad histórica.
- **CA-M16-009-06:** cambiar el precio de Pilates no modifica compras o obligaciones anteriores.

### RF-M16-010 — Resolver arancel efectivo para prestación o producto

**Descripción:** Calcular el valor económico según esquema de cobro, cobertura y vigencia.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Resolver arancel efectivo para prestación o producto** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Determina si el cargo corresponde a sesión/clase o a compra de pack/abono.
6. Aplica convenio o precio particular según condición.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- No generar doble arancel si la asistencia ya está cubierta por pack/abono.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M16-010-01:** el happy path produce el resultado descripto.
- **CA-M16-010-02:** un usuario sin permiso no modifica información.
- **CA-M16-010-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M16-010-04:** una validación fallida no deja datos parciales.
- **CA-M16-010-05:** la operación conserva trazabilidad histórica.
- **CA-M16-010-06:** una clase consumida por crédito no crea un segundo cargo por clase.

### 8.3 Requerimientos no funcionales adicionales

### RNF-M16-009 — Extensión de servicios

Las nuevas reglas deben resolverse en backend/dominio y no inferirse desde nombres visibles del servicio.

### RNF-M16-010 — Extensión de servicios

Las consultas ampliadas deben mantener paginación, índices y tiempos de respuesta compatibles con el volumen operativo.

### RNF-M16-011 — Extensión de servicios

La incorporación de clases/servicios no debe degradar ni alterar datos históricos del flujo clínico existente.

### 8.4 Casos borde adicionales de QA

- Precio cambia entre inscripción y asistencia.
- Pack comprado bajo precio viejo y consumido luego.

---

# M17 — Autorizaciones y Documentación de Cobertura

## 1. Objetivo

Gestionar órdenes, autorizaciones, vigencias y cantidades autorizadas/consumidas.

## 2. Actores

- Administrativo
- Profesional en consulta

## 3. Dependencias

- M08 Cobertura
- M10 Caso
- M16 Convenio
- M14 Sesión

## 4. Reglas de negocio

- **RN-M17-001:** Autorizado y consumido son conceptos distintos.
- **RN-M17-002:** El consumo debe ser idempotente.
- **RN-M17-003:** Faltantes generan alertas.
- **RN-M17-004:** Documentación administrativa no reemplaza el registro clínico.

## 5. Requerimientos funcionales detallados

### RF-M17-001 — Registrar autorización

**Descripción:** Guardar número, vigencia, cantidad y documentación.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Registrar autorización** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Guardar número, vigencia, cantidad y documentación.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M17-001-01:** el happy path produce el resultado descripto.
- **CA-M17-001-02:** un usuario sin permiso no modifica información.
- **CA-M17-001-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M17-001-04:** una validación fallida no deja datos parciales.
- **CA-M17-001-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M17-001-06:** los históricos relacionados siguen siendo consultables.

### RF-M17-002 — Adjuntar orden médica

**Descripción:** Vincular documento al caso/autorización.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Adjuntar orden médica** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Vincular documento al caso/autorización.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M17-002-01:** el happy path produce el resultado descripto.
- **CA-M17-002-02:** un usuario sin permiso no modifica información.
- **CA-M17-002-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M17-002-04:** una validación fallida no deja datos parciales.
- **CA-M17-002-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M17-002-06:** los históricos relacionados siguen siendo consultables.

### RF-M17-003 — Consultar saldo autorizado

**Descripción:** Mostrar autorizadas, consumidas y restantes.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Consultar saldo autorizado** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Mostrar autorizadas, consumidas y restantes.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M17-003-01:** el happy path produce el resultado descripto.
- **CA-M17-003-02:** un usuario sin permiso no modifica información.
- **CA-M17-003-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M17-003-04:** una validación fallida no deja datos parciales.
- **CA-M17-003-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M17-003-06:** los históricos relacionados siguen siendo consultables.

### RF-M17-004 — Consumir autorización

**Descripción:** Incrementar consumo de manera idempotente al concretarse la prestación.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Consumir autorización** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Incrementar consumo de manera idempotente al concretarse la prestación.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M17-004-01:** el happy path produce el resultado descripto.
- **CA-M17-004-02:** un usuario sin permiso no modifica información.
- **CA-M17-004-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M17-004-04:** una validación fallida no deja datos parciales.
- **CA-M17-004-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M17-004-06:** los históricos relacionados siguen siendo consultables.

### RF-M17-005 — Revertir consumo

**Descripción:** Corregir consumo cuando una operación sea anulada según regla.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Revertir consumo** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Corregir consumo cuando una operación sea anulada según regla.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M17-005-01:** el happy path produce el resultado descripto.
- **CA-M17-005-02:** un usuario sin permiso no modifica información.
- **CA-M17-005-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M17-005-04:** una validación fallida no deja datos parciales.
- **CA-M17-005-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M17-005-06:** los históricos relacionados siguen siendo consultables.

### RF-M17-006 — Alertar vencimiento/agote

**Descripción:** Avisar antes de superar vigencia o cantidad.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Alertar vencimiento/agote** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Avisar antes de superar vigencia o cantidad.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M17-006-01:** el happy path produce el resultado descripto.
- **CA-M17-006-02:** un usuario sin permiso no modifica información.
- **CA-M17-006-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M17-006-04:** una validación fallida no deja datos parciales.
- **CA-M17-006-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M17-006-06:** los históricos relacionados siguen siendo consultables.

## 6. Requerimientos no funcionales específicos

### RNF-M17-001 — Seguridad

Validar tenant, rol y permiso en backend; aplicar mínimo privilegio.

### RNF-M17-002 — Integridad

Evitar estados parcialmente persistidos y proteger relaciones históricas.

### RNF-M17-003 — Concurrencia

Revalidar recursos compartidos y usar locking/restricciones cuando exista riesgo de doble operación.

### RNF-M17-004 — Rendimiento

Listados paginados, filtros indexables y carga solo de información necesaria.

### RNF-M17-005 — Usabilidad

Interacciones compactas, mensajes funcionales y sin exponer identificadores técnicos como dato principal.

### RNF-M17-006 — Auditoría

Registrar operaciones sensibles con actor y fecha.

### RNF-M17-007 — Observabilidad

Errores y tiempos relevantes deben ser medibles sin registrar datos sensibles.

### RNF-M17-008 — Mantenibilidad

Reglas de negocio críticas deben residir en backend/dominio y no duplicarse entre pantallas.

## 7. Casos borde mínimos de QA

- Entidad dada de baja entre la apertura y la confirmación.
- Dos usuarios intentando operar simultáneamente sobre el mismo recurso.
- Cambio de tenant/consultorio antes de confirmar.
- Reintento del request luego de timeout.
- Referencia histórica a catálogos o usuarios inactivos.
- Rol revocado entre lectura y confirmación.
- Paginación, ordenamiento y filtros con volumen alto.
- Datos incompletos provenientes de una migración histórica.

## 8. Ampliación funcional integrada — Servicios, Clases y Actividades Terapéuticas

Esta sección amplía **Autorizaciones y Documentación de Cobertura** con las capacidades requeridas para que AKINE soporte servicios clínicos, terapéuticos, preventivos y de bienestar sin alterar el comportamiento ya definido para el flujo clínico tradicional.

### 8.1 Reglas de negocio adicionales

- **RN-M17-005:** Los requisitos documentales deben aplicarse solo cuando la Oferta y el convenio lo exijan.
- **RN-M17-006:** Una actividad no cubierta no debe solicitar artificialmente orden médica o autorización.

### 8.2 Requerimientos funcionales adicionales

### RF-M17-007 — Validar documentación por Oferta financiada

**Descripción:** Aplicar orden, autorización, carnet u otros requisitos administrativos únicamente a prestaciones cubiertas que los requieran.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Validar documentación por Oferta financiada** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Resuelve convenio de la Oferta.
6. Determina documentación necesaria.
7. Asocia documentación al participante/prestación concreta.
8. Persiste los cambios de forma atómica y consistente.
9. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
10. Registra auditoría y eventos de dominio aplicables.
11. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- No reutilizar autorización de otro Caso o persona.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M17-007-01:** el happy path produce el resultado descripto.
- **CA-M17-007-02:** un usuario sin permiso no modifica información.
- **CA-M17-007-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M17-007-04:** una validación fallida no deja datos parciales.
- **CA-M17-007-05:** la operación conserva trazabilidad histórica.
- **CA-M17-007-06:** Pilates General particular no solicita orden médica; una actividad clínica cubierta puede requerirla.

### RF-M17-008 — Controlar autorizaciones en actividad grupal clínica

**Descripción:** Validar individualmente la documentación de cada participante aunque compartan la misma ClaseProgramada.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Controlar autorizaciones en actividad grupal clínica** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Evalúa autorización por participante.
6. Permite estados distintos dentro de la misma clase.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Nunca considerar autorizada toda la clase por la autorización de un miembro.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M17-008-01:** el happy path produce el resultado descripto.
- **CA-M17-008-02:** un usuario sin permiso no modifica información.
- **CA-M17-008-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M17-008-04:** una validación fallida no deja datos parciales.
- **CA-M17-008-05:** la operación conserva trazabilidad histórica.
- **CA-M17-008-06:** María puede estar autorizada y Juan pendiente sin afectar la trazabilidad de ambos.

### 8.3 Requerimientos no funcionales adicionales

### RNF-M17-009 — Extensión de servicios

Las nuevas reglas deben resolverse en backend/dominio y no inferirse desde nombres visibles del servicio.

### RNF-M17-010 — Extensión de servicios

Las consultas ampliadas deben mantener paginación, índices y tiempos de respuesta compatibles con el volumen operativo.

### RNF-M17-011 — Extensión de servicios

La incorporación de clases/servicios no debe degradar ni alterar datos históricos del flujo clínico existente.

### 8.4 Casos borde adicionales de QA

- Autorización vence entre inscripción y fecha de clase.

---

# M18 — Obligaciones Económicas y Cuentas por Cobrar

## 1. Objetivo

Representar deuda generada por sesiones y separar responsabilidades de paciente y financiador.

## 2. Actores

- Sistema
- Administrativo
- Administrador

## 3. Dependencias

- M14 Sesiones
- M16 Convenios
- M19 Cobros
- M21 Financiadores

## 4. Reglas de negocio

- **RN-M18-001:** Crear deuda no genera caja.
- **RN-M18-002:** Una sesión puede generar múltiples obligaciones.
- **RN-M18-003:** Pago parcial reduce saldo.
- **RN-M18-004:** Importes monetarios usan decimal exacto.
- **RN-M18-005:** No cobrar nuevamente una obligación sin saldo.

## 5. Requerimientos funcionales detallados

### RF-M18-001 — Generar obligación desde sesión

**Descripción:** Crear deuda al cerrar una sesión facturable.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Generar obligación desde sesión** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Crear deuda al cerrar una sesión facturable.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M18-001-01:** el happy path produce el resultado descripto.
- **CA-M18-001-02:** un usuario sin permiso no modifica información.
- **CA-M18-001-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M18-001-04:** una validación fallida no deja datos parciales.
- **CA-M18-001-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M18-001-06:** los históricos relacionados siguen siendo consultables.

### RF-M18-002 — Separar responsable de pago

**Descripción:** Crear componentes paciente/financiador cuando corresponde.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Separar responsable de pago** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Crear componentes paciente/financiador cuando corresponde.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M18-002-01:** el happy path produce el resultado descripto.
- **CA-M18-002-02:** un usuario sin permiso no modifica información.
- **CA-M18-002-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M18-002-04:** una validación fallida no deja datos parciales.
- **CA-M18-002-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M18-002-06:** los históricos relacionados siguen siendo consultables.

### RF-M18-003 — Consultar deuda del paciente

**Descripción:** Listar obligaciones, imputaciones y saldo real.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Consultar deuda del paciente** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Listar obligaciones, imputaciones y saldo real.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M18-003-01:** el happy path produce el resultado descripto.
- **CA-M18-003-02:** un usuario sin permiso no modifica información.
- **CA-M18-003-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M18-003-04:** una validación fallida no deja datos parciales.
- **CA-M18-003-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M18-003-06:** los históricos relacionados siguen siendo consultables.

### RF-M18-004 — Consultar deuda del financiador

**Descripción:** Mostrar cuenta pendiente independiente.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Consultar deuda del financiador** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Mostrar cuenta pendiente independiente.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M18-004-01:** el happy path produce el resultado descripto.
- **CA-M18-004-02:** un usuario sin permiso no modifica información.
- **CA-M18-004-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M18-004-04:** una validación fallida no deja datos parciales.
- **CA-M18-004-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M18-004-06:** los históricos relacionados siguen siendo consultables.

### RF-M18-005 — Registrar imputación parcial

**Descripción:** Reducir saldo mediante un cobro.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Registrar imputación parcial** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Reducir saldo mediante un cobro.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M18-005-01:** el happy path produce el resultado descripto.
- **CA-M18-005-02:** un usuario sin permiso no modifica información.
- **CA-M18-005-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M18-005-04:** una validación fallida no deja datos parciales.
- **CA-M18-005-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M18-005-06:** los históricos relacionados siguen siendo consultables.

### RF-M18-006 — Anular obligación

**Descripción:** Revertir deuda con reglas y auditoría.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Anular obligación** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Revertir deuda con reglas y auditoría.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M18-006-01:** el happy path produce el resultado descripto.
- **CA-M18-006-02:** un usuario sin permiso no modifica información.
- **CA-M18-006-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M18-006-04:** una validación fallida no deja datos parciales.
- **CA-M18-006-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M18-006-06:** los históricos relacionados siguen siendo consultables.

### RF-M18-007 — Recalcular saldo derivado

**Descripción:** Actualizar estado pendiente/parcial/pagada sin alterar importe histórico.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Recalcular saldo derivado** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Actualizar estado pendiente/parcial/pagada sin alterar importe histórico.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M18-007-01:** el happy path produce el resultado descripto.
- **CA-M18-007-02:** un usuario sin permiso no modifica información.
- **CA-M18-007-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M18-007-04:** una validación fallida no deja datos parciales.
- **CA-M18-007-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M18-007-06:** los históricos relacionados siguen siendo consultables.

## 6. Requerimientos no funcionales específicos

### RNF-M18-001 — Seguridad

Validar tenant, rol y permiso en backend; aplicar mínimo privilegio.

### RNF-M18-002 — Integridad

Evitar estados parcialmente persistidos y proteger relaciones históricas.

### RNF-M18-003 — Concurrencia

Revalidar recursos compartidos y usar locking/restricciones cuando exista riesgo de doble operación.

### RNF-M18-004 — Rendimiento

Listados paginados, filtros indexables y carga solo de información necesaria.

### RNF-M18-005 — Usabilidad

Interacciones compactas, mensajes funcionales y sin exponer identificadores técnicos como dato principal.

### RNF-M18-006 — Auditoría

Registrar operaciones sensibles con actor y fecha.

### RNF-M18-007 — Observabilidad

Errores y tiempos relevantes deben ser medibles sin registrar datos sensibles.

### RNF-M18-008 — Mantenibilidad

Reglas de negocio críticas deben residir en backend/dominio y no duplicarse entre pantallas.

## 7. Casos borde mínimos de QA

- Entidad dada de baja entre la apertura y la confirmación.
- Dos usuarios intentando operar simultáneamente sobre el mismo recurso.
- Cambio de tenant/consultorio antes de confirmar.
- Reintento del request luego de timeout.
- Referencia histórica a catálogos o usuarios inactivos.
- Rol revocado entre lectura y confirmación.
- Paginación, ordenamiento y filtros con volumen alto.
- Datos incompletos provenientes de una migración histórica.

## 8. Ampliación funcional integrada — Servicios, Clases y Actividades Terapéuticas

Esta sección amplía **Obligaciones Económicas y Cuentas por Cobrar** con las capacidades requeridas para que AKINE soporte servicios clínicos, terapéuticos, preventivos y de bienestar sin alterar el comportamiento ya definido para el flujo clínico tradicional.

### 8.1 Reglas de negocio adicionales

- **RN-M18-006:** La obligación económica puede originarse en una Sesión clínica, una Asistencia por clase, la compra de un Pack/Pase o un Abono.
- **RN-M18-007:** Asistencia cubierta por crédito o abono vigente no genera nueva deuda por clase.
- **RN-M18-008:** El origen económico debe quedar identificado de forma inequívoca para impedir duplicación.
- **RN-M18-009:** Los esquemas `POR_SESION`, `POR_CLASE`, `PACK`, `CUOTA_MENSUAL`, `BONO`, `OBRA_SOCIAL` y `MIXTO` son configurables por Oferta.

### 8.2 Requerimientos funcionales adicionales

### RF-M18-008 — Generar obligación por clase asistida

**Descripción:** Crear deuda individual cuando una Oferta usa esquema POR_CLASE y la política define que el cargo se devenga con la asistencia.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Generar obligación por clase asistida** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Confirma asistencia facturable.
6. Obtiene arancel efectivo y pagador.
7. Crea obligación vinculada a la participación.
8. Persiste los cambios de forma atómica y consistente.
9. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
10. Registra auditoría y eventos de dominio aplicables.
11. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- No crear más de una obligación por la misma participación y concepto.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M18-008-01:** el happy path produce el resultado descripto.
- **CA-M18-008-02:** un usuario sin permiso no modifica información.
- **CA-M18-008-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M18-008-04:** una validación fallida no deja datos parciales.
- **CA-M18-008-05:** la operación conserva trazabilidad histórica.
- **CA-M18-008-06:** reintentar el cierre de asistencia no duplica deuda.

### RF-M18-009 — Generar obligación por compra de pack o pase

**Descripción:** Registrar la deuda al adquirir créditos anticipados para una Oferta.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Generar obligación por compra de pack o pase** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Crea operación de compra con cantidad de créditos y vigencia.
6. Genera una única obligación por el producto adquirido.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Cantidad de créditos mayor a cero y vigencia válida.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M18-009-01:** el happy path produce el resultado descripto.
- **CA-M18-009-02:** un usuario sin permiso no modifica información.
- **CA-M18-009-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M18-009-04:** una validación fallida no deja datos parciales.
- **CA-M18-009-05:** la operación conserva trazabilidad histórica.
- **CA-M18-009-06:** cada consumo posterior reduce créditos pero no crea deuda adicional por clase.

### RF-M18-010 — Generar obligación por abono periódico

**Descripción:** Crear la obligación correspondiente a un período de cobertura de servicio.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Generar obligación por abono periódico** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Define período, importe y vigencia.
6. Asocia el abono a persona y oferta.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Evitar duplicación del mismo período/producto según política.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M18-010-01:** el happy path produce el resultado descripto.
- **CA-M18-010-02:** un usuario sin permiso no modifica información.
- **CA-M18-010-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M18-010-04:** una validación fallida no deja datos parciales.
- **CA-M18-010-05:** la operación conserva trazabilidad histórica.
- **CA-M18-010-06:** un abono Agosto cubre asistencias permitidas dentro de su vigencia sin cargos unitarios.

### RF-M18-011 — Resolver obligación bajo esquema mixto

**Descripción:** Combinar financiador, coseguro, crédito, bono o cargo particular según configuración explícita.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Resolver obligación bajo esquema mixto** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Determina componentes pagadores.
6. Genera obligaciones separadas o componentes internos según modelo vigente, manteniendo trazabilidad.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- La suma de componentes debe corresponder al valor económico resuelto.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M18-011-01:** el happy path produce el resultado descripto.
- **CA-M18-011-02:** un usuario sin permiso no modifica información.
- **CA-M18-011-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M18-011-04:** una validación fallida no deja datos parciales.
- **CA-M18-011-05:** la operación conserva trazabilidad histórica.
- **CA-M18-011-06:** una prestación puede generar parte a financiador y parte a paciente sin duplicar el total.

### 8.3 Requerimientos no funcionales adicionales

### RNF-M18-009 — Extensión de servicios

Las nuevas reglas deben resolverse en backend/dominio y no inferirse desde nombres visibles del servicio.

### RNF-M18-010 — Extensión de servicios

Las consultas ampliadas deben mantener paginación, índices y tiempos de respuesta compatibles con el volumen operativo.

### RNF-M18-011 — Extensión de servicios

La incorporación de clases/servicios no debe degradar ni alterar datos históricos del flujo clínico existente.

### 8.4 Casos borde adicionales de QA

- Asistencia marcada dos veces.
- Pack sin créditos disponibles.
- Abono vencido el mismo día de la clase.
- Cambio de esquema de cobro con inscripciones futuras.

---

# M19 — Cobros, Medios de Pago y Comprobantes

## 1. Objetivo

Registrar dinero recibido, múltiples medios, imputaciones y comprobante funcional.

## 2. Actores

- Administrativo
- Administrador

## 3. Dependencias

- M18 Obligaciones
- M20 Caja
- M24 Auditoría

## 4. Reglas de negocio

- **RN-M19-001:** Suma de medios = total del cobro.
- **RN-M19-002:** UUID no es comprobante funcional.
- **RN-M19-003:** Un cobro confirmado no se edita silenciosamente.
- **RN-M19-004:** Anulación debe revertir efectos dependientes de manera consistente.

## 5. Requerimientos funcionales detallados

### RF-M19-001 — Iniciar cobro

**Descripción:** Seleccionar deudas y crear una operación borrador.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Iniciar cobro** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Seleccionar deudas y crear una operación borrador.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M19-001-01:** el happy path produce el resultado descripto.
- **CA-M19-001-02:** un usuario sin permiso no modifica información.
- **CA-M19-001-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M19-001-04:** una validación fallida no deja datos parciales.
- **CA-M19-001-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M19-001-06:** los históricos relacionados siguen siendo consultables.

### RF-M19-002 — Agregar medios de pago

**Descripción:** Componer total con efectivo, transferencia, tarjetas, QR u otros.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Agregar medios de pago** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Componer total con efectivo, transferencia, tarjetas, QR u otros.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M19-002-01:** el happy path produce el resultado descripto.
- **CA-M19-002-02:** un usuario sin permiso no modifica información.
- **CA-M19-002-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M19-002-04:** una validación fallida no deja datos parciales.
- **CA-M19-002-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M19-002-06:** los históricos relacionados siguen siendo consultables.

### RF-M19-003 — Imputar obligaciones

**Descripción:** Distribuir el importe entre una o varias deudas.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Imputar obligaciones** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Distribuir el importe entre una o varias deudas.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M19-003-01:** el happy path produce el resultado descripto.
- **CA-M19-003-02:** un usuario sin permiso no modifica información.
- **CA-M19-003-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M19-003-04:** una validación fallida no deja datos parciales.
- **CA-M19-003-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M19-003-06:** los históricos relacionados siguen siendo consultables.

### RF-M19-004 — Confirmar cobro

**Descripción:** Validar medios, imputar, emitir comprobante y generar caja.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Confirmar cobro** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Validar medios, imputar, emitir comprobante y generar caja.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M19-004-01:** el happy path produce el resultado descripto.
- **CA-M19-004-02:** un usuario sin permiso no modifica información.
- **CA-M19-004-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M19-004-04:** una validación fallida no deja datos parciales.
- **CA-M19-004-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M19-004-06:** los históricos relacionados siguen siendo consultables.

### RF-M19-005 — Emitir comprobante correlativo

**Descripción:** Generar número legible para usuario final.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Emitir comprobante correlativo** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Generar número legible para usuario final.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M19-005-01:** el happy path produce el resultado descripto.
- **CA-M19-005-02:** un usuario sin permiso no modifica información.
- **CA-M19-005-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M19-005-04:** una validación fallida no deja datos parciales.
- **CA-M19-005-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M19-005-06:** los históricos relacionados siguen siendo consultables.

### RF-M19-006 — Reimprimir/consultar comprobante

**Descripción:** Recuperar comprobante sin crear un cobro nuevo.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Reimprimir/consultar comprobante** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Recuperar comprobante sin crear un cobro nuevo.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M19-006-01:** el happy path produce el resultado descripto.
- **CA-M19-006-02:** un usuario sin permiso no modifica información.
- **CA-M19-006-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M19-006-04:** una validación fallida no deja datos parciales.
- **CA-M19-006-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M19-006-06:** los históricos relacionados siguen siendo consultables.

### RF-M19-007 — Anular cobro

**Descripción:** Revertir imputaciones y caja con trazabilidad.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Anular cobro** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Revertir imputaciones y caja con trazabilidad.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M19-007-01:** el happy path produce el resultado descripto.
- **CA-M19-007-02:** un usuario sin permiso no modifica información.
- **CA-M19-007-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M19-007-04:** una validación fallida no deja datos parciales.
- **CA-M19-007-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M19-007-06:** los históricos relacionados siguen siendo consultables.

## 6. Requerimientos no funcionales específicos

### RNF-M19-001 — Seguridad

Validar tenant, rol y permiso en backend; aplicar mínimo privilegio.

### RNF-M19-002 — Integridad

Evitar estados parcialmente persistidos y proteger relaciones históricas.

### RNF-M19-003 — Concurrencia

Revalidar recursos compartidos y usar locking/restricciones cuando exista riesgo de doble operación.

### RNF-M19-004 — Rendimiento

Listados paginados, filtros indexables y carga solo de información necesaria.

### RNF-M19-005 — Usabilidad

Interacciones compactas, mensajes funcionales y sin exponer identificadores técnicos como dato principal.

### RNF-M19-006 — Auditoría

Registrar operaciones sensibles con actor y fecha.

### RNF-M19-007 — Observabilidad

Errores y tiempos relevantes deben ser medibles sin registrar datos sensibles.

### RNF-M19-008 — Mantenibilidad

Reglas de negocio críticas deben residir en backend/dominio y no duplicarse entre pantallas.

## 7. Casos borde mínimos de QA

- Entidad dada de baja entre la apertura y la confirmación.
- Dos usuarios intentando operar simultáneamente sobre el mismo recurso.
- Cambio de tenant/consultorio antes de confirmar.
- Reintento del request luego de timeout.
- Referencia histórica a catálogos o usuarios inactivos.
- Rol revocado entre lectura y confirmación.
- Paginación, ordenamiento y filtros con volumen alto.
- Datos incompletos provenientes de una migración histórica.

## 8. Ampliación funcional integrada — Servicios, Clases y Actividades Terapéuticas

Esta sección amplía **Cobros, Medios de Pago y Comprobantes** con las capacidades requeridas para que AKINE soporte servicios clínicos, terapéuticos, preventivos y de bienestar sin alterar el comportamiento ya definido para el flujo clínico tradicional.

### 8.1 Reglas de negocio adicionales

- **RN-M19-005:** Los cobros continúan cancelando obligaciones; no deben registrarse como sinónimo de asistencia.
- **RN-M19-006:** La compra de pack/abono puede cobrarse con múltiples medios de pago usando la operatoria existente.
- **RN-M19-007:** El comprobante debe identificar el concepto comercial sin exponer UUID técnicos como dato principal.

### 8.2 Requerimientos funcionales adicionales

### RF-M19-008 — Cobrar compra de pack o abono

**Descripción:** Aplicar la operatoria de cobro existente a obligaciones originadas por productos de servicio.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Cobrar compra de pack o abono** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Recupera obligación de compra.
6. Permite uno o más medios de pago.
7. Emite comprobante y actualiza saldo de obligación.
8. Persiste los cambios de forma atómica y consistente.
9. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
10. Registra auditoría y eventos de dominio aplicables.
11. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- El cobro no acredita créditos dos veces ante reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M19-008-01:** el happy path produce el resultado descripto.
- **CA-M19-008-02:** un usuario sin permiso no modifica información.
- **CA-M19-008-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M19-008-04:** una validación fallida no deja datos parciales.
- **CA-M19-008-05:** la operación conserva trazabilidad histórica.
- **CA-M19-008-06:** una compra pagada con dos medios produce un solo pack y un comprobante coherente.

### RF-M19-009 — Cobrar clase individual adeudada

**Descripción:** Cancelar una obligación generada POR_CLASE sin modificar el registro de asistencia.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Cobrar clase individual adeudada** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Selecciona obligación pendiente de la participación.
6. Registra pago y comprobante.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- No cambiar asistencia al revertir/cancelar cobro.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M19-009-01:** el happy path produce el resultado descripto.
- **CA-M19-009-02:** un usuario sin permiso no modifica información.
- **CA-M19-009-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M19-009-04:** una validación fallida no deja datos parciales.
- **CA-M19-009-05:** la operación conserva trazabilidad histórica.
- **CA-M19-009-06:** estado económico y estado de asistencia permanecen independientes.

### RF-M19-010 — Reintegrar cobro asociado a cancelación cuando corresponde

**Descripción:** Permitir devolución/reversión económica siguiendo las reglas de caja y del producto sin borrar la participación histórica.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Reintegrar cobro asociado a cancelación cuando corresponde** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Determina política de devolución.
6. Registra reversión o saldo a favor según modelo.
7. Si correspondía crédito, coordina devolución mediante MovimientoPase.
8. Persiste los cambios de forma atómica y consistente.
9. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
10. Registra auditoría y eventos de dominio aplicables.
11. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- No devolver simultáneamente dinero y crédito salvo regla explícita.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M19-010-01:** el happy path produce el resultado descripto.
- **CA-M19-010-02:** un usuario sin permiso no modifica información.
- **CA-M19-010-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M19-010-04:** una validación fallida no deja datos parciales.
- **CA-M19-010-05:** la operación conserva trazabilidad histórica.
- **CA-M19-010-06:** una cancelación deja movimientos económicos y de crédito auditables.

### 8.3 Requerimientos no funcionales adicionales

### RNF-M19-009 — Extensión de servicios

Las nuevas reglas deben resolverse en backend/dominio y no inferirse desde nombres visibles del servicio.

### RNF-M19-010 — Extensión de servicios

Las consultas ampliadas deben mantener paginación, índices y tiempos de respuesta compatibles con el volumen operativo.

### RNF-M19-011 — Extensión de servicios

La incorporación de clases/servicios no debe degradar ni alterar datos históricos del flujo clínico existente.

### 8.4 Casos borde adicionales de QA

- Pago de pack confirmado y timeout de respuesta.
- Devolución parcial de producto.

---

# M20 — Caja Diaria

## 1. Objetivo

Registrar movimientos reales de dinero, apertura, operación, cierre y diferencias.

## 2. Actores

- Administrativo
- Administrador

## 3. Dependencias

- M19 Cobros
- M22 Egresos
- M21 Pago financiadores

## 4. Reglas de negocio

- **RN-M20-001:** Deuda no afecta caja.
- **RN-M20-002:** Solo movimientos monetarios confirmados afectan saldo.
- **RN-M20-003:** Caja cerrada no se edita silenciosamente.
- **RN-M20-004:** Diferencias deben quedar registradas y justificadas según política.

## 5. Requerimientos funcionales detallados

### RF-M20-001 — Abrir caja

**Descripción:** Crear jornada operativa con saldo inicial.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Abrir caja** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Crear jornada operativa con saldo inicial.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M20-001-01:** el happy path produce el resultado descripto.
- **CA-M20-001-02:** un usuario sin permiso no modifica información.
- **CA-M20-001-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M20-001-04:** una validación fallida no deja datos parciales.
- **CA-M20-001-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M20-001-06:** los históricos relacionados siguen siendo consultables.

### RF-M20-002 — Registrar ingreso manual

**Descripción:** Cargar ingreso autorizado no originado automáticamente.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Registrar ingreso manual** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Cargar ingreso autorizado no originado automáticamente.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M20-002-01:** el happy path produce el resultado descripto.
- **CA-M20-002-02:** un usuario sin permiso no modifica información.
- **CA-M20-002-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M20-002-04:** una validación fallida no deja datos parciales.
- **CA-M20-002-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M20-002-06:** los históricos relacionados siguen siendo consultables.

### RF-M20-003 — Registrar egreso

**Descripción:** Registrar salida de dinero.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Registrar egreso** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Registrar salida de dinero.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M20-003-01:** el happy path produce el resultado descripto.
- **CA-M20-003-02:** un usuario sin permiso no modifica información.
- **CA-M20-003-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M20-003-04:** una validación fallida no deja datos parciales.
- **CA-M20-003-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M20-003-06:** los históricos relacionados siguen siendo consultables.

### RF-M20-004 — Consultar movimientos

**Descripción:** Listar y filtrar operatoria diaria.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Consultar movimientos** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Listar y filtrar operatoria diaria.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M20-004-01:** el happy path produce el resultado descripto.
- **CA-M20-004-02:** un usuario sin permiso no modifica información.
- **CA-M20-004-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M20-004-04:** una validación fallida no deja datos parciales.
- **CA-M20-004-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M20-004-06:** los históricos relacionados siguen siendo consultables.

### RF-M20-005 — Calcular saldo teórico

**Descripción:** Sumar ingresos y restar egresos.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Calcular saldo teórico** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Sumar ingresos y restar egresos.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M20-005-01:** el happy path produce el resultado descripto.
- **CA-M20-005-02:** un usuario sin permiso no modifica información.
- **CA-M20-005-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M20-005-04:** una validación fallida no deja datos parciales.
- **CA-M20-005-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M20-005-06:** los históricos relacionados siguen siendo consultables.

### RF-M20-006 — Cerrar caja

**Descripción:** Registrar saldo declarado y diferencia.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Cerrar caja** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Registrar saldo declarado y diferencia.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M20-006-01:** el happy path produce el resultado descripto.
- **CA-M20-006-02:** un usuario sin permiso no modifica información.
- **CA-M20-006-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M20-006-04:** una validación fallida no deja datos parciales.
- **CA-M20-006-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M20-006-06:** los históricos relacionados siguen siendo consultables.

### RF-M20-007 — Consultar cierres históricos

**Descripción:** Ver resultados diarios sin modificar históricos.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Consultar cierres históricos** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Ver resultados diarios sin modificar históricos.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M20-007-01:** el happy path produce el resultado descripto.
- **CA-M20-007-02:** un usuario sin permiso no modifica información.
- **CA-M20-007-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M20-007-04:** una validación fallida no deja datos parciales.
- **CA-M20-007-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M20-007-06:** los históricos relacionados siguen siendo consultables.

## 6. Requerimientos no funcionales específicos

### RNF-M20-001 — Seguridad

Validar tenant, rol y permiso en backend; aplicar mínimo privilegio.

### RNF-M20-002 — Integridad

Evitar estados parcialmente persistidos y proteger relaciones históricas.

### RNF-M20-003 — Concurrencia

Revalidar recursos compartidos y usar locking/restricciones cuando exista riesgo de doble operación.

### RNF-M20-004 — Rendimiento

Listados paginados, filtros indexables y carga solo de información necesaria.

### RNF-M20-005 — Usabilidad

Interacciones compactas, mensajes funcionales y sin exponer identificadores técnicos como dato principal.

### RNF-M20-006 — Auditoría

Registrar operaciones sensibles con actor y fecha.

### RNF-M20-007 — Observabilidad

Errores y tiempos relevantes deben ser medibles sin registrar datos sensibles.

### RNF-M20-008 — Mantenibilidad

Reglas de negocio críticas deben residir en backend/dominio y no duplicarse entre pantallas.

## 7. Casos borde mínimos de QA

- Entidad dada de baja entre la apertura y la confirmación.
- Dos usuarios intentando operar simultáneamente sobre el mismo recurso.
- Cambio de tenant/consultorio antes de confirmar.
- Reintento del request luego de timeout.
- Referencia histórica a catálogos o usuarios inactivos.
- Rol revocado entre lectura y confirmación.
- Paginación, ordenamiento y filtros con volumen alto.
- Datos incompletos provenientes de una migración histórica.

## 8. Ampliación funcional integrada — Servicios, Clases y Actividades Terapéuticas

Esta sección amplía **Caja Diaria** con las capacidades requeridas para que AKINE soporte servicios clínicos, terapéuticos, preventivos y de bienestar sin alterar el comportamiento ya definido para el flujo clínico tradicional.

### 8.1 Reglas de negocio adicionales

- **RN-M20-005:** Los cobros de clases, packs y abonos impactan Caja únicamente cuando existe movimiento monetario real.
- **RN-M20-006:** Consumir un crédito de pase no genera movimiento de caja.
- **RN-M20-007:** Devoluciones y reversas deben conservar referencia al origen.

### 8.2 Requerimientos funcionales adicionales

### RF-M20-008 — Registrar en caja cobros de nuevos productos de servicio

**Descripción:** Incorporar ingresos por clase, pack y abono a la caja diaria usando las mismas reglas de apertura/cierre y medios de pago.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Registrar en caja cobros de nuevos productos de servicio** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Recibe movimiento desde Cobros.
6. Clasifica concepto para reportes sin crear caja paralela.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Solo registrar una vez por cobro confirmado.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M20-008-01:** el happy path produce el resultado descripto.
- **CA-M20-008-02:** un usuario sin permiso no modifica información.
- **CA-M20-008-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M20-008-04:** una validación fallida no deja datos parciales.
- **CA-M20-008-05:** la operación conserva trazabilidad histórica.
- **CA-M20-008-06:** un pack cobrado aparece como ingreso de caja; sus consumos posteriores no.

### RF-M20-009 — Registrar devolución económica de clase o producto

**Descripción:** Reflejar egreso/reversión cuando una política de cancelación devuelve dinero.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Registrar devolución económica de clase o producto** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Vincula devolución al cobro original.
6. Registra movimiento de caja con signo/tipo correspondiente.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Caja debe estar en estado compatible según reglas existentes.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M20-009-01:** el happy path produce el resultado descripto.
- **CA-M20-009-02:** un usuario sin permiso no modifica información.
- **CA-M20-009-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M20-009-04:** una validación fallida no deja datos parciales.
- **CA-M20-009-05:** la operación conserva trazabilidad histórica.
- **CA-M20-009-06:** la devolución puede rastrearse hasta el comprobante y producto original.

### RF-M20-010 — Distinguir consumo operativo de movimiento monetario

**Descripción:** Evitar que asistencias cubiertas por pase o abono se contabilicen como ingresos de caja ficticios.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Distinguir consumo operativo de movimiento monetario** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Consulta origen económico de la asistencia.
6. Solo expone consumo en reportes operativos, no como movimiento de caja.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- No crear MovimientoCaja para MovimientoPase de tipo CONSUMO.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M20-010-01:** el happy path produce el resultado descripto.
- **CA-M20-010-02:** un usuario sin permiso no modifica información.
- **CA-M20-010-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M20-010-04:** una validación fallida no deja datos parciales.
- **CA-M20-010-05:** la operación conserva trazabilidad histórica.
- **CA-M20-010-06:** ocho clases consumidas de un pack pagado previamente no generan ocho ingresos adicionales.

### 8.3 Requerimientos no funcionales adicionales

### RNF-M20-009 — Extensión de servicios

Las nuevas reglas deben resolverse en backend/dominio y no inferirse desde nombres visibles del servicio.

### RNF-M20-010 — Extensión de servicios

Las consultas ampliadas deben mantener paginación, índices y tiempos de respuesta compatibles con el volumen operativo.

### RNF-M20-011 — Extensión de servicios

La incorporación de clases/servicios no debe degradar ni alterar datos históricos del flujo clínico existente.

### 8.4 Casos borde adicionales de QA

- Compra de pack en caja de un día y consumo en otro.

---

# M21 — Presentaciones, Facturación y Cuenta Corriente de Financiadores

## 1. Objetivo

Gestionar ciclo de prestaciones desde pendiente de presentar hasta cobro y conciliación.

## 2. Actores

- Administrativo
- Administrador

## 3. Dependencias

- M18 Obligaciones
- M16 Convenios
- M17 Autorizaciones
- M20 Caja

## 4. Reglas de negocio

- **RN-M21-001:** Prestado, presentado, facturado y cobrado son estados distintos.
- **RN-M21-002:** Pago del financiador genera caja solo cuando se recibe.
- **RN-M21-003:** Una prestación no debe duplicarse en presentaciones incompatibles.
- **RN-M21-004:** Rechazar una prestación no elimina la sesión original.

## 5. Requerimientos funcionales detallados

### RF-M21-001 — Generar prestaciones elegibles

**Descripción:** Listar obligaciones financiador pendientes por período.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Generar prestaciones elegibles** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Listar obligaciones financiador pendientes por período.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M21-001-01:** el happy path produce el resultado descripto.
- **CA-M21-001-02:** un usuario sin permiso no modifica información.
- **CA-M21-001-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M21-001-04:** una validación fallida no deja datos parciales.
- **CA-M21-001-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M21-001-06:** los históricos relacionados siguen siendo consultables.

### RF-M21-002 — Crear presentación

**Descripción:** Agrupar prestaciones y calcular total.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Crear presentación** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Agrupar prestaciones y calcular total.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M21-002-01:** el happy path produce el resultado descripto.
- **CA-M21-002-02:** un usuario sin permiso no modifica información.
- **CA-M21-002-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M21-002-04:** una validación fallida no deja datos parciales.
- **CA-M21-002-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M21-002-06:** los históricos relacionados siguen siendo consultables.

### RF-M21-003 — Validar documentación de presentación

**Descripción:** Detectar items incompletos antes de confirmar.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Validar documentación de presentación** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Detectar items incompletos antes de confirmar.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M21-003-01:** el happy path produce el resultado descripto.
- **CA-M21-003-02:** un usuario sin permiso no modifica información.
- **CA-M21-003-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M21-003-04:** una validación fallida no deja datos parciales.
- **CA-M21-003-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M21-003-06:** los históricos relacionados siguen siendo consultables.

### RF-M21-004 — Confirmar presentación

**Descripción:** Marcar prestaciones incluidas y registrar envío.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Confirmar presentación** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Marcar prestaciones incluidas y registrar envío.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M21-004-01:** el happy path produce el resultado descripto.
- **CA-M21-004-02:** un usuario sin permiso no modifica información.
- **CA-M21-004-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M21-004-04:** una validación fallida no deja datos parciales.
- **CA-M21-004-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M21-004-06:** los históricos relacionados siguen siendo consultables.

### RF-M21-005 — Registrar factura

**Descripción:** Asociar comprobante/factura externa.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Registrar factura** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Asociar comprobante/factura externa.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M21-005-01:** el happy path produce el resultado descripto.
- **CA-M21-005-02:** un usuario sin permiso no modifica información.
- **CA-M21-005-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M21-005-04:** una validación fallida no deja datos parciales.
- **CA-M21-005-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M21-005-06:** los históricos relacionados siguen siendo consultables.

### RF-M21-006 — Registrar observación/rechazo

**Descripción:** Mantener débitos y motivos sin borrar prestación.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Registrar observación/rechazo** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Mantener débitos y motivos sin borrar prestación.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M21-006-01:** el happy path produce el resultado descripto.
- **CA-M21-006-02:** un usuario sin permiso no modifica información.
- **CA-M21-006-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M21-006-04:** una validación fallida no deja datos parciales.
- **CA-M21-006-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M21-006-06:** los históricos relacionados siguen siendo consultables.

### RF-M21-007 — Registrar pago financiador

**Descripción:** Cobrar total/parcial e impactar caja.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Registrar pago financiador** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Cobrar total/parcial e impactar caja.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M21-007-01:** el happy path produce el resultado descripto.
- **CA-M21-007-02:** un usuario sin permiso no modifica información.
- **CA-M21-007-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M21-007-04:** una validación fallida no deja datos parciales.
- **CA-M21-007-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M21-007-06:** los históricos relacionados siguen siendo consultables.

### RF-M21-008 — Conciliar diferencias

**Descripción:** Resolver facturado vs pagado y saldos residuales.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Conciliar diferencias** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Resolver facturado vs pagado y saldos residuales.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M21-008-01:** el happy path produce el resultado descripto.
- **CA-M21-008-02:** un usuario sin permiso no modifica información.
- **CA-M21-008-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M21-008-04:** una validación fallida no deja datos parciales.
- **CA-M21-008-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M21-008-06:** los históricos relacionados siguen siendo consultables.

## 6. Requerimientos no funcionales específicos

### RNF-M21-001 — Seguridad

Validar tenant, rol y permiso en backend; aplicar mínimo privilegio.

### RNF-M21-002 — Integridad

Evitar estados parcialmente persistidos y proteger relaciones históricas.

### RNF-M21-003 — Concurrencia

Revalidar recursos compartidos y usar locking/restricciones cuando exista riesgo de doble operación.

### RNF-M21-004 — Rendimiento

Listados paginados, filtros indexables y carga solo de información necesaria.

### RNF-M21-005 — Usabilidad

Interacciones compactas, mensajes funcionales y sin exponer identificadores técnicos como dato principal.

### RNF-M21-006 — Auditoría

Registrar operaciones sensibles con actor y fecha.

### RNF-M21-007 — Observabilidad

Errores y tiempos relevantes deben ser medibles sin registrar datos sensibles.

### RNF-M21-008 — Mantenibilidad

Reglas de negocio críticas deben residir en backend/dominio y no duplicarse entre pantallas.

## 7. Casos borde mínimos de QA

- Entidad dada de baja entre la apertura y la confirmación.
- Dos usuarios intentando operar simultáneamente sobre el mismo recurso.
- Cambio de tenant/consultorio antes de confirmar.
- Reintento del request luego de timeout.
- Referencia histórica a catálogos o usuarios inactivos.
- Rol revocado entre lectura y confirmación.
- Paginación, ordenamiento y filtros con volumen alto.
- Datos incompletos provenientes de una migración histórica.

## 8. Ampliación funcional integrada — Servicios, Clases y Actividades Terapéuticas

Esta sección amplía **Presentaciones, Facturación y Cuenta Corriente de Financiadores** con las capacidades requeridas para que AKINE soporte servicios clínicos, terapéuticos, preventivos y de bienestar sin alterar el comportamiento ya definido para el flujo clínico tradicional.

### 8.1 Reglas de negocio adicionales

- **RN-M21-005:** Las prestaciones de servicios cubiertos pueden incorporarse a presentaciones siempre que generen registro facturable y cumplan convenio/documentación.
- **RN-M21-006:** Las asistencias particulares o cubiertas por pase/abono particular no deben presentarse a financiadores.

### 8.2 Requerimientos funcionales adicionales

### RF-M21-009 — Incluir prestaciones de servicios en presentación a financiador

**Descripción:** Permitir que atenciones originadas en Ofertas cubiertas formen parte del circuito de presentación/facturación existente.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Incluir prestaciones de servicios en presentación a financiador** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Selecciona prestaciones elegibles.
6. Incluye servicio/oferta, caso, autorización y arancel según convenio.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Debe existir pagador financiador y documentación requerida.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M21-009-01:** el happy path produce el resultado descripto.
- **CA-M21-009-02:** un usuario sin permiso no modifica información.
- **CA-M21-009-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M21-009-04:** una validación fallida no deja datos parciales.
- **CA-M21-009-05:** la operación conserva trazabilidad histórica.
- **CA-M21-009-06:** una actividad clínica grupal puede generar renglones individuales por paciente.

### RF-M21-010 — Excluir consumos no financiables de presentación

**Descripción:** Impedir que participaciones no clínicas particulares o cubiertas por pack particular ingresen accidentalmente en facturación de OS.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Excluir consumos no financiables de presentación** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Evalúa pagador y origen económico.
6. Descarta de selección financiador cuando no corresponde.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- No inferir financiabilidad por nombre del servicio.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M21-010-01:** el happy path produce el resultado descripto.
- **CA-M21-010-02:** un usuario sin permiso no modifica información.
- **CA-M21-010-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M21-010-04:** una validación fallida no deja datos parciales.
- **CA-M21-010-05:** la operación conserva trazabilidad histórica.
- **CA-M21-010-06:** Pilates General particular no aparece en lote de obra social.

### 8.3 Requerimientos no funcionales adicionales

### RNF-M21-009 — Extensión de servicios

Las nuevas reglas deben resolverse en backend/dominio y no inferirse desde nombres visibles del servicio.

### RNF-M21-010 — Extensión de servicios

Las consultas ampliadas deben mantener paginación, índices y tiempos de respuesta compatibles con el volumen operativo.

### RNF-M21-011 — Extensión de servicios

La incorporación de clases/servicios no debe degradar ni alterar datos históricos del flujo clínico existente.

### 8.4 Casos borde adicionales de QA

- Misma clase con participantes OS y particulares.

---

# M22 — Egresos y Pagos a Profesionales

## 1. Objetivo

Registrar salidas monetarias y pagos a profesionales u otros beneficiarios.

## 2. Actores

- Administrativo
- Administrador

## 3. Dependencias

- M20 Caja
- M05 Profesionales

## 4. Reglas de negocio

- **RN-M22-001:** Egreso confirmado afecta caja.
- **RN-M22-002:** Anular no significa borrar.
- **RN-M22-003:** Pago a profesional no modifica sesiones históricas.

## 5. Requerimientos funcionales detallados

### RF-M22-001 — Registrar egreso

**Descripción:** Crear salida con categoría, beneficiario, importe y medio.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Registrar egreso** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Crear salida con categoría, beneficiario, importe y medio.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M22-001-01:** el happy path produce el resultado descripto.
- **CA-M22-001-02:** un usuario sin permiso no modifica información.
- **CA-M22-001-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M22-001-04:** una validación fallida no deja datos parciales.
- **CA-M22-001-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M22-001-06:** los históricos relacionados siguen siendo consultables.

### RF-M22-002 — Registrar pago a profesional

**Descripción:** Asociar período o prestaciones cuando corresponda.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Registrar pago a profesional** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Asociar período o prestaciones cuando corresponda.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M22-002-01:** el happy path produce el resultado descripto.
- **CA-M22-002-02:** un usuario sin permiso no modifica información.
- **CA-M22-002-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M22-002-04:** una validación fallida no deja datos parciales.
- **CA-M22-002-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M22-002-06:** los históricos relacionados siguen siendo consultables.

### RF-M22-003 — Adjuntar comprobante

**Descripción:** Vincular documentación del egreso.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Adjuntar comprobante** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Vincular documentación del egreso.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M22-003-01:** el happy path produce el resultado descripto.
- **CA-M22-003-02:** un usuario sin permiso no modifica información.
- **CA-M22-003-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M22-003-04:** una validación fallida no deja datos parciales.
- **CA-M22-003-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M22-003-06:** los históricos relacionados siguen siendo consultables.

### RF-M22-004 — Consultar egresos

**Descripción:** Filtrar por fecha, categoría, beneficiario y estado.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Consultar egresos** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Filtrar por fecha, categoría, beneficiario y estado.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M22-004-01:** el happy path produce el resultado descripto.
- **CA-M22-004-02:** un usuario sin permiso no modifica información.
- **CA-M22-004-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M22-004-04:** una validación fallida no deja datos parciales.
- **CA-M22-004-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M22-004-06:** los históricos relacionados siguen siendo consultables.

### RF-M22-005 — Anular egreso

**Descripción:** Crear reversión trazable y corregir caja.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Anular egreso** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Crear reversión trazable y corregir caja.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M22-005-01:** el happy path produce el resultado descripto.
- **CA-M22-005-02:** un usuario sin permiso no modifica información.
- **CA-M22-005-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M22-005-04:** una validación fallida no deja datos parciales.
- **CA-M22-005-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M22-005-06:** los históricos relacionados siguen siendo consultables.

## 6. Requerimientos no funcionales específicos

### RNF-M22-001 — Seguridad

Validar tenant, rol y permiso en backend; aplicar mínimo privilegio.

### RNF-M22-002 — Integridad

Evitar estados parcialmente persistidos y proteger relaciones históricas.

### RNF-M22-003 — Concurrencia

Revalidar recursos compartidos y usar locking/restricciones cuando exista riesgo de doble operación.

### RNF-M22-004 — Rendimiento

Listados paginados, filtros indexables y carga solo de información necesaria.

### RNF-M22-005 — Usabilidad

Interacciones compactas, mensajes funcionales y sin exponer identificadores técnicos como dato principal.

### RNF-M22-006 — Auditoría

Registrar operaciones sensibles con actor y fecha.

### RNF-M22-007 — Observabilidad

Errores y tiempos relevantes deben ser medibles sin registrar datos sensibles.

### RNF-M22-008 — Mantenibilidad

Reglas de negocio críticas deben residir en backend/dominio y no duplicarse entre pantallas.

## 7. Casos borde mínimos de QA

- Entidad dada de baja entre la apertura y la confirmación.
- Dos usuarios intentando operar simultáneamente sobre el mismo recurso.
- Cambio de tenant/consultorio antes de confirmar.
- Reintento del request luego de timeout.
- Referencia histórica a catálogos o usuarios inactivos.
- Rol revocado entre lectura y confirmación.
- Paginación, ordenamiento y filtros con volumen alto.
- Datos incompletos provenientes de una migración histórica.

## 8. Ampliación funcional integrada — Servicios, Clases y Actividades Terapéuticas

Esta sección amplía **Egresos y Pagos a Profesionales** con las capacidades requeridas para que AKINE soporte servicios clínicos, terapéuticos, preventivos y de bienestar sin alterar el comportamiento ya definido para el flujo clínico tradicional.

### 8.1 Reglas de negocio adicionales

- **RN-M22-004:** Los profesionales/instructores pueden liquidarse por sesiones, clases, horas u otras reglas sin modificar su rol de seguridad.
- **RN-M22-005:** La base de liquidación debe distinguir clase dictada de cantidad de participantes cuando la política así lo defina.

### 8.2 Requerimientos funcionales adicionales

### RF-M22-006 — Calcular base de pago por clase o actividad

**Descripción:** Permitir que una ClaseProgramada realizada participe en la liquidación de un profesional según regla configurada.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Calcular base de pago por clase o actividad** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Recupera clases efectivamente realizadas.
6. Aplica esquema de honorario configurado: por clase, hora, participante u otro soportado.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- No duplicar pago por atenciones clínicas individuales si el contrato remunera por clase única, salvo configuración explícita.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M22-006-01:** el happy path produce el resultado descripto.
- **CA-M22-006-02:** un usuario sin permiso no modifica información.
- **CA-M22-006-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M22-006-04:** una validación fallida no deja datos parciales.
- **CA-M22-006-05:** la operación conserva trazabilidad histórica.
- **CA-M22-006-06:** una clase con ocho participantes puede liquidarse como una clase si esa es la regla contractual.

### RF-M22-007 — Mantener trazabilidad entre pago profesional y servicio

**Descripción:** Relacionar el egreso con Oferta, clase o prestaciones que originan la liquidación.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Mantener trazabilidad entre pago profesional y servicio** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Guarda detalle de base calculada.
6. Permite auditoría posterior sin depender de datos actuales del servicio.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Conservar snapshot de importes utilizados.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M22-007-01:** el happy path produce el resultado descripto.
- **CA-M22-007-02:** un usuario sin permiso no modifica información.
- **CA-M22-007-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M22-007-04:** una validación fallida no deja datos parciales.
- **CA-M22-007-05:** la operación conserva trazabilidad histórica.
- **CA-M22-007-06:** un cambio futuro de tarifa profesional no altera una liquidación cerrada.

### 8.3 Requerimientos no funcionales adicionales

### RNF-M22-009 — Extensión de servicios

Las nuevas reglas deben resolverse en backend/dominio y no inferirse desde nombres visibles del servicio.

### RNF-M22-010 — Extensión de servicios

Las consultas ampliadas deben mantener paginación, índices y tiempos de respuesta compatibles con el volumen operativo.

### RNF-M22-011 — Extensión de servicios

La incorporación de clases/servicios no debe degradar ni alterar datos históricos del flujo clínico existente.

### 8.4 Casos borde adicionales de QA

- Clase cancelada luego de haber sido considerada en preliquidación.

---

# M23 — Reportes y Dashboards

## 1. Objetivo

Exponer indicadores operativos, clínicos y económicos consistentes.

## 2. Actores

- Administrador
- Profesional según permiso
- Administrativo según permiso

## 3. Dependencias

- Todos los módulos de negocio

## 4. Reglas de negocio

- **RN-M23-001:** Reportes respetan tenant, consultorio y permisos.
- **RN-M23-002:** Producido no es cobrado.
- **RN-M23-003:** Deuda no es caja.
- **RN-M23-004:** Sesiones de diferentes casos no se mezclan en numeración o conteos contextuales.

## 5. Requerimientos funcionales detallados

### RF-M23-001 — Dashboard operativo

**Descripción:** Mostrar KPIs y alertas del consultorio.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Dashboard operativo** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Mostrar KPIs y alertas del consultorio.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M23-001-01:** el happy path produce el resultado descripto.
- **CA-M23-001-02:** un usuario sin permiso no modifica información.
- **CA-M23-001-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M23-001-04:** una validación fallida no deja datos parciales.
- **CA-M23-001-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M23-001-06:** los históricos relacionados siguen siendo consultables.

### RF-M23-002 — Reporte de turnos

**Descripción:** Analizar volumen, estados, ausentismo y reprogramaciones.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Reporte de turnos** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Analizar volumen, estados, ausentismo y reprogramaciones.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M23-002-01:** el happy path produce el resultado descripto.
- **CA-M23-002-02:** un usuario sin permiso no modifica información.
- **CA-M23-002-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M23-002-04:** una validación fallida no deja datos parciales.
- **CA-M23-002-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M23-002-06:** los históricos relacionados siguen siendo consultables.

### RF-M23-003 — Reporte clínico

**Descripción:** Analizar casos/sesiones respetando separación por caso.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Reporte clínico** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Analizar casos/sesiones respetando separación por caso.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M23-003-01:** el happy path produce el resultado descripto.
- **CA-M23-003-02:** un usuario sin permiso no modifica información.
- **CA-M23-003-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M23-003-04:** una validación fallida no deja datos parciales.
- **CA-M23-003-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M23-003-06:** los históricos relacionados siguen siendo consultables.

### RF-M23-004 — Reporte económico

**Descripción:** Separar producido, deuda, cobrado, ingresos y egresos.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Reporte económico** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Separar producido, deuda, cobrado, ingresos y egresos.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M23-004-01:** el happy path produce el resultado descripto.
- **CA-M23-004-02:** un usuario sin permiso no modifica información.
- **CA-M23-004-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M23-004-04:** una validación fallida no deja datos parciales.
- **CA-M23-004-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M23-004-06:** los históricos relacionados siguen siendo consultables.

### RF-M23-005 — Reporte financiadores

**Descripción:** Mostrar prestado, presentado, facturado, cobrado y pendiente.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Reporte financiadores** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Mostrar prestado, presentado, facturado, cobrado y pendiente.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M23-005-01:** el happy path produce el resultado descripto.
- **CA-M23-005-02:** un usuario sin permiso no modifica información.
- **CA-M23-005-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M23-005-04:** una validación fallida no deja datos parciales.
- **CA-M23-005-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M23-005-06:** los históricos relacionados siguen siendo consultables.

### RF-M23-006 — Exportar reporte

**Descripción:** Generar archivo respetando filtros y permisos.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Exportar reporte** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Generar archivo respetando filtros y permisos.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M23-006-01:** el happy path produce el resultado descripto.
- **CA-M23-006-02:** un usuario sin permiso no modifica información.
- **CA-M23-006-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M23-006-04:** una validación fallida no deja datos parciales.
- **CA-M23-006-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M23-006-06:** los históricos relacionados siguen siendo consultables.

## 6. Requerimientos no funcionales específicos

### RNF-M23-001 — Seguridad

Validar tenant, rol y permiso en backend; aplicar mínimo privilegio.

### RNF-M23-002 — Integridad

Evitar estados parcialmente persistidos y proteger relaciones históricas.

### RNF-M23-003 — Concurrencia

Revalidar recursos compartidos y usar locking/restricciones cuando exista riesgo de doble operación.

### RNF-M23-004 — Rendimiento

Listados paginados, filtros indexables y carga solo de información necesaria.

### RNF-M23-005 — Usabilidad

Interacciones compactas, mensajes funcionales y sin exponer identificadores técnicos como dato principal.

### RNF-M23-006 — Auditoría

Registrar operaciones sensibles con actor y fecha.

### RNF-M23-007 — Observabilidad

Errores y tiempos relevantes deben ser medibles sin registrar datos sensibles.

### RNF-M23-008 — Mantenibilidad

Reglas de negocio críticas deben residir en backend/dominio y no duplicarse entre pantallas.

## 7. Casos borde mínimos de QA

- Entidad dada de baja entre la apertura y la confirmación.
- Dos usuarios intentando operar simultáneamente sobre el mismo recurso.
- Cambio de tenant/consultorio antes de confirmar.
- Reintento del request luego de timeout.
- Referencia histórica a catálogos o usuarios inactivos.
- Rol revocado entre lectura y confirmación.
- Paginación, ordenamiento y filtros con volumen alto.
- Datos incompletos provenientes de una migración histórica.

## 8. Ampliación funcional integrada — Servicios, Clases y Actividades Terapéuticas

Esta sección amplía **Reportes y Dashboards** con las capacidades requeridas para que AKINE soporte servicios clínicos, terapéuticos, preventivos y de bienestar sin alterar el comportamiento ya definido para el flujo clínico tradicional.

### 8.1 Reglas de negocio adicionales

- **RN-M23-005:** Los reportes deben distinguir servicio, oferta, modalidad y naturaleza sin mezclar asistencia con sesión clínica.
- **RN-M23-006:** Los indicadores económicos deben separar devengado, cobrado, créditos consumidos y movimientos de caja.
- **RN-M23-007:** La ocupación grupal se calcula sobre capacidad efectiva del evento.

### 8.2 Requerimientos funcionales adicionales

### RF-M23-007 — Reportar ocupación de clases y espacios

**Descripción:** Medir ocupación por servicio, clase, franja horaria, profesional y espacio.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Reportar ocupación de clases y espacios** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Calcula inscriptos, asistentes, ausentes y capacidad.
6. Permite agregación por período.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- No contar lista de espera como ocupación confirmada salvo indicador específico.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M23-007-01:** el happy path produce el resultado descripto.
- **CA-M23-007-02:** un usuario sin permiso no modifica información.
- **CA-M23-007-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M23-007-04:** una validación fallida no deja datos parciales.
- **CA-M23-007-05:** la operación conserva trazabilidad histórica.
- **CA-M23-007-06:** se puede identificar porcentaje de ocupación de Sala Pilates por horario.

### RF-M23-008 — Reportar continuidad del circuito de salud

**Descripción:** Analizar tránsito de personas entre rehabilitación, readaptación y actividades preventivas sin mezclar historias clínicas.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Reportar continuidad del circuito de salud** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Usa relaciones de Persona y servicios consumidos.
6. Agrega métricas sin exponer contenido clínico innecesario.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Respetar permisos y anonimización cuando aplique.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M23-008-01:** el happy path produce el resultado descripto.
- **CA-M23-008-02:** un usuario sin permiso no modifica información.
- **CA-M23-008-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M23-008-04:** una validación fallida no deja datos parciales.
- **CA-M23-008-05:** la operación conserva trazabilidad histórica.
- **CA-M23-008-06:** se puede medir cuántas personas pasan de alta clínica a un servicio preventivo.

### RF-M23-009 — Reportar rentabilidad e ingresos por servicio

**Descripción:** Consolidar obligaciones, cobros y caja por Oferta/Servicio diferenciando clases, packs y abonos.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Reportar rentabilidad e ingresos por servicio** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Agrupa devengado y cobrado.
6. Separa ingresos anticipados de consumo de créditos.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Consumir crédito no duplica ingresos.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M23-009-01:** el happy path produce el resultado descripto.
- **CA-M23-009-02:** un usuario sin permiso no modifica información.
- **CA-M23-009-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M23-009-04:** una validación fallida no deja datos parciales.
- **CA-M23-009-05:** la operación conserva trazabilidad histórica.
- **CA-M23-009-06:** reporte de Pilates muestra ventas de packs y ocupación sin sumar cada consumo como nuevo ingreso.

### RF-M23-010 — Reportar estado de pases y abonos

**Descripción:** Mostrar créditos vendidos, consumidos, disponibles, vencidos y abonos activos/vencidos.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Reportar estado de pases y abonos** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Agrega movimientos de pase y vigencias de abonos.
6. Persiste los cambios de forma atómica y consistente.
7. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
8. Registra auditoría y eventos de dominio aplicables.
9. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Saldos deben reconciliar con movimientos.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M23-010-01:** el happy path produce el resultado descripto.
- **CA-M23-010-02:** un usuario sin permiso no modifica información.
- **CA-M23-010-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M23-010-04:** una validación fallida no deja datos parciales.
- **CA-M23-010-05:** la operación conserva trazabilidad histórica.
- **CA-M23-010-06:** total de créditos disponibles coincide con suma de saldos vigentes.

### 8.3 Requerimientos no funcionales adicionales

### RNF-M23-009 — Extensión de servicios

Las nuevas reglas deben resolverse en backend/dominio y no inferirse desde nombres visibles del servicio.

### RNF-M23-010 — Extensión de servicios

Las consultas ampliadas deben mantener paginación, índices y tiempos de respuesta compatibles con el volumen operativo.

### RNF-M23-011 — Extensión de servicios

La incorporación de clases/servicios no debe degradar ni alterar datos históricos del flujo clínico existente.

### 8.4 Casos borde adicionales de QA

- Reporte con servicio renombrado: usar histórico coherente.
- Grandes volúmenes de asistencias.

---

# M24 — Auditoría y Trazabilidad

## 1. Objetivo

Registrar operaciones sensibles y permitir reconstrucción de cambios.

## 2. Actores

- Sistema
- Administrador autorizado
- Auditor

## 3. Dependencias

- Todos los módulos

## 4. Reglas de negocio

- **RN-M24-001:** Auditoría no es editable por usuarios operativos.
- **RN-M24-002:** No almacenar secretos.
- **RN-M24-003:** El acceso a auditoría clínica/económica respeta permisos adicionales.

## 5. Requerimientos funcionales detallados

### RF-M24-001 — Registrar evento de auditoría

**Descripción:** Guardar actor, fecha, acción, entidad y contexto.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Registrar evento de auditoría** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Guardar actor, fecha, acción, entidad y contexto.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M24-001-01:** el happy path produce el resultado descripto.
- **CA-M24-001-02:** un usuario sin permiso no modifica información.
- **CA-M24-001-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M24-001-04:** una validación fallida no deja datos parciales.
- **CA-M24-001-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M24-001-06:** los históricos relacionados siguen siendo consultables.

### RF-M24-002 — Consultar historial de entidad

**Descripción:** Mostrar cambios cronológicos.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Consultar historial de entidad** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Mostrar cambios cronológicos.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M24-002-01:** el happy path produce el resultado descripto.
- **CA-M24-002-02:** un usuario sin permiso no modifica información.
- **CA-M24-002-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M24-002-04:** una validación fallida no deja datos parciales.
- **CA-M24-002-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M24-002-06:** los históricos relacionados siguen siendo consultables.

### RF-M24-003 — Consultar por usuario

**Descripción:** Investigar operaciones de un actor.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Consultar por usuario** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Investigar operaciones de un actor.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M24-003-01:** el happy path produce el resultado descripto.
- **CA-M24-003-02:** un usuario sin permiso no modifica información.
- **CA-M24-003-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M24-003-04:** una validación fallida no deja datos parciales.
- **CA-M24-003-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M24-003-06:** los históricos relacionados siguen siendo consultables.

### RF-M24-004 — Consultar por período

**Descripción:** Filtrar eventos por rango temporal.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Consultar por período** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Filtrar eventos por rango temporal.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M24-004-01:** el happy path produce el resultado descripto.
- **CA-M24-004-02:** un usuario sin permiso no modifica información.
- **CA-M24-004-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M24-004-04:** una validación fallida no deja datos parciales.
- **CA-M24-004-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M24-004-06:** los históricos relacionados siguen siendo consultables.

### RF-M24-005 — Mostrar enmiendas clínicas

**Descripción:** Exponer versiones autorizadas de información clínica.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Mostrar enmiendas clínicas** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Exponer versiones autorizadas de información clínica.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M24-005-01:** el happy path produce el resultado descripto.
- **CA-M24-005-02:** un usuario sin permiso no modifica información.
- **CA-M24-005-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M24-005-04:** una validación fallida no deja datos parciales.
- **CA-M24-005-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M24-005-06:** los históricos relacionados siguen siendo consultables.

### RF-M24-006 — Mostrar anulaciones económicas

**Descripción:** Relacionar operación original y reversión.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Mostrar anulaciones económicas** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Relacionar operación original y reversión.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M24-006-01:** el happy path produce el resultado descripto.
- **CA-M24-006-02:** un usuario sin permiso no modifica información.
- **CA-M24-006-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M24-006-04:** una validación fallida no deja datos parciales.
- **CA-M24-006-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M24-006-06:** los históricos relacionados siguen siendo consultables.

## 6. Requerimientos no funcionales específicos

### RNF-M24-001 — Seguridad

Validar tenant, rol y permiso en backend; aplicar mínimo privilegio.

### RNF-M24-002 — Integridad

Evitar estados parcialmente persistidos y proteger relaciones históricas.

### RNF-M24-003 — Concurrencia

Revalidar recursos compartidos y usar locking/restricciones cuando exista riesgo de doble operación.

### RNF-M24-004 — Rendimiento

Listados paginados, filtros indexables y carga solo de información necesaria.

### RNF-M24-005 — Usabilidad

Interacciones compactas, mensajes funcionales y sin exponer identificadores técnicos como dato principal.

### RNF-M24-006 — Auditoría

Registrar operaciones sensibles con actor y fecha.

### RNF-M24-007 — Observabilidad

Errores y tiempos relevantes deben ser medibles sin registrar datos sensibles.

### RNF-M24-008 — Mantenibilidad

Reglas de negocio críticas deben residir en backend/dominio y no duplicarse entre pantallas.

## 7. Casos borde mínimos de QA

- Entidad dada de baja entre la apertura y la confirmación.
- Dos usuarios intentando operar simultáneamente sobre el mismo recurso.
- Cambio de tenant/consultorio antes de confirmar.
- Reintento del request luego de timeout.
- Referencia histórica a catálogos o usuarios inactivos.
- Rol revocado entre lectura y confirmación.
- Paginación, ordenamiento y filtros con volumen alto.
- Datos incompletos provenientes de una migración histórica.

## 8. Ampliación funcional integrada — Servicios, Clases y Actividades Terapéuticas

Esta sección amplía **Auditoría y Trazabilidad** con las capacidades requeridas para que AKINE soporte servicios clínicos, terapéuticos, preventivos y de bienestar sin alterar el comportamiento ya definido para el flujo clínico tradicional.

### 8.1 Reglas de negocio adicionales

- **RN-M24-004:** Crear, modificar, cancelar clases, inscripciones, asistencias, pases, movimientos y abonos son acciones auditables.
- **RN-M24-005:** Los cambios de configuración de Oferta que afectan clínica o economía deben conservar actor, fecha y valores anteriores/nuevos.

### 8.2 Requerimientos funcionales adicionales

### RF-M24-007 — Auditar ciclo de clase e inscripción

**Descripción:** Registrar cambios relevantes desde programación hasta asistencia/cancelación por participante.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Auditar ciclo de clase e inscripción** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Guarda evento, actor, estado anterior/nuevo y motivo cuando aplique.
6. Persiste los cambios de forma atómica y consistente.
7. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
8. Registra auditoría y eventos de dominio aplicables.
9. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- No registrar contenido clínico sensible en logs técnicos generales.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M24-007-01:** el happy path produce el resultado descripto.
- **CA-M24-007-02:** un usuario sin permiso no modifica información.
- **CA-M24-007-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M24-007-04:** una validación fallida no deja datos parciales.
- **CA-M24-007-05:** la operación conserva trazabilidad histórica.
- **CA-M24-007-06:** puede reconstruirse quién canceló una inscripción y cuándo.

### RF-M24-008 — Auditar movimientos de créditos y abonos

**Descripción:** Garantizar trazabilidad de compra, consumo, ajuste, devolución y vencimiento.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Auditar movimientos de créditos y abonos** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Registra referencia funcional y saldo anterior/posterior.
6. Vincula a asistencia/cobro cuando corresponda.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Los movimientos no se editan destructivamente.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M24-008-01:** el happy path produce el resultado descripto.
- **CA-M24-008-02:** un usuario sin permiso no modifica información.
- **CA-M24-008-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M24-008-04:** una validación fallida no deja datos parciales.
- **CA-M24-008-05:** la operación conserva trazabilidad histórica.
- **CA-M24-008-06:** todo cambio de saldo puede explicarse por una secuencia de movimientos.

### 8.3 Requerimientos no funcionales adicionales

### RNF-M24-009 — Extensión de servicios

Las nuevas reglas deben resolverse en backend/dominio y no inferirse desde nombres visibles del servicio.

### RNF-M24-010 — Extensión de servicios

Las consultas ampliadas deben mantener paginación, índices y tiempos de respuesta compatibles con el volumen operativo.

### RNF-M24-011 — Extensión de servicios

La incorporación de clases/servicios no debe degradar ni alterar datos históricos del flujo clínico existente.

### 8.4 Casos borde adicionales de QA

- Ajuste manual de créditos debe requerir motivo.

---

# M25 — Adjuntos y Documentación

## 1. Objetivo

Gestionar archivos relacionados con pacientes, casos, autorizaciones y comprobantes.

## 2. Actores

- Profesional
- Administrativo

## 3. Dependencias

- M07 Paciente
- M09 HC
- M17 Autorizaciones

## 4. Reglas de negocio

- **RN-M25-001:** Validar tipo y tamaño.
- **RN-M25-002:** No exponer rutas internas.
- **RN-M25-003:** El acceso hereda permisos de la entidad asociada.
- **RN-M25-004:** Adjuntos no reemplazan información estructurada esencial.

## 5. Requerimientos funcionales detallados

### RF-M25-001 — Subir adjunto

**Descripción:** Validar archivo, almacenar y vincular metadata.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Subir adjunto** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Validar archivo, almacenar y vincular metadata.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M25-001-01:** el happy path produce el resultado descripto.
- **CA-M25-001-02:** un usuario sin permiso no modifica información.
- **CA-M25-001-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M25-001-04:** una validación fallida no deja datos parciales.
- **CA-M25-001-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M25-001-06:** los históricos relacionados siguen siendo consultables.

### RF-M25-002 — Visualizar/descargar

**Descripción:** Autorizar acceso según entidad y tenant.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Visualizar/descargar** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Autorizar acceso según entidad y tenant.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M25-002-01:** el happy path produce el resultado descripto.
- **CA-M25-002-02:** un usuario sin permiso no modifica información.
- **CA-M25-002-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M25-002-04:** una validación fallida no deja datos parciales.
- **CA-M25-002-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M25-002-06:** los históricos relacionados siguen siendo consultables.

### RF-M25-003 — Clasificar adjunto

**Descripción:** Asignar tipo documental y descripción.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Clasificar adjunto** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Asignar tipo documental y descripción.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M25-003-01:** el happy path produce el resultado descripto.
- **CA-M25-003-02:** un usuario sin permiso no modifica información.
- **CA-M25-003-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M25-003-04:** una validación fallida no deja datos parciales.
- **CA-M25-003-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M25-003-06:** los históricos relacionados siguen siendo consultables.

### RF-M25-004 — Dar baja lógica

**Descripción:** Retirar del uso operativo preservando trazabilidad.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Dar baja lógica** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Retirar del uso operativo preservando trazabilidad.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M25-004-01:** el happy path produce el resultado descripto.
- **CA-M25-004-02:** un usuario sin permiso no modifica información.
- **CA-M25-004-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M25-004-04:** una validación fallida no deja datos parciales.
- **CA-M25-004-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M25-004-06:** los históricos relacionados siguen siendo consultables.

### RF-M25-005 — Consultar adjuntos por entidad

**Descripción:** Listar metadata sin cargar binarios innecesariamente.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Consultar adjuntos por entidad** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Listar metadata sin cargar binarios innecesariamente.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M25-005-01:** el happy path produce el resultado descripto.
- **CA-M25-005-02:** un usuario sin permiso no modifica información.
- **CA-M25-005-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M25-005-04:** una validación fallida no deja datos parciales.
- **CA-M25-005-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M25-005-06:** los históricos relacionados siguen siendo consultables.

## 6. Requerimientos no funcionales específicos

### RNF-M25-001 — Seguridad

Validar tenant, rol y permiso en backend; aplicar mínimo privilegio.

### RNF-M25-002 — Integridad

Evitar estados parcialmente persistidos y proteger relaciones históricas.

### RNF-M25-003 — Concurrencia

Revalidar recursos compartidos y usar locking/restricciones cuando exista riesgo de doble operación.

### RNF-M25-004 — Rendimiento

Listados paginados, filtros indexables y carga solo de información necesaria.

### RNF-M25-005 — Usabilidad

Interacciones compactas, mensajes funcionales y sin exponer identificadores técnicos como dato principal.

### RNF-M25-006 — Auditoría

Registrar operaciones sensibles con actor y fecha.

### RNF-M25-007 — Observabilidad

Errores y tiempos relevantes deben ser medibles sin registrar datos sensibles.

### RNF-M25-008 — Mantenibilidad

Reglas de negocio críticas deben residir en backend/dominio y no duplicarse entre pantallas.

## 7. Casos borde mínimos de QA

- Entidad dada de baja entre la apertura y la confirmación.
- Dos usuarios intentando operar simultáneamente sobre el mismo recurso.
- Cambio de tenant/consultorio antes de confirmar.
- Reintento del request luego de timeout.
- Referencia histórica a catálogos o usuarios inactivos.
- Rol revocado entre lectura y confirmación.
- Paginación, ordenamiento y filtros con volumen alto.
- Datos incompletos provenientes de una migración histórica.

## 8. Ampliación funcional integrada — Servicios, Clases y Actividades Terapéuticas

Esta sección amplía **Adjuntos y Documentación** con las capacidades requeridas para que AKINE soporte servicios clínicos, terapéuticos, preventivos y de bienestar sin alterar el comportamiento ya definido para el flujo clínico tradicional.

### 8.1 Reglas de negocio adicionales

- **RN-M25-005:** Los adjuntos clínicos siguen asociados a Persona/Paciente, Caso o Atención; una clase no clínica no debe transformarse en contenedor clínico.
- **RN-M25-006:** Las Ofertas pueden requerir documentación administrativa propia sin mezclarla con Historia Clínica.

### 8.2 Requerimientos funcionales adicionales

### RF-M25-006 — Adjuntar documentación administrativa de servicio

**Descripción:** Permitir asociar documentos necesarios para una inscripción o cobertura sin clasificarlos automáticamente como documentación clínica.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Adjuntar documentación administrativa de servicio** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Selecciona tipo documental y contexto: persona, inscripción, autorización u oferta.
6. Aplica permisos y retención correspondientes.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- No exponer documentación de otros participantes.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M25-006-01:** el happy path produce el resultado descripto.
- **CA-M25-006-02:** un usuario sin permiso no modifica información.
- **CA-M25-006-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M25-006-04:** una validación fallida no deja datos parciales.
- **CA-M25-006-05:** la operación conserva trazabilidad histórica.
- **CA-M25-006-06:** una autorización de clase clínica queda accesible al circuito administrativo sin aparecer como evolución clínica.

### 8.3 Requerimientos no funcionales adicionales

### RNF-M25-009 — Extensión de servicios

Las nuevas reglas deben resolverse en backend/dominio y no inferirse desde nombres visibles del servicio.

### RNF-M25-010 — Extensión de servicios

Las consultas ampliadas deben mantener paginación, índices y tiempos de respuesta compatibles con el volumen operativo.

### RNF-M25-011 — Extensión de servicios

La incorporación de clases/servicios no debe degradar ni alterar datos históricos del flujo clínico existente.

### 8.4 Casos borde adicionales de QA

- Documento de inscripción eliminado lógicamente luego de prestación.

---

# M26 — Notificaciones

## 1. Objetivo

Comunicar eventos relevantes de cuentas, invitaciones y turnos sin convertir la notificación en fuente de verdad.

## 2. Actores

- Sistema
- Usuario
- Administrador

## 3. Dependencias

- M02 Usuarios
- M05 Invitaciones
- M12 Turnos

## 4. Reglas de negocio

- **RN-M26-001:** Fallo de notificación no borra ni revierte la operación principal.
- **RN-M26-002:** No incluir información clínica sensible innecesaria.
- **RN-M26-003:** Reintentos no deben duplicar efectos de negocio.

## 5. Requerimientos funcionales detallados

### RF-M26-001 — Enviar activación/invitación

**Descripción:** Enviar mensaje con enlace seguro y registrar resultado.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Enviar activación/invitación** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Enviar mensaje con enlace seguro y registrar resultado.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M26-001-01:** el happy path produce el resultado descripto.
- **CA-M26-001-02:** un usuario sin permiso no modifica información.
- **CA-M26-001-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M26-001-04:** una validación fallida no deja datos parciales.
- **CA-M26-001-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M26-001-06:** los históricos relacionados siguen siendo consultables.

### RF-M26-002 — Notificar creación de turno

**Descripción:** Comunicar fecha, hora y consultorio.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Notificar creación de turno** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Comunicar fecha, hora y consultorio.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M26-002-01:** el happy path produce el resultado descripto.
- **CA-M26-002-02:** un usuario sin permiso no modifica información.
- **CA-M26-002-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M26-002-04:** una validación fallida no deja datos parciales.
- **CA-M26-002-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M26-002-06:** los históricos relacionados siguen siendo consultables.

### RF-M26-003 — Notificar cancelación/reprogramación

**Descripción:** Informar cambio sobre un turno existente.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Notificar cancelación/reprogramación** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Informar cambio sobre un turno existente.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M26-003-01:** el happy path produce el resultado descripto.
- **CA-M26-003-02:** un usuario sin permiso no modifica información.
- **CA-M26-003-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M26-003-04:** una validación fallida no deja datos parciales.
- **CA-M26-003-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M26-003-06:** los históricos relacionados siguen siendo consultables.

### RF-M26-004 — Registrar resultado de envío

**Descripción:** Guardar enviado/fallido/reintentado.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Registrar resultado de envío** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Guardar enviado/fallido/reintentado.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M26-004-01:** el happy path produce el resultado descripto.
- **CA-M26-004-02:** un usuario sin permiso no modifica información.
- **CA-M26-004-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M26-004-04:** una validación fallida no deja datos parciales.
- **CA-M26-004-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M26-004-06:** los históricos relacionados siguen siendo consultables.

### RF-M26-005 — Reintentar notificación

**Descripción:** Reprocesar fallos sin duplicar la operación de negocio.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes y en estado compatible.

**Disparador:**
- El actor solicita la operación **Reintentar notificación** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor y el contexto activo.
2. Valida autorización y pertenencia al tenant.
3. Recupera las entidades necesarias y valida su estado.
4. Valida los datos funcionales de entrada.
5. Ejecuta la regla específica: Reprocesar fallos sin duplicar la operación de negocio.
6. Persiste los cambios de forma consistente.
7. Actualiza estados, saldos, contadores o relaciones derivadas cuando corresponda.
8. Registra auditoría si la operación es sensible.
9. Devuelve resultado funcional y datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación admite modo parcial/borrador, conservar explícitamente dicho estado; de lo contrario no dejar cambios parciales.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia de referencias.
- Estado de las entidades.
- Coherencia temporal.
- Coherencia con reglas del módulo.
- Aislamiento multi-tenant.
- Permisos en backend.
- Idempotencia cuando la operación pueda repetirse por reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- La información puede recuperarse posteriormente por consulta normal.
- Los históricos previos permanecen disponibles cuando corresponde.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, entidad, operación y cambios relevantes cuando aplique.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M26-005-01:** el happy path produce el resultado descripto.
- **CA-M26-005-02:** un usuario sin permiso no modifica información.
- **CA-M26-005-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M26-005-04:** una validación fallida no deja datos parciales.
- **CA-M26-005-05:** reintentos no duplican efectos cuando la operación debe ser idempotente.
- **CA-M26-005-06:** los históricos relacionados siguen siendo consultables.

## 6. Requerimientos no funcionales específicos

### RNF-M26-001 — Seguridad

Validar tenant, rol y permiso en backend; aplicar mínimo privilegio.

### RNF-M26-002 — Integridad

Evitar estados parcialmente persistidos y proteger relaciones históricas.

### RNF-M26-003 — Concurrencia

Revalidar recursos compartidos y usar locking/restricciones cuando exista riesgo de doble operación.

### RNF-M26-004 — Rendimiento

Listados paginados, filtros indexables y carga solo de información necesaria.

### RNF-M26-005 — Usabilidad

Interacciones compactas, mensajes funcionales y sin exponer identificadores técnicos como dato principal.

### RNF-M26-006 — Auditoría

Registrar operaciones sensibles con actor y fecha.

### RNF-M26-007 — Observabilidad

Errores y tiempos relevantes deben ser medibles sin registrar datos sensibles.

### RNF-M26-008 — Mantenibilidad

Reglas de negocio críticas deben residir en backend/dominio y no duplicarse entre pantallas.

## 7. Casos borde mínimos de QA

- Entidad dada de baja entre la apertura y la confirmación.
- Dos usuarios intentando operar simultáneamente sobre el mismo recurso.
- Cambio de tenant/consultorio antes de confirmar.
- Reintento del request luego de timeout.
- Referencia histórica a catálogos o usuarios inactivos.
- Rol revocado entre lectura y confirmación.
- Paginación, ordenamiento y filtros con volumen alto.
- Datos incompletos provenientes de una migración histórica.

## 8. Ampliación funcional integrada — Servicios, Clases y Actividades Terapéuticas

Esta sección amplía **Notificaciones** con las capacidades requeridas para que AKINE soporte servicios clínicos, terapéuticos, preventivos y de bienestar sin alterar el comportamiento ya definido para el flujo clínico tradicional.

### 8.1 Reglas de negocio adicionales

- **RN-M26-004:** Las clases deben soportar confirmación, reprogramación, cancelación y avisos de lista de espera.
- **RN-M26-005:** Los pases/abonos pueden generar avisos de vencimiento o saldo bajo según configuración.
- **RN-M26-006:** Las notificaciones no deben revelar diagnósticos ni contenido clínico innecesario.

### 8.2 Requerimientos funcionales adicionales

### RF-M26-006 — Notificar cambios de clase a inscriptos

**Descripción:** Informar programación, reprogramación o cancelación a las personas afectadas.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Notificar cambios de clase a inscriptos** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Obtiene participantes vigentes.
6. Genera notificación individual con información operativa mínima.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- No incluir datos de otros participantes.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M26-006-01:** el happy path produce el resultado descripto.
- **CA-M26-006-02:** un usuario sin permiso no modifica información.
- **CA-M26-006-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M26-006-04:** una validación fallida no deja datos parciales.
- **CA-M26-006-05:** la operación conserva trazabilidad histórica.
- **CA-M26-006-06:** cancelar una clase notifica a sus inscriptos sin exponer la lista completa.

### RF-M26-007 — Notificar liberación de cupo a lista de espera

**Descripción:** Permitir avisar a personas en espera cuando se libera capacidad, respetando política de prioridad y ventana de aceptación.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Notificar liberación de cupo a lista de espera** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Detecta cupo liberado.
6. Selecciona siguiente inscripción en espera.
7. Envía aviso y registra estado de ofrecimiento cuando corresponda.
8. Persiste los cambios de forma atómica y consistente.
9. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
10. Registra auditoría y eventos de dominio aplicables.
11. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- No sobreasignar el mismo cupo a múltiples personas sin control de concurrencia.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M26-007-01:** el happy path produce el resultado descripto.
- **CA-M26-007-02:** un usuario sin permiso no modifica información.
- **CA-M26-007-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M26-007-04:** una validación fallida no deja datos parciales.
- **CA-M26-007-05:** la operación conserva trazabilidad histórica.
- **CA-M26-007-06:** el último cupo no termina confirmado para dos personas.

### RF-M26-008 — Notificar vencimiento de pase o abono

**Descripción:** Emitir avisos configurables antes del vencimiento o ante saldo reducido.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Notificar vencimiento de pase o abono** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Evalúa vigencias y créditos restantes.
6. Genera recordatorio sin modificar saldos.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Respetar preferencias y canales habilitados.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M26-008-01:** el happy path produce el resultado descripto.
- **CA-M26-008-02:** un usuario sin permiso no modifica información.
- **CA-M26-008-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M26-008-04:** una validación fallida no deja datos parciales.
- **CA-M26-008-05:** la operación conserva trazabilidad histórica.
- **CA-M26-008-06:** el aviso no renueva ni consume automáticamente el producto.

### 8.3 Requerimientos no funcionales adicionales

### RNF-M26-009 — Extensión de servicios

Las nuevas reglas deben resolverse en backend/dominio y no inferirse desde nombres visibles del servicio.

### RNF-M26-010 — Extensión de servicios

Las consultas ampliadas deben mantener paginación, índices y tiempos de respuesta compatibles con el volumen operativo.

### RNF-M26-011 — Extensión de servicios

La incorporación de clases/servicios no debe degradar ni alterar datos históricos del flujo clínico existente.

### 8.4 Casos borde adicionales de QA

- Clase cancelada masivamente con cientos de inscriptos: procesamiento idempotente.

---

# M27 — Servicios y Ofertas de Servicio por Consultorio

## 1. Objetivo

Administrar el catálogo de Servicios y la configuración concreta con la que cada consultorio los ofrece, permitiendo combinar naturaleza clínica/no clínica, modalidad individual/grupal, requisitos de Caso Clínico, generación de registro clínico, capacidad, profesionales, espacios, cobertura y esquema de cobro sin reglas hardcodeadas por nombre.

## 2. Actores

- Administrador de plataforma
- Administrador de organización
- Administrador de consultorio
- Profesional autorizado

## 3. Dependencias

- M03 Consultorios
- M04 Espacios
- M05 Profesionales
- M06 Catálogos
- M15 Financiadores
- M16 Convenios
- M18 Obligaciones

## 4. Reglas de negocio

- **RN-M27-001:** Un Servicio es catálogo global/conceptual y no representa por sí solo una oferta activa.
- **RN-M27-002:** OfertaServicioConsultorio define cómo un consultorio presta el Servicio.
- **RN-M27-003:** La Oferta puede sobrescribir defaults del Servicio sin modificar el catálogo global.
- **RN-M27-004:** Las reglas clínicas dependen de `requiereCasoClinico` y `generaRegistroClinico`, no del nombre o naturaleza.
- **RN-M27-005:** Las reglas de agenda dependen de modalidad, duración, capacidad, profesional y espacio.
- **RN-M27-006:** Las reglas económicas dependen de `esquemaCobro` y convenios/aranceles vigentes.
- **RN-M27-007:** Una Oferta inactiva no admite nuevas reservas pero conserva históricos.
- **RN-M27-008:** Un mismo Servicio puede existir en múltiples consultorios con configuraciones distintas.

## 5. Requerimientos funcionales detallados

### RF-M27-001 — Crear Servicio de catálogo

**Descripción:** Registrar un nuevo concepto de servicio reutilizable en AKINE.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Crear Servicio de catálogo** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Registra nombre, descripción, naturaleza, modalidad default y defaults clínicos.
6. Deja el Servicio disponible para ser utilizado por consultorios autorizados.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- No utilizar el nombre como identificador de lógica de negocio.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M27-001-01:** el happy path produce el resultado descripto.
- **CA-M27-001-02:** un usuario sin permiso no modifica información.
- **CA-M27-001-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M27-001-04:** una validación fallida no deja datos parciales.
- **CA-M27-001-05:** la operación conserva trazabilidad histórica.
- **CA-M27-001-06:** crear Yoga o Pilates no activa automáticamente comportamiento clínico o no clínico.

### RF-M27-002 — Editar o inactivar Servicio

**Descripción:** Modificar datos descriptivos/defaults o retirarlo de nuevas ofertas conservando referencias históricas.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Editar o inactivar Servicio** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Actualiza únicamente atributos permitidos.
6. Al inactivar impide nuevas altas de oferta según política.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- No borrar físicamente si existen referencias.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M27-002-01:** el happy path produce el resultado descripto.
- **CA-M27-002-02:** un usuario sin permiso no modifica información.
- **CA-M27-002-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M27-002-04:** una validación fallida no deja datos parciales.
- **CA-M27-002-05:** la operación conserva trazabilidad histórica.
- **CA-M27-002-06:** ofertas históricas siguen identificando el Servicio utilizado.

### RF-M27-003 — Crear OfertaServicioConsultorio

**Descripción:** Configurar una prestación concreta de un Servicio dentro de un consultorio.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Crear OfertaServicioConsultorio** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Selecciona Servicio y consultorio.
6. Define nombre comercial, modalidad, duración, capacidad, precio base, moneda, esquema de cobro y vigencia.
7. Define `requiereCasoClinico`, `generaRegistroClinico`, `admiteObraSocial`, `requiereProfesional` y `requiereEspacio`.
8. Persiste los cambios de forma atómica y consistente.
9. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
10. Registra auditoría y eventos de dominio aplicables.
11. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Modalidad GRUPAL requiere capacidad mayor a cero.
- No permitir vigencia invertida.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M27-003-01:** el happy path produce el resultado descripto.
- **CA-M27-003-02:** un usuario sin permiso no modifica información.
- **CA-M27-003-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M27-003-04:** una validación fallida no deja datos parciales.
- **CA-M27-003-05:** la operación conserva trazabilidad histórica.
- **CA-M27-003-06:** dos consultorios pueden ofrecer Pilates Reformer con duración y capacidad diferentes.

### RF-M27-004 — Configurar comportamiento clínico de Oferta

**Descripción:** Definir explícitamente si el consumo del servicio requiere Caso Clínico y/o genera Registro Clínico.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Configurar comportamiento clínico de Oferta** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Configura ambos flags de forma independiente.
6. Valida combinaciones y muestra advertencias de dominio cuando corresponda.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- `requiereCasoClinico=true` implica que toda prestación clínica debe resolver Caso antes del cierre.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M27-004-01:** el happy path produce el resultado descripto.
- **CA-M27-004-02:** un usuario sin permiso no modifica información.
- **CA-M27-004-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M27-004-04:** una validación fallida no deja datos parciales.
- **CA-M27-004-05:** la operación conserva trazabilidad histórica.
- **CA-M27-004-06:** Pilates Clínico y Pilates General pueden coexistir con reglas distintas.

### RF-M27-005 — Configurar modalidad y capacidad de Oferta

**Descripción:** Definir operación individual o grupal y límites de participantes.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Configurar modalidad y capacidad de Oferta** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Establece modalidad.
6. Para grupal define capacidad default y permite ajustes por clase dentro de límites autorizados.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- INDIVIDUAL debe operar con capacidad efectiva 1 salvo caso expresamente modelado.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M27-005-01:** el happy path produce el resultado descripto.
- **CA-M27-005-02:** un usuario sin permiso no modifica información.
- **CA-M27-005-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M27-005-04:** una validación fallida no deja datos parciales.
- **CA-M27-005-05:** la operación conserva trazabilidad histórica.
- **CA-M27-005-06:** una oferta grupal de capacidad 10 permite crear clases con límite compatible.

### RF-M27-006 — Configurar profesionales y espacios habilitados

**Descripción:** Restringir quién puede prestar el servicio y en qué recursos físicos puede ejecutarse.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Configurar profesionales y espacios habilitados** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Asocia profesionales habilitados.
6. Asocia espacios habilitados.
7. Permite configuración abierta cuando política del consultorio lo autoriza.
8. Persiste los cambios de forma atómica y consistente.
9. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
10. Registra auditoría y eventos de dominio aplicables.
11. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Todos deben pertenecer al consultorio y estar activos.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M27-006-01:** el happy path produce el resultado descripto.
- **CA-M27-006-02:** un usuario sin permiso no modifica información.
- **CA-M27-006-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M27-006-04:** una validación fallida no deja datos parciales.
- **CA-M27-006-05:** la operación conserva trazabilidad histórica.
- **CA-M27-006-06:** solo profesionales/espacios compatibles aparecen en agenda.

### RF-M27-007 — Configurar cobertura y esquema de cobro

**Descripción:** Definir si la Oferta es particular, admite financiadores y cómo se devenga económicamente.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Configurar cobertura y esquema de cobro** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Selecciona esquema `POR_SESION`, `POR_CLASE`, `PACK`, `CUOTA_MENSUAL`, `BONO`, `OBRA_SOCIAL` o `MIXTO`.
6. Relaciona convenios/aranceles cuando aplique.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Esquema no puede provocar simultáneamente cargo unitario y consumo anticipado sin regla MIXTO explícita.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M27-007-01:** el happy path produce el resultado descripto.
- **CA-M27-007-02:** un usuario sin permiso no modifica información.
- **CA-M27-007-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M27-007-04:** una validación fallida no deja datos parciales.
- **CA-M27-007-05:** la operación conserva trazabilidad histórica.
- **CA-M27-007-06:** un pack de Pilates genera cargo al comprar y no por cada consumo.

### RF-M27-008 — Gestionar vigencia y baja de Oferta

**Descripción:** Activar, suspender o finalizar una Oferta sin eliminar historial.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Gestionar vigencia y baja de Oferta** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Registra vigencia y estado.
6. Impide nuevas programaciones fuera de vigencia.
7. Identifica eventos futuros afectados para resolución.
8. Persiste los cambios de forma atómica y consistente.
9. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
10. Registra auditoría y eventos de dominio aplicables.
11. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- No invalidar prestaciones históricas.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M27-008-01:** el happy path produce el resultado descripto.
- **CA-M27-008-02:** un usuario sin permiso no modifica información.
- **CA-M27-008-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M27-008-04:** una validación fallida no deja datos parciales.
- **CA-M27-008-05:** la operación conserva trazabilidad histórica.
- **CA-M27-008-06:** una oferta finalizada conserva reportes, cobros y asistencias anteriores.

## 6. Requerimientos no funcionales específicos

### RNF-M27-001 — Configurabilidad

Las decisiones funcionales deben provenir de configuración persistida y validada en backend, evitando condicionales por texto o nombre comercial.

### RNF-M27-002 — Rendimiento

La búsqueda de ofertas activas por consultorio, modalidad y servicio debe ser indexable y apta para agenda de alta frecuencia.

### RNF-M27-003 — Integridad

Cambios de configuración con impacto clínico/económico deben aplicarse a futuro y preservar snapshots históricos cuando corresponda.

### RNF-M27-004 — Seguridad

Solo actores autorizados pueden modificar configuración clínica, económica o de vigencia de una Oferta.

### RNF-M27-005 — Mantenibilidad

Los enums y políticas de esquema de cobro deben estar centralizados en dominio y versionarse de manera compatible.

## 7. Casos borde mínimos de QA

- Oferta creada con Servicio que luego se inactiva.
- Cambio de capacidad con clases futuras ya llenas.
- Cambio de `generaRegistroClinico` con eventos futuros e históricos.
- Oferta sin profesionales habilitados cuando `requiereProfesional=true`.
- Vigencias superpuestas de precios/convenios.

---
# M28 — Clases Programadas, Inscripciones, Lista de Espera y Asistencia

## 1. Objetivo

Gestionar actividades grupales como eventos únicos de agenda con múltiples participantes, cupos, lista de espera, asistencia individual y eventual derivación al flujo clínico, evitando representar una clase como múltiples turnos independientes.

## 2. Actores

- Administrativo
- Profesional / Instructor
- Administrador de consultorio
- Persona / Paciente cuando exista autoservicio

## 3. Dependencias

- M04 Espacios
- M05 Profesionales
- M07 Personas/Pacientes
- M12 Agenda
- M13 Recepción
- M14 Sesiones
- M27 Servicios y Ofertas
- M29 Pases y Abonos

## 4. Reglas de negocio

- **RN-M28-001:** Cada ClaseProgramada pertenece a una Oferta GRUPAL y a un consultorio.
- **RN-M28-002:** La Clase tiene capacidad propia limitada además por la capacidad del espacio.
- **RN-M28-003:** Cada participante posee una InscripcionClase independiente.
- **RN-M28-004:** Los estados mínimos de inscripción son `RESERVADA`, `CONFIRMADA`, `ASISTIO`, `AUSENTE`, `CANCELADA`, `LISTA_ESPERA`.
- **RN-M28-005:** Lista de espera no consume cupo confirmado salvo política explícita.
- **RN-M28-006:** La asistencia se registra por participante.
- **RN-M28-007:** Una asistencia no clínica no crea Sesión Clínica.
- **RN-M28-008:** Una actividad clínica grupal crea registros clínicos individuales cuando corresponde.
- **RN-M28-009:** Cancelaciones y reprogramaciones conservan trazabilidad y deben coordinar impacto económico/créditos.

## 5. Requerimientos funcionales detallados

### RF-M28-001 — Crear ClaseProgramada

**Descripción:** Programar una ocurrencia grupal de una Oferta.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Crear ClaseProgramada** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Selecciona Oferta GRUPAL, fecha, horario, profesional e espacio.
6. Calcula capacidad efectiva.
7. Inicializa estado programado y ocupación cero.
8. Persiste los cambios de forma atómica y consistente.
9. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
10. Registra auditoría y eventos de dominio aplicables.
11. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- No superponer profesional/espacio con eventos incompatibles.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M28-001-01:** el happy path produce el resultado descripto.
- **CA-M28-001-02:** un usuario sin permiso no modifica información.
- **CA-M28-001-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M28-001-04:** una validación fallida no deja datos parciales.
- **CA-M28-001-05:** la operación conserva trazabilidad histórica.
- **CA-M28-001-06:** la clase existe una sola vez independientemente del número de participantes.

### RF-M28-002 — Inscribir persona en clase

**Descripción:** Reservar un cupo para una Persona/Paciente.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Inscribir persona en clase** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Verifica identidad y elegibilidad.
6. Revalida cupo en transacción.
7. Crea InscripcionClase y determina estado confirmado o lista de espera.
8. Persiste los cambios de forma atómica y consistente.
9. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
10. Registra auditoría y eventos de dominio aplicables.
11. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- No duplicar inscripción activa de la misma persona en la misma clase.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M28-002-01:** el happy path produce el resultado descripto.
- **CA-M28-002-02:** un usuario sin permiso no modifica información.
- **CA-M28-002-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M28-002-04:** una validación fallida no deja datos parciales.
- **CA-M28-002-05:** la operación conserva trazabilidad histórica.
- **CA-M28-002-06:** dos solicitudes concurrentes sobre último cupo dejan solo una confirmada.

### RF-M28-003 — Cancelar inscripción

**Descripción:** Liberar cupo y conservar historial individual.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Cancelar inscripción** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Valida política de cancelación.
6. Marca estado CANCELADA.
7. Libera cupo y evalúa devolución económica/crédito.
8. Dispara promoción de lista de espera cuando corresponda.
9. Persiste los cambios de forma atómica y consistente.
10. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
11. Registra auditoría y eventos de dominio aplicables.
12. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- No borrar físicamente.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M28-003-01:** el happy path produce el resultado descripto.
- **CA-M28-003-02:** un usuario sin permiso no modifica información.
- **CA-M28-003-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M28-003-04:** una validación fallida no deja datos parciales.
- **CA-M28-003-05:** la operación conserva trazabilidad histórica.
- **CA-M28-003-06:** cancelar una inscripción no cancela la clase ni a otros participantes.

### RF-M28-004 — Gestionar lista de espera

**Descripción:** Mantener cola ordenada cuando no hay cupos y ofrecer vacantes liberadas.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Gestionar lista de espera** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Registra prioridad/fecha de ingreso.
6. Ante liberación selecciona siguiente elegible.
7. Confirma según política automática o ventana de aceptación.
8. Persiste los cambios de forma atómica y consistente.
9. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
10. Registra auditoría y eventos de dominio aplicables.
11. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Evitar promociones duplicadas por concurrencia.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M28-004-01:** el happy path produce el resultado descripto.
- **CA-M28-004-02:** un usuario sin permiso no modifica información.
- **CA-M28-004-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M28-004-04:** una validación fallida no deja datos parciales.
- **CA-M28-004-05:** la operación conserva trazabilidad histórica.
- **CA-M28-004-06:** la prioridad se respeta y el cupo no se sobrevende.

### RF-M28-005 — Reprogramar ClaseProgramada

**Descripción:** Cambiar horario/profesional/espacio manteniendo participantes y trazabilidad.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Reprogramar ClaseProgramada** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Revalida recursos.
6. Actualiza clase.
7. Identifica inscriptos afectados y notifica.
8. Persiste los cambios de forma atómica y consistente.
9. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
10. Registra auditoría y eventos de dominio aplicables.
11. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Nueva capacidad no menor a confirmados salvo flujo explícito de resolución.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M28-005-01:** el happy path produce el resultado descripto.
- **CA-M28-005-02:** un usuario sin permiso no modifica información.
- **CA-M28-005-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M28-005-04:** una validación fallida no deja datos parciales.
- **CA-M28-005-05:** la operación conserva trazabilidad histórica.
- **CA-M28-005-06:** las inscripciones mantienen identidad y estado luego de reprogramar.

### RF-M28-006 — Cancelar ClaseProgramada

**Descripción:** Cancelar el evento completo y resolver inscripciones, créditos y obligaciones relacionadas.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Cancelar ClaseProgramada** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Marca la clase CANCELADA.
6. Actualiza estados de inscripciones según política.
7. Devuelve créditos o genera reversas cuando corresponda.
8. Notifica a participantes.
9. Persiste los cambios de forma atómica y consistente.
10. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
11. Registra auditoría y eventos de dominio aplicables.
12. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Proceso idempotente.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M28-006-01:** el happy path produce el resultado descripto.
- **CA-M28-006-02:** un usuario sin permiso no modifica información.
- **CA-M28-006-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M28-006-04:** una validación fallida no deja datos parciales.
- **CA-M28-006-05:** la operación conserva trazabilidad histórica.
- **CA-M28-006-06:** una segunda ejecución no devuelve créditos ni dinero dos veces.

### RF-M28-007 — Registrar asistencia individual

**Descripción:** Marcar ASISTIO/AUSENTE por participante y crear AsistenciaActividad cuando corresponda.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Registrar asistencia individual** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Confirma presencia.
6. Registra profesional/instructor y observación operativa.
7. Dispara consumo económico o crédito según esquema.
8. Persiste los cambios de forma atómica y consistente.
9. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
10. Registra auditoría y eventos de dominio aplicables.
11. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Un estado final de asistencia no debe duplicar consumo ante reintento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M28-007-01:** el happy path produce el resultado descripto.
- **CA-M28-007-02:** un usuario sin permiso no modifica información.
- **CA-M28-007-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M28-007-04:** una validación fallida no deja datos parciales.
- **CA-M28-007-05:** la operación conserva trazabilidad histórica.
- **CA-M28-007-06:** cada participante puede quedar con estado distinto.

### RF-M28-008 — Derivar participante a atención clínica

**Descripción:** Para Ofertas clínicas, abrir/crear la Atención individual correspondiente.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Derivar participante a atención clínica** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Valida perfil clínico y Caso.
6. Crea atención vinculada a clase/inscripción.
7. Mantiene privacidad individual.
8. Persiste los cambios de forma atómica y consistente.
9. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
10. Registra auditoría y eventos de dominio aplicables.
11. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- No mostrar información clínica cruzada entre participantes.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M28-008-01:** el happy path produce el resultado descripto.
- **CA-M28-008-02:** un usuario sin permiso no modifica información.
- **CA-M28-008-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M28-008-04:** una validación fallida no deja datos parciales.
- **CA-M28-008-05:** la operación conserva trazabilidad histórica.
- **CA-M28-008-06:** una clase clínica de seis personas puede tener seis evoluciones independientes.

### RF-M28-009 — Consultar detalle operativo de clase

**Descripción:** Mostrar cabecera y listado compacto con DNI/persona, inscripción, asistencia, Pase/Abono y estado económico.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Consultar detalle operativo de clase** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Recupera datos operativos necesarios en una consulta paginable/eficiente.
6. Separa indicadores clínicos de datos económicos.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Contenido clínico detallado solo se carga al seleccionar participante autorizado.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M28-009-01:** el happy path produce el resultado descripto.
- **CA-M28-009-02:** un usuario sin permiso no modifica información.
- **CA-M28-009-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M28-009-04:** una validación fallida no deja datos parciales.
- **CA-M28-009-05:** la operación conserva trazabilidad histórica.
- **CA-M28-009-06:** la pantalla soporta listas numerosas sin convertir cada participante en una card gigante.

## 6. Requerimientos no funcionales específicos

### RNF-M28-001 — Concurrencia de cupos

La confirmación de inscripción y promoción de lista de espera debe proteger el último cupo con transacción, constraint o estrategia equivalente.

### RNF-M28-002 — Privacidad

Nunca exponer a un participante información clínica, cobertura sensible o datos innecesarios de otros miembros.

### RNF-M28-003 — Rendimiento

Listas de participantes, asistencia y ocupación deben cargar de forma compacta y eficiente.

### RNF-M28-004 — Idempotencia

Cancelación, asistencia y devolución de créditos deben ser seguras ante reintentos.

### RNF-M28-005 — Usabilidad

La gestión de clase debe priorizar una tabla/listado compacto, acciones por fila y estado visible de cupos.

## 7. Casos borde mínimos de QA

- Dos personas toman el último cupo simultáneamente.
- Participante cancela después de haber consumido crédito.
- Clase reprogramada a un espacio menor.
- Instructor dado de baja con clases futuras.
- Participante de clase clínica no tiene Caso válido.
- Clase con mezcla de particulares y obra social.

---
# M29 — Pases, Paquetes, Créditos y Abonos de Servicio

## 1. Objetivo

Gestionar productos económicos anticipados para servicios —packs/pases de créditos y abonos periódicos—, su vigencia, consumo, ajustes y trazabilidad, integrándolos con Obligaciones, Cobros y Caja sin reutilizar el término Suscripción reservado al SaaS.

## 2. Actores

- Administrativo
- Administrador de consultorio
- Sistema
- Persona / Paciente

## 3. Dependencias

- M18 Obligaciones
- M19 Cobros
- M20 Caja
- M27 Ofertas
- M28 Clases
- M24 Auditoría

## 4. Reglas de negocio

- **RN-M29-001:** `Suscripción` no se utiliza para productos de servicio; se usan Pase/Paquete/Abono.
- **RN-M29-002:** PaseServicio posee créditos iniciales y disponibles, vigencia y estado.
- **RN-M29-003:** Todo cambio de créditos genera MovimientoPase inmutable/auditable.
- **RN-M29-004:** Los tipos mínimos de movimiento son `COMPRA`, `CONSUMO`, `AJUSTE`, `DEVOLUCION`, `VENCIMIENTO`.
- **RN-M29-005:** Una asistencia solo consume crédito cuando existe pase elegible y la política lo indica.
- **RN-M29-006:** El consumo de crédito no genera movimiento de Caja.
- **RN-M29-007:** AbonoServicio cubre un período y no genera deuda por cada asistencia incluida.
- **RN-M29-008:** No se permiten saldos negativos salvo funcionalidad explícita futura.
- **RN-M29-009:** Cancelaciones deben devolver crédito o dinero según política, nunca duplicar compensaciones.

## 5. Requerimientos funcionales detallados

### RF-M29-001 — Crear producto de pack/pase para Oferta

**Descripción:** Definir cantidad de créditos, precio, vigencia y reglas de uso aplicables a una Oferta.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Crear producto de pack/pase para Oferta** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Configura cantidad, duración/vigencia y precio.
6. Define si créditos son exclusivos de una Oferta o grupo permitido.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Cantidad positiva; precio y moneda válidos.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M29-001-01:** el happy path produce el resultado descripto.
- **CA-M29-001-02:** un usuario sin permiso no modifica información.
- **CA-M29-001-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M29-001-04:** una validación fallida no deja datos parciales.
- **CA-M29-001-05:** la operación conserva trazabilidad histórica.
- **CA-M29-001-06:** un Pack Pilates 8 clases queda disponible como producto sin crear todavía saldo a una persona.

### RF-M29-002 — Comprar PaseServicio

**Descripción:** Asignar créditos a una persona y generar la obligación económica de compra.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Comprar PaseServicio** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Selecciona producto/oferta.
6. Crea Pase con saldo inicial.
7. Genera MovimientoPase COMPRA y obligación de pago.
8. Persiste los cambios de forma atómica y consistente.
9. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
10. Registra auditoría y eventos de dominio aplicables.
11. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Idempotencia de la compra.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M29-002-01:** el happy path produce el resultado descripto.
- **CA-M29-002-02:** un usuario sin permiso no modifica información.
- **CA-M29-002-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M29-002-04:** una validación fallida no deja datos parciales.
- **CA-M29-002-05:** la operación conserva trazabilidad histórica.
- **CA-M29-002-06:** reintentar la compra no duplica pase, créditos ni obligación.

### RF-M29-003 — Consumir crédito por asistencia

**Descripción:** Descontar un crédito válido al confirmar una asistencia cubierta por pase.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Consumir crédito por asistencia** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Selecciona pase elegible.
6. Bloquea/revalida saldo.
7. Crea MovimientoPase CONSUMO con saldo anterior/posterior.
8. Vincula movimiento a la asistencia.
9. Persiste los cambios de forma atómica y consistente.
10. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
11. Registra auditoría y eventos de dominio aplicables.
12. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Saldo suficiente y vigencia válida.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M29-003-01:** el happy path produce el resultado descripto.
- **CA-M29-003-02:** un usuario sin permiso no modifica información.
- **CA-M29-003-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M29-003-04:** una validación fallida no deja datos parciales.
- **CA-M29-003-05:** la operación conserva trazabilidad histórica.
- **CA-M29-003-06:** saldo 8 pasa a 7 exactamente una vez.

### RF-M29-004 — Devolver crédito por cancelación

**Descripción:** Restituir crédito cuando la política de cancelación lo permite.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Devolver crédito por cancelación** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Localiza consumo original.
6. Crea MovimientoPase DEVOLUCION referenciado.
7. Actualiza saldo.
8. Persiste los cambios de forma atómica y consistente.
9. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
10. Registra auditoría y eventos de dominio aplicables.
11. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- No devolver dos veces el mismo consumo.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M29-004-01:** el happy path produce el resultado descripto.
- **CA-M29-004-02:** un usuario sin permiso no modifica información.
- **CA-M29-004-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M29-004-04:** una validación fallida no deja datos parciales.
- **CA-M29-004-05:** la operación conserva trazabilidad histórica.
- **CA-M29-004-06:** una cancelación idempotente produce una sola devolución.

### RF-M29-005 — Ajustar saldo manualmente

**Descripción:** Permitir correcciones excepcionales con autorización y motivo obligatorio.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Ajustar saldo manualmente** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Solicita cantidad positiva/negativa permitida y motivo.
6. Crea MovimientoPase AJUSTE.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- No permitir saldo final negativo.
- Requiere permiso específico.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M29-005-01:** el happy path produce el resultado descripto.
- **CA-M29-005-02:** un usuario sin permiso no modifica información.
- **CA-M29-005-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M29-005-04:** una validación fallida no deja datos parciales.
- **CA-M29-005-05:** la operación conserva trazabilidad histórica.
- **CA-M29-005-06:** el ajuste puede auditarse con actor y motivo.

### RF-M29-006 — Vencer PaseServicio

**Descripción:** Finalizar el uso de créditos al alcanzar la vigencia según política.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Vencer PaseServicio** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Detecta vencimiento.
6. Actualiza estado.
7. Registra MovimientoPase VENCIMIENTO cuando se descartan créditos.
8. Persiste los cambios de forma atómica y consistente.
9. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
10. Registra auditoría y eventos de dominio aplicables.
11. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- No consumir créditos luego del vencimiento.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M29-006-01:** el happy path produce el resultado descripto.
- **CA-M29-006-02:** un usuario sin permiso no modifica información.
- **CA-M29-006-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M29-006-04:** una validación fallida no deja datos parciales.
- **CA-M29-006-05:** la operación conserva trazabilidad histórica.
- **CA-M29-006-06:** los créditos remanentes quedan explicados por movimiento de vencimiento.

### RF-M29-007 — Crear y vender AbonoServicio

**Descripción:** Asignar a una persona cobertura de una Oferta durante un período determinado.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Crear y vender AbonoServicio** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Define período, fecha desde/hasta e importe.
6. Genera obligación económica.
7. Activa el abono según política de pago.
8. Persiste los cambios de forma atómica y consistente.
9. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
10. Registra auditoría y eventos de dominio aplicables.
11. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Período coherente y Oferta compatible.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M29-007-01:** el happy path produce el resultado descripto.
- **CA-M29-007-02:** un usuario sin permiso no modifica información.
- **CA-M29-007-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M29-007-04:** una validación fallida no deja datos parciales.
- **CA-M29-007-05:** la operación conserva trazabilidad histórica.
- **CA-M29-007-06:** Abono Agosto cubre las asistencias permitidas entre sus fechas.

### RF-M29-008 — Validar cobertura por AbonoServicio

**Descripción:** Determinar si una asistencia está incluida en un abono vigente.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Validar cobertura por AbonoServicio** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Busca abono activo para persona/oferta/fecha.
6. Marca asistencia como cubierta sin generar obligación unitaria.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- No utilizar abono fuera de vigencia o para Oferta no incluida.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M29-008-01:** el happy path produce el resultado descripto.
- **CA-M29-008-02:** un usuario sin permiso no modifica información.
- **CA-M29-008-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M29-008-04:** una validación fallida no deja datos parciales.
- **CA-M29-008-05:** la operación conserva trazabilidad histórica.
- **CA-M29-008-06:** asistencia cubierta no crea nueva deuda POR_CLASE.

### RF-M29-009 — Consultar estado y movimientos de productos

**Descripción:** Mostrar saldo, vigencia, consumos, devoluciones, ajustes y relación con cobros/asistencias.

**Precondiciones generales:**
- Usuario autenticado cuando la operación no sea pública.
- Tenant y consultorio resueltos cuando corresponda.
- Actor con permisos suficientes.
- Entidades referenciadas existentes, vigentes y pertenecientes al mismo tenant.

**Disparador:**
- El actor solicita la operación **Consultar estado y movimientos de productos** desde UI, API o evento de negocio autorizado.

**Flujo principal:**
1. El sistema identifica al actor, organización y consultorio activos.
2. Valida autorización, membership y aislamiento multi-tenant.
3. Recupera las entidades relacionadas y revalida su estado vigente.
4. Valida los datos funcionales de entrada y la coherencia con la configuración del servicio/oferta cuando corresponda.
5. Recupera movimientos ordenados.
6. Calcula o verifica saldo actual contra historial.
7. Persiste los cambios de forma atómica y consistente.
8. Actualiza estados, saldos, cupos, relaciones o proyecciones derivadas cuando corresponda.
9. Registra auditoría y eventos de dominio aplicables.
10. Devuelve el resultado funcional y los datos necesarios para refrescar la interfaz.

**Flujos alternativos / excepciones:**
- Si el actor no posee permiso, rechazar sin producir cambios.
- Si una entidad pertenece a otro tenant, tratarla como no accesible.
- Si el estado actual no admite la operación, devolver conflicto funcional.
- Si existe una condición concurrente, revalidar antes de confirmar y no sobrescribir silenciosamente.
- Si la operación es reintentada luego de timeout, aplicar idempotencia cuando corresponda.

**Validaciones obligatorias:**
- Campos requeridos y formato.
- Vigencia y estado de referencias.
- Coherencia temporal.
- Aislamiento multi-tenant.
- Permisos en backend.
- Consistencia con las reglas del módulo y con la configuración efectiva de la Oferta de Servicio.
- Saldo mostrado debe coincidir con reconstrucción por movimientos.

**Postcondiciones:**
- La operación queda persistida o se rechaza sin cambios parciales silenciosos.
- Los datos derivados quedan consistentes con la entidad principal.
- Los históricos previos permanecen consultables.

**Errores esperables:**
- `VALIDATION_ERROR`: datos inválidos o incompletos.
- `FORBIDDEN`: actor sin permiso.
- `NOT_FOUND`: entidad inexistente/no accesible.
- `CONFLICT`: estado, cupo, agenda o concurrencia incompatible.
- `BUSINESS_RULE_VIOLATION`: regla de negocio incumplida.
- `INTERNAL_ERROR`: error no previsto, sin exponer información sensible.

**Auditoría:**
- Registrar actor, fecha/hora, tenant, consultorio, entidad, operación y cambios relevantes.
- No registrar contraseñas, tokens completos ni datos sensibles innecesarios.

**Criterios de aceptación:**
- **CA-M29-009-01:** el happy path produce el resultado descripto.
- **CA-M29-009-02:** un usuario sin permiso no modifica información.
- **CA-M29-009-03:** una referencia de otro tenant no puede utilizarse.
- **CA-M29-009-04:** una validación fallida no deja datos parciales.
- **CA-M29-009-05:** la operación conserva trazabilidad histórica.
- **CA-M29-009-06:** el usuario puede explicar cómo se pasó de 8 a 5 créditos mediante tres consumos.

## 6. Requerimientos no funcionales específicos

### RNF-M29-001 — Integridad de saldo

Las modificaciones de créditos deben realizarse con control de concurrencia y nunca mediante edición directa silenciosa del saldo.

### RNF-M29-002 — Idempotencia

Compra, consumo, devolución y vencimiento deben soportar reintentos sin duplicar movimientos.

### RNF-M29-003 — Auditoría

MovimientoPase debe conservar actor/sistema, timestamp, referencia y saldo anterior/posterior.

### RNF-M29-004 — Rendimiento

La consulta de pase elegible al check-in debe ser rápida e indexada por persona, oferta, estado y vigencia.

### RNF-M29-005 — Contabilidad operativa

Los consumos de créditos no deben duplicar ingresos de caja ya reconocidos en la compra.

## 7. Casos borde mínimos de QA

- Dos asistencias intentan consumir el último crédito al mismo tiempo.
- Pase vence durante una clase ya inscripta.
- Devolución después de vencimiento.
- Abono solapado con pack.
- Pago de compra rechazado luego de crear el producto: política de estado pendiente.
- Ajuste manual negativo cercano a cero.

---

# 30. Visión funcional consolidada — Servicios, Clases y Circuito de Salud

## 30.1 Evolución del alcance de AKINE

AKINE evoluciona desde un modelo centrado exclusivamente en tratamientos hacia un modelo centrado en **servicios de salud, rehabilitación, actividad terapéutica, prevención y bienestar ofrecidos por un consultorio**.

El flujo clínico existente permanece válido y obligatorio cuando corresponde:

Paciente → Historia Clínica → Caso Clínico → Plan de Tratamiento → Turno → Sesión/Atención → Obligación económica → Cobro → Caja → Reportes.

En paralelo se incorpora el flujo no clínico:

Persona → Oferta de Servicio → ClaseProgramada → Inscripción → Asistencia → Pago / Pack / Abono → Caja / Reportes.

Ambos flujos pueden converger sin confundirse. Una Persona puede comenzar con una actividad preventiva y posteriormente activar su PerfilPaciente para iniciar rehabilitación sin duplicar identidad.

## 30.2 Circuito de salud

El modelo permite representar continuidad real de una persona:

lesión → rehabilitación → readaptación deportiva → actividad preventiva → mantenimiento / bienestar.

Ejemplo funcional:

1. La persona ingresa por lesión de LCA.
2. Se crea PerfilPaciente, Historia Clínica y Caso Clínico.
3. Realiza sesiones de Kinesiología bajo Plan de Tratamiento.
4. Evoluciona a una Oferta de Readaptación Deportiva configurada como clínica, individual o grupal.
5. Al cerrar el Caso puede continuar en Pilates Preventivo o Entrenamiento Funcional configurado como no clínico.
6. Las nuevas asistencias quedan registradas operativamente, pero no producen evoluciones clínicas artificiales.

## 30.3 Ejemplos de configuración válidos

| Servicio | Modalidad | Requiere Caso Clínico | Genera Registro Clínico | Capacidad orientativa |
|---|---|---:|---:|---:|
| Kinesiología | Individual | Sí | Sí | 1 |
| RPG | Individual | Sí | Sí | 1 |
| Punción Seca | Individual | Sí | Sí | 1 |
| Osteopatía | Individual | Configurable | Sí | 1 |
| Pilates Clínico | Grupal | Configurable | Configurable | 8 |
| Readaptación Deportiva | Grupal / Individual | Sí | Sí | 6 |
| Pilates General | Grupal | No | No | 10 |
| Yoga | Grupal | No | No | 12 |
| Stretching | Grupal | No | No | 12 |
| Entrenamiento Funcional | Grupal | No | No | 10 |

Estos valores son ejemplos funcionales y **no deben hardcodearse**. Cada consultorio resuelve su Oferta concreta.

## 30.4 Qué no hacer

No se debe modelar automáticamente:

- `Pilates = práctica clínica`;
- `Yoga = práctica clínica`;
- `Clase = Sesión Clínica`;
- `Alumno = Paciente`;
- `Clase grupal = múltiples turnos individuales desconectados`;
- `Caso Clínico: Pilates` para una persona que realiza solamente actividad preventiva;
- `Instructor` como rol de seguridad obligatorio por profesión;
- `Suscripción` de servicio reutilizando la Suscripción SaaS.

Estas simplificaciones rompen la semántica de Historia Clínica, Casos, Agenda, Facturación, Caja y Reportes.

## 30.5 Modelo conceptual consolidado

Organización / Empresa
→ Consultorio
→ Servicio
→ OfertaServicioConsultorio
→ Agenda (Turno o ClaseProgramada)
→ Reserva / Inscripción
→ Persona
→ Prestación realizada
   - AtenciónClínica cuando corresponda
   - ParticipaciónActividad cuando corresponda
→ Impacto económico según esquema
→ Cobro
→ Caja
→ Reportes.

Para el subconjunto clínico se mantiene adicionalmente:

Persona / Paciente → Historia Clínica → Caso Clínico → Plan de Tratamiento → Atención Clínica.

## 30.6 Campos conceptuales mínimos de las nuevas entidades

### Servicio

- id
- nombre
- descripción
- categoría / naturaleza
- modalidadDefault
- requiereCasoClinicoDefault
- generaRegistroClinicoDefault
- activo

### OfertaServicioConsultorio

- id
- consultorioId
- servicioId
- nombreComercial
- descripción
- modalidad
- duraciónMinutos
- capacidad
- precioBase
- moneda
- admiteObraSocial
- requiereCasoClinico
- generaRegistroClinico
- requiereProfesional
- requiereEspacio
- esquemaCobro
- vigenciaDesde
- vigenciaHasta
- activo

### ClaseProgramada

- id
- ofertaServicioId
- consultorioId
- profesionalId
- espacioId
- fecha
- horaDesde
- horaHasta
- capacidad
- estado

### InscripcionClase

- id
- claseId
- personaId
- fechaInscripcion
- estado
- origen
- observaciones

Estados mínimos: `RESERVADA`, `CONFIRMADA`, `ASISTIO`, `AUSENTE`, `CANCELADA`, `LISTA_ESPERA`.

### AsistenciaActividad

- id
- claseId
- personaId
- fecha
- asistencia
- profesionalId
- observaciones

### PaseServicio

- id
- personaId
- ofertaServicioId
- cantidadCreditosInicial
- cantidadCreditosDisponibles
- fechaCompra
- vigenciaDesde
- vigenciaHasta
- estado

### MovimientoPase

- id
- paseId
- fecha
- tipoMovimiento
- cantidad
- referencia
- saldoAnterior
- saldoPosterior

Tipos mínimos: `COMPRA`, `CONSUMO`, `AJUSTE`, `DEVOLUCION`, `VENCIMIENTO`.

### AbonoServicio

- id
- personaId
- ofertaServicioId
- periodo
- fechaDesde
- fechaHasta
- importe
- estado

## 30.7 Estrategia incremental obligatoria

Fase 1: catálogo `Servicio` + `OfertaServicioConsultorio`, sin reemplazar Turno ni Sesión.

Fase 2: `ClaseProgramada` + `InscripcionClase` + `AsistenciaActividad`, manteniendo Turnos intactos.

Fase 3: esquemas `POR_CLASE`, `PACK`, `ABONO` y entidades de Pase/Movimiento/Abono integradas al circuito económico.

Fase 4: actividades clínicas grupales con atención, Caso y evolución individual por participante.

Fase 5: evaluar consolidación técnica de `EventoAgenda` y `PrestacionRealizada` solo cuando ambos flujos estén maduros.

## 30.8 Regla funcional final

**No toda persona que utiliza un servicio es necesariamente un paciente clínico, no toda actividad es una sesión clínica y no toda prestación requiere un Caso Clínico.**

Esta regla debe preservarse en dominio, API, base de datos, UX/UI, pruebas, auditoría y reportes.

---

# 31. Matriz de trazabilidad de la extensión original

La siguiente matriz permite verificar que los 45 apartados de `AKINE_Extension_Servicios_Clases_Actividades(1).md` fueron incorporados y no quedaron como un anexo aislado.

| Apartado extensión | Integración principal |
|---|---|
| 1 Contexto | Sección 30 + reglas maestras |
| 2 Problema del modelo actual | Reglas maestras + M07/M10/M14 |
| 3 Nuevo concepto Servicio | M06 + M27 |
| 4 No hardcodear | M06 + M27 + reglas maestras |
| 5 Dimensiones | M27 |
| 6 Ejemplos | 30.3 |
| 7 Oferta por consultorio | M03 + M27 |
| 8 Campos Oferta | M27 + 30.6 |
| 9 Generalización agenda | M12 + M28 |
| 10 EventoAgenda | M12 |
| 11 Reserva individual | M12 existente + M27 |
| 12 Clase grupal | M12 + M28 |
| 13 Estrategia incremental Turno | M12 + 30.7 |
| 14 Diferenciar Sesión/Asistencia | M14 + M28 |
| 15 Atención clínica | M14 existente/ampliado |
| 16 Participación actividad | M14 + M28 |
| 17 Ejemplo clínico | M10/M11/M14 + 30 |
| 18 Ejemplo no clínico | M07/M14/M28 + 30 |
| 19 Híbrido grupal clínico | M09/M10/M14/M28 |
| 20 Persona vs Paciente | M07 + reglas maestras |
| 21 Impacto económico | M18 + M29 |
| 22 Cobro por clase | M18/M19/M20 |
| 23 Pack de clases | M18/M29 |
| 24 Abono mensual | M18/M29 |
| 25 Evitar Suscripción | M29 + reglas maestras |
| 26 Espacios físicos | M04 |
| 27 Profesionales/instructores | M05 |
| 28 No rol por profesión | M05 |
| 29 Modelo conceptual | 30.5 |
| 30 Flujo clínico permanece | Reglas maestras + 30.1 |
| 31 Flujo no clínico | M27/M28/M29 + 30.1 |
| 32 Circuito de salud | M23 + 30.2 |
| 33 Entidades nuevas | M27/M28/M29 |
| 34 Entidades conceptuales | 30.6 + M27/M28/M29 |
| 35 RF principales | Distribuidos en M03–M29 |
| 36 Reglas UX/UI | M28 RNF + reglas existentes |
| 37 Agenda | M12/M28 |
| 38 Gestión de clase | M28 |
| 39 Actividad clínica grupal | M14/M28 |
| 40 Qué no hacer | 30.4 |
| 41 Evolución conceptual | 30.1 |
| 42 Beneficio funcional | 30.1–30.3 |
| 43 Beneficio comercial | M23 + 30.2 |
| 44 Implementación por fases | 30.7 |
| 45 Definición final | 30.8 |

---

# 32. Requerimientos Transversales Complementarios

## 27.1 Matriz mínima de permisos

| Acción | Admin plataforma | Admin organización | Admin consultorio | Profesional | Administrativo | Paciente |
|---|---:|---:|---:|---:|---:|---:|
| Gestionar tenant | Sí | Limitado | No | No | No | No |
| Gestionar consultorio | Global | Sí | Sí | No | No | No |
| Gestionar colaboradores | Global | Sí | Sí | No | No | No |
| Gestionar paciente | Soporte | Sí | Sí | Según permiso | Sí | Propio |
| Ver Historia Clínica | Restringido | No por defecto | Según rol clínico | Sí | Limitado | Propia autorizada |
| Editar Historia Clínica | No por defecto | No | No por defecto | Sí | No | No |
| Crear Caso Clínico | No | No | Según rol clínico | Sí | No | No |
| Registrar Sesión | No | No | Según rol clínico | Sí | No | No |
| Administrar Convenios | Catálogo global | Sí | Sí | No | No | No |
| Registrar Cobro | No | Sí | Sí | No por defecto | Sí | No |
| Operar Caja | No | Sí | Sí | No | Sí | No |
| Ver Reportes | Global | Sí | Sí | Limitado | Limitado | No |

Esta tabla es una base funcional. El backend debe implementar permisos más granulares.

---

# 33. Estados y máquinas de transición

Todo módulo con estados debe definir explícitamente:

- estado inicial;
- estados finales;
- transiciones permitidas;
- transiciones prohibidas;
- actor autorizado;
- efectos colaterales;
- auditoría.

No debe existir lógica donde el frontend pueda asignar arbitrariamente cualquier estado.

Ejemplos críticos:

- suscripción;
- membership;
- turno;
- sesión;
- Caso Clínico;
- Plan de Tratamiento;
- autorización;
- obligación;
- cobro;
- caja;
- presentación a financiador.

---

# 34. Idempotencia obligatoria

Deben contemplar claves/idempotencia o mecanismos equivalentes:

- finalización de sesión;
- asignación del número de sesión;
- consumo de autorización;
- generación de obligación económica;
- confirmación de cobro;
- imputación;
- generación de movimiento de caja;
- recepción de pago de financiador;
- reintentos de notificación.

El objetivo es evitar duplicados por:

- doble click;
- timeout;
- retry HTTP;
- retry de cola;
- reconexión del cliente.

---

# 35. Concurrencia

Debe diseñarse protección específica para:

- reservar turno;
- reservar box;
- crear correlativo de sesión dentro del caso;
- generar número de comprobante;
- consumir saldo autorizado;
- imputar saldo pendiente;
- cerrar caja;
- editar una sesión en paralelo.

La implementación puede usar:

- optimistic locking;
- pessimistic locking;
- constraints únicas;
- transacciones;
- versiones;
- serialización puntual.

La técnica exacta depende de arquitectura, pero la condición funcional es obligatoria: no deben existir duplicaciones silenciosas.

---

# 36. Fechas y horas

Reglas:

- manejar timestamps de forma no ambigua;
- aplicar zona horaria del consultorio;
- mostrar `DD/MM/YYYY` para usuario final en Argentina;
- registrar hora real de check-in e inicio/fin de sesión;
- diferenciar fecha de negocio de fecha técnica de creación/modificación;
- no usar la fecha del browser como única autoridad para operaciones críticas.

---

# 37. Importes y precisión monetaria

Reglas:

- usar tipos decimales exactos;
- no usar `float` para importes;
- guardar importe original;
- guardar importe pagado;
- derivar importe pendiente;
- guardar snapshot del arancel/convenio aplicado;
- no recalcular históricos por cambios de tarifa;
- documentar redondeos cuando se incorporen reglas que los requieran.

---

# 38. Bajas lógicas y vigencias

Entidades con uso histórico deben conservarse.

Como mínimo:

- consultorios;
- espacios;
- profesionales;
- memberships;
- especialidades;
- prácticas;
- financiadores;
- planes;
- convenios.

En nuevas selecciones se ocultan inactivos por defecto.

En históricos se muestran con su nombre original y estado correspondiente.

---

# 39. Errores funcionales y contrato de API

Los servicios deben distinguir:

- `VALIDATION_ERROR`;
- `NOT_FOUND`;
- `FORBIDDEN`;
- `CONFLICT`;
- `BUSINESS_RULE_VIOLATION`;
- `INTERNAL_ERROR`.

Ejemplo:

No devolver un error genérico si un turno fue tomado por otro usuario.

La respuesta funcional debe permitir a UI mostrar:

> El horario seleccionado ya no está disponible. Seleccioná otro turno.

Los códigos HTTP deben ser coherentes con el tipo de error.

---

# 40. Pruebas obligatorias por requerimiento

Todo RF debe probar como mínimo:

1. Happy path.
2. Datos obligatorios faltantes.
3. Formatos inválidos.
4. Usuario sin permiso.
5. Referencia de otro tenant.
6. Estado incompatible.
7. Concurrencia, cuando aplique.
8. Reintento/idempotencia, cuando aplique.
9. Datos históricos con referencias inactivas.
10. Auditoría, cuando aplique.
11. Paginación/filtros en listados.
12. Error interno sin exposición de información sensible.

---

# 41. Definition of Done funcional

Un requerimiento no está terminado porque exista la pantalla.

Para considerarse completo debe incluir, según corresponda:

- migración/modelo de datos;
- backend;
- API;
- permisos;
- multi-tenancy;
- validaciones;
- reglas de negocio;
- estados;
- errores funcionales;
- auditoría;
- frontend;
- responsive;
- pruebas unitarias;
- pruebas de integración;
- E2E del flujo crítico;
- documentación OpenAPI;
- criterios de aceptación cumplidos;
- no regresión sobre históricos.

---

# 42. Eventos de dominio sugeridos

| Evento | Módulo origen | Módulos consumidores posibles |
|---|---|---|
| `OrganizationCreated` | M01 | configuración, auditoría |
| `MembershipActivated` | M02/M05 | permisos, notificaciones |
| `ConsultorioCreated` | M03 | onboarding |
| `AvailabilityChanged` | M05 | agenda |
| `PatientCreated` | M07 | HC |
| `ClinicalCaseOpened` | M10 | plan |
| `TreatmentPlanActivated` | M11 | agenda/autorizaciones |
| `AppointmentBooked` | M12 | notificaciones |
| `PatientCheckedIn` | M13 | sesión |
| `SessionStarted` | M14 | agenda |
| `SessionCompleted` | M14 | HC, obligaciones, financiadores |
| `AuthorizationConsumed` | M17 | alertas |
| `EconomicObligationCreated` | M18 | cuenta corriente |
| `PaymentConfirmed` | M19 | imputación, caja |
| `CashMovementCreated` | M20 | reportes |
| `ClaimSubmitted` | M21 | cuenta corriente financiador |
| `PayerPaymentReceived` | M21 | caja, conciliación |

Los eventos son una estrategia sugerida de desacoplamiento. No implican que AKINE deba convertirse en microservicios.

---

# 43. Uso conjunto con el documento maestro

El documento:

`AKINE_Especificacion_Funcional_No_Funcional_Completa.md`

define principalmente:

**qué es AKINE, cómo se estructura y cuáles son sus módulos.**

Este complemento define:

**cómo debe comportarse cada módulo, qué validar y cómo verificar su implementación.**

Cada cambio funcional nuevo debería actualizar:

1. módulo afectado;
2. RF;
3. reglas de negocio;
4. estados;
5. permisos;
6. criterios de aceptación;
7. RNF específicos;
8. pruebas;
9. dependencias;
10. eventos o efectos derivados.

---

# 44. Regla final para agentes de desarrollo

Antes de implementar cualquier requerimiento de AKINE, el agente debe determinar explícitamente:

- tenant;
- consultorio;
- actor;
- permiso;
- entidad principal;
- estado actual;
- estado destino;
- entidades relacionadas;
- validaciones;
- impacto clínico;
- impacto económico;
- impacto en caja;
- auditoría;
- idempotencia;
- concurrencia;
- criterio de aceptación.

Si alguno de estos puntos resulta aplicable y no está definido, debe tratarse como un hueco funcional a resolver, no inventarse silenciosamente.

# 45. Fuentes de integración de esta versión

Esta especificación fue construida preservando el contenido del documento base `AKINE_Complemento_Requerimientos_Detallados_por_Modulo(1).md` e integrando funcionalmente `AKINE_Extension_Servicios_Clases_Actividades(1).md`.

La integración no elimina requerimientos previos. Las ampliaciones se incorporan como reglas y RF adicionales dentro de los módulos existentes o como módulos M27–M29 cuando el concepto no tenía un equivalente correcto en el modelo anterior.


