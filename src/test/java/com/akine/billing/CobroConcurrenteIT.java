package com.akine.billing;

import com.akine.TestcontainersConfiguration;
import com.akine.billing.application.CobroCommand;
import com.akine.billing.application.CobroService;
import com.akine.billing.application.CobroView;
import com.akine.billing.application.OperatingActor;
import com.akine.billing.domain.MedioDePago;
import com.akine.billing.domain.exception.SaldoInsuficienteException;
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
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lo que decide la correctitud de M19: <b>una deuda no se cobra dos veces</b>.
 *
 * <h2>Por que no puede ser un test unitario</h2>
 *
 * <p>Lo que impide el doble cobro es que
 * {@code UPDATE obligacion SET saldo = saldo - :importe WHERE saldo >= :importe} sea atomico, y eso
 * solo lo puede contestar MySQL. Un mock verificaria que se llamo al metodo, no que dos
 * transacciones no puedan restar la misma plata.
 *
 * <p>El caso real: dos administrativos en dos mostradores cobran la misma sesion al mismo tiempo.
 * Con {@code SELECT saldo} seguido de {@code UPDATE}, los dos leen 8500, los dos restan 8500 y el
 * saldo queda en -8500 — la cuenta corriente muestra que el centro le debe plata al paciente.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class CobroConcurrenteIT {

	private static final String ZONA = "America/Argentina/Cordoba";
	private static final BigDecimal IMPORTE = new BigDecimal("8500.00");

	@Autowired private CobroService cobroService;
	@Autowired private JdbcTemplate jdbc;

	@Test
	@DisplayName("dos cobros concurrentes de la misma deuda: uno entra y el otro recibe 409")
	void dos_cobros_de_la_misma_deuda_no_pasan_los_dos() {
		Fixture fixture = crearFixture();

		Callable<CobroView> primero = () -> cobrar(fixture, IMPORTE);
		Callable<CobroView> segundo = () -> cobrar(fixture, IMPORTE);

		List<Desenlace> desenlaces = enParalelo(List.of(primero, segundo));

		assertThat(desenlaces.stream().filter(d -> !d.fallo()).count())
				.as("exactamente un cobro entra. Desenlaces: %s", desenlaces)
				.isEqualTo(1);
		assertThat(desenlaces.stream()
				.filter(Desenlace::fallo)
				.filter(d -> causaEs(d.error(), SaldoInsuficienteException.class))
				.count())
				.as("el otro recibe saldo-insuficiente, no un saldo negativo. Desenlaces: %s", desenlaces)
				.isEqualTo(1);

		assertThat(saldoDe(fixture))
				.as("y el saldo queda en cero, nunca en negativo")
				.isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(estadoDe(fixture)).isEqualTo("PAGADA");
	}

	@Test
	@DisplayName("dos cobros parciales concurrentes entran los dos y dejan el saldo exacto")
	void dos_parciales_concurrentes_cuadran() {
		// La otra mitad de la misma regla: el UPDATE condicional no debe rechazar cobros que SI
		// caben. Si lo hiciera, el arreglo de la carrera habria roto el caso normal — pagar una
		// sesion en dos partes es corriente.
		Fixture fixture = crearFixture();
		BigDecimal mitad = new BigDecimal("4250.00");

		List<Desenlace> desenlaces = enParalelo(List.of(
				() -> cobrar(fixture, mitad),
				() -> cobrar(fixture, mitad)));

		assertThat(desenlaces.stream().filter(d -> !d.fallo()).count())
				.as("los dos entran. Desenlaces: %s", desenlaces)
				.isEqualTo(2);
		assertThat(saldoDe(fixture)).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(estadoDe(fixture)).isEqualTo("PAGADA");
	}

	@Test
	@DisplayName("dos comprobantes concurrentes de la misma sede reciben numeros distintos")
	void los_comprobantes_no_se_repiten() {
		// Un comprobante repetido es un problema fiscal, no un detalle. Se cobran dos deudas
		// distintas para que la competencia sea SOLO por el numerador y no por el saldo.
		Fixture fixture = crearFixture();
		// Su PROPIA sesion: `uk_obligacion_prestacion` es (sesion_id, responsable), asi que dos
		// deudas del mismo paciente sobre la misma sesion no pueden existir — y es correcto que
		// no puedan: una prestacion genera su deuda una sola vez.
		long segundaDeuda = insertarObligacion(conSesionNueva(fixture));

		List<Desenlace> desenlaces = enParalelo(List.of(
				() -> cobrar(fixture, IMPORTE),
				() -> cobrarContra(fixture, segundaDeuda, IMPORTE)));

		assertThat(desenlaces.stream().filter(Desenlace::fallo).toList())
				.as("los dos cobros entran: no compiten por saldo. Desenlaces: %s", desenlaces)
				.isEmpty();
		assertThat(jdbc.queryForList("""
				SELECT comprobante_numero FROM cobro WHERE organization_id = ?
				 ORDER BY comprobante_numero
				""", Integer.class, fixture.organizationId()))
				.as("uno y dos, sin repetir")
				.containsExactly(1, 2);
	}

	@Test
	@DisplayName("un cobro cuyos medios no suman el total se rechaza antes de tocar la deuda")
	void los_medios_tienen_que_sumar() {
		// La invariante de RN-M19 que ninguna constraint puede expresar. Sin ella, un cobro de
		// 8500 con un medio de 850 —un cero de menos— entra igual, la deuda queda saldada y en la
		// caja falta plata que nadie puede explicar.
		Fixture fixture = crearFixture();

		try {
			cobroService.registrar(fixture.actor(), fixture.consultorioId(), new CobroCommand(
					fixture.personaId(), IMPORTE,
					List.of(new CobroCommand.MedioPedido(MedioDePago.EFECTIVO, new BigDecimal("850.00"), null)),
					List.of(new CobroCommand.ImputacionPedida(fixture.obligacionId(), IMPORTE)),
					null));
		} catch (RuntimeException esperado) {
			// El desenlace correcto. Lo que importa es lo de abajo.
		}

		assertThat(saldoDe(fixture))
				.as("la deuda no se toco: el rechazo revierte el descuento")
				.isEqualByComparingTo(IMPORTE);
	}

	// =================================================================================

	private CobroView cobrar(Fixture fixture, BigDecimal importe) {
		return cobrarContra(fixture, fixture.obligacionId(), importe);
	}

	private CobroView cobrarContra(Fixture fixture, long obligacionId, BigDecimal importe) {
		return cobroService.registrar(fixture.actor(), fixture.consultorioId(), new CobroCommand(
				fixture.personaId(), importe,
				List.of(new CobroCommand.MedioPedido(MedioDePago.EFECTIVO, importe, null)),
				List.of(new CobroCommand.ImputacionPedida(obligacionId, importe)),
				null));
	}

	private BigDecimal saldoDe(Fixture fixture) {
		return jdbc.queryForObject(
				"SELECT saldo FROM obligacion WHERE id = ?", BigDecimal.class, fixture.obligacionId());
	}

	private String estadoDe(Fixture fixture) {
		return jdbc.queryForObject(
				"SELECT estado FROM obligacion WHERE id = ?", String.class, fixture.obligacionId());
	}

	/** La barrera es lo que hace real la carrera: sin ella la primera suele terminar antes. */
	private static List<Desenlace> enParalelo(List<Callable<CobroView>> tareas) {
		CyclicBarrier salida = new CyclicBarrier(tareas.size());
		try (ExecutorService pool = Executors.newFixedThreadPool(tareas.size())) {
			List<Future<Desenlace>> futuros = new ArrayList<>();
			for (Callable<CobroView> tarea : tareas) {
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

	private static boolean causaEs(Throwable error, Class<? extends Throwable> tipo) {
		for (Throwable actual = error; actual != null; actual = actual.getCause()) {
			if (tipo.isInstance(actual)) {
				return true;
			}
		}
		return false;
	}

	private record Desenlace(CobroView cobro, Exception error) {

		boolean fallo() {
			return error != null;
		}

		@Override
		public String toString() {
			return fallo()
					? "FALLO(" + error.getClass().getSimpleName() + ")"
					: "OK(comprobante=" + cobro.comprobanteNumero() + ")";
		}
	}

	// =================================================================================
	// Fixture
	// =================================================================================

	private record Fixture(
			long organizationId, long consultorioId, long personaId, long obligacionId,
			long sesionId, long ofertaId, OperatingActor actor) {
	}

	/**
	 * Una deuda de 8500 lista para cobrar.
	 *
	 * <p>Se inserta directo: llegar hasta aca por los servicios exigiria turno, sesion y cierre,
	 * que ya tienen sus propios tests. Lo que este mide es el descuento del saldo.
	 */
	private Fixture crearFixture() {
		String sufijo = UUID.randomUUID().toString().substring(0, 8);

		long organizationId = insertar("""
				INSERT INTO organization (name, slug, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM organization WHERE slug = ?",
				new Object[]{"Centro " + sufijo, "cobro-it-" + sufijo, ZONA},
				new Object[]{"cobro-it-" + sufijo});

		long consultorioId = insertar("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM consultorio WHERE organization_id = ? AND name = ?",
				new Object[]{organizationId, "Sede " + sufijo, ZONA},
				new Object[]{organizationId, "Sede " + sufijo});

		String email = "cobro-it-" + sufijo + "@ejemplo.test";
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

		// LA CAJA ABIERTA, que este test no necesitaba cuando se escribio.
		//
		// 07.02 cobraba en efectivo sin mas; 07.03 hizo que el efectivo exija una jornada abierta,
		// porque la plata entra al cajon exista o no la jornada y sin ella el arqueo del dia no
		// cuadra contra nada. Sin esta fila los tres casos concurrentes fallan con
		// CajaNoAbiertaException ANTES de tocar el saldo, y lo que el test mide —que dos
		// transacciones no resten la misma plata— no llega a ejercerse.
		//
		// `findAbierta` busca por sede y estado, no por fecha, asi que la fecha de negocio se
		// calcula en la zona de la sede solo para que la fila sea coherente.
		jdbc.update("""
				INSERT INTO jornada_caja (organization_id, consultorio_id, fecha_negocio, moneda,
				                          estado, saldo_inicial, saldo_arqueo, abierta_en,
				                          abierta_por_cuenta_id, created_at, updated_at)
				VALUES (?, ?, ?, 'ARS', 'ABIERTA', 0.00, 0.00, UTC_TIMESTAMP(6), ?,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, consultorioId,
				java.sql.Date.valueOf(java.time.LocalDate.now(java.time.ZoneId.of(ZONA))), cuentaId);


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
				new Object[]{"COBRO-" + sufijo.toUpperCase(), "Servicio " + sufijo},
				new Object[]{"COBRO-" + sufijo.toUpperCase()});

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

		Fixture parcial = new Fixture(organizationId, consultorioId, personaId, 0, sesionId, ofertaId,
				new OperatingActor(cuentaId, false, organizationId, consultorioId));

		return new Fixture(organizationId, consultorioId, personaId,
				insertarObligacion(parcial), sesionId, ofertaId, parcial.actor());
	}

	/** Otra sesion del mismo paciente, para poder devengar una segunda deuda. */
	private Fixture conSesionNueva(Fixture fixture) {
		Long historiaId = jdbc.queryForObject(
				"SELECT historia_clinica_id FROM sesion WHERE id = ?", Long.class, fixture.sesionId());
		jdbc.update("""
				INSERT INTO sesion (organization_id, consultorio_id, historia_clinica_id, oferta_id,
				                    profesional_membership_id, estado, iniciada_en,
				                    iniciada_por_cuenta_id, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 'BORRADOR', UTC_TIMESTAMP(6), ?, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", fixture.organizationId(), fixture.consultorioId(), historiaId, fixture.ofertaId(),
				1L, fixture.actor().accountId());
		return new Fixture(fixture.organizationId(), fixture.consultorioId(), fixture.personaId(),
				fixture.obligacionId(),
				jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class),
				fixture.ofertaId(), fixture.actor());
	}

	private long insertarObligacion(Fixture fixture) {
		jdbc.update("""
				INSERT INTO obligacion (organization_id, consultorio_id, sesion_id, persona_id,
				                        responsable, importe_original, saldo, moneda, estado,
				                        oferta_id, snapshot_nombre, snapshot_precio, devengada_en,
				                        version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'PACIENTE', 8500.00, 8500.00, 'ARS', 'PENDIENTE',
				        ?, 'Sesion sintetica', 8500.00, UTC_TIMESTAMP(6), 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", fixture.organizationId(), fixture.consultorioId(), fixture.sesionId(),
				fixture.personaId(), fixture.ofertaId());
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private long insertar(String insert, String select, Object[] insertArgs, Object[] selectArgs) {
		jdbc.update(insert, insertArgs);
		return jdbc.queryForObject(select, Long.class, selectArgs);
	}
}
