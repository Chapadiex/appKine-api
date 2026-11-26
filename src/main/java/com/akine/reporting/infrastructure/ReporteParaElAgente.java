package com.akine.reporting.infrastructure;

import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ContextoDeConsulta;
import com.akine.assistant.spi.EntradaDeHerramienta;
import com.akine.assistant.spi.HerramientaDelAgente;
import com.akine.assistant.spi.Periodo;
import com.akine.assistant.spi.ResultadoDeHerramienta;
import com.akine.reporting.application.OperatingActor;
import com.akine.reporting.application.ReporteService;
import com.akine.reporting.application.ReporteView;
import com.akine.reporting.spi.AdvertenciaDeReporte;
import com.akine.reporting.spi.AporteDeReporte;
import com.akine.reporting.spi.IndicadorDeReporte;
import com.akine.reporting.spi.ReporteCode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** {@code reporte}: los indicadores de los reportes de M23, con los permisos y recortes de la pantalla. */
@Component
class ReporteParaElAgente implements HerramientaDelAgente {

	static final int TOPE = 50;
	static final String ENLACE = "/reportes";
	static final String OMITIDAS = "Hay secciones de este reporte que tu rol no puede ver; no se muestran.";
	static final List<String> CODIGOS = List.of("ECONOMICO", "TURNOS", "CLASES");
	private static final String FECHA = "{\"type\":\"string\",\"description\":\"%s (YYYY-MM-DD o hoy, ayer, "
			+ "esta_semana, semana_pasada, este_mes, mes_pasado)\"}";

	private final ReporteService reportes;

	ReporteParaElAgente(ReporteService reportes) {
		this.reportes = reportes;
	}

	@Override
	public String nombre() {
		return "reporte";
	}

	@Override
	public String descripcion() {
		return "Devuelve los indicadores de un reporte de la sede activa: ECONOMICO (deuda, cobros y caja; se "
				+ "muestran separados, no se suman), TURNOS o CLASES, en un periodo (por defecto este_mes). "
				+ "Las secciones que el rol no puede ver vuelven como advertencia, no como cero.";
	}

	@Override
	public String esquemaEntrada() {
		return "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
				+ "\"codigo\":{\"type\":\"string\",\"enum\":[\"ECONOMICO\",\"TURNOS\",\"CLASES\"],"
				+ "\"description\":\"Que reporte\"},"
				+ "\"desde\":" + FECHA.formatted("Primer dia del periodo") + ","
				+ "\"hasta\":" + FECHA.formatted("Ultimo dia del periodo, inclusive") + "},"
				+ "\"required\":[\"codigo\"]}";
	}

	@Override
	public ResultadoDeHerramienta ejecutar(ActorDelAsistente actor, Map<String, Object> entrada,
			ContextoDeConsulta ctx) {
		String codigo = EntradaDeHerramienta.texto(entrada, "codigo");
		if (codigo == null || !CODIGOS.contains(codigo.toUpperCase(Locale.ROOT))) {
			throw new IllegalArgumentException("'codigo' tiene que ser uno de " + String.join(", ", CODIGOS));
		}
		Periodo esteMes = Periodo.resolver("este_mes", null, ctx, null);
		Periodo periodo = Periodo.resolver(entrada.get("desde"), entrada.get("hasta"), ctx, esteMes);
		OperatingActor operador = new OperatingActor(actor.accountId(), actor.platformAdmin(),
				actor.organizationId(), actor.consultorioId());
		ReporteView reporte = reportes.generar(operador, actor.consultorioId(),
				ReporteCode.valueOf(codigo.toUpperCase(Locale.ROOT)), periodo.desde(), periodo.hasta());
		List<Map<String, Object>> filas = new ArrayList<>();
		for (AporteDeReporte seccion : reporte.secciones()) {
			for (IndicadorDeReporte indicador : seccion.indicadores()) {
				Map<String, Object> fila = new LinkedHashMap<>();
				fila.put("seccion", seccion.titulo());
				fila.put("indicador", indicador.etiqueta());
				fila.put("tipo", indicador.tipo().name());
				fila.put("valor", indicador.valor());
				fila.put("moneda", indicador.moneda());
				filas.add(fila);
			}
		}
		List<String> advertencias = new ArrayList<>();
		if (!reporte.omitidas().isEmpty()) {
			advertencias.add(OMITIDAS);
		}
		reporte.advertencias().stream().map(AdvertenciaDeReporte::detalle)
				.filter(d -> d != null && !d.isBlank()).distinct().forEach(advertencias::add);
		if (filas.size() > TOPE) {
			advertencias.add("Hay " + filas.size() + " resultados; muestro los primeros " + TOPE);
			filas = new ArrayList<>(filas.subList(0, TOPE));
		}
		return new ResultadoDeHerramienta(filas, advertencias, ENLACE, false);
	}
}
