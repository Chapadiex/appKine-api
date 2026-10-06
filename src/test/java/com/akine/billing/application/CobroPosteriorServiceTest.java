package com.akine.billing.application;

import com.akine.billing.domain.Cobro;
import com.akine.billing.domain.CobroImputacion;
import com.akine.billing.domain.CobroMedio;
import com.akine.billing.domain.CobroReintegro;
import com.akine.billing.domain.MedioDePago;
import com.akine.billing.domain.MovimientoCaja;
import com.akine.billing.domain.Obligacion;
import com.akine.billing.domain.OrigenMovimiento;
import com.akine.billing.domain.PermissionCodes;
import com.akine.billing.domain.Responsable;
import com.akine.billing.domain.TipoMovimiento;
import com.akine.billing.domain.exception.CobroAnuladoException;
import com.akine.billing.domain.exception.CobroConReintegrosException;
import com.akine.billing.domain.exception.CobroNotAccessibleException;
import com.akine.billing.domain.exception.ObligacionNoCobrableException;
import com.akine.billing.domain.exception.SaldoAFavorInsuficienteException;
import com.akine.billing.domain.exception.SaldoInsuficienteException;
import com.akine.billing.domain.port.CobroReintegroRepositoryPort;
import com.akine.billing.domain.port.CobroRepositoryPort;
import com.akine.billing.domain.port.MovimientoCajaRepositoryPort;
import com.akine.billing.domain.port.ObligacionRepositoryPort;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Lo que se hace sobre un cobro ya registrado (F-3, {@link CobroPosteriorService}).
 *
 * <h2>Que decide la correctitud aca</h2>
 *
 * <ul>
 *   <li><b>El lock del cobro es la primera carga.</b> Todo pasa por
 *       {@code findByIdInScopeParaEscribir}; ninguna operacion lee el cobro por otro camino antes.</li>
 *   <li><b>Deuda, cobro y caja siguen separados.</b> Imputar no toca la caja; reintegrar no toca
 *       ninguna deuda; anular toca las tres, cada una por su primitivo.</li>
 *   <li><b>La anulacion devuelve la deuda antes de revertir la caja</b>, y si la caja la rechaza la
 *       excepcion sale antes de marcar el cobro.</li>
 * </ul>
 *
 * <p><b>Lo que no pueden decir:</b> que el lock serialice de verdad, que el UPDATE de la deuda sea
 * condicional y que los {@code CHECK} de V69 sostengan el rango. Eso es {@code AnticipoYAnulacionIT}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Cobros despues de confirmados (F-3, CobroPosteriorService)")
class CobroPosteriorServiceTest {

	private static final long ORG = 7L;
	private static final long SEDE = 20L;
	private static final long PERSONA = 4100L;
	private static final long OTRA_PERSONA = 4101L;
	private static final long OBLIGACION = 8800L;
	private static final long COBRO = 5501L;
	private static final long REINTEGRO = 301L;
	private static final long CUENTA = 31L;
	private static final String MONEDA = "ARS";
	private static final String CLAVE = "idem-f3-0001";

	@Mock private CobroRepositoryPort cobros;
	@Mock private ObligacionRepositoryPort obligaciones;
	@Mock private CobroReintegroRepositoryPort reintegros;
	@Mock private MovimientoCajaRepositoryPort movimientos;
	@Mock private MovimientoCajaService movimientoService;
	@Mock private CajaDeCobro caja;
	@Mock private ConsultorioDirectory consultorios;
	@Mock private PermissionGuard permissionGuard;
	@Mock private AuditTrail auditTrail;

	private CobroPosteriorService service;

	private final ConsultorioSnapshot sede =
			new ConsultorioSnapshot(SEDE, ORG, "Sede Centro", "America/Argentina/Cordoba", true);
	private final OperatingActor administrativo = new OperatingActor(CUENTA, false, ORG, SEDE);

