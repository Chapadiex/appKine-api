package com.akine.diferidos;

import com.akine.notification.application.OutboxDispatcher;
import com.akine.notification.domain.EmailMessage;
import com.akine.notification.domain.exception.EmailDeliveryException;
import com.akine.notification.domain.port.EmailSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El correo de una invitacion cancelada mientras el relay estaba caido NO sale (QA, PR #95).
 *
 * <p>Hasta este cambio: el relay cae, la fila de la invitacion queda REINTENTABLE, el
 * administrador cancela la invitacion, el relay vuelve y el correo sale —ENVIADA— con un enlace
 * que da 404. Ahora el worker pregunta antes de cada intento si el hecho sigue en pie
 * ({@code VigenciaDeNotificacion}) y la fila termina AGOTADA con el motivo.
 *
 * <h2>Como se simula el relay caido</h2>
 *
 * <p>Con un {@link EmailSender} propio que se apaga y se prende, y con el worker programado
 * apagado ({@code akine.notification.worker.enabled=false}): los ticks los dispara el test, uno
 * con el relay caido y otro con el relay de vuelta. El Mailpit del {@code compose.yaml} no sirve
 * para esto: es compartido con la maquina, puede no estar corriendo y no se puede "caer" desde un
 * test. Lo unico que se toca por SQL es {@code proxima_ejecucion_en}, para no esperar el minuto
 * del primer escalon del backoff: es el paso del tiempo, no el estado que se verifica.
 */
@TestPropertySource(properties = "akine.notification.worker.enabled=false")
class InvitacionAnuladaNoSeEnviaIT extends BaseEscenarioDiferido {

	@Autowired
	private OutboxDispatcher dispatcher;

	@AfterEach
	void relayDeVuelta() {
		CorreoControlable.CAIDO.set(false);
		CorreoControlable.ENVIADOS.clear();
	}

	@Test
	@DisplayName("Invitacion con el relay caido, cancelada antes de que vuelva: la fila termina "
			+ "AGOTADA con el motivo y no sale ningun correo; la activacion del mismo lote sale y "
			+ "no arrastra el error viejo")
	void la_invitacion_cancelada_no_se_envia_al_volver_el_relay() {
		Sesion centro = altaCompleta("invitacion-anulada");
		String email = "anulada-" + UUID.randomUUID() + "@ejemplo.test";

		Respuesta emitida = post(invitaciones(centro), centro.token(),
				"{\"email\":\"%s\",\"roleCode\":\"PROFESIONAL\",\"consultorioId\":%d}"
						.formatted(email, centro.consultorioId()));
		assertThat(emitida.status()).as("alta de invitacion: %s", emitida.body()).isEqualTo(201);
		long invitacionId = emitida.json().get("id").asLong();
		long filaInvitacion = jdbc.queryForObject("""
				SELECT id FROM notification_outbox
				 WHERE organization_id = ? AND tipo = 'INVITACION_COLABORADOR'
				""", Long.class, centro.organizationId());

		// 1. El relay esta caido: la invitacion (y la activacion del alta) quedan REINTENTABLE.
		CorreoControlable.CAIDO.set(true);
		dispatcher.runOnce();
		assertThat(fila(filaInvitacion).get("estado")).isEqualTo("REINTENTABLE");
		assertThat(fila(filaInvitacion).get("error_sanitizado")).asString().contains("relay caido");

		// 2. Mientras tanto, el administrador cancela la invitacion.
		Respuesta cancelada = post(invitaciones(centro) + "/" + invitacionId + "/cancel",
				centro.token(), "{\"reason\":\"Se cubrio el puesto\"}");
		assertThat(cancelada.status()).as("cancelacion: %s", cancelada.body()).isEqualTo(200);

		// 3. El relay vuelve y pasa el backoff.
		CorreoControlable.CAIDO.set(false);
		jdbc.update("""
				UPDATE notification_outbox SET proxima_ejecucion_en = '2000-01-01 00:00:00'
				 WHERE estado = 'REINTENTABLE'
				""");
		dispatcher.runOnce();

		Map<String, Object> invitacion = fila(filaInvitacion);
		assertThat(invitacion.get("estado"))
				.as("no fallo nada: se decidio no enviar (V9, T-11)")
				.isEqualTo("AGOTADA");
		assertThat(invitacion.get("error_sanitizado"))
				.isEqualTo("No se envia: la invitacion fue cancelada");
		assertThat(invitacion.get("enviado_en")).isNull();
		assertThat(CorreoControlable.ENVIADOS)
				.as("al invitado no le llega el enlace muerto")
				.extracting(EmailMessage::destinatario)
				.doesNotContain(email);

		// La activacion del alta, que tambien esperaba, sale y no conserva el error del intento
		// anterior: ENVIADA con "relay caido" al lado se leia como un envio fallido.
		Map<String, Object> activacion = jdbc.queryForMap("""
				SELECT estado, error_sanitizado, intentos FROM notification_outbox
				 WHERE tipo = 'ACTIVACION_CUENTA' AND destinatario = ?
				""", normalizar(centro.email()));
		assertThat(activacion.get("estado")).isEqualTo("ENVIADA");
		assertThat(activacion.get("error_sanitizado")).isNull();
		assertThat(((Number) activacion.get("intentos")).intValue()).isEqualTo(2);
		assertThat(CorreoControlable.ENVIADOS)
				.extracting(EmailMessage::destinatario)
				.contains(normalizar(centro.email()));
	}

	private Map<String, Object> fila(long id) {
		return jdbc.queryForMap(
				"SELECT estado, error_sanitizado, enviado_en FROM notification_outbox WHERE id = ?", id);
	}

	private String invitaciones(Sesion sesion) {
		return "/api/v1/organizations/" + sesion.organizationId() + "/colaborador-invitaciones";
	}

	/** Un relay que el test apaga y prende, y que recuerda lo que entrego. */
	@TestConfiguration(proxyBeanMethods = false)
	static class CorreoControlable {

		static final AtomicBoolean CAIDO = new AtomicBoolean();
		static final List<EmailMessage> ENVIADOS = new CopyOnWriteArrayList<>();

		@Bean
		@Primary
		EmailSender correoControlable() {
			return new EmailSender() {
				@Override
				public void send(EmailMessage mensaje) {
					if (CAIDO.get()) {
						throw EmailDeliveryException.transitorio("relay caido", null);
					}
					ENVIADOS.add(mensaje);
				}

				@Override
				public String nombre() {
					return "controlable";
				}
			};
		}
	}
}
