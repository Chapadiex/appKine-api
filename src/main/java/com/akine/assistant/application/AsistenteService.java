package com.akine.assistant.application;

import com.akine.assistant.application.RespuestaDelAsistente.OpcionDePersona;
import com.akine.assistant.application.RespuestaDelAsistente.Resultado;
import com.akine.assistant.domain.AsistenteAjustes;
import com.akine.assistant.domain.PermissionCodes;
import com.akine.assistant.domain.exception.AsistenteDeshabilitadoException;
import com.akine.assistant.domain.exception.SedeNoEncontradaException;
import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ClavesDeFila;
import com.akine.assistant.spi.HerramientaDelAsistente;
import com.akine.assistant.spi.Intencion;
import com.akine.assistant.spi.PedidoDeHerramienta;
import com.akine.assistant.spi.ResultadoDeHerramienta;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.organization.spi.ZonaDeSede;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Responde una pregunta en lenguaje natural sobre los datos de una sede (diseno, §4).
 *
 * <p>Orden fijo: interruptor, tenant y permiso <b>antes</b> de llamar al modelo; despues interpretar,
 * resolver el periodo, resolver la persona (y repreguntar si no esta claro), ejecutar la herramienta
 * del modulo dueno del dato con el mismo actor y armar el texto con la plantilla.
 *
 * <p><b>Sin transaccion propia</b>: ver {@link AuditoriaDelAsistente}. Sólo lectura en todo el
 * recorrido.
 */
@Service
public class AsistenteService {

	private static final Logger log = LoggerFactory.getLogger(AsistenteService.class);

	static final int MAX_OPCIONES = 5;
	static final String ENLACE_PERSONA = "/pacientes/";

	private final AsistenteAjustes ajustes;
	private final PermissionGuard permissionGuard;
	private final ConsultorioDirectory sedes;
	private final Interpretador interpretador;
	private final AuditoriaDelAsistente auditoria;
	private final Map<Intencion, HerramientaDelAsistente> herramientas;
	private final Clock clock;

	/** El de Spring. El otro fija el reloj para los tests; con dos, Spring necesita que se le diga cual. */
	@Autowired
	public AsistenteService(AsistenteAjustes ajustes, PermissionGuard permissionGuard, ConsultorioDirectory sedes,
			Interpretador interpretador, AuditoriaDelAsistente auditoria, List<HerramientaDelAsistente> herramientas) {
		this(ajustes, permissionGuard, sedes, interpretador, auditoria, herramientas, Clock.systemUTC());
	}

	AsistenteService(AsistenteAjustes ajustes, PermissionGuard permissionGuard, ConsultorioDirectory sedes,
			Interpretador interpretador, AuditoriaDelAsistente auditoria, List<HerramientaDelAsistente> herramientas,
			Clock clock) {
		this.ajustes = ajustes;
		this.permissionGuard = permissionGuard;
		this.sedes = sedes;
		this.interpretador = interpretador;
		this.auditoria = auditoria;
		this.clock = clock;
		this.herramientas = new EnumMap<>(Intencion.class);
		for (HerramientaDelAsistente herramienta : herramientas) {
			if (this.herramientas.put(herramienta.intencion(), herramienta) != null) {
				throw new IllegalStateException("Dos herramientas para la intencion " + herramienta.intencion());
			}
		}
	}

	/**
	 * @param personaId la persona que el usuario eligio entre las opciones de una aclaracion, o
	 *                  {@code null}
	 * @throws AsistenteDeshabilitadoException si el interruptor esta apagado (409)
	 * @throws AccessDeniedException sin contexto de organizacion o sin {@code asistente:use} (403)
	 * @throws SedeNoEncontradaException si la sede no es de la organizacion (404)
	 * @throws com.akine.assistant.domain.exception.ModeloNoDisponibleException si el modelo no responde (503)
	 */
	public RespuestaDelAsistente consultar(ActorDelAsistente actor, long consultorioId, String pregunta,
			List<TurnoPrevio> historial, Long personaId) {
		if (!ajustes.enabled()) {
			throw new AsistenteDeshabilitadoException();
		}
		Long organizationId = actor.organizationId();
		if (organizationId == null) {
			throw new AccessDeniedException("La operacion requiere una organizacion en contexto");
		}
		permissionGuard.requirePermission(new PermissionQuery(actor.accountId(), PermissionCodes.ASISTENTE_USE,
				organizationId, consultorioId, null, Instant.now(clock)));
		ConsultorioSnapshot sede = sedes.find(organizationId, consultorioId)
				.orElseThrow(SedeNoEncontradaException::new);
		ZoneId zona = ZonaDeSede.de(sede);
		ActorDelAsistente enLaSede = new ActorDelAsistente(actor.accountId(), actor.platformAdmin(),
				organizationId, consultorioId, actor.roleCode());

		Interpretacion interpretacion = interpretador.interpretar(pregunta, historial);
		RangoDeDias rango = rango(interpretacion, pregunta, LocalDate.now(clock.withZone(zona)));
		RespuestaDelAsistente respuesta = responder(enLaSede, interpretacion, pregunta, rango, zona, sede.name(), personaId);

		auditar(organizationId, consultorioId, actor.accountId(), respuesta, rango);
		return respuesta;
	}

