package com.akine.clinical.api;

import com.akine.clinical.api.dto.ActualizarResumenRequest;
import com.akine.clinical.api.dto.AntecedenteResponse;
import com.akine.clinical.api.dto.BajaDeAntecedenteRequest;
import com.akine.clinical.api.dto.HistoriaClinicaResponse;
import com.akine.clinical.api.dto.RegistrarAntecedenteRequest;
import com.akine.clinical.application.AntecedenteClinicoService;
import com.akine.clinical.application.AntecedenteView;
import com.akine.clinical.application.HistoriaClinicaService;
import com.akine.clinical.application.HistoriaClinicaView;
import com.akine.clinical.application.OperatingActor;
import com.akine.clinical.domain.TipoAntecedente;
import com.akine.clinical.domain.exception.HistoriaClinicaNotAccessibleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * La capa HTTP de la historia clinica por persona (C-2), sin levantar Spring.
 *
 * <p>Escrito contra el CONTRATO de la partichela de A2.T1, no contra la implementacion: el
 * controller lo escribe A2 en paralelo. Lo que se verifica son decisiones del borde —200 contra
 * 201, que la cabecera de justificacion llegue al servicio, que la {@code expectedVersion} y los
 * filtros viajen tal cual— y que los errores del servicio no se traguen en el controller.
 *
 * <p>Los nombres de los metodos del controller no estan en el contrato; se asumieron siguiendo el
 * vocabulario de {@code EntradaClinicaController}. Ver "Estado actual" de la partichela.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("API de la historia clinica por persona (C-2)")
class HistoriaClinicaControllerTest {

	private static final long PERSONA_ID = 55L;
	private static final long HISTORIA_ID = 88L;
	private static final long ANTECEDENTE_ID = 9L;
	private static final String MOTIVO = "El paciente llamo por el resultado";

	@Mock
	private HistoriaClinicaService historiaService;

	@Mock
	private AntecedenteClinicoService antecedenteService;

	@Mock
	private ClinicalApiActor apiActor;

	private HistoriaClinicaController controller;

	@BeforeEach
	void setUp() {
		controller = new HistoriaClinicaController(historiaService, antecedenteService, apiActor);
		given(apiActor.current()).willReturn(new OperatingActor(40L, false, 10L, 20L));
	}

	@Nested
	@DisplayName("Obtener o abrir")
	class ObtenerOAbrir {

		@Test
		@DisplayName("dos PUT seguidos devuelven la misma historia: es idempotente")
		void dos_put_devuelven_la_misma_historia() {
			// AC-1
			given(historiaService.abrirOObtener(any(), anyLong(), any()))
					.willReturn(unaHistoria(0L));

			HistoriaClinicaResponse primera = controller.abrirOObtener(PERSONA_ID, MOTIVO);
			HistoriaClinicaResponse segunda = controller.abrirOObtener(PERSONA_ID, MOTIVO);

			assertThat(primera.id()).isEqualTo(HISTORIA_ID);
			assertThat(segunda.id()).isEqualTo(primera.id());
			verify(historiaService, times(2))
					.abrirOObtener(any(), eq(PERSONA_ID), eq(MOTIVO));
		}

		@Test
		@DisplayName("la justificacion de la cabecera llega al servicio tal cual, y ausente es null")
		void la_justificacion_llega_al_servicio() {
			// AC-1
			given(historiaService.abrirOObtener(any(), anyLong(), any()))
					.willReturn(unaHistoria(0L));
			given(historiaService.ver(any(), anyLong(), any())).willReturn(unaHistoria(0L));

			controller.abrirOObtener(PERSONA_ID, MOTIVO);
			controller.ver(PERSONA_ID, null);

			verify(historiaService).abrirOObtener(any(), eq(PERSONA_ID), eq(MOTIVO));
			verify(historiaService).ver(any(), eq(PERSONA_ID), isNull());
		}

		@Test
		@DisplayName("el actor sale del contexto del request y no del cuerpo ni de la ruta")
		void el_actor_sale_del_contexto() {
			// AC-1
			given(historiaService.abrirOObtener(any(), anyLong(), any()))
					.willReturn(unaHistoria(0L));

			controller.abrirOObtener(PERSONA_ID, MOTIVO);

			verify(historiaService).abrirOObtener(
					eq(new OperatingActor(40L, false, 10L, 20L)), eq(PERSONA_ID), eq(MOTIVO));
		}
	}

	@Nested
	@DisplayName("Ver")
	class Ver {

		@Test
		@DisplayName("sin historia, el 404 del servicio sube sin que el controller lo convierta")
		void sin_historia_es_404() {
			// AC-1
			given(historiaService.ver(any(), anyLong(), any()))
					.willThrow(new HistoriaClinicaNotAccessibleException(PERSONA_ID));

			assertThatThrownBy(() -> controller.ver(PERSONA_ID, MOTIVO))
					.as("la traduccion a 404 la hace ClinicalProblemHandler: el controller no la traga")
					.isInstanceOf(HistoriaClinicaNotAccessibleException.class);
		}

		@Test
		@DisplayName("una persona de otra organizacion es indistinguible de una sin historia")
		void otra_organizacion_es_la_misma_excepcion() {
			// AC-1
			given(historiaService.ver(any(), eq(999L), any()))
					.willThrow(new HistoriaClinicaNotAccessibleException(999L));
			given(historiaService.abrirOObtener(any(), eq(999L), any()))
					.willThrow(new HistoriaClinicaNotAccessibleException(999L));

			assertThatThrownBy(() -> controller.ver(999L, MOTIVO))
					.isInstanceOf(HistoriaClinicaNotAccessibleException.class);
			assertThatThrownBy(() -> controller.abrirOObtener(999L, MOTIVO))
					.as("tampoco el PUT filtra la existencia de la persona")
					.isInstanceOf(HistoriaClinicaNotAccessibleException.class);
		}
	}

