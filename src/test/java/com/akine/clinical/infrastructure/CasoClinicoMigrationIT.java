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
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code V47} y {@code V48} contra MySQL real: los CHECK, los uniques y la columna generada.
 *
 * <p><b>ESCRITO EL 19/09/2026 Y NUNCA EJECUTADO: Docker no estaba disponible.</b> El motor de
 * contenedores de esta maquina no arranca sin elevacion, asi que esta clase compila y no corrio.
 * Nada de lo que afirma esta verificado todavia.
 *
 * <h2>Que escenario prueba</h2>
 *
 * <p><b>Las dos migraciones de esta etapa no se aplicaron jamas contra un motor.</b> Se escribieron
 * con Docker caido, asi que hasta que alguien corra esta clase no hay evidencia de que
 * {@code V47} y {@code V48} siquiera <i>ejecuten</i> — mucho menos de que sus once CHECK y sus
 * cuatro uniques hagan lo que sus comentarios dicen.
 *
 * <p>Se inserta <b>directo</b>, sin pasar por ningun servicio, que es el mismo criterio de
 * {@code EntradaYAdjuntoClinicoMigrationIT} y {@code HistoriaClinicaMigrationIT}. Un invariante que
 * solo la aplicacion respeta deja de proteger en cuanto alguien escribe por otro camino: una
 * migracion de datos, un fix a mano, un modulo futuro.
 *
 * <h2>Por que importa que sea un test de integracion y no otra cosa</h2>
 *
 * <p>MySQL ignoro en silencio toda la sintaxis {@code CHECK} hasta 8.0.16: un {@code CREATE TABLE}
 * que menciona un CHECK y un {@code CREATE TABLE} que lo hace cumplir se leen igual. Y en 8.4 una
 * expresion mal escrita sobre una columna generada falla con un <b>3819</b> que solo aparece al
 * ejecutar — que es exactamente lo que esta clase de test destapo en 03.06.
 *
 * <h2>Lo que se verifica</h2>
 *
 * <ol>
 *   <li><b>Tenant en las cinco tablas</b> y en todos sus indices declarados. {@code caso_evento} y
 *       {@code caso_profesional} lo llevan aunque sea derivable del caso: derivarlo obliga a un
 *       join para filtrar, y la consulta que se olvide del join es una fuga que ningun test de la
 *       etapa ve, porque los tests de una etapa usan un solo tenant.</li>
 *   <li><b>{@code ck_caso_clinico_cierre_coherente}</b>, que ata las cuatro columnas del cierre.
 *       Sin el, un caso ACTIVO con {@code cerrado_en} puesto haria que una consulta por
 *       {@code cerrado_en IS NOT NULL} devolviera casos abiertos.</li>
 *   <li><b>{@code uk_caso_numero}</b>, el respaldo del numerador — no el mecanismo.</li>
 *   <li><b>Que NO haya baja logica de caso.</b> La ausencia del cuarteto
 *       {@code active}/{@code deleted_at}/… <i>es</i> la decision del challenge §5: dos formas de
 *       que un caso "no este" es como se construye una consulta que se olvida de una.</li>
 *   <li><b>{@code hasta_key}</b>: existe, es {@code STORED} y vale el centinela mientras el
 *       profesional sigue en el equipo. Con {@code hasta} a secas, varios {@code NULL} no
 *       colisionan en MySQL y el unique protegeria el historico desprotegiendo lo vigente.</li>
 *   <li><b>{@code caso_evento} es append-only</b>: sin {@code version}, sin {@code updated_at} y
 *       sin baja. Un historial que se puede editar no es un historial.</li>
 *   <li><b>{@code ck_sesion_numero_en_caso}</b> de {@code V48}, y en particular que <b>no pueda
 *       haber {@code numero_en_caso} sin {@code caso_id}</b>: un correlativo que no numera dentro
 *       de nada aparece en un informe como "la octava sesion de su tratamiento" y no hay
 *       tratamiento al cual pertenezca.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class CasoClinicoMigrationIT {

	private static final long ORG_A = 9471L;
	private static final long ORG_B = 9472L;

	private static final String ZONA = "America/Argentina/Cordoba";

	/** Cada corrida siembra sus propias filas: el unique de documento no perdona repeticiones. */
	private static final AtomicLong SECUENCIA = new AtomicLong(40470000L);

	@Autowired
	private DataSource dataSource;

	private JdbcTemplate jdbc;

	private long historiaEnA;
	private long historiaEnB;
	private long consultorioEnA;
	private long ofertaEnA;
	private long membershipEnA;

	private JdbcTemplate jdbc() {
		if (jdbc == null) {
			jdbc = new JdbcTemplate(dataSource);
		}
		return jdbc;
	}

	@BeforeEach
	void sembrar() {
		crearOrganizacion(ORG_A, "caso-org-a");
		crearOrganizacion(ORG_B, "caso-org-b");
		historiaEnA = abrirHistoria(ORG_A);
		historiaEnB = abrirHistoria(ORG_B);
		consultorioEnA = crearConsultorio(ORG_A);
		ofertaEnA = crearOferta(ORG_A, consultorioEnA);
		membershipEnA = crearMembership(ORG_A, consultorioEnA);
	}

	// =================================================================================
	// Tenant y forma de las tablas
	// =================================================================================

	@Test
	@DisplayName("las cinco tablas nuevas llevan organization_id NOT NULL: no hay caso global")
	void las_cinco_tablas_llevan_tenant() {
		for (String tabla : tablasDelCaso()) {
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
	@DisplayName("todo unique e indice declarado de las cinco tablas empieza por organization_id")
	void los_indices_empiezan_por_el_tenant() {
		// Un indice que no empieza por el tenant no sirve para acotar por organizacion, y un
		// unique sin el es directamente un bug de aislamiento (AGENT.md seccion 5). Se excluyen
		// los indices que MySQL crea sola para sostener cada FK, mismo filtro que
		// EntradaYAdjuntoClinicoMigrationIT.
		for (String tabla : tablasDelCaso()) {
			List<String> primeras = jdbc().queryForList("""
					SELECT DISTINCT s.column_name
					  FROM information_schema.statistics s
					 WHERE s.table_schema = DATABASE() AND s.table_name = ?
					   AND s.seq_in_index = 1 AND s.index_name <> 'PRIMARY'
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
	@DisplayName("los dos indices que V48 agrega a sesion tambien empiezan por el tenant")
	void los_indices_de_v48_empiezan_por_el_tenant() {
		// `sesion` es de encounter y ya traia sus propios indices, asi que no se puede barrer la
		// tabla entera: se miran los dos que esta etapa agrega, por nombre.
		for (String indice : List.of("uk_sesion_numero_en_caso", "ix_sesion_caso")) {
			assertThat(jdbc().queryForObject("""
					SELECT column_name FROM information_schema.statistics
					 WHERE table_schema = DATABASE() AND table_name = 'sesion'
					   AND index_name = ? AND seq_in_index = 1
					""", String.class, indice))
					.as("%s tiene que empezar por organization_id", indice)
					.isEqualTo("organization_id");
		}
	}

	@Test
	@DisplayName("caso_clinico NO tiene baja logica: cerrar no es borrar")
	void el_caso_no_se_da_de_baja() {
		// Challenge seccion 5, escrito en el esquema. `active` al lado de `estado` daria dos
		// formas de que un caso "no este", y una consulta se olvidaria de una. Si alguien agrega
		// el cuarteto, este test lo frena.
		assertThat(columnasDe("caso_clinico"))
				.doesNotContain("active", "deleted_at", "deactivation_reason", "deleted_key");
	}

	@Test
	@DisplayName("caso_evento es append-only: sin version, sin updated_at y sin baja")
	void el_historial_no_se_edita() {
		// Un historial que se puede editar no es un historial (ADR-0011). Mismo diseño que
		// turno_evento en V38.
		assertThat(columnasDe("caso_evento"))
				.doesNotContain("version", "updated_at", "active", "deleted_at",
						"deactivation_reason", "deleted_key");
		assertThat(columnasDe("caso_evento"))
				.as("y si conserva lo que hace legible el historial")
				.contains("tipo", "estado_anterior", "estado_nuevo", "motivo", "ocurrio_en",
						"actor_cuenta_id");
	}

	@Test
	@DisplayName("caso_clinico no guarda contador de sesiones: eso es un numerador, no un cache")
	void el_caso_no_cachea_el_conteo() {
		// Challenge seccion 4 y regla maestra 3: un cache de COUNT(*) se desincroniza y un
		// numerador no puede, porque nunca vuelve atras. El contador vive en
		// caso_sesion_numerador, que es otra tabla a proposito.
		assertThat(columnasDe("caso_clinico"))
				.doesNotContain("cantidad_sesiones", "ultimo_numero_sesion", "ultimo_numero");
	}

	// =================================================================================
	// caso_clinico — estado, cierre coherente y el correlativo
	// =================================================================================

	@Test
	@DisplayName("un estado fuera de ACTIVO/CERRADO no entra")
	void el_estado_es_lista_cerrada() {
		// Dos valores y nada mas (V47 punto 3): un estado sin transicion que lo produzca es modelo
		// muerto, y `SUSPENDIDO` es el que la intuicion pide primero.
		assertThatThrownBy(() -> insertarCaso(ORG_A, historiaEnA, 1, "SUSPENDIDO"))
				.as("ck_caso_clinico_estado. Un CHECK de MySQL llega como "
						+ "UncategorizedSQLException, al reves que el UNIQUE")
				.isInstanceOf(UncategorizedSQLException.class);
	}

	@Test
	@DisplayName("un numero de caso cero o negativo no entra")
	void el_correlativo_es_positivo() {
		assertThatThrownBy(() -> insertarCaso(ORG_A, historiaEnA, 0, "ACTIVO"))
				.isInstanceOf(UncategorizedSQLException.class);
	}

	@Test
	@DisplayName("un caso CERRADO sin motivo, sin instante o sin actor no entra")
	void el_cierre_va_completo() {
		// Los cuatro datos del cierre van juntos o no va ninguno. Sin motivo, un cierre es
		// indistinguible de un abandono y el historial deja de servir para lo unico que sirve.
		long casoId = insertarCaso(ORG_A, historiaEnA, 1, "ACTIVO");

		assertThatThrownBy(() -> jdbc().update("""
				UPDATE caso_clinico SET estado = 'CERRADO', cerrado_en = UTC_TIMESTAMP(6),
				                        cerrado_por = 1
				 WHERE id = ?
				""", casoId))
				.as("ck_caso_clinico_cierre_coherente: falta el motivo")
				.isInstanceOf(UncategorizedSQLException.class);

		assertThatThrownBy(() -> jdbc().update("""
				UPDATE caso_clinico SET estado = 'CERRADO', motivo_cierre = 'Alta'
				 WHERE id = ?
				""", casoId))
				.as("ck_caso_clinico_cierre_coherente: faltan instante y actor")
				.isInstanceOf(UncategorizedSQLException.class);
	}

	@Test
	@DisplayName("un caso ACTIVO con datos de cierre puestos no entra")
	void un_activo_no_arrastra_el_cierre_anterior() {
		// Es la otra mitad del CHECK, y la que hace que reabrir tenga que LIMPIAR las tres
		// columnas: si quedaran puestas sobre un caso activo, una consulta por
		// `cerrado_en IS NOT NULL` devolveria casos abiertos. El historial del cierre anterior
		// vive en caso_evento, que es append-only.
		long casoId = insertarCaso(ORG_A, historiaEnA, 1, "ACTIVO");

		assertThatThrownBy(() -> jdbc().update("""
				UPDATE caso_clinico SET cerrado_en = UTC_TIMESTAMP(6), cerrado_por = 1,
				                        motivo_cierre = 'Alta'
				 WHERE id = ?
				""", casoId))
				.isInstanceOf(UncategorizedSQLException.class);
	}

	@Test
	@DisplayName("un cierre con las cuatro columnas puestas entra")
	void el_cierre_completo_se_admite() {
		// Control positivo: sin el, un CHECK demasiado estricto —que prohibiera todo cierre—
		// pasaria inadvertido con los dos rechazos en verde.
		long casoId = insertarCaso(ORG_A, historiaEnA, 1, "ACTIVO");

		assertThatCode(() -> jdbc().update("""
				UPDATE caso_clinico SET estado = 'CERRADO', cerrado_en = UTC_TIMESTAMP(6),
				                        cerrado_por = 1, motivo_cierre = 'Alta por objetivos'
				 WHERE id = ?
				""", casoId))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("dos casos con el mismo numero en la misma historia no entran")
	void el_numero_de_caso_es_unico_por_historia() {
		// uk_caso_numero es el RESPALDO del numerador, no el mecanismo: si el numerador fallara,
		// la base impide el numero repetido en vez de dejarlo pasar. A diferencia del solapamiento
		// de turnos, aca la regla SI es igualdad y el motor la puede hacer cumplir.
		insertarCaso(ORG_A, historiaEnA, 1, "ACTIVO");

		assertThatThrownBy(() -> insertarCaso(ORG_A, historiaEnA, 1, "ACTIVO"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("dos casos activos de la misma oferta en la misma historia SI entran")
	void no_hay_unique_de_un_solo_caso_activo() {
		// V47 punto 2 y RN-M10-002: una rodilla y un hombro son dos casos legitimos del mismo
		// paciente el mismo dia. Un unique aca seria un bug disfrazado de proteccion. El duplicado
		// razonable lo resuelve la aplicacion con 409 y candidatos, no el esquema.
		insertarCaso(ORG_A, historiaEnA, 1, "ACTIVO");

		assertThatCode(() -> insertarCaso(ORG_A, historiaEnA, 2, "ACTIVO"))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("dos historias distintas tienen cada una su caso numero 1")
	void el_correlativo_no_es_global() {
		// Control negativo del unique: si no llevara historia_clinica_id, una organizacion entera
		// admitiria un solo "caso 1" y el correlativo dejaria de ser del paciente.
		long otraHistoria = abrirHistoria(ORG_A);
		insertarCaso(ORG_A, historiaEnA, 1, "ACTIVO");

		assertThatCode(() -> insertarCaso(ORG_A, otraHistoria, 1, "ACTIVO"))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("el mismo numero de caso en otra organizacion entra: el unique arranca por tenant")
	void el_correlativo_no_colisiona_entre_organizaciones() {
		insertarCaso(ORG_A, historiaEnA, 1, "ACTIVO");

		assertThatCode(() -> insertarCaso(ORG_B, historiaEnB, 1, "ACTIVO"))
				.doesNotThrowAnyException();
	}

	// =================================================================================
	// caso_profesional — rol, vigencia y el centinela
	// =================================================================================

	@Test
	@DisplayName("un rol fuera de RESPONSABLE/TRATANTE no entra")
	void el_rol_en_el_caso_es_lista_cerrada() {
		long casoId = insertarCaso(ORG_A, historiaEnA, 1, "ACTIVO");

		assertThatThrownBy(() -> insertarParticipacion(ORG_A, casoId, membershipEnA, "AUXILIAR"))
				.isInstanceOf(UncategorizedSQLException.class);
	}

	@Test
	@DisplayName("una participacion que termina antes de empezar no entra")
	void la_vigencia_del_profesional_es_coherente() {
		long casoId = insertarCaso(ORG_A, historiaEnA, 1, "ACTIVO");
		long participacionId = insertarParticipacion(ORG_A, casoId, membershipEnA, "TRATANTE");

		assertThatThrownBy(() -> jdbc().update("""
				UPDATE caso_profesional SET hasta = DATE_SUB(desde, INTERVAL 1 DAY) WHERE id = ?
				""", participacionId))
				.isInstanceOf(UncategorizedSQLException.class);
	}

	@Test
	@DisplayName("hasta_key existe, es STORED y vale el centinela mientras el profesional sigue")
	void el_centinela_de_la_vigencia() {
		// Aca `hasta IS NULL` significa "sigue en el equipo", que es al reves que deleted_at: sin
		// la columna generada el unique protegeria el historico y desprotegeria lo vigente.
		assertThat(jdbc().queryForObject("""
				SELECT extra FROM information_schema.columns
				 WHERE table_schema = DATABASE() AND table_name = 'caso_profesional'
				   AND column_name = 'hasta_key'
				""", String.class))
				.as("una columna VIRTUAL no puede participar de un indice unico en MySQL")
				.contains("STORED GENERATED");

		long casoId = insertarCaso(ORG_A, historiaEnA, 1, "ACTIVO");
		long participacionId = insertarParticipacion(ORG_A, casoId, membershipEnA, "TRATANTE");

		assertThat(jdbc().queryForObject(
				"SELECT hasta_key FROM caso_profesional WHERE id = ?", String.class, participacionId))
				.as("el centinela, no NULL: es lo que hace comparables las filas vigentes")
				.startsWith("1970-01-01");
	}

	@Test
	@DisplayName("el mismo profesional dos veces vigente en el mismo caso no entra")
	void el_unique_protege_la_participacion_vigente() {
		long casoId = insertarCaso(ORG_A, historiaEnA, 1, "ACTIVO");
		insertarParticipacion(ORG_A, casoId, membershipEnA, "TRATANTE");

		assertThatThrownBy(() ->
				insertarParticipacion(ORG_A, casoId, membershipEnA, "RESPONSABLE"))
				.as("uk_caso_profesional_vigente. Dos filas vigentes del mismo profesional harian "
						+ "que el equipo lo muestre dos veces con roles distintos")
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("un profesional desvinculado deja lugar para volver a incorporarse")
	void una_desvinculacion_libera_el_unique() {
		// Es lo que hace `hasta_key`. Quien vuelve al equipo abre una participacion NUEVA: la
		// anterior termino y sigue siendo cierta, y borrarla reescribiria historia.
		long casoId = insertarCaso(ORG_A, historiaEnA, 1, "ACTIVO");
		long participacionId = insertarParticipacion(ORG_A, casoId, membershipEnA, "TRATANTE");
		jdbc().update("UPDATE caso_profesional SET hasta = UTC_TIMESTAMP(6) WHERE id = ?",
				participacionId);

		assertThatCode(() -> insertarParticipacion(ORG_A, casoId, membershipEnA, "TRATANTE"))
				.doesNotThrowAnyException();
		assertThat(jdbc().queryForObject("""
				SELECT COUNT(*) FROM caso_profesional WHERE caso_id = ?
				""", Long.class, casoId))
				.as("las dos filas quedan: la que trato y la que trata")
				.isEqualTo(2L);
	}

	// =================================================================================
	// caso_evento — tipo, motivo y la apertura
	// =================================================================================

	@Test
	@DisplayName("un tipo de evento fuera del catalogo no entra")
	void el_tipo_de_evento_es_lista_cerrada() {
		long casoId = insertarCaso(ORG_A, historiaEnA, 1, "ACTIVO");

		assertThatThrownBy(() ->
				insertarEvento(ORG_A, casoId, "SUSPENSION", "ACTIVO", "ACTIVO", "motivo"))
				.isInstanceOf(UncategorizedSQLException.class);
	}

	@Test
	@DisplayName("un CIERRE o una REAPERTURA sin motivo no entran")
	void cerrar_y_reabrir_exigen_motivo() {
		// Sin motivo, el historial registra que algo paso y no por que, que es lo unico que
		// despues se quiere leer.
		long casoId = insertarCaso(ORG_A, historiaEnA, 1, "ACTIVO");

		assertThatThrownBy(() ->
				insertarEvento(ORG_A, casoId, "CIERRE", "ACTIVO", "CERRADO", null))
				.isInstanceOf(UncategorizedSQLException.class);
		assertThatThrownBy(() ->
				insertarEvento(ORG_A, casoId, "REAPERTURA", "CERRADO", "ACTIVO", null))
				.isInstanceOf(UncategorizedSQLException.class);

		assertThatCode(() ->
				insertarEvento(ORG_A, casoId, "CIERRE", "ACTIVO", "CERRADO", "Alta por objetivos"))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("una EDICION sin motivo entra: el motivo solo aplica a cierre y reapertura")
	void los_demas_eventos_no_exigen_motivo() {
		// Control negativo del CHECK: si exigiera motivo en todos, cada edicion pediria una
		// explicacion que el requisito no pide y la pantalla no tiene donde escribir.
		long casoId = insertarCaso(ORG_A, historiaEnA, 1, "ACTIVO");

		assertThatCode(() ->
				insertarEvento(ORG_A, casoId, "EDICION", "ACTIVO", "ACTIVO", null))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("una APERTURA con estado anterior no entra, y cualquier otro evento sin el tampoco")
	void la_apertura_es_el_unico_evento_sin_estado_anterior() {
		// Antes de la apertura no habia estado. Y al reves: un CIERRE sin estado anterior seria
		// una transicion que no dice desde donde, o sea media transicion.
		long casoId = insertarCaso(ORG_A, historiaEnA, 1, "ACTIVO");

		assertThatThrownBy(() ->
				insertarEvento(ORG_A, casoId, "APERTURA", "ACTIVO", "ACTIVO", null))
				.isInstanceOf(UncategorizedSQLException.class);
		assertThatThrownBy(() ->
				insertarEvento(ORG_A, casoId, "EDICION", null, "ACTIVO", null))
				.isInstanceOf(UncategorizedSQLException.class);

		assertThatCode(() ->
				insertarEvento(ORG_A, casoId, "APERTURA", null, "ACTIVO", null))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("un estado nuevo fuera de ACTIVO/CERRADO no entra en el historial")
	void los_estados_del_historial_son_los_del_caso() {
		long casoId = insertarCaso(ORG_A, historiaEnA, 1, "ACTIVO");

		assertThatThrownBy(() ->
				insertarEvento(ORG_A, casoId, "EDICION", "ACTIVO", "SUSPENDIDO", null))
				.isInstanceOf(UncategorizedSQLException.class);
	}

	// =================================================================================
	// Los dos numeradores
	// =================================================================================

	@Test
	@DisplayName("cada historia tiene un solo numerador de casos y cada caso uno de sesiones")
	void los_numeradores_son_unicos_por_clave() {
		// Si la clave no fuera unica, `INSERT ... ON DUPLICATE KEY UPDATE` insertaria una segunda
		// fila en vez de no hacer nada, y dos transacciones incrementarian contadores distintos:
		// el mecanismo entero se apoya en que haya UNA fila por clave.
		long casoId = insertarCaso(ORG_A, historiaEnA, 1, "ACTIVO");

		jdbc().update("""
				INSERT INTO caso_numerador (organization_id, historia_clinica_id, ultimo_numero)
				 VALUES (?, ?, 1)
				""", ORG_A, historiaEnA);
		assertThatThrownBy(() -> jdbc().update("""
				INSERT INTO caso_numerador (organization_id, historia_clinica_id, ultimo_numero)
				 VALUES (?, ?, 1)
				""", ORG_A, historiaEnA))
				.isInstanceOf(DataIntegrityViolationException.class);

		jdbc().update("""
				INSERT INTO caso_sesion_numerador (organization_id, caso_id, ultimo_numero)
				 VALUES (?, ?, 1)
				""", ORG_A, casoId);
		assertThatThrownBy(() -> jdbc().update("""
				INSERT INTO caso_sesion_numerador (organization_id, caso_id, ultimo_numero)
				 VALUES (?, ?, 1)
				""", ORG_A, casoId))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	// =================================================================================
	// V48 — el gancho en sesion
	// =================================================================================

	@Test
	@DisplayName("una sesion con numero_en_caso y sin caso_id no entra")
	void no_hay_numero_de_caso_sin_caso() {
		// El invariante central de V48. Un numero de caso huerfano es un correlativo que no numera
		// dentro de nada: aparece en un informe, alguien lo lee como "la octava sesion de su
		// tratamiento" y no hay tratamiento al cual pertenezca.
		assertThatThrownBy(() -> insertarSesion(ORG_A, historiaEnA, null, 1, 3))
				.as("ck_sesion_numero_en_caso")
				.isInstanceOf(UncategorizedSQLException.class);
	}

	@Test
	@DisplayName("una sesion con numero_en_caso cero no entra")
	void el_numero_dentro_del_caso_es_positivo() {
		long casoId = insertarCaso(ORG_A, historiaEnA, 1, "ACTIVO");

		assertThatThrownBy(() -> insertarSesion(ORG_A, historiaEnA, casoId, 1, 0))
				.isInstanceOf(UncategorizedSQLException.class);
	}

	@Test
	@DisplayName("una sesion sin caso y sin numero de caso entra: RF-M14-002 lo admite")
	void la_sesion_sin_caso_sigue_siendo_valida() {
		// Es lo que son TODAS las sesiones anteriores a esta etapa, y por eso V48 no pone caso_id
		// NOT NULL: encender ese gate hoy romperia la vertical que funciona.
		assertThatCode(() -> insertarSesion(ORG_A, historiaEnA, null, 1, null))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("el par caso_id + numero_en_caso completo entra")
	void el_par_completo_se_admite() {
		long casoId = insertarCaso(ORG_A, historiaEnA, 1, "ACTIVO");

		assertThatCode(() -> insertarSesion(ORG_A, historiaEnA, casoId, 1, 1))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("dos sesiones con el mismo numero dentro del mismo caso no entran")
	void el_numero_dentro_del_caso_es_unico() {
		long casoId = insertarCaso(ORG_A, historiaEnA, 1, "ACTIVO");
		insertarSesion(ORG_A, historiaEnA, casoId, 1, 1);

		assertThatThrownBy(() -> insertarSesion(ORG_A, historiaEnA, casoId, 2, 1))
				.as("uk_sesion_numero_en_caso: el respaldo del numerador, no el mecanismo")
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("muchas sesiones sin caso conviven: MySQL no aplica el unique con NULL")
	void las_sesiones_sin_caso_no_se_estorban() {
		// El unique lleva caso_id, que en las sesiones sin caso es NULL, y MySQL no aplica un
		// unique cuando alguna columna lo es. Pueden ser miles y ninguna choca — que es lo que
		// permite que V48 sea aditiva de verdad.
		insertarSesion(ORG_A, historiaEnA, null, 1, null);
		insertarSesion(ORG_A, historiaEnA, null, 2, null);

		assertThatCode(() -> insertarSesion(ORG_A, historiaEnA, null, 3, null))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("dos casos distintos tienen cada uno su sesion numero 1")
	void el_correlativo_de_sesion_es_del_caso() {
		long unCaso = insertarCaso(ORG_A, historiaEnA, 1, "ACTIVO");
		long otroCaso = insertarCaso(ORG_A, historiaEnA, 2, "ACTIVO");
		insertarSesion(ORG_A, historiaEnA, unCaso, 1, 1);

		assertThatCode(() -> insertarSesion(ORG_A, historiaEnA, otroCaso, 2, 1))
				.doesNotThrowAnyException();
	}

	// =================================================================================
	// Fixtures — todo sintetico (AGENT.md seccion 10)
	// =================================================================================

	private static List<String> tablasDelCaso() {
		return List.of("caso_clinico", "caso_numerador", "caso_sesion_numerador",
				"caso_profesional", "caso_evento");
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
		String nombre = "Sede " + SECUENCIA.incrementAndGet();
		jdbc().update("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version,
				                         created_at, updated_at)
				 VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, nombre, ZONA);
		return ultimoId();
	}

	private long crearMembership(long organizationId, long consultorioId) {
		String email = "caso-mig-" + SECUENCIA.incrementAndGet() + "@ejemplo.test";
		jdbc().update("""
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado, active,
				                    version, created_at, updated_at)
				 VALUES (?, ?, 'Sintetico', 'DePrueba', 'ACTIVA', 1, 0,
				         UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", email, email);
		long cuentaId = ultimoId();

		jdbc().update("""
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, estado, active, version,
				                        created_at, updated_at)
				 VALUES (?, ?, ?, 'PROFESIONAL', 0, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 YEAR),
				         'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, consultorioId, cuentaId);
		return ultimoId();
	}

	private long crearOferta(long organizationId, long consultorioId) {
		String codigo = "CASOMIG-" + SECUENCIA.incrementAndGet();
		jdbc().update("""
				INSERT INTO servicio (codigo, nombre, naturaleza, modalidad_default,
				                      requiere_caso_clinico_default,
				                      genera_registro_clinico_default, active, version,
				                      created_at, updated_at)
				 VALUES (?, ?, 'CLINICO', 'INDIVIDUAL', 0, 0, 1, 0,
				         UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", codigo, "Servicio " + codigo);
		long servicioId = ultimoId();

		jdbc().update("""
				INSERT INTO oferta_servicio_consultorio
				       (organization_id, consultorio_id, servicio_id, nombre_comercial, modalidad,
				        duracion_minutos, capacidad, precio_base, moneda, admite_obra_social,
				        requiere_caso_clinico, genera_registro_clinico, requiere_profesional,
				        requiere_espacio, vigencia_desde, active, version, created_at, updated_at)
				 VALUES (?, ?, ?, ?, 'INDIVIDUAL', 60, 1, 8500.00, 'ARS', 0, 0, 0, 1, 0,
				         DATE_SUB(CURDATE(), INTERVAL 5 YEAR), 1, 0,
				         UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, consultorioId, servicioId, "Oferta " + codigo);
		return ultimoId();
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
		long personaId = ultimoId();

		jdbc().update("""
				INSERT INTO historia_clinica (organization_id, persona_id, abierta_en, abierta_por,
				                              created_at, updated_at)
				 VALUES (?, ?, UTC_TIMESTAMP(6), 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, personaId);
		return ultimoId();
	}

	private long insertarCaso(long organizationId, long historiaId, int numero, String estado) {
		jdbc().update("""
				INSERT INTO caso_clinico (organization_id, historia_clinica_id, numero_caso,
				                          oferta_id, oferta_consultorio_id, diagnostico_presuntivo,
				                          estado, abierto_en, abierto_por, version,
				                          created_at, updated_at)
				 VALUES (?, ?, ?, ?, ?, 'Diagnostico sintetico', ?, UTC_TIMESTAMP(6), 1, 0,
				         UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, historiaId, numero, ofertaEnA, consultorioEnA, estado);
		return ultimoId();
	}

	private long insertarParticipacion(
			long organizationId, long casoId, long membershipId, String rol) {

		jdbc().update("""
				INSERT INTO caso_profesional (organization_id, caso_id, profesional_membership_id,
				                              rol, desde, created_at, updated_at)
				 VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, casoId, membershipId, rol);
		return ultimoId();
	}

	private void insertarEvento(
			long organizationId, long casoId, String tipo,
			String estadoAnterior, String estadoNuevo, String motivo) {

		jdbc().update("""
				INSERT INTO caso_evento (organization_id, caso_id, tipo, estado_anterior,
				                         estado_nuevo, motivo, ocurrio_en, actor_cuenta_id,
				                         created_at)
				 VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), 1, UTC_TIMESTAMP(6))
				""", organizationId, casoId, tipo, estadoAnterior, estadoNuevo, motivo);
	}

	/**
	 * Una sesion insertada directo, con o sin caso.
	 *
	 * <p>{@code numeroSesion} se pasa explicito porque {@code uk_sesion_numero} de {@code V35}
	 * tambien es unico por historia: dos sesiones del mismo paciente con el mismo numero chocarian
	 * por ese otro unique y el test estaria midiendo el equivocado.
	 */
	private void insertarSesion(
			long organizationId, long historiaId, Long casoId,
			Integer numeroSesion, Integer numeroEnCaso) {

		// Se inserta CERRADA y no en BORRADOR, y no es un detalle del fixture: una sesion con
		// numero tiene que llevar tambien instante y actor de cierre —`ck_sesion_cierre_completo`
		// de V35, los tres o ninguno— y, desde `ck_sesion_ultimo_numero_version` de V53, un
		// `ultimo_numero_version` de al menos 1. Un BORRADOR numerado es un estado que el esquema
		// no admite, asi que el fixture viejo no medía el unique: no llegaba a insertar la fila.
		jdbc().update("""
				INSERT INTO sesion (organization_id, consultorio_id, historia_clinica_id, caso_id,
				                    oferta_id, profesional_membership_id, estado, numero_sesion,
				                    numero_en_caso, iniciada_en, iniciada_por_cuenta_id,
				                    cerrada_en, cerrada_por_cuenta_id, ultimo_numero_version, version,
				                    created_at, updated_at)
				 VALUES (?, ?, ?, ?, ?, ?, 'CERRADA', ?, ?, UTC_TIMESTAMP(6), 1,
				         UTC_TIMESTAMP(6), 1, 1, 0,
				         UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, consultorioEnA, historiaId, casoId, ofertaEnA, membershipEnA,
				numeroSesion, numeroEnCaso);
	}

	private long ultimoId() {
		return jdbc().queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}
}
