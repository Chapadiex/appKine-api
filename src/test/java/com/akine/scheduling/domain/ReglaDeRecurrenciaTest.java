package com.akine.scheduling.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ReglaDeRecurrencia")
class ReglaDeRecurrenciaTest {

	private static final LocalTime NUEVE = LocalTime.of(9, 0);
	/** Un miercoles: la primera ocurrencia de lunes y jueves cae el jueves siguiente. */
	private static final LocalDate MIERCOLES = LocalDate.of(2027, 3, 3);

	@Test
	@DisplayName("lunes y jueves, cuatro turnos: arranca por el primer dia que cae despues del desde")
	void por_cantidad() {
		ReglaDeRecurrencia regla = new ReglaDeRecurrencia(
				Set.of(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), NUEVE, MIERCOLES, null, 4);

		assertThat(regla.ocurrencias()).containsExactly(
				LocalDateTime.of(2027, 3, 4, 9, 0),
				LocalDateTime.of(2027, 3, 8, 9, 0),
				LocalDateTime.of(2027, 3, 11, 9, 0),
				LocalDateTime.of(2027, 3, 15, 9, 0));
		assertThat(regla.diasComoTexto()).isEqualTo("1,4");
	}

	@Test
	@DisplayName("por fecha fin, inclusiva")
	void por_fecha_fin() {
		ReglaDeRecurrencia regla = new ReglaDeRecurrencia(
				Set.of(DayOfWeek.MONDAY), NUEVE, MIERCOLES, LocalDate.of(2027, 3, 22), null);

		assertThat(regla.ocurrencias()).hasSize(3).last()
				.isEqualTo(LocalDateTime.of(2027, 3, 22, 9, 0));
	}

	@Test
	@DisplayName("cantidad y fecha fin juntas, o ninguna, es invalido")
	void exactamente_un_fin() {
		assertThatThrownBy(() -> new ReglaDeRecurrencia(
				Set.of(DayOfWeek.MONDAY), NUEVE, MIERCOLES, MIERCOLES.plusDays(30), 3))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ReglaDeRecurrencia(
				Set.of(DayOfWeek.MONDAY), NUEVE, MIERCOLES, null, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("una fecha fin que produce mas de 52 turnos se rechaza en vez de reservar de mas")
	void tope() {
		ReglaDeRecurrencia regla = new ReglaDeRecurrencia(
				Set.of(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), NUEVE, MIERCOLES, MIERCOLES.plusYears(1), null);

		assertThatThrownBy(regla::ocurrencias)
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("52");
	}

	@Test
	@DisplayName("un rango sin ninguno de los dias pedidos no produce una serie vacia")
	void sin_ocurrencias() {
		ReglaDeRecurrencia regla = new ReglaDeRecurrencia(
				Set.of(DayOfWeek.MONDAY), NUEVE, MIERCOLES, MIERCOLES.plusDays(2), null);

		assertThatThrownBy(regla::ocurrencias).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("los dias viajan como texto y vuelven iguales")
	void ida_y_vuelta_de_los_dias() {
		assertThat(ReglaDeRecurrencia.diasDesdeTexto("1,4"))
				.containsExactlyInAnyOrder(DayOfWeek.MONDAY, DayOfWeek.THURSDAY);
	}
}
