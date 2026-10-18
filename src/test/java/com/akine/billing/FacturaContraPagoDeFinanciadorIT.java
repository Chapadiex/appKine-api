package com.akine.billing;

import com.akine.TestcontainersConfiguration;
import com.akine.billing.PresentacionItFixture.Tenant;
import com.akine.billing.application.FinanciadorPagoService;
import com.akine.billing.application.PresentacionCommands;
import com.akine.billing.application.PresentacionService;
import com.akine.billing.application.PresentacionView;
import com.akine.billing.domain.MedioDePago;
import com.akine.billing.domain.Presentacion;
import com.akine.billing.domain.port.PresentacionRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El mismo patron que la carrera pago contra anulacion de egreso (web #94), en la presentacion.
 *
 * <p>Registrar la factura de un lote {@code PRESENTADA} es una escritura por JPA; el pago del
 * financiador mueve {@code saldo} y {@code total_cobrado} con un {@code UPDATE} nativo que no toca
 * {@code version}. Sin {@code @DynamicUpdate}, el flush de la factura reescribia las columnas del
 * saldo con lo que habia leido y su {@code WHERE version = N} pasaba: el pago quedaba registrado y
 * asentado en la caja, y el lote volvia a deber lo que ya se habia cobrado.
 *
 * <p>Intercalado a mano —leer, pagar y commitear en otro hilo, despues facturar—, porque es lo unico
 * que hace la carrera determinista.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class FacturaContraPagoDeFinanciadorIT {

	@Autowired private PresentacionService presentacionService;
	@Autowired private FinanciadorPagoService pagoService;
	@Autowired private PresentacionRepositoryPort presentaciones;
	@Autowired private TransactionTemplate transacciones;
	@Autowired private JdbcTemplate jdbc;

	private PresentacionItFixture fixture;

	@BeforeEach
	void armar() {
		fixture = new PresentacionItFixture(jdbc, presentacionService);
	}

	@Test
	@DisplayName("una factura registrada sobre un lote leido antes de un pago del financiador no borra el pago")
	void la_factura_no_pisa_el_pago() {
		Tenant tenant = fixture.crearTenant();
		PresentacionView lote = fixture.loteConfirmado(tenant, "100000.00");

		transacciones.executeWithoutResult(estado -> {
			Presentacion leida = presentaciones
					.findByIdInScope(tenant.organizationId(), tenant.consultorioId(), lote.id())
					.orElseThrow();

			// El financiador transfiere 40.000 y otro operador lo registra mientras esta
			// transaccion sigue abierta.
			try (ExecutorService otro = Executors.newSingleThreadExecutor()) {
				otro.submit(() -> pagoService.registrar(tenant.actor(), tenant.consultorioId(), lote.id(),
						new PresentacionCommands.Pago(new BigDecimal("40000.00"), MedioDePago.TRANSFERENCIA,
								LocalDate.now(), "TRF-FACT", null)))
						.get(30, TimeUnit.SECONDS);
			} catch (Exception fallo) {
				throw new IllegalStateException(fallo);
			}

			leida.registrarFactura("A-0001-" + UUID.randomUUID().toString().substring(0, 8),
					LocalDate.now(), Instant.now(), tenant.actor().accountId());
			presentaciones.save(leida);
		});

		Map<String, Object> fila = jdbc.queryForMap(
				"SELECT estado, saldo, total_cobrado, factura_numero FROM presentacion WHERE id = ?", lote.id());
		assertThat(fila.get("estado")).isEqualTo("FACTURADA");
		assertThat(fila.get("factura_numero")).isNotNull();
		assertThat((BigDecimal) fila.get("total_cobrado"))
				.as("el pago del financiador sigue en el lote")
				.isEqualByComparingTo("40000.00");
		assertThat((BigDecimal) fila.get("saldo"))
				.as("y el lote debe lo que falta, no los 100.000 de antes del pago")
				.isEqualByComparingTo("60000.00");
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM financiador_pago WHERE presentacion_id = ?", Integer.class, lote.id()))
				.isEqualTo(1);
	}
}
