package com.akine.organization.api;

/**
 * La cuenta ya tiene el rol de plataforma vigente.
 *
 * <h2>Por que vive en la capa {@code api} y no en {@code domain}</h2>
 *
 * <p>{@code PlatformRoleService} traduce la clave duplicada de {@code uk_platform_role_activo} a
 * un {@code IllegalStateException}, y esa excepcion no tiene manejador propio: caeria en la red
 * de contencion del advice global y devolveria <b>500</b> a un caso que es un conflicto legitimo
 * y esperable —dos administradores otorgando el mismo rol a la vez—.
 *
 * <p>Traducirla necesita un tipo. Ponerlo en {@code organization.domain.exception} seria tocar
 * el nucleo, que esta cerrado y verificado; ponerlo aca lo deja donde se usa —el controller que
 * la lanza y el advice que la mapea estan en este mismo paquete— y nadie mas puede depender de
 * el, porque la capa {@code api} no es accesible desde ninguna otra.
 *
 * <p><b>Deuda declarada.</b> Lo correcto a futuro es que el servicio lance una excepcion de
 * dominio propia, como ya hacen {@code GrantAlreadyActiveException} y
 * {@code MembershipAlreadyExistsException} para el mismo patron. Cuando eso ocurra, esta clase
 * y su manejador se retiran sin cambiar el contrato: el codigo y el {@code type} de la respuesta
 * son los mismos.
 */
public class PlatformRoleAlreadyGrantedException extends RuntimeException {

	public PlatformRoleAlreadyGrantedException(Throwable causa) {
		super("La cuenta ya tiene el rol de plataforma vigente", causa);
	}
}
