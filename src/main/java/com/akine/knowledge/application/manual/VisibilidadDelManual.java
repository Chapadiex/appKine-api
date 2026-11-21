package com.akine.knowledge.application.manual;

import com.akine.knowledge.domain.manual.FichaDelManual;
import com.akine.organization.spi.FuncionesIaDelCentro;
import com.akine.organization.spi.PermissionEvaluator;
import com.akine.organization.spi.ServiciosDelCentro;
import com.akine.organization.spi.FuncionIa;
import com.akine.organization.spi.Servicio;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

/**
 * Que fichas ve una persona: las mismas condiciones que el menu ({@code secciones.ts}) para que el
 * manual no describa pantallas que no alcanza. Permisos (incluidos los de alcance propio), funcion de
 * IA del centro y servicio contratado.
 */
@Component
public class VisibilidadDelManual {

	private final PermissionEvaluator permisos;
	private final FuncionesIaDelCentro funcionesIa;
	private final ServiciosDelCentro servicios;

	public VisibilidadDelManual(PermissionEvaluator permisos, FuncionesIaDelCentro funcionesIa,
			ServiciosDelCentro servicios) {
		this.permisos = permisos;
		this.funcionesIa = funcionesIa;
		this.servicios = servicios;
	}

	/** El filtro de una persona en una sede. Permisos, IA y servicios se consultan una vez, no por ficha. */
	public Predicate<FichaDelManual> para(long accountId, long organizationId, long consultorioId) {
		Set<String> tiene = new HashSet<>(permisos.effectivePermissions(accountId, organizationId, consultorioId));
		tiene.addAll(permisos.ownScopedPermissions(accountId, organizationId, consultorioId));
		Map<FuncionIa, Boolean> ia = new EnumMap<>(FuncionIa.class);
		Map<Servicio, Boolean> contratados = new EnumMap<>(Servicio.class);
		return ficha -> (ficha.permisos().isEmpty() || ficha.permisos().stream().anyMatch(tiene::contains))
				&& (ficha.funcionIa() == null
						|| ia.computeIfAbsent(ficha.funcionIa(), f -> funcionesIa.prendida(organizationId, f)))
				&& (ficha.servicio() == null
						|| contratados.computeIfAbsent(ficha.servicio(), s -> servicios.tiene(organizationId, s)));
	}
}
