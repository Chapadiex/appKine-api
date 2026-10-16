package com.akine.encounter.spi;

import java.util.List;

/**
 * La atencion clinica de los participantes de una clase (AKINE-08.05, RF-M14-011..013).
 *
 * <h2>Una Sesion por participante, no una Sesion de la clase</h2>
 *
 * <p>Cada participante derivado tiene <b>su</b> Sesion: la misma entidad, el mismo borrador, la
 * misma evaluacion, los mismos tratamientos, el mismo cierre con sus dos numeradores y la misma
 * enmienda que una atencion de turno. No existe una evolucion de la clase que sustituya los
 * registros individuales (RF-M14-013), y cerrar la de una persona no cierra la de otra
 * (CA-M14-013-06). Lo que este puerto agrega es el <b>origen</b>: de que derivacion, de que
 * asistencia, de que clase.
 *
 * <h2>La direccion de la dependencia</h2>
 *
 * <p>{@code activity -> encounter.spi}. {@code encounter} no conoce una clase ni una asistencia, y
 * no puede: nadie depende de {@code activity} hoy, y {@code encounter -> activity} cerraria el ciclo
 * con esta arista. Lo grupal lo valida el llamador y lo afirma en {@link PedidoDeAtencion}; lo
 * clinico lo valida este modulo con {@code clinical.spi}.
 *
 * <h2>Este puerto SI autoriza</h2>
 *
 * <p>Como {@code clinical.spi.DerivacionClinicaRegistry} y por el mismo motivo: la propiedad de la
 * atencion, {@code sesion:register} y la auditoria de la Sesion son de {@code encounter}, y la
 * politica clinica de DP-03 es de {@code clinical}. El llamador autoriza ver la clase; nada mas.
 */
public interface AtencionGrupal {

	/**
	 * Abre la atencion del participante, o devuelve la que ya existe ({@code yaExistia = true}).
	 *
	 * <p>Exige {@code sesion:register} en la sede y {@code hc:write} con relacion asistencial o
	 * justificacion. Quien la abre es su profesional: escribir en ella sera solo suyo (409 a
	 * cualquier otro, no 403). No numera nada: el numero dentro del Caso se asigna al cerrar, como
	 * en toda Sesion.
	 */
	AtencionDeParticipante iniciar(PedidoDeAtencion pedido);

	/**
	 * Las atenciones de una clase, sin contenido clinico. Exige {@code sesion:register} y queda
	 * auditada: decir que una persona tiene una atencion clinica abierta ya es informacion clinica.
	 */
	List<AtencionDeParticipante> deLaClase(ActorDeAtencion actor, long claseId);
}
