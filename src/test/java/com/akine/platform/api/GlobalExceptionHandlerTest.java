package com.akine.platform.api;

import com.akine.platform.infrastructure.config.SecurityConfig;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifica el contrato de errores del proyecto (ADR-0005).
 *
 * <p>Lo que se prueba no es "que devuelva un error", sino las dos garantias que hacen
 * util ese contrato: que los errores de validacion lleguen accionables por campo, y que
 * <b>ninguna respuesta filtre detalles internos</b>.
 *
 * <p>Usa un controller de prueba porque el baseline todavia no tiene endpoints de negocio
 * que fallen. Vive en las fuentes de test, asi que los tests de arquitectura no lo ven
 * ({@code ImportOption.DoNotIncludeTests}).
 */
// El controller va en @Import, no en el atributo de @WebMvcTest: al ser una clase anidada
// del test no la alcanza el escaneo de componentes, y sin importarla el DispatcherServlet
// responde 404 de recurso estatico. Eso hacia que los tests que esperan 500 pasaran por el
// motivo equivocado.
@WebMvcTest(controllers = GlobalExceptionHandlerTest.ControllerDePrueba.class)
@Import({
		GlobalExceptionHandlerTest.ControllerDePrueba.class,
		GlobalExceptionHandler.class,
		SecurityConfig.class})
class GlobalExceptionHandlerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("Una validacion fallida devuelve 400 con el detalle por campo")
	void validacion_fallida_detalla_los_campos() throws Exception {
		mockMvc.perform(post("/test/validar")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"nombre\":\"\",\"email\":\"no-es-un-email\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Error de validacion"))
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/validation-error"))
				// El cliente necesita saber QUE campo rechazar para pintar el formulario.
				.andExpect(jsonPath("$.errors.nombre").exists())
				.andExpect(jsonPath("$.errors.email").exists());
	}

	@Test
	@DisplayName("Una excepcion inesperada devuelve 500 generico")
	void excepcion_inesperada_responde_generico() throws Exception {
		mockMvc.perform(post("/test/explotar")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.title").value("Error interno"))
				.andExpect(jsonPath("$.status").value(500))
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/internal-error"))
				.andExpect(jsonPath("$.detail").value("Ocurrio un error inesperado"));
	}

	@Test
	@DisplayName("Ninguna respuesta de error filtra detalles internos")
	void los_errores_no_filtran_internals() throws Exception {
		String cuerpo = mockMvc.perform(post("/test/explotar")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isInternalServerError())
				.andReturn()
				.getResponse()
				.getContentAsString();

		// El mensaje de la excepcion nombra una tabla a proposito: si se filtrara, un
		// atacante obtendria el mapa interno de la aplicacion.
		org.assertj.core.api.Assertions.assertThat(cuerpo)
				.doesNotContain("paciente_historia_clinica")
				.doesNotContain("com.akine")
				.doesNotContain("java.lang")
				.doesNotContain("Exception");
	}

	@Test
	@DisplayName("Un cuerpo valido pasa sin tocar el handler")
	void cuerpo_valido_no_dispara_el_handler() throws Exception {
		mockMvc.perform(post("/test/validar")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"nombre\":\"Ana\",\"email\":\"ana@ejemplo.test\"}"))
				.andExpect(status().isOk())
				.andExpect(content().string("ok"));
	}

	// =================================================================================
	// Andamiaje de prueba
	// =================================================================================

	record CuerpoDePrueba(
			@NotBlank(message = "el nombre es obligatorio") String nombre,
			@Email(message = "debe ser una direccion valida") String email) {
	}

	@RestController
	@RequestMapping("/test")
	static class ControllerDePrueba {

		@PostMapping("/validar")
		String validar(@Valid @RequestBody CuerpoDePrueba cuerpo) {
			return "ok";
		}

		@PostMapping("/explotar")
		String explotar() {
			throw new IllegalStateException(
					"detalle interno que jamas debe salir: tabla paciente_historia_clinica");
		}
	}
}