	@BeforeEach
	void setUp() {
		service = new CobroPosteriorService(
				cobros, obligaciones, reintegros, movimientos, movimientoService, caja,
				new CajaAcceso(consultorios, permissionGuard), permissionGuard, auditTrail);

		given(consultorios.find(ORG, SEDE)).willReturn(Optional.of(sede));
		given(permissionGuard.requirePermission(any()))
				.willReturn(PermissionDecision.concedida("CONSULTORIO", false));
		given(cobros.descontarSaldo(anyLong(), anyLong(), any())).willReturn(1);
		given(cobros.devolverSaldo(anyLong(), anyLong(), any())).willReturn(1);
		given(cobros.cobroDeLaImputacionConClave(anyLong(), anyString())).willReturn(Optional.empty());
		given(reintegros.save(any())).willAnswer(invocacion -> {
			CobroReintegro guardado = invocacion.getArgument(0);
			ReflectionTestUtils.setField(guardado, "id", REINTEGRO);
			return guardado;
		});
	}

	// =================================================================================

	@Nested
	@DisplayName("Imputar el saldo a favor")
	class Imputar {

		@Test
		@DisplayName("descuenta el anticipo y la deuda, no toca la caja y audita")
		void camino_feliz() {
			given(cobros.findByIdInScopeParaEscribir(ORG, SEDE, COBRO)).willReturn(Optional.of(anticipo("5000.00")));
			given(obligaciones.findByIdInScope(ORG, SEDE, OBLIGACION))
					.willReturn(Optional.of(deuda(PERSONA, "8500.00", MONEDA)));

			CobroView vista = service.imputarSaldoAFavor(administrativo, SEDE, COBRO,
					new ImputacionPosteriorCommand(OBLIGACION, new BigDecimal("3000.00"), CLAVE));

			assertThat(vista.saldoAFavor()).isEqualByComparingTo("2000.00");
			assertThat(vista.imputaciones()).singleElement()
					.satisfies(imputacion -> assertThat(imputacion.obligacionId()).isEqualTo(OBLIGACION));
			verify(cobros).descontarSaldo(ORG, OBLIGACION, new BigDecimal("3000.00"));
			verify(cobros).actualizarEstadoPorSaldo(ORG, OBLIGACION);
			verifyNoInteractions(caja, movimientoService);
			verify(auditTrail).record(argThat(entrada ->
					entrada.eventType().equals(AuditEvents.COBRO_SALDO_IMPUTADO)));
		}

		@Test
		@DisplayName("el lock del cobro va antes de la idempotencia y de la deuda")
		void el_lock_va_primero() {
			given(cobros.findByIdInScopeParaEscribir(ORG, SEDE, COBRO)).willReturn(Optional.of(anticipo("5000.00")));
			given(obligaciones.findByIdInScope(ORG, SEDE, OBLIGACION))
					.willReturn(Optional.of(deuda(PERSONA, "8500.00", MONEDA)));

			service.imputarSaldoAFavor(administrativo, SEDE, COBRO,
					new ImputacionPosteriorCommand(OBLIGACION, new BigDecimal("3000.00"), CLAVE));

			InOrder orden = inOrder(cobros, obligaciones);
			orden.verify(cobros).findByIdInScopeParaEscribir(ORG, SEDE, COBRO);
			orden.verify(cobros).cobroDeLaImputacionConClave(ORG, CLAVE);
			orden.verify(obligaciones).findByIdInScope(ORG, SEDE, OBLIGACION);
			orden.verify(cobros).descontarSaldo(ORG, OBLIGACION, new BigDecimal("3000.00"));
		}

