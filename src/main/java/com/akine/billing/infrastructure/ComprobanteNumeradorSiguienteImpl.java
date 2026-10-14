package com.akine.billing.infrastructure;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * El incremento del comprobante en una sentencia: {@code SET ultimo_numero =
 * LAST_INSERT_ID(ultimo_numero + 1)}.
 *
 * <p>Con un argumento, {@code LAST_INSERT_ID(expr)} devuelve {@code expr} y lo deja como el
 * {@code last_insert_id} de la conexion, que MySQL manda en la misma respuesta del {@code UPDATE}
 * y Connector/J expone por {@code getGeneratedKeys()}. Asi el numero llega sin el {@code SELECT}
 * que antes lo leia, y ese {@code SELECT} corria con el lock del numerador tomado: era una ida y
 * vuelta mas dentro de la seccion que serializa todos los cobros de la sede (docs/carga.md §10).
 *
 * <p>Corre sobre la conexion de la transaccion en curso, por la sesion de Hibernate: el lock es el
 * del cobro que la llama. No toca la entidad {@code ComprobanteNumerador}, asi que no hay copia en
 * memoria que quede vieja. El {@code INSERT} del cobro que viene despues pisa el
 * {@code last_insert_id} con su propio id, que es lo que Hibernate lee: no se cruzan.
 */
class ComprobanteNumeradorSiguienteImpl implements ComprobanteNumeradorSiguiente {

	private static final String INCREMENTO = """
			UPDATE comprobante_numerador
			   SET ultimo_numero = LAST_INSERT_ID(ultimo_numero + 1)
			 WHERE organization_id = ?
			   AND consultorio_id = ?
			""";

	@PersistenceContext
	private EntityManager entityManager;

	@Override
	public int siguiente(long organizationId, long consultorioId) {
		return entityManager.unwrap(Session.class).doReturningWork(conexion -> {
			try (PreparedStatement sentencia =
					conexion.prepareStatement(INCREMENTO, Statement.RETURN_GENERATED_KEYS)) {
				sentencia.setLong(1, organizationId);
				sentencia.setLong(2, consultorioId);
				if (sentencia.executeUpdate() != 1) {
					throw new IllegalStateException("No existe el numerador de comprobantes de la sede "
							+ consultorioId + ": ComprobanteIniciador#asegurar tiene que correr antes");
				}
				try (ResultSet numero = sentencia.getGeneratedKeys()) {
					if (numero.next()) {
						return Math.toIntExact(numero.getLong(1));
					}
				}
			}
			throw new IllegalStateException(
					"El UPDATE del numerador no devolvio el numero (last_insert_id)");
		});
	}
}
