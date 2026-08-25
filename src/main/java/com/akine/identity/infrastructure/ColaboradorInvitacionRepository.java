package com.akine.identity.infrastructure;

import com.akine.identity.domain.ColaboradorInvitacion;
import com.akine.identity.domain.EstadoInvitacion;
import com.akine.identity.domain.port.ColaboradorInvitacionRepositoryPort;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Adaptador Spring Data del puerto de invitaciones.
 *
 * <p>Extiende {@code Repository} pelado y no {@code JpaRepository}: la interfaz de dominio
 * declara exactamente lo que el servicio necesita, y heredar los cuarenta metodos de
 * {@code JpaRepository} pondria a disposicion consultas sin {@code organizationId} que nadie
 * pidio. Misma decision que el resto de los repositorios del modulo.
 */
public interface ColaboradorInvitacionRepository
		extends Repository<ColaboradorInvitacion, Long>, ColaboradorInvitacionRepositoryPort {

	@Override
	Optional<ColaboradorInvitacion> findByTokenHash(String tokenHash);

	@Override
	Optional<ColaboradorInvitacion> findByIdAndOrganizationId(Long id, Long organizationId);

	@Override
	Optional<ColaboradorInvitacion> findByOrganizationIdAndConsultorioIdAndEmailNormalizadoAndEstado(
			Long organizationId, Long consultorioId, String emailNormalizado, EstadoInvitacion estado);

	@Override
	Optional<ColaboradorInvitacion> findByOrganizationIdAndConsultorioIdIsNullAndEmailNormalizadoAndEstado(
			Long organizationId, String emailNormalizado, EstadoInvitacion estado);

	@Override
	List<ColaboradorInvitacion> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);

	@Override
	List<ColaboradorInvitacion> findByOrganizationIdAndEstadoOrderByCreatedAtDesc(
			Long organizationId, EstadoInvitacion estado);

	@Override
	ColaboradorInvitacion save(ColaboradorInvitacion invitacion);

	@Override
	ColaboradorInvitacion saveAndFlush(ColaboradorInvitacion invitacion);
}
