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
		LocalDate resueltoPara) {
}
