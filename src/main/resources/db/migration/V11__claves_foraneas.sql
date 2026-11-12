-- =====================================================================================
-- AKINE — claves foraneas de todos los modulos
--
-- Baseline PostgreSQL consolidado (11/11/2026). Reemplaza las 145 migraciones de MySQL, que quedan
-- como archivo historico en ops/migracion-mysql/mysql-historico. Generado por
-- ops/migracion-mysql/generar-baseline.mjs desde el esquema final de MySQL y revisado a mano.
--
-- Convenciones que vienen de MySQL y se conservan:
--   * Textos con COLLATE akine_ci: comparacion sin mayusculas ni tildes, como utf8mb4_0900_ai_ci.
--   * Instantes en timestamptz (UTC); fechas en date; horas de agenda en time.
--   * Las claves *_key generadas sostienen los UNIQUE de la baja logica (centinela 1970-01-01).
--   * Las claves foraneas se crean todas juntas en V11, despues de todas las tablas.
-- =====================================================================================

-- organization
ALTER TABLE account_active_context ADD CONSTRAINT fk_active_context_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE account_active_context ADD CONSTRAINT fk_active_context_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE consultorio ADD CONSTRAINT fk_consultorio_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE consultorio_alta ADD CONSTRAINT fk_consultorio_alta_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE consultorio_alta ADD CONSTRAINT fk_consultorio_alta_org FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE membership ADD CONSTRAINT fk_membership_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE membership ADD CONSTRAINT fk_membership_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE membership_grant ADD CONSTRAINT fk_membership_grant_membership FOREIGN KEY (membership_id) REFERENCES membership (id);
ALTER TABLE membership_grant ADD CONSTRAINT fk_membership_grant_org FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE organization_onboarding ADD CONSTRAINT fk_onboarding_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE organization_onboarding ADD CONSTRAINT fk_onboarding_membership FOREIGN KEY (membership_id) REFERENCES membership (id);
ALTER TABLE organization_onboarding ADD CONSTRAINT fk_onboarding_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE plan_feature ADD CONSTRAINT fk_plan_feature_plan FOREIGN KEY (plan_id) REFERENCES plan (id);
ALTER TABLE plan_limit ADD CONSTRAINT fk_plan_limit_plan FOREIGN KEY (plan_id) REFERENCES plan (id);
ALTER TABLE subscription ADD CONSTRAINT fk_subscription_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE subscription ADD CONSTRAINT fk_subscription_plan FOREIGN KEY (plan_id) REFERENCES plan (id);
ALTER TABLE subscription_transition ADD CONSTRAINT fk_subs_transition_subscription FOREIGN KEY (subscription_id) REFERENCES subscription (id);
ALTER TABLE support_access ADD CONSTRAINT fk_support_access_org FOREIGN KEY (organization_id) REFERENCES organization (id);

-- identity
ALTER TABLE aceptacion_terminos ADD CONSTRAINT fk_aceptacion_terminos_cuenta FOREIGN KEY (cuenta_id) REFERENCES cuenta (id);
ALTER TABLE colaborador_invitacion ADD CONSTRAINT fk_colaborador_invitacion_aceptada_por FOREIGN KEY (aceptada_por_account_id) REFERENCES cuenta (id);
ALTER TABLE colaborador_invitacion ADD CONSTRAINT fk_colaborador_invitacion_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE colaborador_invitacion ADD CONSTRAINT fk_colaborador_invitacion_invitada_por FOREIGN KEY (invitada_por_account_id) REFERENCES cuenta (id);
ALTER TABLE colaborador_invitacion ADD CONSTRAINT fk_colaborador_invitacion_membership FOREIGN KEY (membership_id) REFERENCES membership (id);
ALTER TABLE colaborador_invitacion ADD CONSTRAINT fk_colaborador_invitacion_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE onboarding_registro ADD CONSTRAINT fk_onboarding_registro_cuenta FOREIGN KEY (cuenta_id) REFERENCES cuenta (id);
ALTER TABLE refresh_token ADD CONSTRAINT fk_refresh_token_cuenta FOREIGN KEY (cuenta_id) REFERENCES cuenta (id);
ALTER TABLE token_verificacion ADD CONSTRAINT fk_token_verificacion_cuenta FOREIGN KEY (cuenta_id) REFERENCES cuenta (id);

