package com.akine.organization.infrastructure;

import com.akine.organization.domain.port.PlatformRoleRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Avisa, al arrancar, si no hay ningun administrador de plataforma.
 *
 * <h2>Por que existe</h2>
 *
 * <p>Sin ninguna fila activa en {@code platform_role}, el alta administrativa de tenants
 * —{@code POST /organizations}, las transiciones de suscripcion y los cambios de plan— queda
 * inoperante. Es un estado perfectamente valido en desarrollo y catastrofico en un despliegue
 * productivo, y hasta AKINE-01.03 <b>no lo avisaba nadie</b>: el sistema arrancaba, respondia
 * health check y esos tres endpoints simplemente no los podia ejecutar ninguna persona.
 *
 * <h2>Por que avisa y no falla</h2>
 *
 * <p>Un entorno de desarrollo sin administrador de plataforma es legitimo, y un test de
 * integracion que levanta el contexto sobre una base recien creada tambien. Hacer fallar el
 * arranque convertiria una advertencia operativa en un bloqueo de desarrollo, y la reaccion
 * previsible seria agregarle un flag para apagarlo — que es como se apagan las advertencias que
 * importan.
 *
 * <p>El seed de la migracion V15 hace que el caso normal sea "hay al menos uno". Este chequeo
 * cubre lo que el seed no puede: una base restaurada a medias, o alguien que revoco el ultimo
 * rol de plataforma sin darse cuenta de lo que dejaba apagado.
 */
@Component
public class PlatformAdminBootstrapCheck implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(PlatformAdminBootstrapCheck.class);

	private final PlatformRoleRepositoryPort platformRoleRepository;

	public PlatformAdminBootstrapCheck(PlatformRoleRepositoryPort platformRoleRepository) {
		this.platformRoleRepository = platformRoleRepository;
	}

	@Override
	public void run(ApplicationArguments args) {
		long activos = platformRoleRepository.countByActiveTrue();
		if (activos == 0) {
			log.warn("No hay ningun PLATFORM_ADMIN activo: el alta administrativa de tenants "
					+ "esta inoperante. Ningun actor puede ejecutar POST /organizations, las "
					+ "transiciones de suscripcion ni los cambios de plan. Revisar el seed de "
					+ "bootstrap (migracion V15) y la tabla platform_role.");
			return;
		}
		log.info("Administradores de plataforma activos: {}", activos);
	}
}
