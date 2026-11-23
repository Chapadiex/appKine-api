package com.akine.billing.application;

import com.akine.billing.domain.EstadoItemPresentacion;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * El detalle de un lote como CSV: el listado exportable de AKINE-07.04 ("Listados
 * filtrados/exportables").
 *
 * <p><b>Serializa la misma vista que el detalle</b> ({@link PresentacionService#detalle}) y no tiene
 * consulta propia: un export con su query garantiza que algun dia el archivo y la pantalla digan
 * cosas distintas. Es el mismo criterio que el export de reportes (RF-M23-006).
 *
 * <p><b>Lo que la obra social pide para liquidar</b> (obras sociales simples, paso 3): cada renglon
 * lleva paciente, documento, afiliado, plan, practica con su codigo y autorizacion, y al final va un
 * resumen por paciente. Los datos los completa {@link RenglonesLegibles} en la vista del detalle;
 * este archivo solo los escribe. Ningun dato clinico.
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

		linea(csv, "Detalle");
		linea(csv, "Paciente", "Documento", "Afiliado", "Plan", "Fecha de prestacion",
				"Codigo de practica", "Practica", "Autorizacion", "Concepto", "Estado",
				"Importe presentado", "Importe debitado", "Motivo de debito", "Sesion", "Item");
		List<PresentacionItemView> items = lote.items() == null ? List.of() : lote.items();
		for (PresentacionItemView item : items) {
			linea(csv,
					item.pacienteNombre(),
					item.pacienteDocumento(),
					item.numeroAfiliado(),
					item.planNombre(),
					texto(item.fechaPrestacion()),
					item.practicaCodigo(),
					item.practicaNombre(),
					item.autorizacionNumero(),
					item.concepto(),
					item.estado().name(),
					texto(item.importePresentado()),
					texto(item.importeDebitado()),
					item.motivoDebito(),
					String.valueOf(item.sesionId()),
					String.valueOf(item.id()));
		}

		resumenPorPaciente(csv, items);
		return csv.toString();
	}

	/**
	 * Una linea por paciente con sus sesiones y fechas, para controlar el lote de un vistazo. Cuenta
	 * los renglones vivos: un renglon anulado ya no esta en la liquidacion.
	 */
	private static void resumenPorPaciente(StringBuilder csv, List<PresentacionItemView> items) {
		Map<Long, List<PresentacionItemView>> porPaciente = items.stream()
				.filter(item -> item.estado() != EstadoItemPresentacion.ANULADO)
				.collect(Collectors.groupingBy(PresentacionItemView::personaId, LinkedHashMap::new,
						Collectors.toList()));
		if (porPaciente.isEmpty()) {
			return;
		}
		csv.append(FIN_DE_LINEA);
		linea(csv, "Resumen por paciente");
		linea(csv, "Paciente", "Documento", "Afiliado", "Plan", "Sesiones", "Fechas",
				"Importe presentado");
		porPaciente.values().stream()
				.sorted(Comparator.comparing(
						(List<PresentacionItemView> renglones) -> renglones.getFirst().pacienteNombre(),
						Comparator.nullsLast(Comparator.naturalOrder())))
				.forEach(renglones -> {
					PresentacionItemView primero = renglones.getFirst();
					linea(csv,
							primero.pacienteNombre(),
							primero.pacienteDocumento(),
							primero.numeroAfiliado(),
							primero.planNombre(),
							String.valueOf(renglones.size()),
							renglones.stream()
									.map(PresentacionItemView::fechaPrestacion)
									.filter(Objects::nonNull)
									.sorted()
									.map(LocalDate::toString)
									.collect(Collectors.joining(" ")),
							texto(renglones.stream()
									.map(PresentacionItemView::importePresentado)
									.filter(Objects::nonNull)
									.reduce(BigDecimal.ZERO, BigDecimal::add)));
				});
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
