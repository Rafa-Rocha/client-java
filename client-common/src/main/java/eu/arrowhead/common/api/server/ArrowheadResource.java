package eu.arrowhead.common.api.server;

import eu.arrowhead.common.exception.ArrowheadException;
import eu.arrowhead.common.misc.SecurityVerifier;
import org.apache.log4j.Logger;

/**
 * Helper class for creating resources for {@link ArrowheadHttpServer}. The verifier object should be used to verify
 * each request before any action is taken, see {@link SecurityVerifier}, and the server object can be used to consume
 * services as part of responding to a request.
 *
 * See {@link eu.arrowhead.common.api.server.ArrowheadGrizzlyHttpServer#addResources}() on how to add resources to the
 * Grizzly implementation of {@link ArrowheadHttpServer}.
 */
public abstract class ArrowheadResource {
    protected final Logger log = Logger.getLogger(getClass());
    private SecurityVerifier verifier;
    protected final ArrowheadHttpServer server;

    /**
     * Constructor.
     * @param server the {@link ArrowheadHttpServer} that this resource belongs to.
     */
    public ArrowheadResource(ArrowheadHttpServer server) throws ArrowheadException {
        this.server = server;
    }

    public SecurityVerifier getVerifier() {
        if (verifier == null) verifier = SecurityVerifier.createFromProperties();
        return verifier;
    }
}
