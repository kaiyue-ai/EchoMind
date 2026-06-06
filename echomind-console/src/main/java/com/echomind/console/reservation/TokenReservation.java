package com.echomind.console.reservation;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.UUID;

public record TokenReservation(
    String id,
    OwnerType ownerType,
    String ownerId,
    String scope,
    LocalDate bucketStart,
    long reservedTokens
) {

    private static final String VERSION = "v1";

    public static TokenReservation create(OwnerType ownerType, String ownerId, String scope,
                                          LocalDate bucketStart, long reservedTokens) {
        return new TokenReservation(
            String.join("|",
                VERSION,
                ownerType.name(),
                encode(ownerId),
                scope,
                bucketStart.toString(),
                String.valueOf(reservedTokens),
                UUID.randomUUID().toString()
            ),
            ownerType,
            ownerId,
            scope,
            bucketStart,
            reservedTokens
        );
    }

    public static TokenReservation parse(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("reservation id is blank");
        }
        String[] parts = id.split("\\|", -1);
        if (parts.length != 7 || !VERSION.equals(parts[0])) {
            throw new IllegalArgumentException("invalid reservation id");
        }
        return new TokenReservation(
            id,
            OwnerType.valueOf(parts[1]),
            decode(parts[2]),
            parts[3],
            LocalDate.parse(parts[4]),
            Long.parseLong(parts[5])
        );
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    public enum OwnerType {
        USER,
        PROVIDER
    }
}
