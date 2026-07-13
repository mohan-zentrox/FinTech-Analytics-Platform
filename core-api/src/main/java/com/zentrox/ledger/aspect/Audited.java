package com.zentrox.ledger.aspect;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a service method as a mutating operation that must be recorded in
 * the append-only audit log. Applied to service-layer methods (not
 * controllers) so the aspect fires only after the operation has actually
 * completed successfully.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

    /** e.g. CREATE, UPDATE, DELETE, IMPORT, RECONCILE */
    String action();

    /** Entity type affected, e.g. "Transaction" */
    String entity();
}
