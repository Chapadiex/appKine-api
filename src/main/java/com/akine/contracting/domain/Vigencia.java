package com.akine.contracting.domain;

import java.time.LocalDate;

/**
 * Un periodo de vigencia con fin ABIERTO opcional, y la unica definicion de "se solapan" del
 * modulo.
 *
 * <h2>Por que existe una clase para esto</h2>
 *
 * <p>Porque el solapamiento es la regla que define AKINE-03.05 (RN-M16-002) y se evalua en dos
 * lugares distintos —convenios entre si, y aranceles de la misma practica entre si—. Escribirla
 * dos veces garantiza que un dia las dos copias digan cosas distintas, y la que se olvide de
 * tratar el {@code null} como infinito va a dejar pasar exactamente el caso que mas duele: dos
 * acuerdos abiertos superpuestos para siempre.
 *
 * <h2>{@code hasta} es INCLUSIVA, y el {@code null} es infinito</h2>
 *
 * <p>Lo fijo V41 para {@code plan_cobertura} y esta etapa no lo contradice: {@code hasta} es el
 * ultimo dia en que el periodo se aplica, {@code hasta == desde} es un periodo de un solo dia
 * —un estado real— y {@code hasta == null} significa "sin fin previsto", que a los efectos de
 * la comparacion es {@code +infinito} y NO "no se sabe".
 *
 * <p>Ese matiz es el que hace que dos periodos abiertos SIEMPRE se solapen, aunque empiecen con
 * diez años de diferencia. Es correcto: los dos siguen aplicando hoy.
 *
 * <h2>Lo que esta clase NO hace</h2>
 *
 * <p>No serializa nada. Dos transacciones concurrentes pueden preguntarle a esta clase por el
 * mismo conjunto y las dos recibir "no se solapa", porque cada una lee un conjunto en el que la
 * otra fila todavia no esta. La serializacion la da el lock de {@code convenio_lock}, tomado
 * antes de leer; esta clase solo sabe comparar dos intervalos.
 */
public record Vigencia(LocalDate desde, LocalDate hasta) {

	public Vigencia {
		if (desde == null) {
			throw new IllegalArgumentException("La vigencia necesita una fecha de inicio");
		}
		if (hasta != null && hasta.isBefore(desde)) {
			throw new IllegalArgumentException(
					"La vigencia no puede terminar antes de empezar: " + desde + " a " + hasta);
		}
	}

	/**
	 * {@code true} si los dos periodos comparten al menos un dia.
	 *
	 * <p>La formula es la clasica de interseccion de intervalos cerrados, con los dos finales
	 * abiertos tratados como infinito:
	 *
	 * <pre>
	 *   se solapan  &lt;=&gt;  a.desde &lt;= b.hasta   Y   b.desde &lt;= a.hasta
	 * </pre>
	 *
	 * <p>Un {@code hasta} nulo hace verdadera su mitad de la conjuncion, porque nada es posterior
	 * a infinito. <b>Es la unica forma correcta de tratarlo</b>: escribir la comparacion sin ese
	 * caso —comparando contra el {@code hasta} nulo como si fuera una fecha— dejaria pasar dos
	 * convenios abiertos superpuestos, que es el escenario que la regla existe para impedir.
	 */
	public boolean seSolapaCon(Vigencia otra) {
		return noTerminaAntesDe(this, otra.desde()) && noTerminaAntesDe(otra, this.desde());
	}

	/** {@code true} si {@code fecha} cae dentro del periodo. {@code hasta} INCLUSIVE. */
	public boolean cubre(LocalDate fecha) {
		if (fecha == null || fecha.isBefore(desde)) {
			return false;
		}
		return hasta == null || !fecha.isAfter(hasta);
	}

	/** {@code true} si este periodo esta enteramente contenido en {@code contenedor}. */
	public boolean estaContenidaEn(Vigencia contenedor) {
		if (desde.isBefore(contenedor.desde())) {
			return false;
		}
		if (contenedor.hasta() == null) {
			return true;
		}
		return hasta != null && !hasta.isAfter(contenedor.hasta());
	}

	/** {@code true} si el periodo todavia no habia terminado en {@code fecha}. */
	private static boolean noTerminaAntesDe(Vigencia periodo, LocalDate fecha) {
		return periodo.hasta() == null || !periodo.hasta().isBefore(fecha);
	}

	@Override
	public String toString() {
		return desde + " a " + (hasta == null ? "sin fin previsto" : hasta.toString());
	}
}
