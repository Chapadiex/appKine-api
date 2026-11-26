package com.akine.assistant.application.fichas;

import com.akine.assistant.application.CatalogoDeHerramientas;
import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ContextoDeConsulta;
import com.akine.assistant.spi.Periodo;
import com.akine.assistant.spi.ResultadoDeHerramienta;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Los turnos de la sede en un dia, mas el total por estado. */
@Component
class AgendaDelDia extends FichaCompuesta {

	/** El tope de filas de la herramienta {@code turnos}; llegar a el es lista cortada. */
	static final int TOPE_DE_TURNOS = 50;
	static final String TOTALES_PARCIALES =
			"Hay más de 50 turnos: los totales por estado no se muestran completos.";

	AgendaDelDia(ObjectProvider<CatalogoDeHerramientas> catalogo) {
		super(catalogo);
	}

	@Override
	public String nombre() {
		return "agenda_del_dia";
	}

	@Override
	public String descripcion() {
		return "La agenda de la sede en un dia: los turnos y cuantos hay en cada estado. "
				+ "Sin fecha es hoy. No incluye atenciones sin turno ni datos clinicos.";
	}

	@Override
	public String esquemaEntrada() {
		return "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
				+ "\"fecha\":{\"type\":\"string\",\"description\":\"YYYY-MM-DD o hoy, ayer, manana; sin fecha, hoy\"}}}";
	}

	@Override
	public ResultadoDeHerramienta ejecutar(ActorDelAsistente actor, Map<String, Object> entrada,
			ContextoDeConsulta ctx) {
		Periodo dia = Periodo.resolver(entrada.get("fecha"), null, ctx, Periodo.delDia(ctx.hoy()));
		Partes partes = partes(actor, ctx);
		ResultadoDeHerramienta turnos = partes.llamar("turnos",
				Map.of("desde", dia.desde().toString(), "hasta", dia.desde().toString()));
		if (turnos != null && truncado(turnos)) {
			// Con la lista cortada en el tope, contar sus filas daria totales parciales presentados
			// como completos: se omiten y se dice por que.
			partes.advertir(TOTALES_PARCIALES);
		}
		else if (turnos != null) {
			Map<String, Integer> porEstado = new TreeMap<>();
			for (Map<String, Object> fila : turnos.filas()) {
				porEstado.merge(String.valueOf(fila.get("estado")), 1, Integer::sum);
			}
			List<Map<String, Object>> totales = new ArrayList<>();
			porEstado.forEach((estado, cantidad) -> {
				Map<String, Object> fila = new LinkedHashMap<>();
				fila.put("estado", estado);
				fila.put("cantidad", cantidad);
				totales.add(fila);
			});
			partes.agregar("totales_por_estado", totales, List.of());
		}
		return partes.resultado();
	}

	private static boolean truncado(ResultadoDeHerramienta turnos) {
		// La herramienta corta en el tope y lo avisa con "muestro los primeros"; exactamente 50 turnos
		// sin ese aviso es la lista entera.
		return turnos.filas().size() > TOPE_DE_TURNOS
				|| turnos.advertencias().stream().anyMatch(a -> a.contains("muestro los primeros"));
	}
}