		@Test
		@DisplayName("sin saldo a favor suficiente es 409 y la deuda no se toca")
		void sin_saldo_a_favor() {
			given(cobros.findByIdInScopeParaEscribir(ORG, SEDE, COBRO)).willReturn(Optional.of(anticipo("1000.00")));
			given(obligaciones.findByIdInScope(ORG, SEDE, OBLIGACION))
					.willReturn(Optional.of(deuda(PERSONA, "8500.00", MONEDA)));

			assertThatThrownBy(() -> service.imputarSaldoAFavor(administrativo, SEDE, COBRO,
					new ImputacionPosteriorCommand(OBLIGACION, new BigDecimal("3000.00"), null)))
					.isInstanceOf(SaldoAFavorInsuficienteException.class);
			verify(cobros, never()).descontarSaldo(anyLong(), anyLong(), any());
		}

		@Test
		@DisplayName("la deuda de otra persona es 409: un anticipo no salda la cuenta de otro paciente")
		void deuda_de_otra_persona() {
			given(cobros.findByIdInScopeParaEscribir(ORG, SEDE, COBRO)).willReturn(Optional.of(anticipo("5000.00")));
			given(obligaciones.findByIdInScope(ORG, SEDE, OBLIGACION))
					.willReturn(Optional.of(deuda(OTRA_PERSONA, "8500.00", MONEDA)));

			assertThatThrownBy(() -> service.imputarSaldoAFavor(administrativo, SEDE, COBRO,
					new ImputacionPosteriorCommand(OBLIGACION, new BigDecimal("3000.00"), null)))
					.isInstanceOf(ObligacionNoCobrableException.class);
			verify(cobros, never()).descontarSaldo(anyLong(), anyLong(), any());
		}

		@Test
		@DisplayName("la deuda en otra moneda es 409")
		void deuda_en_otra_moneda() {
			given(cobros.findByIdInScopeParaEscribir(ORG, SEDE, COBRO)).willReturn(Optional.of(anticipo("5000.00")));
			given(obligaciones.findByIdInScope(ORG, SEDE, OBLIGACION))
					.willReturn(Optional.of(deuda(PERSONA, "8500.00", "USD")));

			assertThatThrownBy(() -> service.imputarSaldoAFavor(administrativo, SEDE, COBRO,
					new ImputacionPosteriorCommand(OBLIGACION, new BigDecimal("3000.00"), null)))
					.isInstanceOf(ObligacionNoCobrableException.class);
		}

		@Test
		@DisplayName("la deuda que ya no debe tanto es 409 saldo-insuficiente")
		void deuda_sin_saldo() {
			given(cobros.findByIdInScopeParaEscribir(ORG, SEDE, COBRO)).willReturn(Optional.of(anticipo("5000.00")));
			given(obligaciones.findByIdInScope(ORG, SEDE, OBLIGACION))
					.willReturn(Optional.of(deuda(PERSONA, "8500.00", MONEDA)));
			given(cobros.descontarSaldo(ORG, OBLIGACION, new BigDecimal("3000.00"))).willReturn(0);

			assertThatThrownBy(() -> service.imputarSaldoAFavor(administrativo, SEDE, COBRO,
					new ImputacionPosteriorCommand(OBLIGACION, new BigDecimal("3000.00"), null)))
					.isInstanceOf(SaldoInsuficienteException.class);
		}

		@Test
		@DisplayName("un cobro anulado no imputa: 409")
		void cobro_anulado() {
			Cobro anulado = anticipo("5000.00");
			anulado.anular("error", Instant.now(), CUENTA);
			given(cobros.findByIdInScopeParaEscribir(ORG, SEDE, COBRO)).willReturn(Optional.of(anulado));
			given(obligaciones.findByIdInScope(ORG, SEDE, OBLIGACION))
					.willReturn(Optional.of(deuda(PERSONA, "8500.00", MONEDA)));

			assertThatThrownBy(() -> service.imputarSaldoAFavor(administrativo, SEDE, COBRO,
					new ImputacionPosteriorCommand(OBLIGACION, new BigDecimal("3000.00"), null)))
					.isInstanceOf(CobroAnuladoException.class);
		}

