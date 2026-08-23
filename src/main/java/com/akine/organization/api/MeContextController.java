package com.akine.organization.api;

import com.akine.organization.api.dto.AuthorizedContextResponse;
import com.akine.organization.spi.AccountContextDirectory;
import com.akine.platform.spi.tenant.TenantContextHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ProblemDetail;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Contextos de trabajo de la cuenta autenticada.
 *
 * <h2>Por que este endpoint no exige contexto de tenant</h2>
 * Es el endpoint con el que el usuario averigua QUE contextos tiene. Exigirle uno para poder
 * elegirlo seria el mismo bucle que el 403 de contexto faltante existe para evitar. Por eso
 * {@code TenantContextFilter} lo tiene exceptuado por ruta exacta, y por eso la respuesta se
 * arma solo con la identidad del principal.
 *
 * <h2>Por que no se publica la seleccion de contexto en 01.01</h2>
 * <b>Decision D-6, no un olvido.</b> Cambiar de contexto tiene que renovar el token, y el token
 * es del modulo {@code identity}. Si 01.01 publicara {@code PUT /me/active-context}, el frontend
 * necesitaria dos round-trips —persistir el contexto y despues pedir el token— y quedaria una
 * ventana con el puntero ya cambiado y el token viejo. Peor: cuando 01.02 publique el suyo,
 * este quedaria redundante y habria que retirarlo, que es un cambio incompatible de contrato
 * sobre un endpoint ya publicado.
 * <p>La seleccion se expone unicamente por {@code spi}
 * ({@code AccountContextDirectory.selectContext}), y 01.02 publica
 * {@code POST /api/v1/auth/context}: un endpoint, un round-trip, sin romper nada despues.
 * {@code GET /me/active-context} tampoco se publica aca —sin token renovado no tiene consumidor
 * real.
 *
 * <p>Capa {@code api}: solo HTTP. La validacion de vigencia de cada membership la hace
 * {@code application}, contra la base y sin cache.
 */
@RestController
@RequestMapping(path = "/api/v1/me", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Mi cuenta", description = "Contextos de trabajo habilitados para la cuenta autenticada")
public class MeContextController {

	private final AccountContextDirectory accountContextDirectory;
	private final TenantContextHolder tenantContextHolder;

	public MeContextController(
			AccountContextDirectory accountContextDirectory,
			TenantContextHolder tenantContextHolder) {
		this.accountContextDirectory = accountContextDirectory;
		this.tenantContextHolder = tenantContextHolder;
	}

	@GetMapping("/contexts")
	@Operation(
			operationId = "listMyContexts",
			summary = "Contextos de trabajo habilitados",
			description = """
					Devuelve los pares Organizacion + Consultorio en los que la cuenta tiene una \
					membership vigente y cuya organizacion admite operar. Es lo que alimenta la \
					pantalla de seleccion de contexto.

					Cada elemento trae los nombres junto a los ids porque este listado es \
					exactamente lo que el usuario ve para elegir: resolverlos con llamadas \
					adicionales exigiria pedir datos de organizaciones antes de tener contexto en \
					ninguna.

					No requiere contexto de tenant: es el endpoint con el que se averigua que \
					contextos hay. Devuelve una lista vacia —no un error— cuando la cuenta todavia \
					no tiene ninguno; el frontend distingue 'no elegiste' de 'no tenes donde'.

					Este endpoint no cambia el contexto activo. Seleccionarlo renueva el token, y \
					eso lo publica el modulo de identidad en 01.02 como POST /api/v1/auth/context.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Contextos habilitados. Lista vacia si la cuenta no tiene ninguno",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							array = @ArraySchema(
									schema = @Schema(implementation = AuthorizedContextResponse.class)))),
			@ApiResponse(
					responseCode = "403",
					description = "No hay sesion autenticada. Nunca 401: un 401 haria que el cliente "
							+ "descarte el token y entre en un bucle de login",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<List<AuthorizedContextResponse>> contexts() {
		ApiActor actor = ApiActor.current(tenantContextHolder);

		List<AuthorizedContextResponse> contextos =
				accountContextDirectory.authorizedContexts(actor.accountId()).stream()
						.map(AuthorizedContextResponse::from)
						.toList();

		return ResponseEntity.ok(contextos);
	}
}
