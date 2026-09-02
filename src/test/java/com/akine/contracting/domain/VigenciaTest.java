package com.akine.contracting.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La definicion de "se solapan" de M16, que es la regla que define AKINE-03.05 (RN-M16-002).
 *
 * <p>Este test no prueba concurrencia: prueba la comparacion. Que la regla resista dos escrituras
 * simultaneas lo prueba {@code ConvenioConcurrenteIT}, y hace falta MySQL real para eso.
 *
 * <p>Los casos que importan son los bordes —periodos que se tocan por un dia, y los finales
 * abiertos—, porque son los que una implementacion descuidada deja pasar.
 */
@DisplayName("Vigencia")
class VigenciaTest {

	private static final LocalDate ENERO = LocalDate.of(2026, 1, 1);
	private static final LocalDate JUNIO_30 = LocalDate.of(2026, 6, 30);
	private static final LocalDate JULIO = LocalDate.of(2026, 7, 1);
	private static final LocalDate DICIEMBRE = LocalDate.of(2026, 12, 31);

	@Nested
	@DisplayName("Construccion")
	class Construccion {

		@Test
		@DisplayName("un periodo que termina antes de empezar no existe")
		void invertida_se_rechaza() {
			assertThatThrownBy(() -> new Vigencia(JULIO, ENERO))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("no puede terminar antes de empezar");
		}

		@Test
		@DisplayName("un periodo de UN SOLO DIA es un estado real")
		void un_solo_dia_se_admite() {
			Vigencia unDia = new Vigencia(ENERO, ENERO);

			assertThat(unDia.cubre(ENERO)).isTrue();
			assertThat(unDia.cubre(ENERO.plusDays(1))).isFalse();
		}

