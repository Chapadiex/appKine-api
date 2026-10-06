package com.akine.billing.application;

import com.akine.billing.domain.JornadaCaja;
import com.akine.billing.domain.MedioDePago;
import com.akine.billing.domain.MovimientoCaja;
import com.akine.billing.domain.OrigenMovimiento;
import com.akine.billing.domain.TipoMovimiento;
import com.akine.billing.domain.exception.CajaCerradaException;
import com.akine.billing.domain.exception.CajaNoAbiertaException;
import com.akine.billing.domain.exception.MovimientoCajaNotAccessibleException;
import com.akine.billing.domain.exception.MovimientoNoReversibleException;
import com.akine.billing.domain.port.JornadaCajaRepositoryPort;
import com.akine.billing.domain.port.MovimientoCajaRepositoryPort;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Los movimientos de la caja (M20, AKINE-07.03).
 *
 * <h2>Lo que decide la correctitud</h2>
 *
 * <p><b>Una reversion no se revierte.</b> Para deshacerla se asienta un movimiento nuevo: revertir
 * la reversion dejaria dos filas que se anulan entre si y un ledger que ya no explica el saldo. Por
 * lo mismo, un movimiento ya revertido no se revierte dos veces — eso sacaria plata del cajon dos
 * veces por un solo error.
 *
 * <p><b>Y la reversion cae en la jornada abierta HOY, no en la del original</b> (RN-M20-003). Una
 * caja cerrada no se edita: el arqueo de ayer ya se firmo, y meterle una fila despues haria que el
 * cierre dejara de cuadrar contra lo que el operador conto.
 *
 * <p><b>Lo que NO afecta el arqueo no exige caja abierta.</b> Una transferencia nunca toco el
 * cajon, asi que revertirla no puede quedar bloqueada porque nadie abrio la caja esta manana.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MovimientoCajaServiceTest {

	private static final long ORG_ID = 1L;
	private static final long CONSULTORIO_ID = 7L;
	private static final long JORNADA_ID = 55L;
	private static final long MOVIMIENTO_ID = 770L;
	private static final long CUENTA = 99L;
	private static final BigDecimal IMPORTE = new BigDecimal("2500.00");

	@Mock private MovimientoCajaRepositoryPort movimientos;
	@Mock private JornadaCajaRepositoryPort jornadas;
	@Mock private CajaAcceso acceso;
	@Mock private AuditTrail auditTrail;

	private MovimientoCajaService service;

	private final OperatingActor actor = new OperatingActor(CUENTA, false, ORG_ID, CONSULTORIO_ID);

	@BeforeEach
	void setUp() {
		service = new MovimientoCajaService(movimientos, jornadas, acceso, auditTrail);

		given(acceso.exigirSedeDelTenant(ORG_ID, CONSULTORIO_ID)).willReturn(sede());
		given(jornadas.findAbierta(ORG_ID, CONSULTORIO_ID)).willReturn(Optional.of(jornada()));
		given(movimientos.save(any())).willAnswer(MovimientoCajaServiceTest::conIdComoJpa);
		given(movimientos.findByIdInScope(ORG_ID, CONSULTORIO_ID, MOVIMIENTO_ID))
				.willReturn(Optional.of(ingresoEnEfectivo()));
		given(movimientos.existeReversionDe(anyLong(), anyLong())).willReturn(false);
		// El saldo se mueve con un UPDATE condicional: una fila afectada es el camino feliz. Cero
		// filas significa que la jornada se cerro o que no alcanza, y eso tiene sus propios casos.
		given(jornadas.sumarAlSaldo(anyLong(), anyLong(), any())).willReturn(1);
		given(jornadas.restarDelSaldo(anyLong(), anyLong(), any())).willReturn(1);
		given(movimientos.buscar(anyLong(), anyLong(), any(), any(), any(), anyInt(), anyInt()))
				.willReturn(List.of());
	}

	@Nested
	@DisplayName("El movimiento manual")
	class Manual {

		@Test
		@DisplayName("Un ingreso manual entra en la jornada abierta y queda auditado")
		void ingreso_manual() {
			service.registrarManual(actor, CONSULTORIO_ID, comando(TipoMovimiento.INGRESO, null));

			verify(movimientos).save(any(MovimientoCaja.class));
			verify(auditTrail).record(any(AuditEntry.class));
		}

		@Test
		@DisplayName("Sin caja abierta el movimiento manual no entra")
		void sin_caja_abierta() {
			// La plata entra al cajon exista o no la jornada, y si el sistema no sabe a cual
			// pertenece, el arqueo de ese dia no cuadra contra nada.
			given(jornadas.findAbierta(ORG_ID, CONSULTORIO_ID)).willReturn(Optional.empty());

			assertThatThrownBy(() -> service.registrarManual(
					actor, CONSULTORIO_ID, comando(TipoMovimiento.INGRESO, null)))
					.isInstanceOf(CajaNoAbiertaException.class);

			verify(movimientos, never()).save(any());
		}

		@Test
		@DisplayName("Un ingreso que no mueve el saldo es caja-cerrada, aunque la relectura la vea abierta")
		void ingreso_contra_jornada_que_cerro() {
			// El WHERE de la suma no mira el saldo: cero filas solo puede ser que cerro. La
			// relectura bajo REPEATABLE READ —el cobro— devuelve la foto vieja, ABIERTA, y antes
			// eso terminaba en caja-saldo-insuficiente sobre un ingreso. Ver
			// CierreDeCajaConcurrenteIT.
			given(jornadas.sumarAlSaldo(anyLong(), anyLong(), any())).willReturn(0);
			given(jornadas.findByIdInScope(ORG_ID, CONSULTORIO_ID, JORNADA_ID))
					.willReturn(Optional.of(jornada()));

			assertThatThrownBy(() -> service.registrarManual(
					actor, CONSULTORIO_ID, comando(TipoMovimiento.INGRESO, null)))
					.isInstanceOf(CajaCerradaException.class);

			verify(movimientos, never()).save(any());
		}

		@Test
		@DisplayName("Una REVERSION no se carga como movimiento manual")
		void reversion_a_mano_no() {
			// Una reversion exige el movimiento que compensa: cargarla a mano dejaria una fila que
			// dice -reversion- sin decir de que, y el ledger perderia la trazabilidad del par.
			assertThatThrownBy(() -> service.registrarManual(
					actor, CONSULTORIO_ID, comando(TipoMovimiento.REVERSION_DE_INGRESO, null)))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("El reintento con la misma clave y el mismo cuerpo no duplica el movimiento")
		void reintento_idempotente() {
			MovimientoManualCommand command = comando(TipoMovimiento.INGRESO, "clave-1");
			MovimientoCaja existente = ingresoEnEfectivo();
			ReflectionTestUtils.setField(
					existente, "requestHash", command.huella(CONSULTORIO_ID));
			given(movimientos.findByIdempotencyKey(ORG_ID, "clave-1"))
					.willReturn(Optional.of(existente));

			service.registrarManual(actor, CONSULTORIO_ID, command);

			verify(movimientos, never()).save(any());
		}

		@Test
		@DisplayName("La misma clave con otro cuerpo es conflicto")
		void misma_clave_otro_cuerpo() {
			MovimientoCaja existente = ingresoEnEfectivo();
			ReflectionTestUtils.setField(existente, "requestHash", "otra-huella");
			given(movimientos.findByIdempotencyKey(ORG_ID, "clave-1"))
					.willReturn(Optional.of(existente));

			assertThatThrownBy(() -> service.registrarManual(
					actor, CONSULTORIO_ID, comando(TipoMovimiento.INGRESO, "clave-1")))
					.isInstanceOf(IdempotencyKeyConflictException.class);
		}
	}

	@Nested
	@DisplayName("La reversion")
	class Reversion {

		@Test
		@DisplayName("Revertir asienta un movimiento NUEVO, con el tipo opuesto")
		void revertir_asienta_el_opuesto() {
			// No se borra ni se edita el original: el ledger es append-only, y lo que explica el
			// saldo es el par de filas.
			service.revertir(actor, CONSULTORIO_ID, MOVIMIENTO_ID, "Se cobro de mas");

			ArgumentCaptor<MovimientoCaja> guardado =
					ArgumentCaptor.forClass(MovimientoCaja.class);
			verify(movimientos).save(guardado.capture());
			assertThat(guardado.getValue().getTipo()).isEqualTo(TipoMovimiento.REVERSION_DE_INGRESO);
			verify(auditTrail).record(any(AuditEntry.class));
		}

		@Test
		@DisplayName("Una reversion NO se revierte: para deshacerla se asienta un movimiento nuevo")
		void la_reversion_no_se_revierte() {
			// Revertir la reversion dejaria dos filas que se anulan entre si y un ledger que ya no
			// explica el saldo.
			given(movimientos.findByIdInScope(ORG_ID, CONSULTORIO_ID, MOVIMIENTO_ID))
					.willReturn(Optional.of(unaReversion()));

			assertThatThrownBy(() ->
					service.revertir(actor, CONSULTORIO_ID, MOVIMIENTO_ID, "Error"))
					.isInstanceOf(MovimientoNoReversibleException.class);
		}

		@Test
		@DisplayName("Un movimiento ya revertido no se revierte dos veces")
		void no_se_revierte_dos_veces() {
			// Sacaria plata del cajon dos veces por un solo error.
			given(movimientos.existeReversionDe(ORG_ID, MOVIMIENTO_ID)).willReturn(true);

			assertThatThrownBy(() ->
					service.revertir(actor, CONSULTORIO_ID, MOVIMIENTO_ID, "Error"))
					.isInstanceOf(MovimientoNoReversibleException.class);

			verify(movimientos, never()).save(any());
		}

		@Test
		@DisplayName("Revertir efectivo SIN caja abierta no se puede")
		void revertir_efectivo_sin_caja() {
			// Esa plata sale del cajon hoy: sin jornada abierta no hay arqueo al que atribuirla.
			given(jornadas.findAbierta(ORG_ID, CONSULTORIO_ID)).willReturn(Optional.empty());

			assertThatThrownBy(() ->
					service.revertir(actor, CONSULTORIO_ID, MOVIMIENTO_ID, "Error"))
					.isInstanceOf(CajaNoAbiertaException.class);
		}

		@Test
		@DisplayName("Un movimiento de otra sede da 404")
		void movimiento_de_otra_sede() {
			given(movimientos.findByIdInScope(ORG_ID, CONSULTORIO_ID, MOVIMIENTO_ID))
					.willReturn(Optional.empty());

			assertThatThrownBy(() ->
					service.revertir(actor, CONSULTORIO_ID, MOVIMIENTO_ID, "Error"))
					.isInstanceOf(MovimientoCajaNotAccessibleException.class);
		}
	}

	@Test
	@DisplayName("La busqueda acota el limite entre 1 y el tope, y el desplazamiento no es negativo")
	void la_busqueda_se_acota() {
		service.buscar(actor, CONSULTORIO_ID, JORNADA_ID, LocalDate.of(2026, 9, 30), "INGRESO",
				100_000, -3);

		verify(movimientos).buscar(ORG_ID, CONSULTORIO_ID, JORNADA_ID,
				LocalDate.of(2026, 9, 30), "INGRESO", 200, 0);
	}

	// =================================================================================
	// Fixtures
	// =================================================================================

	private static MovimientoManualCommand comando(TipoMovimiento tipo, String idempotencyKey) {
		return new MovimientoManualCommand(
				tipo, MedioDePago.EFECTIVO, IMPORTE, "Vuelto inicial", idempotencyKey);
	}

	private static ConsultorioSnapshot sede() {
		return new ConsultorioSnapshot(
				CONSULTORIO_ID, ORG_ID, "Sede", "America/Argentina/Cordoba", true);
	}

	private static JornadaCaja jornada() {
		JornadaCaja jornada = new JornadaCaja(ORG_ID, CONSULTORIO_ID, LocalDate.of(2026, 9, 30),
				"ARS", BigDecimal.ZERO, Instant.EPOCH, CUENTA);
		ReflectionTestUtils.setField(jornada, "id", JORNADA_ID);
		return jornada;
	}

	private static MovimientoCaja ingresoEnEfectivo() {
		return movimiento(TipoMovimiento.INGRESO, MedioDePago.EFECTIVO, OrigenMovimiento.MANUAL);
	}

	/**
	 * Una reversion ya asentada.
	 *
	 * <p>Lleva motivo y el id del movimiento que compensa porque la propia entidad lo exige: una
	 * fila que dice "reversion" sin decir de que ni por que no explica nada en el ledger.
	 */
	private static MovimientoCaja unaReversion() {
		MovimientoCaja movimiento = new MovimientoCaja(ORG_ID, CONSULTORIO_ID, JORNADA_ID,
				LocalDate.of(2026, 9, 30), TipoMovimiento.REVERSION_DE_INGRESO,
				MedioDePago.EFECTIVO, IMPORTE, "ARS", "Reversion del movimiento 769",
				"Se cobro de mas", OrigenMovimiento.REVERSION, 769L, 769L, Instant.EPOCH, CUENTA,
				null, null);
		ReflectionTestUtils.setField(movimiento, "id", MOVIMIENTO_ID);
		return movimiento;
	}

	private static MovimientoCaja movimiento(
			TipoMovimiento tipo, MedioDePago medio, OrigenMovimiento origen) {

		MovimientoCaja movimiento = new MovimientoCaja(ORG_ID, CONSULTORIO_ID, JORNADA_ID,
				LocalDate.of(2026, 9, 30), tipo, medio, IMPORTE, "ARS", "Concepto", null,
				origen, null, null, Instant.EPOCH, CUENTA, null, null);
		ReflectionTestUtils.setField(movimiento, "id", MOVIMIENTO_ID);
		return movimiento;
	}

	/** JPA asigna el id al persistir; el doble tiene que hacer lo mismo o el fixture mentiria. */
	private static MovimientoCaja conIdComoJpa(org.mockito.invocation.InvocationOnMock i) {
		MovimientoCaja guardado = i.getArgument(0);
		if (guardado.getId() == null) {
			ReflectionTestUtils.setField(guardado, "id", MOVIMIENTO_ID + 1);
		}
		return guardado;
	}
}
