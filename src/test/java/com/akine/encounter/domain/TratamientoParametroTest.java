package com.akine.encounter.domain;

import com.akine.encounter.domain.exception.ParametroInvalidoException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El tipado de un parametro de tratamiento (AKINE-06.04).
 *
 * <p>La entidad valida en el constructor —un parametro mal tipado no debe existir ni en memoria— y
 * lo que construye tiene que entrar en {@code ck_tratamiento_parametro_valor} de V55: el valor en
 * la columna de su tipo y las otras dos en {@code NULL}. Lo que el dominio acepta y el motor
 * rechaza es un 409 de "dato existente" sobre un tratamiento que se pierde.
 */
@DisplayName("TratamientoParametro")
class TratamientoParametroTest {

	@Test
	@DisplayName("Un parametro sin clave no se puede nombrar en el error ni en la pantalla")
	void sin_clave_se_rechaza() {
		assertThatThrownBy(() -> crear(new ParametroAplicado(
				"  ", TipoDatoParametro.NUMERICO, BigDecimal.ONE, null, null, null)))
				.isInstanceOf(ParametroInvalidoException.class);
	}

	@Test
	@DisplayName("Un parametro lleva exactamente un valor: ni ninguno ni dos")
	void exactamente_un_valor() {
		assertThatThrownBy(() -> crear(new ParametroAplicado(
				"series", TipoDatoParametro.NUMERICO, null, null, null, null)))
				.isInstanceOf(ParametroInvalidoException.class);
		assertThatThrownBy(() -> crear(new ParametroAplicado(
				"series", TipoDatoParametro.NUMERICO, BigDecimal.TEN, "diez", null, null)))
				.isInstanceOf(ParametroInvalidoException.class);
	}

	@Test
	@DisplayName("El valor tiene que caer en la columna de su tipo: un numero como texto se rechaza")
	void el_valor_cae_en_su_columna() {
		// Es el "parametro legado sin tipo" del caso borde: un numero guardado como texto no se
		// promedia ni se compara contra la sesion anterior.
		assertThatThrownBy(() -> crear(new ParametroAplicado(
				"intensidad", TipoDatoParametro.NUMERICO, null, "12", null, null)))
				.isInstanceOf(ParametroInvalidoException.class);
		assertThatThrownBy(() -> crear(new ParametroAplicado(
				"con_calor", TipoDatoParametro.BOOLEANO, BigDecimal.ONE, null, null, null)))
				.isInstanceOf(ParametroInvalidoException.class);
	}

	@Test
	@DisplayName("Solo un parametro numerico lleva unidad: \"mA\" de un texto no significa nada")
	void unidad_solo_en_numerico() {
		assertThatThrownBy(() -> crear(new ParametroAplicado(
				"electrodo", TipoDatoParametro.TEXTO, null, "bipolar", null, "mA")))
				.isInstanceOf(ParametroInvalidoException.class);
	}

	@Test
	@DisplayName("Un texto en blanco al lado de un numero se guarda como NULL, no como cadena vacia")
	void texto_en_blanco_se_guarda_como_null() {
		// DEFECTO corregido en G-11. La validacion cuenta un texto en blanco como ausente —y bien:
		// un formulario manda "" en el campo que no usa— pero la entidad lo guardaba como "".
		// `ck_tratamiento_parametro_valor` exige `valor_texto IS NULL` en un NUMERICO, asi que el
		// INSERT moria contra el CHECK y el tratamiento entero se perdia con un 409 que culpa a un
		// "dato existente". Lo mismo con un BOOLEANO.
		TratamientoParametro numerico = crear(new ParametroAplicado(
				" intensidad ", TipoDatoParametro.NUMERICO, new BigDecimal("12.5"), "   ", null,
				" mA "));
		TratamientoParametro booleano = crear(new ParametroAplicado(
				"con_calor", TipoDatoParametro.BOOLEANO, null, "", Boolean.TRUE, null));

		assertThat(numerico.getValorTexto()).isNull();
		assertThat(numerico.getClave()).isEqualTo("intensidad");
		assertThat(numerico.getUnidad()).isEqualTo("mA");
		assertThat(booleano.getValorTexto()).isNull();
	}

	@Test
	@DisplayName("Un texto valido se guarda sin espacios de borde")
	void el_texto_se_normaliza() {
		TratamientoParametro texto = crear(new ParametroAplicado(
				"electrodo", TipoDatoParametro.TEXTO, null, "  bipolar ", null, "  "));

		assertThat(texto.getValorTexto()).isEqualTo("bipolar");
		assertThat(texto.getUnidad()).isNull();
	}

	private static TratamientoParametro crear(ParametroAplicado aplicado) {
		return new TratamientoParametro(1L, 900L, aplicado, 0, Instant.EPOCH);
	}
}
