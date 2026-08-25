package com.akine.resource.domain.port;

import com.akine.resource.domain.CatalogoSolicitud;
import com.akine.resource.domain.Especialidad;
import com.akine.resource.domain.Nomenclador;
import com.akine.resource.domain.NomencladorItem;
import com.akine.resource.domain.Practica;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Los cinco puertos de persistencia del catalogo clinico (M06), en un solo archivo.
 *
 * <h2>Por que estan juntos</h2>
 *
 * <p>Los cinco comparten <b>un unico protocolo de aislamiento</b>, y tenerlos a la vista es lo
 * que hace evidente que ninguno se aparta de el. Repartirlos en cinco archivos escondia la
 * unica cosa que hay que verificar de un vistazo: que toda consulta esta acotada por
 * {@code owners}.
 *
 * <h2>{@code owners}: el aislamiento de este modulo, y por que no es {@code organizationId}</h2>
 *
 * <p>El resto del sistema filtra por {@code organization_id = ?}. Aca <b>no alcanza</b>, porque
 * un concepto global no tiene tenant y tiene que ser visible para todos. Toda consulta recibe
 * en cambio la coleccion de <b>duenios visibles</b>, expresada sobre la columna generada
 * {@code owner_key} —el id del tenant, o el centinela {@code 0} para lo global—:
 *
 * <pre>
 *   solo global              -> [0]
 *   solo el tenant           -> [organizationId]
 *   lo que ve un tenant      -> [0, organizationId]
 * </pre>
 *
 * <p>La lista la arma el servicio <b>despues</b> de haber validado el contexto, nunca el
 * cliente. Un id de otro tenant no aparece en ninguna de las tres formas, asi que no resuelve
 * nunca: es la misma garantia que ADR-0004 pide, dicha sobre la columna que el indice usa.
 *
 * <h2>Por que las consultas son nativas</h2>
 *
 * <p>Porque {@code owner_key} y {@code deleted_key} son columnas <b>generadas</b>: no existen en
 * la entidad —mapearlas invitaria a escribirlas— y por lo tanto no se pueden nombrar en JPQL.
 * Escribirlas en SQL ademas deja que el planificador use {@code ix_*_owner_estado} tal cual esta
 * declarado, y hace que la sentencia que se lee sea exactamente la que corre.
 *
 * <h2>Los filtros opcionales viajan como centinelas, no como {@code null}</h2>
 *
 * <p>{@code activoFiltro = -1}, {@code especialidadId = -1} y {@code codigo = ""} significan "no
 * filtres por eso". La alternativa —{@code (:param IS NULL OR col = :param)}— obliga a que
 * Hibernate infiera el tipo de un parametro nulo en una consulta nativa, que es exactamente el
 * caso en el que falla con un "could not determine type". Un centinela imposible no tiene ese
 * problema y produce el mismo plan.
 */
public final class CatalogoRepositoryPorts {

	private CatalogoRepositoryPorts() {
		// Contenedor de puertos.
	}

	/** Especialidades clinicas (RF-M06-001). */
	public interface EspecialidadPort {

		Especialidad save(Especialidad especialidad);

		/**
		 * Guarda y FUERZA el flush.
		 *
		 * <p>La violacion de {@code uk_especialidad_codigo_vigente} tiene que manifestarse dentro
		 * del bloque que sabe traducirla a un 409, y no al cerrar la transaccion, donde ya no hay
		 * a quien avisarle y el advice generico responde 500.
		 *
		 * <p><b>Despues de un flush fallido no se vuelve a tocar la sesion JPA.</b> Ni una
		 * lectura, ni un {@code save}, ni la auditoria: la especificacion prohibe seguir usando
		 * un {@code EntityManager} tras un flush que fallo, y lo que sale de ahi es un
		 * {@code AssertionFailure} que convierte un 409 legitimo en un 500.
		 */
		Especialidad saveAndFlush(Especialidad especialidad);

		/** Una especialidad por id, acotada a los duenios visibles. Activa o no. */
		Optional<Especialidad> findVisible(Long id, Collection<Long> owners);

		/** Busqueda ordenada por nombre. Ver los centinelas en el javadoc de la clase. */
		List<Especialidad> buscar(Collection<Long> owners, String patron, int activoFiltro);
	}

	/** Prestaciones (RF-M06-002, RF-M06-004). */
	public interface PracticaPort {

		Practica save(Practica practica);

		Practica saveAndFlush(Practica practica);

		Optional<Practica> findVisible(Long id, Collection<Long> owners);

