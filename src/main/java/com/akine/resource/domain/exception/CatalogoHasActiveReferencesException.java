package com.akine.resource.domain.exception;

/**
 * La baja se rechaza porque el concepto todavia sostiene otros conceptos vigentes (409).
 *
 * <p>Hoy lo emiten dos casos, los dos internos a este modulo: dar de baja una
 * <b>especialidad</b> que todavia tiene practicas vigentes colgando, o un <b>nomenclador</b>
 * que todavia tiene vigencias abiertas. Dejarlo pasar produciria practicas huerfanas —vigentes,
 * seleccionables, y apuntando a una especialidad que ya no se ofrece— que ningun listado sabria
 * explicar.
 *
 * <p><b>No es lo mismo que "esta usado historicamente".</b> Un concepto referenciado por
 * sesiones o convenios viejos SI se puede dar de baja: RN-M06-001 y RN-M06-002 piden justamente
 * que el historico siga resolviendo despues de la baja. Lo que se bloquea es dejar colgando algo
 * que todavia se ofrece.
 *
 * <p>El conteo viaja en el cuerpo porque sin el el mensaje es inaccionable: el usuario no sabe
 * cuantas practicas tiene que dar de baja primero.
 */
public class CatalogoHasActiveReferencesException extends RuntimeException {

	private final long conceptoId;
	private final String referenceType;
	private final long count;

	public CatalogoHasActiveReferencesException(
			long conceptoId, String referenceType, long count) {

		super("El concepto " + conceptoId + " tiene " + count + " referencias vigentes");
		this.conceptoId = conceptoId;
		this.referenceType = referenceType;
		this.count = count;
	}

	public long getConceptoId() {
		return conceptoId;
	}

	public String getReferenceType() {
		return referenceType;
	}

	public long getCount() {
		return count;
	}
}
