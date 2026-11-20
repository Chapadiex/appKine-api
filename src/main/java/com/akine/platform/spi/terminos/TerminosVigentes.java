package com.akine.platform.spi.terminos;

import java.util.Optional;

/**
 * Version vigente de los terminos y condiciones (DP-45).
 *
 * <p>La configura {@code identity}, duenia de las aceptaciones de cada cuenta, y la consulta
 * tambien {@code organization} para el pedido de prueba de la landing. Vive en {@code platform}
 * porque {@code organization} no puede importar {@code identity}.
 */
public interface TerminosVigentes {

	/** La version que hay que aceptar, o vacio si el despliegue no exige terminos (solo tests). */
	Optional<String> version();
}
