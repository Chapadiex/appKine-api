package com.akine.assistant.infrastructure;

import com.akine.assistant.domain.AccionPropuesta;
import com.akine.assistant.domain.port.AccionesPropuestas;
import com.akine.assistant.spi.TipoDeAccion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * {@code asistente_accion} por JDBC. Sin entidad JPA a proposito: cada cambio de estado es un UPDATE
 * condicionado que tiene que quedar commiteado antes de llamar al servicio que graba, y con
 * {@code JdbcTemplate} fuera de transaccion cada sentencia se commitea sola.
 */
@Repository
class JdbcAccionesPropuestas implements AccionesPropuestas {

	private static final TypeReference<Map<String, Long>> IDS = new TypeReference<>() {
	};

	private final JdbcTemplate jdbc;
	private final JsonMapper json = JsonMapper.builder().build();

	JdbcAccionesPropuestas(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public long guardar(AccionPropuesta nueva) {
		Long id = jdbc.queryForObject("""
				INSERT INTO asistente_accion (organization_id, consultorio_id, cuenta_id, tipo, estado, referencias,
				                              creada_en, vence_en)
				VALUES (?, ?, ?, ?, 'PROPUESTA', ?::jsonb, ?, ?)
				RETURNING id""", Long.class, nueva.organizationId(), nueva.consultorioId(), nueva.cuentaId(),
				nueva.tipo().name(), json.writeValueAsString(nueva.referencias()), Timestamp.from(nueva.creadaEn()),
				Timestamp.from(nueva.venceEn()));
		return id == null ? 0 : id;
	}

	@Override
	public Optional<AccionPropuesta> buscar(long id, long organizationId, long consultorioId, long cuentaId) {
		return jdbc.query("""
				SELECT id, organization_id, consultorio_id, cuenta_id, tipo, estado, referencias::text AS referencias,
				       resultado::text AS resultado, creada_en, vence_en
				FROM asistente_accion
				WHERE id = ? AND organization_id = ? AND consultorio_id = ? AND cuenta_id = ?""",
				(rs, n) -> new AccionPropuesta(rs.getLong("id"), rs.getLong("organization_id"),
						rs.getLong("consultorio_id"), rs.getLong("cuenta_id"), TipoDeAccion.valueOf(rs.getString("tipo")),
						AccionPropuesta.Estado.valueOf(rs.getString("estado")),
						json.readValue(rs.getString("referencias"), IDS), rs.getString("resultado"),
						rs.getTimestamp("creada_en").toInstant(), rs.getTimestamp("vence_en").toInstant()),
				id, organizationId, consultorioId, cuentaId).stream().findFirst();
	}

	@Override
	public boolean tomar(long id, Instant ahora) {
		return jdbc.update("""
				UPDATE asistente_accion SET estado = 'EN_CURSO'
				WHERE id = ? AND estado = 'PROPUESTA' AND vence_en > ?""", id, Timestamp.from(ahora)) == 1;
	}

	@Override
	public void soltar(long id) {
		jdbc.update("UPDATE asistente_accion SET estado = 'PROPUESTA' WHERE id = ? AND estado = 'EN_CURSO'", id);
	}

	@Override
	public void confirmar(long id, String resultadoJson, Instant ahora) {
		jdbc.update("""
				UPDATE asistente_accion SET estado = 'CONFIRMADA', resultado = ?::jsonb, resuelta_en = ?
				WHERE id = ? AND estado = 'EN_CURSO'""", resultadoJson, Timestamp.from(ahora), id);
	}

	@Override
	public boolean descartar(long id, Instant ahora) {
		return jdbc.update("""
				UPDATE asistente_accion SET estado = 'DESCARTADA', resuelta_en = ?
				WHERE id = ? AND estado = 'PROPUESTA'""", Timestamp.from(ahora), id) == 1;
	}
}