		/**
		 * Busqueda incremental de RF-M06-004: por nombre <b>o por codigo</b>, filtrable por
		 * especialidad.
		 *
		 * <p>Que el codigo entre en la busqueda no es un extra: en un mostrador se tipea el
		 * codigo, no el nombre completo, y obligar a dos campos distintos segun que se sepa es
		 * lo que hace que nadie use el buscador.
		 */
		List<Practica> buscar(
				Collection<Long> owners, String patron, int activoFiltro, long especialidadId);

		/**
		 * Cuantas practicas VIGENTES cuelgan de esa especialidad.
		 *
		 * <p>Es lo que impide dar de baja una especialidad y dejar practicas huerfanas. No mira
		 * el historico a proposito: RN-M06-001 pide que el historico sobreviva a la baja, no que
		 * la impida.
		 */
		long contarVigentesPorEspecialidad(Long especialidadId);
	}

	/** Nomencladores (RF-M06-003). */
	public interface NomencladorPort {

		Nomenclador save(Nomenclador nomenclador);

		Nomenclador saveAndFlush(Nomenclador nomenclador);

		Optional<Nomenclador> findVisible(Long id, Collection<Long> owners);

		/** Busqueda ordenada por nombre. Ver los centinelas en el javadoc de la clase. */
		List<Nomenclador> buscar(Collection<Long> owners, String patron, int activoFiltro);

		/**
		 * El mismo nomenclador, <b>tomando lock exclusivo sobre su fila</b>.
		 *
		 * <p>Es la PRIMERA sentencia de toda alta de vigencia, y el orden importa por partida
		 * doble:
		 *
		 * <ul>
		 *   <li>una lectura no bloqueante ANTES del lock fija el snapshot de la transaccion, y la
		 *       consulta de solapamientos posterior devuelve datos anteriores al commit del
		 *       competidor <b>aunque el lock ya se haya adquirido</b>. El lock serializa el
		 *       ACCESO, no la VISIBILIDAD;</li>
		 *   <li>un {@code INSERT} hijo toma lock compartido sobre el padre y lo escala a
		 *       exclusivo al commit: dos altas simetricas se abrazan. Tomando el exclusivo del
		 *       padre primero, el orden de bloqueo del modulo queda fijo en
		 *       {@code nomenclador -> nomenclador_item} y no hay ciclo posible.</li>
		 * </ul>
		 */
		Optional<Nomenclador> findVisibleForUpdate(Long id, Collection<Long> owners);
	}

	/** Vigencias de los codigos de un nomenclador (RF-M06-003, RN-M06-003). */
	public interface NomencladorItemPort {

		NomencladorItem save(NomencladorItem item);

		NomencladorItem saveAndFlush(NomencladorItem item);

		Optional<NomencladorItem> findVisible(Long id, Collection<Long> owners);

		/** Vigencias de un nomenclador, con los filtros de la pantalla de administracion. */
		List<NomencladorItem> listar(
				Long nomencladorId,
				Collection<Long> owners,
				int activoFiltro,
				long practicaId,
				String codigo);

		/**
		 * Todas las vigencias VIGENTES de un codigo, para decidir el solapamiento.
		 *
		 * <p>Se ejecuta SIEMPRE despues del lock sobre el nomenclador padre. Devuelve las filas
		 * completas y no un conteo porque el rechazo tiene que poder decir contra que ventana
		 * choco: un "ya existe" sin fechas es inaccionable.
		 */
		List<NomencladorItem> vigenciasDelCodigo(Long nomencladorId, String codigo);

		/**
		 * Que decia un codigo el dia D (RN-M06-003).
		 *
		 * <p>Resuelve por vigencia y <b>no</b> exige que el codigo siga vigente hoy: es la
		 * consulta que hace que un convenio de 2024 siga diciendo lo que decia en 2024.
		 */
		Optional<NomencladorItem> resolverEn(Long nomencladorId, String codigo, Instant at);

		/** Cuantas vigencias siguen abiertas en ese nomenclador. Bloquea su baja. */
		long contarVigentesPorNomenclador(Long nomencladorId);
	}

	/** Solicitudes de alta de concepto global (RF-M06-005). */
	public interface SolicitudPort {

		CatalogoSolicitud save(CatalogoSolicitud solicitud);

		CatalogoSolicitud saveAndFlush(CatalogoSolicitud solicitud);

		Optional<CatalogoSolicitud> findById(Long id);

		/** Solicitudes de un tenant. {@code estado} vacio significa todas. */
		List<CatalogoSolicitud> listarPorTenant(Long organizationId, String estado);

		/** Bandeja de la plataforma: las de todos los tenants. */
		List<CatalogoSolicitud> listarTodas(String estado);
	}
}
