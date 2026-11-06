package com.akine.organization.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Direccion fisica, ubicacion y opciones de la sede (V140). */
class ConsultorioDireccionTest {

	private static final Long ORG = 5L;

	@Test
	@DisplayName("la direccion formateada se arma con las partes que hay y es null sin ninguna")
	void direccion_formateada() {
		Consultorio sede = new Consultorio(ORG, "Sede Norte", "America/Argentina/Cordoba", null);
		assertThat(sede.direccionFormateada()).isNull();
		assertThat(sede.isUbicacionPendiente()).isFalse();

		boolean cambio = sede.updateDireccion(
				"Norte", "ar", "Cordoba", "Cordoba", "Av. Colon", "1234", "X5000", "Piso 2", null);

		assertThat(cambio).isTrue();
		assertThat(sede.getPais()).as("el pais se guarda en mayusculas").isEqualTo("AR");
		assertThat(sede.direccionFormateada())
				.as("sin el pais, que es Argentina, ni la provincia, que repite la ciudad")
				.isEqualTo("Av. Colon 1234, Piso 2, Cordoba, X5000");
		assertThat(sede.isUbicacionPendiente()).as("hay direccion y ninguna coordenada").isTrue();

		// PATCH: null deja, cadena vacia borra. Sin numero la calle va sola.
		sede.updateDireccion(null, null, null, null, null, "", null, "", null);
		assertThat(sede.direccionFormateada()).isEqualTo("Av. Colon, Cordoba, X5000");
		assertThat(sede.getAlias()).isEqualTo("Norte");
	}

	@Test
	@DisplayName("la ubicacion va con las dos coordenadas, se borra entera y los limites se controlan")
	void ubicacion() {
		Consultorio sede = new Consultorio(ORG, "Sede Norte", "America/Argentina/Cordoba", null);
		sede.updateDireccion(null, "AR", null, "Cordoba", "Av. Colon", "1234", null, null, null);

		boolean cambio = sede.fijarUbicacion(new BigDecimal("-31.420083"), new BigDecimal("-64.188776"), null);

		assertThat(cambio).isTrue();
		assertThat(sede.getUbicacionOrigen()).as("sin origen declarado es MANUAL").isEqualTo(UbicacionOrigen.MANUAL);
		assertThat(sede.isUbicacionPendiente()).isFalse();
		// Volver a fijar lo mismo no es un cambio.
		assertThat(sede.fijarUbicacion(new BigDecimal("-31.420083"), new BigDecimal("-64.188776"),
				UbicacionOrigen.MANUAL)).isFalse();

		assertThatThrownBy(() -> sede.fijarUbicacion(new BigDecimal("91"), BigDecimal.ZERO, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("latitud");
		assertThatThrownBy(() -> sede.fijarUbicacion(BigDecimal.ZERO, null, null))
				.isInstanceOf(IllegalArgumentException.class);
		// Un intento invalido no toca lo que habia.
		assertThat(sede.getLatitud()).isEqualByComparingTo("-31.420083");

		assertThat(sede.borrarUbicacion()).isTrue();
		assertThat(sede.getLatitud()).isNull();
		assertThat(sede.getLongitud()).isNull();
		assertThat(sede.getUbicacionOrigen()).isNull();
		assertThat(sede.isUbicacionPendiente()).isTrue();
		assertThat(sede.borrarUbicacion()).as("borrar lo que no hay no es un cambio").isFalse();
	}

	@Test
	@DisplayName("las opciones nacen con sus defaults, null las deja, y solo la agenda se reporta como cambio")
	void opciones() {
		Consultorio sede = new Consultorio(ORG, "Sede Norte", "America/Argentina/Cordoba", null);
		assertThat(sede.isAgendaHabilitada()).isTrue();
		assertThat(sede.isDireccionPublica()).isTrue();
		assertThat(sede.isDatosFiscalesPropios()).isFalse();

		assertThat(sede.updateOpciones(null, false, true)).isFalse();
		assertThat(sede.isAgendaHabilitada()).isTrue();
		assertThat(sede.isDireccionPublica()).isFalse();
		assertThat(sede.isDatosFiscalesPropios()).isTrue();

		assertThat(sede.updateOpciones(false, null, null)).isTrue();
		assertThat(sede.isAgendaHabilitada()).isFalse();
		assertThat(sede.isOperable()).as("sin agenda sigue siendo una sede ACTIVA").isTrue();
		assertThat(sede.updateOpciones(false, null, null)).as("mismo valor, sin cambio").isFalse();
	}
}
