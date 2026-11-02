package com.akine.billing;

import com.akine.TestcontainersConfiguration;
import com.akine.billing.EgresoItFixture.Tenant;
import com.akine.billing.application.CajaService;
import com.akine.billing.application.EgresoService;
import com.akine.billing.application.EgresoView;
import com.akine.billing.application.JornadaCajaView;
import com.akine.billing.application.MovimientoCajaService;
import com.akine.billing.application.MovimientoCajaView;
import com.akine.billing.application.MovimientoManualCommand;
import com.akine.billing.application.OperatingActor;
import com.akine.billing.application.PagoEgresoService;
import com.akine.billing.application.ResumenDeEgresosView;
import com.akine.billing.domain.MedioDePago;
import com.akine.billing.domain.TipoMovimiento;
import com.akine.billing.domain.exception.ConsultorioNoAccesibleException;
import com.akine.organization.domain.exception.PermissionDeniedException;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DP-45, punto 4: las tiras de la caja diaria y de egresos, contra MySQL real.
 *
 * <p>Lo que se mide es que las sumas nativas por tipo y por medio den lo que el ledger dice
 * —con las reversiones restando de lo que revierten— y que el resumen de egresos respete el mismo
 * recorte por fecha, permiso y tenant que la bandeja. La aritmetica de armado la cubre
 * {@code ResumenesDP45Test}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class IndicadoresDeCajaYEgresosIT {

	@Autowired private JdbcTemplate jdbc;
	@Autowired private CajaService cajaService;
	@Autowired private MovimientoCajaService movimientoService;
	@Autowired private EgresoService egresoService;
	@Autowired private PagoEgresoService pagoService;

	private EgresoItFixture fixture;

	@BeforeEach
	void preparar() {
		fixture = new EgresoItFixture(jdbc, cajaService, egresoService, pagoService);
	}

	@Test
	@DisplayName("Caja: ingresos y egresos por efectivo y otros medios, netos de reversiones, y el efectivo cuadra con el teorico")
	void resumen_de_la_jornada() {
		Tenant tenant = fixture.crearTenant();
		JornadaCajaView jornada = fixture.abrirCaja(tenant, "10000.00");

		mover(tenant, TipoMovimiento.INGRESO, MedioDePago.EFECTIVO, "5000.00");
		MovimientoCajaView errado = mover(tenant, TipoMovimiento.INGRESO, MedioDePago.EFECTIVO, "700.00");
		movimientoService.revertir(tenant.actor(), tenant.consultorioId(), errado.id(), "Cargado dos veces");
		mover(tenant, TipoMovimiento.INGRESO, MedioDePago.TRANSFERENCIA, "3000.00");
		mover(tenant, TipoMovimiento.EGRESO, MedioDePago.EFECTIVO, "1200.00");

		JornadaCajaView vista = cajaService.ver(tenant.actor(), tenant.consultorioId(), jornada.id());
		JornadaCajaView.Resumen resumen = vista.resumen();

		assertThat(resumen.efectivoIngresos())
				.as("el ingreso revertido no suma, y tampoco aparece como egreso")
				.isEqualByComparingTo("5000.00");
		assertThat(resumen.efectivoEgresos()).isEqualByComparingTo("1200.00");
		assertThat(resumen.otrosMediosIngresos()).isEqualByComparingTo("3000.00");
		assertThat(resumen.otrosMediosEgresos()).isEqualByComparingTo("0");
		assertThat(resumen.ingresos()).isEqualByComparingTo("8000.00");
		assertThat(resumen.egresos()).isEqualByComparingTo("1200.00");
		assertThat(resumen.cantidadMovimientos()).isEqualTo(5);
		assertThat(vista.saldoInicial().add(resumen.efectivoIngresos()).subtract(resumen.efectivoEgresos()))
				.as("saldoInicial + efectivo que entro - efectivo que salio = saldo teorico")
				.isEqualByComparingTo(vista.saldoTeorico())
				.isEqualByComparingTo(fixture.saldoArqueo(jornada.id()));

		assertThat(cajaService.historico(tenant.actor(), tenant.consultorioId(), null, null, null, 50, 0))
				.as("los listados no calculan el resumen: seria un N+1")
				.allSatisfy(j -> assertThat(j.resumen()).isNull());
	}

	@Test
	@DisplayName("Egresos: mes en curso por defecto; registrado, pagado y pendiente; borradores aparte")
	void resumen_de_egresos_del_mes() {
		Tenant tenant = fixture.crearTenant();
		fixture.abrirCaja(tenant, "100000.00");
		EgresoView pagadoParcial = fixture.registrarYConfirmar(tenant, "30000.00");
		fixture.pagarEnEfectivo(tenant, pagadoParcial.id(), "10000.00");
		EgresoView pagadoTotal = fixture.registrarYConfirmar(tenant, "5000.00");
		fixture.pagarEnEfectivo(tenant, pagadoTotal.id(), "5000.00");
		fixture.registrar(tenant, "999.00");

		ResumenDeEgresosView resumen = egresoService.resumir(tenant.actor(), tenant.consultorioId(), null, null);

		LocalDate hoy = LocalDate.now(ZoneId.of(EgresoItFixture.ZONA));
		assertThat(resumen.desde()).isEqualTo(hoy.withDayOfMonth(1));
		assertThat(resumen.hasta()).isEqualTo(hoy.withDayOfMonth(hoy.lengthOfMonth()));
		assertThat(resumen.porMoneda()).singleElement().satisfies(t -> {
			assertThat(t.moneda()).isEqualTo("ARS");
			assertThat(t.registrado()).isEqualByComparingTo("35000.00");
			assertThat(t.pagado()).isEqualByComparingTo("15000.00");
			assertThat(t.pendiente()).isEqualByComparingTo("20000.00");
			assertThat(t.cantidad()).isEqualTo(2);
			assertThat(t.cantidadBorrador()).isEqualTo(1);
			assertThat(t.importeBorrador()).isEqualByComparingTo("999.00");
		});
		assertThat(resumen.porCategoria()).singleElement()
				.satisfies(c -> assertThat(c.categoria()).isEqualTo("HONORARIOS_PROFESIONALES"));

		ResumenDeEgresosView otroMes = egresoService.resumir(tenant.actor(), tenant.consultorioId(),
				hoy.withDayOfMonth(1).minusMonths(1), null);
		assertThat(otroMes.hasta()).isEqualTo(hoy.withDayOfMonth(1).minusDays(1));
		assertThat(otroMes.porMoneda()).as("nada del mes anterior").isEmpty();
	}

	@Test
	@DisplayName("Egresos y caja: periodo invertido 400, sin caja:operate 403, sede de otro tenant 404")
	void resumenes_rechazan() {
		Tenant tenant = fixture.crearTenant();
		LocalDate hoy = LocalDate.now(ZoneId.of(EgresoItFixture.ZONA));

		assertThatThrownBy(() -> egresoService.resumir(
				tenant.actor(), tenant.consultorioId(), hoy, hoy.minusDays(1)))
				.isInstanceOf(IllegalArgumentException.class);

		OperatingActor profesional = profesional(tenant);
		assertThatThrownBy(() -> egresoService.resumir(profesional, tenant.consultorioId(), null, null))
				.isInstanceOf(PermissionDeniedException.class);

		Tenant otro = fixture.crearTenant();
		OperatingActor ajeno = new OperatingActor(
				otro.actor().accountId(), false, otro.organizationId(), otro.consultorioId());
		assertThatThrownBy(() -> egresoService.resumir(ajeno, tenant.consultorioId(), null, null))
				.isInstanceOf(ConsultorioNoAccesibleException.class);

		JornadaCajaView jornada = fixture.abrirCaja(tenant, "0.00");
		assertThatThrownBy(() -> cajaService.ver(ajeno, tenant.consultorioId(), jornada.id()))
				.isInstanceOf(ConsultorioNoAccesibleException.class);
		assertThatThrownBy(() -> cajaService.ver(profesional, tenant.consultorioId(), jornada.id()))
				.isInstanceOf(PermissionDeniedException.class);
	}

	private MovimientoCajaView mover(Tenant tenant, TipoMovimiento tipo, MedioDePago medio, String importe) {
		return movimientoService.registrarManual(tenant.actor(), tenant.consultorioId(),
				new MovimientoManualCommand(tipo, medio, new BigDecimal(importe), "Movimiento sintetico",
						UUID.randomUUID().toString()));
	}

	/** Un PROFESIONAL de la misma sede: no tiene caja:operate. */
	private OperatingActor profesional(Tenant tenant) {
		String email = "dp45-prof-" + UUID.randomUUID() + "@ejemplo.test";
		jdbc.update("""
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado, active,
				                    version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', 'DePrueba', 'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", email, email);
		long cuenta = jdbc.queryForObject(
				"SELECT id FROM cuenta WHERE email_normalizado = ?", Long.class, email);
		jdbc.update("""
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, estado, active, version,
				                        created_at, updated_at)
				VALUES (?, ?, ?, 'PROFESIONAL', 0, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 YEAR),
				        'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", tenant.organizationId(), tenant.consultorioId(), cuenta);
		return new OperatingActor(cuenta, false, tenant.organizationId(), tenant.consultorioId());
	}
}
