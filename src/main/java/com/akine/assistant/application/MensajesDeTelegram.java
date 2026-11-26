package com.akine.assistant.application;

import com.akine.assistant.application.RespuestaDelAsistente.AccionParaConfirmar;
import com.akine.assistant.application.RespuestaDelAsistente.OpcionDePersona;
import com.akine.assistant.application.RespuestaDelAsistente.Resultado;
import com.akine.assistant.domain.BotDeTelegram;
import com.akine.assistant.domain.TelegramAjustes;
import com.akine.assistant.domain.VinculoDeTelegram;
import com.akine.assistant.domain.exception.AccionNoVigenteException;
import com.akine.assistant.domain.exception.AccionesDeshabilitadasException;
import com.akine.assistant.domain.exception.AsistenteDeshabilitadoException;
import com.akine.assistant.domain.exception.DictadoDeshabilitadoException;
import com.akine.assistant.domain.exception.DictadoInvalidoException;
import com.akine.assistant.domain.exception.DictadoNoDisponibleException;
import com.akine.assistant.domain.exception.ModeloNoDisponibleException;
import com.akine.assistant.domain.port.ClienteDeTelegram;
import com.akine.assistant.domain.port.ClienteDeTelegram.Boton;
import com.akine.assistant.domain.port.ClienteDeTelegram.Novedad;
import com.akine.assistant.domain.port.VinculosDeTelegram;
import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ResultadoDeAccion;
import com.akine.assistant.spi.Tarjeta;
import com.akine.assistant.spi.Tarjeta.TarjetaDeAsistencia;
import com.akine.assistant.spi.Tarjeta.TarjetaDeAtencion;
import com.akine.assistant.spi.Tarjeta.TarjetaDePaciente;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.platform.spi.identity.AccountIdentity;
import com.akine.platform.spi.identity.AccountIdentityDirectory;
import com.akine.platform.spi.tenant.MembershipDirectory;
import com.akine.platform.spi.tenant.RequestTenantContext;
import com.akine.platform.spi.tenant.TenantContextHolder;
import com.akine.platform.spi.tenant.TenantMembership;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lo que llega al bot de un centro (docs/diseno/AKINE-telegram.md §4).
 *
 * <p>Un mensaje de un profesional vinculado entra al asistente <b>igual que desde la pantalla</b>:
 * con su cuenta, en su sede, con sus permisos y con el interruptor de IA del centro. La nota de voz
 * pasa antes por el dictado (Whisper local). La diferencia es una sola: <b>lo que el asistente
 * propone se confirma solo</b>, salvo que la tarjeta tenga un bloqueo o algo para elegir. Eso es lo
 * que se le pidio a Telegram ("decir lo que hice y que quede cargado"), y es seguro porque la
 * confirmacion revalida todo por el camino normal de cada modulo y queda auditada.
 *
 * <p>Si el asistente no sabe de que paciente se habla, el bot pregunta con botones; el texto se
 * recuerda en memoria unos minutos para reenviarlo con la persona elegida. Nada del mensaje se
 * guarda en la base ni va al log.
 *
 * <p>Sin transaccion: las acciones del asistente exigen no tener una activa.
 */
@Service
public class MensajesDeTelegram {

	private static final Logger log = LoggerFactory.getLogger(MensajesDeTelegram.class);

	static final Duration VIDA_DE_LA_ACLARACION = Duration.ofMinutes(15);
	private static final String PREFIJO_PERSONA = "p:";

	static final String AYUDA = """
			Mandame un audio o un mensaje contando lo que hiciste, por ejemplo:
			«Juan Strudel: movilidad de hombro y electro, dolor 4 de 10, mejoró el rango».
			Lo cargo en AKINE con tu cuenta. Si hay dudas sobre el paciente, te pregunto.""";
	static final String SIN_VINCULO = """
			No conozco tu cuenta. En AKINE entrá a «Asistente de datos» → «Telegram», generá un código \
			y mandámelo así: /vincular 123456""";

