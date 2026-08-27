package com.akine.offering.domain.port;

import com.akine.offering.domain.OfertaEspacioHabilitado;
import com.akine.offering.domain.OfertaProfesionalHabilitado;
import com.akine.offering.domain.OfertaServicioConsultorio;
import com.akine.offering.domain.Servicio;

import java.util.List;
import java.util.Optional;

/**
 * Los dos puertos de persistencia de {@code offering} (M27), en un solo archivo, mismo criterio
 * que {@code CatalogoRepositoryPorts} y {@code DisponibilidadRepositoryPorts}.
 *
 * <h2>Por que estan juntos</h2>
 *
 * <p>Comparten modulo y comparten migracion (V24), pero tienen <b>protocolos de aislamiento
 * opuestos</b>: uno no lleva tenant y el otro lo exige siempre. Tenerlos en el mismo archivo hace
 * evidente el contraste en vez de esconderlo en dos archivos que nadie compara.
 *
 * <h2>El contraste, en una tabla</h2>
 *
 * <pre>
 *   ServicioRepositoryPort   SIN organizationId. Global (ADR-0023). Ver su propio javadoc.
 *   OfertaRepositoryPort     CON organizationId SIEMPRE, y con consultorioId donde el alcance
 *                            es la sede. Ver su propio javadoc.
 * </pre>
 */
public final class OfferingRepositoryPorts {

	private OfferingRepositoryPorts() {
		// Contenedor de puertos.
	}

	/**
	 * Acceso al catalogo GLOBAL de Servicios (M27/M06).
	 *
	 * <h2>{@code organizationId} NO es un parametro de este puerto, y NO es un olvido</h2>
	 *
	 * <p>La tabla {@code servicio} es la quinta excepcion declarada a ADR-0004, consolidada junto
	 * a las cuatro anteriores en <b>ADR-0023</b>. RN-M27-001 lo dice sin lugar a dudas: "un
	 * Servicio es catalogo global/conceptual". No hay dos poblaciones que discriminar como en
	 * {@code especialidad} o {@code practica} (ADR-0021, {@code owner_key}): con una sola
	 * poblacion, un centinela no distinguiria nada. Es exactamente el razonamiento que
	 * {@code FeriadoRepositoryPort} ya declaro para {@code feriado} (ADR-0022) — <b>leer ese
	 * javadoc antes de tocar este</b>.
	 *
	 * <p><b>Si alguien le agrega {@code organizationId} a esta firma, a una consulta de
	 * {@code ServicioRepository} o al {@code WHERE} de una nativa</b>, es que entendio mal el
	 * alcance: la configuracion propia de cada centro no vive en {@code servicio}, vive en
	 * {@link OfertaServicioConsultorio} — esa es la regla maestra 14 y el objetivo entero de esta
	 * etapa. Duplicar el filtro contextual aca seria construir, sin necesidad, la segunda
	 * poblacion que el diseno de la etapa descarto explicitamente (docs/diseno,
	 * "Por que Servicio es puramente global").
	 *
	 * <h2>Para que lo usa la Tarea 5, sin inyectar {@code ServicioService}</h2>
	 *
	 * <p>{@code OfertaService} tiene que rechazar crear una Oferta sobre un Servicio dado de baja
	 * (RF-M27-002, {@code ServicioInactivoException}). Ese chequeo se resuelve leyendo este puerto
	 * directamente y preguntando {@link Servicio#isOperable()} sobre el resultado de
	 * {@link #findById(Long)} — <b>nunca</b> inyectando {@code ServicioService}: un servicio de
	 * aplicacion llamando a otro arrastra su autorizacion, su transaccion y su auditoria a un
	 * camino que no las pidio (ruling R1 de la Tarea 2).
	 */
	public interface ServicioRepositoryPort {

		Servicio save(Servicio servicio);

