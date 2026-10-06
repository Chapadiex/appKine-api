package com.akine.billing;

import com.akine.TestcontainersConfiguration;
import com.akine.billing.EgresoItFixture.Tenant;
import com.akine.billing.application.CajaService;
import com.akine.billing.application.EgresoService;
import com.akine.billing.application.JornadaCajaView;
import com.akine.billing.application.MovimientoCajaService;
import com.akine.billing.application.MovimientoCajaView;
import com.akine.billing.application.MovimientoManualCommand;
import com.akine.billing.application.PagoEgresoService;
import com.akine.billing.domain.MedioDePago;
import com.akine.billing.domain.TipoMovimiento;
import com.akine.billing.domain.exception.CajaSaldoInsuficienteException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Escenario 33 de {@code docs/tests-diferidos.md} (AKINE-07.03): el {@code UPDATE} condicional del
 * saldo de la jornada bajo concurrencia real.
 *
 * <h2>Por que no puede ser un test unitario</h2>
 *
 * <p>Toda la proteccion del cajon es una condicion dentro de una sentencia:
 * {@code UPDATE jornada_caja SET saldo_arqueo = saldo_arqueo - :importe WHERE ... AND
 * saldo_arqueo >= :importe}. El unitario de 07.03 prueba lo que el servicio hace cuando un mock
 * devuelve cero filas; que el motor devuelva cero filas cuando dos transacciones se pelean por el
 * ultimo peso es lo que solo MySQL puede contestar.
 *
 * <p>Cada asercion lee la fila por JDBC: el {@code UPDATE} es nativo y {@code clearAutomatically},
 * y la vista que devuelve el servicio no es testigo de lo que quedo en la base.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class SaldoDeCajaConcurrenteIT {

	@Autowired private JdbcTemplate jdbc;
	@Autowired private CajaService cajaService;
	@Autowired private EgresoService egresoService;
	@Autowired private PagoEgresoService pagoService;
	@Autowired private MovimientoCajaService movimientoService;

	private EgresoItFixture fixture;

	@BeforeEach
	void preparar() {
		fixture = new EgresoItFixture(jdbc, cajaService, egresoService, pagoService);
	}

	@Test
	@DisplayName("33 · dos egresos simultaneos por el ultimo peso: uno entra, el otro recibe caja-saldo-insuficiente")
	void dos_egresos_por_el_ultimo_peso_no_pasan_los_dos() {
		Tenant tenant = fixture.crearTenant();
		JornadaCajaView jornada = fixture.abrirCaja(tenant, "1000.00");

		List<Desenlace> desenlaces = enParalelo(List.of(
				() -> egresar(tenant, "1000.00", "Retiro A"),
				() -> egresar(tenant, "1000.00", "Retiro B")));

		assertThat(desenlaces.stream().filter(d -> !d.fallo()).count())
				.as("exactamente un egreso entra. Desenlaces: %s", desenlaces)
				.isEqualTo(1);
		assertThat(desenlaces.stream()
				.filter(Desenlace::fallo)
				.filter(d -> causaEs(d.error(), CajaSaldoInsuficienteException.class))
				.count())
				.as("el otro termina en caja-saldo-insuficiente, no en un saldo negativo ni en un "
						+ "error tecnico del CHECK. Desenlaces: %s", desenlaces)
				.isEqualTo(1);

		assertThat(fixture.saldoArqueo(jornada.id()))
				.as("el cajon queda en cero, nunca en negativo")
				.isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(egresosAsentados(jornada.id()))
				.as("un solo movimiento asentado: el perdedor no deja una fila describiendo un "
						+ "egreso que no ocurrio")
				.isEqualTo(1);
		assertThat(cajaService.saldoReconstruible(tenant.actor(), tenant.consultorioId(), jornada.id()))
				.as("y el saldo materializado sigue coincidiendo con el ledger")
				.isTrue();
	}

	@Test
	@DisplayName("33 · control: dos egresos simultaneos que caben entran los dos y dejan el saldo exacto")
	void dos_egresos_que_caben_entran_los_dos() {
		// La otra mitad de la regla: la condicion no debe rechazar egresos que SI caben. Si lo
		// hiciera, el arreglo de la carrera habria roto el caso normal.
		Tenant tenant = fixture.crearTenant();
		JornadaCajaView jornada = fixture.abrirCaja(tenant, "1000.00");

		List<Desenlace> desenlaces = enParalelo(List.of(
				() -> egresar(tenant, "400.00", "Retiro A"),
				() -> egresar(tenant, "600.00", "Retiro B")));

		assertThat(desenlaces.stream().filter(Desenlace::fallo).toList())
				.as("los dos entran. Desenlaces: %s", desenlaces)
				.isEmpty();
		assertThat(fixture.saldoArqueo(jornada.id())).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(egresosAsentados(jornada.id())).isEqualTo(2);
		assertThat(cajaService.saldoReconstruible(tenant.actor(), tenant.consultorioId(), jornada.id()))
				.isTrue();
	}

	// =================================================================================

	private MovimientoCajaView egresar(Tenant tenant, String importe, String concepto) {
		return movimientoService.registrarManual(tenant.actor(), tenant.consultorioId(),
				new MovimientoManualCommand(TipoMovimiento.EGRESO, MedioDePago.EFECTIVO,
						new BigDecimal(importe), concepto, null));
	}

	private int egresosAsentados(long jornadaId) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM movimiento_caja WHERE jornada_caja_id = ? AND tipo = 'EGRESO'
				""", Integer.class, jornadaId);
	}

	/** La barrera es lo que hace real la carrera: sin ella el primero suele terminar antes. */
	private static List<Desenlace> enParalelo(List<Callable<?>> tareas) {
		CyclicBarrier salida = new CyclicBarrier(tareas.size());
		try (ExecutorService pool = Executors.newFixedThreadPool(tareas.size())) {
			List<Future<Desenlace>> futuros = new ArrayList<>();
			for (Callable<?> tarea : tareas) {
				futuros.add(pool.submit(() -> {
					salida.await(10, TimeUnit.SECONDS);
					try {
						tarea.call();
						return new Desenlace(null);
					} catch (Exception error) {
						return new Desenlace(error);
					}
				}));
			}
			List<Desenlace> desenlaces = new ArrayList<>();
			for (Future<Desenlace> futuro : futuros) {
				desenlaces.add(futuro.get(30, TimeUnit.SECONDS));
			}
			return desenlaces;
		} catch (Exception fallo) {
			throw new IllegalStateException("La ejecucion concurrente no pudo completarse", fallo);
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

	private record Desenlace(Exception error) {

		boolean fallo() {
			return error != null;
		}

		@Override
		public String toString() {
			return fallo() ? "FALLO(" + error.getClass().getSimpleName() + ": " + error.getMessage() + ")" : "OK";
		}
	}
}
