package com.akine.knowledge.application.manual;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.akine.knowledge.domain.manual.FichaDelManual;
import com.akine.organization.spi.FuncionIa;
import com.akine.organization.spi.FuncionesIaDelCentro;
import com.akine.organization.spi.PermissionEvaluator;
import com.akine.organization.spi.Servicio;
import com.akine.organization.spi.ServiciosDelCentro;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VisibilidadDelManualTest {

	private static final long CUENTA = 1L;
	private static final long ORG = 2L;
	private static final long SEDE = 3L;

	private final PermissionEvaluator permisos = mock(PermissionEvaluator.class);
	private final FuncionesIaDelCentro ia = mock(FuncionesIaDelCentro.class);
	private final ServiciosDelCentro servicios = mock(ServiciosDelCentro.class);
	private final VisibilidadDelManual visibilidad = new VisibilidadDelManual(permisos, ia, servicios);

	@BeforeEach
	void permisosBase() {
		when(permisos.effectivePermissions(CUENTA, ORG, SEDE)).thenReturn(Set.of("turno:read"));
		when(permisos.ownScopedPermissions(CUENTA, ORG, SEDE)).thenReturn(Set.of("turno:manage"));
	}

	@Test
	void ocultaFichaSinPermiso() {
		assertThat(filtro().test(ficha(Set.of("caja:operate"), null, null))).isFalse();
	}

	@Test
	void cuentaPermisoDeAlcancePropio() {
		assertThat(filtro().test(ficha(Set.of("turno:manage"), null, null))).isTrue();
	}

	@Test
	void ocultaFichaDeFuncionIaApagada() {
		when(ia.prendida(ORG, FuncionIa.ASISTENTE)).thenReturn(false);

		assertThat(filtro().test(ficha(Set.of(), FuncionIa.ASISTENTE, null))).isFalse();
	}

	@Test
	void ocultaFichaDeServicioNoContratado() {
		when(servicios.tiene(ORG, Servicio.ACTIVIDADES)).thenReturn(false);

		assertThat(filtro().test(ficha(Set.of(), null, Servicio.ACTIVIDADES))).isFalse();
	}

	private Predicate<FichaDelManual> filtro() {
		return visibilidad.para(CUENTA, ORG, SEDE);
	}

	private static FichaDelManual ficha(Set<String> requeridos, FuncionIa funcionIa, Servicio servicio) {
		return new FichaDelManual("f", "F", "G", List.of("/f"), requeridos, funcionIa, servicio, List.of(),
				List.of(), "", List.of());
	}
}
