package com.akine.billing;

import com.akine.billing.application.CajaService;
import com.akine.billing.application.EgresoCommand;
import com.akine.billing.application.EgresoService;
import com.akine.billing.application.EgresoView;
import com.akine.billing.application.JornadaCajaView;
import com.akine.billing.application.OperatingActor;
import com.akine.billing.application.PagoEgresoCommand;
import com.akine.billing.application.PagoEgresoService;
import com.akine.billing.application.PagoEgresoView;
import com.akine.billing.domain.CategoriaEgreso;
import com.akine.billing.domain.MedioDePago;
import com.akine.billing.domain.TipoBeneficiario;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Lo que comparten los ITs de egresos de 07.05: un tenant con su sede y un administrativo que
 * opera la caja, y los atajos para llegar a un egreso confirmado por los servicios reales.
 *
 * <p>El tenant se inserta por JDBC —igual que {@code CobroConcurrenteIT}— porque llegar hasta aca
 * por el onboarding no es lo que estos tests miden. La caja y los egresos, en cambio, pasan por
 * {@link CajaService} y {@link EgresoService}: los {@code UPDATE} nativos y el
 * {@code clearAutomatically} son justamente lo que esta en duda.
 */
final class EgresoItFixture {

	static final String ZONA = "America/Argentina/Cordoba";
	static final String MONEDA = "ARS";

	private final JdbcTemplate jdbc;
	private final CajaService cajaService;
	private final EgresoService egresoService;
	private final PagoEgresoService pagoService;

	EgresoItFixture(
			JdbcTemplate jdbc, CajaService cajaService,
			EgresoService egresoService, PagoEgresoService pagoService) {
		this.jdbc = jdbc;
		this.cajaService = cajaService;
		this.egresoService = egresoService;
		this.pagoService = pagoService;
	}

	/** Un tenant: organizacion, una sede y un ADMINISTRATIVO, que es quien tiene caja:operate. */
	record Tenant(long organizationId, long consultorioId, OperatingActor actor) {
	}

	Tenant crearTenant() {
		String sufijo = UUID.randomUUID().toString().substring(0, 8);

		long organizationId = insertar("""
				INSERT INTO organization (name, slug, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM organization WHERE slug = ?",
				new Object[]{"Centro " + sufijo, "egreso-it-" + sufijo, ZONA},
				new Object[]{"egreso-it-" + sufijo});

		long consultorioId = insertar("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM consultorio WHERE organization_id = ? AND name = ?",
				new Object[]{organizationId, "Sede " + sufijo, ZONA},
				new Object[]{organizationId, "Sede " + sufijo});

		String email = "egreso-it-" + sufijo + "@ejemplo.test";
		long cuentaId = insertar("""
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado, active,
				                    version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', 'DePrueba', 'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM cuenta WHERE email_normalizado = ?",
				new Object[]{email, email}, new Object[]{email});

		jdbc.update("""
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, estado, active, version,
				                        created_at, updated_at)
				VALUES (?, ?, ?, 'ADMINISTRATIVO', 0, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 YEAR),
				        'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, consultorioId, cuentaId);

		return new Tenant(organizationId, consultorioId,
				new OperatingActor(cuentaId, false, organizationId, consultorioId));
	}

	JornadaCajaView abrirCaja(Tenant tenant, String saldoInicial) {
		return cajaService.abrir(tenant.actor(), tenant.consultorioId(), new BigDecimal(saldoInicial), MONEDA);
	}

	/** Cierra con el arqueo exacto: sin diferencia, sin motivo. */
	JornadaCajaView cerrarCuadrada(Tenant tenant, long jornadaId) {
		BigDecimal teorico = saldoArqueo(jornadaId);
		return cajaService.cerrar(tenant.actor(), tenant.consultorioId(), jornadaId, teorico, teorico, null);
	}

	/** Un egreso EXTERNO en borrador, con comprobante unico para poder confirmarlo. */
	EgresoView registrar(Tenant tenant, String importe) {
		String numero = "0001-" + UUID.randomUUID().toString().substring(0, 8);
		return egresoService.registrar(tenant.actor(), tenant.consultorioId(), new EgresoCommand(
				CategoriaEgreso.HONORARIOS_PROFESIONALES, TipoBeneficiario.EXTERNO, null,
				"Lic. Externa Sintetica", "20-" + numero.substring(5) + "-1",
				null, null, "Liquidacion sintetica", new BigDecimal(importe), MONEDA,
				"FACTURA_C", numero, LocalDate.now(), null));
	}

	EgresoView registrarYConfirmar(Tenant tenant, String importe) {
		EgresoView borrador = registrar(tenant, importe);
		return egresoService.confirmar(tenant.actor(), tenant.consultorioId(), borrador.id());
	}

	PagoEgresoView pagarEnEfectivo(Tenant tenant, long egresoId, String importe) {
		return pagoService.pagar(tenant.actor(), tenant.consultorioId(), egresoId,
				new PagoEgresoCommand(new BigDecimal(importe), MedioDePago.EFECTIVO, null, null));
	}

	// ---------------------------------------------------------------------------------
	// Lecturas directas de la fila: lo que el motor tiene, no la copia de JPA
	// ---------------------------------------------------------------------------------

	BigDecimal saldoArqueo(long jornadaId) {
		return jdbc.queryForObject(
				"SELECT saldo_arqueo FROM jornada_caja WHERE id = ?", BigDecimal.class, jornadaId);
	}

	BigDecimal saldoPendiente(long egresoId) {
		return jdbc.queryForObject(
				"SELECT saldo_pendiente FROM egreso WHERE id = ?", BigDecimal.class, egresoId);
	}

	String estadoEgreso(long egresoId) {
		return jdbc.queryForObject("SELECT estado FROM egreso WHERE id = ?", String.class, egresoId);
	}

	int pagosVigentes(long egresoId) {
		return jdbc.queryForObject(
				"SELECT COUNT(*) FROM pago_egreso WHERE egreso_id = ? AND estado = 'CONFIRMADO'",
				Integer.class, egresoId);
	}

	// ---------------------------------------------------------------------------------
	// Concurrencia
	// ---------------------------------------------------------------------------------

	record Desenlace(Object resultado, Exception error) {

		boolean fallo() {
			return error != null;
		}

		boolean falloPor(Class<? extends Throwable> tipo) {
			for (Throwable actual = error; actual != null; actual = actual.getCause()) {
				if (tipo.isInstance(actual)) {
					return true;
				}
			}
			return false;
		}

		@Override
		public String toString() {
			return fallo() ? "FALLO(" + error.getClass().getSimpleName() + ": " + error.getMessage() + ")"
					: "OK";
		}
	}

	/** La barrera es lo que hace real la carrera: sin ella la primera suele terminar antes. */
	static List<Desenlace> enParalelo(List<Callable<?>> tareas) {
		CyclicBarrier salida = new CyclicBarrier(tareas.size());
		try (ExecutorService pool = Executors.newFixedThreadPool(tareas.size())) {
			List<Future<Desenlace>> futuros = new ArrayList<>();
			for (Callable<?> tarea : tareas) {
				futuros.add(pool.submit(() -> {
					salida.await(10, TimeUnit.SECONDS);
					try {
						return new Desenlace(tarea.call(), null);
					} catch (Exception error) {
						return new Desenlace(null, error);
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

	private long insertar(String insert, String select, Object[] insertArgs, Object[] selectArgs) {
		jdbc.update(insert, insertArgs);
		return jdbc.queryForObject(select, Long.class, selectArgs);
	}
}
