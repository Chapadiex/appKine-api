package com.akine.knowledge.domain.port;

public interface BibliotecaVersionPort {

	/** Suma uno a la version de la organizacion (la crea en 1 si no existe). */
	void avanzar(long orgId);

	/** Version vigente; 0 si nunca avanzo. */
	long actual(long orgId);
}
