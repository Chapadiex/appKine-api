package com.akine.organization.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica las marcas temporales comunes de las entidades mutables.
 *
 * <p>Los callbacks existen para que ningun camino de escritura pueda olvidarse de cargarlas:
 * un olvido en uno solo deja filas sin fecha de modificacion y arruina la trazabilidad justo
 * cuando hace falta. Se prueban sobre {@link Organization}, que es una de las entidades que
 * hereda de {@link TimestampedEntity}.
 */
class TimestampedEntityTest {

	private Organization nuevaEntidad() {
		return new Organization("Centro", "centro", "America/Argentina/Buenos_Aires");
	}

	@Test
	@DisplayName("Antes de persistir, las marcas estan vacias")
	void antes_de_persistir_estan_vacias() {
		Organization entidad = nuevaEntidad();

		assertThat(entidad.getCreatedAt()).isNull();
		assertThat(entidad.getUpdatedAt()).isNull();
	}

	@Test
	@DisplayName("Al insertar se cargan las dos marcas")
	void al_insertar_se_cargan_las_dos() {
		Organization entidad = nuevaEntidad();
		Instant antes = Instant.now();

		entidad.alInsertar();

		// Las dos columnas son NOT NULL: si el callback dejara una sin cargar, el insert
		// fallaria entero y recien se veria en el primer alta real.
		assertThat(entidad.getCreatedAt()).isNotNull().isAfterOrEqualTo(antes);
		assertThat(entidad.getUpdatedAt()).isNotNull().isAfterOrEqualTo(antes);
	}

	@Test
	@DisplayName("Al actualizar cambia updated_at y created_at queda intacto")
	void al_actualizar_solo_cambia_updated_at() throws InterruptedException {
		Organization entidad = nuevaEntidad();
		entidad.alInsertar();
		Instant creacion = entidad.getCreatedAt();

		Thread.sleep(5);
		entidad.alActualizar();

		// Pisar created_at en cada update borraria la fecha de alta de la organizacion, que
		// es dato de auditoria y no se puede reconstruir de ningun lado.
		assertThat(entidad.getCreatedAt()).isEqualTo(creacion);
		assertThat(entidad.getUpdatedAt()).isAfter(creacion);
	}

	@Test
	@DisplayName("Un created_at ya cargado no se pisa al insertar")
	void un_created_at_ya_cargado_no_se_pisa() throws InterruptedException {
		Organization entidad = nuevaEntidad();
		entidad.alInsertar();
		Instant creacion = entidad.getCreatedAt();

		Thread.sleep(5);
		entidad.alInsertar();

		// Importa en la migracion de datos historicos: si la fila trae su fecha original, el
		// callback no puede reemplazarla por la del import.
		assertThat(entidad.getCreatedAt()).isEqualTo(creacion);
		assertThat(entidad.getUpdatedAt()).isAfter(creacion);
	}

	@Test
	@DisplayName("Las entidades append-only no heredan las marcas")
	void las_append_only_no_heredan_las_marcas() {
		// Un updated_at en una fila que por definicion no se actualiza seria una invitacion a
		// hacerlo. El historico y el registro de idempotencia solo llevan created_at propio.
		assertThat(TimestampedEntity.class.isAssignableFrom(SubscriptionTransition.class))
				.isFalse();
		assertThat(TimestampedEntity.class.isAssignableFrom(OrganizationOnboarding.class))
				.isFalse();
		assertThat(TimestampedEntity.class.isAssignableFrom(AccountActiveContext.class))
				.isFalse();
	}

	@Test
	@DisplayName("Las entidades mutables si heredan las marcas")
	void las_mutables_heredan_las_marcas() {
		assertThat(TimestampedEntity.class)
				.isAssignableFrom(Organization.class)
				.isAssignableFrom(Consultorio.class)
				.isAssignableFrom(Membership.class)
				.isAssignableFrom(Subscription.class)
				.isAssignableFrom(Plan.class)
				.isAssignableFrom(PlanLimit.class)
				.isAssignableFrom(PlanFeature.class);
	}
}
