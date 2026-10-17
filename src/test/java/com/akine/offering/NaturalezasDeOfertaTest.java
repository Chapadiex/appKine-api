package com.akine.offering;

import com.akine.offering.domain.Naturaleza;
import com.akine.offering.spi.ClasificacionDeOferta;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ClasificacionDeOferta#NATURALEZAS} es la copia en texto de {@link Naturaleza} que el
 * {@code spi} publica para que nadie importe el dominio de {@code offering}. Si alguien agrega una
 * naturaleza y no la copia, el reporte de continuidad (RF-M23-008) la dejaria sin indicador en
 * silencio: este test lo hace ruidoso.
 */
class NaturalezasDeOfertaTest {

	@Test
	void la_lista_del_spi_es_la_del_dominio_en_el_mismo_orden() {
		assertThat(ClasificacionDeOferta.NATURALEZAS)
				.containsExactlyElementsOf(Arrays.stream(Naturaleza.values()).map(Enum::name).toList());
	}
}
