package com.akine.clinical.domain;

import com.akine.clinical.domain.exception.CierreDeCasoSinMotivoException;
import com.akine.clinical.domain.exception.EnmiendaSinMotivoException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lo que las entidades clinicas no dejan escribir, sin importar quien las llame.
 *
 * <p>Tres reglas atraviesan todo el modulo y se fijan aca para todas las entidades a la vez:
 * <b>ninguna baja logica sin motivo</b> (regla maestra 10: sin motivo la auditoria no explica nada),
 * <b>"vigente" exige las dos marcas</b> —{@code active} y {@code deleted_at}— porque una fila que
 * tenga una sola es un dato roto y no se trata como viva, y <b>ningun registro clinico sin cuerpo</b>.
 */
@DisplayName("Invariantes de las entidades clinicas")
class InvariantesClinicasTest {

	private static final Instant AHORA = Instant.parse("2026-10-14T12:00:00Z");

	@Test
	@DisplayName("ninguna baja logica clinica se acepta sin motivo, ni nulo ni en blanco")
	void baja_sin_motivo() {
		for (String motivo : new String[] {null, "   "}) {
			assertThatThrownBy(() -> historia().deactivate(AHORA, motivo))
					.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> antecedente().deactivate(AHORA, motivo))
					.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> adjunto().deactivate(AHORA, motivo))
					.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> entrada().deactivate(AHORA, motivo))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	@DisplayName("una fila activa pero con deleted_at no es vigente: un dato roto no se trata como vivo")
	void vigente_exige_las_dos_marcas() {
		HistoriaClinica historia = historia();
		AntecedenteClinico antecedente = antecedente();
		AdjuntoClinico adjunto = adjunto();
		EntradaClinica entrada = entrada();
		assertThat(historia.isVigente()).isTrue();
		assertThat(antecedente.isVigente()).isTrue();
		assertThat(adjunto.isVigente()).isTrue();
		assertThat(entrada.isVigente()).isTrue();

		for (Object fila : new Object[] {historia, antecedente, adjunto, entrada}) {
			ReflectionTestUtils.setField(fila, "deletedAt", AHORA);
		}
		assertThat(historia.isVigente()).isFalse();
		assertThat(antecedente.isVigente()).isFalse();
		assertThat(adjunto.isVigente()).isFalse();
		assertThat(entrada.isVigente()).isFalse();

		historia.deactivate(AHORA, "Historia duplicada");
		assertThat(historia.isVigente()).isFalse();
	}

	@Test
	@DisplayName("un antecedente o una version de entrada sin texto no registran nada")
	void sin_cuerpo() {
		assertThatThrownBy(() -> new AntecedenteClinico(1L, 2L, TipoAntecedente.ALERGIA, " ", AHORA, 9L))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AntecedenteClinico(1L, 2L, null, "Penicilina", AHORA, 9L))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new EntradaClinicaVersion(1L, 2L, 1, null, null, AHORA, 9L))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new EntradaClinicaVersion(1L, 2L, 1, "  ", null, AHORA, 9L))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new EntradaClinicaVersion(1L, 2L, 0, "Evoluciona bien", null, AHORA, 9L))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new EntradaClinicaVersion(1L, null, 1, "Evoluciona bien", null, AHORA, 9L))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("una enmienda exige motivo; el original lo descarta en vez de rechazar el registro")
	void motivo_de_enmienda() {
		assertThatThrownBy(() -> new EntradaClinicaVersion(1L, 2L, 2, "Corrige dosis", " ", AHORA, 9L))
				.isInstanceOf(EnmiendaSinMotivoException.class);
		assertThat(new EntradaClinicaVersion(1L, 2L, 1, "Original", "no aplica", AHORA, 9L)
				.getMotivoEnmienda()).isNull();
	}

	@Test
	@DisplayName("una entrada manual no referencia origen, y una de origen SESION lo declara siempre")
	void referencia_de_origen() {
		assertThatThrownBy(() -> new EntradaClinica(1L, 2L, TipoEntradaClinica.EVOLUCION, AHORA,
				OrigenEntradaClinica.MANUAL, 55L, AHORA, 9L))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new EntradaClinica(1L, 2L, TipoEntradaClinica.EVOLUCION, AHORA,
				OrigenEntradaClinica.SESION, null, AHORA, 9L))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(new EntradaClinica(1L, 2L, TipoEntradaClinica.EVOLUCION, AHORA,
				OrigenEntradaClinica.SESION, 55L, AHORA, 9L).getReferenciaOrigen()).isEqualTo(55L);
		assertThatThrownBy(() -> new EntradaClinica(1L, 2L, null, AHORA,
				OrigenEntradaClinica.MANUAL, null, AHORA, 9L))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("el caso: correlativo positivo, cierre y reapertura con motivo e idempotentes")
	void ciclo_del_caso() {
		assertThatThrownBy(() -> new CasoClinico(1L, 2L, 0, 3L, 4L, "Gonalgia", null, AHORA, 9L))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CasoClinico(1L, 2L, 1, 3L, 4L, "  ", null, AHORA, 9L))
				.as("un caso sin diagnostico presuntivo no nombra ningun problema")
				.isInstanceOf(IllegalArgumentException.class);

		CasoClinico caso = new CasoClinico(1L, 2L, 1, 3L, 4L, "Gonalgia", null, AHORA, 9L);
		assertThat(caso.perteneceAHistoria(2L)).isTrue();
		assertThat(caso.perteneceAHistoria(5L)).isFalse();

		assertThatThrownBy(() -> caso.cerrar("  ", AHORA, 9L))
				.isInstanceOf(CierreDeCasoSinMotivoException.class);
		assertThat(caso.reabrir("No estaba cerrado", AHORA))
				.as("reabrir lo activo es el mismo pedido, no un conflicto")
				.isFalse();
		assertThat(caso.cerrar("Alta", AHORA, 9L)).isTrue();
		assertThat(caso.cerrar("Otra alta", AHORA, 9L)).isFalse();
		assertThat(caso.getMotivoCierre()).as("el motivo original queda intacto").isEqualTo("Alta");
		assertThatThrownBy(() -> caso.reabrir(null, AHORA))
				.isInstanceOf(CierreDeCasoSinMotivoException.class);
		assertThat(caso.reabrir("Recidiva", AHORA)).isTrue();
		assertThat(caso.getMotivoCierre()).isNull();
	}

	@Test
	@DisplayName("el equipo: sin rol entra como TRATANTE, y una participacion terminada no cambia")
	void participacion_en_el_equipo() {
		CasoProfesional participacion = new CasoProfesional(1L, 2L, 3L, null, AHORA);
		assertThat(participacion.getRol()).isEqualTo(RolEnCaso.TRATANTE);

		participacion.cambiarRol(null);
		assertThat(participacion.getRol()).isEqualTo(RolEnCaso.TRATANTE);
		participacion.cambiarRol(RolEnCaso.RESPONSABLE);
		assertThat(participacion.getRol()).isEqualTo(RolEnCaso.RESPONSABLE);

		participacion.desvincular(AHORA);
		participacion.desvincular(AHORA.plusSeconds(60));
		assertThat(participacion.getHasta()).as("la primera salida es la que ocurrio").isEqualTo(AHORA);
		participacion.cambiarRol(RolEnCaso.TRATANTE);
		assertThat(participacion.getRol())
				.as("cambiar el rol no reabre una participacion terminada")
				.isEqualTo(RolEnCaso.RESPONSABLE);
		assertThat(participacion.estaVigente()).isFalse();
	}

	@Test
	@DisplayName("el tipo de un adjunto clinico lo deciden sus bytes, no la extension")
	void tipo_por_firma() {
		assertThat(TipoDeContenidoClinico.detectar(null)).isNull();
		assertThat(TipoDeContenidoClinico.detectar(new byte[0])).isNull();
		assertThat(TipoDeContenidoClinico.detectar(new byte[] {0x25, 0x50})).as("mas corto que la firma").isNull();
		assertThat(TipoDeContenidoClinico.detectar(new byte[] {0x25, 0x50, 0x44, 0x46, 0x2D, 0x31}))
				.isEqualTo("application/pdf");
		assertThat(TipoDeContenidoClinico.detectar(new byte[] {
				(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00})).isEqualTo("image/png");
		assertThat(TipoDeContenidoClinico.detectar(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01}))
				.isEqualTo("image/jpeg");
		assertThat(TipoDeContenidoClinico.detectar("MZ ejecutable".getBytes())).isNull();
	}

	// =================================================================================
	// Apoyo
	// =================================================================================

	private static HistoriaClinica historia() {
		return new HistoriaClinica(1L, 2L, AHORA, 9L);
	}

	private static AntecedenteClinico antecedente() {
		return new AntecedenteClinico(1L, 2L, TipoAntecedente.ALERGIA, "Penicilina", AHORA, 9L);
	}

	private static AdjuntoClinico adjunto() {
		return new AdjuntoClinico(1L, 2L, null, 4L, CategoriaAdjuntoClinico.ESTUDIO, "RMN rodilla",
				"rmn.pdf", "application/pdf", 1024L, "a".repeat(64), "b".repeat(32), 9L, AHORA);
	}

	private static EntradaClinica entrada() {
		return new EntradaClinica(1L, 2L, TipoEntradaClinica.EVOLUCION, AHORA,
				OrigenEntradaClinica.MANUAL, null, AHORA, 9L);
	}
}
