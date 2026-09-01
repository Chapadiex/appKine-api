package com.akine.encounter.spi;

/**
 * Reacciona al cierre de una atencion.
 *
 * <h2>Se ejecuta DENTRO de la transaccion del cierre, y es deliberado</h2>
 *
 * <p>La alternativa —derivar la deuda despues, por un evento posterior al commit o por un proceso
 * que barre sesiones cerradas— tiene una falla que no se puede aceptar: <b>una prestacion sin
 * deuda no se nota</b>. Nadie reclama una factura que nunca existio, y el centro descubre el
 * agujero cuando cuadra la caja del mes.
 *
 * <p>La contrapartida esta a la vista y hay que asumirla: un observador que falla <b>hace fallar el
 * cierre clinico</b>. Es el precio de la atomicidad, y se elige asi porque perder plata en silencio
 * es peor que un error ruidoso que el profesional ve y reporta.
 *
 * <p><b>Esto no contradice DP-06 ni "cierre clinico != cobro".</b> Lo que esas reglas separan es el
 * cierre del PAGO: una sesion se cierra sin que nadie haya pagado nada, y asi sigue siendo. Lo que
 * se deriva aca es la DEUDA, que es la consecuencia registral del hecho clinico, no su condicion.
 *
 * <p>La direccion de la dependencia es {@code billing -> encounter}: {@code encounter} declara el
 * contrato y no conoce a ninguno de sus consumidores. Es la misma forma que
 * {@code clinical.spi.EventoClinicoContributor}.
 */
public interface CierreDeSesionObserver {

	/**
	 * @throws RuntimeException y con eso revierte el cierre. Ver la cabecera: es intencional
	 */
	void alCerrar(SesionCerrada cierre);
}