	private final TelegramAjustes ajustes;
	private final ClienteDeTelegram telegram;
	private final VinculosDeTelegram vinculos;
	private final MembershipDirectory memberships;
	private final TenantContextHolder tenantContext;
	private final AsistenteService asistente;
	private final AccionesDelAsistente acciones;
	private final DictadoService dictado;
	private final AccountIdentityDirectory identidades;
	private final ConsultorioDirectory sedes;
	private final AuditoriaDelAsistente auditoria;
	private final Clock clock = Clock.systemUTC();
	private final SecureRandom azar = new SecureRandom();
	private final Map<String, Aclaracion> aclaraciones = new ConcurrentHashMap<>();

	@SuppressWarnings("java:S107")
	public MensajesDeTelegram(TelegramAjustes ajustes, ClienteDeTelegram telegram, VinculosDeTelegram vinculos,
			MembershipDirectory memberships, TenantContextHolder tenantContext, AsistenteService asistente,
			AccionesDelAsistente acciones, DictadoService dictado, AccountIdentityDirectory identidades,
			ConsultorioDirectory sedes, AuditoriaDelAsistente auditoria) {
		this.ajustes = ajustes;
		this.telegram = telegram;
		this.vinculos = vinculos;
		this.memberships = memberships;
		this.tenantContext = tenantContext;
		this.asistente = asistente;
		this.acciones = acciones;
		this.dictado = dictado;
		this.identidades = identidades;
		this.sedes = sedes;
		this.auditoria = auditoria;
	}

	/**
	 * Procesa un update y contesta en el mismo chat. <b>Nunca propaga</b> un rechazo de negocio: lo
	 * contesta. Solo propaga un fallo al hablar con Telegram, para que el sondeo no avance el offset
	 * de un mensaje que no pudo contestar.
	 */
	public void procesar(BotDeTelegram bot, Novedad novedad) {
		purgarAclaraciones();
		if (novedad.esBoton()) {
			telegram.contestarBoton(bot.token(), novedad.callbackId());
			procesarBoton(bot, novedad);
			return;
		}
		String texto = novedad.texto() == null ? "" : novedad.texto().strip();
		String comando = comando(texto, bot.usuario());
		if (comando != null) {
			procesarComando(bot, novedad, comando, argumento(texto));
			return;
		}
		if (texto.isEmpty() && novedad.vozFileId() == null) {
			return;
		}
		Optional<VinculoDeTelegram> vinculo = vinculos.delUsuario(bot.organizationId(), novedad.usuarioId());
		if (vinculo.isEmpty()) {
			// En un grupo no se contesta a quien no esta vinculado: el bot no tiene que hablar en cada
			// mensaje del grupo. En privado, si: es la unica forma de que la persona sepa que hacer.
			if (esPrivado(novedad)) {
				responder(bot, novedad, SIN_VINCULO, List.of());
			}
			return;
		}
		atender(bot, novedad, vinculo.get(), texto, null);
	}

	// =================================================================================
	// Comandos
	// =================================================================================

	private void procesarComando(BotDeTelegram bot, Novedad novedad, String comando, String argumento) {
		switch (comando) {
			case "start", "vincular" -> {
				if (argumento.isEmpty()) {
					responder(bot, novedad, "start".equals(comando) ? AYUDA + "\n\n" + SIN_VINCULO : SIN_VINCULO,
							List.of());
					return;
				}
				vincular(bot, novedad, argumento);
			}
			case "ayuda", "help" -> responder(bot, novedad, AYUDA, List.of());
			default -> { /* Comandos de otros bots del grupo: no son para este. */ }
		}
	}

	private void vincular(BotDeTelegram bot, Novedad novedad, String codigo) {
		String limpio = codigo.replaceAll("\\s", "");
		Instant ahora = Instant.now(clock);
		Optional<VinculoDeTelegram> vinculado = limpio.matches("\\d{6}")
				? vinculos.usarCodigo(bot.organizationId(), limpio, novedad.usuarioId(),
						recortar(novedad.nombreUsuario(), 120), ahora)
				: Optional.empty();
		if (vinculado.isEmpty()) {
			responder(bot, novedad, "Ese código no existe o venció (dura 15 minutos). Generá otro en AKINE.",
					List.of());
			return;
		}
		VinculoDeTelegram v = vinculado.get();
		auditoria.registrarAccion("TELEGRAM_VINCULADO", v.organizationId(), v.consultorioId(), v.cuentaId(),
				Map.of("telegramUsuarioId", String.valueOf(novedad.usuarioId())));
		String nombre = Optional.ofNullable(identidades.identidadesDe(List.of(v.cuentaId())).get(v.cuentaId()))
				.map(AccountIdentity::nombreCompleto).filter(n -> !n.isBlank()).orElse("tu cuenta");
		String sede = sedes.find(v.organizationId(), v.consultorioId()).map(ConsultorioSnapshot::name).orElse("tu sede");
		responder(bot, novedad, "Listo, " + nombre + ". Lo que me mandes se carga con tu cuenta en " + sede
				+ ".\n\n" + AYUDA, List.of());
	}

