package com.akine.billing;

import com.akine.TestcontainersConfiguration;
import com.akine.billing.application.CobroCommand;
import com.akine.billing.application.CobroPosteriorService;
import com.akine.billing.application.CobroService;
import com.akine.billing.application.CobroView;
import com.akine.billing.application.ImputacionPosteriorCommand;
import com.akine.billing.application.OperatingActor;
import com.akine.billing.application.ReintegroCommand;
import com.akine.billing.domain.MedioDePago;
import com.akine.billing.domain.Obligacion;
import com.akine.billing.domain.exception.CobroAnuladoException;
import com.akine.billing.domain.exception.CobroConReintegrosException;
import com.akine.billing.domain.exception.SaldoAFavorInsuficienteException;
import com.akine.billing.domain.port.ObligacionRepositoryPort;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Anticipos, imputacion posterior, anulacion y reintegro de cobros contra MySQL real (F-3).
 *
 * <h2>Por que estos no pueden ser unitarios</h2>
 *
 * <p>Lo que decide la correctitud es el lock de fila del cobro ({@code SELECT ... FOR UPDATE}) en
 * {@code READ_COMMITTED}, el UPDATE condicional de la deuda, los {@code CHECK} de {@code V69} y que
 * la reversion de caja mueva el saldo de la jornada. Un mock contestaria que se llamo a cada metodo,
 * no que dos transacciones no puedan gastar el mismo anticipo.
 *
 * <p>Cada caso arma su propio tenant: las sedes, deudas y jornadas no se comparten entre metodos, y
 * el orden de ejecucion no importa.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class AnticipoYAnulacionIT {

	private static final String ZONA = "America/Argentina/Cordoba";
	private static final BigDecimal IMPORTE = new BigDecimal("8500.00");

	@Autowired private CobroService cobroService;
	@Autowired private CobroPosteriorService posteriorService;
	@Autowired private ObligacionRepositoryPort obligaciones;
	@Autowired private TransactionTemplate transacciones;
	@Autowired private JdbcTemplate jdbc;

	@Test
	@DisplayName("V69 ejecuto: columnas nuevas de cobro y cobro_imputacion, y la tabla cobro_reintegro")
	void la_migracion_ejecuto() {
		assertThat(jdbc.queryForList("""
				SELECT COLUMN_NAME FROM information_schema.COLUMNS
				 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cobro'
				   AND COLUMN_NAME IN ('saldo_a_favor', 'anulado_por_cuenta_id', 'motivo_anulacion')
				""", String.class))
				.containsExactlyInAnyOrder("saldo_a_favor", "anulado_por_cuenta_id", "motivo_anulacion");
		assertThat(jdbc.queryForList("""
				SELECT COLUMN_NAME FROM information_schema.COLUMNS
				 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cobro_imputacion'
				   AND COLUMN_NAME IN ('imputada_en', 'imputada_por_cuenta_id', 'idempotency_key', 'request_hash')
				""", String.class)).hasSize(4);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM information_schema.TABLES
				 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cobro_reintegro'
				""", Integer.class)).isEqualTo(1);
	}

	@Test
	@DisplayName("anticipo en efectivo: entra a la caja una vez, y la imputacion posterior no la vuelve a mover")
	void anticipo_e_imputacion_posterior() {
		Fixture fixture = crearFixture();

		CobroView anticipo = anticipar(fixture, IMPORTE, null);
		assertThat(anticipo.saldoAFavor()).isEqualByComparingTo(IMPORTE);
		assertThat(anticipo.imputaciones()).isEmpty();
		assertThat(saldoDeCaja(fixture)).as("el anticipo entro al cajon").isEqualByComparingTo(IMPORTE);

		CobroView imputado = posteriorService.imputarSaldoAFavor(fixture.actor(), fixture.consultorioId(),
				anticipo.id(), new ImputacionPosteriorCommand(fixture.obligacionId(), IMPORTE, "imp-" + UUID.randomUUID()));

		assertThat(imputado.saldoAFavor()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(imputado.imputaciones()).singleElement()
				.satisfies(imputacion -> assertThat(imputacion.imputadaEn()).isAfterOrEqualTo(anticipo.cobradoEn()));
		assertThat(saldoDeDeuda(fixture.obligacionId())).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(estadoDeDeuda(fixture.obligacionId())).isEqualTo("PAGADA");
		assertThat(saldoDeCaja(fixture)).as("la imputacion no mueve caja").isEqualByComparingTo(IMPORTE);
		assertThat(movimientosDeCaja(fixture)).as("un solo movimiento: el del anticipo").isEqualTo(1);
	}

	@Test
	@DisplayName("el mismo anticipo imputado a dos deudas a la vez: una entra y la otra recibe saldo-a-favor-insuficiente")
	void imputar_el_mismo_anticipo_dos_veces_a_la_vez() {
		Fixture fixture = crearFixture();
		long otraDeuda = insertarObligacion(conSesionNueva(fixture));
		CobroView anticipo = anticipar(fixture, IMPORTE, null);

		List<Desenlace> desenlaces = enParalelo(List.of(
				() -> posteriorService.imputarSaldoAFavor(fixture.actor(), fixture.consultorioId(), anticipo.id(),
						new ImputacionPosteriorCommand(fixture.obligacionId(), IMPORTE, null)),
				() -> posteriorService.imputarSaldoAFavor(fixture.actor(), fixture.consultorioId(), anticipo.id(),
						new ImputacionPosteriorCommand(otraDeuda, IMPORTE, null))));

		assertThat(desenlaces.stream().filter(d -> !d.fallo()).count())
				.as("exactamente una entra. Desenlaces: %s", desenlaces).isEqualTo(1);
		assertThat(desenlaces.stream().filter(d -> causaEs(d.error(), SaldoAFavorInsuficienteException.class)).count())
				.as("la otra recibe saldo-a-favor-insuficiente. Desenlaces: %s", desenlaces).isEqualTo(1);

		assertThat(saldoAFavor(anticipo.id())).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(List.of(estadoDeDeuda(fixture.obligacionId()), estadoDeDeuda(otraDeuda)))
				.as("una deuda pagada y la otra intacta")
				.containsExactlyInAnyOrder("PAGADA", "PENDIENTE");
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM cobro_imputacion WHERE cobro_id = ?", Integer.class, anticipo.id()))
				.isEqualTo(1);
	}

	@Test
	@DisplayName("la misma clave de imputacion en dos pedidos simultaneos imputa una sola vez")
	void la_misma_clave_a_la_vez_imputa_una_vez() {
		Fixture fixture = crearFixture();
		CobroView anticipo = anticipar(fixture, IMPORTE, null);
		String clave = "imp-" + UUID.randomUUID();
		BigDecimal mitad = new BigDecimal("4250.00");

		List<Desenlace> desenlaces = enParalelo(List.of(
				() -> posteriorService.imputarSaldoAFavor(fixture.actor(), fixture.consultorioId(), anticipo.id(),
						new ImputacionPosteriorCommand(fixture.obligacionId(), mitad, clave)),
				() -> posteriorService.imputarSaldoAFavor(fixture.actor(), fixture.consultorioId(), anticipo.id(),
						new ImputacionPosteriorCommand(fixture.obligacionId(), mitad, clave))));

		assertThat(desenlaces).as("los dos responden bien: el segundo es un reintento. %s", desenlaces)
				.noneMatch(Desenlace::fallo);
		assertThat(saldoAFavor(anticipo.id())).isEqualByComparingTo(mitad);
		assertThat(saldoDeDeuda(fixture.obligacionId())).isEqualByComparingTo(mitad);
	}

	@Test
	@DisplayName("anular un cobro ya imputado devuelve la deuda entera, revierte la caja y no borra nada")
	void anular_un_cobro_imputado() {
		Fixture fixture = crearFixture();
		CobroView cobro = cobroService.registrar(fixture.actor(), fixture.consultorioId(), new CobroCommand(
				fixture.personaId(), IMPORTE,
				List.of(new CobroCommand.MedioPedido(MedioDePago.EFECTIVO, IMPORTE, null)),
				List.of(new CobroCommand.ImputacionPedida(fixture.obligacionId(), IMPORTE)),
				null));
		assertThat(estadoDeDeuda(fixture.obligacionId())).isEqualTo("PAGADA");

		CobroView anulado = posteriorService.anular(
				fixture.actor(), fixture.consultorioId(), cobro.id(), "Cobrado al paciente equivocado");

		assertThat(anulado.estado()).isEqualTo(CobroView.ANULADO);
		assertThat(anulado.comprobanteNumero()).as("el comprobante se conserva").isEqualTo(cobro.comprobanteNumero());
		assertThat(saldoDeDeuda(fixture.obligacionId())).isEqualByComparingTo(IMPORTE);
		assertThat(estadoDeDeuda(fixture.obligacionId())).isEqualTo("PENDIENTE");
		assertThat(saldoDeCaja(fixture)).as("la plata salio del cajon").isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM movimiento_caja
				 WHERE organization_id = ? AND tipo = 'REVERSION_DE_INGRESO'
				""", Integer.class, fixture.organizationId())).isEqualTo(1);
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM cobro_imputacion WHERE cobro_id = ?", Integer.class, cobro.id()))
				.as("la imputacion historica sigue ahi").isEqualTo(1);
		assertThat(jdbc.queryForObject(
				"SELECT motivo_anulacion FROM cobro WHERE id = ?", String.class, cobro.id()))
				.isEqualTo("Cobrado al paciente equivocado");

		assertThatThrownBy(() -> posteriorService.anular(
				fixture.actor(), fixture.consultorioId(), cobro.id(), "otra vez"))
				.isInstanceOf(CobroAnuladoException.class);

		// Y la deuda vuelve a poder cobrarse: el circuito queda como si el cobro no hubiera existido.
		cobroService.registrar(fixture.actor(), fixture.consultorioId(), new CobroCommand(
				fixture.personaId(), IMPORTE,
				List.of(new CobroCommand.MedioPedido(MedioDePago.TRANSFERENCIA, IMPORTE, null)),
				List.of(new CobroCommand.ImputacionPedida(fixture.obligacionId(), IMPORTE)),
				null));
		assertThat(estadoDeDeuda(fixture.obligacionId())).isEqualTo("PAGADA");
	}

	@Test
	@DisplayName("anular e imputar el mismo anticipo a la vez deja un estado consistente, gane quien gane")
	void anular_e_imputar_a_la_vez() {
		Fixture fixture = crearFixture();
		CobroView anticipo = anticipar(fixture, IMPORTE, null);

		List<Desenlace> desenlaces = enParalelo(List.of(
				() -> posteriorService.imputarSaldoAFavor(fixture.actor(), fixture.consultorioId(), anticipo.id(),
						new ImputacionPosteriorCommand(fixture.obligacionId(), IMPORTE, null)),
				() -> posteriorService.anular(fixture.actor(), fixture.consultorioId(), anticipo.id(), "Se arrepintio")));

		assertThat(desenlaces.get(1).fallo()).as("la anulacion siempre puede: %s", desenlaces).isFalse();
		assertThat(saldoAFavor(anticipo.id())).isEqualByComparingTo(BigDecimal.ZERO);
		// Gane quien gane, la deuda termina como si el anticipo nunca hubiera existido: o la
		// imputacion llego primero y la anulacion la devolvio, o llego despues y vio el cobro anulado.
		assertThat(saldoDeDeuda(fixture.obligacionId())).isEqualByComparingTo(IMPORTE);
		assertThat(estadoDeDeuda(fixture.obligacionId())).isEqualTo("PENDIENTE");
		if (desenlaces.get(0).fallo()) {
			assertThat(causaEs(desenlaces.get(0).error(), CobroAnuladoException.class))
					.as("si la imputacion perdio, fue por el cobro anulado: %s", desenlaces).isTrue();
		}
		assertThat(saldoDeCaja(fixture)).isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	@DisplayName("reintegro en efectivo: descuenta el saldo a favor y saca la plata del cajon; despues el cobro no se anula")
	void reintegro_del_saldo_a_favor() {
		Fixture fixture = crearFixture();
		CobroView anticipo = anticipar(fixture, new BigDecimal("5000.00"), null);
		String clave = "rein-" + UUID.randomUUID();
		ReintegroCommand pedido = new ReintegroCommand(
				new BigDecimal("2000.00"), MedioDePago.EFECTIVO, null, "Suspendio el tratamiento", clave);

		var reintegro = posteriorService.reintegrar(fixture.actor(), fixture.consultorioId(), anticipo.id(), pedido);
		var reintento = posteriorService.reintegrar(fixture.actor(), fixture.consultorioId(), anticipo.id(), pedido);

		assertThat(reintento.id()).as("el reintento devuelve el mismo reintegro").isEqualTo(reintegro.id());
		assertThat(reintegro.saldoAFavorRestante()).isEqualByComparingTo("3000.00");
		assertThat(saldoAFavor(anticipo.id())).isEqualByComparingTo("3000.00");
		assertThat(saldoDeCaja(fixture)).isEqualByComparingTo("3000.00");
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM movimiento_caja
				 WHERE organization_id = ? AND tipo = 'EGRESO' AND tipo_origen = 'REINTEGRO'
				   AND referencia_origen = ?
				""", Integer.class, fixture.organizationId(), reintegro.id())).isEqualTo(1);

		assertThatThrownBy(() -> posteriorService.reintegrar(fixture.actor(), fixture.consultorioId(), anticipo.id(),
				new ReintegroCommand(new BigDecimal("3000.01"), MedioDePago.EFECTIVO, null, "de mas", null)))
				.isInstanceOf(SaldoAFavorInsuficienteException.class);
		assertThatThrownBy(() -> posteriorService.anular(
				fixture.actor(), fixture.consultorioId(), anticipo.id(), "ya no"))
				.isInstanceOf(CobroConReintegrosException.class);
	}

	/**
	 * El defecto que destapo F-3, reproducido sin carrera de hilos: la anulacion lee la deuda, otro
	 * cobro commitea en el medio, y la anulacion escribe con la version que leyo.
	 *
	 * <p>Antes del arreglo, {@code descontarSaldo} no avanzaba {@code version}: el
	 * {@code WHERE version = N} de la anulacion pasaba y la deuda quedaba {@code ANULADA} con saldo
	 * cero y un cobro imputado — plata en la caja sin deuda que la explique.
	 */
	@Test
	@DisplayName("una deuda leida antes de un cobro no se puede anular despues: la version avanza con el descuento")
	void la_anulacion_vieja_no_pisa_el_cobro() {
		Fixture fixture = crearFixture();
		BigDecimal mitad = new BigDecimal("4250.00");

		assertThatThrownBy(() -> transacciones.executeWithoutResult(estado -> {
			Obligacion leida = obligaciones
					.findByIdInScope(fixture.organizationId(), fixture.consultorioId(), fixture.obligacionId())
					.orElseThrow();

			// Otro mostrador cobra la mitad y commitea mientras esta transaccion sigue abierta.
			try (ExecutorService otro = Executors.newSingleThreadExecutor()) {
				otro.submit(() -> cobroService.registrar(fixture.actor(), fixture.consultorioId(), new CobroCommand(
						fixture.personaId(), mitad,
						List.of(new CobroCommand.MedioPedido(MedioDePago.TRANSFERENCIA, mitad, null)),
						List.of(new CobroCommand.ImputacionPedida(fixture.obligacionId(), mitad)),
						null))).get(30, TimeUnit.SECONDS);
			} catch (Exception fallo) {
				throw new IllegalStateException(fallo);
			}

			leida.anular("Cargada por error", Instant.now(), fixture.actor().accountId());
			obligaciones.save(leida);
		})).isInstanceOf(OptimisticLockingFailureException.class);

		assertThat(estadoDeDeuda(fixture.obligacionId()))
				.as("la deuda NO quedo anulada con un cobro imputado")
				.isEqualTo("PARCIAL");
		assertThat(saldoDeDeuda(fixture.obligacionId())).isEqualByComparingTo(mitad);
	}

	// =================================================================================

	private CobroView anticipar(Fixture fixture, BigDecimal importe, String clave) {
		return cobroService.registrar(fixture.actor(), fixture.consultorioId(), new CobroCommand(
				fixture.personaId(), importe,
				List.of(new CobroCommand.MedioPedido(MedioDePago.EFECTIVO, importe, null)),
				List.of(), clave, importe, "ARS"));
	}

	private BigDecimal saldoDeDeuda(long obligacionId) {
		return jdbc.queryForObject("SELECT saldo FROM obligacion WHERE id = ?", BigDecimal.class, obligacionId);
	}

	private String estadoDeDeuda(long obligacionId) {
		return jdbc.queryForObject("SELECT estado FROM obligacion WHERE id = ?", String.class, obligacionId);
	}

	private BigDecimal saldoAFavor(long cobroId) {
		return jdbc.queryForObject("SELECT saldo_a_favor FROM cobro WHERE id = ?", BigDecimal.class, cobroId);
	}

	private BigDecimal saldoDeCaja(Fixture fixture) {
		return jdbc.queryForObject("""
				SELECT saldo_arqueo FROM jornada_caja
				 WHERE organization_id = ? AND consultorio_id = ? AND estado = 'ABIERTA'
				""", BigDecimal.class, fixture.organizationId(), fixture.consultorioId());
	}

	private int movimientosDeCaja(Fixture fixture) {
		return jdbc.queryForObject(
				"SELECT COUNT(*) FROM movimiento_caja WHERE organization_id = ?",
				Integer.class, fixture.organizationId());
	}

	/** La barrera es lo que hace real la carrera: sin ella la primera suele terminar antes. */
	private static List<Desenlace> enParalelo(List<Callable<Object>> tareas) {
		CyclicBarrier salida = new CyclicBarrier(tareas.size());
		try (ExecutorService pool = Executors.newFixedThreadPool(tareas.size())) {
			List<Future<Desenlace>> futuros = new ArrayList<>();
			for (Callable<Object> tarea : tareas) {
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
				desenlaces.add(futuro.get(60, TimeUnit.SECONDS));
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

	private record Desenlace(Object resultado, Exception error) {

		boolean fallo() {
			return error != null;
		}

		@Override
		public String toString() {
			return fallo() ? "FALLO(" + error.getClass().getSimpleName() + ": " + error.getMessage() + ")" : "OK";
		}
	}

	// =================================================================================
	// Fixture — mismo criterio que CobroConcurrenteIT: insercion directa
	// =================================================================================

	private record Fixture(
			long organizationId, long consultorioId, long personaId, long obligacionId,
			long sesionId, long ofertaId, OperatingActor actor) {
	}

	private Fixture crearFixture() {
		String sufijo = UUID.randomUUID().toString().substring(0, 8);

		long organizationId = insertar("""
				INSERT INTO organization (name, slug, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM organization WHERE slug = ?",
				new Object[]{"Centro " + sufijo, "anticipo-it-" + sufijo, ZONA},
				new Object[]{"anticipo-it-" + sufijo});

		long consultorioId = insertar("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM consultorio WHERE organization_id = ? AND name = ?",
				new Object[]{organizationId, "Sede " + sufijo, ZONA},
				new Object[]{organizationId, "Sede " + sufijo});

		String email = "anticipo-it-" + sufijo + "@ejemplo.test";
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

		// Caja abierta en cero: todo el efectivo que la prueba ve en el cajon lo puso la prueba.
		jdbc.update("""
				INSERT INTO jornada_caja (organization_id, consultorio_id, fecha_negocio, moneda,
				                          estado, saldo_inicial, saldo_arqueo, abierta_en,
				                          abierta_por_cuenta_id, created_at, updated_at)
				VALUES (?, ?, ?, 'ARS', 'ABIERTA', 0.00, 0.00, UTC_TIMESTAMP(6), ?,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, consultorioId,
				java.time.LocalDate.now(java.time.ZoneId.of(ZONA)).toString(), cuentaId);

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
				new Object[]{"ANT-" + sufijo.toUpperCase(), "Servicio " + sufijo},
				new Object[]{"ANT-" + sufijo.toUpperCase()});

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

		long sesionId = insertarSesion(organizationId, consultorioId, historiaId, ofertaId, cuentaId);

		Fixture parcial = new Fixture(organizationId, consultorioId, personaId, 0, sesionId, ofertaId,
				new OperatingActor(cuentaId, false, organizationId, consultorioId));
		return new Fixture(organizationId, consultorioId, personaId,
				insertarObligacion(parcial), sesionId, ofertaId, parcial.actor());
	}

	/** Otra sesion del mismo paciente: una prestacion devenga su deuda una sola vez. */
	private Fixture conSesionNueva(Fixture fixture) {
		Long historiaId = jdbc.queryForObject(
				"SELECT historia_clinica_id FROM sesion WHERE id = ?", Long.class, fixture.sesionId());
		long sesionId = insertarSesion(fixture.organizationId(), fixture.consultorioId(), historiaId,
				fixture.ofertaId(), fixture.actor().accountId());
		return new Fixture(fixture.organizationId(), fixture.consultorioId(), fixture.personaId(),
				fixture.obligacionId(), sesionId, fixture.ofertaId(), fixture.actor());
	}

	private long insertarSesion(long organizationId, long consultorioId, long historiaId, long ofertaId, long cuentaId) {
		String marca = "ses-" + UUID.randomUUID();
		jdbc.update("""
				INSERT INTO sesion (organization_id, consultorio_id, historia_clinica_id, oferta_id,
				                    profesional_membership_id, estado, iniciada_en,
				                    iniciada_por_cuenta_id, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 'BORRADOR', UTC_TIMESTAMP(6), ?, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, consultorioId, historiaId, ofertaId, 1L, cuentaId);
		// El id mas alto de la historia: cada fixture tiene su propia historia, asi que no hay
		// carrera con otra prueba, y no depende de que LAST_INSERT_ID caiga en la misma conexion.
		return jdbc.queryForObject(
				"SELECT MAX(id) FROM sesion WHERE organization_id = ? AND historia_clinica_id = ?",
				Long.class, organizationId, historiaId);
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
		return jdbc.queryForObject(
				"SELECT id FROM obligacion WHERE organization_id = ? AND sesion_id = ?",
				Long.class, fixture.organizationId(), fixture.sesionId());
	}

	private long insertar(String insert, String select, Object[] insertArgs, Object[] selectArgs) {
		jdbc.update(insert, insertArgs);
		return jdbc.queryForObject(select, Long.class, selectArgs);
	}
}
