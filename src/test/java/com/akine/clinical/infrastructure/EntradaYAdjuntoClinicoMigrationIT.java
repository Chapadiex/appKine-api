package com.akine.clinical.infrastructure;

import com.akine.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code V45} y {@code V46} contra MySQL real: los CHECK, los uniques y las columnas generadas.
 *
 * <p><b>ESCRITO EL 19/09/2026 Y NUNCA EJECUTADO: Docker no estaba disponible.</b> El motor de
 * contenedores de esta maquina no arranca sin elevacion, asi que esta clase compila y no corrio.
 * Nada de lo que afirma esta verificado todavia.
 *
 * <h2>Que escenario prueba y por que importa</h2>
 *
 * <p>Mismo criterio que {@code HistoriaClinicaMigrationIT} y {@code PersonaMigrationIT}: se
 * inserta <b>directo</b> contra una base real, sin pasar por ningun servicio. Un invariante que
 * solo la aplicacion respeta deja de proteger en cuanto alguien escribe por otro camino — una
 * migracion de datos, un fix a mano, un modulo futuro. Y esa es la unica forma de comprobar que
 * el CHECK <b>existe</b> y no solo que el {@code CREATE TABLE} lo mencionaba: MySQL ignoro en
 * silencio toda la sintaxis {@code CHECK} hasta 8.0.16, y en 8.4 una expresion que referencia mal
 * una columna generada falla con un 3819 que solo aparece al ejecutar.
 *
 * <p>Es la clase de test que en 03.06 destapo exactamente ese 3819.
 *
 * <h2>Lo que se verifica</h2>
 *
 * <ol>
 *   <li><b>El par {@code origen} ↔ {@code referencia_origen}.</b> Una entrada {@code MANUAL} con
 *       referencia, o una no-manual sin ella, es una entrada que dice venir de algun lado que no
 *       se puede ir a buscar. La columna existe desde ahora aunque hoy solo se escriba
 *       {@code MANUAL} porque agregarla despues obligaria a decidir que valor llevan las viejas
 *       (DP-10).</li>
 *   <li><b>El motivo obligatorio desde la version 2.</b> Sin motivo, una enmienda es
 *       indistinguible de una correccion de tipeo y el historico deja de servir para lo unico que
 *       sirve. Y a la inversa: la version 1 no enmienda nada, asi que no puede llevar motivo.</li>
 *   <li><b>La coherencia de la baja logica</b>, el cuarteto completo o ninguno. Una fila a medio
 *       dar de baja pasa por vigente para unas consultas y por inactiva para otras, y ninguna de
 *       las dos lecturas esta mal.</li>
 *   <li><b>Las listas cerradas</b> de tipo de entrada y de categoria de adjunto. La separacion
 *       con {@code adjunto_administrativo} corre en las dos direcciones (RN-M25-005): ninguna
 *       categoria administrativa entra aca.</li>
 *   <li><b>Las columnas generadas {@code deleted_key}</b> existen, son {@code STORED} y valen el
 *       centinela {@code '1970-01-01'} mientras la fila este vigente. Es lo que hace que el unique
 *       de contenido del adjunto proteja lo VIGENTE: con {@code deleted_at} a secas, varios
 *       {@code NULL} no colisionan en MySQL y el unique protegeria el historico y desprotegeria lo
 *       que esta en uso.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class EntradaYAdjuntoClinicoMigrationIT {

	private static final long ORG_A = 9401L;
	private static final long ORG_B = 9402L;

	/** Cada corrida siembra sus propias personas: el unique de documento no perdona repeticiones. */
	private static final AtomicLong SECUENCIA = new AtomicLong(40400000L);

	private static final String CHECKSUM =
			"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

	@Autowired
	private DataSource dataSource;

	private JdbcTemplate jdbc;
	private long historiaEnA;
	private long historiaEnB;

	private JdbcTemplate jdbc() {
		if (jdbc == null) {
			jdbc = new JdbcTemplate(dataSource);
		}
		return jdbc;
	}

	@BeforeEach
	void sembrar() {
		crearOrganizacion(ORG_A, "entrada-adj-org-a");
		crearOrganizacion(ORG_B, "entrada-adj-org-b");
		historiaEnA = abrirHistoria(ORG_A);
		historiaEnB = abrirHistoria(ORG_B);
	}

	// =================================================================================
	// Tenant y forma de las tablas
	// =================================================================================

	@Test
	@DisplayName("las tres tablas llevan organization_id NOT NULL: no hay entrada ni adjunto global")
	void las_tres_tablas_llevan_tenant() {
		for (String tabla : List.of(
				"entrada_clinica", "entrada_clinica_version", "adjunto_clinico")) {

			assertThat(jdbc().queryForObject("""
					SELECT is_nullable FROM information_schema.columns
					 WHERE table_schema = DATABASE() AND table_name = ?
					   AND column_name = 'organization_id'
					""", String.class, tabla))
					.as("%s tiene que llevar organization_id NOT NULL", tabla)
					.isEqualTo("NO");
		}
	}

	@Test
	@DisplayName("todo unique e indice declarado empieza por organization_id")
	void los_indices_empiezan_por_el_tenant() {
		// Un indice que no empieza por el tenant no sirve para acotar por organizacion, y un
		// unique sin el es directamente un bug de aislamiento (AGENT.md seccion 5). Se excluyen
		// los que MySQL crea sola para sostener cada FK, mismo filtro que HistoriaClinicaMigrationIT.
		//
		// `uk_adjunto_clinico_storage_key` es la excepcion declarada y por eso se excluye por
		// nombre: la clave de almacenamiento es global a proposito —es la ruta de un binario en
		// disco— y acotarla por tenant permitiria que dos organizaciones apunten al mismo archivo.
		for (String tabla : List.of(
				"entrada_clinica", "entrada_clinica_version", "adjunto_clinico")) {

			List<String> primeras = jdbc().queryForList("""
					SELECT DISTINCT s.column_name
					  FROM information_schema.statistics s
					 WHERE s.table_schema = DATABASE() AND s.table_name = ?
					   AND s.seq_in_index = 1 AND s.index_name <> 'PRIMARY'
					   AND s.index_name <> 'uk_adjunto_clinico_storage_key'
					   AND NOT EXISTS (
					       SELECT 1 FROM information_schema.table_constraints tc
					        WHERE tc.table_schema = s.table_schema
					          AND tc.table_name = s.table_name
					          AND tc.constraint_name = s.index_name
					          AND tc.constraint_type = 'FOREIGN KEY')
					""", String.class, tabla);

			assertThat(primeras)
					.as("%s tiene que conservar sus indices de busqueda", tabla)
					.isNotEmpty();
			assertThat(primeras)
					.as("todo indice declarado de %s empieza por organization_id", tabla)
					.containsOnly("organization_id");
		}
	}

	@Test
	@DisplayName("una version de contenido NO tiene active: darla de baja seria reescribir historia")
	void las_versiones_no_se_dan_de_baja() {
		// Decision del challenge seccion 5, escrita en el esquema. Una version es un hecho pasado;
		// ADR-0011 prohibe reescribirlo. Si alguien agrega `active` aca, este test lo frena.
		assertThat(columnasDe("entrada_clinica_version"))
				.doesNotContain("active", "deleted_at", "deactivation_reason", "deleted_key");
	}

	@Test
	@DisplayName("la entrada no se adelanta al Caso Clinico: no tiene caso_clinico_id")
	void la_entrada_no_inventa_un_caso() {
		// Regla maestra 1 y challenge seccion 4: el Caso es 04.03. Un "caso por defecto" seria un
		// Caso mal hecho que despues hay que desarmar, y 04.03 tendria que decidir que hacer con
		// todas las entradas colgadas de un caso fantasma.
		assertThat(columnasDe("entrada_clinica")).doesNotContain("caso_clinico_id", "sesion_id");
	}

	// =================================================================================
	// Las columnas generadas
	// =================================================================================

	@Test
	@DisplayName("deleted_key existe, es STORED y vale el centinela mientras la fila esta vigente")
	void el_centinela_de_la_baja_logica() {
		for (String tabla : List.of("entrada_clinica", "adjunto_clinico")) {
			assertThat(jdbc().queryForObject("""
					SELECT extra FROM information_schema.columns
					 WHERE table_schema = DATABASE() AND table_name = ?
					   AND column_name = 'deleted_key'
					""", String.class, tabla))
					.as("%s.deleted_key tiene que ser una columna generada STORED: una VIRTUAL no "
							+ "puede participar de un indice unico en MySQL", tabla)
					.contains("STORED GENERATED");
		}

		long entradaId = insertarEntrada(ORG_A, historiaEnA, "EVOLUCION", "MANUAL", null);
		long adjuntoId = insertarAdjunto(ORG_A, historiaEnA, "ESTUDIO", CHECKSUM);

		assertThat(jdbc().queryForObject(
				"SELECT deleted_key FROM entrada_clinica WHERE id = ?", String.class, entradaId))
				.as("el centinela, no NULL: es lo que hace comparables las filas vigentes")
				.startsWith("1970-01-01");
		assertThat(jdbc().queryForObject(
				"SELECT deleted_key FROM adjunto_clinico WHERE id = ?", String.class, adjuntoId))
				.startsWith("1970-01-01");
	}

	// =================================================================================
	// ck_entrada_clinica_origen_trazable
	// =================================================================================

	@Test
	@DisplayName("una entrada MANUAL con referencia de origen no entra")
	void una_manual_no_puede_referenciar_nada() {
		assertThatThrownBy(() -> insertarEntrada(ORG_A, historiaEnA, "EVOLUCION", "MANUAL", 77L))
				.as("ck_entrada_clinica_origen_trazable. El CHECK de MySQL llega como "
						+ "UncategorizedSQLException, al reves que el UNIQUE")
				.isInstanceOf(UncategorizedSQLException.class);
	}

	@Test
	@DisplayName("una entrada de origen SESION sin referencia no entra")
	void una_no_manual_tiene_que_decir_de_donde_viene() {
		// Una entrada que dice venir de una sesion y no dice de cual es una entrada que nadie
		// puede ir a verificar. Hoy este camino no lo escribe nadie —solo se escribe MANUAL— y el
		// CHECK esta igual: el dia que otro modulo genere entradas, el invariante ya esta puesto.
		assertThatThrownBy(() -> insertarEntrada(ORG_A, historiaEnA, "EVOLUCION", "SESION", null))
				.isInstanceOf(UncategorizedSQLException.class);
	}

	@Test
	@DisplayName("una entrada de origen SESION con su referencia entra")
	void el_par_completo_se_admite() {
		// Control positivo: sin el, un CHECK demasiado estricto —que prohibiera todo origen que no
		// fuera MANUAL— pasaria inadvertido con los dos rechazos en verde.
		assertThatCode(() -> insertarEntrada(ORG_A, historiaEnA, "EVOLUCION", "SESION", 77L))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("un tipo de entrada fuera del catalogo no entra")
	void el_tipo_de_entrada_es_lista_cerrada() {
		assertThatThrownBy(() -> insertarEntrada(ORG_A, historiaEnA, "DIAGNOSTICO", "MANUAL", null))
				.isInstanceOf(UncategorizedSQLException.class);
	}

	// =================================================================================
	// ck_entrada_version_motivo_de_enmienda y uk_entrada_version_numero
	// =================================================================================

	@Test
	@DisplayName("la version 1 con motivo de enmienda no entra: no enmienda nada")
	void el_original_no_lleva_motivo() {
		long entradaId = insertarEntrada(ORG_A, historiaEnA, "EVOLUCION", "MANUAL", null);

		assertThatThrownBy(() -> insertarVersion(ORG_A, entradaId, 1, "Motivo indebido"))
				.isInstanceOf(UncategorizedSQLException.class);
	}

	@Test
	@DisplayName("una enmienda sin motivo no entra desde la version 2")
	void toda_enmienda_lleva_motivo() {
		long entradaId = insertarEntrada(ORG_A, historiaEnA, "EVOLUCION", "MANUAL", null);
		insertarVersion(ORG_A, entradaId, 1, null);

		assertThatThrownBy(() -> insertarVersion(ORG_A, entradaId, 2, null))
				.as("ck_entrada_version_motivo_de_enmienda: RN-M09-004 pide que el cambio clinico "
						+ "sea trazable, y un cambio sin motivo no lo es")
				.isInstanceOf(UncategorizedSQLException.class);

		assertThatCode(() -> insertarVersion(ORG_A, entradaId, 2, "Se corrige la lateralidad"))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("dos versiones con el mismo numero en la misma entrada no entran")
	void el_numero_de_version_es_unico_por_entrada() {
		// Es la red debajo del OPTIMISTIC_FORCE_INCREMENT: si dos enmiendas concurrentes llegaran
		// a numerar igual, la segunda muere aca y no deja un historico con dos "version 2".
		long entradaId = insertarEntrada(ORG_A, historiaEnA, "EVOLUCION", "MANUAL", null);
		insertarVersion(ORG_A, entradaId, 1, null);

		assertThatThrownBy(() -> insertarVersion(ORG_A, entradaId, 1, null))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("dos entradas distintas pueden tener cada una su version 1")
	void el_unique_no_es_global() {
		// Control negativo del anterior: el unique lleva `entrada_clinica_id`, y si no lo llevara
		// una historia clinica entera admitiria una sola "version 1".
		long una = insertarEntrada(ORG_A, historiaEnA, "EVOLUCION", "MANUAL", null);
		long otra = insertarEntrada(ORG_A, historiaEnA, "INDICACION", "MANUAL", null);
		insertarVersion(ORG_A, una, 1, null);

		assertThatCode(() -> insertarVersion(ORG_A, otra, 1, null)).doesNotThrowAnyException();
	}

	// =================================================================================
	// La baja logica, en las dos tablas
	// =================================================================================

	@Test
	@DisplayName("una baja de entrada sin motivo no entra")
	void la_baja_de_entrada_exige_motivo() {
		long entradaId = insertarEntrada(ORG_A, historiaEnA, "EVOLUCION", "MANUAL", null);

		assertThatThrownBy(() -> jdbc().update("""
				UPDATE entrada_clinica SET active = 0, deleted_at = UTC_TIMESTAMP(6) WHERE id = ?
				""", entradaId))
				.as("una entrada clinica que desaparece sin explicacion es lo que RN-M09-004 "
						+ "quiere impedir")
				.isInstanceOf(UncategorizedSQLException.class);
	}

	@Test
	@DisplayName("una entrada vigente con deleted_at no entra: el cuarteto va completo o ninguno")
	void la_baja_de_entrada_es_coherente() {
		long entradaId = insertarEntrada(ORG_A, historiaEnA, "EVOLUCION", "MANUAL", null);

		assertThatThrownBy(() -> jdbc().update("""
				UPDATE entrada_clinica
				   SET active = 1, deleted_at = UTC_TIMESTAMP(6), deactivation_reason = 'x'
				 WHERE id = ?
				""", entradaId))
				.isInstanceOf(UncategorizedSQLException.class);
	}

	@Test
	@DisplayName("una baja de adjunto sin motivo no entra")
	void la_baja_de_adjunto_exige_motivo() {
		long adjuntoId = insertarAdjunto(ORG_A, historiaEnA, "ESTUDIO", CHECKSUM);

		assertThatThrownBy(() -> jdbc().update("""
				UPDATE adjunto_clinico SET active = 0, deleted_at = UTC_TIMESTAMP(6) WHERE id = ?
				""", adjuntoId))
				.isInstanceOf(UncategorizedSQLException.class);
	}

	// =================================================================================
	// V46 — categorias, estado, tamaño y el unique de contenido
	// =================================================================================

	@Test
	@DisplayName("ninguna categoria ADMINISTRATIVA entra en el adjunto clinico")
	void las_categorias_son_clinicas_y_cerradas() {
		// RN-M25-005 corre en las dos direcciones: el contenedor clinico no es el administrativo,
		// y tampoco al reves. `DNI` y `CREDENCIAL` son categorias de V40.
		for (String administrativa : List.of("DNI", "CREDENCIAL", "CONSENTIMIENTO")) {
			assertThatThrownBy(() ->
					insertarAdjunto(ORG_A, historiaEnA, administrativa, nuevoChecksum()))
					.as("%s es una categoria administrativa y no puede entrar acá", administrativa)
					.isInstanceOf(UncategorizedSQLException.class);
		}
	}

	@Test
	@DisplayName("un estado fuera de DISPONIBLE/NO_DISPONIBLE no entra, y un tamaño cero tampoco")
	void el_estado_y_el_tamano_estan_acotados() {
		long adjuntoId = insertarAdjunto(ORG_A, historiaEnA, "ESTUDIO", CHECKSUM);

		assertThatThrownBy(() -> jdbc().update(
				"UPDATE adjunto_clinico SET estado = 'BORRADO' WHERE id = ?", adjuntoId))
				.isInstanceOf(UncategorizedSQLException.class);
		assertThatThrownBy(() -> jdbc().update(
				"UPDATE adjunto_clinico SET tamano_bytes = 0 WHERE id = ?", adjuntoId))
				.as("un adjunto de cero bytes es un archivo que no se subio")
				.isInstanceOf(UncategorizedSQLException.class);
	}

	@Test
	@DisplayName("el mismo contenido dos veces en la misma historia no entra")
	void el_contenido_vigente_es_unico_por_historia() {
		insertarAdjunto(ORG_A, historiaEnA, "ESTUDIO", CHECKSUM);

		assertThatThrownBy(() -> insertarAdjunto(ORG_A, historiaEnA, "INFORME", CHECKSUM))
				.as("uk_adjunto_clinico_contenido_vigente. Es lo que hace idempotente el reintento "
						+ "de una subida, y lo garantiza la BASE, no el pre-chequeo del servicio")
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("un adjunto dado de baja libera el lugar para el mismo contenido")
	void una_baja_libera_el_unique() {
		// Es lo que hace `deleted_key`. Con un unique sobre `deleted_at` a secas, todos los
		// vigentes tendrian NULL y no colisionarian entre si: el unique protegeria el historico y
		// desprotegeria lo vigente, o sea exactamente al reves de lo que hace falta.
		long adjuntoId = insertarAdjunto(ORG_A, historiaEnA, "ESTUDIO", CHECKSUM);
		jdbc().update("""
				UPDATE adjunto_clinico
				   SET active = 0, deleted_at = UTC_TIMESTAMP(6),
				       deactivation_reason = 'cargado en la historia que no era'
				 WHERE id = ?
				""", adjuntoId);

		assertThatCode(() -> insertarAdjunto(ORG_A, historiaEnA, "ESTUDIO", CHECKSUM))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("el mismo contenido en otra organizacion entra: el unique arranca por el tenant")
	void el_contenido_no_colisiona_entre_organizaciones() {
		// El mismo consentimiento modelo, el mismo laboratorio derivado: dos centros distintos
		// pueden tener el mismo binario y compartir la fila pondria el documento de uno en la
		// historia del otro. Nunca se comparte entre organizaciones (DP-03).
		insertarAdjunto(ORG_A, historiaEnA, "ESTUDIO", CHECKSUM);

		assertThatCode(() -> insertarAdjunto(ORG_B, historiaEnB, "ESTUDIO", CHECKSUM))
				.doesNotThrowAnyException();
	}

	// =================================================================================
	// Fixtures — todo sintetico (AGENT.md seccion 10)
	// =================================================================================

	private List<String> columnasDe(String tabla) {
		return jdbc().queryForList("""
				SELECT column_name FROM information_schema.columns
				 WHERE table_schema = DATABASE() AND table_name = ?
				""", String.class, tabla);
	}

	private void crearOrganizacion(long id, String slug) {
		jdbc().update("""
				INSERT IGNORE INTO organization (id, name, slug, created_at, updated_at)
				 VALUES (?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", id, "Centro " + slug, slug);
	}

	private long abrirHistoria(long organizationId) {
		String documento = String.valueOf(SECUENCIA.incrementAndGet());
		jdbc().update("""
				INSERT INTO persona (organization_id, tipo_documento, numero_documento,
				                     documento_clave, apellido, nombre, apellido_clave,
				                     nombre_clave, created_at, updated_at)
				 VALUES (?, 'DNI', ?, ?, 'Perez', 'Ana', 'PEREZ', 'ANA',
				         UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, documento, documento);
		long personaId = jdbc().queryForObject("SELECT LAST_INSERT_ID()", Long.class);

		jdbc().update("""
				INSERT INTO historia_clinica (organization_id, persona_id, abierta_en, abierta_por,
				                              created_at, updated_at)
				 VALUES (?, ?, UTC_TIMESTAMP(6), 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, personaId);
		return jdbc().queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private long insertarEntrada(
			long organizationId, long historiaId, String tipo, String origen, Long referencia) {

		jdbc().update("""
				INSERT INTO entrada_clinica (organization_id, historia_clinica_id, tipo, ocurrio_en,
				                             origen, referencia_origen, ultimo_numero_version,
				                             registrada_en, registrada_por, created_at, updated_at)
				 VALUES (?, ?, ?, UTC_TIMESTAMP(6), ?, ?, 1, UTC_TIMESTAMP(6), 1,
				         UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, historiaId, tipo, origen, referencia);
		return jdbc().queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private void insertarVersion(
			long organizationId, long entradaId, int numero, String motivo) {

		jdbc().update("""
				INSERT INTO entrada_clinica_version (organization_id, entrada_clinica_id,
				                                     numero_version, cuerpo, motivo_enmienda,
				                                     registrada_en, registrada_por,
				                                     created_at, updated_at)
				 VALUES (?, ?, ?, 'cuerpo sintetico', ?, UTC_TIMESTAMP(6), 1,
				         UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, entradaId, numero, motivo);
	}

	private long insertarAdjunto(
			long organizationId, long historiaId, String categoria, String checksum) {

		jdbc().update("""
				INSERT INTO adjunto_clinico (organization_id, historia_clinica_id, categoria,
				                             nombre_archivo, content_type, tamano_bytes,
				                             checksum_sha256, storage_key, subido_por, subido_en,
				                             created_at, updated_at)
				 VALUES (?, ?, ?, 'estudio.pdf', 'application/pdf', 1024, ?, ?, 1,
				         UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, historiaId, categoria, checksum,
				UUID.randomUUID().toString().replace("-", ""));
		return jdbc().queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	/** Un checksum sintetico distinto por llamada: el unique de contenido no perdona repeticiones. */
	private static String nuevoChecksum() {
		return (UUID.randomUUID().toString() + UUID.randomUUID())
				.replace("-", "").substring(0, 64);
	}
}
