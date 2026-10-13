package com.akine.platform.infrastructure.audit;

import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.platform.spi.transaccion.AlTerminarLaTransaccion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Las garantias de {@code AuditoriaAparte} sobre el ciclo que el transaction manager de la
 * aplicacion le avisa a {@link AlTerminarLaTransaccion}: {@code empezo}, {@code commiteo} y
 * {@code termino} se llaman a mano, en el orden en que los llama {@code TransaccionesConfig}.
 */
class AuditoriaAparteImplTest {

	private final AuditTrail auditTrail = mock(AuditTrail.class);
	private final AuditoriaAparteImpl auditoria =
			new AuditoriaAparteImpl(auditTrail, mock(PlatformTransactionManager.class));

	@AfterEach
	void limpiar() {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			TransactionSynchronizationManager.setActualTransactionActive(false);
			TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
		}
	}

	@Test
	@DisplayName("soporte en una lectura readOnly: si la auditoria falla despues del commit, la lectura no se entrega")
	void la_lectura_no_se_entrega_sin_su_auditoria() {
		willThrow(new IllegalStateException("audit_event no disponible")).given(auditTrail).record(any());
		abrir(true);

		auditoria.registrarAccesoDeSoporte(entrada());
		verifyNoInteractions(auditTrail);

		AlTerminarLaTransaccion.commiteo();
		cerrarTransaccionFisica();
		assertThatThrownBy(AlTerminarLaTransaccion::termino)
				.as("la falla sale del commit: el llamador recibe la excepcion, no el resultado")
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("soporte en una lectura que revierte: se escribe igual, y su falla no tapa la excepcion original")
	void la_lectura_que_revierte_deja_su_rastro_sin_tapar_nada() {
		abrir(true);
		auditoria.registrarAccesoDeSoporte(entrada());
		cerrarTransaccionFisica();

		AlTerminarLaTransaccion.termino();
		verify(auditTrail).record(any());

		willThrow(new IllegalStateException("audit_event no disponible")).given(auditTrail).record(any());
		abrir(true);
		auditoria.registrarAccesoDeSoporte(entrada());
		cerrarTransaccionFisica();
		AlTerminarLaTransaccion.termino(); // no lanza: el pedido ya falla con su propia excepcion
	}

	@Test
	@DisplayName("soporte en una mutacion: se escribe en la transaccion y si falla, la mutacion revierte")
	void en_una_mutacion_la_auditoria_va_en_la_transaccion() {
		willThrow(new IllegalStateException("audit_event no disponible")).given(auditTrail).record(any());
		abrir(false);

		assertThatThrownBy(() -> auditoria.registrarAccesoDeSoporte(entrada()))
				.as("la falla sale en la transaccion de negocio, que entonces no commitea")
				.isInstanceOf(IllegalStateException.class);
		cerrarTransaccionFisica();
		AlTerminarLaTransaccion.termino();
	}

	@Test
	@DisplayName("soporte en una mutacion que commitea: una sola escritura, adentro")
	void la_mutacion_que_commitea_no_escribe_dos_veces() {
		abrir(false);
		auditoria.registrarAccesoDeSoporte(entrada());
		AlTerminarLaTransaccion.commiteo();
		cerrarTransaccionFisica();
		AlTerminarLaTransaccion.termino();

		verify(auditTrail, times(1)).record(any());
	}

	@Test
	@DisplayName("soporte en una mutacion que revierte por otra causa: el intento queda escrito aparte")
	void la_mutacion_que_revierte_deja_el_intento() {
		abrir(false);
		auditoria.registrarAccesoDeSoporte(entrada());
		cerrarTransaccionFisica();
		AlTerminarLaTransaccion.termino();

		verify(auditTrail, times(2)).record(any());
	}

	@Test
	@DisplayName("un rechazo es al mejor esfuerzo: su falla despues del commit no hace fallar el pedido")
	void el_rechazo_es_al_mejor_esfuerzo() {
		willThrow(new IllegalStateException("audit_event no disponible")).given(auditTrail).record(any());
		abrir(true);
		auditoria.registrar(entrada());
		AlTerminarLaTransaccion.commiteo();
		cerrarTransaccionFisica();

		AlTerminarLaTransaccion.termino();
		assertThat(AlTerminarLaTransaccion.siguiendoUnaTransaccion()).isFalse();
	}

	private static void abrir(boolean soloLectura) {
		AlTerminarLaTransaccion.empezo();
		TransactionSynchronizationManager.setActualTransactionActive(true);
		TransactionSynchronizationManager.setCurrentTransactionReadOnly(soloLectura);
	}

	/** Lo que la limpieza de Spring hace antes de avisar {@code termino}. */
	private static void cerrarTransaccionFisica() {
		TransactionSynchronizationManager.setActualTransactionActive(false);
		TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
	}

	private static AuditEntry entrada() {
		return new AuditEntry(1L, null, 2L, "SUPPORT_ACCESS_USED", "Persona", 3L, null, null,
				Map.of(), null, "correlacion", Instant.now());
	}
}
