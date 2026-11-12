package com.akine.knowledge.api;

import com.akine.knowledge.domain.exception.AsistenteNoDisponibleException;
import com.akine.knowledge.domain.exception.BibliotecaDeshabilitadaException;
import com.akine.knowledge.domain.exception.ConsultaOcupadaException;
import com.akine.knowledge.domain.exception.LibroArchivoNoAceptadoException;
import com.akine.knowledge.domain.exception.LibroArchivoNoAceptadoException.Causa;
import com.akine.knowledge.domain.exception.LibroDuplicadoException;
import com.akine.knowledge.domain.exception.LibroEnProcesoException;
import com.akine.knowledge.domain.exception.LibroNoEncontradoException;
import com.akine.platform.spi.problem.ProblemType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("KnowledgeProblemHandler")
class KnowledgeProblemHandlerTest {

	private final KnowledgeProblemHandler handler = new KnowledgeProblemHandler();

	@Test
	void deshabilitada_es_409() {
		ProblemDetail p = handler.handleDeshabilitada(new BibliotecaDeshabilitadaException());
		assertThat(p.getStatus()).isEqualTo(409);
		assertThat(p.getType()).isEqualTo(ProblemType.BIBLIOTECA_DESHABILITADA.uri());
	}

	@Test
	void no_encontrado_es_404() {
		ProblemDetail p = handler.handleNoEncontrado(new LibroNoEncontradoException());
		assertThat(p.getStatus()).isEqualTo(404);
		assertThat(p.getType()).isEqualTo(ProblemType.LIBRO_NO_ENCONTRADO.uri());
	}

	@Test
	void duplicado_es_409_y_lleva_el_libro_existente() {
		ProblemDetail p = handler.handleDuplicado(new LibroDuplicadoException(42L));
		assertThat(p.getStatus()).isEqualTo(409);
		assertThat(p.getType()).isEqualTo(ProblemType.LIBRO_DUPLICADO.uri());
		assertThat(p.getProperties()).containsEntry("libroId", 42L);
	}

	@Test
	void archivo_no_aceptado_es_400_y_lleva_la_causa() {
		for (Causa causa : Causa.values()) {
			ProblemDetail p = handler.handleArchivoNoAceptado(new LibroArchivoNoAceptadoException(causa));
			assertThat(p.getStatus()).isEqualTo(400);
			assertThat(p.getType()).isEqualTo(ProblemType.LIBRO_ARCHIVO_NO_ACEPTADO.uri());
			assertThat(p.getProperties()).containsEntry("causa", causa.name());
		}
	}

	@Test
	void en_proceso_es_409() {
		ProblemDetail p = handler.handleEnProceso(new LibroEnProcesoException());
		assertThat(p.getStatus()).isEqualTo(409);
		assertThat(p.getType()).isEqualTo(ProblemType.LIBRO_EN_PROCESO.uri());
	}

	@Test
	void consulta_ocupada_es_503_con_retry_after() {
		ResponseEntity<ProblemDetail> r = handler.handleConsultaOcupada(new ConsultaOcupadaException());
		assertThat(r.getStatusCode().value()).isEqualTo(503);
		assertThat(r.getBody().getType()).isEqualTo(ProblemType.CONSULTA_OCUPADA.uri());
		assertThat(r.getHeaders().getFirst("Retry-After")).isEqualTo("15");
	}

	@Test
	void asistente_no_disponible_es_503() {
		ProblemDetail p = handler.handleAsistenteNoDisponible(new AsistenteNoDisponibleException());
		assertThat(p.getStatus()).isEqualTo(503);
		assertThat(p.getType()).isEqualTo(ProblemType.ASISTENTE_NO_DISPONIBLE.uri());
	}
}
