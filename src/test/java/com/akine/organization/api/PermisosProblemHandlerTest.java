package com.akine.organization.api;

import com.akine.organization.domain.MembershipEstado;
import com.akine.organization.domain.exception.FounderRevocationNotAllowedException;
import com.akine.organization.domain.exception.GrantAlreadyActiveException;
import com.akine.organization.domain.exception.LastAdminException;
import com.akine.organization.domain.exception.MembershipAlreadyExistsException;
import com.akine.organization.domain.exception.MembershipNotAccessibleException;
import com.akine.organization.domain.exception.MembershipNotActiveException;
import com.akine.organization.domain.exception.PermissionDeniedException;
import com.akine.organization.domain.exception.PlatformRoleNotFoundException;
import com.akine.organization.domain.exception.SelfRevokeNotAllowedException;
import com.akine.organization.domain.exception.SupportAccessNotFoundException;
import com.akine.organization.domain.exception.UnknownPermissionCodeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Las respuestas de error que AKINE-01.03 agrega.
 *
 * <h2>Lo que este test protege</h2>
 *
 * <p>Tres reglas que se enuncian en una linea y se pierden en cuanto alguien las decide caso por
 * caso:
 * <ul>
 *   <li><b>Fuera del alcance &rarr; 404.</b> Un 403 confirmaria que ese id existe y bastarian
 *       ids consecutivos para enumerar a los colaboradores de otros centros.</li>
 *   <li><b>Dentro del alcance y sin permiso &rarr; 403.</b> Aca el 404 no protege nada y ademas
 *       le miente al usuario.</li>
 *   <li><b>Estado incompatible &rarr; 409, no 403.</b> El actor tiene el permiso; lo que no
 *       admite la operacion es el estado. La diferencia es lo que hace que el mensaje sea
 *       accionable: "promove a otro administrador antes" en vez de "no podes".</li>
 * </ul>
 *
 * <p>Y en todas: ninguna respuesta filtra nombres de clase, paquetes ni SQL (ADR-0005).
 */
class PermisosProblemHandlerTest {

	private static final String BASE = "https://akine.app/problems/";

	private final OrganizationProblemHandler handler = new OrganizationProblemHandler();

	private static void assertSinInternals(ProblemDetail problem) {
		assertThat(problem.getDetail())
				.doesNotContain("com.akine")
				.doesNotContain("org.springframework")
				.doesNotContain("Exception");
	}

	// =================================================================================
	// 403 — dentro del alcance, sin permiso
	// =================================================================================

