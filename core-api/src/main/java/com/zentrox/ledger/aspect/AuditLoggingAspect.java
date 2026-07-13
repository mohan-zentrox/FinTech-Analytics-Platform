package com.zentrox.ledger.aspect;

import com.zentrox.ledger.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * Records an AuditLog row after every successful invocation of a
 * @Audited service method (create/update/delete/import/reconcile).
 * Runs as a separate REQUIRES_NEW transaction (see AuditLogService) so the
 * audit trail is append-only and independent of the business transaction's
 * outcome once the method has actually returned.
 *
 * This fulfils the "audit every mutating request" requirement via a
 * cross-cutting aspect rather than duplicating logging calls in every
 * controller/service method.
 */
@Aspect
@Component
@Order(10)
@RequiredArgsConstructor
@Slf4j
public class AuditLoggingAspect {

    private final AuditLogService auditLogService;

    @Around("@annotation(audited)")
    public Object logAudit(ProceedingJoinPoint joinPoint, Audited audited) throws Throwable {
        Object result = joinPoint.proceed();

        try {
            String actor = currentActor();
            String entityId = resolveEntityId(result, joinPoint.getArgs());
            String details = summarize(result);
            auditLogService.record(actor, audited.action(), audited.entity(), entityId, details);
        } catch (Exception e) {
            // Audit logging must never break the primary business operation.
            log.warn("Failed to write audit log for {} {}: {}", audited.action(), audited.entity(), e.getMessage());
        }

        return result;
    }

    private String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return "system";
        }
        return auth.getName();
    }

    private String resolveEntityId(Object result, Object[] args) {
        // Try both JavaBean-style getters (entities) and Java record accessors (DTOs like ReconciliationResult).
        for (String candidate : new String[]{"getId", "id", "getRunId", "runId"}) {
            String value = tryInvokeIdGetter(result, candidate);
            if (value != null) {
                return value;
            }
        }
        // Fall back to the first UUID/String-looking argument (typical for update(id, ...) / delete(id)).
        if (args != null) {
            for (Object arg : args) {
                if (arg != null && (arg instanceof java.util.UUID || arg instanceof String)) {
                    return arg.toString();
                }
            }
        }
        return null;
    }

    private String tryInvokeIdGetter(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method m = target.getClass().getMethod(methodName);
            Object value = m.invoke(target);
            return value == null ? null : value.toString();
        } catch (NoSuchMethodException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String summarize(Object result) {
        if (result == null) {
            return null;
        }
        String s = result.toString();
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
