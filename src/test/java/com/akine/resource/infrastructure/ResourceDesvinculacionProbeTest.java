package com.akine.resource.infrastructure;

import com.akine.organization.spi.ColaboradorDesvinculacionProbe.Impacto;
import com.akine.resource.domain.DisponibilidadExcepcion;
import com.akine.resource.domain.MotivoExcepcion;
import com.akine.resource.domain.TipoExcepcion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * Tests de {@link ResourceDesvinculacionProbe}: la implementacion de
 * {@code organization.spi.ColaboradorDesvinculacionProbe} desde {@code resource} (RN-M05-004).
 *
 * <p>Los dos repositorios se mockean: lo que se verifica aca es que la sonda combina
 * correctamente sus dos fuentes (bloques activos y excepciones futuras de la membership) en un
 * unico {@code Impacto}, no la correctitud del JPQL de cada consulta —eso es responsabilidad de
 * un test de integracion contra MySQL real, que es donde {@code membershipId IS NULL} realmente
 * se ejecuta o no.
 */
@ExtendWith(MockitoExtension.class)
class ResourceDesvinculacionProbeTest {

	private static final long ORG_ID = 10L;
	private static final long MEMBERSHIP_ID = 60L;
	private static final long ACCOUNT_ID = 30L;
	private static final Instant AT = Instant.parse("2026-09-01T00:00:00Z");

	@Mock
	private BloqueDisponibilidadRepository bloqueRepository;

	@Mock
	private DisponibilidadExcepcionRepository excepcionRepository;

	@InjectMocks
	private ResourceDesvinculacionProbe probe;

	@Test
	@DisplayName("sin bloques activos ni excepciones futuras, no hay nada que reportar")
	void sin_bloques_ni_excepciones_devuelve_ninguno() {
		given(bloqueRepository.countByOrganizationIdAndMembershipIdAndActiveTrue(ORG_ID, MEMBERSHIP_ID))
				.willReturn(0L);
		given(excepcionRepository.findFuturasDeLaMembership(eq(ORG_ID), eq(MEMBERSHIP_ID), any()))
				.willReturn(List.of());

		Impacto impacto = probe.pendingWorkOn(ORG_ID, MEMBERSHIP_ID, ACCOUNT_ID, AT);

		assertThat(impacto).isEqualTo(Impacto.ninguno());
		assertThat(impacto.hayAlgo()).isFalse();
	}

	@Test
	@DisplayName("solo bloques activos: el primero es 'at', porque un bloque activo ya rige ahora")
	void solo_bloques_activos_reporta_desde_at() {
		given(bloqueRepository.countByOrganizationIdAndMembershipIdAndActiveTrue(ORG_ID, MEMBERSHIP_ID))
				.willReturn(3L);
		given(excepcionRepository.findFuturasDeLaMembership(eq(ORG_ID), eq(MEMBERSHIP_ID), any()))
				.willReturn(List.of());

		Impacto impacto = probe.pendingWorkOn(ORG_ID, MEMBERSHIP_ID, ACCOUNT_ID, AT);

		assertThat(impacto.tipo()).isEqualTo("bloques de disponibilidad");
		assertThat(impacto.count()).isEqualTo(3L);
		assertThat(impacto.desde()).isEqualTo(AT);
		assertThat(impacto.hayAlgo()).isTrue();
	}

