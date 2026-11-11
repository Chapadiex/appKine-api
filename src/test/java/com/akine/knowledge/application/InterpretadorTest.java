package com.akine.knowledge.application;

import com.akine.knowledge.application.Interpretacion.Aclarar;
import com.akine.knowledge.application.Interpretacion.Buscar;
import com.akine.knowledge.infrastructure.ModeloDeLenguajeDePrueba;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Interpretador")
class InterpretadorTest {

	private static final long ORG = 7L;
	private static final List<TurnoDeConsulta> CHARLA_LUMBALGIA = List.of(
			new TurnoDeConsulta(TurnoDeConsulta.Rol.USUARIO, "lumbalgia"),
			new TurnoDeConsulta(TurnoDeConsulta.Rol.ASISTENTE, "¿Qué buscás sobre lumbalgia?"),
			new TurnoDeConsulta(TurnoDeConsulta.Rol.USUARIO, "Intervenciones de rehabilitación para lumbalgia"),
			new TurnoDeConsulta(TurnoDeConsulta.Rol.ASISTENTE, "- Ejercicio: mejora la función."));

	private final ModeloDeLenguajeDePrueba modelo = new ModeloDeLenguajeDePrueba();
	private final List<String> sondeadas = new ArrayList<>();

	private Interpretador interpretador(SondaDeEvidencia sonda) {
		SondaDeEvidencia registrada = (orgId, consulta) -> {
			sondeadas.add(consulta);
			return sonda.mejorPuntaje(orgId, consulta);
		};
		return new Interpretador(() -> modelo, registrada, Interpretador.CORTE_VECTOR_POR_DEFECTO);
	}

	private Interpretador conEvidenciaEnTodo() {
		return interpretador((orgId, consulta) -> 0.9);
	}

	private static Instant limite() {
		return Instant.now().plusSeconds(90);
	}

	private static String json(String tema, boolean temaNuevo, String intencion, String completa) {
		return "{\"tema\":\"" + tema + "\",\"tema_nuevo\":" + temaNuevo + ",\"intencion\":\"" + intencion
				+ "\",\"pregunta_completa\":\"" + completa + "\"}";
	}

	@Test
	void palabraSueltaRepreguntaSinLlamarAlModelo() {
		Interpretacion resultado = conEvidenciaEnTodo().interpretar(ORG, "Lumbalgia", List.of(), limite());

		assertThat(modelo.llamadas()).isZero();
		Aclarar aclarar = (Aclarar) resultado;
		assertThat(aclarar.texto()).isEqualTo("¿Qué buscás sobre Lumbalgia?");
		assertThat(aclarar.opciones()).extracting(OpcionDeAclaracion::etiqueta).containsExactly(
				"¿Qué es Lumbalgia?",
				"Evaluación de Lumbalgia",
				"Intervenciones de rehabilitación para Lumbalgia",
				"Ejercicios para Lumbalgia",
				"Profesionales que intervienen en Lumbalgia");
		assertThat(aclarar.opciones()).allMatch(o -> o.pregunta().equals(o.etiqueta()));
		assertThat(sondeadas).containsExactly(
				"qué es Lumbalgia",
				"evaluación de Lumbalgia",
				"intervenciones de rehabilitación para Lumbalgia",
				"ejercicios para Lumbalgia",
				"profesionales que intervienen en Lumbalgia");
	}

	@Test
	void ofreceSoloAspectosConEvidencia() {
		Interpretador interpretador = interpretador((orgId, consulta) ->
				consulta.startsWith("ejercicios") ? 0.85 : consulta.startsWith("qué es") ? 0.818 : 0.817);

		Aclarar aclarar = (Aclarar) interpretador.interpretar(ORG, "kinesiofobia", List.of(), limite());

		assertThat(aclarar.opciones()).containsExactly(
				new OpcionDeAclaracion("¿Qué es kinesiofobia?", "¿Qué es kinesiofobia?"),
				new OpcionDeAclaracion("Ejercicios para kinesiofobia", "Ejercicios para kinesiofobia"));

		// Sin ningun aspecto con evidencia: busqueda normal, el control de evidencia decide.
		Interpretacion sinEvidencia = interpretador((orgId, consulta) -> {
			throw new IllegalStateException("indice no disponible");
		}).interpretar(ORG, "acv hombro", List.of(), limite());

		assertThat(sinEvidencia).isEqualTo(
				new Buscar("acv hombro", Intencion.OTRA, "acv hombro", List.of("acv hombro"), true, false));
		assertThat(modelo.llamadas()).isZero();
	}

