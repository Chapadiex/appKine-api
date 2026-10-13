package com.akine.billing;

import com.akine.TestcontainersConfiguration;
import com.akine.billing.EgresoItFixture.Tenant;
import com.akine.billing.application.CajaService;
import com.akine.billing.application.EgresoService;
import com.akine.billing.application.EgresoView;
import com.akine.billing.application.JornadaCajaView;
import com.akine.billing.application.PagoEgresoCommand;
import com.akine.billing.application.PagoEgresoService;
import com.akine.billing.application.PagoEgresoView;
import com.akine.billing.domain.MedioDePago;
import com.akine.billing.domain.exception.CajaNoAbiertaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Escenario 47 de {@code docs/tests-diferidos.md} (AKINE-07.05): anular un pago de egreso cuando la
 * sede no tiene caja abierta.
 *
 * <p>La mitad de la transferencia ya la ejercita {@code SaldoDeEgresoConfrontadoIT}, que anula un
 * pago por transferencia en una sede sin ninguna jornada (el 500 que corrigio el PR #19). Faltaba la
 * otra mitad, la que protege el cajon: el pago <b>en efectivo</b> salio de una jornada que ya se
 * cerro, y anularlo tiene que seguir rechazandose con {@code caja-no-abierta} —la plata no tiene a
 * donde volver— <b>sin</b> dejar el pago anulado ni devolver saldo al egreso.
 *
 * <p>Necesita el motor porque lo que sostiene la regla es el
 * {@code CHECK (medio <> 'EFECTIVO' OR jornada_caja_id IS NOT NULL)} de {@code V54} y el rollback de
 * una transaccion que ya movio el saldo del egreso con un UPDATE nativo.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class AnulacionDePagoSinCajaIT {

	@Autowired private JdbcTemplate jdbc;
	@Autowired private CajaService cajaService;
	@Autowired private EgresoService egresoService;
	@Autowired private PagoEgresoService pagoService;

	private EgresoItFixture fixture;

	@BeforeEach
	void preparar() {
		fixture = new EgresoItFixture(jdbc, cajaService, egresoService, pagoService);
	}

	@Test
	@DisplayName("47 · sin caja abierta, anular un pago en efectivo es caja-no-abierta y no mueve nada")
	void efectivo_sin_caja_abierta_se_rechaza() {
		Tenant tenant = fixture.crearTenant();
		JornadaCajaView jornada = fixture.abrirCaja(tenant, "50000.00");
		EgresoView egreso = fixture.registrarYConfirmar(tenant, "20000.00");
		PagoEgresoView pago = fixture.pagarEnEfectivo(tenant, egreso.id(), "20000.00");
		fixture.cerrarCuadrada(tenant, jornada.id());

		assertThatThrownBy(() -> pagoService.anularPago(tenant.actor(), tenant.consultorioId(),
				egreso.id(), pago.id(), "Pago duplicado"))
				.isInstanceOf(CajaNoAbiertaException.class);

		assertThat(jdbc.queryForObject("SELECT estado FROM pago_egreso WHERE id = ?", String.class, pago.id()))
				.as("el pago sigue vigente: el rollback se llevo la anulacion")
				.isEqualTo("CONFIRMADO");
		assertThat(fixture.saldoPendiente(egreso.id()))
				.as("y el egreso no recupero la deuda")
				.isEqualByComparingTo("0.00");
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM movimiento_caja
				 WHERE organization_id = ? AND tipo = 'REVERSION_DE_EGRESO'
				""", Integer.class, tenant.organizationId()))
				.as("ni hay una reversion huerfana")
				.isZero();
	}

	@Test
	@DisplayName("47 · el mismo caso por transferencia se anula sin caja abierta y no toca ninguna jornada")
	void transferencia_sin_caja_abierta_se_anula() {
		Tenant tenant = fixture.crearTenant();
		JornadaCajaView jornada = fixture.abrirCaja(tenant, "50000.00");
		EgresoView egreso = fixture.registrarYConfirmar(tenant, "20000.00");
		PagoEgresoView pago = pagoService.pagar(tenant.actor(), tenant.consultorioId(), egreso.id(),
				new PagoEgresoCommand(new BigDecimal("20000.00"), MedioDePago.TRANSFERENCIA, "TRF-47", null));
		fixture.cerrarCuadrada(tenant, jornada.id());

		pagoService.anularPago(tenant.actor(), tenant.consultorioId(), egreso.id(), pago.id(),
				"Transferencia rechazada");

		assertThat(fixture.saldoPendiente(egreso.id())).isEqualByComparingTo("20000.00");
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM movimiento_caja
				 WHERE organization_id = ? AND tipo = 'REVERSION_DE_EGRESO' AND jornada_caja_id IS NULL
				""", Integer.class, tenant.organizationId()))
				.as("la reversion se asienta sin jornada, como el pago que compensa")
				.isEqualTo(1);
		assertThat(fixture.saldoArqueo(jornada.id()))
				.as("y la jornada cerrada no se toca")
				.isEqualByComparingTo("50000.00");
	}
}