	private RespuestaDelAsistente responder(ActorDelAsistente actor, Interpretacion interpretacion, String pregunta,
			RangoDeDias rango, ZoneId zona, String sede, Long personaElegida) {
		if (interpretacion.fueraDeAlcance() || !herramientas.containsKey(interpretacion.intencion())) {
			return simple(Plantillas.AYUDA, Interpretacion.FUERA_DE_ALCANCE, Resultado.FUERA_DE_ALCANCE);
		}

		Long personaId = null;
		String personaNombre = null;
		if (interpretacion.usaPersona()) {
			Optional<RespuestaDelAsistente> corte = Optional.empty();
			if (interpretacion.persona() == null) {
				corte = Optional.of(simple(Plantillas.FALTA_PERSONA, interpretacion, Resultado.FALTA_PERSONA));
			}
			else {
				ResultadoDeHerramienta candidatos = ejecutar(Intencion.BUSCAR_PERSONA, actor,
						pedido(interpretacion, null, interpretacion.persona(), pregunta, rango, zona));
				if (candidatos.sinPermiso()) {
					corte = Optional.of(simple(Plantillas.SIN_PERMISO, interpretacion, Resultado.SIN_PERMISO));
				}
				else if (candidatos.filas().isEmpty()) {
					corte = Optional.of(simple(Plantillas.personaNoEncontrada(interpretacion.persona()),
							interpretacion, Resultado.PERSONA_NO_ENCONTRADA));
				}
				else {
					// Con una opcion elegida, se toma esa, pero solo si esta entre los candidatos del
					// mismo nombre: un id cualquiera mandado por el cliente no se usa a ciegas.
					Optional<Map<String, Object>> elegida = candidatos.filas().stream()
							.filter(f -> personaElegida != null
									? personaElegida.equals(((Number) f.get(ClavesDeFila.PERSONA_ID)).longValue())
									: candidatos.filas().size() == 1)
							.findFirst();
					if (elegida.isPresent()) {
						personaId = ((Number) elegida.get().get(ClavesDeFila.PERSONA_ID)).longValue();
						personaNombre = String.valueOf(elegida.get().get(ClavesDeFila.NOMBRE));
					}
					else if (personaElegida != null) {
						corte = Optional.of(simple(Plantillas.personaNoEncontrada(interpretacion.persona()),
								interpretacion, Resultado.PERSONA_NO_ENCONTRADA));
					}
					else {
						corte = Optional.of(aclaracion(interpretacion, candidatos));
					}
				}
			}
			if (corte.isPresent()) {
				return corte.get();
			}
		}

		String texto = interpretacion.usaClase() ? interpretacion.clase() : null;
		ResultadoDeHerramienta resultado = ejecutar(interpretacion.intencion(), actor,
				pedido(interpretacion, personaId, texto, pregunta, rango, zona));
		if (resultado.sinPermiso()) {
			return simple(Plantillas.SIN_PERMISO, interpretacion, Resultado.SIN_PERMISO);
		}
		String enlace = personaId != null ? ENLACE_PERSONA + personaId : resultado.enlace();
		String cuerpo = Plantillas.texto(interpretacion.intencion(), personaNombre, interpretacion.clase(),
				interpretacion.reporte(), rango, zona, sede, resultado.filas(), Instant.now(clock));
		return new RespuestaDelAsistente(cuerpo, interpretacion, resultado.filas(), List.of(), enlace,
				resultado.advertencias(), Resultado.RESPONDIDA);
	}

