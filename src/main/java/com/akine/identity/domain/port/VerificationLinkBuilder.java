package com.akine.identity.domain.port;

import com.akine.identity.domain.TipoTokenVerificacion;

/**
 * Arma el enlace que recibe la persona por correo.
 *
 * <p>Es un puerto y no una constante porque la base de la URL es del entorno —local, staging,
 * produccion— y {@code application} no puede leer configuracion sin arrastrar
 * {@code infrastructure} hacia adentro.
 *
 * <p>El enlace se construye <b>en identity y en el momento de encolar</b> (T-11): asi el
 * token en claro nunca entra al payload consultable del outbox, sino a un campo que el worker
 * solo transporta. La contrapartida es que la ventana en la que el enlace existe en reposo
 * dura lo que tarda el envio, y no la vida entera del token.
 */
public interface VerificationLinkBuilder {

	/**
	 * Devuelve la URL que consume el token.
	 *
	 * @param tipo       determina a que pantalla del frontend apunta
	 * @param tokenPlano valor en claro. Es la unica vez que sale de memoria, y sale hacia el
	 *                   correo, no hacia un log ni hacia la base
	 */
	String enlaceDe(TipoTokenVerificacion tipo, String tokenPlano);
}
