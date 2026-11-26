package com.akine.organization.infrastructure;

import com.akine.organization.domain.ConsentimientoIa;
import com.akine.organization.domain.port.ConsentimientoIaRepositoryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/** Acceso a {@code organization_ia_consentimiento}, propiedad del modulo {@code organization}. */
@Repository
class JdbcConsentimientoIaRepository implements ConsentimientoIaRepositoryPort {

	private final JdbcClient jdbc;

	JdbcConsentimientoIaRepository(JdbcTemplate jdbcTemplate) {
		this.jdbc = JdbcClient.create(jdbcTemplate);
	}

	@Override
	public Optional<ConsentimientoIa> buscar(long organizationId, String version) {
		return jdbc.sql("""
						SELECT organization_id, version, account_id, nombre, aceptado_at
						FROM organization_ia_consentimiento
						WHERE organization_id = :org AND version = :version""")
				.param("org", organizationId)
				.param("version", version)
				.query((rs, i) -> new ConsentimientoIa(
						rs.getLong("organization_id"), rs.getString("version"), rs.getLong("account_id"),
						rs.getString("nombre"), rs.getTimestamp("aceptado_at").toInstant()))
				.optional();
	}

	@Override
	public boolean insertarSiNoExiste(long organizationId, String version, long accountId, String nombre,
			Instant aceptadoAt) {
		return jdbc.sql("""
						INSERT INTO organization_ia_consentimiento
						    (organization_id, version, account_id, nombre, aceptado_at)
						VALUES (:org, :version, :account, :nombre, :at)
						ON CONFLICT (organization_id, version) DO NOTHING""")
				.param("org", organizationId)
				.param("version", version)
				.param("account", accountId)
				.param("nombre", nombre)
				.param("at", Timestamp.from(aceptadoAt))
				.update() == 1;
	}
}
