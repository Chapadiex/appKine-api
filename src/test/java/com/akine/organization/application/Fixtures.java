package com.akine.organization.application;

import com.akine.organization.domain.AccountActiveContext;
import com.akine.organization.domain.Consultorio;
import com.akine.organization.domain.Membership;
import com.akine.organization.domain.Organization;
import com.akine.organization.domain.OrganizationOnboarding;
import com.akine.organization.domain.Plan;
import com.akine.organization.domain.PlanLimit;
import com.akine.organization.domain.RoleCode;
import com.akine.organization.domain.Subscription;
import com.akine.organization.domain.SubscriptionStatus;
import com.akine.organization.spi.LimitCode;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Entidades de prueba con id asignado.
 *
 * <p>Las entities no exponen setter de {@code id} —lo asigna la base— asi que en un test sin
 * base hay que ponerlo por reflexion. La alternativa seria agregarles un setter solo para los
 * tests, que es exactamente como se abre la puerta a que produccion lo use.
 *
 * <p>Datos sinteticos, sin excepcion: ningun dato real de paciente ni de centro entra en un
 * fixture.
 */
final class Fixtures {

	static final long ORG_ID = 10L;
	static final long CONSULTORIO_ID = 20L;
	static final long OTRO_CONSULTORIO_ID = 21L;
	static final long ACCOUNT_ID = 30L;
	static final long PLAN_BASICO_ID = 40L;
	static final long PLAN_PRO_ID = 41L;
	static final long SUBSCRIPTION_ID = 50L;
	static final long MEMBERSHIP_ID = 60L;

	private Fixtures() {
	}

	static <T> T conId(T entidad, long id) {
		ReflectionTestUtils.setField(entidad, "id", id);
		return entidad;
	}

	static Organization organizacion() {
		return conId(new Organization("Centro Kine Norte", "centro-kine-norte",
				"America/Argentina/Cordoba"), ORG_ID);
	}

	static Consultorio consultorio(long id, String nombre) {
		return conId(new Consultorio(ORG_ID, nombre), id);
	}

	static Plan plan(long id, String code) {
		return conId(new Plan(code, "Plan " + code), id);
	}

	static Subscription suscripcionActiva(long planId) {
		return conId(new Subscription(ORG_ID, planId, Instant.now()), SUBSCRIPTION_ID);
	}

	static PlanLimit limite(long planId, LimitCode code, Integer valor) {
		return conId(new PlanLimit(planId, code, valor), 70L);
	}

	/** Membership vigente de alcance ORGANIZACION (consultorioId nulo). */
	static Membership membershipVigente() {
		return conId(new Membership(ORG_ID, null, ACCOUNT_ID, RoleCode.ORG_ADMIN, true,
				Instant.now().minus(30, ChronoUnit.DAYS)), MEMBERSHIP_ID);
	}

	/** Misma membership, pero con la vigencia ya cerrada en el pasado. */
	static Membership membershipVencida() {
		Membership membership = membershipVigente();
		membership.endValidity(Instant.now().minus(1, ChronoUnit.DAYS));
		return membership;
	}

	static AccountActiveContext puntero(long organizationId, long consultorioId) {
		return conId(new AccountActiveContext(ACCOUNT_ID, organizationId, consultorioId), 80L);
	}

	/** Membership vigente acotada a UNA sede (consultorioId no nulo). */
	static Membership membershipDeSede(long consultorioId, RoleCode rol) {
		return conId(new Membership(ORG_ID, consultorioId, ACCOUNT_ID, rol, false,
				Instant.now().minus(30, ChronoUnit.DAYS)), MEMBERSHIP_ID);
	}

	/** Membership vigente de alcance ORGANIZACION con el rol indicado. */
	static Membership membershipVigenteCon(RoleCode rol) {
		return conId(new Membership(ORG_ID, null, ACCOUNT_ID, rol, false,
				Instant.now().minus(30, ChronoUnit.DAYS)), MEMBERSHIP_ID);
	}

	static Subscription suscripcionEn(long planId, SubscriptionStatus estado) {
		Subscription subscription = suscripcionActiva(planId);
		if (estado != SubscriptionStatus.ACTIVA) {
			subscription.transitionTo(estado);
		}
		return subscription;
	}

	static OrganizationOnboarding onboarding(String clave, String hash) {
		return conId(new OrganizationOnboarding(clave, hash, ACCOUNT_ID, ORG_ID,
				CONSULTORIO_ID, MEMBERSHIP_ID, Instant.now()), 90L);
	}
}
