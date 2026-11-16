package com.akine.assistant.application;

import com.akine.assistant.domain.exception.DictadoNoDisponibleException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepartidorDeEvaluacionTest {

	private final RepartidorDeEvaluacion repartidor =
			new RepartidorDeEvaluacion(() -> null, "prompt", Duration.ofSeconds(1));

	@Test
	void acepta_el_dolor_como_texto_y_normaliza_el_catalogo() {
		PropuestaDeEvaluacion p = repartidor.leer(
				"{\"dolorEva\":\"7\",\"dolorLateralidad\":\"no aplica\",\"evolucion\":\"Igual\"}");

		assertThat(p.dolorEva()).isEqualTo(7);
		assertThat(p.dolorLateralidad()).isEqualTo("NO_APLICA");
		assertThat(p.evolucion()).isEqualTo("IGUAL");
	}

	@Test
	void descarta_dolor_fraccionario_o_fuera_de_rango_y_textos_vacios() {
		PropuestaDeEvaluacion p = repartidor.leer("{\"dolorEva\":6.5,\"dolorZona\":\"  \",\"motivoClinico\":3}");

		assertThat(p.dolorEva()).isNull();
		assertThat(p.dolorZona()).isNull();
		assertThat(p.motivoClinico()).isNull();
	}

	@Test
	void lo_que_excede_el_campo_no_se_pierde_va_a_restante() {
		String largo = "palabra ".repeat(80).strip();

		PropuestaDeEvaluacion p = repartidor.leer("{\"objetivoSesion\":\"" + largo + "\",\"restante\":\"Nota.\"}");

		String cortado = largo.substring(p.objetivoSesion().length()).strip();
		assertThat(p.objetivoSesion()).hasSizeLessThanOrEqualTo(RepartidorDeEvaluacion.MAX_TEXTO_CAMPO);
		assertThat(largo).startsWith(p.objetivoSesion());
		assertThat(p.restante()).isEqualTo("Nota. " + cortado);
	}

	@Test
	void una_respuesta_ilegible_es_una_propuesta_vacia() {
		assertThat(repartidor.leer("no es json")).isEqualTo(PropuestaDeEvaluacion.VACIA);
	}

	@Test
	void sin_modelo_prendido_es_503() {
		assertThatThrownBy(() -> repartidor.repartir("dolor 5")).isInstanceOf(DictadoNoDisponibleException.class);
	}
}
