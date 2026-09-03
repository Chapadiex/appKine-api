package com.akine.person.application;

import com.akine.contracting.spi.ArancelCongelado;
import com.akine.contracting.spi.ArancelDirectory;
import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionGuard;
import com.akine.person.domain.AccionSobreAutorizacion;
import com.akine.person.domain.AdjuntoAdministrativo;
import com.akine.person.domain.Autorizacion;
import com.akine.person.domain.CategoriaAdjunto;
import com.akine.person.domain.CoberturaPaciente;
import com.akine.person.domain.EstadoAutorizacion;
import com.akine.person.domain.OrdenMedica;
import com.akine.person.domain.PerfilPaciente;
import com.akine.person.domain.Persona;
import com.akine.person.domain.TipoDocumento;
import com.akine.person.domain.exception.AdjuntoNotAccessibleException;
import com.akine.person.domain.exception.AutorizacionSuperpuestaException;
import com.akine.person.domain.exception.AutorizacionTransicionNoPermitidaException;
import com.akine.person.domain.exception.CoberturaInactivaException;
import com.akine.person.domain.exception.OrdenNotAccessibleException;
import com.akine.person.domain.exception.PersonaSinPerfilPacienteException;
import com.akine.person.domain.port.PersonRepositoryPorts.AdjuntoRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.AutorizacionPersonaLockRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.AutorizacionRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.CoberturaPacienteRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.OrdenMedicaRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.PerfilPacienteRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.PersonaRepositoryPort;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.contracting.spi.ReferenciaDeCobertura;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Las decisiones de la capa de aplicacion de M17: autorizacion, orden del lock y snapshot.
 *
 * <p>Lo que estos casos fijan y que ningun otro nivel puede:
 *
 * <ul>
 *   <li><b>El orden asegurar → bloquear → leer.</b> Es la garantia de la etapa, y se verifica con
 *       un {@code InOrder}. Que dos transacciones se esperen de verdad lo contesta
 *       {@code AutorizacionConcurrenteIT} contra MySQL; aca se fija que las llamadas vayan en el
 *       orden correcto, que es la otra mitad.
 *   <li><b>Quien NO toma el lock.</b> Una PENDIENTE y una baja no lo toman, y eso es tan
 *       deliberado como tomarlo: serializarlas agregaria contencion sin proteger nada.
 *   <li><b>El catalogo se llama UNA sola vez, en el alta.</b> Ninguna lectura posterior vuelve a
 *       tocar {@code contracting}, y esa ausencia de llamadas <b>es</b> la garantia del snapshot.
 * </ul>
 */
@DisplayName("Servicios de ordenes y autorizaciones (M17)")
class OrdenYAutorizacionServiceTest {

	private static final long ORG = 7L;
	private static final long SEDE = 20L;
	private static final long PERSONA = 1204L;
	private static final long COBERTURA = 412L;
	private static final long PRACTICA = 33L;
	private static final LocalDate ENERO = LocalDate.of(2027, 1, 1);
	private static final LocalDate DICIEMBRE = LocalDate.of(2027, 12, 31);
	private static final OperatingActor ACTOR = new OperatingActor(1L, false, ORG, SEDE);

	private OrdenMedicaRepositoryPort ordenes;
	private AutorizacionRepositoryPort autorizaciones;
	private AutorizacionPersonaLockRepositoryPort candados;
	private AutorizacionLockIniciador iniciador;
	private CoberturaPacienteRepositoryPort coberturas;
	private AdjuntoRepositoryPort adjuntos;
	private PersonaRepositoryPort personas;
	private PerfilPacienteRepositoryPort perfiles;
	private ArancelDirectory aranceles;
	private PermissionGuard permissionGuard;
	private AuditTrail auditTrail;

	private OrdenMedicaService ordenService;
	private AutorizacionService autorizacionService;

