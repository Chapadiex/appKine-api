package com.akine.encounter.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.akine.encounter.domain.evolucion.ConfiguracionDeEvolucion;
import com.akine.encounter.domain.evolucion.UmbralesDeEvolucion;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

/** RN-M31-004 y RN-M31-010: valores por defecto del diseno, interruptor apagado y umbrales validos. */
class EvolucionPropertiesTest {

	@Test
	void sinConfiguracionQuedaApagadaYConLosUmbralesDelDiseno() {
		ConfiguracionDeEvolucion configuracion = enlazar(Map.of()).aConfiguracion();

		assertThat(configuracion.habilitada()).isFalse();
		assertThat(configuracion.umbrales()).isEqualTo(UmbralesDeEvolucion.porDefecto());
	}

	@Test
	void seEnciendeYSeCambiaUnUmbralPorPropiedad() {
		ConfiguracionDeEvolucion configuracion = enlazar(Map.of(
				"akine.evolucion.enabled", "true",
				"akine.evolucion.dolor-ventana", "5")).aConfiguracion();

		assertThat(configuracion.habilitada()).isTrue();
		assertThat(configuracion.umbrales().dolorVentana()).isEqualTo(5);
	}

	@Test
	void unaVentanaEnCeroNoArranca() {
		EvolucionProperties enCero = enlazar(Map.of("akine.evolucion.dolor-ventana", "0"));

		assertThatThrownBy(enCero::aConfiguracion).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void unUmbralNegativoNoArranca() {
		EvolucionProperties negativo = enlazar(Map.of("akine.evolucion.dolor-piso", "-1"));

		assertThatThrownBy(negativo::aConfiguracion).isInstanceOf(IllegalStateException.class);
	}

	private static EvolucionProperties enlazar(Map<String, String> propiedades) {
		return new Binder(new MapConfigurationPropertySource(propiedades))
				.bindOrCreate("akine.evolucion", EvolucionProperties.class);
	}
}
