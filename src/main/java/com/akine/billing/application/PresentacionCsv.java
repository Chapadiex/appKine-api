package com.akine.billing.application;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * El detalle de un lote como CSV: el listado exportable de AKINE-07.04 ("Listados
 * filtrados/exportables").
 *
 * <p><b>Serializa la misma vista que el detalle</b> ({@link PresentacionService#detalle}) y no tiene
 * consulta propia: un export con su query garantiza que algun dia el archivo y la pantalla digan
 * cosas distintas. Es el mismo criterio que el export de reportes (RF-M23-006).
 *
 * <p><b>PHI minima</b> (RNF-M21-001): viaja el {@code personaId} y el concepto congelado, igual que
 * en la pantalla. El nombre y el documento del paciente <b>no</b> se agregan aca: sumarlos es leer el
 * padron desde {@code billing} y es una decision sobre que datos salen del sistema, no un formato.
 *
 * <p>El escapado repite el de {@code reporting.application.ExportadorCsv} —RFC 4180 siempre y un
 * apostrofo delante de una celda que una planilla tomaria por formula— porque {@code billing} no
 * depende de {@code reporting}. Aca importa mas que alla: el concepto, el motivo de debito, la
 * factura y el nombre del financiador son texto libre del tenant.
 */
@Component
public class PresentacionCsv {

	private static final String SEPARADOR = ",";
	private static final String FIN_DE_LINEA = "\r\n";
	private static final String ARRANQUES_DE_FORMULA = "=+-@\t\r";

	public String aCsv(PresentacionView lote) {
		StringBuilder csv = new StringBuilder(1024);

		linea(csv, "Presentacion", String.valueOf(lote.id()));
		linea(csv, "Numero", texto(lote.numero()));
		linea(csv, "Consultorio", String.valueOf(lote.consultorioId()));
		linea(csv, "Financiador", lote.financiadorNombre());
		linea(csv, "Financiador id", String.valueOf(lote.financiadorId()));
		linea(csv, "Periodo desde", texto(lote.periodoDesde()));
		linea(csv, "Periodo hasta", texto(lote.periodoHasta()));
		linea(csv, "Estado", lote.estado().name());
		linea(csv, "Moneda", lote.moneda());
		linea(csv, "Total presentado", texto(lote.totalPresentado()));
		linea(csv, "Total debitado", texto(lote.totalDebitado()));
		linea(csv, "Total cobrado", texto(lote.totalCobrado()));
		linea(csv, "Saldo", texto(lote.saldo()));
		linea(csv, "Factura", lote.facturaNumero());
		linea(csv, "Fecha de factura", texto(lote.facturaFecha()));
		linea(csv, "Confirmada en", texto(lote.confirmadaEn()));
		csv.append(FIN_DE_LINEA);

		linea(csv, "Item", "Obligacion", "Sesion", "Persona", "Fecha de prestacion", "Concepto",
				"Estado", "Importe presentado", "Importe debitado", "Motivo de debito");
		if (lote.items() != null) {
			for (PresentacionItemView item : lote.items()) {
				linea(csv,
						String.valueOf(item.id()),
						String.valueOf(item.obligacionId()),
						String.valueOf(item.sesionId()),
						String.valueOf(item.personaId()),
						texto(item.fechaPrestacion()),
						item.concepto(),
						item.estado().name(),
						texto(item.importePresentado()),
						texto(item.importeDebitado()),
						item.motivoDebito());
			}
		}
		return csv.toString();
	}

	/** Nombre del archivo: el numero si el lote ya se confirmo, el id si sigue en borrador. */
	public String nombreDeArchivo(PresentacionView lote) {
		return lote.numero() == null
				? "presentacion-borrador-" + lote.id() + ".csv"
				: "presentacion-" + lote.financiadorId() + "-" + lote.numero() + ".csv";
	}

	private static String texto(Object valor) {
		if (valor == null) {
			return "";
		}
		if (valor instanceof BigDecimal importe) {
			return importe.toPlainString();
		}
		if (valor instanceof LocalDate || valor instanceof Instant) {
			return valor.toString();
		}
		return String.valueOf(valor);
	}

	private static void linea(StringBuilder csv, String... celdas) {
		for (int i = 0; i < celdas.length; i++) {
			if (i > 0) {
				csv.append(SEPARADOR);
			}
			csv.append(escapar(celdas[i]));
		}
		csv.append(FIN_DE_LINEA);
	}

	private static String escapar(String celda) {
		String valor = celda == null ? "" : celda;
		if (!valor.isEmpty() && ARRANQUES_DE_FORMULA.indexOf(valor.charAt(0)) >= 0) {
			valor = "'" + valor;
		}
		return '"' + valor.replace("\"", "\"\"") + '"';
	}
}
