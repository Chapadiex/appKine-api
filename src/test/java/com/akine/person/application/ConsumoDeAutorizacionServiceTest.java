package com.akine.person.application;

import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionGuard;
import com.akine.person.domain.Autorizacion;
import com.akine.person.domain.AutorizacionMovimiento;
import com.akine.person.domain.EstadoAutorizacion;
import com.akine.person.domain.TipoMovimientoAutorizacion;
import com.akine.person.domain.TipoOrigenMovimiento;
import com.akine.person.domain.exception.AutorizacionNotAccessibleException;
import com.akine.person.domain.exception.MovimientoNotAccessibleException;
import com.akine.person.domain.exception.MovimientoYaRevertidoException;
import com.akine.person.domain.exception.ReversionSinMotivoException;
import com.akine.person.domain.port.PersonRepositoryPorts.AutorizacionMovimientoRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.AutorizacionRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.PersonaRepositoryPort;
import com.akine.person.spi.ConsumoPorSesion;
import com.akine.person.spi.ResultadoDeConsumo;
import com.akine.platform.spi.audit.AuditTrail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Las reglas de AKINE-04.05 que cuestan caro si alguien las "arregla".
 *
 * <ul>
 *   <li><b>Sin saldo NO se lanza.</b> Es la decision central de la etapa y va contra el precedente
 *       de {@code billing}: la atencion ocurrio, y DP-06 prohibe bloquear un cierre clinico por un
 *       dato administrativo. Se devuelve un desenlace.
 *   <li><b>El saldo lo decide la BASE.</b> Estos casos lo hacen ejecutable: cuando el
 *       {@code UPDATE} condicional devuelve cero filas, no se escribe ningun movimiento. No hay
 *       ningun {@code if} sobre un saldo leido que pueda discrepar con el motor.
 *   <li><b>La idempotencia responde con lo que ya existe</b>, no con un 409 ni con un segundo
 *       descuento.
 *   <li><b>La reversion compensa y no borra</b>, exige motivo, y la segunda del mismo origen
 *       choca.
 * </ul>
 */
@DisplayName("Consumo de autorizaciones (M17, AKINE-04.05)")
class ConsumoDeAutorizacionServiceTest {

	private static final long ORG = 7L;
	private static final long SEDE = 20L;
	private static final long PERSONA = 1204L;
	private static final long COBERTURA = 412L;
	private static final long PRACTICA = 33L;
	private static final long AUTORIZACION = 77L;
	private static final long SESION = 3312L;
	private static final LocalDate HOY = LocalDate.of(2027, 3, 15);

	private final AutorizacionRepositoryPort autorizaciones = mock(AutorizacionRepositoryPort.class);
	private final AutorizacionMovimientoRepositoryPort movimientos =
			mock(AutorizacionMovimientoRepositoryPort.class);
	private final PersonaRepositoryPort personas = mock(PersonaRepositoryPort.class);
	private final PermissionGuard permissionGuard = mock(PermissionGuard.class);
	private final AuditTrail auditTrail = mock(AuditTrail.class);

	private ConsumoDeAutorizacionService service;

	private final OperatingActor administrativo = new OperatingActor(1L, false, ORG, SEDE);

	@BeforeEach
	void setUp() {
		service = new ConsumoDeAutorizacionService(
				autorizaciones, movimientos, personas, permissionGuard, auditTrail);
		given(permissionGuard.requirePermission(any()))
				.willReturn(PermissionDecision.concedida("CONSULTORIO", false));
	}

	// =================================================================================
	// El consumo por cierre de sesion
	// =================================================================================

	@Nested
	@DisplayName("Consumo por cierre de sesion")
	class DelConsumo {

