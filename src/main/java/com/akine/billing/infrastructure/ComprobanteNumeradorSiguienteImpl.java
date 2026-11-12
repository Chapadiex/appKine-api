package com.akine.billing.infrastructure;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;

import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * El incremento del comprobante en una sentencia: {@code UPDATE ... RETURNING ultimo_numero}.
 *
 * <p>El numero vuelve en la misma respuesta del {@code UPDATE}, sin el {@code SELECT} que antes lo
 * leia, y ese {@code SELECT} corria con el lock del numerador tomado: era una ida y vuelta mas
 * dentro de la seccion que serializa todos los cobros de la sede (docs/carga.md §10). En MySQL lo
 * mismo se lograba con {@code LAST_INSERT_ID(expr)}; {@code RETURNING} lo dice sin trucos.
 *
 * <p>Corre sobre la conexion de la transaccion en curso, por la sesion de Hibernate: el lock es el
 * del cobro que la llama. No toca la entidad {@code ComprobanteNumerador}, asi que no hay copia en
 * memoria que quede vieja.
 */
class ComprobanteNumeradorSiguienteImpl implements ComprobanteNumeradorSiguiente {

	private static final String INCREMENTO = """
			UPDATE comprobante_numerador
			   SET ultimo_numero = ultimo_numero + 1
			 WHERE organization_id = ?
			   AND consultorio_id = ?
			RETURNING ultimo_numero
			""";

	@PersistenceContext
	private EntityManager entityManager;

	@Override
	public int siguiente(long organizationId, long consultorioId) {
		return entityManager.unwrap(Session.class).doReturningWork(conexion -> {
			try (PreparedStatement sentencia = conexion.prepareStatement(INCREMENTO)) {
				sentencia.setLong(1, organizationId);
				sentencia.setLong(2, consultorioId);
				try (ResultSet numero = sentencia.executeQuery()) {
					if (numero.next()) {
						return Math.toIntExact(numero.getLong(1));
					}
				}
			}
			throw new IllegalStateException("No existe el numerador de comprobantes de la sede "
					+ consultorioId + ": ComprobanteIniciador#asegurar tiene que correr antes");
		});
	}
}
