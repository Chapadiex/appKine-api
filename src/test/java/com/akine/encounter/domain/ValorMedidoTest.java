package com.akine.encounter.domain;

import com.akine.encounter.domain.exception.MedicionTipoIncompatibleException;
import com.akine.resource.spi.MedicionTipo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El valor de una medicion tiene que ser el que su tipo admite, y ninguno mas (AKINE-06.03).
 *
 * <p>Las dos mitades importan: que falte deja una medicion que no mide nada, y que sobre otro es
 * un numero guardado como texto que despues nadie puede comparar.
 */
@DisplayName("ValorMedido")
class ValorMedidoTest {

	@Test
	@DisplayName("Cada tipo acepta su valor")
	void cada_tipo_acepta_el_suyo() {
		assertThatCode(() -> numerico().exigirCompatibleCon(MedicionTipo.ESCALA, "EVA"))
				.doesNotThrowAnyException();
		assertThatCode(() -> new ValorMedido(null, "positivo", null)
				.exigirCompatibleCon(MedicionTipo.TEXTO, "LASEGUE")).doesNotThrowAnyException();
		assertThatCode(() -> new ValorMedido(null, null, Boolean.TRUE)
				.exigirCompatibleCon(MedicionTipo.BOOLEANO, "PHALEN")).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("Ninguno o mas de uno se rechaza, aunque uno de ellos sea el correcto")
	void exactamente_uno() {
		assertThatThrownBy(() -> new ValorMedido(null, "   ", null)
				.exigirCompatibleCon(MedicionTipo.TEXTO, "LASEGUE"))
				.as("un texto en blanco es ausencia, no un valor")
				.isInstanceOf(MedicionTipoIncompatibleException.class);
		assertThatThrownBy(() -> new ValorMedido(BigDecimal.ONE, "uno", null)
				.exigirCompatibleCon(MedicionTipo.NUMERICO, "ROM"))
				.isInstanceOf(MedicionTipoIncompatibleException.class);
	}

	@Test
	@DisplayName("Un valor de otro tipo se rechaza: un numero no es un booleano")
	void el_valor_de_otro_tipo() {
		assertThatThrownBy(() -> numerico().exigirCompatibleCon(MedicionTipo.BOOLEANO, "PHALEN"))
				.isInstanceOf(MedicionTipoIncompatibleException.class);
		assertThatThrownBy(() -> new ValorMedido(null, "92", null)
				.exigirCompatibleCon(MedicionTipo.NUMERICO, "ROM"))
				.isInstanceOf(MedicionTipoIncompatibleException.class);
	}

	private static ValorMedido numerico() {
		return new ValorMedido(new BigDecimal("6"), null, null);
	}
}
