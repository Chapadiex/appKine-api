package com.akine.clinical.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Los invariantes del adjunto clinico que no dependen de ningun servicio.
 *
 * <p>Lo que fijan estos tests es que la clase no admita los dos estados que arruinarian la
 * trazabilidad de una historia clinica: un adjunto vacio y una baja sin motivo.
 */
@DisplayName("AdjuntoClinico")
class AdjuntoClinicoTest {

	private static final Instant AHORA = Instant.parse("2026-09-19T10:00:00Z");

	@Test
	@DisplayName("nace vigente, disponible y sin entrada si no se la declara")
	void nace_vigente() {
		AdjuntoClinico adjunto = adjunto(null);

		assertThat(adjunto.isVigente()).isTrue();
		assertThat(adjunto.isDescargable()).isTrue();
		assertThat(adjunto.getEntradaClinicaId()).isNull();
		assertThat(adjunto.getEstado()).isEqualTo(EstadoAdjuntoClinico.DISPONIBLE);
	}

	@Test
	@DisplayName("un adjunto vacio no es un adjunto")
	void rechaza_contenido_vacio() {
		assertThatThrownBy(() -> new AdjuntoClinico(1L, 2L, null, 3L,
				CategoriaAdjuntoClinico.ESTUDIO, null, "eco.pdf", "application/pdf", 0L,
				"abc", "clave", 9L, AHORA))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("la baja exige motivo declarado")
	void baja_sin_motivo_no_pasa() {
		AdjuntoClinico adjunto = adjunto(null);

		assertThatThrownBy(() -> adjunto.deactivate(AHORA, "   "))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(adjunto.isVigente()).isTrue();
	}

	@Test
	@DisplayName("la baja no toca el contenido: el adjunto se sigue pudiendo descargar")
	void la_baja_no_impide_descargar() {
		AdjuntoClinico adjunto = adjunto(null);

		adjunto.deactivate(AHORA, "  Cargado en la historia equivocada  ");

		assertThat(adjunto.isVigente()).isFalse();
		assertThat(adjunto.getDeactivationReason()).isEqualTo("Cargado en la historia equivocada");
		assertThat(adjunto.isDescargable()).isTrue();
	}

	@Test
	@DisplayName("reclasificar solo cambia como esta descripto, y un null no pisa nada")
	void reclasificar_no_toca_el_contenido() {
		AdjuntoClinico adjunto = adjunto(77L);

		adjunto.reclasificar(CategoriaAdjuntoClinico.INFORME, "Informe de resonancia");
		adjunto.reclasificar(null, null);

		assertThat(adjunto.getCategoria()).isEqualTo(CategoriaAdjuntoClinico.INFORME);
		assertThat(adjunto.getTitulo()).isEqualTo("Informe de resonancia");
		assertThat(adjunto.getNombreArchivo()).isEqualTo("resonancia.pdf");
		assertThat(adjunto.getChecksumSha256()).isEqualTo("abc123");
		assertThat(adjunto.getEntradaClinicaId()).isEqualTo(77L);
	}

	@Test
	@DisplayName("marcarNoDisponible conserva la metadata: la descarga da 409, no 404")
	void binario_perdido_no_borra_la_fila() {
		AdjuntoClinico adjunto = adjunto(null);

		adjunto.marcarNoDisponible();

		assertThat(adjunto.isDescargable()).isFalse();
		assertThat(adjunto.isVigente()).isTrue();
	}

	private static AdjuntoClinico adjunto(Long entradaId) {
		return new AdjuntoClinico(10L, 700L, entradaId, 20L,
				CategoriaAdjuntoClinico.ESTUDIO, null, "resonancia.pdf", "application/pdf",
				2048L, "abc123", "0123456789abcdef0123456789abcdef", 40L, AHORA);
	}
}
