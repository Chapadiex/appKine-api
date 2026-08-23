package com.akine.organization.api;

import com.akine.organization.application.IdempotencyKeyConflictException;
import com.akine.organization.application.PlanNotFoundException;
import com.akine.organization.domain.SubscriptionStatus;
import com.akine.organization.domain.exception.ContextNotAuthorizedException;
import com.akine.organization.domain.exception.FeatureNotAvailableException;
import com.akine.organization.domain.exception.InvalidSubscriptionTransitionException;
import com.akine.organization.domain.exception.OrganizationNotFoundException;
import com.akine.organization.domain.exception.PlanLimitExceededException;
import com.akine.organization.domain.exception.SubscriptionSuspendedException;
import com.akine.organization.spi.FeatureCode;
import com.akine.organization.spi.LimitCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Traduccion de las excepciones de dominio de {@code organization} a Problem Details.
 *
 * <p>Vive en el advice del modulo y no en {@code GlobalExceptionHandler} porque mapearlas en
 * {@code platform} exigiria importar {@code organization.domain} y cerraria un ciclo entre
 * modulos. Lo que se verifica aca es el contrato de cada respuesta: el codigo, el {@code type}
 * estable que el cliente ramifica, y que nada interno se filtre.
 */
class OrganizationProblemHandlerTest {

	private final OrganizationProblemHandler handler = new OrganizationProblemHandler();

	@Test
	@DisplayName("Una organizacion no accesible responde 404 con un cuerpo que no dice cual era")
	void organizacion_no_accesible_es_404_generico() {
		ProblemDetail problem =
				handler.handleOrganizationNotFound(new OrganizationNotFoundException(99L));

		assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(problem.getTitle()).isEqualTo("Recurso no encontrado");
		assertThat(problem.getType())
				.isEqualTo(URI.create("https://akine.app/problems/not-found"));
		assertThat(problem.getDetail())
				.isEqualTo("El recurso solicitado no existe o no esta disponible.")
				.doesNotContain("99");
	}

	@Test
	@DisplayName("Un contexto no autorizado tambien es 404: un 403 confirmaria que existe")
	void contexto_no_autorizado_es_404_y_no_403() {
		ProblemDetail problem = handler.handleContextNotAuthorized(
				new ContextNotAuthorizedException(7L, 99L, 500L));

		assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(problem.getType())
				.isEqualTo(URI.create("https://akine.app/problems/not-found"));
		assertThat(problem.getDetail()).doesNotContain("99").doesNotContain("500");
	}

	@Test
	@DisplayName("Un plan retirado se responde igual que uno que nunca existio")
	void plan_no_contratable_es_404_generico() {
		ProblemDetail problem = handler.handlePlanNotFound(new PlanNotFoundException("RETIRADO"));

		assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(problem.getDetail()).doesNotContain("RETIRADO");
	}

	@Test
	@DisplayName("Un salto no admitido es 409 y publica origen y destino, que el cliente ya conoce")
	void transicion_invalida_es_409_con_los_estados() {
		ProblemDetail problem = handler.handleInvalidTransition(
				new InvalidSubscriptionTransitionException(
						SubscriptionStatus.CANCELADA, SubscriptionStatus.ACTIVA));

		assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
		assertThat(problem.getTitle()).isEqualTo("Transicion de suscripcion no permitida");
		assertThat(problem.getType()).isEqualTo(
				URI.create("https://akine.app/problems/invalid-subscription-transition"));
		assertThat(problem.getProperties())
				.containsEntry("fromStatus", SubscriptionStatus.CANCELADA)
				.containsEntry("toStatus", SubscriptionStatus.ACTIVA);
		assertThat(problem.getDetail()).contains("CANCELADA").contains("ACTIVA");
	}

	@Test
	@DisplayName("Un limite alcanzado publica limite y uso actual: sin eso el aviso es inaccionable")
	void limite_de_plan_alcanzado_publica_limite_y_uso() {
		ProblemDetail problem = handler.handlePlanLimitExceeded(
				new PlanLimitExceededException(LimitCode.MAX_CONSULTORIOS, 3, 3));

		assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
		assertThat(problem.getTitle()).isEqualTo("Limite del plan alcanzado");
		assertThat(problem.getType())
				.isEqualTo(URI.create("https://akine.app/problems/plan-limit-exceeded"));
		assertThat(problem.getProperties())
				.containsEntry("limitCode", LimitCode.MAX_CONSULTORIOS)
				.containsEntry("limitValue", 3)
				.containsEntry("currentUsage", 3L);
	}

	@Test
	@DisplayName("Una funcionalidad fuera del plan es 409 y dice cual, para poder ofrecer upgrade")
	void funcionalidad_no_incluida_es_409_y_nombra_la_feature() {
		ProblemDetail problem = handler.handleFeatureNotAvailable(
				new FeatureNotAvailableException(FeatureCode.REPORTES_AVANZADOS));

		assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
		assertThat(problem.getTitle()).isEqualTo("Funcionalidad no incluida en el plan");
		assertThat(problem.getType())
				.isEqualTo(URI.create("https://akine.app/problems/feature-not-available"));
		assertThat(problem.getProperties())
				.containsEntry("featureCode", FeatureCode.REPORTES_AVANZADOS);
		assertThat(problem.getDetail()).contains("REPORTES_AVANZADOS");
	}

	@Test
	@DisplayName("Una suscripcion no activa bloquea la mutacion con 409, no con 403: el permiso esta")
	void suscripcion_suspendida_es_409_y_no_403() {
		ProblemDetail problem =
				handler.handleSubscriptionSuspended(new SubscriptionSuspendedException(1L));

		assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
		assertThat(problem.getTitle()).isEqualTo("Suscripcion suspendida");
		assertThat(problem.getType())
				.isEqualTo(URI.create("https://akine.app/problems/subscription-suspended"));
		assertThat(problem.getDetail()).contains("lecturas");
	}

	@Test
	@DisplayName("Reusar una clave de idempotencia con otro contenido es 409 y la clave no vuelve")
	void clave_de_idempotencia_reusada_es_409_sin_devolver_la_clave() {
		ProblemDetail problem = handler.handleIdempotencyConflict(
				new IdempotencyKeyConflictException("0f9d5f6e-1c2b-4a3d-9e8f-7a6b5c4d3e2f"));

		assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
		assertThat(problem.getTitle()).isEqualTo("Clave de idempotencia en conflicto");
		assertThat(problem.getType())
				.isEqualTo(URI.create("https://akine.app/problems/idempotency-key-conflict"));
		assertThat(problem.getDetail()).doesNotContain("0f9d5f6e");
	}

	@Test
	@DisplayName("Ninguna respuesta del advice filtra nombres de clase, paquetes ni stack traces")
	void ninguna_respuesta_filtra_detalles_internos() {
		assertThat(java.util.List.of(
						handler.handleOrganizationNotFound(new OrganizationNotFoundException(1L)),
						handler.handleContextNotAuthorized(
								new ContextNotAuthorizedException(1L, 1L, 1L)),
						handler.handlePlanNotFound(new PlanNotFoundException("X")),
						handler.handleSubscriptionSuspended(new SubscriptionSuspendedException(1L)),
						handler.handleIdempotencyConflict(
								new IdempotencyKeyConflictException("k"))))
				.allSatisfy(problem -> assertThat(problem.getDetail())
						.doesNotContain("com.akine")
						.doesNotContain("java.lang")
						.doesNotContain("Exception"));
	}
}