		@Test
		@DisplayName("sin fecha de inicio no hay periodo")
		void sin_desde_se_rechaza() {
			assertThatThrownBy(() -> new Vigencia(null, ENERO))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Nested
	@DisplayName("Solapamiento")
	class Solapamiento {

		@Test
		@DisplayName("dos periodos consecutivos NO se solapan: hasta es inclusiva y el siguiente "
				+ "empieza al dia siguiente")
		void consecutivos_no_se_solapan() {
			// Es el caso que un centro carga todos los años al renovar, y el que una implementacion
			// con hasta EXCLUSIVA rechazaria por error.
			Vigencia primerSemestre = new Vigencia(ENERO, JUNIO_30);
			Vigencia segundoSemestre = new Vigencia(JULIO, DICIEMBRE);

			assertThat(primerSemestre.seSolapaCon(segundoSemestre)).isFalse();
			assertThat(segundoSemestre.seSolapaCon(primerSemestre)).isFalse();
		}

		@Test
		@DisplayName("compartir UN SOLO dia ya es solaparse")
		void un_dia_compartido_alcanza() {
			Vigencia hastaJulio = new Vigencia(ENERO, JULIO);
			Vigencia desdeJulio = new Vigencia(JULIO, DICIEMBRE);

			assertThat(hastaJulio.seSolapaCon(desdeJulio)).isTrue();
			assertThat(desdeJulio.seSolapaCon(hastaJulio)).isTrue();
		}

		@Test
		@DisplayName("el caso del enunciado: 01/01-30/06 y 01/03-31/12 se pisan, y ningun unique "
				+ "podria detectarlo")
		void el_caso_que_ningun_unique_detecta() {
			// No comparten NINGUN valor de columna: ni desde, ni hasta. Un UNIQUE compara igualdad
			// y esto es una interseccion de intervalos. MySQL 8.4 no tiene exclusion constraints.
			Vigencia a = new Vigencia(ENERO, JUNIO_30);
			Vigencia b = new Vigencia(LocalDate.of(2026, 3, 1), DICIEMBRE);

			assertThat(a.desde()).isNotEqualTo(b.desde());
			assertThat(a.hasta()).isNotEqualTo(b.hasta());
			assertThat(a.seSolapaCon(b)).isTrue();
		}

		@Test
		@DisplayName("dos periodos ABIERTOS siempre se solapan, aunque empiecen con años de "
				+ "diferencia")
		void dos_abiertos_siempre_chocan() {
			// El null es +infinito, no "no se sabe". Es el caso que una comparacion escrita sin
			// tratar el null dejaria pasar, y el que mas duele: dos convenios abiertos y
			// superpuestos para siempre.
			Vigencia desde2026 = new Vigencia(ENERO, null);
			Vigencia desde2030 = new Vigencia(LocalDate.of(2030, 1, 1), null);

			assertThat(desde2026.seSolapaCon(desde2030)).isTrue();
			assertThat(desde2030.seSolapaCon(desde2026)).isTrue();
		}

		@Test
		@DisplayName("un periodo cerrado ANTERIOR a uno abierto no lo toca")
		void cerrado_anterior_a_uno_abierto() {
			assertThat(new Vigencia(ENERO, JUNIO_30).seSolapaCon(new Vigencia(JULIO, null)))
					.isFalse();
		}

		@Test
		@DisplayName("uno contenido dentro de otro se solapa")
		void contenido_se_solapa() {
			assertThat(new Vigencia(ENERO, DICIEMBRE)
					.seSolapaCon(new Vigencia(JUNIO_30, JULIO)))
					.isTrue();
		}
	}

	@Nested
	@DisplayName("Cobertura y contencion")
	class CoberturaYContencion {

		@Test
		@DisplayName("cubre evalua dia por dia, con hasta INCLUSIVA")
		void cubre_es_inclusiva() {
			Vigencia semestre = new Vigencia(ENERO, JUNIO_30);

			assertThat(semestre.cubre(ENERO)).isTrue();
			assertThat(semestre.cubre(JUNIO_30)).isTrue();
			assertThat(semestre.cubre(JULIO)).isFalse();
			assertThat(semestre.cubre(ENERO.minusDays(1))).isFalse();
			assertThat(semestre.cubre(null)).isFalse();
		}

		@Test
		@DisplayName("un periodo abierto cubre cualquier fecha posterior a su inicio")
		void abierto_cubre_el_futuro() {
			assertThat(new Vigencia(ENERO, null).cubre(LocalDate.of(2099, 12, 31))).isTrue();
		}

		@Test
		@DisplayName("estaContenidaEn: un arancel no puede sobresalir de su convenio")
		void contencion() {
			Vigencia convenio = new Vigencia(ENERO, DICIEMBRE);

			assertThat(new Vigencia(ENERO, JUNIO_30).estaContenidaEn(convenio)).isTrue();
			assertThat(new Vigencia(ENERO, DICIEMBRE).estaContenidaEn(convenio)).isTrue();
			assertThat(new Vigencia(ENERO.minusDays(1), JUNIO_30).estaContenidaEn(convenio))
					.as("empezar antes que el convenio")
					.isFalse();
			assertThat(new Vigencia(ENERO, null).estaContenidaEn(convenio))
					.as("un arancel abierto dentro de un convenio que termina")
					.isFalse();
		}

		@Test
		@DisplayName("dentro de un convenio ABIERTO cabe cualquier arancel")
		void contencion_en_convenio_abierto() {
			Vigencia convenioAbierto = new Vigencia(ENERO, null);

			assertThat(new Vigencia(JULIO, null).estaContenidaEn(convenioAbierto)).isTrue();
			assertThat(new Vigencia(JULIO, DICIEMBRE).estaContenidaEn(convenioAbierto)).isTrue();
		}

		@Test
		@DisplayName("toString explica el fin abierto en vez de escribir null")
		void to_string_legible() {
			// Es lo que viaja dentro del 409 de solapamiento, asi que lo lee una persona.
			assertThat(new Vigencia(ENERO, null)).hasToString("2026-01-01 a sin fin previsto");
			assertThat(new Vigencia(ENERO, JUNIO_30)).hasToString("2026-01-01 a 2026-06-30");
		}
	}
}
