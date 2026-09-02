package com.akine.contracting.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Los invariantes de {@link Financiador}, sin base de datos.
 *
 * <p>Lo que este test protege es la mitad estructural de la estabilidad de las referencias
 * historicas: que el codigo no se pueda cambiar por la edicion, y que la baja no borre nada.
 */
@DisplayName("Financiador")
class FinanciadorTest {

	private static Financiador nuevo() {
		return new Financiador(
				7L, "OSDE", "OSDE Binario", TipoFinanciador.PREPAGA,
				"30-71234567-8", "admin@osde.test", "011-4000-0000", null);
	}

	@Nested
	@DisplayName("Identidad")
	class Identidad {

		@Test
		@DisplayName("La edicion NO puede cambiar el codigo: no hay parametro para hacerlo")
		void el_codigo_es_inmutable() {
			// La garantia es estructural y no una promesa: updateDatos no recibe el codigo, y la
			// columna es updatable = false. Si alguien le agrega el parametro, este test se cae y
			// con el se cae la estabilidad de toda referencia historica a este financiador.
			Financiador financiador = nuevo();

			financiador.updateDatos("Otro nombre", null, null, null, null, null);

			assertThat(financiador.getCodigo()).isEqualTo("OSDE");
			assertThat(financiador.getNombre()).isEqualTo("Otro nombre");
		}

		@Test
		@DisplayName("Un financiador sin organizacion no existe")
		void exige_organizacion() {
			assertThatThrownBy(() -> new Financiador(
					null, "X", "X", TipoFinanciador.OTRO, null, null, null, null))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Nested
	@DisplayName("CUIT")
	class Cuit {

		@Test
		@DisplayName("Se normaliza a 11 digitos: los guiones y los puntos no cuentan")
		void se_normaliza() {
			// Es la leccion de 03.01 con el documento: 27888999 choco contra una ficha guardada
			// como 27.888.999. Sin normalizar, el unique toma por dos al mismo contribuyente.
			assertThat(nuevo().getCuit()).isEqualTo("30712345678");
			assertThat(Financiador.normalizarCuit("30.71234567.8")).isEqualTo("30712345678");
		}

		@ParameterizedTest
		@ValueSource(strings = {"123", "3071234567", "307123456789"})
		@DisplayName("Un valor que no queda en 11 digitos se rechaza, no se guarda como venga")
		void rechaza_lo_que_no_normaliza(String valor) {
			// Guardarlo tal cual lo rechazaria igual ck_financiador_cuit_normalizado, pero al
			// cerrar la transaccion: un 500 en vez del 400 que nombra el campo.
			assertThatThrownBy(() -> Financiador.normalizarCuit(valor))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("Vacio y null son lo mismo: sin CUIT es un estado real")
		void sin_cuit_es_valido() {
			assertThat(Financiador.normalizarCuit(null)).isNull();
			assertThat(Financiador.normalizarCuit("   ")).isNull();
		}
	}

	@Nested
	@DisplayName("Baja logica")
	class Baja {

		@Test
		@DisplayName("La baja exige motivo y deja los tres campos coherentes")
		void la_baja_exige_motivo() {
			Financiador financiador = nuevo();
			Instant ahora = Instant.now();

			assertThatThrownBy(() -> financiador.deactivate(ahora, "  "))
					.isInstanceOf(IllegalArgumentException.class);
			assertThat(financiador.isOperable()).isTrue();

			financiador.deactivate(ahora, "  El centro dejo de trabajar con este financiador  ");

			assertThat(financiador.isOperable()).isFalse();
			assertThat(financiador.isActive()).isFalse();
			assertThat(financiador.getDeletedAt()).isEqualTo(ahora);
			assertThat(financiador.getDeactivationReason())
					.isEqualTo("El centro dejo de trabajar con este financiador");
		}

		@Test
		@DisplayName("La baja NO borra el codigo ni el nombre: los historicos siguen resolviendo")
		void la_baja_no_borra_la_identidad() {
			Financiador financiador = nuevo();

			financiador.deactivate(Instant.now(), "motivo");

			assertThat(financiador.getCodigo()).isEqualTo("OSDE");
			assertThat(financiador.getNombre()).isEqualTo("OSDE Binario");
		}
	}

	@Test
	@DisplayName("La edicion parcial deja intacto lo que llega en null")
	void la_edicion_es_parcial() {
		Financiador financiador = nuevo();

		financiador.updateDatos(null, TipoFinanciador.OBRA_SOCIAL, null, null, null, "una nota");

		assertThat(financiador.getNombre()).isEqualTo("OSDE Binario");
		assertThat(financiador.getTipo()).isEqualTo(TipoFinanciador.OBRA_SOCIAL);
		assertThat(financiador.getCuit()).isEqualTo("30712345678");
		assertThat(financiador.getEmailContacto()).isEqualTo("admin@osde.test");
		assertThat(financiador.getObservaciones()).isEqualTo("una nota");
	}

	@Test
	@DisplayName("Cadena vacia borra un opcional: es la unica forma de distinguirlo de 'no tocar'")
	void la_cadena_vacia_borra() {
		Financiador financiador = nuevo();

		financiador.updateDatos(null, null, null, "", "", "");

		assertThat(financiador.getEmailContacto()).isNull();
		assertThat(financiador.getTelefonoContacto()).isNull();
		assertThat(financiador.getObservaciones()).isNull();
	}
}
