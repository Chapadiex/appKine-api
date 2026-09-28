package com.akine.person.infrastructure;

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
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code V50} contra MySQL real: los cinco CHECK, el unique de idempotencia y el append-only.
 *
 * <p><b>ESCRITO EL 19/09/2026 Y NUNCA EJECUTADO: Docker no estaba disponible.</b> El motor de
 * contenedores de esta maquina no arranca sin elevacion, asi que esta clase compila y no corrio.
 * Nada de lo que afirma esta verificado todavia.
 *
 * <h2>Que escenario prueba</h2>
 *
 * <p><b>{@code V50} no se aplico jamas contra un motor.</b> El registro de avance de AKINE-04.05
 * lo declara en su seccion "Sin verificar": <i>"sus cinco CHECK, el unique de idempotencia y la FK
 * autorreferencial, sin ejercer"</i>. Hasta que alguien corra esta clase no hay evidencia de que
 * la migracion siquiera <i>ejecute</i>.
 *
 * <p>Se inserta <b>directo</b>, sin pasar por ningun servicio — el mismo criterio de
 * {@code CasoClinicoMigrationIT} y {@code EntradaYAdjuntoClinicoMigrationIT}. Un invariante que
 * solo la aplicacion respeta deja de proteger en cuanto alguien escribe por otro camino: una
 * migracion de datos, un fix a mano, un modulo futuro. Y en esta tabla en particular ese "otro
 * camino" es exactamente el modo de falla que el challenge declaro indetectable.
 *
 * <h2>Por que tiene que ser un test de integracion</h2>
 *
 * <p>MySQL <b>ignoro en silencio</b> toda la sintaxis {@code CHECK} hasta 8.0.16: un
 * {@code CREATE TABLE} que menciona un CHECK y uno que lo hace cumplir se leen igual. Y en 8.4 una
 * expresion mal escrita falla con un <b>3819</b> que solo aparece al ejecutar — que es lo que esta
 * clase de test destapo en 03.06.
 *
 * <h2>Lo que se verifica</h2>
 *
 * <ol>
 *   <li><b>Tenant</b>: {@code organization_id NOT NULL} y todo indice declarado empezando por el,
 *       aunque sea derivable de la autorizacion. Derivarlo obliga a un join para filtrar, y la
 *       consulta que se olvide del join entrega el ledger entero de otro centro.</li>
 *   <li><b>Los cinco CHECK</b>, cada uno con su control negativo donde corresponde: la lista
 *       cerrada de {@code tipo} —con las dos que ningun camino emite—, la de {@code tipo_origen},
 *       la cantidad estrictamente positiva, el motivo obligatorio <b>solo</b> en
 *       {@code REVERSION}, y que solo una {@code REVERSION} pueda compensar a otro movimiento.
 *       </li>
 *   <li><b>{@code uk_autorizacion_movimiento_origen}</b>, que <b>es</b> la idempotencia. Con sus
 *       tres controles negativos: el mismo origen con otro {@code tipo} entra —por eso una
 *       reversion es posible—, otra {@code referencia_origen} entra, y otra organizacion tambien.
 *       </li>
 *   <li><b>La FK autorreferencial</b> {@code movimiento_origen_id}: existe, apunta a esta misma
 *       tabla y rechaza un movimiento inexistente.</li>
 *   <li><b>Append-only</b>: sin {@code version}, sin {@code updated_at} y sin el cuarteto de baja
 *       logica. <b>La ausencia ES el diseño</b>: un historial que se puede editar no es un
 *       historial. Si alguien agrega cualquiera de esas columnas, este test lo frena.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class AutorizacionMovimientoMigrationIT {

	private static final long ORG_A = 9481L;
	private static final long ORG_B = 9482L;

	private static final String ZONA = "America/Argentina/Cordoba";

	/** Cada corrida siembra sus propias filas: el unique de documento no perdona repeticiones. */
	private static final AtomicLong SECUENCIA = new AtomicLong(50500000L);

	@Autowired
	private DataSource dataSource;

	private JdbcTemplate jdbc;

	private long personaEnA;
	private long personaEnB;
	private long consultorioEnA;
	private long consultorioEnB;
	private long autorizacionEnA;
	private long autorizacionEnB;

	private JdbcTemplate jdbc() {
		if (jdbc == null) {
			jdbc = new JdbcTemplate(dataSource);
		}
		return jdbc;
	}

	@BeforeEach
	void sembrar() {
		crearOrganizacion(ORG_A, "movimiento-org-a");
		crearOrganizacion(ORG_B, "movimiento-org-b");
		consultorioEnA = crearConsultorio(ORG_A);
		consultorioEnB = crearConsultorio(ORG_B);
		personaEnA = crearPersona(ORG_A);
		personaEnB = crearPersona(ORG_B);
		autorizacionEnA = crearAutorizacion(ORG_A, personaEnA, consultorioEnA);
		autorizacionEnB = crearAutorizacion(ORG_B, personaEnB, consultorioEnB);
	}

	// =================================================================================
	// Tenant y forma de la tabla
	// =================================================================================

	@Test
	@DisplayName("autorizacion_movimiento lleva organization_id NOT NULL: no hay ledger global")
	void la_tabla_lleva_tenant() {
		assertThat(jdbc().queryForObject("""
				SELECT is_nullable FROM information_schema.columns
				 WHERE table_schema = DATABASE() AND table_name = 'autorizacion_movimiento'
				   AND column_name = 'organization_id'
				""", String.class))
				.isEqualTo("NO");
	}

	@Test
	@DisplayName("todo unique e indice declarado empieza por organization_id")
	void los_indices_empiezan_por_el_tenant() {
		// Un indice que no empieza por el tenant no sirve para acotar por organizacion, y un
		// unique sin el es directamente un bug de aislamiento (AGENT.md seccion 6). Se excluyen
		// los indices que MySQL crea sola para sostener cada FK, mismo filtro que
		// CasoClinicoMigrationIT.
		List<String> primeras = jdbc().queryForList("""
				SELECT DISTINCT s.column_name
				  FROM information_schema.statistics s
				 WHERE s.table_schema = DATABASE()
				   AND s.table_name = 'autorizacion_movimiento'
				   AND s.seq_in_index = 1 AND s.index_name <> 'PRIMARY'
				   AND NOT EXISTS (
				       SELECT 1 FROM information_schema.table_constraints tc
				        WHERE tc.table_schema = s.table_schema
				          AND tc.table_name = s.table_name
				          AND tc.constraint_name = s.index_name
				          AND tc.constraint_type = 'FOREIGN KEY')
				""", String.class);

		assertThat(primeras)
				.as("la tabla tiene que conservar sus indices de busqueda")
				.isNotEmpty();
		assertThat(primeras).containsOnly("organization_id");
	}

	@Test
	@DisplayName("el ledger es append-only: sin version, sin updated_at y sin baja logica")
	void el_ledger_no_se_edita() {
		// LA AUSENCIA ES EL DISEÑO. Un historial que se puede editar no es un historial: el puerto
		// de persistencia tampoco declara update ni delete, y este test es lo que impide que
		// alguien "arregle" la tabla agregandole las columnas que el resto del esquema tiene.
		assertThat(columnasDe("autorizacion_movimiento"))
				.doesNotContain("version", "updated_at", "active", "deleted_at",
						"deactivation_reason", "deleted_key");
		assertThat(columnasDe("autorizacion_movimiento"))
				.as("y si conserva lo que hace legible el hecho")
				.contains("tipo", "cantidad", "tipo_origen", "referencia_origen", "motivo",
						"movimiento_origen_id", "ocurrio_en", "actor_cuenta_id", "created_at");
	}

	@Test
	@DisplayName("el ledger no cachea el saldo: la columna vive en autorizacion")
	void el_ledger_no_duplica_el_saldo() {
		// Un saldo por fila seria una tercera copia de la verdad —la columna, la suma y el cache—
		// y la unica forma de que dos de las tres coincidan por casualidad.
		assertThat(columnasDe("autorizacion_movimiento"))
				.doesNotContain("saldo", "saldo_restante", "cantidad_consumida",
						"cantidad_autorizada");
	}

	// =================================================================================
	// Los cinco CHECK
	// =================================================================================

	@Test
	@DisplayName("un tipo fuera del catalogo no entra")
	void el_tipo_es_lista_cerrada() {
		assertThatThrownBy(() -> insertar(ORG_A, autorizacionEnA, personaEnA, "AJUSTE", 1,
				"SESION", 1L, null, null))
				.as("ck_movimiento_tipo. Un CHECK de MySQL llega como UncategorizedSQLException, "
						+ "al reves que el UNIQUE")
				.isInstanceOf(UncategorizedSQLException.class);
	}

	@Test
	@DisplayName("RESERVA y LIBERACION_DE_RESERVA entran aunque ningun camino las emita")
	void el_modelo_contempla_la_reserva_sin_que_exista_el_alcance() {
		// Se corta el ALCANCE, no el MODELO (DP-10). Las dos existen en el CHECK para no tener
		// que migrarlo el dia que exista la pre-reserva de unidades al agendar. Este es el control
		// positivo de que el CHECK las admite: sin el, alguien podria "limpiar" el CHECK dejando
		// solo las dos que se usan y nadie lo notaria hasta esa migracion.
		assertThatCode(() -> insertar(ORG_A, autorizacionEnA, personaEnA, "RESERVA", 1,
				"SESION", 41L, null, null))
				.doesNotThrowAnyException();
		assertThatCode(() -> insertar(ORG_A, autorizacionEnA, personaEnA,
				"LIBERACION_DE_RESERVA", 1, "SESION", 41L, null, null))
				.as("y conviven con la RESERVA del mismo origen porque `tipo` entra en el unique")
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("un tipo_origen fuera de SESION/MANUAL no entra")
	void el_origen_es_lista_cerrada() {
		// TURNO no esta, y es DP-05 escrito en el esquema: ninguna transicion administrativa
		// prueba que una prestacion ocurrio.
		assertThatThrownBy(() -> insertar(ORG_A, autorizacionEnA, personaEnA, "CONSUMO", 1,
				"TURNO", 2L, null, null))
				.isInstanceOf(UncategorizedSQLException.class);
	}

	@Test
	@DisplayName("una cantidad cero o negativa no entra")
	void la_cantidad_es_estrictamente_positiva() {
		// Cero unidades no es un movimiento: es una fila que no explica nada y que ensucia la
		// suma del ledger. Y el negativo esta prohibido porque el signo lo da el TIPO: con
		// numeros negativos, todo lector tiene que saber el signo de cada tipo para sumar.
		assertThatThrownBy(() -> insertar(ORG_A, autorizacionEnA, personaEnA, "CONSUMO", 0,
				"SESION", 3L, null, null))
				.isInstanceOf(UncategorizedSQLException.class);
		assertThatThrownBy(() -> insertar(ORG_A, autorizacionEnA, personaEnA, "CONSUMO", -1,
				"SESION", 4L, null, null))
				.isInstanceOf(UncategorizedSQLException.class);
	}

	@Test
	@DisplayName("una REVERSION sin motivo no entra, y un CONSUMO sin motivo si")
	void el_motivo_se_exige_solo_al_revertir() {
		// RF-M17-005 pide motivo al revertir, y aca lo exige la BASE y no solo el servicio: el dia
		// que aparezca un segundo camino de reversion, la regla sigue valiendo sin que nadie tenga
		// que acordarse de repetirla. Sin motivo, una reversion deja al que audita sin poder
		// distinguir un error de carga de un fraude.
		assertThatThrownBy(() -> insertar(ORG_A, autorizacionEnA, personaEnA, "REVERSION", 1,
				"SESION", 5L, null, null))
				.as("ck_movimiento_motivo_exigido")
				.isInstanceOf(UncategorizedSQLException.class);

		// Control negativo: si el CHECK exigiera motivo en todos, cada cierre de sesion tendria
		// que inventar una explicacion que el requisito no pide.
		assertThatCode(() -> insertar(ORG_A, autorizacionEnA, personaEnA, "CONSUMO", 1,
				"SESION", 5L, null, null))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("solo una REVERSION puede apuntar a otro movimiento")
	void la_compensacion_es_exclusiva_de_la_reversion() {
		// Un CONSUMO que apunte a otro movimiento seria una cadena que nadie sabe leer: al
		// recorrer el ledger habria que decidir si ese consumo suma o si "reemplaza" al anterior,
		// y las dos lecturas son defendibles. Por eso el CHECK corta la pregunta.
		long consumoId = insertar(ORG_A, autorizacionEnA, personaEnA, "CONSUMO", 1,
				"SESION", 6L, null, null);

		assertThatThrownBy(() -> insertar(ORG_A, autorizacionEnA, personaEnA, "CONSUMO", 1,
				"SESION", 7L, null, consumoId))
				.as("ck_movimiento_origen_solo_en_reversion")
				.isInstanceOf(UncategorizedSQLException.class);

		assertThatCode(() -> insertar(ORG_A, autorizacionEnA, personaEnA, "REVERSION", 1,
				"SESION", 6L, "Se cargo en el paciente equivocado", consumoId))
				.as("la REVERSION del mismo origen SI entra, y apuntando al consumo que compensa")
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("la FK autorreferencial rechaza un movimiento de origen inexistente")
	void el_origen_compensado_tiene_que_existir() {
		assertThatThrownBy(() -> insertar(ORG_A, autorizacionEnA, personaEnA, "REVERSION", 1,
				"SESION", 8L, "Motivo sintetico", 999999999L))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	// =================================================================================
	// El unique de idempotencia
	// =================================================================================

	@Test
	@DisplayName("dos CONSUMO del mismo origen no entran: eso ES la idempotencia")
	void el_mismo_origen_no_se_consume_dos_veces() {
		// Cerrar dos veces la misma sesion es idempotente por RN-M14-005, asi que el consumo
		// tambien tiene que serlo. Sin este unique, el reintento de un cierre descuenta dos veces
		// la misma atencion y el paciente pierde una unidad que nunca uso.
		insertar(ORG_A, autorizacionEnA, personaEnA, "CONSUMO", 1, "SESION", 10L, null, null);

		assertThatThrownBy(() -> insertar(ORG_A, autorizacionEnA, personaEnA, "CONSUMO", 1,
				"SESION", 10L, null, null))
				.as("uk_autorizacion_movimiento_origen")
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("una REVERSION del mismo origen SI entra, y la segunda no")
	void el_tipo_entra_en_la_clave_a_proposito() {
		// Es el punto entero de meter `tipo` en el unique. Una REVERSION del mismo origen es OTRA
		// fila y tiene que poder entrar —si no, no habria forma de compensar—; una SEGUNDA
		// reversion del mismo origen si es un duplicado, y eso es lo que la hace idempotente en
		// vez de meramente segura: un doble click devolveria dos unidades donde se gasto una.
		long consumoId = insertar(ORG_A, autorizacionEnA, personaEnA, "CONSUMO", 1,
				"SESION", 11L, null, null);

		assertThatCode(() -> insertar(ORG_A, autorizacionEnA, personaEnA, "REVERSION", 1,
				"SESION", 11L, "Error de carga", consumoId))
				.doesNotThrowAnyException();

		assertThatThrownBy(() -> insertar(ORG_A, autorizacionEnA, personaEnA, "REVERSION", 1,
				"SESION", 11L, "Error de carga otra vez", consumoId))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("dos consumos de sesiones distintas entran los dos")
	void el_unique_no_bloquea_origenes_distintos() {
		// Control negativo: sin `referencia_origen` en la clave, una autorizacion admitiria UN
		// solo consumo en toda su vida y el modelo seria inservible.
		insertar(ORG_A, autorizacionEnA, personaEnA, "CONSUMO", 1, "SESION", 12L, null, null);

		assertThatCode(() -> insertar(ORG_A, autorizacionEnA, personaEnA, "CONSUMO", 1,
				"SESION", 13L, null, null))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("el mismo origen en otra organizacion entra: el unique arranca por tenant")
	void el_unique_no_colisiona_entre_organizaciones() {
		// Los ids de sesion no son globales entre tenants, asi que sin organization_id en la clave
		// el consumo de un centro podria bloquear el de otro por pura coincidencia de id.
		insertar(ORG_A, autorizacionEnA, personaEnA, "CONSUMO", 1, "SESION", 14L, null, null);

		assertThatCode(() -> insertar(ORG_B, autorizacionEnB, personaEnB, "CONSUMO", 1,
				"SESION", 14L, null, null))
				.doesNotThrowAnyException();
	}

	// =================================================================================
	// Fixtures — todo sintetico (AGENT.md seccion 10)
	// =================================================================================

	/** Un movimiento insertado DIRECTO, sin pasar por ningun servicio. Devuelve su id. */
	@SuppressWarnings("checkstyle:ParameterNumber")
	private long insertar(
			long organizationId,
			long autorizacionId,
			long personaId,
			String tipo,
			int cantidad,
			String tipoOrigen,
			Long referenciaOrigen,
			String motivo,
			Long movimientoOrigenId) {

		jdbc().update("""
				INSERT INTO autorizacion_movimiento
				       (organization_id, autorizacion_id, persona_id, consultorio_id, tipo,
				        cantidad, tipo_origen, referencia_origen, motivo, movimiento_origen_id,
				        ocurrio_en, actor_cuenta_id, created_at)
				 VALUES (?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), NULL,
				         UTC_TIMESTAMP(6))
				""", organizationId, autorizacionId, personaId, tipo, cantidad, tipoOrigen,
				referenciaOrigen, motivo, movimientoOrigenId);
		return ultimoId();
	}

	private List<String> columnasDe(String tabla) {
		return jdbc().queryForList("""
				SELECT column_name FROM information_schema.columns
				 WHERE table_schema = DATABASE() AND table_name = ?
				""", String.class, tabla);
	}

	private void crearOrganizacion(long id, String slug) {
		jdbc().update("""
				INSERT IGNORE INTO organization (id, name, slug, timezone, created_at, updated_at)
				 VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", id, "Centro " + slug, slug, ZONA);
	}

	private long crearConsultorio(long organizationId) {
		jdbc().update("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version,
				                         created_at, updated_at)
				 VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, "Sede " + SECUENCIA.incrementAndGet(), ZONA);
		return ultimoId();
	}

	private long crearPersona(long organizationId) {
		String documento = String.valueOf(SECUENCIA.incrementAndGet());
		jdbc().update("""
				INSERT INTO persona (organization_id, tipo_documento, numero_documento,
				                     documento_clave, apellido, nombre, apellido_clave,
				                     nombre_clave, created_at, updated_at)
				 VALUES (?, 'DNI', ?, ?, 'Sintetica', 'Paciente', 'SINTETICA', 'PACIENTE',
				         UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, documento, documento);
		return ultimoId();
	}

	/**
	 * Una autorizacion APROBADA con tope alto.
	 *
	 * <p>El tope es alto a proposito: esta clase mide el <b>esquema del ledger</b> y no el saldo,
	 * asi que ninguna insercion tiene que chocar contra {@code ck_autorizacion_consumo_coherente}
	 * de {@code V44}. El saldo lo ejercen {@code ConsumoConcurrenteIT} y
	 * {@code LedgerCoherenteIT}, por el camino real.
	 */
	private long crearAutorizacion(long organizationId, long personaId, long consultorioId) {
		String sufijo = String.valueOf(SECUENCIA.incrementAndGet());
		long financiadorId = crearFinanciador(organizationId, "OS-" + sufijo);
		long planId = crearPlan(organizationId, financiadorId, "P-" + sufijo);
		long coberturaId =
				crearCobertura(organizationId, personaId, financiadorId, planId, sufijo);
		long especialidadId = crearEspecialidad(organizationId, "E-" + sufijo);
		long practicaId = crearPractica(organizationId, especialidadId, "PR-" + sufijo);

		jdbc().update("""
				INSERT INTO autorizacion (organization_id, persona_id, consultorio_id,
				                          cobertura_id, practica_id, numero, estado,
				                          cantidad_autorizada, cantidad_consumida, vigencia_desde,
				                          active, version, created_at, updated_at)
				 VALUES (?, ?, ?, ?, ?, ?, 'APROBADA', 9999, 0, '2020-01-01', 1, 0,
				         UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, personaId, consultorioId, coberturaId, practicaId,
				"AUT-" + sufijo);
		return ultimoId();
	}

	private long crearFinanciador(long organizationId, String codigo) {
		jdbc().update("""
				INSERT INTO financiador (organization_id, codigo, nombre, tipo, active, version,
				                         created_at, updated_at)
				 VALUES (?, ?, ?, 'PREPAGA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, codigo, "Financiador " + codigo);
		return ultimoId();
	}

	private long crearPlan(long organizationId, long financiadorId, String codigo) {
		jdbc().update("""
				INSERT INTO plan_cobertura (organization_id, financiador_id, codigo, nombre,
				                            vigencia_desde, requiere_autorizacion,
				                            requiere_credencial, active, version,
				                            created_at, updated_at)
				 VALUES (?, ?, ?, ?, '2020-01-01', 1, 0, 1, 0,
				         UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, financiadorId, codigo, "Plan " + codigo);
		return ultimoId();
	}

	/** La copia congelada va ENTERA: {@code ck_cobertura_referencia_coherente} rechaza media. */
	private long crearCobertura(
			long organizationId, long personaId, long financiadorId, long planId, String sufijo) {

		jdbc().update("""
				INSERT INTO cobertura_paciente (
				        organization_id, persona_id, tipo,
				        financiador_id, financiador_codigo, financiador_nombre, financiador_tipo,
				        plan_id, plan_codigo, plan_nombre,
				        requeria_autorizacion, requeria_credencial, referencia_capturada_el,
				        numero_afiliado, vigencia_desde, principal, active, version,
				        created_at, updated_at)
				 VALUES (?, ?, 'FINANCIADA', ?, ?, ?, 'PREPAGA', ?, ?, ?, 1, 0, UTC_TIMESTAMP(6),
				         ?, '2020-01-01', 1, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""",
				organizationId, personaId,
				financiadorId, "OS-" + sufijo, "Financiador OS-" + sufijo,
				planId, "P-" + sufijo, "Plan P-" + sufijo,
				"AF-" + sufijo);
		return ultimoId();
	}

	private long crearEspecialidad(long organizationId, String codigo) {
		jdbc().update("""
				INSERT INTO especialidad (organization_id, codigo, name, valid_from, active,
				                          version, created_at, updated_at)
				 VALUES (?, ?, ?, '2020-01-01 00:00:00.000000', 1, 0,
				         UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, codigo, "Especialidad " + codigo);
		return ultimoId();
	}

	private long crearPractica(long organizationId, long especialidadId, String codigo) {
		jdbc().update("""
				INSERT INTO practica (organization_id, especialidad_id, codigo, name, valid_from,
				                      active, version, created_at, updated_at)
				 VALUES (?, ?, ?, ?, '2020-01-01 00:00:00.000000', 1, 0,
				         UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, especialidadId, codigo, "Practica " + codigo);
		return ultimoId();
	}

	private long ultimoId() {
		return jdbc().queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}
}
