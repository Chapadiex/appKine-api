package com.akine.organization.domain;

/**
 * Roles de seguridad de la matriz de permisos aprobada
 * ({@code docs/seguridad/matriz-permisos-minima.md}, vinculante).
 *
 * <p>Esta lista es cerrada. RN-M05-006 prohibe crear roles nuevos por denominacion: si hace
 * falta distinguir a alguien dentro de un rol —por ejemplo al propietario que fundo la
 * organizacion— eso es un ATRIBUTO de la membership ({@code is_founder}), no un rol. El rol
 * de seguridad y la condicion de fundador son dimensiones distintas (RN-M05-005).
 *
 * <p>{@code OWNER} y {@code ADMIN} NO existen: la membership del primer propietario se crea
 * con {@link #ORG_ADMIN} y {@code is_founder = true}.
 *
 * <p>01.01 solo ESCRIBE {@link #ORG_ADMIN} (via onboarding). La evaluacion fina de permisos
 * llega en 01.03; hasta entonces el guard es grueso y esta anotado como deuda.
 */
public enum RoleCode {

	/** Administra la plataforma completa, por encima de cualquier tenant. */
	PLATFORM_ADMIN,

	/** Administra una organizacion entera, todos sus consultorios incluidos. */
	ORG_ADMIN,

	/** Administra un consultorio concreto de la organizacion. */
	CONSULTORIO_ADMIN,

	/** Profesional que atiende: acceso clinico segun relacion asistencial. */
	PROFESIONAL,

	/** Personal administrativo: recepcion, turnos, cobros. Sin acceso clinico. */
	ADMINISTRATIVO,

	/** El propio paciente sobre sus datos. */
	PACIENTE
}
