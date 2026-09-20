package com.akine.billing.application;

import com.akine.billing.domain.CategoriaEgreso;
import com.akine.billing.domain.Egreso;
import com.akine.billing.domain.EstadoEgreso;
import com.akine.billing.domain.MedioDePago;
import com.akine.billing.domain.PagoEgreso;
import com.akine.billing.domain.TipoBeneficiario;
import com.akine.billing.domain.exception.EgresoConPagosException;
import com.akine.billing.domain.exception.EgresoNoPagableException;
import com.akine.billing.domain.exception.EgresoSaldoInsuficienteException;
import com.akine.billing.domain.exception.EgresoSinComprobanteException;
import com.akine.billing.domain.port.EgresoRepositoryPort;
import com.akine.billing.domain.port.MovimientoCajaRepositoryPort;
import com.akine.billing.domain.port.PagoEgresoRepositoryPort;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.platform.spi.audit.AuditTrail;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * Las reglas de M22 que cuestan caro si se olvidan.
 *
 * <p>Cinco casos, elegidos porque cada uno protege una decision del diseno que el codigo podria
 * perder sin que nada mas se queje:
 *
 * <ol>
 *   <li><b>Un borrador no se paga.</b> Si esto cae, la confirmacion deja de significar algo y un
 *       egreso a medio cargar puede sacar plata del cajon.</li>
 *   <li><b>Cero filas del descuento no es un error tecnico</b>: se desambigua releyendo y devuelve
 *       el saldo disponible. Sin eso, la pantalla solo puede decir "volve a intentar" y el
 *       operador reintenta exactamente lo mismo.</li>
 *   <li><b>El pago sale por el ledger de caja y por ningun otro lado</b>, apuntado desde el
 *       movimiento con {@code PAGO_EGRESO} y el id del pago. Es la prueba de que no hay un segundo
 *       camino para que salga plata.</li>
 *   <li><b>Un egreso con pagos no se anula</b>, y el 409 lleva lo ya pagado.</li>
 *   <li><b>Confirmar exige comprobante</b>: un egreso confirmado sin respaldo documental es plata
 *       que salio sin papel.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Egresos y pagos a profesionales (M22)")
class EgresoYPagoTest {

	private static final long ORG_ID = 10L;
	private static final long SEDE_ID = 20L;
	private static final long EGRESO_ID = 5501L;
	private static final long PAGO_ID = 7701L;
	private static final long CUENTA_ID = 1204L;

	private static final ConsultorioSnapshot SEDE =
			new ConsultorioSnapshot(SEDE_ID, ORG_ID, "Sede Centro", "America/Argentina/Cordoba", true);

	private static final OperatingActor ACTOR =
			new OperatingActor(CUENTA_ID, false, ORG_ID, SEDE_ID);

	@Mock
	private EgresoRepositoryPort egresos;

	@Mock
	private PagoEgresoRepositoryPort pagos;

	@Mock
	private MovimientoCajaRepositoryPort movimientos;

	@Mock
	private MovimientoCajaService movimientoService;

	@Mock
	private CajaDeEgreso cajaDeEgreso;

	@Mock
	private CajaAcceso acceso;

	@Mock
	private AuditTrail auditTrail;

	private PagoEgresoService servicio() {
		given(acceso.exigirSedeDelTenant(ORG_ID, SEDE_ID)).willReturn(SEDE);
		return new PagoEgresoService(
				egresos, pagos, movimientos, movimientoService, cajaDeEgreso, acceso, auditTrail);
	}

	// =================================================================================
	// 1 y 2 — el compromiso decide si se puede pagar, y cuanto
	// =================================================================================

	@Test
	@DisplayName("un borrador no se paga: confirmar es lo que habilita a mover la caja")
	void un_borrador_no_se_paga() {
		Egreso borrador = egreso("185000.00");
		given(egresos.findByIdInScope(ORG_ID, SEDE_ID, EGRESO_ID)).willReturn(Optional.of(borrador));

		assertThat(borrador.getEstado()).isEqualTo(EstadoEgreso.BORRADOR);

		assertThatThrownBy(() -> servicio().pagar(
				ACTOR, SEDE_ID, EGRESO_ID,
				new PagoEgresoCommand(new BigDecimal("1000.00"), MedioDePago.EFECTIVO, null, null)))
				.isInstanceOf(EgresoNoPagableException.class)
				.hasMessageContaining("borrador");

		// Y lo que importa de verdad: no se toco la caja.
		verify(cajaDeEgreso, org.mockito.Mockito.never())
				.registrarSalida(anyLong(), any(), anyLong(), any(), any(), any(), any(), any(), anyLong());
	}

	@Test
	@DisplayName("cero filas del descuento se desambigua releyendo y devuelve el saldo disponible")
	void el_pago_no_puede_exceder_el_saldo() {
		Egreso confirmado = egresoConfirmado("185000.00");
		ReflectionTestUtils.setField(confirmado, "saldoPendiente", new BigDecimal("85000.00"));

		given(egresos.findByIdInScope(ORG_ID, SEDE_ID, EGRESO_ID))
				.willReturn(Optional.of(confirmado));
		given(egresos.descontarSaldo(eq(ORG_ID), eq(EGRESO_ID), any(BigDecimal.class)))
				.willReturn(0);

		assertThatThrownBy(() -> servicio().pagar(
				ACTOR, SEDE_ID, EGRESO_ID,
				new PagoEgresoCommand(new BigDecimal("100000.00"), MedioDePago.EFECTIVO, null, null)))
				.isInstanceOf(EgresoSaldoInsuficienteException.class)
				.extracting(e -> ((EgresoSaldoInsuficienteException) e).getSaldoDisponible())
				.isEqualTo(new BigDecimal("85000.00"));
	}

