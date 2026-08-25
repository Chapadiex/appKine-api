package com.akine.identity.domain.port;

import com.akine.identity.domain.ColaboradorInvitacion;
import com.akine.identity.domain.EstadoInvitacion;

import java.util.List;
import java.util.Optional;

/**
 * Persistencia de las invitaciones a colaborar (M05, AKINE-02.03).
 *
 * <p><b>Toda consulta que exponga una invitacion lleva {@code organizationId}</b>, salvo la
 * busqueda por token: esa no lo lleva porque el token ES el alcance —quien lo tiene demostro
 * que llega al buzon del invitado— y porque el invitado todavia no pertenece a ningun tenant,
 * asi que no hay contexto del que sacarlo.
 */
public interface ColaboradorInvitacionRepositoryPort {

	/**
	 * Busca por el hash del token. Camino publico: sin tenant, por lo dicho arriba.
	 *
	 * <p>Recibe el hash y nunca el token en claro: quien llama hashea primero.
	 */
	Optional<ColaboradorInvitacion> findByTokenHash(String tokenHash);

	/** Una invitacion del tenant, por id. El {@code organizationId} es el aislamiento. */
	Optional<ColaboradorInvitacion> findByIdAndOrganizationId(Long id, Long organizationId);

	/**
	 * La invitacion PENDIENTE de ese email en ese alcance, si la hay.
	 *
	 * <p>{@code consultorioId} nulo significa alcance ORGANIZACION, y por eso la consulta usa
	 * {@code IsNull} en su propio metodo: en JPQL un {@code = :param} con {@code null} no
	 * matchea nada, asi que una sola firma nulable devolveria siempre vacio para el alcance
	 * organizacion y dejaria pasar el duplicado que este metodo existe para detectar.
	 */
	Optional<ColaboradorInvitacion> findByOrganizationIdAndConsultorioIdAndEmailNormalizadoAndEstado(
			Long organizationId, Long consultorioId, String emailNormalizado, EstadoInvitacion estado);

	/** La misma consulta para el alcance ORGANIZACION. Ver el metodo de arriba. */
	Optional<ColaboradorInvitacion> findByOrganizationIdAndConsultorioIdIsNullAndEmailNormalizadoAndEstado(
			Long organizationId, String emailNormalizado, EstadoInvitacion estado);

	/** Invitaciones del tenant, de la mas nueva a la mas vieja. */
	List<ColaboradorInvitacion> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);

	/** Invitaciones del tenant en ese estado, de la mas nueva a la mas vieja. */
	List<ColaboradorInvitacion> findByOrganizationIdAndEstadoOrderByCreatedAtDesc(
			Long organizationId, EstadoInvitacion estado);

	ColaboradorInvitacion save(ColaboradorInvitacion invitacion);

	/**
	 * Guarda forzando el flush.
	 *
	 * <p>Existe para que la violacion del unique de invitacion pendiente aparezca <b>aca</b> y
	 * no al commit, donde el {@code catch} ya no la ve y el advice generico devuelve 500. Misma
	 * razon que el {@code saveAndFlush} de {@code MembershipService.createDirect}.
	 */
	ColaboradorInvitacion saveAndFlush(ColaboradorInvitacion invitacion);
}
