package com.akine.identity.infrastructure;

import com.akine.identity.domain.AceptacionTerminos;
import com.akine.identity.domain.port.AceptacionTerminosRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AceptacionTerminosRepository
		extends JpaRepository<AceptacionTerminos, Long>, AceptacionTerminosRepositoryPort {
}
