package com.akine.person.spi;

/**
 * Lo que {@code person} ofrece a otros modulos para descontar unidades autorizadas (RF-M17-004).
 *
 * <h2>Por que el contrato vive ACA y no al reves</h2>
 *
 * <p>La forma natural, mirando a 07.01, seria que {@code person} implementara
 * {@code encounter.spi.CierreDeSesionObserver} en su propia {@code infrastructure}, igual que hace
 * {@code billing.ObligacionDevengador}. <b>No se puede: cierra un ciclo entre modulos.</b>
 *
 * <pre>
 *   clinical  -> person.spi     ya existe (HistoriaClinicaService usa PacienteDirectory)
 *   encounter -> clinical.spi   ya existe (SesionService usa CasoDirectory)
 *   person    -> encounter.spi  seria la arista nueva
 * </pre>
 *
 * <p>Las tres juntas son el ciclo {@code person -> encounter -> clinical -> person}, y
 * {@code ModuleArchitectureTest.sin_ciclos_entre_modulos} lo detecta: {@code SlicesRuleDefinition}
 * busca ciclos de <b>cualquier</b> longitud, no solo de dos. Que {@code billing} pueda depender de
 * los dos no es analogo: <b>nadie depende de {@code billing}</b>, y esa es toda la diferencia.
 *
 * <p>La arista que si funciona es {@code encounter -> person.spi}: {@code person} no alcanza a
 * {@code encounter} por ningun camino —solo llega a {@code contracting}, {@code organization} y
 * {@code platform}—, asi que no cierra nada. El observador del cierre vive entonces en
 * {@code encounter.infrastructure} y llama a este puerto. El patron del observador se conserva
 * entero —{@code SesionService} sigue sin saber quien lo escucha— y {@code person} sigue siendo el
 * unico que escribe sus tablas, que es lo que el challenge exigia de verdad.
 *
 * <h2>Este puerto NO lanza por falta de saldo</h2>
 *
 * <p>Devuelve {@link ResultadoDeConsumo}. Ver su javadoc: la atencion ocurrio, y el saldo
 * insuficiente es un desenlace administrativo, no una excepcion que deba revertir una historia
 * clinica.
 */
public interface ConsumoDeAutorizaciones {

	/**
	 * Descuenta las unidades que corresponden a una sesion cerrada, si hay de donde.
	 *
	 * <p><b>Idempotente por el hecho de origen.</b> Llamarlo dos veces con el mismo
	 * {@code sesionId} descuenta una sola vez: lo garantiza el unique
	 * {@code uk_autorizacion_movimiento_origen} de {@code V50}, y el segundo intento devuelve
	 * {@link ResultadoDeConsumo#YA_CONSUMIDA} con el movimiento que ya existia.
	 *
	 * @return el desenlace. <b>Nunca lanza por falta de saldo ni por falta de autorizacion</b>
	 */
	ResultadoDeConsumo consumirPorSesion(ConsumoPorSesion hecho);
}
