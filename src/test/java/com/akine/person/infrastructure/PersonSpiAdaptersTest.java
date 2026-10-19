package com.akine.person.infrastructure;

import com.akine.person.application.AutorizacionElegibleView;
import com.akine.person.application.OperatingActor;
import com.akine.person.application.PerfilPacienteService;
import com.akine.person.application.PersonaView;
import com.akine.person.domain.Autorizacion;
import com.akine.person.domain.EstadoAutorizacion;
import com.akine.person.domain.port.PersonRepositoryPorts.AutorizacionRepositoryPort;
import com.akine.person.spi.ActivacionDePerfilPaciente;
import com.akine.person.spi.AutorizacionSnapshot;
import com.akine.person.spi.PacienteSnapshot;
import com.akine.testsupport.Fixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Los dos adaptadores de {@code person.spi} que ningún IT recorre: el directorio de autorizaciones
 * que consulta {@code clinical} al vincular un plan, y el aprovisionamiento del perfil de paciente
 * que usa la reserva de turnos. Lo que se fija es el contrato del snapshot: el veredicto y su
 * motivo, y de dónde sale la organización.
 */
class PersonSpiAdaptersTest {

	private static final long ORG = 10L;
	private static final long PERSONA = 12L;
	private static final LocalDate HOY = LocalDate.of(2026, 10, 15);

	private final AutorizacionRepositoryPort repositorio = mock(AutorizacionRepositoryPort.class);
	private final PersonAutorizacionDirectory directorio = new PersonAutorizacionDirectory(
			repositorio, organizationId -> java.time.ZoneId.systemDefault());

	@Test
	@DisplayName("una autorizacion aprobada, vigente y con saldo habilita, sin motivo")
	void habilita() {
		dada(autorizacion(EstadoAutorizacion.APROBADA, 10, 2, HOY.minusDays(5), HOY.plusDays(20)));

		AutorizacionSnapshot snapshot = directorio.find(ORG, PERSONA, 40L, HOY).orElseThrow();

		assertThat(snapshot.habilita()).isTrue();
		assertThat(snapshot.motivoNoElegible()).isNull();
		assertThat(snapshot.saldo()).isEqualTo(8);
		assertThat(snapshot.fecha()).isEqualTo(HOY);
		assertThat(snapshot.organizationId()).isEqualTo(ORG);
	}

	@Test
	@DisplayName("cada razon por la que no habilita viaja con su motivo")
	void motivos_no_elegible() {
		assertThat(motivo(autorizacion(EstadoAutorizacion.APROBADA, 10, 0, HOY.minusDays(40), HOY.minusDays(1))))
				.isEqualTo(AutorizacionElegibleView.VENCIDA);
		assertThat(motivo(autorizacion(EstadoAutorizacion.PENDIENTE, 10, 0, HOY.minusDays(5), HOY.plusDays(5))))
				.isEqualTo(AutorizacionElegibleView.NO_APROBADA);
		assertThat(motivo(autorizacion(EstadoAutorizacion.APROBADA, 10, 0, HOY.plusDays(3), HOY.plusDays(30))))
				.isEqualTo(AutorizacionElegibleView.AUN_NO_VIGENTE);
		assertThat(motivo(autorizacion(EstadoAutorizacion.APROBADA, 4, 4, HOY.minusDays(5), HOY.plusDays(5))))
				.isEqualTo(AutorizacionElegibleView.AGOTADA);
	}

	@Test
	@DisplayName("una autorizacion dada de baja no existe para el consumidor; sin fecha se evalua hoy")
	void baja_y_fecha_por_defecto() {
		Autorizacion dadaDeBaja = autorizacion(EstadoAutorizacion.APROBADA, 10, 0,
				LocalDate.now().minusDays(1), LocalDate.now().plusDays(1));
		dadaDeBaja.deactivate(Instant.now(), "Cargada por error");
		dada(dadaDeBaja);
		assertThat(directorio.find(ORG, PERSONA, 40L, HOY)).isEmpty();

		dada(autorizacion(EstadoAutorizacion.APROBADA, 10, 0,
				LocalDate.now().minusDays(1), LocalDate.now().plusDays(1)));
		assertThat(directorio.find(ORG, PERSONA, 40L, null).orElseThrow().fecha())
				.isEqualTo(LocalDate.now());
	}

	@Test
	@DisplayName("el perfil de paciente se activa con el actor de la activacion y la organizacion sale de ella")
	void aprovisionamiento_de_perfil() {
		PerfilPacienteService perfiles = mock(PerfilPacienteService.class);
		PersonaView persona = Fixture.de(PersonaView.class);
		given(perfiles.activar(eq(new OperatingActor(8L, false, ORG, 20L)), eq(PERSONA), eq("Primer turno")))
				.willReturn(persona);

		PacienteSnapshot snapshot = new PersonPerfilPacienteProvisioning(perfiles).asegurarPerfilVigente(
				new ActivacionDePerfilPaciente(8L, false, ORG, 20L, PERSONA, "Primer turno"));

		verify(perfiles).activar(new OperatingActor(8L, false, ORG, 20L), PERSONA, "Primer turno");
		assertThat(snapshot.organizationId()).isEqualTo(ORG);
		assertThat(snapshot.personaId()).isEqualTo(persona.id());
		assertThat(snapshot.apellido()).isEqualTo(persona.apellido());
	}

	private String motivo(Autorizacion autorizacion) {
		dada(autorizacion);
		AutorizacionSnapshot snapshot = directorio.find(ORG, PERSONA, 40L, HOY).orElseThrow();
		assertThat(snapshot.habilita()).isFalse();
		return snapshot.motivoNoElegible();
	}

	private void dada(Autorizacion autorizacion) {
		given(repositorio.findByIdAndOrganizationIdAndPersonaId(40L, ORG, PERSONA))
				.willReturn(Optional.of(autorizacion));
	}

	private static Autorizacion autorizacion(
			EstadoAutorizacion estado, int cantidad, int consumida, LocalDate desde, LocalDate hasta) {
		Autorizacion autorizacion = new Autorizacion(
				ORG, PERSONA, 20L, 3L, null, 5L, "AUT-1", estado, cantidad, desde, hasta, null, null);
		ReflectionTestUtils.setField(autorizacion, "id", 40L);
		ReflectionTestUtils.setField(autorizacion, "cantidadConsumida", consumida);
		return autorizacion;
	}
}
