package com.akine.clinical.application;

import com.akine.clinical.domain.PlanTratamiento;
import com.akine.clinical.domain.PlanTratamientoVersion;

import java.time.Instant;
import java.util.List;

/**
 * Un Plan de Tratamiento con su version <b>vigente</b>, tal como sale de la aplicacion.
 *
 * <p>Trae la vigente y no el historico completo: el historico se lee con la consulta de versiones,
 * que es otra operacion y deja su propio evento de auditoria. Mostrar las dos cosas juntas pondria
 * en la misma pantalla cantidades de hace dos meses al lado de las de hoy sin que se distinga
 * cuales son cuales — que es exactamente el error que colgar los items de la version evita.
 *
 * <p><b>No trae avance.</b> El avance se deriva contando sesiones del Caso y es su propia consulta:
 * meterlo aca haria que abrir la ficha de un plan consultara {@code encounter}, y que la ficha y el
 * avance pudieran discrepar sin que se note cual de los dos vale.
 *
 * @param numeroPlan correlativo <b>dentro del Caso</b>: "el segundo plan de este problema". No es
 *                   el id, no es el numero de caso y no es el de sesion — desde 04.04 hay cuatro
 *                   numeros distintos dando vueltas y ninguno sustituye a otro
 * @param version    la del bloqueo optimista, que el cliente devuelve al modificar o al transicionar.
 *                   <b>No</b> es {@code numeroVersion}, que es el orden del contenido: son dos
 *                   versiones distintas y se llaman parecido, asi que conviene mirarlas dos veces
 */
public record PlanTratamientoView(
		long id,
		long casoClinicoId,
		int numeroPlan,
		String estado,
		Instant creadoEn,
		long creadoPor,
		Instant activadoEn,
		Long activadoPor,
		Instant suspendidoEn,
		String motivoSuspension,
		Instant finalizadoEn,
		Long finalizadoPor,
		String motivoFinalizacion,
		PlanVersionView versionVigente,
		long version) {

	public static PlanTratamientoView de(
			PlanTratamiento plan, PlanTratamientoVersion vigente, List<PlanItemView> items) {

		return new PlanTratamientoView(
				plan.getId(),
				plan.getCasoClinicoId(),
				plan.getNumeroPlan(),
				plan.getEstado().name(),
				plan.getCreadoEn(),
				plan.getCreadoPor(),
				plan.getActivadoEn(),
				plan.getActivadoPor(),
				plan.getSuspendidoEn(),
				plan.getMotivoSuspension(),
				plan.getFinalizadoEn(),
				plan.getFinalizadoPor(),
				plan.getMotivoFinalizacion(),
				vigente == null ? null : PlanVersionView.de(vigente, items),
				plan.getVersion());
	}
}
