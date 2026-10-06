package com.akine.encounter.api;

import com.akine.encounter.application.SesionService;
import com.akine.encounter.application.SesionVersionView;
import com.akine.encounter.application.SesionView;
import com.akine.encounter.domain.Asistencia;
import com.akine.encounter.domain.CierreDeSesion;
import com.akine.encounter.domain.ContenidoDeSesion;
import com.akine.encounter.domain.EvaluacionBase;
import com.akine.encounter.domain.Lateralidad;
import com.akine.encounter.domain.ProximaConducta;
import com.akine.encounter.domain.Sesion;
import com.akine.encounter.domain.SesionVersion;
import com.akine.encounter.domain.Tolerancia;
import com.akine.platform.spi.tenant.AuthenticatedPrincipal;
import com.akine.platform.spi.tenant.TenantContextHolder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP de la atencion (M14): inicio, evaluacion, cierre, enmienda e historial.
 *
 * <p>Lo que solo se rompe en esta capa: que el {@code casoId} opcional llegue como {@code null}
 * y no como cero, que cada cuerpo se traduzca al objeto de dominio campo por campo —un campo que
 * se cae en el {@code aDominio()} no lo ve ningun test del servicio—, que una sesion abierta no
 * publique un cierre vacio, y que una enmienda sin motivo muera en la validacion sin tocar el
 * servicio.
 */
@WebMvcTest(SesionController.class)
@Import({SesionControllerTest.SliceSecurityConfig.class, EncounterApiActor.class})
class SesionControllerTest {

	private static final String RUTA = "/api/v1/consultorios/7/sesiones";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private SesionService sesionService;

	@MockitoBean
	private TenantContextHolder tenantContextHolder;

	@Test
	@DisplayName("Iniciar sin caso responde 201 con Location, y el caso viaja como null: "
			+ "RF-M14-002 admite atencion sin caso")
	void iniciar_sin_caso() throws Exception {
		given(sesionService.iniciar(any(), anyLong(), anyLong(), any()))
				.willReturn(SesionView.de(abierta()));

		mockMvc.perform(post(RUTA + "/turnos/301").with(miembro(8L)))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/api/v1/consultorios/7/sesiones/501"))
				.andExpect(jsonPath("$.estado").value("BORRADOR"))
				.andExpect(jsonPath("$.turnoId").value(301))
				.andExpect(jsonPath("$.cierre").doesNotExist());

		verify(sesionService).iniciar(any(), eq(7L), eq(301L), isNull());
	}

	@Test
	@DisplayName("El caso declarado al iniciar llega al servicio tal cual")
	void iniciar_con_caso() throws Exception {
		given(sesionService.iniciar(any(), anyLong(), anyLong(), any()))
				.willReturn(SesionView.de(abierta()));

		mockMvc.perform(post(RUTA + "/turnos/301?casoId=17").with(miembro(8L)))
				.andExpect(status().isCreated());

		verify(sesionService).iniciar(any(), eq(7L), eq(301L), eq(17L));
	}

