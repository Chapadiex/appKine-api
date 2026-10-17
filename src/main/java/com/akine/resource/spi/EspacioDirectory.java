package com.akine.resource.spi;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Consulta de espacios para los modulos que reservan o registran hechos sobre ellos.
 *
 * <h2>Quien lo va a usar, y por que existe antes que ellos</h2>
 *
 * <p>{@code scheduling} (RF-M04-004, asignar espacio a turno) y {@code clinical} (RF-M04-005,
 * espacio realmente utilizado en la sesion) son los consumidores previstos, y ninguno de los
 * dos existe todavia. El puerto se declara igual porque es lo que convierte a AKINE-02.02 en
 * un cimiento y no en una pantalla: la alternativa —que F5 lea {@code espacio} directamente—
 * viola la regla 1 de AGENT.md seccion 4 y ArchUnit la rechaza, asi que el dia que llegue
 * habria que escribir esto igual, con la diferencia de que ya habria codigo escrito contra el
 * atajo.
 *
 * <p><b>Ninguno de estos metodos autoriza nada.</b> Devuelven datos del tenant que se les pide
 * y confian en que el llamador ya evaluo el permiso: mezclar autorizacion aca obligaria a cada
 * consumidor a pasar su actor y su codigo de permiso, y la decision quedaria duplicada en dos
 * modulos. El aislamiento de tenant SI se aplica: toda firma exige {@code organizationId} y
 * ninguna consulta resuelve por id pelado.
 */
public interface EspacioDirectory {

	/**
	 * Un espacio del tenant, activo o no, con {@code enServicio} evaluado en {@code at}.
	 *
	 * <p>Devuelve tambien los dados de baja porque el consumidor necesita distinguir "ese
	 * espacio no es tuyo" de "ese espacio ya no se usa": el primero es un 404 y el segundo un
	 * 409, y colapsarlos haria imposible mostrar un historico.
	 */
	Optional<EspacioSnapshot> find(long organizationId, long espacioId, Instant at);

	/**
	 * {@link #find} para varios espacios en UNA consulta, indexados por id. Los que no existen o son
	 * de otro tenant no aparecen en el mapa; los dados de baja si, igual que en {@link #find}.
	 *
	 * <p>Para listas: pedir el espacio de cada fila con {@link #find} es una consulta por fila
	 * (docs/carga.md §6.4). {@code enServicio} se evalua en {@code at} para todos.
	 */
	Map<Long, EspacioSnapshot> findTodos(long organizationId, Collection<Long> espacioIds, Instant at);

	/**
	 * Los espacios de una sede que estan EN SERVICIO durante toda la ventana
	 * {@code [desde, hasta)}.
	 *
	 * <p>Es la consulta base de disponibilidad de RF-M04-003, y es la que responde el criterio
	 * de aceptacion de la etapa: <b>las selecciones nuevas excluyen los inactivos</b>. Un
	 * recurso dado de baja o fuera de su ventana operativa no aparece aca nunca.
	 */
	List<EspacioSnapshot> enServicio(
			long organizationId, long consultorioId, Instant desde, Instant hasta);

	/**
	 * {@code true} si el espacio esta dado de baja (o no es del tenant), leido con <b>lock
	 * compartido</b> que dura hasta el fin de la transaccion del llamador, que tiene que existir.
	 *
	 * <p>Para quien asigna el espacio a un hecho nuevo —hoy la reserva de {@code scheduling}, bajo
	 * su lock de {@code agenda_sede}—. La baja del espacio toma el exclusivo de la misma fila antes
	 * de preguntar por turnos pendientes, asi que con esto un turno ya no puede colarse en un box
	 * entre la sonda y el commit de la baja: es la misma carrera que la de la sede.
	 */
	boolean inactivoBajoLockCompartido(long organizationId, long espacioId);
}
