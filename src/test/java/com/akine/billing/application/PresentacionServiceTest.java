package com.akine.billing.application;

import com.akine.billing.domain.EstadoItemPresentacion;
import com.akine.billing.domain.EstadoPresentacion;
import com.akine.billing.domain.Obligacion;
import com.akine.billing.domain.Presentacion;
import com.akine.billing.domain.PresentacionItem;
import com.akine.billing.domain.Responsable;
import com.akine.billing.domain.exception.ObligacionYaPresentadaException;
import com.akine.billing.domain.exception.PresentacionNoConciliaException;
import com.akine.billing.domain.exception.PresentacionSaldoInsuficienteException;
import com.akine.billing.domain.port.CobroRepositoryPort;
import com.akine.billing.domain.port.ObligacionRepositoryPort;
import com.akine.billing.domain.port.PresentacionItemRepositoryPort;
import com.akine.billing.domain.port.PresentacionNumeradorPort;
import com.akine.billing.domain.port.PresentacionRepositoryPort;
import com.akine.organization.spi.ConsultorioSnapshot;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Las cinco reglas de M21 que cuestan caro si se rompen.
 *
 * <p>No se cubre el camino feliz de cada operacion: lo que se fija aca es lo que distingue este
 * modelo de uno que colapsa las cuatro cosas —prestado, presentado, facturado y cobrado— en una
 * sola. Todo lo que solo se puede probar contra MySQL real —la columna generada, los CHECK, dos
 * escrituras concurrentes contra el mismo saldo— esta en {@code docs/tests-diferidos.md}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PresentacionService")
class PresentacionServiceTest {

	private static final long ACCOUNT_ID = 40L;
	private static final long ORG_ID = 10L;
	private static final long SEDE_ID = 20L;
	private static final long FINANCIADOR_ID = 31L;
	private static final long PRESENTACION_ID = 1204L;

	@Mock
	private PresentacionRepositoryPort presentaciones;

	@Mock
	private PresentacionItemRepositoryPort items;

	@Mock
	private ObligacionRepositoryPort obligaciones;

	@Mock
	private CobroRepositoryPort cobros;

	@Mock
	private PresentacionNumeradorPort numerador;

	@Mock
	private PresentacionNumeradorIniciador numeradorIniciador;

	@Mock
	private PresentacionAcceso acceso;

	@Mock
	private AuditTrail auditTrail;

	private PresentacionService service;

	private final OperatingActor actor = new OperatingActor(ACCOUNT_ID, false, ORG_ID, SEDE_ID);

	@BeforeEach
	void setUp() {
		service = new PresentacionService(
				presentaciones, items, obligaciones, cobros, numerador, numeradorIniciador,
				acceso, auditTrail);

		given(acceso.exigirSedeDelTenant(anyLong(), anyLong()))
				.willReturn(new ConsultorioSnapshot(
						SEDE_ID, ORG_ID, "Sede Centro", "America/Argentina/Cordoba", true));
	}

	@Test
	@DisplayName("conciliar con residual se rechaza y nombra el numero que falta explicar")
	void conciliarConResidual() {
		Presentacion lote = confirmada(new BigDecimal("100000.00"), new BigDecimal("15000.00"));
		given(presentaciones.findByIdInScope(ORG_ID, SEDE_ID, PRESENTACION_ID))
				.willReturn(Optional.of(lote));

		assertThatThrownBy(() -> service.conciliar(actor, SEDE_ID, PRESENTACION_ID))
				.isInstanceOf(PresentacionNoConciliaException.class)
				.hasMessageContaining("15000.00");

		// Y lo que importa tanto como el rechazo: no salda NADA. Un cierre con diferencia dejaria
		// obligaciones marcadas como pagadas por plata que nunca entro.
		verify(cobros, never()).descontarSaldo(anyLong(), any());
		assertThat(lote.getEstado()).isEqualTo(EstadoPresentacion.FACTURADA);
	}