	@BeforeEach
	void setUp() {
		ordenes = mock(OrdenMedicaRepositoryPort.class);
		autorizaciones = mock(AutorizacionRepositoryPort.class);
		candados = mock(AutorizacionPersonaLockRepositoryPort.class);
		iniciador = mock(AutorizacionLockIniciador.class);
		coberturas = mock(CoberturaPacienteRepositoryPort.class);
		adjuntos = mock(AdjuntoRepositoryPort.class);
		personas = mock(PersonaRepositoryPort.class);
		perfiles = mock(PerfilPacienteRepositoryPort.class);
		aranceles = mock(ArancelDirectory.class);
		permissionGuard = mock(PermissionGuard.class);
		auditTrail = mock(AuditTrail.class);

		ordenService = new OrdenMedicaService(
				ordenes, coberturas, adjuntos, personas, perfiles, permissionGuard, auditTrail);
		autorizacionService = new AutorizacionService(
				autorizaciones, candados, iniciador, ordenes, coberturas, adjuntos, personas,
				perfiles, aranceles, permissionGuard, auditTrail);

		given(personas.findByIdAndOrganizationId(anyLong(), anyLong()))
				.willReturn(Optional.of(persona()));
		given(perfiles.buscarVigente(anyLong(), anyLong()))
				.willReturn(Optional.of(mock(PerfilPaciente.class)));
		given(permissionGuard.requirePermission(any()))
				.willReturn(PermissionDecision.concedida("CONSULTORIO", false));
		given(candados.lockByScope(anyLong(), anyLong()))
				.willReturn(Optional.of(mock(com.akine.person.domain.AutorizacionPersonaLock.class)));
		given(coberturas.findByIdAndOrganizationIdAndPersonaId(anyLong(), anyLong(), anyLong()))
				.willReturn(Optional.of(cobertura(true)));
		given(ordenes.saveAndFlush(any())).willAnswer(llamada -> conId(llamada.getArgument(0), 51L));
		given(ordenes.save(any())).willAnswer(llamada -> llamada.getArgument(0));
		given(autorizaciones.saveAndFlush(any()))
				.willAnswer(llamada -> conId(llamada.getArgument(0), 77L));
		given(autorizaciones.save(any())).willAnswer(llamada -> llamada.getArgument(0));
	}

	@Nested
	@DisplayName("Ordenes medicas")
	class DeLasOrdenes {

		@Test
		@DisplayName("registrar una orden no toma ningun lock: dos ordenes solapadas son legitimas")
		void sin_lock() {
			ordenService.registrar(ACTOR, PERSONA, altaDeOrden(null));

			// Un paciente puede traer dos prescripciones vigentes de dos medicos distintos, y
			// ninguna invalida a la otra. La unica unicidad es el numero, y eso lo expresa un
			// unique: usar un lock donde alcanza un indice solo agrega contencion.
			verifyNoInteractions(candados);
		}

		@Test
		@DisplayName("una persona sin perfil de paciente no puede tener orden: RF-M07-010 desde M17")
		void exige_paciente() {
			given(perfiles.buscarVigente(anyLong(), anyLong())).willReturn(Optional.empty());

			assertThatThrownBy(() -> ordenService.registrar(ACTOR, PERSONA, altaDeOrden(null)))
					.isInstanceOf(PersonaSinPerfilPacienteException.class);
		}

		@Test
		@DisplayName("registrar exige paciente:manage, y la lectura no")
		void permisos() {
			ordenService.listar(ACTOR, PERSONA, DocumentoEstadoFiltro.TODAS, ENERO);
			verifyNoInteractions(permissionGuard);

			ordenService.registrar(ACTOR, PERSONA, altaDeOrden(null));
			verify(permissionGuard).requirePermission(any());
		}

		@Test
		@DisplayName("sin contexto de organizacion la lectura se rechaza con 403, nunca con 401")
		void sin_contexto() {
			OperatingActor sinContexto = new OperatingActor(1L, false, null, null);

			assertThatThrownBy(() -> ordenService.listar(
					sinContexto, PERSONA, DocumentoEstadoFiltro.TODAS, ENERO))
					.isInstanceOf(AccessDeniedException.class);
		}