		@Test
		@DisplayName("descuenta una unidad y asienta el movimiento con el origen que lo produjo")
		void consume() {
			given(autorizaciones.aprobadasDePersona(ORG, PERSONA))
					.willReturn(List.of(autorizacion(10, HOY.plusMonths(2))));
			given(movimientos.buscarPorOrigen(any(), any(), any(), any(), any()))
					.willReturn(Optional.empty());
			given(autorizaciones.descontarSaldo(ORG, AUTORIZACION, 1)).willReturn(1);
			given(movimientos.save(any())).willAnswer(invocacion -> conId(invocacion, 9001L));

			ResultadoDeConsumo resultado = service.consumirPorSesion(cierre());

			assertThat(resultado.desenlace()).isEqualTo(ResultadoDeConsumo.CONSUMIDA);
			assertThat(resultado.autorizacionId()).isEqualTo(AUTORIZACION);
			assertThat(resultado.movimientoId()).isEqualTo(9001L);
			// 10 autorizadas, 0 consumidas antes, 1 ahora.
			assertThat(resultado.saldoRestante()).isEqualTo(9);
			verify(autorizaciones).descontarSaldo(ORG, AUTORIZACION, 1);
		}

		@Test
		@DisplayName("sin saldo NO lanza: devuelve el desenlace y NO escribe ningun movimiento")
		void sin_saldo_no_lanza() {
			given(autorizaciones.aprobadasDePersona(ORG, PERSONA))
					.willReturn(List.of(autorizacion(10, HOY.plusMonths(2))));
			given(movimientos.buscarPorOrigen(any(), any(), any(), any(), any()))
					.willReturn(Optional.empty());
			// Cero filas afectadas: la BASE dijo que no alcanza.
			given(autorizaciones.descontarSaldo(ORG, AUTORIZACION, 1)).willReturn(0);

			ResultadoDeConsumo resultado = service.consumirPorSesion(cierre());

			// La atencion ocurrio. Bloquear el cierre clinico por esto es lo que DP-06 prohibe.
			assertThat(resultado.desenlace()).isEqualTo(ResultadoDeConsumo.SIN_SALDO);
			assertThat(resultado.descontoEfectivo()).isFalse();
			// Y el ledger no miente: sin descuento no hay fila.
			verify(movimientos, never()).save(any());
		}

		@Test
		@DisplayName("el reintento del mismo cierre devuelve el movimiento que ya existe, no un 409")
		void idempotente() {
			Autorizacion autorizacion = autorizacion(10, HOY.plusMonths(2));
			given(autorizaciones.aprobadasDePersona(ORG, PERSONA)).willReturn(List.of(autorizacion));
			given(movimientos.buscarPorOrigen(
					ORG, AUTORIZACION, TipoMovimientoAutorizacion.CONSUMO,
					TipoOrigenMovimiento.SESION, SESION))
					.willReturn(Optional.of(movimiento(9001L, TipoMovimientoAutorizacion.CONSUMO)));

			ResultadoDeConsumo resultado = service.consumirPorSesion(cierre());

			assertThat(resultado.desenlace()).isEqualTo(ResultadoDeConsumo.YA_CONSUMIDA);
			assertThat(resultado.movimientoId()).isEqualTo(9001L);
			// Lo que importa: NO vuelve a descontar.
			verify(autorizaciones, never()).descontarSaldo(anyLong(), anyLong(), anyInt());
			verify(movimientos, never()).save(any());
		}

		@Test
		@DisplayName("sin autorizacion elegible no descuenta nada, y es el caso mas frecuente")
		void sin_autorizacion() {
			given(autorizaciones.aprobadasDePersona(ORG, PERSONA)).willReturn(List.of());

			ResultadoDeConsumo resultado = service.consumirPorSesion(cierre());

			assertThat(resultado.desenlace())
					.isEqualTo(ResultadoDeConsumo.SIN_AUTORIZACION_ELEGIBLE);
			verify(autorizaciones, never()).descontarSaldo(anyLong(), anyLong(), anyInt());
			verifyNoInteractions(auditTrail);
		}

		@Test
		@DisplayName("una autorizacion vencida ese dia no es candidata")
		void vencida_no_es_candidata() {
			given(autorizaciones.aprobadasDePersona(ORG, PERSONA))
					.willReturn(List.of(autorizacion(10, HOY.minusDays(1))));

			ResultadoDeConsumo resultado = service.consumirPorSesion(cierre());

			assertThat(resultado.desenlace())
					.isEqualTo(ResultadoDeConsumo.SIN_AUTORIZACION_ELEGIBLE);
		}
	}

	// =================================================================================
	// La reversion
	// =================================================================================

