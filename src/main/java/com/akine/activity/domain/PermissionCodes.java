package com.akine.activity.domain;

/**
 * Codigos de permiso que este modulo evalua, en texto.
 *
 * <p>{@code activity} no importa {@code organization.domain.PermissionCode}: seria una dependencia
 * hacia el dominio de otro modulo y ArchUnit la rechaza. El evaluador recibe el codigo como texto
 * por el {@code spi} y lo resuelve contra el catalogo. Mismo patron que
 * {@code scheduling.domain.PermissionCodes}.
 */
public final class PermissionCodes {

	/** Ver clases programadas y su historial. */
	public static final String CLASE_READ = "clase:read";

	/** Programar, reprogramar y cancelar clases. */
	public static final String CLASE_MANAGE = "clase:manage";

	/**
	 * Ver la lista de participantes de una clase.
	 *
	 * <p><b>Separado de {@link #CLASE_READ} a proposito.</b> 08.01 decidio que ninguna respuesta de
	 * clase lleva participantes, y esta etapa no lo deshace: quien mira la grilla del dia no
	 * necesita saber quien esta anotado, y darle los dos permisos juntos convertiria esa decision
	 * en decorativa.
	 */
	public static final String INSCRIPCION_READ = "inscripcion:read";

	/** Inscribir, confirmar y cancelar inscripciones. */
	public static final String INSCRIPCION_MANAGE = "inscripcion:manage";

	private PermissionCodes() {
	}
}
