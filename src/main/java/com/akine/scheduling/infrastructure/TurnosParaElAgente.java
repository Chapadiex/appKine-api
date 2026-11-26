package com.akine.scheduling.infrastructure;

import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ContextoDeConsulta;
import com.akine.assistant.spi.EntradaDeHerramienta;
import com.akine.assistant.spi.HerramientaDelAgente;
import com.akine.assistant.spi.Periodo;
import com.akine.assistant.spi.ResultadoDeHerramienta;
import com.akine.person.spi.PacienteDirectory;
import com.akine.scheduling.application.FiltroDeTurnos;
import com.akine.scheduling.application.OperatingActor;
import com.akine.scheduling.application.RecepcionService;
import com.akine.scheduling.application.TurnoDeCalendarioView;
import com.akine.scheduling.application.TurnoDelDiaView;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Herramientas de turnos del agente. Leen con {@link RecepcionService#delRango}, que exige
 * {@code turno:read} y aplica el alcance del actor. El estado de cada fila es el estado operativo.
 */
abstract class TurnosParaElAgente implements HerramientaDelAgente {

	static final int TOPE = 50;
	static final List<String> ESTADOS = List.of("RESERVADO", "CONFIRMADO", "PRESENTE", "EN_ATENCION", "ATENDIDO",
			"AUSENTE", "RETIRADO", "CANCELADO");
	/** {@code vinieron}: quien llego, este o no atendido todavia. */
	static final Set<String> VINIERON = Set.of("PRESENTE", "EN_ATENCION", "ATENDIDO");
	static final String NO_ENCONTRADA = "No encontré esa persona";
	static final String ENLACE = "/agenda/recepcion";

	private final RecepcionService recepcion;
	private final PacienteDirectory pacientes;

	TurnosParaElAgente(RecepcionService recepcion, PacienteDirectory pacientes) {
		this.recepcion = recepcion;
		this.pacientes = pacientes;
	}

	ResultadoDeHerramienta leer(ActorDelAsistente actor, Periodo periodo, Long personaId, String estado) {
		return leer(actor, periodo, personaId, estado == null ? Set.of() : Set.of(estado));
	}

	ResultadoDeHerramienta leer(ActorDelAsistente actor, Periodo periodo, Long personaId, Set<String> estados) {
		if (personaId != null && pacientes.find(actor.organizationId(), personaId).isEmpty()) {
			return new ResultadoDeHerramienta(List.of(), List.of(NO_ENCONTRADA), ENLACE, false);
		}
		OperatingActor operador = new OperatingActor(actor.accountId(), actor.platformAdmin(),
				actor.organizationId(), actor.consultorioId(), actor.roleCode());
		FiltroDeTurnos filtro = FiltroDeTurnos.de(estados, personaId);
		// delRango toma [desde, hasta).
		List<TurnoDeCalendarioView> turnos = recepcion.delRango(operador, actor.consultorioId(), periodo.desde(),
				periodo.hastaExclusivo(), null, null, true, filtro).turnos().stream()
				.sorted(Comparator.comparing(t -> t.turno().inicio()))
				.toList();
		List<String> advertencias = new ArrayList<>();
		if (turnos.size() > TOPE) {
			advertencias.add("Hay " + turnos.size() + " resultados; muestro los primeros " + TOPE);
		}
		List<Map<String, Object>> filas = turnos.stream().limit(TOPE).map(TurnosParaElAgente::fila).toList();
		return new ResultadoDeHerramienta(filas, advertencias, ENLACE, false);
	}

	static Map<String, Object> fila(TurnoDeCalendarioView calendario) {
		TurnoDelDiaView turno = calendario.turno();
		Map<String, Object> fila = new LinkedHashMap<>();
		fila.put("turno_id", turno.id());
		fila.put("persona_id", turno.personaId());
		fila.put("paciente", turno.personaNombre());
		fila.put("inicio", turno.inicio());
		fila.put("estado", turno.operativo() == null ? turno.estado() : turno.operativo().estadoOperativo());
		fila.put("servicio", turno.ofertaNombre());
		fila.put("profesional", calendario.profesionalNombre());
		fila.put("sesion_id", turno.operativo() == null ? null : turno.operativo().sesionId());
		return fila;
	}

	static String fecha(String descripcion) {
		return "{\"type\":\"string\",\"description\":\"" + descripcion
				+ " (YYYY-MM-DD o hoy, ayer, esta_semana, semana_pasada, este_mes, mes_pasado)\"}";
	}

	@Component
	static class Turnos extends TurnosParaElAgente {

		Turnos(RecepcionService recepcion, PacienteDirectory pacientes) {
			super(recepcion, pacientes);
		}

		@Override
		public String nombre() {
			return "turnos";
		}

		@Override
		public String descripcion() {
			return "Lista los turnos de la sede activa en un periodo (por defecto hoy), con su estado operativo, "
					+ "paciente, servicio y profesional; opcionalmente de una persona (persona_id) o en un estado. "
					+ "Para \"¿quiénes vinieron?\" o \"¿a quiénes se atendió?\" usá vinieron=true (PRESENTE, "
					+ "EN_ATENCION y ATENDIDO) o estado=ATENDIDO; para quienes faltaron, la herramienta ausentes. "
					+ "Un turno no prueba que hubo prestacion y no incluye atenciones sin turno. No da datos clinicos.";
		}

		@Override
		public String esquemaEntrada() {
			return "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
					+ "\"persona_id\":{\"type\":\"integer\",\"description\":\"Solo los turnos de esta persona\"},"
					+ "\"desde\":" + fecha("Primer dia del periodo") + ","
					+ "\"hasta\":" + fecha("Ultimo dia del periodo, inclusive") + ","
					+ "\"estado\":{\"type\":\"string\",\"enum\":[\"RESERVADO\",\"CONFIRMADO\",\"PRESENTE\","
					+ "\"EN_ATENCION\",\"ATENDIDO\",\"AUSENTE\",\"RETIRADO\",\"CANCELADO\"],"
					+ "\"description\":\"Filtra por estado operativo\"},"
					+ "\"vinieron\":{\"type\":\"boolean\",\"description\":\"true: solo los que llegaron "
					+ "(PRESENTE, EN_ATENCION o ATENDIDO); no se combina con estado\"}}}";
		}

		@Override
		public ResultadoDeHerramienta ejecutar(ActorDelAsistente actor, Map<String, Object> entrada,
				ContextoDeConsulta ctx) {
			Periodo periodo = Periodo.resolver(entrada.get("desde"), entrada.get("hasta"), ctx,
					Periodo.delDia(ctx.hoy()));
			String estado = EntradaDeHerramienta.texto(entrada, "estado");
			if (estado != null) {
				estado = estado.toUpperCase(Locale.ROOT);
				if (!ESTADOS.contains(estado)) {
					throw new IllegalArgumentException("Estado no valido: '" + estado + "'. Usá uno de "
							+ String.join(", ", ESTADOS));
				}
			}
			Long personaId = EntradaDeHerramienta.id(entrada, "persona_id");
			if (Boolean.TRUE.equals(entrada.get("vinieron"))) {
				if (estado != null) {
					throw new IllegalArgumentException("Usá vinieron o estado, no los dos");
				}
				return leer(actor, periodo, personaId, VINIERON);
			}
			return leer(actor, periodo, personaId, estado);
		}
	}

	@Component
	static class Ausentes extends TurnosParaElAgente {

		Ausentes(RecepcionService recepcion, PacienteDirectory pacientes) {
			super(recepcion, pacientes);
		}

		@Override
		public String nombre() {
			return "ausentes";
		}

		@Override
		public String descripcion() {
			return "Lista SOLO quienes faltaron (turnos AUSENTE) de la sede activa en una fecha (por defecto hoy). "
					+ "No incluye cancelados ni retirados. No sirve para saber quiénes vinieron o fueron "
					+ "atendidos: para eso usá turnos con vinieron=true.";
		}

		@Override
		public String esquemaEntrada() {
			return "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
					+ "\"fecha\":" + fecha("Dia a consultar") + "}}";
		}

		@Override
		public ResultadoDeHerramienta ejecutar(ActorDelAsistente actor, Map<String, Object> entrada,
				ContextoDeConsulta ctx) {
			Periodo periodo = Periodo.resolver(entrada.get("fecha"), null, ctx, Periodo.delDia(ctx.hoy()));
			return leer(actor, periodo, null, "AUSENTE");
		}
	}
}
