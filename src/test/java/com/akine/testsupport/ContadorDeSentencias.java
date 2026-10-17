package com.akine.testsupport;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Cuenta las sentencias JDBC que prepara <b>el hilo que lo activo</b>, y ninguna otra
 * (docs/carga.md §6.4 y §11). Nacio en {@code AgendaDelDiaSentenciasIT}; 09.03 lo saco aca para
 * medir los listados de F9 con la misma vara.
 *
 * <p>Envuelve el {@code DataSource} y cuenta cada {@code prepareStatement}/{@code prepareCall}/
 * {@code createStatement}. Solo en el hilo del test: los jobs de fondo (outbox, vencimientos) usan la
 * misma base y no deben ensuciar el numero. Se usa importando {@link Config} en el IT.
 */
public final class ContadorDeSentencias extends DelegatingDataSource {

	private static final ThreadLocal<int[]> EN_ESTE_HILO = new ThreadLocal<>();

	private static final Set<String> SENTENCIAS = Set.of("prepareStatement", "prepareCall", "createStatement");

	ContadorDeSentencias(DataSource destino) {
		super(destino);
	}

	/** Corre la lectura y devuelve cuantas sentencias preparo. */
	public static int contar(Runnable lectura) {
		int[] cuenta = new int[1];
		contar(cuenta, () -> {
			lectura.run();
			return null;
		});
		return cuenta[0];
	}

	/** Corre la lectura, deja en {@code cuenta[0]} las sentencias y devuelve lo que la lectura devolvio. */
	public static <T> T contar(int[] cuenta, Supplier<T> lectura) {
		EN_ESTE_HILO.set(new int[1]);
		try {
			T resultado = lectura.get();
			cuenta[0] = EN_ESTE_HILO.get()[0];
			return resultado;
		} finally {
			EN_ESTE_HILO.remove();
		}
	}

	@Override
	public Connection getConnection() throws SQLException {
		return contando(super.getConnection());
	}

	@Override
	public Connection getConnection(String username, String password) throws SQLException {
		return contando(super.getConnection(username, password));
	}

	private static Connection contando(Connection real) {
		return (Connection) Proxy.newProxyInstance(
				ContadorDeSentencias.class.getClassLoader(),
				new Class<?>[] {Connection.class},
				(proxy, metodo, args) -> {
					int[] cuenta = EN_ESTE_HILO.get();
					if (cuenta != null && SENTENCIAS.contains(metodo.getName())) {
						cuenta[0]++;
					}
					try {
						return metodo.invoke(real, args);
					} catch (InvocationTargetException e) {
						throw e.getCause();
					}
				});
	}

	/** Envuelve todo {@code DataSource} del contexto. */
	@TestConfiguration(proxyBeanMethods = false)
	public static class Config {

		@Bean
		static BeanPostProcessor contadorDeSentencias() {
			return new BeanPostProcessor() {
				@Override
				public Object postProcessAfterInitialization(Object bean, String beanName) {
					return bean instanceof DataSource ds && !(bean instanceof ContadorDeSentencias)
							? new ContadorDeSentencias(ds)
							: bean;
				}
			};
		}
	}
}
