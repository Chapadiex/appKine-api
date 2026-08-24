package com.akine.organization.domain.port;

import com.akine.organization.domain.Membership;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Acceso a las memberships. Es el puerto mas caliente del sistema: la resolucion de contexto
 * lo consulta en CADA request, sin cache, porque cachear la vigencia abriria una ventana en la
 * que un acceso revocado sigue funcionando.
 *
 * <p>La vigencia temporal ({@code valid_from} / {@code valid_until}) NO se filtra aca: se
 * evalua con {@code Membership.isValidAt(Instant)}. Asi la regla vive en un solo lugar, se
 * testea sin base y no depende del reloj del motor.
 */
public interface MembershipRepositoryPort {

	Membership save(Membership membership);

	/**
	 * Memberships activas de una cuenta, en cualquier organizacion.
	 *
	 * <p>Es la unica consulta cross-tenant del modulo, y lo es por definicion: responde a que
	 * contextos puede entrar esta persona, pregunta que no ocurre dentro de un tenant.
	 */
	List<Membership> findAllByAccountIdAndActiveTrue(Long accountId);

	/**
	 * Memberships activas de una cuenta en un tenant concreto, de la mas vieja a la mas nueva.
	 *
	 * <p><b>Devuelve una lista y no un {@code Optional}, y eso no es una comodidad.</b> Hasta
	 * la migracion V10, {@code uk_membership_org_account} permitia a lo sumo una fila por
	 * (organizacion, cuenta) —lo que contradice RN-M02-002, que exige que la misma persona
	 * pueda tener roles distintos en consultorios distintos—. Con el unique expandido, un
	 * {@code Optional} se volveria {@code IncorrectResultSizeDataAccessException} en cuanto
	 * apareciera la segunda membership, es decir un 500 en la resolucion de contexto, que corre
	 * en CADA request.
	 *
	 * <p>Cual de las memberships aplica NO lo decide este puerto: depende de la pregunta que se
	 * este haciendo. Los criterios estan en {@code MembershipSelection} y cada llamador
	 * documenta cual usa.
	 */
	List<Membership> findAllByOrganizationIdAndAccountIdAndActiveTrueOrderByIdAsc(
			Long organizationId, Long accountId);

	/** Conteo de uso para {@code MAX_MIEMBROS_ACTIVOS}. Mismas condiciones que el de sedes. */
	long countByOrganizationIdAndActiveTrue(Long organizationId);

	/**
	 * Persiste y sincroniza con la base de inmediato.
	 *
	 * <p>Existe para que una violacion de {@code uk_membership_org_account_scope} sea un 409 y
	 * no un 500: sin el flush explicito la clave duplicada aparece al commit, fuera del alcance
	 * del {@code catch} del servicio. Despues de un flush fallido <b>no se vuelve a tocar la
	 * sesion JPA</b>: se traduce la excepcion y se deja propagar.
	 */
	Membership saveAndFlush(Membership membership);

	/**
	 * Una membership por id, acotada al tenant.
	 *
	 * <p><b>Se busca por (id, organizacion) y nunca por id pelado.</b> Un id de otro tenant
	 * simplemente no resuelve, y el llamador responde 404 sin poder confundirse: si la consulta
	 * fuera por id y el filtro por tenant quedara en Java, alcanzaria con que alguien se
	 * olvidara de una linea para tener una fuga cross-tenant.
	 *
	 * <p>Incluye las memberships dadas de baja: quien administra tiene que poder ver el vinculo
	 * revocado y quien lo revoco. La vigencia la decide el llamador.
	 */
	Optional<Membership> findByIdAndOrganizationId(Long id, Long organizationId);

	/** Memberships activas de un tenant. Base del listado de colaboradores. */
	List<Membership> findAllByOrganizationIdAndActiveTrue(Long organizationId);

	/** Memberships de un tenant, activas e historicas, para el listado administrativo paginado. */
	Page<Membership> findAllByOrganizationId(Long organizationId, Pageable pageable);

	/**
	 * Cuenta los {@code ORG_ADMIN} VIGENTES del tenant, excluyendo una membership, tomando
	 * lock compartido sobre las filas contadas.
	 *
	 * <p><b>El {@code FOR SHARE} no es decorativo y quitarlo reintroduce un bug conocido.</b> En
	 * {@code REPEATABLE READ} —el default de MySQL— una lectura consistente previa fija el
	 * snapshot de la transaccion, y un {@code COUNT(*)} comun posterior devuelve datos anteriores
	 * al commit del competidor <b>aunque el bloqueo del tenant ya se haya adquirido</b>: el lock
	 * serializa el ACCESO, no la VISIBILIDAD. Las dos revocaciones simetricas cuentan uno cada
	 * una, las dos pasan, y la organizacion queda sin ningun administrador. Una lectura con lock
	 * siempre lee la ultima version confirmada.
	 *
	 * <p><b>Por que excluye la fila objetivo.</b> Para que el lock compartido nunca caiga sobre
	 * la fila que la sentencia siguiente va a actualizar: si cayera, al commit habria que
	 * escalar S -&gt; X sobre una fila que otra transaccion tiene en compartido, que es el
	 * segundo bug de concurrencia de 01.01/01.02, con las mismas transacciones simetricas.
	 *
	 * <p>La vigencia se evalua en SQL <b>solo aca</b>, y a proposito: es el unico caso en que la
	 * decision es un conteo y no se puede traer las filas a Java sin perder el lock.
	 *
	 * @param excludedMembershipId membership que la operacion esta por modificar. Usar un valor
	 *                             imposible (-1) cuando no hay ninguna que excluir
	 */
	long countActiveOrgAdminsForShare(
			Long organizationId, Instant at, Long excludedMembershipId);
}