-- resource
ALTER TABLE agenda_profesional ADD CONSTRAINT fk_agenda_profesional_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE catalogo_solicitud ADD CONSTRAINT fk_catalogo_solicitud_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE catalogo_solicitud ADD CONSTRAINT fk_catalogo_solicitud_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE consultorio_calendario ADD CONSTRAINT fk_consultorio_calendario_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE consultorio_calendario ADD CONSTRAINT fk_consultorio_calendario_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE consultorio_horario ADD CONSTRAINT fk_consultorio_horario_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE consultorio_horario ADD CONSTRAINT fk_consultorio_horario_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE disponibilidad_excepcion ADD CONSTRAINT fk_disponibilidad_excepcion_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE disponibilidad_excepcion ADD CONSTRAINT fk_disponibilidad_excepcion_feriado FOREIGN KEY (feriado_id) REFERENCES feriado (id);
ALTER TABLE disponibilidad_excepcion ADD CONSTRAINT fk_disponibilidad_excepcion_membership FOREIGN KEY (membership_id) REFERENCES membership (id);
ALTER TABLE disponibilidad_excepcion ADD CONSTRAINT fk_disponibilidad_excepcion_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE espacio ADD CONSTRAINT fk_espacio_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE espacio ADD CONSTRAINT fk_espacio_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE especialidad ADD CONSTRAINT fk_especialidad_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE medicion_definicion ADD CONSTRAINT fk_medicion_definicion_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE nomenclador ADD CONSTRAINT fk_nomenclador_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE nomenclador_item ADD CONSTRAINT fk_nomenclador_item_nomenclador FOREIGN KEY (nomenclador_id) REFERENCES nomenclador (id);
ALTER TABLE nomenclador_item ADD CONSTRAINT fk_nomenclador_item_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE nomenclador_item ADD CONSTRAINT fk_nomenclador_item_practica FOREIGN KEY (practica_id) REFERENCES practica (id);
ALTER TABLE practica ADD CONSTRAINT fk_practica_especialidad FOREIGN KEY (especialidad_id) REFERENCES especialidad (id);
ALTER TABLE practica ADD CONSTRAINT fk_practica_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE profesional_disponibilidad ADD CONSTRAINT fk_profesional_disponibilidad_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE profesional_disponibilidad ADD CONSTRAINT fk_profesional_disponibilidad_membership FOREIGN KEY (membership_id) REFERENCES membership (id);
ALTER TABLE profesional_disponibilidad ADD CONSTRAINT fk_profesional_disponibilidad_organization FOREIGN KEY (organization_id) REFERENCES organization (id);

-- offering
ALTER TABLE oferta_duracion_profesional ADD CONSTRAINT fk_odp_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE oferta_duracion_profesional ADD CONSTRAINT fk_odp_membership FOREIGN KEY (membership_id) REFERENCES membership (id);
ALTER TABLE oferta_duracion_profesional ADD CONSTRAINT fk_odp_oferta FOREIGN KEY (oferta_id) REFERENCES oferta_servicio_consultorio (id);
ALTER TABLE oferta_duracion_profesional ADD CONSTRAINT fk_odp_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE oferta_espacio_habilitado ADD CONSTRAINT fk_oeh_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE oferta_espacio_habilitado ADD CONSTRAINT fk_oeh_espacio FOREIGN KEY (espacio_id) REFERENCES espacio (id);
ALTER TABLE oferta_espacio_habilitado ADD CONSTRAINT fk_oeh_oferta FOREIGN KEY (oferta_id) REFERENCES oferta_servicio_consultorio (id);
ALTER TABLE oferta_espacio_habilitado ADD CONSTRAINT fk_oeh_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE oferta_honorario_clase ADD CONSTRAINT fk_oferta_honorario_clase_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE oferta_honorario_clase ADD CONSTRAINT fk_oferta_honorario_clase_oferta FOREIGN KEY (oferta_id) REFERENCES oferta_servicio_consultorio (id);
ALTER TABLE oferta_honorario_clase ADD CONSTRAINT fk_oferta_honorario_clase_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE oferta_practica ADD CONSTRAINT fk_oferta_practica_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE oferta_practica ADD CONSTRAINT fk_oferta_practica_oferta FOREIGN KEY (oferta_id) REFERENCES oferta_servicio_consultorio (id);
ALTER TABLE oferta_practica ADD CONSTRAINT fk_oferta_practica_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE oferta_practica ADD CONSTRAINT fk_oferta_practica_practica FOREIGN KEY (practica_id) REFERENCES practica (id);
ALTER TABLE oferta_precio_particular ADD CONSTRAINT fk_oferta_precio_particular_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE oferta_precio_particular ADD CONSTRAINT fk_oferta_precio_particular_oferta FOREIGN KEY (oferta_id) REFERENCES oferta_servicio_consultorio (id);
ALTER TABLE oferta_precio_particular ADD CONSTRAINT fk_oferta_precio_particular_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE oferta_profesional_habilitado ADD CONSTRAINT fk_oph_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE oferta_profesional_habilitado ADD CONSTRAINT fk_oph_membership FOREIGN KEY (membership_id) REFERENCES membership (id);
ALTER TABLE oferta_profesional_habilitado ADD CONSTRAINT fk_oph_oferta FOREIGN KEY (oferta_id) REFERENCES oferta_servicio_consultorio (id);
ALTER TABLE oferta_profesional_habilitado ADD CONSTRAINT fk_oph_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE oferta_servicio_consultorio ADD CONSTRAINT fk_oferta_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE oferta_servicio_consultorio ADD CONSTRAINT fk_oferta_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE oferta_servicio_consultorio ADD CONSTRAINT fk_oferta_servicio FOREIGN KEY (servicio_id) REFERENCES servicio (id);
ALTER TABLE servicio ADD CONSTRAINT fk_servicio_organization FOREIGN KEY (organization_id) REFERENCES organization (id);

