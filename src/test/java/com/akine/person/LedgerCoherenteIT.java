package com.akine.person;

import com.akine.TestcontainersConfiguration;
import com.akine.person.application.AutorizacionAltaCommand;
import com.akine.person.application.AutorizacionEdicionCommand;
import com.akine.person.application.AutorizacionService;
import com.akine.person.application.AutorizacionView;
import com.akine.person.application.ConsumoDeAutorizacionService;
import com.akine.person.application.MovimientoView;
import com.akine.person.application.OperatingActor;
import com.akine.person.application.ReversionCommand;
import com.akine.person.application.SaldoDeAutorizacionView;
import com.akine.person.domain.EstadoAutorizacion;
import com.akine.person.domain.exception.AutorizacionNotAccessibleException;
import com.akine.person.spi.ConsumoPorSesion;
import com.akine.person.spi.ResultadoDeConsumo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

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
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La suma del ledger contra {@code autorizacion.cantidad_consumida}, despues de N movimientos.
 *
 * <p><b>ESCRITO EL 19/09/2026 Y NUNCA EJECUTADO: Docker no estaba disponible.</b> El motor de
 * contenedores de esta maquina no arranca sin elevacion, asi que esta clase compila y no corrio.
 * Nada de lo que afirma esta verificado todavia.
 *
 * <h2>Que escenario prueba, y por que esta clase existe</h2>
 *
 * <p>El design challenge de AKINE-04.05 lo nombro como <b>la quinta cosa, "la que va a doler"</b>,
 * y la dejo sin implementar:
 *
 * <blockquote>Si el {@code UPDATE} de saldo y el {@code INSERT} del movimiento divergen alguna vez
 * —por un camino que escriba uno y no el otro—, <b>nada lo detecta</b>, porque no hay nadie
 * recalculando el saldo desde el ledger. Mitigacion posible y no implementada: un test de
 * integracion que, despues de N movimientos, compare la suma del ledger contra la columna. Queda
 * escrito como lo primero a cubrir cuando haya Docker.</blockquote>
 *
 * <p>Esta clase es esa mitigacion. La cuenta es una sola y esta escrita una sola vez en el sistema
 * —en {@code SaldoDeAutorizacionView}— porque la {@code cantidad} del ledger es <b>siempre
 * positiva</b> y el signo lo da el {@code tipo}:
 *
 * <pre>
 *   SUM(CASE WHEN tipo IN ('CONSUMO','RESERVA') THEN cantidad ELSE -cantidad END)
 * </pre>
 *
 * <h2>Por que no puede ser un unitario</h2>
 *
 * <p>Lo que se afirma es que <b>dos escrituras distintas</b> —un {@code UPDATE} nativo sobre
 * {@code autorizacion} y un {@code INSERT} por JPA sobre {@code autorizacion_movimiento}— quedan
 * coherentes <i>despues del commit</i>, incluso cuando varias transacciones se pisan. Con dobles
 * las dos escrituras son dos llamadas a dos mocks y la coherencia es una afirmacion sobre el
 * codigo, no sobre los datos. Y el modo de falla que se persigue es justamente el que no lanza
 * ninguna excepcion: una queda y la otra no.
 *
 * <h2>El control negativo es la mitad que importa</h2>
 *
 * <p>Un test de coherencia que solo mira el camino feliz siempre esta en verde y no sirve de nada.
 * {@link #la_divergencia_inyectada_se_detecta()} <b>rompe la coherencia a mano</b> —un
 * {@code UPDATE} directo a la columna, que es exactamente "un camino que escribe uno y no el
 * otro"— y exige que la cuenta lo note y que {@code coherente} pase a {@code false}. Sin ese
 * escenario, esta clase no tendria dientes.
 *
 * <h2>Lo que esta clase NO hace, y es deliberado</h2>
 *
 * <p><b>No corrige la divergencia.</b> El registro de la etapa lo deja fijado: una mutacion
 * escondida en un {@code GET} taparia el sintoma. {@code GET /saldo} <i>informa</i> las dos
 * cuentas; reconciliar es una decision de producto que todavia no se tomo.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class LedgerCoherenteIT {

	private static final String ZONA = "America/Argentina/Cordoba";

	private static final LocalDate DESDE = LocalDate.of(2027, 1, 1);
	private static final LocalDate HASTA = LocalDate.of(2027, 12, 31);
	private static final LocalDate DIA_DE_LA_ATENCION = LocalDate.of(2027, 6, 15);

	private static final AtomicLong SESIONES = new AtomicLong(880000L);

	@Autowired private ConsumoDeAutorizacionService consumo;
	@Autowired private AutorizacionService autorizaciones;
	@Autowired private JdbcTemplate jdbc;

	// =================================================================================
	// La coherencia despues de N movimientos mezclados
	// =================================================================================

	@Test
	@DisplayName("despues de cinco consumos y dos reversiones, la suma del ledger es la columna")
	void la_suma_del_ledger_iguala_la_columna() {
		// Cinco consumos y dos reversiones intercaladas: es la secuencia mas corta que ejerce los
		// dos signos, deja el saldo en un valor que no es ni cero ni el total, y pasa por el
		// UPDATE directo y por el inverso. Si la columna y el ledger se escribieran en
		// transacciones distintas —la tentacion de resolver el choque de idempotencia con
		// REQUIRES_NEW— este es el escenario que lo destapa.
		Fixture fixture = crearFixture();
		long autorizacionId = autorizar(fixture, "L-1", 10);

		List<Long> movimientos = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			ResultadoDeConsumo resultado = consumirSesion(fixture, siguienteSesion());
			assertThat(resultado.descontoEfectivo()).isTrue();
			movimientos.add(resultado.movimientoId());
		}
		assertThat(consumida(autorizacionId)).isEqualTo(5);

		revertir(fixture, autorizacionId, movimientos.get(1), "Se cargo dos veces la misma sesion");
		revertir(fixture, autorizacionId, movimientos.get(3), "El paciente no asistio");

		assertThat(consumida(autorizacionId))
				.as("cinco consumidas menos dos devueltas")
				.isEqualTo(3);
		exigirCoherencia(fixture, autorizacionId, 3);
		assertThat(filasDelLedger(autorizacionId))
				.as("siete filas: la reversion compensa y NO borra (regla maestra 10)")
				.isEqualTo(7);
	}

	@Test
	@DisplayName("despues de una rafaga concurrente mezclada, las dos cuentas siguen coincidiendo")
	void la_coherencia_sobrevive_a_la_concurrencia() {
		// El modo de falla que se persigue no lanza nada: una escritura queda y la otra no. Si
		// existiera, es bajo concurrencia donde aparece —una transaccion que revienta despues del
		// UPDATE y antes del INSERT, o al reves—. Ocho consumos simultaneos sobre una autorizacion
		// de seis dejan dos perdedores, que es la situacion en la que el UPDATE condicional
		// devuelve cero y el servicio tiene que salir SIN escribir fila.
		Fixture fixture = crearFixture();
		long autorizacionId = autorizar(fixture, "L-2", 6);

		List<Callable<ResultadoDeConsumo>> rafaga = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			rafaga.add(() -> consumirSesion(fixture, siguienteSesion()));
		}
		List<Desenlace<ResultadoDeConsumo>> desenlaces = enParalelo(rafaga);

		assertThat(desenlaces.stream().filter(Desenlace::fallo).toList())
				.as("ninguno falla: sin saldo es un desenlace. Desenlaces: %s", desenlaces)
				.isEmpty();
		assertThat(consumida(autorizacionId))
				.as("seis entran y dos no. Desenlaces: %s", desenlaces)
				.isEqualTo(6);

		exigirCoherencia(fixture, autorizacionId, 6);
		assertThat(filasDelLedger(autorizacionId))
				.as("exactamente seis filas: los dos perdedores no dejan rastro en el ledger, "
						+ "que es lo que mantiene la cuenta igual a la columna")
				.isEqualTo(6);
	}

	@Test
	@DisplayName("un consumo sin saldo no mueve la columna ni escribe fila")
	void el_consumo_sin_saldo_no_escribe_nada() {
		// Es la mitad silenciosa de la coherencia. Si el servicio escribiera el movimiento
		// igual —"para dejar constancia del intento"— el ledger sumaria mas que la columna y
		// nadie se enteraria: el cierre responde 200 en los dos casos.
		Fixture fixture = crearFixture();
		long autorizacionId = autorizar(fixture, "L-3", 1);
		consumirSesion(fixture, siguienteSesion());

		ResultadoDeConsumo sinSaldo = consumirSesion(fixture, siguienteSesion());

		assertThat(sinSaldo.desenlace()).isEqualTo("SIN_SALDO");
		assertThat(sinSaldo.movimientoId())
				.as("no hay movimiento que devolver porque no se escribio ninguno")
				.isNull();
		assertThat(consumida(autorizacionId)).isEqualTo(1);
		assertThat(filasDelLedger(autorizacionId)).isEqualTo(1);
		exigirCoherencia(fixture, autorizacionId, 1);
	}

	@Test
	@DisplayName("toda fila del ledger tiene cantidad positiva: el signo lo da el tipo")
	void ninguna_fila_lleva_el_signo_en_el_numero() {
		// Es el invariante sobre el que se apoya la cuenta entera. Con cantidades negativas, todo
		// lector tiene que saber el signo de cada tipo para sumar, y el primero que se olvide
		// produce un saldo que nadie entiende. `ck_movimiento_cantidad_positiva` lo hace cumplir
		// en la base; aca se verifica que el camino real lo respete tambien al revertir.
		Fixture fixture = crearFixture();
		long autorizacionId = autorizar(fixture, "L-4", 4);
		ResultadoDeConsumo consumida = consumirSesion(fixture, siguienteSesion());
		MovimientoView reversion =
				revertir(fixture, autorizacionId, consumida.movimientoId(), "Error de carga");

		assertThat(reversion.cantidad())
				.as("la REVERSION guarda 1, no -1")
				.isEqualTo(1);
		assertThat(reversion.efectoSobreElSaldo())
				.as("y el efecto derivado si es negativo: se calcula, no se guarda")
				.isEqualTo(-1);

		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM autorizacion_movimiento
				 WHERE autorizacion_id = ? AND cantidad <= 0
				""", Integer.class, autorizacionId))
				.as("ninguna fila con cantidad no positiva")
				.isZero();
	}

	@Test
	@DisplayName("una edicion administrativa solapada con un cierre no puede pisar el consumo")
	void la_edicion_concurrente_no_reescribe_el_consumo() {
		// ESTE ES EL CAMINO CONCRETO DE DIVERGENCIA, y puede ser un defecto de produccion. No se
		// corrige desde `src/test`: queda anotado.
		//
		//   * `AutorizacionRepository#descontarSaldo` es un UPDATE NATIVO: mueve
		//     `cantidad_consumida` sin pasar por la sesion de JPA y SIN tocar `@Version`.
		//   * `person.domain.Autorizacion` NO declara `@DynamicUpdate`, asi que el flush de
		//     CUALQUIER edicion emite un UPDATE con TODAS las columnas — incluida
		//     `cantidad_consumida`, con el valor que la entidad leyo antes del consumo.
		//   * El `WHERE version = N` pasa igual, porque el consumo no movio la version.
		//
		// Resultado: la columna vuelve al valor viejo y el movimiento queda. El ledger suma una
		// unidad que la columna ya no tiene, y NADA LO DETECTA — que es exactamente lo que el
		// challenge de 04.05 declaro como "la quinta cosa, la que va a doler".
		//
		// SECUENCIALMENTE NO FALLA: hace falta que la edicion lea la autorizacion ANTES del
		// consumo y flushee DESPUES. Por eso se corren varias rondas con barrera de salida.
		// Que una corrida quede en verde NO prueba que el defecto no este: prueba que esa vez no
		// se dio el solapamiento.
		Fixture fixture = crearFixture();
		String numero = "L-EDIT";
		long autorizacionId = autorizar(fixture, numero, 500);

		for (int ronda = 0; ronda < 8; ronda++) {
			long version = versionDe(autorizacionId);
			List<Desenlace<Object>> desenlaces = enParalelo(List.<Callable<Object>>of(
					() -> consumirSesion(fixture, siguienteSesion()),
					() -> autorizaciones.editar(fixture.actor(), fixture.personaId(),
							autorizacionId, new AutorizacionEdicionCommand(
									numero, null, 500, DESDE, HASTA,
									"Observacion sintetica", version))));

			assertThat(sumaDelLedger(autorizacionId))
					.as("ronda %d: la columna no puede quedar por debajo de lo que el ledger "
							+ "afirma. Si falla, el flush de la edicion piso el consumo. "
							+ "Desenlaces: %s", ronda, desenlaces)
					.isEqualTo(consumida(autorizacionId));
		}
	}

	// =================================================================================
	// El control negativo: que la cuenta sepa decir que NO
	// =================================================================================

	@Test
	@DisplayName("una divergencia inyectada a mano se detecta: la cuenta tiene dientes")
	void la_divergencia_inyectada_se_detecta() {
		// EL ESCENARIO QUE JUSTIFICA LA CLASE. Un test de coherencia que solo mira el camino
		// feliz esta siempre en verde y no prueba nada: si la cuenta estuviera mal escrita —si
		// sumara todos los tipos con signo positivo, por ejemplo— los escenarios anteriores
		// pasarian igual mientras no hubiera reversiones.
		//
		// El UPDATE directo a la columna es literalmente "un camino que escribe uno y no el otro",
		// que es el modo de falla que el challenge declaro indetectable. Aca se detecta.
		Fixture fixture = crearFixture();
		long autorizacionId = autorizar(fixture, "L-5", 10);
		consumirSesion(fixture, siguienteSesion());
		consumirSesion(fixture, siguienteSesion());
		exigirCoherencia(fixture, autorizacionId, 2);

		jdbc.update("""
				UPDATE autorizacion SET cantidad_consumida = cantidad_consumida + 3 WHERE id = ?
				""", autorizacionId);

		assertThat(consumida(autorizacionId)).isEqualTo(5);
		assertThat(sumaDelLedger(autorizacionId))
				.as("el ledger sigue diciendo la verdad: dos hechos, dos unidades")
				.isEqualTo(2);

		SaldoDeAutorizacionView saldo =
				consumo.saldo(fixture.actor(), autorizacionId, DIA_DE_LA_ATENCION);

		assertThat(saldo.cantidadConsumida()).isEqualTo(5);
		assertThat(saldo.consumidaSegunElLedger()).isEqualTo(2);
		assertThat(saldo.coherente())
				.as("GET /saldo tiene que DECIRLO. Es la unica mitigacion que 04.05 pudo poner "
						+ "sin Docker, y si este assert falla esa mitigacion no existe")
				.isFalse();
		assertThat(saldo.saldo())
				.as("informa las dos y no elige: corregir en un GET taparia el sintoma")
				.isEqualTo(5);
		assertThat(saldo.saldoSegunElLedger()).isEqualTo(8);
	}

	@Test
	@DisplayName("una reversion sobre una columna que el ledger no respalda se corta y no empeora")
	void la_reversion_no_escribe_sobre_una_divergencia() {
		// El otro lado de la misma moneda, y el unico lugar donde el servicio ya reacciona a la
		// divergencia: si `cantidad_consumida` esta por debajo de lo que el ledger afirma, el
		// UPDATE inverso devuelve cero filas y `revertir` CORTA en vez de escribir una reversion
		// que dejaria la divergencia peor —un movimiento compensando un descuento que la columna
		// no tiene—.
		Fixture fixture = crearFixture();
		long autorizacionId = autorizar(fixture, "L-6", 10);
		ResultadoDeConsumo consumida = consumirSesion(fixture, siguienteSesion());

		jdbc.update("UPDATE autorizacion SET cantidad_consumida = 0 WHERE id = ?", autorizacionId);

		assertThatThrownBy(() -> revertir(
				fixture, autorizacionId, consumida.movimientoId(), "Reversion sobre divergencia"))
				.as("se corta con un 409 explicable, no con un saldo negativo")
				.isInstanceOf(com.akine.person.domain.exception.AutorizacionSinSaldoException.class);

		assertThat(filasDelLedger(autorizacionId))
				.as("y NO quedo escrita ninguna reversion: la transaccion entera se deshizo")
				.isEqualTo(1);
		assertThat(consumida(autorizacionId)).isZero();
	}

	// =================================================================================
	// Aislamiento de tenant — AGENT.md seccion 6
	// =================================================================================

	@Test
	@DisplayName("un actor del tenant B no lee el saldo ni el ledger de A: 404, nunca 403")
	void un_tenant_no_audita_el_ledger_del_otro() {
		// Este ledger dice cuantas sesiones se le prestaron a un paciente y cuando. Una lectura
		// cruzada no es una fuga de metadatos: es informacion asistencial del centro vecino.
		Fixture tenantA = crearFixture();
		Fixture tenantB = crearFixture();
		long autorizacionId = autorizar(tenantA, "L-T", 5);
		consumirSesion(tenantA, siguienteSesion());

		assertThatThrownBy(() -> consumo.saldo(tenantB.actor(), autorizacionId, null))
				.isInstanceOf(AutorizacionNotAccessibleException.class);
		assertThatThrownBy(() -> consumo.movimientos(tenantB.actor(), autorizacionId))
				.isInstanceOf(AutorizacionNotAccessibleException.class);

		assertThat(consumo.movimientos(tenantA.actor(), autorizacionId))
				.as("y el dueño si lo ve: el control negativo del aislamiento")
				.hasSize(1);
	}

	// =================================================================================
	// La cuenta, en un solo lugar
	// =================================================================================

	/**
	 * Exige que las dos fuentes digan lo mismo, por SQL y por la vista.
	 *
	 * <p>Se comprueba <b>dos veces a proposito</b>: la suma por SQL es independiente del codigo de
	 * produccion, asi que si {@code SaldoDeAutorizacionView} calculara mal, la primera afirmacion
	 * sigue siendo cierta y la segunda falla. Un test que solo usara la vista estaria preguntandole
	 * al acusado.
	 */
	private void exigirCoherencia(Fixture fixture, long autorizacionId, int esperado) {
		assertThat(sumaDelLedger(autorizacionId))
				.as("la suma del ledger, calculada por SQL y no por el codigo bajo prueba")
				.isEqualTo(esperado);
		assertThat(consumida(autorizacionId))
				.as("y la columna materializada")
				.isEqualTo(esperado);

		SaldoDeAutorizacionView saldo =
				consumo.saldo(fixture.actor(), autorizacionId, DIA_DE_LA_ATENCION);
		assertThat(saldo.consumidaSegunElLedger()).isEqualTo(esperado);
		assertThat(saldo.coherente())
				.as("y la vista lo confirma")
				.isTrue();
	}

	/** La cuenta del ledger: la cantidad es siempre positiva y el signo lo da el tipo. */
	private int sumaDelLedger(long autorizacionId) {
		Integer suma = jdbc.queryForObject("""
				SELECT COALESCE(SUM(CASE WHEN tipo IN ('CONSUMO', 'RESERVA')
				                         THEN cantidad ELSE -cantidad END), 0)
				  FROM autorizacion_movimiento
				 WHERE autorizacion_id = ?
				""", Integer.class, autorizacionId);
		return suma == null ? 0 : suma;
	}

	private int consumida(long autorizacionId) {
		return jdbc.queryForObject(
				"SELECT cantidad_consumida FROM autorizacion WHERE id = ?",
				Integer.class, autorizacionId);
	}

	/** La {@code @Version} de la autorizacion, leida directo: el consumo NO la mueve. */
	private long versionDe(long autorizacionId) {
		return jdbc.queryForObject(
				"SELECT version FROM autorizacion WHERE id = ?", Long.class, autorizacionId);
	}

	private int filasDelLedger(long autorizacionId) {
		return jdbc.queryForObject(
				"SELECT COUNT(*) FROM autorizacion_movimiento WHERE autorizacion_id = ?",
				Integer.class, autorizacionId);
	}

	// =================================================================================
	// Ejecucion concurrente
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
				desenlaces.add(futuro.get(30, TimeUnit.SECONDS));
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
	// Operaciones bajo prueba
	// =================================================================================

	private ResultadoDeConsumo consumirSesion(Fixture fixture, long sesionId) {
		return consumo.consumirPorSesion(new ConsumoPorSesion(
				fixture.organizationId(),
				fixture.personaId(),
				fixture.consultorioId(),
				sesionId,
				DIA_DE_LA_ATENCION,
				1,
				fixture.accountId(),
				java.util.Set.of()));
	}

	private MovimientoView revertir(
			Fixture fixture, long autorizacionId, long movimientoId, String motivo) {

		return consumo.revertir(
				fixture.actor(), autorizacionId, new ReversionCommand(movimientoId, motivo));
	}

	private long autorizar(Fixture fixture, String numero, int cantidad) {
		AutorizacionView vista = autorizaciones.registrar(
				fixture.actor(), fixture.personaId(), new AutorizacionAltaCommand(
						fixture.coberturaId(), fixture.practicaId(), null, numero,
						EstadoAutorizacion.APROBADA, cantidad, DESDE, HASTA, null));
		return vista.id();
	}

	private static long siguienteSesion() {
		return SESIONES.incrementAndGet();
	}

	// =================================================================================
	// Fixture — todo sintetico (AGENT.md seccion 10)
	// =================================================================================

	private record Fixture(
			long organizationId,
			long consultorioId,
			long accountId,
			long personaId,
			long coberturaId,
			long practicaId,
			OperatingActor actor) {
	}

	private Fixture crearFixture() {
		String sufijo = UUID.randomUUID().toString().substring(0, 12);
		long organizationId = insertarOrganization(sufijo);
		long consultorioId = insertarConsultorio(organizationId, sufijo);
		long accountId = insertarCuenta(sufijo);
		insertarMembership(organizationId, consultorioId, accountId);

		long personaId = insertarPersona(organizationId, sufijo);
		insertarPerfilPaciente(organizationId, personaId, accountId);

		long financiadorId = insertarFinanciador(organizationId, "OS-" + sufijo);
		long planId = insertarPlan(organizationId, financiadorId, "P-" + sufijo);
		long coberturaId =
				insertarCobertura(organizationId, personaId, financiadorId, planId, sufijo);

		long especialidadId = insertarEspecialidad(organizationId, "E-" + sufijo);
		long practicaId = insertarPractica(organizationId, especialidadId, "PR-" + sufijo);

		return new Fixture(organizationId, consultorioId, accountId, personaId, coberturaId,
				practicaId,
				new OperatingActor(accountId, false, organizationId, consultorioId));
	}

	private long insertarOrganization(String sufijo) {
		String slug = "ledger-it-" + sufijo;
		jdbc.update("""
				INSERT INTO organization (name, slug, timezone, active, version,
				                          created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "Centro Sintetico " + sufijo, slug, ZONA);
		return jdbc.queryForObject("SELECT id FROM organization WHERE slug = ?", Long.class, slug);
	}

	private long insertarConsultorio(long organizationId, String sufijo) {
		String nombre = "Sede Sintetica " + sufijo;
		jdbc.update("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version,
				                         created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, nombre, ZONA);
		return jdbc.queryForObject(
				"SELECT id FROM consultorio WHERE organization_id = ? AND name = ?",
				Long.class, organizationId, nombre);
	}

	private long insertarCuenta(String sufijo) {
		String email = "ledger-it-" + sufijo + "@ejemplo.test";
		jdbc.update("""
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado,
				                    active, version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', 'DePrueba', 'ACTIVA', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", email, email);
		return jdbc.queryForObject(
				"SELECT id FROM cuenta WHERE email_normalizado = ?", Long.class, email);
	}

	private void insertarMembership(long organizationId, long consultorioId, long accountId) {
		jdbc.update("""
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, estado, active, version,
				                        created_at, updated_at)
				VALUES (?, ?, ?, 'CONSULTORIO_ADMIN', 0,
				        DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 YEAR), 'ACTIVA', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, consultorioId, accountId);
	}

	private long insertarPersona(long organizationId, String sufijo) {
		String documento = String.valueOf(10000000 + Math.abs(sufijo.hashCode()) % 80000000);
		jdbc.update("""
				INSERT INTO persona (organization_id, tipo_documento, numero_documento,
				                     documento_clave, apellido, nombre, apellido_clave,
				                     nombre_clave, created_at, updated_at)
				VALUES (?, 'DNI', ?, ?, 'Sintetica', 'Paciente', 'SINTETICA', 'PACIENTE',
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, documento, documento);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private void insertarPerfilPaciente(long organizationId, long personaId, long accountId) {
		jdbc.update("""
				INSERT INTO perfil_paciente (organization_id, persona_id, activado_en, activado_por,
				                             active, version, created_at, updated_at)
				VALUES (?, ?, UTC_TIMESTAMP(6), ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, personaId, accountId);
	}

	private long insertarFinanciador(long organizationId, String codigo) {
		jdbc.update("""
				INSERT INTO financiador (organization_id, codigo, nombre, tipo, active, version,
				                         created_at, updated_at)
				VALUES (?, ?, ?, 'PREPAGA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, codigo, "Financiador " + codigo);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private long insertarPlan(long organizationId, long financiadorId, String codigo) {
		jdbc.update("""
				INSERT INTO plan_cobertura (organization_id, financiador_id, codigo, nombre,
				                            vigencia_desde, requiere_autorizacion,
				                            requiere_credencial, active, version,
				                            created_at, updated_at)
				VALUES (?, ?, ?, ?, '2020-01-01', 1, 0, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, financiadorId, codigo, "Plan " + codigo);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private long insertarCobertura(
			long organizationId, long personaId, long financiadorId, long planId, String sufijo) {

		jdbc.update("""
				INSERT INTO cobertura_paciente (
				        organization_id, persona_id, tipo,
				        financiador_id, financiador_codigo, financiador_nombre, financiador_tipo,
				        plan_id, plan_codigo, plan_nombre,
				        requeria_autorizacion, requeria_credencial, referencia_capturada_el,
				        numero_afiliado, vigencia_desde, principal, active, version,
				        created_at, updated_at)
				VALUES (?, ?, 'FINANCIADA', ?, ?, ?, 'PREPAGA', ?, ?, ?, 1, 0, UTC_TIMESTAMP(6),
				        ?, '2020-01-01', 1, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""",
				organizationId, personaId,
				financiadorId, "OS-" + sufijo, "Financiador OS-" + sufijo,
				planId, "P-" + sufijo, "Plan P-" + sufijo,
				"AF-" + sufijo);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private long insertarEspecialidad(long organizationId, String codigo) {
		jdbc.update("""
				INSERT INTO especialidad (organization_id, codigo, name, valid_from, active,
				                          version, created_at, updated_at)
				VALUES (?, ?, ?, '2020-01-01 00:00:00.000000', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, codigo, "Especialidad " + codigo);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private long insertarPractica(long organizationId, long especialidadId, String codigo) {
		jdbc.update("""
				INSERT INTO practica (organization_id, especialidad_id, codigo, name, valid_from,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, '2020-01-01 00:00:00.000000', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, especialidadId, codigo, "Practica " + codigo);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}
}
