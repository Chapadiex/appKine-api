package com.akine.scheduling.spi;

import java.time.Instant;
import java.util.List;

/**
 * El punto de serializacion de la agenda de una sede, y las lecturas de ocupacion que hay que hacer
 * bajo el. <b>Es la costura que permite que una clase y un turno se excluyan entre si.</b>
 *
 * <h2>El problema que resuelve</h2>
 *
 * <p>Una clase ocupa un espacio y un profesional durante una franja, exactamente igual que un
 * turno. Ningun unique puede impedir que se pisen —un unique compara igualdad, y dos eventos se
 * cruzan cuando sus INTERVALOS se solapan— y MySQL 8.4 no tiene exclusion constraints. Lo unico que
 * hace cumplir la regla es el lock de {@code agenda_sede}.
 *
 * <p>Si {@code activity} serializara sus clases contra una fila propia, las dos transacciones
 * <b>no se verian</b>: las dos ganarian, el box quedaria doblemente vendido y <b>nada fallaria</b>.
 * Un defecto silencioso, que es el peor tipo. Por eso existe este contrato: para que la clase se
 * dispute <b>la misma fila</b> que el turno.
 *
 * <p>Que un modulo bloquee una fila de otro es acoplamiento, y conviene llamarlo por su nombre. Es
 * acoplamiento deliberado: la alternativa —un segundo punto de serializacion— no es menos
 * acoplamiento, es el mismo sin exclusion. Esta forma lo deja visible en el {@code spi} en vez de
 * esconderlo.
 *
 * <h2>El orden, y por que no es negociable</h2>
 *
 * <pre>
 *   0. asegurar(...)   &lt;- en una transaccion APARTE, antes de abrir la del negocio
 *   1. bloquear(...)   &lt;- lo PRIMERO de la transaccion, antes de leer nada
 *   2. leer ocupacion
 *   3. escribir
 * </pre>
 *
 * <p><b>Paso 0:</b> crear la fila-lock perezosamente dentro de la transaccion que la bloquea
 * produce un DEADLOCK entre las primeras N escrituras de una sede, no una violacion de unique que
 * una pierda limpiamente. Y el {@code try/catch} no salva: atrapar una excepcion de persistencia no
 * des-marca la transaccion, y Spring lanza {@code UnexpectedRollbackException} al commitear. Ya se
 * pago cuatro veces en este proyecto.
 *
 * <p><b>Paso 1:</b> leer primero y bloquear despues es una escalada S -&gt; X sobre las mismas
 * filas, y dos transacciones concurrentes se esperan mutuamente.
 *
 * <p><b>Y la transaccion va en {@code READ_COMMITTED}</b>: con {@code REPEATABLE READ} InnoDB fija
 * la foto en la primera lectura consistente —anterior al lock— y el lock deja de servir para lo
 * unico que sirve.
 *
 * <h2>Lo que este contrato NO hace</h2>
 *
 * <p><b>No autoriza nada.</b> Quien lo llama ya resolvio pertenencia y permiso con su propio
 * criterio: el permiso de la agenda no es el mismo que el de programar una clase.
 */
public interface AgendaDeSede {

	/**
	 * Se asegura de que exista la fila que despues se va a bloquear. Idempotente.
	 *
	 * <p><b>Corre en su propia transaccion</b> ({@code REQUIRES_NEW}) y hay que llamarla ANTES de
	 * abrir la transaccion del negocio. Ver el paso 0 de la cabecera.
	 */
	void asegurar(long organizationId, long consultorioId);

	/**
	 * Toma el lock exclusivo de la agenda de la sede. <b>Primera sentencia de la transaccion.</b>
	 *
	 * @throws IllegalStateException si la fila no existe, o sea si nadie llamo a {@link #asegurar}
	 *                               antes
	 */
	void bloquear(long organizationId, long consultorioId);

	/**
	 * Turnos vivos de ese profesional que se cruzan con {@code [inicio, fin)}.
	 *
	 * <p>Se llama <b>bajo el lock</b>. Fuera de el la respuesta es una foto y no prueba nada.
	 */
	List<OcupacionDeAgenda> turnosDeProfesionalQueCruzan(
			long organizationId, long profesionalMembershipId, Instant inicio, Instant fin);

	/** Lo mismo para un espacio. Ver {@link #turnosDeProfesionalQueCruzan}. */
	List<OcupacionDeAgenda> turnosDeEspacioQueCruzan(
			long organizationId, long espacioId, Instant inicio, Instant fin);
}