	// =================================================================================
	// 3 — la salida de plata pasa por el ledger de caja, y por ningun otro lado
	// =================================================================================

	@Test
	@DisplayName("el pago asienta la salida en la caja, con el id del pago como referencia")
	void el_pago_asienta_la_salida_en_la_caja() {
		Egreso confirmado = egresoConfirmado("185000.00");
		given(egresos.findByIdInScope(ORG_ID, SEDE_ID, EGRESO_ID))
				.willReturn(Optional.of(confirmado));
		given(egresos.descontarSaldo(eq(ORG_ID), eq(EGRESO_ID), any(BigDecimal.class)))
				.willReturn(1);
		given(pagos.save(any(PagoEgreso.class))).willAnswer(invocacion -> {
			PagoEgreso pago = invocacion.getArgument(0);
			ReflectionTestUtils.setField(pago, "id", PAGO_ID);
			return pago;
		});

		PagoEgresoView vista = servicio().pagar(
				ACTOR, SEDE_ID, EGRESO_ID,
				new PagoEgresoCommand(new BigDecimal("100000.00"), MedioDePago.TRANSFERENCIA,
						"TRF-99213847", null));

		assertThat(vista.id()).isEqualTo(PAGO_ID);
		assertThat(vista.estado()).isEqualTo("CONFIRMADO");

		// El movimiento apunta al pago por su id: es lo que hace que el unique de V54 garantice
		// un solo movimiento por pago, y lo que hace innecesaria una columna en pago_egreso.
		verify(cajaDeEgreso).registrarSalida(
				eq(ORG_ID), eq(SEDE), eq(PAGO_ID), eq(MedioDePago.TRANSFERENCIA),
				eq(new BigDecimal("100000.00")), eq("ARS"), anyString(),
				any(Instant.class), eq(CUENTA_ID));
	}

	// =================================================================================
	// 4 y 5 — reglas del compromiso, sin mocks: viven en la entidad
	// =================================================================================

	@Test
	@DisplayName("un egreso con pagos no se anula, y el 409 lleva lo ya pagado")
	void un_egreso_con_pagos_no_se_anula() {
		Egreso confirmado = egresoConfirmado("185000.00");
		ReflectionTestUtils.setField(confirmado, "saldoPendiente", new BigDecimal("85000.00"));

		assertThatThrownBy(() -> confirmado.anular("Error de carga", Instant.now(), CUENTA_ID))
				.isInstanceOf(EgresoConPagosException.class)
				.extracting(e -> ((EgresoConPagosException) e).getYaPagado())
				.isEqualTo(new BigDecimal("100000.00"));

		// Y el egreso sigue como estaba: una anulacion rechazada no deja cambios parciales.
		assertThat(confirmado.getEstado()).isEqualTo(EstadoEgreso.CONFIRMADO);
		assertThat(confirmado.getSaldoPendiente()).isEqualTo(new BigDecimal("85000.00"));
	}

	@Test
	@DisplayName("confirmar sin comprobante se rechaza: es plata que saldria sin papel")
	void confirmar_exige_comprobante() {
		Egreso sinComprobante = egreso("185000.00");

		assertThatThrownBy(() -> sinComprobante.confirmar(Instant.now(), CUENTA_ID))
				.isInstanceOf(EgresoSinComprobanteException.class);
		assertThat(sinComprobante.getEstado()).isEqualTo(EstadoEgreso.BORRADOR);

		sinComprobante.editarBorrador(
				CategoriaEgreso.HONORARIOS_PROFESIONALES, null, null,
				"Honorarios de septiembre 2026", new BigDecimal("185000.00"),
				"FACTURA_C", "0001-00000123", null);

		sinComprobante.confirmar(Instant.now(), CUENTA_ID);
		assertThat(sinComprobante.getEstado()).isEqualTo(EstadoEgreso.CONFIRMADO);
		assertThat(sinComprobante.admitePago()).isTrue();
	}

	// =================================================================================
	// Interno
	// =================================================================================

	private static Egreso egreso(String importe) {
		Egreso egreso = new Egreso(
				ORG_ID, SEDE_ID, CategoriaEgreso.HONORARIOS_PROFESIONALES,
				TipoBeneficiario.COLABORADOR, 412L, "M:412", "Lucia Fernandez", null,
				null, null, "Honorarios de septiembre 2026", new BigDecimal(importe), "ARS",
				Instant.now(), CUENTA_ID, null, null);
		ReflectionTestUtils.setField(egreso, "id", EGRESO_ID);
		return egreso;
	}

	private static Egreso egresoConfirmado(String importe) {
		Egreso egreso = egreso(importe);
		egreso.editarBorrador(
				CategoriaEgreso.HONORARIOS_PROFESIONALES, null, null,
				"Honorarios de septiembre 2026", new BigDecimal(importe),
				"FACTURA_C", "0001-00000123", null);
		egreso.confirmar(Instant.now(), CUENTA_ID);
		return egreso;
	}
}
