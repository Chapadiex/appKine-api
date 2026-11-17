package com.akine.encounter.application;

import com.akine.encounter.domain.evolucion.CodigoDeHallazgo;
import com.akine.encounter.domain.evolucion.Hallazgo;
import com.akine.encounter.domain.evolucion.sugerencias.HallazgoConEvidencia;
import com.akine.encounter.domain.evolucion.sugerencias.PasajeEnviado;
import com.akine.encounter.domain.evolucion.sugerencias.RecursosDeSugerencias;
import com.akine.knowledge.spi.BibliotecaDirectory;
import com.akine.knowledge.spi.PasajeRecuperado;
import com.akine.knowledge.spi.ResultadoBusqueda;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Arma la evidencia de las sugerencias: una busqueda en la biblioteca por hallazgo (diseno fase 2, §5.2).
 *
 * <p>Separada del servicio para que el arnes de evaluacion ({@code EvaluacionDeSugerencias}, fuera del CI)
 * recupere exactamente como el servicio, sin copiar la logica.
 */
public final class EvidenciaDeSugerencias {

	public static final int K = 4;

	static final int MAXIMO_CONSULTA = 600;

	private EvidenciaDeSugerencias() {
	}

	/**
	 * Un hallazgo entra solo si la busqueda alcanza, con sus pasajes sobre el corte; un pasaje que aparece
	 * para dos hallazgos viaja una vez, con un solo id. Lista vacia: ninguno alcanzo ({@code SIN_MATERIAL}).
	 */
	public static List<HallazgoConEvidencia> buscar(BibliotecaDirectory biblioteca, long organizationId,
			String diagnostico, List<Hallazgo> elegidos) {
		Map<Hallazgo, List<PasajeRecuperado>> porHallazgo = new LinkedHashMap<>();
		for (Hallazgo hallazgo : elegidos) {
			ResultadoBusqueda resultado = biblioteca.buscar(organizationId, consulta(diagnostico, hallazgo), K);
			if (resultado.alcanza()) {
				List<PasajeRecuperado> sobreElCorte = resultado.pasajes().stream()
						.filter(PasajeRecuperado::superaCorte).toList();
				if (!sobreElCorte.isEmpty()) {
					porHallazgo.put(hallazgo, sobreElCorte);
				}
			}
		}
		Map<ClaveDePasaje, Set<CodigoDeHallazgo>> duenios = new LinkedHashMap<>();
		Map<ClaveDePasaje, PasajeRecuperado> pasajes = new LinkedHashMap<>();
		porHallazgo.forEach((hallazgo, lista) -> lista.forEach(p -> {
			ClaveDePasaje clave = ClaveDePasaje.de(p);
			pasajes.putIfAbsent(clave, p);
			duenios.computeIfAbsent(clave, k -> new LinkedHashSet<>()).add(hallazgo.codigo());
		}));
		Map<ClaveDePasaje, PasajeEnviado> enviados = new LinkedHashMap<>();
		int n = 0;
		for (Map.Entry<ClaveDePasaje, PasajeRecuperado> e : pasajes.entrySet()) {
			enviados.put(e.getKey(), new PasajeEnviado("P" + ++n, e.getValue(), duenios.get(e.getKey())));
		}
		List<HallazgoConEvidencia> evidencia = new ArrayList<>();
		porHallazgo.forEach((hallazgo, lista) -> evidencia.add(new HallazgoConEvidencia(hallazgo,
				lista.stream().map(p -> enviados.get(ClaveDePasaje.de(p))).distinct().toList())));
		return evidencia;
	}

	/** Diagnostico + la frase del codigo, + el nombre de la medicion si la hay; hasta 600 caracteres. */
	public static String consulta(String diagnostico, Hallazgo hallazgo) {
		StringBuilder consulta = new StringBuilder();
		if (diagnostico != null && !diagnostico.isBlank()) {
			consulta.append(diagnostico.strip()).append(". ");
		}
		consulta.append(RecursosDeSugerencias.consultaDe(hallazgo.codigo()));
		String medicion = hallazgo.valores().get("medicion");
		if (medicion != null && !medicion.isBlank()) {
			consulta.append(". ").append(medicion.strip());
		}
		return consulta.length() > MAXIMO_CONSULTA ? consulta.substring(0, MAXIMO_CONSULTA) : consulta.toString();
	}

	/** Un mismo pasaje es el mismo libro, las mismas paginas y el mismo texto. */
	private record ClaveDePasaje(long libroId, int paginaDesde, int paginaHasta, String texto) {

		static ClaveDePasaje de(PasajeRecuperado p) {
			return new ClaveDePasaje(p.libroId(), p.paginaDesde(), p.paginaHasta(), p.texto());
		}
	}
}
