package com.akine.organization.spi;

import java.util.Optional;

/**
 * Resolucion de una sede para los modulos que cuelgan hechos de ella.
 *
 * <h2>Por que existe, y por que no es lo mismo que {@link AccountContextDirectory}</h2>
 *
 * <p>{@code AccountContextDirectory} responde preguntas sobre una CUENTA —donde puede
 * trabajar, que membership tiene—. Esto responde una pregunta sobre una SEDE: existe, es de
 * este tenant, y esta activa. Son consumidores distintos: el primero lo usa la autenticacion,
 * este lo usa cualquier modulo que necesite validar el {@code consultorioId} de una fila
 * propia antes de escribirla.
 *
 * <p>El primer consumidor es {@code resource} (M04): RN-M04-001 dice que todo espacio
 * pertenece a un consultorio, y la FK de la base garantiza que el id exista pero <b>no</b>
 * que sea del tenant del request. Sin esta consulta, un {@code consultorioId} ajeno inyectado
 * en la URL crearia una fila con el {@code organization_id} del atacante y el
 * {@code consultorio_id} de la victima: la FK la aceptaria sin objetar nada.
 *
 * <p><b>La flecha va {@code resource -> organization.spi}</b>, que es la direccion permitida:
 * {@code organization} es cimiento de {@code resource} en la ruta critica de dominio
 * (AGENT.md seccion 9). No hay ciclo y no hace falta invertir el puerto, a diferencia de
 * {@link ConsultorioDeactivationProbe}, que si lo esta porque alli el cimiento necesita
 * preguntarle al consumidor.
 */
public interface ConsultorioDirectory {

	/**
	 * La sede, acotada al tenant, <b>activa o no</b>.
	 *
	 * <p>Se busca por el par {@code (organizationId, consultorioId)} y nunca por id pelado:
	 * una sede de otro tenant no resuelve, y el llamador la traduce al mismo 404 que una
	 * inexistente. Distinguirlas permitiria enumerar las sedes del SaaS probando ids.
	 *
	 * <p>Devuelve tambien las INACTIVAS porque quien las consulta necesita distinguir dos
	 * rechazos distintos: "esa sede no es tuya" (404) y "esa sede esta dada de baja" (409).
	 * Filtrar aca las colapsaria en uno solo y borraria historia por la puerta de atras.
	 */
	Optional<ConsultorioSnapshot> find(long organizationId, long consultorioId);

	/**
	 * {@code true} si la sede esta dada de baja (o no es del tenant), leida con <b>lock
	 * compartido</b> que dura hasta el fin de la transaccion del llamador. Exige una transaccion
	 * abierta: sin ella el lock se soltaria al volver.
	 *
	 * <p>Es para las escrituras que crean hechos nuevos en la sede y compiten con su baja —hoy, las
	 * de agenda de {@code scheduling}, bajo su lock de {@code agenda_sede}—. La baja toma el lock
	 * exclusivo de la misma fila ANTES de preguntarle a las sondas, asi que la que llega segunda
	 * espera a la otra y ve lo que dejo: o la sonda ve el turno, o la reserva ve la sede de baja.
	 * Validar con {@link #find} fuera del lock dejaba colar un turno entre la sonda y el commit de
	 * la baja (decision pendiente 8b).
	 *
	 * <p>Dice "dada de baja" y no "activa" a proposito: es la respuesta que corta la escritura.
	 */
	boolean inactivaBajoLockCompartido(long organizationId, long consultorioId);
}
