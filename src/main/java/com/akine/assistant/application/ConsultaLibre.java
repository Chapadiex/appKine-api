package com.akine.assistant.application;

import com.akine.assistant.domain.port.MensajeChat;
import com.akine.assistant.domain.port.MensajeChat.Rol;
import com.akine.assistant.domain.port.LectorDeVistas;
import com.akine.assistant.domain.port.LectorDeVistas.ConsultaInvalidaException;
import com.akine.assistant.domain.port.ModeloDeLenguaje;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Respaldo del asistente para las preguntas que no entran en ninguna intencion: el modelo escribe un
 * SELECT sobre las vistas de {@code asistente_sql} y el backend lo valida y lo ejecuta
 * (docs/diseno/AKINE-asistente-sql.md).
 *
 * <p>El modelo ve el esquema de las vistas y la pregunta, nunca filas. Si el motor rechaza la consulta
 * (una columna que no existe, un error de tipos), se le devuelve el mensaje <b>una</b> vez para que la
 * corrija; despues se responde que no se pudo.
 */
public class ConsultaLibre {

	private static final Logger log = LoggerFactory.getLogger(ConsultaLibre.class);

	static final int INTENTOS = 2;

	private final Supplier<ModeloDeLenguaje> modelo;
	private final LectorDeVistas lector;
	private final String prompt;
	private final Duration plazo;
	private final int limiteFilas;
	private final JsonMapper json = JsonMapper.builder().build();

	public ConsultaLibre(Supplier<ModeloDeLenguaje> modelo, LectorDeVistas lector, String prompt, Duration plazo,
			int limiteFilas) {
		this.modelo = modelo;
		this.lector = lector;
		this.prompt = prompt;
		this.plazo = plazo;
		this.limiteFilas = limiteFilas;
	}

	/** Lo que devolvio una consulta libre. {@code sql} es la que se ejecuto, o la ultima que se intento. */
	public record Resultado(String sql, List<Map<String, Object>> filas, boolean truncado, String error) {

		public boolean respondida() {
			return error == null;
		}
	}

	/**
	 * @param hoy el dia en la zona de la sede: el modelo lo necesita para "este mes" o "ayer"
	 * @throws com.akine.assistant.domain.exception.ModeloNoDisponibleException si el modelo no responde
	 */
	public Resultado responder(String pregunta, LocalDate hoy, long organizationId, long consultorioId) {
		List<MensajeChat> mensajes = new ArrayList<>();
		mensajes.add(new MensajeChat(Rol.SISTEMA, prompt.replace("{{HOY}}", hoy.toString())));
		mensajes.add(new MensajeChat(Rol.USUARIO, pregunta));
		String sql = null;
		String error = null;
		for (int intento = 1; intento <= INTENTOS; intento++) {
			String crudo = modelo.get().completarJson(mensajes, plazo);
			sql = extraerSql(crudo).orElse(null);
			if (sql == null) {
				error = "El modelo no devolvio una consulta";
				break;
			}
			Optional<String> rechazo = ValidadorDeSql.rechazo(sql);
			if (rechazo.isPresent()) {
				error = rechazo.get();
			}
			else {
				try {
					List<Map<String, Object>> filas = lector.leer(sql, organizationId, consultorioId, limiteFilas + 1);
					boolean truncado = filas.size() > limiteFilas;
					return new Resultado(sql, truncado ? filas.subList(0, limiteFilas) : filas, truncado, null);
				}
				catch (ConsultaInvalidaException e) {
					error = e.getMessage();
				}
			}
			log.debug("Consulta libre rechazada (intento {}): {}", intento, error);
			mensajes.add(new MensajeChat(Rol.ASISTENTE, crudo));
			mensajes.add(new MensajeChat(Rol.USUARIO, "La consulta fallo: " + error
					+ ". Corregila y devolve solo el JSON con la consulta."));
		}
		return new Resultado(sql, List.of(), false, error);
	}

	/** {@code {"sql": "..."}}; tolera que el modelo devuelva la consulta sola. */
	Optional<String> extraerSql(String crudo) {
		if (crudo == null || crudo.isBlank()) {
			return Optional.empty();
		}
		try {
			JsonNode raiz = json.readTree(crudo);
			JsonNode sql = raiz.get("sql");
			if (sql != null && sql.isString() && !sql.asString().isBlank()) {
				return Optional.of(sql.asString().strip());
			}
			return Optional.empty();
		}
		catch (JacksonException e) {
			String texto = crudo.strip();
			return texto.regionMatches(true, 0, "select", 0, 6) || texto.regionMatches(true, 0, "with", 0, 4)
					? Optional.of(texto) : Optional.empty();
		}
	}
}
