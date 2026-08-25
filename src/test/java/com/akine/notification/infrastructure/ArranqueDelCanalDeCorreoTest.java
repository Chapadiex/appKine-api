package com.akine.notification.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.akine.notification.domain.port.EmailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * <b>Sin canal de correo configurado, la aplicacion no arranca.</b> La misma regla que ADR-0017
 * aplica al secreto de firma del JWT, por la misma razon.
 *
 * <h2>Que previene</h2>
 *
 * <p>ADR-0018 apoya todas sus respuestas uniformes en que la persona legitima reciba el correo y
 * siga por ahi: el {@code 202} del registro y el del pedido de reset no dicen nada porque el
 * canal dice todo. Un despliegue sin ese canal —en modo {@code log}, o en modo {@code smtp}
 * apuntando a un host que nadie configuro— responde {@code 202} a todo, escribe una linea en el
 * log y tira el mensaje. <b>No hay error, no hay fila FALLIDA, no hay nada que mirar</b>: la
 * unica evidencia es que los usuarios no pueden activar la cuenta, y eso se descubre por soporte.
 *
 * <p>Los casos negativos usan {@code staging} a proposito: un nombre plausible que nadie previo,
 * que es donde siempre estuvo el agujero (ver {@code PerfilesDeEjecucion}).
 */
class ArranqueDelCanalDeCorreoTest {

	private final NotificationConfig config = new NotificationConfig();

	private static NotificationProperties propiedades() {
		return new NotificationProperties();
	}

	private EmailSender adaptadorCon(NotificationProperties propiedades, String... perfiles) {
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles(perfiles);
		return config.emailSender(propiedades, environment);
	}

	// =================================================================================
	// El modo log es de desarrollo y solo de desarrollo
	// =================================================================================

	@Test
	@DisplayName("en un perfil de desarrollo el modo log sigue siendo valido")
	void en_desarrollo_el_modo_log_vale() {
		assertThat(adaptadorCon(propiedades(), "local").nombre()).isEqualTo("log");
	}

	@Test
	@DisplayName("fuera de desarrollo, el modo log NO arranca: responderia 202 y tiraria el correo")
	void fuera_de_desarrollo_el_modo_log_no_arranca() {
		assertThatIllegalStateException()
				.isThrownBy(() -> adaptadorCon(propiedades(), "staging"))
				.withMessageContaining("modo 'log'")
				.withMessageContaining("akine.notification.email.mode=smtp");
	}

	@Test
	@DisplayName("sin ningun perfil activo tampoco: un despliegue sin SPRING_PROFILES_ACTIVE no es desarrollo")
	void sin_perfil_tampoco() {
		assertThatIllegalStateException().isThrownBy(() -> adaptadorCon(propiedades()));
	}

	// =================================================================================
	// El host no tiene default en ningun perfil
	// =================================================================================

	@Test
	@DisplayName("modo smtp sin host no arranca, y el error dice que variable de entorno falta")
	void sin_host_no_arranca() {
		NotificationProperties propiedades = propiedades();
		propiedades.getEmail().setMode(NotificationProperties.EmailMode.SMTP);

		assertThatIllegalStateException()
				.isThrownBy(() -> adaptadorCon(propiedades, "staging"))
				.withMessageContaining("AKINE_MAIL_HOST");
	}

	@Test
	@DisplayName("ni siquiera en desarrollo: elegir smtp sin decir contra que servidor no significa nada")
	void sin_host_no_arranca_ni_en_desarrollo() {
		NotificationProperties propiedades = propiedades();
		propiedades.getEmail().setMode(NotificationProperties.EmailMode.SMTP);

		assertThatIllegalStateException()
				.isThrownBy(() -> adaptadorCon(propiedades, "local"))
				.withMessageContaining("AKINE_MAIL_HOST");
	}

	// =================================================================================
	// Fuera de desarrollo, ni transporte en claro ni el remitente versionado
	// =================================================================================

	@Test
	@DisplayName("fuera de desarrollo no se acepta transporte sin cifrar: iria la contrasena del relay en claro")
	void fuera_de_desarrollo_el_transporte_va_cifrado() {
		NotificationProperties propiedades = smtpConfigurado();
		propiedades.getEmail().getSmtp()
				.setTransportSecurity(NotificationProperties.TransportSecurity.NINGUNA);

		assertThatIllegalStateException()
				.isThrownBy(() -> adaptadorCon(propiedades, "staging"))
				.withMessageContaining("transport-security");
	}

	@Test
	@DisplayName("en local si: el Mailpit del compose no tiene TLS y no sale de la maquina")
	void en_local_el_transporte_puede_ir_en_claro() {
		NotificationProperties propiedades = smtpConfigurado();
		propiedades.getEmail().getSmtp()
				.setTransportSecurity(NotificationProperties.TransportSecurity.NINGUNA);

		assertThat(adaptadorCon(propiedades, "local").nombre()).isEqualTo("smtp");
	}

	@Test
	@DisplayName("fuera de desarrollo no se acepta el remitente versionado: su dominio no es enrutable")
	void fuera_de_desarrollo_el_remitente_es_propio() {
		NotificationProperties propiedades = smtpConfigurado();
		propiedades.getEmail().setFrom(NotificationConfig.REMITENTE_DE_DESARROLLO);

		assertThatIllegalStateException()
				.isThrownBy(() -> adaptadorCon(propiedades, "staging"))
				.withMessageContaining("AKINE_MAIL_FROM");
	}

	@Test
	@DisplayName("con todo configurado, el adaptador activo es el SMTP real")
	void configurado_del_todo_queda_el_smtp() {
		assertThat(adaptadorCon(smtpConfigurado(), "staging"))
				.isInstanceOf(SmtpEmailSender.class)
				.extracting(EmailSender::nombre)
				.isEqualTo("smtp");
	}

	/** Configuracion sintetica valida para produccion. Ningun valor real. */
	private static NotificationProperties smtpConfigurado() {
		NotificationProperties propiedades = propiedades();
		propiedades.getEmail().setMode(NotificationProperties.EmailMode.SMTP);
		propiedades.getEmail().setFrom("no-reply@ejemplo.test");
		propiedades.getEmail().getSmtp().setHost("smtp.ejemplo.test");
		propiedades.getEmail().getSmtp().setUsername("akine");
		propiedades.getEmail().getSmtp().setPassword("contrasena-sintetica-de-test");
		return propiedades;
	}
}
