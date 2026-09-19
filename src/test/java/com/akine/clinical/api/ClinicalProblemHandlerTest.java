package com.akine.clinical.api;

import com.akine.clinical.domain.exception.AccesoClinicoNoJustificadoException;
import com.akine.clinical.domain.exception.AdjuntoClinicoInactivoException;
import com.akine.clinical.domain.exception.AdjuntoClinicoNoDisponibleException;
import com.akine.clinical.domain.exception.AdjuntoClinicoNotAccessibleException;
import com.akine.clinical.domain.exception.AntecedenteNotAccessibleException;
import com.akine.clinical.domain.exception.ArchivoClinicoNoAceptadoException;
import com.akine.clinical.domain.exception.CursorInvalidoException;
import com.akine.clinical.domain.exception.EnmiendaSinMotivoException;
import com.akine.clinical.domain.exception.EntradaClinicaInactivaException;
import com.akine.clinical.domain.exception.EntradaClinicaNotAccessibleException;
import com.akine.clinical.domain.exception.HistoriaClinicaNotAccessibleException;
import com.akine.clinical.domain.exception.PacienteSinPerfilVigenteException;
import com.akine.platform.spi.problem.ProblemType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El contrato de errores de {@code clinical}.
 *
 * <p>Lo que se verifica no es que "devuelve un error", sino <b>cual</b>: el status y el
 * {@code type} son lo unico que el cliente puede leer con una maquina, y cada eleccion de esta
 * clase lleva a una accion distinta en la pantalla. Un 404 donde deberia haber 409 manda al
 * profesional a pensar que el estudio no existe; un 403 pelado donde deberia haber
 * {@code requiereJustificacion} lo manda a pedir un permiso que ya tiene.
 */
@DisplayName("ClinicalProblemHandler")
class ClinicalProblemHandlerTest {

	private final ClinicalProblemHandler handler = new ClinicalProblemHandler();

	@Test
	@DisplayName("todo lo que esta fuera del alcance es 404, nunca 403")
	void fuera_del_alcance_es_404() {
		assertThat(handler.handleHistoriaNoAccesible(
				new HistoriaClinicaNotAccessibleException(88L)))
				.satisfies(problem -> {
					assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
					assertThat(problem.getType()).isEqualTo(ProblemType.NOT_FOUND.uri());
				});

		assertThat(esperar(handler.handleEntradaNoAccesible(
				new EntradaClinicaNotAccessibleException(312L)),
				HttpStatus.NOT_FOUND, ProblemType.ENTRADA_CLINICA_NO_ACCESIBLE)).isTrue();

		assertThat(esperar(handler.handleAdjuntoNoAccesible(
				new AdjuntoClinicoNotAccessibleException(17L)),
				HttpStatus.NOT_FOUND, ProblemType.ADJUNTO_CLINICO_NO_ACCESIBLE)).isTrue();

		assertThat(esperar(handler.handleAntecedenteNoAccesible(
				new AntecedenteNotAccessibleException(5L)),
				HttpStatus.NOT_FOUND, ProblemType.NOT_FOUND)).isTrue();
	}

	@Test
	@DisplayName("el acceso sin justificar es 403 y le dice a la pantalla que pida el motivo")
	void acceso_sin_justificar_es_403_accionable() {
		ProblemDetail problem = handler.handleAccesoNoJustificado(
				new AccesoClinicoNoJustificadoException(42L));

		assertThat(problem.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(problem.getType()).isEqualTo(ProblemType.FORBIDDEN.uri());
		assertThat(problem.getProperties())
				.containsEntry("requiereJustificacion", true)
				.containsEntry("cabecera", "X-Justificacion-Acceso");
		// El detalle no reproduce ningun dato clinico ni la justificacion de nadie.
		assertThat(problem.getDetail()).doesNotContain("42");
	}

	@Test
	@DisplayName("los estados que la operacion no admite son 409, no 404")
	void los_estados_son_409() {
		assertThat(esperar(handler.handleEntradaInactiva(
				new EntradaClinicaInactivaException(312L)),
				HttpStatus.CONFLICT, ProblemType.ENTRADA_CLINICA_INACTIVA)).isTrue();

		assertThat(esperar(handler.handleAdjuntoInactivo(
				new AdjuntoClinicoInactivoException(17L)),
				HttpStatus.CONFLICT, ProblemType.ADJUNTO_CLINICO_INACTIVO)).isTrue();

		assertThat(esperar(handler.handleAdjuntoNoDisponible(
				new AdjuntoClinicoNoDisponibleException(17L)),
				HttpStatus.CONFLICT, ProblemType.ADJUNTO_CLINICO_NO_DISPONIBLE)).isTrue();

		ProblemDetail sinPerfil = handler.handlePacienteSinPerfil(
				new PacienteSinPerfilVigenteException(42L));
		assertThat(esperar(sinPerfil,
				HttpStatus.CONFLICT, ProblemType.PERSONA_SIN_PERFIL_PACIENTE)).isTrue();
		assertThat(sinPerfil.getProperties()).containsEntry("personaId", 42L);
	}

	@Test
	@DisplayName("los datos del pedido son 400: reintentar lo mismo falla igual")
	void los_datos_del_pedido_son_400() {
		assertThat(esperar(handler.handleEnmiendaSinMotivo(
				new EnmiendaSinMotivoException(312L)),
				HttpStatus.BAD_REQUEST, ProblemType.ENMIENDA_SIN_MOTIVO)).isTrue();

		ProblemDetail cursor = handler.handleCursorInvalido(
				new CursorInvalidoException("no es base64"));
		assertThat(esperar(cursor, HttpStatus.BAD_REQUEST, ProblemType.CURSOR_INVALIDO)).isTrue();

		ProblemDetail archivo = handler.handleArchivoNoAceptado(
				new ArchivoClinicoNoAceptadoException("DEMASIADO_GRANDE", "Supera el tope."));
		assertThat(esperar(archivo,
				HttpStatus.BAD_REQUEST, ProblemType.ARCHIVO_NO_ACEPTADO)).isTrue();
		assertThat(archivo.getProperties()).containsEntry("motivo", "DEMASIADO_GRANDE");
	}

	private static boolean esperar(ProblemDetail problem, HttpStatus status, ProblemType type) {
		assertThat(problem.getStatus()).isEqualTo(status.value());
		assertThat(problem.getType()).isEqualTo(type.uri());
		assertThat(problem.getTitle()).isNotBlank();
		return true;
	}
}
