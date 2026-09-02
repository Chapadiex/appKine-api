package com.akine.contracting.domain.port;

import com.akine.contracting.domain.Financiador;
import com.akine.contracting.domain.PlanCobertura;

import java.util.List;
import java.util.Optional;

/**
 * Los dos puertos de persistencia de {@code contracting} (M15), en un solo archivo, mismo
 * criterio que {@code OfferingRepositoryPorts} y {@code CatalogoRepositoryPorts}.
 *
 * <h2>La invariante que comparten, y que hay que verificar consulta por consulta</h2>
 *
 * <p><b>Toda firma de las dos interfaces lleva {@code organizationId}</b>, sin ninguna excepcion
 * y a diferencia de {@code ServicioRepositoryPort}, que no lo lleva porque el catalogo de
 * servicios es global (ADR-0023). Aca no hay poblacion global: un financiador es dato de una
 * organizacion (V41), y una fila de otro tenant no tiene que resolver nunca — el llamador
 * responde 404, jamas 403.
 *
 * <p>Las consultas de planes ademas llevan {@code financiadorId} donde el alcance es el
 * financiador. <b>No hay FK compuesta {@code (organization_id, financiador_id)}</b>, asi que a
 * nivel de base una fila podria en teoria declarar la organizacion A y apuntar a un financiador
 * de la B. La garantia de aislamiento no la da el esquema: la da que cada consulta lleve las
 * columnas en el {@code WHERE}. Si se agrega una consulta nueva, se verifica esto
 * individualmente y no por analogia con las demas.
 */
public final class ContractingRepositoryPorts {

	private ContractingRepositoryPorts() {
		// Contenedor de puertos.
	}

	/** Acceso a los financiadores de una organizacion (M15). */
	public interface FinanciadorRepositoryPort {

		Financiador save(Financiador financiador);

		/**
		 * Guarda y FUERZA el flush.
		 *
		 * <p>Las violaciones de {@code uk_financiador_codigo_vigente},
		 * {@code uk_financiador_nombre_vigente} y {@code uk_financiador_cuit_vigente} tienen que
		 * manifestarse dentro del bloque que sabe traducirlas a su 409, y no al cerrar la
		 * transaccion, donde ya no hay a quien avisarle y el advice generico responde 500.
		 *
		 * <p><b>Despues de un flush fallido no se vuelve a tocar la sesion JPA</b>: ni una lectura
		 * para averiguar cual de los tres uniques choco, ni un {@code save}, ni la auditoria. La
		 * especificacion lo prohibe y lo que sale de ahi es un 500 en vez del 409 legitimo. Misma
		 * trampa que documenta {@code ServicioRepositoryPort#saveAndFlush}.
		 */
		Financiador saveAndFlush(Financiador financiador);

		/** Un financiador del tenant, activo o no. {@code empty} si es de otra organizacion. */
		Optional<Financiador> findByIdAndOrganizationId(Long id, Long organizationId);

		/**
		 * Busqueda del catalogo de la organizacion, ordenada por nombre (RF-M15-006).
		 *
		 * <p>{@code patron} llega listo para {@code LIKE}, con los comodines puestos y los
		 * metacaracteres del usuario escapados por quien llama. Se compara contra el nombre, el
		 * codigo <b>y</b> el CUIT: en el mostrador se busca por cualquiera de los tres.
		 *
		 * <p>{@code activoFiltro = -1} significa "no filtres por estado" — mismo centinela que
		 * {@code CatalogoRepositoryPorts} y {@code ServicioRepositoryPort}, y por el mismo motivo:
		 * un parametro nulo en una consulta nativa dispara el "could not determine type" de
		 * Hibernate. {@code tipo} viaja como {@code String} nulable porque el {@code IS NULL} de
		 * un {@code VARCHAR} si lo resuelve Hibernate sin ambiguedad.
		 */
		List<Financiador> buscar(Long organizationId, String patron, int activoFiltro, String tipo);
	}

	/** Acceso a los planes de cobertura (M15). Alcance: organizacion y financiador. */
	public interface PlanCoberturaRepositoryPort {

		PlanCobertura save(PlanCobertura plan);

		/** Guarda y FUERZA el flush. Mismo motivo, y misma trampa, que el puerto de financiador. */
		PlanCobertura saveAndFlush(PlanCobertura plan);

		/**
		 * Un plan del tenant, activo o no, <b>sin</b> acotar por financiador.
		 *
		 * <p>Existe solo para el {@code spi}: un consumidor de M08 tiene un {@code planId} guardado
		 * y no tiene por que conocer a que financiador pertenece — justamente esa es la pregunta
		 * que viene a hacer. Los caminos HTTP <b>no</b> usan esta consulta: usan
		 * {@link #findByIdAndOrganizationIdAndFinanciadorId}, porque su ruta declara el financiador
		 * y un plan de otro financiador respondiendo bajo esa ruta seria una respuesta que miente.
		 */
		Optional<PlanCobertura> findByIdAndOrganizationId(Long id, Long organizationId);

		/** Un plan de ESE financiador. La consulta de los caminos HTTP. */
		Optional<PlanCobertura> findByIdAndOrganizationIdAndFinanciadorId(
				Long id, Long organizationId, Long financiadorId);

		/**
		 * Los planes de un financiador, activos e historicos, ordenados por nombre.
		 *
		 * <p>Devuelve tambien los dados de baja y los de vigencia vencida: quien filtra es la capa
		 * de aplicacion. La pantalla necesita mostrarlos —esconder un plan vencido dejaria al
		 * administrador sin entender por que una cobertura vieja apunta a algo que no ve— y el
		 * mismo metodo sirve para el selector, que si filtra.
		 */
		List<PlanCobertura> findAllByOrganizationIdAndFinanciadorIdOrderByNombreAsc(
				Long organizationId, Long financiadorId);

		/**
		 * Cuantos planes VIGENTES administrativamente cuelgan del financiador.
		 *
		 * <p>Es la advertencia de "baja con convenios" del caso borde de la etapa: la baja de un
		 * financiador no se bloquea por tener planes —no cascadea y no impide— pero el numero se
		 * registra en la auditoria, para que seis meses despues se sepa cuanto arrastraba esa baja.
		 */
		long countByOrganizationIdAndFinanciadorIdAndActive(
				Long organizationId, Long financiadorId, boolean active);
	}
}
