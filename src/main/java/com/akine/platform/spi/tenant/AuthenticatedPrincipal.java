package com.akine.platform.spi.tenant;

/**
 * Lo minimo que {@code platform} necesita saber de quien hace el request.
 *
 * <p>La implementa {@code identity} en 01.02 leyendo el JWT; en 01.01 solo existe el contrato
 * y los tests usan fixtures. Se declara aca —y no en {@code identity.spi}— porque
 * {@code platform} no puede depender de ningun modulo funcional: es el modulo base, y una
 * flecha {@code platform -> identity} cerraria un ciclo.
 *
 * <p><b>Los valores de contexto son un HINT, jamas autoridad (RN-M01-003).</b>
 * {@link #organizationId()} y {@link #consultorioId()} vienen de claims firmados por nosotros,
 * pero firmar un claim no lo mantiene verdadero: entre la emision del token y este request la
 * membership pudo revocarse, el consultorio darse de baja o la suscripcion cancelarse. Por eso
 * {@code TenantContextFilter} revalida SIEMPRE contra la base y nunca confia en estos valores
 * para autorizar. Su unico uso legitimo es decir "que contexto se esta pidiendo".
 */
public interface AuthenticatedPrincipal {

	/** Cuenta autenticada. Referencia logica al modulo {@code identity}. */
	long accountId();

	/** Organizacion pedida por el token, o {@code null} si el token no tiene contexto. */
	Long organizationId();

	/** Consultorio pedido por el token, o {@code null} si el token no tiene contexto. */
	Long consultorioId();

	/**
	 * Administrador de plataforma: opera por encima de cualquier tenant.
	 *
	 * <p>Es un flag del principal, no una membership: un {@code PLATFORM_ADMIN} no tiene por
	 * que ser miembro de ninguna organizacion. La evaluacion fina de lo que puede hacer llega
	 * en 01.03; en 01.01 solo determina que no se le exige contexto de tenant.
	 */
	boolean platformAdmin();
}