		@Test
		@DisplayName("un cobro de otro tenant o de otra sede es 404")
		void cobro_ajeno() {
			given(cobros.findByIdInScopeParaEscribir(ORG, SEDE, COBRO)).willReturn(Optional.empty());

			assertThatThrownBy(() -> service.imputarSaldoAFavor(administrativo, SEDE, COBRO,
					new ImputacionPosteriorCommand(OBLIGACION, new BigDecimal("3000.00"), null)))
					.isInstanceOf(CobroNotAccessibleException.class);
		}

		@Test
		@DisplayName("el reintento con la misma clave devuelve el cobro sin imputar dos veces")
		void reintento_idempotente() {
			ImputacionPosteriorCommand pedido =
					new ImputacionPosteriorCommand(OBLIGACION, new BigDecimal("3000.00"), CLAVE);
			Cobro yaImputado = anticipo("5000.00");
			yaImputado.imputar(CobroImputacion.posterior(ORG, OBLIGACION, new BigDecimal("3000.00"),
					Instant.now(), CUENTA, CLAVE, pedido.huella(SEDE, COBRO)));
			given(cobros.findByIdInScopeParaEscribir(ORG, SEDE, COBRO)).willReturn(Optional.of(yaImputado));
			given(cobros.cobroDeLaImputacionConClave(ORG, CLAVE)).willReturn(Optional.of(COBRO));

			CobroView vista = service.imputarSaldoAFavor(administrativo, SEDE, COBRO, pedido);

			assertThat(vista.saldoAFavor()).isEqualByComparingTo("2000.00");
			verify(cobros, never()).descontarSaldo(anyLong(), anyLong(), any());
		}

		@Test
		@DisplayName("la misma clave con otro contenido, o usada en otro cobro, es 409")
		void clave_reusada() {
			given(cobros.findByIdInScopeParaEscribir(ORG, SEDE, COBRO)).willReturn(Optional.of(anticipo("5000.00")));
			given(cobros.cobroDeLaImputacionConClave(ORG, CLAVE)).willReturn(Optional.of(COBRO + 1));

			assertThatThrownBy(() -> service.imputarSaldoAFavor(administrativo, SEDE, COBRO,
					new ImputacionPosteriorCommand(OBLIGACION, new BigDecimal("3000.00"), CLAVE)))
					.isInstanceOf(IdempotencyKeyConflictException.class);
		}

		@Test
		@DisplayName("sin contexto de trabajo es 403 y no toca nada")
		void sin_contexto() {
			assertThatThrownBy(() -> service.imputarSaldoAFavor(
					new OperatingActor(CUENTA, false, null, null), SEDE, COBRO,
					new ImputacionPosteriorCommand(OBLIGACION, new BigDecimal("3000.00"), null)))
					.isInstanceOf(AccessDeniedException.class);
			verifyNoInteractions(cobros);
		}
	}

	// =================================================================================

	@Nested
	@DisplayName("Anular")
	class Anular {

		@Test
		@DisplayName("devuelve cada imputacion a su deuda, revierte cada ingreso de caja y marca el cobro")
		void camino_feliz() {
			given(cobros.findByIdInScopeParaEscribir(ORG, SEDE, COBRO)).willReturn(Optional.of(cobroImputado()));
			MovimientoCaja ingreso = movimiento(900L, TipoMovimiento.INGRESO);
			given(movimientos.findTodosPorOrigen(ORG, OrigenMovimiento.COBRO.name(), COBRO))
					.willReturn(List.of(ingreso));

			CobroView vista = service.anular(administrativo, SEDE, COBRO, "Cobrado al paciente equivocado");

			assertThat(vista.estado()).isEqualTo(CobroView.ANULADO);
			assertThat(vista.motivoAnulacion()).isEqualTo("Cobrado al paciente equivocado");
			InOrder orden = inOrder(cobros, movimientoService);
			orden.verify(cobros).devolverSaldo(ORG, OBLIGACION, new BigDecimal("8500.00"));
			orden.verify(cobros).flush();
			orden.verify(movimientoService).revertir(administrativo, SEDE, 900L, "Cobrado al paciente equivocado");

			ArgumentCaptor<AuditEntry> auditoria = ArgumentCaptor.forClass(AuditEntry.class);
			verify(auditTrail).record(auditoria.capture());
			assertThat(auditoria.getValue().eventType()).isEqualTo(AuditEvents.COBRO_ANULADO);
		}

