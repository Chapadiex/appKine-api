package com.akine.activity.application;

/**
 * Lo que hizo una pasada de un job de F9 sobre su lote de candidatos (AKINE-09.03): el resumen que
 * el worker publica como metricas y como una linea de log estructurado.
 *
 * <p>Las tres cuentas suman los candidatos: cada uno termina <b>procesado</b> (se vencio, se renovo
 * o se encolo su aviso), <b>omitido</b> (la pasada lo miro y no tenia nada que hacer: todavia no le
 * tocaba en la zona de su sede, otra instancia lo resolvio, la renovacion no era posible y se apago
 * con su motivo, o la persona no tiene correo) o <b>fallido</b> (excepcion: queda para el tick
 * siguiente). Solo cuentas: ni ids ni datos de nadie, para que el resumen pueda ir a cualquier lado.
 */
public record PasadaDeJob(int procesados, int omitidos, int fallidos) {

	public static final PasadaDeJob VACIA = new PasadaDeJob(0, 0, 0);

	public int candidatos() {
		return procesados + omitidos + fallidos;
	}

	/** Las dos pasadas juntas, como si fueran una. */
	public PasadaDeJob mas(PasadaDeJob otra) {
		return new PasadaDeJob(
				procesados + otra.procesados, omitidos + otra.omitidos, fallidos + otra.fallidos);
	}

	/** Acumulador de una pasada en curso. No es seguro entre hilos: cada pasada tiene el suyo. */
	static final class Cuenta {

		private int procesados;
		private int omitidos;
		private int fallidos;

		void contar(boolean procesado) {
			if (procesado) {
				procesados++;
			} else {
				omitidos++;
			}
		}

		void fallo() {
			fallidos++;
		}

		PasadaDeJob resumen() {
			return new PasadaDeJob(procesados, omitidos, fallidos);
		}
	}
}
