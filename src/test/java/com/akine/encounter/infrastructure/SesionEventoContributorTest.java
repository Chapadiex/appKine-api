package com.akine.encounter.infrastructure;

import com.akine.clinical.spi.EventoClinico;
import com.akine.encounter.domain.Sesion;
import com.akine.encounter.domain.port.SesionRepositoryPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * La sesion cerrada como hecho del timeline clinico.
 *
 * <p>Lo que este test fija es que el evento se date por el <b>cierre</b> y que no arrastre nada de
 * la atencion: ni evolucion, ni motivo clinico, ni nota de cierre. Quien quiera la sesion va a
 * {@code encounter} con su propio permiso, y ese acceso se audita alla.
 *
 * <p>Que solo se indexen las cerradas lo hace cumplir la consulta —{@code estado = 'CERRADA'}— y
 * no este mapeo, asi que <b>eso no se verifica aca</b>: necesita base real.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SesionEventoContributor")
class SesionEventoContributorTest {

	private static final long ORG_ID = 10L;
	private static final long HC_ID = 700L;
	private static final Instant CIERRE = Instant.parse("2026-09-19T12:00:00Z");

	@Mock
	private SesionRepositoryPort sesiones;

	@Test
	@DisplayName("data el evento por el cierre y no arrastra contenido clinico")
	void sesion_cerrada() {
		Sesion sesion = new Sesion(ORG_ID, 20L, HC_ID, null, 300L, 400L, 500L,
				CIERRE.minusSeconds(3600), 40L);
		ReflectionTestUtils.setField(sesion, "id", 1100L);
		ReflectionTestUtils.setField(sesion, "cerradaEn", CIERRE);
		ReflectionTestUtils.setField(sesion, "notaDeCierre", "Dolor lumbar en remision");
		given(sesiones.buscarCerradasParaTimeline(ORG_ID, HC_ID, CIERRE, 5, null))
				.willReturn(List.of(sesion));

		List<EventoClinico> eventos =
				new SesionEventoContributor(sesiones).eventosDe(ORG_ID, HC_ID, CIERRE, 5, null);

		assertThat(eventos).singleElement().satisfies(evento -> {
			assertThat(evento.ocurrioEn()).isEqualTo(CIERRE);
			assertThat(evento.origen()).isEqualTo("SESION");
			assertThat(evento.tipo()).isEqualTo("SESION_CERRADA");
			assertThat(evento.referencia()).isEqualTo(1100L);
			assertThat(evento.titulo()).doesNotContain("lumbar");
		});
	}
}
