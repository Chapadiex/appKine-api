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
 * {@code V49} contra MySQL real: la columna generada, los CHECK y los uniques del Plan.
 *
 * <p><b>ESCRITO EL 19/09/2026 Y NUNCA EJECUTADO: Docker no estaba disponible.</b> El motor de
 * contenedores de esta maquina no arranca sin elevacion, asi que esta clase compila y no corrio.
 * Nada de lo que afirma esta verificado todavia.
 *
 * <h2>Que escenario prueba</h2>
 *
 * <p>El registro de avance de AKINE-04.04 declara en su seccion "Sin verificar": <i>"{@code V49}
 * nunca se aplico, los ocho CHECK sin ejercer, la columna generada {@code activo_key} sin compilar
 * en el motor"</i>. Hasta que alguien corra esta clase no hay evidencia de que la migracion
 * siquiera <i>ejecute</i>.
 *
 * <p>Se inserta <b>directo</b>, sin pasar por ningun servicio, que es el criterio de
 * {@code CasoClinicoMigrationIT}: un invariante que solo la aplicacion respeta deja de proteger en
 * cuanto alguien escribe por otro camino.
 *
 * <h2>{@code activo_key} es lo primero que hay que mirar</h2>
 *
 * <p>El unique de "un solo plan activo por caso" no puede escribirse sobre {@code estado}: hay que
 * admitir <b>varios</b> BORRADOR y varios FINALIZADO en el mismo caso —el historico son justamente
 * esos—. Lo natural seria {@code IF(estado = 'ACTIVO', 0, id)} y <b>MySQL lo prohibe</b>: una
 * columna generada no puede referenciar un {@code AUTO_INCREMENT}. El discriminador es entonces
 * {@code numero_plan}, que ya es unico por {@code (organization_id, caso_clinico_id)} y arranca en
 * 1, asi que el 0 nunca es un {@code numero_plan} real.
 *
 * <p>Es exactamente la clase de cosa que <b>solo aparece al ejecutar</b>: en 8.4 una expresion mal
 * escrita sobre una columna generada falla con un <b>3819</b> que ningun analisis estatico ve, y
 * es lo que esta clase de test destapo en 03.06. Peor todavia: si {@code activo_key} existiera
 * pero calculara mal, la tabla se crea, los tests de servicio pasan, y el sistema admite dos planes
 * activos en el mismo Caso sin que nada falle.
 *
 * <h2>Lo que se verifica</h2>
 *
 * <ol>
 *   <li><b>Tenant en las cinco tablas</b> y todo indice declarado empezando por
 *       {@code organization_id}, aunque en tres de ellas sea derivable.</li>
 *   <li><b>{@code activo_key}</b>: que exista, que sea {@code STORED} —una {@code VIRTUAL} no
 *       puede participar de un indice unico en MySQL— y que valga 0 en el ACTIVO y
 *       {@code numero_plan} en los demas.</li>
 *   <li><b>{@code uk_plan_activo_por_caso}</b> con sus dos controles negativos: varios BORRADOR y
 *       varios FINALIZADO conviven, y dos casos distintos tienen cada uno su plan activo.</li>
 *   <li><b>Los seis CHECK de la cabecera</b>, cada uno en sus dos direcciones donde el CHECK las
 *       tiene: el estado, el correlativo positivo, la numeracion de versiones, y los trios de la
 *       activacion, la suspension y la finalizacion. La otra mitad es la que obliga a
 *       <b>limpiar</b> las columnas al reanudar.</li>
 *   <li><b>{@code plan_tratamiento_version} NO tiene {@code active}</b>. La ausencia <i>es</i> la
 *       decision del challenge §5: una version es un hecho pasado y darla de baja seria reescribir
 *       historia clinica (ADR-0011).</li>
 *   <li><b>Ninguna tabla guarda realizadas ni canceladas.</b> Es la decision central de 04.04 y la
 *       unica forma de cumplirla que no depende de la disciplina de quien escriba el proximo
 *       endpoint: no tener donde guardarlo.</li>
 *   <li><b>{@code uk_plan_item_oferta}</b>, que es la red que sostiene la derivacion del avance:
 *       dos items de la misma oferta en una version contarian las mismas sesiones dos veces y el
 *       avance mentiria <b>sin que nada falle</b>.</li>
 *   <li><b>{@code plan_evento} es append-only</b> y sus CHECK de motivo y de creacion.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class PlanTratamientoMigrationIT {

	private static final long ORG_A = 9491L;
	private static final long ORG_B = 9492L;

	private static final String ZONA = "America/Argentina/Cordoba";

	private static final AtomicLong SECUENCIA = new AtomicLong(60490000L);

	@Autowired
	private DataSource dataSource;

	private JdbcTemplate jdbc;

	private long consultorioEnA;
	private long ofertaEnA;
	private long ofertaAlternativaEnA;
	private long casoEnA;
	private long otroCasoEnA;
	private long casoEnB;

	private JdbcTemplate jdbc() {
		if (jdbc == null) {
			jdbc = new JdbcTemplate(dataSource);
		}
		return jdbc;
	}

	@BeforeEach
	void sembrar() {
		crearOrganizacion(ORG_A, "plan-mig-org-a");
		crearOrganizacion(ORG_B, "plan-mig-org-b");
		consultorioEnA = crearConsultorio(ORG_A);
		long consultorioEnB = crearConsultorio(ORG_B);
		ofertaEnA = crearOferta(ORG_A, consultorioEnA);
		ofertaAlternativaEnA = crearOferta(ORG_A, consultorioEnA);
		long ofertaEnB = crearOferta(ORG_B, consultorioEnB);

		casoEnA = crearCaso(ORG_A, consultorioEnA, ofertaEnA, 1);
		otroCasoEnA = crearCaso(ORG_A, consultorioEnA, ofertaEnA, 1);
		casoEnB = crearCaso(ORG_B, consultorioEnB, ofertaEnB, 1);
	}

	// =================================================================================
	// Tenant y forma de las tablas
	// =================================================================================

	@Test
	@DisplayName("las cinco tablas nuevas llevan organization_id NOT NULL")
	void las_cinco_tablas_llevan_tenant() {
		for (String tabla : tablasDelPlan()) {
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
		// En `plan_tratamiento_version`, `plan_item` y `plan_evento` el tenant es DERIVABLE, y va
		// igual: derivarlo obliga a un join para filtrar, y el dia que alguien escriba la consulta
		// sin el join tiene una fuga que ningun test de la etapa ve, porque los tests de una etapa
		// usan un solo tenant.
		for (String tabla : tablasDelPlan()) {
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
	@DisplayName("ninguna tabla del plan guarda realizadas ni canceladas")
	void el_esquema_no_tiene_donde_guardar_lo_realizado() {
		// LA DECISION CENTRAL DE 04.04, escrita en el esquema. Si la columna existiera, su dueño
		// real seria `encounter` —el unico modulo que sabe cuando una sesion se cerro— y
		// `clinical` tendria una columna que solo otro modulo puede mantener correcta. Ese es el
		// camino por el que un contador se desincroniza.
		//
		// `PlanSinContadoresTest` cubre entidades, DTOs y vistas por reflexion; esto cubre lo que
		// esa prueba no puede ver: que tampoco haya DONDE guardarlo.
		for (String tabla : tablasDelPlan()) {
			assertThat(columnasDe(tabla))
					.as("%s no puede tener contadores de lo realizado", tabla)
					.doesNotContain("cantidad_realizada", "cantidad_cancelada",
							"sesiones_realizadas", "sesiones_canceladas");
		}
	}

	@Test
	@DisplayName("plan_tratamiento_version NO tiene active: una version no se da de baja")
	void las_versiones_no_se_dan_de_baja() {
		// La ausencia ES la decision (challenge seccion 5). Una version es un hecho pasado y darla
		// de baja seria reescribir historia clinica (ADR-0011). Mismo criterio que
		// `entrada_clinica_version` en V45.
		assertThat(columnasDe("plan_tratamiento_version"))
				.doesNotContain("active", "deleted_at", "deactivation_reason", "deleted_key");
	}

	@Test
	@DisplayName("plan_tratamiento NO tiene baja logica: un plan no se borra, se finaliza")
	void el_plan_no_se_da_de_baja() {
		// Un `active` al lado de `estado` daria DOS formas de que un plan "no este", que es como
		// se construye una consulta que se olvida de una. Misma decision que `caso_clinico`.
		assertThat(columnasDe("plan_tratamiento"))
				.doesNotContain("active", "deleted_at", "deactivation_reason", "deleted_key");
	}

	@Test
	@DisplayName("plan_evento es append-only: sin version, sin updated_at y sin baja")
	void el_historial_no_se_edita() {
		assertThat(columnasDe("plan_evento"))
				.doesNotContain("version", "updated_at", "active", "deleted_at",
						"deactivation_reason", "deleted_key");
		assertThat(columnasDe("plan_evento"))
				.as("y si conserva lo que hace legible el historial")
				.contains("tipo", "estado_anterior", "estado_nuevo", "motivo", "detalle",
						"numero_version", "ocurrio_en", "actor_cuenta_id");
	}

	// =================================================================================
	// activo_key y el unique de un solo plan activo
	// =================================================================================

	@Test
	@DisplayName("activo_key existe, es STORED y vale 0 en el ACTIVO y numero_plan en los demas")
	void el_discriminador_del_plan_activo() {
		// Si esta expresion no compila como se cree, la tabla NO se crea y la migracion falla con
		// un 3819. Y si compilara mal —devolviendo siempre numero_plan, por ejemplo— la tabla se
		// crea igual, todos los tests de servicio pasan, y el sistema admite dos planes activos en
		// el mismo Caso sin que nada falle. Por eso se miran las dos cosas: la forma y el VALOR.
		assertThat(jdbc().queryForObject("""
				SELECT extra FROM information_schema.columns
				 WHERE table_schema = DATABASE() AND table_name = 'plan_tratamiento'
				   AND column_name = 'activo_key'
				""", String.class))
				.as("una columna VIRTUAL no puede participar de un indice unico en MySQL")
				.contains("STORED GENERATED");

		long borrador = insertarPlan(ORG_A, casoEnA, 1, "BORRADOR");
		long activo = insertarPlan(ORG_A, casoEnA, 2, "ACTIVO");

		assertThat(activoKey(activo))
				.as("el ACTIVO vale el centinela 0, que nunca es un numero_plan real")
				.isZero();
		assertThat(activoKey(borrador))
				.as("y el resto vale su propio numero_plan, ya unico en el caso")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("dos planes ACTIVOS en el mismo caso no entran")
	void un_solo_plan_activo_por_caso() {
		// El Caso ES el problema terapeutico: dos planes activos para el mismo problema significan
		// que en realidad son dos problemas, o sea dos Casos. Este unique es lo que hace que dos
		// activaciones concurrentes no dejen dos vivos.
		insertarPlan(ORG_A, casoEnA, 1, "ACTIVO");

		assertThatThrownBy(() -> insertarPlan(ORG_A, casoEnA, 2, "ACTIVO"))
				.as("uk_plan_activo_por_caso")
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("varios BORRADOR y varios FINALIZADO conviven en el mismo caso")
	void el_historico_del_caso_no_choca_contra_el_unique() {
		// EL CONTROL NEGATIVO QUE HACE VALIDO AL DISCRIMINADOR. Si `activo_key` fuera constante
		// para los no-activos, el unique dejaria un solo BORRADOR y un solo FINALIZADO por caso, y
		// el recorrido terapeutico del paciente —que es justamente ese historico— seria
		// inalmacenable.
		insertarPlan(ORG_A, casoEnA, 1, "BORRADOR");
		insertarPlan(ORG_A, casoEnA, 2, "BORRADOR");
		insertarPlan(ORG_A, casoEnA, 3, "FINALIZADO");

		assertThatCode(() -> insertarPlan(ORG_A, casoEnA, 4, "FINALIZADO"))
				.doesNotThrowAnyException();
		assertThatCode(() -> insertarPlan(ORG_A, casoEnA, 5, "ACTIVO"))
				.as("y todavia entra el unico activo")
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("un plan SUSPENDIDO sigue ocupando el lugar del activo del Caso")
	void un_suspendido_no_libera_el_lugar_del_activo() {
		// ESTE TEST FALLA HOY, Y EL DEFECTO ES DE PRODUCCION. No se corrige desde `src/test`.
		//
		// `V49` define el discriminador como `IF(estado = 'ACTIVO', 0, numero_plan)`, asi que un
		// plan SUSPENDIDO deja de valer 0 y LIBERA el slot. Tres lugares del codigo afirman por
		// escrito lo contrario:
		//
		//   * clinical/domain/EstadoPlan.java:47-49 — "Un plan suspendido sigue ocupando el lugar
		//     del activo del Caso, porque activo_key solo libera al FINALIZADO"
		//   * clinical/application/PlanTratamientoService.java:432-436 — "Suspender no libera el
		//     lugar del plan activo del Caso [...] Quien quiera empezar otro tratamiento finaliza
		//     este"
		//   * El challenge de 04.04 seccion 3, que fija el unique como LA garantia de "un solo
		//     plan activo por Caso".
		//
		// La consecuencia no necesita concurrencia: suspender A y activar B deja el Caso con dos
		// planes vivos —`buscarActivoDelCaso` filtra por `estado = 'ACTIVO'`, asi que no encuentra
		// a A y no lo finaliza— y despues REANUDAR A choca contra el unique con un 409 generico:
		// A queda irrecuperable. Ver `PlanTratamientoIT#suspender_no_abre_la_puerta_a_otro_plan`.
		//
		// El test se escribe contra la conducta ESPECIFICADA, no contra la vigente. Es el mismo
		// criterio con el que 04.02 dejo `el_binario_faltante_es_conflicto_y_no_un_404`.
		insertarPlan(ORG_A, casoEnA, 1, "SUSPENDIDO");

		assertThatThrownBy(() -> insertarPlan(ORG_A, casoEnA, 2, "ACTIVO"))
				.as("suspender es frenar el que hay, no abrir la puerta a otro. El discriminador "
						+ "tendria que liberar el 0 solo en FINALIZADO")
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("dos casos distintos tienen cada uno su plan activo")
	void el_plan_activo_no_es_por_organizacion() {
		insertarPlan(ORG_A, casoEnA, 1, "ACTIVO");

		assertThatCode(() -> insertarPlan(ORG_A, otroCasoEnA, 1, "ACTIVO"))
				.doesNotThrowAnyException();
		assertThatCode(() -> insertarPlan(ORG_B, casoEnB, 1, "ACTIVO"))
				.as("y el tenant encabeza la clave")
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("dos planes con el mismo numero en el mismo caso no entran")
	void el_correlativo_del_plan_es_unico_en_el_caso() {
		// uk_plan_numero es el RESPALDO del numerador, no el mecanismo. Y ademas es lo que hace
		// que activo_key funcione: si el numero se repitiera, dos no-activos del mismo caso
		// tendrian el mismo discriminador.
		insertarPlan(ORG_A, casoEnA, 1, "BORRADOR");

		assertThatThrownBy(() -> insertarPlan(ORG_A, casoEnA, 1, "FINALIZADO"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	// =================================================================================
	// Los CHECK de la cabecera
	// =================================================================================

	@Test
	@DisplayName("un estado fuera de los cuatro no entra")
	void el_estado_es_lista_cerrada() {
		assertThatThrownBy(() -> insertarPlan(ORG_A, casoEnA, 1, "ARCHIVADO"))
				.as("ck_plan_tratamiento_estado. Un CHECK llega como UncategorizedSQLException")
				.isInstanceOf(UncategorizedSQLException.class);
	}

	@Test
	@DisplayName("un numero de plan cero o negativo no entra")
	void el_correlativo_es_positivo() {
		assertThatThrownBy(() -> insertarPlan(ORG_A, casoEnA, 0, "BORRADOR"))
				.isInstanceOf(UncategorizedSQLException.class);
	}

	@Test
	@DisplayName("un contador de versiones menor que uno no entra")
	void la_numeracion_de_versiones_arranca_en_uno() {
		long planId = insertarPlan(ORG_A, casoEnA, 1, "BORRADOR");

		assertThatThrownBy(() -> jdbc().update(
				"UPDATE plan_tratamiento SET ultimo_numero_version = 0 WHERE id = ?", planId))
				.isInstanceOf(UncategorizedSQLException.class);
	}

	@Test
	@DisplayName("un plan ACTIVO sin datos de activacion no entra, y un BORRADOR con ellos tampoco")
	void la_activacion_va_completa_y_solo_donde_corresponde() {
		// Un plan vigente o suspendido estuvo activo alguna vez; uno en BORRADOR no. La segunda
		// mitad es la que importa: si un borrador pudiera arrastrar `activado_en`, una consulta
		// por "planes que alguna vez estuvieron vigentes" devolveria borradores.
		assertThatThrownBy(() -> jdbc().update("""
				INSERT INTO plan_tratamiento (organization_id, caso_clinico_id, numero_plan, estado,
				                              creado_en, creado_por, version, created_at, updated_at)
				 VALUES (?, ?, 9, 'ACTIVO', UTC_TIMESTAMP(6), 1, 0,
				         UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", ORG_A, casoEnA))
				.as("ck_plan_tratamiento_activacion: ACTIVO sin activado_en")
				.isInstanceOf(UncategorizedSQLException.class);

		assertThatThrownBy(() -> jdbc().update("""
				INSERT INTO plan_tratamiento (organization_id, caso_clinico_id, numero_plan, estado,
				                              creado_en, creado_por, activado_en, activado_por,
				                              version, created_at, updated_at)
				 VALUES (?, ?, 10, 'BORRADOR', UTC_TIMESTAMP(6), 1, UTC_TIMESTAMP(6), 1, 0,
				         UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", ORG_A, casoEnA))
				.as("ck_plan_tratamiento_activacion: BORRADOR que arrastra la activacion")
				.isInstanceOf(UncategorizedSQLException.class);
	}

	@Test
	@DisplayName("un FINALIZADO que nunca se activo entra: un borrador descartado se finaliza")
	void un_finalizado_puede_no_haber_estado_activo_nunca() {
		// Control positivo del mismo CHECK, y una decision real: un plan NO SE BORRA, asi que un
		// borrador que se descarta se finaliza. Si el CHECK exigiera activacion en FINALIZADO, ese
		// camino seria imposible y alguien terminaria borrando la fila.
		assertThatCode(() -> insertarPlan(ORG_A, casoEnA, 1, "FINALIZADO"))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("los dos datos de la suspension van juntos y solo en SUSPENDIDO")
	void la_suspension_es_coherente() {
		// La segunda mitad es la que obliga a LIMPIAR las columnas al reanudar: si quedaran
		// puestas sobre un plan ACTIVO, una consulta por `suspendido_en IS NOT NULL` devolveria
		// planes vigentes. Lo suspendido anterior vive en plan_evento, que es append-only.
		long activo = insertarPlan(ORG_A, casoEnA, 1, "ACTIVO");

		assertThatThrownBy(() -> jdbc().update("""
				UPDATE plan_tratamiento SET estado = 'SUSPENDIDO', suspendido_en = UTC_TIMESTAMP(6)
				 WHERE id = ?
				""", activo))
				.as("ck_plan_tratamiento_suspension: falta el motivo")
				.isInstanceOf(UncategorizedSQLException.class);

		assertThatThrownBy(() -> jdbc().update("""
				UPDATE plan_tratamiento SET suspendido_en = UTC_TIMESTAMP(6),
				                            motivo_suspension = 'Viaje'
				 WHERE id = ?
				""", activo))
				.as("un ACTIVO no puede arrastrar los datos de una suspension anterior")
				.isInstanceOf(UncategorizedSQLException.class);

		assertThatCode(() -> jdbc().update("""
				UPDATE plan_tratamiento SET estado = 'SUSPENDIDO', suspendido_en = UTC_TIMESTAMP(6),
				                            motivo_suspension = 'El paciente viaja'
				 WHERE id = ?
				""", activo))
				.as("control positivo: los dos juntos, en SUSPENDIDO")
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("los tres datos de la finalizacion van juntos o no va ninguno")
	void la_finalizacion_es_coherente() {
		long activo = insertarPlan(ORG_A, casoEnA, 1, "ACTIVO");

		assertThatThrownBy(() -> jdbc().update("""
				UPDATE plan_tratamiento SET estado = 'FINALIZADO', finalizado_en = UTC_TIMESTAMP(6)
				 WHERE id = ?
				""", activo))
				.as("ck_plan_tratamiento_finalizacion: faltan actor y motivo")
				.isInstanceOf(UncategorizedSQLException.class);

		assertThatThrownBy(() -> jdbc().update("""
				UPDATE plan_tratamiento SET finalizado_en = UTC_TIMESTAMP(6), finalizado_por = 1,
				                            motivo_finalizacion = 'Alta'
				 WHERE id = ?
				""", activo))
				.as("y un ACTIVO no puede llevarlos")
				.isInstanceOf(UncategorizedSQLException.class);

		assertThatCode(() -> jdbc().update("""
				UPDATE plan_tratamiento SET estado = 'FINALIZADO', finalizado_en = UTC_TIMESTAMP(6),
				                            finalizado_por = 1,
				                            motivo_finalizacion = 'Alta por objetivos'
				 WHERE id = ?
				""", activo))
				.doesNotThrowAnyException();
	}

	// =================================================================================
	// plan_tratamiento_version
	// =================================================================================

	@Test
	@DisplayName("la version 1 no lleva motivo y toda posterior si")
	void el_motivo_de_modificacion_se_exige_desde_la_dos() {
		// La version 1 no modifica nada, asi que un motivo ahi seria una explicacion de algo que
		// no ocurrio. Desde la 2, sin motivo una modificacion es indistinguible de una correccion
		// de tipeo — y el historial deja de servir para lo unico que sirve.
		long planId = insertarPlan(ORG_A, casoEnA, 1, "ACTIVO");

		assertThatThrownBy(() -> insertarVersion(ORG_A, planId, 1, "Motivo indebido"))
				.as("ck_plan_version_motivo_de_modificacion: la 1 no lleva motivo")
				.isInstanceOf(UncategorizedSQLException.class);
		assertThatThrownBy(() -> insertarVersion(ORG_A, planId, 2, null))
				.as("y la 2 no puede no llevarlo")
				.isInstanceOf(UncategorizedSQLException.class);

		assertThatCode(() -> insertarVersion(ORG_A, planId, 1, null)).doesNotThrowAnyException();
		assertThatCode(() -> insertarVersion(ORG_A, planId, 2, "El paciente progresa"))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("dos versiones con el mismo numero en el mismo plan no entran")
	void el_numero_de_version_es_unico_por_plan() {
		// La red debajo de la numeracion. La serializacion la hace el contador de la cabecera
		// —`ultimo_numero_version`, nunca un MAX+1—; esto la verifica: si dos modificaciones
		// concurrentes llegaran al mismo numero, la segunda choca contra la base en vez de dejar
		// dos "version 3" sin criterio de desempate.
		long planId = insertarPlan(ORG_A, casoEnA, 1, "ACTIVO");
		insertarVersion(ORG_A, planId, 1, null);

		assertThatThrownBy(() -> insertarVersion(ORG_A, planId, 1, null))
				.as("uk_plan_version_numero")
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("una frecuencia o una duracion fuera de rango no entran")
	void los_rangos_de_la_recurrencia_propuesta() {
		// El plan PROPONE una regla de recurrencia y no agenda nada; los topes existen para que un
		// tecleo de mas no produzca un plan de cuarenta sesiones semanales que despues alguien lee
		// como una indicacion clinica.
		long planId = insertarPlan(ORG_A, casoEnA, 1, "ACTIVO");

		assertThatThrownBy(() -> insertarVersionCon(ORG_A, planId, 1, null, 0, 8))
				.isInstanceOf(UncategorizedSQLException.class);
		assertThatThrownBy(() -> insertarVersionCon(ORG_A, planId, 1, null, 22, 8))
				.isInstanceOf(UncategorizedSQLException.class);
		assertThatThrownBy(() -> insertarVersionCon(ORG_A, planId, 1, null, 3, 0))
				.isInstanceOf(UncategorizedSQLException.class);

		assertThatCode(() -> insertarVersionCon(ORG_A, planId, 1, null, null, null))
				.as("las dos son opcionales: un plan puede no proponer recurrencia")
				.doesNotThrowAnyException();
	}

	// =================================================================================
	// plan_item
	// =================================================================================

	@Test
	@DisplayName("dos items de la MISMA oferta en una version no entran")
	void la_oferta_no_se_repite_dentro_de_una_version() {
		// uk_plan_item_oferta es la red que SOSTIENE la derivacion del avance: con dos items de la
		// misma oferta, las sesiones realizadas de esa oferta se contarian dos veces, una por cada
		// item, y el avance mentiria SIN QUE NADA FALLE. Es el peor tipo de defecto.
		long planId = insertarPlan(ORG_A, casoEnA, 1, "ACTIVO");
		long versionId = insertarVersion(ORG_A, planId, 1, null);
		insertarItem(ORG_A, versionId, ofertaEnA, 10, null, "DECLARADA", null);

		assertThatThrownBy(() ->
				insertarItem(ORG_A, versionId, ofertaEnA, 5, null, "DECLARADA", null))
				.isInstanceOf(DataIntegrityViolationException.class);

		assertThatCode(() ->
				insertarItem(ORG_A, versionId, ofertaAlternativaEnA, 5, null, "DECLARADA", null))
				.as("otra oferta si entra: la regla es por oferta, no por version")
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("un item que dice venir de una autorizacion sin decir de cual no entra")
	void el_origen_de_la_cantidad_es_trazable() {
		// La costura que 04.05 estrena: `AUTORIZACION` exige el id, y `DECLARADA` exige que NO
		// este. Sin esto, un item podria afirmar que su cantidad la dio el financiador sin poder
		// mostrar cual autorizacion lo respalda, que es exactamente lo que se presenta a cobro.
		long planId = insertarPlan(ORG_A, casoEnA, 1, "ACTIVO");
		long versionId = insertarVersion(ORG_A, planId, 1, null);

		assertThatThrownBy(() ->
				insertarItem(ORG_A, versionId, ofertaEnA, 10, 8, "AUTORIZACION", null))
				.as("ck_plan_item_origen_trazable: AUTORIZACION sin autorizacion_id")
				.isInstanceOf(UncategorizedSQLException.class);
	}

	@Test
	@DisplayName("una cantidad planificada cero no entra, y una autorizada cero si")
	void los_rangos_de_las_cantidades() {
		// La asimetria es deliberada y significa algo: planificar cero practicas no es un item, es
		// una fila que no dice nada. Autorizar CERO si es una respuesta del financiador —"no te
		// cubro ninguna"— y es distinta de NULL, que es "sin tope declarado".
		long planId = insertarPlan(ORG_A, casoEnA, 1, "ACTIVO");
		long versionId = insertarVersion(ORG_A, planId, 1, null);

		assertThatThrownBy(() ->
				insertarItem(ORG_A, versionId, ofertaEnA, 0, null, "DECLARADA", null))
				.isInstanceOf(UncategorizedSQLException.class);

		assertThatCode(() ->
				insertarItem(ORG_A, versionId, ofertaEnA, 10, 0, "DECLARADA", null))
				.doesNotThrowAnyException();
	}

	// =================================================================================
	// plan_evento y plan_numerador
	// =================================================================================

	@Test
	@DisplayName("suspender, modificar y finalizar exigen motivo en el historial; el resto no")
	void el_motivo_del_evento_se_exige_donde_corresponde() {
		long planId = insertarPlan(ORG_A, casoEnA, 1, "ACTIVO");

		assertThatThrownBy(() ->
				insertarEvento(ORG_A, planId, "SUSPENSION", "ACTIVO", "SUSPENDIDO", null))
				.isInstanceOf(UncategorizedSQLException.class);
		assertThatThrownBy(() ->
				insertarEvento(ORG_A, planId, "FINALIZACION", "ACTIVO", "FINALIZADO", null))
				.isInstanceOf(UncategorizedSQLException.class);

		// Control negativo: si exigiera motivo en todos, cada activacion pediria una explicacion
		// que el requisito no pide y la pantalla no tiene donde escribir.
		assertThatCode(() ->
				insertarEvento(ORG_A, planId, "ACTIVACION", "BORRADOR", "ACTIVO", null))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("la CREACION es el unico evento sin estado anterior")
	void la_creacion_es_el_unico_evento_sin_estado_previo() {
		// Antes de la creacion no habia estado. Y al reves: una ACTIVACION sin estado anterior
		// seria una transicion que no dice desde donde, o sea media transicion.
		long planId = insertarPlan(ORG_A, casoEnA, 1, "ACTIVO");

		assertThatThrownBy(() ->
				insertarEvento(ORG_A, planId, "CREACION", "BORRADOR", "BORRADOR", null))
				.isInstanceOf(UncategorizedSQLException.class);
		assertThatThrownBy(() ->
				insertarEvento(ORG_A, planId, "ACTIVACION", null, "ACTIVO", null))
				.isInstanceOf(UncategorizedSQLException.class);

		assertThatCode(() ->
				insertarEvento(ORG_A, planId, "CREACION", null, "BORRADOR", null))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("un tipo de evento fuera del catalogo no entra")
	void el_tipo_de_evento_es_lista_cerrada() {
		// El vocabulario esta CERRADO por el CHECK, y es la razon por la que RF-M11-007 —vincular
		// una autorizacion— no deja plan_evento: meterlo dentro de EDICION haria que el historial
		// afirme que alguien edito el contenido del plan, que es falso.
		long planId = insertarPlan(ORG_A, casoEnA, 1, "ACTIVO");

		assertThatThrownBy(() ->
				insertarEvento(ORG_A, planId, "VINCULACION", "ACTIVO", "ACTIVO", null))
				.isInstanceOf(UncategorizedSQLException.class);
	}

	@Test
	@DisplayName("cada caso tiene un solo numerador de planes")
	void el_numerador_es_unico_por_caso() {
		// Si la clave no fuera unica, `INSERT ... ON DUPLICATE KEY UPDATE` insertaria una segunda
		// fila en vez de no hacer nada, y dos transacciones incrementarian contadores distintos:
		// el mecanismo entero se apoya en que haya UNA fila por clave.
		jdbc().update("""
				INSERT INTO plan_numerador (organization_id, caso_clinico_id, ultimo_numero)
				 VALUES (?, ?, 1)
				""", ORG_A, casoEnA);

		assertThatThrownBy(() -> jdbc().update("""
				INSERT INTO plan_numerador (organization_id, caso_clinico_id, ultimo_numero)
				 VALUES (?, ?, 1)
				""", ORG_A, casoEnA))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	// =================================================================================
	// Fixtures — todo sintetico (AGENT.md seccion 10)
	// =================================================================================

	private static List<String> tablasDelPlan() {
		return List.of("plan_tratamiento", "plan_tratamiento_version", "plan_item",
				"plan_numerador", "plan_evento");
	}

	private List<String> columnasDe(String tabla) {
		return jdbc().queryForList("""
				SELECT column_name FROM information_schema.columns
				 WHERE table_schema = DATABASE() AND table_name = ?
				""", String.class, tabla);
	}

	private int activoKey(long planId) {
		return jdbc().queryForObject(
				"SELECT activo_key FROM plan_tratamiento WHERE id = ?", Integer.class, planId);
	}

	/** Un plan con las columnas coherentes con su estado, para no pelear con los tres CHECK. */
	private long insertarPlan(long organizationId, long casoId, int numero, String estado) {
		String activacion = switch (estado) {
			case "ACTIVO", "SUSPENDIDO" -> "UTC_TIMESTAMP(6), 1";
			default -> "NULL, NULL";
		};
		String suspension = "SUSPENDIDO".equals(estado)
				? "UTC_TIMESTAMP(6), 'Motivo sintetico'"
				: "NULL, NULL";
		String finalizacion = "FINALIZADO".equals(estado)
				? "UTC_TIMESTAMP(6), 1, 'Motivo sintetico'"
				: "NULL, NULL, NULL";

		jdbc().update("""
				INSERT INTO plan_tratamiento (organization_id, caso_clinico_id, numero_plan, estado,
				                              creado_en, creado_por, activado_en, activado_por,
				                              suspendido_en, motivo_suspension,
				                              finalizado_en, finalizado_por, motivo_finalizacion,
				                              version, created_at, updated_at)
				 VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6), 1, %s, %s, %s, 0,
				         UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""".formatted(activacion, suspension, finalizacion),
				organizationId, casoId, numero, estado);
		return ultimoId();
	}

	private long insertarVersion(
			long organizationId, long planId, int numero, String motivo) {

		return insertarVersionCon(organizationId, planId, numero, motivo, 3, 8);
	}

	@SuppressWarnings("checkstyle:ParameterNumber")
	private long insertarVersionCon(
			long organizationId,
			long planId,
			int numero,
			String motivo,
			Integer frecuencia,
			Integer semanas) {

		jdbc().update("""
				INSERT INTO plan_tratamiento_version (organization_id, plan_tratamiento_id,
				                                      numero_version, objetivos, indicaciones,
				                                      frecuencia_semanal, duracion_semanas,
				                                      motivo_modificacion, registrada_en,
				                                      registrada_por, created_at, updated_at)
				 VALUES (?, ?, ?, 'Objetivos sinteticos', NULL, ?, ?, ?, UTC_TIMESTAMP(6), 1,
				         UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, planId, numero, frecuencia, semanas, motivo);
		return ultimoId();
	}

	@SuppressWarnings("checkstyle:ParameterNumber")
	private void insertarItem(
			long organizationId,
			long versionId,
			long ofertaId,
			int planificadas,
			Integer autorizadas,
			String origen,
			Long autorizacionId) {

		jdbc().update("""
				INSERT INTO plan_item (organization_id, plan_tratamiento_version_id, oferta_id,
				                       oferta_consultorio_id, oferta_nombre, servicio_id,
				                       cantidad_planificada, cantidad_autorizada,
				                       origen_autorizacion, autorizacion_id,
				                       created_at, updated_at)
				 VALUES (?, ?, ?, ?, 'Oferta sintetica', 1, ?, ?, ?, ?,
				         UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, versionId, ofertaId, consultorioEnA, planificadas,
				autorizadas, origen, autorizacionId);
	}

	private void insertarEvento(
			long organizationId, long planId, String tipo,
			String estadoAnterior, String estadoNuevo, String motivo) {

		jdbc().update("""
				INSERT INTO plan_evento (organization_id, plan_tratamiento_id, tipo,
				                         estado_anterior, estado_nuevo, motivo, detalle,
				                         numero_version, ocurrio_en, actor_cuenta_id, created_at)
				 VALUES (?, ?, ?, ?, ?, ?, NULL, 1, UTC_TIMESTAMP(6), 1, UTC_TIMESTAMP(6))
				""", organizationId, planId, tipo, estadoAnterior, estadoNuevo, motivo);
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

	private long crearOferta(long organizationId, long consultorioId) {
		String codigo = "PLANMIG-" + SECUENCIA.incrementAndGet();
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

	private long crearCaso(long organizationId, long consultorioId, long ofertaId, int numero) {
		String documento = String.valueOf(SECUENCIA.incrementAndGet());
		jdbc().update("""
				INSERT INTO persona (organization_id, tipo_documento, numero_documento,
				                     documento_clave, apellido, nombre, apellido_clave,
				                     nombre_clave, created_at, updated_at)
				 VALUES (?, 'DNI', ?, ?, 'Sintetica', 'Paciente', 'SINTETICA', 'PACIENTE',
				         UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, documento, documento);
		long personaId = ultimoId();

		jdbc().update("""
				INSERT INTO historia_clinica (organization_id, persona_id, abierta_en, abierta_por,
				                              created_at, updated_at)
				 VALUES (?, ?, UTC_TIMESTAMP(6), 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, personaId);
		long historiaId = ultimoId();

		jdbc().update("""
				INSERT INTO caso_clinico (organization_id, historia_clinica_id, numero_caso,
				                          oferta_id, oferta_consultorio_id, diagnostico_presuntivo,
				                          estado, abierto_en, abierto_por, version,
				                          created_at, updated_at)
				 VALUES (?, ?, ?, ?, ?, 'Diagnostico sintetico', 'ACTIVO', UTC_TIMESTAMP(6), 1, 0,
				         UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, historiaId, numero, ofertaId, consultorioId);
		return ultimoId();
	}

	private long ultimoId() {
		return jdbc().queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}
}
