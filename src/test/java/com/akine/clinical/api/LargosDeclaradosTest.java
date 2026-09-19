package com.akine.clinical.api;

import com.akine.clinical.api.dto.EnmendarEntradaClinicaRequest;
import com.akine.clinical.api.dto.ReclasificarAdjuntoClinicoRequest;
import com.akine.clinical.api.dto.RegistrarEntradaClinicaRequest;
import com.akine.clinical.domain.AdjuntoClinico;
import com.akine.clinical.domain.CategoriaAdjuntoClinico;
import com.akine.clinical.domain.EntradaClinicaVersion;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Que el tope que la API declara sea el que la columna admite, y que el dominio lo haga cumplir.
 *
 * <p>El defecto que esto impide volver a cometer: {@code cuerpo} validaba 20000 sobre un
 * {@code VARCHAR(8000)} y {@code titulo} 200 sobre un {@code VARCHAR(160)}. Un tope mas alto que
 * la columna no rechaza nada, solo cambia donde explota: el {@code INSERT} muere con data
 * truncation, {@code GlobalExceptionHandler} mapea {@code DataIntegrityViolationException} a
 * <b>409 "choca con un dato ya existente"</b> —un mensaje sin sentido para lo que es un 400— y el
 * texto clinico recien escrito se pierde. En la subida de adjuntos era peor: el titulo viaja como
 * {@code @RequestParam} de un multipart, sin tope ninguno, y el rollback ocurre DESPUES de que el
 * binario ya se escribio.
 *
 * <p>Se compara contra las constantes del dominio y no contra numeros sueltos a proposito: son
 * las mismas que documentan el largo de la columna, asi que el dia que una migracion ensanche el
 * campo hay <b>un</b> lugar que cambiar y este test lo sigue.
 */
@DisplayName("Los topes de largo de lo clinico")
class LargosDeclaradosTest {

	@Test
	@DisplayName("el cuerpo de la entrada clinica no declara mas de lo que entra en V45")
	void el_cuerpo_respeta_la_columna() {
		assertThat(topeDe(RegistrarEntradaClinicaRequest.class, "cuerpo"))
				.isEqualTo(EntradaClinicaVersion.CUERPO_MAXIMO);
		assertThat(topeDe(EnmendarEntradaClinicaRequest.class, "cuerpo"))
				.isEqualTo(EntradaClinicaVersion.CUERPO_MAXIMO);
	}

	@Test
	@DisplayName("el titulo del adjunto no declara mas de lo que entra en V46")
	void el_titulo_respeta_la_columna() {
		assertThat(topeDe(ReclasificarAdjuntoClinicoRequest.class, "titulo"))
				.isEqualTo(AdjuntoClinico.TITULO_MAXIMO);
	}

	@Test
	@DisplayName("el dominio tambien lo hace cumplir: la validacion no vive en una sola puerta")
	void el_dominio_rechaza_lo_que_no_entra() {
		String cuerpoLargo = "x".repeat(EntradaClinicaVersion.CUERPO_MAXIMO + 1);
		assertThatThrownBy(() -> new EntradaClinicaVersion(
				1L, 2L, 1, cuerpoLargo, null, Instant.now(), 3L))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining(String.valueOf(EntradaClinicaVersion.CUERPO_MAXIMO));

		String tituloLargo = "x".repeat(AdjuntoClinico.TITULO_MAXIMO + 1);
		assertThatThrownBy(() -> adjuntoCon(tituloLargo))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining(String.valueOf(AdjuntoClinico.TITULO_MAXIMO));

		// Y reclasificar tampoco lo deja entrar por la otra puerta.
		AdjuntoClinico adjunto = adjuntoCon("Resonancia lumbar");
		assertThatThrownBy(() -> adjunto.reclasificar(null, tituloLargo))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private static AdjuntoClinico adjuntoCon(String titulo) {
		return new AdjuntoClinico(1L, 2L, null, 3L, CategoriaAdjuntoClinico.ESTUDIO, titulo,
				"estudio.pdf", "application/pdf", 10L, "abc123",
				"0123456789abcdef0123456789abcdef", 4L, Instant.now());
	}

	/**
	 * El {@code max} declarado, leido del campo del record.
	 *
	 * <p>Se lee del campo y no del {@code RecordComponent}: {@code @Size} no declara
	 * {@code RECORD_COMPONENT} entre sus targets, asi que javac la propaga al campo y al accesor
	 * pero no la deja en el componente.
	 */
	private static int topeDe(Class<? extends Record> request, String componente) {
		try {
			Size size = request.getDeclaredField(componente).getAnnotation(Size.class);
			assertThat(size)
					.as("%s.%s tiene que declarar un tope: sin el, el limite lo pone la columna y "
							+ "el error llega como 409", request.getSimpleName(), componente)
					.isNotNull();
			return size.max();
		}
		catch (NoSuchFieldException ausente) {
			throw new AssertionError(
					"El record " + request.getSimpleName() + " no tiene " + componente, ausente);
		}
	}
}
