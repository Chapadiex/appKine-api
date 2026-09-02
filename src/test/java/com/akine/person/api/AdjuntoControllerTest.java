package com.akine.person.api;

import com.akine.person.api.dto.AdjuntoPageResponse;
import com.akine.person.api.dto.AdjuntoResponse;
import com.akine.person.api.dto.BajaDeAdjuntoRequest;
import com.akine.person.api.dto.ClasificarAdjuntoRequest;
import com.akine.person.application.AdjuntoAltaCommand;
import com.akine.person.application.AdjuntoService;
import com.akine.person.application.AdjuntoService.AdjuntoAlta;
import com.akine.person.application.AdjuntoService.AdjuntoPagina;
import com.akine.person.application.AdjuntoView;
import com.akine.person.application.ContenidoDeAdjunto;
import com.akine.person.application.OperatingActor;
import com.akine.person.domain.CategoriaAdjunto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * La capa HTTP de los adjuntos, sin levantar Spring.
 *
 * <p>Se instancia el controller a mano: lo que hay que verificar son <b>decisiones suyas</b> que
 * ningun test de servicio alcanza —el 201 contra el 200 de la subida idempotente, el saneado del
 * nombre de archivo y las dos cabeceras de seguridad de la descarga— y ninguna necesita el
 * contenedor.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AdjuntoController")
class AdjuntoControllerTest {

	private static final long PERSONA_ID = 42L;
	private static final long ADJUNTO_ID = 17L;

	private static final byte[] UN_PDF = "%PDF-1.7 x".getBytes(StandardCharsets.US_ASCII);

	@Mock
	private AdjuntoService adjuntoService;

	@Mock
	private PersonApiActor apiActor;

	private AdjuntoController controller;

	@BeforeEach
	void setUp() {
		controller = new AdjuntoController(adjuntoService, apiActor);
		given(apiActor.current()).willReturn(new OperatingActor(40L, false, 10L, 20L));
	}

	@Test
	@DisplayName("una subida nueva responde 201 con Location")
	void subida_nueva_es_201() {
		given(adjuntoService.subir(any(), anyLong(), any()))
				.willReturn(new AdjuntoAlta(unaVista(), true));

		ResponseEntity<AdjuntoResponse> respuesta = controller.subir(
				PERSONA_ID, archivo("dni.pdf"), CategoriaAdjunto.DOCUMENTO_IDENTIDAD, "DNI");

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(respuesta.getHeaders().getLocation())
				.hasToString("/api/v1/personas/42/adjuntos/17");
	}

	@Test
	@DisplayName("un reintento del mismo archivo responde 200 y SIN Location")
	void reintento_es_200() {
		given(adjuntoService.subir(any(), anyLong(), any()))
				.willReturn(new AdjuntoAlta(unaVista(), false));

		ResponseEntity<AdjuntoResponse> respuesta = controller.subir(
				PERSONA_ID, archivo("dni.pdf"), CategoriaAdjunto.OTRO, null);

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(respuesta.getHeaders().getLocation()).isNull();
	}

	@Test
	@DisplayName("el nombre del archivo se sanea: se queda el ultimo segmento y nada raro")
	void el_nombre_se_sanea() {
		given(adjuntoService.subir(any(), anyLong(), any()))
				.willReturn(new AdjuntoAlta(unaVista(), true));

		// Directorios en las dos formas, y un nombre con comillas y salto de linea en el ultimo
		// segmento, que es lo unico que sobrevive al recorte.
		controller.subir(PERSONA_ID, archivo("..\\..\\Windows\\system32/dn\"i\r\n frente.pdf"),
				CategoriaAdjunto.OTRO, null);

		ArgumentCaptor<AdjuntoAltaCommand> comando =
				ArgumentCaptor.forClass(AdjuntoAltaCommand.class);
		verify(adjuntoService).subir(any(), anyLong(), comando.capture());

		// El saneado NO es lo que impide el path traversal —eso lo garantiza que la ruta en disco
		// se componga solo con la storageKey del servidor—: evita que un nombre con comillas o
		// saltos de linea rompa la cabecera Content-Disposition.
		assertThat(comando.getValue().nombreArchivo()).isEqualTo("dn_i__ frente.pdf");
	}

