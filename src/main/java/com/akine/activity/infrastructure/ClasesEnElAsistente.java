package com.akine.activity.infrastructure;

import com.akine.activity.application.AsistenciaService;
import com.akine.activity.application.ClaseService;
import com.akine.activity.application.ClaseView;
import com.akine.activity.application.DetalleOperativoView;
import com.akine.activity.application.OperatingActor;
import com.akine.activity.application.ParticipanteOperativoView;
import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ClavesDeFila;
import com.akine.assistant.spi.HerramientaDelAsistente;
import com.akine.assistant.spi.Intencion;
import com.akine.assistant.spi.PedidoDeHerramienta;
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
 * "¿Quienes vinieron a yoga?" Lista las clases de la sede en el periodo con
 * {@link ClaseService#listar} y sus participantes con {@link AsistenciaService#detalleOperativo},
 * que exige {@code inscripcion:read}: lleva nombres, nunca un dato clinico ni uno economico.
 *
 * <p><b>El titulo se resuelve contra las clases reales</b> (medicion del 15/11): el modelo acorta
 * "yoga para embarazadas" a "yoga". Se toman las clases cuyo titulo contiene lo que entendio el
 * modelo y, entre esas, si la pregunta nombra un titulo completo mas largo que eso, gana el mas
 * largo. Asi "yoga para embarazadas" no trae tambien "yoga" a secas, y "yoga" trae las dos si las
 * dos existen.
 *
 * <p>Se pide el detalle de a lo sumo {@link #MAX_CLASES} clases, las mismas que la respuesta muestra:
 * cada detalle es una lectura propia con su control de permiso.
 */
@Component
class ClasesEnElAsistente implements HerramientaDelAsistente {

	static final int MAX_CLASES = 3;
	static final int MAX_PARTICIPANTES = 200;
	static final String ENLACE = "/asistencia";
	private static final Pattern ESPACIOS = Pattern.compile("\\s+");

	private final ClaseService clases;
	private final AsistenciaService asistencias;

	ClasesEnElAsistente(ClaseService clases, AsistenciaService asistencias) {
		this.clases = clases;
		this.asistencias = asistencias;
	}

	@Override
	public Intencion intencion() {
		return Intencion.ASISTENTES_CLASE;
	}

	@Override
	public ResultadoDeHerramienta ejecutar(ActorDelAsistente actor, PedidoDeHerramienta pedido) {
		OperatingActor operador = new OperatingActor(actor.accountId(), actor.platformAdmin(),
				actor.organizationId(), actor.consultorioId());
		List<ClaseView> candidatas = elegir(
				clases.listar(operador, actor.consultorioId(), pedido.desdeInstante(), pedido.hastaInstante()),
				pedido.texto(), pedido.pregunta(), Instant.now());
		List<Map<String, Object>> filas = new ArrayList<>();
		for (ClaseView clase : candidatas) {
			DetalleOperativoView detalle = asistencias.detalleOperativo(operador, actor.consultorioId(),
					clase.id(), 0, MAX_PARTICIPANTES);
			if (detalle.contenido().isEmpty()) {
				filas.add(fila(clase, null));
			}
			detalle.contenido().forEach(p -> filas.add(fila(clase, p)));
		}
		return new ResultadoDeHerramienta(filas, List.of(), ENLACE, false);
	}

	/**
	 * Las clases que coinciden, ya empezadas primero y de la mas reciente a la mas vieja: "¿quienes
	 * vinieron?" pregunta por lo que ya paso.
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
				.filter(c -> c.titulo() != null)
				.map(c -> new Candidata(c, normal(c.titulo())))
				.filter(c -> c.titulo().contains(buscado))
				.toList();
		int masLargo = coinciden.stream()
				.map(Candidata::titulo)
				.filter(enLaPregunta::contains)
				.mapToInt(String::length)
				.max()
				.orElse(-1);
		// Solo se acota si la pregunta nombra un titulo MAS LARGO que lo que entendio el modelo: es el
		// caso en que el modelo lo acorto. Si nombra exactamente "yoga", vienen todas las de yoga.
		return coinciden.stream()
				.filter(c -> masLargo <= buscado.length()
						|| (c.titulo().length() == masLargo && enLaPregunta.contains(c.titulo())))
				.map(Candidata::clase)
				.sorted(Comparator.comparing((ClaseView c) -> c.inicio().isAfter(ahora))
						.thenComparing(ClaseView::inicio, Comparator.reverseOrder()))
				.limit(MAX_CLASES)
				.toList();
	}

	private static Map<String, Object> fila(ClaseView clase, ParticipanteOperativoView participante) {
		Map<String, Object> fila = new LinkedHashMap<>();
		fila.put(ClavesDeFila.CLASE_ID, clase.id());
		fila.put(ClavesDeFila.CLASE, clase.titulo());
		fila.put(ClavesDeFila.INICIO, clase.inicio());
		fila.put(ClavesDeFila.ESTADO_CLASE, clase.estado());
		fila.put(ClavesDeFila.PARTICIPANTE, participante == null ? null
				: participante.apellido() + ", " + participante.nombre());
		fila.put(ClavesDeFila.RESULTADO, participante == null || participante.asistencia() == null ? null
				: participante.asistencia().resultado());
		return fila;
	}

	/** Plegado de la plataforma (sin acentos, minusculas) con los espacios colapsados. */
	static String normal(String texto) {
		return ESPACIOS.matcher(TextoDeBusqueda.plegar(texto)).replaceAll(" ").strip();
	}
}
