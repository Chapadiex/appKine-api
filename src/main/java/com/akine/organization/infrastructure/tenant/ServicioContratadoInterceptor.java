package com.akine.organization.infrastructure.tenant;

import com.akine.organization.domain.exception.ServicioNoContratadoException;
import com.akine.organization.domain.port.ContratacionRepositoryPort;
import com.akine.organization.spi.Adicional;
import com.akine.organization.spi.Servicio;
import com.akine.platform.spi.tenant.RequestTenantContext;
import com.akine.platform.spi.tenant.TenantContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;
import java.util.Set;

/**
 * Corta las operaciones de un servicio que el centro no contrato (18/11/2026,
 * docs/diseno/AKINE-landing-y-ofertas.md §7).
 *
 * <p><b>Por que por el controller y no por la ruta.</b> Las rutas de un modulo no comparten
 * prefijo (la historia clinica cuelga de {@code /personas}, las autorizaciones tambien), y una
 * lista de prefijos se desactualiza en silencio con cada endpoint nuevo. El paquete del
 * controller dice de que modulo es: un controller nuevo en {@code clinical} queda cubierto sin
 * tocar esto.
 *
 * <p>Solo actua con contexto de tenant: la consola de plataforma y las rutas publicas pasan. La
 * IA no se mira aca salvo el dictado: el resto de sus funciones ya preguntan a
 * {@code FuncionesIaDelCentro}, que exige el adicional.
 */
@Component
class ServicioContratadoInterceptor implements HandlerInterceptor {

	private static final Set<String> PAQUETES_DE_KINESIOLOGIA = Set.of(
			"com.akine.clinical.", "com.akine.encounter.", "com.akine.contracting.");

	/** Controllers de otros modulos que solo tienen sentido con kinesiologia. */
	private static final Set<String> CONTROLLERS_DE_KINESIOLOGIA = Set.of(
			"com.akine.billing.api.PresentacionController",
			"com.akine.person.api.AutorizacionController",
			"com.akine.person.api.CoberturaPacienteController",
			"com.akine.person.api.CoberturaParaOfertaController",
			"com.akine.person.api.ConsumoDeAutorizacionController",
			"com.akine.person.api.ElegibilidadController",
			"com.akine.person.api.OrdenMedicaController");

	private static final String PAQUETE_DE_ACTIVIDADES = "com.akine.activity.";

	private static final Set<String> LECTURAS = Set.of("GET", "HEAD", "OPTIONS");

	private static final String CONTROLLER_DE_DICTADO = "com.akine.assistant.api.DictadoController";

	// ObjectProvider y no inyeccion directa: los slices @WebMvcTest cargan los interceptores pero
	// no los repositorios. Sin ellos no hay tenant que mirar, y el interceptor deja pasar.
	private final ObjectProvider<TenantContextHolder> tenantContextHolder;
	private final ObjectProvider<ContratacionRepositoryPort> contratacionProvider;

	ServicioContratadoInterceptor(ObjectProvider<TenantContextHolder> tenantContextHolder,
			ObjectProvider<ContratacionRepositoryPort> contratacionProvider) {
		this.tenantContextHolder = tenantContextHolder;
		this.contratacionProvider = contratacionProvider;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!(handler instanceof HandlerMethod metodo)) {
			return true;
		}
		TenantContextHolder holder = tenantContextHolder.getIfAvailable();
		ContratacionRepositoryPort contratacion = contratacionProvider.getIfAvailable();
		if (holder == null || contratacion == null) {
			return true;
		}
		Optional<RequestTenantContext> contexto = holder.current();
		if (contexto.isEmpty()) {
			return true;
		}
		long organizationId = contexto.get().organizationId();
		String clase = metodo.getBeanType().getName();

		Optional<Servicio> servicio = servicioDe(clase);
		if (servicio.isPresent()
				&& !contratacion.serviciosDe(organizationId).contains(servicio.get())) {
			// Un servicio que el centro tuvo y dejo queda en solo lectura (26/11/2026): se consulta,
			// no se modifica.
			boolean enLectura = contratacion.serviciosEnLecturaDe(organizationId).contains(servicio.get());
			if (enLectura && LECTURAS.contains(request.getMethod())) {
				return true;
			}
			throw new ServicioNoContratadoException(servicio.get(), enLectura);
		}
		if (CONTROLLER_DE_DICTADO.equals(clase)
				&& !contratacion.adicionalesDe(organizationId).contains(Adicional.IA)) {
			throw new ServicioNoContratadoException(Adicional.IA);
		}
		return true;
	}

	static Optional<Servicio> servicioDe(String clase) {
		if (CONTROLLERS_DE_KINESIOLOGIA.contains(clase)
				|| PAQUETES_DE_KINESIOLOGIA.stream().anyMatch(clase::startsWith)) {
			return Optional.of(Servicio.KINESIOLOGIA);
		}
		if (clase.startsWith(PAQUETE_DE_ACTIVIDADES)) {
			return Optional.of(Servicio.ACTIVIDADES);
		}
		return Optional.empty();
	}
}