-- person
ALTER TABLE adjunto_administrativo ADD CONSTRAINT fk_adjunto_persona FOREIGN KEY (persona_id) REFERENCES persona (id);
ALTER TABLE autorizacion ADD CONSTRAINT fk_autorizacion_adjunto FOREIGN KEY (adjunto_id) REFERENCES adjunto_administrativo (id);
ALTER TABLE autorizacion ADD CONSTRAINT fk_autorizacion_cobertura FOREIGN KEY (cobertura_id) REFERENCES cobertura_paciente (id);
ALTER TABLE autorizacion ADD CONSTRAINT fk_autorizacion_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE autorizacion ADD CONSTRAINT fk_autorizacion_convenio FOREIGN KEY (convenio_id) REFERENCES convenio (id);
ALTER TABLE autorizacion ADD CONSTRAINT fk_autorizacion_orden FOREIGN KEY (orden_medica_id) REFERENCES orden_medica (id);
ALTER TABLE autorizacion ADD CONSTRAINT fk_autorizacion_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE autorizacion ADD CONSTRAINT fk_autorizacion_persona FOREIGN KEY (persona_id) REFERENCES persona (id);
ALTER TABLE autorizacion ADD CONSTRAINT fk_autorizacion_practica FOREIGN KEY (practica_id) REFERENCES practica (id);
ALTER TABLE autorizacion_alerta ADD CONSTRAINT fk_autorizacion_alerta_autorizacion FOREIGN KEY (autorizacion_id) REFERENCES autorizacion (id);
ALTER TABLE autorizacion_alerta ADD CONSTRAINT fk_autorizacion_alerta_movimiento FOREIGN KEY (movimiento_id) REFERENCES autorizacion_movimiento (id);
ALTER TABLE autorizacion_alerta ADD CONSTRAINT fk_autorizacion_alerta_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE autorizacion_alerta ADD CONSTRAINT fk_autorizacion_alerta_persona FOREIGN KEY (persona_id) REFERENCES persona (id);
ALTER TABLE autorizacion_evento ADD CONSTRAINT fk_autorizacion_evento_autorizacion FOREIGN KEY (autorizacion_id) REFERENCES autorizacion (id);
ALTER TABLE autorizacion_evento ADD CONSTRAINT fk_autorizacion_evento_movimiento FOREIGN KEY (movimiento_id) REFERENCES autorizacion_movimiento (id);
ALTER TABLE autorizacion_evento ADD CONSTRAINT fk_autorizacion_evento_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE autorizacion_evento ADD CONSTRAINT fk_autorizacion_evento_persona FOREIGN KEY (persona_id) REFERENCES persona (id);
ALTER TABLE autorizacion_movimiento ADD CONSTRAINT fk_movimiento_autorizacion FOREIGN KEY (autorizacion_id) REFERENCES autorizacion (id);
ALTER TABLE autorizacion_movimiento ADD CONSTRAINT fk_movimiento_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE autorizacion_movimiento ADD CONSTRAINT fk_movimiento_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE autorizacion_movimiento ADD CONSTRAINT fk_movimiento_origen FOREIGN KEY (movimiento_origen_id) REFERENCES autorizacion_movimiento (id);
ALTER TABLE autorizacion_movimiento ADD CONSTRAINT fk_movimiento_persona FOREIGN KEY (persona_id) REFERENCES persona (id);
ALTER TABLE autorizacion_persona_lock ADD CONSTRAINT fk_autorizacion_lock_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE autorizacion_persona_lock ADD CONSTRAINT fk_autorizacion_lock_persona FOREIGN KEY (persona_id) REFERENCES persona (id);
ALTER TABLE cobertura_paciente ADD CONSTRAINT fk_cobertura_financiador FOREIGN KEY (financiador_id) REFERENCES financiador (id);
ALTER TABLE cobertura_paciente ADD CONSTRAINT fk_cobertura_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE cobertura_paciente ADD CONSTRAINT fk_cobertura_persona FOREIGN KEY (persona_id) REFERENCES persona (id);
ALTER TABLE cobertura_paciente ADD CONSTRAINT fk_cobertura_plan FOREIGN KEY (plan_id) REFERENCES plan_cobertura (id);
ALTER TABLE cobertura_persona_lock ADD CONSTRAINT fk_cobertura_lock_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE cobertura_persona_lock ADD CONSTRAINT fk_cobertura_lock_persona FOREIGN KEY (persona_id) REFERENCES persona (id);
ALTER TABLE orden_medica ADD CONSTRAINT fk_orden_adjunto FOREIGN KEY (adjunto_id) REFERENCES adjunto_administrativo (id);
ALTER TABLE orden_medica ADD CONSTRAINT fk_orden_cobertura FOREIGN KEY (cobertura_id) REFERENCES cobertura_paciente (id);
ALTER TABLE orden_medica ADD CONSTRAINT fk_orden_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE orden_medica ADD CONSTRAINT fk_orden_persona FOREIGN KEY (persona_id) REFERENCES persona (id);
ALTER TABLE perfil_paciente ADD CONSTRAINT fk_perfil_paciente_persona FOREIGN KEY (persona_id) REFERENCES persona (id);

