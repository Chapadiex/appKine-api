package com.akine.activity.infrastructure;

import com.akine.activity.application.AsistenciaService;
import com.akine.activity.application.ClaseService;
import com.akine.activity.application.ClaseView;
import com.akine.activity.application.DetalleOperativoView;
import com.akine.activity.application.OperatingActor;
import com.akine.activity.application.ParticipanteOperativoView;
import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ContextoDeConsulta;
import com.akine.assistant.spi.EntradaDeHerramienta;
import com.akine.assistant.spi.HerramientaDelAgente;
import com.akine.assistant.spi.Periodo;
import com.akine.assistant.spi.ResultadoDeHerramienta;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code clases}: clases de la sede en un periodo y, si son pocas, quienes asistieron. Misma resolucion de
 * titulo y mismo servicio que {@link ClasesEnElAsistente}; exige {@code inscripcion:read}.
 */
@Component
class ClasesParaElAgente implements HerramientaDelAgente {

	static final int TOPE = 50;
	private static final String FECHA = "{\"type\":\"string\",\"description\":\"%s (YYYY-MM-DD o hoy, ayer, "
			+ "esta_semana, semana_pasada, este_mes, mes_pasado)\"}";

	private final ClaseService clases;
	private final AsistenciaService asistencias;

	ClasesParaElAgente(ClaseService clases, AsistenciaService asistencias) {
		this.clases = clases;
		this.asistencias = asistencias;
	}

	@Override
	public String nombre() {
		return "clases";
	}

	@Override
	public String descripcion() {
		return "Lista las clases grupales de la sede activa en un periodo (por defecto hoy), opcionalmente de una "
				+ "actividad (por ejemplo yoga). Si coinciden hasta 3 clases, incluye sus participantes y su "
				+ "asistencia (presente, tarde, ausente). No da datos clinicos ni de cobro.";
	}

	@Override
	public String esquemaEntrada() {
		return "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
				+ "\"actividad\":{\"type\":\"string\",\"description\":\"Titulo o parte del titulo de la clase\"},"
				+ "\"desde\":" + FECHA.formatted("Primer dia del periodo") + ","
				+ "\"hasta\":" + FECHA.formatted("Ultimo dia del periodo, inclusive") + "}}";
	}

	@Override
	public ResultadoDeHerramienta ejecutar(ActorDelAsistente actor, Map<String, Object> entrada,
			ContextoDeConsulta ctx) {
		Periodo periodo = Periodo.resolver(entrada.get("desde"), entrada.get("hasta"), ctx,
				Periodo.delDia(ctx.hoy()));
		String actividad = EntradaDeHerramienta.texto(entrada, "actividad");
		OperatingActor operador = new OperatingActor(actor.accountId(), actor.platformAdmin(),
				actor.organizationId(), actor.consultorioId());
		List<ClaseView> delPeriodo = clases.listar(operador, actor.consultorioId(),
				periodo.desdeInstante(ctx.zona()), periodo.hastaInstante(ctx.zona()));
		List<ClaseView> candidatas = actividad == null
				? delPeriodo.stream().sorted(Comparator.comparing(ClaseView::inicio)).toList()
				: ClasesEnElAsistente.elegir(delPeriodo, actividad, actividad, Instant.now());
		List<String> advertencias = new ArrayList<>();
		List<Map<String, Object>> filas = new ArrayList<>();
		if (candidatas.size() <= ClasesEnElAsistente.MAX_CLASES) {
			for (ClaseView clase : candidatas) {
				DetalleOperativoView detalle = asistencias.detalleOperativo(operador, actor.consultorioId(),
						clase.id(), 0, ClasesEnElAsistente.MAX_PARTICIPANTES);
				if (detalle.contenido().isEmpty()) {
					filas.add(fila(clase, null));
				}
				detalle.contenido().forEach(p -> filas.add(fila(clase, p)));
			}
		}
		else {
			candidatas.stream().limit(TOPE).forEach(c -> filas.add(fila(c, null)));
			advertencias.add("Hay " + candidatas.size() + " clases; no incluyo participantes. "
					+ "Pedí una actividad o un periodo más acotado para ver quiénes asistieron.");
		}
		if (filas.size() > TOPE) {
			advertencias.add("Hay " + filas.size() + " resultados; muestro los primeros " + TOPE);
			filas.subList(TOPE, filas.size()).clear();
		}
		return new ResultadoDeHerramienta(filas, advertencias, ClasesEnElAsistente.ENLACE, false);
	}

	private static Map<String, Object> fila(ClaseView clase, ParticipanteOperativoView participante) {
		Map<String, Object> fila = new LinkedHashMap<>();
		fila.put("clase_id", clase.id());
		fila.put("clase", ClasesEnElAsistente.nombre(clase));
		fila.put("inicio", clase.inicio());
		fila.put("estado_clase", clase.estado());
		if (participante != null) {
			fila.put("participante", participante.apellido() + ", " + participante.nombre());
			fila.put("resultado", participante.asistencia() == null ? null : participante.asistencia().resultado());
		}
		return fila;
	}
}
