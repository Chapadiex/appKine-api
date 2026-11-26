package com.akine.assistant.application;

import com.akine.assistant.domain.BotDeTelegram;
import com.akine.assistant.domain.PermissionCodes;
import com.akine.assistant.domain.TelegramAjustes;
import com.akine.assistant.domain.VinculoDeTelegram;
import com.akine.assistant.domain.exception.BotDeTelegramNoConfiguradoException;
import com.akine.assistant.domain.exception.SedeNoEncontradaException;
import com.akine.assistant.domain.exception.TelegramDeshabilitadoException;
import com.akine.assistant.domain.exception.TokenDeTelegramInvalidoException;
import com.akine.assistant.domain.port.BotsDeTelegram;
import com.akine.assistant.domain.port.ClienteDeTelegram;
import com.akine.assistant.domain.port.ClienteDeTelegram.FalloDeTelegram;
import com.akine.assistant.domain.port.ClienteDeTelegram.IdentidadDelBot;
import com.akine.assistant.domain.port.VinculosDeTelegram;
import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Conectar el bot de Telegram del centro y vincular la cuenta de cada profesional
 * (docs/diseno/AKINE-telegram.md).
 *
 * <p>El bot lo conecta quien tiene {@code tenant:update} (el ORG_ADMIN). Vincularse lo hace cada
 * persona con {@code asistente:acciones} en la sede: lo que mande por Telegram se carga con su
 * cuenta, en esa sede, con sus permisos.
 *
 * <p>El token nunca vuelve por la API: la respuesta dice de que bot es, no con que se entra.
 */
@Service
public class TelegramService {

	static final Duration VIGENCIA_DEL_CODIGO = Duration.ofMinutes(15);
	private static final int INTENTOS_DE_CODIGO = 5;
	/** La forma de un token de @BotFather: id numerico, dos puntos y 35 caracteres. */
	private static final Pattern FORMA_DEL_TOKEN = Pattern.compile("\\d{5,15}:[A-Za-z0-9_-]{30,64}");

	private final TelegramAjustes ajustes;
	private final BotsDeTelegram bots;
	private final VinculosDeTelegram vinculos;
	private final ClienteDeTelegram telegram;
	private final PermissionGuard permissionGuard;
	private final ConsultorioDirectory sedes;
	private final AuditoriaDelAsistente auditoria;
	private final Clock clock = Clock.systemUTC();
	private final SecureRandom azar = new SecureRandom();

	public TelegramService(TelegramAjustes ajustes, BotsDeTelegram bots, VinculosDeTelegram vinculos,
			ClienteDeTelegram telegram, PermissionGuard permissionGuard, ConsultorioDirectory sedes,
			AuditoriaDelAsistente auditoria) {
		this.ajustes = ajustes;
		this.bots = bots;
		this.vinculos = vinculos;
		this.telegram = telegram;
		this.permissionGuard = permissionGuard;
		this.sedes = sedes;
		this.auditoria = auditoria;
	}

	// =================================================================================
	// El bot del centro
	// =================================================================================

	/** El bot conectado, o vacio. Exige {@code tenant:update}. */
	public Optional<BotDeTelegram> verBot(ActorDelAsistente actor, long organizationId) {
		exigirAdministrador(actor, organizationId);
		return bots.buscar(organizationId).filter(BotDeTelegram::activo);
	}

	/**
	 * Valida el token contra Telegram ({@code getMe}) y lo guarda cifrado. Reemplaza al bot anterior.
	 *
	 * @throws TokenDeTelegramInvalidoException si no tiene la forma de un token o Telegram no lo reconoce
	 */
	public BotDeTelegram conectar(ActorDelAsistente actor, long organizationId, String token) {
		exigirAdministrador(actor, organizationId);
		String limpio = token == null ? "" : token.strip();
		if (!FORMA_DEL_TOKEN.matcher(limpio).matches()) {
			throw new TokenDeTelegramInvalidoException(
					"Eso no parece un token de Telegram: copialo entero del mensaje de @BotFather.");
		}
		IdentidadDelBot identidad;
		try {
			identidad = telegram.conocer(limpio);
		}
		catch (FalloDeTelegram e) {
			throw new TokenDeTelegramInvalidoException(e.tokenRechazado()
					? "Telegram no reconoce ese token. Revisalo en @BotFather (/mybots → API Token)."
					: "No se pudo hablar con Telegram para validar el token: " + e.getMessage());
		}
		Instant ahora = Instant.now(clock);
		bots.guardar(organizationId, limpio, identidad.id(), identidad.usuario(), actor.accountId(), ahora);
		auditoria.registrarAccion("TELEGRAM_BOT_CONECTADO", organizationId, actor.consultorioId(),
				actor.accountId(), Map.of("bot", identidad.usuario()));
		return bots.buscar(organizationId).orElseThrow();
	}