		@Test
		@DisplayName("exige caja:operate ademas de cobro:register: mueve plata del cajon")
		void exige_operar_caja() {
			given(permissionGuard.requirePermission(argThat(consulta ->
					consulta != null && PermissionCodes.CAJA_OPERATE.equals(consulta.permissionCode()))))
					.willThrow(new AccessDeniedException("sin caja"));

			assertThatThrownBy(() -> service.anular(administrativo, SEDE, COBRO, "motivo"))
					.isInstanceOf(AccessDeniedException.class);
			verify(cobros, never()).findByIdInScopeParaEscribir(anyLong(), anyLong(), anyLong());
		}

		@Test
		@DisplayName("las reversiones de caja ignoran lo que no sea un INGRESO del cobro")
		void solo_ingresos() {
			given(cobros.findByIdInScopeParaEscribir(ORG, SEDE, COBRO)).willReturn(Optional.of(cobroImputado()));
			given(movimientos.findTodosPorOrigen(ORG, OrigenMovimiento.COBRO.name(), COBRO))
					.willReturn(List.of(movimiento(900L, TipoMovimiento.INGRESO), movimiento(901L, TipoMovimiento.EGRESO)));

			service.anular(administrativo, SEDE, COBRO, "motivo");

			verify(movimientoService).revertir(administrativo, SEDE, 900L, "motivo");
			verify(movimientoService, never()).revertir(any(), anyLong(), eq(901L), any());
		}

		@Test
		@DisplayName("anular dos veces es 409 y no revierte la caja otra vez")
		void dos_veces() {
			Cobro anulado = cobroImputado();
			anulado.anular("primera", Instant.now(), CUENTA);
			given(cobros.findByIdInScopeParaEscribir(ORG, SEDE, COBRO)).willReturn(Optional.of(anulado));

			assertThatThrownBy(() -> service.anular(administrativo, SEDE, COBRO, "segunda"))
					.isInstanceOf(CobroAnuladoException.class);
			verifyNoInteractions(movimientoService);
			verify(cobros, never()).devolverSaldo(anyLong(), anyLong(), any());
		}

		@Test
		@DisplayName("un cobro con reintegros no se anula: la plata saldria dos veces del cajon")
		void con_reintegros() {
			given(cobros.findByIdInScopeParaEscribir(ORG, SEDE, COBRO)).willReturn(Optional.of(anticipo("5000.00")));
			given(reintegros.existenDelCobro(ORG, COBRO)).willReturn(true);

			assertThatThrownBy(() -> service.anular(administrativo, SEDE, COBRO, "motivo"))
					.isInstanceOf(CobroConReintegrosException.class);
			verifyNoInteractions(movimientoService);
		}

		@Test
		@DisplayName("si la deuda no admite la devolucion, la transaccion cae: es una divergencia, no un 409")
		void deuda_divergente() {
			given(cobros.findByIdInScopeParaEscribir(ORG, SEDE, COBRO)).willReturn(Optional.of(cobroImputado()));
			given(cobros.devolverSaldo(ORG, OBLIGACION, new BigDecimal("8500.00"))).willReturn(0);

			assertThatThrownBy(() -> service.anular(administrativo, SEDE, COBRO, "motivo"))
					.isInstanceOf(IllegalStateException.class);
			verifyNoInteractions(movimientoService);
		}

