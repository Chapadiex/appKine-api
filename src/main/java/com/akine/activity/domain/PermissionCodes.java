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

	/**
	 * Registrar, corregir y cerrar asistencia de una clase (AKINE-08.03).
	 *
	 * <p><b>Separado de {@link #INSCRIPCION_MANAGE} a proposito.</b> Anotar a alguien y decir que
	 * vino son decisiones distintas: en un centro chico las toma la misma persona, pero en uno con
	 * instructores el que da la clase marca asistencia y <b>no</b> deberia poder inscribir ni
	 * cancelar inscripciones ajenas. Un permiso que no se puede separar convierte esa politica en
	 * imposible, y separarlo despues es un cambio incompatible.
	 *
	 * <p><b>No existe {@code asistencia:read}</b>: el detalle operativo y el historial usan
	 * {@link #INSCRIPCION_READ}, que ya protege el mismo dato —quien esta en esta clase— y partirlo
	 * en dos obligaria a darlos siempre juntos, que es la definicion de un permiso decorativo.
	 */
	public static final String ASISTENCIA_MANAGE = "asistencia:manage";

	private PermissionCodes() {
	}
}
