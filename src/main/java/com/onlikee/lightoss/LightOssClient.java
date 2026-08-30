package com.onlikee.lightoss;

import com.onlikee.lightoss.exception.LightOssConfigurationException;
import com.onlikee.lightoss.internal.Checks;
import com.onlikee.lightoss.internal.ClientContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Thread-safe synchronous entry point for the Light OSS HTTP API.
 *
 * <p>The client and all domain clients are immutable and may be shared by virtual or platform
 * threads. Close this client when it created its own JDK {@link HttpClient}; an injected HTTP
 * client remains owned by the caller.</p>
 */
public final class LightOssClient implements AutoCloseable {
    private final ClientContext context;
    private final HealthClient health;
    private final SystemClient system;
    private final BucketClient buckets;
    private final ExplorerClient explorer;
    private final ObjectClient objects;
    private final RecycleBinClient recycleBin;
    private final SiteClient sites;
    private final SigningClient signing;

    private LightOssClient(ClientContext context) {
        this.context = context;
        health = new HealthClient(context);
        system = new SystemClient(context);
        buckets = new BucketClient(context);
        explorer = new ExplorerClient(context);
        objects = new ObjectClient(context);
        recycleBin = new RecycleBinClient(context);
        sites = new SiteClient(context);
        signing = new SigningClient(context);
    }

    /** Creates a client builder for an HTTP(S) origin URI. */
    public static Builder builder(URI baseUri) {
        return new Builder(baseUri);
    }

    /** Returns the health domain client. */
    public HealthClient health() { return health; }

    /** Returns the system domain client. */
    public SystemClient system() { return system; }

    /** Returns the bucket domain client. */
    public BucketClient buckets() { return buckets; }

    /** Returns the explorer domain client. */
    public ExplorerClient explorer() { return explorer; }

    /** Returns the object domain client. */
    public ObjectClient objects() { return objects; }

    /** Returns the recycle-bin domain client. */
    public RecycleBinClient recycleBin() { return recycleBin; }

    /** Returns the site domain client. */
    public SiteClient sites() { return sites; }

    /** Returns the signing domain client. */
    public SigningClient signing() { return signing; }

    /** Closes this SDK instance and its SDK-owned JDK HTTP client. */
    @Override
    public void close() {
        context.close();
    }

    /** Builder for {@link LightOssClient}. */
    public static final class Builder {
        private final URI baseUri;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private boolean connectTimeoutConfigured;
        private Duration requestTimeout;
        private HttpClient httpClient;
        private Supplier<String> tokenProvider;
        private boolean fixedToken;
        private Supplier<String> requestIdProvider = () -> UUID.randomUUID().toString();

        private Builder(URI baseUri) {
            this.baseUri = Checks.baseUri(baseUri);
        }

        /** Uses one fixed bearer token for protected calls. Mutually exclusive with {@link #tokenProvider}. */
        public Builder bearerToken(String bearerToken) {
            if (tokenProvider != null && !fixedToken) {
                throw new LightOssConfigurationException("bearerToken and tokenProvider are mutually exclusive");
            }
            String checked;
            try {
                checked = Checks.headerValue(bearerToken, "bearerToken");
            } catch (RuntimeException exception) {
                throw new LightOssConfigurationException("bearerToken is invalid", null, exception);
            }
            tokenProvider = () -> checked;
            fixedToken = true;
            return this;
        }

        /**
         * Resolves a bearer token for every protected request. The supplier must be thread-safe.
         * This option is mutually exclusive with {@link #bearerToken(String)}.
         */
        public Builder tokenProvider(Supplier<String> tokenProvider) {
            if (fixedToken) {
                throw new LightOssConfigurationException("bearerToken and tokenProvider are mutually exclusive");
            }
            this.tokenProvider = Objects.requireNonNull(tokenProvider, "tokenProvider");
            return this;
        }

        /** Sets the connect timeout for an SDK-created JDK HTTP client. The default is 10 seconds. */
        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = Checks.positive(connectTimeout, "connectTimeout");
            connectTimeoutConfigured = true;
            return this;
        }

        /** Sets an optional total timeout applied to every SDK request. */
        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = Checks.positive(requestTimeout, "requestTimeout");
            return this;
        }

        /**
         * Injects a preconfigured JDK HTTP client owned by the caller. Redirect and connect-timeout
         * behavior is consequently controlled by that client.
         */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
            return this;
        }

        /** Sets the thread-safe request-ID supplier invoked once per call. */
        public Builder requestIdProvider(Supplier<String> requestIdProvider) {
            this.requestIdProvider = Objects.requireNonNull(requestIdProvider, "requestIdProvider");
            return this;
        }

        /** Builds an immutable, thread-safe client. */
        public LightOssClient build() {
            if (httpClient != null && connectTimeoutConfigured) {
                throw new LightOssConfigurationException(
                        "connectTimeout cannot configure an injected HttpClient");
            }
            boolean ownsHttpClient = httpClient == null;
            HttpClient actualClient = httpClient;
            if (actualClient == null) {
                actualClient = HttpClient.newBuilder()
                        .connectTimeout(connectTimeout)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
            }
            return new LightOssClient(new ClientContext(
                    baseUri,
                    actualClient,
                    ownsHttpClient,
                    tokenProvider,
                    requestIdProvider,
                    requestTimeout));
        }
    }
}
