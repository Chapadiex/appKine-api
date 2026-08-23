package com.akine.notification.domain.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La distincion transitorio/permanente del fallo de entrega.
 *
 * <p>Es lo unico que el worker necesita para decidir, y equivocarla cuesta en las dos
 * direcciones: marcar transitorio lo que nunca va a funcionar quema los cinco intentos contra
 * una direccion invalida; marcar permanente un SMTP momentaneamente caido descarta una
 * notificacion que habria salido al segundo intento.
 */
class EmailDeliveryExceptionTest {

	@Test
	@DisplayName("un fallo transitorio conserva la causa para poder diagnosticarlo")
	void el_fallo_transitorio_conserva_la_causa() {
		SocketTimeoutException causa = new SocketTimeoutException("read timed out");

		EmailDeliveryException error = EmailDeliveryException.transitorio("el relay no responde", causa);

		assertThat(error.esTransitorio()).isTrue();
		assertThat(error.getMessage()).isEqualTo("el relay no responde");
		assertThat(error.getCause()).isSameAs(causa);
	}

	@Test
	@DisplayName("un fallo permanente no arrastra causa y no se reintenta")
	void el_fallo_permanente_no_se_reintenta() {
		EmailDeliveryException error = EmailDeliveryException.permanente("destinatario invalido");

		assertThat(error.esTransitorio()).isFalse();
		assertThat(error.getMessage()).isEqualTo("destinatario invalido");
		assertThat(error.getCause()).isNull();
	}

	@Test
	@DisplayName("la excepcion es no chequeada: no obliga a envolverla en cada adaptador")
	void es_no_chequeada() {
		assertThat(RuntimeException.class)
				.isAssignableFrom(EmailDeliveryException.class);
	}
}
