package com.akine.platform.infrastructure.modelo;

import com.akine.platform.spi.modelo.ModeloNoDisponibleException;
import com.akine.platform.spi.modelo.EstadisticasDeGeneracion;
import com.akine.platform.spi.modelo.MensajeChat;
import com.akine.platform.spi.modelo.ModeloDeLenguaje;
import com.akine.platform.spi.modelo.OpcionesGeneracion;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Modelo determinista para tests y desarrollo (tipo {@code prueba} de cada consumidor).
 * {@code completar} devuelve el texto del ultimo mensaje de usuario; {@code generar} emite tres
 * fragmentos: "Respuesta de prueba: ", el primer renglon de lo recibido y ".". Cuenta las llamadas
 * y puede forzar el fallo de cada una. Con {@link #responderJson(String)}, las llamadas a
 * {@code completar} en modo JSON y a {@code completarJson} devuelven ese texto.
 *
 * <p>{@code completarJson} contesta por defecto el JSON que se le dio al crearlo (el asistente pide
 * {@code FUERA_DE_ALCANCE}), o el ultimo mensaje de usuario si es nulo; un test puede cambiarlo con
 * {@link #responderCon}. Ninguna de sus llamadas cuenta en {@link #llamadas()}.
 */
public class ModeloDeLenguajeDePrueba implements ModeloDeLenguaje {

	private final AtomicInteger llamadas = new AtomicInteger();
	private final AtomicInteger generaciones = new AtomicInteger();
	private final Function<List<MensajeChat>, String> respuestaJsonPorDefecto;
	private volatile boolean fallarEnGenerar;
	private volatile boolean fallarEnCompletar;
	private volatile boolean sinFin;
	private volatile String respuestaJson;
	private volatile Function<List<MensajeChat>, String> respuestaDeCompletarJson;
	private volatile Duration demoraAntesDelPrimero = Duration.ZERO;

	static final long PAUSA_SIN_FIN_MS = 50;

	public ModeloDeLenguajeDePrueba() {
		this(null);
	}

	/** {@code jsonPorDefecto}: lo que contesta {@code completarJson}; nulo, el ultimo mensaje de usuario. */
	public ModeloDeLenguajeDePrueba(String jsonPorDefecto) {
		this.respuestaJsonPorDefecto = jsonPorDefecto == null
				? ModeloDeLenguajeDePrueba::ultimoDeUsuario
				: mensajes -> jsonPorDefecto;
		this.respuestaDeCompletarJson = respuestaJsonPorDefecto;
	}

	@Override
	public String completarJson(List<MensajeChat> mensajes, String esquemaJson, Duration plazo) {
		return respuestaDeCompletarJson.apply(List.copyOf(mensajes));
	}

	/** Las proximas llamadas a {@code completarJson} devuelven lo que arme esta funcion. */
	public void responderCon(Function<List<MensajeChat>, String> respuesta) {
		this.respuestaDeCompletarJson = Objects.requireNonNull(respuesta);
	}

	@Override
	public String completar(List<MensajeChat> mensajes, OpcionesGeneracion opciones, Duration plazo) {
		llamadas.incrementAndGet();
		if (fallarEnCompletar) {
			throw new ModeloNoDisponibleException();
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
			throw new ModeloNoDisponibleException();
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
				throw new ModeloNoDisponibleException();
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
		this.respuestaDeCompletarJson = respuestaJsonPorDefecto;
		this.sinFin = false;
		this.demoraAntesDelPrimero = Duration.ZERO;
	}

	private static void dormir(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ModeloNoDisponibleException(e);
		}
	}

	public void fallarEnGenerar(boolean fallar) {
		this.fallarEnGenerar = fallar;
	}

	/**
	 * Texto que devuelven {@code completar} cuando se pide JSON y {@code completarJson}; {@code null}
	 * vuelve a la respuesta de fabrica: el eco del ultimo mensaje de usuario (que no es JSON) o el
	 * JSON por defecto de {@code completarJson}.
	 */
	public void responderJson(String json) {
		this.respuestaJson = json;
		this.respuestaDeCompletarJson = json == null ? respuestaJsonPorDefecto : mensajes -> json;
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
