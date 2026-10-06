package com.akine.billing;

import com.akine.TestcontainersConfiguration;
import com.akine.billing.EgresoItFixture.Desenlace;
import com.akine.billing.EgresoItFixture.Tenant;
import com.akine.billing.application.CajaService;
import com.akine.billing.application.EgresoService;
import com.akine.billing.application.EgresoView;
import com.akine.billing.application.JornadaCajaView;
import com.akine.billing.application.PagoEgresoService;
import com.akine.billing.domain.exception.CajaSaldoInsuficienteException;
import com.akine.billing.domain.exception.EgresoNoPagableException;
import com.akine.billing.domain.exception.EgresoSaldoInsuficienteException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Escenarios 41 y 42 de {@code docs/tests-diferidos.md} (AKINE-07.05): <b>que la plata cuadre
 * cuando dos administrativos pagan al mismo tiempo</b>.
 *
 * <h2>Por que no puede ser un test unitario</h2>
 *
 * <p>Las dos defensas son condiciones del {@code WHERE} de un {@code UPDATE} nativo:
 * {@code saldo_arqueo >= :importe} en {@code jornada_caja} y {@code saldo_pendiente >= :importe}
 * en {@code egreso}. Un mock que devuelve cero filas prueba la rama del {@code if}; lo que esta en
 * duda es que InnoDB, bajo {@code READ_COMMITTED}, reevalue la condicion contra la fila ya
 * commiteada por la otra transaccion despues de esperar su lock. Eso solo lo contesta el motor.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class PagoEgresoConcurrenteIT {

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
	@DisplayName("41 · dos pagos en efectivo contra un cajon que no alcanza: entra uno y el otro recibe caja-saldo-insuficiente")
	void dos_pagos_en_efectivo_no_vacian_el_cajon_dos_veces() {
		// El caso que rompe el diseno (challenge §8): 80.000 en la caja y dos liquidaciones de
		// 50.000. Son DOS egresos distintos a proposito, para que la unica competencia sea por el
		// cajon y no por el saldo del compromiso. Con SELECT + UPDATE los dos verian 80.000 y la
		// jornada quedaria en -20.000 — o reventaria el CHECK de saldo no negativo con un 500.
		Tenant tenant = fixture.crearTenant();
		JornadaCajaView jornada = fixture.abrirCaja(tenant, "80000.00");
		EgresoView primero = fixture.registrarYConfirmar(tenant, "50000.00");
		EgresoView segundo = fixture.registrarYConfirmar(tenant, "50000.00");

		List<Desenlace> desenlaces = EgresoItFixture.enParalelo(List.of(
				() -> fixture.pagarEnEfectivo(tenant, primero.id(), "50000.00"),
				() -> fixture.pagarEnEfectivo(tenant, segundo.id(), "50000.00")));

		assertThat(desenlaces.stream().filter(d -> !d.fallo()).count())
				.as("exactamente un pago entra. Desenlaces: %s", desenlaces)
				.isEqualTo(1);
		assertThat(desenlaces.stream().filter(d -> d.falloPor(CajaSaldoInsuficienteException.class)).count())
				.as("el otro recibe caja-saldo-insuficiente, no un 500 ni un cajon en rojo. Desenlaces: %s",
						desenlaces)
				.isEqualTo(1);

		assertThat(fixture.saldoArqueo(jornada.id()))
				.as("el cajon queda en 30.000: salio un solo pago")
				.isEqualByComparingTo("30000.00");
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM movimiento_caja
				 WHERE jornada_caja_id = ? AND tipo = 'EGRESO' AND tipo_origen = 'PAGO_EGRESO'
				""", Integer.class, jornada.id()))
				.as("un solo movimiento de egreso en el ledger")
				.isEqualTo(1);

		// El rechazo revierte la transaccion entera: el pago perdedor no queda huerfano y su
		// egreso sigue debiendo todo. Si el descuento del compromiso no se deshiciera, la
		// liquidacion apareceria pagada sin que la plata haya salido.
		BigDecimal pendientes = fixture.saldoPendiente(primero.id()).add(fixture.saldoPendiente(segundo.id()));
		assertThat(pendientes)
				.as("entre los dos egresos se debe exactamente lo que no se pago")
				.isEqualByComparingTo("50000.00");
		assertThat(fixture.pagosVigentes(primero.id()) + fixture.pagosVigentes(segundo.id()))
				.as("una sola fila de pago_egreso sobrevive")
				.isEqualTo(1);
		assertThat(List.of(fixture.estadoEgreso(primero.id()), fixture.estadoEgreso(segundo.id())))
				.containsExactlyInAnyOrder("PAGADO", "CONFIRMADO");
	}

	@Test
	@DisplayName("42 · dos pagos parciales que juntos exceden el egreso: entra uno y el otro recibe egreso-saldo-insuficiente")
	void dos_pagos_contra_el_mismo_egreso_no_lo_dejan_negativo() {
		// La segunda condicion, la del compromiso. El cajon sobra (300.000) para que la unica
		// competencia sea por `saldo_pendiente`. Dos pagos de 60.000 contra una liquidacion de
		// 100.000: el segundo, al despertar del lock, tiene que ver 40.000 y rechazarse — con
		// SELECT + UPDATE el saldo quedaria en -20.000.
		Tenant tenant = fixture.crearTenant();
		JornadaCajaView jornada = fixture.abrirCaja(tenant, "300000.00");
		EgresoView egreso = fixture.registrarYConfirmar(tenant, "100000.00");

		List<Desenlace> desenlaces = EgresoItFixture.enParalelo(List.of(
				() -> fixture.pagarEnEfectivo(tenant, egreso.id(), "60000.00"),
				() -> fixture.pagarEnEfectivo(tenant, egreso.id(), "60000.00")));

		assertThat(desenlaces.stream().filter(d -> !d.fallo()).count())
				.as("exactamente un pago entra. Desenlaces: %s", desenlaces)
				.isEqualTo(1);
		assertThat(desenlaces.stream().filter(d -> d.falloPor(EgresoSaldoInsuficienteException.class)).count())
				.as("el otro recibe egreso-saldo-insuficiente. Desenlaces: %s", desenlaces)
				.isEqualTo(1);

		assertThat(fixture.saldoPendiente(egreso.id())).isEqualByComparingTo("40000.00");
		assertThat(fixture.estadoEgreso(egreso.id())).isEqualTo("CONFIRMADO");
		assertThat(fixture.pagosVigentes(egreso.id())).isEqualTo(1);
		assertThat(fixture.saldoArqueo(jornada.id()))
				.as("y el cajon perdio un solo pago: el rechazo del egreso revierte la salida de caja")
				.isEqualByComparingTo("240000.00");
	}

	@Test
	@DisplayName("42 · dos pagos totales del mismo egreso: entra uno, el saldo queda en cero y nunca en negativo")
	void dos_pagos_totales_dejan_el_saldo_en_cero() {
		// El caso textual del escenario 42: pagar 100.000 dos veces contra una liquidacion de
		// 100.000. Aca el perdedor NO recibe egreso-saldo-insuficiente sino egreso-no-pagable,
		// y es correcto: el ganador deja el egreso en PAGADO dentro de su transaccion, y el
		// UPDATE del perdedor —`estado = 'CONFIRMADO' AND saldo_pendiente >= :importe`— falla por
		// las dos condiciones a la vez; al releer, el servicio informa la del estado. Los dos son
		// 409 y lo que importa es la invariante: saldo cero y una sola salida del cajon.
		Tenant tenant = fixture.crearTenant();
		JornadaCajaView jornada = fixture.abrirCaja(tenant, "300000.00");
		EgresoView egreso = fixture.registrarYConfirmar(tenant, "100000.00");

		List<Desenlace> desenlaces = EgresoItFixture.enParalelo(List.of(
				() -> fixture.pagarEnEfectivo(tenant, egreso.id(), "100000.00"),
				() -> fixture.pagarEnEfectivo(tenant, egreso.id(), "100000.00")));

		assertThat(desenlaces.stream().filter(d -> !d.fallo()).count())
				.as("exactamente un pago entra. Desenlaces: %s", desenlaces)
				.isEqualTo(1);
		assertThat(desenlaces.stream()
				.filter(d -> d.falloPor(EgresoNoPagableException.class)
						|| d.falloPor(EgresoSaldoInsuficienteException.class))
				.count())
				.as("el otro recibe un 409 del egreso. Desenlaces: %s", desenlaces)
				.isEqualTo(1);

		assertThat(fixture.saldoPendiente(egreso.id()))
				.as("cero, no -100.000")
				.isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(fixture.estadoEgreso(egreso.id())).isEqualTo("PAGADO");
		assertThat(fixture.pagosVigentes(egreso.id())).isEqualTo(1);
		assertThat(fixture.saldoArqueo(jornada.id())).isEqualByComparingTo("200000.00");
	}
}
