package com.akine.testsupport;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Arma una instancia COMPLETA de un record de vista para los slices de controller: cada componente
 * con un valor no nulo, cada lista con un elemento, cada record anidado armado de la misma forma.
 *
 * <p>Existe para que un slice ejercite el mapeo vista → DTO de respuesta campo por campo sin
 * escribir a mano constructores de quince argumentos que se rompen con cada campo nuevo. Lo que un
 * test quiere afirmar lo fija despues con {@code with}-style: el fixture da la forma, el test el
 * valor que le importa.
 *
 * <p>Los valores son deterministas: numeros en 1, textos con el nombre del componente, fechas fijas,
 * y la primera constante de cada enum. Un record cuyo constructor compacto rechace esos valores no
 * se puede armar asi y el test lo dice con la causa.
 */
public final class Fixture {

	public static final Instant INSTANTE = Instant.parse("2026-10-01T12:00:00Z");
	public static final LocalDate FECHA = LocalDate.of(2026, 10, 1);

	private Fixture() {
	}

	public static <T> T de(Class<T> tipo) {
		return tipo.cast(valor(tipo, null, "valor", 0));
	}

	/**
	 * Un mock cuyos métodos devuelven, sin stubbing, una vista completa armada por {@link #de}: el
	 * slice ejercita el mapeo a DTO de cada endpoint y el test sólo stubbea lo que quiere afirmar.
	 */
	public static <T> T mockQueResponde(Class<T> tipo) {
		return org.mockito.Mockito.mock(tipo, invocacion -> {
			java.lang.reflect.Method m = invocacion.getMethod();
			if (m.getReturnType() == void.class) {
				return null;
			}
			Object v = valor(m.getReturnType(), m.getGenericReturnType(), m.getName(), 0);
			return v != null ? v : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocacion);
		});
	}

	/**
	 * Instancia una clase cualquiera —típicamente una excepción de dominio— por su constructor con
	 * más parámetros, llenándolos con los mismos valores que {@link #de}.
	 */
	public static <T> T instanciar(Class<T> tipo) {
		Constructor<?> elegido = null;
		for (Constructor<?> c : tipo.getDeclaredConstructors()) {
			if (elegido == null || c.getParameterCount() > elegido.getParameterCount()) {
				elegido = c;
			}
		}
		if (elegido == null) {
			throw new IllegalStateException(tipo.getName() + " no tiene constructores");
		}
		Object[] valores = new Object[elegido.getParameterCount()];
		for (int i = 0; i < valores.length; i++) {
			valores[i] = valor(elegido.getParameterTypes()[i], elegido.getGenericParameterTypes()[i],
					elegido.getParameters()[i].getName(), 1);
		}
		try {
			elegido.setAccessible(true);
			return tipo.cast(elegido.newInstance(valores));
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("No se pudo instanciar " + tipo.getName() + ": "
					+ (e.getCause() == null ? e : e.getCause()), e);
		}
	}

	private static Object valor(Class<?> tipo, Type generico, String nombre, int profundidad) {
		if (profundidad > 6) {
			return null;
		}
		if (tipo == long.class || tipo == Long.class) {
			return 1L;
		}
		if (tipo == int.class || tipo == Integer.class) {
			return 1;
		}
		if (tipo == short.class || tipo == Short.class) {
			return (short) 1;
		}
		if (tipo == boolean.class || tipo == Boolean.class) {
			return Boolean.TRUE;
		}
		if (tipo == double.class || tipo == Double.class) {
			return 1.0d;
		}
		if (tipo == String.class) {
			String n = nombre.toLowerCase();
			if (n.contains("moneda")) {
				return "ARS";
			}
			if (n.contains("email") || n.contains("correo")) {
				return "persona@ejemplo.test";
			}
			return nombre;
		}
		if (tipo == BigDecimal.class) {
			return new BigDecimal("100.00");
		}
		if (tipo == Instant.class) {
			return INSTANTE;
		}
		if (tipo == LocalDate.class) {
			return FECHA;
		}
		if (tipo == LocalDateTime.class) {
			return FECHA.atTime(10, 0);
		}
		if (tipo == LocalTime.class) {
			return LocalTime.of(10, 0);
		}
		if (tipo == OffsetDateTime.class) {
			return OffsetDateTime.parse("2026-10-01T12:00:00Z");
		}
		if (tipo == UUID.class) {
			return UUID.fromString("00000000-0000-0000-0000-000000000001");
		}
		if (Throwable.class.isAssignableFrom(tipo) && tipo.isAssignableFrom(RuntimeException.class)) {
			return new RuntimeException(nombre);
		}
		if (tipo.isEnum()) {
			return tipo.getEnumConstants()[0];
		}
		if (tipo == Optional.class) {
			return Optional.ofNullable(valor(argumento(generico, 0), argumentoGenerico(generico, 0),
					nombre, profundidad + 1));
		}
		if (List.class.isAssignableFrom(tipo) || tipo == Collection.class) {
			Object elemento = valor(argumento(generico, 0), argumentoGenerico(generico, 0),
					nombre, profundidad + 1);
			List<Object> lista = new ArrayList<>();
			if (elemento != null) {
				lista.add(elemento);
			}
			return List.copyOf(lista);
		}
		if (Set.class.isAssignableFrom(tipo)) {
			Object elemento = valor(argumento(generico, 0), argumentoGenerico(generico, 0),
					nombre, profundidad + 1);
			return elemento == null ? Set.of() : Set.of(elemento);
		}
		if (Map.class.isAssignableFrom(tipo)) {
			return Map.of();
		}
		if (tipo.isArray()) {
			return Array.newInstance(tipo.getComponentType(), 0);
		}
		if (tipo.isRecord()) {
			return record(tipo, profundidad);
		}
		return null;
	}

	private static Object record(Class<?> tipo, int profundidad) {
		RecordComponent[] componentes = tipo.getRecordComponents();
		Class<?>[] tipos = new Class<?>[componentes.length];
		Object[] valores = new Object[componentes.length];
		for (int i = 0; i < componentes.length; i++) {
			tipos[i] = componentes[i].getType();
			valores[i] = valor(componentes[i].getType(), componentes[i].getGenericType(),
					componentes[i].getName(), profundidad + 1);
		}
		try {
			Constructor<?> canonico = tipo.getDeclaredConstructor(tipos);
			canonico.setAccessible(true);
			return canonico.newInstance(valores);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("No se pudo armar " + tipo.getName() + ": "
					+ (e.getCause() == null ? e : e.getCause()), e);
		}
	}

	private static Class<?> argumento(Type generico, int indice) {
		Type t = argumentoGenerico(generico, indice);
		if (t instanceof Class<?> c) {
			return c;
		}
		if (t instanceof ParameterizedType p && p.getRawType() instanceof Class<?> c) {
			return c;
		}
		return Object.class;
	}

	private static Type argumentoGenerico(Type generico, int indice) {
		if (generico instanceof ParameterizedType p && p.getActualTypeArguments().length > indice) {
			return p.getActualTypeArguments()[indice];
		}
		return Object.class;
	}
}
