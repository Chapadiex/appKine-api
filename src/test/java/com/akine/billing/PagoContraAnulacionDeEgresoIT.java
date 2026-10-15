package com.akine.billing;

import com.akine.TestcontainersConfiguration;
import com.akine.billing.EgresoItFixture.Desenlace;
import com.akine.billing.EgresoItFixture.Tenant;
import com.akine.billing.application.CajaService;
import com.akine.billing.application.EgresoService;
import com.akine.billing.application.EgresoView;
import com.akine.billing.application.JornadaCajaView;
import com.akine.billing.application.PagoEgresoService;
import com.akine.billing.domain.Egreso;
import com.akine.billing.domain.exception.EgresoConPagosException;
import com.akine.billing.domain.exception.EgresoNoPagableException;
import com.akine.billing.domain.port.EgresoRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El pago en efectivo de un egreso y la anulacion del mismo egreso, al mismo tiempo.
 *
 * <p>Lo destapo el E2E de {@code appKine-web} #94 contra MySQL real: en 9 de 12 intentos las dos
 * operaciones respondian bien y el egreso quedaba {@code ANULADO} con un pago {@code CONFIRMADO}
 * vigente. La plata habia salido del cajon y nada lo marcaba.
 *
 * <p>La causa: la anulacion lee el egreso, decide "no tiene pagos" mirando el saldo y lo escribe por
 * JPA con {@code WHERE version = N}. El pago descuenta el saldo con un {@code UPDATE} nativo que no
 * tocaba {@code version}, asi que si commiteaba entre la lectura y el flush de la anulacion, la
 * version seguia siendo N y la anulacion pisaba el saldo. Es el defecto 5 del 29/09 y la fila 20 del
 * 06/10, sobre otra tabla.
 *
 * <p>Las dos pruebas cubren las dos defensas: la del servicio (la anulacion lee con
 * {@code FOR UPDATE}) con una carrera real de hilos, y la de la base (el UPDATE nativo avanza la
 * version) intercalando a mano, que es lo unico que hace la carrera determinista.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class PagoContraAnulacionDeEgresoIT {

	private static final int RONDAS = 12;
	private static final String IMPORTE = "1000.00";
	private static final String CAJA_INICIAL = "500000.00";

	@Autowired private JdbcTemplate jdbc;
	@Autowired private CajaService cajaService;
	@Autowired private EgresoService egresoService;
	@Autowired private PagoEgresoService pagoService;
	@Autowired private EgresoRepositoryPort egresos;
	@Autowired private TransactionTemplate transacciones;

	private EgresoItFixture fixture;

	@BeforeEach
	void preparar() {
		fixture = new EgresoItFixture(jdbc, cajaService, egresoService, pagoService);
	}

	@Test
	@DisplayName("pago en efectivo y anulacion del mismo egreso a la vez: en cada ronda gana uno y el otro recibe 409")
	void pago_y_anulacion_simultaneos_no_dejan_un_egreso_anulado_con_pagos() {
		Tenant tenant = fixture.crearTenant();
		JornadaCajaView jornada = fixture.abrirCaja(tenant, CAJA_INICIAL);
		BigDecimal cajaEsperada = new BigDecimal(CAJA_INICIAL);

		for (int ronda = 1; ronda <= RONDAS; ronda++) {
			EgresoView egreso = fixture.registrarYConfirmar(tenant, IMPORTE);

			List<Desenlace> desenlaces = EgresoItFixture.enParalelo(List.of(
					() -> fixture.pagarEnEfectivo(tenant, egreso.id(), IMPORTE),
					() -> egresoService.anular(
							tenant.actor(), tenant.consultorioId(), egreso.id(), "Cargado dos veces")));
			Desenlace pago = desenlaces.get(0);
			Desenlace anulacion = desenlaces.get(1);

			assertThat(desenlaces.stream().filter(d -> !d.fallo()).count())
					.as("ronda %d: exactamente una de las dos entra. Desenlaces: %s", ronda, desenlaces)
					.isEqualTo(1);

			if (!pago.fallo()) {
				assertThat(anulacion.falloPor(EgresoConPagosException.class))
						.as("ronda %d: la anulacion ve el pago y responde egreso-con-pagos. Desenlaces: %s",
								ronda, desenlaces)
						.isTrue();
				assertThat(fixture.estadoEgreso(egreso.id())).isEqualTo("PAGADO");
				assertThat(fixture.saldoPendiente(egreso.id())).isEqualByComparingTo(BigDecimal.ZERO);
				assertThat(fixture.pagosVigentes(egreso.id())).isEqualTo(1);
				cajaEsperada = cajaEsperada.subtract(new BigDecimal(IMPORTE));
			} else {
				assertThat(pago.falloPor(EgresoNoPagableException.class))
						.as("ronda %d: el pago ve el egreso anulado y responde egreso-no-pagable. Desenlaces: %s",
								ronda, desenlaces)
						.isTrue();
				assertThat(fixture.estadoEgreso(egreso.id())).isEqualTo("ANULADO");
				assertThat(fixture.pagosVigentes(egreso.id()))
						.as("ronda %d: un egreso anulado no tiene pagos vigentes", ronda)
						.isZero();
			}

			assertThat(fixture.saldoArqueo(jornada.id()))
					.as("ronda %d: el cajon perdio exactamente los pagos que quedaron vigentes", ronda)
					.isEqualByComparingTo(cajaEsperada);
		}

		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM egreso e
				 WHERE e.organization_id = ? AND e.estado = 'ANULADO'
				   AND EXISTS (SELECT 1 FROM pago_egreso p
				                WHERE p.egreso_id = e.id AND p.estado = 'CONFIRMADO')
				""", Integer.class, tenant.organizationId()))
				.as("ningun egreso anulado con un pago vigente, en ninguna ronda")
				.isZero();
	}

	@Test
	@DisplayName("una anulacion que leyo el egreso antes del pago no lo pisa: el descuento avanza la version")
	void la_anulacion_vieja_no_pisa_el_pago() {
		// La carrera sin hilos compitiendo: esta transaccion lee el egreso, otro mostrador paga y
		// commitea en el medio, y recien despues se anula con lo leido. Antes del arreglo el
		// WHERE version = N pasaba y el egreso quedaba ANULADO con el pago vigente.
		Tenant tenant = fixture.crearTenant();
		JornadaCajaView jornada = fixture.abrirCaja(tenant, CAJA_INICIAL);
		EgresoView egreso = fixture.registrarYConfirmar(tenant, IMPORTE);

		assertThatThrownBy(() -> transacciones.executeWithoutResult(estado -> {
			Egreso leido = egresos
					.findByIdInScope(tenant.organizationId(), tenant.consultorioId(), egreso.id())
					.orElseThrow();

			try (ExecutorService otro = Executors.newSingleThreadExecutor()) {
				otro.submit(() -> fixture.pagarEnEfectivo(tenant, egreso.id(), IMPORTE))
						.get(30, TimeUnit.SECONDS);
			} catch (Exception fallo) {
				throw new IllegalStateException(fallo);
			}

			leido.anular("Cargado dos veces", Instant.now(), tenant.actor().accountId());
			egresos.save(leido);
		})).isInstanceOf(OptimisticLockingFailureException.class);

		assertThat(fixture.estadoEgreso(egreso.id()))
				.as("el egreso NO quedo anulado con un pago vigente")
				.isEqualTo("PAGADO");
		assertThat(fixture.saldoPendiente(egreso.id())).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(fixture.pagosVigentes(egreso.id())).isEqualTo(1);
		assertThat(fixture.saldoArqueo(jornada.id())).isEqualByComparingTo("499000.00");
	}
}
