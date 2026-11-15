package com.akine.resource.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.akine.resource.spi.MedicionTipo;
import com.akine.resource.spi.SentidoDeMejora;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** RF-M31-003: el sentido de mejora solo vale para medidas numericas. */
class MedicionDefinicionSentidoDeMejoraTest {

	@Test
	void unaMedidaNumericaAceptaYQuitaElSentido() {
		MedicionDefinicion rom = new MedicionDefinicion(7L, "ROM_RODILLA", "ROM de rodilla", null,
				MedicionTipo.NUMERICO, "grados", BigDecimal.ZERO, new BigDecimal("160"));

		rom.declararSentidoDeMejora(SentidoDeMejora.SUBE);
		assertThat(rom.getMejoraCuando()).isEqualTo(SentidoDeMejora.SUBE);

		rom.declararSentidoDeMejora(null);
		assertThat(rom.getMejoraCuando()).isNull();
	}

	@Test
	void unaMedidaDeTextoRechazaElSentido() {
		MedicionDefinicion texto = new MedicionDefinicion(7L, "MARCHA", "Marcha", null,
				MedicionTipo.TEXTO, null, null, null);

		assertThatThrownBy(() -> texto.declararSentidoDeMejora(SentidoDeMejora.BAJA))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(texto.getMejoraCuando()).isNull();
	}
}
