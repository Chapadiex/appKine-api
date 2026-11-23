package com.akine.assistant.application;

import com.akine.assistant.application.RespuestaDelAsistente.AccionParaConfirmar;
import com.akine.assistant.application.RespuestaDelAsistente.OpcionDePersona;
import com.akine.assistant.application.RespuestaDelAsistente.Resultado;
import com.akine.assistant.domain.AccionPropuesta;
import com.akine.assistant.domain.AsistenteAjustes;
import com.akine.assistant.domain.PermissionCodes;
import com.akine.assistant.domain.exception.AccionInvalidaException;
import com.akine.assistant.domain.exception.AccionNoEncontradaException;
import com.akine.assistant.domain.exception.AccionNoVigenteException;
import com.akine.assistant.domain.exception.AccionesDeshabilitadasException;
import com.akine.assistant.domain.port.AccionesPropuestas;
import com.akine.assistant.spi.AccionDelAsistente;
import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ClavesDeFila;
import com.akine.assistant.spi.HerramientaDelAsistente;
import com.akine.assistant.spi.Intencion;
import com.akine.assistant.spi.PedidoDeAccion;
import com.akine.assistant.spi.PedidoDeHerramienta;
import com.akine.assistant.spi.PropuestaDeAccion;
import com.akine.assistant.spi.ResultadoDeAccion;
import com.akine.assistant.spi.ResultadoDeHerramienta;
import com.akine.assistant.spi.Tarjeta;
import com.akine.assistant.spi.TipoDeAccion;
import com.akine.organization.spi.FuncionIa;
import com.akine.organization.spi.FuncionesIaDelCentro;
import com.akine.organization.spi.PermissionEvaluator;
import com.akine.organization.spi.PermissionQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Las acciones del asistente: proponer a partir de una frase, y confirmar o descartar lo propuesto
 * (docs/diseno/AKINE-asistente-acciones.md).
 *
 * <p><b>Nada se graba al proponer.</b> La propuesta guarda solo ids y vence; la confirmacion la toma
 * con un UPDATE condicionado, asi que dos confirmaciones a la vez ejecutan una sola, y recien ahi
 * llama al modulo dueño, que revalida todo con los permisos de quien confirma.
 *
 * <p><b>Sin transaccion</b>, igual que {@link AsistenteService} y por un motivo mas: el cobro, el
 * lote de asistencia y el cierre de sesion exigen no tener ninguna activa.
 */
@Service
public class AccionesDelAsistente {

	private static final Logger log = LoggerFactory.getLogger(AccionesDelAsistente.class);

	static final String SESION_REGISTER = "sesion:register";

	/**
	 * Las que confirmadas dos veces no graban dos veces: el cobro lleva la clave de la accion, la
	 * asistencia es idempotente por el hecho (clase, persona) y la llegada tambien. Solo estas se
	 * vuelven a tomar si un pedido murio con la propuesta {@code EN_CURSO}; en las demas (una atencion
	 * a medio cargar, un alta) repetir duplicaria.
	 */
	static final java.util.Set<TipoDeAccion> REPETIBLES = java.util.EnumSet.of(TipoDeAccion.PAGO,
			TipoDeAccion.ASISTENCIA_CLASE, TipoDeAccion.LLEGADA);

	/** Cuanto se espera antes de dar por muerto al pedido que tomo una propuesta. */
	static final java.time.Duration ABANDONO = java.time.Duration.ofMinutes(2);
	static final String TEXTO_PROPUESTA = "Entendí esto. Revisalo y confirmá.";
	static final String SIN_PERMISO = "No tenés permiso para hacer eso.";
	static final String YA_CONFIRMADA = "Esto ya estaba confirmado: no lo grabé de nuevo.";

	private final AsistenteAjustes ajustes;
	private final PermissionEvaluator permisos;
	private final FuncionesIaDelCentro funcionesIa;
	private final InterpretadorDeAcciones interpretador;
	private final AccionesPropuestas propuestas;
	private final AuditoriaDelAsistente auditoria;
	private final Map<TipoDeAccion, AccionDelAsistente> acciones = new EnumMap<>(TipoDeAccion.class);
	private final HerramientaDelAsistente buscadorDePersonas;
	private final Clock clock;
	private final JsonMapper json = JsonMapper.builder().build();

	@Autowired
	public AccionesDelAsistente(AsistenteAjustes ajustes, PermissionEvaluator permisos,
			FuncionesIaDelCentro funcionesIa, InterpretadorDeAcciones interpretador, AccionesPropuestas propuestas,
			AuditoriaDelAsistente auditoria, List<AccionDelAsistente> acciones,
			List<HerramientaDelAsistente> herramientas) {
		this(ajustes, permisos, funcionesIa, interpretador, propuestas, auditoria, acciones, herramientas,
				Clock.systemUTC());
	}