-- contracting
ALTER TABLE convenio ADD CONSTRAINT fk_convenio_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE convenio ADD CONSTRAINT fk_convenio_financiador FOREIGN KEY (financiador_id) REFERENCES financiador (id);
ALTER TABLE convenio ADD CONSTRAINT fk_convenio_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE convenio ADD CONSTRAINT fk_convenio_plan FOREIGN KEY (plan_id) REFERENCES plan_cobertura (id);
ALTER TABLE convenio_arancel ADD CONSTRAINT fk_arancel_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE convenio_arancel ADD CONSTRAINT fk_arancel_convenio FOREIGN KEY (convenio_id) REFERENCES convenio (id);
ALTER TABLE convenio_arancel ADD CONSTRAINT fk_arancel_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE convenio_arancel ADD CONSTRAINT fk_arancel_practica FOREIGN KEY (practica_id) REFERENCES practica (id);
ALTER TABLE convenio_arancel ADD CONSTRAINT fk_convenio_arancel_oferta FOREIGN KEY (oferta_id) REFERENCES oferta_servicio_consultorio (id);
ALTER TABLE convenio_lock ADD CONSTRAINT fk_convenio_lock_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE convenio_lock ADD CONSTRAINT fk_convenio_lock_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE financiador ADD CONSTRAINT fk_financiador_catalogo_origen FOREIGN KEY (catalogo_id) REFERENCES financiador_catalogo (id);
ALTER TABLE financiador ADD CONSTRAINT fk_financiador_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE plan_catalogo ADD CONSTRAINT fk_plan_catalogo_financiador_catalogo FOREIGN KEY (financiador_catalogo_id) REFERENCES financiador_catalogo (id);
ALTER TABLE plan_cobertura ADD CONSTRAINT fk_plan_cobertura_catalogo_origen FOREIGN KEY (catalogo_id) REFERENCES plan_catalogo (id);
ALTER TABLE plan_cobertura ADD CONSTRAINT fk_plan_financiador FOREIGN KEY (financiador_id) REFERENCES financiador (id);
ALTER TABLE plan_cobertura ADD CONSTRAINT fk_plan_organization FOREIGN KEY (organization_id) REFERENCES organization (id);

-- scheduling
ALTER TABLE agenda_sede ADD CONSTRAINT fk_agenda_sede_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE agenda_sede ADD CONSTRAINT fk_agenda_sede_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE lista_espera_turno ADD CONSTRAINT fk_lista_espera_turno_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE lista_espera_turno ADD CONSTRAINT fk_lista_espera_turno_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE lista_espera_turno ADD CONSTRAINT fk_lista_espera_turno_turno FOREIGN KEY (turno_id) REFERENCES turno (id);
ALTER TABLE recepcion ADD CONSTRAINT fk_recepcion_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE recepcion ADD CONSTRAINT fk_recepcion_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE recepcion ADD CONSTRAINT fk_recepcion_turno FOREIGN KEY (turno_id) REFERENCES turno (id);
ALTER TABLE recepcion_evento ADD CONSTRAINT fk_recepcion_evento_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE recepcion_evento ADD CONSTRAINT fk_recepcion_evento_recepcion FOREIGN KEY (recepcion_id) REFERENCES recepcion (id);
ALTER TABLE turno ADD CONSTRAINT fk_turno_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE turno ADD CONSTRAINT fk_turno_espacio FOREIGN KEY (espacio_id) REFERENCES espacio (id);
ALTER TABLE turno ADD CONSTRAINT fk_turno_oferta FOREIGN KEY (oferta_id) REFERENCES oferta_servicio_consultorio (id);
ALTER TABLE turno ADD CONSTRAINT fk_turno_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE turno ADD CONSTRAINT fk_turno_persona FOREIGN KEY (persona_id) REFERENCES persona (id);
ALTER TABLE turno ADD CONSTRAINT fk_turno_turno_origen FOREIGN KEY (turno_origen_id) REFERENCES turno (id);
ALTER TABLE turno ADD CONSTRAINT fk_turno_turno_serie FOREIGN KEY (serie_id) REFERENCES turno_serie (id);
ALTER TABLE turno_evento ADD CONSTRAINT fk_turno_evento_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE turno_evento ADD CONSTRAINT fk_turno_evento_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE turno_evento ADD CONSTRAINT fk_turno_evento_turno FOREIGN KEY (turno_id) REFERENCES turno (id);
ALTER TABLE turno_serie ADD CONSTRAINT fk_turno_serie_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE turno_serie ADD CONSTRAINT fk_turno_serie_oferta FOREIGN KEY (oferta_id) REFERENCES oferta_servicio_consultorio (id);
ALTER TABLE turno_serie ADD CONSTRAINT fk_turno_serie_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE turno_serie ADD CONSTRAINT fk_turno_serie_persona FOREIGN KEY (persona_id) REFERENCES persona (id);
ALTER TABLE turno_serie_omision ADD CONSTRAINT fk_turno_serie_omision_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE turno_serie_omision ADD CONSTRAINT fk_turno_serie_omision_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE turno_serie_omision ADD CONSTRAINT fk_turno_serie_omision_serie FOREIGN KEY (serie_id) REFERENCES turno_serie (id);