	// =================================================================================
	// Mensajes y botones
	// =================================================================================

	private void procesarBoton(BotDeTelegram bot, Novedad novedad) {
		String datos = novedad.callbackDatos() == null ? "" : novedad.callbackDatos();
		if (!datos.startsWith(PREFIJO_PERSONA)) {
			return;
		}
		String[] partes = datos.substring(PREFIJO_PERSONA.length()).split(":");
		Aclaracion aclaracion = partes.length == 2 ? aclaraciones.get(partes[0]) : null;
		Optional<VinculoDeTelegram> vinculo = vinculos.delUsuario(bot.organizationId(), novedad.usuarioId());
		// Solo quien mando el mensaje elige: otro del grupo no puede cargar a nombre de el.
		if (aclaracion == null || vinculo.isEmpty() || aclaracion.cuentaId() != vinculo.get().cuentaId()
				|| aclaracion.organizationId() != bot.organizationId()) {
			responder(bot, novedad, "Esa pregunta ya no está vigente: mandá el mensaje de nuevo.", List.of());
			return;
		}
		long personaId;
		try {
			personaId = Long.parseLong(partes[1]);
		}
		catch (NumberFormatException e) {
			return;
		}
		aclaraciones.remove(partes[0]);
		atender(bot, novedad, vinculo.get(), aclaracion.texto(), personaId);
	}

	private void atender(BotDeTelegram bot, Novedad novedad, VinculoDeTelegram vinculo, String texto, Long personaId) {
		Instant ahora = Instant.now(clock);
		Optional<TenantMembership> membership = memberships.resolveMembership(
				vinculo.cuentaId(), vinculo.organizationId(), vinculo.consultorioId(), ahora);
		if (membership.isEmpty() || !membership.get().operationalStatus().allowsContextUsage()) {
			responder(bot, novedad, "Tu cuenta ya no tiene acceso a la sede vinculada. Volvé a vincularte desde AKINE.",
					List.of());
			return;
		}
		if (!membership.get().operationalStatus().allowsBusinessMutations()) {
			responder(bot, novedad, "La suscripción del centro está suspendida: no puedo cargar nada.", List.of());
			return;
		}
		ActorDelAsistente actor = new ActorDelAsistente(vinculo.cuentaId(), false, vinculo.organizationId(),
				vinculo.consultorioId(), membership.get().roleCode());
		// Los servicios de los modulos leen el contexto de tenant del hilo, como en un request.
		tenantContext.set(new RequestTenantContext(vinculo.cuentaId(), vinculo.organizationId(),
				vinculo.consultorioId(), membership.get().roleCode(), membership.get().operationalStatus()));
		try {
			String pregunta = novedad.vozFileId() != null && personaId == null
					? transcribir(bot, novedad, actor)
					: texto;
			if (pregunta == null || pregunta.isBlank()) {
				responder(bot, novedad, "No entendí el audio. Probá de nuevo o escribilo.", List.of());
				return;
			}
			RespuestaDelAsistente respuesta = asistente.consultar(actor, vinculo.consultorioId(), recortar(pregunta, 600),
					List.of(), personaId, AgenteDelAsistente.RUTA_TELEGRAM);
			contestar(bot, novedad, actor, pregunta, respuesta);
		}
		catch (AsistenteDeshabilitadoException e) {
			responder(bot, novedad, "El asistente está apagado en este centro.", List.of());
		}
		catch (ModeloNoDisponibleException | DictadoNoDisponibleException e) {
			responder(bot, novedad, "No lo pude procesar ahora. Probá de nuevo en un rato.", List.of());
		}
		catch (DictadoDeshabilitadoException e) {
			responder(bot, novedad, "El dictado por voz está apagado en este centro: mandalo escrito.", List.of());
		}
		catch (DictadoInvalidoException e) {
			responder(bot, novedad, e.getMessage(), List.of());
		}
		catch (AccessDeniedException e) {
			responder(bot, novedad, AccionesDelAsistente.SIN_PERMISO, List.of());
		}
		catch (ClienteDeTelegram.FalloDeTelegram e) {
			throw e;
		}
		catch (RuntimeException e) {
			log.warn("Telegram: el mensaje no se pudo procesar: org={} causa={}", vinculo.organizationId(),
					e.getClass().getSimpleName(), e);
			responder(bot, novedad, "Algo falló y no cargué nada. Probá de nuevo o cargalo desde AKINE.", List.of());
		}
		finally {
			tenantContext.clear();
		}
	}

