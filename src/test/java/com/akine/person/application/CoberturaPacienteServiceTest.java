package com.akine.person.application;

import com.akine.contracting.spi.CoberturaCatalogoDirectory;
import com.akine.contracting.spi.ReferenciaDeCobertura;
import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionGuard;
import com.akine.person.domain.CoberturaPaciente;
import com.akine.person.domain.CoberturaPersonaLock;
import com.akine.person.domain.PerfilPaciente;
import com.akine.person.domain.Persona;
import com.akine.person.domain.TipoCobertura;
import com.akine.person.domain.TipoDocumento;
import com.akine.person.domain.exception.CoberturaPrincipalSuperpuestaException;
import com.akine.person.domain.exception.CoberturaSuperpuestaException;
import com.akine.person.domain.exception.PersonaSinPerfilPacienteException;
import com.akine.person.domain.exception.PlanNoSeleccionableException;
import com.akine.person.domain.port.PersonRepositoryPorts.CoberturaPacienteRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.CoberturaPersonaLockRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.PerfilPacienteRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.PersonaRepositoryPort;
import com.akine.platform.spi.audit.AuditTrail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Las coberturas del paciente: la copia congelada, las dos reglas del lock y Particular.
 *
 * <p>Lo que este test fija, y que ninguna otra capa puede fijar:
 *
 * <ol>
 *   <li>Que el alta <b>copie</b> del catalogo y que ninguna lectura posterior lo vuelva a
 *       consultar. Es el requisito entero de la etapa.</li>
 *   <li>Que las dos invariantes temporales —mismo plan solapado, segunda principal solapada— se
 *       evaluen <b>despues</b> de tomar el lock, y que la fila-lock se asegure antes.</li>
 *   <li>Que PARTICULAR no toque el catalogo ni exija credencial.</li>
 *   <li>Que una Persona sin perfil de paciente no pueda tener cobertura (RF-M07-010).</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CoberturaPacienteService")
class CoberturaPacienteServiceTest {

	private static final long ACCOUNT_ID = 40L;
	private static final long ORG_ID = 10L;
	private static final long SEDE_ID = 20L;
	private static final long PERSONA_ID = 500L;
	private static final long PLAN_ID = 88L;
	private static final long COBERTURA_ID = 412L;
	private static final LocalDate DESDE = LocalDate.of(2026, 9, 1);

	@Mock
	private CoberturaPacienteRepositoryPort coberturas;

	@Mock
	private CoberturaPersonaLockRepositoryPort candados;

	@Mock
	private CoberturaLockIniciador iniciador;

	@Mock
	private PersonaRepositoryPort personas;

	@Mock
	private PerfilPacienteRepositoryPort perfiles;

	@Mock
	private CoberturaCatalogoDirectory catalogo;

	@Mock
	private PermissionGuard permissionGuard;

	@Mock
	private AuditTrail auditTrail;

	private CoberturaPacienteService service;

	private final OperatingActor delMostrador =
			new OperatingActor(ACCOUNT_ID, false, ORG_ID, SEDE_ID);

	@BeforeEach
	void setUp() {
		service = new CoberturaPacienteService(
				coberturas, candados, iniciador, personas, perfiles, catalogo, permissionGuard,
				auditTrail);

		given(permissionGuard.requirePermission(any()))
				.willReturn(PermissionDecision.concedida("CONSULTORIO", false));
		given(personas.findByIdAndOrganizationId(PERSONA_ID, ORG_ID))
				.willReturn(Optional.of(personaVigente()));
		given(perfiles.buscarVigente(ORG_ID, PERSONA_ID))
				.willReturn(Optional.of(new PerfilPaciente(
						ORG_ID, PERSONA_ID, Instant.now(), ACCOUNT_ID, null)));
		given(candados.lockByScope(ORG_ID, PERSONA_ID))
				.willReturn(Optional.of(new CoberturaPersonaLock()));
		given(catalogo.congelar(ORG_ID, PLAN_ID, DESDE)).willReturn(Optional.of(referencia()));
		given(coberturas.activasDe(ORG_ID, PERSONA_ID)).willReturn(List.of());
		given(coberturas.saveAndFlush(any())).willAnswer(i -> conId(i.getArgument(0)));
		given(coberturas.save(any())).willAnswer(i -> conId(i.getArgument(0)));
	}

	// =================================================================================
	// La copia congelada
	// =================================================================================

	@Test
	@DisplayName("El alta COPIA el plan a columnas propias y no vuelve a consultarlo despues")
	void el_alta_congela_la_referencia() {
		CoberturaView creada = service.agregar(delMostrador, PERSONA_ID, altaFinanciada(false));

		assertThat(creada.planNombre()).isEqualTo("Plan 210");
		assertThat(creada.financiadorNombre()).isEqualTo("OSDE Binario");
		assertThat(creada.copago()).isEqualByComparingTo("1500.00");
		assertThat(creada.referenciaCapturadaEl()).isNotNull();

		// Lo que importa no es que haya llamado a congelar, sino que despues NO vuelva a preguntar.
		verify(catalogo).congelar(ORG_ID, PLAN_ID, DESDE);
		verifyNoMoreInteractions(catalogo);
	}