-- clinical
ALTER TABLE adjunto_clinico ADD CONSTRAINT fk_adjunto_clinico_entrada FOREIGN KEY (entrada_clinica_id) REFERENCES entrada_clinica (id);
ALTER TABLE adjunto_clinico ADD CONSTRAINT fk_adjunto_clinico_historia FOREIGN KEY (historia_clinica_id) REFERENCES historia_clinica (id);
ALTER TABLE adjunto_clinico ADD CONSTRAINT fk_adjunto_clinico_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE caso_clinico ADD CONSTRAINT fk_caso_clinico_historia FOREIGN KEY (historia_clinica_id) REFERENCES historia_clinica (id);
ALTER TABLE caso_clinico ADD CONSTRAINT fk_caso_clinico_oferta FOREIGN KEY (oferta_id) REFERENCES oferta_servicio_consultorio (id);
ALTER TABLE caso_clinico ADD CONSTRAINT fk_caso_clinico_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE caso_evento ADD CONSTRAINT fk_caso_evento_caso FOREIGN KEY (caso_id) REFERENCES caso_clinico (id);
ALTER TABLE caso_evento ADD CONSTRAINT fk_caso_evento_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE caso_numerador ADD CONSTRAINT fk_caso_numerador_historia FOREIGN KEY (historia_clinica_id) REFERENCES historia_clinica (id);
ALTER TABLE caso_numerador ADD CONSTRAINT fk_caso_numerador_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE caso_profesional ADD CONSTRAINT fk_caso_profesional_caso FOREIGN KEY (caso_id) REFERENCES caso_clinico (id);
ALTER TABLE caso_profesional ADD CONSTRAINT fk_caso_profesional_membership FOREIGN KEY (profesional_membership_id) REFERENCES membership (id);
ALTER TABLE caso_profesional ADD CONSTRAINT fk_caso_profesional_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE caso_sesion_numerador ADD CONSTRAINT fk_caso_sesion_numerador_caso FOREIGN KEY (caso_id) REFERENCES caso_clinico (id);
ALTER TABLE caso_sesion_numerador ADD CONSTRAINT fk_caso_sesion_numerador_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE derivacion_clinica ADD CONSTRAINT fk_derivacion_caso FOREIGN KEY (caso_clinico_id) REFERENCES caso_clinico (id);
ALTER TABLE derivacion_clinica ADD CONSTRAINT fk_derivacion_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE derivacion_clinica ADD CONSTRAINT fk_derivacion_historia FOREIGN KEY (historia_clinica_id) REFERENCES historia_clinica (id);
ALTER TABLE derivacion_clinica ADD CONSTRAINT fk_derivacion_oferta FOREIGN KEY (oferta_id) REFERENCES oferta_servicio_consultorio (id);
ALTER TABLE derivacion_clinica ADD CONSTRAINT fk_derivacion_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE derivacion_clinica ADD CONSTRAINT fk_derivacion_persona FOREIGN KEY (persona_id) REFERENCES persona (id);
ALTER TABLE derivacion_clinica ADD CONSTRAINT fk_derivacion_plan FOREIGN KEY (plan_tratamiento_id) REFERENCES plan_tratamiento (id);
ALTER TABLE entrada_clinica ADD CONSTRAINT fk_entrada_clinica_historia FOREIGN KEY (historia_clinica_id) REFERENCES historia_clinica (id);
ALTER TABLE entrada_clinica ADD CONSTRAINT fk_entrada_clinica_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE entrada_clinica_version ADD CONSTRAINT fk_entrada_version_entrada FOREIGN KEY (entrada_clinica_id) REFERENCES entrada_clinica (id);
ALTER TABLE entrada_clinica_version ADD CONSTRAINT fk_entrada_version_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE historia_clinica ADD CONSTRAINT fk_historia_clinica_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE historia_clinica ADD CONSTRAINT fk_historia_clinica_persona FOREIGN KEY (persona_id) REFERENCES persona (id);
ALTER TABLE historia_clinica_antecedente ADD CONSTRAINT fk_hc_antecedente_historia FOREIGN KEY (historia_clinica_id) REFERENCES historia_clinica (id);
ALTER TABLE historia_clinica_antecedente ADD CONSTRAINT fk_hc_antecedente_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE plan_evento ADD CONSTRAINT fk_plan_evento_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE plan_evento ADD CONSTRAINT fk_plan_evento_plan FOREIGN KEY (plan_tratamiento_id) REFERENCES plan_tratamiento (id);
ALTER TABLE plan_item ADD CONSTRAINT fk_plan_item_autorizacion FOREIGN KEY (autorizacion_id) REFERENCES autorizacion (id);
ALTER TABLE plan_item ADD CONSTRAINT fk_plan_item_oferta FOREIGN KEY (oferta_id) REFERENCES oferta_servicio_consultorio (id);
ALTER TABLE plan_item ADD CONSTRAINT fk_plan_item_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE plan_item ADD CONSTRAINT fk_plan_item_version FOREIGN KEY (plan_tratamiento_version_id) REFERENCES plan_tratamiento_version (id);
ALTER TABLE plan_numerador ADD CONSTRAINT fk_plan_numerador_caso FOREIGN KEY (caso_clinico_id) REFERENCES caso_clinico (id);
ALTER TABLE plan_numerador ADD CONSTRAINT fk_plan_numerador_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE plan_tratamiento ADD CONSTRAINT fk_plan_tratamiento_caso FOREIGN KEY (caso_clinico_id) REFERENCES caso_clinico (id);
ALTER TABLE plan_tratamiento ADD CONSTRAINT fk_plan_tratamiento_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE plan_tratamiento_version ADD CONSTRAINT fk_plan_version_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE plan_tratamiento_version ADD CONSTRAINT fk_plan_version_plan FOREIGN KEY (plan_tratamiento_id) REFERENCES plan_tratamiento (id);

