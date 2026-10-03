package com.akine.encounter.infrastructure;

import com.akine.clinical.spi.HistoriaClinicaDirectory;
import com.akine.clinical.spi.HistoriaClinicaSnapshot;
import com.akine.encounter.domain.port.SesionRepositoryPort;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.scheduling.spi.TurnoDirectory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * La relacion asistencial real (C-3): el algoritmo por vinculos de la sonda, con los puertos
 * mockeados.
 *
 * <p>Los ids son todos distintos entre si a proposito: si la sonda cruzara dos argumentos al
 * llamar a un puerto, el {@code verify} con valores exactos lo detecta. Que las consultas lleven
 * {@code organizationId}, {@code consultorioId} y el {@code membershipId} del vinculo es lo que
 * este test fija; que el SQL los use es cosa de {@code RelacionAsistencialIT}.
 *
 * <p>Los vinculos son {@code ConsultorioMembershipSnapshot} reales y no mocks: lo que decide si
 * un vinculo cuenta es {@code validAt} y {@code cubreConsultorio}, y mockearlos probaria la
 * sonda contra una idea del predicado y no contra el predicado.
 */
@DisplayName("Relacion asistencial real: la sonda de encounter (C-3)")
class EncounterRelacionAsistencialProbeTest {

	private static final long ORG = 7L;
	private static final long SEDE = 20L;
	private static final long OTRA_SEDE = 21L;
	private static final long CUENTA = 501L;
	private static final long PERSONA = 1204L;
	private static final long HISTORIA = 3001L;
	private static final long VINCULO_SEDE = 88L;
	private static final long VINCULO_ORG = 89L;

	private static final Instant AYER = Instant.now().minus(1, ChronoUnit.DAYS);
	private static final Instant HACE_UN_ANIO = Instant.now().minus(365, ChronoUnit.DAYS);

	private final SesionRepositoryPort sesiones = mock(SesionRepositoryPort.class);
	private final TurnoDirectory turnos = mock(TurnoDirectory.class);
	private final HistoriaClinicaDirectory historias = mock(HistoriaClinicaDirectory.class);
	private final ConsultorioMembershipDirectory vinculos =
			mock(ConsultorioMembershipDirectory.class);

	private final EncounterRelacionAsistencialProbe sonda =
			new EncounterRelacionAsistencialProbe(sesiones, turnos, historias, vinculos);

	// =================================================================================
	// Vinculos que no cuentan
	// =================================================================================

	@Test
	@DisplayName("sin vinculos de la cuenta no hay relacion, y no se consulta nada mas")
	void sin_vinculos_no_hay_relacion() {
		// AC-1
		given(vinculos.findByAccount(ORG, CUENTA)).willReturn(List.of());

		assertThat(consultar(SEDE)).isFalse();

		verify(vinculos).findByAccount(ORG, CUENTA);
		verifyNoInteractions(historias, sesiones, turnos);
	}

	@Test
	@DisplayName("un vinculo SUSPENDIDO no cuenta, aunque haya sesion y turno")
	void vinculo_suspendido_no_cuenta() {
		// AC-1
		assertNoCuenta(vinculo(VINCULO_SEDE, SEDE, "SUSPENDIDA", AYER, null, true, false));
	}

	@Test
	@DisplayName("un vinculo REVOCADO no cuenta, aunque haya sesion y turno")
	void vinculo_revocado_no_cuenta() {
		// AC-1
		assertNoCuenta(vinculo(VINCULO_SEDE, SEDE, "REVOCADA", AYER, null, true, false));
	}

	@Test
	@DisplayName("un vinculo VENCIDO no cuenta, aunque haya sesion y turno")
	void vinculo_vencido_no_cuenta() {
		// AC-1
		assertNoCuenta(vinculo(VINCULO_SEDE, SEDE, "ACTIVA", HACE_UN_ANIO, AYER, true, true));
	}

	@Test
	@DisplayName("un vinculo que todavia no empezo no cuenta")
	void vinculo_futuro_no_cuenta() {
		// AC-1
		Instant manana = Instant.now().plus(1, ChronoUnit.DAYS);
		assertNoCuenta(vinculo(VINCULO_SEDE, SEDE, "ACTIVA", manana, null, true, true));
	}

	@Test
	@DisplayName("un vinculo dado de baja logica no cuenta, aunque haya sesion y turno")
	void vinculo_dado_de_baja_no_cuenta() {
		// AC-1
		assertNoCuenta(vinculo(VINCULO_SEDE, SEDE, "ACTIVA", AYER, null, false, true));
	}

	@Test
	@DisplayName("un vinculo de OTRA sede no cuenta en esta, aunque haya sesion y turno")
	void vinculo_de_otra_sede_no_cuenta() {
		// AC-1
		assertNoCuenta(vinculo(VINCULO_SEDE, OTRA_SEDE, "ACTIVA", AYER, null, true, true));
	}

	// =================================================================================
	// Historia clinica
	// =================================================================================

