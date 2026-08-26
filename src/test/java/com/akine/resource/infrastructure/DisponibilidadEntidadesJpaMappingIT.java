package com.akine.resource.infrastructure;

import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.EntityType;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.akine.TestcontainersConfiguration;
import com.akine.resource.domain.BloqueDisponibilidad;
import com.akine.resource.domain.CalendarioSede;
import com.akine.resource.domain.DisponibilidadExcepcion;
import com.akine.resource.domain.Feriado;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Las cuatro entidades JPA de disponibilidad profesional (AKINE-02.04, tarea 4) contra el
 * esquema real de V22/V23, con {@code ddl-auto: validate} (application.yml).
 *
 * <h2>Por que este test y no uno de repositorio</h2>
 *
 * <p>La tarea 4 no crea repositorios ni servicios: solo el mapeo. No hay ningun
 * {@code JpaRepository} para inyectar, asi que no hay una forma "natural" de forzar el arranque
 * completo del contexto de persistencia salvo levantando el contexto de Spring entero, que es
 * exactamente lo que hace {@code @SpringBootTest}.
 *
 * <h2>Por que esto es LA prueba, y no un detalle</h2>
 *
 * <p>Ninguna de las columnas de {@code profesional_disponibilidad} es tan atipica en este
 * esquema como {@code dia_semana TINYINT} (sin el {@code (1)} que todo otro TINYINT del
 * proyecto usa como booleano), y se mapeo a {@code int}. Hasta este test, ningun contexto de
 * Spring habia arrancado con las cuatro entidades nuevas en el classpath: si Hibernate
 * rechazara esa columna —o cualquier otra de las cuatro tablas— {@code ddl-auto: validate}
 * lanzaria {@code SchemaManagementException} durante el arranque del contexto, ANTES de que el
 * {@code @Autowired} de abajo pudiera siquiera resolverse. La asercion del metodo es
 * secundaria: que este test arranque y llegue a ejecutarse ya es la prueba principal.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class DisponibilidadEntidadesJpaMappingIT {

	@Autowired
	private EntityManager entityManager;

	@Test
	@DisplayName("las cuatro entidades de disponibilidad profesional validan contra el esquema real de V22/V23")
	void las_cuatro_entidades_validan_contra_el_esquema_real() {
		Set<Class<?>> entidadesMapeadas = entityManager.getMetamodel().getEntities().stream()
				.map(EntityType::getJavaType)
				.collect(Collectors.toSet());

		assertThat(entidadesMapeadas).contains(
				Feriado.class,
				CalendarioSede.class,
				BloqueDisponibilidad.class,
				DisponibilidadExcepcion.class);
	}
}