		/**
		 * Guarda y FUERZA el flush.
		 *
		 * <p>Las violaciones de {@code uk_servicio_codigo_vigente} y {@code uk_servicio_nombre_
		 * vigente} tienen que manifestarse dentro del bloque que sabe traducirlas a
		 * {@link com.akine.offering.domain.exception.ServicioCodigoTakenException} o
		 * {@link com.akine.offering.domain.exception.ServicioNombreTakenException}, y no al cerrar
		 * la transaccion, donde ya no hay a quien avisarle y el advice generico responde 500.
		 * Despues de un flush fallido no se vuelve a tocar la sesion JPA: ni una lectura, ni un
		 * {@code save}, ni la auditoria — mismo motivo que documenta
		 * {@code EspecialidadPort.saveAndFlush}.
		 */
		Servicio saveAndFlush(Servicio servicio);

		/**
		 * Un servicio por id, activo o no. Sin acotar por tenant: es global, lo ve cualquiera con
		 * sesion (contrato §5, {@code GET /servicios} solo exige autenticado).
		 */
		Optional<Servicio> findById(Long id);

		/**
		 * Busqueda del catalogo global, ordenada por nombre.
		 *
		 * <p>{@code patron} llega ya listo para {@code LIKE} (con sus {@code %} puestos por quien
		 * llama, igual que {@code CatalogoBusqueda.patron()} en {@code resource}) y se compara
		 * contra {@link Servicio#getNombre()} <b>o</b> {@link Servicio#getCodigo()}: en un
		 * selector se puede tipear cualquiera de los dos. {@code activoFiltro = -1} significa "no
		 * filtres por estado" — mismo centinela que {@code CatalogoRepositoryPorts}, documentado
		 * ahi con el motivo completo (evitar el "could not determine type" de un parametro nulo en
		 * consulta nativa).
		 */
		List<Servicio> buscar(String patron, int activoFiltro);
	}

	/**
	 * Acceso a las Ofertas de servicio por consultorio (M27/M03).
	 *
	 * <h2>{@code organizationId} SIEMPRE, y {@code consultorioId} donde el alcance es la sede</h2>
	 *
	 * <p>{@code oferta_servicio_consultorio} no es como {@code servicio}: no existe la oferta
	 * global, toda fila pertenece a una sede real y {@code organization_id} es {@code NOT NULL}
	 * (V24). <b>Toda consulta de esta interfaz filtra por {@code organizationId}, y las que
	 * ademas acotan a una sede filtran tambien por {@code consultorioId}.</b> Una fila de otro
	 * tenant no tiene que resolver nunca: el llamador (Tarea 5 / Tarea 6) responde 404 en ese
	 * caso, jamas 403 — un 403 confirma que la fila existe.
	 *
	 * <p><b>No hay FK compuesta {@code (organization_id, consultorio_id)}</b> sobre esta tabla —
	 * mismo caso que {@code espacio} y las tablas de disponibilidad de M05—, asi que a nivel de
	 * base una fila podria, en teoria, declarar la organizacion A y apuntar a un consultorio de la
	 * organizacion B. La garantia de aislamiento no la da el esquema: la da que <b>cada consulta
	 * de esta interfaz lleve las dos columnas en el {@code WHERE}</b>. Si se agrega una consulta
	 * nueva a este puerto, se verifica esto individualmente, no por analogia con las demas.
	 */
	public interface OfertaRepositoryPort {

		OfertaServicioConsultorio save(OfertaServicioConsultorio oferta);

		/**
		 * Guarda y FUERZA el flush.
		 *
		 * <p>La violacion de {@code uk_oferta_sede_nombre_vigente} tiene que manifestarse dentro
		 * del bloque que sabe traducirla a
		 * {@link com.akine.offering.domain.exception.OfertaNombreComercialTakenException}, y no al
		 * cerrar la transaccion. Mismo motivo que {@code ServicioRepositoryPort#saveAndFlush} y que
		 * {@code EspacioRepositoryPort#saveAndFlush}.
		 */
		OfertaServicioConsultorio saveAndFlush(OfertaServicioConsultorio oferta);

