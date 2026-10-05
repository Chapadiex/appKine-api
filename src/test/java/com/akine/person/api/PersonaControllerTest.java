package com.akine.person.api;

import com.akine.person.api.dto.ActivarPerfilPacienteRequest;
import com.akine.person.api.dto.BajaDePerfilPacienteRequest;
import com.akine.person.api.dto.BajaDePersonaRequest;
import com.akine.person.api.dto.CreatePersonaRequest;
import com.akine.person.api.dto.PersonaPageResponse;
import com.akine.person.api.dto.PersonaResponse;
import com.akine.person.api.dto.ResumenDePersonaResponse;
import com.akine.person.api.dto.UpdatePersonaRequest;
import com.akine.person.application.OperatingActor;
import com.akine.person.application.PerfilPacienteService;
import com.akine.person.application.PersonaAltaCommand;
import com.akine.person.application.PerfilFiltro;
import com.akine.person.application.PersonaBusqueda;
import com.akine.person.application.PersonaEstadoFiltro;
import com.akine.person.application.PersonaPagina;
import com.akine.person.application.PersonaService;
import com.akine.person.application.PersonaView;
import com.akine.person.application.ResumenDePersonaService;
import com.akine.person.application.ResumenDePersonaView;
import com.akine.person.domain.TipoDocumento;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * La capa HTTP del padron, sin levantar Spring.
 *
 * <p>Se instancia el controller a mano y se verifica lo que decide <b>el</b>: el acotado de la
 * paginacion, el 201 con Location del alta, y que la baja no invente un 204 —devuelve la ficha
 * con su version nueva, que es lo que la pantalla necesita para seguir operando—.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PersonaController")
class PersonaControllerTest {

	private static final long PERSONA_ID = 500L;

	@Mock
	private PersonaService personaService;

	@Mock
	private PerfilPacienteService perfilPacienteService;

	@Mock
	private ResumenDePersonaService resumenDePersonaService;

	@Mock
	private PersonApiActor apiActor;

	private PersonaController controller;

	@BeforeEach
	void setUp() {
		controller = new PersonaController(
				personaService, perfilPacienteService, resumenDePersonaService, apiActor);
		given(apiActor.current()).willReturn(new OperatingActor(40L, false, 10L, 20L));
	}

	@Test
	@DisplayName("la busqueda acota el tamano de pagina y no acepta pagina negativa")
	void la_busqueda_acota_la_paginacion() {
		given(personaService.buscar(any(), any(), anyInt(), anyInt()))
				.willReturn(new PersonaPagina(List.of(unaVista()), 1L));

		PersonaPageResponse pagina = controller.buscar(null, null, null, -3, 9999);

		assertThat(pagina.page()).isZero();
		assertThat(pagina.size()).isEqualTo(100);
		assertThat(pagina.totalPages()).isEqualTo(1);

		ArgumentCaptor<PersonaBusqueda> filtros = ArgumentCaptor.forClass(PersonaBusqueda.class);
		verify(personaService).buscar(any(), filtros.capture(), anyInt(), anyInt());
		assertThat(filtros.getValue()).isNotNull();
	}

	@Test
	@DisplayName("B1-E3b el texto del operador llega a la busqueda desde el parametro q, con estado y perfil")
	void b1_e3b_el_parametro_http_es_q() {
		given(personaService.buscar(any(), any(), anyInt(), anyInt()))
				.willReturn(new PersonaPagina(List.of(), 0L));

		controller.buscar("ab-1234", PersonaEstadoFiltro.TODOS, PerfilFiltro.SIN_PERFIL, 0, 20);

		ArgumentCaptor<PersonaBusqueda> filtros = ArgumentCaptor.forClass(PersonaBusqueda.class);
		verify(personaService).buscar(any(), filtros.capture(), anyInt(), anyInt());
		assertThat(filtros.getValue().texto()).isEqualTo("ab-1234");
		assertThat(filtros.getValue().estado()).isEqualTo(PersonaEstadoFiltro.TODOS);
		assertThat(filtros.getValue().perfil()).isEqualTo(PerfilFiltro.SIN_PERFIL);

		// El nombre HTTP del parametro es el del argumento Java: tiene que ser "q", no "texto".
		var buscar = java.util.Arrays.stream(PersonaController.class.getDeclaredMethods())
				.filter(m -> m.getName().equals("buscar")).findFirst().orElseThrow();
		assertThat(buscar.getParameters()[0].getName()).isEqualTo("q");
		assertThat(buscar.getParameters()[0].getAnnotation(
				org.springframework.web.bind.annotation.RequestParam.class)).isNotNull();
	}