-- encounter
ALTER TABLE politica_de_enmienda ADD CONSTRAINT fk_politica_de_enmienda_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE sesion ADD CONSTRAINT fk_sesion_caso FOREIGN KEY (caso_id) REFERENCES caso_clinico (id);
ALTER TABLE sesion ADD CONSTRAINT fk_sesion_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE sesion ADD CONSTRAINT fk_sesion_derivacion_clinica FOREIGN KEY (derivacion_id) REFERENCES derivacion_clinica (id);
ALTER TABLE sesion ADD CONSTRAINT fk_sesion_historia_clinica FOREIGN KEY (historia_clinica_id) REFERENCES historia_clinica (id);
ALTER TABLE sesion ADD CONSTRAINT fk_sesion_oferta FOREIGN KEY (oferta_id) REFERENCES oferta_servicio_consultorio (id);
ALTER TABLE sesion ADD CONSTRAINT fk_sesion_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE sesion ADD CONSTRAINT fk_sesion_turno FOREIGN KEY (turno_id) REFERENCES turno (id);
ALTER TABLE sesion_medicion ADD CONSTRAINT fk_sesion_medicion_definicion FOREIGN KEY (definicion_id) REFERENCES medicion_definicion (id);
ALTER TABLE sesion_medicion ADD CONSTRAINT fk_sesion_medicion_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE sesion_medicion ADD CONSTRAINT fk_sesion_medicion_sesion FOREIGN KEY (sesion_id) REFERENCES sesion (id);
ALTER TABLE sesion_numerador ADD CONSTRAINT fk_sesion_numerador_historia FOREIGN KEY (historia_clinica_id) REFERENCES historia_clinica (id);
ALTER TABLE sesion_numerador ADD CONSTRAINT fk_sesion_numerador_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE sesion_reemplazo_profesional ADD CONSTRAINT fk_sesion_reemplazo_profesional_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE sesion_reemplazo_profesional ADD CONSTRAINT fk_sesion_reemplazo_profesional_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE sesion_reemplazo_profesional ADD CONSTRAINT fk_sesion_reemplazo_profesional_sesion FOREIGN KEY (sesion_id) REFERENCES sesion (id);
ALTER TABLE sesion_version ADD CONSTRAINT fk_sesion_version_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE sesion_version ADD CONSTRAINT fk_sesion_version_sesion FOREIGN KEY (sesion_id) REFERENCES sesion (id);
ALTER TABLE sesion_version_medicion ADD CONSTRAINT fk_svm_definicion FOREIGN KEY (definicion_id) REFERENCES medicion_definicion (id);
ALTER TABLE sesion_version_medicion ADD CONSTRAINT fk_svm_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE sesion_version_medicion ADD CONSTRAINT fk_svm_sesion_version FOREIGN KEY (sesion_version_id) REFERENCES sesion_version (id);
ALTER TABLE sesion_version_tratamiento ADD CONSTRAINT fk_svt_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE sesion_version_tratamiento ADD CONSTRAINT fk_svt_sesion_version FOREIGN KEY (sesion_version_id) REFERENCES sesion_version (id);
ALTER TABLE sesion_version_tratamiento ADD CONSTRAINT fk_svt_tratamiento FOREIGN KEY (tratamiento_realizado_id) REFERENCES tratamiento_realizado (id);
ALTER TABLE sesion_version_tratamiento_parametro ADD CONSTRAINT fk_svtp_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE sesion_version_tratamiento_parametro ADD CONSTRAINT fk_svtp_tratamiento FOREIGN KEY (sesion_version_tratamiento_id) REFERENCES sesion_version_tratamiento (id);
ALTER TABLE tratamiento_parametro ADD CONSTRAINT fk_tratamiento_parametro_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE tratamiento_parametro ADD CONSTRAINT fk_tratamiento_parametro_tratamiento FOREIGN KEY (tratamiento_realizado_id) REFERENCES tratamiento_realizado (id);
ALTER TABLE tratamiento_realizado ADD CONSTRAINT fk_tratamiento_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE tratamiento_realizado ADD CONSTRAINT fk_tratamiento_espacio FOREIGN KEY (espacio_id) REFERENCES espacio (id);
ALTER TABLE tratamiento_realizado ADD CONSTRAINT fk_tratamiento_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE tratamiento_realizado ADD CONSTRAINT fk_tratamiento_practica FOREIGN KEY (practica_id) REFERENCES practica (id);
ALTER TABLE tratamiento_realizado ADD CONSTRAINT fk_tratamiento_sesion FOREIGN KEY (sesion_id) REFERENCES sesion (id);

