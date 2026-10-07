package com.akine.contracting.spi;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * El arancel que se aplica a una practica en una fecha, con la explicacion de por que.
 *
 * <p><b>Es una lectura VIVA, y por eso no sirve para guardar.</b> Refleja el convenio y el arancel
 * tal como estan hoy: si mañana se corrige el importe de esta misma ventana, este record devuelve
 * el importe nuevo. Sirve para DECIDIR y para mostrar —¿cuanto sale esta practica bajo esta
 * cobertura?— y nunca para persistir. Lo que se guarda es {@link ArancelCongelado}.
 *
 * <p>Es la misma separacion, y por el mismo motivo, que {@code PlanCoberturaSnapshot} contra
 * {@code ReferenciaDeCobertura} en 03.03, y que {@code PrecioDeOferta} contra el snapshot de
 * {@code obligacion} en 07.01.
 *
 * <h2>Por que trae la explicacion y no solo el numero</h2>
 *
 * <p>RF-M16-006 pide "determinar condiciones validas para una fecha" y la etapa exige que el
 * resultado sea <b>explicable</b>. Un endpoint que devuelve 1500 y nada mas deja al administrador
 * sin poder responder la unica pregunta que importa cuando el numero no es el esperado: ¿de que
 * convenio salio? Por eso viajan el convenio, su codigo y las dos vigencias que intervinieron —la
 * del convenio y la del arancel—, que juntas son la derivacion completa.
 *
 * <h2>Los tres importes, y ningun porcentaje</h2>
 *
 * <pre>
 *   importeTotal        lo que vale la practica bajo ese convenio
 *   importeFinanciador  la parte que paga el financiador
 *   coseguro            la parte que paga el paciente
 * </pre>
 *
 * <p>Las dos partes suman el total exactamente. No hay porcentaje que multiplicar ni nada que
 * redondear: ver {@code ConvenioArancel} y §37.
 *
 * @param convenioVigenciaHasta ultimo dia del convenio, INCLUSIVE. {@code null} = sin fin previsto
 * @param arancelVigenciaHasta  ultimo dia de ESTE importe, INCLUSIVE. {@code null} = sin fin
 * @param ofertaId              B-3: {@code null} si salio el arancel general de la practica; el id
 *                              de la oferta si salio el especifico de esa oferta (RF-M16-008)
 */
public record ArancelVigente(
		long convenioId,
		String convenioCodigo,
		String convenioNombre,
		String modalidad,
		long financiadorId,
		long planId,
		long practicaId,
		long arancelId,
		BigDecimal importeTotal,
		BigDecimal importeFinanciador,
		BigDecimal coseguro,
		String moneda,
		boolean requiereOrden,
		boolean requiereAutorizacion,
		boolean requiereCredencial,
		Integer limiteSesionesMensual,
		LocalDate convenioVigenciaDesde,
		LocalDate convenioVigenciaHasta,
		LocalDate arancelVigenciaDesde,
		LocalDate arancelVigenciaHasta,
		LocalDate resueltoPara,
		Long ofertaId) {

	/**
	 * La forma anterior a B-3: un arancel GENERAL de la practica, sin oferta.
	 *
	 * <p>{@code ofertaId} (B-3, RF-M16-008) es parte de la explicacion: {@code null} dice que salio
	 * el arancel general de la practica; con valor, que salio el especifico de esa oferta, que manda
	 * sobre el general cuando se resuelve con oferta.
	 */
	@SuppressWarnings("java:S107")
	public ArancelVigente(
			long convenioId, String convenioCodigo, String convenioNombre, String modalidad,
			long financiadorId, long planId, long practicaId, long arancelId,
			BigDecimal importeTotal, BigDecimal importeFinanciador, BigDecimal coseguro,
			String moneda, boolean requiereOrden, boolean requiereAutorizacion,
			boolean requiereCredencial, Integer limiteSesionesMensual,
			LocalDate convenioVigenciaDesde, LocalDate convenioVigenciaHasta,
			LocalDate arancelVigenciaDesde, LocalDate arancelVigenciaHasta,
			LocalDate resueltoPara) {

		this(convenioId, convenioCodigo, convenioNombre, modalidad, financiadorId, planId,
				practicaId, arancelId, importeTotal, importeFinanciador, coseguro, moneda,
				requiereOrden, requiereAutorizacion, requiereCredencial, limiteSesionesMensual,
				convenioVigenciaDesde, convenioVigenciaHasta, arancelVigenciaDesde,
				arancelVigenciaHasta, resueltoPara, null);
	}
}
