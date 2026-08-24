package com.akine.organization.domain.port;

import com.akine.organization.domain.Consultorio;

import java.util.List;
import java.util.Optional;

/**
 * Acceso a los consultorios. Toda consulta filtra por {@code organizationId}: un id de sede de
 * otro tenant no debe resolver nunca.
 */
public interface ConsultorioRepositoryPort {

	Consultorio save(Consultorio consultorio);

	/**
	 * Guarda y FUERZA el flush.
	 *
	 * <p>Existe para el alta: la violacion de {@code uk_consultorio_org_name_vigente} tiene que
	 * manifestarse dentro del bloque que sabe traducirla a un 409, y no al cerrar la
	 * transaccion, donde ya no hay a quien avisarle y el advice generico responde 500.
	 *
	 * <p><b>Despues de un flush fallido no se vuelve a tocar la sesion JPA.</b> Ni una lectura,
	 * ni un {@code save}, ni la auditoria: la especificacion de JPA prohibe seguir usando un
	 * {@code EntityManager} tras un flush que fallo, y lo que sale de ahi es un
	 * {@code AssertionFailure} o un "Transaction marked as rollbackOnly". Ya costo un 500 en
	 * 01.02; el razonamiento completo esta en {@code OnboardingService}.
	 */
	Consultorio saveAndFlush(Consultorio consultorio);

	/**
	 * Busca por id DENTRO del tenant.
	 *
	 * <p>Nunca se resuelve una sede del contexto con {@code findById} pelado: eso permitiria
	 * que un id ajeno inyectado en la URL devuelva una fila de otra organizacion.
	 */
	Optional<Consultorio> findByIdAndOrganizationIdAndActiveTrue(Long id, Long organizationId);

	/**
	 * Busca por id DENTRO del tenant, <b>sin filtrar por estado</b>.
	 *
	 * <p>Es la lectura que hace posible RF-M03-004: una sede dada de baja sigue siendo legible
	 * con toda su historia. Devolverle 404 seria borrar informacion historica por la puerta de
	 * atras, que es justamente lo que la regla maestra 10 prohibe.
	 *
	 * <p>Conserva la forma {@code (id, organizationId)}: relajar el filtro de estado no relaja
	 * el de tenant.
	 */
	Optional<Consultorio> findByIdAndOrganizationId(Long id, Long organizationId);

	List<Consultorio> findAllByOrganizationIdAndActiveTrue(Long organizationId);

	/** Todas las sedes del tenant, activas e historicas. Base del listado con filtro de estado. */
	List<Consultorio> findAllByOrganizationId(Long organizationId);

	/**
	 * Conteo de uso para {@code MAX_CONSULTORIOS}.
	 *
	 * <p>Solo filas ACTIVAS: dar de baja una sede libera cupo, coherente con RN-M01-004 porque
	 * no invalida nada de lo que ocurrio ahi.
	 *
	 * <p>Cuando decide un alta, se ejecuta DENTRO de la transaccion y DESPUES de bloquear la
	 * suscripcion. Contarlo antes convierte el limite en una sugerencia.
	 */
	long countByOrganizationIdAndActiveTrue(Long organizationId);

	/**
	 * Cuenta las sedes ACTIVAS del tenant excluyendo una, <b>tomando lock compartido sobre las
	 * filas contadas</b>.
	 *
	 * <p><b>El {@code FOR SHARE} no es decorativo y quitarlo reintroduce un bug conocido.</b> Es
	 * el conteo que decide el invariante "no se puede dar de baja la ultima sede activa" (D-7),
	 * y tiene exactamente la forma del "ultimo admin" de 01.03. En {@code REPEATABLE READ} —el
	 * default de MySQL— una lectura consistente previa fija el snapshot de la transaccion, y un
	 * {@code COUNT(*)} comun devuelve datos anteriores al commit del competidor <b>aunque el
	 * bloqueo del tenant ya se haya adquirido</b>: el lock serializa el ACCESO, no la
	 * VISIBILIDAD. Las dos bajas simetricas contarian una sede cada una, las dos pasarian, y el
	 * tenant quedaria sin ninguna. Una lectura con lock siempre lee la ultima version
	 * confirmada.
	 *
	 * <p><b>Por que excluye la fila objetivo.</b> Para que el lock compartido nunca caiga sobre
	 * la fila que la sentencia siguiente va a actualizar: si cayera, al commit habria que
	 * escalar S -&gt; X sobre una fila que otra transaccion tiene en compartido, que es el
	 * segundo bug de concurrencia de {@code src/test/java/com/akine/diferidos}, con las mismas
	 * transacciones simetricas.
	 *
	 * @param excludedConsultorioId sede que la operacion esta por dar de baja. Usar un valor
	 *                              imposible (-1) cuando no hay ninguna que excluir
	 */
	long countActiveForShare(Long organizationId, Long excludedConsultorioId);
}
