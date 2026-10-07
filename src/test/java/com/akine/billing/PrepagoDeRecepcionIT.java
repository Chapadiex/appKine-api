package com.akine.billing;

import com.akine.TestcontainersConfiguration;
import com.akine.billing.application.CobroCommand;
import com.akine.billing.application.CobroPosteriorService;
import com.akine.billing.application.CobroService;
import com.akine.billing.application.CobroView;
import com.akine.billing.application.OperatingActor;
import com.akine.billing.application.ReintegroCommand;
import com.akine.billing.domain.MedioDePago;
import com.akine.billing.domain.exception.PrepagoNoAdmitidoException;
import com.akine.billing.domain.exception.PrepagoYaRegistradoException;
import com.akine.billing.domain.exception.TurnoNoAccesibleException;
import com.akine.encounter.application.SesionService;
import com.akine.encounter.domain.Asistencia;
import com.akine.encounter.domain.CierreDeSesion;
import com.akine.offering.application.OfertaService;
import com.akine.offering.application.OfertaView;
import com.akine.scheduling.application.CicloDeRecepcionService;
import com.akine.scheduling.application.PrepagoView;
import com.akine.scheduling.application.RecepcionView;
import com.akine.scheduling.spi.PrepagoDeTurnoProbe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * AKINE E-6 contra MySQL real: el prepago de recepcion es un anticipo (F-3) atado al turno que, al
 * cerrar la sesion, se imputa a la deuda del paciente que devengo el cierre (F-4).
 *
 * <p>Todo entra por los servicios reales: la politica por {@code OfertaService}, la recepcion por
 * {@code CicloDeRecepcionService}, el cobro por {@code CobroService} y el cierre por
 * {@code SesionService#cerrar}. Es lo unico que prueba el cableado entero: que la recepcion vea
 * el anticipo de billing por el {@code spi} invertido, que el cierre lleve el turno, y que la
 * imputacion corra <b>despues</b> del commit del cierre, en su propia transaccion, contra la deuda
 * ya commiteada.
 *
 * <p>Cada caso arma su propio tenant.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class PrepagoDeRecepcionIT {

	private static final String ZONA = "America/Argentina/Cordoba";
	private static final BigDecimal PRECIO = new BigDecimal("8500.00");

	@Autowired private OfertaService ofertaService;
	@Autowired private CicloDeRecepcionService recepcion;
	@Autowired private CobroService cobroService;
	@Autowired private CobroPosteriorService posteriorService;
	@Autowired private SesionService sesionService;
	@Autowired private PrepagoDeTurnoProbe prepagos;
	@Autowired private JdbcTemplate jdbc;

	@Test
	@DisplayName("V81 ejecuto: exige_prepago en la oferta, turno_id en cobro y el unique del prepago vigente")
	void la_migracion_ejecuto() {
		assertThat(columna("oferta_servicio_consultorio", "exige_prepago")).isEqualTo(1);
		assertThat(columna("cobro", "turno_id")).isEqualTo(1);
		assertThat(columna("cobro", "turno_prepago_vigente")).isEqualTo(1);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
				 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cobro'
				   AND CONSTRAINT_NAME = 'uk_cobro_prepago_turno_vigente'
				""", Integer.class)).isEqualTo(1);
	}

	// =================================================================================
	// De punta a punta
	// =================================================================================

	@Test
	@DisplayName("oferta con prepago: PENDIENTE en la recepcion, anticipo en efectivo por caja, y al cerrar se imputa entero")
	void de_punta_a_punta_en_efectivo() {
		Fixture f = crearFixture(false);

		// La politica se configura por la oferta (M27), con su propio recurso.
		long version = jdbc.queryForObject(
				"SELECT version FROM oferta_servicio_consultorio WHERE id = ?", Long.class, f.ofertaId());
		OfertaView oferta = ofertaService.cambiarPoliticaDePrepago(
				f.ofertaActor(), f.organizationId(), f.consultorioId(), f.ofertaId(), true, version);
		assertThat(oferta.exigePrepago()).isTrue();

		long turno = insertarTurno(f);
		RecepcionView llegada = recepcion.registrarLlegada(f.agendaActor(), f.consultorioId(), turno).recepcion();
		RecepcionView particular = recepcion.atenderComoParticular(
				f.agendaActor(), f.consultorioId(), turno, "Sin cobertura", llegada.version());
		assertThat(particular.prepago().estado()).isEqualTo(PrepagoView.PENDIENTE);
		assertThat(particular.prepago().importeSugerido()).isEqualByComparingTo(PRECIO);

		CobroView prepago = prepagar(f, turno, MedioDePago.EFECTIVO, PRECIO, "pre-" + UUID.randomUUID());
		assertThat(prepago.turnoId()).isEqualTo(turno);
		assertThat(prepago.saldoAFavor()).isEqualByComparingTo(PRECIO);
		assertThat(saldoDeCaja(f)).as("el efectivo entro al cajon una vez").isEqualByComparingTo(PRECIO);

		RecepcionView vista = recepcion.ver(f.agendaActor(), f.consultorioId(), turno);
		assertThat(vista.prepago().estado()).isEqualTo(PrepagoView.REGISTRADO);
		assertThat(vista.prepago().cobroId()).isEqualTo(prepago.id());

		RecepcionView enEspera = recepcion.pasarAEspera(
				f.agendaActor(), f.consultorioId(), turno, particular.version());
		assertThat(motivoDeLaEspera(turno)).as("con el prepago hecho, la espera no lleva alerta").isNull();
		assertThat(enEspera.estado()).isEqualTo("EN_ESPERA");

		long sesion = insertarSesion(f, turno);
		cerrar(f, sesion, Asistencia.PRESENTE);

		Map<String, Object> deuda = deudaDelPaciente(sesion);
		assertThat(deuda.get("concepto")).isEqualTo("PARTICULAR");
		assertThat(deuda.get("estado")).isEqualTo("PAGADA");
		assertThat((BigDecimal) deuda.get("saldo")).isEqualByComparingTo("0");
		assertThat(saldoAFavor(prepago.id())).isEqualByComparingTo("0");
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM cobro_imputacion WHERE cobro_id = ? AND obligacion_id = ?",
				Integer.class, prepago.id(), ((Number) deuda.get("id")).longValue())).isEqualTo(1);
		assertThat(saldoDeCaja(f)).as("imputar no vuelve a mover caja").isEqualByComparingTo(PRECIO);
		assertThat(movimientosDeCaja(f)).isEqualTo(1);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM audit_event
				 WHERE organization_id = ? AND event_type = 'COBRO_SALDO_IMPUTADO'
				""", Integer.class, f.organizationId())).isEqualTo(1);
	}

	@Test
	@DisplayName("prepago que sobra, por transferencia: no mueve el arqueo y lo que sobra queda a favor")
	void anticipo_que_sobra_por_transferencia() {
		Fixture f = crearFixture(true);
		long turno = insertarTurno(f);

		CobroView prepago = prepagar(f, turno, MedioDePago.TRANSFERENCIA, new BigDecimal("10000.00"), null);
		assertThat(saldoDeCaja(f)).as("solo el efectivo mueve el arqueo").isEqualByComparingTo("0");

		long sesion = insertarSesion(f, turno);
		cerrar(f, sesion, Asistencia.PRESENTE);

		assertThat(deudaDelPaciente(sesion).get("estado")).isEqualTo("PAGADA");
		assertThat(saldoAFavor(prepago.id())).as("lo que sobra queda a favor").isEqualByComparingTo("1500.00");
		assertThat(prepagos.prepagosDe(f.organizationId(), List.of(turno)).get(turno).saldoAFavor())
				.isEqualByComparingTo("1500.00");
	}

	@Test
	@DisplayName("prepago que no alcanza: se imputa entero y la deuda queda PARCIAL por la diferencia")
	void anticipo_que_no_alcanza() {
		Fixture f = crearFixture(true);
		long turno = insertarTurno(f);
		CobroView prepago = prepagar(f, turno, MedioDePago.EFECTIVO, new BigDecimal("5000.00"), null);

		long sesion = insertarSesion(f, turno);
		cerrar(f, sesion, Asistencia.PRESENTE);

		Map<String, Object> deuda = deudaDelPaciente(sesion);
		assertThat(deuda.get("estado")).isEqualTo("PARCIAL");
		assertThat((BigDecimal) deuda.get("saldo")).isEqualByComparingTo("3500.00");
		assertThat(saldoAFavor(prepago.id())).isEqualByComparingTo("0");
	}

	@Test
	@DisplayName("sesion con ausencia: no hay deuda y el anticipo queda disponible; se reintegra por caja")
	void ausencia_deja_el_anticipo_para_reintegro() {
		Fixture f = crearFixture(true);
		long turno = insertarTurno(f);
		CobroView prepago = prepagar(f, turno, MedioDePago.EFECTIVO, PRECIO, null);

		long sesion = insertarSesion(f, turno);
		cerrar(f, sesion, Asistencia.AUSENTE);

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM obligacion WHERE sesion_id = ?",
				Integer.class, sesion)).as("un no-show no se cobra solo").isZero();
		assertThat(saldoAFavor(prepago.id())).isEqualByComparingTo(PRECIO);

		posteriorService.reintegrar(f.billingActor(), f.consultorioId(), prepago.id(),
				new ReintegroCommand(PRECIO, MedioDePago.EFECTIVO, null, "El paciente no vino", null));
		assertThat(saldoAFavor(prepago.id())).isEqualByComparingTo("0");
		assertThat(saldoDeCaja(f)).isEqualByComparingTo("0");
	}

	@Test
	@DisplayName("sin prepago: pasa a espera igual con la alerta escrita en el evento, y el cierre devenga sin imputar")
	void prepago_pendiente_alerta_y_no_bloquea() {
		Fixture f = crearFixture(true);
		long turno = insertarTurno(f);
		RecepcionView llegada = recepcion.registrarLlegada(f.agendaActor(), f.consultorioId(), turno).recepcion();
		RecepcionView particular = recepcion.atenderComoParticular(
				f.agendaActor(), f.consultorioId(), turno, "Sin cobertura", llegada.version());

		RecepcionView enEspera = recepcion.pasarAEspera(
				f.agendaActor(), f.consultorioId(), turno, particular.version());

		assertThat(enEspera.estado()).isEqualTo("EN_ESPERA");
		assertThat(enEspera.prepago().estado()).isEqualTo(PrepagoView.PENDIENTE);
		assertThat(motivoDeLaEspera(turno)).startsWith("PREPAGO_PENDIENTE");

		long sesion = insertarSesion(f, turno);
		cerrar(f, sesion, Asistencia.PRESENTE);
		assertThat(deudaDelPaciente(sesion).get("estado")).as("cerrar no cobra").isEqualTo("PENDIENTE");
	}

	@Test
	@DisplayName("anular el prepago antes del cierre: la recepcion vuelve a PENDIENTE y el cierre no imputa nada")
	void prepago_anulado_antes_del_cierre() {
		Fixture f = crearFixture(true);
		long turno = insertarTurno(f);
		recepcion.registrarLlegada(f.agendaActor(), f.consultorioId(), turno);
		CobroView prepago = prepagar(f, turno, MedioDePago.EFECTIVO, PRECIO, null);

		posteriorService.anular(f.billingActor(), f.consultorioId(), prepago.id(), "Cobrado de mas");
		assertThat(recepcion.ver(f.agendaActor(), f.consultorioId(), turno).prepago().estado())
				.isEqualTo(PrepagoView.PENDIENTE);

		long sesion = insertarSesion(f, turno);
		cerrar(f, sesion, Asistencia.PRESENTE);
		assertThat(deudaDelPaciente(sesion).get("estado")).isEqualTo("PENDIENTE");

		// Anular libera el turno: el unique es del prepago VIGENTE.
		CobroView otro = prepagar(f, turno, MedioDePago.EFECTIVO, PRECIO, null);
		assertThat(otro.turnoId()).isEqualTo(turno);
	}

	// =================================================================================
	// Idempotencia, unicidad y tenant
	// =================================================================================

	@Test
	@DisplayName("la misma clave devuelve el mismo prepago; otra clave sobre el mismo turno es prepago-ya-registrado")
	void idempotencia_y_un_solo_prepago_por_turno() {
		Fixture f = crearFixture(true);
		long turno = insertarTurno(f);
		String clave = "pre-" + UUID.randomUUID();

		CobroView primero = prepagar(f, turno, MedioDePago.EFECTIVO, PRECIO, clave);
		CobroView reintento = prepagar(f, turno, MedioDePago.EFECTIVO, PRECIO, clave);
		assertThat(reintento.id()).isEqualTo(primero.id());

		assertThatThrownBy(() -> prepagar(f, turno, MedioDePago.EFECTIVO, PRECIO, "otra-" + UUID.randomUUID()))
				.isInstanceOf(PrepagoYaRegistradoException.class);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cobro WHERE turno_id = ?", Integer.class, turno))
				.isEqualTo(1);
		assertThat(saldoDeCaja(f)).as("el cajon recibio una sola vez").isEqualByComparingTo(PRECIO);
	}

	@Test
	@DisplayName("dos operadores cobran el mismo prepago a la vez: entra uno y el otro recibe prepago-ya-registrado")
	void dos_prepagos_a_la_vez() {
		Fixture f = crearFixture(true);
		long turno = insertarTurno(f);

		List<Throwable> errores = enParalelo(List.of(
				() -> prepagar(f, turno, MedioDePago.TRANSFERENCIA, PRECIO, "a-" + UUID.randomUUID()),
				() -> prepagar(f, turno, MedioDePago.TRANSFERENCIA, PRECIO, "b-" + UUID.randomUUID())));

		assertThat(errores.stream().filter(e -> e == null).count()).as("%s", errores).isEqualTo(1);
		assertThat(errores.stream().filter(e -> e instanceof PrepagoYaRegistradoException).count())
				.as("%s", errores).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cobro WHERE turno_id = ?", Integer.class, turno))
				.isEqualTo(1);
	}

	@Test
	@DisplayName("el turno de otra persona, uno cancelado o el de otro tenant no admiten el prepago")
	void reglas_del_turno_y_tenant() {
		Fixture f = crearFixture(true);
		long turno = insertarTurno(f);

		long otraPersona = insertarPersona(f.organizationId(), f.adminCuentaId(), "Otra" + UUID.randomUUID().toString().substring(0, 6));
		assertThatThrownBy(() -> cobroService.registrar(f.billingActor(), f.consultorioId(),
				comando(otraPersona, turno, MedioDePago.EFECTIVO, PRECIO, null)))
				.isInstanceOf(PrepagoNoAdmitidoException.class);

		long cancelado = insertarTurno(f);
		jdbc.update("UPDATE turno SET estado = 'CANCELADO', deleted_at = UTC_TIMESTAMP(6) WHERE id = ?", cancelado);
		assertThatThrownBy(() -> prepagar(f, cancelado, MedioDePago.EFECTIVO, PRECIO, null))
				.isInstanceOf(PrepagoNoAdmitidoException.class);

		// Tenant B, con su propia sede y su persona, apuntando al turno de A: 404, nunca 403.
		Fixture b = crearFixture(true);
		assertThatThrownBy(() -> cobroService.registrar(b.billingActor(), b.consultorioId(),
				comando(b.personaId(), turno, MedioDePago.EFECTIVO, PRECIO, null)))
				.isInstanceOf(TurnoNoAccesibleException.class);

		// Y lo que A cobro no se ve desde B.
		prepagar(f, turno, MedioDePago.EFECTIVO, PRECIO, null);
		assertThat(prepagos.prepagosDe(b.organizationId(), List.of(turno))).isEmpty();
		assertThat(prepagos.prepagosDe(f.organizationId(), List.of(turno))).containsKey(turno);
	}

	// =================================================================================
	// Operaciones
	// =================================================================================

	private CobroView prepagar(Fixture f, long turno, MedioDePago medio, BigDecimal importe, String clave) {
		return cobroService.registrar(f.billingActor(), f.consultorioId(),
				comando(f.personaId(), turno, medio, importe, clave));
	}

	private static CobroCommand comando(long persona, long turno, MedioDePago medio, BigDecimal importe, String clave) {
		return new CobroCommand(persona, importe,
				List.of(new CobroCommand.MedioPedido(medio, importe, null)),
				List.of(), clave, importe, "ARS", turno);
	}

	private void cerrar(Fixture f, long sesionId, Asistencia asistencia) {
		long version = sesionService.ver(f.profesional(), f.consultorioId(), sesionId).version();
		sesionService.cerrar(f.profesional(), f.consultorioId(), sesionId,
				new CierreDeSesion(asistencia, asistencia == Asistencia.PRESENTE ? "Terapia manual" : null,
						null, null, null, null),
				version);
	}

	private Map<String, Object> deudaDelPaciente(long sesionId) {
		return jdbc.queryForMap(
				"SELECT * FROM obligacion WHERE sesion_id = ? AND responsable = 'PACIENTE'", sesionId);
	}

	private String motivoDeLaEspera(long turno) {
		return jdbc.queryForObject(
				"SELECT motivo FROM recepcion_evento WHERE turno_id = ? AND tipo = 'ESPERA'",
				String.class, turno);
	}

	private BigDecimal saldoAFavor(long cobroId) {
		return jdbc.queryForObject("SELECT saldo_a_favor FROM cobro WHERE id = ?", BigDecimal.class, cobroId);
	}

	private BigDecimal saldoDeCaja(Fixture f) {
		return jdbc.queryForObject("""
				SELECT saldo_arqueo FROM jornada_caja
				 WHERE organization_id = ? AND consultorio_id = ? AND estado = 'ABIERTA'
				""", BigDecimal.class, f.organizationId(), f.consultorioId());
	}

	private int movimientosDeCaja(Fixture f) {
		return jdbc.queryForObject(
				"SELECT COUNT(*) FROM movimiento_caja WHERE organization_id = ?",
				Integer.class, f.organizationId());
	}

	private int columna(String tabla, String columna) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM information_schema.COLUMNS
				 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?
				""", Integer.class, tabla, columna);
	}

	/** Devuelve el error de cada tarea, o {@code null} si termino bien. */
	private static List<Throwable> enParalelo(List<Callable<Object>> tareas) {
		CyclicBarrier salida = new CyclicBarrier(tareas.size());
		try (ExecutorService pool = Executors.newFixedThreadPool(tareas.size())) {
			List<Future<Throwable>> futuros = new ArrayList<>();
			for (Callable<Object> tarea : tareas) {
				futuros.add(pool.submit(() -> {
					salida.await(10, TimeUnit.SECONDS);
					try {
						tarea.call();
						return null;
					} catch (Exception error) {
						return error;
					}
				}));
			}
			List<Throwable> errores = new ArrayList<>();
			for (Future<Throwable> futuro : futuros) {
				errores.add(futuro.get(30, TimeUnit.SECONDS));
			}
			return errores;
		} catch (Exception fallo) {
			throw new IllegalStateException("La ejecucion concurrente no pudo completarse", fallo);
		}
	}

	// =================================================================================
	// Fixture — todo sintetico
	// =================================================================================

	private record Fixture(
			long organizationId,
			long consultorioId,
			long adminCuentaId,
			long profesionalCuentaId,
			long profesionalMembershipId,
			long personaId,
			long historiaClinicaId,
			long ofertaId) {

		OperatingActor billingActor() {
			return new OperatingActor(adminCuentaId, false, organizationId, consultorioId);
		}

		com.akine.scheduling.application.OperatingActor agendaActor() {
			return new com.akine.scheduling.application.OperatingActor(
					adminCuentaId, false, organizationId, consultorioId);
		}

		com.akine.offering.application.OperatingActor ofertaActor() {
			return new com.akine.offering.application.OperatingActor(
					adminCuentaId, false, organizationId, consultorioId);
		}

		com.akine.encounter.application.OperatingActor profesional() {
			return new com.akine.encounter.application.OperatingActor(
					profesionalCuentaId, false, organizationId, consultorioId);
		}
	}

	private Fixture crearFixture(boolean exigePrepago) {
		String s = UUID.randomUUID().toString().substring(0, 10);

		long org = insertar("""
				INSERT INTO organization (name, slug, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "Centro " + s, "e6-it-" + s, ZONA);
		long sede = insertar("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, "Sede " + s, ZONA);

		long admin = cuenta("e6-adm-" + s);
		membership(org, sede, admin, "ORG_ADMIN");
		long profesional = cuenta("e6-pro-" + s);
		long membership = membership(org, sede, profesional, "PROFESIONAL");

		long persona = insertarPersona(org, admin, "Paciente" + s);
		long historia = insertar("""
				INSERT INTO historia_clinica (organization_id, persona_id, abierta_en, abierta_por,
				                              active, version, created_at, updated_at)
				VALUES (?, ?, UTC_TIMESTAMP(6), ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, persona, profesional);

		long servicio = insertar("""
				INSERT INTO servicio (codigo, nombre, naturaleza, modalidad_default,
				                      requiere_caso_clinico_default, genera_registro_clinico_default,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, 'CLINICO', 'INDIVIDUAL', 0, 0, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "E6-" + s.toUpperCase(), "Servicio " + s);
		long oferta = insertar("""
				INSERT INTO oferta_servicio_consultorio
				       (organization_id, consultorio_id, servicio_id, nombre_comercial, modalidad,
				        duracion_minutos, capacidad, precio_base, moneda, admite_obra_social,
				        exige_prepago, requiere_caso_clinico, genera_registro_clinico,
				        requiere_profesional, requiere_espacio, vigencia_desde, active, version,
				        created_at, updated_at)
				VALUES (?, ?, ?, ?, 'INDIVIDUAL', 60, 1, 8500.00, 'ARS', 0, ?, 0, 0, 1, 0,
				        DATE_SUB(CURDATE(), INTERVAL 5 YEAR), 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, sede, servicio, "Oferta " + s, exigePrepago ? 1 : 0);

		// Caja abierta en cero: todo el efectivo que la prueba ve en el cajon lo puso la prueba.
		jdbc.update("""
				INSERT INTO jornada_caja (organization_id, consultorio_id, fecha_negocio, moneda,
				                          estado, saldo_inicial, saldo_arqueo, abierta_en,
				                          abierta_por_cuenta_id, created_at, updated_at)
				VALUES (?, ?, ?, 'ARS', 'ABIERTA', 0.00, 0.00, UTC_TIMESTAMP(6), ?,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, sede, LocalDate.now(ZoneId.of(ZONA)).toString(), admin);

		return new Fixture(org, sede, admin, profesional, membership, persona, historia, oferta);
	}

	private long insertarTurno(Fixture f) {
		return insertar("""
				INSERT INTO turno (organization_id, consultorio_id, oferta_id, persona_id,
				                   profesional_membership_id, inicio, fin, estado,
				                   reservado_por_cuenta_id, reservado_en, version,
				                   created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 HOUR),
				        DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 2 HOUR), 'RESERVADO', ?,
				        UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", f.organizationId(), f.consultorioId(), f.ofertaId(), f.personaId(),
				f.profesionalMembershipId(), f.adminCuentaId());
	}

	private long insertarSesion(Fixture f, long turno) {
		return insertar("""
				INSERT INTO sesion (organization_id, consultorio_id, historia_clinica_id, oferta_id,
				                    turno_id, profesional_membership_id, estado, iniciada_en,
				                    iniciada_por_cuenta_id, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, 'BORRADOR', UTC_TIMESTAMP(6), ?, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", f.organizationId(), f.consultorioId(), f.historiaClinicaId(), f.ofertaId(), turno,
				f.profesionalMembershipId(), f.profesionalCuentaId());
	}

	private long insertarPersona(long org, long activadoPor, String apellido) {
		long persona = insertar("""
				INSERT INTO persona (organization_id, apellido, nombre, apellido_clave, nombre_clave,
				                     active, version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', ?, 'SINTETICO', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, apellido, apellido.toUpperCase());
		jdbc.update("""
				INSERT INTO perfil_paciente (organization_id, persona_id, activado_en, activado_por,
				                             active, version, created_at, updated_at)
				VALUES (?, ?, UTC_TIMESTAMP(6), ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, persona, activadoPor);
		return persona;
	}

	private long cuenta(String prefijo) {
		String email = prefijo + "@ejemplo.test";
		return insertar("""
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado, active,
				                    version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', 'DePrueba', 'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", email, email);
	}

	private long membership(long org, long sede, long cuenta, String rol) {
		return insertar("""
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, estado, active, version,
				                        created_at, updated_at)
				VALUES (?, ?, ?, ?, 0, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 YEAR),
				        'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, sede, cuenta, rol);
	}

	/**
	 * Inserta y devuelve el id, en la MISMA conexion: {@code LAST_INSERT_ID()} es por conexion y
	 * el pool podria darle otra a la consulta siguiente.
	 */
	private long insertar(String insert, Object... args) {
		return jdbc.execute((java.sql.Connection conexion) -> {
			try (var sentencia = conexion.prepareStatement(insert, java.sql.Statement.RETURN_GENERATED_KEYS)) {
				for (int i = 0; i < args.length; i++) {
					sentencia.setObject(i + 1, args[i]);
				}
				sentencia.executeUpdate();
				try (var claves = sentencia.getGeneratedKeys()) {
					claves.next();
					return claves.getLong(1);
				}
			}
		});
	}
}
