package com.akine.person.infrastructure;

import com.akine.person.application.ElegibilidadAdministrativa;
import com.akine.person.application.ElegibilidadAdministrativaService;
import com.akine.person.application.RequisitoAdministrativo;
import com.akine.person.application.TipoRequisito;
import com.akine.person.spi.VeredictoDeElegibilidad;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/** El borde {@code spi} de la elegibilidad de M17, primer consumidor: la recepcion (AKINE E-4). */
class PersonElegibilidadAdministrativaDirectoryTest {

	private static final LocalDate HOY = LocalDate.of(2027, 3, 8);

	private final ElegibilidadAdministrativaService servicio = mock(ElegibilidadAdministrativaService.class);
	private final PersonElegibilidadAdministrativaDirectory directory =
			new PersonElegibilidadAdministrativaDirectory(servicio);

	@Test
	@DisplayName("lleva el veredicto, el convenio y solo los requisitos FALTANTES, con el tipo adelante")
	void solo_los_faltantes() {
		given(servicio.evaluar(1L, 7L, 128L, 412L, 33L, HOY)).willReturn(Optional.of(
				ElegibilidadAdministrativa.evaluada(HOY, List.of(
						RequisitoAdministrativo.cumplido(TipoRequisito.CREDENCIAL, 412L, "Credencial vigente.", null),
						RequisitoAdministrativo.faltante(TipoRequisito.ORDEN, "Falta la orden.")),
						9L, "Convenio", null)));

		VeredictoDeElegibilidad veredicto = directory.evaluar(1L, 7L, 128L, 412L, 33L, HOY);

		assertThat(veredicto.elegible()).isFalse();
		assertThat(veredicto.convenioId()).isEqualTo(9L);
		assertThat(veredicto.faltantes()).containsExactly("ORDEN: Falta la orden.");
	}

	@Test
	@DisplayName("sin requisitos que evaluar es elegible con el motivo")
	void sin_requisitos() {
		given(servicio.evaluar(1L, 7L, 128L, 412L, 33L, HOY)).willReturn(Optional.of(
				ElegibilidadAdministrativa.sinRequisitos(HOY, "SIN_CONVENIO_VIGENTE")));

		VeredictoDeElegibilidad veredicto = directory.evaluar(1L, 7L, 128L, 412L, 33L, HOY);

		assertThat(veredicto.elegible()).isTrue();
		assertThat(veredicto.motivo()).isEqualTo("SIN_CONVENIO_VIGENTE");
		assertThat(veredicto.faltantes()).isEmpty();
	}

	@Test
	@DisplayName("una persona o cobertura de otra organizacion es no elegible con motivo, nunca una excepcion")
	void no_accesible() {
		given(servicio.evaluar(1L, 7L, 128L, 412L, 33L, HOY)).willReturn(Optional.empty());

		VeredictoDeElegibilidad veredicto = directory.evaluar(1L, 7L, 128L, 412L, 33L, HOY);

		assertThat(veredicto.elegible()).isFalse();
		assertThat(veredicto.motivo()).isEqualTo(PersonElegibilidadAdministrativaDirectory.NO_ACCESIBLE);
		assertThat(veredicto.faltantes()).hasSize(1);
	}
}
