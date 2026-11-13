package com.akine.knowledge.api;

import com.akine.knowledge.application.LibroService;
import com.akine.knowledge.application.LibroView;
import com.akine.knowledge.domain.exception.BibliotecaDeshabilitadaException;
import com.akine.knowledge.domain.exception.LibroArchivoNoAceptadoException;
import com.akine.knowledge.domain.exception.LibroArchivoNoAceptadoException.Causa;
import com.akine.knowledge.domain.exception.LibroNoEncontradoException;
import com.akine.platform.spi.tenant.AuthenticatedPrincipal;
import com.akine.platform.spi.tenant.RequestTenantContext;
import com.akine.platform.spi.tenant.TenantContextHolder;
import com.akine.platform.spi.tenant.TenantOperationalStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(LibroController.class)
@Import({KnowledgeApiSliceSecurityConfig.class, KnowledgeApiActor.class})
@DisplayName("LibroController - borde HTTP del alta")
class LibroControllerTest {

	private static final String RUTA = "/api/v1/organizations/1/biblioteca/libros";

	@Autowired private MockMvc mockMvc;
	@MockitoBean private LibroService libroService;
	@MockitoBean private TenantContextHolder tenantContextHolder;

	@BeforeEach
	void setUp() {
		given(tenantContextHolder.current()).willReturn(Optional.of(new RequestTenantContext(
				99L, 1L, 10L, "ORG_ADMIN", TenantOperationalStatus.ACTIVA)));
	}

	private MockMultipartHttpServletRequestBuilder alta(String declaracion) {
		MockMultipartHttpServletRequestBuilder pedido = multipart(RUTA)
				.file(new MockMultipartFile("archivo", "a.pdf", "application/pdf", "%PDF-1.7 x".getBytes()))
				.param("titulo", "Kinesiologia")
				.param("declaracionDerechos", declaracion);
		return pedido;
	}

	private record PrincipalDePrueba(long accountId) implements AuthenticatedPrincipal {
		@Override public boolean platformAdmin() { return false; }
		@Override public Long organizationId() { return 1L; }
		@Override public Long consultorioId() { return 10L; }
	}

	private void falla(Throwable error) {
		org.mockito.Mockito.reset(libroService);
		org.mockito.Mockito.doThrow(error).when(libroService)
				.subir(any(), anyLong(), any(), any(), anyBoolean());
	}

	private static org.springframework.test.web.servlet.request.RequestPostProcessor sesion() {
		return authentication(new UsernamePasswordAuthenticationToken(
				new PrincipalDePrueba(99L), "n/a", java.util.List.of()));
	}

	@Test
	@DisplayName("el alta responde 202 con el libro PENDIENTE")
	void alta_es_202() throws Exception {
		LibroView libro = new LibroView(7L, "Kinesiologia", null, null, null, null,
				com.akine.knowledge.domain.EstadoVisibleLibro.PENDIENTE, 0, null, null, 0L);
		given(libroService.subir(any(), anyLong(), any(), any(), anyBoolean())).willReturn(libro);

		mockMvc.perform(alta("true").with(sesion()))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.estado").value("PENDIENTE"))
				.andExpect(jsonPath("$.titulo").value("Kinesiologia"));
	}

	@Test
	@DisplayName("un archivo que no es PDF sale 400 con causa NO_ES_PDF")
	void no_es_pdf_es_400() throws Exception {
		falla(new LibroArchivoNoAceptadoException(Causa.NO_ES_PDF));

		mockMvc.perform(alta("true").with(sesion()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.causa").value("NO_ES_PDF"));
	}

	@Test
	@DisplayName("sin declaracion de derechos sale 400")
	void sin_declaracion_es_400() throws Exception {
		falla(new IllegalArgumentException("Hay que declarar"));

		mockMvc.perform(alta("false").with(sesion()))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("otra organizacion es 404, sin permiso 403 e interruptor apagado 409")
	void errores_de_acceso() throws Exception {
		falla(new LibroNoEncontradoException());
		mockMvc.perform(alta("true").with(sesion())).andExpect(status().isNotFound());

		falla(new AccessDeniedException("no"));
		mockMvc.perform(alta("true").with(sesion())).andExpect(status().isForbidden());

		falla(new BibliotecaDeshabilitadaException());
		mockMvc.perform(alta("true").with(sesion())).andExpect(status().isConflict());
	}
}
