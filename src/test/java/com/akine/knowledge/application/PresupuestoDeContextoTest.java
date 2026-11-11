package com.akine.knowledge.application;

import com.akine.knowledge.domain.port.MensajeChat;
import com.akine.knowledge.domain.port.MensajeChat.Rol;
import com.akine.knowledge.spi.PasajeRecuperado;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PresupuestoDeContexto")
class PresupuestoDeContextoTest {

	private static PasajeRecuperado pasaje(long id, int largo, double puntaje) {
		return new PasajeRecuperado(id, "Libro " + id, 1, 2, "x".repeat(largo), puntaje, true);
	}

	@Test
	void systemNuncaSeRecorta() {
		String system = "s".repeat(8000);
		List<MensajeChat> mensajes = PresupuestoDeContexto.armar(system,
				List.of(pasaje(1, 3000, 0.9), pasaje(2, 3000, 0.8)), List.of(), "pregunta?").mensajes();

		assertThat(mensajes.get(0)).isEqualTo(new MensajeChat(Rol.SISTEMA, system));
		assertThat(mensajes.get(mensajes.size() - 1).texto()).isEqualTo("pregunta?");
	}

	@Test
	void recortaPrimeroHistorialViejo() {
		List<TurnoDeConsulta> historial = List.of(
				new TurnoDeConsulta(TurnoDeConsulta.Rol.USUARIO, "viejo" + "a".repeat(2000)),
				new TurnoDeConsulta(TurnoDeConsulta.Rol.ASISTENTE, "nuevo" + "b".repeat(1000)));
		List<MensajeChat> mensajes = PresupuestoDeContexto.armar("sistema",
				List.of(pasaje(1, 3000, 0.9), pasaje(2, 3000, 0.8)), historial, "y ahora?").mensajes();

		assertThat(mensajes).noneMatch(m -> m.texto().startsWith("viejo"));
		assertThat(mensajes).anyMatch(m -> m.texto().contains("Libro 1"));
		assertThat(mensajes).anyMatch(m -> m.texto().contains("Libro 2"));
	}

	@Test
	void sinHistorialSaleElPasajeDeMenorPuntaje() {
		PresupuestoDeContexto.Armado armado = PresupuestoDeContexto.armar("sistema",
				List.of(pasaje(1, 3000, 0.5), pasaje(2, 3000, 0.9), pasaje(3, 3000, 0.7)), List.of(), "pregunta?");

		assertThat(armado.pasajes()).extracting(PasajeRecuperado::libroId).containsExactly(2L, 3L);
		assertThat(armado.mensajes()).noneMatch(m -> m.texto().contains("Libro 1"));
	}

	@Test
	void systemYPreguntaQueNoEntranNoLanza() {
		String system = "s".repeat(PresupuestoDeContexto.PRESUPUESTO_CARACTERES);
		PresupuestoDeContexto.Armado armado = PresupuestoDeContexto.armar(system,
				List.of(pasaje(1, 100, 0.9)), List.of(), "pregunta?");

		assertThat(armado.pasajes()).isEmpty();
		assertThat(armado.mensajes()).containsExactly(
				new MensajeChat(Rol.SISTEMA, system), new MensajeChat(Rol.USUARIO, "pregunta?"));
	}
}
