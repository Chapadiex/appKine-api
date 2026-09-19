package com.akine.clinical.api;

import com.akine.clinical.api.dto.AdjuntoClinicoPageResponse;
import com.akine.clinical.api.dto.AdjuntoClinicoResponse;
import com.akine.clinical.api.dto.BajaDeAdjuntoClinicoRequest;
import com.akine.clinical.api.dto.BajaDeEntradaClinicaRequest;
import com.akine.clinical.api.dto.EnmendarEntradaClinicaRequest;
import com.akine.clinical.api.dto.EntradaClinicaResponse;
import com.akine.clinical.api.dto.EntradaClinicaVersionResponse;
import com.akine.clinical.api.dto.RegistrarEntradaClinicaRequest;
import com.akine.clinical.api.dto.TimelineResponse;
import com.akine.clinical.application.AdjuntoClinicoAltaCommand;
import com.akine.clinical.application.AdjuntoClinicoService;
import com.akine.clinical.application.AdjuntoClinicoService.AdjuntoClinicoAlta;
import com.akine.clinical.application.AdjuntoClinicoService.AdjuntoClinicoPagina;
import com.akine.clinical.application.AdjuntoClinicoView;
import com.akine.clinical.application.ContenidoDeAdjuntoClinico;
import com.akine.clinical.application.EntradaClinicaService;
import com.akine.clinical.application.EntradaClinicaVersionView;
import com.akine.clinical.application.EntradaClinicaView;
import com.akine.clinical.application.OperatingActor;
import com.akine.clinical.application.TimelinePagina;
import com.akine.clinical.application.TimelineService;
import com.akine.clinical.domain.CategoriaAdjuntoClinico;
import com.akine.clinical.domain.TipoEntradaClinica;
import com.akine.clinical.spi.EventoClinico;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * La capa HTTP de {@code clinical}, sin levantar Spring.
 *
 * <p>Los controllers se instancian a mano: lo que hay que verificar son <b>decisiones suyas</b>
 * que ningun test de servicio alcanza —el 201 contra el 200 de la subida idempotente, el saneado
 * del nombre de archivo, las dos cabeceras de seguridad de la descarga, el recorte del tamano de
 * pagina y, sobre todo, que la justificacion de acceso llegue al servicio— y ninguna necesita el
 * contenedor.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Capa api de clinical (AKINE-04.02)")
class ClinicalApiTest {

	private static final long HISTORIA_ID = 88L;
	private static final long ENTRADA_ID = 312L;
	private static final long ADJUNTO_ID = 17L;
	private static final String MOTIVO = "El paciente llamo por el resultado";

	private static final byte[] UN_PDF = "%PDF-1.7 x".getBytes(StandardCharsets.US_ASCII);

	@Mock
	private TimelineService timelineService;

	@Mock
	private EntradaClinicaService entradaService;

	@Mock
	private AdjuntoClinicoService adjuntoService;

	@Mock
	private ClinicalApiActor apiActor;

	private TimelineController timeline;
	private EntradaClinicaController entradas;
	private AdjuntoClinicoController adjuntos;

	@BeforeEach
	void setUp() {
		timeline = new TimelineController(timelineService, apiActor);
		entradas = new EntradaClinicaController(entradaService, apiActor);
		adjuntos = new AdjuntoClinicoController(adjuntoService, apiActor);
		given(apiActor.current()).willReturn(new OperatingActor(40L, false, 10L, 20L));
	}

	@Nested
	@DisplayName("Timeline")
	class Timeline {

		@Test
		@DisplayName("un proximoCursor nulo significa fin de la linea, no error")
		void sin_proximo_cursor_es_fin() {
			given(timelineService.ver(any(), anyLong(), any(), any(), any()))
					.willReturn(new TimelinePagina(List.of(unEvento()), null));

			TimelineResponse respuesta =
					timeline.ver(HISTORIA_ID, null, null, MOTIVO);

			assertThat(respuesta.proximoCursor()).isNull();
			assertThat(respuesta.eventos()).singleElement()
					.satisfies(evento -> {
						assertThat(evento.origen()).isEqualTo("ENTRADA_CLINICA");
						assertThat(evento.referencia()).isEqualTo(ENTRADA_ID);
					});
		}

