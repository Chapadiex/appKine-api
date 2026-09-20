package com.akine.billing.infrastructure;

import com.akine.billing.domain.Egreso;
import com.akine.billing.domain.port.EgresoRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Egresos. Las dos escrituras del saldo son <b>UPDATE condicionales nativos</b>.
 *
 * <p>Nativos y no JPQL a proposito: la condicion del {@code WHERE} es lo que decide la correctitud
 * y tiene que estar a la vista de quien lee. Hacerlas por JPA exigiria ademas cargar la entidad
 * antes, que es exactamente la lectura previa que se quiere evitar — ahi es donde se cuela la
 * ventana entre dos administrativos concurrentes.
 *
 * <p>Llevan {@code clearAutomatically} y {@code flushAutomatically} por la misma razon que
 * {@link JornadaCajaRepository}: <b>un UPDATE nativo no actualiza la copia que la sesion de JPA
 * tiene en memoria</b>, y releer el egreso despues de escribirlo devolveria el saldo viejo —
 * justamente la relectura que decide si el rechazo fue "no lo admite el estado" o "no alcanza el
 * saldo".
 *
 * <p><b>No hay delete</b>: RN-M22-002, anular no significa borrar.
 */
public interface EgresoRepository extends JpaRepository<Egreso, Long>, EgresoRepositoryPort {

	@Override
	@Query("""
			SELECT e FROM Egreso e
			 WHERE e.organizationId = :organizationId
			   AND e.consultorioId = :consultorioId
			   AND e.id = :egresoId
			""")
	Optional<Egreso> findByIdInScope(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("egresoId") long egresoId);

	@Override
	@Query("""
			SELECT e FROM Egreso e
			 WHERE e.organizationId = :organizationId
			   AND e.idempotencyKey = :idempotencyKey
			""")
	Optional<Egreso> findByIdempotencyKey(
			@Param("organizationId") long organizationId,
			@Param("idempotencyKey") String idempotencyKey);

	/**
	 * El caso borde "factura externa duplicada", con 409 legible.
	 *
	 * <p>Excluye los anulados: sin eso, un egreso cargado por error dejaria ese numero de factura
	 * quemado para siempre. Es lo mismo que hace {@code anulado_key} en el unique.
	 */
	@Override
	@Query("""
			SELECT e FROM Egreso e
			 WHERE e.organizationId = :organizationId
			   AND e.beneficiarioClave = :beneficiarioClave
			   AND e.comprobanteTipo = :comprobanteTipo
			   AND e.comprobanteNumero = :comprobanteNumero
			   AND e.estado <> com.akine.billing.domain.EstadoEgreso.ANULADO
			""")
	Optional<Egreso> findVigentePorComprobante(
			@Param("organizationId") long organizationId,
			@Param("beneficiarioClave") String beneficiarioClave,
			@Param("comprobanteTipo") String comprobanteTipo,
			@Param("comprobanteNumero") String comprobanteNumero);

	/** RF-M22-004. Nativo por los filtros opcionales y el {@code LIMIT/OFFSET}. */
	@Override
	@Query(value = """
			SELECT * FROM egreso
			 WHERE organization_id = :organizationId
			   AND consultorio_id = :consultorioId
			   AND (:estado IS NULL OR estado = :estado)
			   AND (:categoria IS NULL OR categoria = :categoria)
			   AND (:beneficiarioClave IS NULL OR beneficiario_clave = :beneficiarioClave)
			   AND (:desde IS NULL OR DATE(registrado_en) >= :desde)
			   AND (:hasta IS NULL OR DATE(registrado_en) <= :hasta)
			 ORDER BY registrado_en DESC, id DESC
			 LIMIT :limite OFFSET :desplazamiento
			""", nativeQuery = true)
	@SuppressWarnings("checkstyle:ParameterNumber")
	List<Egreso> buscar(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("estado") String estado,
			@Param("categoria") String categoria,
			@Param("beneficiarioClave") String beneficiarioClave,
			@Param("desde") LocalDate desde,
			@Param("hasta") LocalDate hasta,
			@Param("limite") int limite,
			@Param("desplazamiento") int desplazamiento);

	/**
	 * El descuento atomico. La condicion {@code saldo_pendiente >= :importe} es todo el diseno.
	 *
	 * <p>Filtra tambien por estado: un borrador no se paga y un anulado tampoco. El predicado
	 * explicito impide que un cambio futuro en como se anula deje pasar un pago contra un
	 * compromiso que ya no existe — mismo criterio que {@code CobroRepository.descontarSaldo}.
	 *
	 * @return cero filas = el estado no lo admite, o no alcanza el saldo. El servicio lo desambigua
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			UPDATE egreso
			   SET saldo_pendiente = saldo_pendiente - :importe
			 WHERE id = :egresoId
			   AND organization_id = :organizationId
			   AND estado = 'CONFIRMADO'
			   AND saldo_pendiente >= :importe
			""", nativeQuery = true)
	@Override
	int descontarSaldo(
			@Param("organizationId") long organizationId,
			@Param("egresoId") long egresoId,
			@Param("importe") BigDecimal importe);

	/**
	 * La vuelta: un pago anulado devuelve lo suyo al saldo.
	 *
	 * <p>Sin condicion de importe —devolver no puede pasarse de cero— pero si de estado: un egreso
	 * anulado no puede recibir saldo de vuelta, porque se anula solo cuando no tiene pagos.
	 *
	 * @return cero filas = el estado ya no lo admite
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			UPDATE egreso
			   SET saldo_pendiente = saldo_pendiente + :importe
			 WHERE id = :egresoId
			   AND organization_id = :organizationId
			   AND estado IN ('CONFIRMADO', 'PAGADO')
			   AND saldo_pendiente + :importe <= importe_total
			""", nativeQuery = true)
	@Override
	int devolverSaldo(
			@Param("organizationId") long organizationId,
			@Param("egresoId") long egresoId,
			@Param("importe") BigDecimal importe);

	/**
	 * Deriva el estado del saldo, en una sentencia.
	 *
	 * <p>Con SQL y no leyendo la entidad para cambiarle el estado en Java: leerla despues del
	 * UPDATE de arriba traeria la version que la sesion de JPA tiene cacheada, que ya no refleja el
	 * saldo real. Es una trampa que este repositorio ya pago antes.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			UPDATE egreso
			   SET estado = CASE WHEN saldo_pendiente = 0 THEN 'PAGADO' ELSE 'CONFIRMADO' END
			 WHERE id = :egresoId
			   AND organization_id = :organizationId
			   AND estado IN ('CONFIRMADO', 'PAGADO')
			""", nativeQuery = true)
	@Override
	void actualizarEstadoPorSaldo(
			@Param("organizationId") long organizationId,
			@Param("egresoId") long egresoId);
}
