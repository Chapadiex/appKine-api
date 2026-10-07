package com.akine.person;

import com.akine.TestcontainersConfiguration;
import com.akine.person.application.AutorizacionAltaCommand;
import com.akine.person.application.AutorizacionService;
import com.akine.person.application.AutorizacionView;
import com.akine.person.application.ConsumoDeAutorizacionService;
import com.akine.person.application.MovimientoView;
import com.akine.person.application.OperatingActor;
import com.akine.person.application.ReversionCommand;
import com.akine.person.application.SaldoDeAutorizacionView;
import com.akine.person.domain.EstadoAutorizacion;
import com.akine.person.domain.exception.AutorizacionNotAccessibleException;
import com.akine.person.domain.exception.MovimientoYaRevertidoException;
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
 * La ultima unidad autorizada, peleada por dos transacciones de verdad contra MySQL real.
 *
 * <p><b>ESCRITO EL 19/09/2026 Y NUNCA EJECUTADO: Docker no estaba disponible.</b> El motor de
 * contenedores de esta maquina no arranca sin elevacion, asi que esta clase compila y no corrio.
 * Nada de lo que afirma esta verificado todavia.
 *
 * <h2>Que escenario prueba, y por que importa</h2>
 *
 * <p>El registro de avance de AKINE-04.05 lo declara con todas las letras: <i>"el {@code UPDATE}
 * condicional nunca corrio contra la base; la ultima unidad concurrente esta probada con un
 * <b>mock que devuelve cero filas</b>, no con dos transacciones peleandose. Es lo primero a cubrir
 * cuando haya Docker"</i>. Esta clase es eso.
 *
 * <p>Todo el mecanismo es una sola linea de SQL, y la condicion del {@code WHERE} <b>es</b> la
 * correctitud:
 *
 * <pre>
 *   UPDATE autorizacion
 *      SET cantidad_consumida = cantidad_consumida + :n
 *    WHERE id = :id AND organization_id = :org AND active = 1 AND estado = 'APROBADA'
 *      AND (cantidad_autorizada IS NULL
 *           OR cantidad_autorizada - cantidad_consumida &gt;= :n)
 * </pre>
 *
 * <p><b>Cero filas afectadas significa "no hay saldo", y lo decide el motor.</b> Un test unitario
 * que configura el doble para devolver {@code 0} prueba que el servicio sabe leer un cero; no
 * prueba que el motor devuelva cero cuando dos transacciones llegan juntas. Esa afirmacion
 * —que no hay ventana entre la evaluacion de la condicion y la escritura— solo la puede contestar
 * InnoDB, porque un {@code UPDATE} lee la version <b>actual</b> de la fila y no la foto de la
 * transaccion. Es exactamente la leccion de 05.02 y de 07.02.
 *
 * <p>Y lo que se pierde si falla es concreto: {@code cantidad_consumida} pasa de
 * {@code cantidad_autorizada}, el centro le presenta al financiador mas sesiones de las que
 * autorizo, y {@code ck_autorizacion_consumo_coherente} de {@code V44} —la red del motor— empieza
 * a rechazar escrituras legitimas de esa autorizacion para siempre.
 *
 * <h2>Los seis escenarios</h2>
 *
 * <ol>
 *   <li><b>Dos consumos de la ultima unidad.</b> Uno entra, el otro ve cero filas y recibe
 *       {@code SIN_SALDO} —no una excepcion: el cierre clinico no se bloquea (DP-06)—, y
 *       {@code cantidad_consumida} <b>nunca</b> supera {@code cantidad_autorizada}.</li>
 *   <li><b>Rafaga de N+1 sobre una autorizacion de N unidades.</b> Entran exactamente N. Es el
 *       escenario 1 escalado, y el que destaparia una condicion que funciona de a dos y se rompe
 *       de a seis.</li>
 *   <li><b>Control negativo: dos consumos de pacientes distintos entran los dos.</b> Sin el, un
 *       lock demasiado grueso —o una serializacion accidental por tabla— pasaria inadvertido con
 *       el resto en verde, porque todos los demas escenarios usan un solo paciente. Es lo que a
 *       02.07 le falto.</li>
 *   <li><b>El reintento del mismo cierre devuelve el movimiento que ya existe.</b> Ni 409 ni fila
 *       duplicada: {@code uk_autorizacion_movimiento_origen} es la regla y el pre-chequeo es la
 *       forma de responderla sin chocar.</li>
 *   <li><b>Reversion y re-consumo.</b> El saldo vuelve y se puede volver a gastar. La reversion
 *       <b>compensa</b>: el consumo original sigue en el ledger diciendo que ocurrio (regla
 *       maestra 10).</li>
 *   <li><b>Revertir dos veces el mismo origen no entra.</b> Es lo que hace a la reversion
 *       idempotente en vez de meramente segura.</li>
 * </ol>
 *
 * <h2>Como comprobar que este test tiene dientes</h2>
 *
 * <p>Los escenarios de concurrencia que pasan a la primera son motivo de sospecha, no de alivio.
 * La verificacion por mutacion que corresponde hacer la primera vez que esto corra, igual que
 * {@code AutorizacionConcurrenteIT} hizo con su lock: <b>sacarle al {@code UPDATE} de
 * {@code AutorizacionRepository#descontarSaldo} la condicion
 * {@code cantidad_autorizada - cantidad_consumida &gt;= :cantidad}</b>. Los escenarios 1 y 2 tienen
 * que fallar con el saldo pasado de rosca; si siguen en verde, lo que esta mal es el test.
 *
 * <h2>Lo que esta clase NO cubre, declarado</h2>
 *
 * <p><b>Cual autorizacion se elige cuando hay varias.</b> El registro de 04.05 lo declara como
 * limite de la etapa: sin tabla puente Oferta-Practica, el consumo puede imputarse a una
 * autorizacion de otra practica, y unificarlo es 06.04. Probar el desempate aca congelaria una
 * regla que la etapa declaro provisoria.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class ConsumoConcurrenteIT {

	private static final String ZONA = "America/Argentina/Cordoba";

	/** Fechas fijas y lejanas: un escenario atado al reloj de pared falla el dia que lo alcanza. */
	private static final LocalDate DESDE = LocalDate.of(2027, 1, 1);
	private static final LocalDate HASTA = LocalDate.of(2027, 12, 31);
	private static final LocalDate DIA_DE_LA_ATENCION = LocalDate.of(2027, 6, 15);

	/** Ids de sesion sinteticos. No hay FK a {@code sesion}: el ledger guarda una referencia. */
	private static final AtomicLong SESIONES = new AtomicLong(770000L);

	@Autowired private ConsumoDeAutorizacionService consumo;
	@Autowired private AutorizacionService autorizaciones;
	@Autowired private JdbcTemplate jdbc;

	// =================================================================================
	// 1 y 2 — la ultima unidad, y la rafaga
	// =================================================================================

	@Test
	@DisplayName("dos consumos concurrentes de la ultima unidad: uno entra y el otro ve cero filas")
	void la_ultima_unidad_la_gana_uno_solo() {
		Fixture fixture = crearFixture();
		long autorizacionId = autorizar(fixture, "A-1", 1);

		Callable<ResultadoDeConsumo> uno = () -> consumirSesion(fixture, siguienteSesion());
		Callable<ResultadoDeConsumo> otro = () -> consumirSesion(fixture, siguienteSesion());

		List<Desenlace<ResultadoDeConsumo>> desenlaces = enParalelo(List.of(uno, otro));

		// NINGUNO falla: el saldo insuficiente es un DESENLACE, no una excepcion. Es la decision
		// de la etapa que se aparta del precedente de billing, y si alguien la "arregla" haciendo
		// lanzar al servicio, este assert es el que lo frena.
		assertThat(desenlaces.stream().filter(Desenlace::fallo).toList())
				.as("un cierre sin saldo NO puede fallar: la atencion ocurrio (DP-06). "
						+ "Desenlaces: %s", desenlaces)
				.isEmpty();

		assertThat(desenlaces.stream().filter(d -> d.valor().descontoEfectivo()).count())
				.as("exactamente uno descuenta. Desenlaces: %s", desenlaces)
				.isEqualTo(1);
		assertThat(desenlaces.stream()
				.filter(d -> "SIN_SALDO".equals(d.valor().desenlace()))
				.count())
				.as("el otro ve cero filas afectadas. Desenlaces: %s", desenlaces)
				.isEqualTo(1);

		assertThat(consumida(autorizacionId))
				.as("y la columna queda en 1: el saldo NUNCA supera lo autorizado")
				.isEqualTo(1);
		assertThat(movimientosDe(autorizacionId))
				.as("el que no descuento tampoco deja fila: el ledger no registra intenciones")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("rafaga de seis consumos sobre una autorizacion de cinco: entran exactamente cinco")
	void la_rafaga_se_corta_donde_termina_el_saldo() {
		// El escenario 1 escalado. Una condicion que funciona de a dos y se rompe de a seis es
		// posible —por ejemplo si alguien reemplazara el UPDATE condicional por un "leer, decidir,
		// escribir" que de a dos gana por suerte— y de a dos no se ve.
		Fixture fixture = crearFixture();
		long autorizacionId = autorizar(fixture, "A-5", 5);

		List<Callable<ResultadoDeConsumo>> rafaga = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			rafaga.add(() -> consumirSesion(fixture, siguienteSesion()));
		}

		List<Desenlace<ResultadoDeConsumo>> desenlaces = enParalelo(rafaga);

		assertThat(desenlaces.stream().filter(Desenlace::fallo).toList())
				.as("ninguno falla. Desenlaces: %s", desenlaces)
				.isEmpty();
		assertThat(desenlaces.stream().filter(d -> d.valor().descontoEfectivo()).count())
				.as("cinco entran y el sexto no. Desenlaces: %s", desenlaces)
				.isEqualTo(5);

		assertThat(consumida(autorizacionId)).isEqualTo(5);
		assertThat(movimientosDe(autorizacionId))
				.as("cinco filas en el ledger, una por unidad gastada")
				.isEqualTo(5);
	}

	// =================================================================================
	// 3 — el control negativo
	// =================================================================================

	@Test
	@DisplayName("dos consumos de PACIENTES distintos entran los dos: no se serializa de mas")
	void dos_autorizaciones_distintas_no_se_estorban() {
		// La otra mitad, y la que hace valida a la primera. Si el consumo tomara un lock grueso
		// —por organizacion, o una fila-lock compartida— los dos escenarios anteriores seguirian
		// en verde y el sistema serializaria todos los cierres del centro entre si.
		//
		// Son dos PACIENTES y no dos autorizaciones del mismo paciente a proposito: el servicio
		// elige la autorizacion por persona, asi que dos autorizaciones de un mismo paciente
		// serian las dos candidatas de los dos consumos y el escenario mediria otra cosa.
		Fixture unPaciente = crearFixture();
		Fixture otroPaciente = crearFixture();
		long unaAutorizacion = autorizar(unPaciente, "N-1", 1);
		long otraAutorizacion = autorizar(otroPaciente, "N-2", 1);

		List<Desenlace<ResultadoDeConsumo>> desenlaces = enParalelo(List.of(
				() -> consumirSesion(unPaciente, siguienteSesion()),
				() -> consumirSesion(otroPaciente, siguienteSesion())));

		assertThat(desenlaces.stream().filter(Desenlace::fallo).toList())
				.as("ninguno falla. Desenlaces: %s", desenlaces)
				.isEmpty();
		assertThat(desenlaces.stream().filter(d -> d.valor().descontoEfectivo()).count())
				.as("los DOS descuentan: no comparten saldo. Desenlaces: %s", desenlaces)
				.isEqualTo(2);

		assertThat(consumida(unaAutorizacion)).isEqualTo(1);
		assertThat(consumida(otraAutorizacion)).isEqualTo(1);
	}

	// =================================================================================
	// 4 — la idempotencia
	// =================================================================================

	@Test
	@DisplayName("el reintento del mismo cierre devuelve el movimiento que ya existe")
	void el_reintento_no_duplica_ni_falla() {
		// RN-M14-005 hace idempotente al cierre de sesion, asi que el consumo que cuelga de el
		// tambien tiene que serlo. Un 409 aca dejaria al cierre reintentado fallando por un efecto
		// secundario suyo, y una segunda fila descontaria dos veces la misma atencion.
		Fixture fixture = crearFixture();
		long autorizacionId = autorizar(fixture, "I-1", 10);
		long sesionId = siguienteSesion();

		ResultadoDeConsumo primero = consumirSesion(fixture, sesionId);
		ResultadoDeConsumo reintento = consumirSesion(fixture, sesionId);

		assertThat(primero.desenlace()).isEqualTo("CONSUMIDA");
		assertThat(reintento.desenlace())
				.as("el segundo reconoce el hecho ya asentado")
				.isEqualTo("YA_CONSUMIDA");
		assertThat(reintento.movimientoId())
				.as("y devuelve EL MISMO movimiento, no uno nuevo")
				.isEqualTo(primero.movimientoId());

		assertThat(consumida(autorizacionId))
				.as("una sola unidad descontada")
				.isEqualTo(1);
		assertThat(movimientosDe(autorizacionId))
				.as("una sola fila en el ledger")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("dos reintentos SIMULTANEOS del mismo cierre dejan una sola fila")
	void el_reintento_simultaneo_tampoco_duplica() {
		// La ventana residual que el javadoc del servicio declara: el pre-chequeo de idempotencia
		// y el INSERT no son atomicos entre si, asi que dos consumos REALMENTE simultaneos del
		// mismo sesionId pueden pasar los dos el chequeo. Arriba eso lo cierra el @Version de
		// Sesion; aca se ejerce el piso: pase lo que pase, la base no admite dos filas del mismo
		// origen y la columna no se descuenta dos veces.
		Fixture fixture = crearFixture();
		long autorizacionId = autorizar(fixture, "I-2", 10);
		long sesionId = siguienteSesion();

		List<Desenlace<ResultadoDeConsumo>> desenlaces = enParalelo(List.of(
				() -> consumirSesion(fixture, sesionId),
				() -> consumirSesion(fixture, sesionId)));

		assertThat(movimientosDe(autorizacionId))
				.as("una sola fila del mismo origen: lo garantiza "
						+ "uk_autorizacion_movimiento_origen. Desenlaces: %s", desenlaces)
				.isEqualTo(1);
		assertThat(consumida(autorizacionId))
				.as("y el saldo bajo una sola vez. Si esto diera 2, el pre-chequeo dejo pasar un "
						+ "descuento cuyo movimiento despues no entro: la divergencia que "
						+ "LedgerCoherenteIT persigue. Desenlaces: %s", desenlaces)
				.isEqualTo(1);
	}

	// =================================================================================
	// 5 y 6 — la reversion
	// =================================================================================

	@Test
	@DisplayName("revertir devuelve el saldo y permite volver a consumir, sin borrar el consumo")
	void la_reversion_devuelve_el_saldo_y_no_borra_nada() {
		Fixture fixture = crearFixture();
		long autorizacionId = autorizar(fixture, "R-1", 1);
		long sesionId = siguienteSesion();

		ResultadoDeConsumo consumida = consumirSesion(fixture, sesionId);
		assertThat(consumida.descontoEfectivo()).isTrue();
		assertThat(consumida(autorizacionId)).isEqualTo(1);

		MovimientoView reversion = consumo.revertir(fixture.actor(), autorizacionId,
				new ReversionCommand(consumida.movimientoId(), "La sesion se cargo en el paciente "
						+ "equivocado"));

		assertThat(reversion.tipo()).isEqualTo("REVERSION");
		assertThat(reversion.movimientoOrigenId())
				.as("la reversion apunta al consumo que compensa: sin eso, ligarlos obliga a "
						+ "reconstruirlo por tipo_origen + referencia_origen")
				.isEqualTo(consumida.movimientoId());
		assertThat(consumida(autorizacionId))
				.as("el saldo volvio")
				.isZero();
		assertThat(movimientosDe(autorizacionId))
				.as("DOS filas: la reversion compensa, NO borra (regla maestra 10)")
				.isEqualTo(2);

		// Y la unidad devuelta se puede volver a gastar, que es lo unico que prueba que el saldo
		// volvio de verdad y no solo en la columna.
		ResultadoDeConsumo otraVez = consumirSesion(fixture, siguienteSesion());
		assertThat(otraVez.descontoEfectivo())
				.as("con el saldo devuelto, otra sesion entra")
				.isTrue();
		assertThat(consumida(autorizacionId)).isEqualTo(1);
	}

	@Test
	@DisplayName("revertir dos veces el mismo consumo no entra")
	void la_segunda_reversion_del_mismo_origen_choca() {
		// `tipo` entra en el unique A PROPOSITO: una REVERSION del mismo origen es otra fila y
		// tiene que poder entrar; una SEGUNDA reversion del mismo origen si es un duplicado. Eso
		// es lo que hace a la reversion idempotente en vez de meramente segura — y sin esto, un
		// doble click devolveria dos unidades donde se gasto una.
		Fixture fixture = crearFixture();
		long autorizacionId = autorizar(fixture, "R-2", 3);

		ResultadoDeConsumo consumida = consumirSesion(fixture, siguienteSesion());
		consumo.revertir(fixture.actor(), autorizacionId,
				new ReversionCommand(consumida.movimientoId(), "Error de carga"));

		assertThatThrownBy(() -> consumo.revertir(fixture.actor(), autorizacionId,
				new ReversionCommand(consumida.movimientoId(), "Error de carga otra vez")))
				.as("409 explicable y no un error de constraint: el servicio consulta antes para "
						+ "poder nombrar la situacion")
				.isInstanceOf(MovimientoYaRevertidoException.class);

		assertThat(consumida(autorizacionId))
				.as("y el saldo devuelto es UNO, no dos")
				.isZero();
		assertThat(movimientosDe(autorizacionId)).isEqualTo(2);
	}

	// =================================================================================
	// Aislamiento de tenant — AGENT.md seccion 6, exigido en CADA test de integracion
	// =================================================================================

	@Test
	@DisplayName("un actor del tenant B no lee ni revierte el saldo de una autorizacion de A: 404")
	void un_tenant_no_toca_el_ledger_del_otro() {
		// 404 y NUNCA 403. Un 403 confirmaria que esa autorizacion existe, y con eso se puede
		// censar por id cuantas autorizaciones tiene el centro de al lado.
		Fixture tenantA = crearFixture();
		Fixture tenantB = crearFixture();
		long autorizacionId = autorizar(tenantA, "T-1", 5);
		ResultadoDeConsumo consumida = consumirSesion(tenantA, siguienteSesion());

		assertThatThrownBy(() -> consumo.saldo(tenantB.actor(), autorizacionId, null))
				.isInstanceOf(AutorizacionNotAccessibleException.class);
		assertThatThrownBy(() -> consumo.movimientos(tenantB.actor(), autorizacionId))
				.isInstanceOf(AutorizacionNotAccessibleException.class);
		assertThatThrownBy(() -> consumo.revertir(tenantB.actor(), autorizacionId,
				new ReversionCommand(consumida.movimientoId(), "Intento cruzado")))
				.isInstanceOf(AutorizacionNotAccessibleException.class);

		assertThat(consumida(autorizacionId))
				.as("y nada se movio")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("el saldo se lee por las dos fuentes y las dos coinciden despues de consumir")
	void el_saldo_reporta_las_dos_cuentas() {
		// GET /saldo devuelve la columna Y el recalculo del ledger con un `coherente` al lado. Es
		// la unica mitigacion que 04.05 pudo poner sin Docker al riesgo de divergencia, y este es
		// su control positivo: en el camino normal las dos cuentas tienen que dar lo mismo.
		// LedgerCoherenteIT se ocupa del control NEGATIVO —que el indicador sepa decir que no—.
		Fixture fixture = crearFixture();
		long autorizacionId = autorizar(fixture, "S-1", 4);
		consumirSesion(fixture, siguienteSesion());
		consumirSesion(fixture, siguienteSesion());

		SaldoDeAutorizacionView saldo =
				consumo.saldo(fixture.actor(), autorizacionId, DIA_DE_LA_ATENCION);

		assertThat(saldo.cantidadConsumida()).isEqualTo(2);
		assertThat(saldo.consumidaSegunElLedger()).isEqualTo(2);
		assertThat(saldo.saldo()).isEqualTo(2);
		assertThat(saldo.coherente()).isTrue();
		assertThat(saldo.movimientos()).isEqualTo(2);
		assertThat(saldo.habilita())
				.as("con saldo y dentro de la vigencia, la autorizacion habilita")
				.isTrue();
	}

	// =================================================================================
	// Ejecucion concurrente
	// =================================================================================

	/** Barrera de salida para que las N tareas arranquen juntas y no en fila. */
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

	/**
	 * Un consumo tal como lo produce el cierre de una sesion.
	 *
	 * <p>Se entra por {@code ConsumoDeAutorizacionService} y no por {@code SesionService#cerrar} a
	 * proposito: lo que estos escenarios miden es el {@code UPDATE} condicional, y pasar por el
	 * cierre completo agregaria el numerador de sesiones, el devengamiento de la obligacion y el
	 * {@code @Version} de {@code Sesion} como fuentes de fallo ajenas. Que el observador arme este
	 * mismo {@code ConsumoPorSesion} ya lo cubre {@code ConsumoDeAutorizacionEnCierreTest}.
	 */
	private ResultadoDeConsumo consumirSesion(Fixture fixture, long sesionId) {
		return consumo.consumirPorSesion(new ConsumoPorSesion(
				fixture.organizationId(),
				fixture.personaId(),
				fixture.consultorioId(),
				sesionId,
				DIA_DE_LA_ATENCION,
				1,
				fixture.accountId(),
				java.util.Set.of())).get(0);
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

	private int consumida(long autorizacionId) {
		return jdbc.queryForObject(
				"SELECT cantidad_consumida FROM autorizacion WHERE id = ?",
				Integer.class, autorizacionId);
	}

	private int movimientosDe(long autorizacionId) {
		return jdbc.queryForObject(
				"SELECT COUNT(*) FROM autorizacion_movimiento WHERE autorizacion_id = ?",
				Integer.class, autorizacionId);
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

	/**
	 * Un centro con una sede, un administrativo, un paciente con cobertura y una practica.
	 *
	 * <p>Es el mismo fixture de {@code AutorizacionConcurrenteIT} —misma etapa, mismo modulo— y el
	 * rol es {@code CONSULTORIO_ADMIN} por lo mismo: {@code revertir} exige
	 * {@code paciente:manage} sobre la sede del contexto y el test corre contra el evaluador REAL,
	 * asi que tambien falla si esa asignacion se quita por descuido.
	 */
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
		String slug = "consumo-it-" + sufijo;
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
		String email = "consumo-it-" + sufijo + "@ejemplo.test";
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

	/** La copia congelada va ENTERA: {@code ck_cobertura_referencia_coherente} rechaza media. */
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
