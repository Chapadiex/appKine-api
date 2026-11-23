package com.akine.organization.spi;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Lo que necesita quien avisa del vencimiento de la prueba gratis (18/11/2026) y del periodo
 * pagado (19/11/2026, docs/diseno/AKINE-cobro-mensual.md): que centros vencen en una ventana y a
 * que cuentas avisarles. Lo consume {@code identity}, que resuelve los correos y encola los avisos.
 */
public interface PruebasGratis {

	/** Centros vigentes en PRUEBA cuyo fin cae en {@code [desde, hasta)}. */
	List<PruebaEnCurso> queTerminanEntre(Instant desde, Instant hasta);

	/**
	 * Centros vigentes con la suscripcion ACTIVA guardada cuyo periodo pagado vence en
	 * {@code [desde, hasta)}. Una SUSPENDIDA o CANCELADA guardada no aparece: la suspension manual
	 * manda sobre el cobro y no se le avisa del vencimiento.
	 */
	List<PagoQueVence> pagosQueVencenEntre(Instant desde, Instant hasta);

	/** Cuentas con ORG_ADMIN vigente en la organizacion. */
	Set<Long> administradores(long organizationId);

	record PruebaEnCurso(long organizationId, String nombreCentro, Instant pruebaHasta) {
	}

	/**
	 * Un periodo pagado con sus hitos ya calculados por {@code organization}, que es quien sabe
	 * cuanto dura la gracia y cuando se cierra el acceso.
	 *
	 * @param pagadoHasta      vencimiento del periodo pagado
	 * @param soloLecturaDesde fin de la gracia: desde ahi el centro queda en solo lectura
	 * @param cierreEl         desde ahi el centro queda sin acceso
	 */
	record PagoQueVence(long organizationId, String nombreCentro, Instant pagadoHasta,
			Instant soloLecturaDesde, Instant cierreEl) {
	}
}
