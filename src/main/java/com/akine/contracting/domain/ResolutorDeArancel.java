package com.akine.contracting.domain;

import com.akine.contracting.spi.ArancelCongelado;
import com.akine.contracting.spi.ArancelVigente;
import com.akine.contracting.spi.MotivoSinArancel;
import com.akine.contracting.spi.ResolucionDeArancel;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * La resolucion de RF-M16-006 y RF-M16-010, en un solo lugar.
 *
 * <h2>Por que es una clase de dominio y no vive en el servicio</h2>
 *
 * <p>La misma resolucion la necesitan dos llamadores con vidas distintas: el endpoint de
 * administracion —que la muestra con su explicacion— y el {@code spi}, que la consume
 * {@code billing} al devengar. Si cada uno la escribiera, el dia que cambie el criterio de
 * candidatura una de las dos copias se quedaria atras y el importe que se cobra dejaria de ser el
 * importe que la pantalla muestra. Es un caso donde la duplicacion no cuesta trabajo: cuesta
 * correctitud.
 *
 * <h2>El algoritmo, y por que es determinista</h2>
 *
 * <ol>
 *   <li>De los convenios ACTIVOS de esa {@code (sede, financiador, plan)}, el que cubre la fecha.
 *       <b>Como mucho hay uno</b>: dos convenios del mismo alcance no se pueden solapar
 *       (RN-M16-002). Si no hay ninguno, {@link MotivoSinArancel#SIN_CONVENIO_VIGENTE} — y eso es
 *       RN-M16-005, no un error.</li>
 *   <li>De los aranceles ACTIVOS de esa practica en ese convenio, el que cubre la fecha. Como mucho
 *       hay uno, por la misma regla. Si no hay ninguno,
 *       {@link MotivoSinArancel#SIN_ARANCEL_VIGENTE}.</li>
 * </ol>
 *
 * <p><b>El resultado unico sale de que no existan dos candidatas, no de un desempate.</b> Esa es la
 * diferencia entre un motor explicable y uno que necesita una tabla de prioridades: acá la
 * respuesta a "¿por que salio este?" es "porque es el unico que cubre esa fecha", que cualquiera
 * puede verificar mirando la grilla.
 *
 * <p>Aun asi los dos {@code primero(...)} toman el <b>primero</b> de una lista que llega ordenada
 * por {@code vigenciaDesde DESC, id DESC} y no fallan si hubiera dos. Es una defensa contra la
 * unica via por la que dos candidatas podrian existir —una escritura hecha a mano contra la base,
 * saltando el servicio y su lock—: en ese caso gana la mas reciente, que es una respuesta
 * reproducible, en vez de la que el motor decida devolver primero, que no lo es.
 */
public final class ResolutorDeArancel {

	private ResolutorDeArancel() {
		// Utilidad de resolucion.
	}

	/**
	 * Resuelve contra los conjuntos ya filtrados por alcance y estado.
	 *
	 * <p>Recibe listas y no repositorios a proposito: asi la regla es una funcion pura, se puede
	 * probar sin base, y quien la llama es tambien quien decide si esas listas se leyeron bajo un
	 * lock o no.
	 */
	public static ResolucionDeArancel resolver(
			List<Convenio> convenios,
			List<ConvenioArancel> aranceles,
			long practicaId,
			LocalDate fecha) {

		Optional<Convenio> convenio = convenioAplicable(convenios, fecha);
		if (convenio.isEmpty()) {
			return ResolucionDeArancel.sinArancel(MotivoSinArancel.SIN_CONVENIO_VIGENTE);
		}

		Optional<ConvenioArancel> arancel = arancelAplicable(aranceles, fecha);
		if (arancel.isEmpty()) {
			return ResolucionDeArancel.sinArancel(MotivoSinArancel.SIN_ARANCEL_VIGENTE);
		}

		return ResolucionDeArancel.resuelta(vigente(convenio.get(), arancel.get(), practicaId, fecha));
	}

	/** El convenio que se aplica ese dia. Ver la cabecera sobre por que hay como mucho uno. */
	public static Optional<Convenio> convenioAplicable(
			List<Convenio> convenios, LocalDate fecha) {

		return convenios.stream().filter(c -> c.aplicaEl(fecha)).findFirst();
	}

	/** El arancel que se aplica ese dia. Ver la cabecera. */
	public static Optional<ConvenioArancel> arancelAplicable(
			List<ConvenioArancel> aranceles, LocalDate fecha) {

		return aranceles.stream().filter(a -> a.aplicaEl(fecha)).findFirst();
	}

	/**
	 * Convierte la resolucion en la COPIA que el consumidor guarda (RN-M16-004).
	 *
	 * <p>Es una funcion aparte de {@link #resolver} porque congelar y leer vivo son operaciones
	 * distintas y confundirlas es el defecto que M18 no puede cometer. Ver {@link ArancelCongelado}.
	 */
	public static ArancelCongelado congelar(
			ArancelVigente vigente, Instant capturadoEl) {

		return new ArancelCongelado(
				vigente.convenioId(),
				vigente.convenioCodigo(),
				vigente.convenioNombre(),
				vigente.modalidad(),
				vigente.financiadorId(),
				vigente.planId(),
				vigente.practicaId(),
				vigente.arancelId(),
				vigente.importeTotal(),
				vigente.importeFinanciador(),
				vigente.coseguro(),
				vigente.moneda(),
				vigente.requiereOrden(),
				vigente.requiereAutorizacion(),
				vigente.requiereCredencial(),
				vigente.resueltoPara(),
				capturadoEl);
	}

	private static ArancelVigente vigente(
			Convenio convenio, ConvenioArancel arancel, long practicaId, LocalDate fecha) {

		return new ArancelVigente(
				convenio.getId(),
				convenio.getCodigo(),
				convenio.getNombre(),
				convenio.getModalidad().name(),
				convenio.getFinanciadorId(),
				convenio.getPlanId(),
				practicaId,
				arancel.getId(),
				arancel.getImporteTotal(),
				arancel.getImporteFinanciador(),
				arancel.getCoseguro(),
				arancel.getMoneda(),
				convenio.isRequiereOrden(),
				convenio.isRequiereAutorizacion(),
				convenio.isRequiereCredencial(),
				convenio.getLimiteSesionesMensual(),
				convenio.getVigenciaDesde(),
				convenio.getVigenciaHasta(),
				arancel.getVigenciaDesde(),
				arancel.getVigenciaHasta(),
				fecha);
	}
}
