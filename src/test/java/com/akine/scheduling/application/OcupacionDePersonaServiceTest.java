package com.akine.scheduling.application;

import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.platform.spi.problem.ConflictoDeAgenda;
import com.akine.platform.spi.problem.ConflictoDeAgenda.Tipo;
import com.akine.resource.spi.DisponibilidadDirectory;
import com.akine.resource.spi.DisponibilidadDirectory.CierreDeAgenda;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import com.akine.scheduling.spi.OcupacionDePersona.Consulta;
import com.akine.scheduling.spi.OcupacionExternaProbe;
import com.akine.scheduling.spi.OcupacionExternaProbe.OcupacionExterna;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * La logica central de ocupacion por persona (disponibilidad central, 0.158.0): cruza turnos y clases de
 * todas las sedes de la persona, respeta las exclusiones y traduce los cierres con su causa.
 */
class OcupacionDePersonaServiceTest {

	private static final long ORG = 1L;
	private static final long CENTRO = 10L;
	private static final long NORTE = 20L;
	private static final long EN_CENTRO = 31L;
	private static final long EN_NORTE = 41L;
	private static final String ZONA = "America/Argentina/Cordoba";
	/** Lunes 08/03/2027 10:00 en Cordoba. */
	private static final Instant DIEZ = Instant.parse("2027-03-08T13:00:00Z");
	private static final Instant ONCE = DIEZ.plusSeconds(3600);

	private final TurnoRepositoryPort turnos = mock(TurnoRepositoryPort.class);
	private final OcupacionExternaProbe clases = mock(OcupacionExternaProbe.class);
	private final AgendaDelProfesional agenda = mock(AgendaDelProfesional.class);
	private final DisponibilidadDirectory disponibilidad = mock(DisponibilidadDirectory.class);
	private final ConsultorioDirectory consultorios = mock(ConsultorioDirectory.class);
	private final OcupacionDePersonaService servicio =
			new OcupacionDePersonaService(turnos, clases, agenda, disponibilidad, consultorios);

	@BeforeEach
	void setUp() {
		given(agenda.deLaMismaPersona(ORG, EN_CENTRO)).willReturn(Set.of(EN_CENTRO, EN_NORTE));
		given(consultorios.find(ORG, CENTRO)).willReturn(Optional.of(new ConsultorioSnapshot(CENTRO, ORG, "Centro", ZONA, true)));
		given(consultorios.find(ORG, NORTE)).willReturn(Optional.of(new ConsultorioSnapshot(NORTE, ORG, "Norte", ZONA, true)));
		given(turnos.findVivosDeProfesionalesQueCruzan(anyLong(), any(), any(), any())).willReturn(List.of());
		given(clases.ocupacionDeProfesionales(anyLong(), any(), any(), any())).willReturn(List.of());
	}

	@Test
	@DisplayName("Un turno de la misma persona en otra sede es el conflicto, con la sede y la franja reales")
	void turno_en_otra_sede() {
		Turno enNorte = turno(500L, NORTE, EN_NORTE, 7L);
		given(turnos.findVivosDeProfesionalesQueCruzan(ORG, Set.of(EN_CENTRO, EN_NORTE), DIEZ, ONCE))
				.willReturn(List.of(enNorte));

		List<ConflictoDeAgenda> conflictos = servicio.actividadReservada(Consulta.de(ORG, CENTRO, EN_CENTRO, DIEZ, ONCE));

		assertThat(conflictos).singleElement().satisfies(c -> {
			assertThat(c.tipo()).isEqualTo(Tipo.TURNO);
			assertThat(c.eventoId()).isEqualTo(500L);
			assertThat(c.consultorioId()).isEqualTo(NORTE);
			assertThat(c.consultorioNombre()).isEqualTo("Norte");
			assertThat(c.mismaSede()).isFalse();
			assertThat(c.detalle("No es posible asignarle otro turno en ese horario."))
					.isEqualTo("El profesional ya tiene un turno el 08/03/2027 de 10:00 a 11:00 en la sede Norte. "
							+ "No es posible asignarle otro turno en ese horario.");
		});
	}

	@Test
	@DisplayName("El turno que se mueve, el del mismo grupo y la clase excluida no son conflicto")
	void exclusiones() {
		given(turnos.findVivosDeProfesionalesQueCruzan(anyLong(), any(), any(), any()))
				.willReturn(List.of(turno(1L, CENTRO, EN_CENTRO, 7L), turno(2L, CENTRO, EN_CENTRO, 8L)));
		given(clases.ocupacionDeProfesionales(anyLong(), any(), any(), any()))
				.willReturn(List.of(new OcupacionExterna("CLASE", 9L, CENTRO, EN_CENTRO, DIEZ, ONCE)));

		List<ConflictoDeAgenda> conflictos = servicio.actividadReservada(
				new Consulta(ORG, CENTRO, EN_CENTRO, DIEZ, ONCE, 1L, 9L, 8L));

		assertThat(conflictos).isEmpty();
	}

	@Test
	@DisplayName("Un cierre del profesional por vacaciones es AUSENCIA y uno de la sede es CIERRE_DE_SEDE")
	void cierres_con_su_causa() {
		LocalDate lunes = LocalDate.of(2027, 3, 8);
		given(disponibilidad.cierresEnVentana(anyLong(), anyLong(), any(), any())).willReturn(List.of(
				new CierreDeAgenda(70L, EN_CENTRO, "VACACIONES", lunes, lunes.plusDays(1),
						LocalTime.of(10, 30), LocalTime.of(11, 30), null),
				new CierreDeAgenda(71L, null, "OTRO", lunes, lunes.plusDays(1), null, null, "Corte de luz"),
				new CierreDeAgenda(72L, 99L, "BLOQUEO", lunes, lunes.plusDays(1), null, null, null)));

		List<ConflictoDeAgenda> conflictos = servicio.cierres(Consulta.de(ORG, CENTRO, EN_CENTRO, DIEZ, ONCE));

		assertThat(conflictos).extracting(ConflictoDeAgenda::tipo)
				.containsExactly(Tipo.CIERRE_DE_SEDE, Tipo.AUSENCIA);
		assertThat(conflictos.get(1).desde()).isEqualTo(DIEZ.plusSeconds(1800));
		assertThat(conflictos.get(1).eventoId()).isEqualTo(70L);
	}

	private static Turno turno(long id, long sede, long membership, long oferta) {
		Turno turno = new Turno(ORG, sede, oferta, 3L, membership, null, DIEZ, ONCE, 1L, DIEZ, null, null);
		ReflectionTestUtils.setField(turno, "id", id);
		return turno;
	}
}
