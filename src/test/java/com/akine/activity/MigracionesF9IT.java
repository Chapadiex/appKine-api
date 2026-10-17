package com.akine.activity;

import com.akine.TestcontainersConfiguration;
import com.akine.activity.ActividadItFixture.Tenant;
import com.akine.activity.application.InscripcionService;
import com.akine.billing.MigracionItSoporte;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.akine.billing.MigracionItSoporte.ahora;
import static com.akine.billing.MigracionItSoporte.con;
import static com.akine.billing.MigracionItSoporte.entra;
import static com.akine.billing.MigracionItSoporte.fila;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Escenarios 40, 47, 54 y 58 de {@code docs/tests-diferidos.md}: {@code V58}, {@code V60} y
 * {@code V61} contra el motor, insertando DIRECTO y sin servicio.
 *
 * <p>Que las migraciones ejecuten lo prueba que el contexto arranque. Lo que esta clase prueba es
 * que cada constraint haga lo que su comentario dice, <b>en las dos direcciones</b>: la fila que
 * tiene que rechazar se rechaza por ESA constraint, y la que tiene que entrar entra. La
 * multi-tenencia de las cuatro tablas (organization_id NOT NULL) ya la mira
 * {@code EsquemaMultiTenantIT} para todo el esquema; aca se mira que los indices declarados
 * empiecen por ella.
 *
 * <p>El modo de falla que importa no es el error de sintaxis: es un {@code deleted_key} que
 * compila y calcula mal, con el que la misma persona se anota dos veces o las consultas de
 * solapamiento dejan de ver clases vivas sin que nada falle.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class MigracionesF9IT {

	private static final String CENTINELA = "1970-01-01T00:00";

	@Autowired private InscripcionService inscripcionService;
	@Autowired private JdbcTemplate jdbc;

	private MigracionItSoporte db;
	private ActividadItFixture fx;
	private Tenant tenant;

	@BeforeEach
	void armar() {
		db = new MigracionItSoporte(jdbc);
		fx = new ActividadItFixture(jdbc, inscripcionService);
		tenant = fx.crearTenant(10, 3);
	}

	// =================================================================================
	// Lo comun a las cuatro tablas
	// =================================================================================

	@Test
	@DisplayName("40/47/58 · Todo indice ix_/uk_ de las tablas de F9 empieza por organization_id")
	void los_indices_empiezan_por_el_tenant() {
		List<Map<String, Object>> primeras = jdbc.queryForList("""
				SELECT table_name AS tabla, index_name AS indice, column_name AS columna
				  FROM information_schema.statistics
				 WHERE table_schema = DATABASE() AND seq_in_index = 1
				   AND table_name IN ('clase_programada', 'clase_evento', 'inscripcion_clase',
				                      'asistencia_actividad', 'asistencia_evento')
				   AND (index_name LIKE 'ix\\_%' OR index_name LIKE 'uk\\_%')
				""");

		assertThat(primeras).as("hay indices declarados que mirar").hasSizeGreaterThanOrEqualTo(12);
		assertThat(primeras)
				.allSatisfy(indice -> assertThat(indice.get("columna"))
						.as("%s.%s", indice.get("tabla"), indice.get("indice"))
						.isEqualTo("organization_id"));
	}

	@Test
	@DisplayName("40/47 · deleted_key es STORED y vale el centinela mientras deleted_at es nulo")
	void deleted_key_stored_y_centinela() {
		db.esGeneradaStored("clase_programada", "deleted_key", "1970-01-01");
		db.esGeneradaStored("inscripcion_clase", "deleted_key", "1970-01-01");

		long clase = db.insertar("clase_programada", clase());
		long inscripcion = db.insertar("inscripcion_clase", inscripcion(clase, tenant.persona(0)));

		assertThat(deletedKey("clase_programada", clase)).isEqualTo(LocalDateTime.parse(CENTINELA));
		assertThat(deletedKey("inscripcion_clase", inscripcion)).isEqualTo(LocalDateTime.parse(CENTINELA));
	}

	@Test
	@DisplayName("40/47/58 · Las foreign keys de las tablas de F9 apuntan donde dicen")
	void las_foreign_keys_apuntan_donde_dicen() {
		Map<String, String> fks = jdbc.queryForList("""
				SELECT constraint_name AS fk, referenced_table_name AS destino
				  FROM information_schema.key_column_usage
				 WHERE table_schema = DATABASE() AND referenced_table_name IS NOT NULL
				   AND table_name IN ('clase_programada', 'clase_evento', 'inscripcion_clase',
				                      'asistencia_actividad', 'asistencia_evento')
				""").stream().collect(Collectors.toMap(
				f -> (String) f.get("fk"), f -> (String) f.get("destino"), (a, b) -> a));

		assertThat(fks).containsAllEntriesOf(Map.ofEntries(
				Map.entry("fk_clase_organization", "organization"),
				Map.entry("fk_clase_consultorio", "consultorio"),
				Map.entry("fk_clase_oferta", "oferta_servicio_consultorio"),
				Map.entry("fk_clase_espacio", "espacio"),
				Map.entry("fk_clase_evento_clase", "clase_programada"),
				Map.entry("fk_inscripcion_clase", "clase_programada"),
				Map.entry("fk_inscripcion_persona", "persona"),
				Map.entry("fk_asistencia_inscripcion", "inscripcion_clase"),
				Map.entry("fk_asistencia_clase", "clase_programada"),
				Map.entry("fk_asistencia_evento_asistencia", "asistencia_actividad")));
	}

	// =================================================================================
	// V58 — clase_programada y clase_evento
	// =================================================================================

	@Test
	@DisplayName("40 · uk_clase_idempotencia choca en la misma organizacion y no en otra")
	void idempotencia_de_la_clase() {
		db.insertar("clase_programada", con(clase(), "idempotency_key", "k-clase", "request_hash", hash('a')));
		db.violaUnique("uk_clase_idempotencia", () -> db.insertar("clase_programada",
				con(clase(), "idempotency_key", "k-clase", "request_hash", hash('a'))));

		Tenant otro = fx.crearTenant(10, 0);
		entra(() -> db.insertar("clase_programada", con(claseDe(otro),
				"idempotency_key", "k-clase", "request_hash", hash('a'))));
	}

	@Test
	@DisplayName("40 · Los cuatro CHECK de V58, en sus dos direcciones")
	void los_check_de_la_clase() {
		LocalDateTime inicio = ahora().plusDays(3);
		db.violaCheck("ck_clase_intervalo", () -> db.insertar("clase_programada",
				con(clase(), "inicio", inicio, "fin", inicio)));
		entra(() -> db.insertar("clase_programada", con(clase(), "inicio", inicio, "fin", inicio.plusMinutes(1))));

		// Capacidad 1 es un turno individual con otro nombre: es lo que este CHECK impide.
		db.violaCheck("ck_clase_capacidad", () -> db.insertar("clase_programada", con(clase(), "capacidad", 1)));
		entra(() -> db.insertar("clase_programada", con(clase(), "capacidad", 2)));

		db.violaCheck("ck_clase_idempotencia_completa", () -> db.insertar("clase_programada",
				con(clase(), "idempotency_key", "sin-hash")));
		db.violaCheck("ck_clase_idempotencia_completa", () -> db.insertar("clase_programada",
				con(clase(), "request_hash", hash('b'))));

		db.violaCheck("ck_clase_cancelacion_completa", () -> db.insertar("clase_programada",
				con(clase(), "estado", "CANCELADA", "deleted_at", ahora())));
		db.violaCheck("ck_clase_cancelacion_completa", () -> db.insertar("clase_programada",
				con(clase(), "motivo_cancelacion", "Lluvia")));
		entra(() -> db.insertar("clase_programada",
				con(clase(), "estado", "CANCELADA", "deleted_at", ahora(), "motivo_cancelacion", "Lluvia")));
	}

	@Test
	@DisplayName("40 · clase_evento es append-only en el esquema: sin version, updated_at ni baja logica")
	void clase_evento_sin_columnas_de_edicion() {
		List<String> columnas = jdbc.queryForList("""
				SELECT column_name FROM information_schema.columns
				 WHERE table_schema = DATABASE() AND table_name = 'clase_evento'
				""", String.class);
		assertThat(columnas).contains("organization_id", "clase_id", "tipo", "ocurrido_en")
				.doesNotContain("version", "updated_at", "deleted_at", "deleted_key");
	}

	// =================================================================================
	// V60 — inscripcion_clase y el cupo de la clase
	// =================================================================================

	@Test
	@DisplayName("47 · La misma persona dos veces viva choca; despues de cancelar entra de nuevo")
	void la_persona_una_sola_vez_viva() {
		long clase = db.insertar("clase_programada", clase());
		long primera = db.insertar("inscripcion_clase", inscripcion(clase, tenant.persona(0)));

		db.violaUnique("uk_inscripcion_clase_persona",
				() -> db.insertar("inscripcion_clase", inscripcion(clase, tenant.persona(0))));
		entra(() -> db.insertar("inscripcion_clase", inscripcion(clase, tenant.persona(1))));

		cancelarInscripcion(primera);
		entra(() -> db.insertar("inscripcion_clase", inscripcion(clase, tenant.persona(0))));
	}

	@Test
	@DisplayName("47 · uk_inscripcion_idempotencia choca en la misma organizacion y no en otra")
	void idempotencia_de_la_inscripcion() {
		long clase = db.insertar("clase_programada", clase());
		db.insertar("inscripcion_clase", con(inscripcion(clase, tenant.persona(0)),
				"idempotency_key", "k-insc", "request_hash", hash('c')));
		db.violaUnique("uk_inscripcion_idempotencia", () -> db.insertar("inscripcion_clase",
				con(inscripcion(clase, tenant.persona(1)), "idempotency_key", "k-insc", "request_hash", hash('c'))));

		Tenant otro = fx.crearTenant(10, 1);
		long claseAjena = db.insertar("clase_programada", claseDe(otro));
		entra(() -> db.insertar("inscripcion_clase", con(inscripcionDe(otro, claseAjena, otro.persona(0)),
				"idempotency_key", "k-insc", "request_hash", hash('c'))));
	}

	@Test
	@DisplayName("47 · Los tres CHECK de inscripcion_clase y ck_clase_cupo_ocupado, en sus dos direcciones")
	void los_check_de_la_inscripcion_y_del_cupo() {
		long clase = db.insertar("clase_programada", con(clase(), "capacidad", 3));

		db.violaCheck("ck_inscripcion_idempotencia_completa", () -> db.insertar("inscripcion_clase",
				con(inscripcion(clase, tenant.persona(0)), "idempotency_key", "sin-hash")));
		db.violaCheck("ck_inscripcion_cancelacion_completa", () -> db.insertar("inscripcion_clase",
				con(inscripcion(clase, tenant.persona(0)), "deleted_at", ahora())));
		db.violaCheck("ck_inscripcion_cancelacion_completa", () -> db.insertar("inscripcion_clase",
				con(inscripcion(clase, tenant.persona(0)), "motivo_cancelacion", "Se fue")));
		db.violaCheck("ck_inscripcion_posicion", () -> db.insertar("inscripcion_clase",
				con(inscripcion(clase, tenant.persona(0)), "estado", "LISTA_ESPERA", "posicion_espera", 0)));
		entra(() -> db.insertar("inscripcion_clase",
				con(inscripcion(clase, tenant.persona(0)), "estado", "LISTA_ESPERA", "posicion_espera", 1)));

		db.violaCheck("ck_clase_cupo_ocupado",
				() -> jdbc.update("UPDATE clase_programada SET cupo_ocupado = -1 WHERE id = ?", clase));
		db.violaCheck("ck_clase_cupo_ocupado",
				() -> jdbc.update("UPDATE clase_programada SET cupo_ocupado = 4 WHERE id = ?", clase));
		entra(() -> jdbc.update("UPDATE clase_programada SET cupo_ocupado = 3 WHERE id = ?", clase));
		db.violaCheck("ck_clase_cupo_ocupado",
				() -> jdbc.update("UPDATE clase_programada SET capacidad = 2 WHERE id = ?", clase));
	}

	// =================================================================================
	// V61 — asistencia_actividad, asistencia_evento y los estados operativos de la clase
	// =================================================================================

	@Test
	@DisplayName("54 · uk_asistencia_clase_persona: choca en la misma clase, entra en otra, y cancelar y volver a inscribirse NO habilita una segunda")
	void una_asistencia_por_persona_y_clase() {
		long clase = db.insertar("clase_programada", clase());
		long otraClase = db.insertar("clase_programada", clase());
		long inscripcion = db.insertar("inscripcion_clase", inscripcion(clase, tenant.persona(0)));
		db.insertar("asistencia_actividad", asistencia(clase, tenant.persona(0), inscripcion));

		// La misma persona en la misma clase, desde otra inscripcion: es la segunda direccion del
		// unique, la que el de inscripcion no cubre.
		cancelarInscripcion(inscripcion);
		long reinscripcion = db.insertar("inscripcion_clase", inscripcion(clase, tenant.persona(0)));
		db.violaUnique("uk_asistencia_clase_persona", () -> db.insertar("asistencia_actividad",
				asistencia(clase, tenant.persona(0), reinscripcion)));

		long enOtraClase = db.insertar("inscripcion_clase", inscripcion(otraClase, tenant.persona(0)));
		entra(() -> db.insertar("asistencia_actividad", asistencia(otraClase, tenant.persona(0), enOtraClase)));
	}

	@Test
	@DisplayName("54 · uk_asistencia_inscripcion: una inscripcion respalda una sola asistencia")
	void una_asistencia_por_inscripcion() {
		long clase = db.insertar("clase_programada", clase());
		long inscripcion = db.insertar("inscripcion_clase", inscripcion(clase, tenant.persona(0)));
		db.insertar("asistencia_actividad", asistencia(clase, tenant.persona(0), inscripcion));

		// Otra persona colgada de la MISMA inscripcion: el unique de (clase, persona) no lo ve.
		db.violaUnique("uk_asistencia_inscripcion", () -> db.insertar("asistencia_actividad",
				asistencia(clase, tenant.persona(1), inscripcion)));
	}

	@Test
	@DisplayName("54/58 · ck_asistencia_correccion_completa y ck_asistencia_evento_correccion, en sus dos direcciones")
	void los_check_de_la_asistencia() {
		long clase = db.insertar("clase_programada", clase());
		long inscripcion = db.insertar("inscripcion_clase", inscripcion(clase, tenant.persona(0)));

		db.violaCheck("ck_asistencia_correccion_completa", () -> db.insertar("asistencia_actividad",
				con(asistencia(clase, tenant.persona(0), inscripcion), "correcciones", 1)));
		db.violaCheck("ck_asistencia_correccion_completa", () -> db.insertar("asistencia_actividad",
				con(asistencia(clase, tenant.persona(0), inscripcion), "corregida_en", ahora(),
						"corregida_por_cuenta_id", tenant.actor().accountId())));
		long asistencia = db.insertar("asistencia_actividad", con(asistencia(clase, tenant.persona(0), inscripcion),
				"correcciones", 1, "corregida_en", ahora(), "corregida_por_cuenta_id", tenant.actor().accountId()));

		entra(() -> db.insertar("asistencia_evento", evento(clase, asistencia, "REGISTRO", null, "PRESENTE", null)));
		db.violaCheck("ck_asistencia_evento_correccion", () -> db.insertar("asistencia_evento",
				evento(clase, asistencia, "CORRECCION", "PRESENTE", "AUSENTE", null)));
		db.violaCheck("ck_asistencia_evento_correccion", () -> db.insertar("asistencia_evento",
				evento(clase, asistencia, "CORRECCION", "PRESENTE", "PRESENTE", "Sin cambio")));
		entra(() -> db.insertar("asistencia_evento",
				evento(clase, asistencia, "CORRECCION", "PRESENTE", "AUSENTE", "Se marco por error")));
	}

	@Test
	@DisplayName("58 · El estado de la clase acepta los cuatro valores y ningun CHECK lo restringe")
	void los_cuatro_estados_de_la_clase() {
		for (String estado : List.of("PROGRAMADA", "EN_CURSO", "REALIZADA")) {
			entra(() -> db.insertar("clase_programada", con(clase(), "estado", estado)));
		}
		entra(() -> db.insertar("clase_programada",
				con(clase(), "estado", "CANCELADA", "deleted_at", ahora(), "motivo_cancelacion", "Lluvia")));

		List<String> clausulas = jdbc.queryForList("""
				SELECT cc.check_clause
				  FROM information_schema.table_constraints tc
				  JOIN information_schema.check_constraints cc
				    ON cc.constraint_schema = tc.constraint_schema AND cc.constraint_name = tc.constraint_name
				 WHERE tc.table_schema = DATABASE() AND tc.table_name = 'clase_programada'
				   AND tc.constraint_type = 'CHECK'
				""", String.class);
		assertThat(clausulas).as("los CHECK de la clase que dejaron V58, V60 y V61").hasSize(5)
				.noneMatch(clausula -> clausula.contains("`estado`"));
	}

	// =================================================================================
	// Filas validas por defecto
	// =================================================================================

	private Map<String, Object> clase() {
		return claseDe(tenant);
	}

	private static Map<String, Object> claseDe(Tenant t) {
		LocalDateTime inicio = ahora().plusDays(7);
		return fila(
				"organization_id", t.organizationId(),
				"consultorio_id", t.consultorioId(),
				"oferta_id", t.ofertaId(),
				"inicio", inicio,
				"fin", inicio.plusHours(1),
				"capacidad", 5,
				"estado", "PROGRAMADA",
				"programado_por_cuenta_id", t.actor().accountId(),
				"programado_en", ahora());
	}

	private Map<String, Object> inscripcion(long clase, long persona) {
		return inscripcionDe(tenant, clase, persona);
	}

	private static Map<String, Object> inscripcionDe(Tenant t, long clase, long persona) {
		return fila(
				"organization_id", t.organizationId(),
				"consultorio_id", t.consultorioId(),
				"clase_id", clase,
				"persona_id", persona,
				"estado", "RESERVADA",
				"inscripto_por_cuenta_id", t.actor().accountId(),
				"inscripto_en", ahora());
	}

	private Map<String, Object> asistencia(long clase, long persona, long inscripcion) {
		return fila(
				"organization_id", tenant.organizationId(),
				"consultorio_id", tenant.consultorioId(),
				"clase_id", clase,
				"persona_id", persona,
				"inscripcion_id", inscripcion,
				"resultado", "PRESENTE",
				"origen", "MOSTRADOR",
				"registrada_en", ahora(),
				"registrada_por_cuenta_id", tenant.actor().accountId());
	}

	private Map<String, Object> evento(long clase, long asistencia, String tipo, String anterior,
			String nuevo, String motivo) {
		return fila(
				"organization_id", tenant.organizationId(),
				"consultorio_id", tenant.consultorioId(),
				"clase_id", clase,
				"asistencia_id", asistencia,
				"tipo", tipo,
				"resultado_anterior", anterior,
				"resultado_nuevo", nuevo,
				"motivo", motivo,
				"ocurrido_en", ahora());
	}

	private void cancelarInscripcion(long id) {
		jdbc.update("""
				UPDATE inscripcion_clase
				   SET estado = 'CANCELADA', deleted_at = UTC_TIMESTAMP(6), motivo_cancelacion = 'Baja sintetica'
				 WHERE id = ?
				""", id);
	}

	private LocalDateTime deletedKey(String tabla, long id) {
		return jdbc.queryForObject("SELECT deleted_key FROM " + tabla + " WHERE id = ?", LocalDateTime.class, id);
	}

	private static String hash(char c) {
		return String.valueOf(c).repeat(64);
	}
}
