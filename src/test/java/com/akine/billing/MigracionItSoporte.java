package com.akine.billing;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lo que comparten los ITs de migracion de 07.03–07.05 ({@code V54}, {@code V56}, {@code V57}):
 * insertar filas DIRECTO —sin servicio, con defaults validos que cada caso pisa— y afirmar que el
 * motor rechaza por la constraint que corresponde.
 *
 * <p>Se afirma el <b>nombre</b> de la constraint y no solo el tipo de excepcion. Una fila que el
 * motor rechaza por otro CHECK prueba que hay un CHECK, no que funciona el que se quiere probar.
 * MySQL 8.4 dice {@code Check constraint 'ck_x' is violated.} (error 3819) y
 * {@code Duplicate entry ... for key 'tabla.uk_x'} (1062).
 */
final class MigracionItSoporte {

	private final JdbcTemplate jdbc;

	MigracionItSoporte(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	static LocalDateTime ahora() {
		return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
	}

	/** Mapa ordenado y que admite {@code null}, a partir de pares columna/valor. */
	static Map<String, Object> fila(Object... pares) {
		Map<String, Object> fila = new LinkedHashMap<>();
		for (int i = 0; i < pares.length; i += 2) {
			fila.put((String) pares[i], pares[i + 1]);
		}
		return fila;
	}

	/** Los defaults, pisados por los pares dados. */
	static Map<String, Object> con(Map<String, Object> defaults, Object... pisar) {
		Map<String, Object> fila = new LinkedHashMap<>(defaults);
		fila.putAll(fila(pisar));
		return fila;
	}

	/** INSERT directo; devuelve el id generado. */
	long insertar(String tabla, Map<String, Object> valores) {
		String columnas = String.join(", ", valores.keySet());
		String marcas = valores.keySet().stream().map(c -> "?").collect(Collectors.joining(", "));
		jdbc.update("INSERT INTO " + tabla + " (" + columnas + ") VALUES (" + marcas + ")",
				valores.values().toArray());
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	void violaCheck(String constraint, ThrowingCallable intento) {
		assertThatThrownBy(intento)
				.as(constraint)
				.isInstanceOf(UncategorizedSQLException.class)
				.hasMessageContaining("'" + constraint + "'");
	}

	void violaUnique(String constraint, ThrowingCallable intento) {
		assertThatThrownBy(intento)
				.as(constraint)
				.isInstanceOf(DuplicateKeyException.class)
				.hasMessageContaining(constraint);
	}

	static void entra(ThrowingCallable intento) {
		assertThatCode(intento).doesNotThrowAnyException();
	}

	/** Que la columna exista, sea generada STORED y con la expresion que se espera. */
	void esGeneradaStored(String tabla, String columna, String fragmentoDeExpresion) {
		Map<String, Object> fila = jdbc.queryForMap("""
				SELECT extra, generation_expression FROM information_schema.columns
				 WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
				""", tabla, columna);
		assertThat((String) fila.get("extra")).as(tabla + "." + columna).isEqualTo("STORED GENERATED");
		assertThat((String) fila.get("generation_expression"))
				.as(tabla + "." + columna + " expresion")
				.containsIgnoringCase(fragmentoDeExpresion);
	}

	/** Cuantas constraints CHECK con ese nombre hay en el esquema (0 o 1). */
	int checksLlamados(String constraint) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM information_schema.check_constraints
				 WHERE constraint_schema = DATABASE() AND constraint_name = ?
				""", Integer.class, constraint);
	}

	String clausulaDe(String constraint) {
		return jdbc.queryForObject("""
				SELECT check_clause FROM information_schema.check_constraints
				 WHERE constraint_schema = DATABASE() AND constraint_name = ?
				""", String.class, constraint);
	}

	Object valor(String sql, Object... args) {
		return jdbc.queryForObject(sql, Object.class, args);
	}
}