	private String transcribir(BotDeTelegram bot, Novedad novedad, ActorDelAsistente actor) {
		if (novedad.vozBytes() != null && novedad.vozBytes() > ajustes.maxBytesAudio()) {
			throw new DictadoInvalidoException("El audio es muy largo: mandalo en partes.");
		}
		byte[] audio = telegram.descargar(bot.token(), novedad.vozFileId(), ajustes.maxBytesAudio());
		String mime = novedad.vozMime() == null ? "audio/ogg" : novedad.vozMime();
		return dictado.transcribir(actor, actor.consultorioId(), audio, mime);
	}

	private void contestar(BotDeTelegram bot, Novedad novedad, ActorDelAsistente actor, String pregunta,
			RespuestaDelAsistente respuesta) {
		if (respuesta.resultado() == Resultado.PROPUESTA && respuesta.accion() != null) {
			confirmarSolo(bot, novedad, actor, respuesta.accion());
			return;
		}
		if (respuesta.resultado() == Resultado.ACLARACION && !respuesta.opciones().isEmpty()) {
			byte[] bytes = new byte[4];
			azar.nextBytes(bytes);
			String clave = HexFormat.of().formatHex(bytes);
			aclaraciones.put(clave, new Aclaracion(actor.organizationId(), actor.accountId(), pregunta,
					Instant.now(clock)));
			List<Boton> botones = new ArrayList<>();
			for (OpcionDePersona opcion : respuesta.opciones()) {
				botones.add(new Boton(recortar(opcion.etiqueta(), 60),
						PREFIJO_PERSONA + clave + ":" + opcion.personaId()));
			}
			responder(bot, novedad, respuesta.texto(), botones);
			return;
		}
		StringBuilder texto = new StringBuilder(respuesta.texto() == null ? "" : respuesta.texto());
		respuesta.advertencias().forEach(a -> texto.append("\n⚠️ ").append(a));
		responder(bot, novedad, texto.toString(), List.of());
	}

	/**
	 * Confirma lo propuesto si no hay nada que decidir. Lo que la persona dijo y no se reconocio como
	 * practica no se pierde: ya esta en la nota, que es la frase entera.
	 */
	private void confirmarSolo(BotDeTelegram bot, Novedad novedad, ActorDelAsistente actor,
			AccionParaConfirmar accion) {
		if (!accion.bloqueos().isEmpty()) {
			responder(bot, novedad, "No lo cargué: " + String.join(" ", accion.bloqueos()), List.of());
			return;
		}
		String pendiente = eleccionPendiente(accion.tarjeta());
		if (pendiente != null) {
			responder(bot, novedad, pendiente + " Decímelo en el mensaje, o cargalo desde AKINE.", List.of());
			return;
		}
		Tarjeta tarjeta = sinTratamientosSueltos(accion.tarjeta());
		ResultadoDeAccion resultado;
		try {
			resultado = acciones.confirmar(actor, actor.consultorioId(), accion.id(), tarjeta);
		}
		catch (AccionesDeshabilitadasException e) {
			responder(bot, novedad, "Las acciones del asistente están apagadas en este centro: no puedo cargar.",
					List.of());
			return;
		}
		catch (AccionNoVigenteException e) {
			responder(bot, novedad, "No pude confirmarlo a tiempo. Mandalo de nuevo.", List.of());
			return;
		}
		StringBuilder texto = new StringBuilder(resultado.fueConfirmada() ? "✅ " : "❌ No lo cargué: ")
				.append(resultado.texto());
		resultado.detalles().forEach(d -> texto.append("\n• ").append(d));
		accion.avisos().forEach(a -> texto.append("\n⚠️ ").append(a));
		if (resultado.enlace() != null && !ajustes.urlApp().isEmpty()) {
			texto.append("\n").append(ajustes.urlApp()).append(resultado.enlace());
		}
		responder(bot, novedad, texto.toString(), List.of());
	}

