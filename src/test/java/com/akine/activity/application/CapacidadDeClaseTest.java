package com.akine.activity.application;

import com.akine.activity.domain.ClaseProgramada;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.resource.spi.EspacioDirectory;
import com.akine.resource.spi.EspacioSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * La capacidad efectiva es el minimo entre la clase, la oferta y el box (RF-M12-010). Es el tope
 * contra el que la base otorga cada lugar, asi que un error aca es sobreventa o lugares que nunca
 * se ofrecen.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CapacidadDeClase")
class CapacidadDeClaseTest {

	private static final long ORG_ID = 1L;
	private static final long SEDE_ID = 10L;
	private static final long OFERTA_ID = 45L;
	private static final long ESPACIO_ID = 8L;
	private static final Instant INICIO = Instant.now().plus(3, ChronoUnit.DAYS);

	@Mock private OfertaDirectory ofertas;
	@Mock private EspacioDirectory espacios;

	@Test
	@DisplayName("Gana el menor de los tres: clase, oferta y box")
	void gana_el_minimo() {
		CapacidadDeClase capacidad = new CapacidadDeClase(ofertas, espacios);
		given(ofertas.find(ORG_ID, SEDE_ID, OFERTA_ID)).willReturn(Optional.of(oferta(10)));
		given(espacios.find(ORG_ID, ESPACIO_ID, INICIO)).willReturn(Optional.of(espacio(6)));

		assertThat(capacidad.efectiva(ORG_ID, SEDE_ID, clase(ESPACIO_ID, 8))).isEqualTo(6);
	}

	@Test
	@DisplayName("Sin box asignado no se consulta ningun espacio")
	void sin_espacio() {
		CapacidadDeClase capacidad = new CapacidadDeClase(ofertas, espacios);

		assertThat(capacidad.efectiva(ORG_ID, oferta(5), null, INICIO, 8)).isEqualTo(5);
		verifyNoInteractions(espacios);
	}

	/**
	 * Un box que ya no existe en ese instante, o una oferta dada de baja, no bajan la capacidad a
	 * cero: la clase conserva la que se le dio al programarla. Bajarla a cero dejaria afuera a
	 * gente que ya tiene su lugar pago.
	 */
	@Test
	@DisplayName("Sin oferta ni box resolubles queda la capacidad propia de la clase")
	void sin_oferta_ni_espacio() {
		CapacidadDeClase capacidad = new CapacidadDeClase(ofertas, espacios);
		given(ofertas.find(anyLong(), anyLong(), anyLong())).willReturn(Optional.empty());
		assertThat(capacidad.efectiva(ORG_ID, SEDE_ID, clase(ESPACIO_ID, 8))).isEqualTo(8);

		given(espacios.find(anyLong(), anyLong(), any())).willReturn(Optional.empty());
		assertThat(capacidad.efectiva(ORG_ID, oferta(12), ESPACIO_ID, INICIO, 8)).isEqualTo(8);
	}

	private static ClaseProgramada clase(Long espacioId, int capacidad) {
		return new ClaseProgramada(ORG_ID, SEDE_ID, OFERTA_ID, 31L, espacioId, "Pilates",
				INICIO, INICIO.plus(1, ChronoUnit.HOURS), capacidad, 99L, Instant.now(), null,
				null);
	}

	private static OfertaSnapshot oferta(int capacidad) {
		return new OfertaSnapshot(OFERTA_ID, ORG_ID, SEDE_ID, 12L, "Pilates grupal", 60,
				capacidad, true, true, true, false, false, LocalDate.now().minusYears(1), null,
				true);
	}

	private static EspacioSnapshot espacio(int capacidad) {
		return new EspacioSnapshot(ESPACIO_ID, ORG_ID, SEDE_ID, "Box 1", "SALON", capacidad,
				Instant.now().minus(365, ChronoUnit.DAYS), null, true, true);
	}
}
