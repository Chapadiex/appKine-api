package com.akine.assistant.infrastructure.telegram;

import com.akine.assistant.domain.VinculoDeTelegram;
import com.akine.assistant.domain.port.VinculosDeTelegram;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** {@code telegram_vinculo} por JDBC. */
@Repository
class JdbcVinculosDeTelegram implements VinculosDeTelegram {

	private static final String COLUMNAS = """
			organization_id, cuenta_id, consultorio_id, codigo, codigo_vence_en, telegram_usuario_id, telegram_nombre,
			vinculado_en""";

	private static final RowMapper<VinculoDeTelegram> FILA = (rs, n) -> new VinculoDeTelegram(
			rs.getLong("organization_id"), rs.getLong("cuenta_id"), rs.getLong("consultorio_id"),
			rs.getString("codigo"), instante(rs.getTimestamp("codigo_vence_en")),
			(Long) rs.getObject("telegram_usuario_id", Long.class), rs.getString("telegram_nombre"),
			instante(rs.getTimestamp("vinculado_en")));

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transaccion;

	JdbcVinculosDeTelegram(JdbcTemplate jdbc, TransactionTemplate transaccion) {
		this.jdbc = jdbc;
		this.transaccion = transaccion;
	}

	@Override
	public Optional<VinculoDeTelegram> deLaCuenta(long organizationId, long cuentaId) {
		return primera(jdbc.query("SELECT " + COLUMNAS + " FROM telegram_vinculo WHERE organization_id = ? AND cuenta_id = ?",
				FILA, organizationId, cuentaId));
	}

	@Override
	public Optional<VinculoDeTelegram> delUsuario(long organizationId, long telegramUsuarioId) {
		return primera(jdbc.query("SELECT " + COLUMNAS
				+ " FROM telegram_vinculo WHERE organization_id = ? AND telegram_usuario_id = ?",
				FILA, organizationId, telegramUsuarioId));
	}

	@Override
	public boolean nuevoCodigo(long organizationId, long cuentaId, long consultorioId, String codigo, Instant venceEn,
			Instant ahora) {
		Timestamp momento = Timestamp.from(ahora);
		try {
			// La sede del codigo pasa a ser la del vinculo recien al usarlo; sin vincular todavia, ya es esa.
			jdbc.update("""
					INSERT INTO telegram_vinculo (organization_id, cuenta_id, consultorio_id, codigo, codigo_vence_en,
					                              codigo_consultorio_id, creado_en, actualizado_en)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?)
					ON CONFLICT (organization_id, cuenta_id) DO UPDATE SET
					    codigo = EXCLUDED.codigo,
					    codigo_vence_en = EXCLUDED.codigo_vence_en,
					    codigo_consultorio_id = EXCLUDED.codigo_consultorio_id,
					    consultorio_id = CASE WHEN telegram_vinculo.telegram_usuario_id IS NULL
					                          THEN EXCLUDED.consultorio_id ELSE telegram_vinculo.consultorio_id END,
					    actualizado_en = EXCLUDED.actualizado_en""",
					organizationId, cuentaId, consultorioId, codigo, Timestamp.from(venceEn), consultorioId, momento,
					momento);
			return true;
		}
		catch (DuplicateKeyException codigoTomado) {
			return false;
		}
	}

	@Override
	public Optional<VinculoDeTelegram> usarCodigo(long organizationId, String codigo, long telegramUsuarioId,
			String telegramNombre, Instant ahora) {
		Timestamp momento = Timestamp.from(ahora);
		return transaccion.execute(estado -> {
			Optional<VinculoDeTelegram> fila = primera(jdbc.query("SELECT " + COLUMNAS
					+ " FROM telegram_vinculo WHERE organization_id = ? AND codigo = ? AND codigo_vence_en > ? FOR UPDATE",
					FILA, organizationId, codigo, momento));
			if (fila.isEmpty()) {
				return Optional.<VinculoDeTelegram>empty();
			}
			long cuentaId = fila.get().cuentaId();
			// El usuario de Telegram atado a otra cuenta del centro se suelta antes: es unico.
			jdbc.update("""
					UPDATE telegram_vinculo SET telegram_usuario_id = NULL, telegram_nombre = NULL, vinculado_en = NULL,
					       actualizado_en = ?
					WHERE organization_id = ? AND telegram_usuario_id = ? AND cuenta_id <> ?""",
					momento, organizationId, telegramUsuarioId, cuentaId);
			jdbc.update("""
					UPDATE telegram_vinculo SET consultorio_id = COALESCE(codigo_consultorio_id, consultorio_id),
					       codigo = NULL, codigo_vence_en = NULL, codigo_consultorio_id = NULL, telegram_usuario_id = ?,
					       telegram_nombre = ?, vinculado_en = ?, actualizado_en = ?
					WHERE organization_id = ? AND cuenta_id = ?""",
					telegramUsuarioId, telegramNombre, momento, momento, organizationId, cuentaId);
			return deLaCuenta(organizationId, cuentaId);
		});
	}

	@Override
	public void desvincular(long organizationId, long cuentaId, Instant ahora) {
		jdbc.update("""
				UPDATE telegram_vinculo SET telegram_usuario_id = NULL, telegram_nombre = NULL, vinculado_en = NULL,
				       codigo = NULL, codigo_vence_en = NULL, codigo_consultorio_id = NULL, actualizado_en = ?
				WHERE organization_id = ? AND cuenta_id = ?""", Timestamp.from(ahora), organizationId, cuentaId);
	}

	private static Optional<VinculoDeTelegram> primera(List<VinculoDeTelegram> filas) {
		return filas.stream().findFirst();
	}

	private static Instant instante(Timestamp ts) {
		return ts == null ? null : ts.toInstant();
	}
}
