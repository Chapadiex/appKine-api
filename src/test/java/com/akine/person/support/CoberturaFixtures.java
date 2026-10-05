package com.akine.person.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Siembra por SQL de organizaciones, personas y coberturas para los IT de B-1 y B-2. Datos
 * sinteticos unicamente. Va por SQL y no por los servicios a proposito: el test fija el estado
 * de la base que el contrato describe, sin depender del codigo que se prueba.
 */
public final class CoberturaFixtures {

	private final JdbcTemplate jdbc;

	public CoberturaFixtures(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public long organizacion() {
		String slug = "afiliado-it-" + UUID.randomUUID().toString().substring(0, 12);
		jdbc.update("""
				INSERT INTO organization (name, slug, timezone, active, version,
				                          created_at, updated_at)
				VALUES (?, ?, 'America/Argentina/Cordoba', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "Centro Sintetico " + slug, slug);
		return jdbc.queryForObject("SELECT id FROM organization WHERE slug = ?", Long.class, slug);
	}

	/** Persona activa sin telefono. El documento es la clave, sin separadores. */
	public long persona(long org, String apellido, String nombre, String documento) {
		return persona(org, apellido, nombre, documento, null, true);
	}

	public long persona(
			long org, String apellido, String nombre, String documento, String telefonoClave,
			boolean activa) {

		jdbc.update("""
				INSERT INTO persona (organization_id, tipo_documento, numero_documento,
				                     documento_clave, apellido, nombre, apellido_clave,
				                     nombre_clave, telefono, telefono_clave, created_at, updated_at)
				VALUES (?, 'DNI', ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, documento, documento, apellido, nombre, apellido.toUpperCase(),
				nombre.toUpperCase(), telefonoClave, telefonoClave);
		long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		if (!activa) {
			jdbc.update("""
					UPDATE persona SET active = 0, deleted_at = UTC_TIMESTAMP(6),
					       deactivation_reason = 'baja sintetica'
					 WHERE id = ?
					""", id);
		}
		return id;
	}

	public void perfilPaciente(long org, long persona) {
		jdbc.update("""
				INSERT INTO perfil_paciente (organization_id, persona_id, activado_en, activado_por,
				                             active, version, created_at, updated_at)
				VALUES (?, ?, UTC_TIMESTAMP(6), 1, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, persona);
	}

	public long financiador(long org) {
		String codigo = "OS-" + UUID.randomUUID().toString().substring(0, 12);
		jdbc.update("""
				INSERT INTO financiador (organization_id, codigo, nombre, tipo, active, version,
				                         created_at, updated_at)
				VALUES (?, ?, ?, 'PREPAGA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, codigo, "Financiador " + codigo);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	public long plan(long org, long financiadorId) {
		String codigo = "P-" + UUID.randomUUID().toString().substring(0, 12);
		jdbc.update("""
				INSERT INTO plan_cobertura (organization_id, financiador_id, codigo, nombre,
				                            vigencia_desde, requiere_autorizacion,
				                            requiere_credencial, active, version,
				                            created_at, updated_at)
				VALUES (?, ?, ?, ?, '2020-01-01', 0, 0, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, financiadorId, codigo, "Plan " + codigo);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	/** Un par financiador+plan nuevo, para sembrar una cobertura de un plan que no se repite. */
	public Plan planNuevo(long org) {
		long financiador = financiador(org);
		return new Plan(financiador, plan(org, financiador));
	}

	public record Plan(long financiadorId, long planId) {
	}

	/** Cobertura FINANCIADA activa. {@code hasta}, {@code afiliado} y la credencial pueden ser null. */
	public long cobertura(
			long org, long persona, Plan plan, String afiliado, LocalDate desde, LocalDate hasta,
			boolean principal, LocalDate credencialHasta) {

		jdbc.update("""
				INSERT INTO cobertura_paciente (organization_id, persona_id, tipo,
				                                financiador_id, financiador_codigo,
				                                financiador_nombre, financiador_tipo,
				                                plan_id, plan_codigo, plan_nombre,
				                                requeria_autorizacion, requeria_credencial,
				                                referencia_capturada_el,
				                                numero_afiliado, credencial_vigencia_hasta,
				                                vigencia_desde, vigencia_hasta, principal,
				                                created_at, updated_at)
				VALUES (?, ?, 'FINANCIADA', ?, 'OS-SINT', 'Financiador Sintetico', 'PREPAGA',
				        ?, 'P-SINT', 'Plan Sintetico', 0, 0, '2026-01-01 00:00:00.000000',
				        ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, persona, plan.financiadorId(), plan.planId(), afiliado, credencialHasta,
				desde, hasta, principal ? 1 : 0);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	public long particular(
			long org, long persona, LocalDate desde, LocalDate hasta, boolean principal) {

		jdbc.update("""
				INSERT INTO cobertura_paciente (organization_id, persona_id, tipo, vigencia_desde,
				                                vigencia_hasta, principal, created_at, updated_at)
				VALUES (?, ?, 'PARTICULAR', ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, persona, desde, hasta, principal ? 1 : 0);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	/** Baja logica de una cobertura, con el motivo que la base exige. */
	public void darDeBaja(long coberturaId) {
		jdbc.update("""
				UPDATE cobertura_paciente
				   SET active = 0, deleted_at = UTC_TIMESTAMP(6), deactivation_reason = 'baja sintetica'
				 WHERE id = ?
				""", coberturaId);
	}
}