	@Test
	@DisplayName("Listar y resolver la cobertura del dia NO tocan el catalogo")
	void las_lecturas_no_leen_vivo() {
		// Es la mitad del requisito que un doble si puede probar: si el listado resolviera el
		// nombre del plan al mostrar, renombrarlo reescribiria coberturas ya firmadas.
		given(coberturas.historial(ORG_ID, PERSONA_ID)).willReturn(List.of(financiadaGuardada()));
		given(coberturas.activasDe(ORG_ID, PERSONA_ID)).willReturn(List.of(financiadaGuardada()));

		service.listar(delMostrador, PERSONA_ID, CoberturaEstadoFiltro.TODAS, DESDE);
		service.resolverParaAtencion(delMostrador, PERSONA_ID, DESDE);

		verifyNoMoreInteractions(catalogo);
	}

	@Test
	@DisplayName("Un plan que no se puede elegir ese dia es 409 y no crea nada")
	void plan_no_seleccionable() {
		// congelar devuelve empty por cinco causas distintas y las cinco terminan aca.
		given(catalogo.congelar(ORG_ID, PLAN_ID, DESDE)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.agregar(delMostrador, PERSONA_ID, altaFinanciada(false)))
				.isInstanceOf(PlanNoSeleccionableException.class);

		verify(coberturas, never()).saveAndFlush(any());
	}

	// =================================================================================
	// Particular
	// =================================================================================

	@Test
	@DisplayName("PARTICULAR no consulta el catalogo, no lleva plan y no exige credencial")
	void particular_es_la_ausencia_de_plan() {
		// RN-M08-001: siempre disponible. Si dependiera de una fila de catalogo, darla de baja
		// dejaria al mostrador sin poder cobrar una consulta.
		CoberturaView creada = service.agregar(delMostrador, PERSONA_ID, new CoberturaAltaCommand(
				TipoCobertura.PARTICULAR, null, null, null, DESDE, null, false, null));

		assertThat(creada.tipo()).isEqualTo("PARTICULAR");
		assertThat(creada.planId()).isNull();
		assertThat(creada.numeroAfiliado()).isNull();
		verifyNoMoreInteractions(catalogo);
	}

	@Test
	@DisplayName("La seleccion del dia siempre declara Particular disponible, aun sin coberturas")
	void particular_viaja_siempre() {
		SeleccionDeCobertura seleccion =
				service.resolverParaAtencion(delMostrador, PERSONA_ID, DESDE);

		assertThat(seleccion.vigentes()).isEmpty();
		assertThat(seleccion.principal()).isNull();
		assertThat(seleccion.particularSiempreDisponible()).isTrue();
	}

	// =================================================================================
	// Las dos reglas que hace cumplir el lock
	// =================================================================================

	@Test
	@DisplayName("La fila-lock se asegura ANTES de tomarla, y el lock se toma antes de leer")
	void el_lock_se_asegura_en_su_propia_transaccion() {
		// Crear la fila dentro de la transaccion que la bloquea produce deadlock entre las
		// primeras N escrituras concurrentes. Ya se pago tres veces en este repositorio.
		service.agregar(delMostrador, PERSONA_ID, altaFinanciada(false));

		verify(iniciador).asegurar(ORG_ID, PERSONA_ID);
		verify(candados).lockByScope(ORG_ID, PERSONA_ID);
	}

