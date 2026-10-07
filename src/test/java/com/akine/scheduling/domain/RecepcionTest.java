package com.akine.scheduling.domain;

import com.akine.scheduling.domain.exception.TransicionDeRecepcionNoPermitidaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** La maquina de estados de la recepcion (DP-16), sin base. */
class RecepcionTest {

	private static final Instant AHORA = Instant.parse("2027-03-08T12:00:00Z");

	@Test
	@DisplayName("nace en LLEGO con la hora y el responsable de la llegada")
	void nace_en_llego() {
		Recepcion recepcion = nueva();

		assertThat(recepcion.getEstado()).isEqualTo(EstadoRecepcion.LLEGO);
		assertThat(recepcion.getLlegadaEn()).isEqualTo(AHORA);
		assertThat(recepcion.getLlegadaPorCuentaId()).isEqualTo(9L);
		assertThat(recepcion.getTurnoId()).isEqualTo(301L);
		assertThat(recepcion.estaAbierta()).isTrue();
	}

	@Test
	@DisplayName("el camino feliz con cobertura: validada, en espera, llamada")
	void camino_con_cobertura() {
		Recepcion recepcion = nueva();

		recepcion.validarConCobertura(33L, 412L, 9L, 9L, AHORA);
		assertThat(recepcion.getEstado()).isEqualTo(EstadoRecepcion.VALIDADA);
		assertThat(recepcion.getModalidad()).isEqualTo(ModalidadRecepcion.COBERTURA);
		assertThat(recepcion.getCoberturaId()).isEqualTo(412L);
		assertThat(recepcion.getConvenioId()).isEqualTo(9L);
		assertThat(recepcion.getValidadaEn()).isEqualTo(AHORA);

		recepcion.pasarAEspera(AHORA.plusSeconds(60));
		recepcion.llamar(7L, AHORA.plusSeconds(120));

		assertThat(recepcion.getEstado()).isEqualTo(EstadoRecepcion.LLAMADA);
		assertThat(recepcion.getEnEsperaDesde()).isEqualTo(AHORA.plusSeconds(60));
		assertThat(recepcion.getLlamadaEn()).isEqualTo(AHORA.plusSeconds(120));
	}

	@Test
	@DisplayName("observada exige que se diga que se observo, y se puede revalidar")
	void observada_y_revalidada() {
		Recepcion recepcion = nueva();

		assertThatThrownBy(() -> recepcion.observar(" ", null, null, null, 9L, AHORA))
				.isInstanceOf(IllegalArgumentException.class);

		recepcion.observar("DOCUMENTACION_INCOMPLETA: falta la orden", 33L, 412L, 9L, 9L, AHORA);
		assertThat(recepcion.getEstado()).isEqualTo(EstadoRecepcion.OBSERVADA);
		assertThat(recepcion.getModalidad()).isNull();

		recepcion.validarConCobertura(33L, 412L, 9L, 9L, AHORA);
		assertThat(recepcion.getEstado()).isEqualTo(EstadoRecepcion.VALIDADA);
		assertThat(recepcion.getObservacion()).isNull();
	}

	@Test
	@DisplayName("Particular exige motivo y conserva la observacion que lo motivo")
	void particular() {
		Recepcion recepcion = nueva();
		recepcion.observar("SIN_COBERTURA_APLICABLE: nada aplica", 33L, null, null, 9L, AHORA);

		assertThatThrownBy(() -> recepcion.atenderComoParticular(null, 9L, AHORA))
				.isInstanceOf(IllegalArgumentException.class);

		recepcion.atenderComoParticular("  Abona en el mostrador ", 9L, AHORA);
		assertThat(recepcion.getEstado()).isEqualTo(EstadoRecepcion.VALIDADA);
		assertThat(recepcion.getModalidad()).isEqualTo(ModalidadRecepcion.PARTICULAR);
		assertThat(recepcion.getMotivoParticular()).isEqualTo("Abona en el mostrador");
		assertThat(recepcion.getObservacion()).startsWith("SIN_COBERTURA_APLICABLE");
		assertThat(recepcion.getPracticaId()).isEqualTo(33L);
	}

