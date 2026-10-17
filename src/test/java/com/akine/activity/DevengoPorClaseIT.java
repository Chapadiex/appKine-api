package com.akine.activity;

import com.akine.TestcontainersConfiguration;
import com.akine.activity.ActividadItFixture.Tenant;
import com.akine.activity.application.AsistenciaService;
import com.akine.activity.application.ClaseService;
import com.akine.activity.application.DevengoDeClase;
import com.akine.activity.application.InscripcionService;
import com.akine.activity.application.ConsumoDeCredito;
import com.akine.activity.application.RegistrarAsistenciaCommand;
import com.akine.activity.application.ResultadoDeAsistencia;
import com.akine.activity.domain.OrigenAsistencia;
import com.akine.activity.domain.ResultadoAsistencia;
import com.akine.billing.application.CobroCommand;
import com.akine.billing.application.CobroPosteriorService;
import com.akine.billing.application.CobroService;
import com.akine.billing.application.CobroView;
import com.akine.billing.application.ObligacionService;
import com.akine.billing.application.ObligacionView;
import com.akine.billing.domain.MedioDePago;
import com.akine.offering.application.OfertaEdicionCommand;
import com.akine.offering.application.OfertaService;
import com.akine.offering.domain.EsquemaCobro;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * U-39 contra MySQL real: <b>en una oferta {@code POR_CLASE} la deuda nace con la asistencia</b>
 * (RF-M18-008), se paga como cualquier otra (RF-M19-009) y la asistencia no depende de ella
 * (CA-M19-009-06).
 *
 * <p>El circuito entero por los servicios reales —oferta, clase, asistencia, correccion, cobro,
 * cancelacion— y cada paso verificado contra lo que quedo en el motor, no contra lo que dijo el
 * servicio.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class DevengoPorClaseIT {

	private static final BigDecimal PRECIO = new BigDecimal("6000.00");
	private static final BigDecimal PRECIO_NUEVO = new BigDecimal("9000.00");
	private static final BigDecimal MITAD = new BigDecimal("3000.00");

	@Autowired private OfertaService ofertaService;
	@Autowired private InscripcionService inscripcionService;
	@Autowired private AsistenciaService asistenciaService;
	@Autowired private ClaseService claseService;
	@Autowired private CobroService cobroService;
	@Autowired private CobroPosteriorService posteriorService;
	@Autowired private ObligacionService obligacionService;
	@Autowired private JdbcTemplate jdbc;

	private ActividadItFixture fx;

	@BeforeEach
	void setUp() {
		fx = new ActividadItFixture(jdbc, inscripcionService);
	}

	@Test
	@DisplayName("POR_CLASE se declara; presente devenga con precio congelado; ausente anula sin borrar; de vuelta a presente devenga otra; el no-show no devenga")
	void circuito_de_la_deuda_por_clase() {
		Tenant tenant = fx.crearTenant(10, 2);
		declararPorClase(tenant, PRECIO);
		// Lo que antes era 400: la oferta queda POR_CLASE con su momento de devengo, y el CHECK de V64
		// (ck_oferta_politica_devengo) lo acepta.
		assertThat(jdbc.queryForMap("""
				SELECT esquema_cobro, momento_devengo, devenga_no_show FROM oferta_servicio_consultorio WHERE id = ?
				""", tenant.ofertaId()))
				.containsEntry("esquema_cobro", "POR_CLASE")
				.containsEntry("momento_devengo", "ASISTENCIA")
				.containsEntry("devenga_no_show", false);

		long clase = claseEnCurso(tenant, 0, 1);
		long inscripcion = fx.inscripcionDe(clase, tenant.persona(0));

		// Presente: nace la deuda, en la misma transaccion que la asistencia.
		ResultadoDeAsistencia alta = registrar(tenant, clase, inscripcion, ResultadoAsistencia.PRESENTE, null);
		assertThat(alta.devengo().efecto()).isEqualTo(DevengoDeClase.Efecto.DEVENGADA);
		assertThat(alta.consumo().efecto()).isEqualTo(ConsumoDeCredito.Efecto.NO_APLICA);
		long asistencia = alta.asistencia().id();
		long primera = alta.devengo().obligacionId();
		assertThat(jdbc.queryForMap("""
				SELECT origen, asistencia_id, clase_id, sesion_id, pase_id, responsable, concepto,
				       importe_original, saldo, estado, oferta_id, persona_id
				  FROM obligacion WHERE id = ?
				""", primera))
				.containsEntry("origen", "ASISTENCIA_CLASE")
				.containsEntry("asistencia_id", asistencia)
				.containsEntry("clase_id", clase)
				.containsEntry("sesion_id", null)
				.containsEntry("pase_id", null)
				.containsEntry("responsable", "PACIENTE")
				.containsEntry("concepto", "PARTICULAR")
				.containsEntry("estado", "PENDIENTE")
				.containsEntry("oferta_id", tenant.ofertaId())
				.containsEntry("persona_id", tenant.persona(0));
		assertThat(importeDe(primera)).isEqualByComparingTo(PRECIO);
		assertThat(contarAuditoria(tenant, "OBLIGACION_DEVENGADA", primera)).isEqualTo(1);

		// El importe se congela: subir el precio de la oferta no toca la deuda ya devengada.
		declararPorClase(tenant, PRECIO_NUEVO);
		assertThat(importeDe(primera)).isEqualByComparingTo(PRECIO);

		// Reintentar el mismo registro no duplica (CA-M18-008-06), y presente -> con demora tampoco.
		ResultadoDeAsistencia reintento = registrar(tenant, clase, inscripcion, ResultadoAsistencia.PRESENTE, null);
		assertThat(reintento.sinCambios()).isTrue();
		ResultadoDeAsistencia tarde = registrar(
				tenant, clase, inscripcion, ResultadoAsistencia.PRESENTE_TARDE, "Llego tarde");
		assertThat(tarde.devengo().efecto()).isEqualTo(DevengoDeClase.Efecto.SIN_CAMBIO);
		assertThat(deudasDe(asistencia)).isEqualTo(1);

		// Ausente: la deuda se ANULA con el motivo de la correccion, y la fila sigue ahi.
		ResultadoDeAsistencia ausente = registrar(
				tenant, clase, inscripcion, ResultadoAsistencia.AUSENTE, "Se marco por error");
		assertThat(ausente.devengo().efecto()).isEqualTo(DevengoDeClase.Efecto.ANULADA);
		assertThat(ausente.devengo().obligacionId()).isEqualTo(primera);
		assertThat(jdbc.queryForMap("SELECT estado, saldo, motivo_anulacion, deleted_at FROM obligacion WHERE id = ?",
				primera))
				.containsEntry("estado", "ANULADA")
				.containsEntry("motivo_anulacion", "Asistencia corregida a ausente: Se marco por error")
				.containsEntry("deleted_at", null);

		// De vuelta a presente: devenga OTRA, al precio vigente hoy. La anulada queda como historia.
		ResultadoDeAsistencia otraVez = registrar(
				tenant, clase, inscripcion, ResultadoAsistencia.PRESENTE, "Si vino, se confundio la lista");
		assertThat(otraVez.devengo().efecto()).isEqualTo(DevengoDeClase.Efecto.DEVENGADA);
		long segunda = otraVez.devengo().obligacionId();
		assertThat(segunda).isNotEqualTo(primera);
		assertThat(importeDe(segunda)).isEqualByComparingTo(PRECIO_NUEVO);
		assertThat(deudasDe(asistencia)).isEqualTo(2);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM obligacion WHERE asistencia_id = ? AND estado <> 'ANULADA'
				""", Integer.class, asistencia)).isEqualTo(1);

		// La cuenta corriente la muestra como lo que es (RF-M18-003), con la anulada al lado.
		List<ObligacionView> cuenta = obligacionService.deLaPersona(
				billing(tenant), tenant.consultorioId(), tenant.persona(0));
		assertThat(cuenta).extracting(ObligacionView::origen).containsOnly("ASISTENCIA_CLASE");
		assertThat(cuenta).extracting(ObligacionView::asistenciaId).containsOnly(asistencia);
		assertThat(cuenta).extracting(ObligacionView::estado).containsExactlyInAnyOrder("PENDIENTE", "ANULADA");

		// El no-show no devenga: ni marcandolo, ni con el cierre que ausenta a los pendientes.
		long otraInscripcion = fx.inscripcionDe(clase, tenant.persona(1));
		ResultadoDeAsistencia noShow = registrar(tenant, clase, otraInscripcion, ResultadoAsistencia.AUSENTE, null);
		assertThat(noShow.devengo().efecto()).isEqualTo(DevengoDeClase.Efecto.NO_APLICA);
		asistenciaService.cerrar(tenant.actor(), tenant.consultorioId(), clase);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM obligacion WHERE persona_id = ?",
				Integer.class, tenant.persona(1))).isZero();
	}

	@Test
	@DisplayName("Con cobro: la correccion a ausente corrige la asistencia y deja la deuda viva; reintegrado el cobro se anula a mano")
	void la_deuda_cobrada_no_se_anula_sola() {
		Tenant tenant = fx.crearTenant(10, 1);
		declararPorClase(tenant, PRECIO);
		abrirCaja(tenant);
		long clase = claseEnCurso(tenant, 0);
		long inscripcion = fx.inscripcionDe(clase, tenant.persona(0));

		ResultadoDeAsistencia alta = registrar(tenant, clase, inscripcion, ResultadoAsistencia.PRESENTE, null);
		long obligacion = alta.devengo().obligacionId();
		long asistencia = alta.asistencia().id();

		// RF-M19-009: la clase adeudada se cobra por POST /cobros, como cualquier deuda.
		CobroView cobro = cobrar(tenant, obligacion, MITAD);
		assertThat(estadoDe(obligacion)).isEqualTo("PARCIAL");

		// La asistencia se corrige igual (CA-M19-009-06); la deuda cobrada no se anula.
		ResultadoDeAsistencia ausente = registrar(
				tenant, clase, inscripcion, ResultadoAsistencia.AUSENTE, "No vino, cobramos de mas");
		assertThat(ausente.corregida()).isTrue();
		assertThat(ausente.devengo().efecto()).isEqualTo(DevengoDeClase.Efecto.CON_COBROS);
		assertThat(ausente.devengo().saldo()).isEqualByComparingTo(MITAD);
		assertThat(jdbc.queryForObject("SELECT resultado FROM asistencia_actividad WHERE id = ?",
				String.class, asistencia)).isEqualTo("AUSENTE");
		assertThat(estadoDe(obligacion)).isEqualTo("PARCIAL");
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM audit_event
				 WHERE organization_id = ? AND event_type = 'ASISTENCIA_DEUDA_CON_COBROS' AND entity_id = ?
				""", Integer.class, tenant.organizationId(), asistencia)).isEqualTo(1);

		// De vuelta a presente no devenga una segunda: la viva sigue siendo esa.
		ResultadoDeAsistencia presente = registrar(
				tenant, clase, inscripcion, ResultadoAsistencia.PRESENTE, "Si vino");
		assertThat(presente.devengo().efecto()).isEqualTo(DevengoDeClase.Efecto.YA_DEVENGADA);
		assertThat(presente.devengo().obligacionId()).isEqualTo(obligacion);
		assertThat(deudasDe(asistencia)).isEqualTo(1);

		// Y a ausente otra vez: se regulariza reintegrando el cobro (RF-M19-010) y anulando la deuda,
		// que desde U-39 si se anula desde la cuenta corriente (no es la deuda de una venta).
		registrar(tenant, clase, inscripcion, ResultadoAsistencia.AUSENTE, "No vino");
		posteriorService.anular(billing(tenant), tenant.consultorioId(), cobro.id(), "Cobrado por error");
		assertThat(estadoDe(obligacion)).isEqualTo("PENDIENTE");
		ObligacionView anulada = obligacionService.anular(billing(tenant), tenant.consultorioId(), obligacion,
				"La clase no se presto", jdbc.queryForObject(
						"SELECT version FROM obligacion WHERE id = ?", Long.class, obligacion));
		assertThat(anulada.estado()).isEqualTo("ANULADA");
		assertThat(jdbc.queryForObject("SELECT resultado FROM asistencia_actividad WHERE id = ?",
				String.class, asistencia)).isEqualTo("AUSENTE");
	}

	@Test
	@DisplayName("Cancelar la clase anula las deudas POR_CLASE de sus asistencias, salvo las cobradas (RF-M28-006 paso 7)")
	void la_cancelacion_anula_las_deudas() {
		Tenant tenant = fx.crearTenant(10, 2);
		declararPorClase(tenant, PRECIO);
		abrirCaja(tenant);
		long clase = claseEnCurso(tenant, 0, 1);
		long sinCobro = registrar(tenant, clase, fx.inscripcionDe(clase, tenant.persona(0)),
				ResultadoAsistencia.PRESENTE, null).devengo().obligacionId();
		long conCobro = registrar(tenant, clase, fx.inscripcionDe(clase, tenant.persona(1)),
				ResultadoAsistencia.PRESENTE, null).devengo().obligacionId();
		cobrar(tenant, conCobro, MITAD);

		claseService.cancelar(tenant.actor(), tenant.consultorioId(), clase, "Se corto la luz");

		assertThat(jdbc.queryForMap("SELECT estado, motivo_anulacion FROM obligacion WHERE id = ?", sinCobro))
				.containsEntry("estado", "ANULADA")
				.containsEntry("motivo_anulacion", "Clase cancelada: Se corto la luz");
		assertThat(estadoDe(conCobro)).isEqualTo("PARCIAL");
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM audit_event
				 WHERE organization_id = ? AND event_type = 'ASISTENCIA_DEUDA_CON_COBROS' AND entity_id = ?
				""", Integer.class, tenant.organizationId(), clase)).isEqualTo(1);

		// Idempotente: la segunda cancelacion no vuelve a anular ni a auditar.
		claseService.cancelar(tenant.actor(), tenant.consultorioId(), clase, "Se corto la luz");
		assertThat(contarAuditoria(tenant, "OBLIGACION_ANULADA", sinCobro)).isEqualTo(1);
	}

	@Test
	@DisplayName("Sin precio la asistencia se registra igual y se informa; POR_PACK sin pase sigue informando sin devengar (U-39 b)")
	void sin_precio_y_por_pack_sin_pase() {
		Tenant tenant = fx.crearTenant(10, 1);
		declararPorClase(tenant, null);
		long clase = claseEnCurso(tenant, 0);
		ResultadoDeAsistencia sinPrecio = registrar(tenant, clase, fx.inscripcionDe(clase, tenant.persona(0)),
				ResultadoAsistencia.PRESENTE, null);
		assertThat(sinPrecio.registrada()).isTrue();
		assertThat(sinPrecio.devengo().efecto()).isEqualTo(DevengoDeClase.Efecto.SIN_PRECIO);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM obligacion WHERE persona_id = ?",
				Integer.class, tenant.persona(0))).isZero();
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM audit_event
				 WHERE organization_id = ? AND event_type = 'ASISTENCIA_POR_CLASE_SIN_PRECIO' AND entity_id = ?
				""", Integer.class, tenant.organizationId(), sinPrecio.asistencia().id())).isEqualTo(1);

		Tenant porPack = fx.crearTenant(10, 1);
		PaseVentaYConsumoIT.declararPorPack(jdbc, porPack.ofertaId());
		long clasePack = claseEnCurso(porPack, 0);
		ResultadoDeAsistencia sinPase = registrar(porPack, clasePack, fx.inscripcionDe(clasePack, porPack.persona(0)),
				ResultadoAsistencia.PRESENTE, null);
		assertThat(sinPase.registrada()).isTrue();
		assertThat(sinPase.consumo().efecto()).isEqualTo(ConsumoDeCredito.Efecto.SIN_PASE_ELEGIBLE);
		assertThat(sinPase.devengo().efecto()).isEqualTo(DevengoDeClase.Efecto.NO_APLICA);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM obligacion WHERE persona_id = ?",
				Integer.class, porPack.persona(0))).isZero();
	}

	@Test
	@DisplayName("V107: el CHECK de origen conserva los tres de V64 y una asistencia no puede tener dos deudas vivas")
	void la_migracion_no_pierde_origenes_y_el_unique_cuida_la_viva() {
		String check = jdbc.queryForObject("""
				SELECT CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS
				 WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'ck_obligacion_origen'
				""", String.class);
		assertThat(check).contains("SESION", "VENTA_PASE", "VENTA_ABONO", "ASISTENCIA_CLASE");

		Tenant tenant = fx.crearTenant(10, 1);
		declararPorClase(tenant, PRECIO);
		long clase = claseEnCurso(tenant, 0);
		ResultadoDeAsistencia alta = registrar(tenant, clase, fx.inscripcionDe(clase, tenant.persona(0)),
				ResultadoAsistencia.PRESENTE, null);
		long asistencia = alta.asistencia().id();

		// Una segunda deuda viva para la misma asistencia: el motor la rechaza aunque alguien
		// saltee el servicio (uk_obligacion_asistencia_viva).
		assertThatThrownBy(() -> insertarDeudaDeClase(tenant, asistencia, clase))
				.isInstanceOf(DataIntegrityViolationException.class);
		// Anulada la viva, la base admite otra: es lo que deja re-devengar.
		jdbc.update("""
				UPDATE obligacion SET estado = 'ANULADA', saldo = 0, anulada_en = UTC_TIMESTAMP(6),
				       anulada_por_cuenta_id = ?, motivo_anulacion = 'IT' WHERE id = ?
				""", tenant.actor().accountId(), alta.devengo().obligacionId());
		insertarDeudaDeClase(tenant, asistencia, clase);
		assertThat(deudasDe(asistencia)).isEqualTo(2);
	}

	// =================================================================================

	/** Por el servicio real de offering: es el camino que respondia 400. {@code null}: sin precio. */
	private void declararPorClase(Tenant tenant, BigDecimal precio) {
		long version = jdbc.queryForObject("SELECT version FROM oferta_servicio_consultorio WHERE id = ?",
				Long.class, tenant.ofertaId());
		ofertaService.editar(
				new com.akine.offering.application.OperatingActor(
						tenant.actor().accountId(), false, tenant.organizationId(), tenant.consultorioId()),
				tenant.organizationId(), tenant.consultorioId(), tenant.ofertaId(),
				new OfertaEdicionCommand(null, null, null, null, null, precio, precio == null ? null : "ARS",
						false, new EsquemaCobro(EsquemaCobro.POR_CLASE), false, null, null, null, null, null,
						null, null, false, version));
	}

	/** Una clase en curso con esas personas inscriptas. */
	private long claseEnCurso(Tenant tenant, int... personas) {
		long clase = fx.programarClase(tenant, 10, 1);
		for (int persona : personas) {
			fx.inscribir(tenant, clase, tenant.persona(persona), false);
		}
		asistenciaService.iniciar(tenant.actor(), tenant.consultorioId(), clase);
		return clase;
	}

	private ResultadoDeAsistencia registrar(
			Tenant tenant, long clase, long inscripcion, ResultadoAsistencia resultado, String motivo) {
		return asistenciaService.registrar(tenant.actor(), tenant.consultorioId(), clase,
				new RegistrarAsistenciaCommand(inscripcion, resultado, OrigenAsistencia.MOSTRADOR, null, motivo));
	}

	private static com.akine.billing.application.OperatingActor billing(Tenant tenant) {
		return new com.akine.billing.application.OperatingActor(
				tenant.actor().accountId(), false, tenant.organizationId(), tenant.consultorioId());
	}

	private CobroView cobrar(Tenant tenant, long obligacionId, BigDecimal importe) {
		long persona = jdbc.queryForObject("SELECT persona_id FROM obligacion WHERE id = ?", Long.class, obligacionId);
		return cobroService.registrar(billing(tenant), tenant.consultorioId(), new CobroCommand(
				persona, importe,
				List.of(new CobroCommand.MedioPedido(MedioDePago.EFECTIVO, importe, null)),
				List.of(new CobroCommand.ImputacionPedida(obligacionId, importe)),
				null));
	}

	private void abrirCaja(Tenant tenant) {
		jdbc.update("""
				INSERT INTO jornada_caja (organization_id, consultorio_id, fecha_negocio, moneda,
				                          estado, saldo_inicial, saldo_arqueo, abierta_en,
				                          abierta_por_cuenta_id, created_at, updated_at)
				VALUES (?, ?, ?, 'ARS', 'ABIERTA', 0.00, 0.00, UTC_TIMESTAMP(6), ?,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", tenant.organizationId(), tenant.consultorioId(),
				LocalDate.now(ZoneId.of(ActividadItFixture.ZONA)).toString(), tenant.actor().accountId());
	}

	private void insertarDeudaDeClase(Tenant tenant, long asistencia, long clase) {
		jdbc.update("""
				INSERT INTO obligacion (organization_id, consultorio_id, origen, asistencia_id, clase_id,
				                        persona_id, responsable, concepto, importe_original, saldo, moneda,
				                        estado, oferta_id, snapshot_nombre, snapshot_precio, devengada_en)
				VALUES (?, ?, 'ASISTENCIA_CLASE', ?, ?, ?, 'PACIENTE', 'PARTICULAR', 6000.00, 6000.00, 'ARS',
				        'PENDIENTE', ?, 'IT', 6000.00, ?)
				""", tenant.organizationId(), tenant.consultorioId(), asistencia, clase, tenant.persona(0),
				tenant.ofertaId(), Timestamp.from(java.time.Instant.now()));
	}

	private BigDecimal importeDe(long obligacionId) {
		return jdbc.queryForObject("SELECT importe_original FROM obligacion WHERE id = ?", BigDecimal.class,
				obligacionId);
	}

	private String estadoDe(long obligacionId) {
		return jdbc.queryForObject("SELECT estado FROM obligacion WHERE id = ?", String.class, obligacionId);
	}

	private int deudasDe(long asistencia) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM obligacion WHERE asistencia_id = ?", Integer.class,
				asistencia);
	}

	private int contarAuditoria(Tenant tenant, String evento, long entidad) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM audit_event WHERE organization_id = ? AND event_type = ? AND entity_id = ?
				""", Integer.class, tenant.organizationId(), evento, entidad);
	}
}
