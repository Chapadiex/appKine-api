package com.akine.assistant.application;

import com.akine.assistant.spi.DatosDePersona;
import com.akine.platform.spi.modelo.ModeloSoloJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** El recorrido frase → accion: que es pregunta, que se lee del modelo y que sobrevive al control. */
class InterpretarAccionesTest {

	private final AtomicInteger llamadas = new AtomicInteger();

	private InterpretadorDeAcciones conRespuesta(String json) {
		ModeloSoloJson modelo = (mensajes, esquema, plazo) -> {
			llamadas.incrementAndGet();
			return json;
		};
		return new InterpretadorDeAcciones(() -> modelo, "PROMPT", Duration.ofSeconds(5));
	}

	private static AccionInterpretada pago(String persona, String monto) {
		return new AccionInterpretada(AccionInterpretada.PAGO, persona, null, null, List.of(),
				ControlDeAccion.importe(monto), "EFECTIVO", List.of(), DatosDePersona.VACIO);
	}

	@ParameterizedTest
	@ValueSource(strings = {"¿vino Juan?", "vino juan?", "quienes faltaron a yoga", "Cuánto debe Ana",
			"turnos del viernes"})
	void una_pregunta_no_pasa_por_las_acciones(String frase) {
		assertThat(EsPregunta.es(frase)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {"vinieron todos menos María", "Juan pagó 15000", "hoy vino Juan e hizo ultrasonido",
			"agregá a Laura Díaz"})
	void lo_que_cuenta_o_pide_algo_no_es_pregunta(String frase) {
		assertThat(EsPregunta.es(frase)).isFalse();
	}

	@Test
	void un_json_roto_o_una_accion_desconocida_es_ninguna() {
		assertThat(conRespuesta("no es json").interpretar("Juan pagó 15000").ninguna()).isTrue();
		assertThat(conRespuesta("{\"accion\":\"BORRAR_TODO\"}").interpretar("Juan pagó 15000").ninguna()).isTrue();
	}

	@Test
	void una_frase_demasiado_larga_no_llega_al_modelo() {
		assertThat(conRespuesta("{\"accion\":\"PAGO\"}").interpretar("a".repeat(601)).ninguna()).isTrue();
		assertThat(llamadas).hasValue(0);
	}

	@Test
	void lee_los_campos_de_un_pago() {
		AccionInterpretada leida = conRespuesta(
				"{\"accion\":\"PAGO\",\"persona\":\"el señor Juan Pérez\",\"monto\":\"15.000\"}")
				.interpretar("Juan Pérez pagó 15.000 en efectivo");

		assertThat(leida.accion()).isEqualTo("PAGO");
		assertThat(leida.persona()).isEqualTo("Juan Pérez");
		assertThat(leida.monto()).isEqualByComparingTo("15000");
	}

	@Test
	void el_monto_dicho_en_letras_no_se_usa() {
		AccionInterpretada controlada = ControlDeAccion.controlar(pago("juan", "15000"),
				"juan pago quince mil en efectivo");

		assertThat(controlada.monto()).isNull();
		assertThat(controlada.medio()).isEqualTo("EFECTIVO");
	}

	@ParameterizedTest
	@ValueSource(strings = {"Juan pagó 15.000", "juan pago 15000 pesos", "Juan pagó 15 mil", "juan pago $15.000"})
	void el_monto_escrito_con_numeros_pasa(String frase) {
		assertThat(ControlDeAccion.controlar(pago("Juan", "15000"), frase).monto()).isEqualByComparingTo("15000");
	}

	@Test
	void el_medio_sale_de_la_frase_y_no_del_modelo() {
		assertThat(ControlDeAccion.controlar(pago("Juan", "15000"), "Juan pagó 15000").medio()).isNull();
		assertThat(ControlDeAccion.controlar(pago("Juan", "15000"), "Juan transfirió 15000").medio())
				.isEqualTo("TRANSFERENCIA");
		assertThat(ControlDeAccion.controlar(pago("Juan", "15000"), "Juan pagó 15000 con tarjeta").medio()).isNull();
	}

	@Test
	void una_persona_o_un_tratamiento_que_no_estan_en_la_frase_se_descartan() {
		AccionInterpretada leida = new AccionInterpretada(AccionInterpretada.ATENCION, "Pedro Gómez", null, null,
				List.of(), null, null, List.of("ultrasonido", "magnetoterapia"), DatosDePersona.VACIO);

		AccionInterpretada controlada = ControlDeAccion.controlar(leida, "hoy vino Juan e hizo Ultrasonido");

		assertThat(controlada.persona()).isNull();
		assertThat(controlada.practicas()).containsExactly("ultrasonido");
	}

	@Test
	void el_telefono_pasa_por_sus_digitos_y_la_fecha_se_lee_de_la_frase() {
		AccionInterpretada leida = new AccionInterpretada(AccionInterpretada.PACIENTE_ALTA, null, null, null,
				List.of(), null, null, List.of(),
				new DatosDePersona("Laura", "Díaz", "30111222", "3515551234", "otra@correo.com", null));

		AccionInterpretada controlada = ControlDeAccion.controlar(leida,
				"agregá a Laura Díaz, teléfono 351 555-1234, nació el 3/4/1990");

		assertThat(controlada.datos()).isEqualTo(new DatosDePersona("Laura", "Díaz", null, "3515551234", null,
				LocalDate.of(1990, 4, 3)));
	}

	@Test
	void los_nombres_de_la_asistencia_que_no_se_dijeron_se_descartan() {
		AccionInterpretada leida = new AccionInterpretada(AccionInterpretada.ASISTENCIA_CLASE, null, "pilates",
				"TODOS_MENOS", List.of("María", "Carlos"), null, null, List.of(), DatosDePersona.VACIO);

		AccionInterpretada controlada = ControlDeAccion.controlar(leida, "vinieron todos menos maria");

		assertThat(controlada.nombres()).containsExactly("María");
		assertThat(controlada.clase()).isNull();
		assertThat(controlada.modoAsistencia()).isEqualTo("TODOS_MENOS");
	}

	@Test
	void la_puntuacion_pegada_al_nombre_no_lo_descarta() {
		AccionInterpretada asistencia = new AccionInterpretada(AccionInterpretada.ASISTENCIA_CLASE, null, null,
				"TODOS_MENOS", List.of("María", "Juan Pérez"), null, null, List.of(), DatosDePersona.VACIO);

		assertThat(ControlDeAccion.controlar(asistencia, "Vinieron todos menos María, y Juan Pérez.").nombres())
				.containsExactly("María", "Juan Pérez");
		assertThat(ControlDeAccion.controlar(pago("Juan Pérez", "15000"), "Cobrale 15000 a Juan Pérez.").persona())
				.isEqualTo("Juan Pérez");
	}

	@Test
	void pedir_un_alta_o_un_cobro_no_es_una_pregunta() {
		assertThat(EsPregunta.es("Dame de alta a Laura Díaz, teléfono 3515551234")).isFalse();
		assertThat(EsPregunta.es("Hay que cobrarle 15000 a Juan")).isFalse();
	}

	@Test
	void el_importe_entiende_miles_y_decimales() {
		assertThat(ControlDeAccion.importe("15.000,50")).isEqualByComparingTo(new BigDecimal("15000.50"));
		assertThat(ControlDeAccion.importe("$ 8500")).isEqualByComparingTo("8500");
		assertThat(ControlDeAccion.importe("mucho")).isNull();
		assertThat(ControlDeAccion.importe("0")).isNull();
	}
}