	/**
	 * AKINE-06.04 — la imputacion por practica.
	 *
	 * <p>Hasta 06.04 el consumo elegia "la que vence antes" sin mirar la practica, porque no habia
	 * forma de saber que se habia aplicado. <b>Podia gastar la autorizacion equivocada</b>: una
	 * unidad de fonoaudiologia por una sesion de kinesiologia, comiendole al paciente unidades que
	 * si iba a necesitar y declarandole al financiador algo que no se presto. Era el limite que
	 * 04.05 dejo escrito y delego en esta etapa.
	 *
	 * <p>Los dos casos que cuestan caro si alguien los "simplifica":
	 *
	 * <ul>
	 *   <li><b>Sin autorizacion de la practica realizada NO se consume.</b> Caer de nuevo a
	 *       "cualquiera que habilite" es comodo y es el defecto.</li>
	 *   <li><b>El conjunto vacio conserva el comportamiento anterior.</b> Vacio significa "no se
	 *       sabe", no "ninguna": son todas las sesiones anteriores a 06.04. Filtrar igual apagaria
	 *       el consumo de autorizaciones en todo el sistema, que es peor que el defecto.</li>
	 * </ul>
	 */
	@Nested
	@DisplayName("Imputacion por practica realizada (AKINE-06.04)")
	class DeLaImputacionPorPractica {

		private static final long OTRA_PRACTICA = 77L;
		private static final long OTRA_AUTORIZACION = 4242L;

		@Test
		@DisplayName("elige la autorizacion de la practica realizada, no la que vence antes")
		void elige_por_practica_y_no_por_vencimiento() {
			// La de OTRA_PRACTICA vence ANTES, asi que el desempate de 04.05 la elegiria. Pero la
			// practica que se realizo es la otra: gastarla seria declararle al financiador una
			// prestacion que no ocurrio.
			Autorizacion venceAntesPeroOtraPractica =
					autorizacionDe(OTRA_AUTORIZACION, OTRA_PRACTICA, HOY.plusDays(3));
			Autorizacion laQueCorresponde = autorizacion(10, HOY.plusMonths(2));

			given(autorizaciones.aprobadasDePersona(ORG, PERSONA))
					.willReturn(List.of(venceAntesPeroOtraPractica, laQueCorresponde));
			given(movimientos.buscarPorOrigen(any(), any(), any(), any(), any()))
					.willReturn(Optional.empty());
			given(autorizaciones.descontarSaldo(ORG, AUTORIZACION, 1)).willReturn(1);
			given(movimientos.save(any())).willAnswer(invocacion -> conId(invocacion, 9001L));

			ResultadoDeConsumo resultado = service.consumirPorSesion(cierreCon(Set.of(PRACTICA)));

			assertThat(resultado.desenlace()).isEqualTo(ResultadoDeConsumo.CONSUMIDA);
			assertThat(resultado.autorizacionId()).isEqualTo(AUTORIZACION);
			// Lo que importa: la que vencia antes NO se toco.
			verify(autorizaciones, never()).descontarSaldo(ORG, OTRA_AUTORIZACION, 1);
		}

		@Test
		@DisplayName("con saldo vigente pero de otra practica NO consume, y no lanza")
		void sin_autorizacion_para_la_practica_no_consume() {
			given(autorizaciones.aprobadasDePersona(ORG, PERSONA))
					.willReturn(List.of(autorizacionDe(
							OTRA_AUTORIZACION, OTRA_PRACTICA, HOY.plusMonths(2))));

			ResultadoDeConsumo resultado = service.consumirPorSesion(cierreCon(Set.of(PRACTICA)));

			assertThat(resultado.desenlace())
					.isEqualTo(ResultadoDeConsumo.SIN_AUTORIZACION_PARA_LA_PRACTICA);
			// NO se descuenta nada: consumir la equivocada le come al paciente unidades que si
			// necesita. Y NO se lanza: DP-06 prohibe bloquear el cierre clinico por esto.
			verify(autorizaciones, never()).descontarSaldo(anyLong(), anyLong(), anyInt());
			verify(movimientos, never()).save(any());
		}

