package com.akine.organization.infrastructure.tenant;

import com.akine.organization.spi.Servicio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ServicioContratadoInterceptorTest {

	@Test
	@DisplayName("el paquete o la clase del controller dicen a que servicio pertenece")
	void servicio_por_controller() {
		assertThat(ServicioContratadoInterceptor.servicioDe("com.akine.clinical.api.TimelineController"))
				.contains(Servicio.KINESIOLOGIA);
		assertThat(ServicioContratadoInterceptor.servicioDe("com.akine.encounter.api.SesionController"))
				.contains(Servicio.KINESIOLOGIA);
		assertThat(ServicioContratadoInterceptor.servicioDe("com.akine.billing.api.PresentacionController"))
				.contains(Servicio.KINESIOLOGIA);
		assertThat(ServicioContratadoInterceptor.servicioDe("com.akine.person.api.AutorizacionController"))
				.contains(Servicio.KINESIOLOGIA);
		assertThat(ServicioContratadoInterceptor.servicioDe("com.akine.activity.api.ClaseController"))
				.contains(Servicio.ACTIVIDADES);
		assertThat(ServicioContratadoInterceptor.servicioDe("com.akine.billing.api.CajaController")).isEmpty();
		assertThat(ServicioContratadoInterceptor.servicioDe("com.akine.person.api.PersonaController")).isEmpty();
		assertThat(ServicioContratadoInterceptor.servicioDe("com.akine.scheduling.api.TurnoController")).isEmpty();
	}
}
