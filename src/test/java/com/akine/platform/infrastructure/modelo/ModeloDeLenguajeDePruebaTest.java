package com.akine.platform.infrastructure.modelo;

import com.akine.platform.spi.modelo.MensajeChat;
import com.akine.platform.spi.modelo.MensajeChat.Rol;
import com.akine.platform.spi.modelo.OpcionesGeneracion;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModeloDeLenguajeDePruebaTest {

	@Test
	void generar_emite_tres_fragmentos_y_cuenta_la_llamada() {
		ModeloDeLenguajeDePrueba modelo = new ModeloDeLenguajeDePrueba();
		List<String> fragmentos = new ArrayList<>();

		modelo.generar(List.of(new MensajeChat(Rol.SISTEMA, "reglas"),
						new MensajeChat(Rol.USUARIO, "primer renglon\nsegundo")),
				new OpcionesGeneracion(new java.math.BigDecimal("0.2"), 400), Duration.ofSeconds(5), fragmentos::add, () -> false);

		assertThat(fragmentos).containsExactly("Respuesta de prueba: ", "primer renglon", ".");
		assertThat(modelo.llamadas()).isEqualTo(1);
	}
}
