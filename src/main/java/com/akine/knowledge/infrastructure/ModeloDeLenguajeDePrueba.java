package com.akine.knowledge.infrastructure;

import com.akine.knowledge.domain.exception.AsistenteNoDisponibleException;
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
 * y puede forzar el fallo de cada una.
 */
public class ModeloDeLenguajeDePrueba implements ModeloDeLenguaje {

	private final AtomicInteger llamadas = new AtomicInteger();
	private volatile boolean fallarEnGenerar;
	private volatile boolean fallarEnCompletar;

	@Override
	public String completar(List<MensajeChat> mensajes, OpcionesGeneracion opciones, Duration plazo) {
		llamadas.incrementAndGet();
		if (fallarEnCompletar) {
			throw new AsistenteNoDisponibleException();
		}
		return ultimoDeUsuario(mensajes);
	}

	@Override
	public void generar(List<MensajeChat> mensajes, OpcionesGeneracion opciones, Duration plazo,
			Consumer<String> alFragmento, BooleanSupplier cancelado) {
		llamadas.incrementAndGet();
		if (fallarEnGenerar) {
			throw new AsistenteNoDisponibleException();
		}
		String primerRenglon = ultimoDeUsuario(mensajes).lines().findFirst().orElse("");
		for (String fragmento : List.of("Respuesta de prueba: ", primerRenglon, ".")) {
			if (cancelado.getAsBoolean()) {
				return;
			}
			alFragmento.accept(fragmento);
		}
	}

	public void fallarEnGenerar(boolean fallar) {
		this.fallarEnGenerar = fallar;
	}

	public void fallarEnCompletar(boolean fallar) {
		this.fallarEnCompletar = fallar;
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
