package com.akine.person.application;

/**
 * Un requisito del convenio y su veredicto para una fecha concreta (RF-M17-007).
 *
 * <p><b>Un requisito insatisfecho NO es un error.</b> Es informacion: dice que falta y con que se
 * cumple, para que el mostrador lo consiga antes de atender. Devolverlo como 409 obligaria a la
 * pantalla a tratar el caso mas frecuente —al paciente le falta la orden— como una excepcion, que
 * es el mismo error que 03.05 evito al hacer que la resolucion de arancel respondiera 200 con
 * motivo.
 *
 * <p>{@code referenciaId} apunta a la orden o autorizacion que lo satisface, cuando hay alguna.
 * Sirve para que la pantalla pueda mostrarla sin una segunda consulta, y para que la etapa que
 * cablee el consumo sepa a que fila descontarle.
 *
 * @param tipo         que exige el convenio
 * @param cumplido     si hoy esta satisfecho
 * @param referenciaId id de la orden o autorizacion que lo satisface, o {@code null}
 * @param detalle      por que esta o no cumplido, en lenguaje del mostrador
 * @param saldo        sesiones restantes cuando el requisito es AUTORIZACION y hay tope
 */
public record RequisitoAdministrativo(
		TipoRequisito tipo,
		boolean cumplido,
		Long referenciaId,
		String detalle,
		Integer saldo) {

	public static RequisitoAdministrativo cumplido(
			TipoRequisito tipo, Long referenciaId, String detalle, Integer saldo) {
		return new RequisitoAdministrativo(tipo, true, referenciaId, detalle, saldo);
	}

	public static RequisitoAdministrativo faltante(TipoRequisito tipo, String detalle) {
		return new RequisitoAdministrativo(tipo, false, null, detalle, null);
	}
}
