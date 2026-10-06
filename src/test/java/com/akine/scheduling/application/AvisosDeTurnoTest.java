package com.akine.scheduling.application;

import com.akine.notification.spi.NotificationEnqueueCommand;
import com.akine.notification.spi.NotificationOutbox;
import com.akine.notification.spi.NotificationType;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.person.spi.ContactoDePersona;
import com.akine.person.spi.ContactoDirectory;
import com.akine.scheduling.domain.Turno;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Los avisos de turno (RF-M26-002/003, AKINE E-5). Lo que decide esta clase: a quien se le encola,
 * con que datos —y con cuales NO—, con que clave idempotente, y que pasa cuando no se puede avisar.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AvisosDeTurno")
class AvisosDeTurnoTest {

	private static final long ORG_ID = 7L;
	private static final long PERSONA_ID = 128L;
	private static final long TURNO_ID = 501L;
	private static final String ZONA = "America/Argentina/Cordoba";
	private static final ConsultorioSnapshot SEDE =
			new ConsultorioSnapshot(3L, ORG_ID, "Sede Centro", ZONA, true);

	@Mock private NotificationOutbox outbox;
	@Mock private ContactoDirectory contactos;

	private AvisosDeTurno avisos;

	@BeforeEach
	void setUp() {
		avisos = new AvisosDeTurno(outbox, contactos);
	}

	@Test
	@DisplayName("la reserva encola al paciente con fecha, hora, sede y servicio, y nada mas")
	void la_reserva_encola_con_phi_minima() {
		conCorreo();
		Turno turno = turno(LocalDateTime.of(2027, 3, 1, 9, 0), 0L);

		avisos.avisarReserva(turno, SEDE, "Kinesiologia deportiva");

		NotificationEnqueueCommand comando = encolado();
		assertThat(comando.tipo()).isEqualTo(NotificationType.TURNO_RESERVADO);
		assertThat(comando.destinatario()).isEqualTo("ana@ejemplo.test");
		assertThat(comando.claveIdempotente()).isEqualTo("turno-reservado:" + TURNO_ID);
		assertThat(comando.organizationId()).isEqualTo(ORG_ID);
		assertThat(comando.referenciaTokenId()).isNull();
		// La hora va en la zona de la SEDE: 09:00 en Cordoba son las 12:00 UTC, y un mail que
		// dijera "12:00" mandaria al paciente tres horas tarde.
		assertThat(comando.datosDeRender()).containsExactlyInAnyOrderEntriesOf(Map.of(
				"nombre", "Ana",
				"turnoInicio", "01/03/2027 a las 09:00",
				"consultorioNombre", "Sede Centro",
				"servicioNombre", "Kinesiologia deportiva"));
	}

	@Test
	@DisplayName("sin correo no se encola, y no se lanza: la reserva sigue")
	void sin_correo_no_se_encola() {
		given(contactos.findAll(ORG_ID, List.of(PERSONA_ID))).willReturn(Map.of(
				PERSONA_ID, new ContactoDePersona(PERSONA_ID, null, "Ana")));

		avisos.avisarReserva(turno(LocalDateTime.of(2027, 3, 1, 9, 0), 0L), SEDE, "Kine");

		verify(outbox, never()).enqueue(any());
	}

	@Test
	@DisplayName("una persona que el padron no devuelve tampoco rompe nada")
	void persona_sin_contacto_no_se_encola() {
		given(contactos.findAll(ORG_ID, List.of(PERSONA_ID))).willReturn(Map.of());

		avisos.avisarCancelacion(turno(LocalDateTime.of(2027, 3, 1, 9, 0), 1L), SEDE, "Kine");

		verify(outbox, never()).enqueue(any());
	}

	@Test
	@DisplayName("la cancelacion se encola una vez por turno y sin servicio si la oferta no resolvio")
	void la_cancelacion_sin_servicio() {
		conCorreo();

		avisos.avisarCancelacion(turno(LocalDateTime.of(2027, 3, 1, 9, 0), 1L), SEDE, null);

		NotificationEnqueueCommand comando = encolado();
		assertThat(comando.tipo()).isEqualTo(NotificationType.TURNO_CANCELADO);
		assertThat(comando.claveIdempotente()).isEqualTo("turno-cancelado:" + TURNO_ID);
		assertThat(comando.datosDeRender())
				.doesNotContainKey("servicioNombre")
				.containsEntry("turnoInicio", "01/03/2027 a las 09:00");
	}

