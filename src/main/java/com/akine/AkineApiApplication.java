package com.akine;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AkineApiApplication {

	public static void main(String[] args) {
		usarUtcComoZonaDeLaJvm();
		SpringApplication.run(AkineApiApplication.class, args);
	}

	/**
	 * La JVM corre en UTC en cualquier maquina, como en la imagen Docker.
	 *
	 * <p>El driver de PostgreSQL manda la zona de la JVM en el arranque de cada conexion. En Windows
	 * la JVM elige el alias legado {@code America/Buenos_Aires}, que el tzdata de Debian trixie ya no
	 * trae (vive en {@code tzdata-legacy}), y el servidor corta la conexion con
	 * <i>invalid value for parameter "TimeZone"</i>. Ese es el error de zona horaria que antes se
	 * esquivaba con H2: la causa no era PostgreSQL sino la zona que le llegaba.
	 *
	 * <p>Ningun codigo de dominio usa la zona de la JVM: "hoy" y los horarios se resuelven con la zona
	 * de la sede o de la organizacion ({@code ZonaDeSede}, {@code ZonaDeOrganizacion}), y los
	 * instantes se guardan en UTC. Fijarla solo hace que una maquina de desarrollo se comporte igual
	 * que el despliegue.
	 */
	static void usarUtcComoZonaDeLaJvm() {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		System.setProperty("user.timezone", "UTC");
	}

}
