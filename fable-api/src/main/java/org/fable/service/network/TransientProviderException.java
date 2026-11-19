package org.fable.service.network;

import lombok.Getter;
import org.fable.model.enums.MetadataProvider;

/**
 * Thrown by metadata providers when a lookup fails due to a transient transport/network outage
 * or transient upstream status failure (distinct from "0 results found" or anti-scraping blocks).
 */
@Getter
public class TransientProviderException extends RuntimeException {

    private final MetadataProvider provider;
    private final boolean connectivityError;
    private final Integer httpStatus;

    private TransientProviderException(
            MetadataProvider provider,
            boolean connectivityError,
            Integer httpStatus,
            String message,
            Throwable cause) {
        super(message, cause);
        this.provider = provider;
        this.connectivityError = connectivityError;
        this.httpStatus = httpStatus;
    }

    public static TransientProviderException connectivity(MetadataProvider provider, String message, Throwable cause) {
        return new TransientProviderException(provider, true, null, message, cause);
    }

    public static TransientProviderException upstreamStatus(MetadataProvider provider, int statusCode, String message, Throwable cause) {
        return new TransientProviderException(provider, false, statusCode, message, cause);
    }
}

