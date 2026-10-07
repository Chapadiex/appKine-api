package com.akine.billing.application;

import com.akine.billing.application.ImputacionDePrepago.CierreConTurno;
import com.akine.billing.domain.Cobro;
import com.akine.billing.domain.CobroMedio;
import com.akine.billing.domain.ConceptoObligacion;
import com.akine.billing.domain.MedioDePago;
import com.akine.billing.domain.Obligacion;
import com.akine.billing.domain.Responsable;
import com.akine.billing.domain.SnapshotDeConvenio;
import com.akine.billing.domain.port.CobroRepositoryPort;
import com.akine.billing.domain.port.ObligacionRepositoryPort;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * La imputacion automatica del prepago al cierre (AKINE E-6). Lo que necesita base —el lock del
 * cobro, que corra despues del commit, el UPDATE condicional— esta en {@code PrepagoDeRecepcionIT}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Imputacion del prepago al cierre (E-6)")
class ImputacionDePrepagoTest {

	private static final long ORG = 1L;
	private static final long SEDE = 7L;
	private static final long SESION = 6600L;
	private static final long TURNO = 301L;
	private static final long PERSONA = 128L;
	private static final long COBRO = 88L;
	private static final long OBLIGACION = 9001L;
	private static final long PROFESIONAL = 40L;
	private static final Instant AYER = Instant.parse("2027-04-07T13:00:00Z");

	@Mock private CobroRepositoryPort cobros;
	@Mock private ObligacionRepositoryPort obligaciones;
	@Mock private AuditTrail auditTrail;

	private ImputacionDePrepago servicio;
	private final CierreConTurno cierre = new CierreConTurno(ORG, SEDE, SESION, TURNO, PERSONA, PROFESIONAL);

	@BeforeEach
	void preparar() {
		servicio = new ImputacionDePrepago(cobros, obligaciones, auditTrail);
		given(cobros.prepagoVigenteDelTurno(ORG, TURNO)).willReturn(Optional.of(COBRO));
		given(cobros.descontarSaldo(anyLong(), anyLong(), any())).willReturn(1);
	}

	@Test
	@DisplayName("anticipo igual a la deuda: la salda entera y el anticipo queda en cero")
	void anticipo_justo() {
		Cobro prepago = prepago("8500.00", "ARS");
		given(cobros.findByIdInScopeParaEscribir(ORG, SEDE, COBRO)).willReturn(Optional.of(prepago));
		given(obligaciones.findDeLaSesion(SESION)).willReturn(List.of(particular("8500.00", "ARS")));

		assertThat(servicio.imputarAlCierre(cierre)).contains(new BigDecimal("8500.00"));

		assertThat(prepago.getSaldoAFavor()).isEqualByComparingTo("0");
		assertThat(prepago.getImputaciones()).singleElement().satisfies(imputacion -> {
			assertThat(imputacion.getObligacionId()).isEqualTo(OBLIGACION);
			assertThat(imputacion.getImputadaPorCuentaId()).as("autor: quien cerro").isEqualTo(PROFESIONAL);
		});
		verify(cobros).descontarSaldo(ORG, OBLIGACION, new BigDecimal("8500.00"));
		verify(cobros).actualizarEstadoPorSaldo(ORG, OBLIGACION);
		ArgumentCaptor<AuditEntry> auditoria = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(auditoria.capture());
		assertThat(auditoria.getValue().details()).containsEntry("origen", "PREPAGO_AL_CIERRE");
	}

	@Test
	@DisplayName("anticipo que sobra: imputa lo que se debe y el resto queda a favor")
	void anticipo_que_sobra() {
		Cobro prepago = prepago("10000.00", "ARS");
		given(cobros.findByIdInScopeParaEscribir(ORG, SEDE, COBRO)).willReturn(Optional.of(prepago));
		given(obligaciones.findDeLaSesion(SESION)).willReturn(List.of(particular("8500.00", "ARS")));

		assertThat(servicio.imputarAlCierre(cierre)).contains(new BigDecimal("8500.00"));
		assertThat(prepago.getSaldoAFavor()).isEqualByComparingTo("1500.00");
	}

	@Test
	@DisplayName("anticipo que no alcanza: imputa todo el anticipo y la deuda queda parcial")
	void anticipo_que_no_alcanza() {
		Cobro prepago = prepago("5000.00", "ARS");
		given(cobros.findByIdInScopeParaEscribir(ORG, SEDE, COBRO)).willReturn(Optional.of(prepago));
		given(obligaciones.findDeLaSesion(SESION)).willReturn(List.of(particular("8500.00", "ARS")));

		assertThat(servicio.imputarAlCierre(cierre)).contains(new BigDecimal("5000.00"));
		assertThat(prepago.getSaldoAFavor()).isEqualByComparingTo("0");
		verify(cobros).descontarSaldo(ORG, OBLIGACION, new BigDecimal("5000.00"));
	}