	@Nested
	@DisplayName("Resumen")
	class Resumen {

		@Test
		@DisplayName("el texto y la expectedVersion viajan al servicio sin tocarse")
		void el_resumen_manda_la_version_esperada() {
			// AC-1
			given(historiaService.actualizarResumen(any(), anyLong(), anyString(), anyLong(), any()))
					.willReturn(unaHistoria(4L));

			HistoriaClinicaResponse respuesta = controller.actualizarResumen(
					PERSONA_ID, new ActualizarResumenRequest("Lumbalgia cronica", 3L), MOTIVO);

			assertThat(respuesta.version()).isEqualTo(4L);
			verify(historiaService).actualizarResumen(
					any(), eq(PERSONA_ID), eq("Lumbalgia cronica"), eq(3L), eq(MOTIVO));
		}

		@Test
		@DisplayName("una expectedVersion desfasada sube como conflicto de bloqueo optimista")
		void version_desfasada_es_conflicto() {
			// AC-1
			given(historiaService.actualizarResumen(any(), anyLong(), anyString(), anyLong(), any()))
					.willThrow(new OptimisticLockingFailureException("cambio desde que se leyo"));

			assertThatThrownBy(() -> controller.actualizarResumen(
					PERSONA_ID, new ActualizarResumenRequest("Texto", 1L), MOTIVO))
					.isInstanceOf(OptimisticLockingFailureException.class);
		}
	}

	@Nested
	@DisplayName("Antecedentes")
	class Antecedentes {

		@Test
		@DisplayName("registrar responde 201 con el antecedente")
		void registrar_es_201() {
			// AC-1
			given(antecedenteService.registrar(any(), anyLong(), any(), anyString(), any()))
					.willReturn(unAntecedente(true));

			ResponseEntity<AntecedenteResponse> respuesta = controller.registrarAntecedente(
					PERSONA_ID,
					new RegistrarAntecedenteRequest(TipoAntecedente.ALERGIA, "Penicilina"),
					MOTIVO);

			assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
			assertThat(respuesta.getBody()).isNotNull();
			assertThat(respuesta.getBody().id()).isEqualTo(ANTECEDENTE_ID);
			verify(antecedenteService).registrar(
					any(), eq(PERSONA_ID), eq(TipoAntecedente.ALERGIA), eq("Penicilina"), eq(MOTIVO));
		}

		@Test
		@DisplayName("listar pasa tipo y soloVigentes al servicio, y sin tipo es null")
		void listar_pasa_los_filtros() {
			// AC-1
			given(antecedenteService.listar(any(), anyLong(), any(), anyBoolean(), any()))
					.willReturn(List.of(unAntecedente(true)));

			List<AntecedenteResponse> filtrados = controller.listarAntecedentes(
					PERSONA_ID, TipoAntecedente.ALERGIA, true, MOTIVO);
			controller.listarAntecedentes(PERSONA_ID, null, false, MOTIVO);

			assertThat(filtrados).hasSize(1);
			verify(antecedenteService).listar(
					any(), eq(PERSONA_ID), eq(TipoAntecedente.ALERGIA), eq(true), eq(MOTIVO));
			verify(antecedenteService).listar(
					any(), eq(PERSONA_ID), isNull(), eq(false), eq(MOTIVO));
		}

		@Test
		@DisplayName("la baja repetida es el mismo pedido: 200 con el motivo original")
		void baja_repetida_devuelve_lo_mismo() {
			// AC-1
			given(antecedenteService.darDeBaja(any(), anyLong(), anyLong(), anyString(), any()))
					.willReturn(unAntecedente(false));

			AntecedenteResponse primera = controller.darDeBajaAntecedente(
					PERSONA_ID, ANTECEDENTE_ID, new BajaDeAntecedenteRequest("Ya no aplica"), MOTIVO);
			AntecedenteResponse repetida = controller.darDeBajaAntecedente(
					PERSONA_ID, ANTECEDENTE_ID, new BajaDeAntecedenteRequest("Otro motivo"), MOTIVO);

			assertThat(primera.vigente()).isFalse();
			assertThat(repetida).isEqualTo(primera);
			assertThat(repetida.deactivationReason()).isEqualTo("Ya no aplica");
			verify(antecedenteService).darDeBaja(
					any(), eq(PERSONA_ID), eq(ANTECEDENTE_ID), eq("Ya no aplica"), eq(MOTIVO));
		}
	}

	// =================================================================================
	// Fixtures sinteticas
	// =================================================================================

	private static HistoriaClinicaView unaHistoria(long version) {
		return new HistoriaClinicaView(
				HISTORIA_ID, 10L, PERSONA_ID, "Paciente Sintetico", "DNI 30111222",
				Instant.EPOCH, 8L, null, null, null,
				List.of(), List.of(), false, version);
	}

	private static AntecedenteView unAntecedente(boolean vigente) {
		return new AntecedenteView(
				ANTECEDENTE_ID, "ALERGIA", "Penicilina", Instant.EPOCH, 8L, vigente,
				vigente ? null : Instant.EPOCH,
				vigente ? null : "Ya no aplica",
				0L);
	}
}
