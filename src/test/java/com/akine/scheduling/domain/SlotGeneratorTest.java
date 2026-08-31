package com.akine.scheduling.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Las reglas del corte de franjas en slots.
 *
 * <p>Se prueban <b>reglas</b>, no el lenguaje: no hay casos de "lanza si el argumento es nulo" ni
 * de aritmetica que el JDK ya garantiza. Los cuatro que estan son los que producen bugs reales y
 * silenciosos — el resto, el borde de medianoche, el resto que no completa un slot, y el anclaje
 * de la grilla.
 */
@DisplayName("SlotGenerator")
class SlotGeneratorTest {

	private static TramoLocal tramo(String desde, String hasta) {
		return new TramoLocal(LocalTime.parse(desde), LocalTime.parse(hasta));
	}

	private static List<String> inicios(List<TramoLocal> slots) {
		return slots.stream().map(slot -> slot.desde().toString()).toList();
	}

	@Nested
	@DisplayName("Anclaje de la grilla")
	class Anclaje {

		@Test
		@DisplayName("La grilla arranca en el inicio de la franja, no en una hora redonda")
		void ancla_en_la_franja() {
			// El caso que decidio el diseno: una apertura excepcional a las 09:20. Con una grilla
			// global anclada a la medianoche, esta franja perderia sus primeros 25 minutos para
			// alinearse a las 09:45, y el administrador que cargo la apertura no tendria forma de
			// entender por que el primer turno no es a la hora que el escribio.
			assertThat(inicios(SlotGenerator.cortar(tramo("09:20", "11:00"), Duration.ofMinutes(45))))
					.containsExactly("09:20", "10:05");
		}

		@Test
		@DisplayName("Dos franjas del mismo dia no comparten grilla, y es correcto")
		void dos_franjas_no_se_alinean_entre_si() {
			// Consecuencia aceptada del anclaje por franja: manana y tarde son dos tramos de
			// atencion distintos y ninguna regla los obliga a caer en la misma grilla.
			var manana = SlotGenerator.cortar(tramo("09:00", "10:30"), Duration.ofMinutes(45));
			var tarde = SlotGenerator.cortar(tramo("14:10", "15:40"), Duration.ofMinutes(45));

			assertThat(inicios(manana)).containsExactly("09:00", "09:45");
			assertThat(inicios(tarde)).containsExactly("14:10", "14:55");
		}
	}

	@Nested
	@DisplayName("El resto de la franja")
	class Resto {

		@Test
		@DisplayName("Lo que no completa un slot se descarta: media consulta no es reservable")
		void el_resto_se_descarta() {
			// 09:00-11:30 con 45 minutos: entran tres y sobran 15. El cuarto slot terminaria
			// 11:45, fuera de la franja.
			assertThat(inicios(SlotGenerator.cortar(tramo("09:00", "11:30"), Duration.ofMinutes(45))))
					.containsExactly("09:00", "09:45", "10:30");
		}

		@Test
		@DisplayName("Una franja mas corta que la oferta no produce slots, y no es un error")
		void franja_mas_corta_que_la_oferta() {
			// Un bloque de 30 minutos con una oferta de 45. Es la situacion que el motor traduce a
			// FRANJA_MAS_CORTA_QUE_LA_OFERTA: el dia queda vacio con una explicacion accionable
			// —ampliar el bloque— en vez de vacio sin motivo.
			assertThat(SlotGenerator.cortar(tramo("09:00", "09:30"), Duration.ofMinutes(45)))
					.isEmpty();
		}
	}

	@Nested
	@DisplayName("El fin de dia")
	class FinDeDia {

		@Test
		@DisplayName("Una franja que cierra a medianoche entrega su ultimo slot completo")
		void medianoche_no_pierde_el_ultimo_slot() {
			// FIN_DE_DIA es LocalTime.MAX: 23:59:59.999999999, un nanosegundo antes de la
			// medianoche real. Restarlo literal deja fuera el ultimo slot de toda franja que
			// cierre a las 24:00, y NADIE lo nota: la diferencia no se ve en ninguna pantalla,
			// que muestra los horarios redondeados.
			var slots = SlotGenerator.cortar(
					new TramoLocal(LocalTime.parse("23:00"), TramoLocal.FIN_DE_DIA),
					Duration.ofMinutes(30));

			assertThat(inicios(slots)).containsExactly("23:00", "23:30");
			assertThat(slots.get(1).hasta()).isEqualTo(TramoLocal.FIN_DE_DIA);
		}

		@Test
		@DisplayName("El cursor no da la vuelta al reloj cerca de la medianoche")
		void no_da_la_vuelta_al_reloj() {
			// LocalTime.plus da la vuelta en silencio: 23:50 + 30 min = 00:20 del mismo dia. Si el
			// generador calculara el fin antes de medir cuanto queda, produciria slots de la
			// madrugada dentro de la franja de la noche.
			assertThat(SlotGenerator.cortar(tramo("23:50", "23:59"), Duration.ofMinutes(30)))
					.isEmpty();
		}
	}

	@Test
	@DisplayName("Para la misma entrada devuelve siempre lo mismo: es el criterio de aceptacion")
	void es_determinista() {
		var franja = tramo("08:00", "12:00");
		var duracion = Duration.ofMinutes(20);

		assertThat(SlotGenerator.cortar(franja, duracion))
				.isEqualTo(SlotGenerator.cortar(franja, duracion));
	}

	@Test
	@DisplayName("Una duracion no positiva es un error de programacion, no un dia sin turnos")
	void duracion_no_positiva() {
		// Se distingue de "no hay slots" a proposito: una oferta con duracion cero es un dato
		// corrupto, y devolver lista vacia lo disfrazaria de agenda sin turnos.
		assertThatThrownBy(() -> SlotGenerator.cortar(tramo("09:00", "10:00"), Duration.ZERO))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
