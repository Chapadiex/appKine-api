package com.akine.billing.application;

import com.akine.billing.domain.Obligacion;
import com.akine.billing.domain.SnapshotDeConvenio;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Una deuda.
 *
 * <p>Los importes son {@link BigDecimal} de punta a punta. <b>Ningun numero de punto flotante toca
 * este camino</b>: un {@code double} de 0.1 + 0.2 no da 0.3, y sobre una cuenta corriente eso son
 * centavos que no cuadran y que nadie puede explicar seis meses despues.
 *
 * @param importeOriginal lo que se devengo. No cambia nunca
 * @param financiadorId   quien debe cuando el responsable es el financiador; {@code null} cuando
 *                        debe el paciente. Lo agrego AKINE-07.04: sin el, la bandeja de M21 no
 *                        tiene por donde agrupar
 * @param saldo           lo que falta pagar
 * @param snapshotPrecio  el importe congelado AL MOMENTO de devengar. Editar la oferta o el
 *                        arancel manana no cambia esto: seria reescribir una cuenta corriente
 * @param concepto        {@code PARTICULAR}, {@code FINANCIADOR} o {@code COSEGURO} (AKINE F-4)
 * @param convenio        el convenio aplicado, congelado; {@code null} en las particulares
 */
public record ObligacionView(
		long id,
		long consultorioId,
		long sesionId,
		long personaId,
		String responsable,
		Long financiadorId,
		BigDecimal importeOriginal,
		BigDecimal saldo,
		String moneda,
		String estado,
		long ofertaId,
		String snapshotNombre,
		BigDecimal snapshotPrecio,
		Instant devengadaEn,
		Instant anuladaEn,
		String motivoAnulacion,
		long version,
		String concepto,
		Long practicaId,
		boolean alertaPracticaNoHabilitada,
		ConvenioAplicado convenio) {

	/**
	 * El snapshot del convenio de una obligacion, para mostrarlo y para RF-M21-003.
	 *
	 * <p>Los tres {@code requeria*} son lo que el convenio exigia el dia de la prestacion: lo que el
	 * administrativo tiene que juntar antes de presentar el lote.
	 */
	public record ConvenioAplicado(
			long convenioId,
			String convenioCodigo,
			String convenioNombre,
			long planId,
			long arancelId,
			long coberturaId,
			BigDecimal importeTotal,
			BigDecimal importeFinanciador,
			BigDecimal coseguro,
			boolean requeriaOrden,
			boolean requeriaAutorizacion,
			boolean requeriaCredencial,
			boolean credencialVencida,
			LocalDate vigenteEl) {

		static ConvenioAplicado de(SnapshotDeConvenio s) {
			return new ConvenioAplicado(
					s.convenioId(), s.convenioCodigo(), s.convenioNombre(), s.planId(),
					s.arancelId(), s.coberturaId(), s.importeTotal(), s.importeFinanciador(),
					s.coseguro(), s.requeriaOrden(), s.requeriaAutorizacion(),
					s.requeriaCredencial(), s.credencialVencida(), s.vigenteEl());
		}
	}

	public static ObligacionView de(Obligacion obligacion) {
		return new ObligacionView(
				obligacion.getId(),
				obligacion.getConsultorioId(),
				obligacion.getSesionId(),
				obligacion.getPersonaId(),
				obligacion.getResponsable().name(),
				obligacion.getFinanciadorId(),
				obligacion.getImporteOriginal(),
				obligacion.getSaldo(),
				obligacion.getMoneda(),
				obligacion.getEstado().name(),
				obligacion.getOfertaId(),
				obligacion.getSnapshotNombre(),
				obligacion.getSnapshotPrecio(),
				obligacion.getDevengadaEn(),
				obligacion.getAnuladaEn(),
				obligacion.getMotivoAnulacion(),
				obligacion.getVersion(),
				obligacion.getConcepto().name(),
				obligacion.getPracticaId(),
				obligacion.isAlertaPracticaNoHabilitada(),
				obligacion.getSnapshotDeConvenio().map(ConvenioAplicado::de).orElse(null));
	}
}
