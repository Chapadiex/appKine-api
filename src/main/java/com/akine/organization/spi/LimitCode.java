package com.akine.organization.spi;

/**
 * Limites cuantitativos que un plan puede imponer a una organizacion (RF-M01-004).
 *
 * <p>Vive en {@code spi} y no en {@code domain} porque el consumidor del limite es siempre
 * OTRO modulo: el modulo que da de alta el recurso es el propietario de esa tabla y es el
 * unico que puede contarla. {@code organization} solo decide si el alta entra o no. Poner el
 * enum en {@code domain} obligaria a duplicarlo con un mapeo explicito para nada.
 *
 * <p>El catalogo arranca chico a proposito: estos son los dos unicos limites evaluables con
 * las entidades que existen en F1. Crece por etapa consumidora, y agregar un valor no exige
 * migracion de datos porque en la base se persiste el nombre como texto.
 *
 * <p><b>Ojo con el orden de evaluacion.</b> Contar el uso ANTES de abrir la transaccion del
 * alta produce una carrera: dos altas concurrentes leen el mismo conteo y las dos pasan.
 * El conteo va dentro de la misma transaccion y despues de bloquear la suscripcion.
 */
public enum LimitCode {

	/** Cuantos consultorios activos puede tener la organizacion. */
	MAX_CONSULTORIOS,

	/** Cuantas memberships activas puede tener la organizacion. */
	MAX_MIEMBROS_ACTIVOS
}
