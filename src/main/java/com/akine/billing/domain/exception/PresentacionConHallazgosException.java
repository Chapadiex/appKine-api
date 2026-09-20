package com.akine.billing.domain.exception;

import java.util.List;

/**
 * El lote tiene prestaciones que no se pueden reclamar. <b>409</b>. RF-M21-003.
 *
 * <p>Lleva la lista entera y no el primer hallazgo: el administrativo tiene que poder arreglar todo
 * de una vez. Devolver el primero lo obligaria a reintentar tantas veces como items rotos haya, que
 * es la forma mas rapida de que deje de mirar el mensaje.
 */
public class PresentacionConHallazgosException extends RuntimeException {

	private final long presentacionId;
	private final transient List<String> hallazgos;

	public PresentacionConHallazgosException(long presentacionId, List<String> hallazgos) {
		super("La presentacion " + presentacionId + " tiene " + hallazgos.size()
				+ " prestaciones que no se pueden reclamar");
		this.presentacionId = presentacionId;
		this.hallazgos = List.copyOf(hallazgos);
	}

	public long getPresentacionId() {
		return presentacionId;
	}

	public List<String> getHallazgos() {
		return hallazgos;
	}
}