-- billing
ALTER TABLE cobro ADD CONSTRAINT fk_cobro_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE cobro ADD CONSTRAINT fk_cobro_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE cobro ADD CONSTRAINT fk_cobro_persona FOREIGN KEY (persona_id) REFERENCES persona (id);
ALTER TABLE cobro_imputacion ADD CONSTRAINT fk_cobro_imputacion_cobro FOREIGN KEY (cobro_id) REFERENCES cobro (id);
ALTER TABLE cobro_imputacion ADD CONSTRAINT fk_cobro_imputacion_obligacion FOREIGN KEY (obligacion_id) REFERENCES obligacion (id);
ALTER TABLE cobro_imputacion ADD CONSTRAINT fk_cobro_imputacion_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE cobro_medio ADD CONSTRAINT fk_cobro_medio_cobro FOREIGN KEY (cobro_id) REFERENCES cobro (id);
ALTER TABLE cobro_medio ADD CONSTRAINT fk_cobro_medio_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE cobro_reintegro ADD CONSTRAINT fk_cobro_reintegro_cobro FOREIGN KEY (cobro_id) REFERENCES cobro (id);
ALTER TABLE cobro_reintegro ADD CONSTRAINT fk_cobro_reintegro_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE cobro_reintegro ADD CONSTRAINT fk_cobro_reintegro_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE cobro_reintegro ADD CONSTRAINT fk_cobro_reintegro_persona FOREIGN KEY (persona_id) REFERENCES persona (id);
ALTER TABLE comprobante_numerador ADD CONSTRAINT fk_comprobante_numerador_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE comprobante_numerador ADD CONSTRAINT fk_comprobante_numerador_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE egreso ADD CONSTRAINT fk_egreso_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE egreso ADD CONSTRAINT fk_egreso_membership FOREIGN KEY (beneficiario_membership_id) REFERENCES membership (id);
ALTER TABLE egreso ADD CONSTRAINT fk_egreso_oferta FOREIGN KEY (oferta_id) REFERENCES oferta_servicio_consultorio (id);
ALTER TABLE egreso ADD CONSTRAINT fk_egreso_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE financiador_pago ADD CONSTRAINT fk_financiador_pago_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE financiador_pago ADD CONSTRAINT fk_financiador_pago_financiador FOREIGN KEY (financiador_id) REFERENCES financiador (id);
ALTER TABLE financiador_pago ADD CONSTRAINT fk_financiador_pago_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE financiador_pago ADD CONSTRAINT fk_financiador_pago_presentacion FOREIGN KEY (presentacion_id) REFERENCES presentacion (id);
ALTER TABLE jornada_caja ADD CONSTRAINT fk_jornada_caja_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE jornada_caja ADD CONSTRAINT fk_jornada_caja_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE liquidacion_honorario ADD CONSTRAINT fk_liquidacion_honorario_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE liquidacion_honorario ADD CONSTRAINT fk_liquidacion_honorario_egreso FOREIGN KEY (egreso_id) REFERENCES egreso (id);
ALTER TABLE liquidacion_honorario ADD CONSTRAINT fk_liquidacion_honorario_membership FOREIGN KEY (colaborador_membership_id) REFERENCES membership (id);
ALTER TABLE liquidacion_honorario ADD CONSTRAINT fk_liquidacion_honorario_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE liquidacion_honorario_clase ADD CONSTRAINT fk_liquidacion_honorario_clase_clase FOREIGN KEY (clase_id) REFERENCES clase_programada (id);
ALTER TABLE liquidacion_honorario_clase ADD CONSTRAINT fk_liquidacion_honorario_clase_liquidacion FOREIGN KEY (liquidacion_id) REFERENCES liquidacion_honorario (id);
ALTER TABLE liquidacion_honorario_clase ADD CONSTRAINT fk_liquidacion_honorario_clase_oferta FOREIGN KEY (oferta_id) REFERENCES oferta_servicio_consultorio (id);
ALTER TABLE liquidacion_honorario_clase ADD CONSTRAINT fk_liquidacion_honorario_clase_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE movimiento_caja ADD CONSTRAINT fk_movimiento_caja_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE movimiento_caja ADD CONSTRAINT fk_movimiento_caja_jornada FOREIGN KEY (jornada_caja_id) REFERENCES jornada_caja (id);
ALTER TABLE movimiento_caja ADD CONSTRAINT fk_movimiento_caja_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE movimiento_caja ADD CONSTRAINT fk_movimiento_caja_origen FOREIGN KEY (movimiento_origen_id) REFERENCES movimiento_caja (id);
ALTER TABLE obligacion ADD CONSTRAINT fk_obligacion_asistencia FOREIGN KEY (asistencia_id) REFERENCES asistencia_actividad (id);
ALTER TABLE obligacion ADD CONSTRAINT fk_obligacion_clase FOREIGN KEY (clase_id) REFERENCES clase_programada (id);
ALTER TABLE obligacion ADD CONSTRAINT fk_obligacion_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE obligacion ADD CONSTRAINT fk_obligacion_financiador FOREIGN KEY (financiador_id) REFERENCES financiador (id);
ALTER TABLE obligacion ADD CONSTRAINT fk_obligacion_oferta FOREIGN KEY (oferta_id) REFERENCES oferta_servicio_consultorio (id);
ALTER TABLE obligacion ADD CONSTRAINT fk_obligacion_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE obligacion ADD CONSTRAINT fk_obligacion_pase FOREIGN KEY (pase_id) REFERENCES pase_servicio (id);
ALTER TABLE obligacion ADD CONSTRAINT fk_obligacion_persona FOREIGN KEY (persona_id) REFERENCES persona (id);
ALTER TABLE obligacion ADD CONSTRAINT fk_obligacion_sesion FOREIGN KEY (sesion_id) REFERENCES sesion (id);
ALTER TABLE pago_egreso ADD CONSTRAINT fk_pago_egreso_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE pago_egreso ADD CONSTRAINT fk_pago_egreso_egreso FOREIGN KEY (egreso_id) REFERENCES egreso (id);
ALTER TABLE pago_egreso ADD CONSTRAINT fk_pago_egreso_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE presentacion ADD CONSTRAINT fk_presentacion_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE presentacion ADD CONSTRAINT fk_presentacion_financiador FOREIGN KEY (financiador_id) REFERENCES financiador (id);
ALTER TABLE presentacion ADD CONSTRAINT fk_presentacion_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE presentacion_item ADD CONSTRAINT fk_presentacion_item_obligacion FOREIGN KEY (obligacion_id) REFERENCES obligacion (id);
ALTER TABLE presentacion_item ADD CONSTRAINT fk_presentacion_item_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE presentacion_item ADD CONSTRAINT fk_presentacion_item_presentacion FOREIGN KEY (presentacion_id) REFERENCES presentacion (id);
ALTER TABLE presentacion_numerador ADD CONSTRAINT fk_presentacion_numerador_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE presentacion_numerador ADD CONSTRAINT fk_presentacion_numerador_financiador FOREIGN KEY (financiador_id) REFERENCES financiador (id);
ALTER TABLE presentacion_numerador ADD CONSTRAINT fk_presentacion_numerador_organization FOREIGN KEY (organization_id) REFERENCES organization (id);