		@Test
		@DisplayName("sin practicas registradas conserva el comportamiento anterior a 06.04")
		void conjunto_vacio_no_filtra() {
			// Es TODA sesion anterior a 06.04, y tambien las de ofertas que no registran
			// practicas. Vacio es "no se sabe", no "ninguna".
			given(autorizaciones.aprobadasDePersona(ORG, PERSONA))
					.willReturn(List.of(autorizacionDe(
							OTRA_AUTORIZACION, OTRA_PRACTICA, HOY.plusMonths(2))));
			given(movimientos.buscarPorOrigen(any(), any(), any(), any(), any()))
					.willReturn(Optional.empty());
			given(autorizaciones.descontarSaldo(ORG, OTRA_AUTORIZACION, 1)).willReturn(1);
			given(movimientos.save(any())).willAnswer(invocacion -> conId(invocacion, 9002L));

			ResultadoDeConsumo resultado = service.consumirPorSesion(cierreCon(Set.of()));

			// Consume igual, aunque la practica no coincida con nada: no hay con que filtrar.
			assertThat(resultado.desenlace()).isEqualTo(ResultadoDeConsumo.CONSUMIDA);
			assertThat(resultado.autorizacionId()).isEqualTo(OTRA_AUTORIZACION);
		}

		@Test
		@DisplayName("varias practicas realizadas: alcanza con que una coincida")
		void alcanza_con_que_una_coincida() {
			given(autorizaciones.aprobadasDePersona(ORG, PERSONA))
					.willReturn(List.of(autorizacion(10, HOY.plusMonths(2))));
			given(movimientos.buscarPorOrigen(any(), any(), any(), any(), any()))
					.willReturn(Optional.empty());
			given(autorizaciones.descontarSaldo(ORG, AUTORIZACION, 1)).willReturn(1);
			given(movimientos.save(any())).willAnswer(invocacion -> conId(invocacion, 9003L));

			ResultadoDeConsumo resultado =
					service.consumirPorSesion(cierreCon(Set.of(OTRA_PRACTICA, PRACTICA)));

			assertThat(resultado.desenlace()).isEqualTo(ResultadoDeConsumo.CONSUMIDA);
			// Y sigue siendo UNA unidad por sesion aunque se hayan aplicado dos practicas:
			// cobrar por practica es una decision economica que esta etapa NO toma.
			verify(autorizaciones).descontarSaldo(ORG, AUTORIZACION, 1);
		}

		@Test
		@DisplayName("sin ninguna autorizacion vigente sigue siendo SIN_AUTORIZACION_ELEGIBLE")
		void sin_ninguna_vigente_no_cambia_de_desenlace() {
			// El filtro por practica no puede tapar el caso mas frecuente de todos —el paciente
			// particular—: los dos desenlaces llevan a acciones distintas en el mostrador.
			given(autorizaciones.aprobadasDePersona(ORG, PERSONA)).willReturn(List.of());

			ResultadoDeConsumo resultado = service.consumirPorSesion(cierreCon(Set.of(PRACTICA)));

			assertThat(resultado.desenlace())
					.isEqualTo(ResultadoDeConsumo.SIN_AUTORIZACION_ELEGIBLE);
		}
	}

	@Nested
	@DisplayName("Reversion de un consumo")
	class DeLaReversion {

		@Test
		@DisplayName("compensa con una fila propia y devuelve el saldo, sin borrar el consumo")
		void revierte() {
			given(autorizaciones.findByIdAndOrganizationId(AUTORIZACION, ORG))
					.willReturn(Optional.of(autorizacion(10, HOY.plusMonths(2))));
			given(movimientos.buscarDeLaAutorizacion(ORG, AUTORIZACION, 9001L))
					.willReturn(Optional.of(movimiento(9001L, TipoMovimientoAutorizacion.CONSUMO)));
			given(movimientos.buscarPorOrigen(
					ORG, AUTORIZACION, TipoMovimientoAutorizacion.REVERSION,
					TipoOrigenMovimiento.SESION, SESION))
					.willReturn(Optional.empty());
			given(autorizaciones.devolverSaldo(ORG, AUTORIZACION, 1)).willReturn(1);
			given(movimientos.save(any())).willAnswer(invocacion -> conId(invocacion, 9002L));

			MovimientoView reversion = service.revertir(
					administrativo, AUTORIZACION, new ReversionCommand(9001L, "Sesion equivocada"));

			assertThat(reversion.tipo()).isEqualTo("REVERSION");
			assertThat(reversion.motivo()).isEqualTo("Sesion equivocada");
			// Apunta al consumo que compensa, y al MISMO origen: eso es lo que hace que la
			// segunda reversion choque contra el unique.
			assertThat(reversion.movimientoOrigenId()).isEqualTo(9001L);
			assertThat(reversion.referenciaOrigen()).isEqualTo(SESION);
			assertThat(reversion.efectoSobreElSaldo()).isEqualTo(1);
			verify(autorizaciones).devolverSaldo(ORG, AUTORIZACION, 1);
		}

