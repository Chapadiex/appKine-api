package com.akine.knowledge.infrastructure;

import com.akine.knowledge.domain.exception.AsistenteNoDisponibleException;
import com.akine.knowledge.domain.port.EstadisticasDeGeneracion;
import com.akine.knowledge.domain.port.MensajeChat;
import com.akine.knowledge.domain.port.ModeloDeLenguaje;
import com.akine.knowledge.domain.port.OpcionesGeneracion;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Modelo determinista para tests y desarrollo ({@code akine.biblioteca.consulta.tipo=prueba}).
 * {@code completar} devuelve el texto del ultimo mensaje de usuario; {@code generar} emite tres
 * fragmentos: "Respuesta de prueba: ", el primer renglon de lo recibido y ".". Cuenta las llamadas
 * y puede forzar el fallo de cada una. Con {@link #responderJson(String)}, las llamadas a
 * {@code completar} en modo JSON devuelven ese texto.
 */
public class ModeloDeLenguajeDePrueba implements ModeloDeLenguaje {

	private final AtomicInteger llamadas = new AtomicInteger();
	private final AtomicInteger generaciones = new AtomicInteger();
	private volatile boolean fallarEnGenerar;
	private volatile boolean fallarEnCompletar;
	private volatile boolean sinFin;
	private volatile String respuestaJson;
	private volatile Duration demoraAntesDelPrimero = Duration.ZERO;

	static final long PAUSA_SIN_FIN_MS = 50;

	@Override
	public String completar(List<MensajeChat> mensajes, OpcionesGeneracion opciones, Duration plazo) {
		llamadas.incrementAndGet();
		if (fallarEnCompletar) {
			throw new AsistenteNoDisponibleException();
		}
		String json = respuestaJson;
		if (opciones.json() && json != null) {
			return json;
		}
		return ultimoDeUsuario(mensajes);
	}

	@Override
	public EstadisticasDeGeneracion generar(List<MensajeChat> mensajes, OpcionesGeneracion opciones, Duration plazo,
			Consumer<String> alFragmento, BooleanSupplier cancelado) {
		llamadas.incrementAndGet();
		generaciones.incrementAndGet();
		if (fallarEnGenerar) {
			throw new AsistenteNoDisponibleException();
		}
		// Como Ollama evaluando el prompt: nada se emite hasta que pase la demora o se cancele.
		long finDemora = System.nanoTime() + demoraAntesDelPrimero.toNanos();
		while (System.nanoTime() - finDemora < 0) {
			if (cancelado.getAsBoolean()) {
				return EstadisticasDeGeneracion.NINGUNA;
			}
			dormir(PAUSA_SIN_FIN_MS);
		}
		String primerRenglon = ultimoDeUsuario(mensajes).lines().findFirst().orElse("");
		for (String fragmento : List.of("Respuesta de prueba: ", primerRenglon, ".")) {
			if (cancelado.getAsBoolean()) {
				return EstadisticasDeGeneracion.NINGUNA;
			}
			alFragmento.accept(fragmento);
		}
		long vence = System.nanoTime() + plazo.toNanos();
		while (sinFin) {
			if (cancelado.getAsBoolean()) {
				return EstadisticasDeGeneracion.NINGUNA;
			}
			if (System.nanoTime() - vence >= 0) {
				throw new AsistenteNoDisponibleException();
			}
			dormir(PAUSA_SIN_FIN_MS);
			alFragmento.accept(".");
		}
		return new EstadisticasDeGeneracion(10, 1_000_000, 3, 1_000_000);
	}

	/**
	 * Con {@code true}, despues de los tres fragmentos sigue emitiendo "." cada 50 ms hasta que se
	 * cancele o venza el plazo (como un modelo lento): para probar la desconexion del cliente.
	 */
	public void sinFin(boolean sinFin) {
		this.sinFin = sinFin;
	}

	/**
	 * Espera antes del primer fragmento, como Ollama evaluando el prompt; corta antes si se cancela.
	 * No cuenta contra el plazo.
	 */
	public void demorarAntesDelPrimero(Duration demora) {
		this.demoraAntesDelPrimero = demora;
	}

	/** Vuelve al comportamiento de fabrica; el contador de llamadas sigue. */
	public void reiniciar() {
		this.fallarEnGenerar = false;
		this.fallarEnCompletar = false;
		this.respuestaJson = null;
		this.sinFin = false;
		this.demoraAntesDelPrimero = Duration.ZERO;
	}

	private static void dormir(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AsistenteNoDisponibleException(e);
		}
	}

	public void fallarEnGenerar(boolean fallar) {
		this.fallarEnGenerar = fallar;
	}

	/**
	 * Texto que devuelve {@code completar} cuando se pide JSON; {@code null} vuelve al eco del
	 * ultimo mensaje de usuario (que no es JSON).
	 */
	public void responderJson(String json) {
		this.respuestaJson = json;
	}

	public void fallarEnCompletar(boolean fallar) {
		this.fallarEnCompletar = fallar;
	}

	/** Solo las llamadas a {@code generar} (redactar), sin las de interpretacion. */
	public int generaciones() {
		return generaciones.get();
	}

	public int llamadas() {
		return llamadas.get();
	}

	private static String ultimoDeUsuario(List<MensajeChat> mensajes) {
		for (int i = mensajes.size() - 1; i >= 0; i--) {
			if (mensajes.get(i).rol() == MensajeChat.Rol.USUARIO) {
				return mensajes.get(i).texto();
			}
		}
		return "";
	}
}
