package com.aurepay;

/** Erro tipado da API AurePay. */
public final class AurePayException extends RuntimeException {
    private final String code;
    private final Object details;
    private final int statusCode;

    public AurePayException(String message, String code, Object details, int statusCode) {
        super(message);
        this.code = code;
        this.details = details;
        this.statusCode = statusCode;
    }

    public String getCode() {
        return code;
    }

    public Object getDetails() {
        return details;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
