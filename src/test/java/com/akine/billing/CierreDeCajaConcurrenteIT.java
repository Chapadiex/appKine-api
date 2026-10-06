package com.akine.billing;

import com.akine.TestcontainersConfiguration;
import com.akine.billing.application.CajaService;
import com.akine.billing.application.CobroCommand;
import com.akine.billing.application.CobroService;
import com.akine.billing.application.JornadaCajaView;
import com.akine.billing.application.OperatingActor;
import com.akine.billing.domain.MedioDePago;
import com.akine.billing.domain.exception.CajaCerradaException;
import com.akine.billing.domain.exception.CajaNoAbiertaException;
import com.akine.billing.domain.exception.CajaSaldoCambioException;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Escenario 34 de {@code docs/tests-diferidos.md} (AKINE-07.03): {@code saldoTeoricoEsperado}, el
 * cobro en efectivo que entra entre el conteo y el cierre, y el cierre concurrente.
 *
 * <h2>Lo que dice el diseno, y por eso lo que se prueba</h2>
 *
 * <p>{@code docs/diseno/AKINE-07.03-caja.md} §6: el cierre es un solo {@code UPDATE} con
 * {@code AND estado = 'ABIERTA' AND saldo_arqueo = :saldoTeoricoEsperado}. Si un cobro en efectivo
 * entra mientras el operador cuenta, el cierre <b>se rechaza</b> —cero filas, 409
 * {@code caja-saldo-cambio} con el teorico actual— en vez de registrar un faltante que nunca
 * existio. Y dos cierres simultaneos: el segundo afecta cero filas porque {@code estado} ya no es
 * {@code ABIERTA} → 409 {@code caja-cerrada}.
 *
 * <p>Las dos condiciones las evalua el motor; un mock que devuelve cero filas prueba el {@code if}
 * del servicio, no la regla. Por eso cada asercion lee la fila por JDBC.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class CierreDeCajaConcurrenteIT {

	private static final String ZONA = "America/Argentina/Cordoba";
	private static final BigDecimal INICIAL = new BigDecimal("1000.00");
	private static final BigDecimal COBRO = new BigDecimal("8500.00");

	@Autowired private CajaService cajaService;
	@Autowired private CobroService cobroService;
	@Autowired private JdbcTemplate jdbc;

	@Test
	@DisplayName("34 · un cobro en efectivo entre el conteo y el cierre: el cierre se rechaza con el teorico actual")
	void el_cobro_que_entra_mientras_se_cuenta_frena_el_cierre() {
		Fixture fixture = crearFixture();

		// El operador mira la pantalla y empieza a contar: el teorico es 1000.
		BigDecimal teoricoAlContar = saldoArqueo(fixture);
		assertThat(teoricoAlContar).isEqualByComparingTo(INICIAL);

		// Mientras cuenta, otro mostrador cobra 8500 en efectivo.
		cobrar(fixture);
		assertThat(saldoArqueo(fixture)).isEqualByComparingTo("9500.00");

		// Confirma el cierre con lo que conto. Un cierre ingenuo registraria -8500 de diferencia.
		assertThatThrownBy(() -> cajaService.cerrar(
				fixture.actor(), fixture.consultorioId(), fixture.jornadaId(),
				teoricoAlContar, teoricoAlContar, null))
				.isInstanceOfSatisfying(CajaSaldoCambioException.class, e -> {
					assertThat(e.getSaldoTeoricoEsperado()).isEqualByComparingTo(INICIAL);
					assertThat(e.getSaldoTeoricoActual())
							.as("el 409 lleva el teorico actual, para que la pantalla diga cuanto entro")
							.isEqualByComparingTo("9500.00");
				});

		Map<String, Object> fila = jornada(fixture);
		assertThat(fila.get("estado"))
				.as("la jornada sigue abierta: nada se cerro en silencio")
				.isEqualTo("ABIERTA");
		assertThat(fila.get("diferencia")).as("ninguna diferencia inventada quedo registrada").isNull();
		assertThat(fila.get("saldo_declarado")).isNull();
		assertThat(fila.get("saldo_teorico_cierre")).isNull();

		// El operador suma los billetes que estan en el cajon y confirma contra el numero nuevo.
		JornadaCajaView cerrada = cajaService.cerrar(
				fixture.actor(), fixture.consultorioId(), fixture.jornadaId(),
				new BigDecimal("9500.00"), new BigDecimal("9500.00"), null);
		assertThat(cerrada.estado()).isEqualTo("CERRADA");

		fila = jornada(fixture);
		assertThat(fila.get("estado")).isEqualTo("CERRADA");
		assertThat((BigDecimal) fila.get("saldo_teorico_cierre")).isEqualByComparingTo("9500.00");
		assertThat((BigDecimal) fila.get("diferencia"))
				.as("cuadra: el cobro no aparece como sobrante ni el conteo viejo como faltante")
				.isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(fila.get("motivo_diferencia")).isNull();
	}

	@Test
	@DisplayName("34 · dos cierres simultaneos: uno cierra y el otro recibe caja-cerrada")
	void dos_cierres_simultaneos_cierran_una_sola_vez() {
		Fixture fixture = crearFixture();

		// Declaraciones distintas a proposito: si los dos entraran, la segunda pisaria la
		// diferencia y el motivo de la primera, y el arqueo historico mentiria.
		List<Desenlace> desenlaces = enParalelo(List.of(
				() -> cajaService.cerrar(fixture.actor(), fixture.consultorioId(), fixture.jornadaId(),
						INICIAL, INICIAL, null),
				() -> cajaService.cerrar(fixture.actor(), fixture.consultorioId(), fixture.jornadaId(),
						INICIAL, new BigDecimal("900.00"), "Faltan cien pesos")));

		assertThat(desenlaces.stream().filter(d -> !d.fallo()).count())
				.as("exactamente un cierre entra. Desenlaces: %s", desenlaces)
				.isEqualTo(1);
		assertThat(desenlaces.stream()
				.filter(Desenlace::fallo)
				.filter(d -> causaEs(d.error(), CajaCerradaException.class))
				.count())
				.as("el otro recibe caja-cerrada. Desenlaces: %s", desenlaces)
				.isEqualTo(1);

		Map<String, Object> fila = jornada(fixture);
		assertThat(fila.get("estado")).isEqualTo("CERRADA");
		int ganador = desenlaces.get(0).fallo() ? 1 : 0;
		if (ganador == 0) {
			assertThat((BigDecimal) fila.get("diferencia")).isEqualByComparingTo(BigDecimal.ZERO);
			assertThat(fila.get("motivo_diferencia")).isNull();
		} else {
			assertThat((BigDecimal) fila.get("diferencia")).isEqualByComparingTo("-100.00");
			assertThat(fila.get("motivo_diferencia")).isEqualTo("Faltan cien pesos");
		}
	}

	@Test
	@DisplayName("34 · un cobro en efectivo y el cierre a la vez: entra exactamente uno, y nunca con una diferencia falsa")
	void cobro_y_cierre_simultaneos_no_dejan_una_diferencia_falsa() {
		// La carrera de verdad del escenario: no secuencial como el primer caso, sino las dos
		// transacciones peleandose por la misma fila. Se repite porque el orden lo decide el motor
		// y los dos desenlaces tienen que ser correctos.
		for (int ronda = 0; ronda < 5; ronda++) {
			Fixture fixture = crearFixture();

			List<Desenlace> desenlaces = enParalelo(List.of(
					() -> {
						cobrar(fixture);
						return null;
					},
					() -> cajaService.cerrar(fixture.actor(), fixture.consultorioId(),
							fixture.jornadaId(), INICIAL, INICIAL, null)));
			Desenlace cobro = desenlaces.get(0);
			Desenlace cierre = desenlaces.get(1);

			assertThat(desenlaces.stream().filter(d -> !d.fallo()).count())
					.as("ronda %d: entra exactamente uno. Cobro=%s Cierre=%s", ronda, cobro, cierre)
					.isEqualTo(1);

			Map<String, Object> fila = jornada(fixture);
			if (!cobro.fallo()) {
				assertThat(causaEs(cierre.error(), CajaSaldoCambioException.class))
						.as("ronda %d: el cierre pierde con caja-saldo-cambio. Cierre=%s", ronda, cierre)
						.isTrue();
				assertThat(fila.get("estado")).isEqualTo("ABIERTA");
				assertThat((BigDecimal) fila.get("saldo_arqueo")).isEqualByComparingTo("9500.00");
			} else {
				assertThat(causaEs(cobro.error(), CajaCerradaException.class)
						|| causaEs(cobro.error(), CajaNoAbiertaException.class))
						.as("ronda %d: el cobro pierde porque la caja cerro, y lo dice. Cobro=%s",
								ronda, cobro)
						.isTrue();
				assertThat(fila.get("estado")).isEqualTo("CERRADA");
				assertThat((BigDecimal) fila.get("saldo_teorico_cierre")).isEqualByComparingTo(INICIAL);
				assertThat((BigDecimal) fila.get("diferencia")).isEqualByComparingTo(BigDecimal.ZERO);
				assertThat(saldoDeuda(fixture))
						.as("ronda %d: el cobro rechazado no desconto la deuda", ronda)
						.isEqualByComparingTo(COBRO);
				assertThat(jdbc.queryForObject(
						"SELECT COUNT(*) FROM movimiento_caja WHERE jornada_caja_id = ?",
						Integer.class, fixture.jornadaId()))
						.as("ronda %d: y no dejo un movimiento en la jornada cerrada", ronda)
						.isZero();
			}
		}
	}

	// =================================================================================

	private void cobrar(Fixture fixture) {
		cobroService.registrar(fixture.actor(), fixture.consultorioId(), new CobroCommand(
				fixture.personaId(), COBRO,
				List.of(new CobroCommand.MedioPedido(MedioDePago.EFECTIVO, COBRO, null)),
				List.of(new CobroCommand.ImputacionPedida(fixture.obligacionId(), COBRO)),
				null));
	}

	private BigDecimal saldoArqueo(Fixture fixture) {
		return jdbc.queryForObject(
				"SELECT saldo_arqueo FROM jornada_caja WHERE id = ?", BigDecimal.class, fixture.jornadaId());
	}

	private BigDecimal saldoDeuda(Fixture fixture) {
		return jdbc.queryForObject(
				"SELECT saldo FROM obligacion WHERE id = ?", BigDecimal.class, fixture.obligacionId());
	}

	private Map<String, Object> jornada(Fixture fixture) {
		return jdbc.queryForMap("""
				SELECT estado, saldo_arqueo, saldo_teorico_cierre, saldo_declarado, diferencia,
				       motivo_diferencia
				  FROM jornada_caja WHERE id = ?
				""", fixture.jornadaId());
	}

	/** La barrera es lo que hace real la carrera: sin ella la primera suele terminar antes. */
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

	// =================================================================================
	// Fixture
	// =================================================================================

	private record Fixture(
			long organizationId, long consultorioId, long personaId, long obligacionId,
			long jornadaId, OperatingActor actor) {
	}

	/**
	 * Una sede con su caja abierta en 1000 —por {@link CajaService}, que es lo que esta en duda— y
	 * una deuda de 8500 lista para cobrar en efectivo. El resto se inserta por JDBC, igual que
	 * {@code CobroConcurrenteIT}: llegar hasta aca por turno, sesion y cierre no es lo que se mide.
	 */
	private Fixture crearFixture() {
		String sufijo = UUID.randomUUID().toString().substring(0, 8);

		long organizationId = insertar("""
				INSERT INTO organization (name, slug, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM organization WHERE slug = ?",
				new Object[]{"Centro " + sufijo, "cierre-it-" + sufijo, ZONA},
				new Object[]{"cierre-it-" + sufijo});

		long consultorioId = insertar("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM consultorio WHERE organization_id = ? AND name = ?",
				new Object[]{organizationId, "Sede " + sufijo, ZONA},
				new Object[]{organizationId, "Sede " + sufijo});

		String email = "cierre-it-" + sufijo + "@ejemplo.test";
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

		OperatingActor actor = new OperatingActor(cuentaId, false, organizationId, consultorioId);
		long jornadaId = cajaService.abrir(actor, consultorioId, INICIAL, "ARS").id();

		String apellido = "Paciente" + sufijo;
		long personaId = insertar("""
				INSERT INTO persona (organization_id, apellido, nombre, apellido_clave, nombre_clave,
				                     active, version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', ?, 'SINTETICO', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM persona WHERE organization_id = ? AND apellido = ?",
				new Object[]{organizationId, apellido, apellido.toUpperCase()},
				new Object[]{organizationId, apellido});

		long historiaId = insertar("""
				INSERT INTO historia_clinica (organization_id, persona_id, abierta_en, abierta_por,
				                              active, version, created_at, updated_at)
				VALUES (?, ?, UTC_TIMESTAMP(6), ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM historia_clinica WHERE organization_id = ? AND persona_id = ?",
				new Object[]{organizationId, personaId, cuentaId},
				new Object[]{organizationId, personaId});

		long servicioId = insertar("""
				INSERT INTO servicio (codigo, nombre, naturaleza, modalidad_default,
				                      requiere_caso_clinico_default, genera_registro_clinico_default,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, 'CLINICO', 'INDIVIDUAL', 0, 0, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM servicio WHERE codigo = ?",
				new Object[]{"CIERRE-" + sufijo.toUpperCase(), "Servicio " + sufijo},
				new Object[]{"CIERRE-" + sufijo.toUpperCase()});

		long ofertaId = insertar("""
				INSERT INTO oferta_servicio_consultorio
				       (organization_id, consultorio_id, servicio_id, nombre_comercial, modalidad,
				        duracion_minutos, capacidad, precio_base, moneda, admite_obra_social,
				        requiere_caso_clinico, genera_registro_clinico, requiere_profesional,
				        requiere_espacio, vigencia_desde, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'INDIVIDUAL', 60, 1, 8500.00, 'ARS', 0, 0, 0, 0, 0,
				        DATE_SUB(CURDATE(), INTERVAL 5 YEAR), 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", """
				SELECT id FROM oferta_servicio_consultorio
				 WHERE organization_id = ? AND nombre_comercial = ?
				""", new Object[]{organizationId, consultorioId, servicioId, "Oferta " + sufijo},
				new Object[]{organizationId, "Oferta " + sufijo});

		jdbc.update("""
				INSERT INTO sesion (organization_id, consultorio_id, historia_clinica_id, oferta_id,
				                    profesional_membership_id, estado, iniciada_en,
				                    iniciada_por_cuenta_id, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 'BORRADOR', UTC_TIMESTAMP(6), ?, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, consultorioId, historiaId, ofertaId, 1L, cuentaId);
		long sesionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

		jdbc.update("""
				INSERT INTO obligacion (organization_id, consultorio_id, sesion_id, persona_id,
				                        responsable, importe_original, saldo, moneda, estado,
				                        oferta_id, snapshot_nombre, snapshot_precio, devengada_en,
				                        version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'PACIENTE', 8500.00, 8500.00, 'ARS', 'PENDIENTE',
				        ?, 'Sesion sintetica', 8500.00, UTC_TIMESTAMP(6), 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, consultorioId, sesionId, personaId, ofertaId);
		long obligacionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

		return new Fixture(organizationId, consultorioId, personaId, obligacionId, jornadaId, actor);
	}

	private long insertar(String insert, String select, Object[] insertArgs, Object[] selectArgs) {
		jdbc.update(insert, insertArgs);
		return jdbc.queryForObject(select, Long.class, selectArgs);
	}
}