	@Test
	void consultaConIntencionNoRepregunta() {
		modelo.responderJson(json("lumbalgia", true, "tratamiento", "¿Cómo se trabaja la lumbalgia en rehabilitación?"));

		Interpretacion resultado = conEvidenciaEnTodo().interpretar(ORG, "como trabajar lumbalgia", List.of(), limite());

		assertThat(resultado).isEqualTo(new Buscar("lumbalgia", Intencion.TRATAMIENTO,
				"¿Cómo se trabaja la lumbalgia en rehabilitación?",
				List.of("¿Cómo se trabaja la lumbalgia en rehabilitación?",
						"intervenciones de rehabilitación para lumbalgia"),
				true, false));
		assertThat(modelo.llamadas()).isEqualTo(1);
		assertThat(sondeadas).isEmpty();

		modelo.responderJson(json("fractura de cadera", true, "ejercicios", "¿Qué ejercicios sirven tras una fractura de cadera?"));

		Buscar buscar = (Buscar) conEvidenciaEnTodo().interpretar(ORG, "ejercicios fractura cadera", List.of(), limite());

		assertThat(buscar.intencion()).isEqualTo(Intencion.EJERCICIOS);
		assertThat(buscar.consultas()).containsExactly(
				"¿Qué ejercicios sirven tras una fractura de cadera?", "ejercicios para fractura de cadera");
	}

	@Test
	void aspectoSinAfeccionPideTema() {
		for (String consulta : List.of("dosis", "Evaluación", "evaluación de", "¿tratamiento?", "qué es")) {
			assertThat(conEvidenciaEnTodo().interpretar(ORG, consulta, List.of(), limite()))
					.as(consulta)
					.isEqualTo(new Aclarar("¿Para qué afección o tema?", List.of()));
		}
		assertThat(modelo.llamadas()).isZero();
		assertThat(sondeadas).isEmpty();
	}

	@Test
	void aspectoSueltoSinHistorialPideTema() {
		Interpretacion resultado = conEvidenciaEnTodo().interpretar(ORG, "ejercicios", List.of(), limite());

		assertThat(resultado).isEqualTo(new Aclarar("¿Para qué afección o tema?", List.of()));
		assertThat(modelo.llamadas()).isZero();
	}

	@Test
	void repreguntaDeMismoTemaHereda() {
		modelo.responderJson(json("lumbalgia", false, "ejercicios", "¿Qué ejercicios se indican para la lumbalgia?"));

		Interpretacion resultado = conEvidenciaEnTodo().interpretar(ORG, "ejercicios", CHARLA_LUMBALGIA, limite());

		Buscar buscar = (Buscar) resultado;
		assertThat(buscar.tema()).isEqualTo("lumbalgia");
		assertThat(buscar.intencion()).isEqualTo(Intencion.EJERCICIOS);
		assertThat(buscar.independiente()).isFalse();
		assertThat(buscar.profundo()).isFalse();
		assertThat(buscar.consultas()).containsExactly(
				"¿Qué ejercicios se indican para la lumbalgia?", "ejercicios para lumbalgia");
	}

	@Test
	void temaNuevoEsIndependiente() {
		modelo.responderJson(json("kinesiofobia", true, "otra", "¿Qué hay sobre kinesiofobia?"));

		Interpretacion resultado = conEvidenciaEnTodo().interpretar(ORG, "kinesiofobia", CHARLA_LUMBALGIA, limite());

		Aclarar aclarar = (Aclarar) resultado;
		assertThat(aclarar.texto()).isEqualTo("¿Qué buscás sobre kinesiofobia?");
		assertThat(aclarar.opciones()).hasSize(5).noneMatch(o -> o.pregunta().contains("lumbalgia"));

		// Una consulta que nombra su tema no hereda, aunque el modelo diga que es el mismo.
		modelo.responderJson(json("manguito rotador", false, "ejercicios", "¿Qué ejercicios hay para el manguito rotador?"));

		Buscar buscar = (Buscar) conEvidenciaEnTodo().interpretar(
				ORG, "ejercicios para manguito rotador", CHARLA_LUMBALGIA, limite());

		assertThat(buscar.independiente()).isTrue();
		assertThat(buscar.consultas()).noneMatch(c -> c.contains("lumbalgia"));

		// Y si el modelo le pone el tema de la charla, se descarta: la pregunta tal cual.
		modelo.responderJson(json("lumbalgia", false, "ejercicios", "¿Qué ejercicios hay para la lumbalgia?"));

		Buscar cambiado = (Buscar) conEvidenciaEnTodo().interpretar(
				ORG, "ejercicios para manguito rotador", CHARLA_LUMBALGIA, limite());

		assertThat(cambiado).isEqualTo(new Buscar("ejercicios para manguito rotador", Intencion.OTRA,
				"ejercicios para manguito rotador", List.of("ejercicios para manguito rotador"), true, false));
	}

