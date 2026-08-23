package com.akine.organization.spi;

/**
 * Datos del alta compuesta de una organizacion (ADR-0008).
 *
 * <p>Es un record y no una entity a proposito: {@code spi} solo expone contratos. El modulo
 * llamador —hoy {@code identity}, en 01.02— no conoce ninguna tabla de {@code organization}.
 *
 * <p>De {@code identity} solo se recibe {@code accountId}: una referencia LOGICA, sin FK
 * fisica. {@code organization} jamas compila contra {@code identity} (T-1), asi que la cuenta
 * nunca cruza este borde como objeto.
 *
 * @param idempotencyKey  clave del alta, unica GLOBAL. Obligatoria: es lo que hace que un
 *                        reintento devuelva el tenant ya creado en lugar de crear otro
 * @param requestHash     SHA-256 del payload canonico cuando el alta entra por HTTP, o
 *                        {@code null} cuando entra por {@code spi}. Sirve para detectar la
 *                        misma clave con un contenido distinto, que es un error del cliente y
 *                        no algo que se pueda resolver devolviendo el resultado viejo
 * @param accountId       cuenta que sera propietaria. Su membership se crea con
 *                        {@code ORG_ADMIN} y {@code is_founder = true} (T-5): los roles
 *                        {@code OWNER} y {@code ADMIN} no existen en la matriz aprobada
 * @param organizationName nombre visible del tenant
 * @param organizationSlug identificador legible unico global. {@code null} lo deriva del
 *                        nombre. No esta en el diseno original y se agrego aca porque
 *                        {@code organization.slug} es NOT NULL UNIQUE: sin este campo, el
 *                        llamador no tendria forma de fijar la URL de su tenant
 * @param consultorioName nombre de la primera sede. {@code null} usa el nombre de la
 *                        organizacion
 * @param planCode        plan a contratar. {@code null} usa {@link #PLAN_POR_DEFECTO}
 */
public record InitialOrganizationCommand(
		String idempotencyKey,
		String requestHash,
		long accountId,
		String organizationName,
		String organizationSlug,
		String consultorioName,
		String planCode) {

	/** Plan con el que nace una organizacion cuando el llamador no elige ninguno. */
	public static final String PLAN_POR_DEFECTO = "BASICO";

	public InitialOrganizationCommand {
		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			throw new IllegalArgumentException("idempotencyKey es obligatoria en el alta compuesta");
		}
		if (organizationName == null || organizationName.isBlank()) {
			throw new IllegalArgumentException("organizationName es obligatorio en el alta compuesta");
		}
		if (accountId <= 0) {
			throw new IllegalArgumentException("accountId es obligatorio en el alta compuesta");
		}
	}

	/** Plan pedido, o el de defecto. Resuelto aca para que el servicio no repita la regla. */
	public String planCodeOrDefault() {
		return planCode == null || planCode.isBlank() ? PLAN_POR_DEFECTO : planCode;
	}

	/** Nombre de la primera sede, o el de la organizacion. */
	public String consultorioNameOrDefault() {
		return consultorioName == null || consultorioName.isBlank()
				? organizationName
				: consultorioName;
	}
}
