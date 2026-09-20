package com.akine.clinical.spi;

import java.util.List;

/**
 * Pregunta cuantas atenciones ocurrieron dentro de un Caso Clinico, por oferta. Es la costura que
 * hace que el avance del Plan de Tratamiento se <b>derive</b> en vez de guardarse (RN-M11-001).
 *
 * <h2>Por que la interfaz vive aca y no en {@code encounter}</h2>
 *
 * <p>{@code clinical} necesita un dato que vive en {@code encounter}. La forma equivocada es que
 * {@code clinical} importe {@code encounter}; la correcta es la que 05.03 ya uso con
 * {@code scheduling.spi.AtencionProbe}: <b>la sonda se declara donde se consume y la implementa
 * quien tiene el dato</b>, porque {@code encounter -> clinical.spi} ya existe desde 06.01 y 04.03.
 * No se agrega ninguna arista nueva: se le agrega peso a una que ya va en ese sentido.
 *
 * <p>El atajo que hay que no tomar: un agente que necesite el estado de una sesion va a querer
 * importar {@code encounter.domain}. <b>Prohibido</b>, y ArchUnit lo rechaza.
 *
 * <h2>Por que existe: la columna que no hay</h2>
 *
 * <p>{@code plan_item} <b>no tiene</b> {@code cantidad_realizada} ni {@code cantidad_cancelada}. Si
 * las tuviera, su dueño real seria {@code encounter} —el unico modulo que sabe cuando una sesion se
 * cerro— y {@code clinical} tendria una columna que solo otro modulo puede mantener correcta. Ese
 * es el camino por el que un contador se desincroniza: no por mala fe, sino porque el dueño del
 * dato y el dueño de la fila son distintos (challenge seccion 1).
 *
 * <p>Derivar al leer tiene una ventaja concreta que conviene tener presente: <b>una sesion que se
 * esta cerrando mientras alguien mira el avance no produce lectura sucia</b>, porque no hay contador
 * que actualizar. Entra o no entra segun haya commiteado, y las dos respuestas son correctas.
 *
 * <h2>Que garantiza y que no</h2>
 *
 * <p><b>No trae contenido clinico.</b> Devuelve cuentas por oferta y nada mas: ni evolucion, ni
 * nota de cierre, ni diagnostico. Si algun dia devolviera contenido, este {@code spi} pasaria a ser
 * una via de lectura clinica sin permiso clinico — motivo de mas para que no lo haga.
 *
 * <p><b>No autoriza nada.</b> Confia en que el llamador ya evaluo su permiso. El aislamiento de
 * tenant SI se aplica: la firma exige {@code organizationId}.
 *
 * <p>Es una lectura, o sea una <b>foto</b>. Una sesion puede cerrarse un instante despues de que
 * esto conteste, y no es un agujero de correctitud: el avance es una lectura y la proxima lo
 * incluye.
 *
 * <h2>El limite declarado, que 04.05 tiene que conocer antes de empezar</h2>
 *
 * <p>Se cuenta <b>por oferta, no por practica individual</b> dentro de la sesion: los tratamientos
 * realizados son 06.04 y estan fuera de alcance. Una sesion cuenta como una realizacion del item
 * cuya oferta coincide. Cuando 04.05 compare "autorizadas" contra "realizadas" para decidir
 * elegibilidad, va a estar comparando dos granularidades distintas.
 */
public interface RealizadoEnElCasoProbe {

	/**
	 * Cuenta las sesiones cerradas del caso, agrupadas por oferta.
	 *
	 * <p>Solo <b>cerradas</b>: una sesion en borrador es eso, un borrador, y contarla haria que el
	 * avance de un plan se moviera mientras alguien tipea. La sesion cerrada si es un hecho clinico
	 * ocurrido, que es lo que DP-05 distingue de cualquier transicion administrativa.
	 *
	 * @return una entrada por oferta con al menos una sesion cerrada. Las ofertas sin sesiones
	 *         <b>no aparecen</b>: el llamador las resuelve como cero, que es lo mismo y evita que
	 *         esta sonda tenga que saber que ofertas planifico un plan que no conoce
	 */
	List<RealizadoPorOferta> contarPorOfertaEnElCaso(long organizationId, long casoId);
}
