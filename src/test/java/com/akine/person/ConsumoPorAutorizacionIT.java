package com.akine.person;

import com.akine.TestcontainersConfiguration;
import com.akine.billing.application.ObligacionService;
import com.akine.encounter.application.SesionService;
import com.akine.encounter.application.SesionView;
import com.akine.encounter.domain.Asistencia;
import com.akine.encounter.domain.CierreDeSesion;
import com.akine.person.application.AlertaDeAutorizacionView;
import com.akine.person.application.AutorizacionAltaCommand;
import com.akine.person.application.AutorizacionService;
import com.akine.person.application.ConsumoDeAutorizacionService;
import com.akine.person.application.OperatingActor;
import com.akine.person.application.ReversionCommand;
import com.akine.person.domain.EstadoAutorizacion;
import com.akine.person.domain.exception.AutorizacionNotAccessibleException;
import com.akine.person.spi.ConsumoARevisar;
import com.akine.person.spi.ConsumoPorSesion;
import com.akine.person.spi.ResultadoDeConsumo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * AKINE C-4 contra MySQL real: DP-12 (una unidad por autorizacion involucrada), DP-13 (alerta al
 * anular la deuda), cobertura vigente y caso al consumir.
 *
 * <p>Los escenarios de DP-12, DP-13 y caso entran por el <b>cierre real de la sesion</b>
 * ({@code SesionService#cerrar}): es lo unico que prueba el cableado entero —{@code encounter} lee
 * los tratamientos y el caso, pregunta a {@code clinical}, {@code person} descuenta y
 * {@code billing} devenga—. Los de idempotencia, cobertura y la rafaga concurrente entran por el
 * servicio de consumo, porque lo que miden es la regla y no el cierre.
 *
 * <p>Todos terminan comparando la suma del ledger con {@code cantidad_consumida}: si alguna vez
 * divergen, nada mas lo detecta.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class ConsumoPorAutorizacionIT {

	private static final String ZONA = "America/Argentina/Cordoba";

	@Autowired private SesionService sesionService;
	@Autowired private ObligacionService obligacionService;
	@Autowired private ConsumoDeAutorizacionService consumo;
	@Autowired private AutorizacionService autorizaciones;
	@Autowired private JdbcTemplate jdbc;

	// =================================================================================
	// DP-12 por el cierre real
	// =================================================================================

	@Test
	@DisplayName("una sesion con practicas de dos autorizaciones consume una unidad de cada una")
	void dos_autorizaciones_una_de_cada_una() {
		Fixture f = crearFixture();
		long kinesio = autorizar(f, f.coberturaId(), f.practica1(), "K-1", 10, 0);
		long fono = autorizar(f, f.coberturaId(), f.practica2(), "F-1", 10, 0);
		long sesion = insertarSesion(f, null);
		tratar(f, sesion, f.practica1());
		tratar(f, sesion, f.practica2());

		cerrar(f, sesion);

		assertThat(consumida(kinesio)).isEqualTo(1);
		assertThat(consumida(fono)).isEqualTo(1);
		assertThat(consumosDeLaSesion(sesion)).isEqualTo(2);
		assertLedgerCoherente(kinesio, fono);
	}

	@Test
	@DisplayName("dos tratamientos de la practica de una misma autorizacion consumen UNA unidad")
	void misma_autorizacion_una_unidad() {
		Fixture f = crearFixture();
		long kinesio = autorizar(f, f.coberturaId(), f.practica1(), "K-2", 10, 0);
		long sesion = insertarSesion(f, null);
		tratar(f, sesion, f.practica1());
		tratar(f, sesion, f.practica1());

		cerrar(f, sesion);

		assertThat(consumida(kinesio)).isEqualTo(1);
		assertThat(consumosDeLaSesion(sesion)).isEqualTo(1);
		assertLedgerCoherente(kinesio);
	}

	// =================================================================================
	// Idempotencia
	// =================================================================================

	@Test
	@DisplayName("el re-disparo del mismo cierre no vuelve a descontar ninguna de las dos")
	void redisparo_idempotente() {
		Fixture f = crearFixture();
		long kinesio = autorizar(f, f.coberturaId(), f.practica1(), "K-3", 10, 0);
		long fono = autorizar(f, f.coberturaId(), f.practica2(), "F-3", 10, 0);
		long sesion = insertarSesion(f, null);
		tratar(f, sesion, f.practica1());
		tratar(f, sesion, f.practica2());
		cerrar(f, sesion);

		// El cierre repetido sale temprano (RN-M14-005) y el re-disparo directo del consumo
		// reconoce lo que ya esta en el ledger.
		cerrar(f, sesion);
		List<ResultadoDeConsumo> otraVez =
				consumir(f, sesion, Set.of(f.practica1(), f.practica2()));

		assertThat(otraVez).extracting(ResultadoDeConsumo::desenlace)
				.containsOnly(ResultadoDeConsumo.YA_CONSUMIDA);
		assertThat(consumida(kinesio)).isEqualTo(1);
		assertThat(consumida(fono)).isEqualTo(1);
		assertLedgerCoherente(kinesio, fono);
	}

	@Test
	@DisplayName("el re-disparo despues de agotar la autorizacion NO gasta otra de la misma practica")
	void el_redisparo_despues_de_agotar_no_consume_otra() {
		// EL DEFECTO QUE C-4 ENCONTRO. La primera autorizacion tiene una sola unidad y vence
		// antes: el primer disparo la gasta y la deja agotada. Sin leer el ledger de la sesion,
		// el re-disparo ya no la ve entre las que habilitan, elige la segunda —misma practica,
		// otra cobertura— y descuenta la misma practica dos veces.
		Fixture f = crearFixture();
		long venceAntes = autorizar(f, f.coberturaId(), f.practica1(), "A-1", 1, 0);
		long venceDespues = autorizar(f, f.otraCoberturaId(), f.practica1(), "A-2", 10, 60);
		long sesion = 880_000L + Math.abs(UUID.randomUUID().hashCode() % 100_000);

		List<ResultadoDeConsumo> primero = consumir(f, sesion, Set.of(f.practica1()));
		List<ResultadoDeConsumo> reintento = consumir(f, sesion, Set.of(f.practica1()));

		assertThat(primero).extracting(ResultadoDeConsumo::autorizacionId)
				.containsExactly(venceAntes);
		assertThat(reintento).extracting(ResultadoDeConsumo::desenlace)
				.containsExactly(ResultadoDeConsumo.YA_CONSUMIDA);
		assertThat(consumida(venceAntes)).isEqualTo(1);
		assertThat(consumida(venceDespues))
				.as("la otra autorizacion de la misma practica NO se toca")
				.isZero();
		assertLedgerCoherente(venceAntes, venceDespues);
	}

	// =================================================================================
	// DP-13 por el cierre y la anulacion reales
	// =================================================================================

	@Test
	@DisplayName("anular la obligacion deja la alerta, no toca el saldo, y revertir la resuelve")
	void anular_la_obligacion_alerta_y_no_toca_el_saldo() {
		Fixture f = crearFixture();
		long kinesio = autorizar(f, f.coberturaId(), f.practica1(), "K-4", 10, 0);
		long sesion = insertarSesion(f, null);
		tratar(f, sesion, f.practica1());
		cerrar(f, sesion);

		Map<String, Object> deuda = jdbc.queryForMap(
				"SELECT id, version FROM obligacion WHERE sesion_id = ?", sesion);
		long obligacionId = ((Number) deuda.get("id")).longValue();
		obligacionService.anular(f.billingAdmin(), f.consultorioId(), obligacionId,
				"Cortesia del centro", ((Number) deuda.get("version")).longValue());

		assertThat(consumida(kinesio))
				.as("anular la deuda NO devuelve la unidad (DP-13)")
				.isEqualTo(1);
		List<AlertaDeAutorizacionView> alertas = consumo.alertas(f.admin(), kinesio);
		assertThat(alertas).hasSize(1);
		AlertaDeAutorizacionView alerta = alertas.get(0);
		assertThat(alerta.tipo()).isEqualTo("CONSUMO_A_REVISAR");
		assertThat(alerta.sesionId()).isEqualTo(sesion);
		assertThat(alerta.obligacionId()).isEqualTo(obligacionId);
		assertThat(alerta.motivoOrigen()).isEqualTo("Cortesia del centro");
		assertThat(alerta.pendiente()).isTrue();
		assertThat(consumo.saldo(f.admin(), kinesio, null).consumosARevisar()).isEqualTo(1);

		// Idempotente: el mismo hecho otra vez —F-4 va a anular dos obligaciones por sesion— no
		// repite la alerta.
		consumo.consumoARevisar(new ConsumoARevisar(
				f.organizationId(), sesion, obligacionId, "Otra", Instant.now(), f.adminCuentaId()));
		assertThat(consumo.alertas(f.admin(), kinesio)).hasSize(1);

		// La reversion manual (RF-M17-005) es la que devuelve la unidad y resuelve la alerta.
		consumo.revertir(f.admin(), kinesio,
				new ReversionCommand(alerta.movimientoId(), "La sesion fue una cortesia"));

		AlertaDeAutorizacionView resuelta = consumo.alertas(f.admin(), kinesio).get(0);
		assertThat(resuelta.pendiente()).isFalse();
		assertThat(resuelta.resolucion()).isEqualTo("REVERTIDO");
		assertThat(consumo.saldo(f.admin(), kinesio, null).consumosARevisar()).isZero();
		assertThat(consumida(kinesio)).isZero();
		assertLedgerCoherente(kinesio);
	}

	@Test
	@DisplayName("las alertas de una autorizacion de otro tenant responden 404")
	void alertas_cross_tenant() {
		Fixture a = crearFixture();
		Fixture b = crearFixture();
		long kinesio = autorizar(a, a.coberturaId(), a.practica1(), "T-1", 10, 0);

		assertThatThrownBy(() -> consumo.alertas(b.admin(), kinesio))
				.isInstanceOf(AutorizacionNotAccessibleException.class);
	}

	// =================================================================================
	// La ultima unidad, disputada
	// =================================================================================

	@Test
	@DisplayName("dos cierres concurrentes por la ultima unidad: ninguno falla y la unidad se gasta una vez")
	void la_ultima_unidad_disputada_por_dos_cierres() {
		Fixture f = crearFixture();
		long ultima = autorizar(f, f.coberturaId(), f.practica1(), "U-1", 1, 0);
		long holgada = autorizar(f, f.coberturaId(), f.practica2(), "U-2", 10, 0);
		long sesionA = insertarSesion(f, null);
		long sesionB = insertarSesion(f, null);
		for (long sesion : List.of(sesionA, sesionB)) {
			tratar(f, sesion, f.practica1());
			tratar(f, sesion, f.practica2());
		}

		List<Desenlace<SesionView>> desenlaces = enParalelo(List.of(
				() -> cerrar(f, sesionA),
				() -> cerrar(f, sesionB)));

		assertThat(desenlaces).as("el cierre no falla por el consumo (DP-06): %s", desenlaces)
				.noneMatch(Desenlace::fallo);
		assertThat(consumida(ultima)).as("la ultima unidad se gasta UNA vez").isEqualTo(1);
		assertThat(consumida(holgada)).as("la otra autorizacion se consume en las dos").isEqualTo(2);
		assertLedgerCoherente(ultima, holgada);
	}

	@Test
	@DisplayName("rafaga de cuatro consumos sobre dos autorizaciones: sin deadlock y sin pasarse")
	void rafaga_sobre_dos_autorizaciones() {
		// Cada consumo toca LAS DOS filas. Si el orden de los UPDATE no fuera el mismo en todas
		// las transacciones, dos de ellas podrian bloquearse en cruz.
		Fixture f = crearFixture();
		long ultima = autorizar(f, f.coberturaId(), f.practica1(), "R-1", 1, 0);
		long holgada = autorizar(f, f.coberturaId(), f.practica2(), "R-2", 10, 0);

		List<Callable<List<ResultadoDeConsumo>>> rafaga = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			long sesion = 890_000L + i + Math.abs(UUID.randomUUID().hashCode() % 100_000) * 10L;
			rafaga.add(() -> consumir(f, sesion, Set.of(f.practica1(), f.practica2())));
		}
		List<Desenlace<List<ResultadoDeConsumo>>> desenlaces = enParalelo(rafaga);

		assertThat(desenlaces).as("%s", desenlaces).noneMatch(Desenlace::fallo);
		assertThat(consumida(ultima)).isEqualTo(1);
		assertThat(consumida(holgada)).isEqualTo(4);
		assertThat(desenlaces.stream()
				.flatMap(d -> d.valor().stream())
				.filter(r -> ResultadoDeConsumo.SIN_SALDO.equals(r.desenlace()))
				.count())
				.as("tres pierden la ultima unidad y lo reciben como desenlace")
				.isEqualTo(3);
		assertLedgerCoherente(ultima, holgada);
	}

	// =================================================================================
	// Cobertura y caso
	// =================================================================================

	@Test
	@DisplayName("con la cobertura vencida la autorizacion no se consume y no se lanza")
	void cobertura_vencida_no_consume() {
		Fixture f = crearFixture();
		long kinesio = autorizar(f, f.coberturaId(), f.practica1(), "C-1", 10, 0);
		jdbc.update("UPDATE cobertura_paciente SET vigencia_hasta = ? WHERE id = ?",
				hoy().minusDays(1), f.coberturaId());

		List<ResultadoDeConsumo> resultado = consumir(f, 870_001L, Set.of(f.practica1()));

		assertThat(resultado).extracting(ResultadoDeConsumo::desenlace)
				.containsExactly(ResultadoDeConsumo.SIN_COBERTURA_VIGENTE);
		assertThat(consumida(kinesio)).isZero();
	}

	@Test
	@DisplayName("una sesion de un caso no gasta la autorizacion atada al plan de OTRO caso")
	void autorizacion_de_otro_caso_no_se_consume() {
		Fixture f = crearFixture();
		// La atada al otro caso vence ANTES: sin la regla del caso, seria la elegida.
		long delOtroCaso = autorizar(f, f.coberturaId(), f.practica1(), "O-1", 10, 0);
		long libre = autorizar(f, f.otraCoberturaId(), f.practica1(), "O-2", 10, 60);
		long casoDeLaSesion = insertarCaso(f, 1);
		long otroCaso = insertarCaso(f, 2);
		vincularAPlan(f, otroCaso, delOtroCaso);

		long sesion = insertarSesion(f, casoDeLaSesion);
		tratar(f, sesion, f.practica1());
		cerrar(f, sesion);

		assertThat(consumida(delOtroCaso)).as("es de otro caso (RF-M17-007)").isZero();
		assertThat(consumida(libre)).isEqualTo(1);

		// Control: una sesion DEL otro caso si la gasta.
		long sesionDelOtro = insertarSesion(f, otroCaso);
		tratar(f, sesionDelOtro, f.practica1());
		cerrar(f, sesionDelOtro);
		assertThat(consumida(delOtroCaso)).isEqualTo(1);
		assertLedgerCoherente(delOtroCaso, libre);
	}

	// =================================================================================
	// Operaciones
	// =================================================================================

	private SesionView cerrar(Fixture f, long sesionId) {
		SesionView actual = sesionService.ver(f.profesional(), f.consultorioId(), sesionId);
		return sesionService.cerrar(f.profesional(), f.consultorioId(), sesionId,
				new CierreDeSesion(Asistencia.PRESENTE, "Terapia manual", null, null, null, null),
				actual.version());
	}

	private List<ResultadoDeConsumo> consumir(Fixture f, long sesionId, Set<Long> practicas) {
		return consumo.consumirPorSesion(new ConsumoPorSesion(
				f.organizationId(), f.personaId(), f.consultorioId(), sesionId, hoy(), 1,
				f.adminCuentaId(), practicas));
	}

	private long autorizar(
			Fixture f, long coberturaId, long practicaId, String numero, int cantidad,
			int diasExtra) {

		return autorizaciones.registrar(f.admin(), f.personaId(), new AutorizacionAltaCommand(
				coberturaId, practicaId, null, numero, EstadoAutorizacion.APROBADA, cantidad,
				hoy().minusDays(30), hoy().plusDays(120 + diasExtra), null)).id();
	}

	private static LocalDate hoy() {
		return LocalDate.now(ZoneId.of(ZONA));
	}

	private int consumida(long autorizacionId) {
		return jdbc.queryForObject(
				"SELECT cantidad_consumida FROM autorizacion WHERE id = ?", Integer.class,
				autorizacionId);
	}

	private int consumosDeLaSesion(long sesionId) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM autorizacion_movimiento
				 WHERE tipo = 'CONSUMO' AND tipo_origen = 'SESION' AND referencia_origen = ?
				""", Integer.class, sesionId);
	}

	/** El ledger es la fuente de verdad y la columna el saldo materializado: tienen que coincidir. */
	private void assertLedgerCoherente(long... autorizacionIds) {
		for (long id : autorizacionIds) {
			Integer segunElLedger = jdbc.queryForObject("""
					SELECT COALESCE(SUM(CASE WHEN tipo IN ('CONSUMO', 'RESERVA')
					                         THEN cantidad ELSE -cantidad END), 0)
					  FROM autorizacion_movimiento WHERE autorizacion_id = ?
					""", Integer.class, id);
			assertThat(segunElLedger)
					.as("ledger contra cantidad_consumida de la autorizacion %s", id)
					.isEqualTo(consumida(id));
		}
	}

	// =================================================================================
	// Concurrencia
	// =================================================================================

	private static <T> List<Desenlace<T>> enParalelo(List<Callable<T>> tareas) {
		CyclicBarrier salida = new CyclicBarrier(tareas.size());
		try (ExecutorService pool = Executors.newFixedThreadPool(tareas.size())) {
			List<Future<Desenlace<T>>> futuros = new ArrayList<>();
			for (Callable<T> tarea : tareas) {
				futuros.add(pool.submit(() -> {
					salida.await(10, TimeUnit.SECONDS);
					try {
						return new Desenlace<>(tarea.call(), null);
					} catch (Exception error) {
						return new Desenlace<T>(null, error);
					}
				}));
			}
			List<Desenlace<T>> desenlaces = new ArrayList<>();
			for (Future<Desenlace<T>> futuro : futuros) {
				desenlaces.add(futuro.get(60, TimeUnit.SECONDS));
			}
			return desenlaces;
		} catch (Exception error) {
			throw new IllegalStateException("La ejecucion concurrente no pudo completarse", error);
		}
	}

	private record Desenlace<T>(T valor, Exception error) {

		boolean fallo() {
			return error != null;
		}

		@Override
		public String toString() {
			return fallo()
					? "FALLO(" + error.getClass().getSimpleName() + ": " + error.getMessage() + ")"
					: "OK(" + valor + ")";
		}
	}

	// =================================================================================
	// Fixture — todo sintetico
	// =================================================================================

	private record Fixture(
			long organizationId,
			long consultorioId,
			long profesionalCuentaId,
			long profesionalMembershipId,
			long adminCuentaId,
			long personaId,
			long historiaClinicaId,
			long servicioId,
			long ofertaId,
			long coberturaId,
			long otraCoberturaId,
			long practica1,
			long practica2) {

		com.akine.encounter.application.OperatingActor profesional() {
			return new com.akine.encounter.application.OperatingActor(
					profesionalCuentaId, false, organizationId, consultorioId);
		}

		OperatingActor admin() {
			return new OperatingActor(adminCuentaId, false, organizationId, consultorioId);
		}

		com.akine.billing.application.OperatingActor billingAdmin() {
			return new com.akine.billing.application.OperatingActor(
					adminCuentaId, false, organizationId, consultorioId);
		}
	}

	private Fixture crearFixture() {
		String s = UUID.randomUUID().toString().substring(0, 10);

		long org = insertar("""
				INSERT INTO organization (name, slug, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{"Centro " + s, "c4-it-" + s, ZONA});
		long sede = insertar("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{org, "Sede " + s, ZONA});

		long profesional = cuenta("c4-pro-" + s);
		long membership = membership(org, sede, profesional, "PROFESIONAL");
		long admin = cuenta("c4-adm-" + s);
		// paciente:manage para registrar y revertir, cobro:register para anular.
		membership(org, sede, admin, "ADMINISTRATIVO");

		String apellido = "Paciente" + s;
		long persona = insertar("""
				INSERT INTO persona (organization_id, apellido, nombre, apellido_clave, nombre_clave,
				                     active, version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', ?, 'SINTETICO', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{org, apellido, apellido.toUpperCase()});
		jdbc.update("""
				INSERT INTO perfil_paciente (organization_id, persona_id, activado_en, activado_por,
				                             active, version, created_at, updated_at)
				VALUES (?, ?, UTC_TIMESTAMP(6), ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, persona, admin);
		long historia = insertar("""
				INSERT INTO historia_clinica (organization_id, persona_id, abierta_en, abierta_por,
				                              active, version, created_at, updated_at)
				VALUES (?, ?, UTC_TIMESTAMP(6), ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{org, persona, profesional});

		long servicio = insertar("""
				INSERT INTO servicio (codigo, nombre, naturaleza, modalidad_default,
				                      requiere_caso_clinico_default, genera_registro_clinico_default,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, 'CLINICO', 'INDIVIDUAL', 0, 0, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{"C4-" + s.toUpperCase(), "Servicio " + s});
		long oferta = insertar("""
				INSERT INTO oferta_servicio_consultorio
				       (organization_id, consultorio_id, servicio_id, nombre_comercial, modalidad,
				        duracion_minutos, capacidad, precio_base, moneda, admite_obra_social,
				        requiere_caso_clinico, genera_registro_clinico, requiere_profesional,
				        requiere_espacio, vigencia_desde, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'INDIVIDUAL', 60, 1, 8500.00, 'ARS', 0, 0, 0, 1, 0,
				        DATE_SUB(CURDATE(), INTERVAL 5 YEAR), 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{org, sede, servicio, "Oferta " + s});

		long financiador = insertar("""
				INSERT INTO financiador (organization_id, codigo, nombre, tipo, active, version,
				                         created_at, updated_at)
				VALUES (?, ?, ?, 'PREPAGA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{org, "OS-" + s, "Financiador " + s});
		long plan = insertar("""
				INSERT INTO plan_cobertura (organization_id, financiador_id, codigo, nombre,
				                            vigencia_desde, requiere_autorizacion,
				                            requiere_credencial, active, version,
				                            created_at, updated_at)
				VALUES (?, ?, ?, ?, '2020-01-01', 1, 0, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{org, financiador, "P-" + s, "Plan " + s});
		long cobertura = cobertura(org, persona, financiador, plan, s, true);
		long otraCobertura = cobertura(org, persona, financiador, plan, s + "b", false);

		long especialidad = insertar("""
				INSERT INTO especialidad (organization_id, codigo, name, valid_from, active,
				                          version, created_at, updated_at)
				VALUES (?, ?, ?, '2020-01-01 00:00:00.000000', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{org, "E-" + s, "Especialidad " + s});
		long practica1 = practica(org, especialidad, "P1-" + s);
		long practica2 = practica(org, especialidad, "P2-" + s);

		return new Fixture(org, sede, profesional, membership, admin, persona, historia, servicio,
				oferta, cobertura, otraCobertura, practica1, practica2);
	}

	private long cuenta(String prefijo) {
		String email = prefijo + "@ejemplo.test";
		return insertar("""
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado, active,
				                    version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', 'DePrueba', 'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{email, email});
	}

	private long membership(long org, long sede, long cuenta, String rol) {
		return insertar("""
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, estado, active, version,
				                        created_at, updated_at)
				VALUES (?, ?, ?, ?, 0, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 YEAR),
				        'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{org, sede, cuenta, rol});
	}

	/** La copia congelada va ENTERA: {@code ck_cobertura_referencia_coherente} rechaza media. */
	private long cobertura(long org, long persona, long financiador, long plan, String s,
			boolean principal) {
		return insertar("""
				INSERT INTO cobertura_paciente (
				        organization_id, persona_id, tipo,
				        financiador_id, financiador_codigo, financiador_nombre, financiador_tipo,
				        plan_id, plan_codigo, plan_nombre,
				        requeria_autorizacion, requeria_credencial, referencia_capturada_el,
				        numero_afiliado, vigencia_desde, principal, active, version,
				        created_at, updated_at)
				VALUES (?, ?, 'FINANCIADA', ?, ?, ?, 'PREPAGA', ?, ?, ?, 1, 0, UTC_TIMESTAMP(6),
				        ?, '2020-01-01', ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{org, persona, financiador, "OS-" + s, "Financiador OS-" + s,
				plan, "P-" + s, "Plan P-" + s, "AF-" + s, principal ? 1 : 0});
	}

	private long practica(long org, long especialidad, String codigo) {
		return insertar("""
				INSERT INTO practica (organization_id, especialidad_id, codigo, name, valid_from,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, '2020-01-01 00:00:00.000000', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{org, especialidad, codigo, "Practica " + codigo});
	}

	private long insertarSesion(Fixture f, Long casoId) {
		return insertar("""
				INSERT INTO sesion (organization_id, consultorio_id, historia_clinica_id, oferta_id,
				                    caso_id, profesional_membership_id, estado, iniciada_en,
				                    iniciada_por_cuenta_id, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, 'BORRADOR', UTC_TIMESTAMP(6), ?, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{f.organizationId(), f.consultorioId(), f.historiaClinicaId(),
				f.ofertaId(), casoId, f.profesionalMembershipId(), f.profesionalCuentaId()});
	}

	private void tratar(Fixture f, long sesionId, long practicaId) {
		Integer orden = jdbc.queryForObject(
				"SELECT COALESCE(MAX(orden), 0) + 1 FROM tratamiento_realizado WHERE sesion_id = ?",
				Integer.class, sesionId);
		jdbc.update("""
				INSERT INTO tratamiento_realizado (organization_id, consultorio_id, sesion_id, orden,
				        practica_id, practica_codigo, practica_nombre, profesional_membership_id,
				        registrado_en, registrado_por_cuenta_id, active, version,
				        created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 'COD', 'Practica sintetica', ?, UTC_TIMESTAMP(6), ?, 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", f.organizationId(), f.consultorioId(), sesionId, orden, practicaId,
				f.profesionalMembershipId(), f.profesionalCuentaId());
	}

	private long insertarCaso(Fixture f, int numero) {
		return insertar("""
				INSERT INTO caso_clinico (organization_id, historia_clinica_id, numero_caso, oferta_id,
				                          oferta_consultorio_id, diagnostico_presuntivo, abierto_en,
				                          abierto_por, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 'Lumbalgia sintetica', UTC_TIMESTAMP(6), ?,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{f.organizationId(), f.historiaClinicaId(), numero, f.ofertaId(),
				f.consultorioId(), f.profesionalCuentaId()});
	}

	/** Un plan del caso con un item atado a la autorizacion (RF-M11-007). */
	private void vincularAPlan(Fixture f, long casoId, long autorizacionId) {
		long plan = insertar("""
				INSERT INTO plan_tratamiento (organization_id, caso_clinico_id, numero_plan,
				                              creado_en, creado_por, created_at, updated_at)
				VALUES (?, ?, 1, UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{f.organizationId(), casoId, f.profesionalCuentaId()});
		long version = insertar("""
				INSERT INTO plan_tratamiento_version (organization_id, plan_tratamiento_id,
				        numero_version, objetivos, registrada_en, registrada_por,
				        created_at, updated_at)
				VALUES (?, ?, 1, 'Objetivo sintetico', UTC_TIMESTAMP(6), ?,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{f.organizationId(), plan, f.profesionalCuentaId()});
		jdbc.update("""
				INSERT INTO plan_item (organization_id, plan_tratamiento_version_id, oferta_id,
				        oferta_consultorio_id, oferta_nombre, servicio_id, cantidad_planificada,
				        origen_autorizacion, autorizacion_id, cantidad_autorizada,
				        created_at, updated_at)
				VALUES (?, ?, ?, ?, 'Oferta sintetica', ?, 10, 'AUTORIZACION', ?, 10,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", f.organizationId(), version, f.ofertaId(), f.consultorioId(), f.servicioId(),
				autorizacionId);
	}

	private long insertar(String insert, Object[] args) {
		jdbc.update(insert, args);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}
}