		@Test
		@DisplayName("si la caja rechaza la reversion, la excepcion sale y no se audita nada: la transaccion vuelve atras entera")
		void la_caja_puede_voltear_la_anulacion() {
			Cobro cobro = cobroImputado();
			given(cobros.findByIdInScopeParaEscribir(ORG, SEDE, COBRO)).willReturn(Optional.of(cobro));
			given(movimientos.findTodosPorOrigen(ORG, OrigenMovimiento.COBRO.name(), COBRO))
					.willReturn(List.of(movimiento(900L, TipoMovimiento.INGRESO)));
			willThrow(new com.akine.billing.domain.exception.CajaNoAbiertaException(SEDE))
					.given(movimientoService).revertir(any(), anyLong(), anyLong(), any());

			assertThatThrownBy(() -> service.anular(administrativo, SEDE, COBRO, "motivo"))
					.isInstanceOf(com.akine.billing.domain.exception.CajaNoAbiertaException.class);
			verifyNoInteractions(auditTrail);
		}
	}

	// =================================================================================

	@Nested
	@DisplayName("Reintegrar el saldo a favor")
	class Reintegrar {

		@Test
		@DisplayName("descuenta el anticipo, guarda la fila y asienta la salida de caja con el id del reintegro")
		void camino_feliz() {
			given(cobros.findByIdInScopeParaEscribir(ORG, SEDE, COBRO)).willReturn(Optional.of(anticipo("5000.00")));

			ReintegroView vista = service.reintegrar(administrativo, SEDE, COBRO, new ReintegroCommand(
					new BigDecimal("2000.00"), MedioDePago.EFECTIVO, null, "Suspendio el tratamiento", CLAVE));

			assertThat(vista.id()).isEqualTo(REINTEGRO);
			assertThat(vista.saldoAFavorRestante()).isEqualByComparingTo("3000.00");
			verify(caja).registrarReintegro(ORG, sede, REINTEGRO, COBRO, MedioDePago.EFECTIVO,
					new BigDecimal("2000.00"), MONEDA, vista.reintegradoEn(), CUENTA);
			// El cobro se escribe antes del asiento: el de efectivo limpia la sesion de JPA.
			InOrder orden = inOrder(cobros, caja);
			orden.verify(cobros).flush();
			orden.verify(caja).registrarReintegro(anyLong(), any(), anyLong(), anyLong(), any(), any(),
					anyString(), any(), anyLong());
			// Un reintegro no toca ninguna deuda.
			verifyNoInteractions(obligaciones);
			verify(cobros, never()).descontarSaldo(anyLong(), anyLong(), any());
		}

		@Test
		@DisplayName("mas de lo que queda a favor es 409 y no sale plata")
		void sin_saldo() {
			given(cobros.findByIdInScopeParaEscribir(ORG, SEDE, COBRO)).willReturn(Optional.of(anticipo("1000.00")));

			assertThatThrownBy(() -> service.reintegrar(administrativo, SEDE, COBRO, new ReintegroCommand(
					new BigDecimal("2000.00"), MedioDePago.EFECTIVO, null, "motivo", null)))
					.isInstanceOf(SaldoAFavorInsuficienteException.class);
			verifyNoInteractions(caja);
			verify(reintegros, never()).save(any());
		}

		@Test
		@DisplayName("el reintento con la misma clave devuelve el mismo reintegro sin sacar plata otra vez")
		void reintento_idempotente() {
			ReintegroCommand pedido = new ReintegroCommand(
					new BigDecimal("2000.00"), MedioDePago.EFECTIVO, null, "motivo", CLAVE);
			Cobro cobro = anticipo("3000.00");
			CobroReintegro previo = new CobroReintegro(cobro, new BigDecimal("2000.00"), MedioDePago.EFECTIVO,
					null, "motivo", Instant.now(), CUENTA, CLAVE, pedido.huella(SEDE, COBRO));
			ReflectionTestUtils.setField(previo, "id", REINTEGRO);
			given(cobros.findByIdInScopeParaEscribir(ORG, SEDE, COBRO)).willReturn(Optional.of(cobro));
			given(reintegros.findByIdempotencyKey(ORG, CLAVE)).willReturn(Optional.of(previo));

			ReintegroView vista = service.reintegrar(administrativo, SEDE, COBRO, pedido);

			assertThat(vista.id()).isEqualTo(REINTEGRO);
			assertThat(cobro.getSaldoAFavor()).isEqualByComparingTo("3000.00");
			verifyNoInteractions(caja);
		}

