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
	 * <p>Hasta AKINE-G-1 no lo tenia ningun rol. Desde DP-15 tiene asignacion base, y el
	 * "Limitado" de §4 se resuelve en dos capas: el recorte por seccion —cada contribuyente declara
	 * el permiso con el que se lee su fuente, y eso deja al {@code ADMINISTRATIVO} sin lo clinico—
	 * y el alcance {@code ACTIVIDAD_PROPIA} del {@code PROFESIONAL}, que recorta turnos, sesiones y
	 * casos a lo que atendio o le fue asignado.
	 */
	public static final String REPORTE_READ = "reporte:read";

	private PermissionCodes() {
	}
}
