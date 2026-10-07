package com.akine.identity.infrastructure;

import com.akine.identity.application.PlatformAdminBootstrapService;
import com.akine.identity.application.PlatformAdminBootstrapService.ResultadoBootstrap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Dispara el bootstrap del administrador de plataforma al arrancar (DP-14, AKINE-A-4).
 *
 * <p>Lee {@code akine.bootstrap.admin-email}, que {@code application.yml} mapea a
 * {@code AKINE_BOOTSTRAP_ADMIN_EMAIL}. Sin la variable no toca la base: es el caso normal de
 * todo arranque.
 *
 * <h2>Nunca tumba el arranque</h2>
 *
 * <p>Mismo criterio que {@code PlatformAdminBootstrapCheck}: avisar, no bloquear. Si el bootstrap
 * falla —otra instancia gano la carrera, alguien registro ese email entre la lectura y el flush—
 * la transaccion se revierte entera y aca solo queda el WARN. Hacer caer la aplicacion por eso
 * convertiria un problema de una sola vez en un despliegue que no levanta.
 *
 * <p>El email se loguea enmascarado: es la casilla de quien administra la plataforma.
 */
@Component
public class PlatformAdminBootstrapRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(PlatformAdminBootstrapRunner.class);

	private final PlatformAdminBootstrapService bootstrapService;
	private final String emailConfigurado;

	public PlatformAdminBootstrapRunner(
			PlatformAdminBootstrapService bootstrapService,
			@Value("${akine.bootstrap.admin-email:}") String emailConfigurado) {
		this.bootstrapService = bootstrapService;
		this.emailConfigurado = emailConfigurado;
	}

	@Override
	public void run(ApplicationArguments args) {
		ResultadoBootstrap resultado;
		try {
			resultado = bootstrapService.ejecutar(emailConfigurado);
		} catch (RuntimeException e) {
			log.warn("El bootstrap del administrador de plataforma fallo y no dejo cambios: {}. "
					+ "La aplicacion arranca igual; revisar y reiniciar si hace falta.",
					e.getClass().getSimpleName());
			return;
		}
		registrar(resultado);
	}

	private void registrar(ResultadoBootstrap resultado) {
		String destino = enmascarar(emailConfigurado);
		switch (resultado) {
			case SIN_VARIABLE -> log.debug("AKINE_BOOTSTRAP_ADMIN_EMAIL no definida: sin bootstrap");
			case ENLACE_EMITIDO -> log.info("Bootstrap de plataforma: la cuenta sembrada se re-apunto "
					+ "a {} y se encolo su enlace de activacion", destino);
			case ENLACE_VIGENTE -> log.info("Bootstrap de plataforma: {} ya tiene un enlace de "
					+ "activacion vigente; no se reenvia", destino);
			case YA_HAY_ADMIN_CON_CREDENCIAL -> log.info("Bootstrap de plataforma: ya hay un "
					+ "administrador con credencial; AKINE_BOOTSTRAP_ADMIN_EMAIL se ignora y puede "
					+ "quitarse del entorno");
			case EMAIL_INVALIDO -> log.warn("Bootstrap de plataforma: AKINE_BOOTSTRAP_ADMIN_EMAIL no "
					+ "tiene forma de email; no se hizo nada");
			case EMAIL_DE_OTRA_CUENTA -> log.warn("Bootstrap de plataforma: {} ya pertenece a otra "
					+ "cuenta. No se modifico nada ni se otorgo el rol; usar otra casilla", destino);
			case SIN_CUENTA_SEMBRADA -> log.warn("Bootstrap de plataforma: no hay ninguna cuenta con "
					+ "rol de plataforma sin credencial que pueda recibirlo (revisar V15 y "
					+ "platform_role); no se hizo nada");
			case CANDIDATAS_AMBIGUAS -> log.warn("Bootstrap de plataforma: hay mas de una cuenta con "
					+ "rol de plataforma y sin credencial; no se elige una al azar y no se hizo nada");
		}
	}

	/** {@code juana@akine.app} -> {@code j***@akine.app}. */
	static String enmascarar(String email) {
		if (email == null || email.isBlank()) {
			return "(sin email)";
		}
		String limpio = email.strip();
		int arroba = limpio.indexOf('@');
		if (arroba <= 0) {
			return "***";
		}
		return limpio.charAt(0) + "***" + limpio.substring(arroba);
	}
}
