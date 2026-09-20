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
		return new ConsumoPorSesion(ORG, PERSONA, SEDE, SESION, HOY, 1, 42L);
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
