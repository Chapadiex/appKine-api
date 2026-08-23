package com.akine.organization.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica el historico de la suscripcion (RN-M01-002, RNF-M01-006).
 *
 * <p>La fila registra dos hechos distintos con la misma forma: el cambio de estado y el cambio
 * de plan. {@link SubscriptionTransition#isPlanChange()} es lo que los separa al leer el
 * historico, y equivocarse ahi hace que un cambio de plan aparezca como una transicion vacia
 * de ACTIVA a ACTIVA.
 */
class SubscriptionTransitionTest {

	private static final Long ORG = 8L;
	private static final Long SUSCRIPCION = 15L;
	private static final Instant CUANDO = Instant.parse("2026-05-05T05:05:05Z");

	@Test
	@DisplayName("El alta inicial se registra con origen nulo y destino ACTIVA")
	void el_alta_inicial_tiene_origen_nulo() {
		SubscriptionTransition alta = new SubscriptionTransition(
				ORG, SUSCRIPCION, null, SubscriptionStatus.ACTIVA, null, 1L, null, null, CUANDO);

		// from_status null es lo que identifica el nacimiento de la suscripcion en el
		// historico; sin esa marca no se distingue del resto de las transiciones.
		assertThat(alta.getFromStatus()).isNull();
		assertThat(alta.getToStatus()).isEqualTo(SubscriptionStatus.ACTIVA);
		assertThat(alta.getOrganizationId()).isEqualTo(ORG);
		assertThat(alta.getSubscriptionId()).isEqualTo(SUSCRIPCION);
		assertThat(alta.getOccurredAt()).isEqualTo(CUANDO);
		assertThat(alta.getId()).isNull();
	}

	@Test
	@DisplayName("Una suspension registra el motivo y el actor")
	void la_suspension_registra_motivo_y_actor() {
		SubscriptionTransition suspension = new SubscriptionTransition(
				ORG, SUSCRIPCION, SubscriptionStatus.ACTIVA, SubscriptionStatus.SUSPENDIDA,
				1L, 1L, "falta de pago", 77L, CUANDO);

		// Sin motivo ni actor, el historico no responde "quien y por que la suspendio" seis
		// meses despues, que es exactamente cuando se pregunta.
		assertThat(suspension.getReason()).isEqualTo("falta de pago");
		assertThat(suspension.getActorAccountId()).isEqualTo(77L);
		assertThat(suspension.getFromStatus()).isEqualTo(SubscriptionStatus.ACTIVA);
		assertThat(suspension.getToStatus()).isEqualTo(SubscriptionStatus.SUSPENDIDA);
	}

	@Test
	@DisplayName("Un actor nulo significa sistema")
	void actor_nulo_significa_sistema() {
		SubscriptionTransition delSistema = new SubscriptionTransition(
				ORG, SUSCRIPCION, null, SubscriptionStatus.ACTIVA, null, 1L, null, null, CUANDO);

		// El onboarding self-service no tiene admin detras: el null es un valor de negocio,
		// no un dato faltante.
		assertThat(delSistema.getActorAccountId()).isNull();
		assertThat(delSistema.getReason()).isNull();
	}

	@Test
	@DisplayName("Un cambio de plan se reconoce por los ids, no por el estado")
	void el_cambio_de_plan_se_reconoce_por_los_ids() {
		SubscriptionTransition cambio = new SubscriptionTransition(
				ORG, SUSCRIPCION, SubscriptionStatus.ACTIVA, SubscriptionStatus.ACTIVA,
				1L, 2L, "upgrade", 77L, CUANDO);

		// En un cambio de plan from_status y to_status son ambos ACTIVA: si isPlanChange
		// mirara el estado, esta fila pareceria una transicion vacia y el historico no
		// mostraria el upgrade.
		assertThat(cambio.isPlanChange()).isTrue();
		assertThat(cambio.getFromPlanId()).isEqualTo(1L);
		assertThat(cambio.getToPlanId()).isEqualTo(2L);
	}

	@Test
	@DisplayName("Una transicion de estado sobre el mismo plan no es cambio de plan")
	void mismo_plan_no_es_cambio_de_plan() {
		SubscriptionTransition suspension = new SubscriptionTransition(
				ORG, SUSCRIPCION, SubscriptionStatus.ACTIVA, SubscriptionStatus.SUSPENDIDA,
				3L, 3L, "falta de pago", 77L, CUANDO);

		assertThat(suspension.isPlanChange()).isFalse();
	}

	@Test
	@DisplayName("Los ids de plan iguales se comparan por valor")
	void los_ids_de_plan_se_comparan_por_valor() {
		SubscriptionTransition sinCambio = new SubscriptionTransition(
				ORG, SUSCRIPCION, SubscriptionStatus.ACTIVA, SubscriptionStatus.ACTIVA,
				Long.valueOf(5000L), Long.valueOf(5000L), null, 77L, CUANDO);

		// Los ids vienen de la base como objetos Long distintos. Con == en lugar de equals,
		// todo plan con id por encima de la cache de Long (127) reportaria un cambio de plan
		// inexistente.
		assertThat(sinCambio.isPlanChange()).isFalse();
	}

	@Test
	@DisplayName("El alta registra el plan de destino como cambio de plan")
	void el_alta_registra_el_plan_de_destino() {
		SubscriptionTransition alta = new SubscriptionTransition(
				ORG, SUSCRIPCION, null, SubscriptionStatus.ACTIVA, null, 1L, null, null, CUANDO);

		// El plan de origen nulo con destino cargado es el alta: el historico tiene que poder
		// responder con que plan nacio la suscripcion.
		assertThat(alta.isPlanChange()).isTrue();
		assertThat(alta.getFromPlanId()).isNull();
	}

	@Test
	@DisplayName("Sin plan de destino no hay cambio de plan")
	void sin_plan_de_destino_no_hay_cambio() {
		SubscriptionTransition soloEstado = new SubscriptionTransition(
				ORG, SUSCRIPCION, SubscriptionStatus.ACTIVA, SubscriptionStatus.CANCELADA,
				null, null, "cierre", 77L, CUANDO);

		assertThat(soloEstado.isPlanChange()).isFalse();
	}

	@Test
	@DisplayName("La fila del historico no tiene un solo setter")
	void la_fila_es_append_only() {
		// Append-only hecho imposible de violar, no declarado en un comentario: si apareciera
		// un setter, una correccion "puntual" podria reescribir el historico que sostiene la
		// auditoria.
		assertThat(SubscriptionTransition.class.getMethods())
				.noneMatch(metodo -> metodo.getName().startsWith("set"));
	}
}
