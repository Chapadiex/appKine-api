package com.akine.knowledge.infrastructure.manual;

import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ClavesDeFila;
import com.akine.assistant.spi.HerramientaDelAsistente;
import com.akine.assistant.spi.Intencion;
import com.akine.assistant.spi.PedidoDeHerramienta;
import com.akine.assistant.spi.ResultadoDeHerramienta;
import com.akine.knowledge.application.manual.IndiceDelManual;
import com.akine.knowledge.application.manual.PasajeEncontrado;
import com.akine.knowledge.application.manual.VisibilidadDelManual;
import com.akine.knowledge.domain.manual.FichaDelManual;
import com.akine.platform.spi.modelo.ModeloDeLenguaje;
import com.akine.platform.spi.modelo.ModeloNoDisponibleException;
import com.akine.knowledge.domain.manual.TipoDePasaje;
import java.text.Normalizer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@link Intencion#COMO_SE_HACE}: busca en el manual con el filtro de la persona, el modelo elige un
 * pasaje (o ninguno) y la fila lleva el pasaje textual. Usa el mismo modelo del asistente, y con él su
 * cupo: es una segunda llamada de la misma consulta, no otra consulta.
 */
@Component
class ComoSeHaceHerramienta implements HerramientaDelAsistente {

	/** Candidatos que ve el modelo: con mas, la lista de titulos confunde al de 4B. */
	static final int CANDIDATOS = 8;
	/** Sugerencias cuando no hay pasaje. */
	static final int SUGERENCIAS = 3;
	private static final Pattern PIDE_DEFINICION =
			Pattern.compile("\\bque (es|son|significa|significan|quiere decir)\\b|\\bdiferencia entre\\b");

	private final IndiceDelManual indice;
	private final VisibilidadDelManual visibilidad;
	private final ElectorDePasaje elector;

	@Autowired
	ComoSeHaceHerramienta(IndiceDelManual indice, VisibilidadDelManual visibilidad,
			@Qualifier("modeloAsistente") ObjectProvider<ModeloDeLenguaje> modelo,
			@Value("${akine.asistente.modelo.timeout-segundos:30}") long plazoSegundos) {
		this(indice, visibilidad, new ElectorDePasaje(modelo::getObject, Duration.ofSeconds(plazoSegundos)));
	}

	ComoSeHaceHerramienta(IndiceDelManual indice, VisibilidadDelManual visibilidad, ElectorDePasaje elector) {
		this.indice = indice;
		this.visibilidad = visibilidad;
		this.elector = elector;
	}

	@Override
	public Intencion intencion() {
		return Intencion.COMO_SE_HACE;
	}

	@Override
	public ResultadoDeHerramienta ejecutar(ActorDelAsistente actor, PedidoDeHerramienta pedido) {
		if (actor.organizationId() == null || actor.consultorioId() == null) {
			return ResultadoDeHerramienta.denegado();
		}
		Predicate<FichaDelManual> visible = visibilidad.para(actor.accountId(), actor.organizationId(),
				actor.consultorioId());
		List<PasajeEncontrado> candidatos = candidatos(pedido.pregunta(), visible, pedido.rutaActual());
		Optional<PasajeEncontrado> elegido;
		try {
			elegido = elector.elegir(pedido.pregunta(), candidatos);
		}
		catch (ModeloNoDisponibleException e) {
			return new ResultadoDeHerramienta(List.of(), List.of(ClavesDeFila.MANUAL_SIN_MODELO), null, false);
		}
		if (elegido.isEmpty()) {
			List<String> sugerencias = candidatos.stream().limit(SUGERENCIAS)
					.map(c -> c.ficha().titulo() + " → " + c.pasaje().titulo() + "|"
							+ Objects.toString(enlace(c.ficha()), ""))
					.toList();
			return new ResultadoDeHerramienta(List.of(), sugerencias, null, false);
		}
		PasajeEncontrado pasaje = elegido.get();
		Map<String, Object> fila = new LinkedHashMap<>();
		fila.put(ClavesDeFila.MANUAL_FICHA, pasaje.ficha().id());
		fila.put(ClavesDeFila.MANUAL_TITULO, pasaje.ficha().titulo() + " → " + pasaje.pasaje().titulo());
		fila.put(ClavesDeFila.MANUAL_TEXTO, pasaje.pasaje().markdown());
		fila.put(ClavesDeFila.MANUAL_ENLACE, enlace(pasaje.ficha()));
		return new ResultadoDeHerramienta(List.of(fila), List.of(), enlace(pasaje.ficha()), false);
	}

	/**
	 * Los pasajes que ve el elector. Las definiciones del glosario compiten solo si la pregunta pide una
	 * definicion ("¿qué es...?", "¿qué significa...?"): ante "¿cómo cierro la atención?" el glosario de
	 * "sesión o atención" le ganaba a la tarea.
	 */
	List<PasajeEncontrado> candidatos(String pregunta, Predicate<FichaDelManual> visible, String rutaActual) {
		boolean pideDefinicion = PIDE_DEFINICION.matcher(normalizar(pregunta)).find();
		return indice.buscar(pregunta, visible, rutaActual, CANDIDATOS * 2).stream()
				.filter(p -> pideDefinicion || p.pasaje().tipo() != TipoDePasaje.TERMINO)
				.limit(CANDIDATOS)
				.toList();
	}

	private static String normalizar(String texto) {
		return Normalizer.normalize(texto == null ? "" : texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
				.toLowerCase(Locale.ROOT);
	}

	/** La primera ruta sin parametros de la ficha, o {@code null}. */
	private static String enlace(FichaDelManual ficha) {
		return ficha.rutas().stream().filter(r -> !r.contains(":")).findFirst().orElse(null);
	}
}
