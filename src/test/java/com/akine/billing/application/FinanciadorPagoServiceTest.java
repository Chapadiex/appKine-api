package com.akine.billing.application;

import com.akine.billing.domain.FinanciadorPago;
import com.akine.billing.domain.JornadaCaja;
import com.akine.billing.domain.MedioDePago;
import com.akine.billing.domain.MovimientoCaja;
import com.akine.billing.domain.Presentacion;
import com.akine.billing.domain.exception.PresentacionEstadoInvalidoException;
import com.akine.billing.domain.exception.PresentacionNotAccessibleException;
import com.akine.billing.domain.exception.PresentacionSaldoInsuficienteException;
import com.akine.billing.domain.port.FinanciadorPagoRepositoryPort;
import com.akine.billing.domain.port.JornadaCajaRepositoryPort;
import com.akine.billing.domain.port.PresentacionRepositoryPort;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Los pagos de un financiador sobre un lote presentado (M21, AKINE-07.04).
 *
 * <h2>Lo que decide la correctitud</h2>
 *
 * <p><b>El saldo lo descuenta la base, no un {@code if}.</b> {@code registrarCobro} es un
 * {@code UPDATE ... WHERE saldo >= :importe}: si afecta cero filas, el pago no entra. Por eso el
 * servicio no pregunta primero y escribe despues — un aviso de debito y una transferencia cargados
 * a la vez se colarian los dos y dejarian el saldo del lote en negativo.
 *
 * <p><b>Cero filas no significa siempre lo mismo</b>, y distinguirlo es la mitad del valor: puede
 * ser que no alcance el saldo —409 con el disponible adentro, para que el operador entienda que
 * hubo un debito en vez de reintentar a ciegas— o que el lote ya no este en curso, que es otro
 * problema y lleva a otra accion.
 *
 * <p><b>Un pago que no es efectivo NO exige caja abierta.</b> Esa plata nunca tocó el cajon: se
 * asienta con la fecha del pago y sin jornada, y exigir una caja abierta para registrar una
 * transferencia bloquearia la conciliacion de un lote por un motivo que no tiene nada que ver.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FinanciadorPagoServiceTest {

	private static final long ORG_ID = 1L;
	private static final long CONSULTORIO_ID = 7L;
	private static final long PRESENTACION_ID = 11L;
	private static final long FINANCIADOR_ID = 3L;
	private static final long PAGO_ID = 90L;
	private static final long CUENTA = 99L;
	private static final BigDecimal IMPORTE = new BigDecimal("100000.00");

	@Mock private FinanciadorPagoRepositoryPort pagos;
	@Mock private PresentacionRepositoryPort presentaciones;
	@Mock private JornadaCajaRepositoryPort jornadas;
	@Mock private MovimientoCajaService movimientos;
	@Mock private PresentacionAcceso acceso;
	@Mock private AuditTrail auditTrail;

	private FinanciadorPagoService service;

	private final OperatingActor actor = new OperatingActor(CUENTA, false, ORG_ID, CONSULTORIO_ID);

	@BeforeEach
	void setUp() {
		service = new FinanciadorPagoService(
				pagos, presentaciones, jornadas, movimientos, acceso, auditTrail);

		given(acceso.exigirSedeDelTenant(ORG_ID, CONSULTORIO_ID)).willReturn(sede());
		given(presentaciones.findByIdInScope(ORG_ID, CONSULTORIO_ID, PRESENTACION_ID))
				.willReturn(Optional.of(presentacion()));
		given(presentaciones.registrarCobro(anyLong(), anyLong(), any())).willReturn(1);
		given(pagos.save(any())).willAnswer(FinanciadorPagoServiceTest::conIdComoJpa);
		given(jornadas.findAbierta(anyLong(), anyLong())).willReturn(Optional.empty());
		// El doble del movimiento se arma ANTES de usarlo como respuesta: stubear un mock dentro de
		// los argumentos de otro `given` deja a Mockito con un stubbing a medio terminar y falla
		// con `UnfinishedStubbing` en el test siguiente, no en este.
		MovimientoCaja movimientoAsentado = org.mockito.Mockito.mock(MovimientoCaja.class);
		given(movimientoAsentado.getId()).willReturn(770L);
		given(movimientos.asentar(anyLong(), anyLong(), any(), any(), any(), any(), any(), any(),
				anyString(), any(), any(), any(), any(), any(), anyLong(), any(), any()))
				.willReturn(movimientoAsentado);
		given(pagos.findDeLaPresentacion(anyLong())).willReturn(List.of());
	}

	@Test
	@DisplayName("El pago descuenta por UPDATE condicional, no por un if del servicio")
	void el_saldo_lo_descuenta_la_base() {
		// Es lo unico que impide que un aviso de debito y una transferencia cargados a la vez se
		// cuelen los dos y dejen el saldo del lote en negativo.
		service.registrar(actor, CONSULTORIO_ID, PRESENTACION_ID, pago(null));

		verify(presentaciones).registrarCobro(ORG_ID, PRESENTACION_ID, IMPORTE);
		verify(pagos).save(any(FinanciadorPago.class));
	}

	@Test
	@DisplayName("Cero filas CON el lote en curso es saldo insuficiente, con el disponible")
	void cero_filas_por_saldo() {
		// Sin el disponible, el operador reintenta a ciegas en vez de entender que hubo un debito.
		given(presentaciones.findByIdInScope(ORG_ID, CONSULTORIO_ID, PRESENTACION_ID))
				.willReturn(Optional.of(presentacionPresentada()));
		given(presentaciones.registrarCobro(anyLong(), anyLong(), any())).willReturn(0);

		assertThatThrownBy(() ->
				service.registrar(actor, CONSULTORIO_ID, PRESENTACION_ID, pago(null)))
				.isInstanceOf(PresentacionSaldoInsuficienteException.class);

		verify(pagos, never()).save(any());
	}

	@Test
	@DisplayName("Cero filas con el lote FUERA de curso es otro problema, y lleva a otra accion")
	void cero_filas_por_estado() {
		// El UPDATE condicional devuelve cero por dos motivos distintos y el servicio los
		// distingue releyendo: un lote en BORRADOR no se cobra, y decirle al operador que -no
		// alcanza el saldo- lo mandaria a buscar plata que no es el problema.
		given(presentaciones.registrarCobro(anyLong(), anyLong(), any())).willReturn(0);

		assertThatThrownBy(() ->
				service.registrar(actor, CONSULTORIO_ID, PRESENTACION_ID, pago(null)))
				.isInstanceOf(PresentacionEstadoInvalidoException.class);

		verify(pagos, never()).save(any());
	}

	@Test
	@DisplayName("Un lote que no es de esa sede da 404 antes de intentar cobrar")
	void presentacion_de_otra_sede() {
		given(presentaciones.findByIdInScope(ORG_ID, CONSULTORIO_ID, PRESENTACION_ID))
				.willReturn(Optional.empty());

		assertThatThrownBy(() ->
				service.registrar(actor, CONSULTORIO_ID, PRESENTACION_ID, pago(null)))
				.isInstanceOf(PresentacionNotAccessibleException.class);

		verify(presentaciones, never()).registrarCobro(anyLong(), anyLong(), any());
	}

	@Test
	@DisplayName("El pago se asienta en caja aunque NO haya jornada abierta")
	void sin_jornada_abierta_igual_se_asienta() {
		// Una transferencia nunca toca el cajon: exigir caja abierta para registrarla bloquearia la
		// conciliacion de un lote por un motivo que no tiene nada que ver con la plata fisica.
		given(jornadas.findAbierta(anyLong(), anyLong())).willReturn(Optional.empty());

		service.registrar(actor, CONSULTORIO_ID, PRESENTACION_ID, pago(null));

		verify(movimientos).asentar(anyLong(), anyLong(), any(), any(), any(), any(), any(), any(),
				anyString(), any(), any(), any(), any(), any(), anyLong(), any(), any());
	}

	@Test
	@DisplayName("Registrar un pago deja su evento de auditoria")
	void el_pago_se_audita() {
		service.registrar(actor, CONSULTORIO_ID, PRESENTACION_ID, pago(null));

		verify(auditTrail).record(any(AuditEntry.class));
	}

	@Test
	@DisplayName("El reintento con la misma clave y el mismo cuerpo no vuelve a cobrar")
	void reintento_idempotente() {
		// Si volviera a cobrar, el lote quedaria con dos pagos por una sola transferencia.
		PresentacionCommands.Pago command = pago("clave-1");
		FinanciadorPago existente = pagoExistente();
		ReflectionTestUtils.setField(
				existente, "requestHash", command.huella(PRESENTACION_ID));
		given(pagos.findByIdempotencyKey(ORG_ID, "clave-1")).willReturn(Optional.of(existente));

		FinanciadorPagoView vista = service.registrar(
				actor, CONSULTORIO_ID, PRESENTACION_ID, command);

		assertThat(vista.id()).isEqualTo(PAGO_ID);
		verify(presentaciones, never()).registrarCobro(anyLong(), anyLong(), any());
		verify(pagos, never()).save(any());
	}

	@Test
	@DisplayName("La misma clave con otro cuerpo es conflicto, no reintento")
	void misma_clave_otro_cuerpo() {
		FinanciadorPago existente = pagoExistente();
		ReflectionTestUtils.setField(existente, "requestHash", "otra-huella");
		given(pagos.findByIdempotencyKey(ORG_ID, "clave-1")).willReturn(Optional.of(existente));

		assertThatThrownBy(() ->
				service.registrar(actor, CONSULTORIO_ID, PRESENTACION_ID, pago("clave-1")))
				.isInstanceOf(IdempotencyKeyConflictException.class);
	}

	@Test
	@DisplayName("Sin clave de idempotencia no se consulta el indice")
	void sin_clave_no_se_consulta() {
		service.registrar(actor, CONSULTORIO_ID, PRESENTACION_ID, pago(null));

		verify(pagos, never()).findByIdempotencyKey(anyLong(), anyString());
	}

	@Test
	@DisplayName("Listar los pagos de un lote exige sede del tenant y permiso, en ese orden")
	void listar_pasa_por_las_puertas() {
		// La sede se resuelve ANTES del permiso: una sede de otro tenant da 404 y no 403.
		service.deLaPresentacion(actor, CONSULTORIO_ID, PRESENTACION_ID);

		verify(acceso).exigirSedeDelTenant(ORG_ID, CONSULTORIO_ID);
		verify(pagos).findDeLaPresentacion(PRESENTACION_ID);
	}

	// =================================================================================
	// Fixtures
	// =================================================================================

	private static PresentacionCommands.Pago pago(String idempotencyKey) {
		return new PresentacionCommands.Pago(IMPORTE, MedioDePago.TRANSFERENCIA,
				LocalDate.of(2026, 9, 30), "TRF-001", idempotencyKey);
	}

	private static ConsultorioSnapshot sede() {
		return new ConsultorioSnapshot(
				CONSULTORIO_ID, ORG_ID, "Sede", "America/Argentina/Cordoba", true);
	}

	private static Presentacion presentacion() {
		Presentacion presentacion = new Presentacion(ORG_ID, CONSULTORIO_ID, FINANCIADOR_ID,
				LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), "ARS", Instant.EPOCH, CUENTA);
		ReflectionTestUtils.setField(presentacion, "id", PRESENTACION_ID);
		return presentacion;
	}

	/** Un lote ya confirmado, que es el unico estado en el que un pago puede entrar. */
	private static Presentacion presentacionPresentada() {
		Presentacion presentacion = presentacion();
		presentacion.confirmar(1, Instant.EPOCH, CUENTA);
		return presentacion;
	}

	private static FinanciadorPago pagoExistente() {
		FinanciadorPago pago = new FinanciadorPago(ORG_ID, CONSULTORIO_ID, FINANCIADOR_ID,
				PRESENTACION_ID, IMPORTE, "ARS", MedioDePago.TRANSFERENCIA,
				LocalDate.of(2026, 9, 30), "TRF-001", Instant.EPOCH, CUENTA, "clave-1", null);
		ReflectionTestUtils.setField(pago, "id", PAGO_ID);
		return pago;
	}

	/** JPA asigna el id al persistir; el doble tiene que hacer lo mismo o el fixture mentiria. */
	private static FinanciadorPago conIdComoJpa(org.mockito.invocation.InvocationOnMock i) {
		FinanciadorPago guardado = i.getArgument(0);
		if (guardado.getId() == null) {
			ReflectionTestUtils.setField(guardado, "id", PAGO_ID);
		}
		return guardado;
	}
}
