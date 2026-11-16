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
import com.akine.organization.spi.PermissionEvaluator;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.organization.spi.ZonaDeSede;
import com.akine.platform.spi.listado.TextoDeBusqueda;
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
	private final PermissionEvaluator permisos;
	private final ConsultaLibre consultaLibre;
	private final Clock clock;

	/** El de Spring. El otro fija el reloj para los tests; con dos, Spring necesita que se le diga cual. */
	@Autowired
	public AsistenteService(AsistenteAjustes ajustes, PermissionGuard permissionGuard, ConsultorioDirectory sedes,
			Interpretador interpretador, AuditoriaDelAsistente auditoria, List<HerramientaDelAsistente> herramientas,
			PermissionEvaluator permisos, ConsultaLibre consultaLibre) {
		this(ajustes, permissionGuard, sedes, interpretador, auditoria, herramientas, permisos, consultaLibre,
				Clock.systemUTC());
	}

	AsistenteService(AsistenteAjustes ajustes, PermissionGuard permissionGuard, ConsultorioDirectory sedes,
			Interpretador interpretador, AuditoriaDelAsistente auditoria, List<HerramientaDelAsistente> herramientas,
			PermissionEvaluator permisos, ConsultaLibre consultaLibre, Clock clock) {
		this.permisos = permisos;
		this.consultaLibre = consultaLibre;
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
		RespuestaDelAsistente respuesta;
		RangoDeDias rango;
		try {
			rango = rango(interpretacion, pregunta, LocalDate.now(clock.withZone(zona)));
			respuesta = responder(enLaSede, interpretacion, pregunta, historial == null ? List.of() : historial,
					rango, zona, sede.name(), personaId);
		}
		catch (RuntimeException e) {
			// La consulta pudo haber leido datos antes de fallar (la busqueda de persona): se audita igual.
			auditoria.registrar(organizationId, consultorioId, actor.accountId(), Map.of(
					"intencion", interpretacion.intencion().name(),
					"resultado", "ERROR",
					"error", e.getClass().getSimpleName()));
			throw e;
		}
		auditar(organizationId, consultorioId, actor.accountId(), respuesta, rango);
		return respuesta;
	}

	@SuppressWarnings("unchecked")
	private RespuestaDelAsistente responder(ActorDelAsistente actor, Interpretacion interpretacion, String pregunta,
			List<TurnoPrevio> historial, RangoDeDias rango, ZoneId zona, String sede, Long personaElegida) {
		if (interpretacion.persona() == null && !interpretacion.usaClase() && !interpretacion.usaReporte()
				&& !pideCambio(pregunta) && esContinuacion(pregunta)) {
			// "¿tiene turno?" sin decir de quien: es de la persona de la pregunta anterior. Si no hay
			// ninguna (o la anterior encontro varias), se repregunta en vez de adivinar.
			String previa = historial.isEmpty() ? null : historial.getLast().interpretacion().persona();
			if (previa == null) {
				return simple(Plantillas.FALTA_PERSONA, interpretacion, Resultado.FALTA_PERSONA);
			}
			if (interpretacion.usaPersona()) {
				interpretacion = new Interpretacion(interpretacion.intencion(), previa, null, interpretacion.periodo(),
						null);
			}
			else if (puedeConsultaLibre(actor)) {
				return consultaLibre(actor, pregunta, historial, previa, zona);
			}
		}
		if (interpretacion.fueraDeAlcance() || !herramientas.containsKey(interpretacion.intencion())) {
			if (!pideCambio(pregunta) && puedeConsultaLibre(actor)) {
				return consultaLibre(actor, pregunta, historial, null, zona);
			}
			return simple(Plantillas.AYUDA, Interpretacion.FUERA_DE_ALCANCE, Resultado.FUERA_DE_ALCANCE);
		}
		if (interpretacion.intencion() == Intencion.REPORTE && pideDetalle(pregunta) && puedeConsultaLibre(actor)) {
			return consultaLibre(actor, pregunta, historial, null, zona);
		}

		Map<String, Object> persona = null;
		if (interpretacion.usaPersona()) {
			Object resuelta = resolverPersona(actor, interpretacion, pregunta, rango, zona, personaElegida);
			if (resuelta instanceof RespuestaDelAsistente corte) {
				return corte;
			}
			persona = (Map<String, Object>) resuelta;
		}
		Long personaId = persona == null ? null : idDe(persona);
		String personaNombre = persona == null ? null : String.valueOf(persona.get(ClavesDeFila.NOMBRE));

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

	private static final java.util.regex.Pattern PIDE_DETALLE = java.util.regex.Pattern.compile(
			"\\b(quien|quienes|cual|cuales|que profesional|que obra|obras? sociales?|cobertura|pacientes|padron"
					+ "|ranking|lista|listado|listame|por profesional|por servicio|mas|menos)\\b");

	/**
	 * Un reporte da los totales de un periodo. "¿Quién nos debe?", "¿qué profesional tiene más turnos?"
	 * o "¿cuántos pacientes tenemos?" el modelo las clasifica REPORTE y el reporte no las responde: con
	 * consultas libres van al SQL. Regla en el backend y no en el prompt: cada ejemplo nuevo en el prompt
	 * del modelo de 4B movio otras preguntas medidas (docs/diseno/AKINE-asistente-sql.md, §6).
	 */
	static boolean pideDetalle(String pregunta) {
		return pregunta != null && PIDE_DETALLE.matcher(TextoDeBusqueda.plegar(pregunta)).find();
	}

	private static final java.util.regex.Pattern HABLA_DE_ALGUIEN = java.util.regex.Pattern.compile(
			"\\b(tiene|tenia|tuvo|vino|viene|vendra|falto|falta|debe|debia|pago|esta|estuvo|atendio|atendieron"
					+ "|ella|el|su|sus|le|lo)\\b");

	private static final java.util.regex.Pattern NOMBRA_OTRA_COSA = java.util.regex.Pattern.compile(
			"\\b(quien|quienes|pacientes|personas|todos|todas|profesional|profesionales|clase|clases|sede|centro"
					+ "|caja|reporte)\\b");

	/**
	 * "¿tiene turno?", "¿vino?", "¿cuánto debe?": corta, en singular y sin nombrar a nadie ni a un grupo.
	 * Una pregunta que trae su propio sujeto ("¿quiénes vienen hoy?") no es continuación.
	 */
	static boolean esContinuacion(String pregunta) {
		if (pregunta == null) {
			return false;
		}
		String plegada = TextoDeBusqueda.plegar(pregunta);
		return plegada.split("\\s+").length <= 6 && HABLA_DE_ALGUIEN.matcher(plegada).find()
				&& !NOMBRA_OTRA_COSA.matcher(plegada).find() && !tieneNombrePropio(pregunta);
	}

	/** Una palabra con mayuscula que no abre la oracion: "¿vino Lucia?" nombra a alguien. */
	private static boolean tieneNombrePropio(String pregunta) {
		String[] palabras = pregunta.replaceAll("[¿?¡!.,]", " ").strip().split("\\s+");
		for (int i = 1; i < palabras.length; i++) {
			if (!palabras[i].isEmpty() && Character.isUpperCase(palabras[i].charAt(0))) {
				return true;
			}
		}
		return false;
	}

	private static final java.util.regex.Pattern PIDE_CAMBIO = java.util.regex.Pattern.compile(
			"\\b(borra|borrar|elimina|eliminar|crea|crear|reserva|reservar|cancela|cancelar|cobra|cobrar"
					+ "|registra|registrar|modifica|modificar|cambia|cambiar|agrega|agregar|anota|anotar|actualiza"
					+ "|actualizar|carga|cargar|ignora|ignorar|olvida|olvidar|instrucciones)\\b");

	/**
	 * Un pedido de crear, borrar o modificar, o de cambiar las instrucciones, no es una consulta: no va al
	 * SQL aunque el modelo lo deje fuera de alcance (el SELECT que inventaria responderia otra cosa).
	 */
	static boolean pideCambio(String pregunta) {
		return pregunta != null && PIDE_CAMBIO.matcher(TextoDeBusqueda.plegar(pregunta)).find();
	}

	/** Interruptor prendido y {@code asistente:sql} en la sede. Sin el permiso, la ayuda de siempre. */
	private boolean puedeConsultaLibre(ActorDelAsistente actor) {
		return ajustes.sql() && consultaLibre != null && permisos.evaluate(new PermissionQuery(actor.accountId(),
				PermissionCodes.ASISTENTE_SQL, actor.organizationId(), actor.consultorioId(), null,
				Instant.now(clock))).granted();
	}

	/**
	 * Respaldo: el modelo escribe un SELECT sobre {@code asistente_sql}. El texto lo arma la plantilla
	 * con las filas; el modelo no las ve.
	 */
	private RespuestaDelAsistente consultaLibre(ActorDelAsistente actor, String pregunta, List<TurnoPrevio> historial,
			String personaConocida, ZoneId zona) {
		List<String> anteriores = historial.stream().map(TurnoPrevio::pregunta).toList();
		ConsultaLibre.Resultado r = consultaLibre.responder(pregunta, anteriores, personaConocida,
				LocalDate.now(clock.withZone(zona)), actor.organizationId(), actor.consultorioId());
		if (!r.respondida()) {
			return new RespuestaDelAsistente(Plantillas.CONSULTA_FALLIDA, Interpretacion.FUERA_DE_ALCANCE, List.of(),
					List.of(), null, List.of(), Resultado.FUERA_DE_ALCANCE, r.sql());
		}
		List<String> personas = personasEn(r.filas());
		if (personaConocida != null && personas.size() > 1) {
			// La continuacion trajo a mas de una persona con ese nombre: se pregunta de cual se habla.
			return simple(Plantillas.variasPersonas(personas), Interpretacion.FUERA_DE_ALCANCE,
					Resultado.FALTA_PERSONA);
		}
		// Queda anotada la persona de la que se hablo, para que la proxima pregunta la siga.
		String persona = personaConocida != null ? personaConocida : personas.size() == 1 ? personas.getFirst() : null;
		Interpretacion interpretacion = new Interpretacion(Intencion.FUERA_DE_ALCANCE, persona, null, null, null);
		List<String> advertencias = r.truncado()
				? List.of("Hay más resultados de los que se muestran: precisá la pregunta para verlos todos.")
				: List.of();
		String texto = r.filas().isEmpty() && personaConocida != null
				? "No encontré datos de " + personaConocida + " para esa pregunta."
				: Plantillas.consultaLibre(r.filas(), r.truncado());
		return new RespuestaDelAsistente(texto, interpretacion, r.filas(),
				List.of(), null, advertencias, Resultado.RESPONDIDA, r.sql());
	}

	/** "Nombre Apellido" distintos entre las filas que traen las dos columnas, en orden de aparicion. */
	static List<String> personasEn(List<Map<String, Object>> filas) {
		return filas.stream()
				.filter(f -> f.get("nombre") != null && f.get("apellido") != null)
				.map(f -> f.get("nombre") + " " + f.get("apellido"))
				.distinct()
				.toList();
	}

	/**
	 * La fila de la persona de la que habla la pregunta, o la respuesta que corta antes (falta el
	 * nombre, sin permiso, no esta, o hay varias y hay que elegir). Con una opcion elegida se toma
	 * esa, pero solo si esta entre los candidatos del mismo nombre: un id cualquiera mandado por el
	 * cliente no se usa a ciegas.
	 */
	private Object resolverPersona(ActorDelAsistente actor, Interpretacion interpretacion, String pregunta,
			RangoDeDias rango, ZoneId zona, Long personaElegida) {
		if (interpretacion.persona() == null) {
			return simple(Plantillas.FALTA_PERSONA, interpretacion, Resultado.FALTA_PERSONA);
		}
		ResultadoDeHerramienta candidatos = ejecutar(Intencion.BUSCAR_PERSONA, actor,
				pedido(interpretacion, null, interpretacion.persona(), pregunta, rango, zona));
		if (candidatos.sinPermiso()) {
			return simple(Plantillas.SIN_PERMISO, interpretacion, Resultado.SIN_PERMISO);
		}
		List<Map<String, Object>> filas = candidatos.filas();
		Map<String, Object> elegida = personaElegida == null
				? (filas.size() == 1 ? filas.getFirst() : null)
				: filas.stream().filter(f -> idDe(f) == personaElegida).findFirst().orElse(null);
		if (elegida != null) {
			return elegida;
		}
		if (filas.isEmpty() || personaElegida != null) {
			return simple(Plantillas.personaNoEncontrada(interpretacion.persona()), interpretacion,
					Resultado.PERSONA_NO_ENCONTRADA);
		}
		return aclaracion(interpretacion, candidatos);
	}

	private static long idDe(Map<String, Object> fila) {
		return ((Number) fila.get(ClavesDeFila.PERSONA_ID)).longValue();
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
			// El unico lugar donde un rechazo del servicio duenio se vuelve "sin permiso" (200). No
			// hay transaccion compartida que pueda quedar marcada: cada lectura vive en la suya.
			return ResultadoDeHerramienta.denegado();
		}
	}

	private static PedidoDeHerramienta pedido(Interpretacion i, Long personaId, String texto, String pregunta,
			RangoDeDias rango, ZoneId zona) {
		return new PedidoDeHerramienta(personaId, texto, pregunta, rango.desde(), rango.hasta(), zona, i.reporte());
	}

	private static RespuestaDelAsistente aclaracion(Interpretacion interpretacion, ResultadoDeHerramienta candidatos) {
		List<OpcionDePersona> opciones = candidatos.filas().stream()
				.limit(MAX_OPCIONES)
				.map(f -> new OpcionDePersona(idDe(f),
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
		boolean adelante = interpretacion.intencion() == Intencion.AGENDA_DEL_DIA
				|| (interpretacion.intencion() == Intencion.ASISTENTES_CLASE && enFuturo(pregunta));
		return ResolutorDePeriodo.resolver(interpretacion.periodo(), pregunta, hoy, adelante)
				.orElseGet(() -> switch (interpretacion.intencion()) {
					case ASISTENCIA_PERSONA -> ResolutorDePeriodo.resolver("ESTA_SEMANA", null, hoy, false).orElseThrow();
					case SESION_DE_PERSONA, ASISTENTES_CLASE -> new RangoDeDias(hoy.minusDays(6), hoy);
					case REPORTE -> ResolutorDePeriodo.resolver("ESTE_MES", null, hoy, false).orElseThrow();
					default -> RangoDeDias.delDia(hoy);
				});
	}

	private static final java.util.regex.Pattern VERBOS_EN_FUTURO =
			java.util.regex.Pattern.compile("\\b(van|va|vienen|viene|vendran|inscript[oa]s?|anotad[oa]s?)\\b");

	/**
	 * "¿Quienes van a yoga el jueves?" pregunta por la clase que viene, no por la que paso. El prompt
	 * acepta preguntas de clase en futuro, asi que el resolutor tiene que saber hacia donde mirar.
	 */
	static boolean enFuturo(String pregunta) {
		return pregunta != null && VERBOS_EN_FUTURO.matcher(TextoDeBusqueda.plegar(pregunta)).find();
	}

	private void auditar(long organizationId, long consultorioId, long accountId, RespuestaDelAsistente respuesta,
			RangoDeDias rango) {
		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("intencion", respuesta.interpretacion().intencion().name());
		detalles.put("resultado", respuesta.resultado().name());
		detalles.put("desde", rango.desde().toString());
		detalles.put("hasta", rango.hasta().toString());
		detalles.put("filas", String.valueOf(respuesta.filas().size()));
		if (respuesta.consulta() != null) {
			// Solo que fue libre: el SELECT puede llevar el nombre de una persona.
			detalles.put("consulta", "LIBRE");
		}
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
