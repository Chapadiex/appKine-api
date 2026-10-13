package com.akine.billing;

import com.akine.TestcontainersConfiguration;
import com.akine.billing.PresentacionItFixture.Desenlace;
import com.akine.billing.PresentacionItFixture.Tenant;
import com.akine.billing.application.PresentacionService;
import com.akine.billing.application.PresentacionView;
import com.akine.billing.domain.exception.ObligacionYaPresentadaException;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El perdedor de la carrera de RN-M21-003 no pide una segunda conexion (docs/carga.md §8).
 *
 * <p>Hasta el 11/10/2026, el que perdia la carrera contra {@code uk_presentacion_item_ocupa} leia el
 * lote ganador con un {@code REQUIRES_NEW} desde el {@code catch}, con su propia conexion todavia
 * tomada. A diferencia de los otros anidamientos, este solo aparece en una carrera, asi que el
 * escenario la arma a mano, con el mismo contexto de pool 2 que {@code PoolDeConexionesIT}:
 *
 * <ol>
 *   <li>una conexion de afuera del pool bloquea la obligacion con {@code FOR UPDATE};</li>
 *   <li>dos agregados de esa obligacion a dos lotes pasan la consulta previa y se frenan en la FK
 *       del INSERT: las dos conexiones del pool quedan tomadas;</li>
 *   <li>un tercer hilo pide una conexion y queda primero en la cola;</li>
 *   <li>se suelta la obligacion. El ganador commitea y su conexion va al tercer hilo, que la
 *       retiene. El perdedor choca contra el unique.</li>
 * </ol>
 *
 * <p>Con el codigo viejo el perdedor pedia una conexion con la suya tomada, y no habia ninguna: moria
 * por {@code CannotCreateTransactionException}. Ahora su transaccion revierte primero, devuelve la
 * conexion y la lectura del lote usa esa.
 */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = {
				"spring.datasource.hikari.maximum-pool-size=2",
				"spring.datasource.hikari.minimum-idle=2",
				"spring.datasource.hikari.connection-timeout=10000"
		})
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class PresentacionPoolDeConexionesIT {

	@Autowired private PresentacionService presentacionService;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private DataSource dataSource;

	@Test
	@DisplayName("el perdedor de la carrera por una obligacion recibe el lote ganador sin pedir una segunda conexion")
	void el_perdedor_no_traba_el_pool() throws Exception {
		PresentacionItFixture fixture = new PresentacionItFixture(jdbc, presentacionService);
		Tenant tenant = fixture.crearTenant();
		long obligacionId = fixture.obligacionDeFinanciador(tenant, tenant.financiadorId(), "8500.00");
		PresentacionView loteA = fixture.borrador(tenant, tenant.financiadorId());
		PresentacionView loteB = fixture.borrador(tenant, tenant.financiadorId());

		HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
		ExecutorService hilos = Executors.newFixedThreadPool(3);
		CountDownLatch soltarTercero = new CountDownLatch(1);
		try (Connection afuera = DriverManager.getConnection(
				hikari.getJdbcUrl(), hikari.getUsername(), hikari.getPassword())) {
			afuera.setAutoCommit(false);
			try (PreparedStatement bloqueo =
					afuera.prepareStatement("SELECT id FROM obligacion WHERE id = ? FOR UPDATE")) {
				bloqueo.setLong(1, obligacionId);
				bloqueo.executeQuery().close();
			}

			List<Future<Desenlace>> agregados = new ArrayList<>();
			for (long lote : new long[]{loteA.id(), loteB.id()}) {
				agregados.add(hilos.submit(() -> desenlace(() -> presentacionService.agregarItem(
						tenant.actor(), tenant.consultorioId(), lote, obligacionId))));
			}
			esperar(() -> hikari.getHikariPoolMXBean().getActiveConnections() == 2,
					"los dos agregados toman las dos conexiones");
			// Margen para que los dos lleguen al INSERT y se frenen en la FK de la obligacion.
			Thread.sleep(1_000);

			Future<?> tercero = hilos.submit(() -> {
				try (Connection retenida = dataSource.getConnection()) {
					soltarTercero.await(30, TimeUnit.SECONDS);
				}
				return null;
			});
			esperar(() -> hikari.getHikariPoolMXBean().getThreadsAwaitingConnection() >= 1,
					"el tercero queda primero en la cola del pool");

			afuera.commit();

			List<Desenlace> desenlaces = new ArrayList<>();
			for (Future<Desenlace> agregado : agregados) {
				desenlaces.add(agregado.get(60, TimeUnit.SECONDS));
			}
			soltarTercero.countDown();
			tercero.get(30, TimeUnit.SECONDS);

			assertThat(desenlaces)
					.as("nadie muere por falta de conexion. Desenlaces: %s", desenlaces)
					.noneMatch(d -> d.falloPor(CannotCreateTransactionException.class)
							|| d.falloPor(SQLTransientConnectionException.class));
			assertThat(desenlaces.stream().filter(d -> !d.fallo()).count())
					.as("entra en un solo lote. Desenlaces: %s", desenlaces)
					.isEqualTo(1);
			assertThat(desenlaces.stream()
					.filter(d -> d.falloPor(ObligacionYaPresentadaException.class)).count())
					.as("y el otro recibe obligacion-ya-presentada. Desenlaces: %s", desenlaces)
					.isEqualTo(1);
		} finally {
			soltarTercero.countDown();
			hilos.shutdownNow();
		}
	}

	private static Desenlace desenlace(java.util.concurrent.Callable<?> tarea) {
		try {
			return new Desenlace(tarea.call(), null);
		} catch (Exception error) {
			return new Desenlace(null, error);
		}
	}

	private static void esperar(BooleanSupplier condicion, String que) throws InterruptedException {
		long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
		while (!condicion.getAsBoolean()) {
			if (System.nanoTime() > limite) {
				throw new AssertionError("No se dio a tiempo: " + que);
			}
			Thread.sleep(20);
		}
	}
}
