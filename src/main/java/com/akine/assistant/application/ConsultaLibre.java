package com.akine.assistant.application;

import com.akine.platform.spi.modelo.MensajeChat;
import com.akine.platform.spi.modelo.MensajeChat.Rol;
import com.akine.assistant.domain.port.LectorDeVistas;
import com.akine.assistant.domain.port.LectorDeVistas.ConsultaInvalidaException;
import com.akine.platform.spi.modelo.ModeloDeLenguaje;
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
import java.util.Set;
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

	/** Cuantas preguntas anteriores ve el modelo: alcanzan para un "¿y ella?" sin agrandar el prompt. */
	static final int ANTERIORES = 3;

	/** El error de una pregunta que necesita las vistas clinicas y el actor no las tiene. */
	public static final String NECESITA_CLINICO = "NECESITA_CLINICO";

	static final String SIN_CLINICAS = "Los datos clinicos (diagnosticos o afecciones, motivos de consulta, tratamientos o aparatos "
			+ "aplicados, mediciones, antecedentes, planes) NO estan disponibles para este usuario. Si la pregunta "
			+ "los necesita, devolve {\"sql\": null, \"clinico\": true}.";

	private final Supplier<ModeloDeLenguaje> modelo;
	private final LectorDeVistas lector;
	private final String prompt;
	private final String promptClinico;
	private final Duration plazo;
	private final int limiteFilas;
	private final JsonMapper json = JsonMapper.builder().build();

	public ConsultaLibre(Supplier<ModeloDeLenguaje> modelo, LectorDeVistas lector, String prompt,
			String promptClinico, Duration plazo, int limiteFilas) {
		this.modelo = modelo;
		this.lector = lector;
		this.prompt = prompt;
		this.promptClinico = promptClinico;
		this.plazo = plazo;
		this.limiteFilas = limiteFilas;
	}

	/** JSON libre (sin esquema), como el respaldo del Interpretador; el cupo lleno tambien es 503. */
	private String completar(List<MensajeChat> mensajes) {
		try {
			return modelo.get().completarJson(mensajes, null, plazo);
		}
		catch (com.akine.platform.spi.modelo.ModeloNoDisponibleException e) {
			throw new com.akine.assistant.domain.exception.ModeloNoDisponibleException(e);
		}
	}

	/** Lo que devolvio una consulta libre. {@code sql} es la que se ejecuto, o la ultima que se intento. */
	public record Resultado(String sql, List<Map<String, Object>> filas, boolean truncado, String error,
			Set<String> vistasClinicas) {

		public boolean respondida() {
			return error == null;
		}
	}

	/**
	 * @param hoy el dia en la zona de la sede: el modelo lo necesita para "este mes" o "ayer"
	 * @throws com.akine.assistant.domain.exception.ModeloNoDisponibleException si el modelo no responde o su cupo esta lleno
	 */
	public Resultado responder(String pregunta, List<String> anteriores, String persona, boolean clinicas,
			LocalDate hoy, long organizationId, long consultorioId) {
		List<MensajeChat> mensajes = new ArrayList<>();
		mensajes.add(new MensajeChat(Rol.SISTEMA, prompt.replace("{{HOY}}", hoy.toString())
				.replace("{{VISTAS_CLINICAS}}", clinicas ? promptClinico : SIN_CLINICAS)));
		mensajes.add(new MensajeChat(Rol.USUARIO, conAnteriores(pregunta, anteriores, persona)));
		String sql = null;
		String error = null;
		for (int intento = 1; intento <= INTENTOS; intento++) {
			String crudo = completar(mensajes);
			sql = extraerSql(crudo).orElse(null);
			if (sql == null) {
				error = !clinicas && pideClinico(crudo) ? NECESITA_CLINICO : "El modelo no devolvio una consulta";
				break;
			}
			Optional<String> rechazo = ValidadorDeSql.rechazo(sql, clinicas);
			if (rechazo.isPresent()) {
				error = rechazo.get();
			}
			else {
				try {
					List<Map<String, Object>> filas = lector.leer(sql, organizationId, consultorioId, limiteFilas + 1);
					boolean truncado = filas.size() > limiteFilas;
					return new Resultado(sql, truncado ? filas.subList(0, limiteFilas) : filas, truncado, null,
							ValidadorDeSql.vistasClinicas(sql));
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
		return new Resultado(sql, List.of(), false, error, Set.of());
	}

	/**
	 * La pregunta con las ultimas anteriores de la charla, para que "¿tiene turno?" despues de "Lucia"
	 * hable de Lucia. Solo preguntas: el modelo sigue sin ver filas.
	 */
	static String conAnteriores(String pregunta, List<String> anteriores, String persona) {
		if ((anteriores == null || anteriores.isEmpty()) && persona == null) {
			return pregunta;
		}
		StringBuilder texto = new StringBuilder();
		if (anteriores != null && !anteriores.isEmpty()) {
			texto.append("Preguntas anteriores de la charla, de la mas vieja a la mas nueva:\n");
			anteriores.subList(Math.max(0, anteriores.size() - ANTERIORES), anteriores.size())
					.forEach(a -> texto.append("- ").append(a).append('\n'));
		}
		if (persona != null) {
			texto.append("Persona de la que se habla: ").append(persona).append('\n');
		}
		return texto.append("Pregunta actual: ").append(pregunta).toString();
	}

	/** {@code {"sql": null, "clinico": true}}: la pregunta necesita datos clinicos que el actor no puede ver. */
	boolean pideClinico(String crudo) {
		try {
			JsonNode clinico = json.readTree(crudo).get("clinico");
			return clinico != null && clinico.asBoolean(false);
		}
		catch (JacksonException | NullPointerException e) {
			return false;
		}
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
