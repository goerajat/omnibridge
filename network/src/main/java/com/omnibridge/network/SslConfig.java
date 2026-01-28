package com.omnibridge.network;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;

/**
 * Configuration for SSL/TLS connections.
 *
 * <p>This class holds the SSL configuration parameters and can create an
 * {@link SSLContext} for use with SSL-enabled connections.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * SslConfig config = SslConfig.builder()
 *     .keyStorePath("/path/to/keystore.jks")
 *     .keyStorePassword("password")
 *     .trustStorePath("/path/to/truststore.jks")
 *     .trustStorePassword("password")
 *     .protocol("TLSv1.3")
 *     .build();
 * </pre>
 */
public class SslConfig {

    private final boolean enabled;
    private final String protocol;
    private final String keyStorePath;
    private final String keyStorePassword;
    private final String keyStoreType;
    private final String keyPassword;
    private final String trustStorePath;
    private final String trustStorePassword;
    private final String trustStoreType;
    private final boolean clientAuth;
    private final boolean hostnameVerification;

    private volatile SSLContext sslContext;

    private SslConfig(Builder builder) {
        this.enabled = builder.enabled;
        this.protocol = builder.protocol;
        this.keyStorePath = builder.keyStorePath;
        this.keyStorePassword = builder.keyStorePassword;
        this.keyStoreType = builder.keyStoreType;
        this.keyPassword = builder.keyPassword;
        this.trustStorePath = builder.trustStorePath;
        this.trustStorePassword = builder.trustStorePassword;
        this.trustStoreType = builder.trustStoreType;
        this.clientAuth = builder.clientAuth;
        this.hostnameVerification = builder.hostnameVerification;
    }

    /**
     * Check if SSL is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Get the SSL/TLS protocol (e.g., "TLSv1.2", "TLSv1.3").
     */
    public String getProtocol() {
        return protocol;
    }

    /**
     * Get the keystore path.
     */
    public String getKeyStorePath() {
        return keyStorePath;
    }

    /**
     * Get the keystore password.
     */
    public String getKeyStorePassword() {
        return keyStorePassword;
    }

    /**
     * Get the keystore type (e.g., "JKS", "PKCS12").
     */
    public String getKeyStoreType() {
        return keyStoreType;
    }

    /**
     * Get the key password (defaults to keystore password if not set).
     */
    public String getKeyPassword() {
        return keyPassword != null ? keyPassword : keyStorePassword;
    }

    /**
     * Get the truststore path.
     */
    public String getTrustStorePath() {
        return trustStorePath;
    }

    /**
     * Get the truststore password.
     */
    public String getTrustStorePassword() {
        return trustStorePassword;
    }

    /**
     * Get the truststore type (e.g., "JKS", "PKCS12").
     */
    public String getTrustStoreType() {
        return trustStoreType;
    }

    /**
     * Check if client authentication is required.
     */
    public boolean isClientAuth() {
        return clientAuth;
    }

    /**
     * Check if hostname verification is enabled.
     */
    public boolean isHostnameVerification() {
        return hostnameVerification;
    }

    /**
     * Get or create the SSLContext for this configuration.
     *
     * <p>The SSLContext is created lazily and cached for reuse.</p>
     *
     * @return the configured SSLContext
     * @throws SslConfigException if unable to create the SSLContext
     */
    public SSLContext getSSLContext() throws SslConfigException {
        if (sslContext == null) {
            synchronized (this) {
                if (sslContext == null) {
                    sslContext = createSSLContext();
                }
            }
        }
        return sslContext;
    }

    private SSLContext createSSLContext() throws SslConfigException {
        try {
            KeyManagerFactory kmf = null;
            TrustManagerFactory tmf = null;

            // Load keystore if configured
            if (keyStorePath != null && !keyStorePath.isEmpty()) {
                KeyStore keyStore = loadKeyStore(keyStorePath, keyStorePassword, keyStoreType);
                kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(keyStore, getKeyPassword().toCharArray());
            }

            // Load truststore if configured
            if (trustStorePath != null && !trustStorePath.isEmpty()) {
                KeyStore trustStore = loadKeyStore(trustStorePath, trustStorePassword, trustStoreType);
                tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(trustStore);
            }

            SSLContext ctx = SSLContext.getInstance(protocol);
            ctx.init(
                    kmf != null ? kmf.getKeyManagers() : null,
                    tmf != null ? tmf.getTrustManagers() : null,
                    null
            );

            return ctx;

        } catch (NoSuchAlgorithmException | KeyStoreException | UnrecoverableKeyException |
                 java.security.KeyManagementException e) {
            throw new SslConfigException("Failed to create SSLContext", e);
        }
    }

    private KeyStore loadKeyStore(String path, String password, String type) throws SslConfigException {
        try (FileInputStream fis = new FileInputStream(path)) {
            KeyStore keyStore = KeyStore.getInstance(type);
            keyStore.load(fis, password != null ? password.toCharArray() : null);
            return keyStore;
        } catch (IOException | KeyStoreException | NoSuchAlgorithmException | CertificateException e) {
            throw new SslConfigException("Failed to load keystore: " + path, e);
        }
    }

    /**
     * Create a disabled SSL configuration.
     */
    public static SslConfig disabled() {
        return builder().enabled(false).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private boolean enabled = false;
        private String protocol = "TLSv1.3";
        private String keyStorePath;
        private String keyStorePassword;
        private String keyStoreType = "JKS";
        private String keyPassword;
        private String trustStorePath;
        private String trustStorePassword;
        private String trustStoreType = "JKS";
        private boolean clientAuth = false;
        private boolean hostnameVerification = true;

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder protocol(String protocol) {
            this.protocol = protocol;
            return this;
        }

        public Builder keyStorePath(String keyStorePath) {
            this.keyStorePath = keyStorePath;
            if (keyStorePath != null && !keyStorePath.isEmpty()) {
                this.enabled = true;
            }
            return this;
        }

        public Builder keyStorePassword(String keyStorePassword) {
            this.keyStorePassword = keyStorePassword;
            return this;
        }

        public Builder keyStoreType(String keyStoreType) {
            this.keyStoreType = keyStoreType;
            return this;
        }

        public Builder keyPassword(String keyPassword) {
            this.keyPassword = keyPassword;
            return this;
        }

        public Builder trustStorePath(String trustStorePath) {
            this.trustStorePath = trustStorePath;
            return this;
        }

        public Builder trustStorePassword(String trustStorePassword) {
            this.trustStorePassword = trustStorePassword;
            return this;
        }

        public Builder trustStoreType(String trustStoreType) {
            this.trustStoreType = trustStoreType;
            return this;
        }

        public Builder clientAuth(boolean clientAuth) {
            this.clientAuth = clientAuth;
            return this;
        }

        public Builder hostnameVerification(boolean hostnameVerification) {
            this.hostnameVerification = hostnameVerification;
            return this;
        }

        public SslConfig build() {
            return new SslConfig(this);
        }
    }

    /**
     * Exception thrown when SSL configuration fails.
     */
    public static class SslConfigException extends Exception {
        public SslConfigException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @Override
    public String toString() {
        return "SslConfig{" +
                "enabled=" + enabled +
                ", protocol='" + protocol + '\'' +
                ", keyStorePath='" + keyStorePath + '\'' +
                ", trustStorePath='" + trustStorePath + '\'' +
                ", clientAuth=" + clientAuth +
                ", hostnameVerification=" + hostnameVerification +
                '}';
    }
}
