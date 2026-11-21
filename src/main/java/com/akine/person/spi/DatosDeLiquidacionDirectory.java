package com.akine.person.spi;

import java.util.Collection;
import java.util.Map;

/**
 * Lo que una liquidacion a la obra social necesita de {@code person} para que cada renglon se
 * entienda sin ids: el numero de afiliado y el plan de la cobertura con la que se atendio, y el
 * numero de autorizacion que consumio la sesion (docs/diseno/AKINE-obras-sociales-simple.md §6).
 *
 * <p><b>No autoriza nada.</b> Quien la llama ya exigio su permiso ({@code cobro:register}) y la
 * sede del lote. Las dos lecturas son en lote, para que un lote de 500 renglones no haga 500
 * consultas, y filtran por organizacion.
 */
public interface DatosDeLiquidacionDirectory {

	/**
	 * Afiliado, financiador y plan <b>de la copia congelada</b> de cada cobertura (el nombre del plan
	 * al firmar, no el de hoy). Las coberturas que no son de la organizacion no vuelven.
	 */
	Map<Long, CoberturaDeLiquidacion> coberturas(long organizationId, Collection<Long> coberturaIds);

	/**
	 * Numero de la autorizacion que consumio cada sesion, sin contar los consumos revertidos. Si
	 * una sesion consumio mas de una, vienen separadas por coma. Las sesiones sin consumo no
	 * vuelven.
	 */
	Map<Long, String> autorizacionesPorSesion(long organizationId, Collection<Long> sesionIds);

	record CoberturaDeLiquidacion(
			long coberturaId, String numeroAfiliado, String financiadorNombre, String planNombre) {
	}
}