	/**
	 * Idempotencia: la clave es estable para el MISMO cambio —un reintento de la transaccion produce
	 * la misma y el UNIQUE del outbox no la duplica— y distinta para dos cambios, aunque el segundo
	 * devuelva el turno al horario del primero.
	 */
	@Test
	@DisplayName("la reprogramacion lleva el horario anterior y una clave por cambio, no por turno")
	void la_reprogramacion_tiene_clave_por_cambio() {
		conCorreo();
		Instant antes = LocalDateTime.of(2027, 3, 1, 9, 0).atZone(ZoneId.of(ZONA)).toInstant();

		avisos.avisarReprogramacion(turno(LocalDateTime.of(2027, 3, 8, 10, 30), 2L), SEDE, "Kine", antes);
		avisos.avisarReprogramacion(turno(LocalDateTime.of(2027, 3, 8, 10, 30), 2L), SEDE, "Kine", antes);
		avisos.avisarReprogramacion(turno(LocalDateTime.of(2027, 3, 1, 9, 0), 3L), SEDE, "Kine", antes);

		ArgumentCaptor<NotificationEnqueueCommand> captor =
				ArgumentCaptor.forClass(NotificationEnqueueCommand.class);
		verify(outbox, times(3)).enqueue(captor.capture());
		List<NotificationEnqueueCommand> comandos = captor.getAllValues();

		assertThat(comandos.get(0).tipo()).isEqualTo(NotificationType.TURNO_REPROGRAMADO);
		assertThat(comandos.get(0).datosDeRender())
				.containsEntry("turnoInicio", "08/03/2027 a las 10:30")
				.containsEntry("turnoInicioAnterior", "01/03/2027 a las 09:00");
		assertThat(comandos.get(0).claveIdempotente())
				.isEqualTo("turno-reprogramado:" + TURNO_ID + ":v2")
				.isEqualTo(comandos.get(1).claveIdempotente())
				.isNotEqualTo(comandos.get(2).claveIdempotente());
	}

	/**
	 * RN-M26-001. Un valor que el sanitizador del outbox rechaza —"Jardin Secreto" contiene
	 * {@code secret}— no puede llegar a {@code enqueue}: alli lanzaria dentro de la transaccion y
	 * la dejaria marcada para rollback. Se omite ese dato y el aviso sale igual.
	 */
	@Test
	@DisplayName("un dato que el outbox rechazaria se omite antes de encolar, y el aviso sale igual")
	void un_dato_rechazado_se_omite() {
		conCorreo();
		ConsultorioSnapshot sede = new ConsultorioSnapshot(3L, ORG_ID, "Jardin Secreto", ZONA, true);
		rechazarClave("consultorioNombre");

		avisos.avisarReserva(turno(LocalDateTime.of(2027, 3, 1, 9, 0), 0L), sede, "Kine");

		assertThat(encolado().datosDeRender())
				.doesNotContainKey("consultorioNombre")
				.containsKeys("nombre", "turnoInicio", "servicioNombre");
	}

	// =================================================================================

	/** El sanitizador real, reducido a lo que importa: rechaza esa clave y acepta las demas. */
	private void rechazarClave(String clave) {
		willAnswer(invocacion -> {
			Map<String, String> datos = invocacion.getArgument(0);
			if (datos.containsKey(clave)) {
				throw new IllegalArgumentException("contiene un enlace o un secreto");
			}
			return null;
		}).given(outbox).validarDatosDeRender(any());
	}

	private void conCorreo() {
		given(contactos.findAll(ORG_ID, List.of(PERSONA_ID))).willReturn(Map.of(
				PERSONA_ID, new ContactoDePersona(PERSONA_ID, "ana@ejemplo.test", "Ana")));
	}

	private NotificationEnqueueCommand encolado() {
		ArgumentCaptor<NotificationEnqueueCommand> captor =
				ArgumentCaptor.forClass(NotificationEnqueueCommand.class);
		verify(outbox).enqueue(captor.capture());
		return captor.getValue();
	}

	private static Turno turno(LocalDateTime inicioLocal, long version) {
		Instant inicio = inicioLocal.atZone(ZoneId.of(ZONA)).toInstant();
		Turno turno = new Turno(ORG_ID, SEDE.id(), 42L, PERSONA_ID, 31L, null,
				inicio, inicio.plusSeconds(45 * 60), 9L, Instant.now(), null, null);
		ReflectionTestUtils.setField(turno, "id", TURNO_ID);
		ReflectionTestUtils.setField(turno, "version", version);
		return turno;
	}
}
