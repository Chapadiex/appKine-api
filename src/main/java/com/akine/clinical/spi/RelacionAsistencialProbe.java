package com.akine.clinical.spi;

/**
 * Si existe relacion asistencial entre un actor y un paciente (DP-03).
 *
 * <h2>Que responde hoy, y que NO — dicho antes de que alguien lo asuma</h2>
 *
 * <p><b>Hoy responde siempre que no hay evidencia, y no es un bug.</b> La relacion asistencial se
 * demuestra con un turno o una sesion, y los dos llegan con 05.0x y 06.01: al 31/08/2026 ninguno
 * existe. La forma de sumar esa mitad esta declarada aca y no cambia el contrato: cuando haya una
 * implementacion real, {@link #tieneRelacionAsistencial} empieza a devolver {@code true} para el
 * profesional que atiende, y ese profesional deja de necesitar justificacion.
 *
 * <p>Mismo patron y mismo motivo que {@code resource.spi.DisponibilidadImpactProbe}. Y como alli,
 * la consecuencia de hoy hay que decirla completa: <b>mientras esto devuelva "sin evidencia", todo
 * acceso clinico exige justificacion declarada</b>. Es mas friccion de la que va a haber cuando
 * exista la agenda, y es el lado seguro del error — la alternativa, conceder por defecto mientras
 * no hay con que verificar, deja el sistema abierto justo en el periodo en que nadie lo mira.
 *
 * <h2>Bean singular y no lista</h2>
 *
 * <p>La relacion asistencial de un actor con un paciente es una sola pregunta con una sola fuente
 * de verdad —la agenda—, no un agregado de varias fuentes que haya que sumar. Por eso
 * {@code clinical} registra hoy una implementacion por defecto de este mismo tipo en vez de dejar
 * una lista vacia: el consumidor inyecta un {@code RelacionAsistencialProbe} unico y el contexto
 * tiene que levantar con algo que responder. Cuando llegue la real, la reemplaza; no conviven.
 */
public interface RelacionAsistencialProbe {

	/**
	 * {@code true} si ese actor atendio, atiende o tiene agendado atender a esa persona en esa
	 * sede.
	 *
	 * <p>La sede entra en la pregunta porque DP-03 permite consultar desde distintos consultorios
	 * de la misma organizacion: quien decide si eso alcanza es esta sonda, no el llamador.
	 */
	boolean tieneRelacionAsistencial(
			long organizationId, long consultorioId, long actorAccountId, long personaId);
}
