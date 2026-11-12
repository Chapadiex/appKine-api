package com.akine.organization.api;

import com.akine.organization.application.AuthorizationGuard;
import com.akine.organization.application.CampoInvalidoException;
import com.akine.organization.application.LogoContenido;
import com.akine.organization.application.MarcaCommand;
import com.akine.organization.application.MarcaService;
import com.akine.organization.application.MarcaView;
import com.akine.organization.domain.LogoVariante;
import com.akine.organization.domain.exception.OrganizationNotFoundException;
import com.akine.platform.spi.tenant.TenantContextHolder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Capa HTTP de la marca del centro: quien puede leer y quien editar, la forma de los datos y que
 * el logo viaje con el tipo que decidio el servidor.
 */
@WebMvcTest(MarcaController.class)
@Import(ApiSliceSecurityConfig.class)
class MarcaControllerTest {

	private static final String URL = "/api/v1/organizations/7/marca";
	private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2};

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private MarcaService marcaService;

	@MockitoBean
	private AuthorizationGuard authorizationGuard;

	@MockitoBean
	private TenantContextHolder tenantContextHolder;

	@Test
	@DisplayName("Leer la marca alcanza con ser miembro, y un color ausente no viaja")
	void lectura_para_cualquier_miembro() throws Exception {
		given(marcaService.ver(7L)).willReturn(vista(3L, "#0f766e", new MarcaView.Logo("image/png", "abc")));

		mockMvc.perform(get(URL).with(ApiActors.miembro(42L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.organizationId").value(7))
				.andExpect(jsonPath("$.version").value(3))
				.andExpect(jsonPath("$.colorPrincipalClaro").value("#0f766e"))
				.andExpect(jsonPath("$.colorAcentoClaro").doesNotExist())
				.andExpect(jsonPath("$.logoClaro.sha256").value("abc"))
				.andExpect(jsonPath("$.logoOscuro").doesNotExist());

		verify(authorizationGuard).requireMember(eq(42L), eq(7L), any(), anyBoolean());
	}

	@Test
	@DisplayName("La marca de otro tenant responde 404, igual que una inexistente")
	void lectura_de_otro_tenant_es_404() throws Exception {
		willThrow(new OrganizationNotFoundException(7L))
				.given(authorizationGuard).requireMember(anyLong(), anyLong(), any(), anyBoolean());

		mockMvc.perform(get(URL).with(ApiActors.miembro(42L))).andExpect(status().isNotFound());
		verify(marcaService, never()).ver(anyLong());
	}

	@Test
	@DisplayName("Editar pasa el reemplazo completo, la version y el actor")
	void edicion_reemplaza_con_version_y_actor() throws Exception {
		given(marcaService.actualizar(eq(7L), any(), eq(3L), eq(42L))).willReturn(vista(4L, "#0f766e", null));

		mockMvc.perform(put(URL).with(ApiActors.miembro(42L))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"version":3,"colorPrincipalClaro":"#0f766e","colorAcentoOscuro":"",
								 "telefono":"+54 351 555-0100","email":"contacto@centro.example",
								 "sitioWeb":"https://centro.example","leyenda":"Lunes a viernes"}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.version").value(4));

		verify(authorizationGuard).requireOrgDataEditor(eq(42L), eq(7L), any(), anyBoolean());
		verify(marcaService).actualizar(7L, new MarcaCommand(
				"#0f766e", null, null, "", "+54 351 555-0100", "contacto@centro.example",
				"https://centro.example", null, "Lunes a viernes"), 3L, 42L);
	}

	@Test
	@DisplayName("Color, email y sitio con mala forma, o sin version, son 400 con el campo")
	void edicion_con_campos_invalidos_es_400() throws Exception {
		mockMvc.perform(put(URL).with(ApiActors.miembro(42L))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"colorPrincipalClaro":"rojo","email":"sin-arroba","sitioWeb":"centro.example"}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.colorPrincipalClaro").exists())
				.andExpect(jsonPath("$.errors.email").exists())
				.andExpect(jsonPath("$.errors.sitioWeb").exists())
				.andExpect(jsonPath("$.errors.version").exists());

		verify(marcaService, never()).actualizar(anyLong(), any(), anyLong(), any());
	}

	@Test
	@DisplayName("Sin tenant:update editar es 403, y la version vieja es 409")
	void edicion_sin_permiso_y_con_version_vieja() throws Exception {
		String cuerpo = "{\"version\":3}";
		given(marcaService.actualizar(anyLong(), any(), anyLong(), any()))
				.willThrow(new OptimisticLockingFailureException("vieja"));
		mockMvc.perform(put(URL).with(ApiActors.miembro(42L))
						.contentType(MediaType.APPLICATION_JSON).content(cuerpo))
				.andExpect(status().isConflict());

		willThrow(new AccessDeniedException("solo el ORG_ADMIN"))
				.given(authorizationGuard).requireOrgDataEditor(anyLong(), anyLong(), any(), anyBoolean());
		mockMvc.perform(put(URL).with(ApiActors.miembro(42L))
						.contentType(MediaType.APPLICATION_JSON).content(cuerpo))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("El logo se sirve con el tipo que decidio el servidor, nosniff y su huella")
	void logo_se_sirve_con_su_tipo() throws Exception {
		given(marcaService.logo(7L, LogoVariante.OSCURO)).willReturn(new LogoContenido("image/png", PNG, "abc123"));

		mockMvc.perform(get(URL + "/logo/OSCURO").with(ApiActors.miembro(42L)))
				.andExpect(status().isOk())
				.andExpect(content().contentType(MediaType.IMAGE_PNG))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(header().string("ETag", "\"abc123\""))
				.andExpect(content().bytes(PNG));
	}

	@Test
	@DisplayName("Sin logo para ese tema es 404")
	void logo_ausente_es_404() throws Exception {
		given(marcaService.logo(7L, LogoVariante.CLARO)).willThrow(new OrganizationNotFoundException(7L));

		mockMvc.perform(get(URL + "/logo/CLARO").with(ApiActors.miembro(42L)))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("Subir un logo entrega los bytes tal cual, y un archivo no admitido es 400 con errors.logo")
	void subida_de_logo() throws Exception {
		given(marcaService.subirLogo(7L, LogoVariante.CLARO, PNG, 42L))
				.willReturn(vista(3L, null, new MarcaView.Logo("image/png", "abc")));

		mockMvc.perform(put(URL + "/logo/CLARO").with(ApiActors.miembro(42L))
						.contentType(MediaType.IMAGE_PNG).content(PNG))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.logoClaro.contentType").value("image/png"));
		verify(authorizationGuard).requireOrgDataEditor(eq(42L), eq(7L), any(), anyBoolean());

		given(marcaService.subirLogo(eq(7L), eq(LogoVariante.OSCURO), any(), eq(42L)))
				.willThrow(new CampoInvalidoException("logo", "El logo tiene que ser una imagen PNG, JPG o WebP"));
		mockMvc.perform(put(URL + "/logo/OSCURO").with(ApiActors.miembro(42L))
						.contentType(MediaType.IMAGE_JPEG).content(new byte[] {1, 2, 3}))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.logo").exists());
	}

	@Test
	@DisplayName("Quitar un logo exige poder editar y devuelve la marca sin el")
	void quita_de_logo() throws Exception {
		given(marcaService.quitarLogo(7L, LogoVariante.CLARO, 42L)).willReturn(vista(3L, null, null));

		mockMvc.perform(delete(URL + "/logo/CLARO").with(ApiActors.miembro(42L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.logoClaro").doesNotExist());
		verify(authorizationGuard).requireOrgDataEditor(eq(42L), eq(7L), any(), anyBoolean());
	}

	@Test
	@DisplayName("Un tema que no existe es 400")
	void variante_desconocida_es_400() throws Exception {
		mockMvc.perform(get(URL + "/logo/SEPIA").with(ApiActors.miembro(42L)))
				.andExpect(status().isBadRequest());
	}

	private static MarcaView vista(long version, String principalClaro, MarcaView.Logo logoClaro) {
		return new MarcaView(7L, version, principalClaro, null, null, null,
				null, null, null, null, null, logoClaro, null);
	}
}