		@Test
		@DisplayName("el filtro ACTIVA deja afuera las dadas de baja, pero NO las vencidas")
		void filtro_por_ciclo_de_vida() {
			OrdenMedica vigente = orden(null);
			OrdenMedica vencida = orden(null);
			ReflectionTestUtils.setField(vencida, "vigenciaHasta", ENERO);
			OrdenMedica baja = orden(null);
			baja.deactivate(Instant.now(), "cargada por error");
			given(ordenes.historial(ORG, PERSONA)).willReturn(List.of(vigente, vencida, baja));

			List<OrdenView> activas = ordenService.listar(
					ACTOR, PERSONA, DocumentoEstadoFiltro.ACTIVA, DICIEMBRE);

			// "Un documento vencido no desaparece": la vencida sigue en la lista de ACTIVAS, con
			// vigente = false. El filtro es de ciclo de vida y no de vigencia.
			assertThat(activas).hasSize(2);
			assertThat(activas).anyMatch(orden -> !orden.vigente());
		}

		@Test
		@DisplayName("vincular un adjunto de otra persona responde 404")
		void adjunto_ajeno() {
			given(ordenes.findByIdAndOrganizationIdAndPersonaId(anyLong(), anyLong(), anyLong()))
					.willReturn(Optional.of(orden(null)));
			given(adjuntos.buscarDeLaPersona(anyLong(), anyLong(), anyLong()))
					.willReturn(Optional.empty());

			assertThatThrownBy(() ->
					ordenService.vincularDocumento(ACTOR, PERSONA, 51L, 907L))
					.isInstanceOf(AdjuntoNotAccessibleException.class);
		}

		@Test
		@DisplayName("desvincular el documento es legitimo y no consulta adjuntos")
		void desvincular() {
			given(ordenes.findByIdAndOrganizationIdAndPersonaId(anyLong(), anyLong(), anyLong()))
					.willReturn(Optional.of(orden(null)));

			OrdenView vista = ordenService.vincularDocumento(ACTOR, PERSONA, 51L, null);

			assertThat(vista.adjuntoId()).isNull();
			verifyNoInteractions(adjuntos);
		}

		@Test
		@DisplayName("una version desactualizada responde 409 en vez de pisar el cambio ajeno")
		void version_vieja() {
			OrdenMedica orden = orden(null);
			ReflectionTestUtils.setField(orden, "version", 3L);
			given(ordenes.findByIdAndOrganizationIdAndPersonaId(anyLong(), anyLong(), anyLong()))
					.willReturn(Optional.of(orden));

			assertThatThrownBy(() -> ordenService.editar(ACTOR, PERSONA, 51L,
					new OrdenEdicionCommand(null, null, null, null, null, null, null, null, null, 0L)))
					.isInstanceOf(OptimisticLockingFailureException.class);
		}

