package com.zentrox.ledger.entity;

/**
 * Application roles used for Spring Security RBAC.
 * Spring Security authorities are exposed as "ROLE_ADMIN", "ROLE_ANALYST", "ROLE_VIEWER".
 */
public enum Role {
    ADMIN,
    ANALYST,
    VIEWER
}
