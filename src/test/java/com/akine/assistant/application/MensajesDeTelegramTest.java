package com.akine.assistant.application;

import com.akine.assistant.spi.Tarjeta;
import com.akine.assistant.spi.Tarjeta.OpcionDeTarjeta;
import com.akine.assistant.spi.Tarjeta.TarjetaDeAtencion;
import com.akine.assistant.spi.Tarjeta.TratamientoEnTarjeta;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Lo que decide si una propuesta del asistente se confirma sola desde Telegram. */
class MensajesDeTelegramTest {

	private static TarjetaDeAtencion atencion(Long turnoId, List<OpcionDeTarjeta> turnos, Long ofertaId,
			Long casoId, List<OpcionDeTarjeta> casos, List<TratamientoEnTarjeta> tratamientos) {
		return new TarjetaDeAtencion(7L, "Juan Strudel", turnoId, turnos, ofertaId, List.of(), casoId, casos,
				tratamientos, "Juan hizo movilidad de hombro", true, 0);
	}

	@Test
	void una_atencion_con_turno_unico_se_confirma_sola() {
		assertThat(MensajesDeTelegram.eleccionPendiente(atencion(10L, List.of(), null, null, List.of(), List.of())))
				.isNull();
	}

	@Test
	void varios_turnos_hoy_piden_la_hora() {
		Tarjeta tarjeta = atencion(null, List.of(new OpcionDeTarjeta(1, "09:00"), new OpcionDeTarjeta(2, "11:00")),
				null, null, List.of(), List.of());
		assertThat(MensajesDeTelegram.eleccionPendiente(tarjeta)).contains("más de un turno");
	}

	@Test
	void varios_casos_abiertos_sin_elegir_piden_cual() {
		Tarjeta tarjeta = atencion(10L, List.of(), null, null,
				List.of(new OpcionDeTarjeta(3, "Caso N.º 1"), new OpcionDeTarjeta(4, "Caso N.º 2")), List.of());
		assertThat(MensajesDeTelegram.eleccionPendiente(tarjeta)).contains("caso clínico");
	}

	@Test
	void los_tratamientos_no_reconocidos_se_sacan_y_la_nota_queda() {
		TarjetaDeAtencion tarjeta = atencion(10L, List.of(), null, null, List.of(), List.of(
				new TratamientoEnTarjeta("movilidad de hombro", 5L, "Movilidad articular"),
				new TratamientoEnTarjeta("algo raro", null, null)));

		TarjetaDeAtencion limpia = (TarjetaDeAtencion) MensajesDeTelegram.sinTratamientosSueltos(tarjeta);

		assertThat(limpia.tratamientos()).extracting(TratamientoEnTarjeta::practicaId).containsExactly(5L);
		assertThat(limpia.nota()).isEqualTo(tarjeta.nota());
		assertThat(limpia.cerrar()).isTrue();
	}

	@Test
	void reconoce_los_comandos_propios_y_no_los_de_otros_bots() {
		assertThat(MensajesDeTelegram.comando("/vincular 123456", "kine_bot")).isEqualTo("vincular");
		assertThat(MensajesDeTelegram.comando("/Vincular@Kine_Bot 123456", "kine_bot")).isEqualTo("vincular");
		assertThat(MensajesDeTelegram.comando("/vincular@otro_bot 123456", "kine_bot")).isEmpty();
		assertThat(MensajesDeTelegram.comando("Juan hizo 3 series", "kine_bot")).isNull();
	}
}