	@Test
	@DisplayName("con convenio imputa al coseguro y nunca a la parte del financiador")
	void solo_la_deuda_del_paciente() {
		Cobro prepago = prepago("8500.00", "ARS");
		given(cobros.findByIdInScopeParaEscribir(ORG, SEDE, COBRO)).willReturn(Optional.of(prepago));
		Obligacion financiador = porConvenio(ConceptoObligacion.FINANCIADOR, 9002L);
		Obligacion coseguro = porConvenio(ConceptoObligacion.COSEGURO, 9003L);
		given(obligaciones.findDeLaSesion(SESION)).willReturn(List.of(financiador, coseguro));

		assertThat(servicio.imputarAlCierre(cierre)).contains(new BigDecimal("1500.00"));
		verify(cobros).descontarSaldo(ORG, 9003L, new BigDecimal("1500.00"));
		verify(cobros, never()).descontarSaldo(ORG, 9002L, new BigDecimal("1500.00"));
		assertThat(prepago.getSaldoAFavor()).as("el resto queda a favor").isEqualByComparingTo("7000.00");
	}

	@Test
	@DisplayName("sin prepago del turno, con el prepago anulado o sin deuda del paciente: no hace nada")
	void nada_que_imputar() {
		given(cobros.prepagoVigenteDelTurno(ORG, TURNO)).willReturn(Optional.empty());
		assertThat(servicio.imputarAlCierre(cierre)).isEmpty();

		given(cobros.prepagoVigenteDelTurno(ORG, TURNO)).willReturn(Optional.of(COBRO));
		Cobro anulado = prepago("8500.00", "ARS");
		anulado.anular("error", AYER, 9L);
		given(cobros.findByIdInScopeParaEscribir(ORG, SEDE, COBRO)).willReturn(Optional.of(anulado));
		assertThat(servicio.imputarAlCierre(cierre)).isEmpty();

		given(cobros.findByIdInScopeParaEscribir(ORG, SEDE, COBRO)).willReturn(Optional.of(prepago("8500.00", "ARS")));
		given(obligaciones.findDeLaSesion(SESION)).willReturn(List.of(porConvenio(ConceptoObligacion.FINANCIADOR, 9002L)));
		assertThat(servicio.imputarAlCierre(cierre)).isEmpty();

		verify(cobros, never()).descontarSaldo(anyLong(), anyLong(), any());
	}

	@Test
	@DisplayName("la deuda en otra moneda no recibe el prepago")
	void otra_moneda() {
		given(cobros.findByIdInScopeParaEscribir(ORG, SEDE, COBRO)).willReturn(Optional.of(prepago("8500.00", "USD")));
		given(obligaciones.findDeLaSesion(SESION)).willReturn(List.of(particular("8500.00", "ARS")));

		assertThat(servicio.imputarAlCierre(cierre)).isEmpty();
		verify(cobros, never()).descontarSaldo(anyLong(), anyLong(), any());
	}

	@Test
	@DisplayName("un segundo disparo encuentra la imputacion hecha y no imputa dos veces")
	void idempotente() {
		Cobro prepago = prepago("10000.00", "ARS");
		given(cobros.findByIdInScopeParaEscribir(ORG, SEDE, COBRO)).willReturn(Optional.of(prepago));
		given(obligaciones.findDeLaSesion(SESION)).willReturn(List.of(particular("8500.00", "ARS")));

		servicio.imputarAlCierre(cierre);
		assertThat(servicio.imputarAlCierre(cierre)).isEmpty();
		verify(cobros).descontarSaldo(ORG, OBLIGACION, new BigDecimal("8500.00"));
	}

	@Test
	@DisplayName("si otro cobro se llevo la deuda entre la lectura y el UPDATE, la transaccion cae entera")
	void la_deuda_cambio() {
		given(cobros.findByIdInScopeParaEscribir(ORG, SEDE, COBRO)).willReturn(Optional.of(prepago("8500.00", "ARS")));
		given(obligaciones.findDeLaSesion(SESION)).willReturn(List.of(particular("8500.00", "ARS")));
		given(cobros.descontarSaldo(anyLong(), anyLong(), any())).willReturn(0);

		assertThatThrownBy(() -> servicio.imputarAlCierre(cierre)).isInstanceOf(IllegalStateException.class);
	}

	// =================================================================================

	private static Cobro prepago(String total, String moneda) {
		BigDecimal importe = new BigDecimal(total);
		Cobro cobro = new Cobro(ORG, SEDE, PERSONA, importe, moneda, 101, AYER, 9L, null, null,
				List.of(new CobroMedio(ORG, MedioDePago.EFECTIVO, importe, null)), List.of(), importe);
		cobro.comoPrepagoDeTurno(TURNO);
		ReflectionTestUtils.setField(cobro, "id", COBRO);
		return cobro;
	}

	private static Obligacion particular(String importe, String moneda) {
		Obligacion obligacion = new Obligacion(ORG, SEDE, SESION, PERSONA, Responsable.PACIENTE,
				new BigDecimal(importe), moneda, 55L, "Sesion 1", AYER);
		ReflectionTestUtils.setField(obligacion, "id", OBLIGACION);
		return obligacion;
	}

	private static Obligacion porConvenio(ConceptoObligacion concepto, long id) {
		Obligacion obligacion = Obligacion.porConvenio(
				ORG, SEDE, SESION, PERSONA, concepto,
				concepto == ConceptoObligacion.FINANCIADOR ? 31L : null, "ARS", 55L, "Sesion 1", AYER,
				new SnapshotDeConvenio(12L, "CONV-1", "Convenio sintetico", 4L, 77L, 55L, 310L,
						new BigDecimal("12000.00"), new BigDecimal("10500.00"), new BigDecimal("1500.00"),
						false, false, false, false, LocalDate.of(2027, 4, 7), AYER),
				false);
		ReflectionTestUtils.setField(obligacion, "id", id);
		return obligacion;
	}
}