	@Test
	@DisplayName("la persona sin historia clinica en la organizacion no tiene relacion")
	void sin_historia_clinica_no_hay_relacion() {
		// AC-1
		darVinculos(vinculoDeSede());
		given(historias.find(ORG, PERSONA)).willReturn(Optional.empty());

		assertThat(consultar(SEDE)).isFalse();

		verify(historias).find(ORG, PERSONA);
		verifyNoInteractions(sesiones, turnos);
	}

	// =================================================================================
	// Sesion y turno
	// =================================================================================

	@Test
	@DisplayName("una sesion del actor en esa historia y sede alcanza: no se consultan los turnos")
	void una_sesion_del_actor_alcanza() {
		// AC-1
		darVinculos(vinculoDeSede());
		darHistoria();
		given(sesiones.existeSesionDelActor(ORG, SEDE, HISTORIA, VINCULO_SEDE, CUENTA))
				.willReturn(true);

		assertThat(consultar(SEDE)).isTrue();

		verify(sesiones).existeSesionDelActor(ORG, SEDE, HISTORIA, VINCULO_SEDE, CUENTA);
		verifyNoInteractions(turnos);
	}

	@Test
	@DisplayName("sin sesion pero con un turno vivo del profesional con la persona, hay relacion")
	void un_turno_vivo_alcanza_sin_sesion() {
		// AC-1
		darVinculos(vinculoDeSede());
		darHistoria();
		given(turnos.existeTurnoVivoDeProfesionalConPersona(ORG, SEDE, VINCULO_SEDE, PERSONA))
				.willReturn(true);

		assertThat(consultar(SEDE)).isTrue();

		InOrder orden = inOrder(sesiones, turnos);
		orden.verify(sesiones).existeSesionDelActor(ORG, SEDE, HISTORIA, VINCULO_SEDE, CUENTA);
		orden.verify(turnos)
				.existeTurnoVivoDeProfesionalConPersona(ORG, SEDE, VINCULO_SEDE, PERSONA);
	}

	@Test
	@DisplayName("sin sesion ni turno no hay relacion, y las dos consultas llevaron org, sede y vinculo")
	void sin_sesion_ni_turno_no_hay_relacion() {
		// AC-1
		darVinculos(vinculoDeSede());
		darHistoria();

		assertThat(consultar(SEDE)).isFalse();

		// Los valores exactos (y no `any()`) son la verificacion: organizationId, consultorioId
		// y el membershipId del vinculo viajan a las dos consultas, y ningun id se cruza.
		verify(sesiones).existeSesionDelActor(ORG, SEDE, HISTORIA, VINCULO_SEDE, CUENTA);
		verify(turnos).existeTurnoVivoDeProfesionalConPersona(ORG, SEDE, VINCULO_SEDE, PERSONA);
		verifyNoMoreInteractions(sesiones, turnos);
	}

	// =================================================================================
	// Alcance del vinculo
	// =================================================================================

	@Test
	@DisplayName("un vinculo de sede cubre su sede")
	void el_vinculo_de_sede_cubre_su_sede() {
		// AC-1
		darVinculos(vinculoDeSede());
		darHistoria();
		given(sesiones.existeSesionDelActor(ORG, SEDE, HISTORIA, VINCULO_SEDE, CUENTA))
				.willReturn(true);

		assertThat(consultar(SEDE)).isTrue();
	}

	@Test
	@DisplayName("un vinculo de sede NO cubre otra sede: ni se consulta con su membershipId")
	void el_vinculo_de_sede_no_cubre_otra_sede() {
		// AC-1
		darVinculos(vinculoDeSede());
		darHistoria();
		given(sesiones.existeSesionDelActor(ORG, OTRA_SEDE, HISTORIA, VINCULO_SEDE, CUENTA))
				.willReturn(true);
		given(turnos.existeTurnoVivoDeProfesionalConPersona(
				ORG, OTRA_SEDE, VINCULO_SEDE, PERSONA)).willReturn(true);

		assertThat(consultar(OTRA_SEDE)).isFalse();

		verifyNoInteractions(historias, sesiones, turnos);
	}

	@Test
	@DisplayName("un vinculo de alcance organizacion cubre cualquier sede")
	void el_vinculo_de_organizacion_cubre_cualquier_sede() {
		// AC-1
		darVinculos(vinculoDeOrganizacion());
		darHistoria();
		given(turnos.existeTurnoVivoDeProfesionalConPersona(
				ORG, OTRA_SEDE, VINCULO_ORG, PERSONA)).willReturn(true);

		assertThat(consultar(OTRA_SEDE)).isTrue();
		assertThat(consultar(SEDE))
				.as("y en la primera sede el mismo vinculo se consulta, sin evidencia: false")
				.isFalse();

		verify(sesiones).existeSesionDelActor(ORG, OTRA_SEDE, HISTORIA, VINCULO_ORG, CUENTA);
		verify(sesiones).existeSesionDelActor(ORG, SEDE, HISTORIA, VINCULO_ORG, CUENTA);
	}

