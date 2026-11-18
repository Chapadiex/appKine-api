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
				"{\"dolorEva\":\"7\",\"dolorLateralidad\":\"no aplica\",\"evolucion\":\"Igual\"}", "dolor siete, igual, no aplica el lado");

		assertThat(p.dolorEva()).isEqualTo(7);
		assertThat(p.dolorLateralidad()).isEqualTo("NO_APLICA");
		assertThat(p.evolucion()).isEqualTo("IGUAL");
	}

	@Test
	void descarta_dolor_fraccionario_o_fuera_de_rango_y_textos_vacios() {
		PropuestaDeEvaluacion p = repartidor.leer("{\"dolorEva\":6.5,\"dolorZona\":\"  \",\"motivoClinico\":3}", "");

		assertThat(p.dolorEva()).isNull();
		assertThat(p.dolorZona()).isNull();
		assertThat(p.motivoClinico()).isNull();
	}

	@Test
	void lo_que_excede_el_campo_no_se_pierde_va_a_las_notas() {
		String largo = "palabra ".repeat(80).strip();

		PropuestaDeEvaluacion p = repartidor.leer(
				"{\"objetivoSesion\":\"" + largo + "\",\"notas\":[{\"tema\":\"Comentario\",\"texto\":\"Nota.\"}]}", largo + " Nota.");

		String cortado = largo.substring(p.objetivoSesion().length()).strip();
		assertThat(p.objetivoSesion()).hasSizeLessThanOrEqualTo(RepartidorDeEvaluacion.MAX_TEXTO_CAMPO);
		assertThat(largo).startsWith(p.objetivoSesion());
		assertThat(p.restante()).isEqualTo("- Comentario: Nota.\n- " + cortado);
	}

	@Test
	void las_notas_salen_como_lista_con_tema() {
		PropuestaDeEvaluacion p = repartidor.leer("""
				{"notas":[{"tema":"realizo:","texto":"ejercito el tren delantero"},
				          {"tema":"Estado","texto":"perfecto"},
				          {"texto":"trajo estudios"}]}""",
				"Hoy realizo ejercito el tren delantero. Estado perfecto. Trajo estudios.");

		assertThat(p.restante()).isEqualTo("""
				- Realizo: ejercito el tren delantero
				- Estado: perfecto
				- trajo estudios""");
	}

	@Test
	void una_frase_que_el_modelo_salteo_va_a_las_notas() {
		// Lo que devolvio qwen3:4b el 18/11/2026: "post operatoria de ligamento cruzado" no quedo en ningun campo.
		String dictado = "Rodilla derecha post operatoria de ligamento cruzado, dolor cinco, igual que la vez "
				+ "anterior, no puede subir escaleras. Trabajamos cuádriceps.";
		String modelo = """
				{"dolorEva":5,"dolorZona":"rodilla","dolorLateralidad":"DERECHA","evolucion":"IGUAL",
				 "objetivoSesion":"trabajamos cuádriceps","limitacionFuncional":"no puede subir escaleras"}""";

		PropuestaDeEvaluacion p = repartidor.leer(modelo, dictado);

		assertThat(p.restante()).isEqualTo("- Dictado: Rodilla derecha post operatoria de ligamento cruzado");
	}

	@Test
	void lo_que_ya_quedo_ubicado_no_se_repite_en_las_notas() {
		String dictado = "Dolor tres en zona lumbar, bilateral. Viene mejor. Objetivo de la sesión fortalecer core. "
				+ "Motivo, lumbalgia mecánica crónica.";
		String modelo = """
				{"dolorEva":3,"dolorZona":"zona lumbar","dolorLateralidad":"BILATERAL","evolucion":"MEJOR",
				 "objetivoSesion":"fortalecer core","motivoClinico":"lumbalgia mecánica crónica"}""";

		assertThat(repartidor.leer(modelo, dictado).restante()).isNull();
	}

	@Test
	void descarta_lo_que_el_modelo_invento() {
		// Lo que devolvio qwen3:4b el 18/11/2026: "ganar flexion" sale del ejemplo del prompt, y nadie
		// hablo de zona, limitacion ni evolucion.
		String dictado = "Hoy realizó distinta rutina. Estado perfecto. Dolor casi nulo, uno de diez.";
		String modelo = """
				{"dolorEva":1,"dolorZona":"NO_APLICA","evolucion":"SIN_REFERENCIA","objetivoSesion":"ganar flexion",
				 "limitacionFuncional":"NO_APLICA","notas":[{"tema":"Realizo","texto":"distinta rutina"},
				 {"tema":"Indicaciones","texto":"reposo absoluto"}]}""";

		PropuestaDeEvaluacion p = repartidor.leer(modelo, dictado);

		assertThat(p.dolorEva()).isEqualTo(1);
		assertThat(p.dolorZona()).isNull();
		assertThat(p.limitacionFuncional()).isNull();
		assertThat(p.objetivoSesion()).isNull();
		assertThat(p.evolucion()).isNull();
		assertThat(p.restante()).isEqualTo("""
				- Realizo: distinta rutina
				- Dictado: Estado perfecto
				- Dictado: Dolor casi nulo""");
	}

	@Test
	void una_respuesta_ilegible_manda_todo_el_dictado_a_las_notas() {
		PropuestaDeEvaluacion p = repartidor.leer("no es json", "Hoy hizo bicicleta");

		assertThat(p.restante()).isEqualTo("- Dictado: Hoy hizo bicicleta");
		assertThat(p.dolorEva()).isNull();
	}

	@Test
	void sin_modelo_prendido_es_503() {
		assertThatThrownBy(() -> repartidor.repartir("dolor 5")).isInstanceOf(DictadoNoDisponibleException.class);
	}
}