	@Test
	@DisplayName("conciliar con saldo cero salda solo las prestaciones aceptadas, no las debitadas")
	void conciliarSaldaLoAceptado() {
		Presentacion lote = confirmada(new BigDecimal("100000.00"), BigDecimal.ZERO.setScale(2));
		given(presentaciones.findByIdInScope(ORG_ID, SEDE_ID, PRESENTACION_ID))
				.willReturn(Optional.of(lote));

		PresentacionItem vivo = item(55010L, 9001L, new BigDecimal("85000.00"));
		PresentacionItem debitado = item(55011L, 9002L, new BigDecimal("15000.00"));
		debitado.debitar(new BigDecimal("15000.00"), "Falta autorizacion", Instant.now(), ACCOUNT_ID);
		given(items.findDeLaPresentacion(PRESENTACION_ID)).willReturn(List.of(vivo, debitado));
		given(cobros.descontarSaldo(9001L, new BigDecimal("85000.00"))).willReturn(1);

		service.conciliar(actor, SEDE_ID, PRESENTACION_ID);

		assertThat(vivo.getEstado()).isEqualTo(EstadoItemPresentacion.ACEPTADO);
		assertThat(debitado.getEstado()).isEqualTo(EstadoItemPresentacion.DEBITADO);
		assertThat(lote.getEstado()).isEqualTo(EstadoPresentacion.CONCILIADA);

		// RN-M21-004: rechazar una prestacion no perdona la deuda. La debitada NO se salda.
		verify(cobros).descontarSaldo(9001L, new BigDecimal("85000.00"));
		verify(cobros).actualizarEstadoPorSaldo(9001L);
		verify(cobros, never()).descontarSaldo(9002L, new BigDecimal("15000.00"));
	}

	@Test
	@DisplayName("un debito mayor que el saldo se rechaza con el saldo disponible")
	void debitoQueNoEntra() {
		Presentacion lote = confirmada(new BigDecimal("100000.00"), new BigDecimal("5000.00"));
		given(presentaciones.findByIdInScope(ORG_ID, SEDE_ID, PRESENTACION_ID))
				.willReturn(Optional.of(lote));
		given(items.findByIdEnLaPresentacion(ORG_ID, PRESENTACION_ID, 55010L))
				.willReturn(Optional.of(item(55010L, 9001L, new BigDecimal("85000.00"))));
		// Cero filas: es lo que devuelve el UPDATE condicional cuando el saldo no alcanza porque
		// otra transaccion —el pago— se lo llevo primero.
		given(presentaciones.registrarDebito(ORG_ID, PRESENTACION_ID, new BigDecimal("15000.00")))
				.willReturn(0);

		assertThatThrownBy(() -> service.debitar(
				actor, SEDE_ID, PRESENTACION_ID, 55010L,
				new PresentacionCommands.Debito(new BigDecimal("15000.00"), "Rechazo parcial")))
				.isInstanceOf(PresentacionSaldoInsuficienteException.class)
				.hasMessageContaining("5000.00");
	}

	@Test
	@DisplayName("una prestacion viva en otro lote no se puede agregar, y el 409 dice en cual esta")
	void obligacionYaPresentada() {
		Presentacion borrador = borrador();
		given(presentaciones.findByIdInScope(ORG_ID, SEDE_ID, PRESENTACION_ID))
				.willReturn(Optional.of(borrador));
		given(obligaciones.findByIdInScope(ORG_ID, SEDE_ID, 9001L))
				.willReturn(Optional.of(obligacionDeFinanciador()));

		PresentacionItem enOtroLote = item(77000L, 9001L, new BigDecimal("85000.00"));
		ReflectionTestUtils.setField(enOtroLote, "presentacionId", 999L);
		given(items.findVivoDeLaObligacion(ORG_ID, 9001L)).willReturn(Optional.of(enOtroLote));

		assertThatThrownBy(() -> service.agregarItem(actor, SEDE_ID, PRESENTACION_ID, 9001L))
				.isInstanceOf(ObligacionYaPresentadaException.class)
				.hasMessageContaining("999");

		verify(items, never()).save(any());
	}