	private ResultadoDeHerramienta ejecutar(Intencion intencion, ActorDelAsistente actor, PedidoDeHerramienta pedido) {
		HerramientaDelAsistente herramienta = herramientas.get(intencion);
		if (herramienta == null) {
			throw new IllegalStateException("No hay herramienta para " + intencion);
		}
		try {
			return herramienta.ejecutar(actor, pedido);
		}
		catch (AccessDeniedException e) {
			// La herramienta deberia devolver denegado(); por si un servicio lo lanza igual.
			return ResultadoDeHerramienta.denegado();
		}
	}

	private static PedidoDeHerramienta pedido(Interpretacion i, Long personaId, String texto, String pregunta,
			RangoDeDias rango, ZoneId zona) {
		return new PedidoDeHerramienta(personaId, texto, pregunta, rango.desde(), rango.hasta(),
				rango.desde().atStartOfDay(zona).toInstant(), rango.hasta().plusDays(1).atStartOfDay(zona).toInstant(),
				zona, i.reporte());
	}

	private static RespuestaDelAsistente aclaracion(Interpretacion interpretacion, ResultadoDeHerramienta candidatos) {
		List<OpcionDePersona> opciones = candidatos.filas().stream()
				.limit(MAX_OPCIONES)
				.map(f -> new OpcionDePersona(((Number) f.get(ClavesDeFila.PERSONA_ID)).longValue(),
						f.get(ClavesDeFila.NOMBRE) + (f.get(ClavesDeFila.DOCUMENTO) == null ? ""
								: " · " + f.get(ClavesDeFila.DOCUMENTO))))
				.toList();
		return new RespuestaDelAsistente(Plantillas.variasPersonas(interpretacion.persona()), interpretacion,
				List.of(), opciones, null, List.of(), Resultado.ACLARACION);
	}

	private static RespuestaDelAsistente simple(String texto, Interpretacion interpretacion, Resultado resultado) {
		return new RespuestaDelAsistente(texto, interpretacion, List.of(), List.of(), null, List.of(), resultado);
	}

	/**
	 * Sin periodo, el default de cada intencion: la semana para la asistencia, los ultimos siete dias
	 * para sesiones y clases (lo reciente, aunque la semana haya empezado hoy), el dia para la agenda
	 * y el mes para los reportes. Un dia de la semana va hacia adelante solo en la agenda.
	 */
	static RangoDeDias rango(Interpretacion interpretacion, String pregunta, LocalDate hoy) {
		boolean adelante = interpretacion.intencion() == Intencion.AGENDA_DEL_DIA;
		return ResolutorDePeriodo.resolver(interpretacion.periodo(), pregunta, hoy, adelante)
				.orElseGet(() -> switch (interpretacion.intencion()) {
					case ASISTENCIA_PERSONA -> ResolutorDePeriodo.resolver("ESTA_SEMANA", null, hoy, false).orElseThrow();
					case SESION_DE_PERSONA, ASISTENTES_CLASE -> new RangoDeDias(hoy.minusDays(6), hoy);
					case REPORTE -> ResolutorDePeriodo.resolver("ESTE_MES", null, hoy, false).orElseThrow();
					default -> RangoDeDias.delDia(hoy);
				});
	}

	private void auditar(long organizationId, long consultorioId, long accountId, RespuestaDelAsistente respuesta,
			RangoDeDias rango) {
		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("intencion", respuesta.interpretacion().intencion().name());
		detalles.put("resultado", respuesta.resultado().name());
		detalles.put("desde", rango.desde().toString());
		detalles.put("hasta", rango.hasta().toString());
		detalles.put("filas", String.valueOf(respuesta.filas().size()));
		if (respuesta.interpretacion().reporte() != null) {
			detalles.put("reporte", respuesta.interpretacion().reporte());
		}
		if (respuesta.enlace() != null && respuesta.enlace().startsWith(ENLACE_PERSONA)) {
			detalles.put("personaId", respuesta.enlace().substring(ENLACE_PERSONA.length()));
		}
		auditoria.registrar(organizationId, consultorioId, accountId, detalles);
		log.debug("Consulta del asistente: intencion={} resultado={} filas={}", detalles.get("intencion"),
				detalles.get("resultado"), detalles.get("filas"));
	}
}