	@Test
	void jsonInvalidoUsaRespaldo() {
		// El modelo de prueba sin JSON configurado devuelve el eco: no es JSON.
		Interpretacion invalido = conEvidenciaEnTodo().interpretar(ORG, "como trabajar lumbalgia", List.of(), limite());

		assertThat(invalido).isEqualTo(new Buscar("como trabajar lumbalgia", Intencion.OTRA,
				"como trabajar lumbalgia", List.of("como trabajar lumbalgia"), true, false));

		modelo.responderJson("{\"tema\":\"\",\"pregunta_completa\":\"x\"}");
		assertThat(((Buscar) conEvidenciaEnTodo().interpretar(ORG, "como trabajar lumbalgia", List.of(), limite()))
				.preguntaCompleta()).isEqualTo("como trabajar lumbalgia");

		// Modelo caido con historial y sin tema propio: el respaldo concatenado de v1.
		modelo.fallarEnCompletar(true);
		Interpretacion caido = conEvidenciaEnTodo().interpretar(
				ORG, "¿y cuántas veces por semana?", CHARLA_LUMBALGIA, limite());

		String junta = "Intervenciones de rehabilitación para lumbalgia ¿y cuántas veces por semana?";
		assertThat(caido).isEqualTo(new Buscar("¿y cuántas veces por semana?", Intencion.OTRA, junta,
				List.of(junta), false, false));

		// Plazo vencido: ni siquiera se llama al modelo.
		int antes = modelo.llamadas();
		conEvidenciaEnTodo().interpretar(ORG, "como trabajar lumbalgia", List.of(), Instant.now().minusSeconds(1));
		assertThat(modelo.llamadas()).isEqualTo(antes);
	}

	@Test
	void profundizarDetectado() {
		modelo.responderJson(json("lumbalgia", false, "tratamiento",
				"Más detalle sobre las intervenciones de rehabilitación para lumbalgia"));

		Buscar buscar = (Buscar) conEvidenciaEnTodo().interpretar(
				ORG, "Más detalle sobre lo anterior", CHARLA_LUMBALGIA, limite());

		assertThat(buscar.profundo()).isTrue();
		assertThat(buscar.independiente()).isFalse();

		modelo.responderJson(json("psicoterapia", false, "otra", "¿Quién hace la psicoterapia y cuánto dura?"));
		List<TurnoDeConsulta> charla = List.of(
				new TurnoDeConsulta(TurnoDeConsulta.Rol.USUARIO, "intervenciones para lumbalgia con psicoterapia"));
		assertThat(((Buscar) conEvidenciaEnTodo().interpretar(ORG, "¿quién la hace?", charla, limite())).profundo())
				.isTrue();

		// Sin historial no hay nada que profundizar.
		modelo.responderJson(json("lumbalgia", true, "otra", "Más detalle sobre la lumbalgia"));
		assertThat(((Buscar) conEvidenciaEnTodo().interpretar(ORG, "más detalle sobre lumbalgia", List.of(), limite()))
				.profundo()).isFalse();
	}

	@Test
	void preguntaCompletaNoMeteTemaDeLaCharla() {
		modelo.responderJson(json("manguito rotador", true, "ejercicios", "Ejercicios para manguito rotador en lumbalgia"));

		Buscar buscar = (Buscar) conEvidenciaEnTodo().interpretar(
				ORG, "ejercicios para manguito rotador", CHARLA_LUMBALGIA, limite());

		assertThat(buscar.independiente()).isTrue();
		assertThat(buscar.preguntaCompleta()).isEqualTo("ejercicios para manguito rotador");
		assertThat(buscar.consultas()).containsExactly("ejercicios para manguito rotador");

		// Tampoco un tema que no esta en ninguna de las dos.
		modelo.responderJson(json("manguito rotador", true, "ejercicios", "Ejercicios para manguito rotador y escoliosis"));

		Buscar otro = (Buscar) conEvidenciaEnTodo().interpretar(
				ORG, "ejercicios para manguito rotador", CHARLA_LUMBALGIA, limite());

		assertThat(otro.consultas()).noneMatch(c -> c.contains("escoliosis") || c.contains("lumbalgia"));
	}

