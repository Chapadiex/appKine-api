package com.akine.assistant.application;

import com.akine.assistant.domain.DictadoAjustes;
import com.akine.assistant.domain.PermissionCodes;
import com.akine.assistant.domain.exception.DictadoDeshabilitadoException;
import com.akine.assistant.domain.exception.DictadoInvalidoException;
import com.akine.assistant.domain.exception.SedeNoEncontradaException;
import com.akine.assistant.domain.port.Transcriptor;
import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;

/**
 * Dictado por voz (docs/diseno/AKINE-dictado-por-voz.md): pasa audio a texto y, para la evaluacion
 * de la sesion, propone como repartir un texto dictado entre sus campos.
 *
 * <p>No guarda nada y no lee datos del centro: lo dictado se guarda cuando el profesional guarda la
 * pantalla, por el camino de siempre. Por eso no se audita. Ni el audio ni el texto van al log: solo
 * el tamano y cuanto tardo.
 */
@Service
public class DictadoService {

	private static final Logger log = LoggerFactory.getLogger(DictadoService.class);

	/**
	 * Chrome graba {@code audio/webm}, Safari {@code audio/mp4}; algunos navegadores etiquetan la
	 * grabacion como video aunque solo tenga audio.
	 */
	static final Set<String> VIDEOS_ACEPTADOS = Set.of("video/webm", "video/mp4", "video/ogg");

	private final DictadoAjustes ajustes;
	private final PermissionGuard permissionGuard;
	private final ConsultorioDirectory sedes;
	private final Transcriptor transcriptor;
	private final RepartidorDeEvaluacion repartidor;
	private final Clock clock = Clock.systemUTC();

	public DictadoService(DictadoAjustes ajustes, PermissionGuard permissionGuard, ConsultorioDirectory sedes,
			Transcriptor transcriptor, RepartidorDeEvaluacion repartidor) {
		this.ajustes = ajustes;
		this.permissionGuard = permissionGuard;
		this.sedes = sedes;
		this.transcriptor = transcriptor;
		this.repartidor = repartidor;
	}

	/**
	 * @throws DictadoDeshabilitadoException con el interruptor apagado (409)
	 * @throws AccessDeniedException sin contexto o sin {@code dictado:use} (403)
	 * @throws SedeNoEncontradaException si la sede no es de la organizacion (404)
	 * @throws DictadoInvalidoException si el audio esta vacio, es muy grande o no es audio (400)
	 * @throws com.akine.assistant.domain.exception.DictadoNoDisponibleException si Whisper no responde (503)
	 */
	public String transcribir(ActorDelAsistente actor, long consultorioId, byte[] audio, String tipoContenido) {
		autorizar(actor, consultorioId);
		if (audio == null || audio.length == 0) {
			throw new DictadoInvalidoException("No llego audio para transcribir.");
		}
		if (audio.length > ajustes.maxBytes()) {
			throw new DictadoInvalidoException("La grabacion es demasiado larga: grabala en partes mas cortas.");
		}
		String tipo = tipoBase(tipoContenido);
		if (!tipo.startsWith("audio/") && !VIDEOS_ACEPTADOS.contains(tipo)) {
			throw new DictadoInvalidoException("El archivo no es una grabacion de audio.");
		}
		long inicio = System.nanoTime();
		String texto = transcriptor.transcribir(audio, tipo, ajustes.plazo());
		log.info("Dictado transcripto: bytes={} ms={} vacio={}", audio.length,
				(System.nanoTime() - inicio) / 1_000_000, texto.isEmpty());
		return texto;
	}

	/**
	 * Propone como repartir un texto dictado entre los campos de la evaluacion. Mismas excepciones
	 * que {@link #transcribir}; el 503 tambien sale si el modelo del asistente esta apagado.
	 */
	public PropuestaDeEvaluacion proponerEvaluacion(ActorDelAsistente actor, long consultorioId, String texto) {
		autorizar(actor, consultorioId);
		if (texto == null || texto.isBlank()) {
			throw new DictadoInvalidoException("No hay texto dictado para repartir.");
		}
		if (texto.length() > RepartidorDeEvaluacion.MAX_LARGO_TEXTO) {
			throw new DictadoInvalidoException("El texto dictado supera los "
					+ RepartidorDeEvaluacion.MAX_LARGO_TEXTO + " caracteres.");
		}
		return repartidor.repartir(texto.strip());
	}

	private void autorizar(ActorDelAsistente actor, long consultorioId) {
		if (!ajustes.enabled()) {
			throw new DictadoDeshabilitadoException();
		}
		Long organizationId = actor.organizationId();
		if (organizationId == null) {
			throw new AccessDeniedException("La operacion requiere una organizacion en contexto");
		}
		permissionGuard.requirePermission(new PermissionQuery(actor.accountId(), PermissionCodes.DICTADO_USE,
				organizationId, consultorioId, null, Instant.now(clock)));
		if (sedes.find(organizationId, consultorioId).isEmpty()) {
			throw new SedeNoEncontradaException();
		}
	}

	/** {@code audio/webm;codecs=opus} → {@code audio/webm}. */
	static String tipoBase(String tipoContenido) {
		if (tipoContenido == null) {
			return "";
		}
		int puntoYComa = tipoContenido.indexOf(';');
		String base = puntoYComa < 0 ? tipoContenido : tipoContenido.substring(0, puntoYComa);
		return base.strip().toLowerCase(Locale.ROOT);
	}
}
