package com.akine.notification.domain;

import com.akine.notification.spi.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Redaccion de los mails de 01.02 (RF-M26-001).
 *
 * <p>Dos cosas se prueban aca y no en otro lado: que ningun tipo que necesita enlace pueda
 * redactarse sin uno —un mail de activacion sin enlace es un mail inutil que igual consume el
 * token—, y que el aviso "ya tenes cuenta" no lleve ningun enlace, porque es el que se manda a
 * un email que quizas no le pertenece a quien disparo el registro (challenge D-4).
 */
class EmailTemplatesTest {

	private static final String DESTINATARIO = "ana.gomez@ejemplo.test";
	private static final String ENLACE = "https://app.akine.test/activar?token=SINTETICO";

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = NotificationType.class, names = {
			"ACTIVACION_CUENTA", "INVITACION_COLABORADOR", "RECUPERACION_PASSWORD"})
	@DisplayName("un tipo que necesita enlace no se puede redactar sin uno")
	void sin_enlace_no_se_redacta(NotificationType tipo) {
		// El token de un solo uso ya se emitio cuando se llega aca. Mandar el mail sin el enlace
		// quema el token y obliga a la persona a pedir otro sin entender por que.
		assertThatThrownBy(() -> EmailTemplates.render(tipo, DESTINATARIO, SanitizedPayload.vacio(), null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining(tipo.name());

		assertThatThrownBy(() -> EmailTemplates.render(tipo, DESTINATARIO, SanitizedPayload.vacio(), "  "))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("la activacion lleva el enlace y avisa que se usa una sola vez")
	void la_activacion_lleva_el_enlace() {
		EmailMessage mensaje = EmailTemplates.render(
				NotificationType.ACTIVACION_CUENTA,
				DESTINATARIO,
				SanitizedPayload.of(Map.of("nombre", "Ana")),
				ENLACE);

		assertThat(mensaje.destinatario()).isEqualTo(DESTINATARIO);
		assertThat(mensaje.asunto()).isEqualTo("Activa tu cuenta de AKINE");
		assertThat(mensaje.cuerpo())
				.startsWith("Hola Ana,")
				.contains(ENLACE)
				.contains("una sola vez")
				.endsWith("El equipo de AKINE");
	}

	@Test
	@DisplayName("la invitacion nombra a quien invita y a la organizacion")
	void la_invitacion_nombra_al_que_invita() {
		EmailMessage mensaje = EmailTemplates.render(
				NotificationType.INVITACION_COLABORADOR,
				DESTINATARIO,
				SanitizedPayload.of(Map.of(
						"nombre", "Ana",
						"organizacionNombre", "Kine Sur",
						"invitadoPor", "Dr. Ruiz")),
				ENLACE);

		assertThat(mensaje.asunto()).isEqualTo("Te invitaron a trabajar en Kine Sur");
		assertThat(mensaje.cuerpo())
				.contains("Dr. Ruiz")
				.contains("Kine Sur")
				.contains(ENLACE);
	}

	@Test
	@DisplayName("la invitacion sin datos de render usa textos neutros y no imprime null")
	void la_invitacion_sin_datos_usa_textos_neutros() {
		// "Hola null," y "null te invito a null" es el bug clasico de un template con getters
		// directos sobre un payload incompleto, y se ve recien cuando ya salio el mail.
		EmailMessage mensaje = EmailTemplates.render(
				NotificationType.INVITACION_COLABORADOR,
				DESTINATARIO,
				SanitizedPayload.vacio(),
				ENLACE);

		assertThat(mensaje.asunto()).isEqualTo("Te invitaron a trabajar en AKINE");
		assertThat(mensaje.cuerpo())
				.doesNotContain("null")
				.startsWith("Hola,")
				.contains("Un administrador")
				.contains("su organizacion");
	}

	@Test
	@DisplayName("la recuperacion no afirma que la contrasena haya cambiado")
	void la_recuperacion_no_afirma_el_cambio() {
		EmailMessage mensaje = EmailTemplates.render(
				NotificationType.RECUPERACION_PASSWORD,
				DESTINATARIO,
				SanitizedPayload.vacio(),
				ENLACE);

		assertThat(mensaje.asunto()).isEqualTo("Restablece tu contrasena de AKINE");
		// Quien recibe esto puede no haberlo pedido: el texto tiene que dejarlo tranquilo sin
		// obligarlo a hacer nada.
		assertThat(mensaje.cuerpo())
				.contains(ENLACE)
				.contains("tu contrasena sigue igual");
	}

	@Test
	@DisplayName("el aviso de cuenta existente no lleva ningun enlace")
	void el_aviso_de_cuenta_existente_no_lleva_enlace() {
		// Este mail se manda ante un registro con un email ya usado, para que la API pueda
		// responder 202 uniforme sin revelar existencia. Si llevara un enlace de acceso,
		// cualquiera podria dispararle a la casilla de otro un enlace vivo.
		EmailMessage mensaje = EmailTemplates.render(
				NotificationType.CUENTA_YA_REGISTRADA,
				DESTINATARIO,
				SanitizedPayload.of(Map.of("nombre", "Ana")),
				null);

		assertThat(mensaje.asunto()).isEqualTo("Ya tenes una cuenta en AKINE");
		assertThat(mensaje.cuerpo())
				.doesNotContain("http")
				.doesNotContain("enlace")
				.contains("Inicia sesion");
	}

	@ParameterizedTest
	@EnumSource(NotificationType.class)
	@DisplayName("ningun template deja el cuerpo o el asunto vacios")
	void ningun_template_queda_vacio(NotificationType tipo) {
		EmailMessage mensaje = EmailTemplates.render(
				tipo, DESTINATARIO, SanitizedPayload.vacio(),
				tipo.requiereEnlaceSeguro() ? ENLACE : null);

		// Meta-test: un tipo nuevo sin rama en el switch no compila, pero uno con una rama que
		// arma un mensaje incompleto si pasaria inadvertido hasta el envio.
		assertThat(mensaje.asunto()).isNotBlank();
		assertThat(mensaje.cuerpo()).isNotBlank();
		assertThat(mensaje.destinatario()).isEqualTo(DESTINATARIO);
	}

	@Test
	@DisplayName("los templates son puros: la clase no se instancia")
	void la_clase_no_se_instancia() throws Exception {
		assertThat(Modifier.isFinal(EmailTemplates.class.getModifiers())).isTrue();

		Constructor<EmailTemplates> constructor = EmailTemplates.class.getDeclaredConstructor();
		assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();
		constructor.setAccessible(true);
		assertThat(constructor.newInstance()).isNotNull();
	}
}