	@Test
	@DisplayName("Dos coberturas activas del MISMO plan con vigencias solapadas son 409")
	void mismo_plan_solapado_se_rechaza() {
		given(coberturas.activasDe(ORG_ID, PERSONA_ID)).willReturn(List.of(financiadaGuardada()));

		assertThatThrownBy(() -> service.agregar(delMostrador, PERSONA_ID, altaFinanciada(false)))
				.isInstanceOf(CoberturaSuperpuestaException.class);

		verify(coberturas, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("Dos coberturas de planes DISTINTOS solapadas conviven: no es una regla")
	void distinto_plan_solapado_se_acepta() {
		// Obra social y prepaga a la vez es el caso normal, no una anomalia.
		CoberturaPaciente otroPlan = financiadaGuardada();
		ReflectionTestUtils.setField(otroPlan, "planId", 999L);
		given(coberturas.activasDe(ORG_ID, PERSONA_ID)).willReturn(List.of(otroPlan));

		assertThat(service.agregar(delMostrador, PERSONA_ID, altaFinanciada(false))).isNotNull();
	}

	@Test
	@DisplayName("Una segunda cobertura principal solapada es 409 y NO desmarca a la anterior")
	void segunda_principal_solapada_se_rechaza() {
		// Desmarcarla en silencio significaria que un click cambia dos coberturas, y la que se
		// cambia sin pedirlo es la que despues nadie puede explicar.
		CoberturaPaciente yaPrincipal = financiadaGuardada();
		ReflectionTestUtils.setField(yaPrincipal, "planId", 999L);
		yaPrincipal.marcarPrincipal(true);
		given(coberturas.activasDe(ORG_ID, PERSONA_ID)).willReturn(List.of(yaPrincipal));

		assertThatThrownBy(() -> service.agregar(delMostrador, PERSONA_ID, altaFinanciada(true)))
				.isInstanceOf(CoberturaPrincipalSuperpuestaException.class);

		assertThat(yaPrincipal.isPrincipal()).isTrue();
		verify(coberturas, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("Una principal ya finalizada no bloquea a la siguiente: no se solapan")
	void principal_anterior_finalizada_no_bloquea() {
		CoberturaPaciente anterior = financiadaGuardada();
		ReflectionTestUtils.setField(anterior, "planId", 999L);
		ReflectionTestUtils.setField(anterior, "vigenciaHasta", DESDE.minusDays(1));
		anterior.marcarPrincipal(true);
		given(coberturas.activasDe(ORG_ID, PERSONA_ID)).willReturn(List.of(anterior));

		assertThat(service.agregar(delMostrador, PERSONA_ID, altaFinanciada(true))).isNotNull();
	}

	// =================================================================================
	// Persona no es Paciente
	// =================================================================================

	@Test
	@DisplayName("Una persona sin perfil de paciente no puede tener cobertura")
	void sin_perfil_no_hay_cobertura() {
		// RF-M07-010 sostenido desde M08: cargarle la obra social a un contacto administrativo no
		// significa nada.
		given(perfiles.buscarVigente(ORG_ID, PERSONA_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.agregar(delMostrador, PERSONA_ID, altaFinanciada(false)))
				.isInstanceOf(PersonaSinPerfilPacienteException.class);

		verify(coberturas, never()).saveAndFlush(any());
	}

	// =================================================================================
	// Ciclo de vida
	// =================================================================================

	@Test
	@DisplayName("Dar de baja desmarca la principal y NO toma el lock")
	void la_baja_no_serializa_nada() {
		// Quitar una fila no puede crear un solapamiento ni una segunda principal: serializarla
		// solo agregaria contencion.
		CoberturaPaciente vigente = financiadaGuardada();
		vigente.marcarPrincipal(true);
		given(coberturas.findByIdAndOrganizationIdAndPersonaId(COBERTURA_ID, ORG_ID, PERSONA_ID))
				.willReturn(Optional.of(vigente));

		CoberturaView baja = service.darDeBaja(
				delMostrador, PERSONA_ID, COBERTURA_ID, "se cargo con el plan equivocado");

		assertThat(baja.estado()).isEqualTo("INACTIVA");
		assertThat(baja.principal()).isFalse();
		verify(candados, never()).lockByScope(anyLong(), anyLong());
	}

	@Test
	@DisplayName("Finalizar la vigencia deja la cobertura ACTIVA, no la da de baja")
	void finalizar_no_es_dar_de_baja() {
		CoberturaPaciente vigente = financiadaGuardada();
		given(coberturas.findByIdAndOrganizationIdAndPersonaId(COBERTURA_ID, ORG_ID, PERSONA_ID))
				.willReturn(Optional.of(vigente));

		CoberturaView editada = service.editar(
				delMostrador, PERSONA_ID, COBERTURA_ID,
				new CoberturaEdicionCommand(null, null, null, DESDE.plusMonths(3), null, 0L));

		assertThat(editada.estado()).isEqualTo("ACTIVA");
		assertThat(editada.vigenciaHasta()).isEqualTo(DESDE.plusMonths(3));
	}

	// =================================================================================
	// Apoyo
	// =================================================================================

	private static CoberturaAltaCommand altaFinanciada(boolean principal) {
		return new CoberturaAltaCommand(
				TipoCobertura.FINANCIADA, PLAN_ID, "62000123456", LocalDate.of(2027, 12, 31),
				DESDE, null, principal, null);
	}

	private static ReferenciaDeCobertura referencia() {
		return new ReferenciaDeCobertura(
				31L, "OSDE", "OSDE Binario", "PREPAGA",
				PLAN_ID, "210", "Plan 210",
				false, true, new BigDecimal("1500.00"), "ARS", DESDE, Instant.now());
	}

	private static CoberturaPaciente financiadaGuardada() {
		return conId(CoberturaPaciente.financiada(
				ORG_ID, PERSONA_ID, referencia(), "62000123456", null, DESDE, null, false, null));
	}

	private static Persona personaVigente() {
		Persona persona = new Persona(
				ORG_ID, TipoDocumento.DNI, "12345678", "Perez", "Ana", null, null, null, null);
		ReflectionTestUtils.setField(persona, "id", PERSONA_ID);
		return persona;
	}

	private static CoberturaPaciente conId(CoberturaPaciente cobertura) {
		if (cobertura.getId() == null) {
			ReflectionTestUtils.setField(cobertura, "id", COBERTURA_ID);
		}
		return cobertura;
	}
}
