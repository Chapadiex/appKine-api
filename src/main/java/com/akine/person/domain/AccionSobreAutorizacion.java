package com.akine.person.domain;

/**
 * Lo que un administrativo le HACE a una autorizacion (M17).
 *
 * <p>La API no acepta un estado destino: acepta una accion. La diferencia importa y es la que el
 * plan pide con "comandos especificos de estado, sin asignacion arbitraria de estado".
 *
 * <p>Con un estado destino, {@code PUT {estado: "APROBADA"}} sobre una autorizacion RECHAZADA es
 * una peticion sintacticamente valida que el servidor tiene que rechazar por semantica, y el
 * cliente puede construir cualquier transicion imaginable. Con una accion, la unica forma de
 * llegar a APROBADA es {@code APROBAR}, y la maquina de estados decide si desde donde esta se
 * puede. El conjunto de transiciones posibles queda del lado del backend, que es donde AGENT.md
 * seccion 8 regla 12 lo pone.
 */
public enum AccionSobreAutorizacion {

	/** El financiador la otorgo. Puede otorgar MENOS de lo pedido: eso es la autorizacion parcial. */
	APROBAR,

	/** El financiador pidio corregir algo. Exige motivo y no cierra el caso. */
	OBSERVAR,

	/** El financiador la denego. Exige motivo y es terminal. */
	RECHAZAR;

	/** El estado al que lleva esta accion. */
	public EstadoAutorizacion destino() {
		return switch (this) {
			case APROBAR -> EstadoAutorizacion.APROBADA;
			case OBSERVAR -> EstadoAutorizacion.OBSERVADA;
			case RECHAZAR -> EstadoAutorizacion.RECHAZADA;
		};
	}

	/** Observar y rechazar sin decir por que dejan al mostrador sin nada que corregir. */
	public boolean exigeMotivo() {
		return this != APROBAR;
	}
}