	@Test
	@DisplayName("confirmar asigna el numero del numerador y no toca ninguna obligacion")
	void confirmarNoCobra() {
		Presentacion borrador = borrador();
		given(presentaciones.findByIdInScope(ORG_ID, SEDE_ID, PRESENTACION_ID))
				.willReturn(Optional.of(borrador));
		given(items.findDeLaPresentacion(PRESENTACION_ID))
				.willReturn(List.of(item(55010L, 9001L, new BigDecimal("85000.00"))));
		given(obligaciones.findByIdInScope(ORG_ID, SEDE_ID, 9001L))
				.willReturn(Optional.of(obligacionDeFinanciador()));
		given(items.sumarPresentado(PRESENTACION_ID)).willReturn(new BigDecimal("85000.00"));
		given(numerador.leerUltimo(ORG_ID, SEDE_ID, FINANCIADOR_ID)).willReturn(48);

		PresentacionView vista = service.confirmar(actor, SEDE_ID, PRESENTACION_ID);

		assertThat(vista.numero()).isEqualTo(48);
		assertThat(vista.estado()).isEqualTo(EstadoPresentacion.PRESENTADA);
		assertThat(vista.saldo()).isEqualByComparingTo("85000.00");

		// La fila del numerador se asegura ANTES de bloquearla: crearla dentro de la transaccion
		// que la bloquea produce deadlock, y el try/catch no salva.
		verify(numeradorIniciador).asegurar(ORG_ID, SEDE_ID, FINANCIADOR_ID);
		verify(numerador).incrementar(ORG_ID, SEDE_ID, FINANCIADOR_ID);

		// RN-M21-001: presentado no es cobrado. Confirmar no mueve un peso.
		verify(cobros, never()).descontarSaldo(anyLong(), any());
	}

	// =================================================================================
	// Fixtures
	// =================================================================================

	private Presentacion borrador() {
		Presentacion presentacion = new Presentacion(
				ORG_ID, SEDE_ID, FINANCIADOR_ID,
				LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), "ARS",
				Instant.parse("2026-09-01T10:00:00Z"), ACCOUNT_ID);
		ReflectionTestUtils.setField(presentacion, "id", PRESENTACION_ID);
		return presentacion;
	}

	private Presentacion confirmada(BigDecimal presentado, BigDecimal saldo) {
		Presentacion presentacion = borrador();
		presentacion.confirmar(48, Instant.parse("2026-09-02T10:00:00Z"), ACCOUNT_ID);
		presentacion.registrarFactura(
				"0001-00004521", LocalDate.of(2026, 9, 5),
				Instant.parse("2026-09-05T10:00:00Z"), ACCOUNT_ID);
		ReflectionTestUtils.setField(presentacion, "totalPresentado", presentado);
		ReflectionTestUtils.setField(presentacion, "saldo", saldo);
		return presentacion;
	}

	private PresentacionItem item(long id, long obligacionId, BigDecimal importe) {
		Obligacion obligacion = obligacionDeFinanciador();
		ReflectionTestUtils.setField(obligacion, "id", obligacionId);
		ReflectionTestUtils.setField(obligacion, "saldo", importe);

		PresentacionItem item = new PresentacionItem(
				ORG_ID, PRESENTACION_ID, obligacion, LocalDate.of(2026, 8, 14),
				Instant.parse("2026-09-01T10:00:00Z"));
		ReflectionTestUtils.setField(item, "id", id);
		return item;
	}

	private Obligacion obligacionDeFinanciador() {
		Obligacion obligacion = new Obligacion(
				ORG_ID, SEDE_ID, 501L, 128L, Responsable.FINANCIADOR, FINANCIADOR_ID,
				new BigDecimal("85000.00"), "ARS", 42L, "Sesion 8",
				Instant.parse("2026-08-14T15:00:00Z"));
		ReflectionTestUtils.setField(obligacion, "id", 9001L);
		return obligacion;
	}
}
