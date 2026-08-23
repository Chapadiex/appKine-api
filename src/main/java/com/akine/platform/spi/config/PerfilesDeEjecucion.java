package com.akine.platform.spi.config;

import java.util.List;
import java.util.Locale;

import org.springframework.core.env.Environment;

/**
 * La unica pregunta que decide si un valor comodo e inseguro es tolerable: <b>¿estamos en un
 * entorno de desarrollo declarado?</b>
 *
 * <h2>Por que la pregunta esta al derecho y no al reves</h2>
 *
 * <p>Antes habia dos listas negras iguales —una en el emisor de tokens y otra en la cadena de
 * seguridad— con tres nombres: {@code prod}, {@code produccion}, {@code production}. Todo lo que
 * no se llamara asi contaba como desarrollo. Eso hace que la seguridad dependa de que alguien
 * haya acertado el nombre del perfil: un despliegue con {@code staging}, {@code docker},
 * {@code prd}, {@code k8s} o <b>sin ningun perfil</b> arrancaba firmando los access token con el
 * secreto de desarrollo, que esta versionado en el repositorio y por lo tanto es publico. Con esa
 * clave se forja a mano un token con {@code scope:"context"} y el {@code accountId} de un
 * profesional real: historia clinica completa sin credenciales. Una lista negra de tres nombres
 * es una lista negra disfrazada de guardarrail, y falla del lado inseguro ante cualquier nombre
 * que nadie previo.
 *
 * <p><b>Ahora el modo seguro es el default.</b> El desarrollo es la excepcion y se declara por
 * nombre positivo. Un perfil nuevo que nadie agrego a esta lista se comporta como produccion:
 * sin secreto explicito la aplicacion no arranca, y la documentacion del contrato no se publica.
 * Equivocarse cuesta un arranque fallido con un mensaje claro, no una fuga silenciosa.
 *
 * <h2>Solo perfiles ACTIVOS, nunca los "por defecto"</h2>
 *
 * <p>Se lee {@link Environment#getActiveProfiles()} y no {@code matchesProfiles(..)}, que
 * considera activos a los perfiles por defecto cuando no hay ninguno explicito. La diferencia es
 * exactamente el agujero que se esta cerrando: mientras {@code application.yml} fijaba
 * {@code spring.profiles.default: local}, arrancar <b>sin</b> {@code SPRING_PROFILES_ACTIVE}
 * equivalia a arrancar en desarrollo. Un despliegue al que se le olvido la variable de entorno no
 * puede ser un despliegue de desarrollo: es el caso que mas hay que cubrir, no el que hay que
 * eximir.
 */
public final class PerfilesDeEjecucion {

	/**
	 * Perfiles bajo los cuales un valor de desarrollo es aceptable.
	 *
	 * <p>Agregar uno es una decision deliberada y visible en el diff, que es justamente lo que no
	 * pasaba cuando la lista era de produccion.
	 */
	private static final List<String> PERFILES_DE_DESARROLLO =
			List.of("local", "dev", "desarrollo", "test");

	private PerfilesDeEjecucion() {
		// Utilidad.
	}

	/**
	 * @return {@code true} solo si hay un perfil de desarrollo EXPLICITAMENTE activo
	 */
	public static boolean esDesarrollo(Environment environment) {
		if (environment == null) {
			return false;
		}
		for (String perfil : environment.getActiveProfiles()) {
			if (perfil != null
					&& PERFILES_DE_DESARROLLO.contains(perfil.toLowerCase(Locale.ROOT))) {
				return true;
			}
		}
		return false;
	}

	/** Los nombres aceptados, para que un mensaje de error pueda decir cuales son. */
	public static List<String> perfilesDeDesarrollo() {
		return PERFILES_DE_DESARROLLO;
	}
}
