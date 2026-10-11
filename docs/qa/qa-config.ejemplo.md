# QA Config — AKINE (appKine-api + appKine-web)

<!--
Plantilla de G-12. Copiala a C:\Users\santo\Desktop\AKINE\appKine-api\.claude\qa-config.md, que está
gitignored (.gitignore línea 2), y completá lo marcado COMPLETAR. Lleva contraseñas de cuentas
SINTÉTICAS de una base local: nunca la commitees y nunca uses una contraseña real.
La guía de ejecución es docs/qa/README.md.
-->

## Stack

- Runtime: Java 21 (Spring Boot 4.1.1) · Angular 21.2 sobre Node 24
- DB: MySQL 8.4 en Docker (`akine-mysql`, del `compose.yaml` del backend)
- Perfil activo: `local`
- Base de la corrida: **`akine_qa`** (aparte de `akine_local`, la de desarrollo) — README §2.1

## Levantar la base de datos

```powershell
cd C:\Users\santo\Desktop\AKINE\appKine-api
docker compose up -d
docker compose ps    # akine-mysql y akine-mailpit "healthy"
docker exec akine-mysql mysql -uroot -proot -e "CREATE DATABASE IF NOT EXISTS akine_qa CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; GRANT ALL ON akine_qa.* TO 'akine'@'%';"
```

## Base de datos

- Host: `localhost:3306` (contenedor `akine-mysql`)
- Database: `akine_qa`
- Usuario / password: `akine` / `akine` (credenciales locales del `compose.yaml`, no son secretos)

Cliente interactivo para toda la corrida (las variables `@...` viven en la sesión):

```powershell
docker exec -it akine-mysql mysql -uakine -pakine akine_qa
```

Flyway crea el esquema al arrancar el backend (`V1`…`V85`). Las fechas se guardan en **UTC**.

## Levantar el backend

```powershell
cd C:\Users\santo\Desktop\AKINE\appKine-api
$env:AKINE_DB_URL = 'jdbc:mysql://localhost:3306/akine_qa?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC'
$env:AKINE_BOOTSTRAP_ADMIN_EMAIL = 'qa.plataforma@ejemplo.test'
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

Health check: `GET http://localhost:8080/actuator/health` → `{"status":"UP"}`.

## API Base URL

`http://localhost:8080` · contrato en `http://localhost:8080/v3/api-docs` (solo perfil local).

## Levantar el frontend

Misma rama que el backend.

```powershell
cd C:\Users\santo\Desktop\AKINE\appKine-web
git branch --show-current
npm start     # http://localhost:4200, con proxy.conf.json → localhost:8080
```

### Local API Switch

**No hay switch de API que tocar.** El proxy de desarrollo (`proxy.conf.json`) redirige `/api`,
`/actuator` y `/v3/api-docs` al backend local. Nada que cambiar antes del QA ni que revertir después.

Correos: Mailpit en `http://localhost:8025`.

## Cuentas de prueba (todas sintéticas)

| Rol | Email | Password | Organización |
|---|---|---|---|
| `PLATFORM_ADMIN` | `qa.plataforma@ejemplo.test` | COMPLETAR | — |
| `ORG_ADMIN` fundador | `qa.admin-a@ejemplo.test` | COMPLETAR | Centro QA A |
| `PROFESIONAL` | `qa.profesional@ejemplo.test` | COMPLETAR | A (sede) + la propia |
| `ADMINISTRATIVO` | `qa.administrativo@ejemplo.test` | COMPLETAR | A (sede) + la propia |
| `PACIENTE` | `qa.paciente@ejemplo.test` | COMPLETAR | A (sede) + la propia |
| `ADMINISTRATIVO` por invitación | `qa.invitado@ejemplo.test` | COMPLETAR | A (sede) |
| `ORG_ADMIN` fundador | `qa.admin-b@ejemplo.test` | COMPLETAR | **Centro QA B** — aislamiento |

Login por la API, con las ayudas de `docs/qa/qa-api.ps1` (piden la contraseña sin mostrarla):

```powershell
. C:\Users\santo\Desktop\AKINE\appKine-api\docs\qa\qa-api.ps1
$A = Entrar 'qa.admin-a@ejemplo.test'
$B = Entrar 'qa.admin-b@ejemplo.test'
```

## Variables de la corrida

Para volver a pegarlas en el cliente MySQL si se corta la sesión (README §4.1). COMPLETAR a medida
que aparecen:

```sql
SET @desde := NULL;        -- COMPLETAR: UTC de inicio, p. ej. '2026-10-12 12:00:00'
SET @org_a := NULL;        -- COMPLETAR (y así cada una)
SET @sede_a := NULL;  SET @org_b := NULL;  SET @sede_b := NULL;
SET @persona := NULL; SET @persona_part := NULL; SET @oferta := NULL; SET @practica := NULL;
SET @financiador := NULL; SET @cobertura := NULL; SET @autorizacion := NULL;
SET @turno := NULL; SET @sesion := NULL; SET @historia := NULL; SET @presentacion := NULL;
```

## Seed QA

No hay seed por SQL, a propósito: el QA crea todo **por la pantalla** (circuitos 01 a 04) porque eso
también es lo que se prueba. El sembrado por API de `docs/qa/validacion/sembrar.mjs` es para validar
las consultas en el stack aislado, no para el QA.

## Protocolo de validación por petición

1. Hacer la acción en la pantalla (o con `Api` si no tiene pantalla).
2. Mirar el resultado esperado de la guía.
3. Correr la consulta SQL del paso. **Innegociable**: un 200 no prueba que el dato quedó guardado.
4. Mirar la auditoría que el paso indica.

## Regla: no maquillar la DB para forzar estados

La guía no tiene un solo `UPDATE`. Si un flujo no llega, se registra como hallazgo (README §7) y se
sigue por otro camino.

## Referencia de casos de prueba

`docs/qa/README.md` y los circuitos `docs/qa/01-…` a `11-…`. Lo que no es bug:
`docs/qa/90-conocido-y-aceptado.md`.

## Notas críticas

| Regla | Detalle |
|---|---|
| Otro proyecto en el disco | `C:\Users\santo\Documents\akine\akine-*` **no es éste**. Verificá el remote |
| Contexto | Todo endpoint de negocio pide un token **con contexto** (`POST /auth/context`): sin contexto es 403 |
| Aislamiento | Otra organización es **404**, nunca 403 |
| Justificación clínica | `X-Justificacion-Acceso` cuando no hay relación asistencial; sin ella, 403 `requiereJustificacion` |
| Concurrencia | Versión vieja → 409 `concurrent-modification`: recargar |
| Baja lógica | `active = 0` + `deleted_at`; nada histórico se borra |
| Permisos en la base | `membership_grant.permission_code` guarda `PACIENTE_MANAGE`, no `paciente:manage` |
| Límites | 5 altas self-service por minuto y por IP; 30 logins por minuto; plan `BASICO`: 5 miembros y 1 sede |
| Zona | La base en UTC; la sede en `America/Argentina/Cordoba` (UTC−3) |