	@Test
	@DisplayName("el alta responde 201 con Location y NO pide ningun flag de paciente")
	void el_alta_es_201() {
		given(personaService.crear(any(), any())).willReturn(unaVista());

		ResponseEntity<PersonaResponse> respuesta = controller.crear(new CreatePersonaRequest(
				TipoDocumento.DNI, "27888999", "Perez", "Ana", null, null, "1155550000", null,
				false));

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(respuesta.getHeaders().getLocation()).hasToString("/api/v1/personas/500");
		// El contrato no ofrece ninguna forma de crear un paciente desde el alta: RF-M07-010.
		assertThat(respuesta.getBody().esPaciente()).isFalse();

		ArgumentCaptor<PersonaAltaCommand> comando =
				ArgumentCaptor.forClass(PersonaAltaCommand.class);
		verify(personaService).crear(any(), comando.capture());
		assertThat(comando.getValue().confirmaPosibleDuplicado()).isFalse();
	}

	@Test
	@DisplayName("ver, editar y activar el perfil devuelven la ficha")
	void las_operaciones_simples_devuelven_la_ficha() {
		given(personaService.ver(any(), anyLong())).willReturn(unaVista());
		given(personaService.editar(any(), anyLong(), any())).willReturn(unaVista());
		given(perfilPacienteService.activar(any(), anyLong(), any())).willReturn(unaVista());

		assertThat(controller.ver(PERSONA_ID).id()).isEqualTo(PERSONA_ID);
		assertThat(controller.editar(PERSONA_ID, new UpdatePersonaRequest(
				null, null, "Gomez", null, null, null, null, null, 0L)).id())
				.isEqualTo(PERSONA_ID);
		assertThat(controller.activarPerfil(PERSONA_ID,
				new ActivarPerfilPacienteRequest(null)).id())
				.isEqualTo(PERSONA_ID);
	}

	@Test
	@DisplayName("la baja devuelve la ficha con su version, no un 204 vacio")
	void la_baja_devuelve_la_ficha() {
		given(personaService.darDeBaja(any(), anyLong(), any(), anyLong()))
				.willReturn(unaVistaDeBaja());

		PersonaResponse respuesta = controller.darDeBaja(
				PERSONA_ID, new BajaDePersonaRequest("Ficha duplicada", 0L));

		assertThat(respuesta.estado()).isEqualTo("INACTIVO");
		assertThat(respuesta.version()).isEqualTo(1L);
		verify(personaService).darDeBaja(any(), anyLong(), any(), anyLong());
	}

	@Test
	@DisplayName("la baja del perfil deja a la persona activa")
	void la_baja_del_perfil_deja_la_persona() {
		given(perfilPacienteService.desactivar(any(), anyLong(), any())).willReturn(unaVista());

		PersonaResponse respuesta = controller.darDeBajaPerfil(
				PERSONA_ID, new BajaDePerfilPacienteRequest("solo clases grupales"));

		assertThat(respuesta.estado()).isEqualTo("ACTIVO");
		assertThat(respuesta.esPaciente()).isFalse();
	}

	@Test
	@DisplayName("el 360 pasa por el servicio de resumen y devuelve sus secciones omitidas")
	void el_360_se_delega() {
		given(resumenDePersonaService.ver(any(), anyLong())).willReturn(
				new ResumenDePersonaView(unaVista(), 0L, Map.of(), List.of(), List.of()));

		ResumenDePersonaResponse respuesta = controller.verResumen(PERSONA_ID);

		assertThat(respuesta.persona().id()).isEqualTo(PERSONA_ID);
		assertThat(respuesta.seccionesOmitidas()).isEmpty();
	}

	private static PersonaView unaVista() {
		return new PersonaView(PERSONA_ID, "DNI", "27888999", "Perez", "Ana", null, null,
				"1155550000", null, false, null, null, "ACTIVO", null, null, 0L);
	}

	private static PersonaView unaVistaDeBaja() {
		return new PersonaView(PERSONA_ID, "DNI", "27888999", "Perez", "Ana", null, null,
				"1155550000", null, false, null, null, "INACTIVO",
				java.time.Instant.now(), "Ficha duplicada", 1L);
	}
}
