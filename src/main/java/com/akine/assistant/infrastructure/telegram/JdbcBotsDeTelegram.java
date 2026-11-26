package com.akine.assistant.infrastructure.telegram;

import com.akine.assistant.domain.BotDeTelegram;
import com.akine.assistant.domain.port.BotsDeTelegram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@code telegram_bot} por JDBC, fuera de transaccion: cada sentencia se commitea sola, que es lo
 * que necesita el alquiler del sondeo. El token se cifra al guardar y se descifra al leer.
 */
@Repository
class JdbcBotsDeTelegram implements BotsDeTelegram {

	private static final Logger log = LoggerFactory.getLogger(JdbcBotsDeTelegram.class);

	private final JdbcTemplate jdbc;
	private final CifradorDeTokens cifrador;

	JdbcBotsDeTelegram(JdbcTemplate jdbc, CifradorDeTokens cifrador) {
		this.jdbc = jdbc;
		this.cifrador = cifrador;
	}

	@Override
	public Optional<BotDeTelegram> buscar(long organizationId) {
		return jdbc.query("""
				SELECT organization_id, token_cifrado, bot_id, bot_usuario, activo, ultimo_update_id, ultimo_error,
				       ultimo_contacto_en
				FROM telegram_bot WHERE organization_id = ?""",
				(rs, n) -> {
					Timestamp contacto = rs.getTimestamp("ultimo_contacto_en");
					return new BotDeTelegram(rs.getLong("organization_id"), descifrar(organizationId,
							rs.getString("token_cifrado")), rs.getLong("bot_id"), rs.getString("bot_usuario"),
							rs.getBoolean("activo"), rs.getLong("ultimo_update_id"), rs.getString("ultimo_error"),
							contacto == null ? null : contacto.toInstant());
				}, organizationId).stream().findFirst();
	}

	/** Un token que no se descifra (cambio la clave del despliegue) cuenta como bot sin token. */
	private String descifrar(long organizationId, String guardado) {
		if (guardado == null) {
			return null;
		}
		try {
			return cifrador.descifrar(guardado);
		}
		catch (IllegalArgumentException | IllegalStateException e) {
			log.warn("Telegram: el token del centro {} no se puede descifrar; hay que volver a conectarlo",
					organizationId);
			return null;
		}
	}

	@Override
	public void guardar(long organizationId, String token, long botId, String usuario, long cuentaId, Instant ahora) {
		Timestamp momento = Timestamp.from(ahora);
		// Otro bot empieza de cero: el offset de uno no vale para el otro.
		jdbc.update("""
				INSERT INTO telegram_bot (organization_id, token_cifrado, bot_id, bot_usuario, activo, ultimo_update_id,
				                          configurado_por, creado_en, actualizado_en)
				VALUES (?, ?, ?, ?, true, 0, ?, ?, ?)
				ON CONFLICT (organization_id) DO UPDATE SET
				    token_cifrado = EXCLUDED.token_cifrado,
				    ultimo_update_id = CASE WHEN telegram_bot.bot_id = EXCLUDED.bot_id
				                            THEN telegram_bot.ultimo_update_id ELSE 0 END,
				    bot_id = EXCLUDED.bot_id,
				    bot_usuario = EXCLUDED.bot_usuario,
				    activo = true,
				    ultimo_error = NULL,
				    configurado_por = EXCLUDED.configurado_por,
				    actualizado_en = EXCLUDED.actualizado_en""",
				organizationId, cifrador.cifrar(token), botId, usuario, cuentaId, momento, momento);
	}

	@Override
	public void desconectar(long organizationId, Instant ahora) {
		jdbc.update("UPDATE telegram_bot SET token_cifrado = NULL, activo = false, actualizado_en = ? "
				+ "WHERE organization_id = ?", Timestamp.from(ahora), organizationId);
	}

	@Override
	public List<Long> activos() {
		return jdbc.queryForList("SELECT organization_id FROM telegram_bot WHERE activo ORDER BY organization_id",
				Long.class);
	}

	@Override
	public boolean tomar(long organizationId, Instant ahora, Instant hasta) {
		return jdbc.update("""
				UPDATE telegram_bot SET tomado_hasta = ?
				WHERE organization_id = ? AND activo AND (tomado_hasta IS NULL OR tomado_hasta < ?)""",
				Timestamp.from(hasta), organizationId, Timestamp.from(ahora)) == 1;
	}

	@Override
	public void soltar(long organizationId) {
		jdbc.update("UPDATE telegram_bot SET tomado_hasta = NULL WHERE organization_id = ?", organizationId);
	}

	@Override
	public void avanzar(long organizationId, long updateId, Instant ahora) {
		jdbc.update("""
				UPDATE telegram_bot SET ultimo_update_id = GREATEST(ultimo_update_id, ?), ultimo_error = NULL,
				       ultimo_contacto_en = ?
				WHERE organization_id = ?""", updateId, Timestamp.from(ahora), organizationId);
	}

	@Override
	public void registrarError(long organizationId, String error, Instant ahora) {
		String recortado = error == null ? null : error.substring(0, Math.min(error.length(), 300));
		jdbc.update("UPDATE telegram_bot SET ultimo_error = ?, actualizado_en = ? WHERE organization_id = ?",
				recortado, Timestamp.from(ahora), organizationId);
	}
}
