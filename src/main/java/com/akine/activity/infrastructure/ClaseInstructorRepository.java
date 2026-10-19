package com.akine.activity.infrastructure;

import com.akine.activity.domain.ClaseInstructor;
import com.akine.activity.domain.port.HonorariosRepositoryPorts.ClaseInstructorRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/** Repositorio de {@code clase_instructor} (AKINE-08.09b). */
public interface ClaseInstructorRepository
		extends JpaRepository<ClaseInstructor, Long>, ClaseInstructorRepositoryPort {

	@Override
	@Query("""
			SELECT i FROM ClaseInstructor i
			 WHERE i.organizationId = :organizationId
			   AND i.claseId = :claseId
			   AND i.removidoEn IS NULL
			 ORDER BY i.membershipId
			""")
	List<ClaseInstructor> vigentesDe(
			@Param("organizationId") long organizationId, @Param("claseId") long claseId);

	@Override
	@Query("""
			SELECT i FROM ClaseInstructor i
			 WHERE i.organizationId = :organizationId
			   AND i.claseId IN :claseIds
			   AND i.removidoEn IS NULL
			 ORDER BY i.claseId, i.membershipId
			""")
	List<ClaseInstructor> vigentesDeLasClases(
			@Param("organizationId") long organizationId, @Param("claseIds") Collection<Long> claseIds);
}
