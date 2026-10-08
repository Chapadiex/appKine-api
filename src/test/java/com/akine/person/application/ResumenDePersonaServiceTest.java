package com.akine.person.application;

import com.akine.organization.spi.PermissionEvaluator;
import com.akine.person.domain.Persona;
import com.akine.person.domain.TipoDocumento;
import com.akine.person.domain.exception.PersonaNotAccessibleException;
import com.akine.person.domain.port.PersonRepositoryPorts.AdjuntoRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.PerfilPacienteRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.PersonaRepositoryPort;
import com.akine.person.spi.AporteDeResumen;
import com.akine.person.spi.ConsultaDeResumen;
import com.akine.person.spi.IndicadorDeResumen;
import com.akine.person.spi.ResumenDePersonaContributor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * El Paciente 360: la agregacion por permisos.
 *
 * <p>Lo unico que este servicio decide, y lo que estos casos fijan:
 *
 * <ol>
 *   <li>Una seccion sin permiso <b>no se pide</b> —el contribuyente ni siquiera se invoca— y
 *       <b>se declara omitida</b>. Las dos mitades importan: si se invocara igual, el permiso
 *       seria decorativo; si no se declarara, la pantalla leeria un recorte como un vacio.</li>
 *   <li>El 360 de una persona dada de baja resuelve (RN-M07-004).</li>
 *   <li>Sin contribuyentes sigue siendo un 360 valido.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ResumenDePersonaService")
class ResumenDePersonaServiceTest {

	private static final long ACCOUNT_ID = 40L;
	private static final long ORG_ID = 10L;
	private static final long SEDE_ID = 20L;
	private static final long PERSONA_ID = 500L;

	@Mock
	private PersonaRepositoryPort personas;

	@Mock
	private PerfilPacienteRepositoryPort perfiles;

	@Mock
	private AdjuntoRepositoryPort adjuntos;

	@Mock
	private PermissionEvaluator permissionEvaluator;

	@Mock
	private com.akine.organization.spi.PermissionGuard permissionGuard;

	private final OperatingActor delMostrador =
			new OperatingActor(ACCOUNT_ID, false, ORG_ID, SEDE_ID);

	@BeforeEach
	void setUp() {
		given(personas.findByIdAndOrganizationId(PERSONA_ID, ORG_ID))
				.willReturn(Optional.of(personaVigente()));
		given(perfiles.buscarVigente(ORG_ID, PERSONA_ID)).willReturn(Optional.empty());
		given(adjuntos.contarVigentesPorCategoria(ORG_ID, PERSONA_ID))
				.willReturn(List.of(new Object[] {"OTRO", 2L}, new Object[] {"CONSENTIMIENTO", 1L}));
	}

	@Test
	@DisplayName("con el permiso, la seccion viene con sus indicadores")
	void con_permiso_la_seccion_viene() {
		given(permissionEvaluator.effectivePermissions(ACCOUNT_ID, ORG_ID, SEDE_ID))
				.willReturn(Set.of("turno:read"));

		AtomicBoolean invocado = new AtomicBoolean(false);
		ResumenDePersonaView vista = servicio(contribuyente("turnos", "turno:read", invocado))
				.ver(delMostrador, PERSONA_ID);

		assertThat(invocado).isTrue();
		assertThat(vista.secciones()).hasSize(1);
		assertThat(vista.secciones().getFirst().seccion()).isEqualTo("turnos");
		assertThat(vista.seccionesOmitidas()).isEmpty();
	}

	@Test
	@DisplayName("sin el permiso, la seccion NO se pide y se declara omitida")
	void sin_permiso_la_seccion_se_omite_y_se_declara() {
		given(permissionEvaluator.effectivePermissions(ACCOUNT_ID, ORG_ID, SEDE_ID))
				.willReturn(Set.of());

		AtomicBoolean invocado = new AtomicBoolean(false);
		ResumenDePersonaView vista = servicio(contribuyente("economia", "cobro:register", invocado))
				.ver(delMostrador, PERSONA_ID);

		assertThat(invocado)
				.as("un contribuyente sin permiso no se invoca: si se invocara, el permiso "
						+ "seria decorativo y la consulta se haria igual")
				.isFalse();
		assertThat(vista.secciones()).isEmpty();
		assertThat(vista.seccionesOmitidas()).singleElement()
				.satisfies(omitida -> {
					assertThat(omitida.seccion()).isEqualTo("economia");
					assertThat(omitida.permisoRequerido()).isEqualTo("cobro:register");
				});
	}

