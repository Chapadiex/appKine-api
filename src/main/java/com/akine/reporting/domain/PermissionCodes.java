package com.akine.reporting.domain;

/**
 * Codigos de permiso que este modulo evalua, en texto.
 *
 * <p>{@code reporting} no importa {@code organization.domain.PermissionCode}: seria una dependencia
 * hacia el dominio de otro modulo y ArchUnit la rechaza. Mismo criterio que
 * {@code billing.domain.PermissionCodes}.
 */
public final class PermissionCodes {

	/**
	 * Ver reportes. Es el gate del tablero entero.
	 *
	 * <p><b>Existe en el catalogo desde 00.03 y esta etapa es su primer consumidor.</b> La matriz
	 * §5 lo declara con fase F8 y lo tenia declarado sin asignacion base. No se inventa ningun
	 * permiso nuevo: una etapa no amplia la matriz.
	 *
	 * <p>Lo que este codigo <b>no</b> resuelve es el recorte "Limitado" de §4 —que un
	 * {@code PROFESIONAL} vea solo su propia actividad—, que es el alcance {@code OWN} y es un
	 * hueco declarado de F8 en la propia matriz. El recorte que esta etapa si aplica es por
	 * seccion: cada contribuyente declara el permiso con el que se lee su fuente.
	 */
	public static final String REPORTE_READ = "reporte:read";

	private PermissionCodes() {
	}
}
