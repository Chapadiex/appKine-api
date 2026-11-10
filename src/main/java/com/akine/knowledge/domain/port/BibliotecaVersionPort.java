package com.akine.knowledge.domain.port;

public interface BibliotecaVersionPort {

	/** Suma uno a la version de la organizacion (la crea en 1 si no existe). */
	void avanzar(long orgId);

	/** Crea la fila de la organizacion (version 0) si no existe. Va en su propia transaccion. */
	void asegurarFila(long orgId);

	/** Toma la fila con FOR UPDATE: serializa las escrituras que dependen del hash. */
	void bloquear(long orgId);

	/** Version vigente; 0 si nunca avanzo. */
	long actual(long orgId);
}