		@Test
		@DisplayName("sin motivo es 400 y no toca nada")
		void sin_motivo() {
			assertThatThrownBy(() -> service.revertir(
					administrativo, AUTORIZACION, new ReversionCommand(9001L, "   ")))
					.isInstanceOf(ReversionSinMotivoException.class);

			verifyNoInteractions(movimientos);
			verify(autorizaciones, never()).devolverSaldo(anyLong(), anyLong(), anyInt());
		}

		@Test
		@DisplayName("revertir dos veces el mismo consumo es 409, no una segunda devolucion")
		void segunda_reversion() {
			given(autorizaciones.findByIdAndOrganizationId(AUTORIZACION, ORG))
					.willReturn(Optional.of(autorizacion(10, HOY.plusMonths(2))));
			given(movimientos.buscarDeLaAutorizacion(ORG, AUTORIZACION, 9001L))
					.willReturn(Optional.of(movimiento(9001L, TipoMovimientoAutorizacion.CONSUMO)));
			given(movimientos.buscarPorOrigen(
					ORG, AUTORIZACION, TipoMovimientoAutorizacion.REVERSION,
					TipoOrigenMovimiento.SESION, SESION))
					.willReturn(Optional.of(
							movimiento(9002L, TipoMovimientoAutorizacion.REVERSION)));

			assertThatThrownBy(() -> service.revertir(
					administrativo, AUTORIZACION, new ReversionCommand(9001L, "Otra vez")))
					.isInstanceOf(MovimientoYaRevertidoException.class);

			verify(autorizaciones, never()).devolverSaldo(anyLong(), anyLong(), anyInt());
		}

		@Test
		@DisplayName("revertir una REVERSION es 404: volver a consumir lo hace una atencion")
		void revertir_una_reversion() {
			given(autorizaciones.findByIdAndOrganizationId(AUTORIZACION, ORG))
					.willReturn(Optional.of(autorizacion(10, HOY.plusMonths(2))));
			given(movimientos.buscarDeLaAutorizacion(ORG, AUTORIZACION, 9002L))
					.willReturn(Optional.of(
							movimiento(9002L, TipoMovimientoAutorizacion.REVERSION)));

			assertThatThrownBy(() -> service.revertir(
					administrativo, AUTORIZACION, new ReversionCommand(9002L, "Deshacer")))
					.isInstanceOf(MovimientoNotAccessibleException.class);
		}

		@Test
		@DisplayName("una autorizacion de otro tenant es 404")
		void cross_tenant() {
			given(autorizaciones.findByIdAndOrganizationId(AUTORIZACION, ORG))
					.willReturn(Optional.empty());

			assertThatThrownBy(() -> service.revertir(
					administrativo, AUTORIZACION, new ReversionCommand(9001L, "Motivo")))
					.isInstanceOf(AutorizacionNotAccessibleException.class);
		}
	}

	// =================================================================================
	// El saldo por las dos fuentes
	// =================================================================================

	@Nested
	@DisplayName("Consulta de saldo")
	class DelSaldo {

