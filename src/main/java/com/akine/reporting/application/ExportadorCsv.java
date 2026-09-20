package com.akine.reporting.application;

import com.akine.reporting.spi.AdvertenciaDeReporte;
import com.akine.reporting.spi.AporteDeReporte;
import com.akine.reporting.spi.FilaDeReporte;
import com.akine.reporting.spi.IndicadorDeReporte;
import org.springframework.stereotype.Component;

/**
 * Serializa un {@link ReporteView} a CSV (RF-M23-006).
 *
 * <h2>El export NO tiene su propia consulta</h2>
 *
 * <p>Recibe el {@code ReporteView} que ya produjo {@link ReporteService}, el mismo que va a la
 * pantalla. La alternativa —una query optimizada para el archivo— <b>garantiza que algun dia la
 * pantalla y el CSV digan cosas distintas y que nadie sepa cual de las dos miente</b>. Es el mismo
 * riesgo que un agregado materializado, en chico.
 *
 * <h2>Que se escribe, y por que en ese orden</h2>
 *
 * <p>Cabecera con el periodo, la zona y el instante de la corrida; despues, por cada seccion, sus
 * indicadores <b>con su fuente y su criterio de fecha</b>, y su detalle si lo tiene. Al final, lo
 * omitido y las advertencias.
 *
 * <p><b>La fuente viaja en el archivo y no solo en la pantalla.</b> Un CSV sobrevive a la sesion
 * que lo genero: llega por mail, se abre en una planilla y alguien le suma una columna a otra. Si
 * el archivo no dice que {@code cobrado} sale de M19 y {@code caja-ingresos-efectivo} de M20, esa
 * suma se hace sola.
 *
 * <h2>El escapado</h2>
 *
 * <p>Se entrecomilla siempre y se duplican las comillas internas (RFC 4180). Siempre y no "cuando
 * hace falta": un concepto con una coma, un salto de linea en un motivo o un nombre con comillas
 * corren el resto de las columnas, y el archivo no falla — se lee mal.
 */
@Component
public class ExportadorCsv {

	private static final String SEPARADOR = ",";
	private static final String FIN_DE_LINEA = "\r\n";

	public String aCsv(ReporteView vista) {
		StringBuilder csv = new StringBuilder(1024);

		linea(csv, "Reporte", vista.reporte().name());
		linea(csv, "Consultorio", String.valueOf(vista.consultorioId()));
		linea(csv, "Desde", vista.desde().toString());
		linea(csv, "Hasta", vista.hasta().toString());
		linea(csv, "Zona horaria", vista.zona());
		linea(csv, "Generado en", vista.generadoEn().toString());
		csv.append(FIN_DE_LINEA);

		for (AporteDeReporte seccion : vista.secciones()) {
			linea(csv, "Seccion", seccion.titulo());

			if (!seccion.indicadores().isEmpty()) {
				linea(csv, "Indicador", "Valor", "Tipo", "Moneda", "Fuente", "Criterio de fecha");
				for (IndicadorDeReporte indicador : seccion.indicadores()) {
					linea(csv,
							indicador.etiqueta(),
							indicador.valor().toPlainString(),
							indicador.tipo().name(),
							indicador.moneda() == null ? "" : indicador.moneda(),
							indicador.fuente() == null ? "" : indicador.fuente(),
							indicador.criterioDeFecha() == null ? "" : indicador.criterioDeFecha());
				}
			}

			if (!seccion.columnas().isEmpty()) {
				csv.append(FIN_DE_LINEA);
				linea(csv, seccion.columnas().toArray(String[]::new));
				for (FilaDeReporte fila : seccion.filas()) {
					linea(csv, fila.celdas().toArray(String[]::new));
				}
			}
			csv.append(FIN_DE_LINEA);
		}

		if (!vista.omitidas().isEmpty()) {
			linea(csv, "Secciones omitidas", "Permiso que falta");
			for (ReporteView.SeccionOmitida omitida : vista.omitidas()) {
				linea(csv, omitida.seccion(), omitida.permisoRequerido());
			}
			csv.append(FIN_DE_LINEA);
		}

		if (!vista.advertencias().isEmpty()) {
			linea(csv, "Advertencia", "Seccion", "Detalle");
			for (AdvertenciaDeReporte advertencia : vista.advertencias()) {
				linea(csv, advertencia.codigo(), advertencia.seccion(), advertencia.detalle());
			}
		}

		return csv.toString();
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
		return '"' + valor.replace("\"", "\"\"") + '"';
	}
}