	@Test
	@DisplayName("un contribuyente sin permiso declarado se pide siempre")
	void sin_permiso_declarado_se_pide_siempre() {
		given(permissionEvaluator.effectivePermissions(ACCOUNT_ID, ORG_ID, SEDE_ID))
				.willReturn(Set.of());

		AtomicBoolean invocado = new AtomicBoolean(false);
		ResumenDePersonaView vista = servicio(contribuyente("libre", null, invocado))
				.ver(delMostrador, PERSONA_ID);

		assertThat(invocado).isTrue();
		assertThat(vista.secciones()).hasSize(1);
	}

	@Test
	@DisplayName("cuenta los adjuntos vigentes por categoria y en total")
	void cuenta_los_adjuntos() {
		given(permissionEvaluator.effectivePermissions(ACCOUNT_ID, ORG_ID, SEDE_ID))
				.willReturn(Set.of());

		ResumenDePersonaView vista = servicio().ver(delMostrador, PERSONA_ID);

		assertThat(vista.adjuntosTotal()).isEqualTo(3L);
		assertThat(vista.adjuntosPorCategoria()).containsEntry("OTRO", 2L);
	}

	@Test
	@DisplayName("el 360 de una persona dada de baja resuelve igual")
	void la_ficha_de_baja_resuelve() {
		Persona deBaja = personaVigente();
		deBaja.deactivate(java.time.Instant.now(), "ficha duplicada");
		given(personas.findByIdAndOrganizationId(PERSONA_ID, ORG_ID)).willReturn(Optional.of(deBaja));
		given(permissionEvaluator.effectivePermissions(ACCOUNT_ID, ORG_ID, SEDE_ID))
				.willReturn(Set.of());

		assertThat(servicio().ver(delMostrador, PERSONA_ID).persona().estado())
				.isEqualTo("INACTIVO");
	}

	@Test
	@DisplayName("una persona de otro tenant es 404")
	void persona_ajena_es_404() {
		given(personas.findByIdAndOrganizationId(PERSONA_ID, ORG_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> servicio().ver(delMostrador, PERSONA_ID))
				.isInstanceOf(PersonaNotAccessibleException.class);
	}

	@Test
	@DisplayName("sin paciente:read es 403 sobre la ficha entera, aunque pertenezca (DP-22)")
	void sin_paciente_read_es_403() {
		given(permissionGuard.requirePermission(org.mockito.ArgumentMatchers.argThat(
				q -> "paciente:read".equals(q.permissionCode()) && q.consultorioId() != null)))
				.willThrow(new AccessDeniedException("sin paciente:read"));

		assertThatThrownBy(() -> servicio().ver(delMostrador, PERSONA_ID))
				.isInstanceOf(AccessDeniedException.class);
		org.mockito.Mockito.verifyNoInteractions(personas);
	}

	@Test
	@DisplayName("sin contexto de organizacion es 403, nunca 401")
	void sin_contexto_es_403() {
		OperatingActor sinContexto = new OperatingActor(ACCOUNT_ID, false, null, null);

		assertThatThrownBy(() -> servicio().ver(sinContexto, PERSONA_ID))
				.isInstanceOf(AccessDeniedException.class);
	}

	private ResumenDePersonaService servicio(ResumenDePersonaContributor... contribuyentes) {
		return new ResumenDePersonaService(
				personas, perfiles, adjuntos, permissionEvaluator, permissionGuard,
				List.of(contribuyentes));
	}

	private static ResumenDePersonaContributor contribuyente(
			String seccion, String permiso, AtomicBoolean invocado) {

		return new ResumenDePersonaContributor() {
			@Override
			public String seccion() {
				return seccion;
			}

			@Override
			public String permisoRequerido() {
				return permiso;
			}

			@Override
			public AporteDeResumen aportar(ConsultaDeResumen consulta) {
				invocado.set(true);
				return new AporteDeResumen(
						seccion,
						List.of(IndicadorDeResumen.contando("algo", "Algo", 1)),
						List.of());
			}
		};
	}

	private static Persona personaVigente() {
		Persona persona = new Persona(ORG_ID, TipoDocumento.DNI, "27888999", "Perez", "Ana",
				null, null, null, null);
		ReflectionTestUtils.setField(persona, "id", PERSONA_ID);
		return persona;
	}
}
