package com.akine.identity.spi;

/**
 * Foto de una cuenta para consumo de otros modulos.
 *
 * <p>Es un record y no la entity {@code Cuenta}: la entity es propiedad de {@code identity} y
 * exponerla dejaria que cualquier modulo la modificara y la guardara por su cuenta, con lo que
 * dejaria de haber un propietario de la tabla.
 *
 * <p><b>Lo que este record no lleva, a proposito:</b> ni el hash de la credencial, ni el
 * contador de intentos fallidos, ni el motivo del bloqueo. Lo primero no sale del modulo bajo
 * ninguna circunstancia (RN-M02-003); los otros dos son informacion administrativa que se
 * consulta por auditoria, no por un {@code spi} que cualquier modulo puede llamar.
 *
 * @param accountId    id de la cuenta
 * @param email        direccion tal como la escribio la persona; para mostrar, no para comparar
 * @param nombreCompleto nombre y apellido, para las pantallas que necesitan identificar a la
 *                       persona sin pedirle los datos a otro modulo
 * @param estado       estado de la cuenta
 * @param active       baja logica. Distinta del estado: una cuenta DESACTIVADA tiene
 *                     {@code active = false}, y las dos cosas se conservan porque la fila nunca
 *                     se borra (RN-M02-004)
 */
public record AccountSnapshot(
		long accountId,
		String email,
		String nombreCompleto,
		AccountState estado,
		boolean active) {

	/** Indica si la cuenta esta en condiciones de autenticarse. Solo {@code ACTIVA} lo esta. */
	public boolean habilitada() {
		return estado == AccountState.ACTIVA && active;
	}
}