	/** Borra el token. Los vinculos quedan: si se vuelve a conectar un bot, siguen valiendo. */
	public void desconectar(ActorDelAsistente actor, long organizationId) {
		exigirAdministrador(actor, organizationId);
		bots.desconectar(organizationId, Instant.now(clock));
		auditoria.registrarAccion("TELEGRAM_BOT_DESCONECTADO", organizationId, actor.consultorioId(),
				actor.accountId(), Map.of());
	}

	private void exigirAdministrador(ActorDelAsistente actor, long organizationId) {
		exigirHabilitado();
		if (actor.organizationId() == null) {
			throw new AccessDeniedException("La operacion requiere un contexto de trabajo activo");
		}
		if (actor.organizationId() != organizationId) {
			// Otra organizacion: para quien no tiene acceso, no existe.
			throw new SedeNoEncontradaException();
		}
		permissionGuard.requirePermission(PermissionQuery.of(actor.accountId(), "tenant:update", organizationId,
				Instant.now(clock)));
	}

	// =================================================================================
	// El vinculo de cada cuenta
	// =================================================================================

	/** El vinculo de quien pregunta y el bot del centro, para mostrarle como vincularse. */
	public MiVinculo verVinculo(ActorDelAsistente actor, long consultorioId) {
		long organizationId = exigirEnLaSede(actor, consultorioId);
		return new MiVinculo(vinculos.deLaCuenta(organizationId, actor.accountId()).orElse(null),
				bots.buscar(organizationId).filter(BotDeTelegram::activo).map(BotDeTelegram::usuario).orElse(null));
	}

	/**
	 * Un codigo de un solo uso para mandarle al bot. Si la cuenta ya estaba vinculada, sigue vinculada
	 * hasta que el codigo se use; usarlo cambia ademas la sede a esta.
	 *
	 * @throws BotDeTelegramNoConfiguradoException si el centro no conecto un bot
	 */
	public MiVinculo generarCodigo(ActorDelAsistente actor, long consultorioId) {
		long organizationId = exigirEnLaSede(actor, consultorioId);
		String botUsuario = bots.buscar(organizationId).filter(BotDeTelegram::activo)
				.map(BotDeTelegram::usuario)
				.orElseThrow(BotDeTelegramNoConfiguradoException::new);
		Instant ahora = Instant.now(clock);
		for (int intento = 0; intento < INTENTOS_DE_CODIGO; intento++) {
			String codigo = String.format("%06d", azar.nextInt(1_000_000));
			if (vinculos.nuevoCodigo(organizationId, actor.accountId(), consultorioId, codigo,
					ahora.plus(VIGENCIA_DEL_CODIGO), ahora)) {
				return new MiVinculo(vinculos.deLaCuenta(organizationId, actor.accountId()).orElseThrow(), botUsuario);
			}
		}
		throw new IllegalStateException("No se pudo generar un codigo de vinculacion libre");
	}

	public void desvincular(ActorDelAsistente actor, long consultorioId) {
		long organizationId = exigirEnLaSede(actor, consultorioId);
		vinculos.desvincular(organizationId, actor.accountId(), Instant.now(clock));
		auditoria.registrarAccion("TELEGRAM_DESVINCULADO", organizationId, consultorioId, actor.accountId(), Map.of());
	}

	private long exigirEnLaSede(ActorDelAsistente actor, long consultorioId) {
		exigirHabilitado();
		Long organizationId = actor.organizationId();
		if (organizationId == null) {
			throw new AccessDeniedException("La operacion requiere un contexto de trabajo activo");
		}
		sedes.find(organizationId, consultorioId).orElseThrow(SedeNoEncontradaException::new);
		permissionGuard.requirePermission(new PermissionQuery(actor.accountId(), PermissionCodes.ASISTENTE_ACCIONES,
				organizationId, consultorioId, null, Instant.now(clock)));
		return organizationId;
	}

	private void exigirHabilitado() {
		if (!ajustes.enabled()) {
			throw new TelegramDeshabilitadoException();
		}
	}

	/**
	 * @param vinculo    el de la cuenta, o {@code null} si nunca pidio un codigo
	 * @param botUsuario el bot del centro, o {@code null} si no hay
	 */
	public record MiVinculo(VinculoDeTelegram vinculo, String botUsuario) {
	}
}
