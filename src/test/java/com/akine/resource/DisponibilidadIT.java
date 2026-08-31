package com.akine.resource;

import com.akine.TestcontainersConfiguration;
import com.akine.organization.spi.ColaboradorDesvinculacionProbe;
import com.akine.resource.application.BloqueAltaCommand;
import com.akine.resource.application.BloqueView;
import com.akine.resource.application.CalendarioService;
import com.akine.resource.application.DisponibilidadEfectivaService;
import com.akine.resource.application.DisponibilidadEfectivaView;
import com.akine.resource.application.DisponibilidadEfectivaView.DiaEfectivo;
import com.akine.resource.application.DisponibilidadService;
import com.akine.resource.application.ExcepcionAltaCommand;
import com.akine.resource.application.ExcepcionService;
import com.akine.resource.application.OperatingActor;
import com.akine.resource.domain.BloqueDisponibilidad;
import com.akine.resource.domain.DisponibilidadExcepcion;
import com.akine.resource.domain.IntervaloLocal;
import com.akine.resource.domain.MotivoExcepcion;
import com.akine.resource.domain.TipoExcepcion;
import com.akine.resource.domain.exception.BloqueNotAccessibleException;
import com.akine.resource.domain.exception.BloqueSolapadoException;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.BloqueDisponibilidadRepositoryPort;
import com.akine.resource.infrastructure.BloqueDisponibilidadRepository;
import com.akine.resource.infrastructure.DisponibilidadExcepcionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * La disponibilidad profesional contra MySQL de verdad (AKINE-02.04, tarea 12).
 *
 * <h2>Que hace este test que ninguno de los unitarios de la etapa puede hacer</h2>
 *
 * <p>La etapa entera se apoya en cuatro afirmaciones que <b>solo una base real puede confirmar o
 * desmentir</b>, y hasta esta tarea estaban razonadas y no medidas:
 *
 * <ol>
 *   <li><b>Que el lock SERIALICE.</b> Hay un test unitario con {@code InOrder} que fija que
 *       {@code lockByScope} se pide ANTES de leer los bloques. Eso prueba el ORDEN de dos
 *       llamadas sobre un mock, no que dos transacciones concurrentes se esperen. MySQL 8.4 no
 *       tiene exclusion constraints, asi que si el {@code FOR UPDATE} no serializa, dos altas
 *       solapadas entran las dos y no falla nada: la base queda con un horario imposible y el
 *       calculo de disponibilidad efectiva lo reporta sin protestar.</li>
 *   <li><b>Que {@code LocalTime.MAX} sobreviva el viaje.</b> {@code HoraLocalConverter} y
 *       {@code HoraJdbcType} traducen el fin del dia a {@code '24:00:00'}. Hasta ahora solo se
 *       habia probado que el contexto arranca y que el esquema valida; ningun valor habia hecho
 *       el viaje completo. Un bug en UNA sola direccion acorta en silencio todo bloque que llegue
 *       a la medianoche.</li>
 *   <li><b>Que el JPQL de alcance diga lo que su javadoc promete.</b>
 *       {@code findFuturasDeLaMembership} tiene que EXCLUIR las excepciones de sede y
 *       {@code findQueCubren} tiene que INCLUIRLAS. Son la misma tabla, el mismo repositorio y
 *       predicados opuestos; con mocks, quien decide que filas vuelven es el propio test.</li>
 *   <li><b>Que la auditoria viva en la transaccion del negocio.</b> Un doble de
 *       {@code AuditTrail} confirma que se la llamo, no que su fila haga rollback junto con la
 *       del bloque.</li>
 * </ol>
 *
 * <h2>Por que no hay ni un solo mock, y por que tampoco hay HTTP</h2>
 *
 * <p>Los servicios se inyectan como beans REALES —con su proxy transaccional, que es lo que hace
 * posible el escenario de concurrencia— y el {@code PermissionGuard} tambien es el real: el actor
 * que opera es el fundador {@code ORG_ADMIN} de un tenant sintetico, y la matriz seccion 6 le
 * concede {@code consultorio:manage} con alcance ORGANIZACION sin ningun caso especial. No hace
 * falta simular la autorizacion porque se cumple de verdad.
 *
 * <p>Tampoco se pasa por HTTP: el token, el filtro de contexto y el mapeo de excepciones a codigos
 * ya tienen sus propios tests, y meterlos aca solo agregaria formas de fallar que no son las que
 * esta tarea investiga. Lo que se ejerce es la frontera aplicacion-base.
 *
 * <p><b>Datos sinteticos unicamente</b>, sembrados por SQL con el mismo fixture que
 * {@code DisponibilidadMigrationIT} y {@code DisponibilidadEfectivaSedeIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class DisponibilidadIT {

	/** UTC-03 todo el anio: no hay DST que corra los instantes y ensucie las aserciones. */
	private static final String ZONA = "America/Argentina/Cordoba";

	/**
	 * Feriado AR sembrado por V22, y por lo tanto el dia donde la politica de la sede se nota.
	 *
	 * <p>Solo tiene que ser una fecha con feriado sembrado: <b>no hay ninguna restriccion respecto
	 * de hoy</b>. La hubo mientras el fixture arrancaba el vinculo "hace un minuto" —una fecha
	 * anterior salia vacia por {@code VINCULO} y el escenario del feriado nunca se evaluaba— y esa
	 * dependencia del calendario se elimino en su origen: ver {@link #insertarMembership}.
	 */
	private static final LocalDate FERIADO = LocalDate.of(2026, 12, 25);

	@Autowired
	private DisponibilidadService disponibilidadService;

	@Autowired
	private DisponibilidadEfectivaService efectivaService;

	@Autowired
	private ExcepcionService excepcionService;

	@Autowired
	private CalendarioService calendarioService;

	@Autowired
	private BloqueDisponibilidadRepositoryPort bloques;

	@Autowired
	private BloqueDisponibilidadRepository bloqueRepository;

	@Autowired
	private DisponibilidadExcepcionRepository excepcionRepository;

	@Autowired
	private ColaboradorDesvinculacionProbe desvinculacionProbe;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private DataSource dataSource;

	private JdbcTemplate jdbc;

	@BeforeEach
	void prepararJdbc() {
		this.jdbc = new JdbcTemplate(dataSource);
	}

	// =================================================================================
	// EL test de la tarea: que el lock serialice de verdad
	// =================================================================================

	/**
	 * Dos altas concurrentes que se pisan: una entra y la otra recibe 409. NUNCA las dos.
	 *
	 * <p><b>Es la garantia central de la etapa.</b> El solapamiento se valida en aplicacion
	 * porque MySQL 8.4 no puede impedirlo con una constraint (diseno seccion 5), asi que lo unico
	 * que separa "dos bloques que se pisan" de "un 409" es que el {@code SELECT ... FOR UPDATE}
	 * sobre {@code consultorio_calendario} serialice a las dos transacciones. Si no serializa,
	 * las dos leen la sede vacia, las dos no encuentran conflicto y las dos insertan.
	 *
	 * <p><b>Este test tiene que poder fallar, y se verifico que falla.</b> Cambiando
	 * {@code lockByScope} por {@code findByScope} en {@code DisponibilidadService#bloquearLaSede}
	 * —o sea, leyendo la fila sin bloquearla— quedan DOS bloques activos solapados y la asercion
	 * final rompe. La evidencia esta en el reporte de la tarea 12.
	 *
	 * <p><b>La fila del calendario se crea ANTES de la carrera, y no es para que el test pase
	 * mas facil.</b> La tarea 7 declaro una ventana angosta y conocida: si dos requests son los
	 * PRIMEROS de una sede, los dos ven la fila ausente y los dos la insertan; el unique rechaza a
	 * uno y ese request falla por otro motivo. Esa ventana no es lo que este test investiga, y
	 * dejarla en juego cambiaria el desenlace por una razon equivocada. En produccion la cierra el
	 * {@code PUT /calendario} del alta de sede, que es exactamente lo que se hace aca.
	 *
	 * <p>La asercion final es sobre el ESTADO DE LA BASE y no sobre el orden en que respondieron
	 * los hilos: un invariante de conteo da la misma respuesta hayan corrido en paralelo o no, y
	 * lo que no puede hacer es dar verde con el invariante roto.
	 */
	@Test
	@DisplayName("dos altas concurrentes solapadas no pasan las dos: una guarda y la otra recibe 409")
	void dos_altas_concurrentes_solapadas_no_pasan_las_dos() {
		Fixture fixture = crearFixture();
		OperatingActor admin = fixture.admin();

		// La fila que sirve de punto de serializacion existe antes de la carrera: ver el javadoc.
		calendarioService.actualizar(admin, fixture.consultorioId(), "AR", true);

		LocalDate vigencia = LocalDate.of(2026, 9, 1);
		// Mismo dia, horarios que se pisan entre las 11 y las 13, y NO coinciden exacto: el
		// camino idempotente de CA-M05-003-05 no aplica, asi que el desenlace legitimo del
		// perdedor es 409 y no "te devuelvo el que ya existe".
		Callable<BloqueView> primera = () -> disponibilidadService.crear(
				admin, fixture.consultorioId(), fixture.profesionalMembershipId(),
				new BloqueAltaCommand(2, LocalTime.of(9, 0), LocalTime.of(13, 0), vigencia, null));
		Callable<BloqueView> segunda = () -> disponibilidadService.crear(
				admin, fixture.consultorioId(), fixture.profesionalMembershipId(),
				new BloqueAltaCommand(2, LocalTime.of(11, 0), LocalTime.of(15, 0), vigencia, null));

		List<Desenlace<BloqueView>> desenlaces = enParalelo(List.of(primera, segunda));

		long exitos = desenlaces.stream().filter(d -> !d.fallo()).count();
		long rechazos = desenlaces.stream()
				.filter(Desenlace::fallo)
				.filter(d -> causaEs(d.error(), BloqueSolapadoException.class))
				.count();

		assertThat(exitos)
				.as("exactamente un alta entra. Desenlaces: %s", describir(desenlaces))
				.isEqualTo(1);
		assertThat(rechazos)
				.as("la otra recibe BloqueSolapadoException (409). Desenlaces: %s",
						describir(desenlaces))
				.isEqualTo(1);

		assertThat(bloques.findActivosDe(
				fixture.organizationId(), fixture.consultorioId(), fixture.profesionalMembershipId()))
				.as("NUNCA dos: si quedan dos filas activas el FOR UPDATE de consultorio_calendario "
						+ "no serializo y la sede tiene un horario imposible que nadie va a "
						+ "detectar hasta que alguien mire la grilla")
				.hasSize(1);
	}

	/**
	 * Lo mismo, pero siendo las DOS las PRIMERAS escrituras de la sede: la fila de
	 * {@code consultorio_calendario} no existe cuando arranca la carrera.
	 *
	 * <p><b>Es el camino que el test de arriba nunca ejercita</b>, porque el crea la fila a
	 * proposito antes de empezar. Mientras la fila se creaba perezosamente DENTRO de la
	 * transaccion de la escritura —bloquear, ver el vacio, insertar, volver a bloquear— las dos
	 * transacciones leian "no existe" y las dos intentaban el mismo INSERT. El javadoc de
	 * {@code BloqueoDeSede} lo llamaba una ventana angosta cuyo "remedio es un reintento del
	 * cliente": el remedio de un 500 en un pedido legitimo.
	 *
	 * <p>Este test <b>tiene que poder fallar y se verifico que falla</b>. Con la version anterior
	 * de {@code BloqueoDeSede} el perdedor murio con
	 * {@code DataIntegrityViolationException: Duplicate entry '1-1' for key
	 * uk_consultorio_calendario_sede} y esta asercion quedo en rojo. El desenlace no es
	 * determinista —el mismo patron en {@code scheduling} produjo un deadlock, con
	 * {@code CannotAcquireLockException}— y por eso la asercion mira que el perdedor reciba
	 * EXACTAMENTE {@code BloqueSolapadoException} en vez de enumerar las formas de fallar.
	 *
	 * <p><b>Y de paso responde la otra pregunta.</b> Un lock no alcanza si la transaccion ya fijo
	 * su foto de lectura antes de tomarlo: con {@code REPEATABLE READ} —el default de MySQL—
	 * InnoDB congela el snapshot en la PRIMERA lectura consistente, y aca hay tres antes del lock
	 * (resolver la sede, evaluar el permiso, resolver la membership). El que espera el lock
	 * seguiria leyendo la sede sin bloques y las dos altas entrarian.
	 *
	 * <p><b>No pasa, y se comprobo por que no pasa</b>: las tres mutaciones de M05 nacieron con
	 * {@code Isolation.READ_COMMITTED} en 02.04. Sacandoselo a {@code DisponibilidadService#crear}
	 * este test y su hermano de arriba fallan los dos con "OK bloque N, OK bloque M" —los dos
	 * bloques solapados entran, que es el sintoma exacto del defecto—. O sea: el nivel de
	 * aislamiento es la pieza que sostiene el lock, no un adorno.
	 */
	@Test
	@DisplayName("las dos PRIMERAS altas de la sede tampoco pasan las dos: una guarda y la otra recibe 409")
	void las_dos_primeras_altas_de_la_sede_no_pasan_las_dos() {
		Fixture fixture = crearFixture();
		OperatingActor admin = fixture.admin();

		// A DIFERENCIA del test de arriba: NO se crea el calendario. La carrera arranca con la
		// fila de serializacion ausente, que es el estado real de una sede recien dada de alta.
		assertThat(contar("""
				SELECT COUNT(*) FROM consultorio_calendario
				 WHERE organization_id = ? AND consultorio_id = ?
				""", fixture.organizationId(), fixture.consultorioId()))
				.as("el escenario pierde todo su valor si algo creo la fila antes")
				.isZero();

		LocalDate vigencia = LocalDate.of(2026, 9, 1);
		Callable<BloqueView> primera = () -> disponibilidadService.crear(
				admin, fixture.consultorioId(), fixture.profesionalMembershipId(),
				new BloqueAltaCommand(4, LocalTime.of(9, 0), LocalTime.of(13, 0), vigencia, null));
		Callable<BloqueView> segunda = () -> disponibilidadService.crear(
				admin, fixture.consultorioId(), fixture.profesionalMembershipId(),
				new BloqueAltaCommand(4, LocalTime.of(11, 0), LocalTime.of(15, 0), vigencia, null));

		List<Desenlace<BloqueView>> desenlaces = enParalelo(List.of(primera, segunda));

		assertThat(desenlaces.stream().filter(d -> !d.fallo()).count())
				.as("exactamente un alta entra. Desenlaces: %s", describir(desenlaces))
				.isEqualTo(1);
		assertThat(desenlaces.stream()
				.filter(Desenlace::fallo)
				.filter(d -> causaEs(d.error(), BloqueSolapadoException.class))
				.count())
				.as("la otra recibe BloqueSolapadoException, y NO un deadlock ni un "
						+ "UnexpectedRollbackException por la fila de calendario. Desenlaces: %s",
						describir(desenlaces))
				.isEqualTo(1);

		assertThat(bloques.findActivosDe(
				fixture.organizationId(), fixture.consultorioId(), fixture.profesionalMembershipId()))
				.as("NUNCA dos bloques solapados")
				.hasSize(1);

		assertThat(contar("""
				SELECT COUNT(*) FROM consultorio_calendario
				 WHERE organization_id = ? AND consultorio_id = ?
				""", fixture.organizationId(), fixture.consultorioId()))
				.as("y UNA sola fila de calendario: el INSERT ... ON DUPLICATE KEY resuelve la "
						+ "carrera sin duplicar y sin lanzar")
				.isEqualTo(1);
	}

	// =================================================================================
	// RULING: el fin de dia tiene que sobrevivir el viaje de ida Y de vuelta
	// =================================================================================

	/**
	 * Un bloque que llega a la medianoche vuelve de MySQL siendo la medianoche, y una hora normal
	 * vuelve sin cambiar.
	 *
	 * <p>{@code LocalTime} no puede representar las 24:00 —su maximo es 23:59:59.999999999— y
	 * MySQL si acepta {@code '24:00:00'} en una columna {@code TIME}. Entre las dos cosas hay un
	 * {@code AttributeConverter} y un {@code JdbcType} propio, y hasta esta tarea ningun valor
	 * habia hecho el viaje completo contra una base real.
	 *
	 * <p><b>La ida sola no alcanza y por eso el test lee de vuelta desde la base.</b> Si la
	 * conversion fallara en una sola direccion, un bloque de 22:00 a medianoche se guardaria o se
	 * leeria como algo mas corto y <b>no lanzaria nada</b>: la disponibilidad efectiva devolveria
	 * una franja recortada, perfectamente formada, y el centro perderia turnos sin un solo error
	 * en el log.
	 *
	 * <p>La hora normal esta en la misma prueba a proposito: un converter que arregla la
	 * medianoche rompiendo el caso comun es peor que el bug que arregla.
	 */
	@Test
	@DisplayName("el fin de dia y una hora normal sobreviven el viaje de ida y vuelta a MySQL")
	void el_fin_de_dia_sobrevive_el_viaje_de_ida_y_vuelta() {
		Fixture fixture = crearFixture();
		LocalDate vigencia = LocalDate.of(2026, 9, 1);

		BloqueView nocturno = disponibilidadService.crear(
				fixture.admin(), fixture.consultorioId(), fixture.profesionalMembershipId(),
				new BloqueAltaCommand(
						1, LocalTime.of(22, 0), IntervaloLocal.FIN_DE_DIA, vigencia, null));
		BloqueView vespertino = disponibilidadService.crear(
				fixture.admin(), fixture.consultorioId(), fixture.profesionalMembershipId(),
				new BloqueAltaCommand(
						3, LocalTime.of(14, 0), LocalTime.of(18, 0), vigencia, null));

		// Lo que la base tiene escrito, sin pasar por el converter: la mitad de IDA.
		assertThat(horaHastaCruda(nocturno.id()))
				.as("en la columna TIME el fin del dia se escribe como '24:00:00': es el unico "
						+ "valor que expresa el final en un extremo exclusivo, porque '00:00:00' "
						+ "significaria el principio")
				.isEqualTo("24:00:00");
		assertThat(horaHastaCruda(vespertino.id())).isEqualTo("18:00:00");

		// Y lo que vuelve por el mapeo, con la sesion de persistencia limpia: la mitad de VUELTA.
		BloqueDisponibilidad leidoNocturno = leerDeLaBase(fixture, nocturno.id());
		assertThat(leidoNocturno.getHoraHasta())
				.as("si la vuelta perdiera el caso especial, todo bloque que llega a la medianoche "
						+ "se acortaria en silencio y nada fallaria")
				.isEqualTo(IntervaloLocal.FIN_DE_DIA);
		assertThat(leidoNocturno.getHoraDesde()).isEqualTo(LocalTime.of(22, 0));

		BloqueDisponibilidad leidoVespertino = leerDeLaBase(fixture, vespertino.id());
		assertThat(leidoVespertino.getHoraDesde()).isEqualTo(LocalTime.of(14, 0));
		assertThat(leidoVespertino.getHoraHasta())
				.as("una hora comun no recibe ningun tratamiento especial y vuelve identica")
				.isEqualTo(LocalTime.of(18, 0));
	}

	// =================================================================================
	// Aislamiento de tenant
	// =================================================================================

	/**
	 * Un bloque de otro tenant no resuelve ni con el id exacto en la mano.
	 *
	 * <p>Es la regla heredada de 01.01: cross-tenant es <b>404 y nunca 403</b>, porque un 403
	 * confirma que la fila existe y bastaria probar ids consecutivos para mapear el padron de
	 * otro centro. Aca se ejerce con el id REAL de un bloque ajeno, que es el unico caso que
	 * distingue "la consulta filtra por tenant" de "el id no existia igual".
	 */
	@Test
	@DisplayName("un bloque de otro tenant no resuelve ni siquiera con el id correcto")
	void un_bloque_de_otro_tenant_no_resuelve_ni_siquiera_con_el_id_correcto() {
		Fixture ajeno = crearFixture();
		Fixture propio = crearFixture();

		BloqueView delAjeno = disponibilidadService.crear(
				ajeno.admin(), ajeno.consultorioId(), ajeno.profesionalMembershipId(),
				new BloqueAltaCommand(2, LocalTime.of(9, 0), LocalTime.of(13, 0),
						LocalDate.of(2026, 9, 1), null));

		assertThatThrownBy(() -> disponibilidadService.darDeBaja(
				propio.admin(), propio.consultorioId(), propio.profesionalMembershipId(),
				delAjeno.id(), "Intento de baja cruzada"))
				.as("con el id correcto de un bloque de otro tenant la respuesta sigue siendo la "
						+ "de un id inexistente: 404, no 403")
				.isInstanceOf(BloqueNotAccessibleException.class);

		// El predicado que lo sostiene, directo contra la consulta: el id existe, el alcance no.
		assertThat(bloques.findByIdScoped(delAjeno.id(), propio.organizationId(), propio.consultorioId()))
				.as("findByIdScoped filtra por organizacion Y por sede")
				.isEmpty();
		assertThat(bloques.findByIdScoped(delAjeno.id(), ajeno.organizationId(), ajeno.consultorioId()))
				.as("y el mismo id si resuelve en su propio alcance: si no, este test estaria "
						+ "verde porque la fila no existe, que es otra cosa")
				.isPresent();
	}

	// =================================================================================
	// Baja logica
	// =================================================================================

	/**
	 * La baja de un bloque no borra nada: apaga la fila y le escribe su historia.
	 *
	 * <p>{@code AGENT.md} lo exige para toda informacion historica, y aca tiene un motivo
	 * concreto: un bloque dado de baja es la unica prueba de por que un profesional dejo de
	 * atender un dia. Un {@code DELETE} deja la pregunta sin respuesta seis meses despues, que es
	 * exactamente cuando se hace.
	 *
	 * <p>Se verifica contra la fila cruda y no contra la vista: el {@code CHECK}
	 * {@code ck_profesional_disponibilidad_baja_coherente} exige que {@code active},
	 * {@code deleted_at} y {@code deactivation_reason} se muevan juntos, y mirar la fila es lo
	 * unico que confirma que los tres llegaron a la base.
	 */
	@Test
	@DisplayName("la baja logica conserva la fila y su historia")
	void la_baja_logica_conserva_la_fila_y_su_historia() {
		Fixture fixture = crearFixture();

		BloqueView creado = disponibilidadService.crear(
				fixture.admin(), fixture.consultorioId(), fixture.profesionalMembershipId(),
				new BloqueAltaCommand(4, LocalTime.of(8, 0), LocalTime.of(12, 0),
						LocalDate.of(2026, 9, 1), null));

		String motivo = "El profesional dejo de atender los jueves por la manana";
		BloqueView baja = disponibilidadService.darDeBaja(
				fixture.admin(), fixture.consultorioId(), fixture.profesionalMembershipId(),
				creado.id(), motivo);

		assertThat(baja.estado()).isEqualTo("INACTIVO");

		Map<String, Object> fila = jdbc.queryForMap(
				"SELECT active, deleted_at, deactivation_reason, hora_desde, hora_hasta, dia_semana "
						+ "FROM profesional_disponibilidad WHERE id = ?", creado.id());

		assertThat(fila.get("active"))
				.as("la fila sigue ahi: la baja es logica, no un DELETE")
				.isEqualTo(false);
		assertThat(fila.get("deleted_at"))
				.as("sin deleted_at la baja no seria coherente y el CHECK la habria rechazado")
				.isNotNull();
		assertThat(fila.get("deactivation_reason"))
				.as("el motivo declarado queda escrito: es lo que responde 'por que' mucho despues")
				.isEqualTo(motivo);
		assertThat(fila.get("dia_semana")).hasToString("4");

		assertThat(bloques.findActivosDe(
				fixture.organizationId(), fixture.consultorioId(), fixture.profesionalMembershipId()))
				.as("y deja de computar: un bloque dado de baja no es disponibilidad")
				.isEmpty();
	}

	// =================================================================================
	// Desvinculacion
	// =================================================================================

	/**
	 * Desvincular a un profesional deja su disponibilidad efectiva vacia sin borrarle una sola
	 * fila.
	 *
	 * <p>RN-M05-003 en su forma mas concreta: el bloque sobrevive a la desvinculacion porque
	 * responde por lo que esa persona hacia, y el calculo lo ignora <b>porque la membership dejo
	 * de estar vigente, no porque el bloque se haya dado de baja</b>. Son dos ejes distintos y el
	 * dia que se confundan, desvincular a alguien va a empezar a borrar historia.
	 *
	 * <p>La razon del dia vacio se verifica y no es un detalle de presentacion: {@code VINCULO} y
	 * {@code CIERRE} mandan al administrador a lugares distintos. Con el cartel equivocado va a
	 * buscar una excepcion que nadie cargo.
	 */
	@Test
	@DisplayName("desvincular no borra la disponibilidad y la efectiva queda vacia")
	void desvincular_no_borra_la_disponibilidad_y_la_efectiva_queda_vacia() {
		Fixture fixture = crearFixture();
		// Un martes cualquiera, deliberadamente NO feriado, para que el unico motivo posible del
		// dia vacio sea el vinculo.
		LocalDate martes = LocalDate.of(2026, 9, 1);

		// El dia de la semana sale de la fecha, igual que en el escenario del feriado: un numero a
		// mano se desincroniza en silencio el dia que alguien mueva la constante, y el sintoma seria
		// un dia vacio que se confunde con el que este test va a buscar a proposito.
		BloqueView creado = disponibilidadService.crear(
				fixture.admin(), fixture.consultorioId(), fixture.profesionalMembershipId(),
				new BloqueAltaCommand(martes.getDayOfWeek().getValue(),
						LocalTime.of(9, 0), LocalTime.of(13, 0), martes, null));

		DisponibilidadEfectivaView antes = efectivaService.efectiva(
				fixture.admin(), fixture.consultorioId(), fixture.profesionalMembershipId(),
				martes, martes.plusDays(1));
		assertThat(antes.dias()).hasSize(1);
		assertThat(antes.dias().getFirst().franjas())
				.as("con el vinculo vigente el bloque se ve: si no, el resto del test no probaria nada")
				.hasSize(1);

		// La desvinculacion, tal como la escribe organization: el vinculo deja de estar vigente.
		// No se toca ni una fila de disponibilidad, que es justamente el punto.
		desvincular(fixture.profesionalMembershipId());

		DisponibilidadEfectivaView despues = efectivaService.efectiva(
				fixture.admin(), fixture.consultorioId(), fixture.profesionalMembershipId(),
				martes, martes.plusDays(1));

		assertThat(despues.dias())
				.as("el dia viaja igual: la pantalla dibuja una grilla y un dia faltante la correria")
				.hasSize(1);
		DiaEfectivo dia = despues.dias().getFirst();
		assertThat(dia.franjas()).isEmpty();
		assertThat(dia.razonVacio())
				.as("VINCULO y no CIERRE: 'ya no trabaja aca' y 'no atiende ese dia' necesitan "
						+ "textos distintos")
				.isEqualTo("VINCULO");

		assertThat(jdbc.queryForObject(
				"SELECT active FROM profesional_disponibilidad WHERE id = ?", Boolean.class,
				creado.id()))
				.as("la fila sigue activa: desvincular no da de baja el horario (RN-M05-003)")
				.isTrue();
	}

	/**
	 * La sonda de desvinculacion cuenta lo del profesional y nada mas: ni las excepciones de la
	 * sede, ni los bloques cuya vigencia ya paso.
	 *
	 * <p>Los dos sobre-conteos producen un numero <b>plausible</b>, y ahi esta el problema: quien
	 * esta por desvincular a alguien ve "tres cosas pendientes", no tiene forma de saber que son
	 * dos, y confirma igual. Nadie audita un numero que parece razonable.
	 *
	 * <ul>
	 *   <li><b>Excepciones de sede.</b> {@code findFuturasDeLaMembership} es el inverso exacto de
	 *       {@code findQueCubren}: aquel INCLUYE {@code membership_id IS NULL} a proposito —un
	 *       cierre de sede se le aplica a todos— y este las EXCLUYE, porque no son "de" esta
	 *       persona y la sede sigue existiendo despues de que se vaya. Viven en el mismo
	 *       repositorio con predicados opuestos y hasta esta tarea solo los cubria Mockito, o sea
	 *       el propio test decidiendo que filas vuelven.</li>
	 *   <li><b>Bloques vencidos.</b> La consulta contaba todo {@code active = true}. Un horario
	 *       que termino en marzo y que nadie dio de baja —no hacia falta, la ventana operativa ya
	 *       lo habia apagado— sumaba igual. Se corrigio en esta tarea:
	 *       {@code BloqueDisponibilidadRepository#countVigentesDe}.</li>
	 * </ul>
	 */
	@Test
	@DisplayName("la sonda de desvinculacion excluye las excepciones de sede y los bloques vencidos")
	void la_sonda_de_desvinculacion_excluye_lo_que_no_es_del_profesional() {
		Fixture fixture = crearFixture();
		Instant ahora = Instant.parse("2026-09-01T12:00:00Z");
		LocalDate hoy = LocalDate.of(2026, 9, 1);

		// Un bloque VENCIDO y nunca dado de baja: activo, pero su vigencia termino en marzo.
		insertarBloqueVencido(fixture, hoy.minusMonths(6), hoy.minusMonths(5));

		// Un cierre de la SEDE ENTERA (membership_id NULL) en el futuro: no es de esta persona.
		excepcionService.crear(fixture.admin(), fixture.consultorioId(), new ExcepcionAltaCommand(
				null, TipoExcepcion.CIERRE, MotivoExcepcion.BLOQUEO,
				hoy.plusDays(10), hoy.plusDays(12), null, null, null, "Corte de luz programado"));

		// Y una del profesional, tambien futura: la UNICA que le pertenece.
		var suya = excepcionService.crear(
				fixture.admin(), fixture.consultorioId(), new ExcepcionAltaCommand(
						fixture.profesionalMembershipId(), TipoExcepcion.CIERRE,
						MotivoExcepcion.LICENCIA, hoy.plusDays(20), hoy.plusDays(30),
						null, null, null, "Licencia sintetica"));

		List<DisponibilidadExcepcion> futuras = excepcionRepository.findFuturasDeLaMembership(
				fixture.organizationId(), fixture.profesionalMembershipId(), hoy);
		assertThat(futuras)
				.as("solo la del profesional: si entrara la de sede, la pantalla le atribuiria a "
						+ "una persona los cierres de todo el centro")
				.extracting(DisponibilidadExcepcion::getId)
				.containsExactly(suya.id());

		assertThat(bloqueRepository.countVigentesDe(
				fixture.organizationId(), fixture.profesionalMembershipId(), hoy))
				.as("el bloque vencido no cuenta: su ventana operativa ya lo apago")
				.isZero();

		ColaboradorDesvinculacionProbe.Impacto impacto = desvinculacionProbe.pendingWorkOn(
				fixture.organizationId(), fixture.profesionalMembershipId(),
				fixture.profesionalAccountId(), ahora);

		assertThat(impacto.count())
				.as("una sola cosa pendiente, no tres")
				.isEqualTo(1L);
		assertThat(impacto.tipo())
				.as("y es una excepcion, no 'bloques y excepciones': el tipo tambien se ensucia "
						+ "con el bloque vencido")
				.isEqualTo("excepciones de disponibilidad");

		// El complemento, para que quede claro que el predicado opuesto sigue siendo el opuesto.
		assertThat(excepcionRepository.findQueCubren(
				fixture.organizationId(), fixture.consultorioId(),
				fixture.profesionalMembershipId(), hoy, hoy.plusDays(40)))
				.as("findQueCubren SI trae la de sede junto con la del profesional: un cierre de "
						+ "sede se le aplica a todos y ese predicado es deliberado")
				.hasSize(2);
	}

	// =================================================================================
	// Feriado y politica de la sede
	// =================================================================================

	/**
	 * El feriado nacional, la politica de la sede y una apertura puntual se combinan como dice el
	 * diseno seccion 4, en ese orden y contra datos reales.
	 *
	 * <p>Tres estados del mismo dia, y los tres importan:
	 *
	 * <ol>
	 *   <li>La sede cierra los feriados: el dia queda vacio y su razon es {@code FERIADO}. El
	 *       feriado es un HECHO del calendario y viaja igual —{@code esFeriado} y el nombre—
	 *       porque sin el la pantalla dice "cerrado" y no puede decir por que.</li>
	 *   <li>La sede NO cierra los feriados: el horario base aplica normalmente. Es la decision
	 *       operativa la que cierra, nunca el feriado por si solo.</li>
	 *   <li>La sede cierra, pero hay una APERTURA puntual: el dia parte SOLO de esa apertura y el
	 *       horario base se DESCARTA. Es el caso que corrigio el diseno el 26/08/2026: con la
	 *       regla vieja, declarar "este 25 de diciembre abrimos de 10 a 14" resolvia a las ocho horas
	 *       del horario base y la apertura no servia para nada.</li>
	 * </ol>
	 */
	@Test
	@DisplayName("el feriado y la politica de la sede se combinan como dice el diseno")
	void el_feriado_y_la_politica_de_la_sede_se_combinan_como_dice_el_diseno() {
		Fixture fixture = crearFixture();
		// El dia de la semana sale de la fecha y no de una constante: un numero a mano se
		// desincroniza en silencio el dia que alguien cambie el feriado elegido.
		int diaDelFeriado = FERIADO.getDayOfWeek().getValue();

		BloqueView base = disponibilidadService.crear(
				fixture.admin(), fixture.consultorioId(), fixture.profesionalMembershipId(),
				new BloqueAltaCommand(diaDelFeriado, LocalTime.of(8, 0), LocalTime.of(18, 0),
						FERIADO.minusMonths(1), null));

		// 1. La sede cierra los feriados.
		calendarioService.actualizar(fixture.admin(), fixture.consultorioId(), "AR", true);
		DiaEfectivo cerrado = unicoDia(fixture, FERIADO);
		assertThat(cerrado.esFeriado())
				.as("el feriado es un hecho del calendario y se informa siempre")
				.isTrue();
		assertThat(cerrado.feriadoNombre()).isEqualTo("Navidad");
		assertThat(cerrado.franjas()).isEmpty();
		assertThat(cerrado.razonVacio()).isEqualTo("FERIADO");

		// 2. La misma sede, con la politica apagada.
		calendarioService.actualizar(fixture.admin(), fixture.consultorioId(), null, false);
		DiaEfectivo abierto = unicoDia(fixture, FERIADO);
		assertThat(abierto.esFeriado())
				.as("sigue siendo feriado aunque el centro atienda: el hecho no depende de la "
						+ "decision")
				.isTrue();
		assertThat(abierto.franjas())
				.as("sin politica de cierre el horario base aplica igual que cualquier otro dia")
				.hasSize(1);
		assertThat(abierto.franjas().getFirst().reglaId()).isEqualTo(base.id());
		assertThat(abierto.razonVacio()).isNull();

		// 3. La politica vuelve a cerrar, pero el centro declara una apertura puntual.
		calendarioService.actualizar(fixture.admin(), fixture.consultorioId(), null, true);
		var apertura = excepcionService.crear(
				fixture.admin(), fixture.consultorioId(), new ExcepcionAltaCommand(
						fixture.profesionalMembershipId(), TipoExcepcion.APERTURA,
						MotivoExcepcion.AMPLIACION, FERIADO, FERIADO.plusDays(1),
						LocalTime.of(10, 0), LocalTime.of(14, 0), null,
						"Este ano abrimos el 25 de diciembre"));

		DiaEfectivo conApertura = unicoDia(fixture, FERIADO);
		assertThat(conApertura.franjas())
				.as("una sola franja: la apertura ES el dia. Si aparecieran las ocho horas del "
						+ "bloque base, declarar una apertura no serviria para nada y el sistema "
						+ "ofreceria turnos un dia que el centro pensaba abrir a medias")
				.hasSize(1);
		assertThat(conApertura.franjas().getFirst().origen()).isEqualTo("APERTURA");
		assertThat(conApertura.franjas().getFirst().reglaId()).isEqualTo(apertura.id());
		assertThat(conApertura.razonVacio()).isNull();
	}

	// =================================================================================
	// Auditoria
	// =================================================================================

	/**
	 * La fila de auditoria del alta vive en la MISMA transaccion que el bloque: si la del bloque
	 * hace rollback, la de auditoria tambien.
	 *
	 * <p>Es una regla heredada de 01.01 y la razon es simetrica en las dos direcciones. Una
	 * auditoria escrita <b>despues</b> del commit —un listener post-commit— puede fallar y dejar
	 * la mutacion sin rastro. Una auditoria escrita en su <b>propia</b> transaccion
	 * ({@code REQUIRES_NEW}, otra conexion) sobrevive al rollback del negocio y deja registrado un
	 * alta que nunca ocurrio. Las dos rompen la trazabilidad, en sentidos opuestos.
	 *
	 * <p>Un doble de {@code AuditTrail} puede confirmar que se lo llamo, y nada mas. Lo que
	 * decide el caso es forzar el rollback contra la base real y ver que no queda NADA: ni el
	 * bloque ni su auditoria. El id se captura antes del rollback justamente porque despues no hay
	 * fila de la que sacarlo.
	 */
	@Test
	@DisplayName("la auditoria queda escrita en la misma transaccion del alta")
	void la_auditoria_queda_escrita_en_la_misma_transaccion_del_alta() {
		Fixture fixture = crearFixture();

		// Mitad uno: el camino feliz deja las dos filas.
		BloqueView commiteado = disponibilidadService.crear(
				fixture.admin(), fixture.consultorioId(), fixture.profesionalMembershipId(),
				new BloqueAltaCommand(5, LocalTime.of(9, 0), LocalTime.of(13, 0),
						LocalDate.of(2026, 9, 1), null));

		Map<String, Object> auditoria = jdbc.queryForMap("""
				SELECT organization_id, consultorio_id, actor_account_id, event_type, new_state
				  FROM audit_event
				 WHERE entity_type = 'BloqueDisponibilidad' AND entity_id = ?
				""", commiteado.id());
		assertThat(auditoria.get("event_type")).isEqualTo("DISPONIBILIDAD_BLOQUE_CREATED");
		assertThat(auditoria.get("new_state")).isEqualTo("ACTIVO");
		assertThat(auditoria.get("organization_id")).hasToString(String.valueOf(fixture.organizationId()));
		assertThat(auditoria.get("consultorio_id")).hasToString(String.valueOf(fixture.consultorioId()));
		assertThat(auditoria.get("actor_account_id")).hasToString(String.valueOf(fixture.adminAccountId()));

		// Mitad dos, la que decide: el rollback se lleva las DOS filas.
		TransactionTemplate plantilla = new TransactionTemplate(transactionManager);
		plantilla.setIsolationLevel(Isolation.READ_COMMITTED.value());
		Long descartado = plantilla.execute(status -> {
			BloqueView efimero = disponibilidadService.crear(
					fixture.admin(), fixture.consultorioId(), fixture.profesionalMembershipId(),
					new BloqueAltaCommand(6, LocalTime.of(9, 0), LocalTime.of(13, 0),
							LocalDate.of(2026, 9, 1), null));
			status.setRollbackOnly();
			return efimero.id();
		});

		assertThat(descartado).isNotNull();
		assertThat(contar("SELECT COUNT(*) FROM profesional_disponibilidad WHERE id = ?", descartado))
				.as("el rollback se llevo el bloque, que es lo esperado")
				.isZero();
		assertThat(contar("""
				SELECT COUNT(*) FROM audit_event
				 WHERE entity_type = 'BloqueDisponibilidad' AND entity_id = ?
				""", descartado))
				.as("y tambien su auditoria. Una fila sobreviviente aca significaria que la "
						+ "auditoria corre en su propia transaccion y estaria registrando un alta "
						+ "que nunca ocurrio")
				.isZero();
	}

	// =================================================================================
	// Concurrencia — dos hilos de verdad, arrancando juntos
	// =================================================================================

	/** Desenlace de una tarea concurrente: su valor, o la excepcion con la que murio. */
	private record Desenlace<T>(T valor, Throwable error) {

		boolean fallo() {
			return error != null;
		}
	}

	/**
	 * Corre las tareas en hilos de verdad, lo mas cerca posible del mismo instante.
	 *
	 * <p>Mismo mecanismo que {@code com.akine.diferidos.Concurrencia}, que no se reutiliza porque
	 * es package-private de ese paquete y hacerlo publico expondria infraestructura de los
	 * escenarios diferidos a todo el arbol de tests.
	 *
	 * <p>El sincronizador es la {@link CyclicBarrier} justo antes de la operacion: <b>ningun hilo
	 * la cruza hasta que llegaron todos</b>, y la ventana entre el cruce y la primera sentencia es
	 * de nanosegundos contra una operacion que tarda milisegundos.
	 *
	 * <p>El original de {@code com.akine.diferidos} tiene ademas un {@code CountDownLatch} de
	 * arranque que se decrementa y <b>nunca se espera</b>: no sincroniza nada. Aca no se copio. El
	 * original queda como esta, que es otra tarea.
	 *
	 * <p>La barrera si hace falta: un {@code invokeAll} a secas no garantiza nada, porque el primer
	 * hilo puede terminar antes de que arranque el segundo y entonces el test da verde por haber
	 * corrido SECUENCIALMENTE, no porque el lock funcione.
	 */
	private static <T> List<Desenlace<T>> enParalelo(List<Callable<T>> tareas) {
		int cantidad = tareas.size();
		CyclicBarrier barrera = new CyclicBarrier(cantidad);

		try (ExecutorService pool = Executors.newFixedThreadPool(cantidad)) {
			List<Future<Desenlace<T>>> futuros = new ArrayList<>(cantidad);
			for (Callable<T> tarea : tareas) {
				futuros.add(pool.submit(() -> {
					try {
						barrera.await(30, TimeUnit.SECONDS);
						return new Desenlace<>(tarea.call(), null);
					} catch (Throwable e) {
						return new Desenlace<T>(null, e);
					}
				}));
			}

			List<Desenlace<T>> desenlaces = new ArrayList<>(cantidad);
			for (Future<Desenlace<T>> futuro : futuros) {
				try {
					desenlaces.add(futuro.get(60, TimeUnit.SECONDS));
				} catch (Exception e) {
					throw new IllegalStateException("Una tarea concurrente no termino a tiempo", e);
				}
			}
			return desenlaces;
		}
	}

	private static boolean causaEs(Throwable error, Class<? extends Throwable> tipo) {
		for (Throwable actual = error; actual != null; actual = actual.getCause()) {
			if (tipo.isInstance(actual)) {
				return true;
			}
		}
		return false;
	}

	private static String describir(List<Desenlace<BloqueView>> desenlaces) {
		return desenlaces.stream()
				.map(d -> d.fallo() ? "ERROR " + d.error() : "OK bloque " + d.valor().id())
				.toList()
				.toString();
	}

	// =================================================================================
	// Lecturas de apoyo
	// =================================================================================

	/** La columna tal cual la tiene MySQL, sin pasar por el converter. */
	private String horaHastaCruda(long bloqueId) {
		return jdbc.queryForObject(
				"SELECT CAST(hora_hasta AS CHAR) FROM profesional_disponibilidad WHERE id = ?",
				String.class, bloqueId);
	}

	/**
	 * Relee el bloque desde la base por el mapeo real.
	 *
	 * <p>Cada consulta de Spring Data abre su propia transaccion en este test —no hay una sesion
	 * de persistencia abierta que pudiera devolver la instancia todavia en memoria—, asi que lo
	 * que vuelve paso de verdad por {@code ResultSet.getString} y por el converter.
	 */
	private BloqueDisponibilidad leerDeLaBase(Fixture fixture, long bloqueId) {
		return bloques.findByIdScoped(bloqueId, fixture.organizationId(), fixture.consultorioId())
				.orElseThrow(() -> new IllegalStateException(
						"El bloque " + bloqueId + " tendria que existir"));
	}

	private DiaEfectivo unicoDia(Fixture fixture, LocalDate fecha) {
		DisponibilidadEfectivaView vista = efectivaService.efectiva(
				fixture.admin(), fixture.consultorioId(), fixture.profesionalMembershipId(),
				fecha, fecha.plusDays(1));
		assertThat(vista.dias()).hasSize(1);
		return vista.dias().getFirst();
	}

	private long contar(String sql, Object... parametros) {
		Long total = jdbc.queryForObject(sql, Long.class, parametros);
		return total == null ? 0L : total;
	}

	// =================================================================================
	// Fixture — datos sinteticos, mismo patron que DisponibilidadMigrationIT
	// =================================================================================

	/**
	 * Un tenant sintetico completo: una sede, un {@code ORG_ADMIN} que opera y un
	 * {@code PROFESIONAL} cuyo horario se administra.
	 *
	 * <p>Hacen falta dos personas distintas y no una: la matriz seccion 6 le niega
	 * {@code consultorio:manage} a {@code PROFESIONAL}, o sea que un profesional NO edita su
	 * propia disponibilidad. Un fixture con una sola cuenta que hiciera las dos cosas escondria
	 * esa regla.
	 */
	private record Fixture(
			long organizationId,
			long consultorioId,
			long adminAccountId,
			long profesionalAccountId,
			long profesionalMembershipId) {

		/** El actor con contexto ya resuelto sobre esta sede, tal como lo arma la capa API. */
		OperatingActor admin() {
			return new OperatingActor(adminAccountId, false, organizationId, consultorioId);
		}
	}

	private Fixture crearFixture() {
		String sufijo = UUID.randomUUID().toString().substring(0, 8);

		long organizationId = insertarOrganization(sufijo);
		long consultorioId = insertarConsultorio(organizationId, sufijo);

		long adminAccountId = insertarCuenta("admin-" + sufijo);
		insertarMembership(organizationId, consultorioId, adminAccountId, "ORG_ADMIN");

		long profesionalAccountId = insertarCuenta("pro-" + sufijo);
		long profesionalMembershipId =
				insertarMembership(organizationId, consultorioId, profesionalAccountId, "PROFESIONAL");

		return new Fixture(organizationId, consultorioId, adminAccountId,
				profesionalAccountId, profesionalMembershipId);
	}

	private long insertarOrganization(String sufijo) {
		String slug = "disponibilidad-it-" + sufijo;
		jdbc.update("""
				INSERT INTO organization (name, slug, timezone, active, version,
				                          created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "Centro Sintetico " + sufijo, slug, ZONA);
		return jdbc.queryForObject("SELECT id FROM organization WHERE slug = ?", Long.class, slug);
	}

	private long insertarConsultorio(long organizationId, String sufijo) {
		String nombre = "Sede Sintetica " + sufijo;
		jdbc.update("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version,
				                         created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, nombre, ZONA);
		return jdbc.queryForObject(
				"SELECT id FROM consultorio WHERE organization_id = ? AND name = ?",
				Long.class, organizationId, nombre);
	}

	private long insertarCuenta(String sufijo) {
		String email = "disponibilidad-it-" + sufijo + "@ejemplo.test";
		jdbc.update("""
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado,
				                    active, version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', 'DePrueba', 'ACTIVA', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", email, email);
		return jdbc.queryForObject(
				"SELECT id FROM cuenta WHERE email_normalizado = ?", Long.class, email);
	}

	/**
	 * {@code valid_from} se siembra CINCO ANOS en el pasado, y los dos motivos importan.
	 *
	 * <p><b>Uno: el desfasaje de relojes.</b> El del contenedor de MySQL y el de la JVM del test no
	 * estan sincronizados al microsegundo, y con {@code UTC_TIMESTAMP(6)} exacto la vigencia puede
	 * quedar unos milisegundos en el FUTURO respecto del instante contra el que se la compara,
	 * produciendo 403 intermitentes. Es el mismo problema, y el mismo remedio, que en
	 * {@code BaseEscenarioDiferido#sembrarRolDePlataforma}. Para eso alcanzaba con un minuto.
	 *
	 * <p><b>Dos, y es el que obliga a los cinco anos: los escenarios de esta clase usan fechas
	 * FIJAS y este valor se corre solo con el reloj de pared.</b> Con un minuto en el pasado, el
	 * vinculo arranca "hoy", asi que toda fecha de escenario anterior a hoy cae ANTES del arranque
	 * del vinculo y {@code DisponibilidadEfectivaService} vacia el dia con
	 * {@code razonVacio = VINCULO}. El test se pone rojo el dia que el calendario pasa la fecha,
	 * <b>sin que haya nada roto</b>, y el sintoma apunta al calculador: quien lo herede va a buscar
	 * un bug que no existe. Ya paso una vez mientras se escribia la tarea 12, con el 9 de julio.
	 *
	 * <p>Mover la constante del escenario arreglaba UN caso y dejaba la bomba armada para el
	 * siguiente. La causa esta aca, en el fixture: un vinculo que arranco hace cinco anos cubre
	 * cualquier fecha que un escenario elija, hoy y dentro de tres anos, y sigue sirviendo igual de
	 * bien contra el desfasaje de relojes.
	 */
	private long insertarMembership(
			long organizationId, long consultorioId, long accountId, String rol) {

		jdbc.update("""
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, estado, active, version,
				                        created_at, updated_at)
				VALUES (?, ?, ?, ?, 0, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 YEAR), 'ACTIVA',
				        1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, consultorioId, accountId, rol);
		return jdbc.queryForObject("""
				SELECT id FROM membership
				 WHERE organization_id = ? AND consultorio_id = ? AND account_id = ?
				""", Long.class, organizationId, consultorioId, accountId);
	}

	/** Cierra la vigencia del vinculo en el pasado, que es lo que hace una desvinculacion. */
	private void desvincular(long membershipId) {
		int filas = jdbc.update("""
				UPDATE membership
				   SET valid_until = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 MINUTE),
				       estado = 'REVOCADA',
				       updated_at = UTC_TIMESTAMP(6)
				 WHERE id = ?
				""", membershipId);
		assertThat(filas).isEqualTo(1);
	}

	/**
	 * Un bloque ACTIVO cuya ventana operativa ya termino. Se siembra por SQL a proposito: el
	 * servicio no deja crear un bloque con vigencia pasada, y este test necesita exactamente la
	 * fila que el tiempo dejo atras y nadie dio de baja.
	 */
	private void insertarBloqueVencido(Fixture fixture, LocalDate desde, LocalDate hasta) {
		jdbc.update("""
				INSERT INTO profesional_disponibilidad
				    (organization_id, consultorio_id, membership_id, dia_semana, hora_desde,
				     hora_hasta, vigencia_desde, vigencia_hasta, active, version,
				     created_at, updated_at)
				VALUES (?, ?, ?, 3, '09:00:00', '13:00:00', ?, ?, 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", fixture.organizationId(), fixture.consultorioId(),
				fixture.profesionalMembershipId(), desde, hasta);
	}
}
