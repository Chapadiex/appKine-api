package com.akine.clinical.infrastructure;

import com.akine.clinical.domain.AdjuntoClinico;
import com.akine.clinical.domain.AntecedenteClinico;
import com.akine.clinical.domain.CategoriaAdjuntoClinico;
import com.akine.clinical.domain.EntradaClinica;
import com.akine.clinical.domain.OrigenEntradaClinica;
import com.akine.clinical.domain.TipoAntecedente;
import com.akine.clinical.domain.TipoEntradaClinica;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.AdjuntoClinicoRepositoryPort;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.AntecedenteClinicoRepositoryPort;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.EntradaClinicaRepositoryPort;
import com.akine.clinical.spi.EventoClinico;
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
 * Las tres fuentes que {@code clinical} aporta al timeline.
 *
 * <p>Lo que estos tests fijan no es el mapeo campo a campo sino la regla del challenge seccion 4:
 * <b>ningun evento lleva contenido clinico</b>. Por eso cada caso construye la entidad con un
 * cuerpo, una descripcion o un titulo reconocibles y verifica que ese texto <b>no aparezca</b> en
 * el evento. El timeline es un indice, no un visor.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Contribuyentes de timeline de clinical")
class ContribuyentesDeTimelineTest {

	private static final long ORG_ID = 10L;
	private static final long HC_ID = 700L;
	private static final Instant T = Instant.parse("2026-09-19T10:00:00Z");
	private static final int LIMITE = 5;

	@Mock
	private EntradaClinicaRepositoryPort entradas;

	@Mock
	private AdjuntoClinicoRepositoryPort adjuntos;

	@Mock
	private AntecedenteClinicoRepositoryPort antecedentes;

	@Test
	@DisplayName("la entrada se indexa por cuando ocurrio y no lleva su cuerpo")
	void entrada() {
		EntradaClinica entrada = new EntradaClinica(ORG_ID, HC_ID, TipoEntradaClinica.EVOLUCION,
				T, OrigenEntradaClinica.MANUAL, null, T.plusSeconds(3600), 40L);
		ReflectionTestUtils.setField(entrada, "id", 900L);
		given(entradas.buscarParaTimeline(ORG_ID, HC_ID, T, LIMITE)).willReturn(List.of(entrada));

		List<EventoClinico> eventos =
				new EntradaClinicaContributor(entradas).eventosDe(ORG_ID, HC_ID, T, LIMITE);

		assertThat(eventos).singleElement().satisfies(evento -> {
			assertThat(evento.ocurrioEn()).isEqualTo(T);
			assertThat(evento.origen()).isEqualTo("ENTRADA_CLINICA");
			assertThat(evento.tipo()).isEqualTo("EVOLUCION");
			assertThat(evento.referencia()).isEqualTo(900L);
			assertThat(evento.titulo()).isEqualTo("Entrada clinica");
		});
	}

	@Test
	@DisplayName("el adjunto se indexa por su alta y no lleva ni su titulo ni su nombre de archivo")
	void adjunto() {
		AdjuntoClinico adjunto = new AdjuntoClinico(ORG_ID, HC_ID, null, 20L,
				CategoriaAdjuntoClinico.ESTUDIO, "RMN rodilla derecha, rotura", "rmn.pdf",
				"application/pdf", 1024L, "a".repeat(64), "b".repeat(32), 40L, T);
		ReflectionTestUtils.setField(adjunto, "id", 800L);
		given(adjuntos.buscarParaTimeline(ORG_ID, HC_ID, T, LIMITE)).willReturn(List.of(adjunto));

		List<EventoClinico> eventos =
				new AdjuntoClinicoContributor(adjuntos).eventosDe(ORG_ID, HC_ID, T, LIMITE);

		assertThat(eventos).singleElement().satisfies(evento -> {
			assertThat(evento.ocurrioEn()).isEqualTo(T);
			assertThat(evento.origen()).isEqualTo("ADJUNTO_CLINICO");
			assertThat(evento.tipo()).isEqualTo("ESTUDIO");
			assertThat(evento.referencia()).isEqualTo(800L);
			assertThat(evento.titulo()).doesNotContain("rotura", "rmn.pdf");
		});
	}

	@Test
	@DisplayName("el antecedente se indexa por cuando se registro y no lleva su descripcion")
	void antecedente() {
		AntecedenteClinico antecedente = new AntecedenteClinico(
				ORG_ID, HC_ID, TipoAntecedente.ALERGIA, "Alergia a la penicilina", T, 40L);
		ReflectionTestUtils.setField(antecedente, "id", 600L);
		given(antecedentes.buscarParaTimeline(ORG_ID, HC_ID, T, LIMITE))
				.willReturn(List.of(antecedente));

		List<EventoClinico> eventos = new AntecedenteClinicoContributor(antecedentes)
				.eventosDe(ORG_ID, HC_ID, T, LIMITE);

		assertThat(eventos).singleElement().satisfies(evento -> {
			assertThat(evento.ocurrioEn()).isEqualTo(T);
			assertThat(evento.origen()).isEqualTo("ANTECEDENTE_CLINICO");
			assertThat(evento.tipo()).isEqualTo("ALERGIA");
			assertThat(evento.referencia()).isEqualTo(600L);
			assertThat(evento.titulo()).doesNotContain("penicilina");
		});
	}
}
