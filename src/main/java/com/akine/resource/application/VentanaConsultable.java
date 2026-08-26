package com.akine.resource.application;

import com.akine.resource.domain.exception.VentanaDemasiadoAmpliaException;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * La ventana de fechas locales {@code [desde, hasta)} que admiten las lecturas de M05, y su tope.
 *
 * <h2>Por que el tope existe, escrito una sola vez</h2>
 *
 * <p>Sin tope, un {@code desde=1970} sobre un tenant grande es un scan completo y un problema de
 * disponibilidad del servicio, no una consulta: la disponibilidad efectiva no esta materializada
 * (diseno §2.5) y se resuelve un dia por iteracion. Es el mismo razonamiento que la matriz de
 * permisos aplica a los listados con rango.
 *
 * <p>Vive aparte de {@code DisponibilidadEfectivaService} —donde el brief lo declaraba— porque
 * las TRES lecturas con rango de esta etapa lo necesitan igual: la disponibilidad efectiva, el
 * listado de excepciones y el calendario de feriados de la sede. Con la constante duplicada en
 * tres servicios, subirla en uno y olvidarla en los otros dos no rompe ningun test y deja dos
 * endpoints con el tope viejo.
 *
 * <h2>Las dos formas de ventana invalida, y por que no son la misma excepcion</h2>
 *
 * <p>Una ventana INVERTIDA o vacia ({@code hasta <= desde}) es un pedido incoherente: sale por
 * {@link IllegalArgumentException}, que el advice ya traduce a 400. Una ventana DEMASIADO AMPLIA
 * es un pedido coherente que el servicio no se compromete a responder: sale por
 * {@link VentanaDemasiadoAmpliaException}, tambien 400, pero con el tope adentro para que la
 * pantalla pueda recortar sola en vez de mostrarle el error al usuario. Los dos son 400 y la
 * diferencia no es cosmetica: es lo que el cliente puede hacer al respecto.
 */
final class VentanaConsultable {

	/**
	 * Tope de la ventana consultable, en dias.
	 *
	 * <p>366 y no 365: un ano completo tiene que entrar aunque sea bisiesto. Que sea exactamente
	 * un ano no sale de ningun RF —es el horizonte con el que un centro planifica— y subirlo no
	 * toca el contrato, solo el costo de la consulta mas cara.
	 */
	static final int VENTANA_MAXIMA_DIAS = 366;

	private VentanaConsultable() {
		// Utilidad de validacion.
	}

	/**
	 * Exige que {@code [desde, hasta)} sea una ventana coherente y dentro del tope.
	 *
	 * @throws IllegalArgumentException si falta un extremo o si {@code hasta <= desde} (400)
	 * @throws VentanaDemasiadoAmpliaException si supera {@link #VENTANA_MAXIMA_DIAS} (400)
	 */
	static void exigirValida(LocalDate desde, LocalDate hasta) {
		if (desde == null || hasta == null) {
			throw new IllegalArgumentException(
					"La ventana consultada exige un inicio y un fin: " + desde + " -> " + hasta);
		}
		if (!hasta.isAfter(desde)) {
			// El extremo superior es EXCLUSIVO en toda esta etapa, asi que hasta == desde no es
			// "un dia": es cero dias. Aceptarlo devolveria una respuesta vacia que el cliente
			// leeria como "no atiende", que es una respuesta distinta y equivocada.
			throw new IllegalArgumentException(
					"El fin de la ventana debe ser posterior a su inicio, y es EXCLUSIVO: "
							+ desde + " -> " + hasta);
		}
		long dias = ChronoUnit.DAYS.between(desde, hasta);
		if (dias > VENTANA_MAXIMA_DIAS) {
			throw new VentanaDemasiadoAmpliaException(desde, hasta, VENTANA_MAXIMA_DIAS);
		}
	}
}
