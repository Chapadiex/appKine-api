package com.akine.activity;

import com.akine.TestcontainersConfiguration;
import com.akine.activity.ActividadItFixture.Desenlace;
import com.akine.activity.ActividadItFixture.Tenant;
import com.akine.activity.CarreraDirigida.Retencion;
import com.akine.activity.application.AsistenciaService;
import com.akine.activity.application.IngresoSinInscripcionCommand;
import com.akine.activity.application.InscripcionService;
import com.akine.activity.application.ItemDeLote;
import com.akine.activity.application.RegistrarAsistenciaCommand;
import com.akine.activity.application.ResultadoDeLote;
import com.akine.activity.domain.OrigenAsistencia;
import com.akine.activity.domain.ResultadoAsistencia;
import com.akine.activity.domain.exception.ClaseCompletaException;
import com.akine.platform.spi.problem.ProblemType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Escenarios 55, 56 y 57 de {@code docs/tests-diferidos.md} (AKINE-08.03), contra MySQL real.
 *
 * <ul>
 *   <li><b>55, el cierre concurrente</b>: dos cierres de la misma clase no pueden resolver dos
 *       veces la misma lista de pendientes.</li>
 *   <li><b>56, el ingreso sin inscripcion sobre la ultima vacante</b>: el gemelo de la ultima
 *       vacante de 08.02 por otro camino, y sin pasar por {@code agenda_sede}.</li>
 *   <li><b>57, el lote con transacciones independientes</b>: lo que commitearon los items buenos
 *       queda commiteado aunque otro falle.</li>
 * </ul>
 *
 * <p>Las clases se insertan por JDBC ({@link ActividadItFixture}); iniciar, inscribir, marcar,
 * cerrar e ingresar pasan por los servicios reales.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class AsistenciaConcurrenteIT {

	@Autowired private InscripcionService inscripcionService;
	@Autowired private AsistenciaService asistenciaService;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private DataSource dataSource;

	private ActividadItFixture fx;

	@BeforeEach
	void setUp() {
		fx = new ActividadItFixture(jdbc, inscripcionService);
	}

	// =================================================================================
	// 55 — el cierre concurrente
	// =================================================================================

	/**
	 * <p><b>Destapo un defecto</b> (17/10/2026): sin lock sobre la clase, los cuatro cierres leian
	 * los mismos cinco pendientes y tres terminaban en {@code DataIntegrityViolationException}
	 * contra {@code uk_asistencia_clase_persona} —un 500— aunque el dato quedara bien. Ahora
	 * {@code cerrar} toma la clase {@code FOR UPDATE} en {@code READ_COMMITTED}.
	 */
	@Test
	@DisplayName("55 · Cuatro cierres simultaneos con cinco pendientes: una asistencia CIERRE por persona y la clase REALIZADA una sola vez")
	void cierres_simultaneos() {
		Tenant tenant = fx.crearTenant(20, 5);
		long clase = claseEnCurso(tenant, 10, 5);

		List<Callable<Object>> cierres = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			cierres.add(() -> asistenciaService.cerrar(tenant.actor(), tenant.consultorioId(), clase));
		}
		List<Desenlace<Object>> desenlaces = ActividadItFixture.enParalelo(cierres);

		assertThat(desenlaces)
				.as("el cierre es idempotente: ninguno falla. Desenlaces: %s", desenlaces)
				.noneMatch(Desenlace::fallo);
		assertThat(jdbc.queryForList("""
				SELECT origen FROM asistencia_actividad WHERE clase_id = ?
				""", String.class, clase))
				.as("exactamente una fila por persona, todas puestas por el cierre")
				.hasSize(5)
				.containsOnly("CIERRE");
		assertThat(fx.contarPorEstado(clase, "AUSENTE")).isEqualTo(5);
		assertThat(jdbc.queryForObject("SELECT estado FROM clase_programada WHERE id = ?", String.class, clase))
				.isEqualTo("REALIZADA");
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM clase_evento WHERE clase_id = ? AND tipo = 'CIERRE'", Integer.class, clase))
				.as("un solo evento de cierre en el historial")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("55 · Control negativo: cerrar una clase sin pendientes no escribe ninguna asistencia ni evento")
	void cierre_sin_pendientes_no_escribe() {
		Tenant tenant = fx.crearTenant(20, 3);
		long clase = claseEnCurso(tenant, 10, 3);
		for (int i = 0; i < 3; i++) {
			asistenciaService.registrar(tenant.actor(), tenant.consultorioId(), clase,
					new RegistrarAsistenciaCommand(fx.inscripcionDe(clase, tenant.persona(i)),
							ResultadoAsistencia.PRESENTE, OrigenAsistencia.MOSTRADOR, null, null));
		}
		int eventosAntes = eventosDeAsistencia(clase);

		asistenciaService.cerrar(tenant.actor(), tenant.consultorioId(), clase);

		assertThat(jdbc.queryForList("SELECT origen FROM asistencia_actividad WHERE clase_id = ?",
				String.class, clase)).hasSize(3).containsOnly("MOSTRADOR");
		assertThat(eventosDeAsistencia(clase)).isEqualTo(eventosAntes);
		assertThat(fx.contarPorEstado(clase, "ASISTIO")).isEqualTo(3);
	}

	// =================================================================================
	// 56 — el ingreso sin inscripcion sobre la ultima vacante
	// =================================================================================

	/**
	 * <p><b>Destapo un defecto</b> (17/10/2026): el ingreso pedia el lugar con el {@code tomarCupo}
	 * de 08.02, que exige {@code estado = 'PROGRAMADA'}, sobre una clase que por regla tiene que
	 * estar {@code EN_CURSO}. Cero filas siempre: los seis recibian {@code clase-completa} con un
	 * lugar libre, y el ingreso sin inscripcion no funciono nunca desde 08.03. Ahora usa
	 * {@code tomarCupoEnCurso}.
	 *
	 * <p><b>Verificacion por mutacion</b>: sin la condicion {@code cupo_ocupado < LEAST(...)} en
	 * {@code tomarCupoEnCurso}, los perdedores mueren contra {@code ck_clase_cupo_ocupado} en vez de
	 * recibir {@code clase-completa}, y este test falla.
	 */
	@Test
	@DisplayName("56 · Seis ingresos simultaneos sobre la ultima vacante, con la agenda de la sede retenida: entra uno y nunca se sobrevende")
	void ingresos_sobre_la_ultima_vacante() {
		Tenant tenant = fx.crearTenant(20, 8);
		long clase = claseEnCurso(tenant, 3, 2);

		// La fila de agenda_sede de la sede queda tomada FOR UPDATE durante toda la rafaga. Si el
		// ingreso pasara por ella —la inversion de orden de locks que 08.02 prohibio— las seis
		// tareas esperarian hasta el innodb_lock_wait_timeout y fallarian por eso.
		long filaDeAgenda = filaDeAgenda(tenant);
		List<Desenlace<Object>> desenlaces;
		try (Retencion agenda = Retencion.de(dataSource, "agenda_sede", filaDeAgenda)) {
			List<Callable<Object>> ingresos = new ArrayList<>();
			for (int i = 2; i < 8; i++) {
				long persona = tenant.persona(i);
				ingresos.add(() -> asistenciaService.registrarIngresoSinInscripcion(tenant.actor(),
						tenant.consultorioId(), clase,
						new IngresoSinInscripcionCommand(persona, ResultadoAsistencia.PRESENTE, null)));
			}
			desenlaces = ActividadItFixture.enParalelo(ingresos);
		}

		assertThat(desenlaces.stream().filter(d -> !d.fallo()))
				.as("exactamente uno entra. Desenlaces: %s", desenlaces)
				.hasSize(1);
		assertThat(desenlaces.stream().filter(Desenlace::fallo))
				.as("y el resto recibe clase-completa. Desenlaces: %s", desenlaces)
				.allMatch(d -> ActividadItFixture.causaEs(d.error(), ClaseCompletaException.class));
		assertThat(fx.cupoOcupado(clase)).isEqualTo(3);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM asistencia_actividad WHERE clase_id = ? AND origen = 'INGRESO_SIN_INSCRIPCION'
				""", Integer.class, clase)).isEqualTo(1);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM inscripcion_clase WHERE clase_id = ? AND deleted_at IS NULL
				""", Integer.class, clase)).as("ni una inscripcion de mas").isEqualTo(3);
	}

	// =================================================================================
	// 57 — el lote con transacciones independientes
	// =================================================================================

	/**
	 * <p><b>Verificacion por mutacion</b> (corrida el 17/10/2026): con {@code @Transactional} sobre
	 * {@code registrarLote}, los items se unen a la transaccion del lote, el primer fallo la marca
	 * rollback-only y el lote termina en {@code UnexpectedRollbackException} sin dejar una sola
	 * asistencia: este test falla.
	 */
	@Test
	@DisplayName("57 · Un lote con un item cancelado y uno de otro tenant: los buenos quedan commiteados y el ajeno es not-found")
	void lote_con_items_que_fallan() {
		Tenant tenant = fx.crearTenant(20, 5);
		long clase = claseEnCurso(tenant, 10, 5);
		long cancelada = fx.inscripcionDe(clase, tenant.persona(2));
		inscripcionService.cancelar(tenant.actor(), tenant.consultorioId(), clase, cancelada, "Avisó que no viene");

		Tenant otro = fx.crearTenant(20, 1);
		long claseAjena = fx.programarClase(otro, 10, 7);
		fx.inscribir(otro, claseAjena, otro.persona(0), false);
		long inscripcionAjena = fx.inscripcionDe(claseAjena, otro.persona(0));

		List<RegistrarAsistenciaCommand> items = List.of(
				marca(fx.inscripcionDe(clase, tenant.persona(0))),
				marca(fx.inscripcionDe(clase, tenant.persona(1))),
				marca(cancelada),
				marca(inscripcionAjena),
				marca(fx.inscripcionDe(clase, tenant.persona(3))),
				marca(fx.inscripcionDe(clase, tenant.persona(4))));

		ResultadoDeLote lote = asistenciaService.registrarLote(
				tenant.actor(), tenant.consultorioId(), clase, items);

		assertThat(lote.exitosos()).as("Items: %s", lote.items()).isEqualTo(4);
		assertThat(lote.fallidos()).isEqualTo(2);
		assertThat(lote.items()).extracting(ItemDeLote::exitoso)
				.containsExactly(true, true, false, false, true, true);
		assertThat(lote.items().get(3).problemType())
				.as("la inscripcion de otro tenant es not-found, nunca 'no es tuya'")
				.isEqualTo(ProblemType.NOT_FOUND.value());

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM asistencia_actividad WHERE clase_id = ?",
				Integer.class, clase))
				.as("los cuatro items buenos quedaron commiteados, antes y despues de los que fallaron")
				.isEqualTo(4);
		assertThat(fx.estadoDe(cancelada)).isEqualTo("CANCELADA");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM asistencia_actividad WHERE inscripcion_id = ?",
				Integer.class, inscripcionAjena)).isZero();
		assertThat(fx.estadoDe(inscripcionAjena)).isEqualTo("RESERVADA");
	}

	// =================================================================================

	/** Una clase con capacidad {@code capacidad}, {@code inscriptos} personas con lugar, ya iniciada. */
	private long claseEnCurso(Tenant tenant, int capacidad, int inscriptos) {
		long clase = fx.programarClase(tenant, capacidad, 1);
		for (int i = 0; i < inscriptos; i++) {
			fx.inscribir(tenant, clase, tenant.persona(i), false);
		}
		asistenciaService.iniciar(tenant.actor(), tenant.consultorioId(), clase);
		return clase;
	}

	private static RegistrarAsistenciaCommand marca(long inscripcionId) {
		return new RegistrarAsistenciaCommand(
				inscripcionId, ResultadoAsistencia.PRESENTE, OrigenAsistencia.LOTE, null, null);
	}

	private int eventosDeAsistencia(long clase) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM asistencia_evento WHERE clase_id = ?",
				Integer.class, clase);
	}

	private long filaDeAgenda(Tenant tenant) {
		jdbc.update("""
				INSERT INTO agenda_sede (organization_id, consultorio_id, created_at)
				VALUES (?, ?, UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE id = id
				""", tenant.organizationId(), tenant.consultorioId());
		return jdbc.queryForObject(
				"SELECT id FROM agenda_sede WHERE organization_id = ? AND consultorio_id = ?",
				Long.class, tenant.organizationId(), tenant.consultorioId());
	}
}
