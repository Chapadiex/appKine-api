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
import com.akine.platform.spi.listado.TextoDeBusqueda;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@code clases}: clases de la sede en un periodo y, si son pocas, quienes asistieron. Misma resolucion de
 * titulo contra las clases reales (ver {@link #elegir}); exige {@code inscripcion:read}.
 */
@Component
class ClasesParaElAgente implements HerramientaDelAgente {

	static final int TOPE = 50;
	/** Hasta cuantas clases se pide el detalle: cada detalle es una lectura propia con su permiso. */
	static final int MAX_CLASES = 3;
	static final int MAX_PARTICIPANTES = 200;
	static final String ENLACE = "/asistencia";
	private static final Pattern ESPACIOS = Pattern.compile("\\s+");
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
				: elegir(delPeriodo, actividad, actividad, Instant.now());
		List<String> advertencias = new ArrayList<>();
		List<Map<String, Object>> filas = new ArrayList<>();
		if (candidatas.size() <= MAX_CLASES) {
			for (ClaseView clase : candidatas) {
				DetalleOperativoView detalle = asistencias.detalleOperativo(operador, actor.consultorioId(),
						clase.id(), 0, MAX_PARTICIPANTES);
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
		return new ResultadoDeHerramienta(filas, advertencias, ENLACE, false);
	}

	private static Map<String, Object> fila(ClaseView clase, ParticipanteOperativoView participante) {
		Map<String, Object> fila = new LinkedHashMap<>();
		fila.put("clase_id", clase.id());
		fila.put("clase", nombre(clase));
		fila.put("inicio", clase.inicio());
		fila.put("estado_clase", clase.estado());
		if (participante != null) {
			fila.put("participante", participante.apellido() + ", " + participante.nombre());
			fila.put("resultado", participante.asistencia() == null ? null : participante.asistencia().resultado());
		}
		return fila;
	}

	/**
	 * Las clases cuyo titulo contiene {@code texto}, ya empezadas primero y de la mas reciente a la mas
	 * vieja. Si {@code pregunta} nombra un titulo completo MAS LARGO que {@code texto} (el modelo lo
	 * acorto: "yoga para embarazadas" a "yoga"), gana el mas largo; si nombra exactamente "yoga",
	 * vienen todas las de yoga.
	 */
	static List<ClaseView> elegir(List<ClaseView> enElPeriodo, String texto, String pregunta, Instant ahora) {
		if (texto == null || texto.isBlank()) {
			return List.of();
		}
		String buscado = normal(texto);
		String enLaPregunta = normal(pregunta == null ? "" : pregunta);
		record Candidata(ClaseView clase, String titulo) {
		}
		List<Candidata> coinciden = enElPeriodo.stream()
				.filter(c -> nombre(c) != null)
				.map(c -> new Candidata(c, normal(nombre(c))))
				.filter(c -> c.titulo().contains(buscado))
				.toList();
		int masLargo = coinciden.stream()
				.map(Candidata::titulo)
				.filter(enLaPregunta::contains)
				.mapToInt(String::length)
				.max()
				.orElse(-1);
		return coinciden.stream()
				.filter(c -> masLargo <= buscado.length()
						|| (c.titulo().length() == masLargo && enLaPregunta.contains(c.titulo())))
				.map(Candidata::clase)
				.sorted(Comparator.comparing((ClaseView c) -> c.inicio().isAfter(ahora))
						.thenComparing(ClaseView::inicio, Comparator.reverseOrder()))
				.limit(MAX_CLASES)
				.toList();
	}

	/** Su titulo, o el de la oferta si no tiene uno propio (el titulo es opcional al programar). */
	static String nombre(ClaseView clase) {
		return clase.titulo() != null && !clase.titulo().isBlank() ? clase.titulo() : clase.ofertaNombre();
	}

	/** Plegado de la plataforma (sin acentos, minusculas) con los espacios colapsados. */
	static String normal(String texto) {
		return ESPACIOS.matcher(TextoDeBusqueda.plegar(texto)).replaceAll(" ").strip();
	}
}