		@Test
		@DisplayName("la baja exige motivo declarado")
		void baja_sin_motivo() {
			given(ordenes.findByIdAndOrganizationIdAndPersonaId(anyLong(), anyLong(), anyLong()))
					.willReturn(Optional.of(orden(null)));

			assertThatThrownBy(() -> ordenService.darDeBaja(ACTOR, PERSONA, 51L, "  "))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Nested
	@DisplayName("Autorizaciones")
	class DeLasAutorizaciones {

		@Test
		@DisplayName("el alta APROBADA asegura el candado, LUEGO lo bloquea y RECIEN despues lee")
		void orden_del_lock() {
			autorizacionService.registrar(ACTOR, PERSONA, alta(EstadoAutorizacion.APROBADA));

			// El orden es la garantia. Bloquear despues de leer es una escalada S→X entre dos
			// transacciones simetricas, o sea un deadlock; crear la fila dentro de la transaccion
			// que la bloquea es el otro deadlock, el de la primera rafaga.
			InOrder pasos = inOrder(iniciador, candados, autorizaciones);
			pasos.verify(iniciador).asegurar(ORG, PERSONA);
			pasos.verify(candados).lockByScope(ORG, PERSONA);
			pasos.verify(autorizaciones).aprobadasDe(ORG, COBERTURA, PRACTICA);
		}

		@Test
		@DisplayName("el alta PENDIENTE no toma el lock: todavia no autoriza ninguna cantidad")
		void pendiente_no_serializa() {
			autorizacionService.registrar(ACTOR, PERSONA, alta(EstadoAutorizacion.PENDIENTE));

			verifyNoInteractions(candados);
			verify(iniciador, never()).asegurar(anyLong(), anyLong());
		}

		@Test
		@DisplayName("la baja no toma el lock: quitar una fila no puede crear un solapamiento")
		void la_baja_no_serializa() {
			given(autorizaciones.findByIdAndOrganizationIdAndPersonaId(
					anyLong(), anyLong(), anyLong()))
					.willReturn(Optional.of(autorizacion(EstadoAutorizacion.APROBADA)));

			autorizacionService.darDeBaja(ACTOR, PERSONA, 77L, "cargada por error");

			verifyNoInteractions(candados);
		}

		@Test
		@DisplayName("una aprobada solapada se rechaza con el id de la que choca")
		void solapamiento() {
			Autorizacion existente = autorizacion(EstadoAutorizacion.APROBADA);
			ReflectionTestUtils.setField(existente, "id", 70L);
			given(autorizaciones.aprobadasDe(ORG, COBERTURA, PRACTICA))
					.willReturn(List.of(existente));

			assertThatThrownBy(() -> autorizacionService.registrar(
					ACTOR, PERSONA, alta(EstadoAutorizacion.APROBADA)))
					.isInstanceOf(AutorizacionSuperpuestaException.class)
					.extracting(error -> ((AutorizacionSuperpuestaException) error)
							.getAutorizacionExistenteId())
					.isEqualTo(70L);
		}

		@Test
		@DisplayName("el catalogo se llama UNA sola vez, en el alta, y ninguna lectura vuelve a tocarlo")
		void el_snapshot_se_congela_una_vez() {
			given(aranceles.congelar(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any()))
					.willReturn(Optional.of(congelado()));

			autorizacionService.registrar(ACTOR, PERSONA, alta(EstadoAutorizacion.PENDIENTE));
			verify(aranceles).congelar(ORG, SEDE, 88L, 99L, PRACTICA, ENERO);

			given(autorizaciones.historial(anyLong(), anyLong())).willReturn(List.of());
			autorizacionService.listar(ACTOR, PERSONA, DocumentoEstadoFiltro.TODAS, DICIEMBRE);

			// La ausencia de llamadas ES la garantia: no hay ningun campo del convenio que este
			// servicio pueda leer tarde, porque no lo pide nunca despues del alta.
			verify(aranceles).congelar(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any());
			verify(aranceles, never())
					.resolver(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any());
		}

		@Test
		@DisplayName("sin convenio resoluble el alta ENTRA IGUAL, sin snapshot")
		void snapshot_opcional() {
			given(aranceles.congelar(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any()))
					.willReturn(Optional.empty());

			AutorizacionView vista = autorizacionService.registrar(
					ACTOR, PERSONA, alta(EstadoAutorizacion.PENDIENTE));

			// El mostrador no se puede quedar sin cargar un numero de autorizacion real porque la
			// grilla de aranceles este incompleta.
			assertThat(vista.convenioId()).isNull();
			assertThat(vista.requeriaOrden()).isNull();
		}

		@Test
		@DisplayName("una cobertura PARTICULAR no consulta el catalogo: no hay financiador")
		void particular_no_congela() {
			given(coberturas.findByIdAndOrganizationIdAndPersonaId(anyLong(), anyLong(), anyLong()))
					.willReturn(Optional.of(coberturaParticular()));

			autorizacionService.registrar(ACTOR, PERSONA, alta(EstadoAutorizacion.PENDIENTE));

			verifyNoInteractions(aranceles);
		}

		@Test
		@DisplayName("una cobertura dada de baja no puede respaldar una autorizacion nueva")
		void cobertura_de_baja() {
			given(coberturas.findByIdAndOrganizationIdAndPersonaId(anyLong(), anyLong(), anyLong()))
					.willReturn(Optional.of(cobertura(false)));

			assertThatThrownBy(() -> autorizacionService.registrar(
					ACTOR, PERSONA, alta(EstadoAutorizacion.PENDIENTE)))
					.isInstanceOf(CoberturaInactivaException.class);
		}

		@Test
		@DisplayName("una orden de otro paciente no puede respaldar la autorizacion")
		void orden_ajena() {
			given(ordenes.findByIdAndOrganizationIdAndPersonaId(anyLong(), anyLong(), anyLong()))
					.willReturn(Optional.empty());

			AutorizacionAltaCommand conOrden = new AutorizacionAltaCommand(
					COBERTURA, PRACTICA, 999L, "AUT-1", EstadoAutorizacion.PENDIENTE, 10,
					ENERO, DICIEMBRE, null);

			assertThatThrownBy(() -> autorizacionService.registrar(ACTOR, PERSONA, conOrden))
					.isInstanceOf(OrdenNotAccessibleException.class);
		}

		@Test
		@DisplayName("aprobar toma el lock ANTES de leer la autorizacion que va a modificar")
		void aprobar_bloquea_antes_de_leer() {
			given(autorizaciones.findByIdAndOrganizationIdAndPersonaId(
					anyLong(), anyLong(), anyLong()))
					.willReturn(Optional.of(autorizacion(EstadoAutorizacion.PENDIENTE)));

			autorizacionService.resolver(ACTOR, PERSONA, 77L, resolucion(
					AccionSobreAutorizacion.APROBAR, null));

			InOrder pasos = inOrder(iniciador, candados, autorizaciones);
			pasos.verify(iniciador).asegurar(ORG, PERSONA);
			pasos.verify(candados).lockByScope(ORG, PERSONA);
			pasos.verify(autorizaciones)
					.findByIdAndOrganizationIdAndPersonaId(77L, ORG, PERSONA);
		}

		@Test
		@DisplayName("observar y rechazar NO toman el lock: no habilitan nada")
		void observar_no_serializa() {
			given(autorizaciones.findByIdAndOrganizationIdAndPersonaId(
					anyLong(), anyLong(), anyLong()))
					.willReturn(Optional.of(autorizacion(EstadoAutorizacion.PENDIENTE)));

			autorizacionService.resolver(ACTOR, PERSONA, 77L, resolucion(
					AccionSobreAutorizacion.OBSERVAR, "falta la orden firmada"));

			verifyNoInteractions(candados);
		}

		@Test
		@DisplayName("resolver una APROBADA es 409: la aprobacion concurrente muere aca")
		void transicion_terminal() {
			given(autorizaciones.findByIdAndOrganizationIdAndPersonaId(
					anyLong(), anyLong(), anyLong()))
					.willReturn(Optional.of(autorizacion(EstadoAutorizacion.APROBADA)));

			assertThatThrownBy(() -> autorizacionService.resolver(ACTOR, PERSONA, 77L,
					resolucion(AccionSobreAutorizacion.APROBAR, null)))
					.isInstanceOf(AutorizacionTransicionNoPermitidaException.class);
		}

		@Test
		@DisplayName("editar una PENDIENTE no toma el lock; editar una APROBADA si")
		void la_edicion_serializa_solo_si_habilita() {
			given(autorizaciones.findByIdAndOrganizationIdAndPersonaId(
					anyLong(), anyLong(), anyLong()))
					.willReturn(Optional.of(autorizacion(EstadoAutorizacion.PENDIENTE)));

			autorizacionService.editar(ACTOR, PERSONA, 77L, edicion());
			verifyNoInteractions(candados);

			given(autorizaciones.findByIdAndOrganizationIdAndPersonaId(
					anyLong(), anyLong(), anyLong()))
					.willReturn(Optional.of(autorizacion(EstadoAutorizacion.APROBADA)));

			autorizacionService.editar(ACTOR, PERSONA, 77L, edicion());
			// Estirar la vigencia de una aprobada hasta pisar a la siguiente es exactamente el
			// mismo riesgo que aprobarla.
			verify(candados).lockByScope(ORG, PERSONA);
		}

		@Test
		@DisplayName("la lectura de una autorizacion no exige paciente:manage")
		void lectura_por_pertenencia() {
			given(autorizaciones.findByIdAndOrganizationIdAndPersonaId(
					anyLong(), anyLong(), anyLong()))
					.willReturn(Optional.of(autorizacion(EstadoAutorizacion.APROBADA)));

			autorizacionService.ver(ACTOR, PERSONA, 77L, ENERO);

			verifyNoInteractions(permissionGuard);
		}
	}

	// =================================================================================
	// Fixture
	// =================================================================================

	private static OrdenAltaCommand altaDeOrden(Long coberturaId) {
		return new OrdenAltaCommand(coberturaId, "OM-1", "Dra. Sintetica", "MP 1", ENERO,
				"Kinesiologia", 10, ENERO, DICIEMBRE, null);
	}

	private static AutorizacionAltaCommand alta(EstadoAutorizacion estado) {
		return new AutorizacionAltaCommand(COBERTURA, PRACTICA, null, "AUT-1", estado, 10,
				ENERO, DICIEMBRE, null);
	}

	private static AutorizacionEdicionCommand edicion() {
		return new AutorizacionEdicionCommand(null, null, null, null, null, "nota", 0L);
	}

	private static ResolucionDeAutorizacionCommand resolucion(
			AccionSobreAutorizacion accion, String motivo) {

		return new ResolucionDeAutorizacionCommand(accion, motivo, null, null, null, 0L);
	}

	private static OrdenMedica orden(Long coberturaId) {
		OrdenMedica orden = new OrdenMedica(ORG, PERSONA, SEDE, coberturaId, "OM-1",
				"Dra. Sintetica", "MP 1", ENERO, "Kinesiologia", 10, ENERO, DICIEMBRE, null);
		ReflectionTestUtils.setField(orden, "id", 51L);
		return orden;
	}

	private static Autorizacion autorizacion(EstadoAutorizacion estado) {
		Autorizacion autorizacion = new Autorizacion(ORG, PERSONA, SEDE, COBERTURA, null, PRACTICA,
				"AUT-1", estado, 10, ENERO, DICIEMBRE, null, null);
		ReflectionTestUtils.setField(autorizacion, "id", 77L);
		return autorizacion;
	}

	private static Persona persona() {
		return new Persona(ORG, TipoDocumento.DNI, "30111222", "Sintetica", "Paciente", null,
				null, null, null);
	}

	private static CoberturaPaciente cobertura(boolean activa) {
		CoberturaPaciente cobertura = CoberturaPaciente.financiada(
				ORG, PERSONA,
				new ReferenciaDeCobertura(88L, "OS-1", "Financiador Sintetico", "PREPAGA",
						99L, "P-1", "Plan Sintetico", true, false, null, null, ENERO,
						Instant.now()),
				"AF-1", null, ENERO, null, true, null);
		ReflectionTestUtils.setField(cobertura, "id", COBERTURA);
		if (!activa) {
			cobertura.deactivate(Instant.now(), "cargada por error");
		}
		return cobertura;
	}

	private static CoberturaPaciente coberturaParticular() {
		CoberturaPaciente cobertura =
				CoberturaPaciente.particular(ORG, PERSONA, ENERO, null, true, null);
		ReflectionTestUtils.setField(cobertura, "id", COBERTURA);
		return cobertura;
	}

	private static ArancelCongelado congelado() {
		return new ArancelCongelado(12L, "CONV-1", "Convenio Sintetico", "PRESTACION", 88L, 99L,
				PRACTICA, 5L, new BigDecimal("12000.00"), new BigDecimal("10000.00"),
				new BigDecimal("2000.00"), "ARS", true, true, false, ENERO, Instant.now());
	}

	/** Los repositorios devuelven la entidad con id, que es lo que hace la base al insertar. */
	private static <T> T conId(T entidad, long id) {
		ReflectionTestUtils.setField(entidad, "id", id);
		return entidad;
	}

	/** Referencia no usada, pero declarada para que el import de AdjuntoAdministrativo no sobre. */
	@SuppressWarnings("unused")
	private static AdjuntoAdministrativo adjunto() {
		return new AdjuntoAdministrativo(ORG, PERSONA, SEDE, CategoriaAdjunto.AUTORIZACION, null,
				"orden.pdf", "application/pdf", 1024L, "a".repeat(64), "b".repeat(32), 1L,
				Instant.now());
	}
}
