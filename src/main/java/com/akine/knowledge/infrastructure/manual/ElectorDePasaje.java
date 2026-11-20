package com.akine.knowledge.infrastructure.manual;

import com.akine.knowledge.application.manual.PasajeEncontrado;
import com.akine.platform.spi.modelo.MensajeChat;
import com.akine.platform.spi.modelo.ModeloDeLenguaje;
import com.akine.platform.spi.modelo.ModeloNoDisponibleException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Elige, entre los pasajes que encontró la búsqueda, el que responde la pregunta, o ninguno
 * (docs/diseno/AKINE-manual-de-usuario.md §5.4). El modelo ve sólo los títulos y devuelve un id: no
 * redacta. Un id que no estaba entre los candidatos, un JSON roto o un modelo caído cuentan como
 * "ninguno": es la abstención que el coseno solo no da.
 */
public class ElectorDePasaje {

	static final String NINGUNO = "NINGUNO";
	static final String ESQUEMA = "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}},"
			+ "\"required\":[\"id\"]}";
	static final String INSTRUCCIONES = """
			Sos el buscador del manual de AKINE, un sistema para centros de kinesiología. Te paso una pregunta \
			y una lista de secciones del manual, cada una con su id. Elegí la UNA sección que explica cómo hacer \
			eso DENTRO de AKINE. Elegí NINGUNO si la pregunta es sobre otro programa o servicio (correo, banco, \
			WhatsApp, otro sistema), sobre cómo tratar a un paciente, o si ninguna sección la responde de \
			verdad: es mejor no contestar que contestar otra cosa. En AKINE, la "sesión" o "atención" de un \
			paciente es la consulta clínica, no la sesión del usuario en el sistema. \
			Devolvé solo {"id": "..."} con el id exacto de la lista o NINGUNO.""";

	private static final Logger log = LoggerFactory.getLogger(ElectorDePasaje.class);

	private final Supplier<ModeloDeLenguaje> modelo;
	private final Duration plazo;
	private final JsonMapper json = JsonMapper.builder().build();

	public ElectorDePasaje(Supplier<ModeloDeLenguaje> modelo, Duration plazo) {
		this.modelo = modelo;
		this.plazo = plazo;
	}

	public Optional<PasajeEncontrado> elegir(String pregunta, List<PasajeEncontrado> candidatos) {
		if (candidatos.isEmpty()) {
			return Optional.empty();
		}
		String lista = candidatos.stream()
				.map(c -> c.pasaje().id() + ": " + c.ficha().titulo() + " → " + c.pasaje().titulo())
				.collect(Collectors.joining("\n"));
		String crudo;
		try {
			crudo = modelo.get().completarJson(List.of(
					new MensajeChat(MensajeChat.Rol.SISTEMA, INSTRUCCIONES),
					new MensajeChat(MensajeChat.Rol.USUARIO, "Pregunta: " + pregunta + "\n\nSecciones:\n" + lista)),
					ESQUEMA, plazo);
		}
		catch (ModeloNoDisponibleException e) {
			log.warn("Manual: el modelo no eligio pasaje ({})", e.getClass().getSimpleName());
			return Optional.empty();
		}
		String id = leerId(crudo);
		if (id == null || NINGUNO.equalsIgnoreCase(id)) {
			return Optional.empty();
		}
		return candidatos.stream().filter(c -> c.pasaje().id().equals(id)).findFirst();
	}

	private String leerId(String crudo) {
		try {
			JsonNode nodo = json.readTree(crudo);
			JsonNode id = nodo == null ? null : nodo.get("id");
			return id == null || !id.isString() ? null : id.asString().strip();
		}
		catch (RuntimeException e) {
			return null;
		}
	}
}
