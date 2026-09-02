package com.akine.contracting.spi;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Resolucion economica de M16 para modulos que no son duenos de los convenios.
 *
 * <p><b>No autoriza nada.</b> Es una costura entre modulos, no una API: quien la llama ya resolvio
 * pertenencia y permiso con su propio criterio. {@code convenio:manage} es de quien administra los
 * convenios, no de quien devenga una obligacion, asi que una llamada aca nunca sustituye a un
 * control de acceso. Mismo contrato, y misma advertencia, que {@code OfertaDirectory} y
 * {@code CoberturaCatalogoDirectory}.
 *
 * <p>Todas las firmas llevan {@code organizationId} <b>y</b> {@code consultorioId}: el convenio es
 * contextual a la sede (RN-M16-001) y una fila de otra sede no resuelve nunca.
 *
 * <h2>Las dos mitades de esta interfaz hacen cosas OPUESTAS</h2>
 *
 * <pre>
 *   resolver   LECTURA VIVA. Para DECIDIR y para mostrar. Refleja el estado de hoy, y por eso
 *              cambia cuando alguien corrige el arancel.
 *
 *   congelar   COPIA CONGELADA. Para GUARDAR. Devuelve ArancelCongelado, que el consumidor
 *              escribe en sus propias columnas y no vuelve a pedir.
 * </pre>
 *
 * <p>Confundirlas es lo que RN-M16-003 prohibe: guardar un {@code arancelId} y resolver el importe
 * al mostrar hace que subir el arancel en enero reescriba lo que se debia en diciembre. Es la misma
 * separacion que 03.03 fijo entre {@code findPlan} y {@code congelar}, y la misma disciplina que
 * {@code obligacion} ya aplica al precio de la oferta desde 07.01.
 *
 * <h2>El resultado es unico, y no por una regla de prioridad</h2>
 *
 * <p>Para una {@code (sede, financiador, plan, practica, fecha)} hay como mucho un convenio y un
 * arancel, porque dos convenios del mismo alcance no pueden solaparse y dos aranceles de la misma
 * practica en el mismo convenio tampoco (RN-M16-002). <b>El resultado unico sale de que no existan
 * dos candidatas</b>, no de un desempate — que es lo que hace que sea explicable y estable en el
 * tiempo.
 */
public interface ArancelDirectory {

	/**
	 * El arancel que se aplica ese dia, o el motivo por el que no hay (RF-M16-006, RF-M16-010).
	 *
	 * <p>LECTURA VIVA: no guardar el resultado. Ver la cabecera.
	 *
	 * <p><b>Nunca devuelve {@code null} ni lanza por "no encontrado".</b> Que la ausencia sea un
	 * valor con motivo y no una excepcion es deliberado: no encontrar convenio es un desenlace
	 * normal —el paciente se atiende como particular— y quien pregunta tiene que poder distinguirlo
	 * de un error. RN-M16-005: sin convenio valido no se asume cobertura.
	 *
	 * @param fecha dia de la prestacion, en la zona local de la sede. Explicito y nunca un reloj
	 *              implicito, misma regla que {@code PermissionQuery.at}
	 */
	ResolucionDeArancel resolver(
			long organizationId,
			long consultorioId,
			long financiadorId,
			long planId,
			long practicaId,
			LocalDate fecha);

	/**
	 * Congela el arancel de ese dia para que el consumidor lo COPIE a sus propias columnas.
	 *
	 * <p>Devuelve {@code empty} cuando no hay arancel aplicable, por cualquiera de los motivos de
	 * {@link MotivoSinArancel}. <b>Que el rechazo sea la ausencia de valor y no una excepcion es
	 * deliberado</b>: quien devenga una obligacion tiene que decidir que hacer con una prestacion
	 * sin convenio —cobrarla como particular, casi siempre— y esa decision es suya, no de este
	 * modulo. Si ademas necesita saber POR QUE no hay, pregunta con {@link #resolver}.
	 *
	 * <p>Lo devuelto ya no depende de esta interfaz: es una copia. Volver a llamar mañana puede dar
	 * otro resultado, y esa es exactamente la razon por la que el consumidor tiene que guardarlo.
	 */
	Optional<ArancelCongelado> congelar(
			long organizationId,
			long consultorioId,
			long financiadorId,
			long planId,
			long practicaId,
			LocalDate fecha);
}