		@Test
		@DisplayName("la misma clave con otro importe es 409")
		void clave_reusada() {
			Cobro cobro = anticipo("5000.00");
			CobroReintegro previo = new CobroReintegro(cobro, new BigDecimal("2000.00"), MedioDePago.EFECTIVO,
					null, "motivo", Instant.now(), CUENTA, CLAVE, "otra-huella");
			given(cobros.findByIdInScopeParaEscribir(ORG, SEDE, COBRO)).willReturn(Optional.of(cobro));
			given(reintegros.findByIdempotencyKey(ORG, CLAVE)).willReturn(Optional.of(previo));

			assertThatThrownBy(() -> service.reintegrar(administrativo, SEDE, COBRO, new ReintegroCommand(
					new BigDecimal("1000.00"), MedioDePago.EFECTIVO, null, "motivo", CLAVE)))
					.isInstanceOf(IdempotencyKeyConflictException.class);
		}
	}

	// =================================================================================
	// Fixtures
	// =================================================================================

	/** Un anticipo puro en efectivo, con id. */
	private static Cobro anticipo(String importe) {
		BigDecimal total = new BigDecimal(importe);
		Cobro cobro = new Cobro(ORG, SEDE, PERSONA, total, MONEDA, 1,
				Instant.parse("2027-04-08T13:00:00Z"), CUENTA, null, null,
				List.of(new CobroMedio(ORG, MedioDePago.EFECTIVO, total, null)),
				List.of(), total);
		ReflectionTestUtils.setField(cobro, "id", COBRO);
		return cobro;
	}

	/** Un cobro de 8500 imputado entero a una deuda. */
	private static Cobro cobroImputado() {
		BigDecimal total = new BigDecimal("8500.00");
		Cobro cobro = new Cobro(ORG, SEDE, PERSONA, total, MONEDA, 1,
				Instant.parse("2027-04-08T13:00:00Z"), CUENTA, null, null,
				List.of(new CobroMedio(ORG, MedioDePago.EFECTIVO, total, null)),
				List.of(new CobroImputacion(ORG, OBLIGACION, total)));
		ReflectionTestUtils.setField(cobro, "id", COBRO);
		return cobro;
	}

	private static Obligacion deuda(long personaId, String importe, String moneda) {
		Obligacion obligacion = new Obligacion(
				ORG, SEDE, 6600L, personaId, Responsable.PACIENTE,
				new BigDecimal(importe), moneda, 55L, "Sesion de kinesiologia",
				Instant.parse("2027-04-08T13:00:00Z"));
		ReflectionTestUtils.setField(obligacion, "id", OBLIGACION);
		return obligacion;
	}

	private static MovimientoCaja movimiento(long id, TipoMovimiento tipo) {
		MovimientoCaja movimiento = new MovimientoCaja(
				ORG, SEDE, 1L, LocalDate.parse("2027-04-08"), tipo, MedioDePago.EFECTIVO,
				new BigDecimal("8500.00"), MONEDA, "Cobro " + COBRO, null,
				OrigenMovimiento.COBRO, COBRO, null, Instant.parse("2027-04-08T13:00:00Z"), CUENTA,
				null, null);
		ReflectionTestUtils.setField(movimiento, "id", id);
		return movimiento;
	}

}