		@Test
		@DisplayName("la justificacion llega desde la cabecera y no desde la query string")
		void la_justificacion_viaja_por_cabecera() {
			given(timelineService.ver(any(), anyLong(), any(), any(), any()))
					.willReturn(new TimelinePagina(List.of(), null));

			timeline.ver(HISTORIA_ID, "cursor-opaco", 500, MOTIVO);

			verify(timelineService).ver(
					any(), eq(HISTORIA_ID), eq("cursor-opaco"), eq(500), eq(MOTIVO));
		}
	}

	@Nested
	@DisplayName("Entradas clinicas")
	class Entradas {

		@Test
		@DisplayName("un alta responde 201 con Location a la ruta plana de la entrada")
		void alta_es_201_con_location() {
			given(entradaService.registrar(any(), anyLong(), any(), anyString(), any(), any()))
					.willReturn(unaEntrada(true));

			ResponseEntity<EntradaClinicaResponse> respuesta = entradas.registrar(
					HISTORIA_ID,
					new RegistrarEntradaClinicaRequest(
							TipoEntradaClinica.EVOLUCION, "Dolor 4/10", null),
					MOTIVO);

			assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
			assertThat(respuesta.getHeaders().getLocation())
					.hasToString("/api/v1/entradas-clinicas/" + ENTRADA_ID);
			assertThat(respuesta.getBody()).isNotNull();
			assertThat(respuesta.getBody().numeroVersion()).isEqualTo(1);
		}

		@Test
		@DisplayName("incluirDadasDeBaja=false se traduce a soloVigentes=true")
		void el_filtro_se_invierte_una_sola_vez() {
			given(entradaService.listar(any(), anyLong(), anyBoolean(), any()))
					.willReturn(List.of(unaEntrada(true)));

			entradas.listar(HISTORIA_ID, false, MOTIVO);
			entradas.listar(HISTORIA_ID, true, MOTIVO);

			verify(entradaService).listar(any(), eq(HISTORIA_ID), eq(true), eq(MOTIVO));
			verify(entradaService).listar(any(), eq(HISTORIA_ID), eq(false), eq(MOTIVO));
		}

		@Test
		@DisplayName("la enmienda manda la version de la CABECERA, no el numero de contenido")
		void la_enmienda_manda_la_version_de_la_cabecera() {
			given(entradaService.enmendar(
					any(), anyLong(), anyString(), anyString(), anyLong(), any()))
					.willReturn(unaEntrada(true));

			entradas.enmendar(
					ENTRADA_ID,
					new EnmendarEntradaClinicaRequest("Texto corregido", "Lateralidad", 3L),
					MOTIVO);

			verify(entradaService).enmendar(
					any(), eq(ENTRADA_ID), eq("Texto corregido"), eq("Lateralidad"), eq(3L),
					eq(MOTIVO));
		}

		@Test
		@DisplayName("la baja responde 200 con la entrada, no 204")
		void la_baja_devuelve_la_entrada() {
			given(entradaService.darDeBaja(any(), anyLong(), anyString(), anyLong(), any()))
					.willReturn(unaEntrada(false));

			EntradaClinicaResponse respuesta = entradas.darDeBaja(
					ENTRADA_ID, new BajaDeEntradaClinicaRequest("Historia equivocada", 1L), MOTIVO);

			assertThat(respuesta.vigente()).isFalse();
			assertThat(respuesta.deactivationReason()).isEqualTo("Historia equivocada");
		}

