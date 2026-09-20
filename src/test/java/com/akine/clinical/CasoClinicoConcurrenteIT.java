package com.akine.clinical;

import com.akine.TestcontainersConfiguration;
import com.akine.clinical.application.CasoClinicoAltaCommand;
import com.akine.clinical.application.CasoClinicoService;
import com.akine.clinical.application.CasoClinicoView;
import com.akine.clinical.application.IntegranteDelEquipo;
import com.akine.clinical.application.OperatingActor;
import com.akine.clinical.domain.RolEnCaso;
import com.akine.clinical.domain.exception.CasoClinicoNotAccessibleException;
import com.akine.clinical.domain.exception.CasoClinicoPosibleDuplicadoException;
import com.akine.clinical.domain.exception.HistoriaClinicaNotAccessibleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El correlativo del Caso Clinico y el duplicado razonable, ejercidos por hilos contra MySQL.
 *
 * <p><b>ESCRITO EL 19/09/2026 Y NUNCA EJECUTADO: Docker no estaba disponible.</b> El motor de
 * contenedores de esta maquina no arranca sin elevacion, asi que esta clase compila y no corrio.
 * Nada de lo que afirma esta verificado todavia.
 *
 * <h2>Que escenario prueba</h2>
 *
 * <p><b>"Dos administrativos abren un caso para el mismo paciente y la misma oferta, al mismo
 * tiempo, desde dos sedes."</b> Es literalmente el caso que el challenge de la etapa (§8) eligio
 * como el que rompe el diseño, y rompe dos cosas distintas a la vez:
 *
 * <ol>
 *   <li><b>El correlativo.</b> Con {@code SELECT MAX(numero_caso) + 1} los dos leen el mismo maximo
 *       y escriben el mismo numero. Lo tiene que impedir el {@code UPDATE ultimo_numero + 1} de
 *       {@code caso_numerador}, que toma un lock exclusivo de fila y serializa sin leer antes.</li>
 *   <li><b>El duplicado.</b> Y aca la expectativa es la contraria de la intuitiva: <b>los dos
 *       tienen que entrar</b>. RN-M10-002 admite varios casos activos —una rodilla y un hombro son
 *       dos casos legitimos del mismo paciente el mismo dia—, asi que no hay ningun unique que lo
 *       impida y la ventana de carrera del pre-chequeo <b>no se pretende cerrar</b>: el resultado
 *       correcto no es rechazar, es que los dos entren y alguien los unifique despues. Un test que
 *       exigiera exclusion aca estaria pidiendo un bug.</li>
 * </ol>
 *
 * <h2>Por que ningun test unitario puede probarlo</h2>
 *
 * <p>{@code CasoClinicoServiceTest} verifica con dobles que el alta pide el numero al numerador y
 * no a un {@code MAX + 1}, y que el pre-chequeo de duplicado corre <b>antes</b> de pedir numero.
 * Eso prueba de donde sale el numero y en que orden; no prueba que dos transacciones concurrentes
 * no puedan sacar el mismo. Quien decide eso es InnoDB.
 *
 * <p>Y el segundo escenario —la rafaga sobre una historia que todavia <b>no tiene fila de
 * numerador</b>— es el deadlock del lazy-create, que este repositorio ya pago cuatro veces
 * (agenda_sede, consultorio_calendario, sesion_numerador, autorizacion_persona_lock). Un mock no
 * puede reproducirlo: lo que falla es el orden de commit de dos transacciones reales, y el
 * {@code try/catch} no salva porque atrapar una excepcion de persistencia no des-marca la
 * transaccion.
 *
 * <h2>El control negativo, que es lo que a 02.07 le falto</h2>
 *
 * <p>Un mecanismo que serializa de mas pasa inadvertido si solo se prueba que ordena. El ultimo
 * escenario abre casos en <b>dos historias distintas</b> a la vez y exige que los dos entren sin
 * esperarse: si fallara, lo que esta mal es el alcance del lock —por organizacion en vez de por
 * historia— y no la regla. Es lo que 03.06 hizo bien y 02.07 no.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class CasoClinicoConcurrenteIT {

	private static final String ZONA = "America/Argentina/Cordoba";

	/** Sin relacion asistencial no hay acceso clinico: hoy el probe siempre dice "sin evidencia". */
	private static final String JUSTIFICACION = "Prueba de integracion sintetica";

	@Autowired private CasoClinicoService servicio;
	@Autowired private JdbcTemplate jdbc;

	// =================================================================================
	// (a) La carrera de los dos administrativos
	// =================================================================================

	@Test
	@DisplayName("dos altas concurrentes sobre la misma historia entran las dos, con 1 y 2")
	void dos_altas_concurrentes_no_numeran_igual() {
		// Las dos pasan el pre-chequeo de duplicado porque cuando lo evaluan no hay ningun caso
		// activo todavia: esa es la ventana declarada del challenge §8 punto 2. Lo que este test
		// exige es que la ventana produzca DOS CASOS BIEN NUMERADOS y no un numero repetido ni un
		// 500.
		Fixture fixture = crearFixture();

		Callable<CasoClinicoView> unAdministrativo = () -> abrir(fixture, "Gonalgia derecha", false);
		Callable<CasoClinicoView> otroAdministrativo = () -> abrir(fixture, "Omalgia izquierda", false);

		List<Desenlace> desenlaces = enParalelo(List.of(unAdministrativo, otroAdministrativo));

		assertThat(desenlaces.stream().filter(Desenlace::fallo).toList())
				.as("los dos casos son legitimos (RN-M10-002): ninguno se rechaza. Desenlaces: %s",
						desenlaces)
				.isEmpty();
		assertThat(numerosDeCaso(fixture.historiaClinicaId()))
				.as("uno y dos, sin repetir y sin huecos. Desenlaces: %s", desenlaces)
				.containsExactly(1, 2);
		assertThat(ultimoNumeroDelNumerador(fixture.historiaClinicaId()))
				.as("y el numerador avanzo exactamente dos veces")
				.isEqualTo(2);
	}

	// =================================================================================
	// (b) La rafaga sobre una historia SIN fila de numerador
	// =================================================================================

	@Test
	@DisplayName("cinco altas simultaneas sobre una historia sin numerador previo entran las cinco")
	void la_creacion_del_numerador_no_produce_deadlock() {
		// Este es el escenario del lazy-create. La fila de `caso_numerador` no existe: las cinco
		// transacciones intentan crearla a la vez. Si la creacion ocurriera DENTRO de la
		// transaccion que despues la bloquea, las primeras N se bloquearian mutuamente y el
		// `try/catch` no salvaria —atrapar la excepcion no des-marca la transaccion y Spring lanza
		// `UnexpectedRollbackException` al commitear, con un mensaje que no nombra la causa—.
		// `CasoNumeradorIniciador` la crea en REQUIRES_NEW justamente para esto.
		//
		// SI ESTE TEST FALLA CON UN DEADLOCK, NO SE TAPA CON UN REINTENTO: el reintento esconde
		// que el orden de creacion esta mal y lo devuelve como latencia en produccion.
		Fixture fixture = crearFixture();
		assertThat(hayFilaDeNumerador(fixture.historiaClinicaId()))
				.as("el escenario exige que la fila NO exista antes de la rafaga")
				.isFalse();

		List<Callable<CasoClinicoView>> altas = new ArrayList<>();
		for (int i = 1; i <= 5; i++) {
			String diagnostico = "Motivo de consulta " + i;
			altas.add(() -> abrir(fixture, diagnostico, true));
		}

		List<Desenlace> desenlaces = enParalelo(altas);

		assertThat(desenlaces.stream().filter(Desenlace::fallo).toList())
				.as("ninguna alta puede caerse por la creacion del numerador. Desenlaces: %s",
						desenlaces)
				.isEmpty();
		assertThat(numerosDeCaso(fixture.historiaClinicaId()))
				.as("1..5, sin huecos y sin repetidos")
				.containsExactly(1, 2, 3, 4, 5);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM caso_numerador WHERE historia_clinica_id = ?
				""", Long.class, fixture.historiaClinicaId()))
				.as("y quedo UNA sola fila de numerador: el ON DUPLICATE KEY UPDATE no duplica")
				.isEqualTo(1L);
	}

	@Test
	@DisplayName("una rafaga secuencial de seis altas numera 1..6 sin huecos")
	void la_numeracion_secuencial_no_deja_huecos() {
		// Un hueco en la numeracion de casos de un paciente se lee como "aca hubo un caso que
		// alguien borro", que es exactamente lo que el modelo sin baja logica quiere impedir. El
		// riesgo concreto es reservar numero ANTES de resolver el duplicado: el paso 3 del alta va
		// antes del 5 justamente por eso (regla 4 del Paquete B).
		Fixture fixture = crearFixture();

		for (int i = 1; i <= 6; i++) {
			abrir(fixture, "Motivo " + i, true);
		}

		assertThat(numerosDeCaso(fixture.historiaClinicaId()))
				.containsExactly(1, 2, 3, 4, 5, 6);
	}

	// =================================================================================
	// (c) El duplicado: 409 con candidatos, y el reenvio confirmado
	// =================================================================================

	@Test
	@DisplayName("un alta que coincide en oferta con un caso activo da 409 con los candidatos")
	void el_duplicado_razonable_se_detiene_con_candidatos() {
		// Mismo mecanismo que el alta de Persona (RN-M07-001), que los usuarios ya conocen. Y es
		// un `problemType` DISTINTO del 409 de concurrencia (challenge §8 punto 3): uno se
		// resuelve confirmando y el otro releyendo, asi que confundirlos le daria al usuario la
		// instruccion equivocada.
		Fixture fixture = crearFixture();
		CasoClinicoView existente = abrir(fixture, "Gonalgia derecha", true);

		assertThatThrownBy(() -> abrir(fixture, "Gonalgia derecha otra vez", false))
				.isInstanceOfSatisfying(CasoClinicoPosibleDuplicadoException.class,
						duplicado -> assertThat(duplicado.getCandidatos())
								.as("el 409 nombra el caso con el que coincide, o el usuario no "
										+ "tiene como decidir si confirma")
								.containsExactly(existente.id()));

		assertThat(numerosDeCaso(fixture.historiaClinicaId()))
				.as("el alta rechazada NO consume correlativo: el pre-chequeo va antes del numerador")
				.containsExactly(1);
		assertThat(ultimoNumeroDelNumerador(fixture.historiaClinicaId()))
				.as("y el numerador tampoco avanzo")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("el reenvio confirmado entra y quedan dos casos activos de la misma oferta")
	void el_reenvio_confirmado_entra() {
		// RN-M10-002: el resultado correcto no es rechazar. Un unique aca seria un bug disfrazado
		// de proteccion, y este test es el que lo dejaria en rojo si alguien lo agregara.
		Fixture fixture = crearFixture();
		abrir(fixture, "Gonalgia derecha", true);

		CasoClinicoView segundo = abrir(fixture, "Gonalgia derecha, otro episodio", true);

		assertThat(segundo.numeroCaso()).isEqualTo(2);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM caso_clinico
				 WHERE historia_clinica_id = ? AND oferta_id = ? AND estado = 'ACTIVO'
				""", Long.class, fixture.historiaClinicaId(), fixture.ofertaId()))
				.as("dos casos activos de la misma oferta conviven a proposito")
				.isEqualTo(2L);
	}

	// =================================================================================
	// (d) El control negativo
	// =================================================================================

	@Test
	@DisplayName("dos altas concurrentes sobre historias DISTINTAS entran las dos")
	void historias_distintas_no_se_estorban() {
		// La otra mitad de la regla, y la que hace que valga la pena serializar en vez de
		// prohibir. Dos administrativos abriendo casos de dos pacientes distintos es lo normal en
		// un centro, y un mecanismo que los serializara —un lock por organizacion, por ejemplo—
		// pasaria inadvertido con los otros escenarios en verde porque todos usan una sola
		// historia.
		Fixture fixture = crearFixture();
		long otraHistoria = abrirOtraHistoria(fixture);

		Callable<CasoClinicoView> enUna = () -> abrir(fixture, "Paciente uno", true);
		Callable<CasoClinicoView> enOtra = () -> servicio.abrir(fixture.actor(),
				new CasoClinicoAltaCommand(otraHistoria, fixture.ofertaId(), "Paciente dos", null,
						List.of(new IntegranteDelEquipo(
								fixture.membershipId(), RolEnCaso.RESPONSABLE)),
						true),
				JUSTIFICACION);

		List<Desenlace> desenlaces = enParalelo(List.of(enUna, enOtra));

		assertThat(desenlaces.stream().filter(Desenlace::fallo).toList())
				.as("no comparten numerador: no tienen por que esperarse. Desenlaces: %s",
						desenlaces)
				.isEmpty();
		assertThat(numerosDeCaso(fixture.historiaClinicaId())).containsExactly(1);
		assertThat(numerosDeCaso(otraHistoria))
				.as("cada historia arranca su propio correlativo en 1")
				.containsExactly(1);
	}

	// =================================================================================
	// El equipo inicial
	// =================================================================================

	@Test
	@DisplayName("el alta escribe el equipo y el evento de APERTURA en la misma transaccion")
	void el_alta_deja_equipo_y_evento() {
		// El evento se asienta DENTRO de la transaccion del alta y no post-commit: uno escrito
		// despues del commit puede perderse y dejar la transicion sin rastro, que es el agujero
		// que este historial existe para tapar.
		Fixture fixture = crearFixture();

		CasoClinicoView caso = abrir(fixture, "Gonalgia derecha", true);

		assertThat(caso.equipo()).hasSize(1);
		assertThat(caso.equipo().getFirst().rol()).isEqualTo("RESPONSABLE");
		assertThat(jdbc.queryForList("""
				SELECT tipo FROM caso_evento WHERE caso_id = ? ORDER BY id
				""", String.class, caso.id()))
				.containsExactly("APERTURA");
		assertThat(jdbc.queryForObject("""
				SELECT estado_anterior FROM caso_evento WHERE caso_id = ?
				""", String.class, caso.id()))
				.as("antes de la apertura no habia estado")
				.isNull();
	}

	// =================================================================================
	// Aislamiento de tenant — 404, nunca 403
	// =================================================================================

	@Test
	@DisplayName("un actor del tenant B no abre ni ve el caso del tenant A: 404, nunca 403")
	void un_tenant_no_alcanza_el_caso_del_otro() {
		// AGENT.md seccion 6, que lo exige en CADA test de integracion. La respuesta es "no
		// accesible" —404— y no 403: un 403 confirmaria que ese caso existe en algun lado, que es
		// media fuga. Y tambien dejaria censar casos ajenos por id.
		Fixture tenantA = crearFixture();
		Fixture tenantB = crearFixture();
		CasoClinicoView deA = abrir(tenantA, "Contenido del tenant A", true);

		assertThatThrownBy(() -> servicio.ver(tenantB.actor(), deA.id(), JUSTIFICACION))
				.isInstanceOf(CasoClinicoNotAccessibleException.class);
		assertThatThrownBy(() -> servicio.eventos(tenantB.actor(), deA.id(), JUSTIFICACION))
				.isInstanceOf(CasoClinicoNotAccessibleException.class);
		assertThatThrownBy(() -> servicio.editar(tenantB.actor(), deA.id(), "Intruso", null,
				deA.version(), JUSTIFICACION))
				.isInstanceOf(CasoClinicoNotAccessibleException.class);
		assertThatThrownBy(() -> servicio.cerrar(tenantB.actor(), deA.id(), "Intruso",
				deA.version(), JUSTIFICACION))
				.isInstanceOf(CasoClinicoNotAccessibleException.class);
		assertThatThrownBy(() -> servicio.cambiarEquipo(tenantB.actor(), deA.id(),
				List.of(), deA.version(), JUSTIFICACION))
				.isInstanceOf(CasoClinicoNotAccessibleException.class);

		// Y por el otro lado: la historia de A tampoco se alcanza desde B para listar ni abrir.
		assertThatThrownBy(() -> servicio.listar(
				tenantB.actor(), tenantA.historiaClinicaId(), false, JUSTIFICACION))
				.isInstanceOf(HistoriaClinicaNotAccessibleException.class);
		assertThatThrownBy(() -> servicio.abrir(tenantB.actor(),
				new CasoClinicoAltaCommand(tenantA.historiaClinicaId(), tenantB.ofertaId(),
						"Intruso", null, List.of(), true),
				JUSTIFICACION))
				.isInstanceOf(HistoriaClinicaNotAccessibleException.class);

		assertThat(numerosDeCaso(tenantA.historiaClinicaId()))
				.as("y nada se escribio en la historia de A")
				.containsExactly(1);
	}

	// =================================================================================
	// Ejecucion concurrente — calcado de EntradaClinicaConcurrenteIT
	// =================================================================================

	/** Barrera de salida para que las N tareas arranquen juntas y no en fila. */
	private static List<Desenlace> enParalelo(List<Callable<CasoClinicoView>> tareas) {
		CyclicBarrier salida = new CyclicBarrier(tareas.size());
		try (ExecutorService pool = Executors.newFixedThreadPool(tareas.size())) {
			List<Future<Desenlace>> futuros = new ArrayList<>();
			for (Callable<CasoClinicoView> tarea : tareas) {
				futuros.add(pool.submit(() -> {
					salida.await(10, TimeUnit.SECONDS);
					try {
						return new Desenlace(tarea.call(), null);
					} catch (Exception error) {
						return new Desenlace(null, error);
					}
				}));
			}
			List<Desenlace> desenlaces = new ArrayList<>();
			for (Future<Desenlace> futuro : futuros) {
				desenlaces.add(futuro.get(30, TimeUnit.SECONDS));
			}
			return desenlaces;
		} catch (Exception error) {
			throw new IllegalStateException("La ejecucion concurrente no pudo completarse", error);
		}
	}

	private record Desenlace(CasoClinicoView caso, Exception error) {

		boolean fallo() {
			return error != null;
		}

		@Override
		public String toString() {
			return fallo()
					? "FALLO(" + error.getClass().getSimpleName() + ": " + error.getMessage() + ")"
					: "OK(numeroCaso=" + caso.numeroCaso() + ")";
		}
	}

	// =================================================================================
	// Operaciones y consultas
	// =================================================================================

	private CasoClinicoView abrir(Fixture fixture, String diagnostico, boolean confirma) {
		return servicio.abrir(fixture.actor(), new CasoClinicoAltaCommand(
						fixture.historiaClinicaId(),
						fixture.ofertaId(),
						diagnostico,
						"Recuperar rango de movimiento",
						List.of(new IntegranteDelEquipo(
								fixture.membershipId(), RolEnCaso.RESPONSABLE)),
						confirma),
				JUSTIFICACION);
	}

	private List<Integer> numerosDeCaso(long historiaClinicaId) {
		return jdbc.queryForList("""
				SELECT numero_caso FROM caso_clinico
				 WHERE historia_clinica_id = ? ORDER BY numero_caso
				""", Integer.class, historiaClinicaId);
	}

	private Integer ultimoNumeroDelNumerador(long historiaClinicaId) {
		return jdbc.queryForObject("""
				SELECT ultimo_numero FROM caso_numerador WHERE historia_clinica_id = ?
				""", Integer.class, historiaClinicaId);
	}

	private boolean hayFilaDeNumerador(long historiaClinicaId) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM caso_numerador WHERE historia_clinica_id = ?
				""", Long.class, historiaClinicaId) > 0;
	}

	// =================================================================================
	// Fixture — todo sintetico (AGENT.md seccion 10)
	// =================================================================================

	private record Fixture(
			long organizationId, long consultorioId, long membershipId, long ofertaId,
			long personaId, long historiaClinicaId, OperatingActor actor) {
	}

	/**
	 * Un centro con una sede, un profesional, una oferta vigente y un paciente con historia.
	 *
	 * <p>{@code PROFESIONAL} es el <b>unico</b> rol con {@code hc:read} y {@code hc:write} (matriz
	 * de permisos seccion 2). El test corre contra el evaluador real, asi que tambien falla si esa
	 * asignacion se quita por descuido.
	 *
	 * <p>La oferta se siembra vigente desde hace cinco años porque {@code abrir} la exige vigente
	 * <b>hoy</b> (RN-M10-006), y una oferta que empieza mañana haria fallar todos los escenarios
	 * por un motivo que no es el que se esta midiendo.
	 */
	private Fixture crearFixture() {
		String sufijo = UUID.randomUUID().toString().substring(0, 12);

		long organizationId = insertar("""
				INSERT INTO organization (name, slug, timezone, active, version,
				                          created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{"Centro Sintetico " + sufijo, "caso-it-" + sufijo, ZONA});

		long consultorioId = insertar("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version,
				                         created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{organizationId, "Sede Sintetica " + sufijo, ZONA});

		String email = "caso-it-" + sufijo + "@ejemplo.test";
		long cuentaId = insertar("""
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado, active,
				                    version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', 'DePrueba', 'ACTIVA', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{email, email});

		long membershipId = insertar("""
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, estado, active, version,
				                        created_at, updated_at)
				VALUES (?, ?, ?, 'PROFESIONAL', 0, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 YEAR),
				        'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{organizationId, consultorioId, cuentaId});

		long servicioId = insertar("""
				INSERT INTO servicio (codigo, nombre, naturaleza, modalidad_default,
				                      requiere_caso_clinico_default,
				                      genera_registro_clinico_default, active, version,
				                      created_at, updated_at)
				VALUES (?, ?, 'CLINICO', 'INDIVIDUAL', 0, 0, 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{"CASO-" + sufijo.toUpperCase(), "Servicio " + sufijo});

		long ofertaId = insertar("""
				INSERT INTO oferta_servicio_consultorio
				       (organization_id, consultorio_id, servicio_id, nombre_comercial, modalidad,
				        duracion_minutos, capacidad, precio_base, moneda, admite_obra_social,
				        requiere_caso_clinico, genera_registro_clinico, requiere_profesional,
				        requiere_espacio, vigencia_desde, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'INDIVIDUAL', 60, 1, 8500.00, 'ARS', 0, 1, 1, 1, 0,
				        DATE_SUB(CURDATE(), INTERVAL 5 YEAR), 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{organizationId, consultorioId, servicioId, "Oferta " + sufijo});

		long personaId = crearPaciente(organizationId, cuentaId, "Paciente" + sufijo);
		long historiaClinicaId = abrirHistoria(organizationId, personaId, cuentaId);

		return new Fixture(organizationId, consultorioId, membershipId, ofertaId, personaId,
				historiaClinicaId,
				new OperatingActor(cuentaId, false, organizationId, consultorioId));
	}

	/** Otro paciente del mismo centro, para el control negativo. */
	private long abrirOtraHistoria(Fixture fixture) {
		String sufijo = UUID.randomUUID().toString().substring(0, 12);
		long personaId = crearPaciente(
				fixture.organizationId(), fixture.actor().accountId(), "Otro" + sufijo);
		return abrirHistoria(fixture.organizationId(), personaId, fixture.actor().accountId());
	}

	private long crearPaciente(long organizationId, long cuentaId, String apellido) {
		long personaId = insertar("""
				INSERT INTO persona (organization_id, apellido, nombre, apellido_clave,
				                     nombre_clave, active, version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', ?, 'SINTETICO', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{organizationId, apellido, apellido.toUpperCase()});

		insertar("""
				INSERT INTO perfil_paciente (organization_id, persona_id, activado_en, activado_por,
				                             active, version, created_at, updated_at)
				VALUES (?, ?, UTC_TIMESTAMP(6), ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{organizationId, personaId, cuentaId});
		return personaId;
	}

	private long abrirHistoria(long organizationId, long personaId, long cuentaId) {
		return insertar("""
				INSERT INTO historia_clinica (organization_id, persona_id, abierta_en, abierta_por,
				                              active, version, created_at, updated_at)
				VALUES (?, ?, UTC_TIMESTAMP(6), ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{organizationId, personaId, cuentaId});
	}

	private long insertar(String sql, Object[] args) {
		jdbc.update(sql, args);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}
}
