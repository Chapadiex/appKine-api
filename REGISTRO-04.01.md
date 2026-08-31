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