	@Test
	@DisplayName("solo excepciones futuras: el primero es la fecha de la mas temprana")
	void solo_excepciones_futuras_reporta_la_fecha_mas_temprana() {
		given(bloqueRepository.countByOrganizationIdAndMembershipIdAndActiveTrue(ORG_ID, MEMBERSHIP_ID))
				.willReturn(0L);
		DisponibilidadExcepcion masTemprana = excepcion(LocalDate.of(2026, 9, 10));
		DisponibilidadExcepcion masTardia = excepcion(LocalDate.of(2026, 10, 1));
		given(excepcionRepository.findFuturasDeLaMembership(eq(ORG_ID), eq(MEMBERSHIP_ID), any()))
				.willReturn(List.of(masTemprana, masTardia));

		Impacto impacto = probe.pendingWorkOn(ORG_ID, MEMBERSHIP_ID, ACCOUNT_ID, AT);

		assertThat(impacto.tipo()).isEqualTo("excepciones de disponibilidad");
		assertThat(impacto.count()).isEqualTo(2L);
		assertThat(impacto.desde())
				.isEqualTo(LocalDate.of(2026, 9, 10).atStartOfDay(ZoneOffset.UTC).toInstant());
	}

	@Test
	@DisplayName("bloques y excepciones a la vez: el tipo combina ambos y el total es la suma")
	void bloques_y_excepciones_combinan_tipo_y_suman_total() {
		given(bloqueRepository.countByOrganizationIdAndMembershipIdAndActiveTrue(ORG_ID, MEMBERSHIP_ID))
				.willReturn(2L);
		given(excepcionRepository.findFuturasDeLaMembership(eq(ORG_ID), eq(MEMBERSHIP_ID), any()))
				.willReturn(List.of(excepcion(LocalDate.of(2026, 9, 15))));

		Impacto impacto = probe.pendingWorkOn(ORG_ID, MEMBERSHIP_ID, ACCOUNT_ID, AT);

		assertThat(impacto.tipo()).isEqualTo("bloques y excepciones de disponibilidad");
		assertThat(impacto.count()).isEqualTo(3L);
		// Hay bloque activo: "ahora" siempre precede a cualquier excepcion futura recortada.
		assertThat(impacto.desde()).isEqualTo(AT);
	}

	@Test
	@DisplayName("accountId no participa de la busqueda: bloques y excepciones se indexan por membership")
	void account_id_no_participa_de_la_busqueda() {
		given(bloqueRepository.countByOrganizationIdAndMembershipIdAndActiveTrue(ORG_ID, MEMBERSHIP_ID))
				.willReturn(0L);
		given(excepcionRepository.findFuturasDeLaMembership(eq(ORG_ID), eq(MEMBERSHIP_ID), any()))
				.willReturn(List.of());

		probe.pendingWorkOn(ORG_ID, MEMBERSHIP_ID, 999L, AT);
		probe.pendingWorkOn(ORG_ID, MEMBERSHIP_ID, 1L, AT);

		verify(bloqueRepository, org.mockito.Mockito.times(2))
				.countByOrganizationIdAndMembershipIdAndActiveTrue(ORG_ID, MEMBERSHIP_ID);
	}

	/** Convierte {@code at} a la misma fecha UTC que usa la sonda, para verificar el filtro. */
	@Test
	@DisplayName("la fecha de corte de excepciones futuras es 'at' llevado a fecha UTC")
	void la_fecha_de_corte_es_at_en_utc() {
		given(bloqueRepository.countByOrganizationIdAndMembershipIdAndActiveTrue(ORG_ID, MEMBERSHIP_ID))
				.willReturn(0L);
		given(excepcionRepository.findFuturasDeLaMembership(eq(ORG_ID), eq(MEMBERSHIP_ID), any()))
				.willReturn(List.of());

		probe.pendingWorkOn(ORG_ID, MEMBERSHIP_ID, ACCOUNT_ID, AT);

		verify(excepcionRepository).findFuturasDeLaMembership(
				ORG_ID, MEMBERSHIP_ID, LocalDate.ofInstant(AT, ZoneOffset.UTC));
	}

	private static DisponibilidadExcepcion excepcion(LocalDate fechaDesde) {
		return new DisponibilidadExcepcion(
				ORG_ID,
				20L,
				MEMBERSHIP_ID,
				TipoExcepcion.CIERRE,
				MotivoExcepcion.LICENCIA,
				fechaDesde,
				fechaDesde.plusDays(5),
				null,
				null,
				null,
				"licencia de prueba");
	}
}
