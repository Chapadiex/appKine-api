# Conciliacion MySQL → PostgreSQL

- Origen: `jdbc:mysql://localhost:3306/akine_rag`
- Destino: `jdbc:postgresql://localhost:5432/akine_rag`
- Fecha: 2026-11-12T04:51:12.799520Z
- Duracion: 6639 ms
- Filas: 1218 en origen, 1218 en destino
- Huerfanos: ninguno

| Tabla | Origen | Destino | Dif | Sumas de control | Nota |
|---|---:|---:|---:|---|---|
| account_active_context | 1 | 1 | 0 | ok (7 controles) |  |
| aceptacion_terminos | 0 | 0 | 0 | ok (9 controles) |  |
| adjunto_administrativo | 0 | 0 | 0 | ok (35 controles) |  |
| adjunto_clinico | 0 | 0 | 0 | ok (36 controles) |  |
| agenda_profesional | 0 | 0 | 0 | ok (4 controles) | columnas nuevas con su default: [created_at] |
| agenda_sede | 0 | 0 | 0 | ok (4 controles) | columnas nuevas con su default: [created_at] |
| asistencia_actividad | 0 | 0 | 0 | ok (22 controles) | columnas nuevas con su default: [created_at, updated_at] |
| asistencia_evento | 0 | 0 | 0 | ok (17 controles) |  |
| audit_event | 135 | 135 | 0 | ok (21 controles) |  |
| autorizacion | 0 | 0 | 0 | ok (48 controles) |  |
| autorizacion_alerta | 0 | 0 | 0 | ok (22 controles) |  |
| autorizacion_evento | 0 | 0 | 0 | ok (23 controles) |  |
| autorizacion_movimiento | 0 | 0 | 0 | ok (20 controles) |  |
| autorizacion_persona_lock | 0 | 0 | 0 | ok (6 controles) |  |
| biblioteca_version | 1 | 1 | 0 | ok (2 controles) |  |
| caso_clinico | 0 | 0 | 0 | ok (27 controles) |  |
| caso_evento | 0 | 0 | 0 | ok (19 controles) |  |
| caso_numerador | 0 | 0 | 0 | ok (5 controles) | columnas nuevas con su default: [created_at, updated_at] |
| caso_profesional | 0 | 0 | 0 | ok (15 controles) |  |
| caso_sesion_numerador | 0 | 0 | 0 | ok (5 controles) | columnas nuevas con su default: [created_at, updated_at] |
| catalogo_solicitud | 0 | 0 | 0 | ok (27 controles) |  |
| clase_evento | 0 | 0 | 0 | ok (26 controles) |  |
| clase_instructor | 0 | 0 | 0 | ok (12 controles) |  |
| clase_programada | 0 | 0 | 0 | ok (42 controles) | columnas nuevas con su default: [created_at, updated_at] |
| cobertura_paciente | 0 | 0 | 0 | ok (51 controles) |  |
| cobertura_persona_lock | 0 | 0 | 0 | ok (6 controles) |  |
| cobro | 0 | 0 | 0 | ok (26 controles) | columnas nuevas con su default: [created_at, updated_at] |
| cobro_imputacion | 0 | 0 | 0 | ok (14 controles) | columnas nuevas con su default: [created_at] |
| cobro_medio | 0 | 0 | 0 | ok (10 controles) | columnas nuevas con su default: [created_at] |
| cobro_reintegro | 0 | 0 | 0 | ok (23 controles) | columnas nuevas con su default: [created_at] |
| colaborador_invitacion | 0 | 0 | 0 | ok (28 controles) |  |
| comprobante_numerador | 0 | 0 | 0 | ok (5 controles) | columnas nuevas con su default: [created_at, updated_at] |
| consultorio | 1 | 1 | 0 | ok (59 controles) |  |
| consultorio_alta | 0 | 0 | 0 | ok (10 controles) |  |
| consultorio_calendario | 0 | 0 | 0 | ok (13 controles) |  |
| consultorio_horario | 0 | 0 | 0 | ok (15 controles) |  |
| convenio | 0 | 0 | 0 | ok (40 controles) |  |
| convenio_arancel | 0 | 0 | 0 | ok (30 controles) |  |
| convenio_lock | 0 | 0 | 0 | ok (6 controles) |  |
| cuenta | 2 | 2 | 0 | ok (31 controles) |  |
| derivacion_clinica | 0 | 0 | 0 | ok (33 controles) |  |
| disponibilidad_excepcion | 0 | 0 | 0 | ok (30 controles) |  |
| egreso | 0 | 0 | 0 | ok (53 controles) | columnas nuevas con su default: [created_at, updated_at] |
| entrada_clinica | 0 | 0 | 0 | ok (26 controles) |  |
| entrada_clinica_version | 0 | 0 | 0 | ok (16 controles) |  |
| espacio | 0 | 0 | 0 | ok (26 controles) |  |
| especialidad | 6 | 6 | 0 | ok (24 controles) |  |
| feriado | 21 | 21 | 0 | ok (14 controles) |  |
| financiador | 0 | 0 | 0 | ok (29 controles) |  |
| financiador_catalogo | 0 | 0 | 0 | ok (27 controles) |  |
| financiador_pago | 0 | 0 | 0 | ok (23 controles) | columnas nuevas con su default: [created_at] |
| historia_clinica | 0 | 0 | 0 | ok (23 controles) |  |
| historia_clinica_antecedente | 0 | 0 | 0 | ok (22 controles) |  |
| inscripcion_clase | 0 | 0 | 0 | ok (29 controles) | columnas nuevas con su default: [created_at, updated_at] |
| jornada_caja | 0 | 0 | 0 | ok (29 controles) | columnas nuevas con su default: [created_at, updated_at] |
| libro | 4 | 4 | 0 | ok (45 controles) |  |
| libro_fragmento | 972 | 972 | 0 | ok (14 controles) |  |
| liquidacion_honorario | 0 | 0 | 0 | ok (27 controles) |  |
| liquidacion_honorario_clase | 0 | 0 | 0 | ok (28 controles) |  |
| lista_espera_turno | 0 | 0 | 0 | ok (34 controles) |  |
| medicion_definicion | 0 | 0 | 0 | ok (28 controles) |  |
| membership | 1 | 1 | 0 | ok (28 controles) |  |
| membership_grant | 0 | 0 | 0 | ok (25 controles) |  |
| movimiento_caja | 0 | 0 | 0 | ok (30 controles) | columnas nuevas con su default: [created_at] |
| movimiento_pase | 0 | 0 | 0 | ok (17 controles) |  |
| nomenclador | 0 | 0 | 0 | ok (24 controles) |  |
| nomenclador_item | 0 | 0 | 0 | ok (28 controles) |  |
| notification_outbox | 2 | 2 | 0 | ok (29 controles) |  |
| obligacion | 0 | 0 | 0 | ok (69 controles) | columnas nuevas con su default: [created_at, updated_at] |
| oferta_duracion_profesional | 0 | 0 | 0 | ok (18 controles) |  |
| oferta_espacio_habilitado | 0 | 0 | 0 | ok (21 controles) |  |
| oferta_honorario_clase | 0 | 0 | 0 | ok (24 controles) |  |
| oferta_practica | 0 | 0 | 0 | ok (19 controles) |  |
| oferta_precio_particular | 0 | 0 | 0 | ok (24 controles) |  |
| oferta_profesional_habilitado | 0 | 0 | 0 | ok (21 controles) |  |
| oferta_servicio_consultorio | 0 | 0 | 0 | ok (50 controles) |  |
| onboarding_registro | 1 | 1 | 0 | ok (16 controles) |  |
| orden_medica | 0 | 0 | 0 | ok (35 controles) |  |
| organization | 1 | 1 | 0 | ok (23 controles) |  |
| organization_onboarding | 1 | 1 | 0 | ok (12 controles) |  |
| pago_egreso | 0 | 0 | 0 | ok (31 controles) | columnas nuevas con su default: [created_at, updated_at] |
| pase_servicio | 0 | 0 | 0 | ok (48 controles) | columnas nuevas con su default: [created_at, updated_at] |
| perfil_paciente | 0 | 0 | 0 | ok (20 controles) |  |
| persona | 0 | 0 | 0 | ok (38 controles) |  |
| plan | 2 | 2 | 0 | ok (15 controles) |  |
| plan_catalogo | 0 | 0 | 0 | ok (32 controles) |  |
| plan_cobertura | 0 | 0 | 0 | ok (34 controles) |  |
| plan_evento | 0 | 0 | 0 | ok (20 controles) |  |
| plan_feature | 2 | 2 | 0 | ok (13 controles) |  |
| plan_item | 0 | 0 | 0 | ok (18 controles) |  |
| plan_limit | 4 | 4 | 0 | ok (14 controles) |  |
| plan_numerador | 0 | 0 | 0 | ok (5 controles) | columnas nuevas con su default: [created_at, updated_at] |
| plan_tratamiento | 0 | 0 | 0 | ok (29 controles) |  |
| plan_tratamiento_version | 0 | 0 | 0 | ok (20 controles) |  |
| platform_role | 1 | 1 | 0 | ok (24 controles) |  |
| platform_schema_info | 1 | 1 | 0 | ok (10 controles) |  |
| politica_de_enmienda | 0 | 0 | 0 | ok (6 controles) | columnas nuevas con su default: [created_at, updated_at] |
| practica | 16 | 16 | 0 | ok (25 controles) |  |
| presentacion | 0 | 0 | 0 | ok (47 controles) | columnas nuevas con su default: [created_at, updated_at] |
| presentacion_item | 0 | 0 | 0 | ok (26 controles) | columnas nuevas con su default: [created_at, updated_at] |
| presentacion_numerador | 0 | 0 | 0 | ok (6 controles) | columnas nuevas con su default: [created_at, updated_at] |
| producto_servicio | 0 | 0 | 0 | ok (28 controles) | columnas nuevas con su default: [created_at, updated_at] |
| profesional_disponibilidad | 0 | 0 | 0 | ok (24 controles) |  |
| recepcion | 0 | 0 | 0 | ok (34 controles) | columnas nuevas con su default: [created_at, updated_at] |
| recepcion_evento | 0 | 0 | 0 | ok (17 controles) |  |
| refresh_token | 39 | 39 | 0 | ok (28 controles) |  |
| servicio | 0 | 0 | 0 | ok (27 controles) | columnas nuevas con su default: [organization_id] |
| sesion | 0 | 0 | 0 | ok (65 controles) | columnas nuevas con su default: [created_at, updated_at] |
| sesion_medicion | 0 | 0 | 0 | ok (32 controles) |  |
| sesion_numerador | 0 | 0 | 0 | ok (5 controles) | columnas nuevas con su default: [created_at, updated_at] |
| sesion_reemplazo_profesional | 0 | 0 | 0 | ok (12 controles) | columnas nuevas con su default: [created_at] |
| sesion_version | 0 | 0 | 0 | ok (33 controles) | columnas nuevas con su default: [created_at, updated_at] |
| sesion_version_medicion | 0 | 0 | 0 | ok (29 controles) |  |
| sesion_version_tratamiento | 0 | 0 | 0 | ok (26 controles) |  |
| sesion_version_tratamiento_parametro | 0 | 0 | 0 | ok (19 controles) |  |
| subscription | 1 | 1 | 0 | ok (17 controles) |  |
| subscription_transition | 1 | 1 | 0 | ok (15 controles) |  |
| support_access | 0 | 0 | 0 | ok (23 controles) |  |
| token_verificacion | 2 | 2 | 0 | ok (15 controles) |  |
| tratamiento_parametro | 0 | 0 | 0 | ok (19 controles) |  |
| tratamiento_realizado | 0 | 0 | 0 | ok (38 controles) |  |
| turno | 0 | 0 | 0 | ok (59 controles) | columnas nuevas con su default: [created_at, updated_at] |
| turno_evento | 0 | 0 | 0 | ok (28 controles) |  |
| turno_serie | 0 | 0 | 0 | ok (28 controles) | columnas nuevas con su default: [created_at, updated_at] |
| turno_serie_omision | 0 | 0 | 0 | ok (11 controles) | columnas nuevas con su default: [created_at] |

## Avisos

- Claves foraneas verificadas: 325.