-- activity
ALTER TABLE asistencia_actividad ADD CONSTRAINT fk_asistencia_clase FOREIGN KEY (clase_id) REFERENCES clase_programada (id);
ALTER TABLE asistencia_actividad ADD CONSTRAINT fk_asistencia_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE asistencia_actividad ADD CONSTRAINT fk_asistencia_inscripcion FOREIGN KEY (inscripcion_id) REFERENCES inscripcion_clase (id);
ALTER TABLE asistencia_actividad ADD CONSTRAINT fk_asistencia_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE asistencia_actividad ADD CONSTRAINT fk_asistencia_persona FOREIGN KEY (persona_id) REFERENCES persona (id);
ALTER TABLE asistencia_evento ADD CONSTRAINT fk_asistencia_evento_asistencia FOREIGN KEY (asistencia_id) REFERENCES asistencia_actividad (id);
ALTER TABLE asistencia_evento ADD CONSTRAINT fk_asistencia_evento_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE clase_evento ADD CONSTRAINT fk_clase_evento_clase FOREIGN KEY (clase_id) REFERENCES clase_programada (id);
ALTER TABLE clase_evento ADD CONSTRAINT fk_clase_evento_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE clase_instructor ADD CONSTRAINT fk_clase_instructor_clase FOREIGN KEY (clase_id) REFERENCES clase_programada (id);
ALTER TABLE clase_instructor ADD CONSTRAINT fk_clase_instructor_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE clase_instructor ADD CONSTRAINT fk_clase_instructor_membership FOREIGN KEY (membership_id) REFERENCES membership (id);
ALTER TABLE clase_instructor ADD CONSTRAINT fk_clase_instructor_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE clase_programada ADD CONSTRAINT fk_clase_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE clase_programada ADD CONSTRAINT fk_clase_espacio FOREIGN KEY (espacio_id) REFERENCES espacio (id);
ALTER TABLE clase_programada ADD CONSTRAINT fk_clase_oferta FOREIGN KEY (oferta_id) REFERENCES oferta_servicio_consultorio (id);
ALTER TABLE clase_programada ADD CONSTRAINT fk_clase_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE inscripcion_clase ADD CONSTRAINT fk_inscripcion_clase FOREIGN KEY (clase_id) REFERENCES clase_programada (id);
ALTER TABLE inscripcion_clase ADD CONSTRAINT fk_inscripcion_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE inscripcion_clase ADD CONSTRAINT fk_inscripcion_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE inscripcion_clase ADD CONSTRAINT fk_inscripcion_persona FOREIGN KEY (persona_id) REFERENCES persona (id);
ALTER TABLE movimiento_pase ADD CONSTRAINT fk_movimiento_pase FOREIGN KEY (pase_id) REFERENCES pase_servicio (id);
ALTER TABLE movimiento_pase ADD CONSTRAINT fk_movimiento_pase_asistencia FOREIGN KEY (asistencia_id) REFERENCES asistencia_actividad (id);
ALTER TABLE movimiento_pase ADD CONSTRAINT fk_movimiento_pase_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE pase_servicio ADD CONSTRAINT fk_pase_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE pase_servicio ADD CONSTRAINT fk_pase_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE pase_servicio ADD CONSTRAINT fk_pase_persona FOREIGN KEY (persona_id) REFERENCES persona (id);
ALTER TABLE pase_servicio ADD CONSTRAINT fk_pase_producto FOREIGN KEY (producto_id) REFERENCES producto_servicio (id);
ALTER TABLE pase_servicio ADD CONSTRAINT fk_pase_servicio_renueva_pase FOREIGN KEY (renueva_pase_id) REFERENCES pase_servicio (id);
ALTER TABLE producto_servicio ADD CONSTRAINT fk_producto_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id);
ALTER TABLE producto_servicio ADD CONSTRAINT fk_producto_oferta FOREIGN KEY (oferta_id) REFERENCES oferta_servicio_consultorio (id);
ALTER TABLE producto_servicio ADD CONSTRAINT fk_producto_organization FOREIGN KEY (organization_id) REFERENCES organization (id);

-- knowledge
ALTER TABLE biblioteca_version ADD CONSTRAINT fk_biblioteca_version_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE libro ADD CONSTRAINT fk_libro_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
ALTER TABLE libro ADD CONSTRAINT fk_libro_reemplaza FOREIGN KEY (reemplaza_libro_id) REFERENCES libro (id);
ALTER TABLE libro_fragmento ADD CONSTRAINT fk_libro_fragmento_libro FOREIGN KEY (libro_id) REFERENCES libro (id) ON DELETE CASCADE;
ALTER TABLE libro_fragmento ADD CONSTRAINT fk_libro_fragmento_organization FOREIGN KEY (organization_id) REFERENCES organization (id);