	@Test
	@DisplayName("un nombre vacio o irrecuperable cae en un default, no en null")
	void el_nombre_vacio_tiene_default() {
		given(adjuntoService.subir(any(), anyLong(), any()))
				.willReturn(new AdjuntoAlta(unaVista(), true));

		controller.subir(PERSONA_ID,
				new MockMultipartFile("archivo", "", "application/pdf", UN_PDF),
				CategoriaAdjunto.OTRO, null);
		controller.subir(PERSONA_ID, archivo("///"), CategoriaAdjunto.OTRO, null);

		ArgumentCaptor<AdjuntoAltaCommand> comando =
				ArgumentCaptor.forClass(AdjuntoAltaCommand.class);
		verify(adjuntoService, org.mockito.Mockito.times(2))
				.subir(any(), anyLong(), comando.capture());

		assertThat(comando.getAllValues())
				.allSatisfy(c -> assertThat(c.nombreArchivo()).isEqualTo("adjunto"));
	}

	@Test
	@DisplayName("la descarga va como attachment y con nosniff")
	void la_descarga_lleva_sus_cabeceras() {
		given(adjuntoService.contenido(any(), anyLong(), anyLong()))
				.willReturn(new ContenidoDeAdjunto("dni.pdf", "application/pdf", UN_PDF));

		ResponseEntity<byte[]> respuesta = controller.descargar(PERSONA_ID, ADJUNTO_ID);

		assertThat(respuesta.getBody()).isEqualTo(UN_PDF);
		assertThat(respuesta.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
				.startsWith("attachment")
				.contains("dni.pdf");
		// Sin nosniff, el navegador puede ignorar el tipo que declaramos y adivinar uno
		// ejecutable: la deteccion del servidor se saltearia del lado del cliente.
		assertThat(respuesta.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
	}

	@Test
	@DisplayName("el listado acota el tamano de pagina y no acepta pagina negativa")
	void el_listado_acota_la_paginacion() {
		given(adjuntoService.listar(any(), anyLong(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
				org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
				.willReturn(new AdjuntoPagina(List.of(unaVista()), 1L));

		AdjuntoPageResponse pagina = controller.listar(PERSONA_ID, null, false, -5, 5000);

		assertThat(pagina.page()).isZero();
		assertThat(pagina.size()).isEqualTo(100);
		assertThat(pagina.totalElements()).isEqualTo(1L);
		assertThat(pagina.content()).hasSize(1);
	}

	@Test
	@DisplayName("reclasificar y dar de baja devuelven la vista actualizada")
	void reclasificar_y_dar_de_baja() {
		given(adjuntoService.reclasificar(any(), anyLong(), anyLong(), any(), any()))
				.willReturn(unaVista());
		given(adjuntoService.darDeBaja(any(), anyLong(), anyLong(), any())).willReturn(unaVista());

		assertThat(controller.clasificar(PERSONA_ID, ADJUNTO_ID,
				new ClasificarAdjuntoRequest(CategoriaAdjunto.OTRO, "t")).id())
				.isEqualTo(ADJUNTO_ID);
		assertThat(controller.darDeBajaAdjunto(PERSONA_ID, ADJUNTO_ID,
				new BajaDeAdjuntoRequest("vencida")).id())
				.isEqualTo(ADJUNTO_ID);
	}

	private static MockMultipartFile archivo(String nombre) {
		return new MockMultipartFile("archivo", nombre, "application/pdf", UN_PDF);
	}

	private static AdjuntoView unaVista() {
		return new AdjuntoView(ADJUNTO_ID, PERSONA_ID, 20L, "OTRO", "titulo", "dni.pdf",
				"application/pdf", UN_PDF.length, "a".repeat(64), "DISPONIBLE", 40L,
				Instant.now(), "ACTIVO", null, null, 0L);
	}
}
