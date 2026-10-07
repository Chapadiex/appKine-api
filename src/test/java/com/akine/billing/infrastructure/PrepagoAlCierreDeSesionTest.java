package com.akine.billing.infrastructure;

import com.akine.billing.application.ImputacionDePrepago;
import com.akine.billing.application.ImputacionDePrepago.CierreConTurno;
import com.akine.encounter.spi.SesionCerrada;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** El observador que dispara la imputacion del prepago despues del commit del cierre (E-6). */
@ExtendWith(MockitoExtension.class)
@DisplayName("Prepago al cierre de la sesion (E-6)")
class PrepagoAlCierreDeSesionTest {

	@Mock private ImputacionDePrepago imputacion;

	@AfterEach
	void limpiar() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	@DisplayName("dentro de una transaccion no imputa: se anota para despues del commit")
	void despues_del_commit() {
		TransactionSynchronizationManager.initSynchronization();
		PrepagoAlCierreDeSesion observador = new PrepagoAlCierreDeSesion(imputacion);

		observador.alCerrar(cierre(true, 301L));
		verifyNoInteractions(imputacion);

		TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
		verify(imputacion).imputarAlCierre(new CierreConTurno(1L, 7L, 6600L, 301L, 128L, 40L));
	}

	@Test
	@DisplayName("sin asistencia o sin turno no hay nada que imputar: el anticipo queda a favor")
	void ausencia_o_sin_turno() {
		PrepagoAlCierreDeSesion observador = new PrepagoAlCierreDeSesion(imputacion);

		observador.alCerrar(cierre(false, 301L));
		observador.alCerrar(cierre(true, null));

		verify(imputacion, never()).imputarAlCierre(any());
	}

	@Test
	@DisplayName("un fallo de la imputacion no se propaga: el cierre ya commiteo y no se toca")
	void el_fallo_no_se_propaga() {
		given(imputacion.imputarAlCierre(any())).willThrow(new IllegalStateException("carrera"));
		PrepagoAlCierreDeSesion observador = new PrepagoAlCierreDeSesion(imputacion);

		assertThatCode(() -> observador.alCerrar(cierre(true, 301L))).doesNotThrowAnyException();
	}

	private static SesionCerrada cierre(boolean asistio, Long turnoId) {
		return new SesionCerrada(6600L, 1L, 7L, 128L, 55L, 1, asistio, Instant.now(), 40L,
				new BigDecimal("8500.00"), "ARS", Set.of(), null, false, turnoId);
	}
}
