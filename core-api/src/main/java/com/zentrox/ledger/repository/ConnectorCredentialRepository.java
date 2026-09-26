package com.zentrox.ledger.repository;

import com.zentrox.ledger.entity.ConnectorCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConnectorCredentialRepository extends JpaRepository<ConnectorCredential, UUID> {

    Optional<ConnectorCredential> findByProviderAndAccount(String provider, String account);

    List<ConnectorCredential> findByProvider(String provider);

    boolean existsByProviderAndAccount(String provider, String account);
}
