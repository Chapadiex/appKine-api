package com.akine.organization.infrastructure.tenant;

import com.akine.organization.domain.exception.ServicioNoContratadoException;
import com.akine.organization.domain.port.ContratacionRepositoryPort;
import com.akine.organization.spi.Adicional;
import com.akine.organization.spi.Servicio;
import com.akine.platform.spi.tenant.RequestTenantContext;
import com.akine.platform.spi.tenant.TenantContextHolder;
import com.akine.platform.spi.tenant.TenantOperationalStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.method.HandlerMethod;

import java.util.EnumSet;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class ServicioContratadoInterceptorTest {

	private static final long ORG = 7L;

	private final TenantContextHolder holder = mock(TenantContextHolder.class);
	private final ContratacionRepositoryPort contratacion = mock(ContratacionRepositoryPort.class);

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

	@Test
	@DisplayName("un centro sin actividades recibe 409 en las clases, y sin IA en el dictado")
	void corta_lo_no_contratado() throws Exception {
		conContexto();
		given(contratacion.serviciosDe(ORG)).willReturn(EnumSet.of(Servicio.KINESIOLOGIA));
		given(contratacion.adicionalesDe(ORG)).willReturn(EnumSet.noneOf(Adicional.class));

		assertThatThrownBy(() -> interceptor().preHandle(null, null,
				handler(com.akine.activity.api.ClaseController.class)))
				.isInstanceOf(ServicioNoContratadoException.class)
				.extracting(e -> ((ServicioNoContratadoException) e).getContratable()).isEqualTo("ACTIVIDADES");
		assertThatThrownBy(() -> interceptor().preHandle(null, null,
				handler(com.akine.assistant.api.DictadoController.class)))
				.isInstanceOf(ServicioNoContratadoException.class)
				.extracting(e -> ((ServicioNoContratadoException) e).getContratable()).isEqualTo("IA");
	}

	@Test
	@DisplayName("un servicio que el centro dejo queda en solo lectura: se consulta y no se modifica")
	void servicio_en_lectura() throws Exception {
		conContexto();
		given(contratacion.serviciosDe(ORG)).willReturn(EnumSet.of(Servicio.KINESIOLOGIA));
		given(contratacion.serviciosEnLecturaDe(ORG)).willReturn(EnumSet.of(Servicio.ACTIVIDADES));

		assertThat(interceptor().preHandle(new org.springframework.mock.web.MockHttpServletRequest("GET", "/x"),
				null, handler(com.akine.activity.api.ClaseController.class))).isTrue();
		assertThatThrownBy(() -> interceptor().preHandle(
				new org.springframework.mock.web.MockHttpServletRequest("POST", "/x"), null,
				handler(com.akine.activity.api.ClaseController.class)))
				.isInstanceOf(ServicioNoContratadoException.class)
				.extracting(e -> ((ServicioNoContratadoException) e).isSoloLectura()).isEqualTo(true);
	}

	@Test
	@DisplayName("lo contratado, lo comun y los pedidos sin contexto pasan")
	void deja_pasar() throws Exception {
		given(holder.current()).willReturn(Optional.empty());
		assertThat(interceptor().preHandle(null, null, handler(com.akine.activity.api.ClaseController.class)))
				.as("sin contexto").isTrue();
		assertThat(interceptor().preHandle(null, null, new Object())).as("no es un controller").isTrue();

		conContexto();
		given(contratacion.serviciosDe(ORG)).willReturn(EnumSet.allOf(Servicio.class));
		given(contratacion.adicionalesDe(ORG)).willReturn(EnumSet.of(Adicional.IA));
		assertThat(interceptor().preHandle(null, null, handler(com.akine.activity.api.ClaseController.class)))
				.isTrue();
		assertThat(interceptor().preHandle(null, null, handler(com.akine.assistant.api.DictadoController.class)))
				.isTrue();
	}

	private void conContexto() {
		given(holder.current()).willReturn(Optional.of(new RequestTenantContext(
				1L, ORG, 10L, "ORG_ADMIN", TenantOperationalStatus.ACTIVA)));
	}

	@SuppressWarnings("unchecked")
	private ServicioContratadoInterceptor interceptor() {
		ObjectProvider<TenantContextHolder> holders = mock(ObjectProvider.class);
		ObjectProvider<ContratacionRepositoryPort> contrataciones = mock(ObjectProvider.class);
		given(holders.getIfAvailable()).willReturn(holder);
		given(contrataciones.getIfAvailable()).willReturn(contratacion);
		return new ServicioContratadoInterceptor(holders, contrataciones);
	}

	private static HandlerMethod handler(Class<?> controller) throws NoSuchMethodException {
		BeanFactory beanFactory = mock(BeanFactory.class);
		given(beanFactory.containsBean("controller")).willReturn(true);
		given(beanFactory.getType("controller")).willAnswer(i -> controller);
		return new HandlerMethod("controller", beanFactory, controller.getMethod("toString"));
	}
}
