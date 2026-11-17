package com.akine.reporting.infrastructure;

import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ClavesDeFila;
import com.akine.assistant.spi.HerramientaDelAsistente;
import com.akine.assistant.spi.Intencion;
import com.akine.assistant.spi.PedidoDeHerramienta;
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
import java.util.Map;

/**
 * "¿Cuanto se facturo?" Genera el reporte de M23 con {@link ReporteService#generar}, con los mismos
 * permisos, recortes y omisiones que la pantalla de reportes, y devuelve sus indicadores.
 *
 * <p><b>No suma nada</b>: cada indicador viaja con su etiqueta, tal como lo calcula su modulo, y
 * deuda, cobro y caja siguen separados. Las secciones que el actor no puede ver vuelven como
 * advertencia, no como cero.
 */
@Component
class ReportesEnElAsistente implements HerramientaDelAsistente {

	static final String ENLACE = "/reportes";
	static final String OMITIDAS = "Hay secciones de este reporte que tu rol no puede ver; no se muestran.";

	private final ReporteService reportes;

	ReportesEnElAsistente(ReporteService reportes) {
		this.reportes = reportes;
	}

	@Override
	public Intencion intencion() {
		return Intencion.REPORTE;
	}

	@Override
	public ResultadoDeHerramienta ejecutar(ActorDelAsistente actor, PedidoDeHerramienta pedido) {
		OperatingActor operador = new OperatingActor(actor.accountId(), actor.platformAdmin(),
				actor.organizationId(), actor.consultorioId());
		// Los dos extremos inclusive, igual que el pedido. El codigo ya viene validado contra
		// Intencion.REPORTES por el interpretador.
		ReporteView reporte = reportes.generar(operador, actor.consultorioId(), ReporteCode.valueOf(pedido.reporte()),
				pedido.desde(), pedido.hasta());
		List<Map<String, Object>> filas = new ArrayList<>();
		for (AporteDeReporte seccion : reporte.secciones()) {
			for (IndicadorDeReporte indicador : seccion.indicadores()) {
				Map<String, Object> fila = new LinkedHashMap<>();
				fila.put(ClavesDeFila.SECCION, seccion.titulo());
				fila.put(ClavesDeFila.ETIQUETA, indicador.etiqueta());
				fila.put(ClavesDeFila.TIPO, indicador.tipo().name());
				fila.put(ClavesDeFila.VALOR, indicador.valor());
				fila.put(ClavesDeFila.MONEDA, indicador.moneda());
				filas.add(fila);
			}
		}
		List<String> advertencias = new ArrayList<>();
		if (!reporte.omitidas().isEmpty()) {
			advertencias.add(OMITIDAS);
		}
		reporte.advertencias().stream()
				.map(AdvertenciaDeReporte::detalle)
				.filter(d -> d != null && !d.isBlank())
				.distinct()
				.forEach(advertencias::add);
		return new ResultadoDeHerramienta(filas, advertencias, ENLACE, false);
	}
}
