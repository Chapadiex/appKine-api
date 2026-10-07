package com.akine.billing;

import com.akine.TestcontainersConfiguration;
import com.akine.billing.PresentacionItFixture.Desenlace;
import com.akine.billing.PresentacionItFixture.Tenant;
import com.akine.billing.application.FinanciadorPagoService;
import com.akine.billing.application.PresentacionCommands;
import com.akine.billing.application.PresentacionItemView;
import com.akine.billing.application.PresentacionService;
import com.akine.billing.application.PresentacionView;
import com.akine.billing.domain.MedioDePago;
import com.akine.billing.domain.exception.PresentacionSaldoInsuficienteException;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Escenario 38 de {@code docs/tests-diferidos.md}: el caso que rompe el diseño de 07.04, con dos
 * transacciones de verdad.
 *
 * <p>Un lote de 100.000 presentado. El financiador manda a la vez un aviso de debito por 15.000
 * sobre la unica prestacion y una transferencia por los 100.000 enteros. Las dos operaciones son
 * legitimas por separado y juntas no caben: 15.000 + 100.000 superan lo reclamado. Toda la
 * proteccion vive en el {@code AND saldo >= :importe} de los dos {@code UPDATE} nativos de
 * {@code PresentacionRepository}: el que llega segundo espera el lock de fila, re-evalua el
 * {@code WHERE} contra la fila ya commiteada y afecta <b>cero filas</b>.
 *
 * <p>El unitario que existia simulaba esas cero filas con un mock: probaba la traduccion a 409,
 * no que el motor serialice. Esto es lo otro.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class DebitoYPagoConcurrenteIT {

	private static final int RONDAS = 6;

	/**
	 * Ventaja que se le da al debito en las rondas pares. Sin ella gana siempre la transferencia:
	 * el debito carga y valida el item antes de su UPDATE, y la mitad "gano el debito" de la regla
	 * quedaba sin ejercer. Con la ventaja el pago llega con la fila ya tomada o ya commiteada, y su
	 * WHERE se re-evalua igual contra el saldo nuevo.
	 */
	private static final long VENTAJA_DEL_DEBITO_MS = 200;

	@Autowired private PresentacionService presentacionService;
	@Autowired private FinanciadorPagoService pagoService;
	@Autowired private JdbcTemplate jdbc;

	private PresentacionItFixture fixture;

	@BeforeEach
	void armar() {
		fixture = new PresentacionItFixture(jdbc, presentacionService);
	}

	@Test
	@DisplayName("debito de 15.000 y transferencia de 100.000 sobre un lote de 100.000 a la vez: entra uno, el otro 409 con el saldo actual")
	void debito_y_pago_simultaneos_no_dejan_el_saldo_negativo() {
		int ganoElDebito = 0;
		int ganoElPago = 0;

		for (int ronda = 1; ronda <= RONDAS; ronda++) {
			Tenant tenant = fixture.crearTenant();
			PresentacionView lote = fixture.loteConfirmado(tenant, "100000.00");
			long itemId = lote.items().getFirst().id();

			long demoraDelPago = ronda % 2 == 0 ? VENTAJA_DEL_DEBITO_MS : 0;
			List<Desenlace> desenlaces = PresentacionItFixture.enParalelo(List.<Callable<?>>of(
					() -> debitar(tenant, lote.id(), itemId, "15000.00"),
					() -> {
						Thread.sleep(demoraDelPago);
						return pagar(tenant, lote.id(), "100000.00");
					}));
			Desenlace debito = desenlaces.get(0);
			Desenlace pago = desenlaces.get(1);

			assertThat(desenlaces.stream().filter(d -> !d.fallo()).count())
					.as("ronda %d: exactamente uno entra. Desenlaces: %s", ronda, desenlaces)
					.isEqualTo(1);
			Desenlace perdedor = debito.fallo() ? debito : pago;
			assertThat(perdedor.falloPor(PresentacionSaldoInsuficienteException.class))
					.as("ronda %d: el perdedor recibe presentacion-saldo-insuficiente, no un CHECK "
							+ "violado ni un 500. Desenlaces: %s", ronda, desenlaces)
					.isTrue();

			Map<String, Object> fila = lote(lote.id());
			BigDecimal saldo = (BigDecimal) fila.get("saldo");
			assertThat(saldo).as("ronda %d: el saldo nunca queda negativo", ronda)
					.isGreaterThanOrEqualTo(BigDecimal.ZERO);
			assertThat(saldo)
					.as("ronda %d: el saldo sigue siendo presentado - debitado - cobrado", ronda)
					.isEqualByComparingTo(((BigDecimal) fila.get("total_presentado"))
							.subtract((BigDecimal) fila.get("total_debitado"))
							.subtract((BigDecimal) fila.get("total_cobrado")));
			assertThat(((PresentacionSaldoInsuficienteException) causa(perdedor.error()))
					.getSaldoDisponible())
					.as("ronda %d: el 409 informa el saldo que dejo el ganador, no el que leyo "
							+ "antes de esperar el lock", ronda)
					.isEqualByComparingTo(saldo);

			Map<String, Object> item = item(itemId);
			int pagos = contar("SELECT COUNT(*) FROM financiador_pago WHERE presentacion_id = ?", lote.id());
			int movimientos = contar("""
					SELECT COUNT(*) FROM movimiento_caja
					 WHERE organization_id = ? AND tipo_origen = 'PAGO_FINANCIADOR'
					""", tenant.organizationId());

			if (!debito.fallo()) {
				ganoElDebito++;
				assertThat(saldo).isEqualByComparingTo("85000.00");
				assertThat(fila.get("total_debitado")).isEqualTo(new BigDecimal("15000.00"));
				assertThat(fila.get("total_cobrado")).isEqualTo(new BigDecimal("0.00"));
				assertThat(item.get("estado"))
						.as("ronda %d: el item del debito ganador quedo DEBITADO en la base", ronda)
						.isEqualTo("DEBITADO");
				assertThat((BigDecimal) item.get("importe_debitado")).isEqualByComparingTo("15000.00");
				assertThat(pagos).as("el pago perdedor no dejo fila").isZero();
				assertThat(movimientos).as("ni movimiento de caja").isZero();
			} else {
				ganoElPago++;
				assertThat(saldo).isEqualByComparingTo("0.00");
				assertThat(fila.get("total_cobrado")).isEqualTo(new BigDecimal("100000.00"));
				assertThat(fila.get("total_debitado")).isEqualTo(new BigDecimal("0.00"));
				assertThat(item.get("estado"))
						.as("el debito perdedor no marco el item")
						.isEqualTo("INCLUIDO");
				assertThat((BigDecimal) item.get("importe_debitado")).isEqualByComparingTo("0.00");
				assertThat(pagos).isEqualTo(1);
				assertThat(movimientos).isEqualTo(1);
			}
		}

		assertThat(ganoElDebito + ganoElPago).isEqualTo(RONDAS);
		assertThat(ganoElDebito)
				.as("las dos mitades de la regla se ejercitaron: gano el debito %d veces y el pago %d",
						ganoElDebito, ganoElPago)
				.isPositive();
		assertThat(ganoElPago).isPositive();
	}

	@Test
	@DisplayName("un debito solo queda escrito en el item: DEBITADO, con motivo, y libera la obligacion")
	void el_debito_queda_escrito_en_el_item() {
		// El control de la mitad que la carrera no siempre ejercita: el item del debito tiene que
		// quedar marcado en la BASE, no solo en la vista que devuelve el servicio. Entre cargar el
		// item y marcarlo corre un UPDATE nativo con clearAutomatically.
		Tenant tenant = fixture.crearTenant();
		PresentacionView lote = fixture.loteConfirmado(tenant, "100000.00");
		long itemId = lote.items().getFirst().id();

		PresentacionItemView vista = debitar(tenant, lote.id(), itemId, "15000.00");
		assertThat(vista.estado().name()).isEqualTo("DEBITADO");

		Map<String, Object> item = item(itemId);
		assertThat(item.get("estado")).isEqualTo("DEBITADO");
		assertThat((BigDecimal) item.get("importe_debitado")).isEqualByComparingTo("15000.00");
		assertThat(item.get("motivo_debito")).isEqualTo("Falta autorizacion previa");
		assertThat(item.get("debitado_en")).isNotNull();
		assertThat(item.get("ocupa_marca"))
				.as("DEBITADO libera la obligacion para otro lote")
				.isNull();
		assertThat((BigDecimal) lote(lote.id()).get("saldo")).isEqualByComparingTo("85000.00");
	}

	// =================================================================================

	private PresentacionItemView debitar(Tenant tenant, long presentacionId, long itemId, String importe) {
		return presentacionService.debitar(tenant.actor(), tenant.consultorioId(), presentacionId,
				itemId, new PresentacionCommands.Debito(new BigDecimal(importe), "Falta autorizacion previa"));
	}

	private Object pagar(Tenant tenant, long presentacionId, String importe) {
		return pagoService.registrar(tenant.actor(), tenant.consultorioId(), presentacionId,
				new PresentacionCommands.Pago(new BigDecimal(importe), MedioDePago.TRANSFERENCIA,
						LocalDate.now(), "TRF-0038", null));
	}

	private static Throwable causa(Throwable error) {
		for (Throwable actual = error; actual != null; actual = actual.getCause()) {
			if (actual instanceof PresentacionSaldoInsuficienteException) {
				return actual;
			}
		}
		throw new AssertionError("sin PresentacionSaldoInsuficienteException en la cadena", error);
	}

	private Map<String, Object> lote(long presentacionId) {
		return jdbc.queryForMap("""
				SELECT saldo, total_presentado, total_debitado, total_cobrado
				  FROM presentacion WHERE id = ?
				""", presentacionId);
	}

	private Map<String, Object> item(long itemId) {
		return jdbc.queryForMap("""
				SELECT estado, importe_debitado, motivo_debito, debitado_en, ocupa_marca
				  FROM presentacion_item WHERE id = ?
				""", itemId);
	}

	private int contar(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}
}
