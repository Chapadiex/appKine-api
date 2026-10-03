package com.akine.encounter.infrastructure;

import com.akine.clinical.spi.HistoriaClinicaDirectory;
import com.akine.clinical.spi.HistoriaClinicaSnapshot;
import com.akine.encounter.domain.port.SesionRepositoryPort;
import com.akine.organization.spi.AccountContextDirectory;
import com.akine.organization.spi.MembershipSnapshot;
import com.akine.scheduling.spi.TurnoDirectory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * La relacion asistencial real (C-3): el algoritmo de la sonda, con los cuatro puertos mockeados.
 *
 * <p>Los ids son todos distintos entre si a proposito: si la sonda cruzara dos argumentos al
 * llamar a un puerto, el {@code verify} con valores exactos lo detecta. Que las consultas lleven
 * {@code organizationId} y {@code consultorioId} es lo que este test fija; que el SQL los use es
 * cosa de {@code RelacionAsistencialIT}.
 */
@DisplayName("Relacion asistencial real: la sonda de encounter (C-3)")
class EncounterRelacionAsistencialProbeTest {

	private static final long ORG = 7L;
	private static final long SEDE = 20L;
	private static final long CUENTA = 501L;
	private static final long PERSONA = 1204L;
	private static final long MEMBERSHIP = 88L;
	private static final long HISTORIA = 3001L;

	private final SesionRepositoryPort sesiones = mock(SesionRepositoryPort.class);
	private final TurnoDirectory turnos = mock(TurnoDirectory.class);
	private final HistoriaClinicaDirectory historias = mock(HistoriaClinicaDirectory.class);
	private final AccountContextDirectory cuentas = mock(AccountContextDirectory.class);

	private final EncounterRelacionAsistencialProbe sonda =
			new EncounterRelacionAsistencialProbe(sesiones, turnos, historias, cuentas);

	@Test
	@DisplayName("sin membership vigente en la organizacion no hay relacion, y no se consulta nada mas")
	void sin_membership_no_hay_relacion() {
		// AC-1
		given(cuentas.membership(CUENTA, ORG)).willReturn(Optional.empty());

		assertThat(sonda.tieneRelacionAsistencial(ORG, SEDE, CUENTA, PERSONA)).isFalse();

		verify(cuentas).membership(CUENTA, ORG);
		verifyNoInteractions(historias, sesiones, turnos);
	}

	@Test
	@DisplayName("la persona sin historia clinica en la organizacion no tiene relacion")
	void sin_historia_clinica_no_hay_relacion() {
		// AC-1
		darMembership();
		given(historias.find(ORG, PERSONA)).willReturn(Optional.empty());

		assertThat(sonda.tieneRelacionAsistencial(ORG, SEDE, CUENTA, PERSONA)).isFalse();

		verify(historias).find(ORG, PERSONA);
		verifyNoInteractions(sesiones, turnos);
	}

	@Test
	@DisplayName("una sesion del actor en esa historia y sede alcanza: no se consultan los turnos")
	void una_sesion_del_actor_alcanza() {
		// AC-1
		darMembership();
		darHistoria();
		given(sesiones.existeSesionDelActor(ORG, SEDE, HISTORIA, MEMBERSHIP, CUENTA))
				.willReturn(true);

		assertThat(sonda.tieneRelacionAsistencial(ORG, SEDE, CUENTA, PERSONA)).isTrue();

		verify(sesiones).existeSesionDelActor(ORG, SEDE, HISTORIA, MEMBERSHIP, CUENTA);
		verifyNoInteractions(turnos);
	}

	@Test
	@DisplayName("sin sesion pero con un turno vivo del profesional con la persona, hay relacion")
	void un_turno_vivo_alcanza_sin_sesion() {
		// AC-1
		darMembership();
		darHistoria();
		given(sesiones.existeSesionDelActor(ORG, SEDE, HISTORIA, MEMBERSHIP, CUENTA))
				.willReturn(false);
		given(turnos.existeTurnoVivoDeProfesionalConPersona(ORG, SEDE, MEMBERSHIP, PERSONA))
				.willReturn(true);

		assertThat(sonda.tieneRelacionAsistencial(ORG, SEDE, CUENTA, PERSONA)).isTrue();

		verify(turnos).existeTurnoVivoDeProfesionalConPersona(ORG, SEDE, MEMBERSHIP, PERSONA);
	}

	@Test
	@DisplayName("sin sesion ni turno no hay relacion, y las dos consultas llevaron organizacion y sede")
	void sin_sesion_ni_turno_no_hay_relacion() {
		// AC-1
		darMembership();
		darHistoria();
		given(sesiones.existeSesionDelActor(ORG, SEDE, HISTORIA, MEMBERSHIP, CUENTA))
				.willReturn(false);
		given(turnos.existeTurnoVivoDeProfesionalConPersona(ORG, SEDE, MEMBERSHIP, PERSONA))
				.willReturn(false);

		assertThat(sonda.tieneRelacionAsistencial(ORG, SEDE, CUENTA, PERSONA)).isFalse();

		// Los valores exactos (y no `any()`) son la verificacion: organizationId y consultorioId
		// viajan a las dos consultas, y ningun id se cruza con otro.
		verify(sesiones).existeSesionDelActor(ORG, SEDE, HISTORIA, MEMBERSHIP, CUENTA);
		verify(turnos).existeTurnoVivoDeProfesionalConPersona(ORG, SEDE, MEMBERSHIP, PERSONA);
		verifyNoMoreInteractions(sesiones, turnos);
	}

	@Test
	@DisplayName("la misma pregunta en otra sede consulta esa sede, no la anterior")
	void la_sede_de_la_pregunta_es_la_de_las_consultas() {
		// AC-1
		long otraSede = 21L;
		darMembership();
		darHistoria();

		assertThat(sonda.tieneRelacionAsistencial(ORG, otraSede, CUENTA, PERSONA)).isFalse();

		verify(sesiones).existeSesionDelActor(ORG, otraSede, HISTORIA, MEMBERSHIP, CUENTA);
		verify(turnos).existeTurnoVivoDeProfesionalConPersona(ORG, otraSede, MEMBERSHIP, PERSONA);
		verifyNoMoreInteractions(sesiones, turnos);
	}

	private void darMembership() {
		given(cuentas.membership(CUENTA, ORG)).willReturn(Optional.of(new MembershipSnapshot(
				MEMBERSHIP, "PROFESIONAL", false, Instant.parse("2020-01-01T00:00:00Z"), null,
				true)));
	}

	private void darHistoria() {
		given(historias.find(ORG, PERSONA)).willReturn(Optional.of(new HistoriaClinicaSnapshot(
				HISTORIA, ORG, PERSONA, Instant.parse("2025-01-01T00:00:00Z"), true, 0L)));
	}
}
