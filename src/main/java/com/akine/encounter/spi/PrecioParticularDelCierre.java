package com.akine.encounter.spi;

/**
 * Pregunta, ANTES de cerrar, si la deuda del paciente va a ser el precio particular de la oferta
 * (AKINE E-7b, DP-17).
 *
 * <p>DP-17: <b>toda prestacion cerrada tiene que generar deuda</b>. Si la parte del paciente se
 * valoriza al precio particular y la oferta no tiene precio ese dia, el cierre se bloquea con 409
 * {@code oferta-sin-precio}. Lo tiene que preguntar {@code encounter} antes de pedir el numero de
 * sesion, consumir autorizaciones o devengar: validarlo dentro del devengo tambien revierte todo,
 * pero despues de tomar los locks de los numeradores.
 *
 * <p>Quien decide es quien devenga —{@code billing}—, con la MISMA regla con la que despues elige
 * entre convenio y particular: si fueran dos reglas, una oferta sin precio podria pasar la
 * validacion y caer igual a particular en el devengo. La direccion de la dependencia es la de
 * {@link CierreDeSesionObserver}: {@code billing -> encounter.spi}.
 *
 * <p>El hecho que recibe es el del cierre <b>sin numero</b> ({@code numeroSesion = 0}): todavia no
 * se pidio. Todo lo demas es exactamente lo que despues reciben los observadores.
 */
public interface PrecioParticularDelCierre {

	/**
	 * {@code true} si el paciente asistio y su deuda va a ser el precio particular de la oferta:
	 * recepcion Particular, oferta sin obra social, o sin cobertura con convenio y arancel
	 * aplicables. {@code false} si no hay deuda (ausencia, practica sin cargo) o si la parte del
	 * paciente sale del arancel del convenio (el coseguro).
	 */
	boolean exigePrecioParticular(SesionCerrada cierreSinNumero);
}