	@Test
	@DisplayName("La evaluacion se traduce entera al dominio, con la version que el cliente leyo")
	void la_evaluacion_se_traduce_entera() throws Exception {
		given(sesionService.evaluar(any(), anyLong(), anyLong(), any(), anyLong()))
				.willReturn(SesionView.de(abierta()));

		mockMvc.perform(put(RUTA + "/501/evaluacion")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"modo":"COMPLETA","motivoClinico":"dolor lumbar","dolorEva":6,
								 "dolorZona":"Lumbar","dolorLateralidad":"DERECHA","evolucion":"PEOR",
								 "objetivoSesion":"reducir dolor","limitacionFuncional":"no se agacha",
								 "version":3}""")
						.with(miembro(8L)))
				.andExpect(status().isOk());

		ArgumentCaptor<EvaluacionBase> evaluacion = ArgumentCaptor.forClass(EvaluacionBase.class);
		verify(sesionService).evaluar(any(), eq(7L), eq(501L), evaluacion.capture(), eq(3L));
		assertThat(evaluacion.getValue().dolorEva()).isEqualTo(6);
		assertThat(evaluacion.getValue().dolorLateralidad()).isEqualTo(Lateralidad.DERECHA);
		assertThat(evaluacion.getValue().limitacionFuncional()).isEqualTo("no se agacha");
	}

	@Test
	@DisplayName("Un dolor fuera de la escala muere en la validacion: 400 sin tocar el servicio")
	void dolor_fuera_de_escala_es_400() throws Exception {
		mockMvc.perform(put(RUTA + "/501/evaluacion")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"dolorEva\":11,\"version\":0}")
						.with(miembro(8L)))
				.andExpect(status().isBadRequest());

		verify(sesionService, never()).evaluar(any(), anyLong(), anyLong(), any(), anyLong());
	}

	@Test
	@DisplayName("El cierre se traduce entero al dominio y la respuesta publica el cierre con su numero")
	void el_cierre_se_traduce_entero() throws Exception {
		given(sesionService.cerrar(any(), anyLong(), anyLong(), any(), anyLong()))
				.willReturn(SesionView.de(cerrada()));

		mockMvc.perform(post(RUTA + "/501/cierre")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"asistencia":"PRESENTE","notaDeCierre":"Terapia manual",
								 "respuestaTratamiento":"alivio","tolerancia":"REGULAR",
								 "indicaciones":"hielo","proximaConducta":"REEVALUA","version":4}""")
						.with(miembro(8L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.estado").value("CERRADA"))
				.andExpect(jsonPath("$.numeroSesion").value(8))
				.andExpect(jsonPath("$.cierre.asistencia").value("PRESENTE"))
				.andExpect(jsonPath("$.cierre.tolerancia").value("REGULAR"));

		ArgumentCaptor<CierreDeSesion> cierre = ArgumentCaptor.forClass(CierreDeSesion.class);
		verify(sesionService).cerrar(any(), eq(7L), eq(501L), cierre.capture(), eq(4L));
		assertThat(cierre.getValue().asistencia()).isEqualTo(Asistencia.PRESENTE);
		assertThat(cierre.getValue().tolerancia()).isEqualTo(Tolerancia.REGULAR);
		assertThat(cierre.getValue().indicaciones()).isEqualTo("hielo");
		assertThat(cierre.getValue().proximaConducta()).isEqualTo(ProximaConducta.REEVALUA);
	}

	@Test
	@DisplayName("La enmienda lleva el contenido y el motivo por separado: el motivo no es contenido")
	void la_enmienda_se_traduce() throws Exception {
		given(sesionService.enmendar(any(), anyLong(), anyLong(), any(), anyString(), anyLong()))
				.willReturn(SesionView.de(cerrada()));

		mockMvc.perform(post(RUTA + "/501/enmiendas")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"dolorEva":4,"dolorZona":"Lumbar","dolorLateralidad":"IZQUIERDA",
								 "notaDeCierre":"Nota corregida","motivo":"Era del lado izquierdo",
								 "version":5}""")
						.with(miembro(8L)))
				.andExpect(status().isOk());

		ArgumentCaptor<ContenidoDeSesion> contenido = ArgumentCaptor.forClass(ContenidoDeSesion.class);
		verify(sesionService).enmendar(any(), eq(7L), eq(501L), contenido.capture(),
				eq("Era del lado izquierdo"), eq(5L));
		assertThat(contenido.getValue().notaDeCierre()).isEqualTo("Nota corregida");
		assertThat(contenido.getValue().dolorLateralidad()).isEqualTo(Lateralidad.IZQUIERDA);
	}

	@Test
	@DisplayName("Una enmienda sin motivo es 400 y no llega al servicio")
	void la_enmienda_sin_motivo_es_400() throws Exception {
		mockMvc.perform(post(RUTA + "/501/enmiendas")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"notaDeCierre\":\"Nota\",\"motivo\":\"  \",\"version\":5}")
						.with(miembro(8L)))
				.andExpect(status().isBadRequest());

		verify(sesionService, never())
				.enmendar(any(), anyLong(), anyLong(), any(), any(), anyLong());
	}

	@Test
	@DisplayName("El historial publica cada version con su numero, y la 1 sin motivo")
	void el_historial_publica_las_versiones() throws Exception {
		Sesion sesion = cerrada();
		given(sesionService.versiones(any(), anyLong(), anyLong()))
				.willReturn(List.of(SesionVersionView.de(SesionVersion.original(sesion))));

		mockMvc.perform(get(RUTA + "/501/versiones").with(miembro(8L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].numeroVersion").value(1))
				.andExpect(jsonPath("$[0].motivoEnmienda").doesNotExist())
				.andExpect(jsonPath("$[0].notaDeCierre").value("Terapia manual"))
				.andExpect(jsonPath("$[0].tolerancia").value("REGULAR"))
				.andExpect(jsonPath("$[0].registradaPor").value(8));
	}

	private static Sesion abierta() {
		Sesion sesion = new Sesion(1L, 7L, 88L, null, 301L, 42L, 31L, Instant.EPOCH, 8L);
		ReflectionTestUtils.setField(sesion, "id", 501L);
		return sesion;
	}

	private static Sesion cerrada() {
		Sesion sesion = abierta();
		sesion.cerrar(new CierreDeSesion(Asistencia.PRESENTE, "Terapia manual", "alivio",
				Tolerancia.REGULAR, "hielo", ProximaConducta.REEVALUA), 8, null,
				Instant.parse("2026-09-15T12:48:00Z"), 8L);
		return sesion;
	}

	/** Cuenta autenticada comun: lo que importa es el TIPO del principal, no sus privilegios. */
	private static RequestPostProcessor miembro(long accountId) {
		return authentication(new UsernamePasswordAuthenticationToken(
				new PrincipalDePrueba(accountId), "n/a", List.of()));
	}

	private record PrincipalDePrueba(long accountId) implements AuthenticatedPrincipal {

		@Override
		public boolean platformAdmin() {
			return false;
		}

		@Override
		public Long organizationId() {
			return null;
		}

		@Override
		public Long consultorioId() {
			return null;
		}
	}

	/** Cadena minima para el slice: {@code permitAll}, la autorizacion la decide el servicio. */
	@TestConfiguration
	@EnableWebSecurity
	static class SliceSecurityConfig {

		@Bean
		SecurityFilterChain sliceSecurityFilterChain(HttpSecurity http) throws Exception {
			http
					.csrf(csrf -> csrf.disable())
					.sessionManagement(session -> session
							.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
					.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
			return http.build();
		}
	}
}
