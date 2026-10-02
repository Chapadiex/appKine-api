package com.akine.billing.application;

import com.akine.billing.domain.CategoriaEgreso;
import com.akine.billing.domain.Egreso;
import com.akine.billing.domain.MedioDePago;
import com.akine.billing.domain.MovimientoCaja;
import com.akine.billing.domain.PagoEgreso;
import com.akine.billing.domain.TipoBeneficiario;
import com.akine.billing.domain.exception.EgresoNoPagableException;
import com.akine.billing.domain.exception.EgresoNotAccessibleException;
import com.akine.billing.domain.exception.EgresoSaldoInsuficienteException;
import com.akine.billing.domain.exception.PagoEgresoNotAccessibleException;
import com.akine.billing.domain.exception.PagoEgresoYaAnuladoException;
import com.akine.billing.domain.port.EgresoRepositoryPort;
import com.akine.billing.domain.port.MovimientoCajaRepositoryPort;
import com.akine.billing.domain.port.PagoEgresoRepositoryPort;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * El pago de un egreso (M22, AKINE-07.05).
 *
 * <h2>Lo que decide la correctitud</h2>
 *
 * <p><b>El saldo lo descuenta la base.</b> {@code descontarSaldo} es un {@code UPDATE} condicional:
 * dos pagos simultaneos del mismo egreso no pueden llevarse los dos el ultimo peso. Y cuando afecta
 * cero filas, el servicio relee para distinguir <b>por que</b> — el egreso dejo de admitir pagos, o
 * no alcanza el saldo— porque son dos problemas con acciones distintas.
 *
 * <p><b>Anular un pago no borra nada: revierte.</b> Se asienta la reversion en la caja, se devuelve
 * el saldo al egreso y se recalcula su estado. Borrar la fila dejaria el movimiento de caja
 * huerfano y el arqueo del dia sin explicacion.
 *
 * <p><b>Si el pago no tiene movimiento de caja, el servicio FALLA en vez de seguir.</b> Significa
 * que el ledger y los pagos divergieron, y continuar devolveria un saldo que la caja nunca movio.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PagoEgresoServiceTest {

	private static final long ORG_ID = 1L;
	private static final long CONSULTORIO_ID = 7L;
	private static final long EGRESO_ID = 400L;
	private static final long PAGO_ID = 50L;
	private static final long MOVIMIENTO_ID = 770L;
	private static final long CUENTA = 99L;
	private static final BigDecimal IMPORTE = new BigDecimal("15000.00");

	@Mock private EgresoRepositoryPort egresos;
	@Mock private PagoEgresoRepositoryPort pagos;
	@Mock private MovimientoCajaRepositoryPort movimientos;
	@Mock private MovimientoCajaService movimientoService;
	@Mock private CajaDeEgreso cajaDeEgreso;
	@Mock private CajaAcceso acceso;
	@Mock private AuditTrail auditTrail;

	private PagoEgresoService service;

	private final OperatingActor actor = new OperatingActor(CUENTA, false, ORG_ID, CONSULTORIO_ID);

	@BeforeEach
	void setUp() {
		service = new PagoEgresoService(egresos, pagos, movimientos, movimientoService,
				cajaDeEgreso, acceso, auditTrail);

		given(acceso.exigirSedeDelTenant(ORG_ID, CONSULTORIO_ID)).willReturn(sede());
		given(egresos.findByIdInScope(ORG_ID, CONSULTORIO_ID, EGRESO_ID))
				.willReturn(Optional.of(egresoConfirmado()));
		given(egresos.descontarSaldo(anyLong(), anyLong(), any())).willReturn(1);
		given(pagos.save(any())).willAnswer(PagoEgresoServiceTest::conIdComoJpa);
		given(pagos.findByIdInScope(ORG_ID, EGRESO_ID, PAGO_ID))
				.willReturn(Optional.of(pagoVigente()));
		// El doble del movimiento se arma ANTES de usarlo como respuesta: stubear un mock dentro de
		// los argumentos de otro `given` deja a Mockito con un stubbing a medio terminar.
		MovimientoCaja movimientoDeCaja = org.mockito.Mockito.mock(MovimientoCaja.class);
		given(movimientoDeCaja.getId()).willReturn(MOVIMIENTO_ID);
		given(movimientos.findPorOrigen(anyLong(), anyString(), anyLong()))
				.willReturn(Optional.of(movimientoDeCaja));
	}

	@Test
	@DisplayName("Pagar descuenta por UPDATE condicional, asienta la salida y audita")
	void pagar_el_camino_feliz() {
		service.pagar(actor, CONSULTORIO_ID, EGRESO_ID, comando(null));

		verify(egresos).descontarSaldo(ORG_ID, EGRESO_ID, IMPORTE);
		verify(egresos).actualizarEstadoPorSaldo(ORG_ID, EGRESO_ID);
		verify(cajaDeEgreso).registrarSalida(anyLong(), any(), anyLong(), any(), any(), anyString(),
				anyString(), any(), anyLong());
		verify(auditTrail).record(any(AuditEntry.class));
	}

	@Test
	@DisplayName("Un egreso en BORRADOR no se paga: solo un confirmado mueve la caja")
	void borrador_no_se_paga() {
		// Es como esta etapa lee RN-M22-001: el compromiso se congela antes de que salga la plata.
		given(egresos.findByIdInScope(ORG_ID, CONSULTORIO_ID, EGRESO_ID))
				.willReturn(Optional.of(borrador()));

		assertThatThrownBy(() ->
				service.pagar(actor, CONSULTORIO_ID, EGRESO_ID, comando(null)))
				.isInstanceOf(EgresoNoPagableException.class);

		verify(pagos, never()).save(any());
	}

	@Test
	@DisplayName("Cero filas al descontar es saldo insuficiente, con el pendiente adentro")
	void cero_filas_por_saldo() {
		// 409 y no 400: el importe era valido cuando se compuso, y lo que cambio es el estado del
		// servidor porque otro pago se llevo el saldo primero.
		given(egresos.descontarSaldo(anyLong(), anyLong(), any())).willReturn(0);

		assertThatThrownBy(() ->
				service.pagar(actor, CONSULTORIO_ID, EGRESO_ID, comando(null)))
				.isInstanceOf(EgresoSaldoInsuficienteException.class);

		verify(pagos, never()).save(any());
		verify(cajaDeEgreso, never()).registrarSalida(anyLong(), any(), anyLong(), any(), any(),
				anyString(), anyString(), any(), anyLong());
	}

	@Test
	@DisplayName("Un egreso de otra sede da 404 antes de tocar la caja")
	void egreso_de_otra_sede() {
		given(egresos.findByIdInScope(ORG_ID, CONSULTORIO_ID, EGRESO_ID))
				.willReturn(Optional.empty());

		assertThatThrownBy(() ->
				service.pagar(actor, CONSULTORIO_ID, EGRESO_ID, comando(null)))
				.isInstanceOf(EgresoNotAccessibleException.class);
	}

	@Test
	@DisplayName("El reintento con la misma clave y el mismo cuerpo no paga dos veces")
	void reintento_idempotente() {
		PagoEgresoCommand command = comando("clave-1");
		PagoEgreso existente = pagoVigente();
		ReflectionTestUtils.setField(existente, "requestHash", command.huella(EGRESO_ID));
		given(pagos.findByIdempotencyKey(ORG_ID, "clave-1")).willReturn(Optional.of(existente));

		service.pagar(actor, CONSULTORIO_ID, EGRESO_ID, command);

		verify(egresos, never()).descontarSaldo(anyLong(), anyLong(), any());
		verify(pagos, never()).save(any());
	}

	@Test
	@DisplayName("La misma clave con otro cuerpo es conflicto, no reintento")
	void misma_clave_otro_cuerpo() {
		PagoEgreso existente = pagoVigente();
		ReflectionTestUtils.setField(existente, "requestHash", "otra-huella");
		given(pagos.findByIdempotencyKey(ORG_ID, "clave-1")).willReturn(Optional.of(existente));

		assertThatThrownBy(() ->
				service.pagar(actor, CONSULTORIO_ID, EGRESO_ID, comando("clave-1")))
				.isInstanceOf(IdempotencyKeyConflictException.class);
	}

	@Test
	@DisplayName("Anular un pago REVIERTE la caja y devuelve el saldo: no borra nada")
	void anular_revierte() {
		// Borrar la fila dejaria el movimiento de caja huerfano y el arqueo del dia sin explicacion.
		service.anularPago(actor, CONSULTORIO_ID, EGRESO_ID, PAGO_ID, "Se pago dos veces");

		verify(movimientoService).revertir(actor, CONSULTORIO_ID, MOVIMIENTO_ID, "Se pago dos veces");
		verify(egresos).devolverSaldo(ORG_ID, EGRESO_ID, IMPORTE);
		verify(egresos).actualizarEstadoPorSaldo(ORG_ID, EGRESO_ID);
		verify(auditTrail).record(any(AuditEntry.class));
	}

	@Test
	@DisplayName("Anular dos veces el mismo pago no pasa")
	void anular_lo_anulado() {
		// Revertir dos veces sacaria plata del cajon dos veces.
		PagoEgreso anulado = pagoVigente();
		anulado.anular("Se pago dos veces", Instant.EPOCH, CUENTA);
		given(pagos.findByIdInScope(ORG_ID, EGRESO_ID, PAGO_ID)).willReturn(Optional.of(anulado));

		assertThatThrownBy(() -> service.anularPago(
				actor, CONSULTORIO_ID, EGRESO_ID, PAGO_ID, "De nuevo"))
				.isInstanceOf(PagoEgresoYaAnuladoException.class);
	}

	@Test
	@DisplayName("Un pago que no es de ese egreso da 404")
	void pago_de_otro_egreso() {
		given(pagos.findByIdInScope(ORG_ID, EGRESO_ID, PAGO_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.anularPago(
				actor, CONSULTORIO_ID, EGRESO_ID, PAGO_ID, "Error"))
				.isInstanceOf(PagoEgresoNotAccessibleException.class);
	}

	@Test
	@DisplayName("Si el pago no tiene movimiento de caja, el servicio FALLA en vez de seguir")
	void sin_movimiento_de_caja() {
		// Significa que el ledger y los pagos divergieron. Seguir devolveria un saldo que la caja
		// nunca movio, y la divergencia quedaria tapada.
		given(movimientos.findPorOrigen(anyLong(), anyString(), anyLong()))
				.willReturn(Optional.empty());

		assertThatThrownBy(() -> service.anularPago(
				actor, CONSULTORIO_ID, EGRESO_ID, PAGO_ID, "Error"))
				.isInstanceOf(IllegalStateException.class);

		verify(egresos, never()).devolverSaldo(anyLong(), anyLong(), any());
	}

	// =================================================================================
	// Fixtures
	// =================================================================================

	private static PagoEgresoCommand comando(String idempotencyKey) {
		return new PagoEgresoCommand(IMPORTE, MedioDePago.TRANSFERENCIA, "TRF-9", idempotencyKey);
	}

	private static ConsultorioSnapshot sede() {
		return new ConsultorioSnapshot(
				CONSULTORIO_ID, ORG_ID, "Sede", "America/Argentina/Cordoba", true);
	}

	private static Egreso borrador() {
		Egreso egreso = new Egreso(ORG_ID, CONSULTORIO_ID, CategoriaEgreso.SERVICIOS,
				TipoBeneficiario.EXTERNO, null, "E:PROVEEDOR", "Proveedor SA", null,
				LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), "Internet", IMPORTE, "ARS",
				Instant.EPOCH, CUENTA, null, null);
		ReflectionTestUtils.setField(egreso, "id", EGRESO_ID);
		return egreso;
	}

	private static Egreso egresoConfirmado() {
		Egreso egreso = borrador();
		egreso.editarBorrador(CategoriaEgreso.SERVICIOS, LocalDate.of(2026, 9, 1),
				LocalDate.of(2026, 9, 30), "Internet", IMPORTE, "FACTURA", "0001-00000001",
				LocalDate.of(2026, 9, 30));
		egreso.confirmar(Instant.EPOCH, CUENTA);
		return egreso;
	}

	private static PagoEgreso pagoVigente() {
		PagoEgreso pago = new PagoEgreso(ORG_ID, CONSULTORIO_ID, EGRESO_ID, IMPORTE, "ARS",
				MedioDePago.TRANSFERENCIA, "TRF-9", LocalDate.of(2026, 9, 30), Instant.EPOCH,
				CUENTA, null, null);
		ReflectionTestUtils.setField(pago, "id", PAGO_ID);
		return pago;
	}

	/** JPA asigna el id al persistir; el doble tiene que hacer lo mismo o el fixture mentiria. */
	private static PagoEgreso conIdComoJpa(org.mockito.invocation.InvocationOnMock i) {
		PagoEgreso guardado = i.getArgument(0);
		if (guardado.getId() == null) {
			ReflectionTestUtils.setField(guardado, "id", PAGO_ID);
		}
		return guardado;
	}
}
