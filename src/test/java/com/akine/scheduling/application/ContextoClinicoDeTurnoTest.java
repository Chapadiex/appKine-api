package com.akine.scheduling.application;

import com.akine.clinical.spi.ContextoClinicoDeAgenda;
import com.akine.clinical.spi.ContextoClinicoDeAgenda.CasoAgendable;
import com.akine.clinical.spi.ContextoClinicoDeAgenda.ItemAgendable;
import com.akine.clinical.spi.ContextoClinicoDeAgenda.PlanAgendable;
import com.akine.scheduling.domain.exception.ContextoClinicoInvalidoException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

/** La unica copia de la regla: lo que un caso y un plan tienen que cumplir para un turno (TURNO.md §28). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ContextoClinicoDeTurno")
class ContextoClinicoDeTurnoTest {

	private static final long ORG_ID = 1L;
	private static final long PERSONA = 128L;
	private static final long OTRA_PERSONA = 129L;
	private static final long OFERTA = 42L;
	private static final long CASO = 9L;
	private static final long PLAN = 57L;

	@Mock private ContextoClinicoDeAgenda contexto;

	@Test
	@DisplayName("sin caso ni plan no se valida nada: una primera consulta no tiene ninguno")
	void sin_contexto() {
		assertThat(contexto().resolver(ORG_ID, PERSONA, OFERTA, null, null)).isEqualTo(ContextoClinicoDeTurno.Resuelto.NINGUNO);

		verifyNoInteractions(contexto);
	}

	@Test
	@DisplayName("con plan y sin caso, el caso sale del plan: quien programa desde un plan no lo repite")
	void el_caso_sale_del_plan() {
		PlanAgendable plan = plan(PERSONA, "ACTIVO", OFERTA, CASO);
		dados(plan, caso(PERSONA, true));

		ContextoClinicoDeTurno.Resuelto resuelto = contexto().resolver(ORG_ID, PERSONA, OFERTA, null, PLAN);

		assertThat(resuelto.casoId()).isEqualTo(CASO);
		assertThat(resuelto.planId()).isEqualTo(PLAN);
		assertThat(resuelto.plan()).isEqualTo(plan);
	}

	@Test
	@DisplayName("con caso y sin plan alcanza con el caso, y no se lee ningun plan")
	void solo_caso() {
		dados(null, caso(PERSONA, true));

		ContextoClinicoDeTurno.Resuelto resuelto = contexto().resolver(ORG_ID, PERSONA, OFERTA, CASO, null);

		assertThat(resuelto.casoId()).isEqualTo(CASO);
		assertThat(resuelto.plan()).isNull();
	}

	static Stream<Arguments> rechazos() {
		return Stream.of(
				Arguments.of("plan inexistente", null, caso(PERSONA, true), CASO, PLAN, "no existe"),
				Arguments.of("plan de otro paciente", plan(OTRA_PERSONA, "ACTIVO", OFERTA, CASO), caso(PERSONA, true), null, PLAN, "otro paciente"),
				Arguments.of("plan suspendido", plan(PERSONA, "SUSPENDIDO", OFERTA, CASO), caso(PERSONA, true), null, PLAN, "esta suspendido"),
				Arguments.of("prestacion fuera del plan", plan(PERSONA, "ACTIVO", 999L, CASO), caso(PERSONA, true), null, PLAN, "no esta incluida"),
				Arguments.of("plan de otro caso", plan(PERSONA, "ACTIVO", OFERTA, 77L), caso(PERSONA, true), CASO, PLAN, "no es de ese caso"),
				Arguments.of("caso inexistente", null, null, CASO, null, "no existe"),
				Arguments.of("caso de otro paciente", null, caso(OTRA_PERSONA, true), CASO, null, "otro paciente"),
				Arguments.of("caso cerrado", null, caso(PERSONA, false), CASO, null, "cerrado"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("rechazos")
	@DisplayName("lo que no sirve para este turno es 409, con un motivo que se puede mostrar")
	void rechazos(String nombre, PlanAgendable plan, CasoAgendable caso, Long casoId, Long planId, String motivo) {
		dados(plan, caso);

		assertThatThrownBy(() -> contexto().resolver(ORG_ID, PERSONA, OFERTA, casoId, planId))
				.isInstanceOf(ContextoClinicoInvalidoException.class)
				.hasMessageContaining(motivo);
	}

	private ContextoClinicoDeTurno contexto() {
		return new ContextoClinicoDeTurno(contexto);
	}

	private void dados(PlanAgendable plan, CasoAgendable caso) {
		given(contexto.plan(ORG_ID, PLAN)).willReturn(Optional.ofNullable(plan));
		given(contexto.caso(ORG_ID, CASO)).willReturn(Optional.ofNullable(caso));
	}

	private static PlanAgendable plan(long personaId, String estado, long ofertaId, long casoId) {
		return new PlanAgendable(PLAN, casoId, personaId, 1, estado, 3, 4,
				List.of(new ItemAgendable(ofertaId, 7L, "Kinesiologia", 10, 0)));
	}

	private static CasoAgendable caso(long personaId, boolean activo) {
		return new CasoAgendable(CASO, personaId, 1, OFERTA, activo, null, List.of());
	}
}
