package com.akine.resource.spi;

import java.time.Instant;
import java.util.List;
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
	 * Los espacios de una sede que estan EN SERVICIO durante toda la ventana
	 * {@code [desde, hasta)}.
	 *
	 * <p>Es la consulta base de disponibilidad de RF-M04-003, y es la que responde el criterio
	 * de aceptacion de la etapa: <b>las selecciones nuevas excluyen los inactivos</b>. Un
	 * recurso dado de baja o fuera de su ventana operativa no aparece aca nunca.
	 */
	List<EspacioSnapshot> enServicio(
			long organizationId, long consultorioId, Instant desde, Instant hasta);
}