	@Test
	void palabraCompartidaNoOcultaTemaNuevo() {
		List<TurnoDeConsulta> charla = List.of(
				new TurnoDeConsulta(TurnoDeConsulta.Rol.USUARIO, "ejercicios fractura cadera"));
		modelo.responderJson(json("fractura de muñeca", false, "ejercicios", "¿Qué ejercicios sirven para la fractura de muñeca?"));

		Buscar buscar = (Buscar) conEvidenciaEnTodo().interpretar(
				ORG, "ejercicios para fractura de muñeca", charla, limite());

		assertThat(buscar.independiente()).isTrue();
		assertThat(buscar.consultas()).noneMatch(c -> c.contains("cadera"));

		// Palabra entera: "dolor" no es "doloroso".
		List<TurnoDeConsulta> hombro = List.of(new TurnoDeConsulta(TurnoDeConsulta.Rol.USUARIO, "hombro doloroso"));
		modelo.responderJson(json("dolor", false, "ejercicios", "¿Qué ejercicios sirven para el dolor?"));

		Buscar dolor = (Buscar) conEvidenciaEnTodo().interpretar(ORG, "ejercicios para el dolor", hombro, limite());

		assertThat(dolor.independiente()).isTrue();
		assertThat(dolor.consultas()).noneMatch(c -> c.contains("hombro"));
	}

	@Test
	void seguimientoDeSesionHereda() {
		List<TurnoDeConsulta> charla = List.of(
				new TurnoDeConsulta(TurnoDeConsulta.Rol.USUARIO, "ejercicios para fractura de cadera"),
				new TurnoDeConsulta(TurnoDeConsulta.Rol.ASISTENTE, "- Fortalecimiento: recupera la marcha."));
		String consulta = "¿quién lo hace y cuánto dura cada sesión?";
		String completa = "¿Quién hace los ejercicios para fractura de cadera y cuánto dura cada sesión?";
		modelo.responderJson(json("fractura de cadera", false, "profesionales", completa));

		Buscar buscar = (Buscar) conEvidenciaEnTodo().interpretar(ORG, consulta, charla, limite());

		assertThat(buscar.independiente()).isFalse();
		assertThat(buscar.profundo()).isTrue();
		assertThat(buscar.tema()).isEqualTo("fractura de cadera");
		assertThat(buscar.consultas()).containsExactly(completa, "profesionales que intervienen en fractura de cadera");

		// Con el modelo caido tambien hereda: el respaldo concatenado.
		modelo.fallarEnCompletar(true);
		Buscar caido = (Buscar) conEvidenciaEnTodo().interpretar(ORG, consulta, charla, limite());

		assertThat(caido.independiente()).isFalse();
		assertThat(caido.preguntaCompleta()).isEqualTo("ejercicios para fractura de cadera " + consulta);
	}

	@Test
	void ambiguaCuentaPalabrasDeContenido() {
		for (String suelta : List.of("lumbalgia", "fractura de cadera", "artritis reumatoide")) {
			assertThat(conEvidenciaEnTodo().interpretar(ORG, suelta, List.of(), limite())).as(suelta)
					.isInstanceOf(Aclarar.class);
		}
		assertThat(modelo.llamadas()).isZero();

		for (String directa : List.of("acv hombro doloroso", "ortesis artritis reumatoide")) {
			modelo.responderJson(json(directa, true, "otra", directa));
			sondeadas.clear();

			Interpretacion resultado = conEvidenciaEnTodo().interpretar(ORG, directa, List.of(), limite());

			assertThat(resultado).as(directa).isEqualTo(
					new Buscar(directa, Intencion.OTRA, directa, List.of(directa), true, false));
			assertThat(sondeadas).isEmpty();
		}
		assertThat(modelo.llamadas()).isEqualTo(2);
	}

	@Test
	void noAgregaTemasQueNadieNombra() {
		modelo.responderJson(json("lumbalgia y ACV", true, "ejercicios", "¿Qué ejercicios sirven para lumbalgia y ACV?"));

		Buscar buscar = (Buscar) conEvidenciaEnTodo().interpretar(ORG, "ejercicios para lumbalgia", List.of(), limite());

		assertThat(buscar.tema()).doesNotContain("ACV");
		assertThat(buscar.preguntaCompleta()).doesNotContain("ACV");
		assertThat(buscar.consultas()).noneMatch(c -> c.contains("ACV"));

		modelo.responderJson(json("lumbalgia", true, "ejercicios", "¿Qué ejercicios sirven para lumbalgia tras un ACV?"));

		Buscar soloSigla = (Buscar) conEvidenciaEnTodo().interpretar(ORG, "ejercicios para lumbalgia", List.of(), limite());

		assertThat(soloSigla.consultas()).noneMatch(c -> c.contains("ACV"));
	}
}
