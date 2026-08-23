package com.akine.organization.application;

import com.akine.organization.domain.port.ConsultorioRepositoryPort;
import com.akine.organization.domain.port.MembershipRepositoryPort;
import com.akine.organization.spi.LimitCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cuenta los recursos activos de un tenant para cada limite del catalogo.
 *
 * <p>Los dos limites de F1 —{@code MAX_CONSULTORIOS} y {@code MAX_MIEMBROS_ACTIVOS}— son
 * tablas del propio modulo {@code organization}, asi que el conteo puede vivir aca sin violar
 * ownership. Cuando un limite futuro cuente una tabla de OTRO modulo, el conteo lo aportara
 * ese modulo como {@code LongSupplier} al invocar el gate: {@code organization} decide si el
 * alta entra, pero nunca cuenta una tabla ajena.
 *
 * <p>Cuenta solo filas ACTIVAS: dar de baja un consultorio o una membership libera cupo, lo
 * que es coherente con RN-M01-004 porque no invalida nada de lo que ocurrio ahi.
 *
 * <p><b>Donde se llama importa.</b> Invocado desde {@code PlanGateService} corre dentro de la
 * transaccion del alta y despues del bloqueo de la suscripcion, y ahi el numero DECIDE.
 * Invocado desde una lectura —mostrar la suscripcion con su consumo, avisar de los limites
 * excedidos al bajar de plan— el numero es informativo y puede quedar viejo un instante
 * despues, lo cual es aceptable para mostrar pero jamas para decidir.
 */
@Service
public class TenantUsageCounter {

	private final ConsultorioRepositoryPort consultorioRepository;
	private final MembershipRepositoryPort membershipRepository;

	public TenantUsageCounter(
			ConsultorioRepositoryPort consultorioRepository,
			MembershipRepositoryPort membershipRepository) {
		this.consultorioRepository = consultorioRepository;
		this.membershipRepository = membershipRepository;
	}

	/**
	 * Recursos activos del tenant para ese limite.
	 *
	 * <p>Sin {@code default} en el switch a proposito: agregar un valor a {@link LimitCode}
	 * sin decidir como se cuenta tiene que romper la compilacion, no devolver cero en
	 * silencio. Un cero silencioso convierte el limite en "siempre permite".
	 */
	@Transactional(readOnly = true)
	public long count(LimitCode limit, long organizationId) {
		return switch (limit) {
			case MAX_CONSULTORIOS ->
					consultorioRepository.countByOrganizationIdAndActiveTrue(organizationId);
			case MAX_MIEMBROS_ACTIVOS ->
					membershipRepository.countByOrganizationIdAndActiveTrue(organizationId);
		};
	}
}