		@Test
		@DisplayName("el historico sale completo, de la mas nueva a la mas vieja")
		void el_historico_sale_completo() {
			given(entradaService.versiones(any(), anyLong(), any())).willReturn(List.of(
					new EntradaClinicaVersionView(2, "Corregido", "Lateralidad", Instant.EPOCH, 8L),
					new EntradaClinicaVersionView(1, "Original", null, Instant.EPOCH, 8L)));

			List<EntradaClinicaVersionResponse> versiones =
					entradas.versiones(ENTRADA_ID, MOTIVO);

			assertThat(versiones).extracting(EntradaClinicaVersionResponse::numeroVersion)
					.containsExactly(2, 1);
			assertThat(versiones.get(1).motivoEnmienda()).isNull();
		}
	}

	@Nested
	@DisplayName("Adjuntos clinicos")
	class Adjuntos {

		@Test
		@DisplayName("una subida nueva responde 201 y una repetida 200: la subida es idempotente")
		void subida_nueva_201_repetida_200() {
			given(adjuntoService.subir(any(), anyLong(), any(), any()))
					.willReturn(new AdjuntoClinicoAlta(unAdjunto(true), true));
			ResponseEntity<AdjuntoClinicoResponse> primera = subir("rmn.pdf");

			given(adjuntoService.subir(any(), anyLong(), any(), any()))
					.willReturn(new AdjuntoClinicoAlta(unAdjunto(true), false));
			ResponseEntity<AdjuntoClinicoResponse> segunda = subir("rmn.pdf");

			assertThat(primera.getStatusCode()).isEqualTo(HttpStatus.CREATED);
			assertThat(primera.getHeaders().getLocation())
					.hasToString("/api/v1/adjuntos-clinicos/" + ADJUNTO_ID);
			assertThat(segunda.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(segunda.getHeaders().getLocation()).isNull();
		}

		@Test
		@DisplayName("el nombre del archivo se sanea antes de llegar al servicio")
		void el_nombre_se_sanea() {
			given(adjuntoService.subir(any(), anyLong(), any(), any()))
					.willReturn(new AdjuntoClinicoAlta(unAdjunto(true), true));

			subir("../../etc/pas\"swd\n.pdf");

			ArgumentCaptor<AdjuntoClinicoAltaCommand> comando =
					ArgumentCaptor.forClass(AdjuntoClinicoAltaCommand.class);
			verify(adjuntoService).subir(any(), eq(HISTORIA_ID), comando.capture(), eq(MOTIVO));
			assertThat(comando.getValue().nombreArchivo())
					.doesNotContain("/", "\\", "\"", "\n")
					.isEqualTo("pas_swd_.pdf");
		}

		@Test
		@DisplayName("la descarga va como attachment, con nosniff, y sin filtrar la storageKey")
		void la_descarga_lleva_las_dos_cabeceras() {
			given(adjuntoService.contenido(any(), anyLong(), anyLong(), any())).willReturn(
					new ContenidoDeAdjuntoClinico("rmn.pdf", "application/pdf", UN_PDF));

			ResponseEntity<byte[]> respuesta =
					adjuntos.descargar(ADJUNTO_ID, HISTORIA_ID, MOTIVO);

			HttpHeaders cabeceras = respuesta.getHeaders();
			assertThat(cabeceras.getFirst(HttpHeaders.CONTENT_DISPOSITION))
					.startsWith("attachment")
					.contains("rmn.pdf");
			assertThat(cabeceras.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
			assertThat(respuesta.getBody()).isEqualTo(UN_PDF);
			// La clave de almacenamiento no sale por ningun campo (RN-M25-002).
			assertThat(cabeceras.toString()).doesNotContain("storage");
		}

		@Test
		@DisplayName("las tres rutas planas exigen la historia: es la ficha que autoriza")
		void las_rutas_planas_exigen_la_historia() {
			given(adjuntoService.contenido(any(), anyLong(), anyLong(), any())).willReturn(
					new ContenidoDeAdjuntoClinico("rmn.pdf", "application/pdf", UN_PDF));
			given(adjuntoService.reclasificar(any(), anyLong(), anyLong(), any(), any(), any()))
					.willReturn(unAdjunto(true));
			given(adjuntoService.darDeBaja(any(), anyLong(), anyLong(), anyString(), any()))
					.willReturn(unAdjunto(false));

			adjuntos.descargar(ADJUNTO_ID, HISTORIA_ID, MOTIVO);
			adjuntos.reclasificar(ADJUNTO_ID, HISTORIA_ID,
					new com.akine.clinical.api.dto.ReclasificarAdjuntoClinicoRequest(
							CategoriaAdjuntoClinico.INFORME, "Informe de RMN"),
					MOTIVO);
			adjuntos.darDeBaja(ADJUNTO_ID, HISTORIA_ID,
					new BajaDeAdjuntoClinicoRequest("Historia equivocada"), MOTIVO);

			verify(adjuntoService).contenido(any(), eq(HISTORIA_ID), eq(ADJUNTO_ID), eq(MOTIVO));
			verify(adjuntoService).reclasificar(any(), eq(HISTORIA_ID), eq(ADJUNTO_ID),
					eq(CategoriaAdjuntoClinico.INFORME), eq("Informe de RMN"), eq(MOTIVO));
			verify(adjuntoService).darDeBaja(any(), eq(HISTORIA_ID), eq(ADJUNTO_ID),
					eq("Historia equivocada"), eq(MOTIVO));
		}

		@Test
		@DisplayName("el tamano de pagina lo decide el servidor: se acota a 100 y nunca es 0")
		void el_tamano_de_pagina_se_acota() {
			given(adjuntoService.listar(any(), anyLong(), any(), any(), anyBoolean(), anyInt(),
					anyInt(), any()))
					.willReturn(new AdjuntoClinicoPagina(List.of(unAdjunto(true)), 1));

			AdjuntoClinicoPageResponse enorme =
					adjuntos.listar(HISTORIA_ID, null, null, false, -3, 100000, MOTIVO);

			assertThat(enorme.size()).isEqualTo(100);
			assertThat(enorme.page()).isZero();
			verify(adjuntoService).listar(any(), eq(HISTORIA_ID), isNull(), isNull(), eq(false),
					eq(0), eq(100), eq(MOTIVO));
		}
	}

	// =================================================================================
	// Fixtures sinteticas
	// =================================================================================

	private ResponseEntity<AdjuntoClinicoResponse> subir(String nombre) {
		return adjuntos.subir(
				HISTORIA_ID,
				new MockMultipartFile("archivo", nombre, "application/pdf", UN_PDF),
				CategoriaAdjuntoClinico.ESTUDIO,
				null,
				"RMN de rodilla",
				MOTIVO);
	}

	private static EventoClinico unEvento() {
		return new EventoClinico(
				Instant.EPOCH, "ENTRADA_CLINICA", "EVOLUCION", "Evolucion", ENTRADA_ID);
	}

	private static EntradaClinicaView unaEntrada(boolean vigente) {
		return new EntradaClinicaView(
				ENTRADA_ID, HISTORIA_ID, "EVOLUCION", "MANUAL", null,
				Instant.EPOCH, Instant.EPOCH, 8L,
				1, "Dolor 4/10", null, Instant.EPOCH, 8L,
				false, vigente,
				vigente ? null : Instant.EPOCH,
				vigente ? null : "Historia equivocada",
				1L);
	}

	private static AdjuntoClinicoView unAdjunto(boolean vigente) {
		return new AdjuntoClinicoView(
				ADJUNTO_ID, HISTORIA_ID, null, 20L, "ESTUDIO", "RMN de rodilla", "rmn.pdf",
				"application/pdf", UN_PDF.length, "abc123", "DISPONIBLE", 8L, Instant.EPOCH,
				vigente ? "ACTIVO" : "INACTIVO",
				vigente ? null : Instant.EPOCH,
				vigente ? null : "Historia equivocada",
				0L);
	}
}
