package com.akine.knowledge.infrastructure.manual;

import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ContextoDeConsulta;
import com.akine.assistant.spi.EntradaDeHerramienta;
import com.akine.assistant.spi.HerramientaDelAgente;
import com.akine.assistant.spi.ResultadoDeHerramienta;
import com.akine.knowledge.application.manual.PasajeEncontrado;
import com.akine.knowledge.application.manual.VisibilidadDelManual;
import com.akine.knowledge.domain.manual.FichaDelManual;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * {@code como_se_hace}: pasajes textuales del manual de usuario, filtrados por lo que la persona puede ver
 * y priorizando la pantalla actual. Devuelve los mejores candidatos y el agente elige y cita; no hace una
 * segunda llamada al modelo.
 */
@Component
class ComoSeHaceParaElAgente implements HerramientaDelAgente {

	static final int PASAJES = 3;

	private final ComoSeHaceHerramienta manual;
	private final VisibilidadDelManual visibilidad;

	ComoSeHaceParaElAgente(ComoSeHaceHerramienta manual, VisibilidadDelManual visibilidad) {
		this.manual = manual;
		this.visibilidad = visibilidad;
	}

	@Override
	public String nombre() {
		return "como_se_hace";
	}

	@Override
	public String descripcion() {
		return "Busca en el manual de usuario de AKINE como se hace una tarea o que significa un termino, y devuelve "
				+ "los pasajes textuales con su pantalla. Citalos tal cual; si ninguno responde, decilo. "
				+ "No responde sobre datos del centro.";
	}

	@Override
	public String esquemaEntrada() {
		return "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
				+ "\"pregunta\":{\"type\":\"string\",\"description\":\"La pregunta de uso, con las palabras de la "
				+ "persona\"}},\"required\":[\"pregunta\"]}";
	}

	@Override
	public ResultadoDeHerramienta ejecutar(ActorDelAsistente actor, Map<String, Object> entrada,
			ContextoDeConsulta ctx) {
		String pregunta = EntradaDeHerramienta.texto(entrada, "pregunta");
		if (pregunta == null) {
			throw new IllegalArgumentException("Falta 'pregunta'");
		}
		if (actor.organizationId() == null || actor.consultorioId() == null) {
			return ResultadoDeHerramienta.denegado();
		}
		Predicate<FichaDelManual> visible = visibilidad.para(actor.accountId(), actor.organizationId(),
				actor.consultorioId());
		List<Map<String, Object>> filas = manual.candidatos(pregunta, visible, ctx.rutaActual()).stream()
				.limit(PASAJES).map(ComoSeHaceParaElAgente::fila).toList();
		return new ResultadoDeHerramienta(filas,
				filas.isEmpty() ? List.of("El manual no tiene un pasaje sobre eso") : List.of(), null, false);
	}

	private static Map<String, Object> fila(PasajeEncontrado p) {
		Map<String, Object> fila = new LinkedHashMap<>();
		fila.put("ficha", p.ficha().id());
		fila.put("titulo", p.ficha().titulo() + " → " + p.pasaje().titulo());
		fila.put("texto", p.pasaje().markdown());
		fila.put("ruta", p.ficha().rutas().stream().filter(r -> !r.contains(":")).findFirst().orElse(null));
		return fila;
	}
}
