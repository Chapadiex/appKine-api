package com.akine.resource.infrastructure;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.akine.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V22 contra MySQL real (AKINE-02.04, tarea 1).
 *
 * <h2>Por que estos cuatro casos y no mas</h2>
 *
 * <p>Esta migracion no tiene todavia servicio, controller ni permiso propios —eso llega en
 * tareas posteriores de la etapa (4, 6 y 8)—, asi que no hay nada que ejercer por HTTP. Lo unico
 * que existe hoy es el ESQUEMA y el SEED, y las dos cosas solo se verifican contra una base real:
 *
 * <ol>
 *   <li>Que {@code feriado} <b>no</b> tenga {@code organization_id}. Es la mitad exacta de
 *       ADR-0022: si alguien la "arregla" agregandosela, el seed se duplicaria por tenant sin que
 *       ningun test unitario lo note, porque no hay dominio propio que lo pueda expresar.</li>
 *   <li>Que el {@code UNIQUE (pais, fecha)} realmente impida el duplicado. A diferencia de los
 *       uniques de V10/V12/V18/V19/V20, este no tiene columna generada ni centinela —el ADR
 *       explica por que no hace falta—, pero "no hace falta centinela" y "el unique funciona" son
 *       afirmaciones distintas, y la segunda solo se prueba insertando la colision.</li>
 *   <li>Que el seed haya cargado los inamovibles de los DOS anios declarados.</li>
 *   <li>Que el {@code CHECK} de {@code tipo} rechace un valor libre.</li>
 * </ol>
 *
 * <p>Los trasladables cargados (San Martin y Diversidad Cultural, solo 2026: ver el comentario de
 * V22) no tienen test dedicado: son dos filas mas del mismo INSERT y no ejercen ninguna regla que
 * las nueve inamovibles no ejerzan ya.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class FeriadoMigrationIT {

	@Autowired
	private DataSource dataSource;

	private JdbcTemplate jdbc;

	private JdbcTemplate jdbc() {
		if (jdbc == null) {
			jdbc = new JdbcTemplate(dataSource);
		}
		return jdbc;
	}

	// =================================================================================
	// ADR-0022: sin organization_id, sin excepcion
	// =================================================================================

	@Test
	@DisplayName("la tabla feriado no tiene organization_id: es la excepcion que declara ADR-0022")
	void la_tabla_no_tiene_organization_id() {
		Integer columnas = jdbc().queryForObject("""
				SELECT COUNT(*) FROM information_schema.columns
				 WHERE table_schema = DATABASE()
				   AND table_name = 'feriado'
				   AND column_name = 'organization_id'
				""", Integer.class);

		assertThat(columnas)
				.as("ADR-0022 es una excepcion declarada: si alguien \"arregla\" la tabla "
						+ "agregandole organization_id, este test se lo dice antes de que el seed "
						+ "se duplique por tenant")
				.isZero();
	}

	// =================================================================================
	// El UNIQUE (pais, fecha)
	// =================================================================================

	@Test
	@DisplayName("el UNIQUE (pais, fecha) impide dos feriados el mismo dia del mismo pais")
	void el_unique_impide_dos_feriados_el_mismo_dia_del_mismo_pais() {
		// El 1 de enero de 2026 ya lo cargo el seed: cualquier segunda fila con el mismo pais y
		// la misma fecha tiene que chocar, sin importar el nombre ni el tipo.
		assertThatThrownBy(() -> jdbc().update("""
				INSERT INTO feriado (pais, fecha, nombre, tipo, created_at, updated_at)
				VALUES ('AR', '2026-01-01', 'Duplicado sintetico', 'INAMOVIBLE', NOW(6), NOW(6))
				"""))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	// =================================================================================
	// El seed
	// =================================================================================

	@Test
	@DisplayName("el seed carga los nueve feriados inamovibles de 2026 y de 2027")
	void el_seed_carga_los_feriados_inamovibles_de_2026_y_2027() {
		for (int anio : new int[] {2026, 2027}) {
			Integer inamovibles = jdbc().queryForObject("""
					SELECT COUNT(*) FROM feriado
					 WHERE pais = 'AR' AND tipo = 'INAMOVIBLE' AND YEAR(fecha) = ?
					""", Integer.class, anio);

			assertThat(inamovibles)
					.as("los nueve inamovibles de Ley 27.399 para el anio %d", anio)
					.isEqualTo(9);
		}

		// Las fechas puntuales, no solo el conteo: el 25 de mayo tiene que ser el 25 de mayo.
		assertThat(jdbc().queryForObject(
				"SELECT nombre FROM feriado WHERE pais = 'AR' AND fecha = '2026-05-25'",
				String.class))
				.isEqualTo("Día de la Revolución de Mayo");
		assertThat(jdbc().queryForObject(
				"SELECT nombre FROM feriado WHERE pais = 'AR' AND fecha = '2027-07-09'",
				String.class))
				.isEqualTo("Día de la Independencia");
	}

	@Test
	@DisplayName("el Dia de la Soberania Nacional 2026 se siembra en su fecha OBSERVADA, el lunes 23")
	void la_soberania_nacional_se_siembra_en_la_fecha_observada() {
		// La conmemoracion es el viernes 20/11/2026 y la observancia se traslada al lunes 23. La
		// columna `fecha` guarda la OBSERVADA, que es el dia en que el centro cierra: sembrar el
		// 20 cerraria el centro el dia equivocado y lo dejaria abierto el dia que nadie trabaja,
		// que es el peor de los dos errores porque nadie lo denuncia hasta que llega el lunes.
		assertThat(jdbc().queryForObject(
				"SELECT nombre FROM feriado WHERE pais = 'AR' AND fecha = '2026-11-23'",
				String.class))
				.isEqualTo("Día de la Soberanía Nacional");

		assertThat(jdbc().queryForObject("""
				SELECT COUNT(*) FROM feriado WHERE pais = 'AR' AND fecha = '2026-11-20'
				""", Integer.class))
				.as("la fecha conmemorativa NO se siembra: no es el dia observado")
				.isZero();
	}

	// =================================================================================
	// El CHECK de tipo
	// =================================================================================

	@Test
	@DisplayName("el CHECK de tipo rechaza un valor libre")
	void el_check_de_tipo_rechaza_un_valor_libre() {
		assertThatThrownBy(() -> jdbc().update("""
				INSERT INTO feriado (pais, fecha, nombre, tipo, created_at, updated_at)
				VALUES ('AR', '2030-01-01', 'Feriado inventado', 'INVENTADO', NOW(6), NOW(6))
				"""))
				.isInstanceOf(UncategorizedSQLException.class)
				.hasMessageContaining("ck_feriado_tipo");
	}
}
