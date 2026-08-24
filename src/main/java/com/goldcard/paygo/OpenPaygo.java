package com.goldcard.paygo;

import com.goldcard.paygo.internal.TokenAlgorithm;
import com.goldcard.paygo.token.TokenDecodeRequest;
import com.goldcard.paygo.token.TokenDecodeResult;
import com.goldcard.paygo.token.TokenGenerationRequest;
import com.goldcard.paygo.token.TokenGenerationResult;

/**
 * Public facade for the OpenPAYGO token protocol.
 *
 * <p>The facade is stateless and thread-safe. Callers own persistence of the token count and the
 * returned {@link com.goldcard.paygo.token.TokenState}; this library never updates device state or
 * performs network I/O on their behalf.</p>
 *
 * @author dyzmj
 */
public final class OpenPaygo {
    private OpenPaygo() {}

    /**
     * Generates a token from an immutable request.
     *
     * <p>The algorithm selects the next count with the parity required by the requested command,
     * encodes the command value into the token base, advances the SipHash token chain, and formats
     * the result with its protocol-defined fixed width. Leading zeroes are therefore significant.</p>
     *
     * @param request secret key, current count, command, value, and formatting options
     * @return the generated token together with the count that must be persisted by the caller
     * @throws IllegalArgumentException if the request, key, value, or count is invalid
     */
    public static TokenGenerationResult generateToken(TokenGenerationRequest request) {
        return TokenAlgorithm.generate(request);
    }

    /**
     * Validates and decodes a token against the supplied device state and validation policy.
     *
     * <p>Cryptographically valid tokens return {@code VALID} only when their count is permitted by
     * the active replay window. A recognized but previously consumed token returns
     * {@code ALREADY_USED}; an unmatched token returns {@code INVALID}. Those two outcomes are
     * normal protocol results rather than exceptions.</p>
     *
     * @param request token text, key, current device state, and validation options
     * @return the decode status and, for a valid token, its command, value, and updated state
     * @throws IllegalArgumentException if the token format or request configuration is invalid
     */
    public static TokenDecodeResult decodeToken(TokenDecodeRequest request) {
        return TokenAlgorithm.decode(request);
    }
}