	AccionesDelAsistente(AsistenteAjustes ajustes, PermissionEvaluator permisos, FuncionesIaDelCentro funcionesIa,
			InterpretadorDeAcciones interpretador, AccionesPropuestas propuestas, AuditoriaDelAsistente auditoria,
			List<AccionDelAsistente> acciones, List<HerramientaDelAsistente> herramientas, Clock clock) {
		this.ajustes = ajustes;
		this.permisos = permisos;
		this.funcionesIa = funcionesIa;
		this.interpretador = interpretador;
		this.propuestas = propuestas;
		this.auditoria = auditoria;
		this.clock = clock;
		for (AccionDelAsistente accion : acciones) {
			if (this.acciones.put(accion.tipo(), accion) != null) {
				throw new IllegalStateException("Dos acciones del asistente para " + accion.tipo());
			}
		}
		this.buscadorDePersonas = herramientas.stream()
				.filter(h -> h.intencion() == Intencion.BUSCAR_PERSONA).findFirst().orElse(null);
	}

	/**
	 * Lo que se le responde a una frase que cuenta o pide algo. Vacio si no es una accion (o si las
	 * acciones estan apagadas, o quien habla no las tiene): el llamador sigue con las consultas, y
	 * en esos tres casos <b>sin haber llamado al modelo</b>.
	 *
	 * @param actor          ya con la sede activa; el interruptor del asistente y {@code asistente:use}
	 *                       los controlo el llamador
	 * @param personaElegida la opcion elegida en una aclaracion, o {@code null}
	 */
	public Optional<RespuestaDelAsistente> proponer(ActorDelAsistente actor, ZoneId zona, String frase,
			Long personaElegida) {
		if (!ajustes.acciones() || EsPregunta.es(frase) || !puede(actor, PermissionCodes.ASISTENTE_ACCIONES)) {
			return Optional.empty();
		}
		AccionInterpretada entendida = ControlDeAccion.controlar(interpretador.interpretar(frase), frase);
		if (entendida.ninguna()) {
			return Optional.empty();
		}
		TipoDeAccion tipo = tipo(entendida, actor);
		AccionDelAsistente accion = acciones.get(tipo);
		if (accion == null) {
			return Optional.empty();
		}
		LocalDate hoy = LocalDate.now(clock.withZone(zona));
		Long personaId = null;
		String personaNombre = null;
		if (usaPersona(tipo)) {
			Object resuelta = resolverPersona(actor, entendida.persona(), frase, hoy, zona, personaElegida);
			if (resuelta instanceof RespuestaDelAsistente corte) {
				return Optional.of(corte);
			}
			@SuppressWarnings("unchecked")
			Map<String, Object> fila = (Map<String, Object>) resuelta;
			personaId = idDe(fila);
			personaNombre = String.valueOf(fila.get(ClavesDeFila.NOMBRE));
		}
		PedidoDeAccion pedido = new PedidoDeAccion(tipo, personaId, personaNombre, entendida.clase(),
				entendida.modoAsistencia(), entendida.nombres(), entendida.monto(), entendida.medio(),
				entendida.practicas(), entendida.datos(), frase.strip(), hoy, zona);
		PropuestaDeAccion propuesta;
		try {
			propuesta = accion.preparar(actor, pedido);
		}
		catch (AccessDeniedException e) {
			return Optional.of(simple(SIN_PERMISO, Resultado.SIN_PERMISO));
		}
		if (propuesta.sinTarjeta() != null || propuesta.tarjeta() == null) {
			return Optional.of(simple(Objects.requireNonNullElse(propuesta.sinTarjeta(), Plantillas.AYUDA),
					Resultado.RESPONDIDA));
		}
		Instant ahora = Instant.now(clock);
		Instant venceEn = ahora.plus(ajustes.vigenciaDeAccion());
		long id = propuestas.guardar(AccionPropuesta.nueva(actor.organizationId(), actor.consultorioId(),
				actor.accountId(), tipo, propuesta.referencias(), ahora, venceEn));
		auditar("ASISTENTE_ACCION_PROPUESTA", actor, id, tipo, propuesta.referencias());
		return Optional.of(new RespuestaDelAsistente(TEXTO_PROPUESTA, Interpretacion.FUERA_DE_ALCANCE, List.of(),
				List.of(), null, List.of(), Resultado.PROPUESTA, null, new AccionParaConfirmar(id, tipo,
						propuesta.titulo(), venceEn, propuesta.tarjeta(), propuesta.avisos(), propuesta.bloqueos())));
	}

