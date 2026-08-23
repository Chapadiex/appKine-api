package com.akine.organization.domain;

import com.akine.organization.domain.exception.InvalidSubscriptionTransitionException;
import com.akine.organization.domain.exception.SubscriptionSuspendedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifica los invariantes de la suscripcion (RF-M01-001, RF-M01-003, RN-M01-004).
 *
 * <p>Lo importante aca no son los campos sino tres garantias: que no exista ningun camino de
 * escritura que mueva el estado sin pasar por la maquina de estados, que cambiar de plan solo
 * se pueda con la suscripcion activa, y que el estado operativo del tenant se derive y no se
 * duplique.
 */
class SubscriptionTest {

	private static final Long ORG = 7L;
	private static final Long PLAN = 3L;
	private static final Instant INICIO = Instant.parse("2026-01-15T10:00:00Z");

	private Subscription nuevaSuscripcion() {
		return new Subscription(ORG, PLAN, INICIO);
	}

	private Subscription suscripcionEn(SubscriptionStatus estado) {
		Subscription suscripcion = nuevaSuscripcion();
		if (estado != SubscriptionStatus.ACTIVA) {
			suscripcion.transitionTo(estado);
		}
		return suscripcion;
	}

	@Test
	@DisplayName("Una suscripcion nueva nace ACTIVA y con los datos del alta")
	void nace_activa() {
		Subscription suscripcion = nuevaSuscripcion();

		// Nacer en cualquier otro estado dejaria un tenant recien creado sin operacion y sin
		// ninguna transicion en el historico que explique por que.
		assertThat(suscripcion.getStatus()).isEqualTo(SubscriptionStatus.ACTIVA);
		assertThat(suscripcion.getOrganizationId()).isEqualTo(ORG);
		assertThat(suscripcion.getPlanId()).isEqualTo(PLAN);
		assertThat(suscripcion.getStartedAt()).isEqualTo(INICIO);
		assertThat(suscripcion.isActive()).isTrue();
		assertThat(suscripcion.getDeletedAt()).isNull();
		assertThat(suscripcion.getId()).isNull();
		assertThat(suscripcion.getVersion()).isZero();
	}

	@Test
	@DisplayName("El estado inicial sale de la maquina de estados, no de una constante propia")
	void el_estado_inicial_lo_define_la_maquina() {
		// Si la entidad tuviera su propia copia del estado inicial, cambiar la maquina de
		// estados dejaria las dos definiciones en desacuerdo sin que nada falle.
		assertThat(nuevaSuscripcion().getStatus())
				.isEqualTo(SubscriptionStateMachine.ESTADO_INICIAL);
	}

	@Test
	@DisplayName("Una transicion permitida cambia el estado")
	void una_transicion_permitida_cambia_el_estado() {
		Subscription suscripcion = nuevaSuscripcion();

		suscripcion.transitionTo(SubscriptionStatus.SUSPENDIDA);
		assertThat(suscripcion.getStatus()).isEqualTo(SubscriptionStatus.SUSPENDIDA);

		suscripcion.transitionTo(SubscriptionStatus.ACTIVA);
		assertThat(suscripcion.getStatus()).isEqualTo(SubscriptionStatus.ACTIVA);

		suscripcion.transitionTo(SubscriptionStatus.CANCELADA);
		assertThat(suscripcion.getStatus()).isEqualTo(SubscriptionStatus.CANCELADA);
	}

	@ParameterizedTest
	@EnumSource(SubscriptionStatus.class)
	@DisplayName("Una transicion prohibida no toca el estado")
	void una_transicion_prohibida_no_toca_el_estado(SubscriptionStatus destino) {
		Subscription suscripcion = suscripcionEn(SubscriptionStatus.CANCELADA);

		// Que falle no alcanza: si la entidad asignara primero y validara despues, la
		// suscripcion quedaria mutada en memoria y un flush posterior persistiria el estado
		// invalido aunque el request devolviera 409.
		assertThatThrownBy(() -> suscripcion.transitionTo(destino))
				.isInstanceOf(InvalidSubscriptionTransitionException.class);
		assertThat(suscripcion.getStatus()).isEqualTo(SubscriptionStatus.CANCELADA);
	}

	@ParameterizedTest
	@EnumSource(SubscriptionStatus.class)
	@DisplayName("Repetir el estado actual falla en vez de no hacer nada")
	void repetir_el_estado_actual_falla(SubscriptionStatus estado) {
		Subscription suscripcion = suscripcionEn(estado);

		// El reintento de un "suspender" que ya se aplico no puede pasar en silencio: la capa
		// de aplicacion escribiria una segunda fila de historico por el mismo hecho.
		assertThatThrownBy(() -> suscripcion.transitionTo(estado))
				.isInstanceOf(InvalidSubscriptionTransitionException.class);
	}

	@Test
	@DisplayName("Cambiar de plan solo se permite con la suscripcion ACTIVA")
	void cambiar_de_plan_exige_estar_activa() {
		Subscription activa = nuevaSuscripcion();
		assertThatCode(() -> activa.changePlan(9L)).doesNotThrowAnyException();
		assertThat(activa.getPlanId()).isEqualTo(9L);
	}

