package com.akine.clinical.spi;

import java.util.Optional;

/**
 * El borde por donde {@code clinical} responde sobre Casos Clinicos a los modulos que registran
 * hechos dentro de uno.
 *
 * <h2>Quien lo usa y para que</h2>
 *
 * <p>{@code encounter} es el consumidor de esta etapa, y por dos cosas distintas: para
 * <b>verificar</b> que el caso al que se quiere colgar una sesion existe, es de la misma historia
 * y sigue activo, y para <b>pedir</b> el correlativo de esa sesion dentro del caso. La direccion
 * {@code encounter -> clinical.spi} ya existe desde 06.01 ({@link HistoriaClinicaDirectory}); la
 * inversa rompe ArchUnit y ademas seria {@code clinical} escribiendo en la tabla de otro modulo.
 *
 * <h2>Ninguno de estos metodos autoriza nada</h2>
 *
 * <p>Confian en que el llamador ya evaluo <b>su</b> permiso: quien cierra una sesion tiene
 * {@code sesion:register}, y exigirle ademas {@code hc:write} para poder numerarla duplicaria la
 * decision en dos modulos. El aislamiento de tenant SI se aplica: las dos firmas exigen
 * {@code organizationId} y ninguna resuelve por id pelado.
 *
 * <p>Y <b>no expone contenido clinico</b>: ver {@link CasoSnapshot}.
 *
 * <h2>Por que el numero se pide y no se calcula</h2>
 *
 * <p>Porque el numerador cuenta <b>por caso</b>, y el ciclo de vida del caso —cuando empieza a
 * contar, que pasa al reabrir— es justamente lo que este spi existe para que {@code encounter} no
 * tenga que saber. Si el contador viviera alla, ese modulo tendria que conocer el estado del caso
 * para numerarlo bien (challenge seccion 1).
 */
public interface CasoDirectory {

	/** El caso por su id, dentro del tenant. {@code empty} si no existe o es de otra organizacion. */
	Optional<CasoSnapshot> find(long organizationId, long casoId);

	/**
	 * Reserva y devuelve el siguiente numero de sesion <b>dentro</b> de ese caso (regla maestra 3).
	 *
	 * <h2>Se llama DENTRO de la transaccion del llamador, y eso es parte del contrato</h2>
	 *
	 * <p>El incremento toma un lock exclusivo de fila que serializa los cierres de sesiones de ese
	 * caso, y la lectura posterior ocurre con ese lock tomado. Llamarlo fuera de la transaccion que
	 * despues escribe la sesion entregaria un numero que otra transaccion podria haber vuelto a
	 * entregar.
	 *
	 * <p>La fila del numerador se asegura internamente en una <b>transaccion aparte</b>: crearla
	 * dentro de la que la bloquea produce el deadlock que este repositorio ya pago cuatro veces.
	 *
	 * <h2>El orden importa, y el llamador es quien lo garantiza</h2>
	 *
	 * <p>El cierre de sesion toma <b>dos</b> numeradores —el de la historia y este— y es la primera
	 * transaccion de este sistema que lo hace. El orden tiene que ser siempre el mismo, historia
	 * primero y caso despues, o dos cierres concurrentes de sesiones de casos cruzados se bloquean
	 * mutuamente (challenge seccion 8.4). Esto no se puede hacer cumplir desde aca: queda fijado en
	 * el javadoc de {@code SesionService#cerrar}.
	 *
	 * <p><b>Reabrir el caso no reinicia el contador.</b> La sesion siguiente a una reapertura es la
	 * 9, no la 1: renumerar seria reescribir historia clinica.
	 *
	 * @return el numero reservado, siempre positivo y nunca repetido dentro del caso
	 */
	int siguienteNumeroDeSesion(long organizationId, long casoId);
}