	@Test
	@DisplayName("Sin permiso sobre un recurso propio es 403, y publica el permiso que falto")
	void sin_permiso_es_403_con_el_permiso_que_falto() {
		ProblemDetail problem = handler.handlePermissionDenied(
				new PermissionDeniedException("colaborador:manage", 30L, 10L));

		assertThat(problem.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(problem.getType()).isEqualTo(URI.create(BASE + "forbidden"));
		// El permiso SI se publica: es lo que el usuario necesita para saber que pedirle a su
		// administrador. No revela nada de otros tenants ni de la implementacion.
		assertThat(problem.getProperties()).containsEntry("requiredPermission", "colaborador:manage");
		assertSinInternals(problem);
	}

	@Test
	@DisplayName("Desvincular al fundador siendo otro admin es 403: es quien, no estado")
	void el_fundador_protegido_es_403() {
		ProblemDetail problem = handler.handleFounderProtected(
				new FounderRevocationNotAllowedException(60L));

		assertThat(problem.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(problem.getType()).isEqualTo(URI.create(BASE + "forbidden"));
		assertThat(problem.getDetail()).doesNotContain("60");
		assertSinInternals(problem);
	}

	// =================================================================================
	// 404 — fuera del alcance
	// =================================================================================

	@Test
	@DisplayName("Una membership fuera del alcance es 404 con el cuerpo generico")
	void membership_fuera_del_alcance_es_404() {
		ProblemDetail problem = handler.handleMembershipNotAccessible(
				new MembershipNotAccessibleException(60L));

		assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(problem.getType()).isEqualTo(URI.create(BASE + "not-found"));
		// Mismo cuerpo que cualquier otro 404: distinguirlo permitiria enumerar.
		assertThat(problem.getDetail())
				.isEqualTo("El recurso solicitado no existe o no esta disponible.")
				.doesNotContain("60");
	}

	@Test
	@DisplayName("Acceso de soporte y rol de plataforma inexistentes son 404")
	void los_recursos_de_plataforma_inexistentes_son_404() {
		assertThat(handler.handleSupportAccessNotFound(new SupportAccessNotFoundException(7L))
				.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(handler.handlePlatformRoleNotFound(new PlatformRoleNotFoundException(8L))
				.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
	}

	// =================================================================================
	// 409 — estado incompatible
	// =================================================================================

	@Test
	@DisplayName("Ultimo administrador es 409 y el mensaje dice que hacer")
	void ultimo_admin_es_409_accionable() {
		ProblemDetail problem = handler.handleLastAdmin(new LastAdminException(10L));

		assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
		assertThat(problem.getType()).isEqualTo(URI.create(BASE + "last-admin-required"));
		assertThat(problem.getDetail()).contains("Asigne otro administrador");
		assertSinInternals(problem);
	}

	@Test
	@DisplayName("Self-revoke es 409 y explica el camino: que lo haga otro administrador")
	void self_revoke_es_409_accionable() {
		ProblemDetail problem = handler.handleSelfRevoke(new SelfRevokeNotAllowedException(30L));

		assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
		assertThat(problem.getType()).isEqualTo(URI.create(BASE + "self-revoke-not-allowed"));
		assertThat(problem.getDetail()).contains("otro administrador");
		assertSinInternals(problem);
	}

	@Test
	@DisplayName("Estado de vinculo incompatible es 409 y publica los dos estados")
	void estado_incompatible_es_409_con_los_dos_estados() {
		ProblemDetail problem = handler.handleMembershipNotActive(new MembershipNotActiveException(
				60L, MembershipEstado.REVOCADA, MembershipEstado.ACTIVA));

		assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
		assertThat(problem.getType()).isEqualTo(URI.create(BASE + "membership-not-active"));
		// El cliente los conoce o los envio: no es informacion nueva, y sin ellos el mensaje
		// seria inaccionable.
		assertThat(problem.getProperties())
				.containsEntry("currentState", "REVOCADA")
				.containsEntry("requestedState", "ACTIVA");
	}

	@Test
	@DisplayName("Grant duplicado es 409 traducido de la clave, no un 500")
	void grant_duplicado_es_409() {
		ProblemDetail problem = handler.handleGrantAlreadyActive(
				new GrantAlreadyActiveException("auditoria:read-clinica", 60L));

		assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
		assertThat(problem.getType()).isEqualTo(URI.create(BASE + "grant-already-active"));
		assertThat(problem.getProperties())
				.containsEntry("permissionCode", "auditoria:read-clinica");
	}

	@Test
	@DisplayName("Vinculo ya existente es 409 y menciona el vinculo revocado (D-13)")
	void vinculo_ya_existente_es_409() {
		// El unique de membership incluye las filas historicas, asi que revincular a alguien en
		// la sede donde ya trabajo choca. Es la decision D-13, abierta: hasta que se resuelva,
		// el mensaje tiene que decirlo o el 409 es incomprensible.
		ProblemDetail problem = handler.handleMembershipExists(
				new MembershipAlreadyExistsException(10L, 20L));

		assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
		assertThat(problem.getType()).isEqualTo(URI.create(BASE + "membership-already-exists"));
		assertThat(problem.getDetail()).contains("revocado");
	}

	// La modificacion concurrente (@Version de JPA) ya no se mapea en este advice: desde DP-21 la
	// emite GlobalExceptionHandler para todos los modulos. Su test vive en GlobalExceptionHandlerTest.

	// =================================================================================
	// 400 — error del cliente
	// =================================================================================

	@Test
	@DisplayName("Un codigo fuera del catalogo es 400, no 500: es un error del cliente")
	void codigo_desconocido_es_400() {
		ProblemDetail problem = handler.handleUnknownPermission(new UnknownPermissionCodeException(
				"no:existe", "El codigo de permiso no existe en el catalogo"));

		assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(problem.getType()).isEqualTo(URI.create(BASE + "validation-error"));
		assertThat(problem.getDetail()).isEqualTo("El codigo de permiso no existe en el catalogo");
		assertSinInternals(problem);
	}
}