	@Test
	@DisplayName("las transiciones fuera de orden son 409: espera sin validar, llamar sin espera, validar dos veces")
	void fuera_de_orden() {
		Recepcion recepcion = nueva();

		assertThatThrownBy(() -> recepcion.pasarAEspera(AHORA))
				.isInstanceOf(TransicionDeRecepcionNoPermitidaException.class)
				.hasMessageContaining("validacion administrativa");
		assertThatThrownBy(() -> recepcion.llamar(9L, AHORA))
				.isInstanceOf(TransicionDeRecepcionNoPermitidaException.class);

		recepcion.validarConCobertura(33L, 412L, null, 9L, AHORA);
		assertThatThrownBy(() -> recepcion.validarConCobertura(33L, 412L, null, 9L, AHORA))
				.isInstanceOf(TransicionDeRecepcionNoPermitidaException.class);
		assertThatThrownBy(() -> recepcion.atenderComoParticular("x", 9L, AHORA))
				.isInstanceOf(TransicionDeRecepcionNoPermitidaException.class);

		recepcion.pasarAEspera(AHORA);
		assertThatThrownBy(() -> recepcion.pasarAEspera(AHORA))
				.isInstanceOf(TransicionDeRecepcionNoPermitidaException.class);
	}

	@Test
	@DisplayName("una observada pasa a espera sin resolverse: advierte, no bloquea")
	void observada_a_espera() {
		Recepcion recepcion = nueva();
		recepcion.observar("OFERTA_SIN_PRACTICA: sin practica", null, null, null, 9L, AHORA);

		recepcion.pasarAEspera(AHORA);

		assertThat(recepcion.getEstado()).isEqualTo(EstadoRecepcion.EN_ESPERA);
	}

	@Test
	@DisplayName("anular y cerrar son terminales; despues no admiten nada")
	void terminales() {
		Recepcion anulada = nueva();
		anulada.anular(" ", 9L, AHORA);
		assertThat(anulada.getEstado()).isEqualTo(EstadoRecepcion.ANULADA);
		assertThat(anulada.getMotivoCierre()).isNull();
		assertThat(anulada.getCerradaEn()).isEqualTo(AHORA);
		assertThat(anulada.estaAbierta()).isFalse();
		assertThatThrownBy(() -> anulada.anular("otra vez", 9L, AHORA))
				.isInstanceOf(TransicionDeRecepcionNoPermitidaException.class);

		Recepcion cerrada = nueva();
		cerrada.atenderComoParticular("Abona", 9L, AHORA);
		cerrada.pasarAEspera(AHORA);
		cerrada.llamar(9L, AHORA);
		cerrada.cerrarPorCancelacion("El profesional se descompuso", 9L, AHORA);
		assertThat(cerrada.getEstado()).isEqualTo(EstadoRecepcion.CERRADA);
		assertThat(cerrada.getMotivoCierre()).isEqualTo("El profesional se descompuso");
		assertThat(cerrada.getLlegadaEn()).as("la llegada consta").isEqualTo(AHORA);
		assertThatThrownBy(() -> cerrada.cerrarPorCancelacion("x", 9L, AHORA))
				.isInstanceOf(TransicionDeRecepcionNoPermitidaException.class);
		assertThatThrownBy(() -> cerrada.validarConCobertura(1L, 1L, null, 9L, AHORA))
				.isInstanceOf(TransicionDeRecepcionNoPermitidaException.class);
	}

	@Test
	@DisplayName("el evento copia el estado nuevo de la recepcion y recorta el motivo al largo de la columna")
	void el_evento() {
		Recepcion recepcion = nueva();
		RecepcionEvento llegada = RecepcionEvento.de(
				recepcion, TipoEventoRecepcion.LLEGADA, null, " ", 9L, AHORA);
		assertThat(llegada.getEstadoAnterior()).isNull();
		assertThat(llegada.getEstadoNuevo()).isEqualTo(EstadoRecepcion.LLEGO);
		assertThat(llegada.getMotivo()).isNull();
		assertThat(llegada.getTurnoId()).isEqualTo(301L);
		assertThat(llegada.getTipo()).isEqualTo(TipoEventoRecepcion.LLEGADA);
		assertThat(llegada.getActorCuentaId()).isEqualTo(9L);
		assertThat(llegada.getOcurridoEn()).isEqualTo(AHORA);

		RecepcionEvento largo = RecepcionEvento.de(
				recepcion, TipoEventoRecepcion.VALIDACION, EstadoRecepcion.LLEGO, "x".repeat(1500), 9L, AHORA);
		assertThat(largo.getMotivo()).hasSize(1000);
	}

	@Test
	@DisplayName("EstadoRecepcion: solo ANULADA y CERRADA estan cerradas")
	void abiertas() {
		assertThat(EstadoRecepcion.values()).filteredOn(e -> !e.estaAbierta())
				.containsExactly(EstadoRecepcion.ANULADA, EstadoRecepcion.CERRADA);
	}

	private static Recepcion nueva() {
		Turno turno = new Turno(1L, 7L, 42L, 128L, 31L, null,
				AHORA.plus(Duration.ofHours(1)), AHORA.plus(Duration.ofHours(2)), 9L, AHORA, null, null);
		ReflectionTestUtils.setField(turno, "id", 301L);
		return Recepcion.llegada(turno, AHORA, 9L);
	}
}
