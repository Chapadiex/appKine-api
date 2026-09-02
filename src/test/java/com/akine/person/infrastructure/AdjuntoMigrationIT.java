package com.akine.person.infrastructure;

import com.akine.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V40 contra MySQL real (AKINE-03.02): {@code adjunto_administrativo}.
 *
 * <p>Mismo criterio que {@code PersonaMigrationIT}: lo que se verifica son propiedades del
 * ESQUEMA, que ningun doble puede simular y que dejan de proteger en cuanto alguien escribe por
 * otro camino —una migracion de datos, un fix a mano—.
 *
 * <h2>Las tres afirmaciones del diseno que viven en el indice</h2>
 *
 * <ol>
 *   <li><b>El mismo contenido no entra dos veces vigente para la misma persona.</b> Es lo que hace
 *       idempotente al reintento de una subida, y el pre-chequeo de la aplicacion NO lo
 *       garantiza: dos requests simultaneos lo pasan los dos.</li>
 *   <li><b>Un archivo dado de baja se puede volver a subir</b>, que es lo que el centinela
 *       {@code deleted_key} permite y que un unique sobre {@code deleted_at} a secas romperia.</li>
 *   <li><b>La lista de categorias no admite ninguna clinica.</b> RN-M25-005 no puede depender de
 *       que la aplicacion se acuerde: el CHECK la hace cumplir aunque el INSERT venga de otro
 *       lado.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class AdjuntoMigrationIT {

	private static final long ORG_A = 9301L;

	@Autowired
	private DataSource dataSource;

	private JdbcTemplate jdbc;

	@Test
	@DisplayName("el mismo contenido no entra dos veces vigente para la misma persona")
	void el_unique_hace_idempotente_al_reintento() {
		long personaId = insertarPersona(ORG_A, "40111222");
		String checksum = "a".repeat(64);

		insertarAdjunto(personaId, checksum, "OTRO", clave());

		assertThatThrownBy(() -> insertarAdjunto(personaId, checksum, "OTRO", clave()))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("el mismo contenido SI entra para dos personas distintas")
	void el_unique_es_por_persona() {
		long unaPersona = insertarPersona(ORG_A, "40222333");
		long otraPersona = insertarPersona(ORG_A, "40333444");
		String checksum = "b".repeat(64);

		insertarAdjunto(unaPersona, checksum, "OTRO", clave());

		assertThatCode(() -> insertarAdjunto(otraPersona, checksum, "OTRO", clave()))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("un archivo dado de baja se puede volver a subir: el centinela deleted_key")
	void la_baja_libera_el_contenido() {
		long personaId = insertarPersona(ORG_A, "40444555");
		String checksum = "c".repeat(64);
		String clave = clave();

		insertarAdjunto(personaId, checksum, "OTRO", clave);
		jdbc().update("""
				UPDATE adjunto_administrativo
				   SET active = 0, deleted_at = UTC_TIMESTAMP(6), deactivation_reason = 'vencido'
				 WHERE storage_key = ?
				""", clave);

		assertThatCode(() -> insertarAdjunto(personaId, checksum, "OTRO", clave()))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("dos filas no pueden apuntar al mismo binario")
	void la_storage_key_es_unica() {
		long personaId = insertarPersona(ORG_A, "40555666");
		String clave = clave();

		insertarAdjunto(personaId, "d".repeat(64), "OTRO", clave);

		assertThatThrownBy(() -> insertarAdjunto(personaId, "e".repeat(64), "OTRO", clave))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("no hay ninguna categoria clinica, y el CHECK lo hace cumplir")
	void la_categoria_es_una_lista_cerrada_y_administrativa() {
		long personaId = insertarPersona(ORG_A, "40666777");

		// Las categorias que un adjunto clinico tendria. RN-M25-005: no entran, y no dependen de
		// que la aplicacion se acuerde de no mandarlas.
		for (String clinica : new String[] {"ESTUDIO", "INFORME", "RADIOGRAFIA", "EVOLUCION"}) {
			assertThatThrownBy(() -> insertarAdjunto(personaId, checksumAlAzar(), clinica, clave()))
					.as("categoria clinica rechazada: %s", clinica)
					.isInstanceOfAny(
							DataIntegrityViolationException.class, UncategorizedSQLException.class);
		}
	}

	@Test
	@DisplayName("un adjunto vacio no es un adjunto, y una baja sin motivo no es coherente")
	void los_checks_de_coherencia_existen() {
		long personaId = insertarPersona(ORG_A, "40777888");

		assertThatThrownBy(() -> jdbc().update("""
				INSERT INTO adjunto_administrativo (organization_id, persona_id, categoria,
				        nombre_archivo, content_type, tamano_bytes, checksum_sha256, storage_key,
				        subido_por, subido_en, created_at, updated_at)
				 VALUES (?, ?, 'OTRO', 'x.pdf', 'application/pdf', 0, ?, ?, 1,
				         UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", ORG_A, personaId, checksumAlAzar(), clave()))
				.isInstanceOfAny(
						DataIntegrityViolationException.class, UncategorizedSQLException.class);

		String clave = clave();
		insertarAdjunto(personaId, checksumAlAzar(), "OTRO", clave);

		assertThatThrownBy(() -> jdbc().update("""
				UPDATE adjunto_administrativo SET active = 0 WHERE storage_key = ?
				""", clave))
				.isInstanceOfAny(
						DataIntegrityViolationException.class, UncategorizedSQLException.class);
	}

	// =================================================================================
	// Fixtures
	// =================================================================================

	private JdbcTemplate jdbc() {
		if (jdbc == null) {
			jdbc = new JdbcTemplate(dataSource);
		}
		return jdbc;
	}

	private long insertarPersona(long organizationId, String documento) {
		jdbc().update("""
				INSERT INTO persona (organization_id, tipo_documento, numero_documento,
				                     documento_clave, apellido, nombre, apellido_clave,
				                     nombre_clave, created_at, updated_at)
				 VALUES (?, 'DNI', ?, ?, 'Sintetico', 'Caso', 'SINTETICO', 'CASO',
				         UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, documento, documento);

		return jdbc().queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private void insertarAdjunto(
			long personaId, String checksum, String categoria, String storageKey) {

		jdbc().update("""
				INSERT INTO adjunto_administrativo (organization_id, persona_id, categoria,
				        nombre_archivo, content_type, tamano_bytes, checksum_sha256, storage_key,
				        subido_por, subido_en, created_at, updated_at)
				 VALUES (?, ?, ?, 'x.pdf', 'application/pdf', 1024, ?, ?, 1,
				         UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", ORG_A, personaId, categoria, checksum, storageKey);
	}

	private static String clave() {
		return java.util.UUID.randomUUID().toString().replace("-", "");
	}

	private static String checksumAlAzar() {
		return (java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID())
				.replace("-", "")
				.substring(0, 64);
	}
}
