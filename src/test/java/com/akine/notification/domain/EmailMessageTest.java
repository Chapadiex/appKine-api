package com.akine.notification.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El mensaje efimero que se le entrega al adaptador de envio (T-11).
 *
 * <p>Lo que estos tests sostienen: que el unico lugar donde el enlace existe en claro no pueda
 * terminar en un log por el {@code toString} que el compilador genera para un record.
 */
class EmailMessageTest {

	private static final String CUERPO_CON_ENLACE =
			"Para activar tu cuenta entra en https://app.akine.test/activar?token=SECRETO";

	@ParameterizedTest(name = "destinatario={0} asunto={1} cuerpo={2}")
	@CsvSource(nullValues = "NULO", value = {
			"NULO, Asunto, Cuerpo",
			"'   ', Asunto, Cuerpo",
			"ana@ejemplo.test, NULO, Cuerpo",
			"ana@ejemplo.test, '  ', Cuerpo",
			"ana@ejemplo.test, Asunto, NULO",
			"ana@ejemplo.test, Asunto, '  '"})
	@DisplayName("un mensaje sin destinatario, sin asunto o sin cuerpo no se puede construir")
	void un_mensaje_incompleto_no_se_construye(String destinatario, String asunto, String cuerpo) {
		// Un mail vacio no falla al enviarse: se envia, y la persona recibe un mensaje mudo que
		// nadie puede diagnosticar despues. Falla aca, donde todavia hay contexto.
		assertThatThrownBy(() -> new EmailMessage(destinatario, asunto, cuerpo))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("toString enmascara el destinatario y omite el cuerpo entero")
	void to_string_no_filtra_el_enlace() {
		EmailMessage mensaje = new EmailMessage(
				"ana.gomez@ejemplo.test", "Activa tu cuenta de AKINE", CUERPO_CON_ENLACE);

		String impreso = mensaje.toString();

		// El toString de un record imprime todos los campos, y el cuerpo lleva el enlace de un
		// solo uso: alcanza con un log.debug del adaptador para que el token quede persistido.
		assertThat(impreso)
				.doesNotContain("SECRETO")
				.doesNotContain("app.akine.test")
				.doesNotContain("ana.gomez")
				.contains("a***@ejemplo.test")
				.contains("Activa tu cuenta de AKINE")
				.contains("<omitido>");
	}

	@Test
	@DisplayName("el mensaje conserva intactos los datos que recibio")
	void el_mensaje_conserva_sus_datos() {
		// El toString oculta, pero el adaptador tiene que recibir el cuerpo completo: si el
		// ocultamiento llegara hasta los accessors, el mail saldria sin el enlace.
		EmailMessage mensaje = new EmailMessage(
				"ana.gomez@ejemplo.test", "Asunto", CUERPO_CON_ENLACE);

		assertThat(mensaje.destinatario()).isEqualTo("ana.gomez@ejemplo.test");
		assertThat(mensaje.asunto()).isEqualTo("Asunto");
		assertThat(mensaje.cuerpo()).isEqualTo(CUERPO_CON_ENLACE);
	}
}