		/**
		 * Una oferta por id, acotada al tenant y a la sede, activa o no.
		 *
		 * <p>Las tres columnas del filtro son necesarias y ninguna es redundante: sin
		 * {@code organizationId} un id ajeno resuelve; sin {@code consultorioId} una oferta de
		 * otra sede del mismo tenant respondería a una ruta que no le corresponde. Mismo
		 * razonamiento que {@code EspacioRepositoryPort#findByIdAndOrganizationIdAndConsultorioId}.
		 */
		Optional<OfertaServicioConsultorio> findByIdAndOrganizationIdAndConsultorioId(
				Long id, Long organizationId, Long consultorioId);

		/** Todas las ofertas de la sede, activas e historicas, ordenadas por nombre comercial. */
		List<OfertaServicioConsultorio> findAllByOrganizationIdAndConsultorioIdOrderByNombreComercialAsc(
				Long organizationId, Long consultorioId);

		/** Solo las vigentes administrativamente. Base del selector que excluye inactivas. */
		List<OfertaServicioConsultorio>
				findAllByOrganizationIdAndConsultorioIdAndActiveOrderByNombreComercialAsc(
						Long organizationId, Long consultorioId, boolean active);

		/**
		 * Ofertas de esta sede que materializan un Servicio dado, activas e historicas.
		 *
		 * <p>La cubre {@code ix_oferta_sede_servicio}. Es la consulta que documenta la cabecera de
		 * V24 para "que ofertas de esta sede usan este servicio" — hoy sin llamador (no hay
		 * agenda todavia), pero la persistencia ya la expone porque la Tarea 5 la necesita para
		 * copiar los {@code *Default} del {@link Servicio} al crear.
		 *
		 * <p><b>Deliberadamente NO responde "a cuantos centros afecta la baja de un Servicio".</b>
		 * Esa es una pregunta cross-tenant — cuenta ofertas de TODAS las organizaciones — que solo
		 * el rol de plataforma puede hacer (diseno §7.8), y agregarla aca diluiria la garantia de
		 * esta interfaz de que toda consulta filtra por tenant. Si una etapa futura cablea esa
		 * pantalla, esa consulta va en un puerto propio y explicito, con su propio javadoc que
		 * declare por que no lleva {@code organizationId} — mismo criterio con el que este diseno
		 * ya decidio no declarar un {@code spi} de {@code offering} sin un llamador (diseno §4).
		 */
		List<OfertaServicioConsultorio>
				findAllByOrganizationIdAndConsultorioIdAndServicioIdOrderByNombreComercialAsc(
						Long organizationId, Long consultorioId, Long servicioId);
	}

	/**
	 * Habilitaciones de profesionales por Oferta (AKINE-02.07).
	 *
	 * <p>Las lecturas devuelven la lista COMPLETA de la oferta, activas e inactivas, y quien
	 * filtra es la capa de aplicacion. No es descuido: la pantalla necesita mostrar la fila dada
	 * de baja con su motivo —esconderla dejaria al administrador sin entender por que la
	 * capacidad efectiva cambio sola— y el mismo metodo sirve para el diff del PUT.
	 */
	public interface OfertaProfesionalHabilitadoRepositoryPort {

		OfertaProfesionalHabilitado save(OfertaProfesionalHabilitado habilitacion);

		List<OfertaProfesionalHabilitado> findAllByOrganizationIdAndOfertaId(
				Long organizationId, Long ofertaId);

		List<OfertaProfesionalHabilitado> findAllByOrganizationIdAndOfertaIdAndActive(
				Long organizationId, Long ofertaId, boolean active);

		/** Cuantas habilitaciones vigentes deja colgando desvincular a este profesional. */
		long countByOrganizationIdAndMembershipIdAndActive(
				Long organizationId, Long membershipId, boolean active);
	}

	/** Habilitaciones de espacios por Oferta (AKINE-02.07). Mismo criterio de lectura. */
	public interface OfertaEspacioHabilitadoRepositoryPort {

		OfertaEspacioHabilitado save(OfertaEspacioHabilitado habilitacion);

		List<OfertaEspacioHabilitado> findAllByOrganizationIdAndOfertaId(
				Long organizationId, Long ofertaId);

		List<OfertaEspacioHabilitado> findAllByOrganizationIdAndOfertaIdAndActive(
				Long organizationId, Long ofertaId, boolean active);
	}
}