	@ParameterizedTest
	@EnumSource(value = SubscriptionStatus.class, names = {"SUSPENDIDA", "CANCELADA"})
	@DisplayName("Cambiar de plan sin estar ACTIVA se rechaza y no toca el plan")
	void cambiar_de_plan_sin_estar_activa_se_rechaza(SubscriptionStatus estado) {
		Subscription suscripcion = suscripcionEn(estado);

		// Dejar pasar el cambio de plan con la suscripcion suspendida permitiria hacer un
		// upgrade comercial sobre un tenant que dejo de pagar.
		assertThatThrownBy(() -> suscripcion.changePlan(9L))
				.isInstanceOf(SubscriptionSuspendedException.class)
				.satisfies(error -> assertThat(
						((SubscriptionSuspendedException) error).getOrganizationId())
						.isEqualTo(ORG));
		assertThat(suscripcion.getPlanId()).isEqualTo(PLAN);
	}

	@Test
	@DisplayName("Cambiar de plan no es una transicion de estado")
	void cambiar_de_plan_no_mueve_el_estado() {
		Subscription suscripcion = nuevaSuscripcion();

		suscripcion.changePlan(9L);

		// El historico registra el cambio de plan con from_status = to_status = ACTIVA; si el
		// cambio de plan moviera el estado, ese invariante del historico se rompe.
		assertThat(suscripcion.getStatus()).isEqualTo(SubscriptionStatus.ACTIVA);
	}

	@ParameterizedTest
	@EnumSource(SubscriptionStatus.class)
	@DisplayName("Solo la suscripcion ACTIVA habilita mutaciones de negocio")
	void solo_activa_habilita_mutaciones(SubscriptionStatus estado) {
		Subscription suscripcion = suscripcionEn(estado);

		// Es el control que aplica el filtro de tenant: suspender bloquea la operacion
		// (RN-M01-002) sin destruir nada, asi que las lecturas siguen andando.
		assertThat(suscripcion.allowsBusinessMutations())
				.isEqualTo(estado == SubscriptionStatus.ACTIVA);
	}

	@ParameterizedTest
	@EnumSource(SubscriptionStatus.class)
	@DisplayName("Una organizacion dada de baja gana sobre cualquier estado de suscripcion")
	void la_baja_de_la_organizacion_gana(SubscriptionStatus estado) {
		Subscription suscripcion = suscripcionEn(estado);

		// La contradiccion "organizacion de baja con suscripcion activa" se resuelve siempre
		// del mismo lado: BAJA. Sin esta regla, el estado operativo dependeria de a quien se
		// le pregunte.
		assertThat(suscripcion.operationalStatus(false)).isEqualTo(OperationalStatus.BAJA);
	}

	@Test
	@DisplayName("Con la organizacion activa, el estado operativo mapea el de la suscripcion")
	void el_estado_operativo_mapea_la_suscripcion() {
		assertThat(suscripcionEn(SubscriptionStatus.ACTIVA).operationalStatus(true))
				.isEqualTo(OperationalStatus.ACTIVA);
		assertThat(suscripcionEn(SubscriptionStatus.SUSPENDIDA).operationalStatus(true))
				.isEqualTo(OperationalStatus.SUSPENDIDA);
		assertThat(suscripcionEn(SubscriptionStatus.CANCELADA).operationalStatus(true))
				.isEqualTo(OperationalStatus.CANCELADA);
	}

	@ParameterizedTest
	@EnumSource(SubscriptionStatus.class)
	@DisplayName("Todo estado de suscripcion tiene un estado operativo definido")
	void todo_estado_tiene_operativo(SubscriptionStatus estado) {
		// Agregar un estado nuevo sin mapearlo dejaria el switch incompleto: este test lo
		// obliga a decidirse en lugar de fallar en el primer request del tenant.
		assertThat(suscripcionEn(estado).operationalStatus(true)).isNotNull();
	}

	@Test
	@DisplayName("Los estados operativos son exactamente los cuatro del diseno")
	void los_estados_operativos_son_cuatro() {
		// BAJA no es un estado de suscripcion sino de la organizacion: por eso son cuatro y
		// no tres, y por eso no puede colapsarse con CANCELADA.
		assertThat(OperationalStatus.values()).containsExactly(
				OperationalStatus.ACTIVA,
				OperationalStatus.SUSPENDIDA,
				OperationalStatus.CANCELADA,
				OperationalStatus.BAJA);
		assertThat(OperationalStatus.valueOf("BAJA")).isEqualTo(OperationalStatus.BAJA);
	}

	@Test
	@DisplayName("La suscripcion hidratada por JPA no habilita mutaciones sin estado")
	void la_hidratada_por_jpa_no_habilita_nada() {
		// El constructor sin argumentos es el que usa JPA: si allowsBusinessMutations no
		// tolerara un estado todavia sin cargar, la hidratacion explotaria con NPE.
		Subscription vacia = new Subscription();

		assertThat(vacia.allowsBusinessMutations()).isFalse();
		assertThat(vacia.getStatus()).isNull();
	}
}