	@Test
	@DisplayName("de varios vinculos solo se consultan los que cubren la sede")
	void solo_se_consultan_los_vinculos_que_cubren_la_sede() {
		// AC-1
		darVinculos(vinculoDeSede(), vinculoDeOrganizacion());
		darHistoria();

		assertThat(consultar(OTRA_SEDE)).isFalse();

		verify(sesiones).existeSesionDelActor(ORG, OTRA_SEDE, HISTORIA, VINCULO_ORG, CUENTA);
		verify(sesiones, never())
				.existeSesionDelActor(ORG, OTRA_SEDE, HISTORIA, VINCULO_SEDE, CUENTA);
		verify(turnos, never())
				.existeTurnoVivoDeProfesionalConPersona(ORG, OTRA_SEDE, VINCULO_SEDE, PERSONA);
	}

	@Test
	@DisplayName("con varios vinculos validos se prueba cada uno, en orden, hasta el primer true")
	void se_prueba_cada_vinculo_hasta_el_primer_true() {
		// AC-1
		darVinculos(vinculoDeSede(), vinculoDeOrganizacion());
		darHistoria();
		given(turnos.existeTurnoVivoDeProfesionalConPersona(ORG, SEDE, VINCULO_ORG, PERSONA))
				.willReturn(true);

		assertThat(consultar(SEDE)).isTrue();

		InOrder orden = inOrder(sesiones, turnos);
		orden.verify(sesiones).existeSesionDelActor(ORG, SEDE, HISTORIA, VINCULO_SEDE, CUENTA);
		orden.verify(turnos)
				.existeTurnoVivoDeProfesionalConPersona(ORG, SEDE, VINCULO_SEDE, PERSONA);
		orden.verify(sesiones).existeSesionDelActor(ORG, SEDE, HISTORIA, VINCULO_ORG, CUENTA);
		orden.verify(turnos)
				.existeTurnoVivoDeProfesionalConPersona(ORG, SEDE, VINCULO_ORG, PERSONA);
	}

	@Test
	@DisplayName("al primer true se corta: el vinculo siguiente no se consulta")
	void al_primer_true_se_corta() {
		// AC-1
		darVinculos(vinculoDeSede(), vinculoDeOrganizacion());
		darHistoria();
		given(sesiones.existeSesionDelActor(ORG, SEDE, HISTORIA, VINCULO_SEDE, CUENTA))
				.willReturn(true);

		assertThat(consultar(SEDE)).isTrue();

		verify(sesiones, never())
				.existeSesionDelActor(ORG, SEDE, HISTORIA, VINCULO_ORG, CUENTA);
		verify(turnos, never())
				.existeTurnoVivoDeProfesionalConPersona(ORG, SEDE, VINCULO_ORG, PERSONA);
	}

	// =================================================================================
	// Operaciones
	// =================================================================================

	private boolean consultar(long sede) {
		return sonda.tieneRelacionAsistencial(ORG, sede, CUENTA, PERSONA);
	}

	/**
	 * Un vinculo que no cuenta, con historia, sesion y turno "a favor": la respuesta tiene que
	 * ser {@code false} y no se puede haber consultado ninguno de los tres puertos, porque la
	 * sonda corta antes de preguntar por evidencia de un vinculo que no vale.
	 */
	private void assertNoCuenta(ConsultorioMembershipSnapshot vinculo) {
		darVinculos(vinculo);
		darHistoria();
		given(sesiones.existeSesionDelActor(ORG, SEDE, HISTORIA, vinculo.membershipId(), CUENTA))
				.willReturn(true);
		given(turnos.existeTurnoVivoDeProfesionalConPersona(
				ORG, SEDE, vinculo.membershipId(), PERSONA)).willReturn(true);

		assertThat(consultar(SEDE)).isFalse();

		verifyNoInteractions(historias, sesiones, turnos);
	}

	private void darVinculos(ConsultorioMembershipSnapshot... snapshots) {
		given(vinculos.findByAccount(ORG, CUENTA)).willReturn(List.of(snapshots));
	}

	private void darHistoria() {
		given(historias.find(ORG, PERSONA)).willReturn(Optional.of(new HistoriaClinicaSnapshot(
				HISTORIA, ORG, PERSONA, Instant.parse("2025-01-01T00:00:00Z"), true, 0L)));
	}

	private static ConsultorioMembershipSnapshot vinculoDeSede() {
		return vinculo(VINCULO_SEDE, SEDE, "ACTIVA", AYER, null, true, true);
	}

	private static ConsultorioMembershipSnapshot vinculoDeOrganizacion() {
		return vinculo(VINCULO_ORG, null, "ACTIVA", AYER, null, true, true);
	}

	private static ConsultorioMembershipSnapshot vinculo(long membershipId, Long consultorioId,
			String estado, Instant desde, Instant hasta, boolean active, boolean habilitada) {
		return new ConsultorioMembershipSnapshot(membershipId, CUENTA, ORG, consultorioId,
				"PROFESIONAL", estado, desde, hasta, active, habilitada);
	}
}
