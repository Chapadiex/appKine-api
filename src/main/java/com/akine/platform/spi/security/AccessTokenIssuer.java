package com.akine.platform.spi.security;

/**
 * Emision del access token.
 *
 * <p>El contrato se declara en {@code platform.spi} y lo implementa
 * {@code identity.infrastructure}. La direccion importa: {@code identity.application} necesita
 * emitir tokens y no puede depender de {@code identity.infrastructure} —ArchUnit prohibe que
 * cualquier capa alcance {@code infrastructure}—, asi que el puerto tiene que vivir en un
 * paquete que las dos puedan ver. {@code platform.spi} lo es, y ademas deja al filtro de
 * autenticacion de {@code platform} verificar tokens sin compilar jamas contra
 * {@code identity}.
 *
 * <p>Nada de lo que entra aca es HTTP: ni cookies, ni headers, ni el request. Lo que sabe de
 * transporte vive en {@code infrastructure} y en {@code api}.
 */
public interface AccessTokenIssuer {

	/**
	 * Emite un access token de vida corta.
	 *
	 * <p>Con {@code scope = PRE_CONTEXT} los tres parametros de contexto van en {@code null};
	 * la implementacion no los escribe como claims. Con {@code scope = CONTEXT} los tres son
	 * obligatorios: un token que dice tener contexto pero no lo lleva completo dejaria pasar
	 * un request que despues no se puede acotar a ningun tenant.
	 *
	 * @param accountId      cuenta autenticada
	 * @param scope          alcance del token
	 * @param organizationId organizacion, o {@code null} si el alcance es pre-contexto
	 * @param consultorioId  consultorio, o {@code null} si el alcance es pre-contexto
	 * @param roleCode       rol vigente en ese contexto, o {@code null} si es pre-contexto.
	 *                       Viaja como pista; no autoriza nada
	 * @param familyId       familia del refresh que origino la sesion
	 * @throws IllegalArgumentException si el alcance y el contexto no se corresponden
	 */
	IssuedAccessToken issue(
			long accountId,
			AccessTokenScope scope,
			Long organizationId,
			Long consultorioId,
			String roleCode,
			String familyId);
}