	/** Lo que la tarjeta deja para elegir a mano, o {@code null} si se puede confirmar como esta. */
	static String eleccionPendiente(Tarjeta tarjeta) {
		if (tarjeta instanceof TarjetaDeAtencion atencion) {
			if (atencion.turnoId() == null && atencion.ofertaId() == null) {
				return atencion.turnos().isEmpty()
						? "No sé qué servicio le diste a " + atencion.nombre() + ": tenés varios habilitados."
						: atencion.nombre() + " tiene más de un turno hoy con vos: decime la hora.";
			}
			if (atencion.casoId() == null && !atencion.casos().isEmpty()) {
				return atencion.nombre() + " tiene más de un caso clínico abierto: decime cuál.";
			}
		}
		if (tarjeta instanceof TarjetaDeAsistencia asistencia && asistencia.claseId() == null) {
			return "Hay más de una clase hoy: decime cuál.";
		}
		if (tarjeta instanceof TarjetaDePaciente paciente && !paciente.posiblesDuplicados().isEmpty()
				&& !paciente.confirmaPosibleDuplicado()) {
			return "Puede que esa persona ya exista en el padrón.";
		}
		return null;
	}

	/** Saca los tratamientos sin practica reconocida: con ellos la atencion no se puede cerrar. */
	static Tarjeta sinTratamientosSueltos(Tarjeta tarjeta) {
		if (!(tarjeta instanceof TarjetaDeAtencion a)
				|| a.tratamientos().stream().allMatch(t -> t.practicaId() != null)) {
			return tarjeta;
		}
		return new TarjetaDeAtencion(a.personaId(), a.nombre(), a.turnoId(), a.turnos(), a.ofertaId(), a.ofertas(),
				a.casoId(), a.casos(), a.tratamientos().stream().filter(t -> t.practicaId() != null).toList(),
				a.nota(), a.cerrar(), a.tratamientosPrevios());
	}

	// =================================================================================
	// Apoyo
	// =================================================================================

	private void responder(BotDeTelegram bot, Novedad novedad, String texto, List<Boton> botones) {
		String cuerpo = texto == null || texto.isBlank() ? "…" : recortar(texto, 4000);
		telegram.enviar(bot.token(), novedad.chatId(), novedad.esBoton() ? null : novedad.mensajeId(), cuerpo, botones);
	}

	/** {@code /comando} o {@code /comando@bot}; {@code null} si no es un comando para este bot. */
	static String comando(String texto, String botUsuario) {
		if (!texto.startsWith("/")) {
			return null;
		}
		String primera = texto.split("\\s+", 2)[0].substring(1);
		int arroba = primera.indexOf('@');
		if (arroba >= 0) {
			if (botUsuario == null || !primera.substring(arroba + 1).equalsIgnoreCase(botUsuario)) {
				return "";
			}
			primera = primera.substring(0, arroba);
		}
		return primera.toLowerCase(Locale.ROOT);
	}

	private static String argumento(String texto) {
		String[] partes = texto.split("\\s+", 2);
		return partes.length < 2 ? "" : partes[1].strip();
	}

	private static boolean esPrivado(Novedad novedad) {
		return novedad.chatId() == novedad.usuarioId();
	}

	private static String recortar(String texto, int maximo) {
		if (texto == null) {
			return null;
		}
		return texto.length() <= maximo ? texto : texto.substring(0, maximo);
	}

	private void purgarAclaraciones() {
		Instant limite = Instant.now(clock).minus(VIDA_DE_LA_ACLARACION);
		aclaraciones.values().removeIf(a -> a.creada().isBefore(limite));
	}

	private record Aclaracion(long organizationId, long cuentaId, String texto, Instant creada) {
	}
}
