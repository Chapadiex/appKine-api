package com.akine.contracting.domain.port;

import com.akine.contracting.domain.Convenio;
import com.akine.contracting.domain.ConvenioArancel;
import com.akine.contracting.domain.ConvenioLock;

import java.util.List;
import java.util.Optional;

/**
 * Los tres puertos de persistencia de M16, en un solo archivo, mismo criterio que
 * {@link ContractingRepositoryPorts}.
 *
 * <h2>Toda firma lleva el tenant Y LA SEDE</h2>
 *
 * <p>A diferencia de M15, donde el alcance es la organizacion, aca el alcance es el
 * <b>consultorio</b> (RN-M16-001). No hay FK compuesta que lo garantice: a nivel de base una fila
 * podria declarar la organizacion A y apuntar a un consultorio de la B. El aislamiento lo da que
 * cada consulta lleve las columnas en el {@code WHERE}, y si se agrega una consulta nueva se
 * verifica esto individualmente y no por analogia con las demas.
 *
 * <h2>El puerto del lock es distinto de los otros dos y conviene entender por que</h2>
 *
 * <p>{@link ConvenioLockRepositoryPort} no persiste informacion: existe para serializar. Sus dos
 * metodos se usan en un orden fijo y en transacciones distintas —{@code crearSiFalta} en una
 * propia, {@code lockByScope} dentro de la que escribe— y hacerlo al reves produce los deadlocks
 * que este proyecto ya pago tres veces. El detalle esta en {@code ConvenioLockIniciador}.
 */
public final class ConvenioRepositoryPorts {

	private ConvenioRepositoryPorts() {
		// Contenedor de puertos.
	}

	/** Acceso a los convenios de una sede (M16). */
	public interface ConvenioRepositoryPort {

		Convenio save(Convenio convenio);

		/**
		 * Guarda y FUERZA el flush.
		 *
		 * <p>La violacion de {@code uk_convenio_codigo_vigente} tiene que manifestarse dentro del
		 * bloque que sabe traducirla a su 409, y no al cerrar la transaccion, donde ya no hay a
		 * quien avisarle y el advice generico responde 500.
		 *
		 * <p><b>Despues de un flush fallido no se vuelve a tocar la sesion JPA.</b> Misma trampa,
		 * y misma consecuencia —un 500 en vez del 409 legitimo—, que documenta
		 * {@code FinanciadorRepositoryPort#saveAndFlush}.
		 */
		Convenio saveAndFlush(Convenio convenio);

		/** Un convenio de ESA sede y ESE tenant, activo o no. */
		Optional<Convenio> findByIdAndScope(Long id, Long organizationId, Long consultorioId);

		/** Los convenios de una sede, activos e historicos, ordenados por nombre. */
		List<Convenio> findAllByScopeOrderByNombreAsc(Long organizationId, Long consultorioId);

		/**
		 * Los convenios ACTIVOS de esa {@code (sede, financiador, plan)}.
		 *
		 * <p>Es el conjunto contra el que se valida el no-solapamiento (RN-M16-002) y tambien el
		 * que resuelve RF-M16-006. <b>Se lee SIEMPRE despues de tomar el lock</b> de
		 * {@code convenio_lock} cuando se va a escribir: leer primero y bloquear despues es una
		 * escalada S-&gt;X entre dos transacciones simetricas, o sea un deadlock.
		 *
		 * <p>Devuelve solo los activos porque un convenio dado de baja no compite por el periodo:
		 * ya no se aplica, y exigir que su ventana quede libre impediria firmar de nuevo con el
		 * mismo plan despues de una baja.
		 *
		 * <p>Ordenado por {@code vigencia_desde DESC, id DESC}: es el desempate determinista de la
		 * resolucion. Ver {@code ArancelService#resolver}.
		 */
		List<Convenio> findActivosPorAlcance(
				Long organizationId, Long consultorioId, Long financiadorId, Long planId);
	}

	/** Acceso a los aranceles de un convenio (M16). */
	public interface ConvenioArancelRepositoryPort {

		ConvenioArancel save(ConvenioArancel arancel);

		ConvenioArancel saveAndFlush(ConvenioArancel arancel);

		/** Un arancel de ESE convenio y ESE tenant, activo o no. */
		Optional<ConvenioArancel> findByIdAndScope(
				Long id, Long organizationId, Long convenioId);

		/** Los aranceles de un convenio, activos e historicos, del mas nuevo al mas viejo. */
		List<ConvenioArancel> findAllByConvenio(Long organizationId, Long convenioId);

		/**
		 * Los aranceles ACTIVOS de esa practica en ese convenio.
		 *
		 * <p>Es el conjunto contra el que se valida el no-solapamiento y el que resuelve
		 * RF-M16-010. Mismas dos advertencias que {@code findActivosPorAlcance}: se lee despues del
		 * lock, y devuelve solo activos.
		 */
		List<ConvenioArancel> findActivosPorPractica(
				Long organizationId, Long convenioId, Long practicaId);

		/**
		 * Cuantos aranceles ACTIVOS cuelgan del convenio.
		 *
		 * <p>La baja de un convenio no se bloquea por tenerlos —obligar a darlos de baja uno por
		 * uno es burocracia sin garantia a cambio, mismo criterio que la baja de un financiador en
		 * 03.03— pero el numero queda en la auditoria.
		 */
		long countActivosDeConvenio(Long organizationId, Long convenioId);
	}

	/**
	 * La fila-lock por sede. <b>No guarda estado.</b>
	 *
	 * <p>Ver {@code ConvenioLock} y la cabecera de V43: es el punto de serializacion sin el cual el
	 * control de no-solapamiento no resiste dos escrituras concurrentes.
	 */
	public interface ConvenioLockRepositoryPort {

		/**
		 * Lock exclusivo sobre la fila de la sede. Serializa TODAS las escrituras de convenios y
		 * aranceles de esa sede.
		 *
		 * <p>La fila tiene que EXISTIR antes: la crea {@link #crearSiFalta} en una transaccion
		 * aparte.
		 */
		Optional<ConvenioLock> lockByScope(long organizationId, long consultorioId);

		/** Crea la fila si no existe. <b>Sin lanzar nunca</b>, y ese es todo el punto. */
		void crearSiFalta(long organizationId, long consultorioId);
	}
}
