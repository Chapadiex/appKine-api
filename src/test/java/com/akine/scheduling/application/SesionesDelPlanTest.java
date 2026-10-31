package com.akine.scheduling.application;

import com.akine.clinical.spi.ContextoClinicoDeAgenda.ItemAgendable;
import com.akine.clinical.spi.ContextoClinicoDeAgenda.PlanAgendable;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.exception.PlanSinSesionesException;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import com.akine.scheduling.spi.AtencionProbe;
import com.akine.scheduling.spi.AtencionProbe.AtencionDeTurno;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

/**
 * La cuenta que pone el tope de un plan (docs/diseno/AKINE-turnos-flujo-unico.md §4):
 * pendientes = planificadas - realizadas - programadas - ausencias que consumen, y nunca menos de cero.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SesionesDelPlan")
class SesionesDelPlanTest {

	private static final long ORG_ID = 1L;
	private static final long OFERTA_ID = 42L;
	private static final long OTRA_OFERTA = 43L;
	private static final long PLAN_ID = 57L;

	@Mock private TurnoRepositoryPort turnos;
	@Mock private AtencionProbe atenciones;

	@Test
	@DisplayName("una atencion cerrada no se cuenta dos veces, una ausencia consume si se decidio y un cancelado devuelve la sesion")
	void la_cuenta_completa() {
		Turno atendido = turno(1L, OFERTA_ID);
		Turno enCurso = turno(2L, OFERTA_ID);
		Turno programado = turno(3L, OFERTA_ID);
		Turno ausenteQueConsume = ausente(4L, true);
		Turno ausenteQueNoConsume = ausente(5L, false);
		Turno cancelado = turno(6L, OFERTA_ID);
		cancelado.cancelar("No puede venir", 9L, Instant.now());
		Turno deOtraPrestacion = turno(7L, OTRA_OFERTA);
		dados(Map.of(atendido.getId(), new AtencionDeTurno(70L, true), enCurso.getId(), new AtencionDeTurno(71L, false)),
				atendido, enCurso, programado, ausenteQueConsume, ausenteQueNoConsume, cancelado, deOtraPrestacion);

		// De las 2 realizadas, una es la del turno atendido y la otra fue sin turno.
		SesionesDelPlan.ItemDelPlan item = sesiones().resumir(ORG_ID, plan(10, 2)).item(OFERTA_ID);

		assertThat(item.realizadas()).isEqualTo(2);
		assertThat(item.programadas()).as("en curso y programado").isEqualTo(2);
		assertThat(item.ausenciasQueConsumen()).isEqualTo(1);
		assertThat(item.pendientes()).isEqualTo(10 - 2 - 2 - 1);
	}

	@Test
	@DisplayName("cada turno vivo sabe que sesion es, arrancando despues de lo atendido sin pasar por un turno del plan")
	void numero_de_sesion_de_cada_turno() {
		Turno atendido = turno(1L, OFERTA_ID);
		Turno programado = turno(2L, OFERTA_ID);
		Turno ausenteQueNoConsume = ausente(3L, false);
		Turno ausenteQueConsume = ausente(4L, true);
		dados(Map.of(atendido.getId(), new AtencionDeTurno(70L, true)),
				atendido, programado, ausenteQueNoConsume, ausenteQueConsume);

		Map<Long, Integer> numeros = sesiones().resumir(ORG_ID, plan(10, 3)).numeroDeTurno();

		assertThat(numeros)
				.as("3 realizadas, 1 con turno: el primer turno del plan es la tercera sesion")
				.containsOnly(Map.entry(1L, 3), Map.entry(2L, 4), Map.entry(4L, 5));
	}

	@Test
	@DisplayName("los pendientes nunca son negativos: un plan achicado despues de programar da cero")
	void pendientes_nunca_negativos() {
		dados(Map.of(), turno(1L, OFERTA_ID), turno(2L, OFERTA_ID), turno(3L, OFERTA_ID));

		assertThat(sesiones().resumir(ORG_ID, plan(2, 0)).item(OFERTA_ID).pendientes()).isZero();
	}

	@Test
	@DisplayName("con lugar pasa, sin lugar dice cuantas quedan y cuantas se pidieron, y una prestacion ajena tiene cero")
	void exigir_disponibles() {
		dados(Map.of(), turno(1L, OFERTA_ID));
		PlanAgendable plan = plan(3, 0);

		assertThatCode(() -> sesiones().exigirDisponibles(ORG_ID, plan, OFERTA_ID, 2)).doesNotThrowAnyException();
		assertThatThrownBy(() -> sesiones().exigirDisponibles(ORG_ID, plan, OFERTA_ID, 3))
				.isInstanceOfSatisfying(PlanSinSesionesException.class, sinSesiones -> {
					assertThat(sinSesiones.getPendientes()).isEqualTo(2);
					assertThat(sinSesiones.getPedidas()).isEqualTo(3);
				})
				.hasMessageContaining("2 sesiones pendientes");
		assertThatThrownBy(() -> sesiones().exigirDisponibles(ORG_ID, plan, 999L, 1))
				.isInstanceOfSatisfying(PlanSinSesionesException.class,
						sinSesiones -> assertThat(sinSesiones.getPendientes()).isZero());
	}

	private SesionesDelPlan sesiones() {
		return new SesionesDelPlan(turnos, atenciones);
	}

	private void dados(Map<Long, AtencionDeTurno> atencionDe, Turno... delPlan) {
		given(turnos.findDeLosPlanes(ORG_ID, List.of(PLAN_ID))).willReturn(List.of(delPlan));
		given(atenciones.atencionesDe(anyLong(), any())).willReturn(atencionDe);
	}

	private static PlanAgendable plan(int planificadas, int realizadas) {
		return new PlanAgendable(PLAN_ID, 9L, 128L, 1, "ACTIVO", 3, 4, List.of(
				new ItemAgendable(OFERTA_ID, 7L, "Kinesiologia", planificadas, realizadas)));
	}

	private static Turno turno(long id, long ofertaId) {
		Instant inicio = Instant.now().plus(Duration.ofDays(id));
		Turno turno = new Turno(ORG_ID, 7L, ofertaId, 128L, 31L, null, inicio, inicio.plus(Duration.ofHours(1)),
				9L, Instant.now(), null, null);
		ReflectionTestUtils.setField(turno, "id", id);
		turno.asociarContexto(9L, PLAN_ID);
		return turno;
	}

	private static Turno ausente(long id, boolean consume) {
		Turno turno = turno(id, OFERTA_ID);
		turno.marcarAusente(consume, turno.getInicio().plusSeconds(60));
		return turno;
	}
}