		@Test
		@DisplayName("declara INCOHERENTE cuando el ledger y la columna no coinciden")
		void detecta_la_divergencia() {
			Autorizacion autorizacion = autorizacion(10, HOY.plusMonths(2));
			ReflectionTestUtils.setField(autorizacion, "cantidadConsumida", 3);
			given(autorizaciones.findByIdAndOrganizationId(AUTORIZACION, ORG))
					.willReturn(Optional.of(autorizacion));
			// El ledger solo conoce UN consumo: la columna dice tres.
			given(movimientos.listarDeAutorizacion(ORG, AUTORIZACION))
					.willReturn(List.of(movimiento(9001L, TipoMovimientoAutorizacion.CONSUMO)));

			SaldoDeAutorizacionView saldo =
					service.saldo(administrativo, AUTORIZACION, HOY);

			assertThat(saldo.cantidadConsumida()).isEqualTo(3);
			assertThat(saldo.consumidaSegunElLedger()).isEqualTo(1);
			assertThat(saldo.coherente()).isFalse();
			// Y no corrige: una mutacion escondida en un GET taparia el sintoma.
			verify(autorizaciones, never()).devolverSaldo(anyLong(), anyLong(), anyInt());
		}

		@Test
		@DisplayName("un consumo y su reversion se cancelan en la suma del ledger")
		void reversion_neutraliza() {
			given(autorizaciones.findByIdAndOrganizationId(AUTORIZACION, ORG))
					.willReturn(Optional.of(autorizacion(10, HOY.plusMonths(2))));
			given(movimientos.listarDeAutorizacion(ORG, AUTORIZACION)).willReturn(List.of(
					movimiento(9001L, TipoMovimientoAutorizacion.CONSUMO),
					movimiento(9002L, TipoMovimientoAutorizacion.REVERSION)));

			SaldoDeAutorizacionView saldo = service.saldo(administrativo, AUTORIZACION, HOY);

			assertThat(saldo.consumidaSegunElLedger()).isZero();
			assertThat(saldo.coherente()).isTrue();
			// El consumo NO se borro: las dos filas siguen ahi.
			assertThat(saldo.movimientos()).isEqualTo(2);
		}
	}

	// =================================================================================
	// Fixtures
	// =================================================================================

	private static ConsumoPorSesion cierre() {
		return new ConsumoPorSesion(ORG, PERSONA, SEDE, SESION, HOY, 1, 42L, Set.of());
	}

	/** El mismo cierre, declarando que practicas se aplicaron (AKINE-06.04). */
	private static ConsumoPorSesion cierreCon(Set<Long> practicas) {
		return new ConsumoPorSesion(ORG, PERSONA, SEDE, SESION, HOY, 1, 42L, practicas);
	}

	/** Una autorizacion de una practica concreta, para probar la imputacion de 06.04. */
	private static Autorizacion autorizacionDe(long id, long practicaId, LocalDate hasta) {
		Autorizacion autorizacion = new Autorizacion(
				ORG, PERSONA, SEDE, COBERTURA, null, practicaId, "AUT-" + id,
				EstadoAutorizacion.APROBADA, 10, LocalDate.of(2027, 1, 1), hasta, null, null);
		ReflectionTestUtils.setField(autorizacion, "id", id);
		return autorizacion;
	}

	private static Autorizacion autorizacion(Integer cantidad, LocalDate hasta) {
		Autorizacion autorizacion = new Autorizacion(
				ORG, PERSONA, SEDE, COBERTURA, null, PRACTICA, "AUT-1",
				EstadoAutorizacion.APROBADA, cantidad, LocalDate.of(2027, 1, 1), hasta, null, null);
		ReflectionTestUtils.setField(autorizacion, "id", AUTORIZACION);
		return autorizacion;
	}

	private static AutorizacionMovimiento movimiento(long id, TipoMovimientoAutorizacion tipo) {
		AutorizacionMovimiento movimiento = new AutorizacionMovimiento(
				ORG, AUTORIZACION, PERSONA, SEDE, tipo, 1,
				TipoOrigenMovimiento.SESION, SESION,
				tipo.exigeMotivo() ? "Motivo declarado" : null,
				null, Instant.parse("2027-03-15T12:00:00Z"), 42L);
		ReflectionTestUtils.setField(movimiento, "id", id);
		return movimiento;
	}

	private static AutorizacionMovimiento conId(
			org.mockito.invocation.InvocationOnMock invocacion, long id) {

		AutorizacionMovimiento guardado = invocacion.getArgument(0);
		ReflectionTestUtils.setField(guardado, "id", id);
		return guardado;
	}
}
