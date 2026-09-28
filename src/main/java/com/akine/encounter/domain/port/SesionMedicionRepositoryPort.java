package com.akine.encounter.domain.port;

import com.akine.encounter.domain.LateralidadMedicion;
import com.akine.encounter.domain.SesionMedicion;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Persistencia de las mediciones de una sesion (RF-M14-004).
 *
 * <p>Vive en {@code domain} porque {@code application} consume puertos y nunca repositorios de
 * {@code infrastructure}: regla que 01.01 dejo fijada y que ArchUnit verifica.
 *
 * <p><b>Toda consulta lleva {@code organizationId}</b>, aunque la sesion ya acote el alcance. Es
 * ADR-0004 y es lo que hace que el indice se use tal como esta declarado: el unique y los dos
 * indices de V52 empiezan por esa columna.
 */
public interface SesionMedicionRepositoryPort {

	SesionMedicion save(SesionMedicion medicion);

	/**
	 * La medicion de esa medida y ese lado en esa sesion, si ya existe.
	 *
	 * <p><b>Es lo que hace idempotente al {@code PUT}.</b> Se consulta <b>antes</b> de insertar,
	 * dentro de la misma transaccion, y el unique de V52 queda como red por si dos autosaves
	 * llegan a la vez. Para un {@code PUT} idempotente eso alcanza: el perdedor de la carrera
	 * escribio el mismo valor que el ganador o uno posterior, y en cualquier caso reintentar
	 * converge.
	 *
	 * <p>Lo que NO se hace es atrapar el choque del unique y consultar despues: tras un flush
	 * fallido la transaccion queda {@code rollbackOnly} y la lectura posterior produce un 500 en
	 * vez del 200 que el contrato promete. Es la regla que este repositorio ya pago cuatro veces.
	 */
	Optional<SesionMedicion> buscarEnSesion(
			long organizationId, long sesionId, long definicionId, LateralidadMedicion lateralidad);

	/** Todas las mediciones de una sesion, en el orden del indice. */
	List<SesionMedicion> listarDeSesion(long organizationId, long sesionId);

	/**
	 * Las mediciones de <b>varias</b> sesiones de una vez.
	 *
	 * <p>La usa la comparacion, que necesita las de esta sesion y las de la anterior. Una consulta
	 * y no dos porque el resultado se indexa igual por sesion y evita un segundo viaje.
	 */
	List<SesionMedicion> listarDeSesiones(long organizationId, Collection<Long> sesionIds);

	/**
	 * Borrado <b>fisico</b>, acotado por el servicio a la sesion en curso.
	 *
	 * <p>No hay baja logica y no contradice la regla maestra 10: lo que nunca se cerro no es
	 * informacion historica. Es el mismo criterio con el que 04.04 admitio borrar items de un plan
	 * en BORRADOR. Sobre una sesion cerrada la operacion no existe, y <b>eso lo decide el
	 * servicio</b>, no la pantalla ni esta interfaz.
	 */
	void delete(SesionMedicion medicion);

	/**
	 * La ultima sesion <b>cerrada</b> anterior a {@code antesDe} que tenga alguna medicion.
	 *
	 * <p>Es el baseline de la comparacion, y cada uno de sus tres filtros esta por algo:
	 *
	 * <ul>
	 *   <li><b>Cerrada.</b> Una sesion en borrador es eso, un borrador: compararse contra algo
	 *       cuyo contenido cambia mientras alguien mira no es una comparacion. Solo la sesion
	 *       cerrada es un hecho clinico ocurrido (DP-05). Es el mismo filtro que el timeline de
	 *       04.02 y el avance del plan de 04.04.</li>
	 *   <li><b>Con mediciones.</b> Devolver la anterior sin mediciones mostraria una columna
	 *       "anterior" vacia donde el profesional espera numeros, y le ocultaria la que si los
	 *       tiene.</li>
	 *   <li><b>Del mismo Caso, cuando la sesion tiene caso.</b> 04.03 admite varios casos activos
	 *       —una rodilla y un hombro— y comparar el ROM de rodilla contra la sesion del hombro es
	 *       comparar contra nada. Cuando la sesion no tiene caso se cae a "la anterior del
	 *       paciente", que es lo unico que existe para las sesiones anteriores a 04.03.</li>
	 * </ul>
	 *
	 * <p>Que no haya baseline <b>no es un error</b>: el llamador responde {@code anterior = null}.
	 *
	 * @param casoId {@code null} no filtra por caso
	 */
	Optional<Long> idSesionAnteriorConMediciones(
			long organizationId, long historiaClinicaId, Long casoId, java.time.Instant antesDe);
}