	/**
	 * Graba lo que la persona confirmo. Confirmar dos veces no graba de nuevo: la segunda devuelve
	 * {@code CONFIRMADA} con el mismo enlace y los mismos ids, y un texto que lo dice.
	 *
	 * @throws AccionesDeshabilitadasException con el interruptor apagado (409)
	 * @throws AccessDeniedException sin contexto o sin {@code asistente:acciones} (403)
	 * @throws AccionNoEncontradaException si no existe o es de otra cuenta, sede u organizacion (404)
	 * @throws AccionInvalidaException si la tarjeta no es la de la propuesta (400)
	 * @throws AccionNoVigenteException si vencio, se descarto o la esta confirmando otro pedido (409)
	 */
	public ResultadoDeAccion confirmar(ActorDelAsistente actor, long consultorioId, long accionId, Tarjeta tarjeta) {
		ActorDelAsistente enLaSede = exigirAcceso(actor, consultorioId);
		AccionPropuesta fila = buscar(enLaSede, accionId);
		if (tarjeta == null || !tarjeta.esDe(fila.tipo())
				|| !Objects.equals(fila.referencias().get(PropuestaDeAccion.PERSONA), tarjeta.persona())) {
			throw new AccionInvalidaException();
		}
		AccionDelAsistente accion = acciones.get(fila.tipo());
		if (accion == null) {
			throw new AccionNoVigenteException();
		}
		Instant ahora = Instant.now(clock);
		if (!propuestas.tomar(accionId, ahora, REPETIBLES.contains(fila.tipo()) ? ahora.minus(ABANDONO) : null)) {
			AccionPropuesta actual = buscar(enLaSede, accionId);
			if (actual.estado() == AccionPropuesta.Estado.CONFIRMADA && actual.resultado() != null) {
				// La fila guarda solo ids (§5.4): el texto de la primera vez no esta, y no hace falta.
				ResultadoDeAccion guardado = json.readValue(actual.resultado(), ResultadoDeAccion.class);
				return ResultadoDeAccion.confirmada(YA_CONFIRMADA, guardado.enlace(), guardado.referencias());
			}
			throw new AccionNoVigenteException();
		}
		ResultadoDeAccion resultado;
		try {
			resultado = accion.ejecutar(enLaSede, accionId, tarjeta);
		}
		catch (AccessDeniedException e) {
			resultado = ResultadoDeAccion.rechazada(SIN_PERMISO);
		}
		catch (RuntimeException e) {
			// No se sabe si llego a grabar algo: la propuesta se suelta y cada accion es idempotente por
			// su cuenta donde la operacion lo permite (la clave del cobro, la de la sesion sin turno).
			propuestas.soltar(accionId);
			log.warn("La accion {} del asistente fallo al ejecutar: {}", fila.tipo(), e.getClass().getSimpleName());
			throw e;
		}
		if (resultado.fueConfirmada()) {
			// Sin el texto ni los detalles: nombran a la persona, y la fila no guarda textos (§5.4).
			try {
				propuestas.confirmar(accionId, json.writeValueAsString(ResultadoDeAccion.confirmada(null,
						resultado.enlace(), resultado.referencias())), Instant.now(clock));
			}
			catch (RuntimeException e) {
				// Ya se grabo: el usuario recibe su resultado igual. La fila queda EN_CURSO; si la accion es
				// repetible se puede volver a tomar y la clave de idempotencia evita la segunda escritura.
				log.warn("La accion {} se grabo pero no se pudo marcar confirmada: {}", fila.tipo(),
						e.getClass().getSimpleName());
			}
			auditar("ASISTENTE_ACCION_CONFIRMADA", enLaSede, accionId, fila.tipo(), resultado.referencias());
		}
		else {
			propuestas.soltar(accionId);
			auditar("ASISTENTE_ACCION_RECHAZADA", enLaSede, accionId, fila.tipo(), fila.referencias());
		}
		return resultado;
	}

	/** Descarta una propuesta. Descartar una que ya no esta propuesta no hace nada. */
	public void descartar(ActorDelAsistente actor, long consultorioId, long accionId) {
		ActorDelAsistente enLaSede = exigirAcceso(actor, consultorioId);
		AccionPropuesta fila = buscar(enLaSede, accionId);
		if (propuestas.descartar(accionId, Instant.now(clock))) {
			auditar("ASISTENTE_ACCION_DESCARTADA", enLaSede, accionId, fila.tipo(), fila.referencias());
		}
	}

	private ActorDelAsistente exigirAcceso(ActorDelAsistente actor, long consultorioId) {
		if (!ajustes.enabled() || !ajustes.acciones()) {
			throw new AccionesDeshabilitadasException();
		}
		if (actor.organizationId() == null) {
			throw new AccessDeniedException("La operacion requiere una organizacion en contexto");
		}
		if (!funcionesIa.prendida(actor.organizationId(), FuncionIa.ASISTENTE)) {
			throw new AccionesDeshabilitadasException();
		}
		ActorDelAsistente enLaSede = new ActorDelAsistente(actor.accountId(), actor.platformAdmin(),
				actor.organizationId(), consultorioId, actor.roleCode());
		if (!puede(enLaSede, PermissionCodes.ASISTENTE_ACCIONES)) {
			throw new AccessDeniedException("Permiso requerido: " + PermissionCodes.ASISTENTE_ACCIONES);
		}
		return enLaSede;
	}

