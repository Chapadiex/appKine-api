package com.akine.organization.spi;

/**
 * Resultado de evaluar un permiso: si se concede y, si no, por que.
 *
 * <p>El "por que" no es cosmetico: es lo que permite que el mapeo a codigos HTTP viva en un
 * unico lugar (ver {@link DenialKind}).
 *
 * @param granted        si el permiso se concede
 * @param denial         motivo del rechazo; {@link DenialKind#NONE} cuando se concede
 * @param grantedByScope alcance con el que se concedio, como texto
 *                       ({@code GLOBAL} | {@code ORGANIZACION} | {@code CONSULTORIO} |
 *                       {@code OWN} | {@code CATALOGO} | {@code SOPORTE} |
 *                       {@code ACTIVIDAD_PROPIA}), o {@code null} si se
 *                       rechazo. Viaja como {@code String} por el mismo motivo que
 *                       {@code permissionCode}: el enum vive en {@code domain} y es privado
 * @param viaSupportAccess si la concesion se apoyo en un acceso de soporte vigente. Quien
 *                       ejecuta la operacion tiene que registrar {@code SUPPORT_ACCESS_USED}:
 *                       la matriz seccion 7 exige que CADA operacion amparada por soporte quede
 *                       auditada, no solo el otorgamiento
 */
public record PermissionDecision(
		boolean granted, DenialKind denial, String grantedByScope, boolean viaSupportAccess) {

	public PermissionDecision {
		if (denial == null) {
			throw new IllegalArgumentException("denial es obligatorio: NONE tambien es un valor");
		}
		if (granted != (denial == DenialKind.NONE)) {
			throw new IllegalArgumentException(
					"Una decision concedida lleva DenialKind.NONE y una rechazada lleva un motivo");
		}
	}

	/**
	 * Nombre del alcance "Limitado a la actividad propia" (AKINE-G-1, DP-15), tal como viaja en
	 * {@link #grantedByScope()}. Es el {@code name()} de {@code PermissionScope.ACTIVIDAD_PROPIA};
	 * un test de {@code organization} lo sostiene igual.
	 */
	public static final String ALCANCE_ACTIVIDAD_PROPIA = "ACTIVIDAD_PROPIA";

	/**
	 * Si el permiso se concedio solo sobre la actividad propia del actor.
	 *
	 * <p>Cuando es {@code true}, <b>el llamador tiene que recortar el dato</b> a lo que el actor
	 * atendio o le fue asignado: el evaluador decide que sede alcanza, no que filas.
	 */
	public boolean limitadaAActividadPropia() {
		return granted && ALCANCE_ACTIVIDAD_PROPIA.equals(grantedByScope);
	}

	public static PermissionDecision concedida(String scope, boolean viaSupportAccess) {
		return new PermissionDecision(true, DenialKind.NONE, scope, viaSupportAccess);
	}

	public static PermissionDecision rechazada(DenialKind denial) {
		return new PermissionDecision(false, denial, null, false);
	}
}
