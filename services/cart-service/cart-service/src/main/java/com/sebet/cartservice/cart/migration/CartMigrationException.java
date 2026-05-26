package com.sebet.cartservice.cart.migration;

/**
 * Thrown when the migration chain cannot be completed — typically because no
 * {@link CartMigrationStep} is registered for a particular source version.
 *
 * <p>Callers should treat this as a signal to fall back to discarding the
 * unreadable cart rather than propagating the exception to the user.
 */
public class CartMigrationException extends RuntimeException {

    public CartMigrationException(String message) {
        super(message);
    }

    public CartMigrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