	private AccionPropuesta buscar(ActorDelAsistente actor, long accionId) {
		return propuestas.buscar(accionId, actor.organizationId(), actor.consultorioId(), actor.accountId())
				.orElseThrow(AccionNoEncontradaException::new);
	}

	/** Quien no puede registrar una sesion y cuenta una atencion, registra la llegada (§6.4). */
	private TipoDeAccion tipo(AccionInterpretada entendida, ActorDelAsistente actor) {
		TipoDeAccion tipo = TipoDeAccion.valueOf(entendida.accion());
		return tipo == TipoDeAccion.ATENCION && !puede(actor, SESION_REGISTER) ? TipoDeAccion.LLEGADA : tipo;
	}

	private static boolean usaPersona(TipoDeAccion tipo) {
		return tipo == TipoDeAccion.PAGO || tipo == TipoDeAccion.ATENCION || tipo == TipoDeAccion.LLEGADA
				|| tipo == TipoDeAccion.PACIENTE_EDICION;
	}

	private boolean puede(ActorDelAsistente actor, String permiso) {
		return permisos.evaluate(new PermissionQuery(actor.accountId(), permiso, actor.organizationId(),
				actor.consultorioId(), null, Instant.now(clock))).granted();
	}

	/**
	 * La fila de la persona, o la respuesta que corta antes. La misma regla que en las consultas: con
	 * varias se pregunta, y un id elegido solo vale si esta entre los candidatos de ese nombre.
	 */
	private Object resolverPersona(ActorDelAsistente actor, String nombre, String frase, LocalDate hoy, ZoneId zona,
			Long personaElegida) {
		if (nombre == null || buscadorDePersonas == null) {
			return simple(Plantillas.FALTA_PERSONA, Resultado.FALTA_PERSONA);
		}
		ResultadoDeHerramienta candidatos;
		try {
			candidatos = buscadorDePersonas.ejecutar(actor,
					new PedidoDeHerramienta(null, nombre, frase, hoy, hoy, zona, null));
		}
		catch (AccessDeniedException e) {
			return simple(SIN_PERMISO, Resultado.SIN_PERMISO);
		}
		if (candidatos.sinPermiso()) {
			return simple(SIN_PERMISO, Resultado.SIN_PERMISO);
		}
		List<Map<String, Object>> filas = candidatos.filas();
		Map<String, Object> elegida = personaElegida == null
				? (filas.size() == 1 ? filas.getFirst() : null)
				: filas.stream().filter(f -> idDe(f) == personaElegida).findFirst().orElse(null);
		if (elegida != null) {
			return elegida;
		}
		if (filas.isEmpty() || personaElegida != null) {
			return simple(Plantillas.personaNoEncontrada(nombre), Resultado.PERSONA_NO_ENCONTRADA);
		}
		List<OpcionDePersona> opciones = filas.stream()
				.limit(AsistenteService.MAX_OPCIONES)
				.map(f -> new OpcionDePersona(idDe(f), f.get(ClavesDeFila.NOMBRE)
						+ (f.get(ClavesDeFila.DOCUMENTO) == null ? "" : " · " + f.get(ClavesDeFila.DOCUMENTO))))
				.toList();
		return new RespuestaDelAsistente(Plantillas.variasPersonas(nombre), Interpretacion.FUERA_DE_ALCANCE,
				List.of(), opciones, null, List.of(), Resultado.ACLARACION);
	}

	private static long idDe(Map<String, Object> fila) {
		return ((Number) fila.get(ClavesDeFila.PERSONA_ID)).longValue();
	}

	private static RespuestaDelAsistente simple(String texto, Resultado resultado) {
		return new RespuestaDelAsistente(texto, Interpretacion.FUERA_DE_ALCANCE, List.of(), List.of(), null,
				List.of(), resultado);
	}

	/** Tipo e ids. Sin la frase ni nada de la tarjeta: pueden traer nombres y la nota clinica. */
	private void auditar(String evento, ActorDelAsistente actor, long accionId, TipoDeAccion tipo,
			Map<String, Long> ids) {
		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("accionId", String.valueOf(accionId));
		detalles.put("tipo", tipo.name());
		ids.forEach((clave, valor) -> detalles.put(clave, String.valueOf(valor)));
		auditoria.registrarAccion(evento, actor.organizationId(), actor.consultorioId(), actor.accountId(), detalles);
	}
}
