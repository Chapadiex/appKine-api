package com.akine.organization.infrastructure;

import com.akine.organization.domain.MembershipGrant;
import com.akine.organization.domain.port.MembershipGrantRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Acceso a los permisos adicionales por membership.
 *
 * <p>Toda consulta parte de {@code membershipId}, que ya esta acotado al tenant por la fila de
 * {@code membership}: no hay forma de que una lectura de aca cruce organizaciones.
 *
 * <p>La vigencia temporal no se filtra en SQL —se evalua con
 * {@code MembershipGrant.isValidAt(Instant)}—, por el mismo motivo que en
 * {@code MembershipRepository}: la regla vive en un solo lugar y no depende del reloj del motor.
 */
public interface MembershipGrantRepository
		extends JpaRepository<MembershipGrant, Long>, MembershipGrantRepositoryPort {
}
