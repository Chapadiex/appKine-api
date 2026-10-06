package com.akine.billing;

import com.akine.TestcontainersConfiguration;
import com.akine.billing.EgresoItFixture.Tenant;
import com.akine.billing.application.CajaService;
import com.akine.billing.application.EgresoService;
import com.akine.billing.application.EgresoView;
import com.akine.billing.application.JornadaCajaView;
import com.akine.billing.application.PagoEgresoService;
import com.akine.billing.application.PagoEgresoView;
import com.akine.billing.domain.exception.EgresoConPagosException;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Escenarios 45 y 46 de {@code docs/tests-diferidos.md} (AKINE-07.05): el egreso recorriendo la
 * caja de punta a punta, con la base como unico testigo.
 *
 * <h2>Por que necesita MySQL</h2>
 *
 * <p>Pagar y anular tocan tres agregados —{@code egreso}, {@code pago_egreso} y
 * {@code jornada_caja}— y dos de ellos con {@code UPDATE} nativos marcados
 * {@code clearAutomatically}. Es exactamente donde la copia en memoria de JPA y la fila real se
 * separan sin que nada falle, y por eso cada asercion de este archivo lee la fila por JDBC en vez
 * de confiar en la vista que devuelve el servicio.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class EgresoCicloDeCajaIT {

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
	@DisplayName("45 · borrador → confirmar → pago parcial → anular pago → pago total → anular egreso rechazado")
	void ciclo_completo_del_egreso_contra_la_caja() {
		// Regla: el compromiso no mueve plata, el pago si, y anular un pago devuelve la plata al
		// cajon con una reversion trazable sin borrar nada. Mientras quede un pago vigente el
		// egreso no se puede anular (409 egreso-con-pagos).
		Tenant tenant = fixture.crearTenant();
		JornadaCajaView jornada = fixture.abrirCaja(tenant, "100000.00");

		EgresoView borrador = fixture.registrar(tenant, "30000.00");
		assertThat(fixture.estadoEgreso(borrador.id())).isEqualTo("BORRADOR");

		egresoService.confirmar(tenant.actor(), tenant.consultorioId(), borrador.id());
		assertThat(fixture.estadoEgreso(borrador.id())).isEqualTo("CONFIRMADO");
		assertThat(fixture.saldoArqueo(jornada.id()))
				.as("confirmar no mueve un peso: el compromiso es lo que se debe, no lo que salio")
				.isEqualByComparingTo("100000.00");

		PagoEgresoView parcial = fixture.pagarEnEfectivo(tenant, borrador.id(), "10000.00");
		assertThat(fixture.saldoArqueo(jornada.id())).isEqualByComparingTo("90000.00");
		assertThat(fixture.saldoPendiente(borrador.id())).isEqualByComparingTo("20000.00");
		assertThat(fixture.estadoEgreso(borrador.id())).isEqualTo("CONFIRMADO");

		pagoService.anularPago(tenant.actor(), tenant.consultorioId(), borrador.id(), parcial.id(),
				"Se pago al beneficiario equivocado");
		assertThat(fixture.saldoArqueo(jornada.id()))
				.as("la plata vuelve al cajon")
				.isEqualByComparingTo("100000.00");
		assertThat(fixture.saldoPendiente(borrador.id()))
				.as("y el saldo vuelve al egreso")
				.isEqualByComparingTo("30000.00");
		assertThat(jdbc.queryForObject("SELECT estado FROM pago_egreso WHERE id = ?", String.class, parcial.id()))
				.as("el pago queda anulado, no borrado")
				.isEqualTo("ANULADO");

		PagoEgresoView total = fixture.pagarEnEfectivo(tenant, borrador.id(), "30000.00");
		assertThat(fixture.saldoArqueo(jornada.id())).isEqualByComparingTo("70000.00");
		assertThat(fixture.saldoPendiente(borrador.id())).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(fixture.estadoEgreso(borrador.id())).isEqualTo("PAGADO");

		assertThatThrownBy(() -> egresoService.anular(
				tenant.actor(), tenant.consultorioId(), borrador.id(), "Ya no corresponde"))
				.as("con un pago vigente el compromiso no se anula: primero se devuelve la plata")
				.isInstanceOf(EgresoConPagosException.class);
		assertThat(fixture.estadoEgreso(borrador.id())).isEqualTo("PAGADO");

		// El ledger, en orden: la salida, su compensacion apuntando a ella, y la segunda salida.
		List<Map<String, Object>> ledger = jdbc.queryForList("""
				SELECT id, tipo, importe, movimiento_origen_id FROM movimiento_caja
				 WHERE jornada_caja_id = ? ORDER BY id
				""", jornada.id());
		assertThat(ledger).extracting(m -> m.get("tipo"))
				.containsExactly("EGRESO", "REVERSION_DE_EGRESO", "EGRESO");
		assertThat(ledger).extracting(m -> new BigDecimal(m.get("importe").toString()).stripTrailingZeros())
				.containsExactly(new BigDecimal("1E+4"), new BigDecimal("1E+4"), new BigDecimal("3E+4"));
		assertThat(((Number) ledger.get(1).get("movimiento_origen_id")).longValue())
				.as("la reversion apunta al movimiento que compensa")
				.isEqualTo(((Number) ledger.get(0).get("id")).longValue());

		// La vista del servicio, leida en una transaccion nueva, tiene que decir lo mismo que la
		// fila: si la copia de JPA se hubiera quedado con un saldo viejo, aca se veria.
		EgresoView detalle = egresoService.ver(tenant.actor(), tenant.consultorioId(), borrador.id());
		assertThat(detalle.saldoPendiente()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(detalle.estado()).isEqualTo("PAGADO");
		assertThat(detalle.pagos()).extracting(PagoEgresoView::id, PagoEgresoView::estado)
				.containsExactlyInAnyOrder(
						org.assertj.core.groups.Tuple.tuple(parcial.id(), "ANULADO"),
						org.assertj.core.groups.Tuple.tuple(total.id(), "CONFIRMADO"));

		// Y el cierre del ciclo: anulado el ultimo pago, el egreso si se anula y la caja vuelve a
		// su valor de apertura.
		pagoService.anularPago(tenant.actor(), tenant.consultorioId(), borrador.id(), total.id(),
				"Liquidacion cargada por error");
		egresoService.anular(tenant.actor(), tenant.consultorioId(), borrador.id(), "Liquidacion cargada por error");
		assertThat(fixture.estadoEgreso(borrador.id())).isEqualTo("ANULADO");
		assertThat(fixture.saldoArqueo(jornada.id())).isEqualByComparingTo("100000.00");
	}

	@Test
	@DisplayName("46 · anular un pago de una jornada ya cerrada compensa en la jornada abierta hoy y no toca el cierre")
	void la_compensacion_cae_en_la_jornada_abierta_hoy() {
		// Regla (RN-M20-003): una jornada cerrada no se reabre ni se corrige. La reversion se
		// asienta en la jornada abierta HOY, y el saldo teorico con el que se cerro la anterior
		// queda intacto. Necesita el motor porque exige dos jornadas reales, un cierre real que
		// escriba `saldo_teorico_cierre`, y releer filas que transacciones anteriores ya commitearon.
		Tenant tenant = fixture.crearTenant();
		JornadaCajaView jornadaA = fixture.abrirCaja(tenant, "50000.00");
		EgresoView egreso = fixture.registrarYConfirmar(tenant, "20000.00");
		PagoEgresoView pago = fixture.pagarEnEfectivo(tenant, egreso.id(), "20000.00");

		JornadaCajaView cerradaA = fixture.cerrarCuadrada(tenant, jornadaA.id());
		assertThat(cerradaA.estado()).isEqualTo("CERRADA");
		BigDecimal teoricoCierreA = jdbc.queryForObject(
				"SELECT saldo_teorico_cierre FROM jornada_caja WHERE id = ?", BigDecimal.class, jornadaA.id());
		assertThat(teoricoCierreA).isEqualByComparingTo("30000.00");

		JornadaCajaView jornadaB = fixture.abrirCaja(tenant, "5000.00");

		pagoService.anularPago(tenant.actor(), tenant.consultorioId(), egreso.id(), pago.id(),
				"Pago duplicado");

		Map<String, Object> reversion = jdbc.queryForMap("""
				SELECT jornada_caja_id, importe FROM movimiento_caja
				 WHERE organization_id = ? AND tipo = 'REVERSION_DE_EGRESO'
				""", tenant.organizationId());
		assertThat(((Number) reversion.get("jornada_caja_id")).longValue())
				.as("la reversion se asienta en B, la abierta hoy, no en A")
				.isEqualTo(jornadaB.id());
		assertThat(fixture.saldoArqueo(jornadaB.id()))
				.as("la plata entra al cajon de B")
				.isEqualByComparingTo("25000.00");

		assertThat(jdbc.queryForMap("""
				SELECT estado, saldo_arqueo, saldo_teorico_cierre FROM jornada_caja WHERE id = ?
				""", jornadaA.id()))
				.as("A queda exactamente como se cerro")
				.satisfies(fila -> {
					assertThat(fila.get("estado")).isEqualTo("CERRADA");
					assertThat((BigDecimal) fila.get("saldo_arqueo")).isEqualByComparingTo("30000.00");
					assertThat((BigDecimal) fila.get("saldo_teorico_cierre")).isEqualByComparingTo(teoricoCierreA);
				});
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM movimiento_caja WHERE jornada_caja_id = ?", Integer.class, jornadaA.id()))
				.as("y su ledger no recibio filas despues del cierre")
				.isEqualTo(1);

		assertThat(fixture.saldoPendiente(egreso.id())).isEqualByComparingTo("20000.00");
		assertThat(fixture.estadoEgreso(egreso.id())).isEqualTo("CONFIRMADO");
	}
}
