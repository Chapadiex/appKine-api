package com.akine.activity.application;

import com.akine.activity.domain.ClaseProgramada;
import com.akine.activity.domain.InscripcionClase;
import com.akine.notification.spi.NotificationEnqueueCommand;
import com.akine.notification.spi.NotificationOutbox;
import com.akine.notification.spi.NotificationType;
import com.akine.person.spi.ContactoDePersona;
import com.akine.person.spi.ContactoDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Los avisos de una clase salen por el outbox transaccional, nunca por SMTP directo. Lo que se
 * prueba aca es lo que decide esta clase: a quien se le encola, con que clave idempotente y que
 * pasa con quien no tiene correo.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AvisosDeClase")
class AvisosDeClaseTest {

	private static final long ORG_ID = 1L;
	private static final Instant INICIO = Instant.now().plus(2, ChronoUnit.DAYS);

	@Mock private NotificationOutbox outbox;
	@Mock private ContactoDirectory contactos;

	private AvisosDeClase avisos;

	@BeforeEach
	void setUp() {
		avisos = new AvisosDeClase(outbox, contactos);
	}

	@Test
	@DisplayName("Sin destinatarios no se consulta el padron ni se encola nada")
	void sin_destinatarios() {
		avisos.avisarCambioDeClase(clase(), "Sede Centro", "UTC", List.of(), Instant.now());

		verifyNoInteractions(contactos, outbox);
	}

	/**
	 * Quien no tiene correo no rompe el aviso de los demas: se registra y se sigue. Y la clave
	 * lleva el instante del cambio, para que dos reprogramaciones distintas no se colapsen en un
	 * solo correo.
	 */
	@Test
	@DisplayName("El cambio avisa a cada inscripto con correo y saltea a quien no lo tiene")
	void cambio_avisa_solo_a_notificables() {
		Instant momento = Instant.now();
		given(contactos.findAll(anyLong(), any())).willReturn(Map.of(
				500L, new ContactoDePersona(500L, "ana@example.test", "Ana"),
				501L, new ContactoDePersona(501L, " ", "Beto")));

		avisos.avisarCambioDeClase(clase(), "Sede Centro", "America/Argentina/Cordoba",
				List.of(inscripcion(1L, 500L), inscripcion(2L, 501L), inscripcion(3L, 502L)),
				momento);

		ArgumentCaptor<NotificationEnqueueCommand> comando =
				ArgumentCaptor.forClass(NotificationEnqueueCommand.class);
		verify(outbox, times(1)).enqueue(comando.capture());
		assertThat(comando.getValue().tipo()).isEqualTo(NotificationType.CLASE_MODIFICADA);
		assertThat(comando.getValue().destinatario()).isEqualTo("ana@example.test");
		assertThat(comando.getValue().claveIdempotente()).isEqualTo("clase-modificada:1:" + momento);
		assertThat(comando.getValue().datosDeRender())
				.containsEntry("claseTitulo", "Pilates")
				.containsEntry("consultorioNombre", "Sede Centro")
				.containsKey("claseInicio");
	}

	/** Una inscripcion se promueve una sola vez: la clave es su id y nada mas. */
	@Test
	@DisplayName("El cupo liberado se avisa una vez por inscripcion promovida")
	void cupo_liberado() {
		given(contactos.findAll(ORG_ID, List.of(500L))).willReturn(Map.of(
				500L, new ContactoDePersona(500L, "ana@example.test", null)));

		avisos.avisarCupoLiberado(clase(), null, "UTC", inscripcion(7L, 500L));

		ArgumentCaptor<NotificationEnqueueCommand> comando =
				ArgumentCaptor.forClass(NotificationEnqueueCommand.class);
		verify(outbox).enqueue(comando.capture());
		assertThat(comando.getValue().tipo()).isEqualTo(NotificationType.CUPO_LIBERADO);
		assertThat(comando.getValue().claveIdempotente()).isEqualTo("cupo-liberado:7");
		// Los nulos viajan como cadena vacia: la plantilla no tiene por que saber de nulls.
		assertThat(comando.getValue().datosDeRender())
				.containsEntry("nombre", "")
				.containsEntry("consultorioNombre", "");
	}

	/**
	 * Defecto corregido en AKINE E-5. "Jardin Secreto" contiene {@code secret}, que el sanitizador
	 * del outbox rechaza; mandado tal cual, {@code enqueue} lanzaba dentro de la transaccion de la
	 * clase y cancelar o reprogramar moria por el nombre de la sede (RN-M26-001). Ahora el dato se
	 * omite antes de encolar y el aviso sale igual.
	 */
	@Test
	@DisplayName("Un dato que el outbox rechazaria se omite y el aviso sale igual")
	void dato_rechazado_se_omite() {
		given(contactos.findAll(ORG_ID, List.of(500L))).willReturn(Map.of(
				500L, new ContactoDePersona(500L, "ana@example.test", "Ana")));
		willAnswer(invocacion -> {
			Map<String, String> datos = invocacion.getArgument(0);
			if (datos.containsKey("consultorioNombre")) {
				throw new IllegalArgumentException("contiene un enlace o un secreto");
			}
			return null;
		}).given(outbox).validarDatosDeRender(any());

		avisos.avisarCupoLiberado(clase(), "Jardin Secreto", "UTC", inscripcion(7L, 500L));

		ArgumentCaptor<NotificationEnqueueCommand> comando =
				ArgumentCaptor.forClass(NotificationEnqueueCommand.class);
		verify(outbox).enqueue(comando.capture());
		assertThat(comando.getValue().datosDeRender())
				.doesNotContainKey("consultorioNombre")
				.containsEntry("claseTitulo", "Pilates");
	}

	private static ClaseProgramada clase() {
		ClaseProgramada clase = new ClaseProgramada(ORG_ID, 10L, 45L, 31L, 8L, "Pilates",
				INICIO, INICIO.plus(1, ChronoUnit.HOURS), 8, 99L, Instant.now(), null, null);
		ReflectionTestUtils.setField(clase, "id", 77L);
		return clase;
	}

	private static InscripcionClase inscripcion(long id, long personaId) {
		InscripcionClase inscripcion = InscripcionClase.conLugar(
				ORG_ID, 10L, 77L, personaId, 99L, Instant.now(), null, null);
		ReflectionTestUtils.setField(inscripcion, "id", id);
		return inscripcion;
	}
}
