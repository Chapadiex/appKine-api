package com.akine.person.application;

import java.time.LocalDate;

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
 * @param vigenciaHasta  ULTIMO dia inclusive del documento que lo satisface, o {@code null} si no
 *                       vence o si el requisito esta faltante. AKINE-04.05
 * @param diasParaVencer dias que faltan para ese vencimiento, contra la fecha consultada.
 *                       {@code null} si no vence. <b>Puede ser cero</b>: vence hoy, y hoy todavia
 *                       sirve, porque la vigencia es inclusiva
 */
public record RequisitoAdministrativo(
		TipoRequisito tipo,
		boolean cumplido,
		Long referenciaId,
		String detalle,
		Integer saldo,
		LocalDate vigenciaHasta,
		Long diasParaVencer) {

	/**
	 * Cumplido sin vigencia declarada.
	 *
	 * <p>Sigue existiendo despues de 04.05 porque la credencial la usa: su vencimiento vive en la
	 * cobertura y no en un documento propio, y publicarlo aca lo duplicaria.
	 */
	public static RequisitoAdministrativo cumplido(
			TipoRequisito tipo, Long referenciaId, String detalle, Integer saldo) {
		return new RequisitoAdministrativo(tipo, true, referenciaId, detalle, saldo, null, null);
	}

	/**
	 * Cumplido, con el saldo y el vencimiento del documento que lo satisface (AKINE-04.05).
	 *
	 * <p>Es lo que convierte a la elegibilidad en el "selector explicable" que el enunciado pide:
	 * sin el vencimiento a la vista, la pantalla dice "tiene autorizacion" el dia antes de que se
	 * venza y el mostrador se entera cuando ya no sirve.
	 */
	@SuppressWarnings("java:S107")
	public static RequisitoAdministrativo cumplidoConVigencia(
			TipoRequisito tipo,
			Long referenciaId,
			String detalle,
			Integer saldo,
			LocalDate vigenciaHasta,
			Long diasParaVencer) {

		return new RequisitoAdministrativo(
				tipo, true, referenciaId, detalle, saldo, vigenciaHasta, diasParaVencer);
	}

	public static RequisitoAdministrativo faltante(TipoRequisito tipo, String detalle) {
		return new RequisitoAdministrativo(tipo, false, null, detalle, null, null, null);
	}
}
