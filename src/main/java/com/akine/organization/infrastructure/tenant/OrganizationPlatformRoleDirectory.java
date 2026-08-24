package com.akine.organization.infrastructure.tenant;

import com.akine.organization.domain.PlatformRole;
import com.akine.organization.domain.port.PlatformRoleRepositoryPort;
import com.akine.platform.spi.tenant.PlatformRoleDirectory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Implementacion del puerto {@link PlatformRoleDirectory} de {@code platform}.
 *
 * <p><b>La flecha va en un solo sentido</b>, exactamente igual que en
 * {@link OrganizationMembershipDirectory}: {@code organization} depende de
 * {@code platform.spi}; {@code platform} recibe la interfaz por inyeccion y no conoce esta
 * clase. Ese es el unico motivo por el que {@code sin_ciclos_entre_modulos} no detecta un ciclo,
 * y por eso este adaptador devuelve un {@code boolean} y nunca la entity.
 *
 * <p><b>Sin cache</b> (ADR-0020): se consulta una vez por request autenticado. La ventana de
 * revocacion del permiso mas alto del sistema tiene que ser cero — si alguien compromete una
 * cuenta de plataforma, revocarle el rol tiene que surtir efecto en el request siguiente y no
 * cuando expire un TTL. El costo es un seek por {@code ix_platform_role_account}.
 *
 * <p>La vigencia se evalua en Java con {@link PlatformRole#isValidAt(Instant)} y no en el
 * {@code WHERE}, por el mismo motivo que en las memberships: la regla vive en un solo lugar, se
 * testea sin base y no depende del reloj del motor, que puede no ser el del backend.
 */
@Component
public class OrganizationPlatformRoleDirectory implements PlatformRoleDirectory {

	private final PlatformRoleRepositoryPort platformRoleRepository;

	public OrganizationPlatformRoleDirectory(PlatformRoleRepositoryPort platformRoleRepository) {
		this.platformRoleRepository = platformRoleRepository;
	}

	@Override
	@Transactional(readOnly = true)
	public boolean isPlatformAdmin(long accountId, Instant at) {
		return platformRoleRepository.findAllByAccountIdAndActiveTrue(accountId).stream()
				.anyMatch(rol -> rol.isValidAt(at));
	}
}
