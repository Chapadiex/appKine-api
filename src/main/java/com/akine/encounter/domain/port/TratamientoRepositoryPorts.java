package com.akine.encounter.domain.port;

import com.akine.encounter.domain.TratamientoParametro;
import com.akine.encounter.domain.TratamientoRealizado;

import java.util.List;
import java.util.Optional;

/**
 * Los dos puertos de persistencia de AKINE-06.04, en un solo archivo — mismo criterio que
 * {@code PersonRepositoryPorts} y {@code ClinicalRepositoryPorts}.
 *
 * <p>Son dos y no uno porque son dos entities, y un {@code JpaRepository} gobierna una sola.
 *
 * <h2>La invariante de este archivo: toda consulta lleva {@code organizationId} Y su padre</h2>
 *
 * <p>Ninguna resuelve por id pelado. El tratamiento se busca siempre dentro de su <b>sesion</b> y
 * el parametro siempre dentro de su <b>tratamiento</b>: un id ajeno tiene que devolver vacio —404—
 * y no una fila que el llamador tenga que acordarse de descartar.
 *
 * <p>Viven en {@code domain} porque {@code application} consume puertos y nunca repositorios de
 * {@code infrastructure}: es la regla que 01.01 dejo fijada y que ArchUnit verifica.
 */
public final class TratamientoRepositoryPorts {

	private TratamientoRepositoryPorts() {
	}

	/** Persistencia de la intervencion realizada. */
	public interface TratamientoRepositoryPort {

		TratamientoRealizado save(TratamientoRealizado tratamiento);

		/** El tratamiento, acotado a su sesion y a su tenant. Ver la invariante del archivo. */
		Optional<TratamientoRealizado> findEnSesion(
				long organizationId, long sesionId, long tratamientoId);

		/**
		 * Los tratamientos <b>vigentes</b> de la sesion, en orden cronologico.
		 *
		 * <p>Es la lectura de la etapa, y la que sostiene {@code ix_tratamiento_sesion}.
		 */
		List<TratamientoRealizado> listarVigentes(long organizationId, long sesionId);

		/**
		 * El mayor {@code orden} usado en la sesion, <b>incluidas las filas dadas de baja</b>.
		 *
		 * <p>Las bajas cuentan a proposito: {@code orden} no se reutiliza, y si se reutilizara, el
		 * unique con {@code deleted_key} dejaria pasar dos vigentes con el mismo numero apenas
		 * alguien diera de baja uno y creara otro.
		 *
		 * <p><b>Esto es un {@code MAX+1} y no es el {@code MAX+1} que el repositorio prohibe.</b>
		 * El prohibido es el que no esta serializado por nada —el de un correlativo por Historia o
		 * por Caso, donde los escritores son sesiones distintas que no comparten ninguna fila—.
		 * Aca todos los escritores del mismo {@code orden} comparten la fila {@code sesion} y la
		 * leyeron con {@code OPTIMISTIC_FORCE_INCREMENT}: dos altas concurrentes no pueden
		 * commitear las dos, la segunda come 409 antes de llegar al {@code INSERT}. El unique es
		 * el respaldo, no el mecanismo — mismo reparto que {@code uk_sesion_numero} en V35.
		 *
		 * @return cero si la sesion no tiene ninguno, para que el primero sea 1
		 */
		int ultimoOrden(long organizationId, long sesionId);

		/**
		 * Las practicas de los tratamientos <b>vigentes</b> de la sesion, sin repetir.
		 *
		 * <p><b>Es lo que cierra el hueco que 04.05 dejo declarado.</b> Permite que el cierre elija
		 * la autorizacion de la practica que realmente se aplico (RF-M17-004) en vez de "la que
		 * vence antes", que podia gastar la autorizacion equivocada.
		 *
		 * <p>Devuelve ids pelados y no entities a proposito: quien lo consume es un observador que
		 * solo necesita saber contra que practicas puede imputar, y no tiene por que ver contenido
		 * clinico. Lo sostiene {@code ix_tratamiento_practica}.
		 */
		List<Long> practicasVigentesDe(long organizationId, long sesionId);
	}

	/** Persistencia de los parametros tipados. */
	public interface TratamientoParametroRepositoryPort {

		TratamientoParametro save(TratamientoParametro parametro);

		List<TratamientoParametro> listarDe(long organizationId, long tratamientoId);

		/**
		 * Borra <b>fisicamente</b> los parametros de un tratamiento.
		 *
		 * <p>Es la <b>unica excepcion a la baja logica</b> de esta etapa, declarada en V55 y en el
		 * challenge: un parametro es un <b>atributo</b> del tratamiento, como lo seria una
		 * columna. No tiene identidad ni historia propias, nadie lo referencia, ninguna auditoria
		 * lo nombra, y solo se edita mientras la sesion esta en <b>borrador</b> — una sesion
		 * cerrada no se edita, se enmienda, y eso es 06.06. Al cerrarse la sesion quedan
		 * congelados para siempre.
		 *
		 * <p><b>Si 06.06 habilita editar un tratamiento de una sesion cerrada, esta excepcion deja
		 * de valer y hay que versionarlos.</b>
		 */
		void borrarDe(long organizationId, long tratamientoId);
	}
}
