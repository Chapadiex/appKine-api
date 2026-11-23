package com.akine.contracting.spi;

/**
 * Un convenio activo que alcanza a una sede: el propio de la sede o el del plan, que vale para
 * todas (V43). Lo lee el asistente para listar lo que paga cada plan por una oferta; el importe lo
 * resuelve {@link ArancelDirectory#resolver}, que es la regla del devengo.
 *
 * @param delPlan {@code true} si es el convenio del plan (sin sede); el de la sede manda sobre el
 */
public record ConvenioDeLaSede(
		long convenioId,
		String codigo,
		String nombre,
		long financiadorId,
		long planId,
		boolean delPlan,
		Integer limiteSesionesMensual) {
}
